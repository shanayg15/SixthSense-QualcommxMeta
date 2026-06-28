package com.sixthsense.vision

import com.sixthsense.core.BoundingBox
import com.sixthsense.core.DepthZones
import com.sixthsense.core.DetectedObj
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ExecuTorch model-output decoders for SixthSense. PURE Kotlin — no Android
 * imports — so the perception math is unit-testable on the JVM with synthetic
 * FloatArrays before it ever touches the phone.
 *
 * Tensors arrive from ExecuTorch as flat FloatArrays via
 * `module.forward(...)[0].toTensor().dataAsFloatArray`.
 *
 * Two models feed the SceneState contract:
 *  - Depth-Anything-V2-Small: 1x3x518x518 in -> inverse RELATIVE depth [518x518]
 *    (LARGER = CLOSER, unitless, per-frame scale -> normalize within the frame).
 *  - YOLOv11n (raw, no NMS): 1x3x640x640 in -> [1,84,8400] channel-major
 *    (4 bbox cxcywh in 0..640 + 80 COCO class scores; NO objectness).
 */

/** COCO-80 labels in the exact training order; argmax over the 80 scores indexes here. */
val COCO_LABELS: Array<String> = arrayOf(
    "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train",
    "truck", "boat", "traffic light", "fire hydrant", "stop sign",
    "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
    "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag",
    "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite",
    "baseball bat", "baseball glove", "skateboard", "surfboard",
    "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon",
    "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot",
    "hot dog", "pizza", "donut", "cake", "chair", "couch", "potted plant",
    "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote",
    "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
    "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
    "hair drier", "toothbrush",
)

/** A decoded detection in MODEL-INPUT pixel space (0..inputSize), xyxy. */
data class RawDet(
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val score: Float, val classId: Int,
)

object YoloDecoder {
    const val NUM_CLASSES = 80
    const val ATTRS = 4 + NUM_CLASSES            // 84
    const val ANCHORS_640 = 8400                 // 80² + 40² + 20² at 640 input

    fun decode(
        out: FloatArray,
        inputSize: Int = 640,
        confThresh: Float = 0.25f,
        iouThresh: Float = 0.45f,
        numAnchors: Int = ANCHORS_640,
    ): List<RawDet> {
        if (out.size < ATTRS * numAnchors) return emptyList()
        val kept = ArrayList<RawDet>()
        for (a in 0 until numAnchors) {
            var bestId = 0
            var bestScore = out[4 * numAnchors + a]
            for (c in 1 until NUM_CLASSES) {
                val s = out[(4 + c) * numAnchors + a]
                if (s > bestScore) { bestScore = s; bestId = c }
            }
            if (bestScore < confThresh) continue
            val cx = out[a]
            val cy = out[numAnchors + a]
            val w = out[2 * numAnchors + a]
            val h = out[3 * numAnchors + a]
            kept.add(RawDet(x1 = cx - w * 0.5f, y1 = cy - h * 0.5f, x2 = cx + w * 0.5f, y2 = cy + h * 0.5f, score = bestScore, classId = bestId))
        }
        return nms(kept, iouThresh)
    }

    fun nms(dets: List<RawDet>, iouThresh: Float): List<RawDet> {
        val sorted = dets.sortedByDescending { it.score }
        val out = ArrayList<RawDet>()
        val removed = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (removed[i]) continue
            val a = sorted[i]
            out.add(a)
            for (j in i + 1 until sorted.size) {
                if (!removed[j] && iou(a, sorted[j]) > iouThresh) removed[j] = true
            }
        }
        return out
    }

    private fun iou(a: RawDet, b: RawDet): Float {
        val ix1 = max(a.x1, b.x1); val iy1 = max(a.y1, b.y1)
        val ix2 = min(a.x2, b.x2); val iy2 = min(a.y2, b.y2)
        val inter = max(0f, ix2 - ix1) * max(0f, iy2 - iy1)
        val areaA = max(0f, a.x2 - a.x1) * max(0f, a.y2 - a.y1)
        val areaB = max(0f, b.x2 - b.x1) * max(0f, b.y2 - b.y1)
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun zoneForCenterX(centerX: Float, width: Int): String = when {
        centerX < width / 3f -> "left"
        centerX < 2f * width / 3f -> "center"
        else -> "right"
    }
}

object DepthDecoder {

    fun toZones(depth: FloatArray, w: Int, h: Int, pct: Float = 0.90f): DepthZones {
        val rowStart = h / 3
        val third = w / 3
        val leftP = bandPercentile(depth, w, rowStart, h, 0, third, pct)
        val centerP = bandPercentile(depth, w, rowStart, h, third, 2 * third, pct)
        val rightP = bandPercentile(depth, w, rowStart, h, 2 * third, w, pct)
        val lo = minOf(leftP, centerP, rightP)
        val hi = maxOf(leftP, centerP, rightP)
        val span = (hi - lo).let { if (it < 1e-6f) 1f else it }
        fun norm(v: Float) = ((v - lo) / span).coerceIn(0f, 1f)
        return DepthZones(left = norm(leftP), center = norm(centerP), right = norm(rightP), curbAhead = detectCurbAhead(depth, w, h), stepDown = false)
    }

