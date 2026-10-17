package io.github.xingyingyuzhui.modbusmobile.ui.workbench

import io.github.xingyingyuzhui.modbusmobile.core.model.RegisterArea
import io.github.xingyingyuzhui.modbusmobile.core.slave.SimulationRule
import io.github.xingyingyuzhui.modbusmobile.core.slave.SimulationTarget
import io.github.xingyingyuzhui.modbusmobile.core.slave.Waveform
import org.junit.Assert.*
import org.junit.Test

class SimulationRuleFormTest {
    private val hr = RegisterArea.HOLDING_REGISTER
    private fun draft() = RuleDraft.newFor(1, hr, 0)

    @Test fun allWaveformsRoundTripWithoutChangingTheRule() {
        for (kind in WaveKind.entries) {
            val (rule, checked) = draft().withKind(kind).build { "rule" }
            assertNotNull("$kind: ${checked.errors}", rule)
            assertEquals(rule, RuleDraft.from(rule!!).build { error("Existing rule needs no new id") }.first)
        }
    }

    @Test fun rejectsNonFiniteClampBoundsAndInvalidUnitWithoutThrowing() {
        for (text in listOf("NaN", "Infinity", "-Infinity")) {
            for (input in listOf(draft().copy(clampMin = text), draft().copy(clampMax = text))) {
                val (rule, checked) = input.build { "rule" }
                assertNull(rule)
                assertTrue(checked.errors.containsKey(RuleField.CLAMP))
            }
        }
        for (unit in listOf(0, -1, 248)) {
            val (rule, checked) = draft().copy(unit = unit).build { "rule" }
            assertNull(rule)
            assertTrue(checked.errors.containsKey(RuleField.UNIT))
        }
    }

    @Test fun rejectsAddressSpanAndRefreshAndWaveformErrors() {
        val invalid = listOf(
            draft().copy(address = "65535", count = "2") to RuleField.COUNT,
            draft().copy(refreshMs = "49") to RuleField.REFRESH,
            draft().copy(noise = "NaN") to RuleField.NOISE,
            draft().copy(c = "0.099") to RuleField.C,
            draft().withKind(WaveKind.SQUARE).copy(d = "101") to RuleField.D,
            draft().withKind(WaveKind.SEQUENCE).copy(sequence = "0,NaN") to RuleField.SEQUENCE,
            draft().withKind(WaveKind.RAMP).copy(c = "10", d = "0") to RuleField.D,
            draft().withKind(WaveKind.RANDOM).copy(a = "10", b = "0") to RuleField.B,
        )
        for ((input, field) in invalid) {
            val (rule, checked) = input.build { "rule" }
            assertNull("$input", rule)
            assertTrue("$field in ${checked.errors}", checked.errors.containsKey(field))
        }
    }

    @Test fun rejectsFiniteValuesThatWouldOverflowDerivedWaveformCalculations() {
        val inputs = listOf(
            draft().copy(d = "1e308"),
            draft().withKind(WaveKind.RAMP).copy(b = "1e308"),
            draft().withKind(WaveKind.RANDOM).copy(a = "-1e308", b = "1e308"),
            draft().withKind(WaveKind.SEQUENCE).copy(sequence = "1e308"),
            draft().copy(noise = "1e308"),
        )
        inputs.forEach { assertNull(it.build { "r" }.first) }
        val boundary = draft().copy(a = "1e12", b = "1e12", d = "1e12").build { "r" }.first!!
        assertTrue(previewSamples(boundary).isNotEmpty())
    }

    @Test fun previewDecimationKeepsRefreshTimesAndStepInSync() {
        val rule = SimulationRule("r", SimulationTarget(1, hr, 0), Waveform.Ramp(0.0, 1.0, 0.0, 65535.0), refreshMs = 300)
        val samples = previewSamples(rule, windowMs = 10_000, maxPoints = 7)
        assertTrue(samples.size <= 7)
        assertTrue(samples.size > 1)
        for ((time, raw) in samples) {
            assertEquals(0L, time % 300)
            assertEquals((time / 300).toInt(), raw)
        }
        val sequence = rule.copy(waveform = Waveform.Sequence(listOf(10.0, 20.0, 30.0)))
        for ((time, raw) in previewSamples(sequence, 10_000, 7)) {
            assertEquals(listOf(10, 20, 30)[((time / 300) % 3).toInt()], raw)
        }
    }

    @Test fun previewHandlesEmptyAndSinglePointLimits() {
        val rule = draft().build { "r" }.first!!
        assertTrue(previewSamples(rule, maxPoints = 0).isEmpty())
        assertTrue(previewSamples(rule, windowMs = -1).isEmpty())
        assertEquals(listOf(0L), previewSamples(rule, maxPoints = 1).map { it.first })
        assertEquals(listOf(0L), previewSamples(rule, windowMs = 0).map { it.first })
        assertEquals(previewSamples(rule.withRandom()), previewSamples(rule.withRandom()))
    }

    private fun SimulationRule.withRandom() = copy(waveform = Waveform.RandomUniform(0.0, 100.0))
}
