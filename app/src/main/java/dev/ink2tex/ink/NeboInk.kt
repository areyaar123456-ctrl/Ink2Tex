package dev.ink2tex.ink

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** BINK v5 decoding, based on the MIT-licensed nebo-ocr format documentation. */
internal object NeboInk {
    private const val MAX_INK_BYTES = 8 * 1024 * 1024
    private const val MAX_ARCHIVE_INK_BYTES = 48 * 1024 * 1024
    private const val MAX_POINTS = 450_000
    private const val MAX_ENTRIES = 3_000
    private const val MAX_PAGES = 100
    private val pageId = Regex("[A-Za-z0-9_-]{1,128}")

    fun sidecar(preview: File) = File(preview.parentFile, preview.name + ".bink")

    suspend fun pages(archive: File, accept: suspend (ByteArray, InkPage) -> Unit): Int =
        ZipFile(archive).use { zip ->
            require(zip.size() in 1..MAX_ENTRIES) { "This Nebo export contains too many entries." }
            val entryNames = zip.entries().asSequence().map { it.name }.toList()
            require(entryNames.size == entryNames.toSet().size) { "The Nebo archive contains duplicate entries." }
            val rel = zip.getEntry("rel.json") ?: error("Missing Nebo page index. Export the note as PDF instead.")
            val indexed = JSONObject(zip.getInputStream(rel).use { limited(it, 512 * 1024) }.decodeToString())
                .optJSONObject("pages")?.keys()?.asSequence()?.toList().orEmpty()
            require(indexed.size in 1..MAX_PAGES && indexed.all(pageId::matches)) {
                "This Nebo page index is unsupported. Export the note as PDF instead."
            }
            val inkNames = entryNames.filter { it.startsWith("pages/") && it.endsWith("/ink.bink") }.toSet()
            require(inkNames == indexed.map { "pages/$it/ink.bink" }.toSet()) {
                "The Nebo archive has missing or unindexed ink pages. Export the note as PDF instead."
            }
            val ordered = if (indexed.size > 1) {
                val index = zip.getEntry("index.bdom") ?: error("This Nebo export has no page order. Export it as PDF instead.")
                val order = zip.getInputStream(index).use { limited(it, 1024 * 1024) }.decodeToString()
                val offsets = indexed.associateWith { order.indexOf(it) }
                require(indexed.all { offsets.getValue(it) >= 0 && offsets.getValue(it) == order.lastIndexOf(it) }) {
                    "The Nebo page order is unclear. Export it as PDF instead."
                }
                indexed.sortedBy(offsets::getValue)
            } else indexed
            var total = 0L
            for (id in ordered) {
                currentCoroutineContext().ensureActive()
                val entry = zip.getEntry("pages/$id/ink.bink") ?: error("Missing ink page.")
                require(entry.size in 1..MAX_INK_BYTES.toLong()) { "This Nebo ink page is too large." }
                val bytes = zip.getInputStream(entry).use { limited(it, MAX_INK_BYTES) }
                total += bytes.size
                require(total <= MAX_ARCHIVE_INK_BYTES) { "Export fewer Nebo pages at a time." }
                accept(bytes, decode(bytes))
            }
            ordered.size
        }

    fun read(file: File): InkPage = file.inputStream().use { decode(limited(it, MAX_INK_BYTES)) }

    fun decode(bytes: ByteArray): InkPage {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun skip(count: Int) {
            require(count in 0..input.remaining()) { "Damaged Nebo ink data." }
            input.position(input.position() + count)
        }
        fun string(maximum: Int): String {
            val size = input.int
            require(size in 0..maximum && size <= input.remaining()) { "Damaged Nebo ink channel." }
            return ByteArray(size).also(input::get).decodeToString()
        }
        try {
            val magic = ByteArray(4).also(input::get)
            require(magic.decodeToString() == "BINK" && input.get().toInt() == 0 &&
                input.get().toInt() == 5 && input.int in setOf(0, 5) && input.int == 1) {
                "Unsupported Nebo ink version. Export the note as PDF instead."
            }
            require(input.int == 4) { "Unsupported Nebo ink channels." }
            for (channel in listOf("X", "Y", "F", "T")) {
                require(string(64) == channel) { "Unsupported Nebo ink channels." }
                skip(4)
                if (input.int != 0) string(64)
            }
            val layoutBytes = input.int
            require(layoutBytes in 1..4096) { "Unsupported Nebo ink layout." }
            skip(layoutBytes)
            require(input.int == 1000 && input.int == 1000) { "Unsupported Nebo coordinate precision." }
            skip(5)
            val records = input.int
            require(records in 0..40_000) { "This Nebo page contains too many strokes." }
            val strokes = mutableListOf<InkStroke>()
            var totalPoints = 0
            fun pointCount(bytesPerPoint: Int): Int {
                val count = input.int
                require(count in 1..minOf(65_536, MAX_POINTS - totalPoints, input.remaining() / bytesPerPoint)) {
                    "Damaged or oversized Nebo stroke."
                }
                totalPoints += count
                return count
            }
            fun point(x: Float, y: Float, force: Int, timeOffsetMs: Long? = null): InkPoint {
                require(x in -100_000f..100_000f && y in -100_000f..100_000f) { "Invalid Nebo coordinates." }
                return InkPoint(x, y, force, timeOffsetMs)
            }
            repeat(records) {
                val flag = input.int
                when (flag) {
                    -1 -> return@repeat
                    Int.MIN_VALUE, 0 -> Unit
                    else -> error("Unsupported Nebo stroke type. Export the note as PDF instead.")
                }
                val start = input.long.takeIf { it >= 0 }
                if (flag == 0) {
                    // Android also writes short, uncompressed traces with float XY/force and elapsed milliseconds.
                    skip(4)
                    val count = pointCount(16)
                    val xy = FloatArray(count * 2) { input.float }
                    val force = IntArray(count) {
                        val value = input.float
                        require(value in 0f..1f) { "Invalid Nebo stroke pressure." }
                        (value * 255f).roundToInt()
                    }
                    val points = List(count) { index ->
                        point(xy[index * 2], xy[index * 2 + 1], force[index], input.int.toLong() and 0xffff_ffffL)
                    }
                    strokes += InkStroke(points, start)
                    return@repeat
                }
                val baseX = input.float
                val baseY = input.float
                skip(6)
                val count = pointCount(5)
                val x = FloatArray(count)
                val y = FloatArray(count)
                var delta = 0L
                for (index in x.indices) {
                    delta += input.short
                    x[index] = baseX + delta / 500f
                }
                delta = 0L
                for (index in y.indices) {
                    delta += input.short
                    y[index] = baseY + delta / 500f
                }
                val points = List(count) { index -> point(x[index], y[index], input.get().toInt() and 255) }
                strokes += InkStroke(points, start)
            }
            return InkPage(strokes)
        } catch (_: java.nio.BufferUnderflowException) {
            error("The Nebo ink data is incomplete. Export the note again or choose PDF.")
        }
    }

    fun limited(input: InputStream, limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16_384)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            require(read <= limit - buffer.size()) { "The selected file exceeds the import limit." }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }
}
