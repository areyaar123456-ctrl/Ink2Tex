package dev.ink2tex

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ink2tex.preprocessing.PdfImport
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfImportDeviceTest {
    @Test fun tallPdfRendersBoundedSectionsWithBottomInkInTheCorrectPlace() = runBlocking(Dispatchers.IO) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as InkApplication
        val file = File.createTempFile("tall-note", ".pdf", app.cacheDir)
        try {
            val pdf = PdfDocument()
            try {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(405, 2034, 1).create())
                val paint = Paint().apply { color = Color.BLACK }
                page.canvas.drawRect(20f, 20f, 70f, 70f, paint)
                page.canvas.drawRect(20f, 1960f, 70f, 2010f, paint)
                pdf.finishPage(page)
                file.outputStream().use(pdf::writeTo)
            } finally {
                pdf.close()
            }
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val document = PdfRenderer(descriptor).use { PdfImport.save(it, app.repository) }
            try {
                val images = document.source.lines()
                assertTrue(images.size >= 4)
                assertTrue(images.all(PdfImport::isSection))
                assertFalse(images.any(PdfImport::hasOverlap))
                val first = BitmapFactory.decodeFile(images.first())
                val last = BitmapFactory.decodeFile(images.last())
                try {
                    assertTrue(first.width >= 1000)
                    assertEquals(Color.BLACK, first.getPixel(100, 100))
                    assertEquals(Color.WHITE, last.getPixel(100, 100))
                    assertEquals(Color.BLACK, last.getPixel(100, last.height - 125))
                } finally {
                    first.recycle()
                    last.recycle()
                }
            } finally {
                app.repository.delete(document)
            }
        } finally {
            file.delete()
        }
    }
}
