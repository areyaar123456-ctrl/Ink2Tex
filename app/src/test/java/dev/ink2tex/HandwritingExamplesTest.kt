package dev.ink2tex

import android.graphics.Bitmap
import dev.ink2tex.storage.HandwritingExample
import dev.ink2tex.storage.HandwritingExamples
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=InkApplication::class)
class HandwritingExamplesTest {
 @Test fun keepsOnlyRecentCheckedExamplesAndSkipsTargetImage() = runBlocking {
  val app=RuntimeEnvironment.getApplication() as InkApplication
  val store=HandwritingExamples(app)
  store.enable(true)
  val examples=(1..3).map { index ->
   HandwritingExample("example-$index",File.createTempFile("example-$index",".png",app.cacheDir).path,
    "Exact example $index: \$p \\nmid a\$")
  }
  for(example in examples)store.save(example)
  val latest=HandwritingExamples(app).profile.first()
  assertEquals(examples.takeLast(2),latest.examples)
  assertEquals(listOf(examples[1]),latest.forScan(listOf(examples.last().source)))
  store.enable(false)
  assertTrue(store.profile.first().forScan(emptyList()).isEmpty())
  store.enable(true)
  assertEquals(examples.takeLast(2),store.profile.first().forScan(emptyList()))
 }

 @Test fun deletingOriginalRemovesItsExampleEvenIfAnImageIsShared() = runBlocking {
  val app=RuntimeEnvironment.getApplication() as InkApplication
  val repository=app.repository
  val bitmap=Bitmap.createBitmap(120,80,Bitmap.Config.ARGB_8888)
  val original=try { repository.save(bitmap) } finally { bitmap.recycle() }
  val shared=original.copy(id="shared-note")
  repository.documents.put(shared)
  repository.teachHandwriting(original.id,"A checked example")
  assertTrue(repository.handwriting.profile.first().examples.any { it.documentId==original.id })
  repository.deleteOriginals(original)
  assertTrue(File(shared.source).exists())
  assertFalse(repository.handwriting.profile.first().examples.any { it.documentId==original.id })
 }
}
