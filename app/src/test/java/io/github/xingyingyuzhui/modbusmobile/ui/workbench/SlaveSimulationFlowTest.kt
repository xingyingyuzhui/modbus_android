@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.xingyingyuzhui.modbusmobile.ui.workbench

import io.github.xingyingyuzhui.modbusmobile.application.*
import io.github.xingyingyuzhui.modbusmobile.core.model.*
import io.github.xingyingyuzhui.modbusmobile.core.slave.*
import io.github.xingyingyuzhui.modbusmobile.core.transport.MonotonicClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class SlaveSimulationFlowTest {
    private class Session : SessionManager {
        val bank = InMemoryRegisterBank()
        val engine = SimulationEngine(bank, object : MonotonicClock { override fun nowNs() = 0L })
        var saves = 0
        var saveResult = true
        var savedRules: List<SimulationRule> = emptyList()
        var writes = 0
        override val state = MutableStateFlow<SessionState>(SessionState.Running(Role.SLAVE, ConnectionTarget.Demo, 1))
        override val traffic = MutableSharedFlow<TrafficEvent>()
        override val slaveBank = MutableStateFlow<VirtualRegisterBank?>(bank)
        override val simulation = MutableStateFlow<SimulationEngine?>(engine)
        override val slaveUnits = MutableStateFlow(listOf(1, 2))
        override val usbDevices = MutableStateFlow<List<UsbDeviceSummary>>(emptyList())
        override val echoDetected = MutableStateFlow<Boolean?>(null)
        override val serial = MutableStateFlow<SerialSettings?>(null)
        override suspend fun connect(target: ConnectionTarget, serial: SerialSettings, role: Role) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun execute(request: ModbusRequest): ModbusResult { writes++; return ModbusResult.Disconnected }
        override fun saveSlaveSetup(): Boolean {
            saves++
            if (saveResult) savedRules = engine.states.value.map { it.rule }
            return saveResult
        }
    }

    private fun withModel(test: suspend TestScope.(Session, SlaveViewModel) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val session = Session()
            var nowNs = 100L
            val model = SlaveViewModel(session, monotonicNs = { nowNs++ }, clock = { 0L })
            runCurrent()
            test(session, model)
            assertEquals("Rule editing must never send a Modbus request", 0, session.writes)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun savesNewRuleReplacesItAndDeletesIt() = withModel { session, model ->
        model.openNewRule()
        model.updateRule { withKind(WaveKind.CONSTANT).copy(a = "42") }
        model.saveRule()
        assertNull(model.ruleDraft)
        assertFalse(model.saveFailed)
        val rule = session.engine.states.value.single().rule
        assertEquals(Waveform.Constant(42.0), rule.waveform)
        assertEquals(listOf(rule), session.savedRules)
        model.openRule(rule.id)
        model.updateRule { copy(a = "43") }
        model.saveRule()
        assertEquals(1, session.engine.states.value.size)
        assertEquals(Waveform.Constant(43.0), session.engine.states.value.single().rule.waveform)
        model.openRule(rule.id)
        model.deleteRule()
        assertNull(model.ruleDraft)
        assertTrue(session.engine.states.value.isEmpty())
        assertTrue(session.savedRules.isEmpty())
        assertEquals(3, session.saves)
    }

    @Test fun consecutiveSavesAndDeletesUseLatestEngineRules() = withModel { session, model ->
        model.openNewRule()
        model.saveRule()
        val firstId = session.engine.states.value.single().rule.id
        model.openNewRule()
        model.updateRule { copy(address = "1") }
        model.saveRule()
        assertEquals(2, session.engine.states.value.size)
        model.openRule(firstId)
        model.deleteRule()
        assertEquals(1, session.engine.states.value.size)
        assertEquals(1, session.engine.states.value.single().rule.target.address)
    }

    @Test fun validationAndRemovedUnitKeepDraftAndDoNotSave() = withModel { session, model ->
        model.openNewRule()
        model.updateRule { copy(clampMin = "NaN") }
        model.saveRule()
        assertTrue(model.ruleDraft!!.errors.containsKey(RuleField.CLAMP))
        model.updateRule { copy(clampMin = "0", unit = 2) }
        session.slaveUnits.value = listOf(1)
        model.saveRule()
        assertTrue(model.ruleDraft!!.errors.containsKey(RuleField.UNIT))
        assertEquals(0, session.saves)
        assertTrue(session.engine.states.value.isEmpty())
    }

    @Test fun failurePreservesDraftAndRetryKeepsTheSameRuleId() = withModel { session, model ->
        model.openNewRule()
        model.updateRule { copy(a = "123") }
        session.saveResult = false
        model.saveRule()
        assertTrue(model.saveFailed)
        val id = model.ruleDraft!!.id
        assertNotNull(id)
        model.saveRule()
        assertEquals(id, session.engine.states.value.single().rule.id)
        session.saveResult = true
        model.saveRule()
        assertNull(model.ruleDraft)
        assertFalse(model.saveFailed)
        model.openRule(id!!)
        session.saveResult = false
        model.deleteRule()
        assertNotNull(model.ruleDraft)
        assertTrue(model.saveFailed)
        session.saveResult = true
        model.deleteRule()
        assertNull(model.ruleDraft)
        assertFalse(model.saveFailed)
    }

    @Test fun draftChangesRequireConfirmationButValidationErrorsDoNotCountAsChanges() = withModel { _, model ->
        model.openNewRule()
        assertFalse(model.ruleDirty)
        model.cancelRule()
        assertNull(model.ruleDraft)
        model.openNewRule()
        model.updateRule { copy(errors = mapOf(RuleField.A to io.github.xingyingyuzhui.modbusmobile.ui.UiText.Of(io.github.xingyingyuzhui.modbusmobile.R.string.rule_err_number))) }
        assertFalse(model.ruleDirty)
        model.updateRule { copy(a = "200") }
        assertTrue(model.ruleDirty)
        model.cancelRule()
        assertTrue(model.confirmDiscardRule)
        assertNotNull(model.ruleDraft)
        model.keepEditingRule()
        assertFalse(model.confirmDiscardRule)
        assertEquals("200", model.ruleDraft!!.a)
        model.cancelRule()
        model.discardRule()
        assertNull(model.ruleDraft)
        assertFalse(model.ruleDirty)
        assertFalse(model.confirmDiscardRule)
    }

    @Test fun saveRunsNowWhileDisabledStartupRemainsPausedOnReload() = withModel { session, model ->
        model.openNewRule()
        model.updateRule { copy(enabled = false) }
        model.saveRule()
        val state = session.engine.states.value.single()
        assertEquals(RuleStatus.RUNNING, state.status)
        assertFalse(state.rule.enabled)
        assertEquals(listOf(state.rule), session.savedRules)
        val reloaded = SimulationEngine(session.bank, object : MonotonicClock { override fun nowNs() = 0L })
        reloaded.setRules(listOf(state.rule))
        assertEquals(RuleStatus.PAUSED, reloaded.states.value.single().status)
        session.engine.pause(state.rule.id)
        model.openRule(state.rule.id)
        model.saveRule()
        assertEquals(RuleStatus.RUNNING, session.engine.states.value.single().status)
        assertFalse(session.engine.states.value.single().rule.enabled)
    }

    @Test fun pauseAndResumeAreRuntimeOnlyAndWorkBeforeUiCollection() = withModel { session, model ->
        model.openNewRule()
        model.saveRule()
        val id = session.engine.states.value.single().rule.id
        val saved = session.saves
        model.toggleRule(id)
        assertEquals(RuleStatus.PAUSED, session.engine.states.value.single().status)
        model.toggleRule(id)
        assertEquals(RuleStatus.RUNNING, session.engine.states.value.single().status)
        model.pauseAllRules()
        assertEquals(RuleStatus.PAUSED, session.engine.states.value.single().status)
        model.resumeAllRules()
        assertEquals(RuleStatus.RUNNING, session.engine.states.value.single().status)
        assertTrue(session.engine.states.value.single().rule.enabled)
        assertEquals(saved, session.saves)
    }
}
