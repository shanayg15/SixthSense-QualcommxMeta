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
import java.util.concurrent.Executors
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
    val backend: String = BuildConfig.CV_BACKEND,
    val depthMs: Double = 0.0,
    val yoloMs: Double = 0.0,
    val fps: Double = 0.0,
    val detections: Int = 0,
    val note: String = "idle",
)

/**
 * The live, fully ON-DEVICE perception pipeline. CameraX frames are run through
 * Qualcomm-AI-Hub **LiteRT (.tflite)** CV models — Depth-Anything-V2 + YOLOv11 —
 * on the Hexagon **NPU** (GPU/CPU fallback), and the result is published as
 * [SceneState] on the [SceneBus] consumed by the belt mapper, voice agent,
 * dashboard, and phone-haptics test mode. (The on-device LLM stays on ExecuTorch.)
 *
 * AI Hub TFLite is NHWC: depth output flattens to a 518×518 inverse-depth map;
 * YOLO output is pre-decoded boxes/scores/class_idx (NMS only). Degrades
 * gracefully: with no `.tflite` in assets it stays idle (use Mock mode).
 */
class VisionPipeline(
    private val context: Context,
    private val bus: SceneBus,
) {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val depthConv = LiteRtFrameConverter(DEPTH_SIZE)
    private val yoloConv = LiteRtFrameConverter(YOLO_SIZE)

    @Volatile private var depthModel: LiteRtModel? = null
    @Volatile private var yoloModel: LiteRtModel? = null
    @Volatile private var running = false
    @Volatile private var modelsRequested = false

    private var cameraProvider: ProcessCameraProvider? = null
    private var lastFrameNs = 0L
    private var emaFps = 0.0
    private var lastFrameEmitMs = 0L

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
        if (!modelsRequested) {
            modelsRequested = true
            analysisExecutor.execute { loadModels() }
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
        // analyze() and this close run on the SAME single-thread executor, so the
        // close is serialized strictly after any in-flight/queued analyze.
        analysisExecutor.execute {
            depthModel?.close(); depthModel = null
            yoloModel?.close(); yoloModel = null
        }
        _status.value = VisionStatus(note = "stopped")
        Log.i(TAG, "VisionPipeline stopped.")
    }

    fun shutdown() {
        stop()
        analysisExecutor.shutdown()
        runCatching {
            if (!analysisExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                analysisExecutor.shutdownNow()
            }
        }
    }

    private fun loadModels() {
        depthModel = LiteRtModel.tryLoad(context, DEPTH_ASSETS)
        yoloModel = LiteRtModel.tryLoad(context, YOLO_ASSETS)
        val accel = depthModel?.accelerator ?: yoloModel?.accelerator
        val backend = if (accel != null) "litert/$accel" else BuildConfig.CV_BACKEND
        val note = when {
            depthModel == null && yoloModel == null ->
                "No .tflite in assets/models — add depth.tflite / yolo.tflite (AI Hub), or use Mock mode."
            depthModel == null ->
                "YOLO only ($backend) — detection drives haptics; nearness from box size."
            yoloModel == null ->
                "Depth only ($backend) — belt works; no object labels."
            else -> "Depth + YOLO loaded on $backend."
        }
        _status.value = _status.value.copy(
            depthLoaded = depthModel != null,
            yoloLoaded = yoloModel != null,
            backend = backend,
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

    /** Runs on [analysisExecutor], never the main thread. */
    private fun analyze(image: ImageProxy) {
        try {
            maybeStreamFrame(image)
            val depth = depthModel
            val yolo = yoloModel
            if (depth == null && yolo == null) return  // nothing loaded -> emit nothing (safe)

            // Depth (if present) -> zones + a flat depth map for object nearness.
            var zones = DepthZones(0f, 0f, 0f)
            var depthData: FloatArray? = null
            var dw = 0; var dh = 0
            var depthMs = 0.0
            if (depth != null) {
                val t0 = System.nanoTime()
                val outs = depth.run(depthConv.toNHWC(image))
                depthMs = (System.nanoTime() - t0) / 1_000_000.0
                // Depth map is the largest output ([1,518,518,1] -> 518*518); guard
                // against an empty/multi-tensor graph instead of blindly indexing [0].
                val flat = outs.maxByOrNull { it.size }
                if (flat != null && flat.isNotEmpty()) {
                    val side = depthSide(flat.size)
                    dw = side; dh = side; depthData = flat
                    zones = DepthDecoder.toZones(flat, side, side)
                }
            }

            // YOLO (if present) -> objects. AI Hub output is pre-decoded (NMS only).
            var objects: List<DetectedObj> = emptyList()
            var yoloMs = 0.0
            if (yolo != null) {
                val t1 = System.nanoTime()
                val outs = yolo.run(yoloConv.toNHWC(image))
                yoloMs = (System.nanoTime() - t1) / 1_000_000.0
                val dets = LiteRtYolo.decode(outs, inputSize = YOLO_SIZE)
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

    /** Throttled: encode the current RGBA frame to a small JPEG for the dashboard. */
    private fun maybeStreamFrame(image: ImageProxy) {
        val sink = onFrame ?: return
        if (!shouldStreamFrame()) return
        val now = System.currentTimeMillis()
        if (now - lastFrameEmitMs < FRAME_MIN_INTERVAL_MS) return
        lastFrameEmitMs = now
        val b64 = encodeJpegBase64(image) ?: return
        sink(b64, image.imageInfo.rotationDegrees)
    }

    /** RGBA_8888 ImageProxy -> downscaled JPEG -> base64 (reuses the pipeline's camera). */
    private fun encodeJpegBase64(image: ImageProxy): String? {
        return try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val bmpW = image.width + rowPadding / pixelStride
            val full = Bitmap.createBitmap(bmpW, image.height, Bitmap.Config.ARGB_8888)
            plane.buffer.rewind()
            full.copyPixelsFromBuffer(plane.buffer)
            val cropped = if (bmpW != image.width) {
                Bitmap.createBitmap(full, 0, 0, image.width, image.height).also { full.recycle() }
            } else full
            val scale = FRAME_WIDTH.toFloat() / image.width
            val scaled = Bitmap.createScaledBitmap(
                cropped, FRAME_WIDTH, (image.height * scale).roundToInt().coerceAtLeast(1), true
            )
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, FRAME_QUALITY, baos)
            if (scaled !== cropped) cropped.recycle()
            scaled.recycle()
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

        // AI Hub LiteRT artifacts (git-ignored; from export_aihub_cv.sh).
        private val DEPTH_ASSETS = listOf(
            "models/depth.tflite",
            "models/depth_anything_v2.tflite",
        )
        private val YOLO_ASSETS = listOf(
            "models/yolo.tflite",
            "models/yolov11_det.tflite",
        )
    }
}
