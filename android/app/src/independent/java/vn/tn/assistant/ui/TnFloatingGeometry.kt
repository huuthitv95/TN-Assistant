package vn.tn.assistant.ui

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Geometry for the original single-microphone overlay and its first-use hint. */
internal object TnFloatingGeometry {

    fun unit(screenWidth: Int, landscape: Boolean, size: String): Int {
        val multiplier = when (size) {
            "small" -> .85f
            "large" -> 1.25f
            else -> 1f
        }
        // ChatHeadService.z / c2.a0: one slot is half the configured
        // orientation-dependent origin size, rather than a fixed dp circle.
        val origin = (screenWidth * if (landscape) .15f else .25f).roundToInt()
        return (origin * multiplier / 2).toInt().coerceAtLeast(1)
    }

    private fun hintInnerWidth(unit: Int) = ((2.1269841f * unit).toInt() - 2 * .06349207f * unit).toInt()
    fun hintWidth(unit: Int) = (hintInnerWidth(unit) * 419f / 359f).toInt().coerceAtLeast(1)
    fun hintHeight(unit: Int) = (hintInnerWidth(unit) * 175f / 359f).toInt().coerceAtLeast(1)

    fun dimensions(unit: Int, suggestionVisible: Boolean, density: Float, planVisible: Boolean = false): Pair<Int, Int> {
        val bodyWidth = (unit * if (planVisible) 2.1269841f else 1.1269841f).toInt()
        val width = if (suggestionVisible) max(bodyWidth, hintWidth(unit)) else bodyWidth
        val height = (unit * 1.1746032f).toInt() + if (suggestionVisible) hintHeight(unit) else 0
        return width to height
    }

    fun hintAnchorOffset(unit: Int, suggestionVisible: Boolean, density: Float): Int =
        (if (suggestionVisible) hintHeight(unit) else 0) + (.56349207f * unit).roundToInt()

    fun maximumOriginY(screenHeight: Int, unit: Float, spacing: Float): Int {
        val slots = ((screenHeight - unit * 1.1746032f - spacing) / (unit * 1.1851852f)).toInt().coerceIn(2, 3)
        val signHeight = (1.1851852f * unit * slots + spacing).toInt()
        return (screenHeight - signHeight - (1.1746032f * unit).toInt()).coerceAtLeast(0)
    }

    fun clampOrigin(origin: Int, screen: Int, size: Int): Int = origin.coerceIn(0, (screen - size).coerceAtLeast(0))

    fun nearCloseTarget(centerX: Int, centerY: Int, x: Float, y: Float, width: Int, height: Int, targetSize: Int, alreadyMagnetized: Boolean): Boolean {
        val distance = hypot(max(abs(centerX - (x + width / 2f)) - width / 2f, 0f), max(abs(centerY - (y + height / 2f)) - height / 2f, 0f))
        return distance < targetSize * if (alreadyMagnetized) 1.25f else .75f
    }

    fun opacity(value: String): Float = when (value) { "faint" -> .8f; "very_faint" -> .6f; else -> 1f }
}
