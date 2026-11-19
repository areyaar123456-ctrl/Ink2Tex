package dev.ink2tex.storage

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "documents")
data class Document(
 @PrimaryKey val id: String,
 val title: String,
 val created: Long = System.currentTimeMillis(),
 val source: String = "",
 val markdown: String = "",
 val modelId: String = "",
 val status: String = "Captured",
 val error: String? = null,
 val durationMs: Long? = null,
 val tokens: Int? = null,
 val warnings: String = ""
)
@Dao interface DocumentDao {
 @Query("SELECT * FROM documents ORDER BY created DESC") fun observe(): Flow<List<Document>>
 @Query("SELECT * FROM documents WHERE id = :id") suspend fun get(id: String): Document?
 @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(document: Document)
 @Query("UPDATE documents SET markdown=:text WHERE id=:id") suspend fun edit(id: String, text: String)
 @Query("SELECT source FROM documents") suspend fun sources(): List<String>
 @Query("UPDATE documents SET status=:status, error=:error WHERE id=:id") suspend fun state(id: String, status: String, error: String?)
 @Query("UPDATE documents SET source='' WHERE id=:id") suspend fun clearSource(id: String)
 @Query("DELETE FROM documents WHERE id=:id") suspend fun delete(id: String)
}
@Database(entities = [Document::class, TrainingLabel::class], version = 2, exportSchema = true)
abstract class InkDatabase : RoomDatabase() {
 abstract fun documents(): DocumentDao
 abstract fun trainingLabels(): TrainingLabelDao
 companion object {
  val ADD_TRAINING_LABELS = object : Migration(1, 2) {
   override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("""CREATE TABLE IF NOT EXISTS training_labels (
        documentId TEXT NOT NULL, source TEXT NOT NULL, transcription TEXT NOT NULL,
        review TEXT NOT NULL, evaluation INTEGER NOT NULL, updated INTEGER NOT NULL,
        PRIMARY KEY(documentId, source))""")
   }
  }
  fun create(context: Context) = Room.databaseBuilder(context, InkDatabase::class.java, "ink2tex.db")
      .addMigrations(ADD_TRAINING_LABELS).build()
 }
}
