@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.ink2tex.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.os.Debug
import android.print.PrintManager
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewAssetLoader
import androidx.work.*
import dev.ink2tex.capture.CaptureService
import dev.ink2tex.crop.CropView
import dev.ink2tex.export.DocumentExport
import dev.ink2tex.preprocessing.ImagePipeline
import dev.ink2tex.storage.Document
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.*

private val Teal=Color(0xFF147D74)
private val Slate=Color(0xFF20383D)
private val Mint=Color(0xFFE5F3ED)
private val Paper=Color(0xFFF6F8F6)
private val Gold=Color(0xFFB68235)
private fun bytes(value:Long):String = when { value>=1024L*1024*1024 -> "%.2f GiB".format(value/(1024.0*1024*1024));else->"%.0f MiB".format(value/(1024.0*1024)) }
private val navItems=listOf(Triple("home","Scanner",InkIcons.CropFree),Triple("history","History",InkIcons.History),Triple("models","Model Store",InkIcons.Memory),Triple("settings","Settings",InkIcons.Tune))

@Composable fun InkApp(vm:InkViewModel,start:()->Unit,stop:()->Unit) {
 val settings by vm.settings.collectAsStateWithLifecycle()
 val nav by vm.navigation.collectAsStateWithLifecycle()
 val message by vm.message.collectAsStateWithLifecycle()
 val importing by vm.importing.collectAsStateWithLifecycle()
 val context=LocalContext.current
 val snackbar=remember { SnackbarHostState() }
 val importLauncher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }
 val colors=if(settings.dark) darkColorScheme(primary=Color(0xFF78D9C4),secondary=Color(0xFFEBC482)) else lightColorScheme(primary=Teal,secondary=Gold,background=Paper,surface=Color.White,surfaceVariant=Color(0xFFEDF2EE),onSurface=Slate)
 LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it);vm.message.value=null } }
 LaunchedEffect(nav.page) { if(nav.page=="import") { vm.go("home");importLauncher.launch(arrayOf("image/png","image/jpeg","image/webp","application/pdf")) } }
 BackHandler(nav.page!="home") { vm.go("home") }
 MaterialTheme(colorScheme=colors,typography=Typography()) {
  BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
   val tablet=maxWidth>=850.dp
   Row(Modifier.fillMaxSize()) {
    if(tablet) Surface(Modifier.width(220.dp).fillMaxHeight(),color=MaterialTheme.colorScheme.surface) {
     Column(Modifier.padding(horizontal=20.dp,vertical=36.dp)) {
      Row(verticalAlignment=Alignment.CenterVertically) { BrandMark();Spacer(Modifier.width(12.dp));Column { Text("Ink2TeX",fontSize=24.sp,fontWeight=FontWeight.Bold);Text("YOUR NOTES, IN LATEX",fontSize=9.sp,letterSpacing=1.sp,color=Teal) } }
      Spacer(Modifier.height(46.dp))
      navItems.forEach { (page,label,icon) ->
       NavigationDrawerItem(label={Text(label)},selected=nav.page==page,icon={Icon(icon,null)},onClick={vm.go(page)},modifier=Modifier.padding(vertical=5.dp),colors=NavigationDrawerItemDefaults.colors(selectedContainerColor=Mint,selectedTextColor=Teal,selectedIconColor=Teal))
      }
      Spacer(Modifier.weight(1f));Icon(InkIcons.Lock,null,tint=Teal,modifier=Modifier.size(22.dp));Spacer(Modifier.height(12.dp));Text("Private by design",fontWeight=FontWeight.SemiBold);Text("Your handwriting stays\non this device.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,lineHeight=20.sp)
     }
    }
    Scaffold(modifier=Modifier.weight(1f),containerColor=MaterialTheme.colorScheme.background,snackbarHost={SnackbarHost(snackbar)},bottomBar={
     if(!tablet) NavigationBar { navItems.forEach { (page,label,icon) -> NavigationBarItem(selected=nav.page==page,onClick={vm.go(page)},icon={Icon(icon,null)},label={Text(label,fontSize=11.sp)}) } }
    }) { padding ->
     Box(Modifier.fillMaxSize().padding(padding).padding(if(tablet)28.dp else 16.dp)) {
      when(nav.page) {
       "home","import" -> HomeScreen(vm,start,stop) { importLauncher.launch(arrayOf("image/png","image/jpeg","image/webp","application/pdf")) }
       "history" -> HistoryScreen(vm)
       "models" -> ModelScreen(vm)
       "settings" -> SettingsScreen(vm)
       "diagnostics" -> DiagnosticsScreen(vm)
       "editor" -> EditorScreen(vm)
       "crop" -> CropScreen(vm)
       else -> HomeScreen(vm,start,stop) { vm.go("import") }
      }
      if(importing) Surface(Modifier.align(Alignment.Center),shape=RoundedCornerShape(24.dp),shadowElevation=12.dp) { Row(Modifier.padding(28.dp),verticalAlignment=Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(28.dp));Spacer(Modifier.width(20.dp));Text("Importing privately…") } }
     }
    }
   }
  }
 }
}
@Composable private fun BrandMark() { Box(Modifier.size(44.dp).background(Teal,RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center) { Text("Tₑₓ",color=Color.White,fontSize=21.sp,fontWeight=FontWeight.Bold) } }
@Composable private fun PageTitle(title:String,subtitle:String,action:@Composable ()->Unit={}) {
 Row(Modifier.fillMaxWidth().padding(bottom=24.dp),verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title,fontSize=28.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(5.dp));Text(subtitle,color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=13.sp) };action() }
}
@Composable private fun Hint(text:String,icon:ImageVector=InkIcons.Info) {
 Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(14.dp)) { Row(Modifier.padding(14.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) { Icon(icon,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.primary);Text(text,fontSize=12.sp,lineHeight=18.sp) } }
}
@Composable private fun HomeScreen(vm:InkViewModel,start:()->Unit,stop:()->Unit,onImport:()->Unit) {
 val active by CaptureService.active.collectAsStateWithLifecycle()
 val docs by vm.history.collectAsStateWithLifecycle()
 val settings by vm.settings.collectAsStateWithLifecycle()
 val model=vm.app.models.manifests.firstOrNull { it.id==settings.model }
 LazyColumn(verticalArrangement=Arrangement.spacedBy(20.dp)) {
  item { PageTitle("Your ideas, beautifully transcribed.","A local workspace for handwritten mathematics") { AssistChip(onClick={vm.go("diagnostics")},label={Text("On-device")},leadingIcon={Icon(InkIcons.Shield,null,Modifier.size(16.dp))}) } }
  item { Surface(color=if(MaterialTheme.colorScheme.background==Paper) Mint else MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(28.dp)) {
   Column(Modifier.padding(28.dp)) {
    Row(verticalAlignment=Alignment.CenterVertically) { Icon(InkIcons.Gesture,null,tint=Teal,modifier=Modifier.size(28.dp));Spacer(Modifier.width(10.dp));Text("CONVERSION MODE",fontSize=11.sp,letterSpacing=1.5.sp,fontWeight=FontWeight.Bold,color=Teal) }
    Spacer(Modifier.height(22.dp));Text("Keep writing.\nCapture the thought.",fontSize=34.sp,lineHeight=40.sp,fontWeight=FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp));Text("Open the floating scanner over Nebo. Snip a whole passage of words and equations, then review its Markdown + LaTeX.",Modifier.widthIn(max=580.dp),lineHeight=24.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(24.dp))
    Button(onClick=if(active)stop else start,shape=RoundedCornerShape(12.dp),contentPadding=PaddingValues(20.dp,14.dp)) { Icon(if(active)InkIcons.StopCircle else InkIcons.PlayArrow,null);Spacer(Modifier.width(10.dp));Text(if(active)"Stop Conversion Mode" else "Start Conversion Mode") }
    Spacer(Modifier.height(12.dp));Text(if(active)"● Screen capture is active · return to Nebo to scan" else "Android will ask for overlay and screen-capture access.",fontSize=12.sp,color=Teal)
   }
  } }
  item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
   ActionCard("Import a note","PNG, JPEG, WebP or PDF",InkIcons.AddPhotoAlternate,Modifier.weight(1f),onImport)
   ActionCard("Last conversion",docs.firstOrNull { it.markdown.isNotBlank() }?.title ?: "Your next proof starts here",InkIcons.Description,Modifier.weight(1f)) { docs.firstOrNull { it.markdown.isNotBlank() }?.let { vm.go("editor",it.id) } ?: run { vm.message.value="Capture or import a note to begin" } }
  } }
  item { Surface(shape=RoundedCornerShape(18.dp)) { Row(Modifier.fillMaxWidth().padding(20.dp),verticalAlignment=Alignment.CenterVertically) {
   Icon(InkIcons.Memory,null,tint=Teal);Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)) { Text(model?.name ?: "Automatic model selection",fontWeight=FontWeight.SemiBold);Text(if(model!=null && vm.app.models.ready(model.id)) "Ready · runs locally" else "Download a model to enable conversion",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) };TextButton(onClick={vm.go("models")}) { Text("Manage") }
  } } }
  item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("Recent notes",fontSize=18.sp,fontWeight=FontWeight.SemiBold);TextButton(onClick={vm.go("history")}) { Text("View all") } } }
  if(docs.isEmpty())item { Hint("Capture your first passage or import an image. The original and editable transcription will appear here.",InkIcons.EditNote) }
  items(docs.take(4),key={it.id}) { doc -> DocumentRow(doc,{vm.go("editor",doc.id)}) }
  item { Hint("Ink2TeX transcribes the author's work. It does not check or correct mathematical reasoning. Always compare the result with the handwriting.",InkIcons.FactCheck) }
 }
}
@Composable private fun ActionCard(title:String,subtitle:String,icon:ImageVector,modifier:Modifier,onClick:()->Unit) {
 Surface(onClick=onClick,modifier=modifier,shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface) { Column(Modifier.padding(20.dp)) { Icon(icon,null,tint=Teal);Spacer(Modifier.height(16.dp));Text(title,fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(5.dp));Text(subtitle,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
}
@Composable private fun DocumentRow(doc:Document,onClick:()->Unit,onDelete:(()->Unit)?=null) {
 Surface(onClick=onClick,shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface) { Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
  Icon(InkIcons.Description,null,tint=Teal);Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)) { Text(doc.title,fontWeight=FontWeight.SemiBold);Text(SimpleDateFormat("d MMM · HH:mm",Locale.getDefault()).format(Date(doc.created))+" · "+doc.status,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);doc.error?.let { Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.error,maxLines=2) } }
  if(doc.status=="Converting" || doc.status=="Queued") CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)
  if(onDelete!=null)IconButton(onClick=onDelete) { Icon(InkIcons.DeleteOutline,"Delete note") }
 } }
}
@Composable private fun HistoryScreen(vm:InkViewModel) {
 val context=LocalContext.current;val scope=rememberCoroutineScope()
 val docs by vm.history.collectAsStateWithLifecycle();var search by rememberSaveable { mutableStateOf("") };var deleting by remember { mutableStateOf<Document?>(null) }
 val archive=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { scope.launch { try { withContext(Dispatchers.IO) { DocumentExport.archive(context,it,docs) };vm.message.value="Exported Markdown and TeX archive" } catch(e:Exception) { vm.message.value=e.message } } } }
 Column {
  PageTitle("Your notebook","Original captures and editable transcriptions") { IconButton(onClick={archive.launch("Ink2TeX-notebook.zip")},enabled=docs.any { it.markdown.isNotBlank() }){Icon(InkIcons.Download,"Export all transcriptions")} }
  OutlinedTextField(search,{search=it},Modifier.fillMaxWidth(),placeholder={Text("Search notes or LaTeX")},leadingIcon={Icon(InkIcons.Search,null)},singleLine=true,shape=RoundedCornerShape(14.dp))
  Spacer(Modifier.height(16.dp))
  LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) {
   if(docs.isEmpty())item { Hint("Your notebook is empty. Start Conversion Mode or import a note.") }
   items(docs.filter { search.isBlank() || (it.title+it.markdown).contains(search,true) },key={it.id}) { doc -> DocumentRow(doc,{vm.go("editor",doc.id)},{deleting=doc}) }
  }
 }
 deleting?.let { doc -> AlertDialog(onDismissRequest={deleting=null},title={Text("Delete this note?")},text={Text("Its transcription and any original images used only by this note will be deleted from this device.")},confirmButton={TextButton(onClick={vm.delete(doc);deleting=null}){Text("Delete")}},dismissButton={TextButton(onClick={deleting=null}){Text("Keep")}}) }
}
@Composable private fun ModelScreen(vm:InkViewModel) {
 val works by vm.works.collectAsStateWithLifecycle();val settings by vm.settings.collectAsStateWithLifecycle();var refresh by remember { mutableIntStateOf(0) }
 val modelChanges by vm.app.models.changes.collectAsStateWithLifecycle()
 val ready=remember(works,refresh,modelChanges) { vm.app.models.available() }
 LazyColumn(verticalArrangement=Arrangement.spacedBy(16.dp)) {
  item { PageTitle("Model Store","Verified artifacts. Private inference. Honest compatibility.") }
  item { Hint("Start with OvisOCR2 Quality when you have at least 3 GiB of free RAM. That is a conservative admission budget, not measured usage. Moto Pad speed and handwriting accuracy are unmeasured.",InkIcons.AutoAwesome) }
  item { Row(verticalAlignment=Alignment.CenterVertically) { Text("Auto-select a ready model",Modifier.weight(1f));Switch(settings.model=="auto",{ if(it)vm.selectModel("auto") else ready.firstOrNull()?.let { m -> vm.selectModel(m.id) } }) } }
  items(vm.app.models.manifests,key={it.id}) { model ->
   val work=works.filter { model.id in it.tags }.maxByOrNull { it.runAttemptCount.toLong()+if(!it.state.isFinished)100000L else 0L }
   val running=work!=null && !work.state.isFinished
   val installed=ready.any { it.id==model.id }
   val partial=model.components.any { File(vm.app.models.directory(model.id),it.name+".part").exists() }
   val error=File(vm.app.models.directory(model.id),"error.txt").takeIf { it.exists() }?.readText()
   Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface) { Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
    Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(model.name,fontSize=19.sp,fontWeight=FontWeight.SemiBold);Text(model.developer,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) };Icon(if(installed)InkIcons.CheckCircle else InkIcons.Memory,null,tint=if(model.compatible)Teal else MaterialTheme.colorScheme.outline) }
    Text(model.description,fontSize=13.sp,lineHeight=20.sp)
    Text(if(model.compatible) "Download ${bytes(model.size)} · installed ${bytes(model.size)}\n${model.quantization} · RAM measured on device: not yet" else "Download / installed size: not offered · RAM: unmeasured",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Text(if(installed)"Ready · validated on this device" else model.compatibility,color=if(model.compatible)Teal else Gold,fontSize=12.sp,fontWeight=FontWeight.SemiBold)
    if(model.compatible)Text(model.reason,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(running) {
     val total=work!!.progress.getLong("total",model.size);val count=work.progress.getLong("bytes",0)
     if(total>0 && count>0)LinearProgressIndicator(progress={ (count.toFloat()/total).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
     Text(work.progress.getString("stage") ?: "Queued for download",fontSize=12.sp)
     if(count>0)Text("${bytes(count)} / ${bytes(total)}",fontSize=12.sp)
    }
    if(error!=null && !installed && !running)Text(error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
     if(model.compatible) {
      if(running)OutlinedButton(onClick={vm.app.models.pause(model.id)}){Text("Pause")}
      else if(!installed)Button(onClick={vm.install(model.id)}){Icon(InkIcons.Download,null,Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text(if(partial)"Resume" else "Download")}
      if(installed) {
       Button(onClick={vm.selectModel(model.id)},enabled=settings.model!=model.id){Text(if(settings.model==model.id)"Selected" else "Select")}
       OutlinedButton(onClick={vm.benchmark(model.id)}){Text("Benchmark")}
      }
      if(installed || partial) TextButton(onClick={vm.removeModel(model.id);refresh++}){Text("Delete")}
     }
     val context=LocalContext.current
     TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(model.licenseUrl)))}) { Text(model.license+" ↗") }
    }
   } }
  }
  item { Hint("Unavailable candidates have no install button. The app never downloads executable model code, starts a server, or sends note images to the internet.",InkIcons.Lock) }
 }
}

