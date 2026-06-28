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

    /** Number of vibrating (amplitude>0) segments = pulse count per cycle. */
    private fun pulseCount(sig: DirectionalEncoding.HapticSignature) =
        sig.amplitudes.count { it > 0 }

    @Test
    fun left_dominant_is_one_pulse() {
        val sig = DirectionalEncoding.encode(listOf(200, 0, 0, 0))!!
        assertEquals(Direction.LEFT, sig.direction)
        assertEquals(1, pulseCount(sig))   // L = 1 tap
    }

    @Test
    fun center_dominant_is_two_pulses() {
        val sig = DirectionalEncoding.encode(listOf(0, 200, 0, 0))!!
        assertEquals(Direction.CENTER, sig.direction)
        assertEquals(2, pulseCount(sig))   // C = 2 taps
    }

    @Test
    fun right_dominant_is_three_pulses() {
        val sig = DirectionalEncoding.encode(listOf(0, 0, 200, 0))!!
        assertEquals(Direction.RIGHT, sig.direction)
        assertEquals(3, pulseCount(sig))   // R = 3 taps
    }

    @Test
    fun pulse_count_strictly_increases_left_center_right() {
        val l = pulseCount(DirectionalEncoding.encode(listOf(200, 0, 0, 0))!!)
        val c = pulseCount(DirectionalEncoding.encode(listOf(0, 200, 0, 0))!!)
        val r = pulseCount(DirectionalEncoding.encode(listOf(0, 0, 200, 0))!!)
        assertTrue("L<C<R pulse counts ($l,$c,$r)", l < c && c < r)
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
    fun approach_pattern_is_escalating_and_directionless() {
        val sig = DirectionalEncoding.encode(listOf(120, 120, 120, 3))!!
        assertEquals(Direction.APPROACH, sig.direction)
        assertTrue("more than two taps", pulseCount(sig) >= 4)
        assertTrue(sig.amplitudes.last() > sig.amplitudes[1])
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
