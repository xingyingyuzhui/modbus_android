package io.github.xingyingyuzhui.modbusmobile.application.history

import io.github.xingyingyuzhui.modbusmobile.application.ConnectionTarget
import io.github.xingyingyuzhui.modbusmobile.application.DefaultSessionManager
import io.github.xingyingyuzhui.modbusmobile.application.SerialSettings
import io.github.xingyingyuzhui.modbusmobile.core.model.Role
import io.github.xingyingyuzhui.modbusmobile.core.transport.MonotonicClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryRecorderTest {
    @Test
    fun offerReturnsBeforeTheStoreWrites() = runTest {
        val store = MemoryHistoryStore()
        store.pauseWrites = true
        val recorder = recorder(store, now = { 0L })
        recorder.onSessionStarted("MASTER", "DEMO", 1, 0)
        recorder.offer(1, ok("a", 0, 1.0))
        assertEquals(0, store.samples.size)
        assertEquals(0, store.sessions.size)
        recorder.close()
    }

    @Test
    fun snapshotsAreThrottledToFiveSecondsAndFailuresDoNotKeepTheOldNumber() = runTest {
        val store = MemoryHistoryStore()
        var now = 10L
        val recorder = recorder(store, now = { now })
        recorder.onSessionStarted("MASTER", "DEMO", 7, 0)
        runCurrent()
        recorder.offer(7, ok("a", 0, 1.0))
        recorder.offer(7, ok("a", 4_999, 2.0))
        recorder.offer(7, Observation("a", 5_000, SampleQuality.TIMEOUT, 2.0, "stale"))
        recorder.offer(7, Observation("a", 5_000, SampleQuality.OK, Double.NaN, null))
        runCurrent()
        val rows = store.samples
        assertEquals(listOf(0L, 5_000L), rows.map { it.sampleAtMs })
        assertEquals(1.0, rows[0].engineering)
        assertNull(rows[1].engineering)
        assertNull(rows[1].text)
        assertEquals(SampleQuality.TIMEOUT, rows[1].quality)
        assertEquals(7L, rows[0].epoch)
        assertEquals(10L, rows[0].recordedAtMs)
        now = 11L
        recorder.close()
    }

    @Test
    fun fullQueueDropsNewSamplesWithoutBlocking() = runTest {
        val store = MemoryHistoryStore()
        val recorder = recorder(store, now = { 0L }, queueLimit = 1)
        repeat(3) { recorder.offer(1, ok("a", it.toLong(), it.toDouble())) }
        assertEquals(2, recorder.dropped)
        recorder.close()
    }

    @Test
    fun capacityStopsRecordingAndLeavesTheOpenSessionAlone() = runTest {
        val store = MemoryHistoryStore(bytesPerSample = 100)
        val recorder = recorder(store, now = { 0L }, policy = HistoryPolicy(capacityBytes = 150))
        recorder.onSessionStarted("MASTER", "DEMO", 1, 0)
        runCurrent()
        recorder.offer(1, ok("a", 0, 1.0))
        recorder.offer(1, ok("a", 5_000, 2.0))
        runCurrent()
        assertEquals(RecordingState.STOPPED_STORAGE, recorder.state.value)
        assertEquals(2, store.samples.size)
        val droppedBefore = recorder.dropped
        recorder.offer(1, ok("a", 10_000, 3.0))
        runCurrent()
        assertEquals(2, store.samples.size)
        assertTrue(recorder.dropped > droppedBefore)
        recorder.close()
    }

    @Test
    fun diskFullStopsRecording() = runTest {
        val store = MemoryHistoryStore()
        val recorder = recorder(store, now = { 0L })
        recorder.onSessionStarted("MASTER", "USB", 1, 0)
        runCurrent()
        store.failWrites = true
        recorder.offer(1, ok("a", 0, 1.0))
        runCurrent()
        assertEquals(RecordingState.STOPPED_STORAGE, recorder.state.value)
        assertTrue(store.samples.isEmpty())
        recorder.close()
    }

    @Test
    fun oldUnkeptSessionsAreRemovedAndKeptOnesStay() = runTest {
        val store = MemoryHistoryStore()
        val old = store.openSession("MASTER", "DEMO", 1, 0)
        store.setRetained(store.openSession("MASTER", "DEMO", 2, 0).id, true)
        store.closeSession(old.id, 1)
        val now = 40L * 86_400_000L
        val recorder = recorder(store, now = { now }, policy = HistoryPolicy(retentionDays = 30, capacityBytes = 50_000_000))
        recorder.onSessionStarted("SLAVE", "LAB", 3, now)
        runCurrent()
        val left = store.sessions.map { it.epoch }
        assertEquals(listOf(2L, 3L), left)
        assertTrue(store.sessions.first { it.epoch == 2L }.retained)
        recorder.close()
    }

    @Test
    fun connectingPublishesASessionWithoutWaitingForTheQueue() = runTest {
        val store = MemoryHistoryStore()
        val recorder = recorder(store, now = { 5_000L })
        val session = DefaultSessionManager(
            scope = backgroundScope,
            clock = object : MonotonicClock {
                override fun nowNs(): Long = 0L
            },
            wallMs = { 5_000L },
            history = recorder,
        )
        session.connect(ConnectionTarget.Demo, SerialSettings(), Role.MASTER)
        runCurrent()
        assertEquals("DEMO", store.sessions.single().target)
        assertEquals("MASTER", store.sessions.single().role)
        session.disconnect()
        runCurrent()
        assertEquals(5_000L, store.sessions.single().endedAtMs)
        recorder.close()
    }

    private fun TestScope.recorder(
        store: MemoryHistoryStore,
        now: () -> Long,
        policy: HistoryPolicy = HistoryPolicy(),
        queueLimit: Int = HistoryPolicy.QUEUE_LIMIT,
    ) = HistoryRecorder(store, backgroundScope, { policy }, now, queueLimit)

    private fun ok(id: String, at: Long, value: Double) =
        Observation(id, at, SampleQuality.OK, value, null)
}

