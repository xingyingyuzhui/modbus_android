package io.github.xingyingyuzhui.modbusmobile

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xingyingyuzhui.modbusmobile.application.SessionState
import io.github.xingyingyuzhui.modbusmobile.ui.LocalHistoryCatalog
import io.github.xingyingyuzhui.modbusmobile.ui.LocalSession
import io.github.xingyingyuzhui.modbusmobile.ui.LocalSlaveSetupStore
import io.github.xingyingyuzhui.modbusmobile.ui.LocalTrafficRecorder
import io.github.xingyingyuzhui.modbusmobile.ui.LocalPanelRuntime
import io.github.xingyingyuzhui.modbusmobile.ui.LocalProjectRepository
import io.github.xingyingyuzhui.modbusmobile.ui.LocalSettingsRepository
import io.github.xingyingyuzhui.modbusmobile.ui.ModbusApp
import io.github.xingyingyuzhui.modbusmobile.ui.theme.ModbusTheme

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val container = (application as ModbusMobileApp).container
        setContent {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()
            val sessionState by container.sessionManager.state.collectAsStateWithLifecycle()
            KeepScreenOn(settings.keepScreenOn && sessionState is SessionState.Running)
            CompositionLocalProvider(
                LocalSession provides container.sessionManager,
                LocalTrafficRecorder provides container.trafficRecorder,
                LocalSettingsRepository provides container.settingsRepository,
                LocalProjectRepository provides container.projectRepository,
                LocalPanelRuntime provides container.panelRuntime,
                LocalHistoryCatalog provides container.historyCatalog,
                LocalSlaveSetupStore provides container.slaveSetupStore,
            ) {
                ModbusTheme(settings) {
                    ModbusApp()
                }
            }
        }
    }

    @Composable
    private fun KeepScreenOn(enabled: Boolean) {
        DisposableEffect(enabled) {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }
}
