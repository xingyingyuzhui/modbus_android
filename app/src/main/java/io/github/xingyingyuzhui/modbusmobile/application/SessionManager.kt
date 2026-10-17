package io.github.xingyingyuzhui.modbusmobile.application

import io.github.xingyingyuzhui.modbusmobile.core.model.ModbusRequest
import io.github.xingyingyuzhui.modbusmobile.core.model.ModbusResult
import io.github.xingyingyuzhui.modbusmobile.core.model.Role
import io.github.xingyingyuzhui.modbusmobile.core.model.TrafficEvent
import io.github.xingyingyuzhui.modbusmobile.core.slave.SimulationEngine
import io.github.xingyingyuzhui.modbusmobile.core.slave.VirtualRegisterBank
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface SessionManager {
    val state: StateFlow<SessionState>
    val traffic: SharedFlow<TrafficEvent>
    val slaveBank: StateFlow<VirtualRegisterBank?>
    val usbDevices: StateFlow<List<UsbDeviceSummary>>
    val echoDetected: StateFlow<Boolean?>
    val serial: StateFlow<SerialSettings?>

    suspend fun connect(target: ConnectionTarget, serial: SerialSettings, role: Role)
    suspend fun disconnect()
    suspend fun execute(request: ModbusRequest): ModbusResult

    /**
     * 从站会话的模拟器（spec §5.3）；主站、未连接时为 null。规则由界面经 [SimulationEngine.setRules] 设置，
     * 断开时随会话一起停掉。
     */
    val simulation: StateFlow<SimulationEngine?> get() = NoSimulation

    /** 从站会话正在模拟的 Unit（按配置顺序）；主站、未连接时为空。 */
    val slaveUnits: StateFlow<List<Int>> get() = NoUnits

    /**
     * 改从站模拟的 Unit 集合（1..247，不重复，至少一个）。从站运行中会在两帧之间重建从站引擎，然后保存配置。
     * 不在从站会话里、参数不合法或保存失败时返回 false。保存失败时运行中的 Unit 已更新，界面应提示未落盘。
     */
    suspend fun setSlaveUnits(units: List<Int>): Boolean = false

    /** 把当前从站的 Unit、地址段、值和模拟规则保存下来（下次连接从站时按它装载）。没有从站会话时返回 false。 */
    fun saveSlaveSetup(): Boolean = false

    /** 本次会话的收发计数，连接时从 0 开始，断开后归零。见 [SessionCounters]。 */
    val counters: StateFlow<SessionCounters> get() = NoCounters

    /**
     * 这条请求将要发出的整帧字节（含 CRC），给写入确认弹窗做预览。
     * 和主站引擎发送时用同一个编码器，所以预览的就是实际会发出的字节；界面层不直接碰 core 的 RTU 编码。
     */
    fun previewFrame(request: ModbusRequest): ByteArray = encodeRequestFrame(request)

    fun refreshDevices() {}
}
