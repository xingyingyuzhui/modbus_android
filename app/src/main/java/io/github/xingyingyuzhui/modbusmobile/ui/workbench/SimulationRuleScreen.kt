package io.github.xingyingyuzhui.modbusmobile.ui.workbench

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xingyingyuzhui.modbusmobile.R
import io.github.xingyingyuzhui.modbusmobile.application.slave.SlaveAreas
import io.github.xingyingyuzhui.modbusmobile.application.slave.isBitArea
import io.github.xingyingyuzhui.modbusmobile.core.model.RegisterArea
import io.github.xingyingyuzhui.modbusmobile.core.slave.ExternalWritePolicy
import io.github.xingyingyuzhui.modbusmobile.core.slave.RuleState
import io.github.xingyingyuzhui.modbusmobile.core.slave.RuleStatus
import io.github.xingyingyuzhui.modbusmobile.core.slave.SimulationRule
import io.github.xingyingyuzhui.modbusmobile.core.slave.Waveform
import io.github.xingyingyuzhui.modbusmobile.ui.components.ChipRow
import io.github.xingyingyuzhui.modbusmobile.ui.components.InPageSheet
import io.github.xingyingyuzhui.modbusmobile.ui.components.ModbusSwitchVisual
import io.github.xingyingyuzhui.modbusmobile.ui.components.NumberInput
import io.github.xingyingyuzhui.modbusmobile.ui.components.PrimaryButton
import io.github.xingyingyuzhui.modbusmobile.ui.components.SecondaryButton
import io.github.xingyingyuzhui.modbusmobile.ui.components.SmallButton
import io.github.xingyingyuzhui.modbusmobile.ui.components.SubPageHeader
import io.github.xingyingyuzhui.modbusmobile.ui.components.TextLink
import io.github.xingyingyuzhui.modbusmobile.ui.resolve
import io.github.xingyingyuzhui.modbusmobile.ui.shell.dockClearance
import io.github.xingyingyuzhui.modbusmobile.ui.shell.screenFrame
import io.github.xingyingyuzhui.modbusmobile.ui.theme.LocalModbusColors

/** b5-4: all rules stay visible across Unit selections; selecting a row opens its draft. */
@Composable
fun SimulationRules(model: SlaveViewModel) {
    val colors = LocalModbusColors.current
    val rules = model.ruleStates
    Column(Modifier.fillMaxWidth().testTag("simulation_rules"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.simulation_title), color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextLink(stringResource(R.string.simulation_new), model::openNewRule, Modifier.testTag("simulation_rule_add"), color = colors.accentText)
        }
        if (rules.isEmpty()) {
            Text(stringResource(R.string.simulation_empty), color = colors.muted, fontSize = 13.sp, lineHeight = 19.sp)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.simulation_pause_all), model::pauseAllRules, Modifier.weight(1f).testTag("simulation_rule_pause_all"), compact = true)
                SecondaryButton(stringResource(R.string.simulation_resume_all), model::resumeAllRules, Modifier.weight(1f).testTag("simulation_rule_resume_all"), compact = true)
            }
            rules.forEach { state -> SimulationRuleRow(state, { model.openRule(state.rule.id) }, { model.toggleRule(state.rule.id) }) }
        }
    }
}

