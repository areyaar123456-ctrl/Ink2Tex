package dev.ink2tex.preprocessing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import dev.ink2tex.storage.Document
import dev.ink2tex.storage.InkRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object PdfImport {
    private const val SECTION_SUFFIX = ".pdf-section.png"
    private const val OVERLAP_SUFFIX = ".pdf-overlap.pdf-section.png"
    private const val MAX_PIXELS = 128_000_000L
    private const val MIN_FREE_BYTES = 32L * 1024 * 1024

    fun isSection(path: String) = path.endsWith(SECTION_SUFFIX)
    fun hasOverlap(path: String) = path.endsWith(OVERLAP_SUFFIX)

    suspend fun read(context: Context, uri: Uri, repository: InkRepository): Document {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Unable to open PDF.")
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (failure: Throwable) {
            descriptor.close()
            throw failure
        }
        return renderer.use { save(it, repository) }
    }

    // PdfRenderer owns its descriptor. The caller runs this on Dispatchers.IO.
    suspend fun save(renderer: PdfRenderer, repository: InkRepository): Document {
        require(renderer.pageCount in 1..500) { "PDF must contain 1–500 pages." }
        val id = UUID.randomUUID().toString()
        val files = mutableListOf<File>()
        var committed = false
        try {
            var totalPixels = 0L
            for (pageIndex in 0 until renderer.pageCount) {
                currentCoroutineContext().ensureActive()
                renderer.openPage(pageIndex).use { page ->
                    val sections = PdfSections(page.width, page.height)
                    totalPixels += sections.pixelCount
                    require(totalPixels <= MAX_PIXELS) {
                        "This PDF exceeds the import size limit at readable resolution. Split it into smaller files."
                    }

                    var top = 0
                    while (top < sections.height) {
                        currentCoroutineContext().ensureActive()
                        val band = sections.next(top) { window ->
                            render(page, sections, window) { blankRows(it) }
                        }
                        val suffix = if (band.overlapsNext) OVERLAP_SUFFIX else SECTION_SUFFIX
                        val file = File(repository.images, "$id-${files.size + 1}$suffix")
                        val rawBytes = sections.width.toLong() * band.rows.count() * 4
                        require(repository.images.usableSpace > MIN_FREE_BYTES + rawBytes) {
                            "Free up storage, then import the PDF again."
                        }
                        files += file
                        render(page, sections, band.rows) { bitmap ->
                            file.outputStream().buffered().use { output ->
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                    "Unable to save PDF section."
                                }
                            }
                        }
                        top = band.nextTop
                    }
                }
            }

            val document = Document(id, "Imported PDF", source = files.joinToString("\n") { it.absolutePath })
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                repository.documents.put(document)
                committed = true
            }
            return document
        } finally {
            if (!committed) files.forEach { it.delete() }
        }
    }

    private inline fun <T> render(
        page: PdfRenderer.Page,
        sections: PdfSections,
        rows: IntRange,
        consume: (Bitmap) -> T
    ): T {
        val bitmap = Bitmap.createBitmap(sections.width, rows.count(), Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val transform = Matrix().apply {
                setScale(sections.scale, sections.scale)
                postTranslate(0f, -rows.first.toFloat())
            }
            page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return consume(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun blankRows(probe: Bitmap): BooleanArray {
        val row = IntArray(probe.width)
        return BooleanArray(probe.height) { y ->
            probe.getPixels(row, 0, probe.width, 0, y, probe.width, 1)
            row.all { it == Color.WHITE }
        }
    }
}
