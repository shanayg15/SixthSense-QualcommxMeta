package com.sixthsense.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import org.pytorch.executorch.Tensor

/** Per-model input normalization. */
enum class Norm { IMAGENET, SCALE_0_1 }

/**
 * Converts an RGBA_8888 [ImageProxy] into a normalized NCHW float [Tensor] for a
 * square ExecuTorch model input.
 *
 * Handles: RGBA row padding, sensor [ImageProxy.imageInfo].rotationDegrees, a plain
 * square resize (stretch — keeps box<->depth coordinate mapping a simple ratio, see
 * [SceneAssembler]), RGB channel order, and per-channel normalization
 * (ImageNet for Depth-Anything, 1/255 for YOLO).
 *
 * NOT thread-safe: reuses internal buffers, so use one instance per analyzer thread.
 *
 * @param size model input side (518 for depth, 640 for YOLO).
 */
class FrameToTensor(private val size: Int, private val norm: Norm) {

    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)
    private val pixels = IntArray(size * size)
    private val shape = longArrayOf(1, 3, size.toLong(), size.toLong())
    // Reused CHW float buffer. Tensor.fromBlob(float[], long[]) copies into its own
    // native buffer, so reusing this array across frames is safe (one thread).
    private val chw = FloatArray(3 * size * size)

    fun toTensor(image: ImageProxy): Tensor {
        val square = squareResize(rotate(imageProxyToBitmap(image), image.imageInfo.rotationDegrees))
        square.getPixels(pixels, 0, size, 0, 0, size, size)

        val area = size * size
        // CHW: R plane [0,area), G plane [area,2area), B plane [2area,3area).
        for (i in 0 until area) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            if (norm == Norm.IMAGENET) {
                chw[i] = (r - mean[0]) / std[0]
                chw[area + i] = (g - mean[1]) / std[1]
                chw[2 * area + i] = (b - mean[2]) / std[2]
            } else {
                chw[i] = r
                chw[area + i] = g
                chw[2 * area + i] = b
            }
        }
        return Tensor.fromBlob(chw, shape)
    }

    /** RGBA_8888 -> ARGB Bitmap, accounting for right-edge row padding. */
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowPadding = plane.rowStride - plane.pixelStride * image.width
        val paddedWidth = image.width + rowPadding / plane.pixelStride
        val bmp = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bmp.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) bmp
        else Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun squareResize(src: Bitmap): Bitmap =
        if (src.width == size && src.height == size) src
        else Bitmap.createScaledBitmap(src, size, size, true)
}
