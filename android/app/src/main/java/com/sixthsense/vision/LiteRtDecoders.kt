package com.sixthsense.vision

import kotlin.math.roundToInt

/**
 * Decoder for the Qualcomm-AI-Hub YOLOv11 LiteRT output, which (by default) is
 * already DECODED into three tensors — unlike the raw ExecuTorch `[1,84,8400]`:
 *   boxes     [1,8400,4]  float  XYXY in input pixels (0..640)
 *   scores    [1,8400]    float  max-class confidence (already sigmoided)
 *   class_idx [1,8400]    uint8  argmax COCO class id 0..79
 * The app only runs NMS (no transpose, no argmax, no sigmoid).
 *
 * Output ordering across LiteRT graphs isn't guaranteed, so tensors are
 * identified by shape (boxes = the 4×-sized one) and value range (scores ≤ 1).
 * If a non-default raw single-tensor export is used instead, this falls back to
 * the channel-major [1,84,8400] [YoloDecoder].
 */
object LiteRtYolo {

    fun decode(
        outputs: List<FloatArray>,
        inputSize: Int = 640,
        confThresh: Float = 0.25f,
        iouThresh: Float = 0.45f,
    ): List<RawDet> = when {
        outputs.isEmpty() -> emptyList()
        outputs.size == 1 -> YoloDecoder.decode(outputs[0], inputSize, confThresh, iouThresh)
        else -> decodeThreeTensor(outputs, confThresh, iouThresh)
    }

    private fun decodeThreeTensor(
        outputs: List<FloatArray>,
        confThresh: Float,
        iouThresh: Float,
    ): List<RawDet> {
        val n = outputs.minOf { it.size }                 // anchor count (8400 @ 640)
        if (n == 0) return emptyList()
        val boxes = outputs.firstOrNull { it.size == 4 * n } ?: return emptyList()
        val perAnchor = outputs.filter { it !== boxes && it.size == n }
        if (perAnchor.isEmpty()) return emptyList()

        // scores = the per-anchor tensor whose values stay within [0,1]; the other
        // (argmax class ids 0..79) is class_idx. Falls back to declared order.
        val scores: FloatArray
        val classIdx: FloatArray?
        if (perAnchor.size >= 2) {
            // scores are sigmoided confidences (≤1); class_idx are integer ids (0..79).
            val (a, b) = perAnchor[0] to perAnchor[1]
            val aMax = maxOf(a); val bMax = maxOf(b)
            if (aMax <= 1.001f && bMax > 1.001f) { scores = a; classIdx = b }
            else if (bMax <= 1.001f && aMax > 1.001f) { scores = b; classIdx = a }
            else { scores = a; classIdx = b }            // ambiguous -> --output_names order
        } else {
            scores = perAnchor[0]; classIdx = null
        }

        val dets = ArrayList<RawDet>()
        for (a in 0 until n) {
            val s = scores[a]
            if (s < confThresh) continue
            val cls = classIdx?.let { (it[a].roundToInt()) and 0xFF } ?: 0   // uint8 mask
            val o = a * 4
            dets.add(RawDet(boxes[o], boxes[o + 1], boxes[o + 2], boxes[o + 3], s, cls))
        }
        return YoloDecoder.nms(dets, iouThresh)
    }

    private fun maxOf(a: FloatArray): Float {
        var m = Float.NEGATIVE_INFINITY
        for (v in a) if (v > m) m = v
        return if (m == Float.NEGATIVE_INFINITY) 0f else m
    }
}
