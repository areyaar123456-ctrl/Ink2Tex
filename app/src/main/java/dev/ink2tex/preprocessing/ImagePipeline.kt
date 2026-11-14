package dev.ink2tex.preprocessing

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import dev.ink2tex.storage.InkRepository
import dev.ink2tex.storage.Document
import java.io.File
import kotlin.math.*
import kotlinx.coroutines.*

object ImagePipeline {
 suspend fun import(context: Context, uri: Uri, repository: InkRepository): List<Document> = withContext(Dispatchers.IO) {
  val type=context.contentResolver.getType(uri) ?: ""
  require(type!="image/svg+xml") { "SVG import is unavailable in this release. Export PNG or PDF from Nebo." }
  if(type=="application/pdf") {
   val descriptor=context.contentResolver.openFileDescriptor(uri,"r") ?: error("Unable to open PDF")
   descriptor.use { fd -> PdfRenderer(fd).use { pdf ->
    require(pdf.pageCount in 1..500) { "PDF must contain 1–500 pages. Split larger exports." }
    (0 until pdf.pageCount).map { index ->
     currentCoroutineContext().ensureActive()
     pdf.openPage(index).use { page ->
      val scale=min(2f,1800f/max(page.width,page.height))
      val bitmap=Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1),(page.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
      try { bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);repository.save(bitmap,"Imported PDF · page ${index+1}") } finally { bitmap.recycle() }
     }
    }
   } }
  } else {
   require(type.startsWith("image/") || type.isBlank()) { "Choose PNG, JPEG, WebP or PDF." }
   val id=java.util.UUID.randomUUID().toString()
   val file=File(repository.images,"$id.import")
   try {
    context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
     val buffer=ByteArray(128*1024);var total=0L
     while(true) { currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;total+=n;require(total<=512L*1024*1024) { "Image exceeds 512 MiB import limit" };output.write(buffer,0,n) }
    } } ?: error("Unable to read the selected image")
    val bounds=bounds(file);require(bounds.first>0 && bounds.second>0) { "Unsupported or damaged image" }
    val doc=Document(id,"Imported note",source=file.absolutePath)
    repository.documents.put(doc);listOf(doc)
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
