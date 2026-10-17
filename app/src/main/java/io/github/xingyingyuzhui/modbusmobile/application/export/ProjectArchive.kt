package io.github.xingyingyuzhui.modbusmobile.application.export

import io.github.xingyingyuzhui.modbusmobile.application.history.HistorySample
import io.github.xingyingyuzhui.modbusmobile.application.panel.PointDefinition
import io.github.xingyingyuzhui.modbusmobile.application.panel.Project
import io.github.xingyingyuzhui.modbusmobile.application.panel.WidgetKinds
import io.github.xingyingyuzhui.modbusmobile.application.slave.SlaveRegion
import io.github.xingyingyuzhui.modbusmobile.application.slave.SlaveSetup
import io.github.xingyingyuzhui.modbusmobile.application.slave.isBitArea
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 导出 ZIP 里可以勾选的部分。完整通信帧不在历史库里，通信日志只来自内存记录。 */
enum class ArchivePart {
    PROJECT,
    SLAVE,
    POINTS,
    REGISTERS,
    HISTORY,
    TRAFFIC,
}

object ArchiveNames {
    const val PROJECT = "default.json"
    const val SLAVE = "slave.json"
    const val POINTS = "points.csv"
    const val REGISTERS = "register-snapshot.csv"
    const val HISTORY = "history.csv"
    const val TRAFFIC_CSV = "traffic.csv"
    const val TRAFFIC_TXT = "traffic.txt"
}

const val POINTS_HEADER =
    "id,name,unit,area,address,dataType,order,bit,quantity,unitLabel,pollMs,timeZone,quality,timeSource"

const val REGISTERS_HEADER =
    "source,unit,area,address,raw,engineering,text,dataType,quality,sampleAt,timeZone,timeSource"

const val HISTORY_HEADER =
    "sessionId,pointId,sampleAt,recordedAt,epoch,quality,engineering,text,dataType,timeZone,timeSource"

/** 主站点位的最后值。一行一个点，不按寄存器个数展开。 */
data class PointValueExport(
    val unit: Int,
    val area: String,
    val address: Int,
    val dataType: String,
    val quality: String,
    val engineering: Double?,
    val text: String?,
    val wallMs: Long?,
)

class ArchiveInput(
    val zone: TimeZone,
    val projectJson: String? = null,
    val slaveJson: String? = null,
    val points: List<PointDefinition> = emptyList(),
    val savedSlave: SlaveSetup? = null,
    val liveSlave: SlaveSetup? = null,
    val pointValues: List<PointValueExport> = emptyList(),
    val samples: List<HistorySample> = emptyList(),
    val pointTypes: Map<String, String> = emptyMap(),
    val trafficCsv: ByteArray? = null,
    val trafficTxt: ByteArray? = null,
)

/** 点表含写入按钮的点；轮询用的 [Project.usablePoints] 不含按钮。 */
fun pointsForExport(project: Project): List<PointDefinition> =
    project.panels.flatMap { panel ->
        panel.widgets.filter { it.isUsable }.flatMap { widget ->
            if (widget.kind == WidgetKinds.BUTTON) listOfNotNull(widget.point) else widget.points
        }
    }

fun formatExportTime(epochMs: Long, zone: TimeZone): String {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    format.timeZone = zone
    return format.format(Date(epochMs))
}

fun writeArchive(out: OutputStream, parts: Set<ArchivePart>, input: ArchiveInput) {
    val zip = ZipOutputStream(out)
    val types = input.points.associate { it.id to it.type.name } + input.pointTypes
    if (ArchivePart.PROJECT in parts) input.projectJson?.let { zip.bytes(ArchiveNames.PROJECT, it.toByteArray(Charsets.UTF_8)) }
    if (ArchivePart.SLAVE in parts) input.slaveJson?.let { zip.bytes(ArchiveNames.SLAVE, it.toByteArray(Charsets.UTF_8)) }
    if (ArchivePart.POINTS in parts) zip.csv(ArchiveNames.POINTS, POINTS_HEADER, pointRows(input.points, input.zone))
    if (ArchivePart.REGISTERS in parts) {
        zip.csv(ArchiveNames.REGISTERS, REGISTERS_HEADER, registerRows(input.savedSlave, input.liveSlave, input.pointValues, input.zone))
    }
    if (ArchivePart.HISTORY in parts) zip.csv(ArchiveNames.HISTORY, HISTORY_HEADER, historyRows(input.samples, types, input.zone))
    if (ArchivePart.TRAFFIC in parts) {
        input.trafficCsv?.let { zip.bytes(ArchiveNames.TRAFFIC_CSV, it) }
        input.trafficTxt?.let { zip.bytes(ArchiveNames.TRAFFIC_TXT, it) }
    }
    zip.finish()
    zip.flush()
}

