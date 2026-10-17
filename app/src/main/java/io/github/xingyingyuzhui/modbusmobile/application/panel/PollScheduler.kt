package io.github.xingyingyuzhui.modbusmobile.application.panel

import io.github.xingyingyuzhui.modbusmobile.application.SessionManager
import io.github.xingyingyuzhui.modbusmobile.application.history.HistoryRecorder
import io.github.xingyingyuzhui.modbusmobile.application.history.Observation
import io.github.xingyingyuzhui.modbusmobile.application.history.SampleQuality
import io.github.xingyingyuzhui.modbusmobile.application.SessionState
import io.github.xingyingyuzhui.modbusmobile.core.model.ModbusResponse
import io.github.xingyingyuzhui.modbusmobile.core.model.ModbusResult
import io.github.xingyingyuzhui.modbusmobile.core.model.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** 轮询统计：[configuredMs] = 最快一组的设定周期；[actualMs] = 它最近两次发送起点的实际间隔（还没有两次时为 null）。 */
data class PollStats(val configuredMs: Long, val actualMs: Long?)

/**
 * 面板轮询。一个协程、一次一个请求：按每组的到期时间依次发 [ReadGroup]，结果写进 [PointStore]。
 *
 * - **与手动请求交错**：`MasterEngine.execute` 内部是公平的互斥锁，手动请求（工作台）最多等当前这一次读，
 *   调度器两次读之间不持有任何锁。没有“严格优先 / 取消排队中的轮询”（那需要改 core，见 panel-plan §2）。
 * - **只在页面可见且是主站会话时发请求**：[setActive] 由界面按生命周期切换；换连接（epoch 变化）或断开时清空 [PointStore]。
 * - **不追赶**：某次读耗时超过周期，下一次从“现在”算，不连发补课。
 * - **出错降频**：设备返回异常码 / 无效响应的组，下一次至少隔 [ERROR_BACKOFF_MS]，避免对不存在的地址狂发请求。
 */
