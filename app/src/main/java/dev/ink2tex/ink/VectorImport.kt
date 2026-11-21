package dev.ink2tex.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.util.Xml
import com.caverock.androidsvg.SVG
import dev.ink2tex.storage.Document
import dev.ink2tex.storage.InkRepository
import java.io.File
import java.util.UUID
import kotlin.math.ceil
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

internal object VectorImport {
    const val PREVIEW_SUFFIX = ".nebo-page.png"
    private const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024

    suspend fun nebo(context: Context, uri: Uri, repository: InkRepository): Document {
        val id = UUID.randomUUID().toString()
        val archive = File.createTempFile("nebo-", ".zip", context.cacheDir)
        val previews = mutableListOf<File>()
        var saved = false
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                archive.outputStream().use { output ->
                    val buffer = ByteArray(32_768)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_ARCHIVE_BYTES) { "Export fewer Nebo pages at a time." }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Unable to open the Nebo export.")

            NeboInk.pages(archive) { raw, page ->
                val preview = File(repository.images, "$id-${previews.size}$PREVIEW_SUFFIX")
                previews += preview
                val bitmap = InkRaster.render(page)
                try {
                    preview.outputStream().use {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Unable to save the ink preview." }
                    }
                } finally {
                    bitmap.recycle()
                }
                NeboInk.sidecar(preview).writeBytes(raw)
            }
            val document = Document(id, "Imported Nebo ink", source = previews.joinToString("\n") { it.absolutePath },
                warnings = "This preview shows pen strokes. Check against MyScript Notes for typed text, images and other elements.")
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                repository.documents.put(document)
                saved = true
            }
            return document
        } catch (_: java.util.zip.ZipException) {
            error("This Nebo archive is damaged or unsupported. Export it again or choose PDF.")
        } finally {
            archive.delete()
            if (!saved) previews.forEach { preview ->
                preview.delete()
                NeboInk.sidecar(preview).delete()
            }
        }
    }

    suspend fun svg(context: Context, uri: Uri, repository: InkRepository): Document {
        val source = context.contentResolver.openInputStream(uri)?.use {
            NeboInk.limited(it, 4 * 1024 * 1024).decodeToString(throwOnInvalidSequence = true)
        } ?: error("Unable to open the SVG.")
        validateSvg(source)
        val svg = SVG.getFromString(source)
        val viewBox = svg.documentViewBox
        val width = svg.documentWidth.takeIf { it > 0 } ?: viewBox?.width() ?: error("SVG width is missing.")
        val height = svg.documentHeight.takeIf { it > 0 } ?: viewBox?.height() ?: error("SVG height is missing.")
        require(width.isFinite() && height.isFinite() && width > 0 && height > 0 && maxOf(width, height) <= 100_000) {
            "The SVG dimensions are unsupported."
        }
        val scale = 1800f / maxOf(width, height)
        val w = ceil(width * scale).toInt().coerceAtLeast(1)
        val h = ceil(height * scale).toInt().coerceAtLeast(1)
        if (viewBox == null) svg.setDocumentViewBox(0f, 0f, width, height)
        svg.documentWidth = w.toFloat()
        svg.documentHeight = h.toFloat()
        val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return try {
            image.eraseColor(Color.WHITE)
            svg.renderToCanvas(Canvas(image))
            repository.save(image, "Imported SVG")
        } finally {
            image.recycle()
        }
    }

    internal fun validateSvg(source: String) {
        require(!source.contains("<!DOCTYPE", ignoreCase = true) &&
            !source.contains("<!ENTITY", ignoreCase = true)) { "SVG document declarations are unsupported." }
        val allowed = setOf("svg", "g", "defs", "path", "rect", "ellipse", "circle", "line", "polyline",
            "polygon", "clipPath", "title", "desc")
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(source.reader())
        var depth = 0
        var count = 0
        var clipDepth = -1
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.PROCESSING_INSTRUCTION, XmlPullParser.DOCDECL ->
                    error("SVG processing instructions and declarations are unsupported.")
                XmlPullParser.START_TAG -> {
                    require(++depth <= 40 && ++count <= 30_000) { "This SVG is too complex." }
                    require(parser.name in allowed && parser.namespace == "http://www.w3.org/2000/svg" &&
                        (depth != 1 || parser.name == "svg")) { "This SVG contains unsupported content. Export as PDF instead." }
                    if (parser.name == "clipPath") {
                        require(clipDepth == -1) { "Nested SVG clipping is unsupported." }
                        clipDepth = depth
                    }
                    for (index in 0 until parser.attributeCount) {
                        val name = parser.getAttributeName(index)
                        val value = parser.getAttributeValue(index)
                        require(!name.startsWith("on", ignoreCase = true) && name !in setOf("href", "src") &&
                            (!value.contains("url(", ignoreCase = true) ||
                                (name == "clip-path" && value.matches(Regex("url\\(#[A-Za-z0-9_-]+\\)"))))) {
                            "SVG resource references are unsupported."
                        }
                        require(name != "clip-path" || clipDepth == -1) { "Nested SVG clipping is unsupported." }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (depth == clipDepth) clipDepth = -1
                    depth--
                }
            }
        }
        require(count > 0) { "The SVG is empty." }
    }
}
