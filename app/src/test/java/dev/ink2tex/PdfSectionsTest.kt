package dev.ink2tex

import dev.ink2tex.preprocessing.PdfSections
import org.junit.Assert.*
import org.junit.Test

class PdfSectionsTest {
    @Test fun tallNoteKeepsReadableWidthAndSplitsOnlyInBlankRowsWhenAvailable() {
        val page = PdfSections(405, 2034)
        assertEquals(1013, page.width)
        assertEquals(5085, page.height)
        val separators = listOf(1060, 2170, 3300, 4415)
        val bands = generateSequence(page.next(0) { window -> blankRows(window, separators) }) { band ->
            if (band.nextTop == page.height) null
            else page.next(band.nextTop) { window -> blankRows(window, separators) }
        }.toList()

        assertTrue(bands.size >= 4)
        assertEquals(0, bands.first().rows.first)
        assertEquals(page.height - 1, bands.last().rows.last)
        assertTrue(bands.all { it.rows.count() <= 1600 && !it.overlapsNext })
        bands.zipWithNext().forEach { (earlier, later) ->
            assertEquals(earlier.rows.last + 1, later.rows.first)
            assertTrue(separators.any { earlier.nextTop in it - 8..it + 8 })
        }
    }

    @Test fun missingBlankSeparatorKeepsBoundaryRowsInBothSections() {
        val page = PdfSections(405, 2034)
        val first = page.next(0) { BooleanArray(it.count()) }
        val second = page.next(first.nextTop) { BooleanArray(it.count()) }
        assertTrue(first.overlapsNext)
        assertEquals(128, first.rows.intersect(second.rows).size)
        assertEquals(0, first.rows.first)
    }

    @Test fun ordinaryPortraitPageStaysInOneImage() {
        val page = PdfSections(595, 842)
        val band = page.next(0) { error("No separator search needed") }
        assertEquals(0 until page.height, band.rows)
        assertEquals(page.height, band.nextTop)
    }

    @Test fun landscapePageRetainsItsFullWidth() {
        val page = PdfSections(1100, 600)
        assertEquals(1800, page.width)
        assertEquals(0 until page.height, page.next(0) { error("Landscape page fits") }.rows)
    }

    @Test fun narrowPageJustPastSplitThresholdKeepsEveryRowInsideTheRaster() {
        val page = PdfSections(100, 450)
        val first = page.next(0) { window ->
            assertTrue(window.first >= 0 && window.last < page.height)
            BooleanArray(window.count())
        }
        val last = page.next(first.nextTop) { error("Remainder fits") }
        assertTrue(first.rows.last < page.height)
        assertEquals(0 until page.height, first.rows.first..last.rows.last)
    }

    private fun blankRows(window: IntRange, separators: List<Int>): BooleanArray =
        BooleanArray(window.count()) { i -> separators.any { window.first + i in it - 13..it + 13 } }
}
