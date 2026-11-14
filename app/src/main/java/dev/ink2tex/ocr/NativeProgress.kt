package dev.ink2tex.ocr

object NativeProgress {
 fun describe(phase:Int,tokens:Int,seconds:Long):String {
  val label=when(phase) {
   1 -> "Loading language model"
   2 -> "Loading vision model"
   3 -> "Preparing image"
   4 -> "Analyzing image and prompt · CPU"
   5 -> "Generating transcription · $tokens output tokens"
   7 -> "Preparing CPU vision cache · first use"
   6 -> "Releasing model memory"
   else -> "Starting native operation"
  }
  return "$label · ${seconds}s elapsed"
 }
}
