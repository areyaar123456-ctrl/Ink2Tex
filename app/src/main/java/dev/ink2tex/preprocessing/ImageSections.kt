package dev.ink2tex.preprocessing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Rect
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object InkBandPlanner {
    const val MAX_SECTIONS = 16

    /** Stop at blank gaps with ink on both sides. Include every original row exactly once. */
    fun plan(blank: BooleanArray, preferredHeight: Int, minimumSections: Int = 1, clearRows: Int = 4): List<IntRange> {
        require(blank.isNotEmpty() && preferredHeight > 0 && clearRows >= 2)
        val full = listOf(blank.indices)
        val firstInk = blank.indexOfFirst { !it }
        val lastInk = blank.indexOfLast { !it }
        if (firstInk < 0 || firstInk == lastInk) return full
        val inkHeight = lastInk - firstInk + 1
        val heightNeedsSplit = inkHeight.toLong() * 4 > preferredHeight.toLong() * 5
        val desired = maxOf(minimumSections,
            if (heightNeedsSplit) (1 + (inkHeight - 1) / preferredHeight) else 1).coerceIn(1, MAX_SECTIONS)
        if (desired == 1) return full

        val gaps = mutableListOf<Int>()
        var row = firstInk + 1
        while (row < lastInk) {
            if (!blank[row]) {
                row++
                continue
            }
            val start = row
            while (row < lastInk && blank[row]) row++
            if (row - start >= clearRows) gaps += start + (row - start) / 2
        }
        val count = minOf(desired, gaps.size + 1)
        val cuts = mutableListOf<Int>()
        var previousIndex = -1
        for (section in 1 until count) {
            val target = firstInk + (inkHeight.toLong() * section / count).toInt()
            val latestIndex = gaps.size - (count - section)
            val index = (previousIndex + 1..latestIndex).minBy { abs(gaps[it] - target) }
            cuts += gaps[index]
            previousIndex = index
        }
        return (listOf(0) + cuts + blank.size).zipWithNext { from, to -> from until to }
    }
}

@Suppress("DEPRECATION")
internal object ImageSections {
    /** Reuse one file decoder and one section plan for both sides of an evaluation. */
    suspend fun forEach(
        file: File,
        sourceCharacters: Int = 0,
        forceSplit: Boolean = false,
        requireSplit: Boolean = false,
        block: suspend (Bitmap, Int, Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val decoder = BitmapRegionDecoder.newInstance(file.absolutePath, false) ?: error("Unable to read the original image.")
        try {
            val width = decoder.width
            val height = decoder.height
            require(width.toLong() * height <= 32_000_000L && maxOf(width, height) <= 32_000) {
                "This original is too large for automatic snips. Crop it first."
            }
            val preferredHeight = (width * 3 / 4).coerceIn(480, 1000)
            val minimum = maxOf(if (forceSplit || requireSplit) 2 else 1, 1 + (sourceCharacters - 1).coerceAtLeast(0) / 1400)
            val rows = if (minimum == 1 && height <= preferredHeight * 5L / 4) listOf(0 until height) else {
                InkBandPlanner.plan(blankRows(decoder), preferredHeight, minimum,
                    clearRows = (width / 200).coerceIn(4, 10))
            }
            require(!requireSplit || rows.size > 1) {
                "This image has no clear blank gap between written lines. Use the manual snip tool."
            }
            rows.forEachIndexed { index, band ->
                currentCoroutineContext().ensureActive()
                val bandHeight = band.last - band.first + 1
                var sample = 1
                while (maxOf(width, bandHeight).toLong() > 1800L * sample ||
                    ((width + sample - 1L) / sample) * ((bandHeight + sample - 1L) / sample) > 4_000_000L) sample *= 2
                val image = decoder.decodeRegion(Rect(0, band.first, width, band.last + 1),
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }) ?: error("Unable to read snip ${index + 1}.")
                try { block(image, index, rows.size) } finally { image.recycle() }
            }
            rows.size
        } finally { decoder.recycle() }
    }

    private suspend fun blankRows(decoder: BitmapRegionDecoder): BooleanArray {
        val width = decoder.width
        val height = decoder.height
        val rows = BooleanArray(height)
        val pixels = IntArray(width)
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }

        fun corner(x: Int, y: Int): Int {
            val pixel = decoder.decodeRegion(Rect(x,y,x+1,y+1), options) ?: return Color.WHITE
            return try { pixel.getPixel(0,0) } finally { pixel.recycle() }
        }

        // Shared corners also support off-white and dark pages. Treat every color variation as ink.
        val topLeft = corner(0,0)
        val background = if (topLeft == corner(width-1,0) &&
            topLeft == corner(0,height-1) && topLeft == corner(width-1,height-1)) topLeft else Color.WHITE
        val blockHeight = (256_000 / width).coerceIn(1, 128)
        for (top in 0 until height step blockHeight) {
            currentCoroutineContext().ensureActive()
            val bottom = minOf(top + blockHeight, height)
            val block = decoder.decodeRegion(Rect(0, top, width, bottom), options)
                ?: error("Unable to locate blank rows.")
            try {
                for (y in 0 until block.height) {
                    block.getPixels(pixels, 0, width, 0, y, width, 1)
                    rows[top+y] = pixels.all { it == background }
                }
            } finally { block.recycle() }
        }
        return rows
    }
}
