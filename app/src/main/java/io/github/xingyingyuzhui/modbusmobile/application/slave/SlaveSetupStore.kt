package io.github.xingyingyuzhui.modbusmobile.application.slave

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 从站配置的存取。会话层只依赖这个接口，测试里用 [MemorySlaveSetupStore]。 */
interface SlaveSetupStore {
    /** 读不到（第一次用 / 文件坏了）时返回默认配置，不抛异常。 */
    fun load(): SlaveSetup

    /** 保存成功返回 true；只读（文件来自更新版本）或写失败返回 false。 */
    fun save(setup: SlaveSetup): Boolean

    /** 导出用的 JSON。文件实现优先返回磁盘上能读懂的原文。 */
    fun exportJson(): String = SlaveSetupJson.encode(load())
}

class MemorySlaveSetupStore(initial: SlaveSetup = SlaveSetup.default()) : SlaveSetupStore {
    @Volatile
    var current: SlaveSetup = initial
        private set
    var saves: Int = 0
        private set

    override fun load(): SlaveSetup = current

    override fun save(setup: SlaveSetup): Boolean {
        current = setup
        saves += 1
        return true
    }
}

/**
 * `<dir>/slave.json`。写入方式和面板工程文件一样：临时文件 → fsync → 旧文件拷成 `.bak` → 原子替换。
 * 读：主文件 → `.bak` → 默认配置；主文件和备份都坏了时把坏文件另存 `.corrupt-<时间>`，原件不删。
 * 文件来自更新版本（version 更大）时只读：能用，但不覆盖。读不懂的内容原样写回。
 */
class FileSlaveSetupStore(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : SlaveSetupStore {
    private val main get() = File(dir, "slave.json")
    private val backup get() = File(dir, "slave.json.bak")
    private val temp get() = File(dir, "slave.json.tmp")

    private var preserved = SlaveSetupJson.Preserved.None
    private var readOnly = false

    /** 最近一次读取的警告（界面可以提示）。 */
    @Volatile
    var warnings: List<String> = emptyList()
        private set

    @Synchronized
    override fun load(): SlaveSetup {
        readOnly = false
        preserved = SlaveSetupJson.Preserved.None
        warnings = emptyList()
        if (!main.exists() && !backup.exists()) return SlaveSetup.default()
        val parsed = read(main) ?: read(backup)?.also { warnings = it.warnings + "主文件无法读取，已用备份" }
        if (parsed == null) {
            // 先把坏文件另存一份；另存成功才允许之后的保存覆盖它，另存失败就只读，绝不丢数据。
            val kept = preserveCorrupt()
            readOnly = !kept
            warnings = listOf(
                if (kept) "从站配置文件无法读取，已另存副本，本次使用默认配置"
                else "从站配置文件无法读取，也无法另存副本，本次使用默认配置且不覆盖原文件",
            )
            return SlaveSetup.default()
        }
        if (warnings.isEmpty()) warnings = parsed.warnings
        preserved = parsed.preserved
        if (parsed.version > SlaveSetupJson.FORMAT_VERSION) {
            readOnly = true
            warnings = warnings + "从站配置来自更新版本（v${parsed.version}），只读，不会覆盖"
        }
        return parsed.setup
    }

    @Synchronized
    override fun exportJson(): String {
        readTextIfDecodable(main)?.let { return it }
        readTextIfDecodable(backup)?.let { return it }
        return SlaveSetupJson.encode(SlaveSetup.default())
    }

    @Synchronized
    override fun save(setup: SlaveSetup): Boolean {
        if (readOnly) return false
        return try {
            dir.mkdirs()
            FileOutputStream(temp).use { out ->
                out.write(SlaveSetupJson.encode(setup, preserved).toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (main.exists()) Files.copy(main.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.move(temp.toPath(), main.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun readTextIfDecodable(file: File): String? {
        if (!file.exists()) return null
        val text = file.readText(Charsets.UTF_8)
        return text.takeIf { runCatching { SlaveSetupJson.decode(it) }.isSuccess }
    }

    private fun read(file: File): SlaveSetupJson.Parsed? {
        if (!file.exists()) return null
        return try {
            SlaveSetupJson.decode(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    private fun preserveCorrupt(): Boolean {
        val source = if (main.exists()) main else return true
        return runCatching { Files.copy(source.toPath(), File(dir, "slave.json.corrupt-${clock()}").toPath()) }.isSuccess
    }
}