@Composable
private fun SimulationRuleRow(state: RuleState, onEdit: () -> Unit, onToggle: () -> Unit) {
    val colors = LocalModbusColors.current
    val rule = state.rule
    val running = state.status == RuleStatus.RUNNING
    val statusColor = when (state.status) {
        RuleStatus.RUNNING -> colors.rxLocal
        RuleStatus.PAUSED -> colors.muted
        RuleStatus.PAUSED_BY_EXTERNAL_WRITE -> colors.accentText
        RuleStatus.UNMAPPED -> colors.error
    }
    val status = stringResource(when (state.status) {
        RuleStatus.RUNNING -> R.string.simulation_running
        RuleStatus.PAUSED -> R.string.simulation_paused
        RuleStatus.PAUSED_BY_EXTERNAL_WRITE -> R.string.simulation_external_paused
        RuleStatus.UNMAPPED -> R.string.simulation_unmapped
    })
    Row(
        Modifier.fillMaxWidth().background(colors.surface1, RoundedCornerShape(12.dp)).border(1.dp, colors.line, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(9.dp).background(statusColor, CircleShape))
        Column(
            Modifier.weight(1f).heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onEdit).padding(vertical = 10.dp).testTag("simulation_rule_${rule.id}"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val end = rule.target.address + rule.target.count - 1
            val address = if (rule.target.count == 1) rule.target.address.toString() else "${rule.target.address}–$end"
            Text(stringResource(R.string.simulation_rule_target, waveName(RuleDraft.from(rule).kind), rule.target.unitId, simulationAreaName(rule.target.area), address), color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(ruleSummary(rule), color = colors.muted, fontSize = 12.sp, lineHeight = 17.sp)
            Text(status, color = statusColor, fontSize = 12.sp, lineHeight = 17.sp)
        }
        SmallButton(stringResource(if (running) R.string.simulation_pause else R.string.simulation_resume), onToggle, Modifier.testTag("simulation_rule_toggle_${rule.id}"))
    }
}

@Composable
private fun ruleSummary(rule: SimulationRule): String {
    fun num(value: Double) = RuleDraft.num(value)
    return when (val wave = rule.waveform) {
        is Waveform.Constant -> stringResource(R.string.simulation_constant_summary, num(wave.value), rule.refreshMs)
        is Waveform.Ramp -> stringResource(R.string.simulation_ramp_summary, num(wave.start), num(wave.step), rule.refreshMs)
        is Waveform.Sine -> stringResource(R.string.simulation_sine_summary, num(wave.center), num(wave.amplitude), num(wave.periodMs / 1000.0))
        is Waveform.Square -> stringResource(R.string.simulation_square_summary, num(wave.low), num(wave.high), num(wave.periodMs / 1000.0))
        is Waveform.RandomUniform -> stringResource(R.string.simulation_random_summary, num(wave.min), num(wave.max), rule.refreshMs)
        is Waveform.Sequence -> stringResource(R.string.simulation_sequence_summary, wave.values.take(6).joinToString(", ") { num(it) } + if (wave.values.size > 6) "…" else "", rule.refreshMs)
    }
}

/** Full content page, with the same native controls and in-page confirmations as the workbench. */
@Composable
fun SimulationRuleEditor(model: SlaveViewModel, navigationBars: WindowInsets = WindowInsets.navigationBars) {
    val draft = model.ruleDraft ?: return
    val colors = LocalModbusColors.current
    val units by model.units.collectAsStateWithLifecycle()
    var confirmDelete by remember(draft.id) { mutableStateOf(false) }
    BackHandler(onBack = model::cancelRule)
    val bottomClearance = maxOf(dockClearance(), navigationBars.asPaddingValues().calculateBottomPadding())
    Box(Modifier.fillMaxSize().background(colors.bg).testTag("simulation_rule_editor")) {
        Column(Modifier.screenFrame().imePadding().padding(bottom = bottomClearance)) {
            SubPageHeader(stringResource(R.string.simulation_title), model::cancelRule) {
                if (draft.id != null) TextLink(stringResource(R.string.simulation_delete), { confirmDelete = true }, Modifier.testTag("simulation_rule_delete_${draft.id}"), colors.error)
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SimulationPreview(draft)
                SectionLabel(R.string.simulation_target)
                Text(stringResource(R.string.simulation_unit), color = colors.muted, fontSize = 12.sp)
                ChipRow(units, { it == draft.unit }, { stringResource(R.string.slave_unit_label, it) }, { next -> model.updateRule { copy(unit = next) } })
                draft.errors[RuleField.UNIT]?.let { Text(it.resolve(), color = colors.error, fontSize = 12.sp) }
                Text(stringResource(R.string.simulation_area), color = colors.muted, fontSize = 12.sp)
                ChipRow(SlaveAreas, { it == draft.area }, { simulationAreaName(it) }, { next -> model.updateRule { copy(area = next) } })
                InputGrid(listOf(
                    InputSpec(RuleField.ADDRESS, R.string.simulation_address, draft.address, "address") { model.updateRule { copy(address = it) } },
                    InputSpec(RuleField.COUNT, R.string.simulation_count, draft.count, "count") { model.updateRule { copy(count = it) } },
                ), draft)
                SectionLabel(R.string.simulation_kind)
                ChipRow(WaveKind.entries, { it == draft.kind }, { waveName(it) }, { next -> model.updateRule { withKind(next) } })
                SectionLabel(R.string.simulation_parameters)
                val labels = when (draft.kind) {
                    WaveKind.CONSTANT -> listOf(R.string.simulation_value)
                    WaveKind.RAMP -> listOf(R.string.simulation_start, R.string.simulation_step, R.string.simulation_min, R.string.simulation_max)
                    WaveKind.SINE -> listOf(R.string.simulation_center, R.string.simulation_amplitude, R.string.simulation_period, R.string.simulation_phase)
                    WaveKind.SQUARE -> listOf(R.string.simulation_low, R.string.simulation_high, R.string.simulation_period, R.string.simulation_duty)
                    WaveKind.RANDOM -> listOf(R.string.simulation_min, R.string.simulation_max)
                    WaveKind.SEQUENCE -> emptyList()
                }
                val fields = listOf(RuleField.A, RuleField.B, RuleField.C, RuleField.D)
                val values = listOf(draft.a, draft.b, draft.c, draft.d)
                InputGrid(labels.mapIndexed { index, label ->
                    InputSpec(fields[index], label, values[index], ('a' + index).toString()) { value ->
                        model.updateRule { when (index) { 0 -> copy(a = value); 1 -> copy(b = value); 2 -> copy(c = value); else -> copy(d = value) } }
                    }
                }, draft)
                if (draft.kind == WaveKind.SEQUENCE) {
                    NumberInput(stringResource(R.string.simulation_sequence), draft.sequence, Modifier.fillMaxWidth().testTag("simulation_rule_sequence"), error = draft.errors[RuleField.SEQUENCE]?.resolve(), keyboardType = KeyboardType.Text, onValue = { value -> model.updateRule { copy(sequence = value) } })
                }
                if (draft.kind == WaveKind.RAMP) ToggleRow(R.string.simulation_wrap, draft.wrap, "simulation_rule_wrap") { model.updateRule { copy(wrap = !wrap) } }
                InputGrid(listOf(
                    InputSpec(RuleField.REFRESH, R.string.simulation_refresh, draft.refreshMs, "refresh") { model.updateRule { copy(refreshMs = it) } },
                    InputSpec(RuleField.NOISE, R.string.simulation_noise, draft.noise, "noise") { model.updateRule { copy(noise = it) } },
                ), draft)
                if (!draft.area.isBitArea()) {
                    InputGrid(listOf(
                        InputSpec(RuleField.CLAMP, R.string.simulation_clamp_min, draft.clampMin, "clamp_min") { model.updateRule { copy(clampMin = it) } },
                        InputSpec(RuleField.CLAMP, R.string.simulation_clamp_max, draft.clampMax, "clamp_max") { model.updateRule { copy(clampMax = it) } },
                    ), draft)
                }
                SectionLabel(R.string.simulation_external_write)
                ChipRow(ExternalWritePolicy.entries, { it == draft.policy }, { stringResource(if (it == ExternalWritePolicy.PAUSE) R.string.simulation_policy_pause else R.string.simulation_policy_continue) }, { next -> model.updateRule { copy(policy = next) } })
                Text(stringResource(R.string.simulation_policy_note), color = colors.muted, fontSize = 12.sp, lineHeight = 18.sp)
                ToggleRow(R.string.simulation_auto_start, draft.enabled, "simulation_rule_auto_start") { model.updateRule { copy(enabled = !enabled) } }
                if (model.saveFailed) Text(stringResource(R.string.simulation_save_failed), color = colors.error, fontSize = 13.sp, lineHeight = 19.sp)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.action_cancel), model::cancelRule, Modifier.weight(1f).testTag("simulation_rule_cancel"))
                PrimaryButton(stringResource(R.string.simulation_save), model::saveRule, Modifier.weight(1.6f).testTag("simulation_rule_save"))
            }
        }
        if (model.confirmDiscardRule) {
            InPageSheet(stringResource(R.string.simulation_discard_title), model::keepEditingRule, tag = "simulation_rule_discard_sheet") {
                Text(stringResource(R.string.simulation_discard_body), color = colors.muted, fontSize = 13.sp)
                SecondaryButton(stringResource(R.string.simulation_keep_editing), model::keepEditingRule, Modifier.testTag("simulation_rule_keep_editing"))
                PrimaryButton(stringResource(R.string.simulation_discard), model::discardRule, Modifier.testTag("simulation_rule_discard"), danger = true)
            }
        }
        if (confirmDelete) {
            InPageSheet(stringResource(R.string.simulation_delete_title), { confirmDelete = false }, tag = "simulation_rule_delete_sheet") {
                Text(stringResource(R.string.simulation_delete_body), color = colors.muted, fontSize = 13.sp)
                SecondaryButton(stringResource(R.string.action_cancel), { confirmDelete = false })
                PrimaryButton(stringResource(R.string.simulation_delete), { confirmDelete = false; model.deleteRule() }, Modifier.testTag("simulation_rule_delete_confirm"), danger = true)
            }
        }
    }
}

