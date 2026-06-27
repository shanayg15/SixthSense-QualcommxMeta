package com.sixthsense.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.sixthsense.core.DetectedObj

/**
 * Transparent AR overlay drawn on top of the camera [androidx.camera.view.PreviewView].
 * Outlines each detected object with a box that turns **green → yellow → red** as the
 * user gets closer (by nearness). When a box goes RED ([RED_THRESHOLD]) the object is
 * "too close" — the operator screen shows red and the app fires a directional buzz
 * (wired in MainActivity).
 *
 * Boxes are normalized [0,1] in the upright frame; we scale them to the view bounds.
 * (FILL mapping; for pixel-perfect alignment under PreviewView crop, tune on-device.)
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    @Volatile
    private var detections: List<DetectedObj> = emptyList()

    private val density = resources.displayMetrics.density
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
    }
    private val rect = RectF()

    /** Update with the latest scene's objects (only those carrying a box are drawn). */
    fun setDetections(objects: List<DetectedObj>) {
        detections = objects.filter { it.box != null }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        for (d in detections) {
            val b = d.box ?: continue
            rect.set(b.x1 * w, b.y1 * h, b.x2 * w, b.y2 * h)
            val color = colorFor(d.nearness)
            boxPaint.color = color
            canvas.drawRect(rect, boxPaint)

            val text = "${d.label} ${(d.nearness * 100).toInt()}%"
            val tw = labelText.measureText(text)
            val th = labelText.textSize
            val pad = 4f * density
            val ty = (rect.top - pad).coerceAtLeast(th + pad)
            labelBg.color = (color and 0x00FFFFFF) or 0xCC000000.toInt() // translucent tint of box color
            canvas.drawRect(rect.left, ty - th - pad, rect.left + tw + 2 * pad, ty + pad, labelBg)
            canvas.drawText(text, rect.left + pad, ty - pad / 2, labelText)
        }
    }

    private fun colorFor(nearness: Float): Int = when {
        nearness >= RED_THRESHOLD -> RED
        nearness >= YELLOW_THRESHOLD -> YELLOW
        else -> GREEN
    }

    companion object {
        /** Object is "too close" -> box turns red and the phone vibrates. Tune on course. */
        const val RED_THRESHOLD = 0.70f
        /** Object is approaching. */
        const val YELLOW_THRESHOLD = 0.45f

        private val GREEN = Color.parseColor("#22DD22")
        private val YELLOW = Color.parseColor("#FFC400")
        private val RED = Color.parseColor("#FF2A2A")
    }
}
