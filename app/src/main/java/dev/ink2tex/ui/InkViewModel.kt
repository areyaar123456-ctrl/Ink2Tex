package dev.ink2tex.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dev.ink2tex.InkApplication
import dev.ink2tex.ink.StrokeModels
import dev.ink2tex.ink.StrokeRecognitionWorker
import dev.ink2tex.crop.*
import dev.ink2tex.ocr.RecognitionWorker
import dev.ink2tex.ocr.HandwritingTrainingWorker
import dev.ink2tex.ocr.TrainingPlan
import dev.ink2tex.ocr.TrainingCheckpoints
import kotlinx.coroutines.sync.withLock
import dev.ink2tex.preprocessing.ImagePipeline
import dev.ink2tex.storage.Document
import dev.ink2tex.storage.TrainingLabel
import dev.ink2tex.storage.TrainingRun
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class Navigation(val page:String="home",val documentId:String?=null,val cropMode:String="rect")
class InkViewModel(application:Application):AndroidViewModel(application) {
 val app=application as InkApplication
 val repository=app.repository
 val history=repository.history.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
 val settings=repository.settings.flow.stateIn(viewModelScope,SharingStarted.Eagerly,dev.ink2tex.storage.ScannerSettings())
 val handwriting=repository.handwriting.profile.stateIn(viewModelScope,SharingStarted.Eagerly,dev.ink2tex.storage.HandwritingProfile())
 val trainingLabels=repository.training.labels.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
 val trainingRuns=app.database.trainingHistory().observe()
     .stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
 val trainingJobs=WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(HandwritingTrainingWorker.NAME)
     .stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
 val navigation=MutableStateFlow(Navigation())
 val message=MutableStateFlow<String?>(null)
 val importing=MutableStateFlow(false)
 val cropSelection=MutableStateFlow<CropSelection?>(null)
 val cropParts=MutableStateFlow<List<Document>>(emptyList())
 val works=WorkManager.getInstance(app).getWorkInfosByTagFlow("models").stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
 val queue=WorkManager.getInstance(app).getWorkInfosByTagFlow("ocr").stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
 private var saveJob:Job?=null
 fun go(page:String,id:String?=null,mode:String="rect") { if(page=="crop" && id!=navigation.value.documentId)cropSelection.value=null;navigation.value=Navigation(page,id,mode) }
 fun import(uri:Uri) { viewModelScope.launch {
  importing.value=true
  try {
   val result=ImagePipeline.import(app,uri,repository)
   go("editor",result.id)
  } catch(e:Exception) { message.value=e.message }
  finally { importing.value=false }
 } }
 fun capture(id:String,mode:String) {
  go(if(mode=="screen")"editor" else "crop",id,mode)
  if(mode=="screen") viewModelScope.launch { repository.documents.get(id)?.let { if(app.models.available().isNotEmpty())convert(it) } }
 }
 private suspend fun finishCropDocument(document:Document) {
  var result=document
  val guided=navigation.value.cropMode=="scroll"
  if(guided) {
   val preferences=app.getSharedPreferences("guided-proof",0)
   val previous=preferences.getString("target",null)?.let { repository.documents.get(it) }
   if(previous!=null && previous.id!=document.id) {
    result=previous.copy(source=previous.source+"\n"+document.source,status="Captured",error=null)
    repository.documents.put(result)
   }
   preferences.edit().putString("target",result.id).apply()
   message.value="Section added. Scroll Nebo manually, then tap Scrolling Proof Scan again. Finish when all sections are captured."
  }
  go("editor",result.id)
  if(!guided && app.models.available().isNotEmpty())convert(result)
 }
 fun finishScroll() { app.getSharedPreferences("guided-proof",0).edit().remove("target").apply();message.value="Guided capture finished. Review the section order, then convert." }
 fun useWholeCapture() { viewModelScope.launch { repository.documents.get(navigation.value.documentId ?: "")?.let { finishCropDocument(it) } } }