fun archiveBytes(parts: Set<ArchivePart>, input: ArchiveInput): ByteArray =
    java.io.ByteArrayOutputStream().also { writeArchive(it, parts, input) }.toByteArray()

private fun pointRows(points: List<PointDefinition>, zone: TimeZone): List<List<String>> =
    points.map { point ->
        listOf(
            point.id,
            point.name,
            point.unitId.toString(),
            point.area.name,
            point.address.toString(),
            point.type.name,
            point.order.name,
            point.bit.toString(),
            point.quantity.toString(),
            point.unitLabel,
            point.pollMs.toString(),
            zone.id,
            "",
            "definition",
        )
    }

private fun registerRows(
    saved: SlaveSetup?,
    live: SlaveSetup?,
    points: List<PointValueExport>,
    zone: TimeZone,
): List<List<String>> {
    val rows = ArrayList<List<String>>()
    saved?.let { rows += regionRows("saved", it, "file", zone) }
    live?.let { rows += regionRows("live", it, "memory", zone) }
    points.forEach { point ->
        rows += listOf(
            "point",
            point.unit.toString(),
            point.area,
            point.address.toString(),
            "",
            point.engineering?.let(::formatExportNumber).orEmpty(),
            point.text.orEmpty(),
            point.dataType,
            point.quality,
            point.wallMs?.let { formatExportTime(it, zone) }.orEmpty(),
            zone.id,
            if (point.wallMs != null) "wall" else "none",
        )
    }
    return rows
}

private fun regionRows(source: String, setup: SlaveSetup, timeSource: String, zone: TimeZone): List<List<String>> {
    val rows = ArrayList<List<String>>()
    for (unit in setup.units) {
        for (region in unit.regions) {
            rows += region.values.mapIndexed { index, value ->
                listOf(
                    source,
                    unit.unitId.toString(),
                    region.area.name,
                    (region.start + index).toString(),
                    value.toString(),
                    "",
                    "",
                    rawType(region),
                    "",
                    "",
                    zone.id,
                    timeSource,
                )
            }
        }
    }
    return rows
}

private fun rawType(region: SlaveRegion): String = if (region.area.isBitArea()) "BIT" else "WORD"

private fun historyRows(samples: List<HistorySample>, types: Map<String, String>, zone: TimeZone): List<List<String>> =
    samples.map { sample ->
        listOf(
            sample.sessionId.toString(),
            sample.pointId,
            formatExportTime(sample.sampleAtMs, zone),
            formatExportTime(sample.recordedAtMs, zone),
            sample.epoch.toString(),
            sample.quality.name,
            sample.engineering?.let(::formatExportNumber).orEmpty(),
            sample.text.orEmpty(),
            types[sample.pointId].orEmpty(),
            zone.id,
            "wall",
        )
    }

private fun formatExportNumber(value: Double): String {
    if (!value.isFinite()) return ""
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}

private fun csvCell(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

private fun ZipOutputStream.bytes(name: String, data: ByteArray) {
    putNextEntry(ZipEntry(name))
    write(data)
    closeEntry()
}

private fun ZipOutputStream.csv(name: String, header: String, rows: List<List<String>>) {
    putNextEntry(ZipEntry(name))
    val writer = OutputStreamWriter(object : OutputStream() {
        override fun write(b: Int) = this@csv.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = this@csv.write(b, off, len)
        override fun flush() = this@csv.flush()
        override fun close() = flush()
    }, Charsets.UTF_8)
    writer.write("\uFEFF")
    writer.write(header)
    writer.write("\r\n")
    rows.forEach { row ->
        writer.write(row.joinToString(",") { csvCell(it) })
        writer.write("\r\n")
    }
    writer.flush()
    closeEntry()
}
