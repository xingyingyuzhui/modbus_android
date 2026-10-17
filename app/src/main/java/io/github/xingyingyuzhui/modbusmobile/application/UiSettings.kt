package io.github.xingyingyuzhui.modbusmobile.application

import io.github.xingyingyuzhui.modbusmobile.application.history.HistoryInterval
import io.github.xingyingyuzhui.modbusmobile.application.history.HistoryPolicy

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AccentColor(val argb: Long, val onAccentArgb: Long) {
    AMBER(0xFFF5A524, 0xFF1A1200),
    BLUE(0xFF2F81F7, 0xFFFFFFFF),
    GREEN(0xFF2DA44E, 0xFFFFFFFF),
    TEAL(0xFF0E8A8A, 0xFFFFFFFF),
    PURPLE(0xFF8250DF, 0xFFFFFFFF),
}

enum class FontScale(val multiplier: Float) {
    STANDARD(1f),
    LARGE(1.15f),
    EXTRA(1.3f),
}

enum class DensityMode(val scale: Float) {
    COMFORTABLE(1f),
    COMPACT(0.75f),
}

/** 地址显示：只显示 0 基地址 / 只显示参考号（40001）/ 两者（0 基为主，参考号小字）。默认“两者”，与稿面一致。 */
enum class AddressStyle { ZERO_BASED, MODICON, BOTH }

enum class NumberFormat { U16, S16, HEX, F32 }

enum class TimeStyle { ELAPSED, CLOCK }

data class UiSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accent: AccentColor = AccentColor.AMBER,
    val fontScale: FontScale = FontScale.STANDARD,
    val density: DensityMode = DensityMode.COMFORTABLE,
    val keepScreenOn: Boolean = false,
    /** 默认关。横屏截图和日常翻页不会被自动拉进曲线页。 */
    val landscapeCurveFullscreen: Boolean = false,
    /**
     * 先只保存。写入确认才是第一次震动，那是工作台阶段的事。
     */
    val vibration: Boolean = false,
    val addressStyle: AddressStyle = AddressStyle.BOTH,
    val numberFormat: NumberFormat = NumberFormat.U16,
    /** 默认开：稿面的 HEX 一律写成 0x007B。 */
    val hexPrefix: Boolean = true,
    /**
     * 时钟样式先只保存。报文只有 monotonicNs，日志仍显示相对毫秒。
     */
    val timeStyle: TimeStyle = TimeStyle.ELAPSED,
    /** 日志里 CRC 两字节用暗色（稿面“日志中 CRC 用暗色”）。默认开，与稿面一致。 */
    val logCrcDim: Boolean = true,
    /** 写线圈的确认弹窗里多一条提示（稿面“线圈写入额外提示”）。默认开。 */
    val coilExtraWarning: Boolean = true,
    /** 通信默认值：只是连接页高级设置的初始值，连接页仍可逐次修改。 */
    val defaultResponseTimeoutMs: Long = DEFAULT_RESPONSE_TIMEOUT_MS,
    val defaultInterRequestMs: Long = DEFAULT_INTER_REQUEST_MS,
    /** 广播写之后等多久再发下一条（对应 SerialSettings.turnaroundMs）。 */
    val defaultBroadcastWaitMs: Long = DEFAULT_BROADCAST_WAIT_MS,
    val defaultEcho: EchoMode = EchoMode.OFF,
    /** 历史快照间隔。曲线画面仍用内存环，这个值只控制落库。 */
    val historyInterval: HistoryInterval = HistoryInterval.SEC_5,
    val historyRetentionDays: Int = HistoryPolicy.DEFAULT_RETENTION_DAYS,
    val historyCapacityMb: Int = HistoryPolicy.DEFAULT_CAPACITY_MB,
) {
    companion object {
        const val DEFAULT_RESPONSE_TIMEOUT_MS = 1_000L
        const val DEFAULT_INTER_REQUEST_MS = 20L
        const val DEFAULT_BROADCAST_WAIT_MS = 150L
    }
}

internal object UiSettingKeys {
    const val THEME = "theme"
    const val ACCENT = "accent"
    const val FONT = "font_scale"
    const val DENSITY = "density"
    const val KEEP = "keep_screen_on"
    const val CURVE = "landscape_curve"
    const val VIBRATION = "vibration"
    const val ADDRESS = "address_style"
    const val NUMBER = "number_format"
    const val HEX = "hex_prefix"
    const val TIME = "time_style"
    const val LOG_CRC_DIM = "log_crc_dim"
    const val COIL_WARNING = "coil_extra_warning"
    const val DEF_TIMEOUT = "default_response_timeout_ms"
    const val DEF_INTERVAL = "default_inter_request_ms"
    const val DEF_BROADCAST = "default_broadcast_wait_ms"
    const val DEF_ECHO = "default_echo"
    const val HISTORY_INTERVAL = "history_interval"
    const val HISTORY_RETENTION_DAYS = "history_retention_days"
    const val HISTORY_CAPACITY_MB = "history_capacity_mb"

