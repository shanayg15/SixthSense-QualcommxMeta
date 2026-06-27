package com.sixthsense.vision

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment

/**
 * Loads a Qualcomm-AI-Hub-exported `.tflite` (LiteRT) model and runs it on the
 * Qualcomm Hexagon **NPU** with automatic GPU→CPU fallback. This is the CV
 * backend for SixthSense (depth + object detection); the on-device LLM stays on
 * ExecuTorch (see [com.sixthsense.voice.LlmEngine]).
 *
 * AI Hub TFLite models are **NHWC** and may be quantized (uint8 with baked-in
 * scale/zero-point); the LiteRT [CompiledModel] typed `writeFloat`/`readFloat`
 * accessors handle the (de)quantization, so callers work in plain floats.
 *
 * Loads straight from `assets/` (no filesystem copy needed, unlike ExecuTorch).
 * Returns null from [tryLoad] when no model is bundled, so the app degrades
 * gracefully (mock mode) instead of crashing.
 */
class LiteRtModel private constructor(
    private val env: Environment,
    private val model: CompiledModel,
    /** Which accelerator actually bound: "npu" | "gpu" | "cpu" — for the operator UI. */
    val accelerator: String,
    val assetPath: String,
) : AutoCloseable {

    /**
     * One forward pass from a single NHWC float input. Returns each output tensor
     * as a flat FloatArray, in graph output order (YOLO = [boxes, scores, class_idx]).
     */
    fun run(input: FloatArray): List<FloatArray> {
        val inputs = model.createInputBuffers()
        val outputs = model.createOutputBuffers()
        try {
            require(inputs.isNotEmpty() && outputs.isNotEmpty()) { "model has no input/output tensors" }
            inputs[0].writeFloat(input)
            model.run(inputs, outputs)
            return outputs.map { it.readFloat() }
        } finally {
            inputs.forEach { runCatching { it.close() } }
            outputs.forEach { runCatching { it.close() } }
        }
    }

    override fun close() {
        runCatching { model.close() }
        runCatching { env.close() }
    }

    companion object {
        private const val TAG = "SixthSenseScene"

        /** Load the first bundled asset among [candidates] (e.g. "models/depth.tflite"). */
        fun tryLoad(context: Context, candidates: List<String>): LiteRtModel? {
            val env = runCatching { Environment.create(context) }.getOrElse {
                Log.w(TAG, "LiteRT Environment.create failed: ${it.message}"); return null
            }
            for (path in candidates) {
                if (!assetExists(context, path)) continue
                val (model, accel) = createWithFallback(context, path, env) ?: continue
                Log.i(TAG, "LiteRT loaded '$path' on $accel.")
                return LiteRtModel(env, model, accel, path)
            }
            runCatching { env.close() }
            Log.i(TAG, "No LiteRT model among $candidates (ok — falling back).")
            return null
        }

        /** NPU → GPU → CPU, returning the model + the accelerator that bound. */
        private fun createWithFallback(
            context: Context, path: String, env: Environment,
        ): Pair<CompiledModel, String>? {
            val attempts = listOf(
                "npu" to CompiledModel.Options(Accelerator.NPU, Accelerator.GPU),
                "gpu" to CompiledModel.Options(Accelerator.GPU),
                "cpu" to CompiledModel.Options(Accelerator.CPU),
            )
            for ((label, options) in attempts) {
                val model = runCatching { CompiledModel.create(context.assets, path, options, env) }
                    .onFailure { Log.w(TAG, "$label init failed for '$path': ${it.message}") }
                    .getOrNull()
                if (model != null) return model to label
            }
            return null
        }

        private fun assetExists(context: Context, path: String): Boolean {
            val dir = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/')
            return runCatching { context.assets.list(dir)?.contains(name) == true }.getOrDefault(false)
        }
    }
}
