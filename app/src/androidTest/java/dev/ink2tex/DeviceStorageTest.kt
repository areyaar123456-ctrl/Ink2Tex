package dev.ink2tex

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ink2tex.modelmanager.ModelStore
import dev.ink2tex.preprocessing.ImageSections
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on an actual Android target; does not assert permissions the user has not granted. */
@RunWith(AndroidJUnit4::class)
class DeviceStorageTest {
 @Test fun cpuBackendModulesArePackagedForRuntimeSelection() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val directory=java.io.File(context.applicationInfo.nativeLibraryDir)
  for(name in listOf("libggml-cpu-android_armv8.0_1.so","libggml-cpu-android_armv8.2_2.so","libggml-cpu-android_armv8.6_1.so")) {
   assertTrue("Missing packaged CPU backend: $name",java.io.File(directory,name).isFile)
  }
 }
 @Test fun largeImageRegionDecoderPreservesOriginalPixels() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val original=android.graphics.Bitmap.createBitmap(2500,2000,android.graphics.Bitmap.Config.ARGB_8888)
  original.eraseColor(android.graphics.Color.BLUE)
  val file=java.io.File(context.cacheDir,"large-device-crop.png")
  file.outputStream().use { original.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
  val result=dev.ink2tex.crop.CropView.crop(file,dev.ink2tex.crop.CropSelection(android.graphics.RectF(0.1f,0.1f,0.8f,0.8f)))
  try { assertEquals(android.graphics.Color.BLUE,result.getPixel(result.width/2,result.height/2)) }
  finally { result.recycle();original.recycle();file.delete() }
 }
 @Test fun modelRegistryLoadsOfflineAndNoCandidateIsSilentlyInstalled() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val store=ModelStore(context)
  assertEquals(8,store.manifests.size)
  assertTrue(store.manifests.filter { !it.compatible }.all { !store.ready(it.id) })
 }
 @Test fun splitsSavedImageIntoOrderedNonOverlappingSnips() = runBlocking {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  val file = File.createTempFile("training-snips", ".png", context.cacheDir)
  val original = Bitmap.createBitmap(300, 1000, Bitmap.Config.ARGB_8888)
  try {
   original.eraseColor(Color.WHITE)
   val paint = Paint()
   val colors = listOf(Color.RED, Color.BLUE)
   val canvas = Canvas(original)
   paint.color = colors[0]
   canvas.drawRect(20f, 90f, 280f, 180f, paint)
   paint.color = colors[1]
   canvas.drawRect(20f, 800f, 280f, 900f, paint)
   file.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
   var coveredRows = 0
   val count = ImageSections.forEach(file, requireSplit = true) { snip, index, total ->
    assertEquals(2, total)
    assertEquals(original.width, snip.width)
    assertEquals(colors[index], snip.getPixel(150, (if (index == 0) 100 else 810) - coveredRows))
    coveredRows += snip.height
   }
   assertEquals(2, count)
   assertEquals(original.height, coveredRows)
  } finally {
   original.recycle()
   file.delete()
  }
 }
}
