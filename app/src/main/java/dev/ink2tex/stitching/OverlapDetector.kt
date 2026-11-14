package dev.ink2tex.stitching

import android.graphics.Bitmap
import kotlin.math.abs

/** Conservative preview-only pixel alignment. Never deletes content automatically. */
object OverlapDetector {
 data class Match(val rows: Int,val meanError: Double)
 fun detect(previous: Bitmap,next: Bitmap): Match? {
  if(previous.width!=next.width)return null
  val max=minOf(previous.height,next.height)/2
  for(rows in max downTo maxOf(24,max/8) step 8) {
   var total=0L;var count=0;var ink=0
   for(y in 0 until rows step 8) for(x in 0 until previous.width step 8) {
    val a=previous.getPixel(x,previous.height-rows+y);val b=next.getPixel(x,y)
    fun gray(v:Int)=((v shr 16 and 255)+(v shr 8 and 255)+(v and 255))/3
    val av=gray(a);val bv=gray(b);total+=abs(av-bv);count++;if(av<180)ink++
   }
   val error=total.toDouble()/count.coerceAtLeast(1)
   if(error<2.0 && ink>count/40)return Match(rows,error)
  }
  return null
 }
}
