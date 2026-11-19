package dev.ink2tex.preprocessing

import kotlin.math.abs
import kotlin.math.ceil

/** Raster dimensions and section boundaries in full-width page pixels. */
internal class PdfSections(pageWidth: Int, pageHeight: Int) {
    init {
        require(pageWidth > 0 && pageHeight > 0) { "Invalid PDF page size." }
    }

    private val tall = pageHeight > 2L * pageWidth
    val scale = if (tall) minOf(2.5f, 1120f / pageWidth)
        else minOf(2f, 1800f / maxOf(pageWidth, pageHeight))
    val width = ceil(pageWidth.toDouble() * scale).toInt().coerceAtMost(if (tall) 1120 else 1800)
    val height = ceil(pageHeight.toDouble() * scale).toInt().let {
        if (tall) it else it.coerceAtMost(1800)
    }
    val pixelCount = width.toLong() * height

    data class Band(val rows: IntRange, val nextTop: Int, val overlapsNext: Boolean = false)

    fun next(top: Int, readBlankRows: (IntRange) -> BooleanArray): Band {
        require(top in 0 until height)
        val remaining = height - top
        if (!tall || remaining <= TARGET_HEIGHT || remaining <= MAX_HEIGHT && top > 0) {
            return Band(top until height, height)
        }

        val target = top + if (remaining <= MAX_HEIGHT) remaining / 2 else TARGET_HEIGHT
        // Leave enough room for the next band, even on narrow pages.
        val window = (target - SEARCH_RADIUS)..minOf(target + SEARCH_RADIUS, height - 180)
        val blankRows = readBlankRows(window)
        require(blankRows.size == window.count())

        var blankRun = 0
        var bestCut: Int? = null
        for (index in blankRows.indices) {
            blankRun = if (blankRows[index]) blankRun + 1 else 0
            if (blankRun < MIN_BLANK_ROWS) continue

            // Every candidate has blank pixels on both sides of the cut.
            val cut = window.first + index - MIN_BLANK_ROWS / 2 + 1
            if (bestCut == null || abs(cut - target) < abs(bestCut - target)) bestCut = cut
        }

        return if (bestCut != null) {
            Band(top until bestCut, bestCut)
        } else {
            // With no clear separator, keep the boundary ink in both inputs for review.
            Band(top until target + OVERLAP / 2, target - OVERLAP / 2, overlapsNext = true)
        }
    }

    companion object {
        private const val TARGET_HEIGHT = 1120
        private const val MAX_HEIGHT = 1600
        private const val SEARCH_RADIUS = 180
        private const val MIN_BLANK_ROWS = 12
        private const val OVERLAP = 128
    }
}
