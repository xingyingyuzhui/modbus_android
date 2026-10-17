package io.github.xingyingyuzhui.modbusmobile.ui.mine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.xingyingyuzhui.modbusmobile.R
import io.github.xingyingyuzhui.modbusmobile.application.SessionManager
import io.github.xingyingyuzhui.modbusmobile.application.SessionState
import io.github.xingyingyuzhui.modbusmobile.application.TrafficRecorder
import io.github.xingyingyuzhui.modbusmobile.application.describeFrame
import io.github.xingyingyuzhui.modbusmobile.application.export.ArchiveInput
import io.github.xingyingyuzhui.modbusmobile.application.export.ArchivePart
import io.github.xingyingyuzhui.modbusmobile.application.export.PointValueExport
import io.github.xingyingyuzhui.modbusmobile.application.export.archiveBytes
import io.github.xingyingyuzhui.modbusmobile.application.export.pointsForExport
import io.github.xingyingyuzhui.modbusmobile.application.history.HistoryCatalog
import io.github.xingyingyuzhui.modbusmobile.application.panel.PointDefinition
import io.github.xingyingyuzhui.modbusmobile.application.panel.PointSnapshot
import io.github.xingyingyuzhui.modbusmobile.application.panel.PointStore
import io.github.xingyingyuzhui.modbusmobile.application.panel.Project
import io.github.xingyingyuzhui.modbusmobile.application.panel.ProjectJson
import io.github.xingyingyuzhui.modbusmobile.application.panel.ProjectRepository
import io.github.xingyingyuzhui.modbusmobile.application.panel.Quality
import io.github.xingyingyuzhui.modbusmobile.application.slave.SlaveSetup
import io.github.xingyingyuzhui.modbusmobile.application.slave.SlaveSetupJson
import io.github.xingyingyuzhui.modbusmobile.application.slave.SlaveSetupStore
import io.github.xingyingyuzhui.modbusmobile.ui.LocalHistoryCatalog
import io.github.xingyingyuzhui.modbusmobile.ui.LocalPanelRuntime
import io.github.xingyingyuzhui.modbusmobile.ui.LocalProjectRepository
import io.github.xingyingyuzhui.modbusmobile.ui.LocalSession
import io.github.xingyingyuzhui.modbusmobile.ui.LocalSlaveSetupStore
import io.github.xingyingyuzhui.modbusmobile.ui.LocalTrafficRecorder
import io.github.xingyingyuzhui.modbusmobile.ui.components.InPageSheet
import io.github.xingyingyuzhui.modbusmobile.ui.components.PrimaryButton
import io.github.xingyingyuzhui.modbusmobile.ui.components.SecondaryButton
import io.github.xingyingyuzhui.modbusmobile.ui.theme.LocalModbusColors
import io.github.xingyingyuzhui.modbusmobile.ui.traffic.ExportFormat
import io.github.xingyingyuzhui.modbusmobile.ui.traffic.LogEntry
import io.github.xingyingyuzhui.modbusmobile.ui.traffic.LogItem
import io.github.xingyingyuzhui.modbusmobile.ui.traffic.LogMarker
import io.github.xingyingyuzhui.modbusmobile.ui.traffic.writeLogExport
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 测试可以换成自己的实现。为空时用系统的保存和分享。 */
val LocalMineExport = staticCompositionLocalOf<MineExport?> { null }

interface MineExport {
    fun save(parts: Set<ArchivePart>)
    fun share(parts: Set<ArchivePart>)
    suspend fun pointCount(): Int
}

