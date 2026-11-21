package dev.ink2tex

import dev.ink2tex.ink.AbelFeatures
import dev.ink2tex.ink.HybridStrokeRecognizer
import dev.ink2tex.ink.InkLayout
import dev.ink2tex.ink.InkPage
import dev.ink2tex.ink.InkPoint
import dev.ink2tex.ink.InkStroke
import org.junit.Assert.*
import org.junit.Test

class StrokeLayoutTest {
    @Test fun layoutKeepsStrokeOrderWithinEachLineAndEachMarkExactlyOnce() {
        val lateTopStroke = stroke(4f to 0f, 4f to 4f)
        val bottomStroke = stroke(0f to 20f, 0f to 24f)
        val earlyTopStroke = stroke(0f to 1f, 0f to 4f)
        val page = InkPage(listOf(earlyTopStroke, bottomStroke, lateTopStroke))
        val lines = InkLayout.lines(page)
        assertEquals(listOf(listOf(earlyTopStroke, lateTopStroke), listOf(bottomStroke)), lines.map { it.strokes })
    }

    @Test fun abelFeaturesMatchExpectedStartEndAndPointCounts() {
        val input = listOf(
            stroke(1f to 0f, 1f to 4f), stroke(3f to 2f, 5f to 2f),
            stroke(4f to 1f, 4f to 3f), stroke(7f to 0f, 7f to 4f)
        )
        val points = checkNotNull(AbelFeatures.from(input))
        assertEquals(64 * 6, points.size)
        assertEquals(-.75f, points[0])
        assertEquals(-.5f, points[1])
        assertEquals(1f, points[4])
        assertEquals(1f, points[20 * 6 + 5])
        assertEquals(4, (4 until points.size step 6).count { points[it] == 1f })
    }

    @Test fun tallInkUsesNonOverlappingImageSections() {
        val marks = (0..5).map { row -> stroke(0f to row * 22f, 8f to row * 22f + 4f) }
        val parts = InkLayout.imageSections(InkPage(marks))
        assertEquals(2, parts.size)
        assertEquals(marks, parts.flatMap { it.strokes })
    }

    @Test fun unsupportedRelationAndChangedEqualityRequireWholeLineFallback() {
        assertTrue(HybridStrokeRecognizer.suspectedUnsupported("p ∤ a"))
        assertTrue(HybridStrokeRecognizer.suspectedUnsupported("p | a"))
        assertTrue(HybridStrokeRecognizer.suspectedUnsupported("a = b mod p"))
        assertFalse(HybridStrokeRecognizer.safeFormula("x+3", "x=3"))
        assertFalse(HybridStrokeRecognizer.safeFormula("a^{2025}+b^{2024}", "a2023+b2024"))
        assertFalse(HybridStrokeRecognizer.safeFormula("a^{2023}+(b^{2024}", "a2023+b2024"))
        assertTrue(HybridStrokeRecognizer.safeFormula("x^{2}=3", "x^2=3"))
    }

    private fun stroke(vararg values: Pair<Float, Float>) =
        InkStroke(values.map { (x, y) -> InkPoint(x, y, 255) })
}