private data class InputSpec(val field: RuleField, val label: Int, val value: String, val tag: String, val onValue: (String) -> Unit)

/** Single column at narrow widths or larger fonts so labels and errors retain their space. */
@Composable
private fun InputGrid(inputs: List<InputSpec>, draft: RuleDraft) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= (300 * fontScale).dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            inputs.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { input ->
                        NumberInput(stringResource(input.label), input.value, Modifier.weight(1f).testTag("simulation_rule_${input.tag}"), error = draft.errors[input.field]?.resolve(), keyboardType = if (input.field == RuleField.ADDRESS || input.field == RuleField.COUNT || input.field == RuleField.REFRESH) KeyboardType.Number else KeyboardType.Text, onValue = input.onValue)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(label: Int) {
    Text(stringResource(label), color = LocalModbusColors.current.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ToggleRow(label: Int, checked: Boolean, tag: String, onToggle: () -> Unit) {
    val colors = LocalModbusColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).background(colors.surface1, RoundedCornerShape(12.dp)).toggleable(checked, role = Role.Switch, onValueChange = { onToggle() }).padding(12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(label), color = colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        ModbusSwitchVisual(checked)
    }
}

@Composable
private fun SimulationPreview(draft: RuleDraft) {
    val colors = LocalModbusColors.current
    val samples = remember(draft) { draft.build { "preview" }.first?.let { previewSamples(it) }.orEmpty() }
    val min = samples.minOfOrNull { it.second } ?: 0
    val max = samples.maxOfOrNull { it.second } ?: 0
    val description = if (samples.isEmpty()) stringResource(R.string.simulation_preview_invalid) else stringResource(R.string.simulation_preview_description, min, max)
    Column(Modifier.fillMaxWidth().background(colors.surface1, RoundedCornerShape(12.dp)).border(1.dp, colors.line, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.simulation_preview), color = colors.muted, fontSize = 12.sp)
        if (samples.isEmpty()) {
            Text(description, color = colors.muted, fontSize = 12.sp, modifier = Modifier.heightIn(min = 80.dp))
        } else {
            Text(stringResource(R.string.simulation_preview_range, min, max), color = colors.muted, fontSize = 11.sp)
            Canvas(Modifier.fillMaxWidth().height(84.dp).semantics { contentDescription = description }.testTag("simulation_rule_preview")) {
                val top = 6.dp.toPx()
                val h = size.height - 2 * top
                repeat(3) { index -> val y = top + h * index / 2; drawLine(colors.line, Offset(0f, y), Offset(size.width, y), 1.dp.toPx()) }
                val span = (max - min).coerceAtLeast(1).toFloat()
                val duration = samples.last().first.coerceAtLeast(1L).toFloat()
                val path = Path()
                samples.forEachIndexed { index, (time, value) ->
                    val x = size.width * time / duration
                    val y = if (max == min) size.height / 2 else top + h * (1 - (value - min) / span)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, colors.foreign, style = Stroke(2.dp.toPx()))
            }
        }
        Text(stringResource(R.string.simulation_preview_note), color = colors.dim, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun simulationAreaName(area: RegisterArea): String = stringResource(when (area) {
    RegisterArea.COIL -> R.string.area_short_coil
    RegisterArea.DISCRETE_INPUT -> R.string.area_short_discrete
    RegisterArea.HOLDING_REGISTER -> R.string.area_short_holding
    RegisterArea.INPUT_REGISTER -> R.string.area_short_input
})
