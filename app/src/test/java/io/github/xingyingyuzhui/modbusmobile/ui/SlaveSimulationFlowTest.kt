package io.github.xingyingyuzhui.modbusmobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.xingyingyuzhui.modbusmobile.application.ConnectionTarget
import io.github.xingyingyuzhui.modbusmobile.application.MemorySettingsRepository
import io.github.xingyingyuzhui.modbusmobile.application.SerialSettings
import io.github.xingyingyuzhui.modbusmobile.application.SessionManager
import io.github.xingyingyuzhui.modbusmobile.application.SessionState
import io.github.xingyingyuzhui.modbusmobile.application.ThemeMode
import io.github.xingyingyuzhui.modbusmobile.application.UiSettings
import io.github.xingyingyuzhui.modbusmobile.application.UsbDeviceSummary
import io.github.xingyingyuzhui.modbusmobile.core.model.ModbusRequest
import io.github.xingyingyuzhui.modbusmobile.core.model.ModbusResult
import io.github.xingyingyuzhui.modbusmobile.core.model.RegisterArea
import io.github.xingyingyuzhui.modbusmobile.core.model.Role
import io.github.xingyingyuzhui.modbusmobile.core.model.TrafficEvent
import io.github.xingyingyuzhui.modbusmobile.core.slave.InMemoryRegisterBank
import io.github.xingyingyuzhui.modbusmobile.core.slave.RuleStatus
import io.github.xingyingyuzhui.modbusmobile.core.slave.SimulationEngine
import io.github.xingyingyuzhui.modbusmobile.core.slave.SimulationRule
import io.github.xingyingyuzhui.modbusmobile.core.slave.SimulationTarget
import io.github.xingyingyuzhui.modbusmobile.core.slave.VirtualRegisterBank
import io.github.xingyingyuzhui.modbusmobile.core.slave.Waveform
import io.github.xingyingyuzhui.modbusmobile.core.transport.MonotonicClock
import io.github.xingyingyuzhui.modbusmobile.ui.shell.Destination
import io.github.xingyingyuzhui.modbusmobile.ui.shell.DockTag
import io.github.xingyingyuzhui.modbusmobile.ui.theme.ModbusTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the real workbench and rule engine together, without starting its periodic loop. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h780dp")
class SlaveSimulationFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val bank = InMemoryRegisterBank().apply {
        repeat(4) { putWord(1, RegisterArea.HOLDING_REGISTER, it, 100 + it) }
    }
    private val engine = SimulationEngine(bank, object : MonotonicClock {
        override fun nowNs() = 0L
    })
    private val session = SimulationSession(bank, engine)

    private fun show(fontScale: Float = 1f, rules: List<SimulationRule> = emptyList()) {
        engine.setRules(rules)
        val settings = UiSettings(theme = ThemeMode.DARK)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalSession provides session,
                LocalSettingsRepository provides MemorySettingsRepository(settings),
                LocalDensity provides Density(density.density, fontScale),
            ) {
                ModbusTheme(settings) { ModbusApp(startRoute = Destination.Workbench.route) }
            }
        }
        compose.waitForIdle()
    }

    private fun node(suffix: String) = compose.onNodeWithTag("simulation_rule_$suffix", useUnmergedTree = true)

    private fun openNew() {
        revealListControl("add")
        node("add").performClick()
        compose.waitForIdle()
        node("editor").assertIsDisplayed()
    }

    private fun type(suffix: String, text: String) {
        node(suffix).performScrollTo()
        compose.onNode(
            hasSetTextAction() and hasAnyAncestor(hasTestTag("simulation_rule_$suffix")),
            useUnmergedTree = true,
        ).performTextReplacement(text)
        compose.waitForIdle()
    }

    private fun click(suffix: String) {
        node(suffix).performClick()
        compose.waitForIdle()
    }

    /** Scroll the actual page past the floating dock before issuing a pointer click. */
    private fun revealListControl(suffix: String) {
        val target = node(suffix)
        target.performScrollTo()
        compose.waitForIdle()
        target.assertIsDisplayed()
        val dock = compose.onAllNodesWithTag(DockTag).fetchSemanticsNodes().singleOrNull()?.boundsInRoot ?: return
        val bounds = target.fetchSemanticsNode().boundsInRoot
        val overlap = bounds.bottom - dock.top
        if (overlap > 0f) {
            // performScrollTo knows the scroll viewport, but the dock overlays its bottom.
            // The page's dockClearance padding permits this extra user scroll.
            compose.onNode(
                hasScrollAction() and hasAnyDescendant(hasTestTag("simulation_rule_$suffix")),
                useUnmergedTree = true,
            ).performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(0f, overlap + 8f) }
            compose.waitForIdle()
        }
        target.assertIsDisplayed()
        assertTrue("$suffix must be fully above the dock before clicking", target.fetchSemanticsNode().boundsInRoot.bottom <= dock.top)
    }

    private fun sample(id: String = "saved") = SimulationRule(
        id = id,
        target = SimulationTarget(1, RegisterArea.HOLDING_REGISTER, 0),
        waveform = Waveform.Constant(42.0),
    )

    @Test
    fun newRuleCanBeSavedPausedResumedAndDeleted() {
        show()
        openNew()
        type("address", "1")
        click("save")
        node("editor").assertDoesNotExist()
        val saved = engine.states.value.single()
        assertEquals(1, saved.rule.target.address)
        assertEquals(RuleStatus.RUNNING, saved.status)
        assertEquals(1, session.saves)
        revealListControl(saved.rule.id)
        revealListControl("toggle_${saved.rule.id}")
        click("toggle_${saved.rule.id}")
        assertEquals(RuleStatus.PAUSED, engine.states.value.single().status)
        revealListControl("toggle_${saved.rule.id}")
        click("toggle_${saved.rule.id}")
        assertEquals(RuleStatus.RUNNING, engine.states.value.single().status)
        // Runtime pause/resume must not replace the saved startup configuration.
        assertEquals(1, session.saves)
        revealListControl(saved.rule.id)
        click(saved.rule.id)
        click("delete_${saved.rule.id}")
        node("delete_confirm").assertIsDisplayed()
        assertEquals(1, engine.states.value.size)
        click("delete_confirm")
        assertTrue(engine.states.value.isEmpty())
        assertEquals(2, session.saves)
        node("editor").assertDoesNotExist()
    }

    @Test
    fun cancelAndBackProtectModifiedDraftUntilDiscardIsConfirmed() {
        val original = sample()
        show(rules = listOf(original))
        revealListControl(original.id)
        click(original.id)
        type("a", "77")
        click("cancel")
        node("discard").assertIsDisplayed()
        click("keep_editing")
        node("editor").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        node("discard").assertIsDisplayed()
        click("discard")
        node("editor").assertDoesNotExist()
        assertEquals(original, engine.states.value.single().rule)
        assertEquals(0, session.saves)
    }

    @Test
    fun unchangedNewDraftClosesWithoutDiscardConfirmation() {
        show()
        openNew()
        click("cancel")
        node("editor").assertDoesNotExist()
        node("discard").assertDoesNotExist()
        assertTrue(engine.states.value.isEmpty())
        assertEquals(0, session.saves)
    }

    @Test
    fun pauseAllAndResumeAllAffectEveryListedRule() {
        show(rules = listOf(sample("one"), sample("two")))
        revealListControl("pause_all")
        node("pause_all").performClick()
        compose.waitForIdle()
        assertTrue(engine.states.value.all { it.status == RuleStatus.PAUSED })
        revealListControl("resume_all")
        node("resume_all").performClick()
        compose.waitForIdle()
        assertTrue(engine.states.value.all { it.status == RuleStatus.RUNNING })
        assertEquals(0, session.saves)
        compose.onRoot().captureRoboImage()
    }

    @Test
    @Config(qualifiers = "w320dp-h780dp")
    fun narrowScreenWithLargeTextKeepsEditorActionsAboveTheDock() {
        show(fontScale = 1.3f)
        openNew()
        assertEditorActionsUsable(checkDock = true)
        type("refresh", "750")
        assertEditorActionsUsable(checkDock = true)
        compose.onRoot().captureRoboImage()
        click("save")
        assertEquals(750L, engine.states.value.single().rule.refreshMs)
    }

    @Test
    @Config(qualifiers = "w780dp-h360dp-land")
    fun landscapeKeepsActionsVisibleWhileTheFormScrolls() {
        show()
        openNew()
        assertEditorActionsUsable(checkDock = false)
        type("clamp_max", "60000")
        assertEditorActionsUsable(checkDock = false)
        compose.onRoot().captureRoboImage()
        click("save")
        assertEquals(60000.0, engine.states.value.single().rule.clampMax, 0.0)
    }

    private fun assertEditorActionsUsable(checkDock: Boolean) {
        val root = compose.onRoot().getBoundsInRoot()
        listOf("save", "cancel").forEach { suffix ->
            node(suffix).assertIsDisplayed()
            val bounds = node(suffix).getBoundsInRoot()
            assertTrue("$suffix needs a 48 dp tall target", bounds.height.value >= 48f)
            assertTrue("$suffix needs a 48 dp wide target", bounds.width.value >= 48f)
            assertTrue("$suffix must fit horizontally", bounds.left >= root.left && bounds.right <= root.right)
            assertTrue("$suffix must fit vertically", bounds.top >= root.top && bounds.bottom <= root.bottom)
            if (checkDock) {
                val dock = compose.onNodeWithTag(DockTag).getBoundsInRoot()
                assertTrue("$suffix must stay above the bottom navigation", bounds.bottom <= dock.top)
            }
        }
        val save = node("save").getBoundsInRoot()
        val cancel = node("cancel").getBoundsInRoot()
        assertTrue("Editor actions must not overlap", save.left >= cancel.right || cancel.left >= save.right || save.top >= cancel.bottom || cancel.top >= save.bottom)
    }
}

private class SimulationSession(bank: VirtualRegisterBank, engine: SimulationEngine) : SessionManager {
    override val state = MutableStateFlow<SessionState>(SessionState.Running(Role.SLAVE, ConnectionTarget.Usb(1, 0), 1))
    override val traffic = MutableSharedFlow<TrafficEvent>(replay = 1)
    override val slaveBank = MutableStateFlow<VirtualRegisterBank?>(bank)
    override val simulation = MutableStateFlow<SimulationEngine?>(engine)
    override val slaveUnits = MutableStateFlow(listOf(1))
    override val usbDevices = MutableStateFlow<List<UsbDeviceSummary>>(emptyList())
    override val echoDetected = MutableStateFlow<Boolean?>(null)
    override val serial = MutableStateFlow<SerialSettings?>(SerialSettings())
    var saves = 0

    override fun saveSlaveSetup(): Boolean {
        saves += 1
        return true
    }

    override suspend fun connect(target: ConnectionTarget, serial: SerialSettings, role: Role) = Unit
    override suspend fun disconnect() = Unit
    override suspend fun execute(request: ModbusRequest): ModbusResult = ModbusResult.Disconnected
}
