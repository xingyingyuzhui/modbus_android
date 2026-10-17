package io.github.xingyingyuzhui.modbusmobile.application.history

/** 非有限工程值不能入库。调用方应改记为错误；这里再挡一层。 */
internal fun finiteOrNull(value: Double?): Double? = value?.takeIf { it.isFinite() }

/**
 * 是否该为这个点写一条新快照。
 * 第一条总是写。定时模式下未到间隔就不写。仅变化模式在质量、工程值、文本都没变时不写。
 */
fun shouldRecord(last: Observation?, next: Observation, interval: HistoryInterval): Boolean {
    if (last == null) return true
    if (interval == HistoryInterval.ON_CHANGE) {
        return last.quality != next.quality ||
            finiteOrNull(last.engineering) != finiteOrNull(next.engineering) ||
            last.text != next.text
    }
    val period = interval.periodMs ?: return false
    return next.sampleAtMs - last.sampleAtMs >= period
}

/**
 * 可以删掉的会话：未标记保留，并且不是当前还开着的会话。
 * 先按保留天数，再在容量仍超限时从最旧的开始。容量是否降下来由调用方删完再量。
 */
fun sessionsEligibleToDelete(
    sessions: List<HistorySession>,
    nowMs: Long,
    retentionDays: Int,
    openSessionId: Long?,
): List<Long> {
    val cutoff = nowMs - retentionDays.toLong() * 86_400_000L
    return sessions
        .asSequence()
        .filter { it.id != openSessionId && !it.retained && it.startedAtMs < cutoff }
        .sortedBy { it.startedAtMs }
        .map { it.id }
        .toList()
}

fun oldestDeletableSession(sessions: List<HistorySession>, openSessionId: Long?): HistorySession? =
    sessions
        .asSequence()
        .filter { it.id != openSessionId && !it.retained }
        .minByOrNull { it.startedAtMs }
