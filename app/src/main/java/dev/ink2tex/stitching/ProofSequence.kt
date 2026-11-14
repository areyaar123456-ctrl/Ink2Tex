package dev.ink2tex.stitching

data class MergedProof(val text: String,val warnings: List<String>)
object ProofSequence {
 /** Only exact, substantial boundary matches are removed, and only by explicit choice. */
 fun merge(sections: List<String>,deduplicate: Boolean=false): MergedProof {
  if(sections.isEmpty())return MergedProof("",emptyList())
  var result=sections.first();var removed=0
  for(next in sections.drop(1)) {
   val a=result.trimEnd().lines();val b=next.trimStart().lines()
   val overlap=if(deduplicate) (minOf(a.size,b.size,40) downTo 3).firstOrNull { n -> a.takeLast(n)==b.take(n) && b.take(n).joinToString("\n").length>=80 } ?: 0 else 0
   removed+=overlap
   result=result.trimEnd()+"\n\n"+b.drop(overlap).joinToString("\n")
  }
  val warnings=mutableListOf<String>()
  if(sections.size>1) warnings+="Sections follow your chosen order. Check boundaries for missing or duplicated lines, especially across processing tiles."
  if(removed>0) warnings+="Removed $removed exactly matching boundary lines. Original images remain available for review."
  return MergedProof(result,warnings)
 }
}
