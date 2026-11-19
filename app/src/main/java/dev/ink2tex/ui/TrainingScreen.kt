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
import dev.ink2tex.ocr.TrainingCheckpoints
import dev.ink2tex.ocr.TrainingPlan
import dev.ink2tex.storage.TrainingDraft
import dev.ink2tex.storage.TrainingRun
import org.json.JSONArray
import java.text.DateFormat
import java.util.Date
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
    val docs by vm.history.collectAsStateWithLifecycle()
    val jobs by vm.trainingJobs.collectAsStateWithLifecycle()
    val runs by vm.trainingRuns.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val changes by vm.app.models.changes.collectAsStateWithLifecycle()
    val available = remember(changes, settings.model) { vm.app.models.available() }
    val model = available.firstOrNull { it.id == settings.model }
        ?: available.firstOrNull().takeIf { settings.model == "auto" }
    val job = jobs.firstOrNull { !it.state.isFinished }
    val running = job != null
    val plan by produceState<TrainingPlan?>(null, model?.id, labels, docs, runs) {
        value = null
        value = if (model == null) null else try {
            vm.repository.training.plan(model.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            TrainingPlan(problem = failure.message ?: "Could not prepare the training batch.")
        }
    }
    val latestRun = runs.firstOrNull { it.modelId == model?.id }
    val jobFailure = jobs.lastOrNull { it.state == androidx.work.WorkInfo.State.FAILED && it.id.toString() !in runs.map { run -> run.id } }
        ?.outputData?.getString("summary")
    val directory = model?.let { vm.app.models.directory(it.id) }
    val hasWeights = directory?.let { File(it, TrainingCheckpoints.ACTIVE).isFile } == true
    val hasPrevious = directory?.let { File(it, TrainingCheckpoints.PREVIOUS).isFile } == true
    val reviewQueue = remember(docs, labels) {
        val reviewed = labels.map { it.source }.toMutableSet()
        val checkedNotes = labels.map { it.documentId }.toSet()
        docs.filter { it.status !in setOf("Queued", "Converting") && !it.title.startsWith("Benchmark · ") }
            .sortedWith(compareBy<Document> { it.id in checkedNotes }.thenBy { it.markdown.isBlank() })
            .flatMap { doc -> doc.source.lines().filter { it.isNotBlank() && reviewed.add(it) }.map { doc to it } }
    }
    val scope = rememberCoroutineScope()
    var reviewTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewPath by rememberSaveable { mutableStateOf<String?>(null) }
    var restore by rememberSaveable { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            exporting = true
            try { vm.message.value = "Exported ${vm.repository.training.export(uri)} reviewed labels." }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { vm.message.value = failure.message ?: "Unable to export." }
            finally { exporting = false }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::import)
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Handwriting training", style = MaterialTheme.typography.headlineMedium) }
        item {
            TrainingCard("1. Check your images") {
                Text("${labels.size} checked · ${reviewQueue.size} to review")
                val next = reviewQueue.firstOrNull()
                Text(if (labels.size < 2) "Check images from two separate notes. Ink2TeX will reserve one for evaluation."
                    else "Confirm an accurate scan or correct its source before saving it as training data.")
                if (next != null) Button(onClick = { reviewTarget = next.first.id; reviewPath = next.second }, enabled = !running) {
                    Text("Review next image")
                }
                OutlinedButton(onClick = { import.launch(arrayOf("image/png", "image/jpeg", "image/webp", "application/pdf")) }) {
                    Text("Import another note")
                }
            }
        }
        item {
            TrainingCard("2. Train and check a batch") {
                Text(model?.name ?: "Install and select a model in Model Store.")
                val batch = plan
                if (job != null) {
                    val fraction = job.progress.getInt("completed", 0).toFloat() /
                        job.progress.getInt("total", 1).coerceAtLeast(1)
                    LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(job.progress.getString("stage") ?: "Queued")
                    TextButton(onClick = vm::cancelTraining) { Text("Cancel training") }
                } else if (model == null) {
                    Button(onClick = { vm.go("models") }) { Text("Open Model Store") }
                } else if (batch == null) {
                    Text("Preparing the checked images…")
                } else if (batch.problem != null) {
                    Text(batch.problem)
                } else {
                    Text("${batch.fit.size} training · ${batch.evaluate.size} reserved for evaluation")
                    Text("${batch.newImages} new or revised image(s) available. Results are checked before weights are activated.", fontSize = 13.sp)
                    Button(onClick = { vm.trainHandwriting(model.id, batch) }) { Text("Train on tablet") }
                }
                jobFailure?.let { Text(it, fontSize = 13.sp) }
            }
        }
        item {
            TrainingCard("3. Results and checkpoints") {
                Text(if (hasWeights && settings.trainedWeights) "Using trained output weights in scans."
                    else "Using stock weights in scans.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use trained weights", Modifier.weight(1f))
                    Switch(settings.trainedWeights && hasWeights,
                        { value -> scope.launch { vm.repository.settings.trainedWeights(value) } },
                        enabled = hasWeights && !running)
                }
                if (hasPrevious) OutlinedButton(onClick = { restore = true }, enabled = !running) { Text("Restore previous weights") }
                if (latestRun == null) Text("The first completed batch will appear here.")
                else Text("Recent batches", fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = { export.launch("Ink2TeX-training.zip") },
                    enabled = labels.isNotEmpty() && !exporting) {
                    Text(if (exporting) "Exporting…" else "Export labeled images")
                }
            }
        }
        items(runs.filter { it.modelId == model?.id }.take(10), key = { it.id }) { RunScorecard(it) }
        if (labels.isNotEmpty()) item { Text("Checked images", style = MaterialTheme.typography.titleMedium) }
        items(labels, key = { "${it.documentId}|${it.source}" }) { label ->
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val reserved = label.evaluation || plan?.reservedNotes?.contains(label.documentId) == true
                    Text(if (reserved) "Evaluation image" else "Checked image", fontWeight = FontWeight.SemiBold)
                    Text(label.transcription, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                }
                TextButton(onClick = { reviewTarget = label.documentId; reviewPath = label.source },
                    enabled = !running) { Text("Review") }
                TextButton(onClick = { scope.launch { vm.repository.training.remove(label) } },
                    enabled = !running) { Text("Remove label") }
            }
        }
    }
    docs.firstOrNull { it.id == reviewTarget }?.let { doc ->
        TrainingReviewDialog(vm, doc, doc.markdown, reviewPath) { reviewTarget = null; reviewPath = null }
    }
    if (restore && model != null) AlertDialog(
        onDismissRequest = { restore = false },
        title = { Text("Restore the previous checkpoint?") },
        text = { Text("Scans will use the previous set of learned weights. Your reviewed images and batch results stay available.") },
        confirmButton = {
            TextButton(onClick = { vm.restoreHandwriting(model.id); restore = false }) { Text("Restore previous") }
        },
        dismissButton = { TextButton(onClick = { restore = false }) { Text("Cancel") } }
    )
}