/** 从工程、从站配置、历史库和内存日志收集导出内容。阻塞的文件读写由调用方放在 IO 线程。 */
internal class ArchiveGatherer(
    private val projects: ProjectRepository?,
    private val slaves: SlaveSetupStore?,
    private val history: HistoryCatalog,
    private val session: SessionManager,
    private val points: PointStore?,
    private val traffic: TrafficRecorder?,
    private val zone: TimeZone = TimeZone.getDefault(),
) {
    fun pointCount(): Int = pointsForExport(loadProject()).size

    suspend fun build(parts: Set<ArchivePart>): ByteArray {
        val project = loadProject()
        val defined = pointsForExport(project)
        val savedJson = slaves?.exportJson() ?: SlaveSetupJson.encode(SlaveSetup.default())
        val saved = runCatching { SlaveSetupJson.decode(savedJson).setup }.getOrNull()
        val snaps = points?.snapshots?.value.orEmpty()
        val samples = if (ArchivePart.HISTORY in parts) {
            history.overview().sessions.flatMap { history.samples(it.id) }
        } else {
            emptyList()
        }
        return archiveBytes(
            parts,
            ArchiveInput(
                zone = zone,
                projectJson = projects?.exportJson() ?: ProjectJson.encode(project),
                slaveJson = savedJson,
                points = defined,
                savedSlave = saved,
                liveSlave = liveSetup(),
                pointValues = defined.map { pointValue(it, snaps[it.id]) },
                samples = samples,
                trafficCsv = if (ArchivePart.TRAFFIC in parts) trafficBytes(ExportFormat.CSV) else null,
                trafficTxt = if (ArchivePart.TRAFFIC in parts) trafficBytes(ExportFormat.TXT) else null,
            ),
        )
    }

    private fun loadProject(): Project {
        val json = projects?.exportJson() ?: return Project.empty()
        return runCatching { ProjectJson.decode(json).project }.getOrDefault(Project.empty())
    }

    private fun liveSetup(): SlaveSetup? {
        val running = session.state.value as? SessionState.Running ?: return null
        if (running.role != io.github.xingyingyuzhui.modbusmobile.core.model.Role.SLAVE) return null
        val bank = session.slaveBank.value ?: return null
        val units = session.slaveUnits.value
        if (units.isEmpty()) return null
        return SlaveSetup.snapshot(bank, units, emptyList())
    }

    private fun pointValue(point: PointDefinition, snap: PointSnapshot?): PointValueExport = PointValueExport(
        unit = point.unitId,
        area = point.area.name,
        address = point.address,
        dataType = point.type.name,
        quality = (snap?.quality ?: Quality.NO_DATA).name,
        engineering = snap?.engineering,
        text = snap?.text,
        wallMs = snap?.updatedWallMs,
    )

    private fun trafficBytes(format: ExportFormat): ByteArray {
        val recorder = traffic
        val items: List<LogItem> = if (recorder == null) {
            emptyList()
        } else {
            val origin = recorder.originNs
            recorder.snapshot().map { item ->
                val event = item.event
                if (event == null) {
                    LogMarker(item.seq, item.wallMs, item.epoch)
                } else {
                    val start = origin ?: event.monotonicNs
                    LogEntry(
                        seq = item.seq,
                        event = event,
                        info = describeFrame(event.bytes, event.kind),
                        wallMs = item.wallMs,
                        offsetMs = ((event.monotonicNs - start) / 1_000_000L).coerceAtLeast(0),
                    )
                }
            }
        }
        return ByteArrayOutputStream().also { writeLogExport(it, items, format, zone) }.toByteArray()
    }
}

private class DeviceExport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val gatherer: ArchiveGatherer,
    private val launchSave: (String) -> Unit,
) : MineExport {
    private var pending: ByteArray? = null

    override suspend fun pointCount(): Int = withContext(Dispatchers.IO) { gatherer.pointCount() }

    override fun save(parts: Set<ArchivePart>) {
        scope.launch {
            val bytes = runCatching { withContext(Dispatchers.IO) { gatherer.build(parts) } }
            val body = bytes.getOrElse {
                toast(context.getString(R.string.export_failed, it.message ?: ""))
                return@launch
            }
            pending = body
            launchSave(exportZipName(System.currentTimeMillis()))
        }
    }

    override fun share(parts: Set<ArchivePart>) {
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = gatherer.build(parts)
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    dir.listFiles()?.filter { it.name.startsWith("modbus-export-") && it.name.endsWith(".zip") }?.forEach { it.delete() }
                    val file = File(dir, exportZipName(System.currentTimeMillis()))
                    file.writeBytes(bytes)
                    FileProvider.getUriForFile(context, context.packageName + ".exports", file)
                }
            }
            result.onSuccess { uri ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                toast(context.getString(R.string.export_failed, it.message ?: ""))
            }
        }
    }

    fun write(uri: Uri?) {
        val bytes = pending
        pending = null
        if (uri == null || bytes == null) return
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法打开目标文件")
                }
            }
            toast(
                result.exceptionOrNull()?.let { context.getString(R.string.export_failed, it.message ?: "") }
                    ?: context.getString(R.string.export_saved),
            )
        }
    }

    private fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}

