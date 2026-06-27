package com.sixthsense.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

/**
 * Converts an RGBA_8888 [ImageProxy] to an **NHWC** float array `[1,size,size,3]`
 * (interleaved R,G,B per pixel), scaled to [0,1]. This is the input layout the
 * Qualcomm-AI-Hub LiteRT models expect.
 *
 * No ImageNet mean/std is applied: the AI Hub Depth-Anything graph bakes the
 * standardization in, and YOLO uses plain /255. (This differs from the old
 * ExecuTorch path, which fed CHW with ImageNet norm for depth.)
 *
 * NOT thread-safe (reuses buffers) — one instance per analyzer thread.
 */
class LiteRtFrameConverter(private val size: Int) {

    private val pixels = IntArray(size * size)
    private val nhwc = FloatArray(size * size * 3)

    /** @return reused FloatArray of length size*size*3, NHWC, RGB, [0,1]. */
    fun toNHWC(image: ImageProxy): FloatArray {
        val square = squareResize(rotate(imageProxyToBitmap(image), image.imageInfo.rotationDegrees))
        square.getPixels(pixels, 0, size, 0, 0, size, size)
        var o = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            nhwc[o++] = ((p shr 16) and 0xFF) / 255f  // R
            nhwc[o++] = ((p shr 8) and 0xFF) / 255f   // G
            nhwc[o++] = (p and 0xFF) / 255f           // B
        }
        return nhwc
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * image.width
        val paddedWidth = image.width + rowPadding / plane.pixelStride
        val bmp = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        bmp.copyPixelsFromBuffer(plane.buffer)
        return if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
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