    val booleans = setOf(KEEP, CURVE, VIBRATION, HEX, LOG_CRC_DIM, COIL_WARNING)
}

fun UiSettings.toPreferenceMap(): Map<String, String> = mapOf(
    UiSettingKeys.THEME to theme.name,
    UiSettingKeys.ACCENT to accent.name,
    UiSettingKeys.FONT to fontScale.name,
    UiSettingKeys.DENSITY to density.name,
    UiSettingKeys.KEEP to keepScreenOn.toString(),
    UiSettingKeys.CURVE to landscapeCurveFullscreen.toString(),
    UiSettingKeys.VIBRATION to vibration.toString(),
    UiSettingKeys.ADDRESS to addressStyle.name,
    UiSettingKeys.NUMBER to numberFormat.name,
    UiSettingKeys.HEX to hexPrefix.toString(),
    UiSettingKeys.TIME to timeStyle.name,
    UiSettingKeys.LOG_CRC_DIM to logCrcDim.toString(),
    UiSettingKeys.COIL_WARNING to coilExtraWarning.toString(),
    UiSettingKeys.DEF_TIMEOUT to defaultResponseTimeoutMs.toString(),
    UiSettingKeys.DEF_INTERVAL to defaultInterRequestMs.toString(),
    UiSettingKeys.DEF_BROADCAST to defaultBroadcastWaitMs.toString(),
    UiSettingKeys.DEF_ECHO to defaultEcho.name,
    UiSettingKeys.HISTORY_INTERVAL to historyInterval.name,
    UiSettingKeys.HISTORY_RETENTION_DAYS to historyRetentionDays.toString(),
    UiSettingKeys.HISTORY_CAPACITY_MB to historyCapacityMb.toString(),
)

fun uiSettingsFrom(map: Map<String, String>): UiSettings = UiSettings(
    theme = map.enum(UiSettingKeys.THEME, ThemeMode.SYSTEM),
    accent = map.enum(UiSettingKeys.ACCENT, AccentColor.AMBER),
    fontScale = map.enum(UiSettingKeys.FONT, FontScale.STANDARD),
    density = map.enum(UiSettingKeys.DENSITY, DensityMode.COMFORTABLE),
    keepScreenOn = map.bool(UiSettingKeys.KEEP, false),
    landscapeCurveFullscreen = map.bool(UiSettingKeys.CURVE, false),
    vibration = map.bool(UiSettingKeys.VIBRATION, false),
    addressStyle = map.enum(UiSettingKeys.ADDRESS, AddressStyle.BOTH),
    numberFormat = map.enum(UiSettingKeys.NUMBER, NumberFormat.U16),
    hexPrefix = map.bool(UiSettingKeys.HEX, true),
    timeStyle = map.enum(UiSettingKeys.TIME, TimeStyle.ELAPSED),
    logCrcDim = map.bool(UiSettingKeys.LOG_CRC_DIM, true),
    coilExtraWarning = map.bool(UiSettingKeys.COIL_WARNING, true),
    defaultResponseTimeoutMs = map.long(
        UiSettingKeys.DEF_TIMEOUT, UiSettings.DEFAULT_RESPONSE_TIMEOUT_MS, 1L..120_000L,
    ),
    defaultInterRequestMs = map.long(
        UiSettingKeys.DEF_INTERVAL, UiSettings.DEFAULT_INTER_REQUEST_MS, 1L..120_000L,
    ),
    defaultBroadcastWaitMs = map.long(
        UiSettingKeys.DEF_BROADCAST, UiSettings.DEFAULT_BROADCAST_WAIT_MS, 0L..120_000L,
    ),
    defaultEcho = map.enum(UiSettingKeys.DEF_ECHO, EchoMode.OFF),
    historyInterval = map.enum(UiSettingKeys.HISTORY_INTERVAL, HistoryInterval.SEC_5),
    historyRetentionDays = map.int(
        UiSettingKeys.HISTORY_RETENTION_DAYS,
        HistoryPolicy.DEFAULT_RETENTION_DAYS,
        HistoryPolicy.RETENTION_DAYS,
    ),
    historyCapacityMb = map.int(
        UiSettingKeys.HISTORY_CAPACITY_MB,
        HistoryPolicy.DEFAULT_CAPACITY_MB,
        1..10_240,
    ),
)

private inline fun <reified T : Enum<T>> Map<String, String>.enum(key: String, default: T): T {
    val raw = this[key] ?: return default
    return enumValues<T>().firstOrNull { it.name == raw } ?: default
}

private fun Map<String, String>.bool(key: String, default: Boolean): Boolean =
    this[key]?.toBooleanStrictOrNull() ?: default

/** 超出范围或不是整数就回默认值：连接页的校验是 1..120000 / ≥1，存进来的值不能比它更宽。 */
private fun Map<String, String>.long(key: String, default: Long, range: LongRange): Long =
    this[key]?.toLongOrNull()?.takeIf { it in range } ?: default

private fun Map<String, String>.int(key: String, default: Int, range: IntRange): Int =
    this[key]?.toIntOrNull()?.takeIf { it in range } ?: default
