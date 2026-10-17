package io.github.xingyingyuzhui.modbusmobile.application.history

import io.github.xingyingyuzhui.modbusmobile.application.ConnectionTarget

/** 落库间隔。曲线内存环仍按自己的采样走，这里只决定写进数据库的快照。 */
enum class HistoryInterval(val periodMs: Long?) {
    SEC_1(1_000L),
    SEC_5(5_000L),
    SEC_10(10_000L),
    SEC_60(60_000L),

    /** 质量、工程值或文本有变化才写一条。 */
    ON_CHANGE(null),
}

/** 与点位 [io.github.xingyingyuzhui.modbusmobile.application.panel.Quality] 对应，但 UNKNOWN 只留给读库时认不出的旧值。 */
enum class SampleQuality { OK, STALE, TIMEOUT, ERROR, UNKNOWN }

data class HistoryPolicy(
    val interval: HistoryInterval = HistoryInterval.SEC_5,
    /** 初始值。规格只规定要有保留天数，30 天是本阶段的产品初值。 */
    val retentionDays: Int = DEFAULT_RETENTION_DAYS,
    /** 规格建议的软上限。超过后先删未保留的旧会话，仍超过就停止记录。 */
    val capacityBytes: Long = DEFAULT_CAPACITY_BYTES,
) {
    init {
        require(retentionDays in RETENTION_DAYS) { "retentionDays" }
        require(capacityBytes in CAPACITY_BYTES) { "capacityBytes" }
    }

    companion object {
        const val DEFAULT_RETENTION_DAYS = 30
        const val DEFAULT_CAPACITY_MB = 250
        const val DEFAULT_CAPACITY_BYTES = DEFAULT_CAPACITY_MB * 1024L * 1024L
        val RETENTION_DAYS = 1..3_650
        val CAPACITY_BYTES = 1L..(10_240L * 1024L * 1024L)
        const val QUEUE_LIMIT = 256
    }
}

data class HistorySession(
    val id: Long,
    val role: String,
    val target: String,
    val epoch: Long,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val retained: Boolean,
)

data class HistorySample(
    val sessionId: Long,
    val pointId: String,
    val sampleAtMs: Long,
    val recordedAtMs: Long,
    val epoch: Long,
    val quality: SampleQuality,
    val engineering: Double?,
    val text: String?,
)

/** 一次待写入的观察。工程值只在质量为 [SampleQuality.OK] 时携带，超时不把旧值写成新样本。 */
data class Observation(
    val pointId: String,
    val sampleAtMs: Long,
    val quality: SampleQuality,
    val engineering: Double?,
    val text: String?,
)

enum class RecordingState { IDLE, RECORDING, STOPPED_STORAGE }

/**
 * 调试桥占用的设备号。只在 debug 包里会出现；release 不注册这台设备。
 * 与 `LocalSerialLab` 的 `Int.MIN_VALUE` 保持一致，application 不引用 debug 源码。
 */
internal const val LAB_DEVICE_ID = Int.MIN_VALUE

fun ConnectionTarget.historyTarget(): String = when (this) {
    ConnectionTarget.Demo -> "DEMO"
    is ConnectionTarget.Usb -> if (deviceId == LAB_DEVICE_ID) "LAB" else "USB"
}
