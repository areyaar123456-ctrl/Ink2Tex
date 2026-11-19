package dev.ink2tex.storage

import android.content.Context
import android.graphics.Bitmap
import dev.ink2tex.preprocessing.ImagePipeline
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class InkRepository(val context: Context, val database: InkDatabase) {
 val documents = database.documents()
 val history = documents.observe()
 val settings = Settings(context)
 val handwriting = HandwritingExamples(context)
 val images = File(context.filesDir, "images").apply { mkdirs() }
 suspend fun save(bitmap: Bitmap, title: String = "Handwritten note"): Document = withContext(Dispatchers.IO) {
  val id = UUID.randomUUID().toString()
  val file = File(images, "$id.png")
  file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
  Document(id, title, source = file.absolutePath).also { documents.put(it) }
 }
 suspend fun teachHandwriting(documentId: String, correctedText: String) = withContext(Dispatchers.IO) {
  val document = documents.get(documentId) ?: error("This note is no longer available.")
  val path = document.source.lines().filter { it.isNotBlank() }.singleOrNull()
      ?: error("Use a short snip with one original image.")
  val file = File(path).canonicalFile
  require(file.parentFile == images.canonicalFile && file.isFile) { "Original image unavailable." }
  val (width, height) = ImagePipeline.bounds(file)
  require(width in 1..1600 && height in 1..800) { "Snip just a line or two for a handwriting example." }
  handwriting.save(HandwritingExample(documentId, path, correctedText.trim()))
 }
 suspend fun deleteOriginals(document: Document) = withContext(Dispatchers.IO) {
  handwriting.remove(document.id)
  documents.put(document.copy(source=""))
  val remaining=documents.sources().flatMap { it.lines() }.toSet()
  document.source.lines().filter { it.isNotBlank() && it !in remaining }.forEach { path ->
   val file=File(path);if(file.canonicalFile.parentFile==images.canonicalFile)file.delete()
  }
 }
 suspend fun delete(document: Document) = withContext(Dispatchers.IO) {
  handwriting.remove(document.id)
  documents.delete(document.id)
  val remaining=documents.sources().flatMap { it.lines() }.toSet()
  document.source.split("\n").filter { it.isNotBlank() }.forEach { path ->
   val f = File(path); if (path !in remaining && f.canonicalFile.parentFile == images.canonicalFile) f.delete()
  }
 }
}
