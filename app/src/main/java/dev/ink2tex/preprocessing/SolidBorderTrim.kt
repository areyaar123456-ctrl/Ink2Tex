package dev.ink2tex.preprocessing

data class PixelBounds(val left:Int,val top:Int,val right:Int,val bottom:Int) {
 val width get()=right-left
 val height get()=bottom-top
}
object SolidBorderTrim {
 /** Remove only pixels exactly equal to the shared corner color, retaining a safety margin. */
 fun bounds(pixels:IntArray,width:Int,height:Int,padding:Int=16):PixelBounds {
  require(width>0 && height>0 && pixels.size==width*height && padding>=0)
  val full=PixelBounds(0,0,width,height)
  val background=pixels[0]
  if(pixels[width-1]!=background || pixels[(height-1)*width]!=background || pixels.last()!=background)return full
  var left=width;var top=height;var right=-1;var bottom=-1
  for(y in 0 until height) for(x in 0 until width) {
   if(pixels[y*width+x]!=background) {
    left=minOf(left,x);top=minOf(top,y);right=maxOf(right,x);bottom=maxOf(bottom,y)
   }
  }
  if(right<0)return full
  return PixelBounds((left-padding).coerceAtLeast(0),(top-padding).coerceAtLeast(0),(right+1+padding).coerceAtMost(width),(bottom+1+padding).coerceAtMost(height))
 }
}
