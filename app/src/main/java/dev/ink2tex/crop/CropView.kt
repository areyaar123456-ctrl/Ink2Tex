package dev.ink2tex.crop

import android.content.Context
import android.graphics.*
import android.view.*
import java.io.File
import kotlin.math.*

data class CropSelection(val bounds: RectF,val outline: List<PointF> = emptyList())
/** Frozen image coordinates are independent of view zoom, tablet rotation and stylus input. */
class CropView(context:Context): View(context) {
 var bitmap:Bitmap?=null
  set(value) { field=value;selection=null;points.clear();zoom=1f;pan=PointF();invalidate() }
 var onSelectionChanged: ((CropSelection?)->Unit)?=null
 var freeform=false
 var selection:RectF?=null
 private val points=mutableListOf<PointF>()
 private var zoom=1f
 private var pan=PointF()
 private val matrix=Matrix();private val inverse=Matrix()
 private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
 private var anchor=PointF();private var original=RectF();private var handle=-1
 private var pinchX=0f;private var pinchY=0f
 private val detector=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener() {
  override fun onScale(detector:ScaleGestureDetector):Boolean { zoom=(zoom*detector.scaleFactor).coerceIn(1f,8f);invalidate();return true }
 })
 fun resetSelection() { selection=null;points.clear();onSelectionChanged?.invoke(null);invalidate() }
 fun restore(value:CropSelection?) {
  val b=bitmap ?: return
  if(value==null)return
  val r=value.bounds;selection=RectF(r.left*b.width,r.top*b.height,r.right*b.width,r.bottom*b.height)
  points.clear();points+=value.outline.map { PointF(it.x*b.width,it.y*b.height) };invalidate()
 }
 fun selected():CropSelection? {
  val b=bitmap ?: return null
  val s=selection ?: return null
  if(s.width()<8 || s.height()<8)return null
  return CropSelection(RectF(s.left/b.width,s.top/b.height,s.right/b.width,s.bottom/b.height),points.map { PointF(it.x/b.width,it.y/b.height) })
 }
 override fun onDraw(canvas:Canvas) {
  super.onDraw(canvas);canvas.drawColor(Color.rgb(13,23,29))
  val b=bitmap ?: return
  val scale=min(width.toFloat()/b.width,height.toFloat()/b.height)*zoom
  matrix.reset();matrix.postScale(scale,scale);matrix.postTranslate((width-b.width*scale)/2+pan.x,(height-b.height*scale)/2+pan.y);matrix.invert(inverse)
  canvas.drawBitmap(b,matrix,null)
  selection?.let { selected ->
   val rect=RectF(selected);matrix.mapRect(rect)
   paint.color=Color.argb(105,0,0,0);paint.style=Paint.Style.FILL
   canvas.drawRect(0f,0f,width.toFloat(),rect.top,paint);canvas.drawRect(0f,rect.bottom,width.toFloat(),height.toFloat(),paint)
   canvas.drawRect(0f,rect.top,rect.left,rect.bottom,paint);canvas.drawRect(rect.right,rect.top,width.toFloat(),rect.bottom,paint)
   paint.color=Color.rgb(75,224,197);paint.style=Paint.Style.STROKE;paint.strokeWidth=3f*resources.displayMetrics.density
   if(freeform && points.size>2) {
    val path=Path();points.forEachIndexed { i,p -> if(i==0)path.moveTo(p.x,p.y) else path.lineTo(p.x,p.y) };path.close();path.transform(matrix);canvas.drawPath(path,paint)
   } else {
    canvas.drawRect(rect,paint);paint.style=Paint.Style.FILL
    listOf(PointF(rect.left,rect.top),PointF(rect.right,rect.top),PointF(rect.right,rect.bottom),PointF(rect.left,rect.bottom)).forEach { canvas.drawCircle(it.x,it.y,7f*resources.displayMetrics.density,paint) }
   }
  }
 }
 private fun imagePoint(event:MotionEvent):PointF {
  val b=bitmap ?: return PointF();val p=floatArrayOf(event.x,event.y);inverse.mapPoints(p);return PointF(p[0].coerceIn(0f,b.width.toFloat()),p[1].coerceIn(0f,b.height.toFloat()))
 }
 override fun onTouchEvent(event:MotionEvent):Boolean {
  detector.onTouchEvent(event)
  if(event.pointerCount>1) {
   val x=(event.getX(0)+event.getX(1))/2;val y=(event.getY(0)+event.getY(1))/2
   if(event.actionMasked==MotionEvent.ACTION_MOVE) { pan.x+=x-pinchX;pan.y+=y-pinchY;invalidate() }
   pinchX=x;pinchY=y;return true
  }
  if(detector.isInProgress)return true
  val b=bitmap ?: return false;val point=imagePoint(event)
  when(event.actionMasked) {
   MotionEvent.ACTION_DOWN -> {
    parent.requestDisallowInterceptTouchEvent(true);anchor=point
    if(freeform) { points.clear();points+=point;selection=RectF(point.x,point.y,point.x,point.y) }
    else {
     val s=selection;original=RectF(s ?: RectF());handle=-1
     val threshold=28*resources.displayMetrics.density/(min(width.toFloat()/b.width,height.toFloat()/b.height)*zoom)
     if(s!=null) {
      val corners=listOf(PointF(s.left,s.top),PointF(s.right,s.top),PointF(s.right,s.bottom),PointF(s.left,s.bottom))
      handle=corners.indexOfFirst { hypot(it.x-point.x,it.y-point.y)<threshold }
      if(handle<0 && s.contains(point.x,point.y))handle=4
     }
     if(handle<0)selection=RectF(point.x,point.y,point.x,point.y)
    };invalidate();return true
   }
   MotionEvent.ACTION_MOVE -> {
    if(freeform) {
     points+=point;selection=RectF(points.minOf{it.x},points.minOf{it.y},points.maxOf{it.x},points.maxOf{it.y})
    } else {
     selection=when(handle) {
      4 -> RectF(original).apply { val dx=(point.x-anchor.x).coerceIn(-left,b.width-right);val dy=(point.y-anchor.y).coerceIn(-top,b.height-bottom);offset(dx,dy) }
      0 -> RectF(point.x,point.y,original.right,original.bottom)
      1 -> RectF(original.left,point.y,point.x,original.bottom)
      2 -> RectF(original.left,original.top,point.x,point.y)
      3 -> RectF(point.x,original.top,original.right,point.y)
      else -> RectF(min(anchor.x,point.x),min(anchor.y,point.y),max(anchor.x,point.x),max(anchor.y,point.y))
     }.apply { sort() }
    };invalidate();return true
   }
   MotionEvent.ACTION_UP -> { parent.requestDisallowInterceptTouchEvent(false);performClick();onSelectionChanged?.invoke(selected());invalidate();return true }
  }
  return true
 }
 override fun performClick():Boolean { super.performClick();return true }
 companion object {
  @Suppress("DEPRECATION")
  fun crop(file:File,selection:CropSelection):Bitmap {
   val decoder=BitmapRegionDecoder.newInstance(file.absolutePath,false) ?: error("Image unavailable")
   try {
    val n=selection.bounds
    val r=Rect((n.left*decoder.width).toInt().coerceIn(0,decoder.width-1),(n.top*decoder.height).toInt().coerceIn(0,decoder.height-1),(n.right*decoder.width).toInt().coerceIn(1,decoder.width),(n.bottom*decoder.height).toInt().coerceIn(1,decoder.height))
    require(r.width()>1 && r.height()>1) { "Select a larger region" }
    var sample=1;while(r.width().toLong()*r.height()/(sample*sample)>4_000_000)sample*=2
    val decoded=if(decoder.width.toLong()*decoder.height<=4_000_000) {
     // A bounded full decode is simpler and avoids region-decoder overhead for small captures.
     val full=BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply { inPreferredConfig=Bitmap.Config.ARGB_8888 }) ?: error("Crop failed")
     Bitmap.createBitmap(full,r.left,r.top,r.width(),r.height()).also { if(it!==full)full.recycle() }
    } else decoder.decodeRegion(r,BitmapFactory.Options().apply { inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888 }) ?: error("Crop failed")
    if(selection.outline.isEmpty())return decoded
    val result=Bitmap.createBitmap(decoded.width,decoded.height,Bitmap.Config.ARGB_8888)
    val canvas=Canvas(result);canvas.drawColor(Color.WHITE)
    val path=Path();selection.outline.forEachIndexed { i,p -> val x=(p.x*decoder.width-r.left)/sample;val y=(p.y*decoder.height-r.top)/sample;if(i==0)path.moveTo(x,y) else path.lineTo(x,y) };path.close()
    canvas.save();canvas.clipPath(path);canvas.drawBitmap(decoded,0f,0f,null);canvas.restore();decoded.recycle();return result
   } finally { decoder.recycle() }
  }
 }
}
