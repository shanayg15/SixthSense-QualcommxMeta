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
 * The live, fully ON-DEVICE perception pipeline. CameraX frames are run through
 * ExecuTorch `.pte` models (Depth-Anything-V2 + YOLOv11n) entirely on the phone.
 *
 * PARALLEL INFERENCE: depth runs on the analyzer thread while YOLO runs on its own
 * executor, both fed from one shared upright bitmap, so per-frame latency is
 * ~max(depth, yolo) instead of depth + yolo. Each model lives entirely on one
 * thread (load + forward + close) so a single Module is never touched concurrently.
 *
 * Degrades gracefully: with no `.pte` in assets it stays idle (use Mock mode).
 */
class VisionPipeline(
    private val context: Context,
    private val bus: SceneBus,
) {
    // analysisExecutor: camera analyzer + depth (load/forward/close) + orchestration.
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // yoloExecutor: YOLO (load/forward/close) — runs in parallel with depth.
    private val yoloExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val depthConv = FrameToTensor(DEPTH_SIZE, Norm.IMAGENET)
    private val yoloConv = FrameToTensor(YOLO_SIZE, Norm.SCALE_0_1)

    // INVARIANT: depthModule is only forwarded/closed on analysisExecutor, yoloModule
    // only on yoloExecutor. ExecuTorch Modules are not reentrant, so a single Module is
    // never touched from two threads at once (the two models DO run in parallel, but on
    // separate Module instances + separate executors).
    @Volatile private var depthModule: EtModule? = null
    @Volatile private var yoloModule: EtModule? = null
    @Volatile private var running = false
    @Volatile private var modelsRequested = false
    @Volatile private var loggedYolo = false

    private var cameraProvider: ProcessCameraProvider? = null
    // Written only on analysisExecutor (updateStatus / maybeStreamFrame) — single thread, no race.
    private var lastFrameNs = 0L
    private var emaFps = 0.0
    private var lastFrameEmitMs = 0L

    /** Dashboard frame sink: a downscaled base64 JPEG of the live camera + its rotation. */
    var onFrame: ((String, Int) -> Unit)? = null

    /** Only encode/stream the dashboard frame while a dashboard client is connected. */
    var shouldStreamFrame: () -> Boolean = { false }

    private val _status = MutableStateFlow(VisionStatus())
    val status: StateFlow<VisionStatus> = _status.asStateFlow()

    private class Timed(val out: EtModule.Out, val ms: Double)

    @Synchronized
    fun start(owner: LifecycleOwner, previewView: PreviewView?) {
        running = true
        _status.value = _status.value.copy(running = true, note = "loading models…")
        // Load each model on the SAME executor it will run on (depth=analysis, yolo=yolo)
        // so a Module is loaded/forwarded/closed from one thread. Queued before camera
        // frames arrive, so a model is ready by its first inference.
        if (!modelsRequested) {
            modelsRequested = true
            analysisExecutor.execute { depthModule = EtModule.tryLoad(context, DEPTH_ASSETS); onModelsChanged() }
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
        // Close each module on its own executor (serialized after any in-flight forward).
        analysisExecutor.execute { depthModule?.close(); depthModule = null }
        yoloExecutor.execute { yoloModule?.close(); yoloModule = null }
        _status.value = VisionStatus(note = "stopped")
        Log.i(TAG, "VisionPipeline stopped.")
    }

    fun shutdown() {
        stop()
        for (ex in listOf(analysisExecutor, yoloExecutor)) {
            ex.shutdown()
            runCatching { if (!ex.awaitTermination(2, TimeUnit.SECONDS)) ex.shutdownNow() }
        }
    }

    // @Synchronized: called from BOTH load executors (depth + yolo), so make the
    // read-modify-write of _status atomic across them.
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
            else -> "Depth + YOLO loaded on $backend (parallel)."
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
                    Preview.Builder().build().also { it.surfaceProvider = pv.surfaceProvider }
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, ::analyze) }
                provider.unbindAll()
                val useCases = listOfNotNull(preview, analysis).toTypedArray()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases)
                Log.i(TAG, "CameraX bound (preview=${preview != null}).")
            } catch (e: Throwable) {
                Log.e(TAG, "CameraX bind failed: ${e.message}", e)
                _status.value = _status.value.copy(note = "camera bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Runs on [analysisExecutor]. Depth runs here; YOLO runs in parallel on [yoloExecutor]. */
    private fun analyze(image: ImageProxy) {
        try {
            val depth = depthModule
            val yolo = yoloModule
            if (depth == null && yolo == null) return  // nothing loaded -> emit nothing (safe)

            // Convert the camera frame ONCE into a shared, read-only upright bitmap.
            // SAFETY: 'upright' is never recycled here (left to GC) and is only READ —
            // depth (this thread) and YOLO (yoloExecutor) each createScaledBitmap from
            // it into their OWN copy, which they recycle independently. Concurrent reads
            // of a Bitmap are safe. maybeStreamFrame runs synchronously BEFORE the YOLO
            // task is submitted, so its scaled copy never overlaps the parallel work.
            val upright = FrameBitmap.upright(image)
            maybeStreamFrame(upright)

            // Kick YOLO off on its own thread FIRST so it overlaps depth on this thread.
            val yoloFuture: Future<Timed>? = if (yolo != null) {
                yoloExecutor.submit(Callable {
                    val t = System.nanoTime()
                    val o = yolo.forward(yoloConv.toTensor(upright))
                    Timed(o, (System.nanoTime() - t) / 1_000_000.0)
                })
            } else null

            // Depth on this thread, concurrently with the YOLO task.
            var zones = DepthZones(0f, 0f, 0f)
            var depthData: FloatArray? = null
            var dw = 0; var dh = 0
            var depthMs = 0.0
            if (depth != null) {
                val t0 = System.nanoTime()
                val flat = depth.forward(depthConv.toTensor(upright)).data
                depthMs = (System.nanoTime() - t0) / 1_000_000.0
                val side = depthSide(flat.size)
                dw = side; dh = side; depthData = flat
                zones = DepthDecoder.toZones(flat, side, side)
            }

            // Join the parallel YOLO result.
            var objects: List<DetectedObj> = emptyList()
            var yoloMs = 0.0
            val yt = try {
                yoloFuture?.get(INFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: Throwable) {
                Log.w(TAG, "yolo task: ${e.message}"); null
            }
            if (yt != null) {
                yoloMs = yt.ms
                if (!loggedYolo) {
                    loggedYolo = true
                    val expected = YoloDecoder.ATTRS * YoloDecoder.ANCHORS_640
                    var m = 0f; val d = yt.out.data; var i = 4 * YoloDecoder.ANCHORS_640
                    while (i < d.size) { if (d[i] > m) m = d[i]; i++ }
                    Log.i(TAG, "YOLO out size=${d.size} (expect $expected) maxScore=$m")
                }
                val dets = YoloDecoder.decode(yt.out.data, inputSize = YOLO_SIZE)
                objects = if (depthData != null)
                    SceneAssembler.toDetectedObjects(dets, depthData, dw, dh, YOLO_SIZE)
                else
                    SceneAssembler.toDetectedObjectsNoDepth(dets, YOLO_SIZE)
            }

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
            updateStatus(depthMs, yoloMs, objects.size)
        } catch (e: Throwable) {
            Log.w(TAG, "analyze error: ${e.message}")
        } finally {
            image.close() // mandatory or KEEP_ONLY_LATEST stalls
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
            val scale = FRAME_WIDTH.toFloat() / src.width
            val scaled = Bitmap.createScaledBitmap(
                src, FRAME_WIDTH, (src.height * scale).roundToInt().coerceAtLeast(1), true
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
        if (lastFrameNs != 0L) {
            val inst = 1_000_000_000.0 / (now - lastFrameNs).coerceAtLeast(1)
            emaFps = if (emaFps == 0.0) inst else 0.8 * emaFps + 0.2 * inst
        }
        lastFrameNs = now
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
        private const val FRAME_MIN_INTERVAL_MS = 125L   // ~8 fps to the dashboard
        private const val INFER_TIMEOUT_MS = 4000L       // guard: never hang the analyzer

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
