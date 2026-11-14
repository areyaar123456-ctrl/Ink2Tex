package dev.ink2tex.export

import dev.ink2tex.format.MathLayout
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.io.File

object DocumentExport {
 private val parser=Parser.builder().build()
 private val renderer=HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build()
 fun html(markdown:String,dark:Boolean=false):String {
  // Never interpolate untrusted OCR output into JavaScript. Raw HTML is escaped.
  val math=mutableListOf<String>()
  val mathProtected=Regex("""(?s)\$\$.*?\$\$|(?<!\\)\$(?!\$).*?(?<!\\)\$""").replace(MathLayout.displayStandalone(markdown)) { match ->
   val token="INKMATHPLACEHOLDER${math.size}END";math+=match.value;token
  }
  var body=renderer.render(parser.parse(mathProtected))
  math.forEachIndexed { i,value -> body=body.replace("INKMATHPLACEHOLDER${i}END",value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")) }
  return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src https://appassets.androidplatform.net 'unsafe-inline'; font-src https://appassets.androidplatform.net; script-src https://appassets.androidplatform.net;"><link rel="stylesheet" href="https://appassets.androidplatform.net/assets/katex/katex.min.css"><style>body{background:${if(dark)"#18272d" else "#ffffff"};color:${if(dark)"#edf6f3" else "#203236"};font:18px/1.8 system-ui;padding:24px;overflow-wrap:anywhere}.katex-display{overflow:auto;padding:10px 0;text-align:left}.katex-display>.katex{text-align:left}.katex-display.fleqn>.katex{margin-left:0}pre{white-space:pre-wrap} @media print{body{background:white;color:black}}</style><script defer src="https://appassets.androidplatform.net/assets/katex/katex.min.js"></script><script defer src="https://appassets.androidplatform.net/assets/katex/contrib/auto-render.min.js"></script><script defer src="https://appassets.androidplatform.net/assets/render.js"></script></head><body>$body</body></html>"""
 }
 fun rawLatex(markdown:String):String = markdown.replace(Regex("(?m)^#{1,6} +"),"")
 fun tex(markdown:String):String = "\\documentclass{article}\n\\usepackage[utf8]{inputenc}\n\\usepackage{amsmath,amssymb}\n\\begin{document}\n"+escapeProse(rawLatex(markdown))+"\n\\end{document}\n"
 private fun escapeProse(text:String):String {
  val output=StringBuilder();var math=false;var i=0
  while(i<text.length) {
   val c=text[i]
   if(!math && c=='\\' && i+1<text.length && text[i+1]=='$') { output.append("\\\$");i+=2;continue }
   if(c=='$' && (i==0 || text[i-1]!='\\')) {
    val display=i+1<text.length && text[i+1]=='$';output.append(if(display)"$$" else "$");if(display)i++;math=!math
   } else if(math)output.append(c)
   else output.append(when(c){ '&'->"\\&";'%'->"\\%";'#'->"\\#";'_'->"\\_";'{'->"\\{";'}'->"\\}";'\\'->"\\textbackslash{}";'~'->"\\textasciitilde{}";'^'->"\\textasciicircum{}";else->c.toString() })
   i++
  }
  return output.toString()
 }
 fun share(context:Context,text:String,tex:Boolean=false) {
  val directory=File(context.filesDir,"exports").apply { mkdirs() }
  directory.listFiles()?.filter { System.currentTimeMillis()-it.lastModified()>86400000 }?.forEach { it.delete() }
  val file=File(directory,"Ink2TeX-${System.currentTimeMillis()}.${if(tex)"tex" else "md"}").apply { writeText(if(tex)DocumentExport.tex(text) else text) }
  val uri=FileProvider.getUriForFile(context,context.packageName+".files",file)
  val intent=Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,uri).putExtra(Intent.EXTRA_TEXT,text).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  context.startActivity(Intent.createChooser(intent,"Share proof"))
 }
 fun archive(context:Context,uri:Uri,documents:List<dev.ink2tex.storage.Document>) {
  val output=context.contentResolver.openOutputStream(uri,"wt") ?: error("Unable to open archive")
  java.util.zip.ZipOutputStream(output.buffered()).use { zip ->
   documents.filter { it.markdown.isNotBlank() }.forEach { doc ->
    val name=doc.title.replace(Regex("[^A-Za-z0-9 _-]"),"_").take(60)+"-"+doc.id
    for(extension in listOf("md","tex")) {
     zip.putNextEntry(java.util.zip.ZipEntry("$name.$extension"))
     zip.write((if(extension=="md")doc.markdown else tex(doc.markdown)).toByteArray(Charsets.UTF_8));zip.closeEntry()
    }
   }
  }
 }
 fun write(context:Context,uri:Uri,text:String) { context.contentResolver.openOutputStream(uri,"wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) } ?: error("Unable to save document") }
}