@Composable private fun SettingsScreen(vm:InkViewModel) {
 val settings by vm.settings.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
 LazyColumn(verticalArrangement=Arrangement.spacedBy(18.dp)) {
  item { PageTitle("Scanner settings","Make room for your handwriting") }
  item { Surface(shape=RoundedCornerShape(20.dp)) { Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
   SettingSwitch("Dark appearance","A quieter workspace for evening proofs",settings.dark) { scope.launch { vm.repository.settings.dark(it) } }
   SettingSwitch("Compact floating panel","A narrower panel with a scrollable action list",settings.compact) { scope.launch { vm.repository.settings.compact(it) } }
   SettingSwitch("Copy when a result opens","Copy newly finished OCR when its editor is visible",settings.autoCopy) { scope.launch { vm.repository.settings.copy(it) } }
   Text("Floating control opacity · ${(settings.opacity*100).toInt()}%",fontWeight=FontWeight.SemiBold)
   Slider(settings.opacity,{ value -> scope.launch { vm.repository.settings.alpha(value) } },valueRange=0.4f..1f)
   Text("Panel changes apply when Conversion Mode starts again. Drag the bubble or panel header to move it; release to snap it to an edge.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
  } } }
  item { Hint("Screen-capture permission ends when you stop Conversion Mode or Android ends the session. Restarting always uses Android's consent dialog. Secure app content may appear blank.",InkIcons.ScreenLockPortrait) }
  item { Hint("Imports, captures, models and history are stored in private app storage. Backups and analytics are disabled. Only model files are downloaded; handwriting is never uploaded.",InkIcons.PrivacyTip) }
  item { OutlinedButton(onClick={vm.go("diagnostics")}) { Icon(InkIcons.MonitorHeart,null);Spacer(Modifier.width(8.dp));Text("Open diagnostics") } }
 }
}
@Composable private fun SettingSwitch(title:String,description:String,checked:Boolean,onChange:(Boolean)->Unit) { Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title,fontWeight=FontWeight.SemiBold);Text(description,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) };Switch(checked,onChange) } }
@Composable private fun DiagnosticsScreen(vm:InkViewModel) {
 val diagnostic by vm.app.engine.diagnostics.collectAsStateWithLifecycle();val queue by vm.queue.collectAsStateWithLifecycle();val context=LocalContext.current
 var memory by remember { mutableStateOf(ActivityManager.MemoryInfo()) };var javaMemory by remember { mutableLongStateOf(0) };var nativeMemory by remember { mutableLongStateOf(0) }
 LaunchedEffect(Unit) { while(true) { memory=ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) };javaMemory=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();nativeMemory=Debug.getNativeHeapAllocatedSize();delay(2000) } }
 LazyColumn(verticalArrangement=Arrangement.spacedBy(14.dp)) {
  item { PageTitle("Diagnostics","Measured values from this running process") }
  val entries=listOf("Device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}","Android / ABI" to "${android.os.Build.VERSION.RELEASE} / ${android.os.Build.SUPPORTED_ABIS.joinToString()}","Engine" to diagnostic.stage,"Loaded model" to (diagnostic.loadedModel ?: "None"),"Available RAM" to bytes(memory.availMem),"Java heap allocated" to bytes(javaMemory),"Native heap allocated" to bytes(nativeMemory),"Free app storage" to bytes(context.filesDir.usableSpace),"Model initialization" to (diagnostic.initializationMs?.let { "$it ms" } ?: "Not measured"),"Last inference" to (diagnostic.inferenceMs?.let { "$it ms" } ?: "Not measured"),"Last output tokens" to (diagnostic.outputTokens?.toString() ?: "Not measured"),"Queued / running conversions" to queue.count { !it.state.isFinished }.toString(),"Last engine error" to (diagnostic.lastError ?: "None"))
  items(entries) { (key,value) -> Surface(shape=RoundedCornerShape(12.dp)) { Row(Modifier.fillMaxWidth().padding(16.dp)) { Text(key,Modifier.weight(1f),fontSize=13.sp);Text(value,Modifier.weight(1f),fontSize=13.sp,fontWeight=FontWeight.Medium) } } }
  item { Hint("Heap allocation is not total process RSS or peak model memory. Benchmark timings include real local execution. The bundled printed smoke page tests the runtime, not handwritten-math accuracy.") }
 }
}
@Composable private fun CropScreen(vm:InkViewModel) {
 val nav by vm.navigation.collectAsStateWithLifecycle();val docs by vm.history.collectAsStateWithLifecycle();val parts by vm.cropParts.collectAsStateWithLifecycle()
 val doc=docs.firstOrNull { it.id==nav.documentId }
 val context=LocalContext.current
 val cropView=remember(nav.documentId) { CropView(context) }
 var bitmap by remember(nav.documentId) { mutableStateOf<Bitmap?>(null) };var failure by remember { mutableStateOf<String?>(null) }
 var freeform by rememberSaveable(nav.documentId) { mutableStateOf(nav.cropMode=="free") }
 LaunchedEffect(doc?.source) { doc?.source?.lines()?.firstOrNull()?.let { path -> try { bitmap=withContext(Dispatchers.IO) { ImagePipeline.preview(File(path)) };cropView.bitmap=bitmap;cropView.restore(vm.cropSelection.value);cropView.onSelectionChanged={vm.cropSelection.value=it} } catch(e:Exception) { failure=e.message } } }
 DisposableEffect(nav.documentId) { onDispose { cropView.bitmap=null;bitmap?.recycle() } }
 Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
  PageTitle(if(nav.cropMode=="scroll")"Scrolling proof · capture a section" else if(nav.cropMode=="multi")"Build a multi-snip proof" else "Snip your handwriting","Frozen capture · draw to select · pinch to zoom · two fingers to pan")
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { FilterChip(selected=!freeform,onClick={freeform=false;cropView.freeform=false;cropView.resetSelection()},label={Text("Rectangle")});FilterChip(selected=freeform,onClick={freeform=true;cropView.freeform=true;cropView.resetSelection()},label={Text("Freeform")});TextButton(onClick={cropView.resetSelection()}){Text("Reset")}}
  if(failure!=null)Hint(failure!!)
  AndroidView(factory={cropView.apply { this.freeform=freeform }},modifier=Modifier.fillMaxWidth().weight(1f).background(Color(0xFF0D171D),RoundedCornerShape(18.dp)))
  if(parts.isNotEmpty())LazyColumn(Modifier.heightIn(max=160.dp)) { items(parts.size) { index -> Row(verticalAlignment=Alignment.CenterVertically) { Text("Region ${index+1}",Modifier.weight(1f));IconButton(onClick={vm.movePart(index,-1)},enabled=index>0){Icon(InkIcons.ArrowUpward,"Move up")};IconButton(onClick={vm.movePart(index,1)},enabled=index<parts.lastIndex){Icon(InkIcons.ArrowDownward,"Move down")};IconButton(onClick={vm.removePart(index)}){Icon(InkIcons.Close,"Remove region")} } } }
  FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
   OutlinedButton(onClick={vm.useWholeCapture()}){Text("Use whole image")}
   if(nav.cropMode=="multi") {
    OutlinedButton(onClick={val s=cropView.selected();if(s==null)vm.message.value="Draw a region first" else { vm.addCrop(s,false);cropView.resetSelection() }}){Text("Add region")}
    Button(onClick={vm.finishMulti()},enabled=parts.isNotEmpty()){Text("Finish ${parts.size} regions")}
   } else Button(onClick={val s=cropView.selected();if(s==null)vm.message.value="Draw a region first" else vm.addCrop(s,true)}){Text(if(nav.cropMode=="scroll")"Add proof section" else "Convert selection")}
  }
  Text("Selections above 4 megapixels are sampled to keep cropping within the memory budget. For fine handwriting, use smaller regions.",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
 }
}
@Composable private fun OriginalPanel(doc:Document) {
 var index by remember(doc.id) { mutableIntStateOf(0) };val paths=doc.source.lines().filter { it.isNotBlank() };var bitmap by remember { mutableStateOf<Bitmap?>(null) }
 LaunchedEffect(paths,index) { bitmap=withContext(Dispatchers.IO) { paths.getOrNull(index)?.let { runCatching { ImagePipeline.preview(File(it),1400) }.getOrNull() } } }
 DisposableEffect(doc.id) { onDispose { bitmap?.recycle() } }
 Column(Modifier.fillMaxSize().padding(12.dp)) {
  Text("ORIGINAL HANDWRITING",fontSize=10.sp,letterSpacing=1.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
  if(paths.size>1)Row(verticalAlignment=Alignment.CenterVertically) { IconButton(onClick={index--},enabled=index>0){Icon(InkIcons.ChevronLeft,"Previous section")};Text("Section ${index+1} / ${paths.size}",fontSize=12.sp);IconButton(onClick={index++},enabled=index<paths.lastIndex){Icon(InkIcons.ChevronRight,"Next section")} }
  bitmap?.let { Image(it.asImageBitmap(),"Original captured handwriting",Modifier.fillMaxWidth().weight(1f)) } ?: Text("Original image unavailable",Modifier.padding(20.dp))
 }
}
@Composable private fun RenderPanel(text:String,dark:Boolean,onWebView:(WebView?)->Unit) {
 val context=LocalContext.current
 val loader=remember { WebViewAssetLoader.Builder().addPathHandler("/assets/",WebViewAssetLoader.AssetsPathHandler(context)).build() }
 val html=remember(text,dark) { DocumentExport.html(text,dark) }
 AndroidView(factory={ WebView(it).apply {
  settings.javaScriptEnabled=true;settings.allowFileAccess=false;settings.allowContentAccess=false;settings.domStorageEnabled=false;settings.blockNetworkLoads=true
  webViewClient=object:WebViewClient() {
   override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? = loader.shouldInterceptRequest(request.url) ?: WebResourceResponse("text/plain","utf-8",java.io.ByteArrayInputStream(ByteArray(0)))
   override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=true
  }
  setBackgroundColor(android.graphics.Color.TRANSPARENT)
  onWebView(this)
 } },update={ if(it.tag!=html) { it.tag=html;it.loadDataWithBaseURL("https://appassets.androidplatform.net/",html,"text/html","utf-8",null) } },modifier=Modifier.fillMaxSize(),onRelease={onWebView(null);it.stopLoading();it.destroy()})
}
@Composable private fun EditorScreen(vm:InkViewModel) {
 val nav by vm.navigation.collectAsStateWithLifecycle();val docs by vm.history.collectAsStateWithLifecycle();val settings by vm.settings.collectAsStateWithLifecycle();val queue by vm.queue.collectAsStateWithLifecycle()
 val doc=docs.firstOrNull { it.id==nav.documentId }
 if(doc==null) { Hint("Opening note…");return }
 val context=LocalContext.current
 var text by rememberSaveable(doc.id) { mutableStateOf(doc.markdown) }
 var copied by rememberSaveable(doc.id) { mutableStateOf("") }
 var databaseText by remember(doc.id) { mutableStateOf(doc.markdown) }
 val undo=remember(doc.id) { mutableStateListOf<String>() };val redo=remember(doc.id) { mutableStateListOf<String>() }
 var tab by rememberSaveable { mutableIntStateOf(1) };var menu by remember { mutableStateOf(false) }
 var rerun by remember { mutableStateOf(false) };var appending by remember { mutableStateOf(false) };var comparing by remember { mutableStateOf(false) };var comparison by remember { mutableStateOf<Document?>(null) }
 var output by remember { mutableStateOf("") };var outputType by remember { mutableStateOf("md") };var web by remember { mutableStateOf<WebView?>(null) };var allowFallback by rememberSaveable { mutableStateOf(false) };var ordering by remember { mutableStateOf(false) };var deleteOriginals by remember { mutableStateOf(false) };val editorScope=rememberCoroutineScope()
 val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let { runCatching { DocumentExport.write(context,it,output) }.onSuccess {vm.message.value="Saved ${outputType.uppercase()} document"}.onFailure {e->vm.message.value=e.message} } }
 val work=queue.firstOrNull { doc.id in it.tags && !it.state.isFinished }
 LaunchedEffect(doc.markdown) { if(text==databaseText)text=doc.markdown;databaseText=doc.markdown }
 LaunchedEffect(doc.status,doc.markdown,settings.autoCopy) { if(settings.autoCopy && doc.status=="Review" && doc.markdown.isNotBlank() && copied!=doc.markdown) { vm.copy(doc.markdown);copied=doc.markdown } }
 DisposableEffect(doc.id) { onDispose { vm.flush(doc.id,text) } }
 fun edit(value:String) { if(value!=text) { undo+=text;if(undo.size>100)undo.removeAt(0);redo.clear();text=value;vm.save(doc.id,value) } }
 Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
  Row(verticalAlignment=Alignment.CenterVertically) {
   IconButton(onClick={vm.flush(doc.id,text);vm.go("history")}) { Icon(InkIcons.ArrowBack,"Back to notebook") }
   Column(Modifier.weight(1f)) { Text(doc.title,fontWeight=FontWeight.SemiBold,fontSize=21.sp);Text(if(doc.durationMs!=null)"${doc.status} · ${doc.durationMs} ms · ${doc.tokens} output tokens" else doc.status,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }
   IconButton(onClick={menu=true}){Icon(InkIcons.MoreVert,"Export and proof actions")}
   DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
    DropdownMenuItem(text={Text("Copy TeX document source")},onClick={vm.copy(DocumentExport.tex(text));menu=false})
    DropdownMenuItem(text={Text("Save .md")},onClick={output=text;outputType="md";export.launch("Ink2TeX.md");menu=false})
    DropdownMenuItem(text={Text("Save .tex")},onClick={output=DocumentExport.tex(text);outputType="tex";export.launch("Ink2TeX.tex");menu=false})
    DropdownMenuItem(text={Text("Export PDF / Print")},onClick={tab=2;web?.let { (context.getSystemService(Context.PRINT_SERVICE) as PrintManager).print("Ink2TeX proof",it.createPrintDocumentAdapter("Ink2TeX"),null) } ?: run {vm.message.value="Open Rendered preview, then choose Export PDF again"};menu=false})
    DropdownMenuItem(text={Text("Share Markdown + LaTeX")},onClick={DocumentExport.share(context,text);menu=false})
    DropdownMenuItem(text={Text("Append to existing proof")},onClick={appending=true;menu=false})
    DropdownMenuItem(text={Text("Rerun / compare models")},onClick={rerun=true;menu=false})
    DropdownMenuItem(text={Text("Reorder / remove captured sections")},onClick={ordering=true;menu=false})
    DropdownMenuItem(text={Text("Compare saved results")},onClick={comparing=true;menu=false})
    DropdownMenuItem(text={Text("Delete original images")},onClick={deleteOriginals=true;menu=false})
    DropdownMenuItem(text={Text("Snip this original again")},onClick={vm.go("crop",doc.id);menu=false})
   }
  }
  FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
   Button(onClick={vm.flush(doc.id,text);vm.convert(doc,fallback=allowFallback)},enabled=work==null) { Icon(InkIcons.AutoAwesome,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(if(text.isBlank())"Convert" else "Rerun OCR") }
   OutlinedButton(onClick={vm.copy(text)},enabled=text.isNotBlank()) { Icon(InkIcons.ContentCopy,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Copy Markdown + LaTeX") }
   if(work!=null)OutlinedButton(onClick={WorkManager.getInstance(context).cancelWorkById(work.id)}){Text("Cancel")}
  }
  Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(allowFallback,{allowFallback=it});Text("Try another ready model if inference fails",fontSize=11.sp) }
  if(work!=null) { LinearProgressIndicator(Modifier.fillMaxWidth());Text(work.progress.getString("stage") ?: "Queued · waiting for local engine",fontSize=12.sp) }
  doc.error?.let { Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.error) }
  if(doc.warnings.isNotBlank())Text(doc.warnings,fontSize=11.sp,lineHeight=16.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=3,overflow=TextOverflow.Ellipsis)
  if(doc.title.contains("Scrolling") || context.getSharedPreferences("guided-proof",0).getString("target",null)==doc.id) {
   Hint("Scroll Nebo manually and tap Scrolling Proof Scan for the next section. Images remain in capture order. Check overlaps before finishing.")
   Row { TextButton(onClick={(context as? Activity)?.moveTaskToBack(true)}){Text("Return to notes")};TextButton(onClick={vm.finishScroll()}){Text("Finish guided capture")};TextButton(onClick={vm.convert(doc,deduplicate=true)}){Text("Convert · exact overlap removal")} }
  }
  BoxWithConstraints(Modifier.weight(1f)) {
   val wide=maxWidth>=950.dp
   @Composable fun Source() {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
     Row(verticalAlignment=Alignment.CenterVertically) { Text("MARKDOWN + LATEX",Modifier.weight(1f),fontSize=10.sp,letterSpacing=1.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);IconButton(onClick={if(undo.isNotEmpty()) { redo+=text;text=undo.removeAt(undo.lastIndex);vm.save(doc.id,text) }},enabled=undo.isNotEmpty()){Icon(InkIcons.Undo,"Undo")};IconButton(onClick={if(redo.isNotEmpty()) {undo+=text;text=redo.removeAt(redo.lastIndex);vm.save(doc.id,text)}},enabled=redo.isNotEmpty()){Icon(InkIcons.Redo,"Redo")} }
     OutlinedTextField(text,{edit(it)},Modifier.fillMaxSize(),placeholder={Text("Your editable transcription appears here. Download a compatible model, then tap Convert. You can also enter source manually.")},enabled=work==null,textStyle=LocalTextStyle.current.copy(fontFamily=FontFamily.Monospace,fontSize=14.sp,lineHeight=23.sp),shape=RoundedCornerShape(12.dp))
    }
   }
   if(wide) Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
    Surface(Modifier.weight(0.85f).fillMaxHeight(),shape=RoundedCornerShape(18.dp)) { OriginalPanel(doc) }
    Surface(Modifier.weight(1f).fillMaxHeight(),shape=RoundedCornerShape(18.dp)) { Source() }
    Surface(Modifier.weight(1f).fillMaxHeight(),shape=RoundedCornerShape(18.dp)) { RenderPanel(text,settings.dark){web=it} }
   } else Column {
    PrimaryTabRow(selectedTabIndex=tab) { listOf("Original","Source","Rendered").forEachIndexed { index,title -> Tab(selected=tab==index,onClick={tab=index},text={Text(title)}) } }
    Surface(Modifier.fillMaxSize(),shape=RoundedCornerShape(16.dp)) { when(tab) { 0->OriginalPanel(doc);1->Source();else->RenderPanel(text,settings.dark){web=it} } }
   }
  }
  Text("Rendering checks syntax, not transcription accuracy or mathematical correctness.",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
 }
 if(rerun)AlertDialog(onDismissRequest={rerun=false},title={Text("Choose a ready model")},text={Column { vm.app.models.available().forEach { model -> Row(verticalAlignment=Alignment.CenterVertically) { Text(model.name,Modifier.weight(1f));TextButton(onClick={vm.convert(doc,model.id);rerun=false}){Text("Rerun")};TextButton(onClick={vm.compare(doc,model.id);rerun=false}){Text("Compare")} } };if(vm.app.models.available().isEmpty())Text("Download and validate a model in Model Store.") }},confirmButton={TextButton(onClick={rerun=false}){Text("Close")}})
 if(appending || comparing)AlertDialog(onDismissRequest={appending=false;comparing=false},title={Text(if(appending)"Append or add to a proof" else "Compare with a saved result")},text={LazyColumn(Modifier.heightIn(max=360.dp)) { items(docs.filter { it.id!=doc.id }) { other -> Column { Text(other.title,fontWeight=FontWeight.Medium);Row { if(appending) {TextButton(onClick={vm.append(doc.copy(markdown=text),other);appending=false}){Text("Append text")};TextButton(onClick={vm.mergeWith(doc,other);appending=false}){Text("Merge captures")}} else TextButton(onClick={comparison=other;comparing=false}){Text("Compare sources")} } } } }},confirmButton={TextButton(onClick={appending=false;comparing=false}){Text("Close")}})
 if(deleteOriginals)AlertDialog(onDismissRequest={deleteOriginals=false},title={Text("Delete this note's original images?")},text={Text("The editable transcription stays. Images still used by another saved note are retained for that note.")},confirmButton={TextButton(onClick={editorScope.launch { vm.repository.deleteOriginals(doc);deleteOriginals=false }}){Text("Delete originals")}},dismissButton={TextButton(onClick={deleteOriginals=false}){Text("Keep")}})
 if(ordering) SectionOrderDialog(doc,vm) { ordering=false }
 comparison?.let { other -> androidx.compose.ui.window.Dialog(onDismissRequest={comparison=null},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) { Surface(Modifier.fillMaxSize().padding(24.dp),shape=RoundedCornerShape(24.dp)) { Column(Modifier.padding(20.dp)) { Row(verticalAlignment=Alignment.CenterVertically) {Text("Compare transcriptions",Modifier.weight(1f),fontWeight=FontWeight.Bold);TextButton(onClick={comparison=null}){Text("Close")}};Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(16.dp)) { Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { Text(doc.modelId,fontWeight=FontWeight.Bold);Text(text,fontFamily=FontFamily.Monospace,fontSize=13.sp) };Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { Text(other.modelId,fontWeight=FontWeight.Bold);Text(other.markdown,fontFamily=FontFamily.Monospace,fontSize=13.sp) } } } } } }
}

