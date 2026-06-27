package com.sixthsense.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that detected OBJECTS drive directional belt/haptic output — the fix for
 * "object detection detects something but nothing buzzes." A near object asserts a
 * buzz in its zone even when the depth map is flat/absent.
 */
class BeltMapperTest {

    private fun scene(objects: List<DetectedObj>, pathClear: Boolean = false) = SceneState(
        ts = 0L,
        depth = DepthZones(0f, 0f, 0f),
        objects = objects,
        pathClear = pathClear,
        conf = 0.85f,
    )

    @Test
    fun near_left_object_buzzes_left_only() {
        val p = BeltMapper.packetAsInts(scene(listOf(DetectedObj("chair", "left", 0.9f, 0.8f))))
        assertTrue("left buzzes", p[0] >= 90)
        assertEquals("center silent", 0, p[1])
        assertEquals("right silent", 0, p[2])
        assertEquals("steady", BeltMapper.PATTERN_STEADY, p[3])
    }

    @Test
    fun near_right_object_buzzes_right_only() {
        val p = BeltMapper.packetAsInts(scene(listOf(DetectedObj("pole", "right", 0.85f, 0.8f))))
        assertTrue("right buzzes", p[2] >= 90)
        assertEquals(0, p[0])
        assertEquals(0, p[1])
    }

    @Test
    fun far_object_below_threshold_does_not_buzz() {
        // nearness below OBJECT_NEAR_THRESHOLD -> no buzz (approach-to-trigger behavior).
        val p = BeltMapper.packetAsInts(scene(listOf(DetectedObj("car", "center", 0.2f, 0.8f))))
        assertEquals(listOf(0, 0, 0, BeltMapper.PATTERN_STEADY), p)
    }

    @Test
    fun closer_object_buzzes_harder() {
        val near = BeltMapper.packetAsInts(scene(listOf(DetectedObj("person", "center", 0.6f, 0.9f))))[1]
        val nearer = BeltMapper.packetAsInts(scene(listOf(DetectedObj("person", "center", 0.95f, 0.9f))))[1]
        assertTrue("nearer object = stronger buzz ($nearer > $near)", nearer > near)
    }

    @Test
    fun depth_and_object_reinforce_take_max() {
        // Depth says left is near; object also on left -> still a single strong left buzz.
        val s = SceneState(
            ts = 0L,
            depth = DepthZones(0.9f, 0f, 0f),
            objects = listOf(DetectedObj("chair", "left", 0.9f, 0.8f)),
            pathClear = false,
            conf = 0.85f,
        )
        val p = BeltMapper.packetAsInts(s)
        assertTrue(p[0] > 0)
        assertEquals(0, p[2])
    }
}
