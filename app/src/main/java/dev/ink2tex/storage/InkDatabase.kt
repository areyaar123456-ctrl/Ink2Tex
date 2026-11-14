package dev.ink2tex.storage

import android.content.Context
import androidx.room.*
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
 @Query("DELETE FROM documents WHERE id=:id") suspend fun delete(id: String)
}
@Database(entities = [Document::class], version = 1, exportSchema = true)
abstract class InkDatabase : RoomDatabase() {
 abstract fun documents(): DocumentDao
 companion object { fun create(context: Context) = Room.databaseBuilder(context, InkDatabase::class.java, "ink2tex.db").build() }
}
