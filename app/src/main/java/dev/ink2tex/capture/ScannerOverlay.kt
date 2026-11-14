package dev.ink2tex.capture

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import dev.ink2tex.InkApplication
import dev.ink2tex.storage.ScannerSettings
import kotlinx.coroutines.*
import kotlin.math.*

class ScannerOverlay(private val context:Context,private val scope:CoroutineScope,private val scan:(String)->Unit,private val open:(String,String?,String?)->Unit,private val stop:()->Unit,private val pauseCapture:(Boolean)->Unit) {
 private val wm=context.getSystemService(WindowManager::class.java)
 private var root:View?=null
 private var params=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.LEFT;x=16;y=220 }
 private val store=(context.applicationContext as InkApplication).repository.settings
 private var settings=ScannerSettings()
 var paused=false
 private fun dp(value:Int)=(value*context.resources.displayMetrics.density).toInt()
 fun applySettings(value:ScannerSettings) { settings=value;params.x=value.x;params.y=value.y }
 private fun background(color:Int,radius:Float)=GradientDrawable().apply { setColor(color);cornerRadius=dp(radius.toInt()).toFloat() }
 private fun text(label:String,size:Float,color:Int=Color.WHITE)=TextView(context).apply { text=label;textSize=size;setTextColor(color);gravity=Gravity.CENTER_VERTICAL;minHeight=dp(48);setPadding(dp(16),0,dp(16),0) }
 fun showBubble() {
  close()
  val bubble=text(if(paused) "Ⅱ" else "Tₑₓ",18f).apply {
   gravity=Gravity.CENTER;typeface=Typeface.DEFAULT_BOLD
   background=background(Color.rgb(20,125,116),28f);contentDescription="Ink2TeX scanner. Tap to expand; drag to move."
   layoutParams=android.view.ViewGroup.LayoutParams(dp(56),dp(56));elevation=dp(8).toFloat()
  }
  root=bubble;params.width=dp(56);params.height=dp(56);attach(bubble);draggable(bubble) { if(paused) { paused=false;pauseCapture(false);showBubble() } else showPanel() }
 }
 private fun showPanel() {
  close()
  val column=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8));background=background(Color.rgb(24,39,45),24f);elevation=dp(10).toFloat() }
  val title=text("Ink2TeX  ·  drag to move",16f).apply { typeface=Typeface.DEFAULT_BOLD }
  column.addView(title);draggable(title) {}
  fun action(label:String,run:()->Unit) { column.addView(text(label,14f).apply { isClickable=true;isFocusable=true;setOnClickListener { run() } }) }
  action("⌄  Collapse") { showBubble() }
  action("▣  Screenshot Scan") { scan("screen") }
  action("⌗  Rectangular Snip Scan") { scan("rect") }
  action("◯  Freeform Snip Scan") { scan("free") }
  action("▤  Multi-Snip Scan") { scan("multi") }
  action("↧  Scrolling Proof Scan") { scan("scroll") }
  action("＋  Import Image / PDF") { open("import",null,null) }
  action("↶  Previous Results") { open("history",null,null) }
  action("⧉  Copy Last LaTeX") { open("copy",null,null) }
  action("◈  OCR Model Selector") { open("models",null,null) }
  action("⚙  Scanner Settings") { open("settings",null,null) }
  action("Ⅱ  Pause scanner") { paused=true;pauseCapture(true);showBubble();Toast.makeText(context,"Scanner paused. Tap the bubble to resume. Capture authorization remains active until Stop.",Toast.LENGTH_LONG).show() }
  action("■  Stop Conversion Mode",stop)
  val scroll=ScrollView(context).apply { addView(column);isFillViewport=false }
  root=scroll;params.width=dp(if(settings.compact) 252 else 300);params.height=minOf(dp(if(settings.compact) 470 else 650),wm.currentWindowMetrics.bounds.height()-dp(80));attach(scroll)
 }
 private fun attach(view:View) { view.alpha=settings.opacity;clamp();runCatching { wm.addView(view,params) }.onFailure { Toast.makeText(context,"Overlay permission is unavailable. Stop and restart Conversion Mode.",Toast.LENGTH_LONG).show() } }
 private fun clamp() { val bounds=wm.currentWindowMetrics.bounds;params.x=params.x.coerceIn(0,max(0,bounds.width()-params.width));params.y=params.y.coerceIn(0,max(0,bounds.height()-max(dp(56),params.height))) }
 private fun draggable(handle:View,click:()->Unit) {
  var startX=0f;var startY=0f;var originX=0;var originY=0;var moved=false
  handle.setOnClickListener { click() }
  handle.setOnTouchListener { _,event ->
   when(event.actionMasked) {
    MotionEvent.ACTION_DOWN -> { handle.parent?.requestDisallowInterceptTouchEvent(true);startX=event.rawX;startY=event.rawY;originX=params.x;originY=params.y;moved=false;true }
    MotionEvent.ACTION_MOVE -> {
     val dx=event.rawX-startX;val dy=event.rawY-startY
     if(abs(dx)+abs(dy)>dp(6))moved=true
     if(moved) { params.x=originX+dx.toInt();params.y=originY+dy.toInt();clamp();root?.let { runCatching { wm.updateViewLayout(it,params) } } };true
    }
    MotionEvent.ACTION_UP -> {
     if(moved) {
      val width=wm.currentWindowMetrics.bounds.width();params.x=if(params.x+params.width/2<width/2) 0 else max(0,width-params.width)
      root?.let { runCatching { wm.updateViewLayout(it,params) } }
      scope.launch { store.position(params.x,params.y) }
     } else handle.performClick();true
    }
    else -> true
   }
  }
 }
 fun hide() { root?.visibility=View.INVISIBLE }
 fun close() { root?.let { runCatching { wm.removeView(it) } };root=null }
}
