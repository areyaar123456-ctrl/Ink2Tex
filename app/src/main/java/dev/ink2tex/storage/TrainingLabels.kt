package dev.ink2tex.storage

import android.content.Context
import android.net.Uri
import androidx.room.*
import dev.ink2tex.modelmanager.ModelStore
import dev.ink2tex.preprocessing.ImagePipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Entity(tableName = "training_labels", primaryKeys = ["documentId", "source"])
data class TrainingLabel(
    val documentId: String,
    val source: String,
    val transcription: String,
    val review: String,
    val evaluation: Boolean,
    val updated: Long = System.currentTimeMillis()
)

@Dao
interface TrainingLabelDao {
    @Query("SELECT * FROM training_labels ORDER BY updated DESC")
    fun observe(): Flow<List<TrainingLabel>>

    @Query("SELECT * FROM training_labels ORDER BY updated DESC")
    suspend fun all(): List<TrainingLabel>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(label: TrainingLabel)

    @Query("DELETE FROM training_labels WHERE documentId = :documentId AND source = :source")
    suspend fun remove(documentId: String, source: String)

    @Query("DELETE FROM training_labels WHERE documentId = :documentId")
    suspend fun removeDocument(documentId: String)
}

class TrainingLabels(
    private val context: Context,
    private val database: InkDatabase,
    private val images: File
) {
    val labels = database.trainingLabels().observe()

    suspend fun save(label: TrainingLabel) = withContext(Dispatchers.IO) {
        require(label.transcription.isNotBlank() && label.transcription.length <= 40_000) {
            "Enter the exact source for one image, up to 40,000 characters."
        }
        require(label.review in setOf("accepted", "corrected"))
        val image = checkedImage(label.source)
        val (width, height) = ImagePipeline.bounds(image)
        require(width > 0 && height > 0 && width.toLong() * height <= 4_000_000) {
            "Choose a smaller snip for training. Each image can contain up to four megapixels."
        }
        database.withTransaction {
            val document = database.documents().get(label.documentId)
            require(document != null && label.source in document.source.lines() && image.isFile) {
                "The original image is no longer available."
            }
            database.trainingLabels().put(label)
        }
    }

    suspend fun remove(label: TrainingLabel) =
        database.trainingLabels().remove(label.documentId, label.source)

    suspend fun export(uri: Uri): Int = withContext(Dispatchers.IO) {
        val labels = database.trainingLabels().all()
        require(labels.isNotEmpty()) { "Review at least one image before exporting." }
        require(labels.size <= 5000) { "Export up to 5,000 labeled images per batch." }
        require(labels.sumOf { it.transcription.length.toLong() } <= 4_000_000L) {
            "This batch contains too much source text. Export fewer labels at a time."
        }
        val totalBytes = labels.sumOf { checkedImage(it.source).length() }
        require(totalBytes <= 2_000_000_000L) { "This batch exceeds 2 GB. Remove some training labels before exporting." }
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("Unable to open the training archive.")
        val jsonl = StringBuilder()
        val exported = mutableSetOf<String>()
        ZipOutputStream(output.buffered()).use { archive ->
            for (label in labels) {
                currentCoroutineContext().ensureActive()
                val image = checkedImage(label.source)
                val hash = ModelStore.sha256(image)
                val name = "images/$hash.image"
                if (exported.add(hash)) {
                    archive.putNextEntry(ZipEntry(name))
                    image.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            archive.write(buffer, 0, count)
                        }
                    }
                    archive.closeEntry()
                }
                jsonl.append(JSONObject().put("image", name).put("sha256", hash)
                    .put("group", label.documentId).put("text", label.transcription)
                    .put("review", label.review).put("evaluation", label.evaluation)).append('\n')
            }
            archive.putNextEntry(ZipEntry("labels.jsonl"))
            archive.write(jsonl.toString().encodeToByteArray())
            archive.closeEntry()
        }
        labels.size
    }

    private fun checkedImage(source: String): File {
        val file = File(source).canonicalFile
        require(file.parentFile == images.canonicalFile && file.isFile && file.length() in 1..16_777_216L) {
            "An original image is missing or exceeds 16 MiB. Remove its label or use a smaller snip."
        }
        return file
    }
}
