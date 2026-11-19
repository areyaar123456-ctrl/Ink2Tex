package dev.ink2tex

import dev.ink2tex.preprocessing.InkBandPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSectionsTest {
    @Test fun longImageSplitsInReadingOrderAtBlankGaps() {
        val ink = listOf(20..300, 380..670, 790..1010, 1120..1430)
        val rows = BooleanArray(1500) { row -> ink.none { row in it } }
        val sections = InkBandPlanner.plan(rows, preferredHeight = 500)
        assertEquals(3, sections.size)
        assertEquals(rows.indices.toList(), sections.flatMap { it.toList() })
        for (section in sections.drop(1)) {
            assertTrue(rows[section.first - 1] && rows[section.first])
        }
        assertTrue(ink.all { mark -> sections.any { mark.first in it && mark.last in it } })
    }

    @Test fun blankOuterMarginsAloneDoNotSplitAnImage() {
        val rows = BooleanArray(3000) { it !in 1100..1290 }
        assertEquals(listOf(rows.indices), InkBandPlanner.plan(rows, 500))
        val blank = BooleanArray(1500) { true }
        assertEquals(listOf(blank.indices), InkBandPlanner.plan(blank, 500))
    }

    @Test fun aNarrowGapNeverCutsThroughConnectedInk() {
        val rows = BooleanArray(1900) { it !in 20..840 && it !in 843..1890 }
        assertEquals(listOf(rows.indices), InkBandPlanner.plan(rows, 500, clearRows = 4))
    }

    @Test fun anExplicitRetryCanSplitACompactImage() {
        val rows = BooleanArray(350) { it !in 30..100 && it !in 190..300 }
        assertEquals(listOf(rows.indices), InkBandPlanner.plan(rows, 500))
        assertEquals(listOf(0..144, 145..349), InkBandPlanner.plan(rows, 500, minimumSections = 2))
    }
}