    fun bandPercentile(depth: FloatArray, w: Int, r0: Int, r1: Int, c0: Int, c1: Int, pct: Float): Float {
        val vals = ArrayList<Float>(max(1, (r1 - r0) * (c1 - c0)))
        for (r in r0 until r1) { val base = r * w; for (c in c0 until c1) vals.add(depth[base + c]) }
        if (vals.isEmpty()) return 0f
        vals.sort()
        val idx = ((vals.size - 1) * pct).toInt().coerceIn(0, vals.size - 1)
        return vals[idx]
    }

    fun frameRange(depth: FloatArray, w: Int, h: Int): Pair<Float, Float> {
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        val r0 = h / 3
        for (r in r0 until h) { val base = r * w; for (c in 0 until w) { val v = depth[base + c]; if (v < lo) lo = v; if (v > hi) hi = v } }
        return if (lo > hi) 0f to 1f else lo to hi
    }

    fun detectCurbAhead(depth: FloatArray, w: Int, h: Int, gap: Int = 6, spikeRatio: Float = 0.18f): Boolean {
        val c0 = w / 3; val c1 = 2 * w / 3
        val r0 = (h * 2) / 3; val r1 = h - gap
        if (r1 <= r0) return false
        var maxGrad = 0f; var scaleSum = 0f; var scaleN = 0
        for (r in r0 until r1) {
            var rowGrad = 0f; var n = 0
            val baseA = r * w; val baseB = (r + gap) * w
            for (c in c0 until c1) { rowGrad += abs(depth[baseA + c] - depth[baseB + c]); scaleSum += depth[baseA + c]; scaleN++; n++ }
            if (n > 0) maxGrad = max(maxGrad, rowGrad / n)
        }
        val scale = if (scaleN > 0) abs(scaleSum / scaleN) else 1f
        val denom = if (scale < 1e-6f) 1f else scale
        return (maxGrad / denom) > spikeRatio
    }

    fun nearnessInBox(depth: FloatArray, w: Int, h: Int, bx1: Float, by1: Float, bx2: Float, by2: Float, frameLo: Float, frameHi: Float, pct: Float = 0.90f): Float {
        val c0 = bx1.toInt().coerceIn(0, w - 1)
        val c1 = bx2.toInt().coerceIn(c0 + 1, w)
        val r0 = by1.toInt().coerceIn(0, h - 1)
        val r1 = by2.toInt().coerceIn(r0 + 1, h)
        val p = bandPercentile(depth, w, r0, r1, c0, c1, pct)
        val span = (frameHi - frameLo).let { if (it < 1e-6f) 1f else it }
        return ((p - frameLo) / span).coerceIn(0f, 1f)
    }
}

object SceneAssembler {
    fun toDetectedObjects(dets: List<RawDet>, depth: FloatArray, depthW: Int, depthH: Int, yoloInput: Int = 640): List<DetectedObj> {
        if (dets.isEmpty()) return emptyList()
        val (lo, hi) = DepthDecoder.frameRange(depth, depthW, depthH)
        val mx = depthW.toFloat() / yoloInput; val my = depthH.toFloat() / yoloInput
        return dets.map { d ->
            val cx = (d.x1 + d.x2) * 0.5f
            val nearness = DepthDecoder.nearnessInBox(depth, depthW, depthH, d.x1 * mx, d.y1 * my, d.x2 * mx, d.y2 * my, lo, hi)
            DetectedObj(label = COCO_LABELS.getOrElse(d.classId) { "object" }, zone = YoloDecoder.zoneForCenterX(cx, yoloInput), nearness = nearness, conf = d.score, box = normBox(d, yoloInput))
        }
    }

    private fun normBox(d: RawDet, inputSize: Int): BoundingBox {
        val s = inputSize.toFloat()
        return BoundingBox(x1 = (d.x1 / s).coerceIn(0f, 1f), y1 = (d.y1 / s).coerceIn(0f, 1f), x2 = (d.x2 / s).coerceIn(0f, 1f), y2 = (d.y2 / s).coerceIn(0f, 1f))
    }

    const val AREA_GAIN = 3.0f

    fun toDetectedObjectsNoDepth(dets: List<RawDet>, yoloInput: Int = 640): List<DetectedObj> {
        if (dets.isEmpty()) return emptyList()
        val frameArea = yoloInput.toFloat() * yoloInput
        return dets.map { d ->
            val cx = (d.x1 + d.x2) * 0.5f
            val area = max(0f, d.x2 - d.x1) * max(0f, d.y2 - d.y1)
            val nearness = (area / frameArea * AREA_GAIN).coerceIn(0f, 1f)
            DetectedObj(label = COCO_LABELS.getOrElse(d.classId) { "object" }, zone = YoloDecoder.zoneForCenterX(cx, yoloInput), nearness = nearness, conf = d.score, box = normBox(d, yoloInput))
        }
    }
}
