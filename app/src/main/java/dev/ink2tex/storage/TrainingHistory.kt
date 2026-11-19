package dev.ink2tex.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "training_runs")
data class TrainingRun(
    @PrimaryKey val id: String,
    val modelId: String,
    val started: Long,
    val finished: Long?,
    val outcome: String,
    val planKey: String,
    val trainingCount: Int,
    val evaluationCount: Int,
    val trainingKeys: String,
    val scorecard: String,
    val summary: String
) {
    val evaluated get() = outcome in setOf("promoted", "kept", "unchanged")
}

@Entity(tableName = "training_drafts", primaryKeys = ["documentId", "source"])
data class TrainingDraft(val documentId: String, val source: String, val text: String, val incomplete: Boolean)

@Entity(tableName = "crop_origins")
data class CropOrigin(@PrimaryKey val source: String, val parent: String)

@Dao
interface TrainingHistoryDao {
    @Query("SELECT * FROM training_runs ORDER BY started DESC, id")
    fun observe(): Flow<List<TrainingRun>>

    @Query("SELECT * FROM training_runs ORDER BY started DESC, id")
    suspend fun runs(): List<TrainingRun>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(run: TrainingRun)

    @Query("SELECT * FROM training_drafts WHERE documentId = :documentId")
    suspend fun drafts(documentId: String): List<TrainingDraft>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDrafts(drafts: List<TrainingDraft>)

    @Query("DELETE FROM training_drafts WHERE documentId = :documentId")
    suspend fun removeDrafts(documentId: String)

    @Query("SELECT * FROM crop_origins")
    suspend fun origins(): List<CropOrigin>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putOrigin(origin: CropOrigin)
}
