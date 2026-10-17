package io.github.xingyingyuzhui.modbusmobile.ui.workbench

import io.github.xingyingyuzhui.modbusmobile.R
import io.github.xingyingyuzhui.modbusmobile.application.slave.isBitArea
import io.github.xingyingyuzhui.modbusmobile.core.model.RegisterArea
import io.github.xingyingyuzhui.modbusmobile.core.slave.ExternalWritePolicy
import io.github.xingyingyuzhui.modbusmobile.core.slave.SimulationRule
import io.github.xingyingyuzhui.modbusmobile.core.slave.SimulationTarget
import io.github.xingyingyuzhui.modbusmobile.core.slave.Waveform
import io.github.xingyingyuzhui.modbusmobile.core.slave.Waveforms
import io.github.xingyingyuzhui.modbusmobile.ui.UiText
import kotlin.random.Random

/** 规则编辑页里会报错的字段。 */
enum class RuleField { UNIT, ADDRESS, COUNT, A, B, C, D, SEQUENCE, REFRESH, NOISE, CLAMP }

/**
 * 模拟规则编辑页（b5-5）的草稿，全部是用户输入的原文，点“保存”时才校验成 [SimulationRule]。
 * 参数 [a]..[d] 的含义随波形变：
 * - 常量：a = 值
 * - 递增：a = 起始，b = 每次加，c = 下限，d = 上限，[wrap] = 越界回绕
 * - 正弦：a = 中心，b = 幅度，c = 周期（秒），d = 相位（度）
 * - 方波：a = 低值，b = 高值，c = 周期（秒），d = 高值占比（%）
 * - 随机：a = 下限，b = 上限
 * - 序列：[sequence] = 逗号分隔的数列
 */
