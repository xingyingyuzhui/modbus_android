package io.github.xingyingyuzhui.modbusmobile.ui.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.xingyingyuzhui.modbusmobile.application.AccentColor
import io.github.xingyingyuzhui.modbusmobile.application.AddressStyle
import io.github.xingyingyuzhui.modbusmobile.application.DensityMode
import io.github.xingyingyuzhui.modbusmobile.application.EchoMode
import io.github.xingyingyuzhui.modbusmobile.application.FontScale
import io.github.xingyingyuzhui.modbusmobile.application.NumberFormat
import io.github.xingyingyuzhui.modbusmobile.application.SettingsRepository
import io.github.xingyingyuzhui.modbusmobile.application.ThemeMode
import io.github.xingyingyuzhui.modbusmobile.application.TimeStyle
import io.github.xingyingyuzhui.modbusmobile.application.UiSettings
import io.github.xingyingyuzhui.modbusmobile.application.history.EmptyHistoryCatalog
import io.github.xingyingyuzhui.modbusmobile.application.history.HistoryCatalog
import io.github.xingyingyuzhui.modbusmobile.application.history.HistoryInterval
import io.github.xingyingyuzhui.modbusmobile.application.history.HistoryOverview
import io.github.xingyingyuzhui.modbusmobile.application.history.HistoryPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MineViewModel(
    private val repository: SettingsRepository,
    private val history: HistoryCatalog = EmptyHistoryCatalog,
) : ViewModel() {
    val settings = repository.settings
    val recording = history.recording
    private val _overview = MutableStateFlow(HistoryOverview.Empty)
    val overview: StateFlow<HistoryOverview> = _overview.asStateFlow()

    fun refresh() {
        viewModelScope.launch { _overview.value = history.overview() }
    }

    fun setRetained(id: Long, retained: Boolean) {
        viewModelScope.launch {
            history.setRetained(id, retained)
            _overview.value = history.overview()
        }
    }

    fun deleteClosed(id: Long) {
        viewModelScope.launch {
            history.deleteClosed(id)
            _overview.value = history.overview()
        }
    }

    fun setTheme(mode: ThemeMode) = edit { it.copy(theme = mode) }
    fun setAccent(accent: AccentColor) = edit { it.copy(accent = accent) }
    fun setFont(scale: FontScale) = edit { it.copy(fontScale = scale) }
    fun setDensity(mode: DensityMode) = edit { it.copy(density = mode) }
    fun setAddress(style: AddressStyle) = edit { it.copy(addressStyle = style) }
    fun setNumber(format: NumberFormat) = edit { it.copy(numberFormat = format) }
    fun setHexPrefix(enabled: Boolean) = edit { it.copy(hexPrefix = enabled) }
    fun setTime(style: TimeStyle) = edit { it.copy(timeStyle = style) }
    fun setKeepScreen(enabled: Boolean) = edit { it.copy(keepScreenOn = enabled) }
    fun setVibration(enabled: Boolean) = edit { it.copy(vibration = enabled) }
    fun setLandscapeCurve(enabled: Boolean) = edit { it.copy(landscapeCurveFullscreen = enabled) }

    fun setLogCrcDim(enabled: Boolean) = edit { it.copy(logCrcDim = enabled) }
    fun setCoilExtraWarning(enabled: Boolean) = edit { it.copy(coilExtraWarning = enabled) }
    fun setDefaultTimeout(ms: Long) = edit { it.copy(defaultResponseTimeoutMs = ms) }
    fun setDefaultInterval(ms: Long) = edit { it.copy(defaultInterRequestMs = ms) }
    fun setDefaultBroadcastWait(ms: Long) = edit { it.copy(defaultBroadcastWaitMs = ms) }
    fun setDefaultEcho(mode: EchoMode) = edit { it.copy(defaultEcho = mode) }
    fun setHistoryInterval(interval: HistoryInterval) = edit { it.copy(historyInterval = interval) }
    fun setHistoryRetention(days: Int) = edit { it.copy(historyRetentionDays = days) }
    fun setHistoryCapacity(mb: Int) = edit { it.copy(historyCapacityMb = mb) }

    private fun edit(transform: (UiSettings) -> UiSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    companion object {
        val TimeoutRange = 1L..120_000L
        val IntervalRange = 1L..120_000L
        val BroadcastRange = 0L..120_000L
        val RetentionRange = HistoryPolicy.RETENTION_DAYS
        val CapacityRange = 1..10_240

        /** 通信默认值的输入校验：整数且在范围内才接受，其余返回 null（界面显示错误、不保存）。 */
        fun parseMs(text: String, range: LongRange): Long? = text.trim().toLongOrNull()?.takeIf { it in range }

        fun parseInt(text: String, range: IntRange): Int? = text.trim().toIntOrNull()?.takeIf { it in range }
    }
}
