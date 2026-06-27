package com.sixthsense.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import org.pytorch.executorch.Tensor

/** Per-model input normalization. */
enum class Norm { IMAGENET, SCALE_0_1 }

/**
 * Converts an RGBA_8888 [ImageProxy] into a single **upright, read-only** ARGB
 * [Bitmap] (rotation applied, row-padding removed). Done ONCE per frame on the
 * analyzer thread; the resulting bitmap is then shared with the depth + YOLO
 * converters, which run on different threads. Reading a Bitmap from multiple
 * threads is safe (pixel data is immutable on read), so the two models can build
 * their tensors in parallel from this one bitmap with no race on the camera buffer.
 */
object FrameBitmap {
    fun upright(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * image.width
        val paddedWidth = image.width + rowPadding / plane.pixelStride
        val raw = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        raw.copyPixelsFromBuffer(plane.buffer)
        val cropped = if (rowPadding == 0) raw
        else Bitmap.createBitmap(raw, 0, 0, image.width, image.height)
        val degrees = image.imageInfo.rotationDegrees
        if (degrees == 0) return cropped
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, m, true)
    }
}

/**
 * Builds a normalized NCHW float [Tensor] for a square ExecuTorch model input from
 * a shared upright [Bitmap] (see [FrameBitmap]). ImageNet norm for Depth-Anything,
 * 1/255 for YOLO.
 *
 * NOT thread-safe within a single instance (reuses [pixels]/[chw]) — use one
 * instance per analyzer thread. Two DIFFERENT instances (depth, yolo) may call
 * [toTensor] on the SAME source bitmap concurrently: each owns its buffers and only
 * READS the bitmap, so that is safe.
 *
 * @param size model input side (518 for depth, 640 for YOLO).
 */
class FrameToTensor(private val size: Int, private val norm: Norm) {

    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)
    private val pixels = IntArray(size * size)
    private val shape = longArrayOf(1, 3, size.toLong(), size.toLong())
    private val chw = FloatArray(3 * size * size)

    fun toTensor(src: Bitmap): Tensor {
        val square = if (src.width == size && src.height == size) src
        else Bitmap.createScaledBitmap(src, size, size, true)
        square.getPixels(pixels, 0, size, 0, 0, size, size)
        if (square !== src) square.recycle()

        val area = size * size
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
}
