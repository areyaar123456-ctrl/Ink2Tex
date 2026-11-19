package dev.ink2tex

import dev.ink2tex.ocr.CheckedImage
import dev.ink2tex.ocr.TrainingCheckpoints
import dev.ink2tex.ocr.TrainingPlanner
import dev.ink2tex.ocr.TrainingEvaluation
import dev.ink2tex.storage.CropOrigin
import dev.ink2tex.storage.Document
import dev.ink2tex.storage.TrainingLabel
import dev.ink2tex.storage.TrainingRun
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files

class TrainingPlannerTest {
    @Test fun twoIndependentImagesMakeABatchAndTheReservedImageStaysPinned() {
        val first = image("first", time = 10)
        val second = image("second", time = 20)
        assertTrue(plan(listOf(first)).problem!!.contains("different note"))
        val ready = plan(listOf(first, second))
        assertNull(ready.problem)
        assertEquals(listOf(first), ready.fit)
        assertEquals(listOf(second), ready.evaluate)
        val earlierImportedNote = image("old-note", time = 5)
        assertEquals(listOf(second), plan(listOf(first, second, earlierImportedNote), pins = ready.reserved).evaluate)
    }

    @Test fun aliasesAndCropsOfTheSameImageCannotLeakIntoEvaluation() {
        val a = image("a")
        val copy = image("copy", hash = a.imageHash)
        assertNotNull(plan(listOf(a, copy)).problem)
        val crop = image("crop")
        assertNotNull(plan(listOf(a,crop), origins = listOf(CropOrigin(crop.label.source, a.label.source))).problem)
        val b = image("b", time=20)
        val split = plan(listOf(a, copy, b))
        assertEquals(listOf(b), split.evaluate)
        assertEquals(1, split.fit.size)
    }

    @Test fun nextBatchesPrioritizeNewRevisionsAndUseOnlyBoundedReplay() {
        val a = image("a")
        val b = image("b", time = 20)
        val first = plan(listOf(a,b))
        val run = completed(first.fit)
        val done = plan(listOf(a,b), runs=listOf(run), pins=first.reserved)
        assertTrue(done.problem!!.contains("used all eligible labels"))
        val nextSamples = (30L..45L).map { image("proof-$it", time = it) }
        val next = plan(listOf(a,b) + nextSamples, runs=listOf(run), pins=first.reserved)
        assertEquals(12, next.fit.size)
        assertEquals(nextSamples.first(), next.fit.first())
        assertEquals(a, next.fit.last())
        assertEquals(16, next.newImages)
        val revised = a.copy(label = a.label.copy(transcription="Corrected sign"))
        assertEquals(listOf(revised), plan(listOf(revised,b), runs=listOf(run), pins=first.reserved).fit)
    }

    @Test fun previouslyTrainedCopiesCannotBecomeEvaluationExamples() {
        val a = image("a")
        val test = image("test",time=20)
        val previous = plan(listOf(a,test))
        val duplicate = image("another-document",hash=a.imageHash).let { it.copy(label=it.label.copy(evaluation=true)) }
        val blocked = plan(listOf(a,test,duplicate),runs=listOf(completed(previous.fit)),pins=previous.reserved)
        assertTrue(blocked.problem!!.contains("already used for training"))
    }

    @Test fun improvedProseCannotMaskChangedMath() {
        val expected = "The answer is \$p \\nmid x^{2023}\$."
        val old = "T answer is \$p \\nmid x^{2023}\$."
        val biased = "The answer is \$p \\mid x^{2023}\$."
        val before = TrainingEvaluation.mathErrors(expected, old)
        val after = TrainingEvaluation.mathErrors(expected, biased)
        assertEquals(0, before)
        assertTrue(after > before)
        assertFalse(TrainingEvaluation.accept(listOf(4), listOf(1), listOf(before), listOf(after)))
        assertTrue(TrainingEvaluation.accept(listOf(2), listOf(2), listOf(1), listOf(0)))
        assertEquals(0, TrainingEvaluation.mathErrors("\$\$x^{2023}\$\$", "\$x^{2023}\$"))
    }

    @Test fun checkpointPromotionKeepsTheLastWeightsAvailableForRestore() {
        val directory = Files.createTempDirectory("tested-head").toFile()
        try {
            val active = File(directory, TrainingCheckpoints.ACTIVE).apply { writeText("last verified weights") }
            val candidate = File(directory, "candidate.part").apply { writeText("better weights") }
            TrainingCheckpoints.promote(directory, candidate)
            assertEquals("better weights", active.readText())
            assertEquals("last verified weights", File(directory, TrainingCheckpoints.PREVIOUS).readText())
            TrainingCheckpoints.restore(directory)
            assertEquals("last verified weights", active.readText())
            assertFalse(File(directory, TrainingCheckpoints.PREVIOUS).exists())
        } finally { directory.deleteRecursively() }
    }

    private fun image(id: String, hash: String = id, time: Long = 10): CheckedImage =
        CheckedImage(TrainingLabel(id, "/image/$id", "Source $id", "accepted", false, time), hash)

    private fun plan(
        labels: List<CheckedImage>,
        runs: List<TrainingRun> = emptyList(),
        origins: List<CropOrigin> = emptyList(),
        pins: Set<String> = emptySet()
    ) = TrainingPlanner.create(labels,
        labels.map { Document(it.label.documentId, "Proof", it.label.updated, it.label.source) },
        origins, runs, "ovis2-q8", pins)

    private fun completed(labels: List<CheckedImage>) = TrainingRun("r", "ovis2-q8", 1, 2, "kept", "plan",
        labels.size, 1, labels.joinToString("\n") { it.historyKey }, "[]", "Checked")
}
