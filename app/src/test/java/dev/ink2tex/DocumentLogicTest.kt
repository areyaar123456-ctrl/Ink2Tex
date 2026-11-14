package dev.ink2tex

import dev.ink2tex.preprocessing.TilePlanner
import dev.ink2tex.stitching.ProofSequence
import dev.ink2tex.ocr.LocalOcrEngine
import dev.ink2tex.export.DocumentExport
import org.junit.Assert.*
import org.junit.Test

class DocumentLogicTest {
 @Test fun tilesCoverLargeExportWithoutGapsAndRemainBounded() {
  val width=4097;val height=9203
  val tiles=TilePlanner.plan(width,height)
  assertTrue(tiles.size>1)
  assertTrue(tiles.all { it.right-it.left<=1600 && it.bottom-it.top<=1600 })
  for(y in 0 until height step 37)for(x in 0 until width step 29)assertTrue("Gap at \$x,\$y",tiles.any { x in it.left until it.right && y in it.top until it.bottom })
  assertTrue(tiles.zipWithNext().all { (a,b) -> b.top>a.top || b.top==a.top && b.left>a.left })
 }
 @Test fun smallScreenshotIsOneCoherentInput() { assertEquals(1,TilePlanner.plan(1200,1500).size) }
 @Test(expected=IllegalArgumentException::class) fun rejectInvalidTileBudget() { TilePlanner.plan(0,100) }
 @Test fun proofErrorsAreNotRewritten() {
  val original="Let a > b.\n\n\$\$\n2+2=5,\\quad a\\mid b.\n\$\$\n\nTherefore c < 0."
  assertEquals(original,ProofSequence.merge(listOf(original)).text)
 }
 @Test fun ordinaryRepetitionIsPreservedByDefault() {
  val boundary="Assume the first condition is satisfied.\nThen consider the second independent condition.\nFinally record the third condition."
  val merged=ProofSequence.merge(listOf("Start\n"+boundary,boundary+"\nEnd"))
  assertEquals(2,Regex("Finally record").findAll(merged.text).count())
 }
 @Test fun exactSubstantialBoundaryOnlyRemovedWhenRequested() {
  val boundary="Assume the first condition is satisfied.\nThen consider the second independent condition.\nFinally record the third condition."
  val merged=ProofSequence.merge(listOf("Start\n"+boundary,boundary+"\nEnd"),true)
  assertEquals(1,Regex("Finally record").findAll(merged.text).count());assertTrue(merged.warnings.any { it.contains("Removed 3") })
 }
 @Test fun changedSignsAtBoundaryAreNeverDeduplicated() {
  val a="Long hypothesis that must remain unchanged.\nThe first inequality is a < b.\nA further observation is recorded here."
  val b=a.replace("a < b","a > b")
  val result=ProofSequence.merge(listOf(a,b),true).text
  assertTrue(result.contains("a < b"));assertTrue(result.contains("a > b"))
 }
 @Test fun normalizationOnlyChangesMathDelimiters() {
  assertEquals("Given \$a_1\$ and \$\$a>b\$\$",LocalOcrEngine.normalizeDelimiters("Given \\(a_1\\) and \\[a>b\\]"))
 }
 @Test fun latexExportEscapesProseButKeepsMathSource() {
  val exported=DocumentExport.tex("A & B: 50% under_score.\n\n\$\$a_1 \\le b^2\$\$")
  assertTrue(exported.contains("A \\& B: 50\\% under\\_score."));assertTrue(exported.contains("\$\$a_1 \\le b^2\$\$"));assertTrue(exported.endsWith("\\end{document}\n"))
 }
 @Test fun markdownDoesNotRewriteUnderscoresInsideMathematics() {
  val html=DocumentExport.html("Given \$a_b_c + x^2 < y\$.")
  assertTrue(html.contains("\$a_b_c + x^2 &lt; y\$"));assertFalse(html.contains("a<em>"))
 }
 @Test fun previewDoesNotExecuteModelHtmlOrRemoteLinks() {
  val html=DocumentExport.html("<script>fetch('https://evil.example')</script>\n\n![x](https://evil.example/image)\n\n\$a=b\$")
  assertFalse(html.contains("<script>fetch"));assertTrue(html.contains("&lt;script&gt;"));assertTrue(html.contains("default-src 'none'"));assertTrue(html.contains("assets/katex/katex.min.js"))
 }
}
