package com.sixthsense.vision

import android.content.Context
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
import com.sixthsense.core.DetectedObj
import com.sixthsense.core.SceneBus
import com.sixthsense.core.SceneState
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
    val backend: String = BuildConfig.EXECUTORCH_BACKEND,
    val depthMs: Double = 0.0,
    val yoloMs: Double = 0.0,
    val fps: Double = 0.0,
    val note: String = "idle",
)

/**
 * The live, fully ON-DEVICE perception pipeline. CameraX frames are run through
 * ExecuTorch `.pte` models (Depth-Anything-V2 + YOLOv11n) entirely on the phone —
 * no network, airplane-mode capable — and the result is published as [SceneState]
 * on the [SceneBus] that the belt mapper, voice agent, dashboard, and phone-haptics
 * test mode all consume.
 *
 * Backend is baked into the `.pte` at export time (XNNPACK CPU now, Qualcomm
 * QNN/Hexagon NPU as a drop-in later — see docs); this code is identical for both.
 *
 * Degrades gracefully: with no `.pte` in assets it stays idle and logs that mock
 * mode should be used (it never emits a confidently-wrong scene). Depth gates
 * emission — protecting MVP rung 1 (depth -> belt); YOLO is additive.
 */
class VisionPipeline(
    private val context: Context,
    private val bus: SceneBus,
) {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val depthConv = FrameToTensor(DEPTH_SIZE, Norm.IMAGENET)
    private val yoloConv = FrameToTensor(YOLO_SIZE, Norm.SCALE_0_1)

    @Volatile private var depthModule: EtModule? = null
    @Volatile private var yoloModule: EtModule? = null
    @Volatile private var running = false
    @Volatile private var modelsRequested = false

    private var cameraProvider: ProcessCameraProvider? = null
    private var lastFrameNs = 0L
    private var emaFps = 0.0

    private val _status = MutableStateFlow(VisionStatus())
    val status: StateFlow<VisionStatus> = _status.asStateFlow()

    /**
     * Start live vision. Loads models off the main thread, then binds CameraX
     * [Preview] (optional, for the operator) + [ImageAnalysis] to [owner]'s lifecycle.
     */
    @Synchronized
    fun start(owner: LifecycleOwner, previewView: PreviewView?) {
        running = true
        _status.value = _status.value.copy(running = true, note = "loading models…")
        // Load once on the analysis thread; queued analyze() calls run after, so
        // models are ready by the first inference (no null-module race). bindCamera
        // unbinds first, so re-tapping Start (e.g. after rotation) safely rebinds.
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
        // close is serialized strictly after any in-flight/queued analyze — no
        // half-destroyed-module access is possible.
        analysisExecutor.execute {
            depthModule?.close(); depthModule = null
            yoloModule?.close(); yoloModule = null
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
        depthModule = EtModule.tryLoad(context, DEPTH_ASSETS)
        yoloModule = EtModule.tryLoad(context, YOLO_ASSETS)
        val note = when {
            depthModule == null ->
                "No depth model in assets/models — live vision idle. Add depth.pte or use Mock mode."
            yoloModule == null ->
                "Depth only (no YOLO) — belt works; no object labels."
            else -> "Depth + YOLO loaded on ${BuildConfig.EXECUTORCH_BACKEND}."
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

    /** Runs on [analysisExecutor], never the main thread. */
    private fun analyze(image: ImageProxy) {
        try {
            val depth = depthModule ?: return  // not loaded / absent -> emit nothing (safe)

            val t0 = System.nanoTime()
            val depthOut = depth.forward(depthConv.toTensor(image))
            val depthMs = (System.nanoTime() - t0) / 1_000_000.0
            val (dw, dh) = depthDims(depthOut)
            val zones = DepthDecoder.toZones(depthOut.data, dw, dh)

            var objects: List<DetectedObj> = emptyList()
            var yoloMs = 0.0
            yoloModule?.let { yolo ->
                val t1 = System.nanoTime()
                val yoloOut = yolo.forward(yoloConv.toTensor(image))
                yoloMs = (System.nanoTime() - t1) / 1_000_000.0
                val dets = YoloDecoder.decode(yoloOut.data, inputSize = YOLO_SIZE)
                objects = SceneAssembler.toDetectedObjects(dets, depthOut.data, dw, dh, YOLO_SIZE)
            }

            val pathClear = !zones.curbAhead && zones.center < BeltMapper.NEAR_THRESHOLD
            val base = SceneState(
                ts = System.currentTimeMillis(),
                depth = zones,
                objects = objects,
                pathClear = pathClear,
                conf = LIVE_CONF,
            )
            bus.emit(base.copy(belt = BeltMapper.packetAsInts(base)))
            updateStatus(depthMs, yoloMs)
        } catch (e: Throwable) {
            Log.w(TAG, "analyze error: ${e.message}")
        } finally {
            image.close() // mandatory or KEEP_ONLY_LATEST stalls
        }
    }

    private fun updateStatus(depthMs: Double, yoloMs: Double) {
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
        )
    }

    /**
     * Derive (width, height) of the depth map from the output shape's last two dims.
     * For a [..,H,W] tensor that is (W, H) = (s[last], s[last-1]); the depth map is
     * square (518x518) so order only matters for indexing, which uses row*w+col.
     */
    private fun depthDims(out: EtModule.Out): Pair<Int, Int> {
        val s = out.shape
        return if (s.size >= 2) s[s.size - 1].toInt() to s[s.size - 2].toInt()
        else {
            val side = sqrt(out.data.size.toDouble()).roundToInt()
            side to side
        }
    }

    companion object {
        private const val TAG = "SixthSenseScene"
        private const val DEPTH_SIZE = 518
        private const val YOLO_SIZE = 640
        private const val LIVE_CONF = 0.85f

        // Candidate asset names (Stream B ships depth.pte/yolo.pte; docs use longer names).
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
