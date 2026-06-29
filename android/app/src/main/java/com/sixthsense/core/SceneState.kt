package com.sixthsense.core

/**
 * The single contract the whole system is built around. Every component either
 * produces or consumes a [SceneState]. Mock mode emits the SAME contract as the
 * real vision pipeline so the belt, voice agent, and dashboard can be built and
 * demoed before the on-device models are ready.
 */

/** Relative obstacle nearness per zone, 0f (far/clear) .. 1f (very near). */
data class DepthZones(
    val left: Float,
    val center: Float,
    val right: Float,
    val curbAhead: Boolean = false,
    /** Reserved — step-down / drop-off detection is not implemented yet (always false). */
    val stepDown: Boolean = false,
)

/**
 * Normalized bounding box in [0,1] of the upright (rotated, square model-input)
 * frame: (0,0) = top-left, (1,1) = bottom-right. Used by the AR overlay to draw the
 * box on the live camera view. Optional — null when a producer doesn't supply it.
 */
data class BoundingBox(
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
)

/** A detected object placed in a zone ("left" | "center" | "right"). */
data class DetectedObj(
    val label: String,
    val zone: String,
    val nearness: Float,
    val conf: Float,
    /** Normalized screen box for the AR overlay (null if unknown). */
    val box: BoundingBox? = null,
)

/** On-demand OCR result (only populated when the user asks to read text). */
data class Ocr(
    val present: Boolean = false,
    val text: String = "",
)

/**
 * @param belt the last belt packet [left, center, right, pattern] for visualization.
 */
data class SceneState(
    val ts: Long,
    val depth: DepthZones,
    val objects: List<DetectedObj>,
    val pathClear: Boolean,
    val ocr: Ocr = Ocr(),
    val conf: Float,
    val belt: List<Int> = emptyList(),
)
