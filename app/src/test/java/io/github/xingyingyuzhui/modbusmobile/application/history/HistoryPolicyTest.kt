package io.github.xingyingyuzhui.modbusmobile.application.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPolicyTest {
    private fun sample(at: Long, quality: SampleQuality = SampleQuality.OK, value: Double? = 1.0) =
        Observation("p", at, quality, value, null)

    @Test
    fun firstSampleIsKeptAndLaterSamplesWaitForTheInterval() {
        assertTrue(shouldRecord(null, sample(0), HistoryInterval.SEC_5))
        val first = sample(0)
        assertFalse(shouldRecord(first, sample(4_999), HistoryInterval.SEC_5))
        assertTrue(shouldRecord(first, sample(5_000), HistoryInterval.SEC_5))
    }

    @Test
    fun changeModeIgnoresTheClockAndKeepsAStableValue() {
        val first = sample(0, value = 3.0)
        assertFalse(shouldRecord(first, sample(1, value = 3.0), HistoryInterval.ON_CHANGE))
        assertTrue(shouldRecord(first, sample(1, value = 4.0), HistoryInterval.ON_CHANGE))
        assertTrue(shouldRecord(first, sample(1, quality = SampleQuality.TIMEOUT, value = 3.0), HistoryInterval.ON_CHANGE))
    }

    @Test
    fun retentionSkipsTheOpenSessionAndAnythingMarkedKept() {
        val open = session(1, started = 0, retained = false)
        val old = session(2, started = 0, retained = false)
        val kept = session(3, started = 0, retained = true)
        val recent = session(4, started = 50L * DAY, retained = false)
        val now = 40L * DAY
        assertEquals(
            listOf(2L),
            sessionsEligibleToDelete(listOf(open, old, kept, recent), now, 30, openSessionId = 1),
        )
        assertEquals(old, oldestDeletableSession(listOf(open, kept, old), openSessionId = 1))
        assertNull(oldestDeletableSession(listOf(open, kept), openSessionId = 1))
    }

    private fun session(id: Long, started: Long, retained: Boolean) = HistorySession(
        id = id,
        role = "MASTER",
        target = "DEMO",
        epoch = id,
        startedAtMs = started,
        endedAtMs = null,
        retained = retained,
    )

    private companion object {
        const val DAY = 86_400_000L
    }
}
