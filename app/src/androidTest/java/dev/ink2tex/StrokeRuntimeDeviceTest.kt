package dev.ink2tex

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ink2tex.ink.AbelFormula
import dev.ink2tex.ink.GoogleStrokeText
import dev.ink2tex.ink.StrokeModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StrokeRuntimeDeviceTest {
    @Test fun installedStrokeModelsRunTheirLocalInferenceChecks() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Set up the stroke models in Model Store before this test.", StrokeModels.ready(context))
        AbelFormula.open(context, StrokeModels.directory(context)).use { AbelFormula.smoke(it) }
        GoogleStrokeText().use { it.validate() }
    }
}
