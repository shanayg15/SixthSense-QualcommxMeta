package com.sixthsense.voice

import android.util.Log
import com.sixthsense.core.SceneState
import com.sixthsense.core.SceneBus

/**
 * On-device voice agent placeholder. In starter mode it does keyword intent
 * routing and generates a deterministic, rule-based answer from the current
 * [SceneState], then LOGS it (it does not pretend a full model exists).
 *
 * TODO(voice):
 *   - Whisper `.pte` speech-to-text (push-to-talk capture → transcript).
 *   - Llama `.pte` 1B for natural single-sentence answers from the scene summary.
 *   - Android TextToSpeech (offline) for spoken output.
 * All of the above must run on-device — no cloud, no external LLM.
 */
class VoiceAgent(private val bus: SceneBus) {

    enum class Intent { SCENE, OCR, FIND, CLEAR }

    /**
     * Optional sink for the dashboard bridge: invoked with (question, intent,
     * answer) on every [ask] so the operator dashboard can display the agent's
     * interaction. Wired in MainActivity to SceneSocket.updateVoice.
     */
    var onAnswer: ((String, String, String) -> Unit)? = null

    /** Answer a question from the current scene. Returns the spoken-answer text. */
    fun ask(question: String): String {
        val scene = bus.state.value
        val intent = route(question)
        val answer = when (intent) {
            Intent.SCENE -> describeScene(scene)
            Intent.OCR -> if (scene.ocr.present && scene.ocr.text.isNotBlank())
                "The sign says: ${scene.ocr.text}." else "I don't see readable text."
            Intent.FIND -> findExit(scene)
            Intent.CLEAR -> if (scene.pathClear && scene.conf >= 0.4f)
                "Path appears clear, continue forward."
            else "I'm not sure the path is clear, proceed carefully."
        }
        Log.i(TAG, "Q='$question' -> A='$answer'")
        // TODO(voice): speak `answer` via offline Android TextToSpeech.
        onAnswer?.invoke(question, intent.name, answer)
        return answer
    }

    private fun route(text: String): Intent {
        val x = text.lowercase()
        return when {
            Regex("ahead|in front|what.?s there|around me|what do you see").containsMatchIn(x) -> Intent.SCENE
            Regex("read|sign|say|text|what does it say").containsMatchIn(x) -> Intent.OCR
            Regex("exit|door|way out|find").containsMatchIn(x) -> Intent.FIND
            Regex("clear|safe|walk").containsMatchIn(x) -> Intent.CLEAR
            else -> Intent.SCENE
        }
    }

    private fun describeScene(s: SceneState): String {
        if (s.conf < 0.4f) return "I'm not sure what's ahead, proceed carefully."
        val d = s.depth
        if (d.curbAhead || d.stepDown) return "Curb or step ahead, slow down."
        val near = 0.55f
        return when {
            d.center >= near -> "Obstacle ahead, move slightly left or right."
            d.left >= near && d.right >= near -> "Obstacles on both sides, go straight slowly."
            d.left >= near -> "Obstacle on your left, stay right."
            d.right >= near -> "Obstacle on your right, stay left."
            s.pathClear -> "Path appears clear, continue forward."
            else -> "Proceed carefully."
        }
    }

    private fun findExit(s: SceneState): String {
        if (s.ocr.present && s.ocr.text.uppercase().contains("EXIT")) {
            val zone = s.objects.firstOrNull { it.label.contains("sign", true) }?.zone ?: "ahead"
            return "Exit sign is $zone."
        }
        val door = s.objects.firstOrNull { it.label.contains("door", true) }
        return if (door != null) "There is a door on your ${door.zone}." else "I don't see the exit yet."
    }

    companion object {
        private const val TAG = "SixthSenseMCP"
    }
}
