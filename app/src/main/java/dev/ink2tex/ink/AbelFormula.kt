package dev.ink2tex.ink

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.floor
import kotlin.math.hypot
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray

internal object AbelFeatures {
    const val MAX_POINTS = 4096

    fun from(strokes: List<InkStroke>): FloatArray? {
        if (strokes.isEmpty()) return null
        val bounds = boundsOf(strokes)
        val centerX = (bounds.left + bounds.right) / 2f
        val centerY = bounds.centerY
        val scale = maxOf(bounds.height, bounds.width * 0.05f, 0.000001f)
        val output = FloatArray(MAX_POINTS * 6)
        var row = 0
        for (stroke in strokes) {
            val distinct = mutableListOf<Pair<Float, Float>>()
            for (point in stroke.points) {
                val pair = (point.x - centerX) / scale to (point.y - centerY) / scale
                if (distinct.lastOrNull() != pair) distinct += pair
            }
            val arc = DoubleArray(distinct.size)
            for (i in 1 until arc.size) {
                val (x, y) = distinct[i]
                val (px, py) = distinct[i - 1]
                arc[i] = arc[i - 1] + hypot((x - px).toDouble(), (y - py).toDouble())
            }
            val count = if (arc.size == 1) 1 else (floor(arc.last() / 0.05).toInt() + 1).coerceAtLeast(2)
            if (count > MAX_POINTS - row) return null
            var segment = 1
            var previousX = 0f
            var previousY = 0f
            for (i in 0 until count) {
                val target = if (count == 1) 0.0 else arc.last() * i / (count - 1)
                while (segment < arc.lastIndex && arc[segment] < target) segment++
                val (x, y) = if (count == 1) distinct.first() else {
                    val (sx, sy) = distinct[segment - 1]
                    val (ex, ey) = distinct[segment]
                    val t = ((target - arc[segment - 1]) / (arc[segment] - arc[segment - 1])).toFloat()
                    sx + (ex - sx) * t to sy + (ey - sy) * t
                }
                val offset = row * 6
                output[offset] = x
                output[offset + 1] = y
                if (i == 0) output[offset + 4] = 1f else {
                    output[offset + 2] = x - previousX
                    output[offset + 3] = y - previousY
                }
                if (i == count - 1) output[offset + 5] = 1f
                previousX = x
                previousY = y
                row++
            }
        }
        return output.copyOf(row * 6)
    }
}

internal class AbelFormula private constructor(
    private val env: OrtEnvironment,
    private val encoder: OrtSession,
    private val decoder: OrtSession,
    private val vocab: List<String>
) : AutoCloseable {
    suspend fun recognize(strokes: List<InkStroke>): String? {
        val features = AbelFeatures.from(strokes) ?: return null
        val tokens = decode(features) ?: return null
        val result = StringBuilder()
        for (id in tokens) {
            val token = vocab[id]
            if (token.startsWith('<') && token.endsWith('>')) return null
            if (result.isNotEmpty() && token.firstOrNull()?.isLetter() == true &&
                Regex("""\\[A-Za-z]+$""").containsMatchIn(result)) result.append(' ')
            result.append(token)
        }
        return result.toString().takeIf { it.isNotBlank() }
    }

    suspend fun decode(features: FloatArray): List<Int>? {
        val dimensions = longArrayOf((features.size / 6).toLong(), 6L)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(features), dimensions).use { ink ->
            encoder.run(mapOf("points" to ink)).use { memory ->
                OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(8L, 0L, 32L)).use { empty ->
                    var past: OrtSession.Result? = null
                    var token = 1
                    val tokens = mutableListOf<Int>()
                    try {
                        for (position in 0 until 192) {
                            currentCoroutineContext().ensureActive()
                            longTensor(token).use { nextToken ->
                                longTensor(position).use { step ->
                                    val inputs = mutableMapOf("token" to nextToken, "position" to step)
                                    for (layer in 0 until 4) for (kv in listOf("k", "v")) {
                                        inputs["cross_$kv$layer"] = memory.get("cross_$kv$layer").get() as OnnxTensor
                                        inputs["past_$kv$layer"] = past?.get("next_$kv$layer")?.get() as? OnnxTensor ?: empty
                                    }
                                    val next = decoder.run(inputs)
                                    past?.close()
                                    past = next
                                    val logits = (next.get("logits").get().value as Array<*>).first() as FloatArray
                                    token = logits.indices.maxBy { logits[it] }
                                }
                            }
                            if (token == 2) return tokens
                            if (token < 3) return null
                            tokens += token
                        }
                        return null
                    } finally {
                        past?.close()
                    }
                }
            }
        }
    }

    private fun longTensor(value: Int) =
        OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(value.toLong())), longArrayOf(1L))

    override fun close() {
        try { decoder.close() } finally { encoder.close() }
    }

    companion object {
        private val fixture = listOf(
            listOf(1f to 0f, 1f to 4f), listOf(3f to 2f, 5f to 2f),
            listOf(4f to 1f, 4f to 3f), listOf(7f to 0f, 7f to 4f)
        ).map { trace -> InkStroke(trace.map { (x, y) -> InkPoint(x, y, 255) }) }

        suspend fun smoke(runtime: AbelFormula) {
            val features = requireNotNull(AbelFeatures.from(fixture))
            check(runtime.decode(features) == listOf(44, 27, 44)) {
                "The stroke formula model failed its runtime check."
            }
        }

        fun open(context: Context, dir: File): AbelFormula {
            val vocabulary = context.assets.open("ink/vocab.json").bufferedReader().use { JSONArray(it.readText()) }
            val vocab = List(vocabulary.length()) { vocabulary.getString(it) }
            val env = OrtEnvironment.getEnvironment()
            OrtSession.SessionOptions().use { settings ->
                settings.setIntraOpNumThreads(2)
                settings.setInterOpNumThreads(1)
                val encoder = env.createSession(File(dir, "encoder.onnx").absolutePath, settings)
                return try {
                    AbelFormula(env, encoder, env.createSession(File(dir, "decoder.onnx").absolutePath, settings), vocab)
                } catch (failure: Exception) {
                    encoder.close()
                    throw failure
                }
            }
        }
    }
}
