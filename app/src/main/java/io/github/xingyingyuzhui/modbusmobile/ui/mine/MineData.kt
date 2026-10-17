package io.github.xingyingyuzhui.modbusmobile.ui.mine

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xingyingyuzhui.modbusmobile.R
import io.github.xingyingyuzhui.modbusmobile.application.UiSettings
import io.github.xingyingyuzhui.modbusmobile.application.history.HistoryInterval
import io.github.xingyingyuzhui.modbusmobile.application.history.HistoryOverview
import io.github.xingyingyuzhui.modbusmobile.application.history.HistorySession
import io.github.xingyingyuzhui.modbusmobile.application.history.RecordingState
import io.github.xingyingyuzhui.modbusmobile.ui.components.NumberInput
import io.github.xingyingyuzhui.modbusmobile.ui.components.PrimaryButton
import io.github.xingyingyuzhui.modbusmobile.ui.components.RowDivider
import io.github.xingyingyuzhui.modbusmobile.ui.components.SecondaryButton
import io.github.xingyingyuzhui.modbusmobile.ui.components.SectionLabel
import io.github.xingyingyuzhui.modbusmobile.ui.components.SegmentedTextControl
import io.github.xingyingyuzhui.modbusmobile.ui.components.SettingRow
import io.github.xingyingyuzhui.modbusmobile.ui.components.SettingTrailing
import io.github.xingyingyuzhui.modbusmobile.ui.components.SettingsCard
import io.github.xingyingyuzhui.modbusmobile.ui.theme.JetBrainsMono
import io.github.xingyingyuzhui.modbusmobile.ui.theme.LocalModbusColors
import io.github.xingyingyuzhui.modbusmobile.ui.theme.LocalSpacing
import io.github.xingyingyuzhui.modbusmobile.ui.traffic.formatWallDateTime
import java.util.Locale
import androidx.compose.foundation.shape.RoundedCornerShape

internal fun formatStorageAmount(bytes: Long): String = when {
    bytes == Long.MAX_VALUE -> "—"
    bytes <= 0L -> "0 MB"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    bytes < 10L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
}

@Composable
internal fun MineDataSection(
    settings: UiSettings,
    overview: HistoryOverview,
    recording: RecordingState,
    selected: MinePage?,
    onExport: () -> Unit,
    onHistory: () -> Unit,
    onStorage: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val colors = LocalModbusColors.current
    Column(verticalArrangement = Arrangement.spacedBy(spacing.gapS)) {
        SectionLabel(stringResource(R.string.mine_section_data))
        SettingsCard {
            SettingRow(
                stringResource(R.string.mine_export),
                Modifier.testTag("mine-export"),
                icon = { StrokeIcon(colors.accentText, IconKind.EXPORT) },
                onClick = onExport,
            )
            RowDivider()
            SettingRow(
                stringResource(R.string.mine_history),
                Modifier.testTag("mine-history"),
                value = stringResource(R.string.mine_history_count, overview.sessions.size),
                icon = { StrokeIcon(colors.accentText, IconKind.LINES) },
                selected = selected == MinePage.HISTORY,
                onClick = onHistory,
            )
        }
        StorageCard(settings, overview, recording, onStorage)
    }
}