class MemoryHistoryStore(
    private val bytesPerSample: Long = 1,
) : HistoryStore {
    var failWrites = false
    var pauseWrites = false
    val sessions = mutableListOf<HistorySession>()
    val samples = mutableListOf<HistorySample>()
    private var nextId = 1L

    override suspend fun openSession(role: String, target: String, epoch: Long, startedAtMs: Long): HistorySession {
        if (failWrites) throw StorageFullException()
        val session = HistorySession(nextId++, role, target, epoch, startedAtMs, null, false)
        sessions += session
        return session
    }

    override suspend fun closeSession(id: Long, endedAtMs: Long) {
        val index = sessions.indexOfFirst { it.id == id }
        if (index >= 0) sessions[index] = sessions[index].copy(endedAtMs = endedAtMs)
    }

    override suspend fun closeDangling(endedAtMs: Long, startedBeforeMs: Long) {
        sessions.replaceAll { session ->
            if (session.endedAtMs == null && session.startedAtMs < startedBeforeMs) session.copy(endedAtMs = endedAtMs) else session
        }
    }

    override suspend fun insertSamples(samples: List<HistorySample>) {
        if (pauseWrites) suspendCancellableCoroutine<Unit> { }
        if (failWrites) throw StorageFullException()
        this.samples += samples
    }

    override suspend fun sessions(): List<HistorySession> = sessions.toList()

    override suspend fun samples(sessionId: Long): List<HistorySample> = samples.filter { it.sessionId == sessionId }

    override suspend fun usageBytes(): Long = samples.size * bytesPerSample

    override suspend fun deleteSessions(ids: List<Long>) {
        val drop = sessions.filter { it.id in ids && !it.retained }.map { it.id }.toSet()
        sessions.removeAll { it.id in drop }
        samples.removeAll { it.sessionId in drop }
    }

    override suspend fun setRetained(id: Long, retained: Boolean) {
        val index = sessions.indexOfFirst { it.id == id }
        if (index >= 0) sessions[index] = sessions[index].copy(retained = retained)
    }
}
