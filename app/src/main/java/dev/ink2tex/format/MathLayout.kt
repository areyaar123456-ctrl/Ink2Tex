package dev.ink2tex.format

/** Layout only: never changes symbols, grouping, operators or equation contents. */
object MathLayout {
 fun displayStandalone(markdown:String):String {
  var fence:Char?=null
  var fenceLength=0
  var inDisplay=false
  return markdown.split('\n').joinToString("\n") { line ->
   val trimmed=line.trim()
   val marker=trimmed.firstOrNull()
   val run=if(marker=='`' || marker=='~')trimmed.takeWhile { it==marker }.length else 0
   if(fence!=null) {
    if(marker==fence && run>=fenceLength && trimmed.drop(run).isBlank())fence=null
    line
   } else if(run>=3) {
    fence=marker;fenceLength=run;line
   } else if(line.startsWith("    ") || line.startsWith('\t'))line
   else {
    val displayDelimiters=trimmed.indices.count { i -> trimmed.startsWith("$$",i) && !escaped(trimmed,i) && (i==0 || trimmed[i-1]!='$') }
    val wasDisplay=inDisplay
    if(displayDelimiters%2==1)inDisplay=!inDisplay
    val single=!wasDisplay && displayDelimiters==0 && trimmed.length>2 && trimmed.first()=='$' && trimmed.last()=='$' && !escaped(trimmed,trimmed.lastIndex) && (1 until trimmed.lastIndex).none { trimmed[it]=='$' && !escaped(trimmed,it) }
    if(single) "$$\n${trimmed.substring(1,trimmed.lastIndex)}\n$$" else line
   }
  }
 }
 private fun escaped(text:String,index:Int):Boolean {
  var count=0;var i=index-1
  while(i>=0 && text[i]=='\\') { count++;i-- }
  return count%2==1
 }
}
