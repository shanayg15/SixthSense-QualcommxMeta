package com.sixthsense.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the AI Hub (LiteRT) YOLO decoder: the 3-tensor pre-decoded form
 * (boxes/scores/class_idx) needs only NMS, and the tensors are identified by
 * shape + value range so output ordering doesn't matter.
 */
class LiteRtDecodersTest {

    private val n = YoloDecoder.ANCHORS_640

    private fun threeTensor(
        anchor: Int, x1: Float, y1: Float, x2: Float, y2: Float, cls: Int, score: Float,
    ): Triple<FloatArray, FloatArray, FloatArray> {
        val boxes = FloatArray(n * 4)
        val scores = FloatArray(n)
        val classIdx = FloatArray(n)
        boxes[anchor * 4] = x1; boxes[anchor * 4 + 1] = y1
        boxes[anchor * 4 + 2] = x2; boxes[anchor * 4 + 3] = y2
        scores[anchor] = score
        classIdx[anchor] = cls.toFloat()
        return Triple(boxes, scores, classIdx)
    }

    @Test
    fun decodes_predecoded_three_tensor_output() {
        val (boxes, scores, classIdx) = threeTensor(7, 100f, 120f, 200f, 260f, cls = 2, score = 0.9f)
        val dets = LiteRtYolo.decode(listOf(boxes, scores, classIdx), inputSize = 640)
        assertEquals(1, dets.size)
        val d = dets[0]
        assertEquals(2, d.classId)               // car
        assertEquals(0.9f, d.score, 1e-4f)
        assertEquals(100f, d.x1, 1e-3f)
        assertEquals(260f, d.y2, 1e-3f)
    }

    @Test
    fun zone_follows_box_center() {
        val (b, s, c) = threeTensor(3, 10f, 10f, 60f, 60f, cls = 0, score = 0.8f) // cx=35 -> left
        val dets = LiteRtYolo.decode(listOf(b, s, c))
        assertEquals("left", YoloDecoder.zoneForCenterX((dets[0].x1 + dets[0].x2) / 2f, 640))
    }

    @Test
    fun tensor_order_is_disambiguated_by_range() {
        // Pass class_idx BEFORE scores; decoder must still identify them by value range.
        val (boxes, scores, classIdx) = threeTensor(5, 50f, 50f, 150f, 150f, cls = 17, score = 0.7f)
        val dets = LiteRtYolo.decode(listOf(boxes, classIdx, scores), inputSize = 640)
        assertEquals(1, dets.size)
        assertEquals(17, dets[0].classId)
        assertEquals(0.7f, dets[0].score, 1e-4f)
    }

    @Test
    fun below_threshold_is_dropped() {
        val (b, s, c) = threeTensor(9, 10f, 10f, 40f, 40f, cls = 1, score = 0.1f)
        assertTrue(LiteRtYolo.decode(listOf(b, s, c), confThresh = 0.25f).isEmpty())
    }

    @Test
    fun single_tensor_falls_back_to_raw_decoder() {
        // Non-default raw [1,84,8400] export -> handled by YoloDecoder.
        val raw = FloatArray(YoloDecoder.ATTRS * n)
        raw[0 * n + 11] = 320f; raw[1 * n + 11] = 320f; raw[2 * n + 11] = 80f; raw[3 * n + 11] = 80f
        raw[(4 + 0) * n + 11] = 0.95f
        val dets = LiteRtYolo.decode(listOf(raw), inputSize = 640)
        assertEquals(1, dets.size)
        assertEquals(0, dets[0].classId)
    }
}
