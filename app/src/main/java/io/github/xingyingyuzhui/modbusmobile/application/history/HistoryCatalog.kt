package io.github.xingyingyuzhui.modbusmobile.application.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class HistoryOverview(
    val usageBytes: Long,
    val sessions: List<HistorySession>,
    val dropped: Long,
) {
    companion object {
        val Empty = HistoryOverview(0L, emptyList(), 0L)
    }
}

/** 界面读历史用的门面。不暴露 Room，也不在主站请求的锁里调用。 */
interface HistoryCatalog {
    val recording: StateFlow<RecordingState>

    suspend fun overview(): HistoryOverview

    suspend fun setRetained(id: Long, retained: Boolean)

    /** 正在进行或已保留的会话不删。删掉了返回 true。 */
    suspend fun deleteClosed(id: Long): Boolean

    suspend fun samples(sessionId: Long): List<HistorySample>
}

class StoreHistoryCatalog(
    private val store: HistoryStore,
    private val recorder: HistoryRecorder,
) : HistoryCatalog {
    override val recording: StateFlow<RecordingState> = recorder.state

    override suspend fun overview(): HistoryOverview =
        HistoryOverview(store.usageBytes(), store.sessions(), recorder.dropped)

    override suspend fun setRetained(id: Long, retained: Boolean) {
        store.setRetained(id, retained)
    }

    override suspend fun deleteClosed(id: Long): Boolean {
        val session = store.sessions().firstOrNull { it.id == id } ?: return false
        if (session.retained || session.endedAtMs == null) return false
        store.deleteSessions(listOf(id))
        return true
    }

    override suspend fun samples(sessionId: Long): List<HistorySample> = store.samples(sessionId)
}

object EmptyHistoryCatalog : HistoryCatalog {
    override val recording: StateFlow<RecordingState> = MutableStateFlow(RecordingState.IDLE)

    override suspend fun overview(): HistoryOverview = HistoryOverview.Empty

    override suspend fun setRetained(id: Long, retained: Boolean) = Unit

    override suspend fun deleteClosed(id: Long): Boolean = false

    override suspend fun samples(sessionId: Long): List<HistorySample> = emptyList()
}
