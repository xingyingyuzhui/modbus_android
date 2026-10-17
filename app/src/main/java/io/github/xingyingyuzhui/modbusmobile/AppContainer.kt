package io.github.xingyingyuzhui.modbusmobile

import android.app.Application
import io.github.xingyingyuzhui.modbusmobile.application.DataStoreSettingsRepository
import io.github.xingyingyuzhui.modbusmobile.application.DefaultSessionManager
import io.github.xingyingyuzhui.modbusmobile.application.SessionManager
import io.github.xingyingyuzhui.modbusmobile.application.SettingsRepository
import io.github.xingyingyuzhui.modbusmobile.application.TrafficRecorder
import io.github.xingyingyuzhui.modbusmobile.application.history.HistoryPolicy
import io.github.xingyingyuzhui.modbusmobile.application.history.HistoryRecorder
import io.github.xingyingyuzhui.modbusmobile.application.history.room.RoomHistoryStore
import io.github.xingyingyuzhui.modbusmobile.core.transport.SystemMonotonicClock
import io.github.xingyingyuzhui.modbusmobile.usb.AndroidUsbStack
import io.github.xingyingyuzhui.modbusmobile.usb.UsbDeviceRepository
import io.github.xingyingyuzhui.modbusmobile.usb.UsbPermissionRequester
import io.github.xingyingyuzhui.modbusmobile.usb.UsbSessionBridge
import io.github.xingyingyuzhui.modbusmobile.usb.withLocalSerialLab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clock = SystemMonotonicClock
    val bridge = UsbSessionBridge()
    val repository = UsbDeviceRepository(app).also { it.start() }
    private val permissions = UsbPermissionRequester(app, repository)
    private val usb = withLocalSerialLab(AndroidUsbStack(app, repository, permissions, bridge), scope, clock)
    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(app, scope)
    private val processStartedAtMs = System.currentTimeMillis()
    private val historyStore = RoomHistoryStore(app)
    val historyRecorder = HistoryRecorder(
        store = historyStore,
        scope = scope,
        policy = {
            val settings = settingsRepository.settings.value
            HistoryPolicy(
                interval = settings.historyInterval,
                retentionDays = settings.historyRetentionDays,
                capacityBytes = settings.historyCapacityMb.toLong() * 1024L * 1024L,
            )
        },
        nowMs = { System.currentTimeMillis() },
    )
    val sessionManager: SessionManager = DefaultSessionManager(
        scope,
        clock,
        usb,
        slaveSetupStore = io.github.xingyingyuzhui.modbusmobile.application.slave.FileSlaveSetupStore(java.io.File(app.filesDir, "projects")),
        history = historyRecorder,
    )
    val trafficRecorder = TrafficRecorder(sessionManager, scope)
    val pointStore = io.github.xingyingyuzhui.modbusmobile.application.panel.PointStore()
    val sampleHistory = io.github.xingyingyuzhui.modbusmobile.application.panel.SampleHistory()
    val pollScheduler = io.github.xingyingyuzhui.modbusmobile.application.panel.PollScheduler(
        sessionManager,
        scope,
        pointStore,
        nowMs = { System.nanoTime() / 1_000_000L },
        history = sampleHistory,
        diskHistory = historyRecorder,
    )

    init {
        scope.launch {
            try {
                historyStore.closeDangling(processStartedAtMs, processStartedAtMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 上次会话没收口就留到下次。不挡住这次启动。
            }
        }
    }
    val panelRuntime = io.github.xingyingyuzhui.modbusmobile.application.panel.PanelRuntime(pointStore, pollScheduler, sampleHistory) { previewScope ->
        io.github.xingyingyuzhui.modbusmobile.application.panel.PointPreview(sessionManager, previewScope) { System.nanoTime() / 1_000_000L }
    }
    val projectRepository = io.github.xingyingyuzhui.modbusmobile.application.panel.ProjectRepository(java.io.File(app.filesDir, "projects"))
}
