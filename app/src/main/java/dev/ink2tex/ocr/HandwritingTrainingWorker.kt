package dev.ink2tex.ocr

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.*
import dev.ink2tex.InkApplication
import dev.ink2tex.format.MathLayout
import dev.ink2tex.preprocessing.ImagePipeline
import dev.ink2tex.storage.TrainingRun
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class HandwritingTrainingWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as InkApplication
        val model = inputData.getString("model") ?: return Result.failure()
        val history = app.database.trainingHistory()
        var record: TrainingRun? = null
        return try {
            setForeground(WorkNotifications.foreground(
                applicationContext, id, 412, "Training handwriting weights",
                if (android.os.Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ))
            require(app.models.ready(model)) { "Install and select an OvisOCR2 model first." }
            val plan = app.repository.training.plan(model, verifyImages = true)
            require(plan.problem == null) { plan.problem.orEmpty() }
            inputData.getString("plan")?.let {
                require(it == plan.key) { "The checked images changed. Open Training to review the new batch." }
            }
            app.repository.training.pinEvaluation(plan)
            val started = System.currentTimeMillis()
            val startedRecord = TrainingRun(id.toString(), model, started, null, "running", plan.key,
                plan.fit.size, plan.evaluate.size, plan.fit.joinToString("\n") { it.historyKey }, "[]", "")
            record = startedRecord
            history.put(startedRecord)
            val stepCount = plan.fit.size + 1 + plan.evaluate.size * 2
            var completed = 0
            suspend fun progress(stage: String) {
                setProgress(workDataOf("stage" to stage, "completed" to completed, "total" to stepCount))
            }
            progress("Waiting for the OCR engine")
            val trial = app.engine.session.withLock {
                val directory = app.models.directory(model)
                val staged = File(directory, "handwriting-${id}.part")
                try {
                    progress("Loading model")
                    app.engine.loadModel(model)
                    app.engine.beginTraining()
                    plan.fit.forEachIndexed { index, sample ->
                        progress("Reading training image ${index + 1} of ${plan.fit.size}")
                        val image = withContext(Dispatchers.IO) { ImagePipeline.preview(File(sample.label.source), 1440) }
                        try {
                            app.engine.trainSample(image, trainingSource(sample.label.transcription))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error("Training image ${index + 1}: ${failure.message ?: "Unable to read it"}")
                        } finally { image.recycle() }
                        completed++
                    }
                    progress("Fitting output weights")
                    if (app.engine.fitTraining() == 0) {
                        return@withLock Trial("unchanged", "The model already predicted the training tokens. Kept the current weights.")
                    }
                    completed++

                    val baselineErrors = mutableListOf<Int>()
                    val candidateErrors = mutableListOf<Int>()
                    val baselineMath = mutableListOf<Int>()
                    val candidateMath = mutableListOf<Int>()
                    val scorecard = JSONArray()
                    plan.evaluate.forEachIndexed { index, sample ->
                        val image = withContext(Dispatchers.IO) { ImagePipeline.preview(File(sample.label.source), 1440) }
                        try {
                            val expected = trainingSource(sample.label.transcription)
                            val step = "image ${index + 1} of ${plan.evaluate.size}"
                            progress("Checking current weights · $step")
                            val baseline = app.engine.recognize(image,
                                RecognitionOptions(maxTokens = 1024, weights = HandwritingWeights.ACTIVE))
                            completed++
                            progress("Checking new weights · $step")
                            val proposed = app.engine.recognize(image,
                                RecognitionOptions(maxTokens = 1024, weights = HandwritingWeights.CANDIDATE))
                            completed++
                            val before = TrainingEvaluation.errors(expected, baseline.markdown)
                            val after = if (proposed.truncated || proposed.markdown.isBlank()) null
                            else TrainingEvaluation.errors(expected, proposed.markdown)
                            baselineErrors += before
                            candidateErrors += after ?: Int.MAX_VALUE / 8
                            val beforeMath = TrainingEvaluation.mathErrors(expected, baseline.markdown)
                            val afterMath = after?.let { TrainingEvaluation.mathErrors(expected, proposed.markdown) }
                            baselineMath += beforeMath
                            candidateMath += afterMath ?: Int.MAX_VALUE / 8
                            scorecard.put(JSONObject().put("beforeMath", beforeMath).put("afterMath", afterMath ?: JSONObject.NULL).put("before", before).put("after", after ?: JSONObject.NULL)
                                .put("currentMs", baseline.durationMs).put("newMs", proposed.durationMs))
                        } finally { image.recycle() }
                    }
                    if (TrainingEvaluation.accept(baselineErrors, candidateErrors, baselineMath, candidateMath)) {
                        progress("Saving the improved checkpoint")
                        app.engine.saveCandidate(staged)
                        currentCoroutineContext().ensureActive()
                        val accepted = Trial("promoted",
                            "Saved new weights. Held-out text edits: ${baselineErrors.sum()} → ${candidateErrors.sum()}. Math edits: ${baselineMath.sum()} → ${candidateMath.sum()}.", scorecard)
                        withContext(NonCancellable + Dispatchers.IO) {
                            TrainingCheckpoints.promote(directory, staged)
                            history.put(startedRecord.completed(accepted))
                            record = null
                        }
                        accepted
                    } else {
                        Trial("kept", "Kept current weights. The candidate tied, worsened, or left an evaluation image incomplete.", scorecard)
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        staged.delete()
                        app.engine.unloadModel()
                    }
                }
            }
            withContext(NonCancellable) {
                record?.let { history.put(it.completed(trial)) }
            }
            Result.success(workDataOf("summary" to trial.summary))
        } catch (failure: CancellationException) {
            withContext(NonCancellable) {
                record?.let { history.put(it.copy(finished = System.currentTimeMillis(), outcome = "cancelled", summary = "Training cancelled.")) }
            }
            throw failure
        } catch (failure: OutOfMemoryError) {
            val message = "Training ran out of memory. Close other large apps or use smaller snips."
            record?.let { history.put(it.copy(finished = System.currentTimeMillis(), outcome = "failed", summary = message)) }
            Result.failure(workDataOf("summary" to message))
        } catch (failure: Exception) {
            val message = (failure.message ?: "Training failed.").take(2000)
            record?.let { history.put(it.copy(finished = System.currentTimeMillis(), outcome = "failed", summary = message)) }
            Result.failure(workDataOf("summary" to message))
        }
    }

    private fun TrainingRun.completed(trial: Trial) = copy(
        finished = System.currentTimeMillis(), outcome = trial.outcome,
        summary = trial.summary, scorecard = trial.scorecard.toString()
    )

    private data class Trial(val outcome: String, val summary: String, val scorecard: JSONArray = JSONArray())

    private fun trainingSource(text: String): String =
        MathLayout.displayStandalone(LocalOcrEngine.normalizeDelimiters(text)).trim()

    companion object {
        const val NAME = "handwriting-training"

        fun enqueue(context: Context, modelId: String, plan: TrainingPlan) {
            val request = OneTimeWorkRequestBuilder<HandwritingTrainingWorker>()
                .setInputData(workDataOf("model" to modelId, "plan" to plan.key)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
