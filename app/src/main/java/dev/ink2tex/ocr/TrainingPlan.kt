package dev.ink2tex.ocr

import dev.ink2tex.storage.CropOrigin
import dev.ink2tex.storage.Document
import dev.ink2tex.storage.TrainingLabel
import dev.ink2tex.storage.TrainingRun
import java.security.MessageDigest

data class CheckedImage(val label: TrainingLabel, val imageHash: String) {
    val revision: String by lazy { digest(imageHash + "\u0000" + label.transcription) }
    val identity: String get() = "${label.documentId}:$imageHash"
    val historyKey: String get() = "$identity:$revision"
}

data class TrainingPlan(
    val fit: List<CheckedImage> = emptyList(),
    val evaluate: List<CheckedImage> = emptyList(),
    val reserved: Set<String> = emptySet(),
    val newImages: Int = 0,
    val reservedNotes: Set<String> = emptySet(),
    val problem: String? = null
) {
    val key: String get() = digest("output-v2\n" + fit.joinToString("\n") { it.historyKey } +
        "\n--evaluation--\n" + evaluate.joinToString("\n") { it.historyKey })
}

object TrainingPlanner {
    fun create(
        labels: List<CheckedImage>,
        documents: List<Document>,
        origins: List<CropOrigin>,
        runs: List<TrainingRun>,
        modelId: String,
        pins: Set<String>
    ): TrainingPlan {
        if (labels.isEmpty()) return TrainingPlan(problem = "Check the source of your first image.")
        val groups = Groups()
        for (doc in documents) {
            doc.source.lineSequence().filter(String::isNotBlank).forEach { groups.link("note:${doc.id}", "file:$it") }
        }
        for (origin in origins) groups.link("file:${origin.source}", "file:${origin.parent}")
        for (sample in labels) {
            val note = "note:${sample.label.documentId}"
            groups.link(note, "file:${sample.label.source}")
            groups.link(note, "hash:${sample.imageHash}")
        }
        val grouped = labels.groupBy { groups.root("note:${it.label.documentId}") }
        val allSeen = runs.filter { it.evaluated }.flatMap { it.trainingKeys.lines() }
            .mapNotNull { line -> line.split(':').takeIf { it.size == 3 } }
        // Keep known related notes, crop descendants and byte-identical copies outside the evaluation.
        val previouslyTrained = allSeen.flatMap { listOf(groups.root("note:${it[0]}"), groups.root("hash:${it[1]}")) }.toSet()
        val created = documents.associate { it.id to it.created }
        val orderedGroups = grouped.entries.sortedWith(compareBy(
            { group -> group.value.minOf { created[it.label.documentId] ?: it.label.updated } },
            { group -> group.value.minOf { it.label.documentId } }
        ))
        val manual = orderedGroups.filter { group -> group.value.any { it.label.evaluation } }
            .map { it.key }.toSet()
        if (manual.any { it in previouslyTrained }) return TrainingPlan(
            problem = "An evaluation image is linked to a note already used for training. Unreserve its marked images and check a separate note."
        )
        val reserved = manual + orderedGroups.filter { group ->
            group.key !in previouslyTrained &&
                group.value.any { "note:${it.label.documentId}" in pins || "sha:${it.imageHash}" in pins }
        }.map { it.key }
        val heldOutGroups = if (reserved.isEmpty()) {
            if (orderedGroups.size < 2) return TrainingPlan(problem = "Check one more image from a different note. Ink2TeX will reserve it to check the result.")
            val selected = (orderedGroups.drop(1) + orderedGroups.first()).firstOrNull { it.key !in previouslyTrained }
                ?: return TrainingPlan(problem = "Check an image from a fresh note for evaluation.")
            setOf(selected.key)
        } else reserved

        val pool = labels.filterNot { groups.root("note:${it.label.documentId}") in heldOutGroups }
            .sortedWith(compareByDescending<CheckedImage> { it.label.updated }.thenBy { it.identity })
            .distinctBy { it.imageHash }
        val evaluation = labels.filter { groups.root("note:${it.label.documentId}") in heldOutGroups }
            .sortedWith(compareByDescending<CheckedImage> { "sha:${it.imageHash}" in pins }.thenByDescending { it.label.evaluation }
                .thenBy { created[it.label.documentId] ?: it.label.updated }.thenBy { it.identity })
            .distinctBy { it.imageHash }.take(3)
        val reservedNotes = labels.filter { groups.root("note:${it.label.documentId}") in heldOutGroups }
            .map { it.label.documentId }.toSet()
        val pinKeys = evaluation.flatMap { listOf("note:${it.label.documentId}", "sha:${it.imageHash}") }.toSet()
        if (pool.isEmpty()) return TrainingPlan(evaluate = evaluation, reserved = pinKeys, reservedNotes = reservedNotes,
            problem = "These images all belong to evaluation notes. Check an image from a separate note for training.")

        val seen = runs.filter { it.modelId == modelId && it.evaluated }
            .flatMap { it.trainingKeys.lines() }.map { it.substringAfterLast(':') }.toSet()
        val new = pool.filterNot { it.revision in seen }
            .sortedWith(compareBy<CheckedImage> { it.label.updated }.thenBy { it.identity })
        if (new.isEmpty()) return TrainingPlan(evaluate = evaluation, reserved = pinKeys, reservedNotes = reservedNotes,
            problem = "This model has used all eligible labels. Check another image or revise an existing source.")
        val replay = pool.filter { it.revision in seen }.take(2)
        val fit = new.take(12 - replay.size) + replay
        val long = (fit + evaluation).firstOrNull { it.label.transcription.encodeToByteArray().size > 12_000 }
        if (long != null) return TrainingPlan(evaluate = evaluation, reserved = pinKeys, reservedNotes = reservedNotes,
            problem = "A selected source is too long. Review it as smaller snips before training.")
        return TrainingPlan(fit, evaluation, pinKeys, new.size, reservedNotes)
    }

    private class Groups {
        private val parents = mutableMapOf<String, String>()
        fun root(key: String): String {
            var current = key
            while (parents[current] != null) current = parents.getValue(current)
            if (current != key) parents[key] = current
            return current
        }
        fun link(a: String, b: String) {
            val rootA = root(a)
            val rootB = root(b)
            if (rootA != rootB) parents[rootB] = rootA
        }
    }
}

private fun digest(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
