package io.github.xingyingyuzhui.modbusmobile.application.history.room

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val target: String,
    val epoch: Long,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val retained: Boolean,
)

@Entity(
    tableName = "samples",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index(value = ["pointId", "sampleAtMs"])],
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val pointId: String,
    val sampleAtMs: Long,
    val recordedAtMs: Long,
    val epoch: Long,
    val quality: String,
    val engineering: Double?,
    val text: String?,
)

@Dao
interface HistoryDao {
    @Insert
    suspend fun insertSession(row: SessionEntity): Long

    @Query("UPDATE sessions SET endedAtMs = :endedAtMs WHERE id = :id")
    suspend fun closeSession(id: Long, endedAtMs: Long)

    @Query("UPDATE sessions SET endedAtMs = :endedAtMs WHERE endedAtMs IS NULL AND startedAtMs < :startedBeforeMs")
    suspend fun closeDangling(endedAtMs: Long, startedBeforeMs: Long)

    @Insert
    suspend fun insertSamples(rows: List<SampleEntity>)

    @Query("SELECT * FROM sessions ORDER BY startedAtMs ASC, id ASC")
    suspend fun sessions(): List<SessionEntity>

    @Query("SELECT * FROM samples WHERE sessionId = :sessionId ORDER BY sampleAtMs ASC, id ASC")
    suspend fun samples(sessionId: Long): List<SampleEntity>

    @Query("DELETE FROM sessions WHERE retained = 0 AND id IN (:ids)")
    suspend fun deleteUnretained(ids: List<Long>)

    @Query("UPDATE sessions SET retained = :retained WHERE id = :id")
    suspend fun setRetained(id: Long, retained: Boolean)
}

@Database(entities = [SessionEntity::class, SampleEntity::class], version = 1, exportSchema = true)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun dao(): HistoryDao
}
