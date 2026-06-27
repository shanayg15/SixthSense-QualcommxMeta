package com.sixthsense.voice

import android.content.Context
import android.util.Log
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmModule
import java.io.File

/**
 * On-device generative LLM (Qwen2.5) hosted with **ExecuTorch** — the
 * "intelligence stays on the phone" half. Fully offline; the same
 * `executorch-android` AAR that runs the `.pte` vision models also provides the
 * [LlmModule] text runner.
 *
 * The runner consumes the HF `tokenizer.json` directly. `LlmModule.load()` and
 * `generate()` need real filesystem paths, so the `.pte` + tokenizer are copied
 * out of assets to filesDir once. Degrades gracefully: if the model isn't bundled
 * [isReady] stays false and [VoiceAgent] uses its deterministic rule-based answers.
 *
 * Uses the 3-arg [LlmModule] constructor (default load mode) — NOT a config with
 * LOAD_MODE_MMAP, which aborts natively on this model. generate() BLOCKS — only
 * call it from a background thread.
 */
class LlmEngine(private val context: Context) {

    @Volatile private var llm: LlmModule? = null
    @Volatile var isReady: Boolean = false
        private set

    /** Copy assets → filesDir and load the model. Call once on a background thread. */
    @Synchronized
    fun load(): Boolean {
        if (isReady) return true
        val modelPath = copyAsset(MODEL_ASSET) ?: run {
            Log.i(TAG, "No $MODEL_ASSET in assets — LLM disabled, using rule-based answers.")
            return false
        }
        val tokPath = TOKENIZER_ASSETS.firstNotNullOfOrNull { copyAsset(it) } ?: run {
            Log.w(TAG, "qwen.pte present but tokenizer.json missing — LLM disabled.")
            return false
        }
        return try {
            val module = LlmModule(modelPath, tokPath, TEMPERATURE) // 3-arg => text decoder
            module.load()
            llm = module
            isReady = true
            Log.i(TAG, "ExecuTorch LLM loaded (qwen).")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "LLM load failed: ${e.message}")
            false
        }
    }

    /**
     * Generate one answer (blocking) from a pre-built prompt; returns cleaned text,
     * or null if the model isn't ready.
     */
    fun generate(prompt: String, seqLen: Int = SEQ_LEN): String? {
        val module = llm ?: return null
        val sb = StringBuilder()
        return try {
            module.generate(prompt, seqLen, object : LlmCallback {
                override fun onResult(result: String) { sb.append(result) }
                override fun onStats(stats: String) { Log.d(TAG, "llm stats=$stats") }
                override fun onError(errorCode: Int, message: String) {
                    Log.w(TAG, "llm error $errorCode: $message")
                }
            }, /* echo = */ false)
            clean(sb.toString())
        } catch (e: Throwable) {
            Log.w(TAG, "generate failed: ${e.message}")
            null
        }
    }

    /** Wrap a user turn in the Qwen2.5 chat template (the .pte applies no template). */
    fun buildPrompt(system: String, user: String): String = buildString {
        append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
        append("<|im_start|>user\n").append(user).append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }

    /** Strip ChatML control tokens that the runner echoes into the text. */
    private fun clean(text: String): String =
        text.substringBefore("<|im_end|>")
            .substringBefore("<|endoftext|>")
            .replace("<|im_start|>", "")
            .trim()

    fun stop() = runCatching { llm?.stop() }

    fun close() {
        runCatching { llm?.close() }
        llm = null
        isReady = false
    }

    /** Copy assets/<name> to filesDir once; return absolute path, or null if absent. */
    private fun copyAsset(name: String): String? {
        val dir = name.substringBeforeLast('/', "")
        val file = name.substringAfterLast('/')
        val present = runCatching { context.assets.list(dir)?.contains(file) == true }.getOrDefault(false)
        if (!present) return null
        val out = File(context.filesDir, name)
        return runCatching {
            if (!out.exists() || out.length() == 0L) {
                out.parentFile?.mkdirs()
                context.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
            out.absolutePath
        }.getOrNull()
    }

    companion object {
        private const val TAG = "SixthSenseScene"
        private const val TEMPERATURE = 0.3f          // navigation answers: low, factual
        private const val SEQ_LEN = 256
        private const val MODEL_ASSET = "models/qwen.pte"
        private val TOKENIZER_ASSETS = listOf(
            "models/qwen-tokenizer/tokenizer.json",
            "models/tokenizer.json",
        )
    }
}
