package dev.ink2tex

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dev.ink2tex.ocr.RecognitionOptions
import dev.ink2tex.storage.HandwritingExample
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Requires a model installed through the UI. Never substitutes mock output. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class LocalOcrDeviceTest {
 private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as InkApplication
 @Test fun installedModelReadsPixelsRepeatedlyWithoutNetworkDependency() = runBlocking {
  val model=app.models.available().firstOrNull()
  Assume.assumeTrue("Install and validate an OvisOCR2 model before this test",model!=null)
  val image=app.assets.open("validation.png").use { BitmapFactory.decodeStream(it) }
  app.engine.session.withLock {
   try {
    app.engine.loadModel(model!!.id)
    repeat(3) { index ->
     app.engine.loadModel(model.id,false,if(index==1)256 else 1600)
     val result=app.engine.recognize(image)
     assertTrue(result.markdown.lowercase().contains("ink2tex"))
     assertTrue(result.tokens>0);assertTrue(result.durationMs>0);assertFalse(result.truncated)
     val diagnostic=app.engine.diagnostics.value
     assertTrue(diagnostic.inputTokens!!>0)
     assertTrue(diagnostic.preprocessingMs!!>=0 && diagnostic.evaluationMs!!>=0 && diagnostic.generationMs!!>=0)
    }
   } finally { image.recycle();app.engine.unloadModel() }
  }
 }
 @Test fun exampleImagesAreIncludedWithoutBeingCopiedIntoTargetText() = runBlocking {
  val model=app.models.available().firstOrNull()
  Assume.assumeTrue("Install and validate an OvisOCR2 model first",model!=null)
  val target=app.assets.open("validation.png").use { BitmapFactory.decodeStream(it) }
  val examples=listOf("Example violet", "Example amber").mapIndexed { index, text ->
   val bitmap=Bitmap.createBitmap(480,120,Bitmap.Config.ARGB_8888)
   val file=File.createTempFile("ink2tex-example",".png",app.cacheDir)
   try {
    bitmap.eraseColor(Color.WHITE)
    Canvas(bitmap).drawText(text,20f,72f,Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize=45f })
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    HandwritingExample("device-example-$index",file.path,text)
   } finally { bitmap.recycle() }
  }
  try {
   app.engine.session.withLock {
    try {
     app.engine.loadModel(model!!.id)
     val baseline=app.engine.recognize(target,RecognitionOptions(maxTokens=128))
     val baselineInput=app.engine.diagnostics.value.inputTokens!!
     assertTrue(baseline.markdown.contains("ink2tex",ignoreCase=true))
     val personalized=app.engine.recognize(target,RecognitionOptions(maxTokens=128,examples=examples))
     assertTrue(app.engine.diagnostics.value.inputTokens!! > baselineInput)
     assertTrue(personalized.markdown.contains("ink2tex",ignoreCase=true))
     assertTrue(examples.none { personalized.markdown.contains(it.transcription,ignoreCase=true) })
    } finally { app.engine.unloadModel() }
   }
  } finally {
   target.recycle()
   examples.forEach { File(it.source).delete() }
  }
 }
 @Test fun cancellationReleasesNativeOwnershipAndAllowsAnotherLoad() = runBlocking {
  val model=app.models.available().firstOrNull()
  Assume.assumeTrue("Install and validate a model first",model!=null)
  app.engine.session.withLock {
   val operation=launch(Dispatchers.Default) { app.engine.loadModel(model!!.id) }
   delay(100);operation.cancelAndJoin();assertTrue(operation.isCancelled)
   app.engine.unloadModel()
   val result=app.engine.loadModel(model!!.id)
   assertEquals(model.id,result.modelId)
   app.engine.unloadModel()
  }
 }
}