 fun addCrop(selection:CropSelection,finish:Boolean) { viewModelScope.launch {
  try {
   val doc=repository.documents.get(navigation.value.documentId ?: "") ?: error("Source image missing")
   val image=withContext(Dispatchers.IO) { CropView.crop(File(doc.source.lines().first()),selection) }
   val cropped=repository.save(image,"Snipped note",doc.source.lineSequence().first());image.recycle()
   cropParts.value=cropParts.value+cropped
   if(finish) {
    val result=if(cropParts.value.size==1)cropped else combine(cropParts.value,"Multi-snip proof")
    cropParts.value=emptyList();finishCropDocument(result)
   }
  } catch(e:Exception) { message.value=e.message }
 } }
 fun finishMulti() { viewModelScope.launch { if(cropParts.value.isNotEmpty()) { val doc=combine(cropParts.value,"Multi-snip proof");cropParts.value=emptyList();finishCropDocument(doc) } } }
 fun movePart(index:Int,delta:Int) { val list=cropParts.value.toMutableList();val target=index+delta;if(target in list.indices) { val item=list.removeAt(index);list.add(target,item);cropParts.value=list } }
 fun removePart(index:Int) { cropParts.value=cropParts.value.filterIndexed { n,_ -> n!=index } }
 private suspend fun combine(docs:List<Document>,title:String):Document {
  val result=Document(UUID.randomUUID().toString(),title,source=docs.joinToString("\n") { it.source })
  repository.documents.put(result);return result
 }
 fun convert(document:Document,model:String=settings.value.model,deduplicate:Boolean=false,fallback:Boolean=false,useExamples:Boolean=true,trainedWeights:Boolean=true) {
  if (StrokeModels.isStroke(model)) {
   StrokeRecognitionWorker.enqueue(app, document.id, model)
   return
  }
  if(model!="auto" && !app.models.ready(model)) { message.value="Download a compatible model in Model Store before converting.";go("models");return }
  RecognitionWorker.enqueue(app,document.id,model,deduplicate,fallback,useExamples,trainedWeights)
 }
 fun save(id:String,text:String) { saveJob?.cancel();saveJob=viewModelScope.launch { delay(300);repository.documents.edit(id,text) } }
 fun flush(id:String,text:String) { saveJob?.cancel();viewModelScope.launch { repository.documents.edit(id,text) } }
 fun copy(text:String) { app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Ink2TeX Markdown + LaTeX",text));message.value="Copied to clipboard" }
 fun copyLast() { history.value.firstOrNull { it.markdown.isNotBlank() }?.let { copy(it.markdown) } ?: run { message.value="No transcription to copy yet" } }
 fun delete(doc:Document) { viewModelScope.launch { withContext(Dispatchers.IO) { WorkManager.getInstance(app).cancelUniqueWork("ocr-${doc.id}").result.get() };repository.delete(doc) } }
 fun append(current:Document,target:Document) { viewModelScope.launch { repository.documents.put(target.copy(markdown=target.markdown.trimEnd()+"\n\n"+current.markdown,source=target.source+"\n"+current.source,status="Review"));go("editor",target.id) } }
 fun mergeWith(current:Document,target:Document) { viewModelScope.launch { val merged=combine(listOf(target,current),"Scrolling proof");go("editor",merged.id) } }
 fun compare(doc:Document,other:String,useExamples:Boolean=true,trainedWeights:Boolean=true) { viewModelScope.launch {
  val label=if(StrokeModels.isStroke(other)) StrokeModels.name(other) else if(!trainedWeights) "stock model" else if(useExamples) "comparison" else "without examples"
  val comparison=doc.copy(id=UUID.randomUUID().toString(),title="${doc.title} · $label",
   markdown="",modelId=other,status="Captured",error=null,durationMs=null,tokens=null,warnings="")
  repository.documents.put(comparison)
  convert(comparison,other,useExamples=useExamples,trainedWeights=trainedWeights)
  message.value="Comparison queued. Both results appear in History."
 } }
 fun recordTraining(label:TrainingLabel, finished:(Boolean)->Unit = {}) { viewModelScope.launch {
  try {
   repository.training.save(label)
   message.value=if(label.evaluation) "Evaluation label saved." else "Training label saved."
   finished(true)
  } catch(e:CancellationException) { throw e }
  catch(e:Exception) {
   message.value=e.message ?: "Unable to save the training label."
   finished(false)
  }
 } }
 fun splitTrainingImage(documentId:String, source:String, finished:(String?)->Unit) { viewModelScope.launch {
  try {
   val note = repository.splitTrainingImage(documentId,source)
   finished(null)
   go("editor",note.id)
   message.value = "Created ${note.source.lineSequence().count()} snips. Convert, then check the source for each snip."
  } catch(e:CancellationException) { throw e }
  catch(e:Exception) {
   finished(e.message ?: "Unable to split this image.")
  }
 } }
 fun trainHandwriting(model:String, plan:TrainingPlan) = HandwritingTrainingWorker.enqueue(app,model,plan)
 fun restoreHandwriting(model:String) { viewModelScope.launch {
  try {
   app.engine.session.withLock {
    app.engine.unloadModel()
    withContext(Dispatchers.IO) {
     TrainingCheckpoints.restore(app.models.directory(model))
     val now=System.currentTimeMillis()
     app.database.trainingHistory().put(TrainingRun(UUID.randomUUID().toString(),model,now,now,
      "restored","",0,0,"","[]","Previous handwriting checkpoint restored from this tablet."))
    }
    app.models.changed()
   }
   message.value="Previous handwriting checkpoint restored."
  } catch(e:CancellationException) { throw e }
  catch(e:Exception) { message.value=e.message ?: "Unable to restore weights." }
 } }
 fun cancelTraining() { WorkManager.getInstance(app).cancelUniqueWork(HandwritingTrainingWorker.NAME) }
 fun teachHandwriting(documentId:String,text:String) { viewModelScope.launch {
  try {
   repository.teachHandwriting(documentId,text)
   message.value="Handwriting example saved."
  } catch(e:CancellationException) {
   throw e
  } catch(e:Exception) {
   message.value=e.message ?: "Unable to save handwriting example."
  }
 } }
 fun selectModel(id:String) { viewModelScope.launch { repository.settings.model(id) } }
 fun benchmark(id:String) { viewModelScope.launch {
  try {
   val bitmap=app.assets.open("validation.png").use { android.graphics.BitmapFactory.decodeStream(it) } ?: error("Benchmark image missing")
   val doc=repository.save(bitmap,"Benchmark · printed smoke image");bitmap.recycle();convert(doc,id,useExamples=false);go("editor",doc.id)
  } catch(e:Exception) { message.value=e.message }
 } }
 fun install(id:String) { runCatching { app.models.install(id) }.onFailure { message.value=it.message } }
 fun removeModel(id:String) { viewModelScope.launch {
  try {
   withContext(Dispatchers.IO) { WorkManager.getInstance(app).cancelUniqueWork("model-$id").result.get() }
   app.engine.session.lock()
   try { app.engine.unloadModel();app.models.remove(id) } finally { app.engine.session.unlock() }
   message.value="Model removed"
  } catch(e:Exception) { message.value=e.message }
 } }
}
