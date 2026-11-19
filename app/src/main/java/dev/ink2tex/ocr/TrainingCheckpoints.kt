package dev.ink2tex.ocr

import java.io.File
import java.util.UUID

object TrainingCheckpoints {
    const val ACTIVE = "handwriting-head-v1.bin"
    const val PREVIOUS = "handwriting-head-v1.previous.bin"

    // Called under the engine session lock. Same-directory renames publish complete files.
    fun promote(directory: File, candidate: File) {
        require(candidate.isFile && candidate.parentFile == directory) { "Missing or misplaced training checkpoint." }
        val current = File(directory, ACTIVE)
        if (current.isFile) copyAtomically(current, File(directory, PREVIOUS))
        check(candidate.renameTo(current)) { "Unable to install the trained weights." }
    }

    fun restore(directory: File) {
        val previous = File(directory, PREVIOUS)
        require(previous.isFile) { "No previous checkpoint is available." }
        copyAtomically(previous, File(directory, ACTIVE))
        previous.delete()
    }

    private fun copyAtomically(source: File, destination: File) {
        val staging = File(destination.parentFile, "weight-copy-${UUID.randomUUID()}.part")
        try {
            source.inputStream().use { input ->
                staging.outputStream().use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            check(staging.renameTo(destination)) { "Unable to save the handwriting checkpoint." }
        } finally { staging.delete() }
    }
}