@Composable private fun TrainingCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable private fun RunScorecard(run: TrainingRun) {
    val date = remember(run.started) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(run.started)) }
    val details = remember(run.scorecard) { runCatching { JSONArray(run.scorecard) }.getOrNull() }
    val title = when (run.outcome) {
        "promoted" -> "Checkpoint saved"
        "kept", "unchanged" -> "Current weights kept"
        "restored" -> "Previous checkpoint restored"
        "running" -> "Training started"
        "cancelled" -> "Training cancelled"
        else -> "Training failed"
    }
    TrainingCard(title) {
        Text("$date · ${run.trainingCount} training · ${run.evaluationCount} evaluation", fontSize = 12.sp)
        if (run.summary.isNotBlank()) Text(run.summary)
        if (details != null) for (index in 0 until details.length()) {
            val score = details.getJSONObject(index)
            val after = if (score.isNull("after")) "incomplete" else score.getInt("after").toString()
            val math = if (score.has("beforeMath")) {
                val afterMath = if (score.isNull("afterMath")) "incomplete" else score.getInt("afterMath").toString()
                " · math edits ${score.getInt("beforeMath")} → $afterMath"
            } else ""
            Text("Image ${index + 1} · text edits ${score.getInt("before")} → $after$math · " +
                "${score.getLong("currentMs")} ms → ${score.getLong("newMs")} ms", fontSize = 12.sp)
        }
    }
}

@Composable
internal fun TrainingReviewDialog(vm: InkViewModel, doc: Document, editorSource: String, startPath: String? = null, dismiss: () -> Unit) {
    val labels by vm.trainingLabels.collectAsStateWithLifecycle()
    val paths = remember(doc.source) { doc.source.lines().filter { it.isNotBlank() } }
    if (paths.isEmpty()) return
    var index by rememberSaveable(doc.id, startPath) { mutableIntStateOf(startPath?.let(paths::indexOf)?.coerceAtLeast(0) ?: 0) }
    val drafts by produceState<List<TrainingDraft>?>(null,doc.id) {
        value = vm.app.database.trainingHistory().drafts(doc.id)
    }
    val visibleIndex = index.coerceIn(paths.indices)
    val path = paths[visibleIndex]
    val prior = labels.firstOrNull { it.documentId == doc.id && it.source == path }
    val draft = drafts?.firstOrNull { it.source == path }
    val suggested = prior?.transcription ?: if (paths.size == 1) editorSource else draft?.text.orEmpty()
    var manualText by rememberSaveable(path) { mutableStateOf<String?>(null) }
    val text = manualText ?: suggested
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
    fun save(review: String) {
        saving = true
        vm.recordTraining(TrainingLabel(doc.id, path, text, review, evaluation)) { ok ->
            saving = false
            if (ok && visibleIndex < paths.lastIndex) index = visibleIndex + 1
            else if (ok) dismiss()
        }
    }
    AlertDialog(
        onDismissRequest = { if (!saving) dismiss() },
        title = { Text("Check this image") },
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
                if (draft?.incomplete == true) Text("The scan is incomplete. Fill in missing source before saving.")
                if (paths.size > 1 && drafts != null && draft == null && prior == null) {
                    Text("This image has no saved draft. Enter its source here, or rerun the note to create drafts.")
                }
                OutlinedTextField(text, { manualText = it }, Modifier.fillMaxWidth(), enabled = !saving,
                    label = { Text("Exact Markdown and LaTeX for this image") }, minLines = 3, maxLines = 8)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(evaluation, { evaluation = it }, enabled = !saving)
                    Text("Reserve this note for evaluation")
                }
            }
        },
        confirmButton = {
            val completeDraft = draft?.incomplete != true && text.isNotBlank() && text == suggested
            TextButton(
                onClick = { save(if (completeDraft) "accepted" else "corrected") },
                enabled = !saving && image != null && text.isNotBlank() &&
                    (paths.size == 1 || drafts != null) && (draft?.incomplete != true || manualText != null)
            ) { Text(if (saving) "Saving…" else if (completeDraft) "Mark correct" else "Save corrected source") }
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !saving) { Text("Close") } }
    )
}
