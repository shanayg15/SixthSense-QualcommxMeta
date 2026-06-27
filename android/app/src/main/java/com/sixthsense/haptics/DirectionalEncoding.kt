package com.sixthsense.haptics

import kotlin.math.max

/**
 * PURE (no Android) logic that turns a belt packet `[L, C, R, pattern]` into a
 * directional vibration signature for the phone's single vibration motor. Kept
 * Android-free so the directional encoding is unit-testable on the JVM.
 *
 * A phone has ONE actuator (no true spatial left/right), so direction is encoded
 * TEMPORALLY by rhythm, with the dominant zone (max of L/C/R) chosen per update:
 *   LEFT    = short pip -> long buzz   (rising, "leans left")
 *   RIGHT   = long buzz -> short pip   (mirror of LEFT)
 *   CENTER  = one steady block
 *   CURB    = hard triple thump at full amplitude (pattern 2, overrides direction)
 *   CAUTION = single soft pulse, NO direction (pattern 1 / low confidence)
 *
 * Intensity rides on amplitude (with a perceptible floor, since it's felt through
 * clothing at the waist). The timings arrays always start with an OFF (silence)
 * segment and alternate off/on, so a device WITHOUT amplitude control can replay
 * the exact same rhythm from the timings alone (see [PhoneHapticsActuator]).
 */
object DirectionalEncoding {

    enum class Direction { LEFT, RIGHT, CENTER, CURB, CAUTION }

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
    private const val GAP = 90L      // silence before the signature repeats
    private const val SHORT = 70L
    private const val LONG = 230L
    private const val MID_GAP = 60L  // gap between the two pips of L/R
    private const val CENTER_MS = 300L
    private const val CURB_ON = 120L
    private const val CURB_GAP = 70L
    private const val CAUTION_MS = 110L

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
        val pat = pattern.coerceIn(0, 2)

        if (li == 0 && ci == 0 && ri == 0) return null

        val maxIntensity = max(li, max(ci, ri))
        val amp = toAmplitude(maxIntensity)

        // Low-confidence caution (pattern 1): never imply a confident direction.
        if (pat == 1) {
            return HapticSignature(
                Direction.CAUTION,
                longArrayOf(GAP, CAUTION_MS),
                intArrayOf(0, softAmplitude(amp)),
                repeat = 0,
            )
        }
        // Curb / step (pattern 2): unmistakable hard triple thump, overrides direction.
        if (pat == 2) {
            return HapticSignature(
                Direction.CURB,
                longArrayOf(GAP, CURB_ON, CURB_GAP, CURB_ON, CURB_GAP, CURB_ON),
                intArrayOf(0, MAX_AMPLITUDE, 0, MAX_AMPLITUDE, 0, MAX_AMPLITUDE),
                repeat = 0,
            )
        }

        // Dominant zone -> direction. Ties resolve left, then right, then center.
        return when (maxIntensity) {
            li -> HapticSignature(
                Direction.LEFT,
                longArrayOf(GAP, SHORT, MID_GAP, LONG),
                intArrayOf(0, amp, 0, amp),
                repeat = 0,
            )
            ri -> HapticSignature(
                Direction.RIGHT,
                longArrayOf(GAP, LONG, MID_GAP, SHORT),
                intArrayOf(0, amp, 0, amp),
                repeat = 0,
            )
            else -> HapticSignature(
                Direction.CENTER,
                longArrayOf(GAP, CENTER_MS),
                intArrayOf(0, amp),
                repeat = 0,
            )
        }
    }
}
