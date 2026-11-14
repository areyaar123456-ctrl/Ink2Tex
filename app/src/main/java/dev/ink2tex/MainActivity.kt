package dev.ink2tex

import android.Manifest
import android.app.Activity
import android.content.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import dev.ink2tex.capture.CaptureService
import dev.ink2tex.ui.InkViewModel
import dev.ink2tex.ui.InkApp

class MainActivity:ComponentActivity() {
 private val vm:InkViewModel by viewModels()
 private val projection=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
  if(result.resultCode==Activity.RESULT_OK && result.data!=null) {
   try {
    val intent=Intent(this,CaptureService::class.java).putExtra("resultCode",result.resultCode).putExtra("consent",result.data)
    ContextCompat.startForegroundService(this,intent)
   } catch(e:Exception) { vm.message.value="Could not start screen capture: ${e.message}" }
  } else vm.message.value="Screen capture was not started. You can try again at any time."
 }
 private val overlay=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
  if(Settings.canDrawOverlays(this))requestCapture() else vm.message.value="Allow display over other apps to use the floating scanner. Image import remains available."
 }
 private val notification=registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestOverlay() }
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  setContent { InkApp(vm,::startConversion,::stopConversion) }
  if(savedInstanceState==null)handle(intent)
 }
 override fun onNewIntent(intent:Intent) { super.onNewIntent(intent);setIntent(intent);handle(intent) }
 private fun handle(intent:Intent) {
  if(intent.action==Intent.ACTION_SEND) {
   val uri=if(Build.VERSION.SDK_INT>=33)intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
   uri?.let { vm.import(it) };return
  }
  val page=intent.getStringExtra("page")
  val doc=intent.getStringExtra("document")
  when(page) {
   "result" -> doc?.let { vm.capture(it,"screen") }
   "crop" -> doc?.let { vm.capture(it,intent.getStringExtra("mode") ?: "rect") }
   "copy" -> { vm.copyLast();vm.go("home") }
   null -> if(intent.action=="dev.ink2tex.START")startConversion()
   else -> vm.go(page)
  }
 }
 private fun startConversion() {
  if(CaptureService.active.value) { vm.message.value="Conversion Mode is active. Return to Nebo and open the floating scanner.";return }
  if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)notification.launch(Manifest.permission.POST_NOTIFICATIONS)
  else requestOverlay()
 }
 private fun requestOverlay() {
  if(Settings.canDrawOverlays(this))requestCapture()
  else overlay.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
 }
 private fun requestCapture() { projection.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()) }
 private fun stopConversion() { startService(Intent(this,CaptureService::class.java).setAction(CaptureService.STOP)) }
}
