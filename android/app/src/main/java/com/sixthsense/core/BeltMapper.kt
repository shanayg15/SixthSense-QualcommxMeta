package com.sixthsense.core

/**
 * Maps a [SceneState] to a 4-byte belt packet: [left, center, right, pattern].
 *
 * Pattern:
 *   0 = steady
 *   1 = single / caution pulse (low confidence)
 *   2 = double pulse (curb / step ahead)
 */
object BeltMapper {

    /** Depth nearness below this maps to no buzz. */
    const val NEAR_THRESHOLD = 0.55f

    /**
     * Object nearness below this maps to no buzz. A confirmed detection warrants an
     * earlier warning than raw depth, so this is a little lower than [NEAR_THRESHOLD]:
     * as the user approaches a detected object its nearness rises past this and the
     * belt/haptics buzz in that object's zone.
     */
    const val OBJECT_NEAR_THRESHOLD = 0.45f

    /** Low-confidence threshold: below this we emit a cautious center pulse only. */
    const val LOW_CONF = 0.4f

    /** Gentle "all clear" hum applied to every motor when the path is clear. */
    const val CLEAR_HUM = 30

    /** Minimum center intensity asserted when a curb/step is detected. */
    const val CURB_CENTER_MIN = 180

    /** Center intensity for a low-confidence caution. */
    const val CAUTION_CENTER = 80

    const val PATTERN_STEADY = 0
    const val PATTERN_PULSE = 1
    const val PATTERN_DOUBLE = 2
    const val PATTERN_APPROACH = 3

    /** Crowd-mode smoothing window, in frames, used by the tuning canvas preview. */
    const val CROWD_SMOOTHING_WINDOW = 5

    /** Crowd-mode saturation cutoff (0f..1f) before crowded zones compress. */
    const val CROWD_SATURATION_CUTOFF = 0.82f

    /** Closing-speed threshold (0f..1f) that escalates into approach cadence. */
    const val APPROACH_SPEED_THRESHOLD = 0.12f

    /** Gap nudge strength (0f..1f) used to bias the clearest zone in crowd mode. */
    const val GAP_NUDGE_STRENGTH = 0.35f

    /** Convenience for the BLE write path. */
    fun map(scene: SceneState): ByteArray {
        val p = packetAsInts(scene)
        return byteArrayOf(p[0].toByte(), p[1].toByte(), p[2].toByte(), p[3].toByte())
    }

    /** The same packet as ints, for SceneState.belt and the dashboard. */
    fun packetAsInts(scene: SceneState): List<Int> {
        var l = intensity(scene.depth.left)
        var c = intensity(scene.depth.center)
        var r = intensity(scene.depth.right)

        // Fuse detected objects: a detected obstacle asserts a buzz in its zone, so
        // object detection drives directional haptics (even when depth is flat or
        // absent — in that case nearness comes from box size). Take the max so depth
        // and detection reinforce rather than cancel.
        for (o in scene.objects) {
            val oi = objectIntensity(o.nearness)
            if (oi == 0) continue
            when (o.zone) {
                "left" -> l = maxOf(l, oi)
                "right" -> r = maxOf(r, oi)
                else -> c = maxOf(c, oi)
            }
        }

        var pattern = PATTERN_STEADY

        // Curb / step takes priority: strong center double-pulse.
        if (scene.depth.curbAhead || scene.depth.stepDown) {
            pattern = PATTERN_DOUBLE
            c = maxOf(c, CURB_CENTER_MIN)
        }

        // Low confidence: never claim a clear direction — caution pulse only.
        if (scene.conf < LOW_CONF) {
            pattern = PATTERN_PULSE
            c = maxOf(c, CAUTION_CENTER)
            l = 0
            r = 0
        }

        // Nothing firing and the scene is genuinely clear: gentle all-clear hum.
        if (l == 0 && c == 0 && r == 0 && pattern == PATTERN_STEADY && scene.pathClear) {
            l = CLEAR_HUM
            c = CLEAR_HUM
            r = CLEAR_HUM
        }

        return listOf(l, c, r, pattern)
    }

    private fun intensity(v: Float): Int {
        if (v < NEAR_THRESHOLD) return 0
        return (((v - NEAR_THRESHOLD) / (1f - NEAR_THRESHOLD)) * 255f)
            .toInt()
            .coerceIn(0, 255)
    }

    /** Object nearness -> intensity, with a perceptible floor so any near detection is felt. */
    private fun objectIntensity(nearness: Float): Int {
        if (nearness < OBJECT_NEAR_THRESHOLD) return 0
        val scaled = ((nearness - OBJECT_NEAR_THRESHOLD) / (1f - OBJECT_NEAR_THRESHOLD)) * 255f
        return scaled.toInt().coerceIn(OBJECT_MIN, 255)
    }

    /** Minimum buzz for a detected object that is past [OBJECT_NEAR_THRESHOLD]. */
    private const val OBJECT_MIN = 90
}
