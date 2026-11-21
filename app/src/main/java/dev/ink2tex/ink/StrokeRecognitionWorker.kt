package dev.ink2tex.ink

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.ink2tex.InkApplication
import dev.ink2tex.modelmanager.ModelStore
import dev.ink2tex.ocr.RecognitionOptions
import dev.ink2tex.ocr.WorkNotifications
import dev.ink2tex.stitching.ProofSequence
import dev.ink2tex.storage.TrainingDraft
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class StrokeRecognitionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as InkApplication
        val documentId = inputData.getString("document") ?: return@withContext Result.failure()
        val modelId = inputData.getString("model") ?: return@withContext Result.failure()
        val documents = app.repository.documents
        val document = documents.get(documentId) ?: return@withContext Result.failure()
        val sources = document.source.lines().filter(String::isNotBlank)
        try {
            require(sources.isNotEmpty() && sources.all { it.endsWith(VectorImport.PREVIEW_SUFFIX) }) {
                "Choose a note imported from a supported Nebo export."
            }
            setForeground(WorkNotifications.foreground(app, id, 414, "Reading Nebo strokes",
                if (android.os.Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0))
            documents.state(documentId, "Queued", null)
            setProgress(workDataOf("stage" to "Waiting for the local recognition engine"))
            app.engine.session.withLock {
                val start = android.os.SystemClock.elapsedRealtime()
                documents.state(documentId, "Converting", null)
                var imageModelLoaded = false
                try {
                    suspend fun imageFallback(page: InkPage): StrokeResult {
                        val chosen = app.repository.settings.flow.first().model
                        val ready = app.models.available()
                        val selected = ready.firstOrNull { it.id == chosen } ?: ready.firstOrNull()
                            ?: return StrokeResult("", listOf("Install OvisOCR2 in Model Store to rerun uncertain lines."), incomplete = true)
                        try {
                            if (!imageModelLoaded) {
                                app.engine.loadModel(selected.id, false, 1600)
                                imageModelLoaded = true
                            }
                            val bitmap = InkRaster.render(page)
                            try {
                                val result = app.engine.recognize(bitmap, RecognitionOptions(maxTokens = 1024))
                                return StrokeResult(result.markdown, incomplete = result.truncated || result.markdown.isBlank())
                            } finally { bitmap.recycle() }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            return StrokeResult("", listOf("Image OCR failed for this line group."), incomplete = true)
                        }
                    }

                    setProgress(workDataOf("stage" to "Loading ${StrokeModels.name(modelId)}"))
                    val engine = when (modelId) {
                        StrokeModels.HYBRID -> {
                            require(StrokeModels.ready(app)) { "Set up ML Kit + Abel Notes in Model Store first." }
                            val dir = StrokeModels.directory(app)
                            require(ModelStore.sha256(File(dir, "math_ink.ablm")) == StrokeModels.DIGEST) {
                                "The installed formula weights are damaged. Set up the stroke models again."
                            }
                            val text = GoogleStrokeText()
                            try {
                                HybridStrokeRecognizer(text, AbelFormula.open(app, dir), ::imageFallback)
                            } catch (failure: Throwable) {
                                text.close()
                                throw failure
                            }
                        }
                        StrokeModels.MYSCRIPT -> MyScriptStroke.open(app)
                        else -> error("Unknown stroke recognizer.")
                    }
                    val warnings = mutableListOf("Review the ink against MyScript Notes. The imported preview shows pen strokes.")
                    val drafts = mutableListOf<TrainingDraft>()
                    engine.use {
                        for ((index, path) in sources.withIndex()) {
                            currentCoroutineContext().ensureActive()
                            setProgress(workDataOf("stage" to "Page ${index + 1} of ${sources.size} · ${StrokeModels.name(modelId)}"))
                            val preview = File(path).canonicalFile
                            require(preview.parentFile == app.repository.images.canonicalFile) { "The ink page is unavailable." }
                            val page = NeboInk.read(NeboInk.sidecar(preview))
                            val result = engine.recognize(page)
                            warnings += result.warnings.map { "Page ${index + 1} · $it" }
                            drafts += TrainingDraft(documentId, path, result.markdown, result.incomplete)
                        }
                    }
                    val joined = ProofSequence.merge(drafts.map { it.text })
                    require(joined.text.isNotBlank()) { "The Nebo note contains no recognizable strokes." }
                    val elapsed = android.os.SystemClock.elapsedRealtime() - start
                    app.database.withTransaction {
                        val current = documents.get(documentId) ?: error("This note has been deleted.")
                        require(current.source == document.source) { "The note's originals changed during recognition." }
                        documents.put(current.copy(markdown = joined.text, modelId = modelId,
                            status = if (drafts.any { it.incomplete }) "Needs review" else "Review",
                            error = null, warnings = warnings.distinct().take(120).joinToString("\n"), durationMs = elapsed,
                            tokens = null))
                        app.database.trainingHistory().removeDrafts(documentId)
                        app.database.trainingHistory().putDrafts(drafts)
                    }
                    Result.success()
                } finally {
                    if (imageModelLoaded || app.engine.diagnostics.value.loadedModel != null) {
                        withContext(NonCancellable) { app.engine.unloadModel() }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { documents.state(documentId, "Cancelled", "Stroke conversion cancelled.") }
            throw cancelled
        } catch (failure: LinkageError) {
            documents.state(documentId, "Failed", "This tablet could not load the selected stroke runtime.")
            Result.failure()
        } catch (failure: OutOfMemoryError) {
            documents.state(documentId, "Failed", "This note exceeds the available memory. Export fewer pages at a time.")
            Result.failure()
        } catch (failure: Exception) {
            val message = (failure.message ?: "Stroke recognition failed.").take(1000)
            documents.state(documentId, "Failed", message)
            Result.failure(workDataOf("error" to message))
        }
    }

    companion object {
        fun enqueue(context: Context, documentId: String, modelId: String) {
            val request = OneTimeWorkRequestBuilder<StrokeRecognitionWorker>()
                .setInputData(workDataOf("document" to documentId, "model" to modelId))
                .addTag("ocr").addTag(documentId).build()
            WorkManager.getInstance(context).enqueueUniqueWork("ocr-$documentId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
