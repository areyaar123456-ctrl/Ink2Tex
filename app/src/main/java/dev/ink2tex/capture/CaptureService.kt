package dev.ink2tex.capture

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.*
import android.os.*
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import dev.ink2tex.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class CaptureService : Service() {
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
 private val thread=HandlerThread("Ink2TeX-capture")
 private lateinit var handler: Handler
 private var projection: MediaProjection?=null
 private var display: VirtualDisplay?=null
 private var reader: ImageReader?=null
 private var latest: Image?=null // Owned exclusively by capture thread.
 private var overlay: ScannerOverlay?=null
 @Volatile private var paused=false
 private var busy=false
 private var stopped=false
 private var width=1;private var height=1
 private val callback=object: MediaProjection.Callback() {
  override fun onStop() { stopSession() }
  override fun onCapturedContentResize(w:Int,h:Int) { if(w>0 && h>0) resize(w,h) }
 }
 override fun onCreate() { super.onCreate();thread.start();handler=Handler(thread.looper) }
 override fun onBind(intent:Intent?) = null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(intent?.action==STOP) { stopSession();return START_NOT_STICKY }
  if(intent?.action==RESUME) { overlay?.paused=false; active.value=true;overlay?.showBubble();return START_NOT_STICKY }
  if(projection!=null)return START_NOT_STICKY
  val consent=if(Build.VERSION.SDK_INT>=33) intent?.getParcelableExtra("consent",Intent::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra("consent")
  val code=intent?.getIntExtra("resultCode",Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
  if(consent==null || code!=Activity.RESULT_OK || !Settings.canDrawOverlays(this)) { stopSelf();return START_NOT_STICKY }
  try {
   val open=PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
   val stop=PendingIntent.getService(this,2,Intent(this,CaptureService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
   val notification=NotificationCompat.Builder(this,"capture").setSmallIcon(R.drawable.ic_ink).setContentTitle("Ink2TeX · screen capture active").setContentText("Only scans you request are saved. Tap Stop to end access.").setOngoing(true).setContentIntent(open).addAction(0,"Stop",stop).build()
   startForeground(100,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
   projection=getSystemService(MediaProjectionManager::class.java).getMediaProjection(code,consent)
   projection!!.registerCallback(callback,Handler(mainLooper))
   val bounds=getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
   width=bounds.width();height=bounds.height()
   reader=newReader(width,height)
   display=projection!!.createVirtualDisplay("Ink2TeX",width,height,resources.configuration.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,handler)
   overlay=ScannerOverlay(this,scope,::scan,::open,::stopSession) { value -> paused=value;handler.post { if(value) { latest?.close();latest=null } } }
   scope.launch { overlay?.applySettings((application as InkApplication).repository.settings.flow.first());overlay?.showBubble() }
   active.value=true
  } catch(e:Exception) { Toast.makeText(this,"Capture could not start: ${e.message}",Toast.LENGTH_LONG).show();stopSession() }
  return START_NOT_STICKY
 }
 private fun newReader(w:Int,h:Int):ImageReader = ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,3).apply {
  setOnImageAvailableListener({ source ->
   runCatching { source.acquireLatestImage() }.getOrNull()?.let { next -> latest?.close();latest=if(paused) { next.close();null } else next }
  },handler)
 }
 private fun resize(w:Int,h:Int) {
  if(stopped || (w==width && h==height))return
  width=w;height=h
  handler.post {
   if(stopped)return@post
   latest?.close();latest=null
   val old=reader;val replacement=newReader(w,h);reader=replacement
   display?.resize(w,h,resources.configuration.densityDpi)
   display?.surface=replacement.surface
   old?.close()
  }
 }
 private fun scan(mode:String) {
  if(busy || paused)return
  busy=true
  overlay?.hide()
  scope.launch {
   try {
    delay(300) // Let SurfaceFlinger present the frame after overlay removal.
    val bitmap=withContext(Dispatchers.IO) { captureBitmap() }
    val app=application as InkApplication
    val doc=app.repository.save(bitmap,if(mode=="scroll") "Scrolling proof section" else "Screen capture")
    bitmap.recycle()
    open(if(mode=="screen") "result" else "crop",doc.id,mode)
   } catch(e:Exception) { Toast.makeText(this@CaptureService,e.message ?: "Capture unavailable",Toast.LENGTH_LONG).show() }
   finally { busy=false;overlay?.showBubble() }
  }
 }
 private suspend fun captureBitmap():Bitmap = suspendCancellableCoroutine { continuation ->
  handler.post {
   try {
    check(!stopped) { "Capture session ended. Start Conversion Mode again." }
    val image=latest ?: error("No frame available. Return to the shared app and try again. Protected content cannot be captured.")
    val plane=image.planes[0];val buffer=plane.buffer;buffer.rewind()
    val paddedWidth=plane.rowStride/plane.pixelStride
    val padded=Bitmap.createBitmap(paddedWidth,image.height,Bitmap.Config.ARGB_8888)
    padded.copyPixelsFromBuffer(buffer)
    val result=if(paddedWidth==image.width) padded else Bitmap.createBitmap(padded,0,0,image.width,image.height).also { padded.recycle() }
    if(continuation.isActive) continuation.resumeWith(kotlin.Result.success(result)) else result.recycle()
   } catch(e:Exception) { if(continuation.isActive)continuation.resumeWith(kotlin.Result.failure(e)) }
  }
 }
 private fun open(page:String,doc:String?=null,mode:String?=null) {
  overlay?.showBubble()
  val intent=Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("page",page)
  doc?.let { intent.putExtra("document",it) };mode?.let { intent.putExtra("mode",it) }
  try { startActivity(intent) } catch(e:Exception) { Toast.makeText(this,"Open Ink2TeX from its notification to continue.",Toast.LENGTH_LONG).show() }
 }
 private fun stopSession() {
  if(stopped)return
  stopped=true;active.value=false
  overlay?.close();overlay=null
  projection?.unregisterCallback(callback)
  display?.release();display=null
  handler.post { latest?.close();latest=null;reader?.close();reader=null }
  projection?.stop();projection=null
  stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
 }
 override fun onDestroy() { stopSession();scope.cancel();thread.quitSafely();super.onDestroy() }
 companion object {
  const val STOP="dev.ink2tex.STOP"
  const val RESUME="dev.ink2tex.RESUME"
  val active=MutableStateFlow(false)
 }
}
