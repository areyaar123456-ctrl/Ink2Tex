package dev.ink2tex

import dev.ink2tex.preprocessing.SolidBorderTrim
import dev.ink2tex.ocr.NativeProgress
import org.junit.Assert.*
import org.junit.Test

class InferenceProgressTest {
 @Test fun trimRetainsEvenAlmostInvisibleMarks() {
  val pixels=IntArray(100*100){-1}
  pixels[50*100+60]=0xfffffffe.toInt()
  val bounds=SolidBorderTrim.bounds(pixels,100,100)
  assertTrue(bounds.width<100 && bounds.height<100)
  assertTrue(60 in bounds.left until bounds.right && 50 in bounds.top until bounds.bottom)
  for(y in 0 until 100)for(x in 0 until 100)if(x !in bounds.left until bounds.right || y !in bounds.top until bounds.bottom)assertEquals(-1,pixels[y*100+x])
 }
 @Test fun nonuniformCornersPreventTrimming() {
  val pixels=IntArray(100){-1};pixels[9]=0
  assertEquals(10,SolidBorderTrim.bounds(pixels,10,10).width)
 }
 @Test fun ruledLinesArePreserved() {
  val pixels=IntArray(100*100){-1};for(x in 0 until 100)pixels[50*100+x]=0xffeeeeee.toInt()
  val bounds=SolidBorderTrim.bounds(pixels,100,100)
  assertEquals(100,bounds.width)
 }
 @Test fun imageAnalysisDoesNotPretendToHaveGeneratedTokens() {
  val label=NativeProgress.describe(4,99,120)
  assertTrue(label.contains("120s"));assertFalse(label.contains("99"));assertFalse(label.contains("%"))
 }
 @Test fun generationReportsActualNativeTokenCount() {
  assertTrue(NativeProgress.describe(5,17,20).contains("17 output tokens"))
 }
}
