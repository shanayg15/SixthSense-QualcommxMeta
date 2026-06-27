package com.sixthsense.haptics

import com.sixthsense.haptics.DirectionalEncoding.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure directional-haptics encoding. Verifies that a belt
 * packet [L,C,R,pattern] maps to the right single-actuator signature so left /
 * center / right stay distinguishable and curb/caution override correctly.
 */
class DirectionalEncodingTest {

    @Test
    fun silent_packet_produces_no_signature() {
        assertNull(DirectionalEncoding.encode(listOf(0, 0, 0, 0)))
        // All-zero zones short-circuit BEFORE the pattern check, so even a curb/caution
        // pattern with no intensity stays silent (nothing to steer toward).
        assertNull(DirectionalEncoding.encode(listOf(0, 0, 0, 2)))
        assertNull(DirectionalEncoding.encode(listOf(0, 0, 0, 1)))
    }

    @Test
    fun left_dominant_is_left_short_then_long() {
        val sig = DirectionalEncoding.encode(listOf(200, 0, 0, 0))!!
        assertEquals(Direction.LEFT, sig.direction)
        // short pip then long buzz: timings = [gap, SHORT, midgap, LONG]
        assertEquals(4, sig.timings.size)
        assertTrue("short before long", sig.timings[1] < sig.timings[3])
    }

    @Test
    fun right_dominant_is_mirror_of_left() {
        val sig = DirectionalEncoding.encode(listOf(0, 0, 200, 0))!!
        assertEquals(Direction.RIGHT, sig.direction)
        // long buzz then short pip: mirror of LEFT
        assertTrue("long before short", sig.timings[1] > sig.timings[3])
    }

    @Test
    fun center_dominant_is_single_steady_block() {
        val sig = DirectionalEncoding.encode(listOf(0, 200, 0, 0))!!
        assertEquals(Direction.CENTER, sig.direction)
        assertEquals(2, sig.timings.size) // [gap, steady]
    }

    @Test
    fun curb_pattern_overrides_direction_with_full_amplitude_thumps() {
        val sig = DirectionalEncoding.encode(listOf(200, 0, 0, 2))!!
        assertEquals(Direction.CURB, sig.direction)
        assertEquals(DirectionalEncoding.MAX_AMPLITUDE, sig.amplitudes.max())
    }

    @Test
    fun caution_pattern_is_soft_and_directionless() {
        val sig = DirectionalEncoding.encode(listOf(0, 0, 200, 1))!!
        assertEquals(Direction.CAUTION, sig.direction)
        // softened amplitude, below the full-intensity mapping
        assertTrue(sig.amplitudes.max() < DirectionalEncoding.toAmplitude(200))
    }

    @Test
    fun amplitude_mapping_respects_perceptible_floor_and_ceiling() {
        assertEquals(DirectionalEncoding.MIN_PERCEPTIBLE, DirectionalEncoding.toAmplitude(0))
        assertTrue(DirectionalEncoding.toAmplitude(1) >= DirectionalEncoding.MIN_PERCEPTIBLE)
        assertEquals(DirectionalEncoding.MAX_AMPLITUDE, DirectionalEncoding.toAmplitude(255))
    }

    @Test
    fun all_on_off_segments_align_for_no_amplitude_control_fallback() {
        // The timing-only fallback (no amplitude control) relies on amplitudes being
        // 0 exactly where the timing array's off-segments are (even indices).
        for (packet in listOf(
            listOf(200, 0, 0, 0), listOf(0, 0, 200, 0), listOf(0, 200, 0, 0),
            listOf(200, 0, 0, 2), listOf(0, 0, 200, 1),
        )) {
            val sig = DirectionalEncoding.encode(packet)!!
            assertEquals(sig.timings.size, sig.amplitudes.size)
            for (i in sig.amplitudes.indices) {
                if (i % 2 == 0) assertEquals("off segment at $i for $packet", 0, sig.amplitudes[i])
                else assertTrue("on segment at $i for $packet", sig.amplitudes[i] > 0)
            }
        }
    }
}
