package com.sixthsense.voice

import android.content.Context
import android.util.Log
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmModule
import java.io.File

/**
 * On-device generative LLM (Qwen2.5-0.5B) hosted with **ExecuTorch** — the
 * "intelligence stays on the phone" half of the hybrid (CV runs on Qualcomm AI
 * Hub / LiteRT; the LLM runs here via ExecuTorch's LlmModule). Fully offline.
 *
 * The runner consumes the HF `tokenizer.json` directly. `LlmModule.load()` and
 * `generate()` need real filesystem paths, so the `.pte` + tokenizer are copied
 * out of assets to filesDir once. Degrades gracefully: if the model isn't bundled
 * [isReady] stays false and the [com.sixthsense.voice.VoiceAgent] uses its
 * deterministic rule-based answers instead.
 *
 * generate() BLOCKS — only call [generate] from a background thread.
 */
class LlmEngine(private val context: Context) {

    @Volatile private var llm: LlmModule? = null
    @Volatile var isReady: Boolean = false
        private set

    fun isAvailable(): Boolean = assetPath(MODEL_ASSET) != null

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
     * Generate one answer (blocking) from a pre-built prompt, streaming chunks to
     * [onChunk]; returns the full text. Returns null if the model isn't ready.
     */
    fun generate(prompt: String, seqLen: Int = SEQ_LEN, onChunk: (String) -> Unit = {}): String? {
        val module = llm ?: return null
        val sb = StringBuilder()
        return try {
            module.generate(prompt, seqLen, object : LlmCallback {
                override fun onResult(result: String) { sb.append(result); onChunk(result) }
                override fun onStats(stats: String) { Log.d(TAG, "llm stats=$stats") }
                override fun onError(errorCode: Int, message: String) {
                    Log.w(TAG, "llm error $errorCode: $message")
                }
            }, /* echo = */ false)
            sb.toString().trim()
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

    fun stop() = runCatching { llm?.stop() }

    fun close() {
        runCatching { llm?.close() }
        llm = null
        isReady = false
    }

    private fun assetPath(name: String): String? =
        runCatching {
            val dir = name.substringBeforeLast('/', "")
            val f = name.substringAfterLast('/')
            if (context.assets.list(dir)?.contains(f) == true) name else null
        }.getOrNull()

    /** Copy assets/<name> to filesDir once; return absolute path, or null if absent. */
    private fun copyAsset(name: String): String? {
        if (assetPath(name) == null) return null
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
