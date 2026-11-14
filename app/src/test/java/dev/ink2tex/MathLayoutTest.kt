package dev.ink2tex

import dev.ink2tex.format.MathLayout
import dev.ink2tex.export.DocumentExport
import org.junit.Assert.*
import org.junit.Test

class MathLayoutTest {
 @Test fun standaloneSummationKeepsExactlyTheWrittenExpression() {
  val body="\\sum_{k=1}^{2} k^2 + 1"
  val original="Well, 2 + 2 is 4, giving\n\n\$$body\$"
  assertEquals("Well, 2 + 2 is 4, giving\n\n\$\$\n$body\n\$\$",MathLayout.displayStandalone(original))
 }
 @Test fun inlineEquationsAndSeparateInlineSpansStayInline() {
  val text="Given \$a<b\$, assume \$a\\mid b\$.\n\$x\$ and \$y\$\n\$x\$ \$y\$"
  assertEquals(text,MathLayout.displayStandalone(text))
 }
 @Test fun formattingDoesNotCorrectMistakesOrChangeGrouping() {
  val body="2+2=5,\\quad b\\mid a,\\quad a>b,\\quad \\sum_{K=1}^{2}K^2+1"
  val formatted=MathLayout.displayStandalone("\$$body\$")
  assertEquals("\$\$\n$body\n\$\$",formatted)
 }
 @Test fun existingDisplayMathIsIdempotent() {
  val text="Given\n\n\$\$\nx^2+1\n\$\$\n\n\$\$y^2\$\$"
  assertEquals(text,MathLayout.displayStandalone(text))
  val formatted=MathLayout.displayStandalone("\$x^2\$\n\$y^2\$")
  assertEquals(formatted,MathLayout.displayStandalone(formatted))
 }
 @Test fun fencedAndIndentedCodeAreNotReformatted() {
  val text="```latex\n\$x\$\n```\n~~~~\n\$y\$\n~~~~\n    \$z\$\n\t\$w\$"
  assertEquals(text,MathLayout.displayStandalone(text))
 }
 @Test fun escapedDollarsInsideMathAreKeptAndCurrencyIsNotRewritten() {
  assertEquals("\$\$\n\\text{cost \\$5}\n\$\$",MathLayout.displayStandalone("\$\\text{cost \\$5}\$"))
  val text="Costs \\$5.\n\\\$5\\\$\n\$unfinished"
  assertEquals(text,MathLayout.displayStandalone(text))
 }
 @Test fun codeFenceContentCannotCloseFenceWithTrailingProse() {
  val text="```\n``` not a closing fence\n\$x\$\n```"
  assertEquals(text,MathLayout.displayStandalone(text))
 }
 @Test fun existingSavedInlineEquationGetsDisplayPreviewWithoutChangingItsSource() {
  val original="Giving\n\n\$\\sum_{k=1}^{2} k^2 + 1\$"
  val html=DocumentExport.html(original)
  assertTrue(html.contains("\$\$\n\\sum_{k=1}^{2} k^2 + 1\n\$\$"))
  assertTrue(html.contains("text-align:left"))
  assertEquals("Giving\n\n\$\\sum_{k=1}^{2} k^2 + 1\$",original)
 }
}
