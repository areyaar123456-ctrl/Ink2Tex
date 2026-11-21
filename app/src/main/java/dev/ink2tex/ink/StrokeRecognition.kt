package dev.ink2tex.ink

import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await

internal data class StrokeResult(val markdown: String, val warnings: List<String> = emptyList(), val incomplete: Boolean = false)

internal interface StrokeRecognizer : AutoCloseable {
    suspend fun recognize(page: InkPage): StrokeResult
}

internal class GoogleStrokeText : AutoCloseable {
    private val recognizer = DigitalInkRecognition.getClient(
        DigitalInkRecognizerOptions.builder(StrokeModels.english()).build()
    )

    suspend fun recognize(strokes: List<InkStroke>): String? {
        val origin = boundsOf(strokes)
        val ink = Ink.builder()
        var tick = 0L
        val firstTime = strokes.firstNotNullOfOrNull { it.startMicros }
        for (stroke in strokes) {
            val builder = Ink.Stroke.builder()
            val start = stroke.startMicros
            if (start != null && firstTime != null) tick = maxOf(tick, ((start - firstTime) / 1000).coerceIn(0, 3_600_000))
            val strokeStart = tick
            for (p in stroke.points) {
                p.timeOffsetMs?.let { tick = maxOf(tick, strokeStart + it.coerceAtMost(60_000)) }
                builder.addPoint(Ink.Point.create((p.x - origin.left) * 8, (p.y - origin.top) * 8, tick++))
            }
            ink.addStroke(builder.build())
            tick += 5
        }
        return recognizer.recognize(ink.build()).await().candidates.firstOrNull()?.text?.trim()?.takeIf(String::isNotBlank)
    }

    suspend fun validate() {
        val h = listOf(
            InkStroke(listOf(InkPoint(0f, 0f, 255), InkPoint(0f, 5f, 255))),
            InkStroke(listOf(InkPoint(0f, 3f, 255), InkPoint(2f, 2f, 255), InkPoint(2f, 5f, 255)))
        )
        check(!recognize(h).isNullOrBlank()) { "English digital ink returned no output in its runtime check." }
    }

    override fun close() { recognizer.close() }
}

internal data class InkLine(val strokes: List<InkStroke>, val crowded: Boolean)

internal object InkLayout {
    private data class Mark(val index: Int, val box: InkBounds)
    private class Band(first: Mark) {
        val marks = mutableListOf(first)
        var top = first.box.top
        var bottom = first.box.bottom

        fun add(mark: Mark) {
            marks += mark
            top = minOf(top, mark.box.top)
            bottom = maxOf(bottom, mark.box.bottom)
        }
    }

    fun lines(page: InkPage): List<InkLine> {
        if (page.strokes.isEmpty()) return emptyList()
        val marks = page.strokes.mapIndexed { index, stroke -> Mark(index, boundsOf(listOf(stroke))) }
        val heights = marks.map { it.box.height }.filter { it >= 0.4f }.sorted()
        val typical = heights.getOrNull(heights.size / 2)?.coerceIn(1f, 25f) ?: 2f
        val bands = mutableListOf<Band>()
        for (mark in marks.sortedWith(compareBy({ it.box.top }, { it.box.left }, { it.index }))) {
            val band = bands.lastOrNull()
            if (band != null && mark.box.top <= band.bottom + typical * 0.38f) {
                band.add(mark)
            } else {
                bands += Band(mark)
            }
        }
        return bands.map { band ->
            InkLine(band.marks.sortedBy { it.index }.map { page.strokes[it.index] },
                band.bottom - band.top > typical * 3.2f)
        }
    }

    fun clusters(line: List<InkStroke>): List<List<InkStroke>> {
        val marks = line.mapIndexed { index, stroke -> Mark(index, boundsOf(listOf(stroke))) }
        val heights = marks.map { it.box.height }.filter { it >= 0.4f }.sorted()
        val gap = ((heights.getOrNull(heights.size / 2) ?: 2f) * 0.4f).coerceIn(0.55f, 3f)
        val groups = mutableListOf<MutableList<Mark>>()
        var right = Float.NEGATIVE_INFINITY
        for (mark in marks.sortedBy { it.box.left }) {
            if (groups.isEmpty() || mark.box.left - right > gap) {
                groups.add(mutableListOf(mark))
                right = mark.box.right
            } else {
                groups.last() += mark
                right = maxOf(right, mark.box.right)
            }
        }
        return groups.map { group -> group.sortedBy { it.index }.map { line[it.index] } }
    }

    fun imageSections(page: InkPage): List<InkPage> {
        val bounds = page.bounds ?: return listOf(page)
        val maxHeight = (bounds.width * 0.85f).coerceIn(60f, 120f)
        val sections = mutableListOf<InkPage>()
        val gathered = mutableListOf<InkStroke>()
        var sectionTop = 0f
        for (line in lines(page)) {
            val lineBounds = boundsOf(line.strokes)
            if (gathered.isNotEmpty() && lineBounds.bottom - sectionTop > maxHeight) {
                sections += InkPage(gathered.toList())
                gathered.clear()
            }
            if (gathered.isEmpty()) sectionTop = lineBounds.top
            gathered += line.strokes
        }
        if (gathered.isNotEmpty()) sections += InkPage(gathered)
        return sections
    }
}

