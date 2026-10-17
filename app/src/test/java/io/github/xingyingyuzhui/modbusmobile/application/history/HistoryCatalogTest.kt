package io.github.xingyingyuzhui.modbusmobile.application.history

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryCatalogTest {
    @Test
    fun deleteSkipsTheOpenSessionAndAnythingMarkedKept() = runTest {
        val store = MemStore(
            listOf(
                session(1, ended = null, retained = false),
                session(2, ended = 10, retained = true),
                session(3, ended = 20, retained = false),
            ),
        )
        val recorder = HistoryRecorder(store, this, { HistoryPolicy() }, { 0L })
        val catalog = StoreHistoryCatalog(store, recorder)
        assertFalse(catalog.deleteClosed(1))
        assertFalse(catalog.deleteClosed(2))
        assertTrue(catalog.deleteClosed(3))
        assertEquals(listOf(3L), store.deleted)
        assertEquals(listOf(1L, 2L), catalog.overview().sessions.map { it.id })
        catalog.setRetained(1, true)
        assertTrue(catalog.overview().sessions.first { it.id == 1L }.retained)
        recorder.close()
    }

    private fun session(id: Long, ended: Long?, retained: Boolean) =
        HistorySession(id, "MASTER", "DEMO", id, 0L, ended, retained)

    private class MemStore(sessions: List<HistorySession>) : HistoryStore {
        val rows = sessions.toMutableList()
        val deleted = mutableListOf<Long>()

        override suspend fun openSession(role: String, target: String, epoch: Long, startedAtMs: Long) =
            error("unused")

        override suspend fun closeSession(id: Long, endedAtMs: Long) = Unit
        override suspend fun closeDangling(endedAtMs: Long, startedBeforeMs: Long) = Unit
        override suspend fun insertSamples(samples: List<HistorySample>) = Unit
        override suspend fun sessions(): List<HistorySession> = rows.toList()
        override suspend fun samples(sessionId: Long): List<HistorySample> = emptyList()
        override suspend fun usageBytes(): Long = 0
        override suspend fun deleteSessions(ids: List<Long>) {
            deleted += ids
            rows.removeAll { it.id in ids && !it.retained }
        }

        override suspend fun setRetained(id: Long, retained: Boolean) {
            val index = rows.indexOfFirst { it.id == id }
            if (index >= 0) rows[index] = rows[index].copy(retained = retained)
        }
    }
}
