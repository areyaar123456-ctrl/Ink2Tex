package dev.ink2tex.preprocessing

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.provider.OpenableColumns
import dev.ink2tex.ink.InkLayout
import dev.ink2tex.ink.InkRaster
import dev.ink2tex.ink.NeboInk
import dev.ink2tex.ink.VectorImport
import dev.ink2tex.storage.InkRepository
import dev.ink2tex.storage.Document
import java.io.File
import kotlin.math.*
import kotlinx.coroutines.*

object ImagePipeline {
 suspend fun import(context: Context, uri: Uri, repository: InkRepository): Document = withContext(Dispatchers.IO) {
  val type=context.contentResolver.getType(uri) ?: ""
  val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
   if (it.moveToFirst()) it.getString(0) else null
  } ?: uri.lastPathSegment.orEmpty()
  if (name.endsWith(".nebo", ignoreCase = true) || type == "application/vnd.myscript.nebo") {
   VectorImport.nebo(context, uri, repository)
  } else if (type == "image/svg+xml" || name.endsWith(".svg", ignoreCase = true)) {
   VectorImport.svg(context, uri, repository)
  } else if(type=="application/pdf" || name.endsWith(".pdf", ignoreCase = true)) {
   PdfImport.read(context,uri,repository)
  } else {
   require(type.startsWith("image/") || type.isBlank() || type == "application/octet-stream") { "Choose PNG, JPEG, WebP, SVG, PDF or a Nebo export." }
   val id=java.util.UUID.randomUUID().toString()
   val file=File(repository.images,"$id.import")
   try {
    context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
     val buffer=ByteArray(128*1024);var total=0L
     while(true) { currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;total+=n;require(total<=512L*1024*1024) { "Image exceeds 512 MiB import limit" };output.write(buffer,0,n) }
    } } ?: error("Unable to read the selected image")
    val bounds=bounds(file);require(bounds.first>0 && bounds.second>0) { "Unsupported or damaged image" }
    val doc=Document(id,"Imported note",source=file.absolutePath)
    repository.documents.put(doc);doc
   } catch(e: Exception) { file.delete();throw e }
  }
 }
 fun bounds(file: File): Pair<Int,Int> { val options=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeFile(file.absolutePath,options);return options.outWidth to options.outHeight }
 fun preview(file: File, longest: Int=1800): Bitmap {
  val (w,h)=bounds(file);require(w>0 && h>0) { "Image cannot be decoded" }
  var sample=1;while(max(w,h)/sample>longest)sample*=2
  return BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply { inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888 }) ?: error("Image cannot be decoded")
 }
 @Suppress("DEPRECATION")
 suspend fun forEachTile(file: File, block: suspend (Bitmap,Int,Int)->Unit) = withContext(Dispatchers.IO) {
  if (file.name.endsWith(VectorImport.PREVIEW_SUFFIX)) {
   val sections = InkLayout.imageSections(NeboInk.read(NeboInk.sidecar(file)))
   sections.forEachIndexed { index, section ->
    currentCoroutineContext().ensureActive()
    val bitmap = InkRaster.render(section)
    try { block(bitmap, index, sections.size) } finally { bitmap.recycle() }
   }
   return@withContext
  }
  val decoder=BitmapRegionDecoder.newInstance(file.absolutePath,false) ?: error("Unsupported image")
  try {
   if(decoder.width<=4096 && decoder.height<=4096) {
    var sample=1
    while(decoder.width.toLong()*decoder.height/(sample*sample)>4_000_000)sample*=2
    val image=decoder.decodeRegion(Rect(0,0,decoder.width,decoder.height),BitmapFactory.Options().apply { inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888 }) ?: error("Unable to decode page")
    try { block(image,0,1) } finally { image.recycle() }
    return@withContext
   }
   val tiles=TilePlanner.plan(decoder.width,decoder.height)
   for((index,tile) in tiles.withIndex()) {
    currentCoroutineContext().ensureActive()
    val bitmap=decoder.decodeRegion(Rect(tile.left,tile.top,tile.right,tile.bottom),BitmapFactory.Options().apply { inPreferredConfig=Bitmap.Config.ARGB_8888 }) ?: error("Unable to decode tile")
    try { block(bitmap,index,tiles.size) } finally { bitmap.recycle() }
   }
  } finally { decoder.recycle() }
 }
}
data class Tile(val left: Int,val top: Int,val right: Int,val bottom: Int)
object TilePlanner {
 fun plan(width: Int,height: Int,edge: Int=1600,overlap: Int=160): List<Tile> {
  require(width>0 && height>0 && edge>overlap && overlap>=0)
  require(width.toLong()*height<=2_000_000_000L) { "Document dimensions exceed safety limit" }
  fun starts(length:Int):List<Int> {
   val result=mutableListOf(0);var position=0
   while(position+edge<length) { position+=edge-overlap;result+=position }
   return result
  }
  val tiles=starts(height).flatMap { y -> starts(width).map { x -> Tile(x,y,min(x+edge,width),min(y+edge,height)) } }
  require(tiles.size<=2000) { "Document has too many processing tiles" }
  return tiles
 }
}
