package com.sixthsense.haptics

import kotlin.math.max

/**
 * PURE (no Android) logic that turns a belt packet `[L, C, R, pattern]` into a
 * directional vibration signature for the phone's single vibration motor. Kept
 * Android-free so the directional encoding is unit-testable on the JVM.
 *
 * A phone has ONE actuator (no true spatial left/right), so direction is encoded
 * TEMPORALLY by PULSE COUNT (far easier to feel on one motor than short-vs-long),
 * with the dominant zone (max of L/C/R) chosen per update:
 *   LEFT    = 1 pulse  per cycle   (· … · … ·)
 *   CENTER  = 2 pulses per cycle   (·· … ··)
 *   RIGHT   = 3 pulses per cycle   (··· … ···)
 *   CURB     = 2 hard long buzzes at full amplitude (pattern 2, overrides direction)
 *   APPROACH = escalating cadence that quickens on a closing target (pattern 3)
 *   CAUTION  = 1 soft pulse, NO direction (pattern 1 / low confidence)
 *
 * A long CYCLE_GAP separates repeats so the pulses are countable, and the whole
 * signature loops (repeat=0). Intensity rides on amplitude (with a perceptible
 * floor — felt through clothing at the waist). Timings always start with an OFF
 * (silence) segment and alternate off/on, so a device WITHOUT amplitude control
 * replays the same rhythm from the timings alone (see [PhoneHapticsActuator]).
 */
object DirectionalEncoding {

    enum class Direction { LEFT, RIGHT, CENTER, CURB, APPROACH, CAUTION }

    /**
     * A repeating waveform. [timings] and [amplitudes] are parallel; amplitude 0 is
     * an OFF segment. [repeat] is the index to loop from (0 = loop whole signature).
     */
    data class HapticSignature(
        val direction: Direction,
        val timings: LongArray,
        val amplitudes: IntArray,
        val repeat: Int,
    )

    const val MAX_AMPLITUDE = 255

    /** Smallest amplitude reliably felt through clothing at the waist. */
    const val MIN_PERCEPTIBLE = 60

    // Rhythm timings (ms).
    private const val CYCLE_GAP = 350L   // long rest between repeats -> pulses are countable
    private const val PULSE = 90L        // one tap
    private const val PULSE_GAP = 90L    // off between taps within a cycle
    private const val CURB_ON = 200L     // hard long buzz
    private const val CURB_GAP = 120L
    private const val CAUTION_MS = 150L

    /** 0..255 zone intensity -> legal 1..255 amplitude with a perceptible floor. */
    fun toAmplitude(intensity: Int): Int {
        if (intensity <= 0) return MIN_PERCEPTIBLE
        val mapped = MIN_PERCEPTIBLE + ((intensity.coerceIn(0, 255) / 255f) * (255 - MIN_PERCEPTIBLE)).toInt()
        return mapped.coerceIn(1, MAX_AMPLITUDE)
    }

    private fun softAmplitude(amp: Int): Int = (amp * 0.55f).toInt().coerceIn(1, MAX_AMPLITUDE)

    /** Convenience overload for a `[L, C, R, pattern]` packet. */
    fun encode(packet: List<Int>): HapticSignature? = encode(
        l = packet.getOrElse(0) { 0 },
        c = packet.getOrElse(1) { 0 },
        r = packet.getOrElse(2) { 0 },
        pattern = packet.getOrElse(3) { 0 },
    )

    /**
     * @return the signature to play, or null if nothing should buzz (all zones silent).
     */
    fun encode(l: Int, c: Int, r: Int, pattern: Int): HapticSignature? {
        val li = l.coerceIn(0, 255)
        val ci = c.coerceIn(0, 255)
        val ri = r.coerceIn(0, 255)
        val pat = pattern.coerceIn(0, 3)

        if (li == 0 && ci == 0 && ri == 0) return null

        val maxIntensity = max(li, max(ci, ri))
        val amp = toAmplitude(maxIntensity)

        // Low-confidence caution (pattern 1): never imply a confident direction.
        if (pat == 1) {
            return HapticSignature(
                Direction.CAUTION,
                longArrayOf(CYCLE_GAP, CAUTION_MS),
                intArrayOf(0, softAmplitude(amp)),
                repeat = 0,
            )
        }
        // Curb / step (pattern 2): unmistakable two hard long buzzes, overrides direction.
        if (pat == 2) {
            return HapticSignature(
                Direction.CURB,
                longArrayOf(CYCLE_GAP, CURB_ON, CURB_GAP, CURB_ON),
                intArrayOf(0, MAX_AMPLITUDE, 0, MAX_AMPLITUDE),
                repeat = 0,
            )
        }

        if (pat == 3) {
            return HapticSignature(
                Direction.APPROACH,
                longArrayOf(CYCLE_GAP, 160L, 170L, 120L, 120L, 85L, 70L, 110L),
                intArrayOf(
                    0,
                    softAmplitude(amp / 2),
                    0,
                    softAmplitude((amp * 0.68f).toInt()),
                    0,
                    softAmplitude((amp * 0.82f).toInt()),
                    0,
                    amp,
                ),
                repeat = 0,
            )
        }

        // Dominant zone -> direction, encoded as a PULSE COUNT (L=1, C=2, R=3).
        // Ties resolve left, then right, then center.
        return when (maxIntensity) {
            li -> pulseSignature(Direction.LEFT, 1, amp)
            ri -> pulseSignature(Direction.RIGHT, 3, amp)
            else -> pulseSignature(Direction.CENTER, 2, amp)
        }
    }

    /** A cycle of [count] equal taps at [amp], a long rest, then looping. */
    private fun pulseSignature(direction: Direction, count: Int, amp: Int): HapticSignature {
        // [CYCLE_GAP, PULSE, PULSE_GAP, PULSE, ...]  -> 'count' on-segments per cycle.
        val timings = ArrayList<Long>(1 + count * 2)
        val amps = ArrayList<Int>(1 + count * 2)
        timings.add(CYCLE_GAP); amps.add(0)            // leading rest (off)
        for (i in 0 until count) {
            timings.add(PULSE); amps.add(amp)          // on
            if (i < count - 1) { timings.add(PULSE_GAP); amps.add(0) }  // off between taps
        }
        return HapticSignature(direction, timings.toLongArray(), amps.toIntArray(), repeat = 0)
    }
}
