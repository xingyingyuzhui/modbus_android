package io.github.xingyingyuzhui.modbusmobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.xingyingyuzhui.modbusmobile.application.ConnectionTarget
import io.github.xingyingyuzhui.modbusmobile.application.SerialSettings
import io.github.xingyingyuzhui.modbusmobile.application.SessionManager
import io.github.xingyingyuzhui.modbusmobile.application.SessionState
import io.github.xingyingyuzhui.modbusmobile.application.UiSettings
import io.github.xingyingyuzhui.modbusmobile.application.UsbDeviceSummary
import io.github.xingyingyuzhui.modbusmobile.core.model.ModbusRequest
import io.github.xingyingyuzhui.modbusmobile.core.model.ModbusResult
import io.github.xingyingyuzhui.modbusmobile.core.model.Role
import io.github.xingyingyuzhui.modbusmobile.core.model.TrafficEvent
import io.github.xingyingyuzhui.modbusmobile.core.slave.InMemoryRegisterBank
import io.github.xingyingyuzhui.modbusmobile.core.slave.SimulationEngine
import io.github.xingyingyuzhui.modbusmobile.core.slave.VirtualRegisterBank
import io.github.xingyingyuzhui.modbusmobile.core.transport.MonotonicClock
import io.github.xingyingyuzhui.modbusmobile.ui.theme.ModbusTheme
import io.github.xingyingyuzhui.modbusmobile.ui.workbench.SimulationRuleEditor
import io.github.xingyingyuzhui.modbusmobile.ui.workbench.SlaveViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Landscape has no bottom dock, but Android's gesture navigation still needs its safe area. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w780dp-h360dp-land")
class SimulationRuleInsetsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun landscapeFooterReservesTheGestureNavigationInsetWithoutABottomDock() {
        val session = InsetsSession()
        compose.setContent {
            val model = viewModel { SlaveViewModel(session) }
            LaunchedEffect(model) { model.openNewRule() }
            ModbusTheme(UiSettings()) {
                SimulationRuleEditor(model, navigationBars = WindowInsets(bottom = 24.dp))
            }
        }
        compose.waitForIdle()

        val root = compose.onRoot().getBoundsInRoot()
        listOf("save", "cancel").forEach { action ->
            val button = compose.onNodeWithTag("simulation_rule_$action", useUnmergedTree = true)
            button.assertIsDisplayed()
            val bounds = button.getBoundsInRoot()
            assertTrue("$action must stay above the 24 dp system gesture area", bounds.bottom <= root.bottom - 24.dp)
        }
    }
}

private class InsetsSession : SessionManager {
    private val bank = InMemoryRegisterBank()
    private val engine = SimulationEngine(bank, object : MonotonicClock {
        override fun nowNs() = 0L
    })
    override val state = MutableStateFlow<SessionState>(SessionState.Running(Role.SLAVE, ConnectionTarget.Usb(1, 0), 1))
    override val traffic = MutableSharedFlow<TrafficEvent>()
    override val slaveBank = MutableStateFlow<VirtualRegisterBank?>(bank)
    override val simulation = MutableStateFlow<SimulationEngine?>(engine)
    override val slaveUnits = MutableStateFlow(listOf(1))
    override val usbDevices = MutableStateFlow<List<UsbDeviceSummary>>(emptyList())
    override val echoDetected = MutableStateFlow<Boolean?>(null)
    override val serial = MutableStateFlow<SerialSettings?>(SerialSettings())
    override suspend fun connect(target: ConnectionTarget, serial: SerialSettings, role: Role) = Unit
    override suspend fun disconnect() = Unit
    override suspend fun execute(request: ModbusRequest): ModbusResult = ModbusResult.Disconnected
}
