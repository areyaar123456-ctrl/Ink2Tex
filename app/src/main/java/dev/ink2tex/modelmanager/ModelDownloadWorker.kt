package dev.ink2tex.modelmanager

import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import androidx.work.*
import dev.ink2tex.InkApplication
import dev.ink2tex.ocr.RecognitionOptions
import dev.ink2tex.ocr.WorkNotifications
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class ModelDownloadWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
 override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
  val app = applicationContext as InkApplication
  val store = app.models
  val model = store.manifest(inputData.getString("model") ?: return@withContext Result.failure())
  val directory = store.directory(model.id).apply { mkdirs() }
  val marker = File(directory,"ready.json")
  try {
   require(model.compatible) { model.reason }
   require(model.license=="Apache-2.0") { "This model license has not been reviewed for automatic installation." }
   setForeground(WorkNotifications.foreground(applicationContext,id,410,"Installing ${model.name}",ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
   marker.delete()
   val remaining = model.components.sumOf { c -> if(File(directory,c.name).length()==c.size) 0L else (c.size - File(directory,c.name+".part").length()).coerceAtLeast(0) }
   require(directory.usableSpace > remaining + 256L*1024*1024) { "Insufficient storage. Free space and tap Resume." }
   for (component in model.components) {
    ensureActive()
    val target = File(directory,component.name)
    if (target.length()==component.size && ModelStore.sha256(target)==component.sha256) continue
    target.delete()
    download(component,directory,model)
   }
   setProgress(workDataOf("stage" to "Validating local image inference", "bytes" to model.size, "total" to model.size))
   val bitmap = applicationContext.assets.open("validation.png").use { BitmapFactory.decodeStream(it) } ?: error("Missing validation image")
   app.engine.session.lock()
   try {
    app.engine.loadModel(model.id, allowUnvalidated = true)
    val output = app.engine.recognize(bitmap, RecognitionOptions(maxTokens=384))
    require(output.markdown.lowercase().replace(" ","").contains("ink2tex") && !output.truncated) { "Validation transcription did not match the bundled smoke image. Model remains unavailable." }
   } finally { bitmap.recycle(); try { withContext(NonCancellable) { app.engine.unloadModel() } } finally { app.engine.session.unlock() } }
   val temporary = File(directory,"ready.tmp")
   temporary.writeText(JSONObject().put("validatedAt",System.currentTimeMillis()).put("runtime","32bdc3a89e6528d3e419024c833c5dabf32c7541").put("fingerprint",model.components.joinToString(":") { it.sha256 }).toString())
   check(temporary.renameTo(marker)) { "Unable to commit installation" }
   File(directory,"error.txt").delete()
   val selected=app.repository.settings.flow.first().model
   if(selected!="auto" && !runCatching { store.ready(selected) }.getOrDefault(false))app.repository.settings.model(model.id)
   store.changed()
   Result.success()
  } catch (e: CancellationException) { throw e }
  catch (e: Exception) {
   marker.delete(); File(directory,"error.txt").writeText(e.message ?: e.javaClass.simpleName)
   Result.failure(workDataOf("error" to (e.message ?: "Installation failed").take(2000)))
  }
 }
 private suspend fun download(c: Component, directory: File, model: ModelManifest) {
  require(c.name.matches(Regex("[A-Za-z0-9._-]+")) && c.url.startsWith("https://huggingface.co/"))
  val part=File(directory,c.name+".part"); val etagFile=File(directory,c.name+".etag")
  if(part.length()>c.size) part.delete()
  if(part.length()<c.size) {
   transfer(c,part,etagFile,model,directory)
  }
  ensureActive()
  require(part.length()==c.size) { "Incomplete download. Tap Resume." }
  if(ModelStore.sha256(part)!=c.sha256) { part.delete();error("Checksum mismatch. Corrupt data removed; download again.") }
  check(part.renameTo(File(directory,c.name))) { "Unable to install model component" }
 }
 private suspend fun transfer(c: Component, part: File, etagFile: File, model: ModelManifest, directory: File) {
  var offset=part.length()
  val connection=URL(c.url).openConnection() as HttpURLConnection
  connection.connectTimeout=30000;connection.readTimeout=30000
  if(offset>0) { connection.setRequestProperty("Range","bytes=$offset-"); if(etagFile.exists()) connection.setRequestProperty("If-Range",etagFile.readText()) }
  try {
   val response=connection.responseCode
   require(response==200 || response==206) { "Download returned HTTP $response" }
   if(response==206) require(connection.getHeaderField("Content-Range")?.startsWith("bytes $offset-")==true) { "Invalid resume response" }
   if(response==200) { offset=0;part.delete() }
   connection.getHeaderField("ETag")?.let { etagFile.writeText(it) }
   val cancelHandle=currentCoroutineContext().job.invokeOnCompletion { connection.disconnect() }
   try {
    connection.inputStream.buffered().use { input ->
     java.io.FileOutputStream(part,offset>0).use { output ->
      val buf=ByteArray(128*1024);var last=0L
      while(true) {
       currentCoroutineContext().ensureActive()
       val n=input.read(buf);if(n<0)break
       require(offset+n<=c.size) { "Source exceeds manifest size" }
       output.write(buf,0,n);offset+=n
       if(System.currentTimeMillis()-last>500) {
        last=System.currentTimeMillis()
        val completed=model.components.sumOf { p -> if(File(directory,p.name).exists()) p.size else File(directory,p.name+".part").length() }
        setProgress(workDataOf("stage" to "Downloading ${c.name}","bytes" to completed,"total" to model.size))
       }
      }
      output.fd.sync()
     }
    }
   } finally { cancelHandle.dispose() }
  } finally { connection.disconnect() }
 }
 private suspend fun ensureActive() { currentCoroutineContext().ensureActive() }
}
