package com.sixthsense.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.sixthsense.BuildConfig
import com.sixthsense.core.BeltMapper
import com.sixthsense.core.DepthZones
import com.sixthsense.core.DetectedObj
import com.sixthsense.core.SceneBus
import com.sixthsense.core.SceneState
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Operator-facing status of the live on-device pipeline. */
data class VisionStatus(
    val running: Boolean = false,
    val depthLoaded: Boolean = false,
    val yoloLoaded: Boolean = false,
    val backend: String = BuildConfig.EXECUTORCH_BACKEND,
    val depthMs: Double = 0.0,
    val yoloMs: Double = 0.0,
    val fps: Double = 0.0,
    val detections: Int = 0,
    val note: String = "idle",
)

/**
 * The live, fully ON-DEVICE perception pipeline. Two rates, kept separate on purpose:
 *
 *  1. FRAME STREAM (smooth, ~camera rate): the camera-analyzer thread only builds the
 *     upright frame and streams it to the dashboard — it NEVER blocks on a model, so the
 *     dashboard camera stays smooth (~11-12 fps).
 *  2. SCENE / HAPTICS (fresh + CONSISTENT): a separate inference cycle runs depth AND
 *     YOLO in parallel on the SAME latest frame, waits for BOTH, and emits ONE scene from
 *     that frame. So the belt/haptics are always built from one real, current frame — never
 *     a mix of stale results — which keeps the direction correct during movement.
 *
 * Uses the **default back camera** (normal field of view) so left/right map to the user's
 * left/right (correct haptic direction) and objects are seen at YOLO's trained scale.
 *
 * Each ExecuTorch Module lives on exactly one thread (depth=depthExecutor, yolo=yoloExecutor);
 * the inference orchestrator only submits + joins, so a Module is never touched concurrently.
 */
