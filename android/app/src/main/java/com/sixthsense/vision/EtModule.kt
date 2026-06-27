package com.sixthsense.vision

import android.content.Context
import android.util.Log
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File

/**
 * Backend-AGNOSTIC ExecuTorch wrapper around one `.pte` model.
 *
 * The delegate (XNNPACK CPU vs Qualcomm QNN/Hexagon NPU) is baked into the `.pte`
 * at export time, so this code is byte-for-byte identical for both backends — a
 * QNN/NPU `.pte` is a drop-in replacement for the CPU one (see
 * docs/ondevice_vision_and_phone_haptics.md for the AAR swap). Nothing here picks
 * a backend; the runtime executes whatever delegate the `.pte` demands.
 *
 * `Module.load` needs a real filesystem path, and APK assets are not files, so the
 * `.pte` is copied out of `assets/` to `filesDir` once (guarded by an exists/size
 * check) and the absolute path is loaded with mmap to keep peak memory low. This is
 * required for an installed, airplane-mode app (the adb-push-to-/data/local/tmp
 * route from the docs is dev-only).
 */
class EtModule private constructor(
    private val module: Module,
    /** The asset name this was loaded from, for operator/debug logging. */
    val assetName: String,
) {

    /** A single output tensor flattened to floats plus its shape. */
    data class Out(val data: FloatArray, val shape: LongArray) {
        override fun equals(other: Any?): Boolean =
            other is Out && data.contentEquals(other.data) && shape.contentEquals(other.shape)
        override fun hashCode(): Int = 31 * data.contentHashCode() + shape.contentHashCode()
    }

    /** One forward pass. Returns output[0] as flat floats + its shape. */
    fun forward(input: Tensor): Out {
        val outputs: Array<EValue> = module.forward(EValue.from(input))
        val t = outputs[0].toTensor()
        return Out(t.dataAsFloatArray, t.shape())
    }

    fun close() {
        try {
            module.destroy()
        } catch (e: Throwable) {
            Log.w(TAG, "module.destroy() failed for $assetName: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "SixthSenseScene"

        /**
         * Try to load the first present `.pte` among [candidateAssets] (e.g.
         * "models/depth.pte", "models/depth_anything_v2.pte"). Returns null if none
         * of the assets exist or every load attempt fails — the caller then degrades
         * gracefully (depth-only, or mock mode) instead of crashing. This is what
         * lets the app build and run on a machine with NO model binaries.
         */
        fun tryLoad(context: Context, candidateAssets: List<String>): EtModule? {
            for (asset in candidateAssets) {
                val path = copyAssetIfPresent(context, asset) ?: continue
                try {
                    val module = Module.load(path, Module.LOAD_MODE_MMAP)
                    Log.i(TAG, "Loaded ExecuTorch model from asset '$asset' -> $path")
                    return EtModule(module, asset)
                } catch (e: Throwable) {
                    // Most common cause: the .pte's required backend isn't registered
                    // in the linked AAR (e.g. a QNN .pte against the XNNPACK AAR).
                    Log.w(TAG, "Module.load failed for '$asset': ${e.message}")
                }
            }
            Log.i(TAG, "No loadable model among $candidateAssets (ok — falling back).")
            return null
        }

        /** Copy assets/<name> to filesDir once; return its absolute path, or null if absent. */
        private fun copyAssetIfPresent(context: Context, assetName: String): String? {
            val outFile = File(context.filesDir, assetName)
            return try {
                context.assets.open(assetName).use { input ->
                    if (!outFile.exists() || outFile.length() == 0L) {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                outFile.absolutePath
            } catch (e: Throwable) {
                // Asset not bundled — expected on dev machines without the .pte files.
                null
            }
        }
    }
}
