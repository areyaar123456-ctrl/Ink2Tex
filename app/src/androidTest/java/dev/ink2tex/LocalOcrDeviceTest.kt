package dev.ink2tex

import android.graphics.BitmapFactory
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