data class RuleDraft(
    /** null = 新建。 */
    val id: String?,
    val unit: Int,
    val area: RegisterArea,
    val address: String,
    val count: String = "1",
    val kind: WaveKind = WaveKind.SINE,
    val a: String = "",
    val b: String = "",
    val c: String = "",
    val d: String = "",
    val wrap: Boolean = true,
    val sequence: String = "",
    val refreshMs: String = "500",
    val noise: String = "0",
    val clampMin: String = "0",
    val clampMax: String = "65535",
    val policy: ExternalWritePolicy = ExternalWritePolicy.PAUSE,
    val enabled: Boolean = true,
    val errors: Map<RuleField, UiText> = emptyMap(),
) {
    /** 换波形时给一组合理的默认参数，不保留上一种波形的数字（含义不同，留着容易误用）。 */
    fun withKind(next: WaveKind): RuleDraft {
        if (next == kind) return this
        val bit = area.isBitArea()
        return when (next) {
            WaveKind.CONSTANT -> copy(kind = next, a = if (bit) "1" else "100", b = "", c = "", d = "")
            WaveKind.RAMP -> copy(kind = next, a = "0", b = "1", c = "0", d = if (bit) "1" else "100", wrap = true)
            WaveKind.SINE -> copy(kind = next, a = if (bit) "0" else "100", b = if (bit) "1" else "50", c = "10", d = "0")
            WaveKind.SQUARE -> copy(kind = next, a = "0", b = if (bit) "1" else "100", c = "2", d = "50")
            WaveKind.RANDOM -> copy(kind = next, a = "0", b = if (bit) "1" else "100", c = "", d = "")
            WaveKind.SEQUENCE -> copy(kind = next, sequence = if (bit) "0, 1" else "0, 50, 100", a = "", b = "", c = "", d = "")
        }.copy(errors = emptyMap())
    }

    /** 校验并生成规则；有错时返回 null，并把错误写进 [errors]。[newId] 只在新建时用。 */
    fun build(newId: () -> String): Pair<SimulationRule?, RuleDraft> {
        val errors = LinkedHashMap<RuleField, UiText>()
        if (unit !in 1..247) errors[RuleField.UNIT] = UiText.Of(R.string.error_slave_unit_range)
        fun number(field: RuleField, text: String): Double? = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && kotlin.math.abs(it) <= MaxMagnitude }
            ?: null.also { errors[field] = UiText.Of(R.string.rule_err_number) }
        val address = address.trim().toIntOrNull()?.takeIf { it in 0..0xFFFF }
            ?: null.also { errors[RuleField.ADDRESS] = UiText.Of(R.string.rule_err_address) }
        val count = count.trim().toIntOrNull()?.takeIf { it in 1..125 }
            ?: null.also { errors[RuleField.COUNT] = UiText.Of(R.string.rule_err_count) }
        if (address != null && count != null && address.toLong() + count - 1 > 0xFFFF) {
            errors[RuleField.COUNT] = UiText.Of(R.string.rule_err_span)
        }
        val waveform: Waveform? = when (kind) {
            WaveKind.CONSTANT -> number(RuleField.A, a)?.let { Waveform.Constant(it) }
            WaveKind.RAMP -> {
                val start = number(RuleField.A, a)
                val step = number(RuleField.B, b)
                val min = number(RuleField.C, c)
                val max = number(RuleField.D, d)
                if (min != null && max != null && max < min) errors[RuleField.D] = UiText.Of(R.string.rule_err_max_min)
                if (start != null && step != null && min != null && max != null && max >= min) Waveform.Ramp(start, step, min, max, wrap) else null
            }
            WaveKind.SINE -> {
                val center = number(RuleField.A, a)
                val amplitude = number(RuleField.B, b)
                val period = number(RuleField.C, c)
                val phase = number(RuleField.D, d)
                val periodMs = period?.let { (it * 1_000).toLong() }
                if (periodMs != null && periodMs < MinPeriodMs) errors[RuleField.C] = UiText.Of(R.string.rule_err_period)
                if (center != null && amplitude != null && periodMs != null && periodMs >= MinPeriodMs && phase != null) {
                    Waveform.Sine(center, amplitude, periodMs, phase)
                } else null
            }
            WaveKind.SQUARE -> {
                val low = number(RuleField.A, a)
                val high = number(RuleField.B, b)
                val periodMs = number(RuleField.C, c)?.let { (it * 1_000).toLong() }
                val duty = number(RuleField.D, d)
                if (periodMs != null && periodMs < MinPeriodMs) errors[RuleField.C] = UiText.Of(R.string.rule_err_period)
                if (duty != null && duty !in 0.0..100.0) errors[RuleField.D] = UiText.Of(R.string.rule_err_duty)
                if (low != null && high != null && periodMs != null && periodMs >= MinPeriodMs && duty != null && duty in 0.0..100.0) {
                    Waveform.Square(low, high, periodMs, duty / 100.0)
                } else null
            }
            WaveKind.RANDOM -> {
                val min = number(RuleField.A, a)
                val max = number(RuleField.B, b)
                if (min != null && max != null && max < min) errors[RuleField.B] = UiText.Of(R.string.rule_err_max_min)
                if (min != null && max != null && max >= min) Waveform.RandomUniform(min, max) else null
            }
            WaveKind.SEQUENCE -> {
                val values = sequence.split(',', '，', ' ', '\n').filter { it.isNotBlank() }.map { it.trim().toDoubleOrNull() }
                if (values.isEmpty() || values.any { it == null || !it.isFinite() || kotlin.math.abs(it) > MaxMagnitude } || values.size > MaxSequence) {
                    errors[RuleField.SEQUENCE] = UiText.Of(R.string.rule_err_sequence, listOf(MaxSequence))
                    null
                } else Waveform.Sequence(values.map { it!! })
            }
        }
        val refresh = refreshMs.trim().toLongOrNull()?.takeIf { it >= SimulationRule.MIN_REFRESH_MS && it <= MaxRefreshMs }
            ?: null.also { errors[RuleField.REFRESH] = UiText.Of(R.string.rule_err_refresh, listOf(SimulationRule.MIN_REFRESH_MS, MaxRefreshMs)) }
        val noise = noise.trim().toDoubleOrNull()?.takeIf { it in 0.0..MaxMagnitude && it.isFinite() }
            ?: null.also { errors[RuleField.NOISE] = UiText.Of(R.string.rule_err_noise) }
        val bit = area.isBitArea()
        val lo = if (bit) 0.0 else clampMin.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        val hi = if (bit) 1.0 else clampMax.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        if (lo == null || hi == null || lo < 0 || hi > 65_535 || hi < lo) errors[RuleField.CLAMP] = UiText.Of(R.string.rule_err_clamp)
        if (errors.isNotEmpty() || address == null || count == null || waveform == null || refresh == null || noise == null || lo == null || hi == null) {
            return null to copy(errors = errors)
        }
        val rule = SimulationRule(
            id = id ?: newId(),
            target = SimulationTarget(unit, area, address, count),
            waveform = waveform,
            refreshMs = refresh,
            noise = noise,
            clampMin = lo,
            clampMax = hi,
            onExternalWrite = policy,
            enabled = enabled,
        )
        return rule to copy(errors = emptyMap())
    }

    companion object {
        const val MinPeriodMs = 100L
        const val MaxRefreshMs = 3_600_000L
        const val MaxSequence = 256
        // 留出派生计算余量，避免有限输入在波形运算中溢出成 NaN。
        const val MaxMagnitude = 1e12

        fun newFor(unit: Int, area: RegisterArea, address: Int): RuleDraft =
            RuleDraft(id = null, unit = unit, area = area, address = address.toString(), kind = WaveKind.CONSTANT).withKind(WaveKind.SINE)

        fun from(rule: SimulationRule): RuleDraft {
            val base = RuleDraft(
                id = rule.id,
                unit = rule.target.unitId,
                area = rule.target.area,
                address = rule.target.address.toString(),
                count = rule.target.count.toString(),
                refreshMs = rule.refreshMs.toString(),
                noise = num(rule.noise),
                clampMin = num(rule.clampMin),
                clampMax = num(rule.clampMax),
                policy = rule.onExternalWrite,
                enabled = rule.enabled,
            )
            return when (val w = rule.waveform) {
                is Waveform.Constant -> base.copy(kind = WaveKind.CONSTANT, a = num(w.value))
                is Waveform.Ramp -> base.copy(kind = WaveKind.RAMP, a = num(w.start), b = num(w.step), c = num(w.min), d = num(w.max), wrap = w.wrap)
                is Waveform.Sine -> base.copy(kind = WaveKind.SINE, a = num(w.center), b = num(w.amplitude), c = num(w.periodMs / 1_000.0), d = num(w.phaseDeg))
                is Waveform.Square -> base.copy(kind = WaveKind.SQUARE, a = num(w.low), b = num(w.high), c = num(w.periodMs / 1_000.0), d = num(w.duty * 100))
                is Waveform.RandomUniform -> base.copy(kind = WaveKind.RANDOM, a = num(w.min), b = num(w.max))
                is Waveform.Sequence -> base.copy(kind = WaveKind.SEQUENCE, sequence = w.values.joinToString(", ") { num(it) })
            }
        }

        /** 整数不带小数点（“100” 而不是 “100.0”）。 */
        fun num(value: Double): String =
            if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()
    }
}

