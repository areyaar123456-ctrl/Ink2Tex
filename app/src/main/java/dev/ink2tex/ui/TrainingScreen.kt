package dev.ink2tex.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ink2tex.preprocessing.ImagePipeline
import dev.ink2tex.storage.Document
import dev.ink2tex.storage.TrainingLabel
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TrainingScreen(vm: InkViewModel) {
    val labels by vm.trainingLabels.collectAsStateWithLifecycle()
    val jobs by vm.trainingJobs.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val changes by vm.app.models.changes.collectAsStateWithLifecycle()
    val available = remember(changes, settings.model) { vm.app.models.available() }
    val model = available.firstOrNull { it.id == settings.model }
        ?: available.firstOrNull().takeIf { settings.model == "auto" }
    val job = jobs.firstOrNull { !it.state.isFinished } ?: jobs.lastOrNull()
    val running = job != null && !job.state.isFinished
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            exporting = true
            try {
                val count = vm.repository.training.export(uri)
                vm.message.value = "Exported $count reviewed labels."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                vm.message.value = failure.message ?: "Unable to export training labels."
            } finally { exporting = false }
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Handwriting training", style = MaterialTheme.typography.headlineMedium)
            Text("Review images in the editor, then fit the model’s output weights on this tablet.")
        }
        item {
            val heldOutDocs = labels.filter { it.evaluation }.map { it.documentId }.toSet()
            val trainingCount = labels.count { it.documentId !in heldOutDocs }
            val testingCount = labels.count { it.evaluation }
            Surface(shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("$trainingCount training · $testingCount evaluation", fontWeight = FontWeight.SemiBold)
                    Text("Mark a separate note for evaluation. Each run uses up to 12 recent training images and three checked evaluation images.")
                    Text(model?.name ?: "Select a ready OvisOCR2 model in Model Store.", fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Use trained weights in scans", Modifier.weight(1f))
                        Switch(settings.trainedWeights, { value ->
                            scope.launch { vm.repository.settings.trainedWeights(value) }
                        })
                    }
                    if (running) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(job?.progress?.getString("stage") ?: "Queued")
                        TextButton(onClick = { vm.cancelTraining() }) { Text("Cancel training") }
                    } else {
                        job?.outputData?.getString("summary")?.let { Text(it, fontSize = 13.sp) }
                        if (job?.state == androidx.work.WorkInfo.State.CANCELLED) Text("Training cancelled.")
                        Button(
                            onClick = { model?.let { vm.trainHandwriting(it.id) } },
                            enabled = model != null && trainingCount > 0 && testingCount > 0
                        ) { Text("Train on tablet") }
                    }
                    OutlinedButton(onClick = { export.launch("Ink2TeX-training.zip") },
                        enabled = labels.isNotEmpty() && !exporting) {
                        Text(if (exporting) "Exporting…" else "Export labeled images")
                    }
                }
            }
        }
        items(labels, key = { "${it.documentId}|${it.source}" }) { label ->
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (label.evaluation) "Evaluation image" else if (label.review == "accepted") "Checked image" else "Corrected image",
                        fontWeight = FontWeight.SemiBold)
                    Text(label.transcription, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                }
                TextButton(onClick = { vm.go("editor", label.documentId) }) { Text("Open note") }
                TextButton(onClick = { scope.launch { vm.repository.training.remove(label) } }, enabled = !running) { Text("Remove label") }
            }
        }
    }
}

@Composable
internal fun TrainingReviewDialog(vm: InkViewModel, doc: Document, editorSource: String, dismiss: () -> Unit) {
    val labels by vm.trainingLabels.collectAsStateWithLifecycle()
    val paths = remember(doc.source) { doc.source.lines().filter { it.isNotBlank() } }
    if (paths.isEmpty()) return
    var index by rememberSaveable(doc.id) { mutableIntStateOf(0) }
    val visibleIndex = index.coerceIn(paths.indices)
    val path = paths[visibleIndex]
    val prior = labels.firstOrNull { it.documentId == doc.id && it.source == path }
    var text by rememberSaveable(path) { mutableStateOf(prior?.transcription ?: editorSource.takeIf { paths.size == 1 }.orEmpty()) }
    var evaluation by rememberSaveable(path) { mutableStateOf(prior?.evaluation ?: false) }
    var saving by remember { mutableStateOf(false) }
    val image by produceState<Bitmap?>(null, path) {
        val bitmap = withContext(Dispatchers.IO) {
            try { ImagePipeline.preview(File(path), 850) }
            catch (_: IllegalArgumentException) { null }
            catch (_: IllegalStateException) { null }
        }
        value = bitmap
        awaitDispose { bitmap?.recycle() }
    }
    AlertDialog(
        onDismissRequest = { if (!saving) dismiss() },
        title = { Text("Corrected source") },
        text = {
            Column(Modifier.heightIn(max = 570.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (paths.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { index = visibleIndex - 1 }, enabled = !saving && visibleIndex > 0) { Text("Previous") }
                        Text("Image ${visibleIndex + 1} of ${paths.size}", Modifier.weight(1f))
                        TextButton(onClick = { index = visibleIndex + 1 }, enabled = !saving && visibleIndex < paths.lastIndex) { Text("Next") }
                    }
                }
                image?.let {
                    Image(it.asImageBitmap(), "Original handwriting", Modifier.fillMaxWidth().heightIn(max = 190.dp))
                }
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), enabled = !saving,
                    label = { Text("Exact Markdown and LaTeX for this image") }, minLines = 3, maxLines = 8)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(evaluation, { evaluation = it }, enabled = !saving)
                    Text("Reserve this note for evaluation")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    saving = true
                    vm.recordTraining(TrainingLabel(doc.id, path, text, "corrected", evaluation)) { ok ->
                        saving = false
                        if (ok && visibleIndex < paths.lastIndex) index = visibleIndex + 1
                        else if (ok) dismiss()
                    }
                },
                enabled = !saving && text.isNotBlank()
            ) { Text(if (saving) "Saving…" else "Save checked source") }
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !saving) { Text("Close") } }
    )
}
