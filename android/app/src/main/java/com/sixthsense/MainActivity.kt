package com.sixthsense

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
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
import com.sixthsense.vision.VisionStatus
import com.sixthsense.ws.SceneSocket
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        setContentView(buildUi())
        observeScene()
        observeVisionStatus()
        startDashboardSocket()
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

        // Operator camera preview (the blind user does not look at this).
        previewView = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (240 * resources.displayMetrics.density).toInt(),
            )
        }
        root.addView(previewView)

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
        root.addView(button(getString(R.string.btn_test_left)) {
            AppGraph.beltClient.send(byteArrayOf(200.toByte(), 0, 0, 0))
        })
        root.addView(button(getString(R.string.btn_test_center)) {
            AppGraph.beltClient.send(byteArrayOf(0, 200.toByte(), 0, 0))
        })
        root.addView(button(getString(R.string.btn_test_right)) {
            AppGraph.beltClient.send(byteArrayOf(0, 0, 200.toByte(), 0))
        })
        root.addView(button(getString(R.string.btn_ask)) {
            val answer = AppGraph.voiceAgent.ask("what's ahead of me?")
            Log.i(TAG, "Voice answer: $answer")
            toast(answer)
        })

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
        AppGraph.phoneHaptics.setEnabled(true)
        testRunButton.text = getString(R.string.btn_test_run_stop)
        hapticsButton.text = getString(R.string.btn_haptics_on)
        if (!AppGraph.phoneHaptics.hasVibrator()) toast("This device has no vibration motor.")
        toast("Test run on — approach an object; the phone buzzes toward it.")
    }

    private fun stopFullTestRun() {
        testRunActive = false
        AppGraph.phoneHaptics.setEnabled(false)
        AppGraph.visionPipeline.stop()
        testRunButton.text = getString(R.string.btn_test_run_start)
        hapticsButton.text = getString(R.string.btn_haptics_off)
        toast("Test run off.")
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
            }
        }
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
            append("haptics=${AppGraph.phoneHaptics.isEnabled()}  ")
            append("belt=${AppGraph.beltClient.isConnected}\n")
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
        // CameraX unbinds with the lifecycle automatically; fully stop the pipeline
        // (close models, free the executor's work) only when the app is finishing.
        if (isFinishing) AppGraph.visionPipeline.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SixthSenseScene"
    }
}