class VisionPipeline(
    private val context: Context,
    private val bus: SceneBus,
) {
    // Camera analyzer ONLY (fast: upright + stream + kick inference). Never runs a model.
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // Inference orchestrator: submits depth+yolo and joins them (blocks here, not on camera).
    private val inferenceExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // Each model on its own thread, in parallel.
    private val depthExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val yoloExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val depthConv = FrameToTensor(DEPTH_SIZE, Norm.IMAGENET)
    private val yoloConv = FrameToTensor(YOLO_SIZE, Norm.SCALE_0_1)

    // INVARIANT: depthModule only loaded/forwarded/closed on depthExecutor, yoloModule only
    // on yoloExecutor — a single Module is never touched from two threads at once.
    @Volatile private var depthModule: EtModule? = null
    @Volatile private var yoloModule: EtModule? = null
    @Volatile private var running = false
    @Volatile private var modelsRequested = false
    @Volatile private var loggedYolo = false

    private var cameraProvider: ProcessCameraProvider? = null
    // Scene-rate fps EMA, written only on inferenceExecutor.
    private var lastSceneNs = 0L
    private var emaFps = 0.0
    // Frame-stream throttle, written only on analysisExecutor.
    private var lastFrameEmitMs = 0L
    // Depth cadence state — inferenceExecutor-only (single thread, no volatile needed). YOLO
    // runs EVERY cycle so a detected object's zone (its direction) is always fresh; depth runs
    // 1/DEPTH_EVERY_N and is cached in between (it only sets nearness, not which side).
    private var cycle = 0L
    private var cZones = DepthZones(0f, 0f, 0f)
    private var cDepthData: FloatArray? = null
    private var cDw = 0
    private var cDh = 0
    private var cDepthMs = 0.0

    // Latest upright frame: streamed, fed to inference, and used for on-demand OCR.
    @Volatile private var lastUpright: Bitmap? = null
    private val inferenceBusy = AtomicBoolean(false)

    /** The most recent upright camera frame, for on-demand OCR (null until vision runs). */
    fun lastFrame(): Bitmap? = lastUpright

    /** Dashboard frame sink: a downscaled base64 JPEG of the live camera + its rotation. */
    var onFrame: ((String, Int) -> Unit)? = null

    /** Only encode/stream the dashboard frame while a dashboard client is connected. */
    var shouldStreamFrame: () -> Boolean = { false }

    private val _status = MutableStateFlow(VisionStatus())
    val status: StateFlow<VisionStatus> = _status.asStateFlow()

    private class YTimed(val out: EtModule.Out, val ms: Double)
    private class DTimed(val data: FloatArray, val ms: Double)

    @Synchronized
    fun start(owner: LifecycleOwner, previewView: PreviewView?) {
        running = true
        _status.value = _status.value.copy(running = true, note = "loading models…")
        if (!modelsRequested) {
            modelsRequested = true
            depthExecutor.execute { depthModule = EtModule.tryLoad(context, DEPTH_ASSETS); onModelsChanged() }
            yoloExecutor.execute { yoloModule = EtModule.tryLoad(context, YOLO_ASSETS); onModelsChanged() }
        }
        bindCamera(owner, previewView)
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        modelsRequested = false
        ContextCompat.getMainExecutor(context).execute {
            runCatching { cameraProvider?.unbindAll() }
        }
        depthExecutor.execute { depthModule?.close(); depthModule = null }
        yoloExecutor.execute { yoloModule?.close(); yoloModule = null }
        inferenceExecutor.execute { cycle = 0L; cDepthData = null; cZones = DepthZones(0f, 0f, 0f) }
        inferenceBusy.set(false)
        _status.value = VisionStatus(note = "stopped")
        Log.i(TAG, "VisionPipeline stopped.")
    }

    fun shutdown() {
        stop()
        for (ex in listOf(analysisExecutor, inferenceExecutor, depthExecutor, yoloExecutor)) {
            ex.shutdown()
            runCatching { if (!ex.awaitTermination(2, TimeUnit.SECONDS)) ex.shutdownNow() }
        }
    }

    // @Synchronized: called from BOTH load executors (depth + yolo).
    @Synchronized
    private fun onModelsChanged() {
        val backend = BuildConfig.EXECUTORCH_BACKEND
        val note = when {
            depthModule == null && yoloModule == null ->
                "No models in assets/models — add depth.pte / yolo.pte, or use Mock mode."
            depthModule == null ->
                "YOLO only ($backend) — detection drives haptics; nearness from box size."
            yoloModule == null ->
                "Depth only ($backend) — belt works; no object labels."
            else -> "Depth + YOLO loaded on $backend (smooth frames + fresh consistent scene)."
        }
        _status.value = _status.value.copy(
            depthLoaded = depthModule != null,
            yoloLoaded = yoloModule != null,
            note = note,
        )
        Log.i(TAG, note)
    }

    private fun bindCamera(owner: LifecycleOwner, previewView: PreviewView?) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = previewView?.let { pv ->
                    Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, ::analyze) }
                provider.unbindAll()
                val useCases = listOfNotNull(preview, analysis).toTypedArray()
                // DEFAULT back camera + default zoom (normal FOV): correct left/right and
                // trained-scale detection. No ultra-wide, no zoom changes.
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases)
                Log.i(TAG, "CameraX bound (default back camera, preview=${preview != null}).")
            } catch (e: Throwable) {
                Log.e(TAG, "CameraX bind failed: ${e.message}", e)
                _status.value = _status.value.copy(note = "camera bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Camera callback (analysisExecutor): stream the frame, kick a fresh inference if idle. Never blocks on a model. */
    private fun analyze(image: ImageProxy) {
        try {
            if (depthModule == null && yoloModule == null) return  // nothing loaded -> emit nothing
            val upright = FrameBitmap.upright(image)
            lastUpright = upright
            maybeStreamFrame(upright)                 // smooth frame stream (throttled)
            // Start one inference cycle on the LATEST frame if the previous one is done.
            if (inferenceBusy.compareAndSet(false, true)) {
                inferenceExecutor.execute { runInference() }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "analyze error: ${e.message}")
        } finally {
            image.close() // mandatory or KEEP_ONLY_LATEST stalls
        }
    }

    /**
     * One inference cycle (inferenceExecutor): depth + YOLO in parallel on the SAME latest
     * frame, joined, then ONE consistent scene emitted. Always processes the newest frame
     * available, so the scene/haptics are as fresh as inference allows and never mismatched.
     */
    private fun runInference() {
        try {
            val frame = lastUpright ?: return
            val depth = depthModule
            val yolo = yoloModule

            // YOLO EVERY cycle: a detected object's zone (left/center/right) is always
            // from the current frame, so the belt direction is fresh and correct.
            val yf: Future<YTimed>? = if (yolo != null) yoloExecutor.submit(Callable {
                val t = System.nanoTime()
                val o = yolo.forward(yoloConv.toTensor(frame))
                YTimed(o, (System.nanoTime() - t) / 1_000_000.0)
            }) else null

            // Depth only 1/DEPTH_EVERY_N cycles (and until we have one) — it's the heavy
            // model and only sets nearness, not which side. Cached in between.
            val runDepth = depth != null && (cycle % DEPTH_EVERY_N == 0L || cDepthData == null)
            cycle++
            val df: Future<DTimed>? = if (runDepth) depthExecutor.submit(Callable {
                val t = System.nanoTime()
                val d = depth!!.forward(depthConv.toTensor(frame)).data
                DTimed(d, (System.nanoTime() - t) / 1_000_000.0)
            }) else null
            if (df != null) {
                val dRes = try { df.get(INFER_TIMEOUT_MS, TimeUnit.MILLISECONDS) } catch (e: Throwable) { Log.w(TAG, "depth: ${e.message}"); null }
                if (dRes != null) {
                    cDepthData = dRes.data; cDepthMs = dRes.ms
                    val side = depthSide(dRes.data.size); cDw = side; cDh = side
                    cZones = DepthDecoder.toZones(dRes.data, side, side)
                }
            }

            var objects: List<DetectedObj> = emptyList()
            var yoloMs = 0.0
            val yRes = try { yf?.get(INFER_TIMEOUT_MS, TimeUnit.MILLISECONDS) } catch (e: Throwable) { Log.w(TAG, "yolo: ${e.message}"); null }
            if (yRes != null) {
                yoloMs = yRes.ms
                if (!loggedYolo) {
                    loggedYolo = true
                    Log.i(TAG, "YOLO out size=${yRes.out.data.size} (expect ${YoloDecoder.ATTRS * YoloDecoder.ANCHORS_640})")
                }
                val dets = YoloDecoder.decode(yRes.out.data, inputSize = YOLO_SIZE)
                val dData = cDepthData
                objects = if (dData != null)
                    SceneAssembler.toDetectedObjects(dets, dData, cDw, cDh, YOLO_SIZE)
                else
                    SceneAssembler.toDetectedObjectsNoDepth(dets, YOLO_SIZE)
            }

            val zones = cZones
            val centerObjNear = objects.any {
                it.zone == "center" && it.nearness >= BeltMapper.OBJECT_NEAR_THRESHOLD
            }
            val pathClear =
                !zones.curbAhead && zones.center < BeltMapper.NEAR_THRESHOLD && !centerObjNear
            val base = SceneState(
                ts = System.currentTimeMillis(),
                depth = zones,
                objects = objects,
                pathClear = pathClear,
                conf = LIVE_CONF,
            )
            bus.emit(base.copy(belt = BeltMapper.packetAsInts(base)))
            updateStatus(cDepthMs, yoloMs, objects.size)
        } catch (e: Throwable) {
            Log.w(TAG, "inference error: ${e.message}")
        } finally {
            inferenceBusy.set(false)
        }
    }

    /** Throttled: encode the shared upright frame to a small JPEG for the dashboard. */
    private fun maybeStreamFrame(upright: Bitmap) {
        val sink = onFrame ?: return
        if (!shouldStreamFrame()) return
        val now = System.currentTimeMillis()
        if (now - lastFrameEmitMs < FRAME_MIN_INTERVAL_MS) return
        lastFrameEmitMs = now
        val b64 = encodeJpegBase64(upright) ?: return
        sink(b64, 0)  // already upright -> no extra rotation needed on the dashboard
    }

    private fun encodeJpegBase64(src: Bitmap): String? {
        return try {
            val targetW = minOf(FRAME_WIDTH, src.width)
            val scale = targetW.toFloat() / src.width
            val scaled = Bitmap.createScaledBitmap(
                src, targetW, (src.height * scale).roundToInt().coerceAtLeast(1), true
            )
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, FRAME_QUALITY, baos)
            if (scaled !== src) scaled.recycle()
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Throwable) {
            Log.w(TAG, "frame encode error: ${e.message}")
            null
        }
    }

    private fun updateStatus(depthMs: Double, yoloMs: Double, detections: Int) {
        val now = System.nanoTime()
        if (lastSceneNs != 0L) {
            val inst = 1_000_000_000.0 / (now - lastSceneNs).coerceAtLeast(1)
            emaFps = if (emaFps == 0.0) inst else 0.8 * emaFps + 0.2 * inst
        }
        lastSceneNs = now
        _status.value = _status.value.copy(
            depthMs = depthMs,
            yoloMs = yoloMs,
            fps = (emaFps * 10).roundToInt() / 10.0,
            detections = detections,
        )
    }

    /** Side length of a square depth map from its flattened length (518*518 -> 518). */
    private fun depthSide(len: Int): Int {
        val s = sqrt(len.toDouble()).roundToInt()
        return if (s > 0) s else DEPTH_SIZE
    }

    companion object {
        private const val TAG = "SixthSenseScene"
        private const val DEPTH_SIZE = 518
        private const val YOLO_SIZE = 640
        private const val LIVE_CONF = 0.85f
        private const val FRAME_WIDTH = 480
        private const val FRAME_QUALITY = 50
        private const val FRAME_MIN_INTERVAL_MS = 66L    // ~15 fps cap; actual ~11-12 fps
        private const val INFER_TIMEOUT_MS = 4000L       // guard: never hang the orchestrator
        private const val DEPTH_EVERY_N = 3L             // heavy depth runs 1 in 3 inference cycles

        private val DEPTH_ASSETS = listOf(
            "models/depth.pte",
            "models/depth_anything_v2.pte",
            "models/depth_anything_v2_small.pte",
        )
        private val YOLO_ASSETS = listOf(
            "models/yolo.pte",
            "models/yolo11n.pte",
            "models/yolov11n.pte",
        )
    }
}
