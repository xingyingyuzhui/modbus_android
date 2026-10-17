package io.github.xingyingyuzhui.modbusmobile.application.history

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 把点位快照写进 [HistoryStore]。
 *
 * [offer]、[onSessionStarted]、[onSessionEnded] 只把命令放进队列就返回，不碰主站引擎的锁，也不等待磁盘。
 * 队列里的样本达到 [HistoryPolicy.QUEUE_LIMIT] 时丢掉新样本并计数。会话的打开和关闭不占这个名额。
 */
class HistoryRecorder(
    private val store: HistoryStore,
    scope: CoroutineScope,
    private val policy: () -> HistoryPolicy,
    private val nowMs: () -> Long,
    private val queueLimit: Int = HistoryPolicy.QUEUE_LIMIT,
) {
    private val lock = Any()
    private val pending = ArrayDeque<Command>()
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val lastWritten = mutableMapOf<String, Observation>()
    private var openSessionId: Long? = null
    private var openEpoch: Long? = null
    private var droppedCount = 0L

    private val _state = MutableStateFlow(RecordingState.IDLE)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    val dropped: Long get() = synchronized(lock) { droppedCount }

    private val job = scope.launch {
        while (isActive) {
            kick.receive()
            drain()
        }
    }

    fun onSessionStarted(role: String, target: String, epoch: Long, startedAtMs: Long) {
        enqueue(Command.Open(role, target, epoch, startedAtMs), droppable = false)
    }

    fun onSessionEnded(epoch: Long, endedAtMs: Long) {
        enqueue(Command.Close(epoch, endedAtMs), droppable = false)
    }

    fun offer(epoch: Long, observation: Observation) {
        val finite = finiteOrNull(observation.engineering)
        val quality = if (observation.engineering != null && finite == null) SampleQuality.ERROR else observation.quality
        enqueue(Command.Sample(epoch, observation.copy(quality = quality, engineering = finite)), droppable = true)
    }

    fun close() {
        job.cancel()
    }

    private fun enqueue(command: Command, droppable: Boolean) {
        synchronized(lock) {
            val stopped = _state.value == RecordingState.STOPPED_STORAGE
            if (droppable && (stopped || pending.size >= queueLimit)) {
                droppedCount += 1
                return
            }
            pending.addLast(command)
        }
        kick.trySend(Unit)
    }

    private suspend fun drain() {
        while (true) {
            val batch = synchronized(lock) {
                if (pending.isEmpty()) return
                val copy = pending.toList()
                pending.clear()
                copy
            }
            for (command in batch) apply(command)
        }
    }

    private suspend fun apply(command: Command) {
        when (command) {
            is Command.Open -> {
                val session = write { store.openSession(command.role, command.target, command.epoch, command.startedAtMs) }
                    ?: return
                openSessionId = session.id
                openEpoch = command.epoch
                lastWritten.clear()
                _state.value = RecordingState.RECORDING
                prune()
            }
            is Command.Close -> {
                val id = openSessionId ?: return
                if (openEpoch != command.epoch) return
                openSessionId = null
                openEpoch = null
                lastWritten.clear()
                write { store.closeSession(id, command.endedAtMs) }
                if (_state.value != RecordingState.STOPPED_STORAGE) _state.value = RecordingState.IDLE
            }
            is Command.Sample -> record(command)
        }
    }

    private suspend fun record(command: Command.Sample) {
        if (_state.value == RecordingState.STOPPED_STORAGE) {
            noteDrop()
            return
        }
        val sessionId = openSessionId ?: return
        val current = policy()
        val next = command.observation
        if (!shouldRecord(lastWritten[next.pointId], next, current.interval)) return
        val accepted = next.quality == SampleQuality.OK
        val sample = HistorySample(
            sessionId = sessionId,
            pointId = next.pointId,
            sampleAtMs = next.sampleAtMs,
            recordedAtMs = nowMs(),
            epoch = command.epoch,
            quality = next.quality,
            engineering = if (accepted) next.engineering else null,
            text = if (accepted) next.text else null,
        )
        if (write { store.insertSamples(listOf(sample)) } == null) return
        lastWritten[next.pointId] = next.copy(engineering = sample.engineering, text = sample.text)
        prune()
    }

    private suspend fun prune() {
        val current = policy()
        val openId = openSessionId
        val expired = sessionsEligibleToDelete(store.sessions(), nowMs(), current.retentionDays, openId)
        if (expired.isNotEmpty()) store.deleteSessions(expired)
        while (store.usageBytes() > current.capacityBytes) {
            val victim = oldestDeletableSession(store.sessions(), openId) ?: break
            val before = store.usageBytes()
            store.deleteSessions(listOf(victim.id))
            if (store.usageBytes() >= before) break
        }
        if (store.usageBytes() > current.capacityBytes) _state.value = RecordingState.STOPPED_STORAGE
    }

    private fun noteDrop() {
        synchronized(lock) { droppedCount += 1 }
    }

    private suspend inline fun <T> write(block: suspend () -> T): T? =
        try {
            block()
        } catch (_: StorageFullException) {
            _state.value = RecordingState.STOPPED_STORAGE
            null
        }

    private sealed interface Command {
        data class Open(val role: String, val target: String, val epoch: Long, val startedAtMs: Long) : Command
        data class Close(val epoch: Long, val endedAtMs: Long) : Command
        data class Sample(val epoch: Long, val observation: Observation) : Command
    }
}
