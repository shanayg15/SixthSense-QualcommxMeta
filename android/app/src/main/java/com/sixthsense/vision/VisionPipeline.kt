package com.sixthsense.vision

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.util.Base64
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraFilter
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
 * DECOUPLED INFERENCE: the camera-analyzer thread NEVER blocks on a model. It
 * streams the live frame and emits the scene every camera frame, while depth and
 * YOLO each run on their OWN executor and publish their latest result back. So the
 * dashboard camera is smooth (camera-rate) even though the models are slower — the
 * boxes/zones just trail by a frame or two. Each Module lives on exactly one thread
 * (load + forward + close) so it is never touched concurrently.
 *
 * Uses the WIDEST back lens (ultra-wide) at minimum zoom for the most field of view.
 *
 * Degrades gracefully: with no `.pte` in assets it stays idle (use Mock mode).
 */
class VisionPipeline(
    private val context: Context,
    private val bus: SceneBus,
) {
    // analysisExecutor: the camera analyzer ONLY (fast: upright + stream + dispatch + emit).
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // Each model runs on its own executor, in parallel, OFF the analyzer thread.
    private val depthExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val yoloExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val depthConv = FrameToTensor(DEPTH_SIZE, Norm.IMAGENET)
    private val yoloConv = FrameToTensor(YOLO_SIZE, Norm.SCALE_0_1)

    // INVARIANT: depthModule is only loaded/forwarded/closed on depthExecutor, yoloModule
    // only on yoloExecutor. ExecuTorch Modules are not reentrant, so a single Module is
    // never touched from two threads at once.
    @Volatile private var depthModule: EtModule? = null
    @Volatile private var yoloModule: EtModule? = null
    @Volatile private var running = false
    @Volatile private var modelsRequested = false
    @Volatile private var loggedYolo = false

    private var cameraProvider: ProcessCameraProvider? = null
    // Written only on analysisExecutor (updateStatus / maybeStreamFrame) — single thread.
    private var lastFrameNs = 0L
    private var emaFps = 0.0
    private var lastFrameEmitMs = 0L

    // Cadence + latest-result publishing. These cross threads (written by the depth/yolo
    // executors, read by the analyzer), so they are @Volatile — reference/Float writes are
    // atomic and a slightly stale depth map or box list is fine (it's all approximate).
    private var frameCount = 0L                       // analyzer-thread-only
    @Volatile private var depthBusy = false
    @Volatile private var yoloBusy = false
    @Volatile private var cachedZones = DepthZones(0f, 0f, 0f)
    @Volatile private var cachedDepthData: FloatArray? = null
    @Volatile private var cachedDw = 0
    @Volatile private var cachedDh = 0
    @Volatile private var cachedDepthMs = 0.0
    @Volatile private var latestObjects: List<DetectedObj> = emptyList()
    @Volatile private var latestYoloMs = 0.0

    /** Dashboard frame sink: a downscaled base64 JPEG of the live camera + its rotation. */
    var onFrame: ((String, Int) -> Unit)? = null

    /** Only encode/stream the dashboard frame while a dashboard client is connected. */
    var shouldStreamFrame: () -> Boolean = { false }

    private val _status = MutableStateFlow(VisionStatus())
    val status: StateFlow<VisionStatus> = _status.asStateFlow()

    @Synchronized
    fun start(owner: LifecycleOwner, previewView: PreviewView?) {
        running = true
        _status.value = _status.value.copy(running = true, note = "loading models…")
        // Load each model on the SAME executor it will run on, so a Module is
        // loaded/forwarded/closed from one thread. Queued before camera frames arrive.
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
        // Close each module on its own executor (serialized after any in-flight forward).
        depthExecutor.execute {
            depthModule?.close(); depthModule = null
            cachedDepthData = null; cachedZones = DepthZones(0f, 0f, 0f); depthBusy = false
        }
        yoloExecutor.execute {
            yoloModule?.close(); yoloModule = null; latestObjects = emptyList(); yoloBusy = false
        }
        analysisExecutor.execute { frameCount = 0L }
        _status.value = VisionStatus(note = "stopped")
        Log.i(TAG, "VisionPipeline stopped.")
    }

    fun shutdown() {
        stop()
        for (ex in listOf(analysisExecutor, depthExecutor, yoloExecutor)) {
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
            else -> "Depth + YOLO loaded on $backend (decoupled, depth 1/$DEPTH_EVERY_N)."
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
                    // Preview has setSurfaceProvider() but no getter, so it's not a Kotlin
                    // property — call the method (not `it.surfaceProvider = …`).
                    Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, ::analyze) }
                val useCases = listOfNotNull(preview, analysis).toTypedArray()
                fun bind(selector: CameraSelector): Camera {
                    provider.unbindAll()
                    return provider.bindToLifecycle(owner, selector, *useCases)
                }
                // Prefer the widest (ultra-wide) back lens; fall back to the default camera
                // if that physical lens can't satisfy the use cases on this device.
                val camera = try {
                    bind(widestBackSelector())
                } catch (e: Throwable) {
                    Log.w(TAG, "widest lens bind failed (${e.message}); using default back camera")
                    bind(CameraSelector.DEFAULT_BACK_CAMERA)
                }
                // Zoom all the way out for maximum field of view.
                runCatching {
                    val minZoom = camera.cameraInfo.zoomState.value?.minZoomRatio ?: 1f
                    camera.cameraControl.setZoomRatio(minZoom)
                    Log.i(TAG, "zoom set to min ${minZoom}x for widest FOV")
                }
                Log.i(TAG, "CameraX bound (preview=${preview != null}).")
            } catch (e: Throwable) {
                Log.e(TAG, "CameraX bind failed: ${e.message}", e)
                _status.value = _status.value.copy(note = "camera bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Back-camera selector that picks the lens with the smallest focal length = widest FOV. */
    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun widestBackSelector(): CameraSelector =
        CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter(CameraFilter { infos ->
                val widest = infos.minByOrNull { info ->
                    runCatching {
                        Camera2CameraInfo.from(info)
                            .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            ?.minOrNull() ?: Float.MAX_VALUE
                    }.getOrDefault(Float.MAX_VALUE)
                }
                if (widest != null) listOf(widest) else infos
            })
            .build()

    /**
     * Runs on [analysisExecutor], once per camera frame, and NEVER blocks on a model:
     * it streams the live frame, kicks depth/YOLO if they're idle, and emits the scene
     * from the latest available results.
     */
    private fun analyze(image: ImageProxy) {
        try {
            val depth = depthModule
            val yolo = yoloModule
            if (depth == null && yolo == null) return  // nothing loaded -> emit nothing (safe)

            // One shared, read-only upright bitmap. It is an independent copy (NOT backed by
            // the ImageProxy buffer), so the async tasks may keep reading it after image.close().
            val upright = FrameBitmap.upright(image)
            maybeStreamFrame(upright)                  // fast path: live frame every loop
            val n = frameCount++

            // Depth: dispatch async on its executor (every DEPTH_EVERY_N frames), skip if busy.
            if (depth != null && !depthBusy && (yolo == null || n % DEPTH_EVERY_N == 0L)) {
                depthBusy = true
                depthExecutor.execute {
                    try {
                        val t0 = System.nanoTime()
                        val flat = depth.forward(depthConv.toTensor(upright)).data
                        val side = depthSide(flat.size)
                        cachedDw = side; cachedDh = side; cachedDepthData = flat
                        cachedZones = DepthDecoder.toZones(flat, side, side)
                        cachedDepthMs = (System.nanoTime() - t0) / 1_000_000.0
                    } catch (e: Throwable) {
                        Log.w(TAG, "depth task: ${e.message}")
                    } finally {
                        depthBusy = false
                    }
                }
            }

            // YOLO: dispatch async on its executor, skip if the previous one is still running.
            if (yolo != null && !yoloBusy) {
                yoloBusy = true
                yoloExecutor.execute {
                    try {
                        val t0 = System.nanoTime()
                        val out = yolo.forward(yoloConv.toTensor(upright))
                        latestYoloMs = (System.nanoTime() - t0) / 1_000_000.0
                        if (!loggedYolo) {
                            loggedYolo = true
                            Log.i(TAG, "YOLO out size=${out.data.size} (expect ${YoloDecoder.ATTRS * YoloDecoder.ANCHORS_640})")
                        }
                        val dets = YoloDecoder.decode(out.data, inputSize = YOLO_SIZE)
                        val dData = cachedDepthData
                        latestObjects = if (dData != null)
                            SceneAssembler.toDetectedObjects(dets, dData, cachedDw, cachedDh, YOLO_SIZE)
                        else
                            SceneAssembler.toDetectedObjectsNoDepth(dets, YOLO_SIZE)
                    } catch (e: Throwable) {
                        Log.w(TAG, "yolo task: ${e.message}")
                    } finally {
                        yoloBusy = false
                    }
                }
            }

            // Emit the scene from the latest available results — never blocks the frame loop.
            val zones = cachedZones
            val objects = latestObjects
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
            updateStatus(cachedDepthMs, latestYoloMs, objects.size)
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
        private const val FRAME_QUALITY = 45
        private const val FRAME_MIN_INTERVAL_MS = 66L    // ~15 fps to the dashboard
        private const val DEPTH_EVERY_N = 3L             // run heavy depth 1 in 3 frames

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
