package dev.ink2tex.ocr

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.*
import dev.ink2tex.InkApplication
import dev.ink2tex.preprocessing.ImagePipeline
import dev.ink2tex.stitching.ProofSequence
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import java.io.File

class RecognitionWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
 override suspend fun doWork(): Result {
  val app=applicationContext as InkApplication
  val dao=app.repository.documents
  val doc=dao.get(inputData.getString("document") ?: "") ?: return Result.failure()
  var modelId=inputData.getString("model") ?: app.repository.settings.flow.first().model
  if(modelId=="auto") modelId=app.models.available().firstOrNull()?.id ?: ""
  val candidateModels=if(inputData.getBoolean("fallback",false)) listOf(modelId)+app.models.available().map { it.id }.filter { it!=modelId } else listOf(modelId)
  try {
   setForeground(WorkNotifications.foreground(applicationContext,id,411,"Converting ${doc.title}",if(android.os.Build.VERSION.SDK_INT>=34)ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0))
   dao.put(doc.copy(status="Queued",error=null))
   return app.engine.session.withLock {
    try {
    var failure: Exception?=null
    for(candidate in candidateModels) {
     try {
      dao.put(doc.copy(status="Converting",modelId=candidate,error=null))
      app.engine.loadModel(candidate)
      val pages=mutableListOf<String>();var elapsed=0L;var tokens=0;val warnings=mutableListOf<String>();var tileCount=0
      for(path in doc.source.split('\n').filter { it.isNotBlank() }) {
       ImagePipeline.forEachTile(File(path)) { bitmap,index,total ->
        currentCoroutineContext().ensureActive()
        tileCount++
        setProgress(workDataOf("stage" to "Reading section $tileCount · tile ${index+1}/$total"))
        val result=app.engine.recognize(bitmap)
        pages+=result.markdown;elapsed+=result.durationMs;tokens+=result.tokens;warnings+=result.warnings
       }
      }
      require(pages.any { it.isNotBlank() }) { "The model returned no transcription. Check the selected image and try another model." }
      val merged=ProofSequence.merge(pages,inputData.getBoolean("deduplicate",false))
      warnings+=merged.warnings
      dao.put(doc.copy(markdown=merged.text,modelId=candidate,status="Review",durationMs=elapsed,tokens=tokens,error=null,warnings=warnings.distinct().joinToString("\n")))
      app.engine.unloadModel()
      return@withLock Result.success()
     } catch(e: CancellationException) { throw e }
     catch(e: Exception) { failure=e;app.engine.unloadModel() }
    }
    throw failure ?: IllegalStateException("No installed compatible model")
    } finally { withContext(NonCancellable) { app.engine.unloadModel() } }
   }
  } catch(e: CancellationException) {
   app.engine.cancel()
   withContext(NonCancellable) { dao.state(doc.id,"Cancelled","Conversion cancelled; the source image is preserved.") }
   throw e
  } catch(e: OutOfMemoryError) {
   dao.state(doc.id,"Failed","Memory budget exceeded. Close large apps or choose a smaller snip.")
   return Result.failure(workDataOf("error" to "Memory budget exceeded"))
  } catch(e: Exception) {
   dao.state(doc.id,"Failed",e.message ?: "Conversion failed")
   return Result.failure(workDataOf("error" to (e.message ?: "Conversion failed").take(2000)))
  }
 }
 companion object {
  fun enqueue(context: Context, documentId: String, model: String, deduplicate: Boolean=false, fallback: Boolean=false) {
   val request=OneTimeWorkRequestBuilder<RecognitionWorker>().setInputData(workDataOf("document" to documentId,"model" to model,"deduplicate" to deduplicate,"fallback" to fallback)).addTag("ocr").addTag(documentId).build()
   WorkManager.getInstance(context).enqueueUniqueWork("ocr-$documentId",ExistingWorkPolicy.KEEP,request)
  }
 }
}