/**
 * 预览：未来 [windowMs] 内每次刷新写进去的原始值（不含叠加随机，随机波形用固定种子，所以图是稳定的）。
 * 返回 (时间 ms, 原始值)，最多 [maxPoints] 个点。
 */
fun previewSamples(rule: SimulationRule, windowMs: Long = 20_000, maxPoints: Int = 400): List<Pair<Long, Int>> {
    if (windowMs < 0 || maxPoints <= 0) return emptyList()
    val quiet = rule.copy(noise = 0.0)
    val random = Random(1)
    val lastTick = windowMs / rule.refreshMs
    // 抽样仍落在真实刷新时刻；Ramp / Sequence 的 step 与时间属于同一拍。
    val intervals = (maxPoints - 1).toLong()
    val stride = if (intervals <= 0) 1L else
        (lastTick / intervals + if (lastTick % intervals == 0L) 0 else 1).coerceAtLeast(1L)
    val out = ArrayList<Pair<Long, Int>>()
    var tick = 0L
    while (tick <= lastTick && out.size < maxPoints) {
        val t = tick * rule.refreshMs
        val value = Waveforms.value(quiet.waveform, t, tick, random)
        out += t to Waveforms.raw(quiet, value, random)
        if (lastTick - tick < stride) break
        tick += stride
    }
    return out
}
