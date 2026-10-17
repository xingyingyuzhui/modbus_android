package io.github.xingyingyuzhui.modbusmobile.application.history.room

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import io.github.xingyingyuzhui.modbusmobile.application.history.HistorySample
import io.github.xingyingyuzhui.modbusmobile.application.history.HistorySession
import io.github.xingyingyuzhui.modbusmobile.application.history.HistoryStore
import io.github.xingyingyuzhui.modbusmobile.application.history.SampleQuality
import io.github.xingyingyuzhui.modbusmobile.application.history.StorageFullException

class RoomHistoryStore internal constructor(
    private val db: HistoryDatabase,
) : HistoryStore {
    constructor(context: Context) : this(
        Room.databaseBuilder(context.applicationContext, HistoryDatabase::class.java, "history.db").build(),
    )

    override suspend fun openSession(role: String, target: String, epoch: Long, startedAtMs: Long): HistorySession =
        guard {
            val id = db.dao().insertSession(
                SessionEntity(
                    role = role,
                    target = target,
                    epoch = epoch,
                    startedAtMs = startedAtMs,
                    endedAtMs = null,
                    retained = false,
                ),
            )
            HistorySession(id, role, target, epoch, startedAtMs, null, false)
        }

    override suspend fun closeSession(id: Long, endedAtMs: Long) {
        guard { db.dao().closeSession(id, endedAtMs) }
    }

    override suspend fun closeDangling(endedAtMs: Long, startedBeforeMs: Long) {
        guard { db.dao().closeDangling(endedAtMs, startedBeforeMs) }
    }

    override suspend fun insertSamples(samples: List<HistorySample>) {
        if (samples.isEmpty()) return
        guard {
            db.dao().insertSamples(samples.map { it.toEntity() })
        }
    }

    override suspend fun sessions(): List<HistorySession> = db.dao().sessions().map { it.toModel() }

    override suspend fun samples(sessionId: Long): List<HistorySample> =
        db.dao().samples(sessionId).map { it.toModel() }

    override suspend fun usageBytes(): Long =
        try {
            val database = db.openHelper.readableDatabase
            fun pragma(name: String): Long {
                database.query("PRAGMA $name").use { cursor ->
                    return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                }
            }
            val pages = pragma("page_count")
            val pageSize = pragma("page_size")
            pages * pageSize
        } catch (_: SQLiteException) {
            Long.MAX_VALUE
        }

    override suspend fun deleteSessions(ids: List<Long>) {
        if (ids.isEmpty()) return
        guard { db.dao().deleteUnretained(ids) }
    }

    override suspend fun setRetained(id: Long, retained: Boolean) {
        guard { db.dao().setRetained(id, retained) }
    }

    private suspend inline fun <T> guard(block: suspend () -> T): T =
        try {
            block()
        } catch (error: SQLiteException) {
            val message = error.message.orEmpty()
            if (message.contains("disk is full", ignoreCase = true) || message.contains("SQLITE_FULL", ignoreCase = true)) {
                throw StorageFullException()
            }
            throw error
        }
}

private fun SessionEntity.toModel() = HistorySession(id, role, target, epoch, startedAtMs, endedAtMs, retained)

private fun SampleEntity.toModel() = HistorySample(
    sessionId, pointId, sampleAtMs, recordedAtMs, epoch,
    quality = enumValues<SampleQuality>().firstOrNull { it.name == quality } ?: SampleQuality.UNKNOWN,
    engineering, text,
)

private fun HistorySample.toEntity() = SampleEntity(
    sessionId = sessionId,
    pointId = pointId,
    sampleAtMs = sampleAtMs,
    recordedAtMs = recordedAtMs,
    epoch = epoch,
    quality = quality.name,
    engineering = engineering,
    text = text,
)
