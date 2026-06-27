package com.sixthsense.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure ExecuTorch output decoders. No Android, no device —
 * synthetic FloatArrays exercise the layout/coordinate math that is easy to get
 * subtly wrong (channel-major YOLO, inverse-depth direction, zone thirds).
 */
class DecodersTest {

    /** Build a [1,84,8400] channel-major YOLO output with a single planted box. */
    private fun yoloOutputWith(
        anchor: Int, cx: Float, cy: Float, w: Float, h: Float, classId: Int, score: Float,
    ): FloatArray {
        val n = YoloDecoder.ANCHORS_640
        val out = FloatArray(YoloDecoder.ATTRS * n)
        out[0 * n + anchor] = cx
        out[1 * n + anchor] = cy
        out[2 * n + anchor] = w
        out[3 * n + anchor] = h
        out[(4 + classId) * n + anchor] = score
        return out
    }

    @Test
    fun yolo_decodes_channel_major_box_with_correct_class_and_center() {
        val out = yoloOutputWith(anchor = 4200, cx = 320f, cy = 320f, w = 100f, h = 100f, classId = 0, score = 0.9f)
        val dets = YoloDecoder.decode(out, inputSize = 640, confThresh = 0.25f)
        assertEquals(1, dets.size)
        val d = dets[0]
        assertEquals(0, d.classId)                       // "person"
        assertEquals(0.9f, d.score, 1e-4f)
        assertEquals(320f, (d.x1 + d.x2) / 2f, 1e-3f)    // center x
        assertEquals("person", COCO_LABELS[d.classId])
    }

    @Test
    fun yolo_thresholds_out_low_confidence() {
        val out = yoloOutputWith(anchor = 10, cx = 100f, cy = 100f, w = 20f, h = 20f, classId = 5, score = 0.10f)
        assertTrue(YoloDecoder.decode(out, confThresh = 0.25f).isEmpty())
    }

    @Test
    fun yolo_nms_suppresses_overlapping_duplicate() {
        val n = YoloDecoder.ANCHORS_640
        val out = FloatArray(YoloDecoder.ATTRS * n)
        // Two near-identical boxes on different anchors -> NMS keeps the higher score.
        for ((anchor, score) in listOf(100 to 0.9f, 101 to 0.7f)) {
            out[0 * n + anchor] = 300f
            out[1 * n + anchor] = 300f
            out[2 * n + anchor] = 80f
            out[3 * n + anchor] = 80f
            out[(4 + 2) * n + anchor] = score   // class "car"
        }
        val dets = YoloDecoder.decode(out, iouThresh = 0.45f)
        assertEquals(1, dets.size)
        assertEquals(0.9f, dets[0].score, 1e-4f)
    }

    @Test
    fun zone_thirds_map_center_x_correctly() {
        assertEquals("left", YoloDecoder.zoneForCenterX(50f, 640))
        assertEquals("center", YoloDecoder.zoneForCenterX(320f, 640))
        assertEquals("right", YoloDecoder.zoneForCenterX(600f, 640))
    }

    @Test
    fun depth_inverse_map_makes_closest_band_highest_nearness() {
        val w = 30; val h = 30
        val depth = FloatArray(w * h)
        // Inverse depth: LARGER = CLOSER. Plant a close obstacle on the LEFT.
        for (r in 0 until h) {
            for (c in 0 until w) {
                depth[r * w + c] = when {
                    c < w / 3 -> 1.0f       // left: closest
                    c < 2 * w / 3 -> 0.2f   // center
                    else -> 0.1f            // right: farthest
                }
            }
        }
        val zones = DepthDecoder.toZones(depth, w, h)
        assertEquals(1.0f, zones.left, 1e-3f)     // normalized nearest
        assertEquals(0.0f, zones.right, 1e-3f)    // normalized farthest
        assertTrue("center between right and left", zones.center > zones.right && zones.center < zones.left)
    }

    @Test
    fun depth_band_percentile_handles_empty_band() {
        val depth = FloatArray(100) { it.toFloat() }
        assertEquals(0f, DepthDecoder.bandPercentile(depth, 10, 5, 5, 0, 10, 0.9f), 0f)
    }

    @Test
    fun no_depth_nearness_grows_with_box_size_and_maps_zone() {
        // A big right-side box (closer) should yield higher nearness than a small one.
        val small = RawDet(x1 = 500f, y1 = 300f, x2 = 540f, y2 = 340f, score = 0.8f, classId = 2)
        val big = RawDet(x1 = 420f, y1 = 200f, x2 = 640f, y2 = 480f, score = 0.8f, classId = 2)
        val objs = SceneAssembler.toDetectedObjectsNoDepth(listOf(small, big), yoloInput = 640)
        assertEquals(2, objs.size)
        val smallObj = objs[0]; val bigObj = objs[1]
        assertEquals("right", bigObj.zone)
        assertTrue("bigger box => nearer", bigObj.nearness > smallObj.nearness)
        assertTrue(bigObj.nearness in 0f..1f)
    }
}
