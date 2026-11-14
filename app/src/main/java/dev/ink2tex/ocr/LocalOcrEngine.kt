package dev.ink2tex.ocr

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import dev.ink2tex.modelmanager.ModelStore
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All JNI ownership stays on one executor, including after cancellation. */
class LocalOcrEngine(private val context: Context, private val store: ModelStore): OcrEngine {
 private val executor = Executors.newSingleThreadExecutor { Thread(it,"Ink2TeX-native").apply { priority=Thread.NORM_PRIORITY-1 } }
 val session = Mutex()
 private val state = MutableStateFlow(Diagnostics())
 override val diagnostics = state.asStateFlow()
 private var active: String? = null
 private suspend fun <T> native(block: () -> T): T = suspendCancellableCoroutine { continuation ->
  continuation.invokeOnCancellation { NativeBridge.cancel() }
  executor.execute {
   NativeBridge.prepare()
   if (!continuation.isActive) return@execute
   try { val result=block(); if(continuation.isActive) continuation.resume(result) }
   catch (e: Throwable) { if(continuation.isActive) continuation.resumeWithException(e) }
  }
 }
 override suspend fun loadModel(modelId: String) = loadModel(modelId,false)
 suspend fun loadModel(modelId: String, allowUnvalidated: Boolean): LoadResult {
  val manifest=store.manifest(modelId)
  require(manifest.compatible && (allowUnvalidated || store.ready(modelId))) { "Download and validate a compatible model first." }
  if(active==modelId) return LoadResult(modelId,state.value.initializationMs ?: 0)
  state.update { it.copy(stage="Loading ${manifest.name}") }
  val memory=ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
  require(!memory.lowMemory && memory.availMem>manifest.ramReserveBytes) { "Not enough free RAM. Close other large apps, then try again. The model reserves a conservative 3 GiB budget." }
  val start=System.nanoTime()
  active=null
  native { NativeBridge.load(File(store.directory(modelId),manifest.components[0].name).absolutePath,File(store.directory(modelId),manifest.components[1].name).absolutePath,Runtime.getRuntime().availableProcessors().coerceIn(1,4)) }
  val elapsed=(System.nanoTime()-start)/1_000_000
  active=modelId; state.update { it.copy(loadedModel=modelId,initializationMs=elapsed,stage="Ready",lastError=null) }
  return LoadResult(modelId,elapsed)
 }
 override suspend fun recognize(image: Bitmap, options: RecognitionOptions): RecognitionResult {
  val id=active ?: error("No model loaded")
  require(image.width.toLong()*image.height<=4_000_000) { "Image needs tiling" }
  state.update { it.copy(stage="Reading handwriting · CPU") }
  val start=System.nanoTime()
  try {
   val rgb=withContext(Dispatchers.Default) {
    val pixels=IntArray(image.width*image.height); image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
    ByteArray(pixels.size*3).also { out -> pixels.forEachIndexed { i,p -> out[i*3]=(p shr 16).toByte();out[i*3+1]=(p shr 8).toByte();out[i*3+2]=p.toByte() } }
   }
   val raw=native { NativeBridge.recognize(rgb,image.width,image.height,store.manifest(id).prompt,options.maxTokens.coerceIn(32,4096)).decodeToString() }
   val pieces=raw.split('\n',limit=3);require(pieces.size==3) { "Invalid native response" }
   val result=RecognitionResult(normalizeDelimiters(pieces[2]),(System.nanoTime()-start)/1_000_000,pieces[0].toInt(),pieces[1]=="1",listOf("Review against the original. The model supplies no calibrated handwriting confidence.") + if(pieces[1]=="1") listOf("Output reached the token limit. Rerun smaller regions; the transcription is incomplete.") else emptyList())
   state.update { it.copy(inferenceMs=result.durationMs,outputTokens=result.tokens,stage="Ready",lastError=null) }
   return result
  } catch(e: Exception) { state.update { it.copy(lastError=e.message,stage=if(e is CancellationException) "Cancelled" else "Failed") };throw e }
 }
 override suspend fun unloadModel() { active=null;native { NativeBridge.unload() };state.update { it.copy(loadedModel=null,stage="Unloaded") } }
 override fun cancel() { NativeBridge.cancel() }
 companion object {
  // Delimiter normalization only: no algebra, spelling or variable rewriting.
  fun normalizeDelimiters(text: String) = text.replace("\\[","$$").replace("\\]","$$").replace("\\(","$").replace("\\)","$")
 }
}
