package com.sixthsense.voice

import android.util.Log
import com.sixthsense.core.SceneState
import com.sixthsense.core.SceneBus
import java.util.concurrent.Executors

/**
 * On-device voice agent. Keyword intent routing over the current [SceneState] with
 * a deterministic rule-based answer that ALWAYS works, plus an optional **on-device
 * LLM** ([LlmEngine], ExecuTorch-hosted Qwen) for natural, scene-grounded answers to
 * open-ended "what's ahead?" questions. Fully offline — no cloud, no external LLM.
 *
 * - [ask] is synchronous and rule-based (safe on the UI thread).
 * - [askAsync] uses the LLM when ready (it blocks, so it runs on a worker thread)
 *   and falls back to the rule-based answer otherwise.
 */
class VoiceAgent(
    private val bus: SceneBus,
    private val llm: LlmEngine? = null,
) {

    enum class Intent { SCENE, OCR, FIND, CLEAR }

    /**
     * Optional sink for the dashboard bridge: invoked with (question, intent,
     * answer) on every answer so the operator dashboard can display the agent's
     * interaction. Wired in MainActivity to SceneSocket.updateVoice.
     */
    var onAnswer: ((String, String, String) -> Unit)? = null

    /**
     * Optional on-device OCR runner: runs text recognition on the CURRENT camera frame
     * and returns the recognized text. Wired in MainActivity (ML Kit) so the spoken
     * "read that sign" intent actually runs OCR live instead of reading a stale result.
     */
    var ocrRunner: ((result: (String) -> Unit) -> Unit)? = null

    private val genExecutor = Executors.newSingleThreadExecutor()

    /**
     * Answer asynchronously. For open-ended SCENE questions, uses the on-device LLM
     * (grounded in the scene) when available; everything else (and any failure) falls
     * back to the rule-based answer. [onResult] is invoked on a worker thread.
     */
    fun askAsync(question: String, onResult: (String) -> Unit = {}) {
        val scene = bus.state.value
        val intent = route(question)
        // "Read that sign": run on-device OCR FRESH on the current frame, then answer.
        val ocr = ocrRunner
        if (intent == Intent.OCR && ocr != null) {
            ocr { text ->
                val answer = if (text.isNotBlank()) "The sign says: $text." else "I don't see readable text."
                Log.i(TAG, "Q='$question' -> A(ocr)='$answer'")
                onAnswer?.invoke(question, intent.name, answer)
                onResult(answer)
            }
            return
        }
        val engine = llm
        if (intent != Intent.SCENE || engine == null || !engine.isReady) {
            val a = ask(question)
            onResult(a)
            return
        }
        genExecutor.execute {
            val prompt = engine.buildPrompt(SYSTEM_PROMPT, sceneSummary(scene, question))
            val llmAnswer = engine.generate(prompt)?.takeIf { it.isNotBlank() }
            val answer = llmAnswer ?: ruleAnswer(intent, scene)  // graceful fallback
            Log.i(TAG, "Q='$question' -> A(${if (llmAnswer != null) "llm" else "rule"})='$answer'")
            onAnswer?.invoke(question, intent.name, answer)
            onResult(answer)
        }
    }

    fun shutdown() = genExecutor.shutdown()

    /** Compact, grounded scene description for the LLM prompt (never invents objects). */
    private fun sceneSummary(s: SceneState, question: String): String {
        fun obj(zone: String) = s.objects.firstOrNull { it.zone == zone }?.label
        fun band(v: Float) = when {
            v >= 0.7f -> "near"; v >= 0.45f -> "mid"; else -> "far"
        }
        fun line(zone: String, depth: Float): String {
            val label = obj(zone) ?: if (depth >= 0.55f) "obstacle" else "clear"
            return "$label (${band(depth)})"
        }
        return buildString {
            append("Scene:\n")
            append("  ahead: ").append(line("center", s.depth.center)).append('\n')
            append("  left:  ").append(line("left", s.depth.left)).append('\n')
            append("  right: ").append(line("right", s.depth.right)).append('\n')
            append("  text:  ").append(if (s.ocr.present) s.ocr.text else "none").append('\n')
            if (s.depth.curbAhead || s.depth.stepDown) append("  note:  curb or step ahead\n")
            append("Question: ").append(question)
        }
    }

    private fun ruleAnswer(intent: Intent, scene: SceneState): String = when (intent) {
        Intent.SCENE -> describeScene(scene)
        Intent.OCR -> if (scene.ocr.present && scene.ocr.text.isNotBlank())
            "The sign says: ${scene.ocr.text}." else "I don't see readable text."
        Intent.FIND -> findExit(scene)
        Intent.CLEAR -> if (scene.pathClear && scene.conf >= 0.4f)
            "Path appears clear, continue forward."
        else "I'm not sure the path is clear, proceed carefully."
    }

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
        private const val SYSTEM_PROMPT =
            "You are SixthSense, a navigation assistant for a blind user walking. " +
                "Answer in ONE short spoken sentence, concrete and directional (left, right, ahead, steps). " +
                "If uncertain, say so and advise caution. Never invent objects that are not listed."
    }
}
