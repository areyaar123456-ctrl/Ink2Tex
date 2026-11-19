package dev.ink2tex.ocr

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.*
import dev.ink2tex.InkApplication
import dev.ink2tex.format.MathLayout
import dev.ink2tex.modelmanager.ModelStore
import dev.ink2tex.preprocessing.ImagePipeline
import dev.ink2tex.storage.TrainingLabel
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

class HandwritingTrainingWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as InkApplication
        val model = inputData.getString("model") ?: return Result.failure()
        return try {
            setForeground(WorkNotifications.foreground(
                applicationContext, id, 412, "Training handwriting weights",
                if (android.os.Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ))
            require(app.models.ready(model)) { "Install and select an OvisOCR2 model first." }
            val labels = app.database.trainingLabels().all()
            val (training, holdout) = withContext(Dispatchers.IO) { split(labels) }

            setProgress(workDataOf("stage" to "Waiting for the OCR engine"))
            app.engine.session.withLock {
                val directory = app.models.directory(model)
                val staged = File(directory, "handwriting-${id}.part")
                try {
                    setProgress(workDataOf("stage" to "Loading model"))
                    app.engine.loadModel(model)
                    app.engine.beginTraining()
                    training.forEachIndexed { index, sample ->
                        setProgress(workDataOf("stage" to "Reading training image ${index + 1} of ${training.size}"))
                        val image = withContext(Dispatchers.IO) { ImagePipeline.preview(File(sample.source), 1440) }
                        try {
                            app.engine.trainSample(image, trainingSource(sample.transcription))
                        } finally { image.recycle() }
                    }
                    setProgress(workDataOf("stage" to "Fitting output weights"))
                    if (app.engine.fitTraining() == 0) {
                        return@withLock Result.success(workDataOf("summary" to "The base model already predicted the training tokens. Kept the active weights."))
                    }

                    val baselineErrors = mutableListOf<Int>()
                    val candidateErrors = mutableListOf<Int>()
                    holdout.forEachIndexed { index, sample ->
                        val image = withContext(Dispatchers.IO) { ImagePipeline.preview(File(sample.source), 1440) }
                        try {
                            val expected = trainingSource(sample.transcription)
                            setProgress(workDataOf("stage" to "Checking active weights on image ${index + 1} of ${holdout.size}"))
                            val baseline = app.engine.recognize(image, RecognitionOptions(maxTokens = 1024, weights = HandwritingWeights.ACTIVE))
                            setProgress(workDataOf("stage" to "Checking new weights on image ${index + 1} of ${holdout.size}"))
                            val proposed = app.engine.recognize(image, RecognitionOptions(maxTokens = 1024, weights = HandwritingWeights.CANDIDATE))
                            baselineErrors += TrainingEvaluation.errors(expected, baseline.markdown)
                            candidateErrors += if (proposed.truncated || proposed.markdown.isBlank()) {
                                Int.MAX_VALUE / 8
                            } else TrainingEvaluation.errors(expected, proposed.markdown)
                        } finally { image.recycle() }
                    }
                    val improved = TrainingEvaluation.accept(baselineErrors, candidateErrors)
                    val summary = if (improved) {
                        app.engine.saveCandidate(staged)
                        currentCoroutineContext().ensureActive()
                        check(staged.renameTo(File(directory, WEIGHTS))) { "Unable to install the trained weights." }
                        "Saved new weights. Held-out character edits: ${baselineErrors.sum()} → ${candidateErrors.sum()} on ${holdout.size} image(s)."
                    } else {
                        "Kept the active weights. The candidate did not improve the held-out images without a regression."
                    }
                    Result.success(workDataOf("summary" to summary))
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        staged.delete()
                        app.engine.unloadModel()
                    }
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: OutOfMemoryError) {
            Result.failure(workDataOf("summary" to "Training ran out of memory. Close other large apps or use smaller snips."))
        } catch (failure: Exception) {
            Result.failure(workDataOf("summary" to (failure.message ?: "Training failed.").take(2000)))
        }
    }

    private suspend fun split(labels: List<TrainingLabel>): Pair<List<TrainingLabel>, List<TrainingLabel>> {
        val heldOutDocuments = labels.filter { it.evaluation }.map { it.documentId }.toSet()
        val holdout = labels.filter { it.evaluation }.take(3)
        require(holdout.isNotEmpty()) { "Mark a separate image for evaluation before training." }
        val evaluationHashes = labels.filter { it.evaluation }.map { ModelStore.sha256(File(it.source)) }.toSet()
        val seen = evaluationHashes.toMutableSet()
        val training = mutableListOf<TrainingLabel>()
        for (label in labels) {
            currentCoroutineContext().ensureActive()
            if (label.documentId in heldOutDocuments) continue
            if (!File(label.source).isFile || !seen.add(ModelStore.sha256(File(label.source)))) continue
            training += label
            if (training.size == 12) break
        }
        require(training.isNotEmpty()) { "Add a reviewed training image from a different note." }
        require((training + holdout).all { it.transcription.encodeToByteArray().size <= 12_000 }) {
            "Use shorter training snips. A batch accepts labels up to 12,000 UTF-8 bytes and 768 model tokens per training image."
        }
        return training to holdout
    }

    private fun trainingSource(text: String): String =
        MathLayout.displayStandalone(LocalOcrEngine.normalizeDelimiters(text)).trim()

    companion object {
        const val NAME = "handwriting-training"
        const val WEIGHTS = "handwriting-head-v1.bin"

        fun enqueue(context: Context, modelId: String) {
            val request = OneTimeWorkRequestBuilder<HandwritingTrainingWorker>()
                .setInputData(workDataOf("model" to modelId)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
