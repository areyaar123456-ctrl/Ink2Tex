package dev.ink2tex

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=InkApplication::class)
class NativeStartupFailureTest {
 @Test fun missingAndroidNativeLibraryReturnsFailureInsteadOfHanging() = runBlocking {
  // The host JVM has no Android ARM64 JNI library. The failed startup must settle its waiter.
  val app=RuntimeEnvironment.getApplication() as InkApplication
  val error=runCatching { withTimeout(5000) { app.engine.unloadModel() } }.exceptionOrNull()
  assertTrue("Expected a native linkage failure rather than an uncompleted request: $error",generateSequence(error) { it.cause }.any { it is LinkageError })
 }
}
