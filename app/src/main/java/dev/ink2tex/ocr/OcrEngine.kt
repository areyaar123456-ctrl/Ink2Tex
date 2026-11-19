package dev.ink2tex.ocr

import android.graphics.Bitmap
import dev.ink2tex.storage.HandwritingExample
import kotlinx.coroutines.flow.StateFlow

data class RecognitionOptions(val maxTokens: Int = 2048, val examples: List<HandwritingExample> = emptyList())
data class RecognitionResult(val markdown: String, val durationMs: Long, val tokens: Int, val truncated: Boolean, val warnings: List<String> = emptyList())
data class LoadResult(val modelId: String, val durationMs: Long)
data class Diagnostics(val loadedModel: String? = null, val initializationMs: Long? = null, val inferenceMs: Long? = null, val outputTokens: Int? = null, val lastError: String? = null, val stage: String = "Idle", val backendInfo: String? = null, val preprocessingMs: Long? = null, val evaluationMs: Long? = null, val generationMs: Long? = null, val inputTokens: Long? = null, val imageTokenBudget: Int = 1600)
interface OcrEngine {
 val diagnostics: StateFlow<Diagnostics>
 suspend fun loadModel(modelId: String): LoadResult
 suspend fun recognize(image: Bitmap, options: RecognitionOptions = RecognitionOptions()): RecognitionResult
 suspend fun unloadModel()
 fun cancel()
}
object NativeBridge {
 init { System.loadLibrary("ink2tex") }
 external fun load(model: String, projector: String, threads: Int, backendDirectory: String, imageTokenBudget: Int)
 external fun recognize(
  rgb: ByteArray, width: Int, height: Int, prompt: String, maxTokens: Int,
  examplePixels: Array<ByteArray>, exampleWidths: IntArray, exampleHeights: IntArray,
  exampleText: Array<ByteArray>
 ): ByteArray
 external fun unload()
 external fun timings(): LongArray
 external fun systemInfo(): String
 external fun status(): IntArray
 external fun prepare()
 external fun cancel()
}