@Composable private fun SectionOrderDialog(doc:Document,vm:InkViewModel,dismiss:()->Unit) {
 val sources=remember(doc.id) { mutableStateListOf<String>().apply { addAll(doc.source.lines().filter { it.isNotBlank() }) } }
 val scope=rememberCoroutineScope()
 AlertDialog(onDismissRequest=dismiss,title={Text("Captured sections")},text={LazyColumn(Modifier.heightIn(max=360.dp)) { items(sources.size) { i -> Row(verticalAlignment=Alignment.CenterVertically) { Text("Section ${i+1}",Modifier.weight(1f));IconButton(enabled=i>0,onClick={val item=sources.removeAt(i);sources.add(i-1,item)}){Icon(InkIcons.ArrowUpward,"Move earlier")};IconButton(enabled=i<sources.lastIndex,onClick={val item=sources.removeAt(i);sources.add(i+1,item)}){Icon(InkIcons.ArrowDownward,"Move later")};IconButton(enabled=sources.size>1,onClick={sources.removeAt(i)}){Icon(InkIcons.Close,"Remove section")} } } }},confirmButton={TextButton(onClick={scope.launch { vm.repository.documents.put(doc.copy(source=sources.joinToString("\n")));dismiss() }}){Text("Save order")}},dismissButton={TextButton(onClick=dismiss){Text("Cancel")}})
}
