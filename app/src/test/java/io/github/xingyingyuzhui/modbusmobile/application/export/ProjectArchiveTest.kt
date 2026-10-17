package io.github.xingyingyuzhui.modbusmobile.application.export

import io.github.xingyingyuzhui.modbusmobile.application.history.HistorySample
import io.github.xingyingyuzhui.modbusmobile.application.history.SampleQuality
import io.github.xingyingyuzhui.modbusmobile.application.panel.DataType
import io.github.xingyingyuzhui.modbusmobile.application.panel.PointDefinition
import io.github.xingyingyuzhui.modbusmobile.application.panel.Project
import io.github.xingyingyuzhui.modbusmobile.application.panel.Widget
import io.github.xingyingyuzhui.modbusmobile.application.panel.WidgetKinds
import io.github.xingyingyuzhui.modbusmobile.application.slave.SlaveRegion
import io.github.xingyingyuzhui.modbusmobile.application.slave.SlaveSetup
import io.github.xingyingyuzhui.modbusmobile.application.slave.SlaveUnitSetup
import io.github.xingyingyuzhui.modbusmobile.core.model.RegisterArea
import java.util.TimeZone
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectArchiveTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun zipUsesTheSelectedNamesAndCsvHeaders() {
        val point = PointDefinition(
            id = "temp",
            name = "温度,1",
            unitId = 1,
            area = RegisterArea.HOLDING_REGISTER,
            address = 0,
            type = DataType.U32,
        )
        val saved = SlaveSetup(
            listOf(
                SlaveUnitSetup(
                    1,
                    listOf(
                        SlaveRegion(RegisterArea.HOLDING_REGISTER, 0, listOf(10, 20)),
                        SlaveRegion(RegisterArea.COIL, 5, listOf(1, 0)),
                    ),
                ),
            ),
        )
        val live = SlaveSetup(
            listOf(SlaveUnitSetup(1, listOf(SlaveRegion(RegisterArea.HOLDING_REGISTER, 0, listOf(11, 21))))),
        )
        val files = unzip(
            archiveBytes(
                ArchivePart.entries.toSet(),
                ArchiveInput(
                    zone = utc,
                    projectJson = """{"name":"冷站"}""",
                    slaveJson = """{"version":1}""",
                    points = listOf(point),
                    savedSlave = saved,
                    liveSlave = live,
                    pointValues = listOf(
                        PointValueExport(1, "HOLDING_REGISTER", 0, "U32", "TIMEOUT", 10.0, "10", 0L),
                    ),
                    samples = listOf(
                        HistorySample(7, "temp", 0L, 5L, 3L, SampleQuality.TIMEOUT, null, null),
                    ),
                    trafficCsv = "csv".toByteArray(),
                    trafficTxt = "hex".toByteArray(),
                ),
            ),
        )
        assertEquals(ArchiveNames.PROJECT, files.keys.elementAt(0))
        assertEquals("""{"name":"冷站"}""", files.getValue(ArchiveNames.PROJECT))
        assertEquals("""{"version":1}""", files.getValue(ArchiveNames.SLAVE))
        val points = files.getValue(ArchiveNames.POINTS)
        assertTrue(points.startsWith("\uFEFF$POINTS_HEADER\r\n"))
        assertTrue(points.contains("\"温度,1\""))
        assertTrue(points.contains(",HOLDING_REGISTER,0,U32,"))
        assertTrue(points.contains(",UTC,,definition"))
        assertFalse("点表地址保持 0 基", points.contains("40001"))
        val registers = files.getValue(ArchiveNames.REGISTERS)
        assertTrue(registers.startsWith("\uFEFF$REGISTERS_HEADER\r\n"))
        assertTrue(registers.contains("saved,1,HOLDING_REGISTER,0,10,"))
        assertTrue(registers.contains("saved,1,COIL,5,1,,,BIT,,"))
        assertTrue(registers.contains("live,1,HOLDING_REGISTER,0,11,"))
        assertTrue(registers.contains("point,1,HOLDING_REGISTER,0,,10,10,U32,TIMEOUT,"))
        assertEquals("U32 点只占一行，不按寄存器展开", 1, registers.lines().count { it.startsWith("point,") })
        val history = files.getValue(ArchiveNames.HISTORY)
        assertTrue(history.startsWith("\uFEFF$HISTORY_HEADER\r\n"))
        assertTrue(history.contains("7,temp,${formatExportTime(0L, utc)},${formatExportTime(5L, utc)},3,TIMEOUT,,,"))
        assertTrue(history.contains(",U32,UTC,wall"))
        assertEquals("csv", files.getValue(ArchiveNames.TRAFFIC_CSV))
        assertEquals("hex", files.getValue(ArchiveNames.TRAFFIC_TXT))
    }

    @Test
    fun uncheckedPartsAreLeftOut() {
        val files = unzip(
            archiveBytes(
                setOf(ArchivePart.POINTS),
                ArchiveInput(zone = utc, projectJson = "{}", slaveJson = "{}", points = emptyList()),
            ),
        )
        assertEquals(setOf(ArchiveNames.POINTS), files.keys)
        assertEquals("\uFEFF$POINTS_HEADER\r\n", files.getValue(ArchiveNames.POINTS))
    }

    @Test
    fun exportPointsIncludeWriteButtonsAndNotBrokenWidgets() {
        val read = PointDefinition("r", "读", 1, RegisterArea.HOLDING_REGISTER, 2, DataType.U16)
        val button = PointDefinition("b", "写", 1, RegisterArea.COIL, 0, DataType.BOOL)
        val project = Project(
            "p",
            "工程",
            listOf(
                io.github.xingyingyuzhui.modbusmobile.application.panel.Panel(
                    "panel",
                    "面板",
                    widgets = listOf(
                        Widget("w", WidgetKinds.NUMBER, point = read),
                        Widget("btn", WidgetKinds.BUTTON, point = button, writeValue = 1.0),
                        Widget("bad", WidgetKinds.NUMBER, raw = "{}"),
                    ),
                ),
            ),
        )
        assertEquals(listOf("r", "b"), pointsForExport(project).map { it.id })
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = linkedMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                out[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }
}
