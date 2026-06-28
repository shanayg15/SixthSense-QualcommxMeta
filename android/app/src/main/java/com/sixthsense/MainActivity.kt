package com.sixthsense

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.GsonBuilder
import com.sixthsense.core.SceneState
import com.sixthsense.debug.AppGraph
import com.sixthsense.vision.DetectionOverlayView
import com.sixthsense.vision.VisionStatus
import com.sixthsense.ws.SceneSocket
import kotlin.math.max
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Operator / developer console — NOT the end-user interface. The blind user is
 * guided by the belt / phone haptics and voice; this screen exists for development
 * and the demo operator (start live vision, toggle the phone-haptics test mode and
 * mock, fire belt tests, watch the live SceneState + backend/latency/fps).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var sceneView: TextView
    private lateinit var statusView: TextView
    private lateinit var previewView: PreviewView
    private lateinit var overlay: DetectionOverlayView
    private lateinit var hapticsButton: Button
    private lateinit var testRunButton: Button
    private var testRunActive = false
    private var socket: SceneSocket? = null
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private val requestBt = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        Log.i(TAG, "BT permissions: $result")
        AppGraph.beltClient.connect()
    }

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startLiveVision()
        else toast("Camera permission denied — live vision needs the camera.")
    }

    private val requestCameraForTestRun = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startFullTestRun()
        else toast("Camera permission denied — the full test run needs the camera.")
    }

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceAsk()
        else toast("Microphone permission denied — voice ask needs the mic.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        setContentView(buildUi())
        observeScene()
        observeVisionStatus()
        startDashboardSocket()
        // The vision pipeline owns the camera; it streams the live S25 frame to the
        // dashboard (only while a dashboard client is connected) and the voice agent
        // forwards each interaction. One camera owner — no second CameraX binding.
        AppGraph.visionPipeline.onFrame = { b64, rot -> socket?.pushFrame(b64, rot) }
        AppGraph.visionPipeline.shouldStreamFrame = { socket?.hasClients() == true }
        AppGraph.voiceAgent.onAnswer = { q, intent, a ->
            socket?.updateVoice(q, intent, a)
            AppGraph.tts.speak(a)   // speak the agent's answer (on-device TTS)
        }
    }

    private fun buildUi(): ScrollView {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.title)
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.subtitle)
            textSize = 12f
            setPadding(0, 0, 0, pad)
        })

        // Operator camera preview + AR detection overlay (the blind user does not look
        // at this). The overlay draws detection boxes on top of the live camera.
        val camHeight = (240 * resources.displayMetrics.density).toInt()
        val camContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, camHeight)
        }
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        overlay = DetectionOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        camContainer.addView(previewView)
        camContainer.addView(overlay)   // overlay sits on top of the preview
        root.addView(camContainer)

        statusView = TextView(this).apply {
            text = getString(R.string.vision_idle)
            textSize = 12f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(statusView)

        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener { onClick() }
        }

        // One-tap full test run: camera + on-device detection + directional phone
        // haptics together. Approach an object; when detection fires, the phone
        // buzzes in that direction. Tap again to exit.
        testRunButton = button(getString(R.string.btn_test_run_start)) { toggleFullTestRun() }
        root.addView(testRunButton)

        root.addView(button(getString(R.string.btn_start_vision)) { connectCameraAndStart() })
        root.addView(button(getString(R.string.btn_stop_vision)) {
            AppGraph.visionPipeline.stop()
            clearScene()
        })
        hapticsButton = button(getString(R.string.btn_haptics_off)) { togglePhoneHaptics() }
        root.addView(hapticsButton)

        root.addView(button(getString(R.string.btn_connect_belt)) { connectBelt() })
        root.addView(button(getString(R.string.btn_mock_on)) {
            AppGraph.mockSceneProducer.setEnabled(true)
        })
        root.addView(button(getString(R.string.btn_mock_off)) {
            AppGraph.mockSceneProducer.setEnabled(false)
        })
        // 4-motor belt bring-up tests: [LEFT, CENTER_L, CENTER_R, RIGHT, pattern].
        root.addView(button(getString(R.string.btn_test_left)) {
            AppGraph.beltClient.send(byteArrayOf(200.toByte(), 0, 0, 0, 0))
        })
        root.addView(button(getString(R.string.btn_test_center)) {
            AppGraph.beltClient.send(byteArrayOf(0, 200.toByte(), 200.toByte(), 0, 0)) // both center
        })
        root.addView(button(getString(R.string.btn_test_right)) {
            AppGraph.beltClient.send(byteArrayOf(0, 0, 0, 200.toByte(), 0))
        })
        root.addView(button(getString(R.string.btn_ask)) {
            // Uses the on-device Qwen LLM when ready (falls back to rule-based);
            // generation runs off-thread, so toast the answer when it returns.
            toast(if (AppGraph.llmEngine.isReady) "Asking Qwen…" else "Answering…")
            AppGraph.voiceAgent.askAsync("what's ahead of me?") { answer ->
                Log.i(TAG, "Voice answer: $answer")
                runOnUiThread { toast(answer) }
            }
        })
        // On-device OCR (ML Kit) — read text in the current frame, spoken + on the dashboard.
        root.addView(button("🔎 Read Text (OCR)") { readTextOcr() })
        // On-device push-to-talk: Android speech recognizer -> agent -> spoken answer.
        root.addView(button("🎤 Voice Ask (push-to-talk)") { voiceAsk() })

        sceneView = TextView(this).apply {
            text = getString(R.string.scene_waiting)
            textSize = 13f
            setPadding(0, pad, 0, 0)
            gravity = Gravity.START
        }
        root.addView(sceneView)

        return ScrollView(this).apply { addView(root) }
    }

    private fun connectCameraAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startLiveVision()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startLiveVision() {
        AppGraph.visionPipeline.start(this, previewView)
    }

    /** On-demand OCR: recognize text in the latest camera frame, speak it, and publish
     *  it on the scene so the dashboard shows it too. */
    private fun readTextOcr() {
        val frame = AppGraph.visionPipeline.lastFrame()
        if (frame == null) {
            toast("Start live vision first.")
            return
        }
        toast("Reading text…")
        AppGraph.ocrEngine.recognize(frame) { text ->
            runOnUiThread {
                val current = AppGraph.sceneBus.state.value
                AppGraph.sceneBus.emit(
                    current.copy(
                        ts = System.currentTimeMillis(),
                        ocr = com.sixthsense.core.Ocr(present = text.isNotBlank(), text = text),
                    )
                )
                val spoken = if (text.isBlank()) "I don't see readable text." else "The sign says: $text"
                AppGraph.tts.speak(spoken)
                toast(spoken)
            }
        }
    }

    /** Push-to-talk: on-device speech -> voice agent -> spoken answer (TTS via onAnswer). */
    private fun voiceAsk() {
        if (!AppGraph.speechInput.available()) {
            toast("On-device speech recognition isn't available on this device.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceAsk()
        } else {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceAsk() {
        toast("Listening…")
        AppGraph.speechInput.listen(
            onResult = { transcript ->
                if (transcript.isBlank()) {
                    toast("Didn't catch that — try again.")
                    return@listen
                }
                toast("You: $transcript")
                // onAnswer (wired in onCreate) speaks the reply via TTS + updates the dashboard.
                AppGraph.voiceAgent.askAsync(transcript) { answer -> Log.i(TAG, "Voice A='$answer'") }
            },
            onError = { msg -> runOnUiThread { toast(msg) } },
        )
    }

    /** One button: enter/exit a full test run (camera + detection + phone haptics). */
    private fun toggleFullTestRun() {
        if (testRunActive) {
            stopFullTestRun()
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startFullTestRun()
        } else {
            requestCameraForTestRun.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startFullTestRun() {
        testRunActive = true
        AppGraph.visionPipeline.start(this, previewView)
        testRunButton.text = getString(R.string.btn_test_run_stop)
        // The 4-motor belt (auto-driven when connected) is the primary haptic; only
        // fall back to the phone when there's no belt, so they don't double-buzz.
        if (AppGraph.beltClient.isConnected) {
            toast("Test run — belt buzzes toward objects (LEFT / ahead / RIGHT).")
        } else {
            AppGraph.phoneHaptics.setEnabled(true)
            hapticsButton.text = getString(R.string.btn_haptics_on)
            toast(
                if (AppGraph.phoneHaptics.hasVibrator()) "Test run — no belt; the phone buzzes toward objects."
                else "Test run on (no belt and no phone vibrator)."
            )
        }
    }

    private fun stopFullTestRun() {
        testRunActive = false
        AppGraph.phoneHaptics.setEnabled(false)
        AppGraph.visionPipeline.stop()
        clearScene()  // quiet the belt/phone
        testRunButton.text = getString(R.string.btn_test_run_start)
        hapticsButton.text = getString(R.string.btn_haptics_off)
        toast("Test run off.")
    }

    /** Emit a clear scene so the belt + phone stop buzzing when vision is stopped. */
    private fun clearScene() {
        AppGraph.sceneBus.emit(
            com.sixthsense.core.SceneBus.SAFE_DEFAULT.copy(ts = System.currentTimeMillis())
        )
    }

    private fun togglePhoneHaptics() {
        val controller = AppGraph.phoneHaptics
        val enable = !controller.isEnabled()
        controller.setEnabled(enable)
        hapticsButton.text =
            getString(if (enable) R.string.btn_haptics_on else R.string.btn_haptics_off)
        if (enable && !controller.hasVibrator()) {
            toast("This device has no vibration motor.")
        }
    }

    private fun connectBelt() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestBt.launch(
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            )
        } else {
            requestBt.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    private fun observeScene() {
        lifecycleScope.launch {
            AppGraph.sceneBus.state.collectLatest { scene ->
                sceneView.text = render(scene)
                overlay.setDetections(scene.objects)
                // Haptics priority: the 4-motor belt (driven live by BeltController when
                // connected) is primary. Only fall back to a phone proximity buzz when no
                // belt is connected and phone test-mode isn't already driving the motor.
                if (!AppGraph.beltClient.isConnected && !AppGraph.phoneHaptics.isEnabled()) {
                    AppGraph.phoneHaptics.driveOnce(proximityPacket(scene))
                }
            }
        }
    }

    /** Belt packet built only from RED (too-close) detections, so only they buzz. */
    private fun proximityPacket(s: SceneState): List<Int> {
        var l = 0; var c = 0; var r = 0
        for (o in s.objects) {
            if (o.nearness < DetectionOverlayView.RED_THRESHOLD) continue
            val i = (o.nearness * 255).toInt().coerceIn(0, 255)
            when (o.zone) {
                "left" -> l = max(l, i)
                "right" -> r = max(r, i)
                else -> c = max(c, i)
            }
        }
        return listOf(l, c, r, 0)
    }

    private fun observeVisionStatus() {
        lifecycleScope.launch {
            AppGraph.visionPipeline.status.collectLatest { s -> statusView.text = renderStatus(s) }
        }
    }

    private fun renderStatus(s: VisionStatus): String = buildString {
        append("vision: ${if (s.running) "ON" else "off"}  backend=${s.backend}  testRun=$testRunActive\n")
        append("models: depth=${if (s.depthLoaded) "✓" else "—"}  yolo=${if (s.yoloLoaded) "✓" else "—"}  detections=${s.detections}\n")
        append("fps=%.1f  depth=%.0fms  yolo=%.0fms\n".format(s.fps, s.depthMs, s.yoloMs))
        append(s.note)
    }

    private fun render(s: SceneState): String {
        val summary = buildString {
            append("mock=${AppGraph.mockSceneProducer.isEnabled()}  ")
            append("phoneHaptics=${AppGraph.phoneHaptics.isEnabled()}  ")
            append("belt=${AppGraph.beltClient.isConnected} drive=${AppGraph.beltHaptics.isEnabled()}\n")
            append("zones L/C/R = %.2f / %.2f / %.2f\n".format(s.depth.left, s.depth.center, s.depth.right))
            append("pathClear=${s.pathClear}  conf=%.2f\n".format(s.conf))
            append("belt packet=${s.belt}\n")
            if (s.ocr.present) append("ocr=\"${s.ocr.text}\"\n")
            append("\n")
        }
        return summary + gson.toJson(s)
    }

    private fun startDashboardSocket() {
        socket = SceneSocket(AppGraph.sceneBus).also { it.launch(AppGraph.scope) }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        socket?.shutdown()
        AppGraph.speechInput.shutdown()   // release the recognizer (recreated on next ask)
        // CameraX unbinds with the lifecycle automatically; fully stop the pipeline
        // (close models, free the executor's work) only when the app is finishing.
        if (isFinishing) {
            AppGraph.visionPipeline.stop()
            AppGraph.tts.shutdown()
            AppGraph.ocrEngine.close()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SixthSenseScene"
    }
}