internal class HybridStrokeRecognizer(
    private val text: GoogleStrokeText,
    private val formula: AbelFormula,
    private val fallback: suspend (InkPage) -> StrokeResult
) : StrokeRecognizer {
    override suspend fun recognize(page: InkPage): StrokeResult {
        val lines = InkLayout.lines(page)
        val warnings = mutableListOf<String>()
        val results = mutableListOf<String>()
        var incomplete = false
        for ((index, line) in lines.withIndex()) {
            currentCoroutineContext().ensureActive()
            val recognized = if (line.crowded || line.strokes.size > 400 || line.strokes.sumOf { it.points.size } > 16_000) null else lineText(line.strokes)
            if (recognized != null) {
                results += recognized
            } else {
                val result = fallback(InkPage(line.strokes))
                val failed = result.markdown.isBlank() || result.incomplete
                warnings += "Line group ${index + 1} used image OCR${if (failed) " and needs transcription" else " · check math and boundaries"}."
                warnings += result.warnings
                results += result.markdown.ifBlank { "[Untranscribed ink at line group ${index + 1}]" }
                incomplete = incomplete || failed
            }
        }
        return StrokeResult(results.joinToString("\n\n"), warnings, incomplete)
    }

    private suspend fun lineText(strokes: List<InkStroke>): String? {
        val label = text.recognize(strokes) ?: return null
        if (suspectedUnsupported(label)) return null
        val bounds = boundsOf(strokes)
        val fractionLike = strokes.any { stroke ->
            val b = boundsOf(listOf(stroke))
            b.width >= 1.6f && b.height < b.width * 0.12f &&
                b.centerY > bounds.top + bounds.height * 0.2f && b.centerY < bounds.bottom - bounds.height * 0.2f &&
                strokes.any { other -> boundsOf(listOf(other)).let { it.bottom < b.centerY && it.left < b.right && it.right > b.left } } &&
                strokes.any { other -> boundsOf(listOf(other)).let { it.top > b.centerY && it.left < b.right && it.right > b.left } }
        }
        val strongMath = mathCue.containsMatchIn(label) || fractionLike
        if (!strongMath) {
            return if (inlineCue.containsMatchIn(label) || standaloneVariable.containsMatchIn(label) ||
                label.any(Char::isDigit)) null else escapeProse(label)
        }
        if (!proseWord.containsMatchIn(label)) {
            val latex = formula.recognize(strokes)?.takeIf { safeFormula(it, label) } ?: return null
            return "\$\$\n$latex\n\$\$"
        }
        val groups = InkLayout.clusters(strokes).takeIf { it.size in 2..30 } ?: return null
        val parts = mutableListOf<String>()
        for (group in groups) parts += text.recognize(group) ?: return null
        // Verify that the proposed segmentation accounts for the line recognizer's full output.
        fun compact(value: String) = value.filterNot(Char::isWhitespace).lowercase()
        if (compact(parts.joinToString("")) != compact(label)) return null
        val written = mutableListOf<String>()
        var index = 0
        var foundMath = false
        while (index < groups.size) {
            if (!candidate.matches(parts[index])) {
                written += escapeProse(parts[index++])
                continue
            }
            val start = index++
            while (index < groups.size && candidate.matches(parts[index])) index++
            val hint = parts.subList(start, index).joinToString(" ")
            if (!mathCue.containsMatchIn(hint)) return null
            val subset = groups.subList(start, index).flatten().toSet()
            val latex = formula.recognize(strokes.filter { it in subset })?.takeIf { safeFormula(it, hint) } ?: return null
            written += "\$$latex\$"
            foundMath = true
        }
        return written.joinToString(" ").takeIf { foundMath }
    }

    override fun close() {
        try { formula.close() } finally { text.close() }
    }

    companion object {
        private val proseWord = Regex("""\b[A-Za-z]{3,}\b""")
        private val mathCue = Regex("""[=+−×÷±<>≤≥≠∑∏√^*/]|\d\s*-\s*\d""")
        private val inlineCue = Regex("""\b(?:let|where|when|if|for)\s+[a-zA-Z]\b""", RegexOption.IGNORE_CASE)
        private val standaloneVariable = Regex("""(?:^|\s)[b-hj-zB-HJ-Z](?:[,.!?;:]+)?(?:\s|$)""")
        private val candidate = Regex("""(?:[A-Za-z]|[0-9]+(?:[.,][0-9]+)?|[=+−×÷±<>≤≥^*/(){}-]+|[A-Za-z0-9]+[=+−×÷^/*-][A-Za-z0-9=+−×÷^/*-]+)""")

        fun suspectedUnsupported(label: String): Boolean =
            label.any { it in "|∣∤¦‖≡" } || Regex("""\b(?:divides|divisible|congruent|mod)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(label) || Regex("""\b\w+\s+[lI/]\s+\w+\b""").containsMatchIn(label)

        fun safeFormula(latex: String, hint: String): Boolean {
            if (suspectedUnsupported(hint) || latex.any { it in "\$\n|" } || latex.contains("\\not")) return false
            val delimiters = ArrayDeque<Char>()
            val expectedClosing = mapOf('{' to '}', '(' to ')', '[' to ']')
            for (character in latex) {
                val closing = expectedClosing[character]
                if (closing != null) delimiters.addLast(closing)
                else if (character in expectedClosing.values && delimiters.removeLastOrNull() != character) return false
            }
            if (delimiters.isNotEmpty()) return false
            val hintedDigits = hint.filter { it in '0'..'9' }
            if (hintedDigits.isNotEmpty() && hintedDigits != latex.filter { it in '0'..'9' }) return false
            return listOf('=' to "=", '+' to "+", '±' to "\\pm", '≠' to "\\ne",
                '≤' to "\\le", '≥' to "\\ge", '∑' to "\\sum").all { (symbol, command) ->
                symbol !in hint || command in latex
            }
        }

        private fun escapeProse(value: String) = buildString {
            for (char in value) {
                if (char in "\\\$*_`#[]~") append('\\')
                append(char)
            }
        }
    }
}
