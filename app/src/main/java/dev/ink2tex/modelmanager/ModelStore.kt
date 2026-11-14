package dev.ink2tex.modelmanager

import android.content.Context
import android.os.Build
import androidx.work.*
import java.io.File
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

data class Component(val name: String, val url: String, val size: Long, val sha256: String)
data class ModelManifest(val id: String, val name: String, val developer: String, val description: String, val license: String, val licenseUrl: String, val quantization: String, val compatibility: String, val reason: String, val runtime: String, val prompt: String, val ramReserveBytes: Long, val components: List<Component>) {
 val size: Long get() = components.sumOf { it.size }
 val compatible: Boolean get() = runtime == "qwen35-ocr" && components.size == 2
}
class ModelStore(val context: Context) {
 val changes=kotlinx.coroutines.flow.MutableStateFlow(0L)
 fun changed() { changes.value+=1 }
 val root = File(context.filesDir, "models").apply { mkdirs() }
 val manifests: List<ModelManifest> = JSONObject(context.assets.open("models.json").bufferedReader().use { it.readText() }).getJSONArray("models").let { array ->
  (0 until array.length()).map { i ->
   val j = array.getJSONObject(i); val parts = j.getJSONArray("components")
   ModelManifest(j.getString("id"),j.getString("name"),j.getString("developer"),j.getString("description"),j.getString("license"),j.getString("licenseUrl"),j.getString("quantization"),j.getString("compatibility"),j.getString("reason"),j.getString("runtime"),j.getString("prompt"),j.getLong("ramReserveBytes"),(0 until parts.length()).map { n -> parts.getJSONObject(n).let { Component(it.getString("name"),it.getString("url"),it.getLong("size"),it.getString("sha256")) } })
  }
 }
 fun manifest(id: String) = manifests.firstOrNull { it.id == id } ?: error("Unknown model")
 fun directory(id: String) = File(root, manifest(id).id)
 fun ready(id: String): Boolean {
  val m = manifest(id); val d = directory(id)
  val marker=runCatching { JSONObject(File(d,"ready.json").readText()) }.getOrNull()
  return m.compatible && marker != null && marker.optString("runtime")=="32bdc3a89e6528d3e419024c833c5dabf32c7541" && marker.optString("fingerprint")==m.components.joinToString(":") { it.sha256 } && m.components.all { File(d,it.name).length() == it.size }
 }
 fun available() = manifests.filter { ready(it.id) }
 fun install(id: String) {
  val m = manifest(id)
  require(m.compatible) { m.reason }
  require(Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "This APK requires ARM64." }
  val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>().setInputData(workDataOf("model" to id)).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).addTag("models").addTag(id).build()
  WorkManager.getInstance(context).enqueueUniqueWork("model-$id",ExistingWorkPolicy.KEEP,request)
 }
 fun pause(id: String) { WorkManager.getInstance(context).cancelUniqueWork("model-$id") }
 suspend fun remove(id: String) = withContext(Dispatchers.IO) { directory(id).deleteRecursively();changed() }
 companion object {
  fun sha256(file: File): String {
   val digest = MessageDigest.getInstance("SHA-256")
   file.inputStream().buffered().use { stream -> val buf=ByteArray(256*1024); while(true) { val n=stream.read(buf); if(n<0)break; digest.update(buf,0,n) } }
   return digest.digest().joinToString("") { "%02x".format(it) }
  }
 }
}
