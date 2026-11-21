package dev.ink2tex.ink

import android.content.Context
import com.myscript.iink.Engine
import com.myscript.iink.MimeType
import com.myscript.iink.PointerEvent
import com.myscript.iink.PointerEventType
import com.myscript.iink.PointerType
import com.myscript.iink.Recognizer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal object MyScriptStroke {
    fun available(context: Context): Boolean = runCatching {
        context.assets.list("myscript")?.contains("license.bin") == true &&
            context.assets.list("conf")?.any { it.endsWith(".conf") } == true
    }.getOrDefault(false)

    fun open(context: Context): StrokeRecognizer {
        require(available(context)) { "Install the licensed MyScript pilot with English, Math and Raw Content resources." }
        val certificate = context.assets.open("myscript/license.bin").use { NeboInk.limited(it, 128 * 1024) }
        val engine = Engine.create(certificate)
        return try {
            engine.configuration.apply {
                setStringArray("recognizer.configuration-manager.search-path", arrayOf("zip://${context.packageCodePath}!/assets/conf"))
                setString("recognizer.lang", "en_US")
                setStringArray("recognizer.raw-content.classification.types", arrayOf("text", "math", "drawing"))
                setStringArray("recognizer.raw-content.recognition.types", arrayOf("text", "math"))
                setString("recognizer.raw-content.configuration.analyzer.bundle", "raw-content2")
                setString("recognizer.raw-content.configuration.math.bundle", "math2")
                setBoolean("recognizer.math.solver.enable", false)
                setBoolean("recognizer.math.enable-syntactic-correction", false)
                setBoolean("recognizer.gesture.enable", false)
                setBoolean("export.jiix.ranges", true)
                setBoolean("export.jiix.math-label", true)
                setBoolean("export.jiix.strokes", false)
            }
            Runtime(engine, engine.createRecognizer(25.4f, 25.4f, "Raw Content"))
        } catch (failure: Throwable) {
            engine.close()
            throw failure
        }
    }

    private class Runtime(private val engine: Engine, private val recognizer: Recognizer) : StrokeRecognizer {
        override suspend fun recognize(page: InkPage): StrokeResult {
            if (page.strokes.isEmpty()) return StrokeResult("")
            require(page.strokes.sumOf { it.points.size } <= 120_000) { "Export this large note in smaller pages for MyScript." }
            recognizer.clear()
            val events = ArrayList<PointerEvent>()
            val bounds = requireNotNull(page.bounds)
            val beginning = page.strokes.firstNotNullOfOrNull { it.startMicros }
            var tick = 0L
            for (stroke in page.strokes) {
                currentCoroutineContext().ensureActive()
                val start = stroke.startMicros
                if (beginning != null && start != null) tick = maxOf(tick, ((start - beginning) / 1000).coerceIn(0, 3_600_000))
                val strokeStart = tick
                for ((index, point) in stroke.points.withIndex()) {
                    point.timeOffsetMs?.let { tick = maxOf(tick, strokeStart + it.coerceAtMost(60_000)) }
                    val type = when (index) {
                        0 -> PointerEventType.DOWN
                        stroke.points.lastIndex -> PointerEventType.UP
                        else -> PointerEventType.MOVE
                    }
                    val x = point.x - bounds.left + 5f
                    val y = point.y - bounds.top + 5f
                    events += PointerEvent(type, x, y, tick++, point.force / 255f, PointerType.PEN, 0)
                    if (stroke.points.size == 1) events += PointerEvent(PointerEventType.UP, x, y, tick++, point.force / 255f, PointerType.PEN, 0)
                }
                tick += 5
            }
            recognizer.pointerEvents(events.toTypedArray())
            check(withTimeoutOrNull(90_000) { while (!recognizer.isIdle) delay(50); true } == true) {
                "MyScript recognition timed out. Export this note in smaller pages."
            }
            return JiixTranscription.parse(recognizer.getResult(MimeType.JIIX), page.strokes.size)
        }

        override fun close() {
            try { recognizer.close() } finally { engine.close() }
        }
    }
}
