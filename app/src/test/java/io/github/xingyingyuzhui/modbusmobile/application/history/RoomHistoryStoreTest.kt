package io.github.xingyingyuzhui.modbusmobile.application.history

import androidx.room.Room
import io.github.xingyingyuzhui.modbusmobile.application.history.room.HistoryDatabase
import io.github.xingyingyuzhui.modbusmobile.application.history.room.RoomHistoryStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomHistoryStoreTest {
    private val database = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(),
        HistoryDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val store = RoomHistoryStore(database)

    @After
    fun close() {
        database.close()
    }

    @Test
    fun sessionAndSamplesRoundTripAndAKeptSessionSurvivesDelete() = runTest {
        val first = store.openSession("MASTER", "USB", 4, 1_000)
        store.insertSamples(
            listOf(
                HistorySample(first.id, "temp", 1_000, 1_100, 4, SampleQuality.OK, 21.5, null),
                HistorySample(first.id, "temp", 6_000, 6_100, 4, SampleQuality.TIMEOUT, null, null),
            ),
        )
        store.closeSession(first.id, 9_000)
        val kept = store.openSession("SLAVE", "LAB", 5, 2_000)
        store.setRetained(kept.id, true)
        store.insertSamples(listOf(HistorySample(kept.id, "coil", 2_000, 2_100, 5, SampleQuality.OK, null, "1")))
        store.deleteSessions(listOf(first.id, kept.id))

        assertEquals(listOf(kept.id), store.sessions().map { it.id })
        assertTrue(store.sessions().single().retained)
        assertEquals(1, store.samples(kept.id).size)
        assertEquals(0, store.samples(first.id).size)
        assertTrue(store.usageBytes() > 0)
    }

    @Test
    fun restartClosesOnlySessionsThatStartedBeforeThisProcess() = runTest {
        val previous = store.openSession("MASTER", "DEMO", 1, 10)
        val current = store.openSession("MASTER", "DEMO", 2, 50)
        store.closeDangling(endedAtMs = 40, startedBeforeMs = 40)
        assertEquals(40L, store.sessions().first { it.id == previous.id }.endedAtMs)
        assertNull(store.sessions().first { it.id == current.id }.endedAtMs)
    }
}
