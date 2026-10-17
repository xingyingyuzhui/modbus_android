package io.github.xingyingyuzhui.modbusmobile.application.history

/** 数据库写不下去（磁盘满或 SQLite 返回 FULL）。记录停止，通信继续。 */
class StorageFullException : Exception()

interface HistoryStore {
    suspend fun openSession(role: String, target: String, epoch: Long, startedAtMs: Long): HistorySession

    suspend fun closeSession(id: Long, endedAtMs: Long)

    /** 进程重启后，把本次启动之前仍未关闭的会话收口。 */
    suspend fun closeDangling(endedAtMs: Long, startedBeforeMs: Long)

    suspend fun insertSamples(samples: List<HistorySample>)

    suspend fun sessions(): List<HistorySession>

    suspend fun samples(sessionId: Long): List<HistorySample>

    suspend fun usageBytes(): Long

    /** 保留中的会话即使出现在名单里也不删。 */
    suspend fun deleteSessions(ids: List<Long>)

    suspend fun setRetained(id: Long, retained: Boolean)
}