internal fun exportZipName(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String =
    "modbus-export-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = zone }.format(Date(nowMs)) + ".zip"

@Composable
internal fun rememberDeviceExport(): MineExport {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projects = LocalProjectRepository.current
    val slaves = LocalSlaveSetupStore.current
    val history = LocalHistoryCatalog.current
    val session = LocalSession.current
    val store = LocalPanelRuntime.current?.store
    val traffic = LocalTrafficRecorder.current
    val gatherer = remember(projects, slaves, history, session, store, traffic) {
        ArchiveGatherer(projects, slaves, history, session, store, traffic)
    }
    var holder: DeviceExport? = null
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
        holder?.write(it)
    }
    val device = remember(gatherer, context) {
        DeviceExport(context, scope, gatherer, launcher::launch)
    }
    holder = device
    return device
}

@Composable
internal fun ExportSheet(
    pointCount: Int,
    onSave: (Set<ArchivePart>) -> Unit,
    onShare: (Set<ArchivePart>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf("PROJECT,SLAVE,POINTS,HISTORY") }
    val parts = selected.split(',').filter { it.isNotEmpty() }.mapNotNull { name ->
        ArchivePart.entries.firstOrNull { it.name == name }
    }.toSet()
    fun toggle(part: ArchivePart) {
        val next = if (part in parts) parts - part else parts + part
        selected = ArchivePart.entries.filter { it in next }.joinToString(",") { it.name }
    }
    InPageSheet(stringResource(R.string.export_title), onDismiss, tag = "export-sheet") {
        Column(
            Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CheckRow(ArchivePart.PROJECT, stringResource(R.string.export_project), stringResource(R.string.export_project_sub), parts, ::toggle)
            CheckRow(ArchivePart.SLAVE, stringResource(R.string.export_slave), stringResource(R.string.export_slave_sub), parts, ::toggle)
            CheckRow(ArchivePart.POINTS, stringResource(R.string.export_points), stringResource(R.string.export_points_sub, pointCount), parts, ::toggle)
            CheckRow(ArchivePart.REGISTERS, stringResource(R.string.export_registers), stringResource(R.string.export_registers_sub), parts, ::toggle)
            CheckRow(ArchivePart.HISTORY, stringResource(R.string.export_history), stringResource(R.string.export_history_sub), parts, ::toggle)
            CheckRow(ArchivePart.TRAFFIC, stringResource(R.string.export_traffic), stringResource(R.string.export_traffic_sub), parts, ::toggle)
        }
        Text(
            stringResource(if (ArchivePart.TRAFFIC in parts) R.string.export_privacy else R.string.export_offline),
            color = LocalModbusColors.current.dim,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                stringResource(R.string.export_save),
                { if (parts.isNotEmpty()) onSave(parts) },
                Modifier.weight(1f).testTag("export-save"),
                enabled = parts.isNotEmpty(),
                compact = true,
            )
            PrimaryButton(
                stringResource(R.string.export_share),
                { if (parts.isNotEmpty()) onShare(parts) },
                Modifier.weight(1f).testTag("export-share"),
                enabled = parts.isNotEmpty(),
                compact = true,
            )
        }
    }
}

@Composable
private fun CheckRow(
    part: ArchivePart,
    title: String,
    subtitle: String,
    selected: Set<ArchivePart>,
    onToggle: (ArchivePart) -> Unit,
) {
    val colors = LocalModbusColors.current
    val on = part in selected
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("export-part-" + part.name)
            .toggleable(value = on, role = Role.Checkbox, onValueChange = { onToggle(part) })
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BoxMark(on)
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = colors.muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun BoxMark(on: Boolean) {
    val colors = LocalModbusColors.current
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .size(18.dp)
            .clip(shape)
            .background(if (on) colors.accent else colors.surface1)
            .border(1.dp, if (on) colors.accent else colors.line, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Text("✓", color = colors.onAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
