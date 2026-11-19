package dev.ink2tex

import android.graphics.Bitmap
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import dev.ink2tex.ocr.TrainingEvaluation
import dev.ink2tex.storage.InkDatabase
import dev.ink2tex.storage.InkRepository
import dev.ink2tex.storage.TrainingLabel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = InkApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeightTrainingTest {
    @Test fun checkedLabelsAreUpdatedPerImageAndOriginalDeletionClearsThem() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as InkApplication
        val database = Room.inMemoryDatabaseBuilder(app, InkDatabase::class.java).build()
        val repository = InkRepository(app, database)
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        val doc = try { repository.save(bitmap) } finally { bitmap.recycle() }
        try {
            repository.training.save(TrainingLabel(doc.id, doc.source, "original", "accepted", false))
            repository.training.save(TrainingLabel(doc.id, doc.source, "\$p \\nmid a\$", "corrected", true))
            val label = database.trainingLabels().all().single()
            assertEquals("\$p \\nmid a\$", label.transcription)
            assertTrue(label.evaluation)
            repository.deleteOriginals(doc)
            assertTrue(database.trainingLabels().all().isEmpty())
        } finally {
            repository.delete(doc)
            database.close()
        }
    }

    @Test fun upgradingTheNotebookPreservesPreviousSourceAndTranscript() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as InkApplication
        val name = "weight-migration-test.db"
        val path = app.getDatabasePath(name)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { previous ->
            previous.execSQL("""CREATE TABLE IF NOT EXISTS `documents` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `created` INTEGER NOT NULL, `source` TEXT NOT NULL, `markdown` TEXT NOT NULL, `modelId` TEXT NOT NULL, `status` TEXT NOT NULL, `error` TEXT, `durationMs` INTEGER, `tokens` INTEGER, `warnings` TEXT NOT NULL, PRIMARY KEY(`id`))""")
            previous.execSQL("INSERT INTO documents (id,title,created,source,markdown,modelId,status,warnings) " +
                "VALUES ('kept','Previous proof',1,'previous.png','keep this source','ovis2-q8','Review','')")
            previous.version = 1
        }
        val upgraded = Room.databaseBuilder(app, InkDatabase::class.java, name)
            .addMigrations(InkDatabase.ADD_TRAINING_LABELS, InkDatabase.ADD_TRAINING_HISTORY).build()
        try {
            assertEquals("keep this source", upgraded.documents().get("kept")?.markdown)
            assertEquals("previous.png", upgraded.documents().get("kept")?.source)
            assertTrue(upgraded.trainingLabels().all().isEmpty())
        } finally {
            upgraded.close()
            app.deleteDatabase(name)
        }
    }

    @Test fun upgradingVersionTwoKeepsPreviouslyReviewedImages() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as InkApplication
        val name = "review-migration-test.db"
        val path = app.getDatabasePath(name)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { previous ->
            previous.execSQL("""CREATE TABLE IF NOT EXISTS documents (
                id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, created INTEGER NOT NULL, source TEXT NOT NULL,
                markdown TEXT NOT NULL, modelId TEXT NOT NULL, status TEXT NOT NULL,
                error TEXT, durationMs INTEGER, tokens INTEGER, warnings TEXT NOT NULL)""")
            previous.execSQL("""CREATE TABLE IF NOT EXISTS training_labels (
                documentId TEXT NOT NULL, source TEXT NOT NULL, transcription TEXT NOT NULL,
                review TEXT NOT NULL, evaluation INTEGER NOT NULL, updated INTEGER NOT NULL,
                PRIMARY KEY(documentId, source))""")
            previous.execSQL("INSERT INTO training_labels VALUES ('note','proof.png','checked math','corrected',0,5)")
            previous.version = 2
        }
        val upgraded = Room.databaseBuilder(app, InkDatabase::class.java, name)
            .addMigrations(InkDatabase.ADD_TRAINING_HISTORY).build()
        try {
            assertEquals("checked math", upgraded.trainingLabels().all().single().transcription)
            assertTrue(upgraded.trainingHistory().runs().isEmpty())
        } finally {
            upgraded.close()
            app.deleteDatabase(name)
        }
    }

    @Test fun evaluationPenalizesChangedSymbolsAndRejectsRegressions() {
        assertTrue(TrainingEvaluation.errors("p \\nmid a", "p \\mid a") > 0)
        assertTrue(TrainingEvaluation.errors("x^{2023}", "x^{2025}") > 0)
        assertEquals(0, TrainingEvaluation.errors("a\n b", "a  b"))
        assertFalse(TrainingEvaluation.accept(listOf(4, 2), listOf(1, 3)))
        assertFalse(TrainingEvaluation.accept(listOf(4, 2), listOf(4, 2)))
        assertTrue(TrainingEvaluation.accept(listOf(4, 2), listOf(3, 2)))
    }
}
