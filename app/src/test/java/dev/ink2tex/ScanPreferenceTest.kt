package dev.ink2tex

import dev.ink2tex.storage.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=InkApplication::class)
class ScanPreferenceTest {
 @Test fun scanPreferenceSurvivesSettingsRecreationAndCanRestoreFullDetail() = runBlocking {
  val app=RuntimeEnvironment.getApplication() as InkApplication
  Settings(app).fastScan(true)
  assertTrue(Settings(app).flow.first().fastScan)
  Settings(app).fastScan(false)
  assertFalse(Settings(app).flow.first().fastScan)
 }
}
