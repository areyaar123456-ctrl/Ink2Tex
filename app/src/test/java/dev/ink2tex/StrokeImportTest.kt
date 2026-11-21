package dev.ink2tex

import android.net.Uri
import androidx.room.Room
import dev.ink2tex.ink.InkPoint
import dev.ink2tex.ink.JiixTranscription
import dev.ink2tex.ink.NeboInk
import dev.ink2tex.ink.VectorImport
import dev.ink2tex.storage.InkDatabase
import dev.ink2tex.storage.InkRepository
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StrokeImportTest {
    @Test fun binkPreservesStrokeOrderForceAndTimeAndSkipsErasedStrokes() {
        val page = NeboInk.decode(bink(0, 10f))
        assertEquals(2, page.strokes.size)
        val stroke = page.strokes.first()
        assertEquals(8_000_000L, stroke.startMicros)
        assertEquals(listOf(InkPoint(10f, 4f, 70), InkPoint(11f, 3.5f, 210)), stroke.points)
        assertEquals(listOf(InkPoint(11f, 5f, 64, 0), InkPoint(12f, 5f, 128, 10)), page.strokes.last().points)
        assertThrows(IllegalStateException::class.java) { NeboInk.decode(bink(5, 10f).copyOf(41)) }
        assertThrows(IllegalArgumentException::class.java) { NeboInk.decode(bink(8, 10f)) }
    }

    @Test fun importsPagesInDocumentOrderAndKeepsTheSharedSidecarUntilLastDelete() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, InkDatabase::class.java).build()
        val repo = InkRepository(context, database)
        val archive = File.createTempFile("stroke-fixture", ".nebo", context.cacheDir)
        try {
            ZipOutputStream(archive.outputStream()).use { zip ->
                fun add(name: String, data: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
                add("rel.json", """{"pages":{"second":{},"first":{}}}""".encodeToByteArray())
                add("index.bdom", "first\u0000second".encodeToByteArray())
                add("pages/second/ink.bink", bink(0, 20f))
                add("pages/first/ink.bink", bink(5, 10f))
            }
            val doc = VectorImport.nebo(context, Uri.fromFile(archive), repo)
            val previews = doc.source.lines().map(::File)
            assertEquals(2, previews.size)
            val firstInk = NeboInk.sidecar(previews.first())
            assertEquals(10f, NeboInk.read(firstInk).strokes.first().points.first().x)
            val copy = doc.copy(id = "comparison")
            repo.documents.put(copy)
            repo.delete(doc)
            assertTrue(firstInk.isFile)
            repo.deleteOriginals(copy)
            assertFalse(firstInk.exists())
        } finally {
            archive.delete()
            database.close()
        }
    }

    @Test fun svgImportRejectsExternalContent() {
        val ns = "http://www.w3.org/2000/svg"
        VectorImport.validateSvg("""<svg xmlns="$ns" viewBox="0 0 50 50"><path d="M0 0 L50 50"/></svg>""")
        assertThrows(IllegalArgumentException::class.java) {
            VectorImport.validateSvg("""<svg xmlns="$ns"><image href="https://example.test/a"/></svg>""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VectorImport.validateSvg("""<!DOCTYPE svg [<!ENTITY x SYSTEM "https://example.test/">]><svg xmlns="$ns">&x;</svg>""")
        }
    }

    @Test fun jiixKeepsInlineMathAndStandaloneMathOnceAndFlagsUnaccountedInk() {
        val value = """{"type":"Raw Content","elements":[
           {"type":"Text","label":"If ${'$'}x<2${'$'} then","range":[{"from":{"stroke":0},"to":{"stroke":2}}]},
           {"type":"Math","label":"x=4","range":[{"from":{"stroke":3},"to":{"stroke":3}}]}
        ]}"""
        val complete = JiixTranscription.parse(value, 4)
        assertEquals("If \$x<2\$ then\n\n\$\$\nx=4\n\$\$", complete.markdown)
        assertFalse(complete.incomplete)
        assertTrue(JiixTranscription.parse(value, 5).incomplete)
    }

    private fun bink(version: Int, firstX: Float): ByteArray {
        val output = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        output.put("BINK".encodeToByteArray()).put(0).put(5)
        output.putInt(version).putInt(1).putInt(4)
        for (channel in "XYFT") {
            output.putInt(1).put(channel.code.toByte()).putInt(0).putInt(0)
        }
        output.putInt(4).putInt(0)
        output.putInt(1000).putInt(1000).putInt(3).put(0)
        output.putInt(3).putInt(-1).putInt(Int.MIN_VALUE)
        output.putLong(8_000_000).putFloat(firstX).putFloat(4f).putInt(0).putShort(0)
        output.putInt(2).putShort(0).putShort(500).putShort(0).putShort(-250)
        output.put(70).put(210.toByte())
        output.putInt(0).putLong(9_000_000).putInt(0).putInt(2)
        output.putFloat(firstX + 1).putFloat(5f).putFloat(firstX + 2).putFloat(5f)
        output.putFloat(.25f).putFloat(.5f).putInt(0).putInt(10)
        return output.array().copyOf(output.position())
    }
}