@Composable
private fun StorageCard(
    settings: UiSettings,
    overview: HistoryOverview,
    recording: RecordingState,
    onStorage: () -> Unit,
) {
    val colors = LocalModbusColors.current
    val cap = settings.historyCapacityMb.toLong() * 1024L * 1024L
    val fraction = if (overview.usageBytes <= 0L || overview.usageBytes == Long.MAX_VALUE || cap <= 0L) {
        0f
    } else {
        (overview.usageBytes.toDouble() / cap.toDouble()).toFloat().coerceIn(0f, 1f)
    }
    SettingsCard(Modifier.testTag("mine-storage")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mine_storage), color = colors.text, fontSize = 14.sp)
                Text(
                    stringResource(R.string.mine_storage_usage, formatStorageAmount(overview.usageBytes), settings.historyCapacityMb),
                    color = colors.dim,
                    fontSize = 12.sp,
                    fontFamily = JetBrainsMono,
                    modifier = Modifier.testTag("mine-storage-usage"),
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(colors.surface3),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .background(if (recording == RecordingState.STOPPED_STORAGE) colors.error else colors.accent),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.mine_storage_retention, settings.historyRetentionDays, recordingLabel(recording)),
                    color = colors.dim,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f).testTag("mine-recording"),
                )
                Text(
                    stringResource(R.string.mine_manage),
                    color = colors.accentText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(onClick = onStorage)
                        .padding(start = 8.dp)
                        .testTag("mine-storage-manage"),
                )
            }
            if (recording == RecordingState.STOPPED_STORAGE) {
                Text(stringResource(R.string.mine_storage_stopped_detail), color = colors.error, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
internal fun HistoryPage(
    model: MineViewModel,
    overview: HistoryOverview,
    recording: RecordingState,
    onDelete: (HistorySession) -> Unit,
) {
    val spacing = LocalSpacing.current
    val colors = LocalModbusColors.current
    Column(verticalArrangement = Arrangement.spacedBy(spacing.gapS)) {
        Text(recordingLabel(recording), color = if (recording == RecordingState.STOPPED_STORAGE) colors.error else colors.muted, fontSize = 13.sp, modifier = Modifier.testTag("history-state"))
        if (recording == RecordingState.STOPPED_STORAGE) {
            Text(stringResource(R.string.mine_storage_stopped_detail), color = colors.error, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (overview.dropped > 0L) {
            Text(stringResource(R.string.mine_history_dropped, overview.dropped), color = colors.dim, fontSize = 12.sp)
        }
        if (overview.sessions.isEmpty()) {
            Text(stringResource(R.string.mine_history_empty), color = colors.muted, fontSize = 14.sp, modifier = Modifier.testTag("history-empty"))
        } else {
            SettingsCard {
                overview.sessions.sortedWith(compareByDescending<HistorySession> { it.startedAtMs }.thenByDescending { it.id }).forEachIndexed { index, session ->
                    if (index > 0) RowDivider()
                    SessionRow(session, model, onDelete)
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: HistorySession, model: MineViewModel, onDelete: (HistorySession) -> Unit) {
    val colors = LocalModbusColors.current
    val open = session.endedAtMs == null
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("history-session-" + session.id)) {
        Text(sessionTitle(session), color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            sessionSubtitle(session, open),
            color = colors.dim,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        SettingRow(
            stringResource(R.string.mine_history_retain),
            Modifier.testTag("history-retain-" + session.id),
            trailing = SettingTrailing.SWITCH,
            checked = session.retained,
            onChecked = { model.setRetained(session.id, it) },
        )
        if (!open && !session.retained) {
            Text(
                stringResource(R.string.mine_history_delete),
                color = colors.error,
                fontSize = 14.sp,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable { onDelete(session) }
                    .padding(vertical = 8.dp)
                    .testTag("history-delete-" + session.id),
            )
        }
    }
}

@Composable
private fun sessionTitle(session: HistorySession): String {
    val role = when (session.role) {
        "MASTER" -> stringResource(R.string.role_master)
        "SLAVE" -> stringResource(R.string.role_slave)
        else -> session.role
    }
    val target = when (session.target) {
        "DEMO" -> stringResource(R.string.target_demo)
        "USB" -> stringResource(R.string.target_usb)
        "LAB" -> stringResource(R.string.target_lab)
        else -> session.target
    }
    return stringResource(R.string.mine_history_session, role, target)
}

@Composable
private fun sessionSubtitle(session: HistorySession, open: Boolean): String {
    val start = formatWallDateTime(session.startedAtMs)
    return if (open) {
        stringResource(R.string.mine_history_open_line, start)
    } else {
        stringResource(R.string.mine_history_closed_line, start, formatWallDateTime(session.endedAtMs ?: session.startedAtMs))
    }
}

@Composable
internal fun recordingLabel(state: RecordingState): String = stringResource(
    when (state) {
        RecordingState.IDLE -> R.string.mine_recording_idle
        RecordingState.RECORDING -> R.string.mine_recording_on
        RecordingState.STOPPED_STORAGE -> R.string.mine_recording_stopped
    },
)

private enum class StorageField { RETENTION, CAPACITY }

@Composable
internal fun StorageSettings(model: MineViewModel, settings: UiSettings) {
    val spacing = LocalSpacing.current
    val colors = LocalModbusColors.current
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val field = StorageField.entries.firstOrNull { it.name == editing }
    BackHandler(enabled = field != null) { editing = null }
    if (field != null) {
        StorageEditor(field, settings, model) { editing = null }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.gapS)) {
        SectionLabel(stringResource(R.string.mine_history_interval))
        SegmentedTextControl(
            HistoryInterval.entries,
            settings.historyInterval,
            model::setHistoryInterval,
            small = true,
        ) { intervalName(it) }
        SettingsCard {
            SettingRow(
                stringResource(R.string.mine_history_keep_days),
                Modifier.testTag("history-retention"),
                value = stringResource(R.string.mine_history_keep_value, settings.historyRetentionDays),
                onClick = { editing = StorageField.RETENTION.name },
            )
            RowDivider()
            SettingRow(
                stringResource(R.string.mine_history_capacity),
                Modifier.testTag("history-capacity"),
                value = stringResource(R.string.mine_history_capacity_value, settings.historyCapacityMb),
                onClick = { editing = StorageField.CAPACITY.name },
            )
        }
        Text(stringResource(R.string.mine_storage_hint), color = colors.dim, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun StorageEditor(field: StorageField, settings: UiSettings, model: MineViewModel, onDone: () -> Unit) {
    val spacing = LocalSpacing.current
    val (initial, range, unit, errorRes) = when (field) {
        StorageField.RETENTION -> Quad(settings.historyRetentionDays, MineViewModel.RetentionRange, R.string.mine_retention_unit, R.string.mine_retention_range)
        StorageField.CAPACITY -> Quad(settings.historyCapacityMb, MineViewModel.CapacityRange, R.string.mine_capacity_unit, R.string.mine_capacity_range)
    }
    var text by rememberSaveable(field.name) { mutableStateOf(initial.toString()) }
    val parsed = MineViewModel.parseInt(text, range)
    val label = stringResource(if (field == StorageField.RETENTION) R.string.mine_history_keep_days else R.string.mine_history_capacity)
    Column(Modifier.testTag("storage-editor"), verticalArrangement = Arrangement.spacedBy(spacing.gapS)) {
        SectionLabel(stringResource(R.string.comm_edit_title, label))
        NumberInput(
            label = label,
            value = text,
            error = if (parsed == null) stringResource(errorRes) else null,
            sub = stringResource(unit),
            modifier = Modifier.fillMaxWidth(),
            onValue = { text = it.filter(Char::isDigit).take(5) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(stringResource(R.string.action_cancel), onDone, Modifier.weight(1f).testTag("storage-cancel"), compact = true)
            PrimaryButton(
                stringResource(R.string.action_ok),
                {
                    parsed?.let {
                        when (field) {
                            StorageField.RETENTION -> model.setHistoryRetention(it)
                            StorageField.CAPACITY -> model.setHistoryCapacity(it)
                        }
                        onDone()
                    }
                },
                Modifier.weight(1f).testTag("storage-save"),
                enabled = parsed != null,
                compact = true,
            )
        }
    }
}

private data class Quad(val initial: Int, val range: IntRange, val unit: Int, val error: Int)

@Composable
private fun intervalName(interval: HistoryInterval): String = stringResource(
    when (interval) {
        HistoryInterval.SEC_1 -> R.string.mine_interval_1
        HistoryInterval.SEC_5 -> R.string.mine_interval_5
        HistoryInterval.SEC_10 -> R.string.mine_interval_10
        HistoryInterval.SEC_60 -> R.string.mine_interval_60
        HistoryInterval.ON_CHANGE -> R.string.mine_interval_change
    },
)

@Composable
private fun StrokeIcon(color: Color, kind: IconKind) {
    Canvas(Modifier.size(16.dp)) {
        val s = size.minDimension
        val stroke = s * 0.12f
        when (kind) {
            IconKind.EXPORT -> {
                drawLine(color, Offset(s * 0.5f, s * 0.72f), Offset(s * 0.5f, s * 0.16f), stroke, StrokeCap.Round)
                drawLine(color, Offset(s * 0.28f, s * 0.38f), Offset(s * 0.5f, s * 0.16f), stroke, StrokeCap.Round)
                drawLine(color, Offset(s * 0.72f, s * 0.38f), Offset(s * 0.5f, s * 0.16f), stroke, StrokeCap.Round)
                drawLine(color, Offset(s * 0.2f, s * 0.84f), Offset(s * 0.8f, s * 0.84f), stroke, StrokeCap.Round)
            }
            IconKind.LINES -> {
                listOf(0.28f, 0.5f, 0.72f).forEach { y ->
                    drawLine(color, Offset(s * 0.18f, s * y), Offset(s * 0.82f, s * y), stroke, StrokeCap.Round)
                }
            }
        }
    }
}

private enum class IconKind { EXPORT, LINES }
