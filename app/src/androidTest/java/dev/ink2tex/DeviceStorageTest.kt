package dev.ink2tex

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ink2tex.modelmanager.ModelStore
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
}
