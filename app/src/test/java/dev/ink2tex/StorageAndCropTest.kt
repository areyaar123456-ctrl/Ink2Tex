package dev.ink2tex

import android.graphics.*
import org.robolectric.RuntimeEnvironment
import dev.ink2tex.crop.CropSelection
import dev.ink2tex.crop.CropView
import dev.ink2tex.modelmanager.ModelStore
import dev.ink2tex.storage.*
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=InkApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StorageAndCropTest {
 private val context get()=RuntimeEnvironment.getApplication() as InkApplication
 @Test fun allDownloadableModelsIncludePinnedWeightsAndVisionChecksums() {
  val store=ModelStore(context)
  for(model in store.manifests.filter { it.compatible }) {
   assertEquals(2,model.components.size)
   assertTrue(model.components[1].name.startsWith("mmproj"))
   assertTrue(model.components.all { it.sha256.matches(Regex("[0-9a-f]{64}")) && Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(it.url) && it.size>0 })
  }
  assertTrue(store.manifests.filter { !it.compatible }.all { it.components.isEmpty() })
 }
 @Test fun filesWithoutValidationMarkerCannotBecomeReady() {
  val store=ModelStore(context);val dir=store.directory("ovis2-q8");dir.mkdirs();File(dir,"ready.json").delete()
  assertFalse(store.ready("ovis2-q8"))
 }
 @Test fun checksumDetectsCorruption() {
  val file=File(context.cacheDir,"integrity.bin");file.writeText("abc")
  assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",ModelStore.sha256(file))
  val before=ModelStore.sha256(file);file.appendText("!");assertNotEquals(before,ModelStore.sha256(file))
 }
 @Test fun rectangleCropUsesOriginalCoordinates() {
  val bitmap=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.BLUE)
  val file=File(context.cacheDir,"crop.png");file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
  val cropped=CropView.crop(file,CropSelection(RectF(0.2f,0.3f,0.8f,0.9f)))
  assertEquals(60,cropped.width);assertEquals(60,cropped.height);assertEquals(Color.BLUE,cropped.getPixel(30,30));cropped.recycle();bitmap.recycle()
 }
 @Test fun freeformMaskKeepsInkInsideAndWhitesOutside() {
  val bitmap=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.BLACK)
  val file=File(context.cacheDir,"free.png");file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
  val cropped=CropView.crop(file,CropSelection(RectF(0f,0f,1f,1f),listOf(PointF(0.5f,0f),PointF(1f,1f),PointF(0f,1f))))
  assertEquals(Color.WHITE,cropped.getPixel(0,0));assertEquals(Color.BLACK,cropped.getPixel(50,70));cropped.recycle();bitmap.recycle()
 }
 @Test fun cancellationStatusCannotOverwriteManualCorrections() = runBlocking {
  val db=Room.inMemoryDatabaseBuilder(context,InkDatabase::class.java).allowMainThreadQueries().build()
  try {
   val snapshot=Document("cancel-regression","Proof",markdown="Old recognition",status="Converting")
   db.documents().put(snapshot)
   db.documents().edit(snapshot.id,"Manual correction: 2+2=5 as written.")
   db.documents().state(snapshot.id,"Cancelled","Stopped")
   assertEquals("Manual correction: 2+2=5 as written.",db.documents().get(snapshot.id)!!.markdown)
   assertEquals("Cancelled",db.documents().get(snapshot.id)!!.status)
  } finally { db.close() }
 }
 @Test fun deletingASectionDoesNotBreakACombinedProof() = runBlocking {
  val db=Room.inMemoryDatabaseBuilder(context,InkDatabase::class.java).allowMainThreadQueries().build()
  try {
   val repo=InkRepository(context,db);val bitmap=Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888)
   val section=repo.save(bitmap);val combined=section.copy(id="combined",title="Proof")
   db.documents().put(combined);repo.delete(section)
   assertTrue(File(combined.source).exists());assertNotNull(db.documents().get("combined"))
   repo.delete(combined);assertFalse(File(combined.source).exists());bitmap.recycle()
  } finally { db.close() }
 }
}
