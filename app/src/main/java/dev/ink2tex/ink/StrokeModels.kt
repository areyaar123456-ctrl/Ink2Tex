package dev.ink2tex.ink

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import dev.ink2tex.InkApplication
import dev.ink2tex.modelmanager.ModelStore
import dev.ink2tex.ocr.WorkNotifications
import java.io.File
import java.net.HttpURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

internal object StrokeModels {
    const val HYBRID = "ink-mlkit-abel"
    const val MYSCRIPT = "ink-myscript"
    const val BYTES = 15_549_904L
    const val DIGEST = "4e247ea7fa410fdccbfa349df53de5895c94da2b65ae6268c699871bee8b24b3"
    const val REVISION = "41e0eb791b0dcf88deb8c5461bc91bf0315dc90d"
    private const val WEIGHTS = "math_ink.ablm"
    private const val URL = "https://raw.githubusercontent.com/abelnotes/abelnotes/$REVISION/assets/models/$WEIGHTS"

    fun isStroke(id: String) = id == HYBRID || id == MYSCRIPT
    fun name(id: String) = when (id) {
        HYBRID -> "ML Kit + Abel Notes"
        MYSCRIPT -> "MyScript iink"
        else -> id
    }

    fun directory(context: Context) = File(context.filesDir, "stroke-model")
    fun ready(context: Context): Boolean {
        val dir = directory(context)
        return File(dir, "ready").isFile && File(dir, WEIGHTS).length() == BYTES
    }

    fun english(): DigitalInkRecognitionModel = DigitalInkRecognitionModel.builder(
        requireNotNull(DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US"))
    ).build()

    fun setup(context: Context) {
        val request = OneTimeWorkRequestBuilder<StrokeModelWorker>()
            .addTag("models").addTag(HYBRID)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(HYBRID, ExistingWorkPolicy.KEEP, request)
    }

    suspend fun remove(app: InkApplication) {
        withContext(Dispatchers.IO) {
            app.engine.session.withLock {
                directory(app).deleteRecursively()
                RemoteModelManager.getInstance().deleteDownloadedModel(english()).await()
            }
        }
        app.models.changed()
    }

    suspend fun prepare(context: Context, progress: suspend (Long) -> Unit): File {
        val dir = directory(context).apply { mkdirs() }
        val weights = File(dir, WEIGHTS)
        if (weights.length() != BYTES || ModelStore.sha256(weights) != DIGEST) {
            val part = File(dir, "$WEIGHTS.part")
            val connection = java.net.URI(URL).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            try {
                require(connection.responseCode == HttpURLConnection.HTTP_OK) { "The formula model download failed." }
                connection.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        var nextProgress = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= BYTES) { "The formula model exceeded its expected size." }
                            output.write(buffer, 0, count)
                            if (total >= nextProgress) {
                                progress(total)
                                nextProgress = total + 512 * 1024
                            }
                        }
                        require(total == BYTES) { "The formula model download was incomplete." }
                    }
                }
                require(ModelStore.sha256(part) == DIGEST) { "Formula model checksum mismatch." }
                check(part.renameTo(weights)) { "Unable to install the formula model." }
            } finally {
                connection.disconnect()
                part.delete()
            }
        }
        for (name in listOf("encoder.onnx", "decoder.onnx")) {
            context.assets.open("ink/$name").use { input ->
                File(dir, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
        return dir
    }
}

internal class StrokeModelWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as InkApplication
        val ready = File(StrokeModels.directory(app), "ready")
        try {
            setForeground(WorkNotifications.foreground(app, id, 413, "Setting up stroke recognition",
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            app.engine.session.withLock {
                ready.delete()
                val dir = StrokeModels.prepare(app) {
                    setProgress(workDataOf("stage" to "Downloading Abel Notes weights", "bytes" to it, "total" to StrokeModels.BYTES))
                }
                setProgress(workDataOf("stage" to "Installing English digital ink"))
                val model = StrokeModels.english()
                RemoteModelManager.getInstance().download(model, DownloadConditions.Builder().build()).await()
                setProgress(workDataOf("stage" to "Validating local stroke inference"))
                AbelFormula.open(app, dir).use { AbelFormula.smoke(it) }
                GoogleStrokeText().use { it.validate() }
                currentCoroutineContext().ensureActive()
                check(ready.createNewFile()) { "Unable to save model validation." }
            }
            app.models.changed()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            Result.failure(workDataOf("error" to (failed.message ?: "Stroke model setup failed.").take(1000)))
        }
    }
}