class PollScheduler(
    private val session: SessionManager,
    scope: CoroutineScope,
    private val store: PointStore,
    private val nowMs: () -> Long,
    private val maxGap: Int = 0,
    private val wallMs: () -> Long = System::currentTimeMillis,
    /** 曲线用的样本历史（只记被 track 登记过的点位）；为空 = 不记录。 */
    private val history: SampleHistory? = null,
    /** 落库记录。只入队，不在这次轮询里等待磁盘。 */
    private val diskHistory: HistoryRecorder? = null,
) {
    private val points = MutableStateFlow<List<PointDefinition>>(emptyList())
    private val active = MutableStateFlow(false)
    private val _paused = MutableStateFlow(false)

    /** 用户点了“暂停”：不再发请求，值保持；与页面是否可见无关（回到页面仍是暂停）。 */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()
    private val _stats = MutableStateFlow<PollStats?>(null)
    val stats: StateFlow<PollStats?> = _stats.asStateFlow()

    /** 设置要轮询的点位。内容没变不会重置已读到的值。 */
    fun setPoints(value: List<PointDefinition>) {
        points.value = value
    }

    fun setActive(value: Boolean) {
        active.value = value
    }

    fun setPaused(value: Boolean) {
        _paused.value = value
    }

    private class GroupState(val group: ReadGroup, var nextDue: Long, var lastStart: Long? = null)

    init {
        val epochs = session.state
            .map { (it as? SessionState.Running)?.takeIf { s -> s.role == Role.MASTER }?.epoch }
            .distinctUntilChanged()
        scope.launch {
            combine(points, epochs) { p, e -> p to e }.collectLatest { (pts, epoch) ->
                store.reset(pts)
                if (epoch == null || pts.isEmpty()) return@collectLatest
                combine(active, _paused) { a, p -> a && !p }.distinctUntilChanged().collectLatest { on -> if (on) run(pts) }
            }
        }
    }

    private suspend fun run(pts: List<PointDefinition>) {
        val groups = ReadPlan.build(pts, maxGap).map { GroupState(it, nowMs()) }
        if (groups.isEmpty()) return
        val fastest = groups.minOf { it.group.pollMs }
        try {
            _stats.value = PollStats(fastest.toLong(), null)
            while (coroutineContext.isActive) {
                val next = groups.minBy { it.nextDue }
                val wait = next.nextDue - nowMs()
                if (wait > 0) delay(wait)
                val start = nowMs()
                next.lastStart?.let {
                    if (next.group.pollMs == fastest) {
                        _stats.value = PollStats(fastest.toLong(), start - it)
                    }
                }
                next.lastStart = start
                val result = session.execute(next.group.request())
                val backoff = handle(next, result)
                val period = next.group.pollMs.toLong()
                next.nextDue = maxOf(start + maxOf(period, backoff), nowMs())
                if (result == ModbusResult.Disconnected) delay(DISCONNECTED_RETRY_MS)
            }
        } finally {
            _stats.value = null
        }
    }

    /** 把一次读取的结果写进存储；返回这一组下一次至少要隔多久（0 = 按周期）。 */
    private fun handle(state: GroupState, result: ModbusResult): Long {
        val group = state.group
        val now = nowMs()
        var backoff = 0L
        val updates = HashMap<String, PointSnapshot>()
        fun fail(error: PointError) {
            for (m in group.members) {
                val prev = store.get(m.point.id) ?: PointSnapshot(m.point.id)
                val failures = prev.failures + 1
                val quality = when {
                    error.kind == ErrorKind.TIMEOUT && failures >= TIMEOUT_STREAK -> Quality.TIMEOUT
                    error.kind == ErrorKind.TIMEOUT || error.kind == ErrorKind.UNKNOWN_OUTCOME || error.kind == ErrorKind.DISCONNECTED ->
                        if (prev.raw != null) Quality.STALE else Quality.NO_DATA
                    else -> Quality.ERROR
                }
                updates[m.point.id] = prev.copy(quality = quality, failures = failures, error = error)
            }
        }
        when (result) {
            is ModbusResult.Success -> {
                val response = result.response
                val ok = when {
                    group.isBit && response is ModbusResponse.ReadBits && response.values.size >= group.quantity -> true
                    !group.isBit && response is ModbusResponse.ReadRegisters && response.values.size == group.quantity -> true
                    else -> false
                }
                if (!ok) {
                    fail(PointError(ErrorKind.INVALID_RESPONSE, detail = "响应长度与请求不符"))
                    backoff = ERROR_BACKOFF_MS
                } else {
                    for (m in group.members) updates[m.point.id] = snapshotFor(m, response, now)
                }
            }
            is ModbusResult.Exception -> {
                fail(PointError(ErrorKind.EXCEPTION, code = result.code.code))
                backoff = ERROR_BACKOFF_MS
            }
            ModbusResult.Timeout -> fail(PointError(ErrorKind.TIMEOUT))
            ModbusResult.UnknownOutcome -> fail(PointError(ErrorKind.UNKNOWN_OUTCOME))
            ModbusResult.Disconnected -> fail(PointError(ErrorKind.DISCONNECTED))
            is ModbusResult.InvalidResponse -> {
                fail(PointError(ErrorKind.INVALID_RESPONSE, detail = result.detail))
                backoff = ERROR_BACKOFF_MS
            }
        }
        store.apply(updates)
        recordHistory(updates)
        offerDisk(updates)
        return backoff
    }

    private fun offerDisk(updates: Map<String, PointSnapshot>) {
        val disk = diskHistory ?: return
        val running = session.state.value as? SessionState.Running ?: return
        if (running.role != Role.MASTER) return
        val wall = wallMs()
        for ((id, snap) in updates) {
            val quality = when (snap.quality) {
                Quality.OK -> SampleQuality.OK
                Quality.STALE -> SampleQuality.STALE
                Quality.TIMEOUT -> SampleQuality.TIMEOUT
                Quality.ERROR -> SampleQuality.ERROR
                Quality.NO_DATA -> continue
            }
            disk.offer(
                running.epoch,
                Observation(
                    pointId = id,
                    sampleAtMs = snap.updatedWallMs ?: wall,
                    quality = quality,
                    engineering = if (snap.quality == Quality.OK) snap.engineering else null,
                    text = if (snap.quality == Quality.OK) snap.text else null,
                ),
            )
        }
    }

    private fun recordHistory(updates: Map<String, PointSnapshot>) {
        val h = history ?: return
        val wall = wallMs()
        for ((id, snap) in updates) {
            when {
                snap.quality == Quality.OK && snap.engineering != null -> h.record(id, snap.updatedWallMs ?: wall, snap.engineering)
                snap.quality == Quality.OK || snap.quality == Quality.NO_DATA -> Unit
                else -> h.recordFailure(id, wall)
            }
        }
    }

    private fun snapshotFor(m: ReadGroup.Member, response: ModbusResponse, now: Long): PointSnapshot {
        val p = m.point
        val raw: RawValue = when (response) {
            is ModbusResponse.ReadBits -> ValueCodec.decodeBit(response.values[m.offset])
            is ModbusResponse.ReadRegisters -> {
                val words = IntArray(p.quantity) { response.values[m.offset + it] }
                ValueCodec.decode(p.type, words, p.order, p.bit, p.asciiRegisters)
            }
            else -> RawValue.Invalid("unexpected response")
        }
        if (raw is RawValue.Invalid) {
            val prev = store.get(p.id) ?: PointSnapshot(p.id)
            return prev.copy(quality = Quality.ERROR, failures = prev.failures + 1, error = PointError(ErrorKind.BAD_VALUE, detail = raw.reason))
        }
        if (raw is RawValue.Text) {
            return PointSnapshot(p.id, Quality.OK, raw, null, raw.value, ThresholdLevel.NORMAL, now, wallMs())
        }
        val eng = ValueCodec.toEngineering(raw, p.conversion)
        if (eng == null) {
            val prev = store.get(p.id) ?: PointSnapshot(p.id)
            return prev.copy(quality = Quality.ERROR, failures = prev.failures + 1, error = PointError(ErrorKind.BAD_VALUE, detail = "非有限数值"))
        }
        val text = if (raw is RawValue.Flag) null else ValueCodec.format(eng, p.conversion.decimals)
        return PointSnapshot(p.id, Quality.OK, raw, eng, text, p.thresholds.levelFor(eng), now, wallMs())
    }

    companion object {
        /** 连续超时达到这个次数，质量从 STALE 变成 TIMEOUT（稿面“连续超时 3 次”）。 */
        const val TIMEOUT_STREAK = 3
        const val ERROR_BACKOFF_MS = 2_000L
        const val DISCONNECTED_RETRY_MS = 1_000L
    }
}
