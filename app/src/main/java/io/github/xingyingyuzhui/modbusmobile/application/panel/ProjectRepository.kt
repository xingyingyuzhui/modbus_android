package io.github.xingyingyuzhui.modbusmobile.application.panel

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

sealed interface ProjectLoad {
    /** [readOnly]：文件来自更新版本的 App，这个版本只读、不覆盖它。 */
    data class Loaded(
        val project: Project,
        val warnings: List<String> = emptyList(),
        val readOnly: Boolean = false,
        val fromBackup: Boolean = false,
    ) : ProjectLoad

    /** 主文件和备份都坏了：[corruptCopy] 是已另存的坏文件副本（不删原件），界面提示后让用户选择新建。 */
    data class Corrupt(val reason: String, val corruptCopy: File?) : ProjectLoad
}

sealed interface ProjectSave {
    data object Saved : ProjectSave
    data object ReadOnly : ProjectSave
    data class Failed(val message: String) : ProjectSave
}

/**
 * 工程文件存取：`<dir>/<id>.json`。写入 = 临时文件 → fsync → 旧文件拷成 `.bak` → 原子替换，
 * 中途断电 / 崩溃时主文件要么是旧的要么是新的。读不出来时回退 `.bak`；都坏了就把坏文件另存 `.corrupt-<时间>` 再报告。
 * 这里不做任何删除用户数据的事。
 */
class ProjectRepository(
    private val dir: File,
    private val projectId: String = Project.DEFAULT_ID,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val main get() = File(dir, "$projectId.json")
    private val backup get() = File(dir, "$projectId.json.bak")
    private val temp get() = File(dir, "$projectId.json.tmp")

    @Volatile
    private var readOnly = false

    @Synchronized
    fun load(): ProjectLoad {
        readOnly = false
        if (!main.exists() && !backup.exists()) return ProjectLoad.Loaded(Project.empty())
        val first = tryRead(main)
        if (first is Attempt.Ok) return finish(first, fromBackup = false)
        val second = tryRead(backup)
        if (second is Attempt.Ok) return finish(second, fromBackup = true)
        val reason = (first as? Attempt.Failed)?.reason ?: "文件不存在"
        return ProjectLoad.Corrupt(reason, preserveCorrupt())
    }

    /** 导出用的 JSON。主文件能读懂就用原文，否则用备份；都没有就写出空工程。不改动坏文件。 */
    @Synchronized
    fun exportJson(): String {
        readTextIfDecodable(main)?.let { return it }
        readTextIfDecodable(backup)?.let { return it }
        return ProjectJson.encode(Project.empty())
    }

    @Synchronized
    fun save(project: Project): ProjectSave {
        if (readOnly) return ProjectSave.ReadOnly
        return try {
            dir.mkdirs()
            val text = ProjectJson.encode(project)
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (main.exists()) Files.copy(main.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.move(temp.toPath(), main.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            ProjectSave.Saved
        } catch (e: Exception) {
            ProjectSave.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private sealed interface Attempt {
        data class Ok(val parsed: ProjectJson.Parsed) : Attempt
        data class Failed(val reason: String) : Attempt
        data object Missing : Attempt
    }

    private fun readTextIfDecodable(file: File): String? {
        if (!file.exists()) return null
        val text = file.readText(Charsets.UTF_8)
        return text.takeIf { runCatching { ProjectJson.decode(it) }.isSuccess }
    }

    private fun tryRead(file: File): Attempt {
        if (!file.exists()) return Attempt.Missing
        return try {
            Attempt.Ok(ProjectJson.decode(file.readText(Charsets.UTF_8)))
        } catch (e: ProjectJson.FormatException) {
            Attempt.Failed(e.message ?: "格式错误")
        } catch (e: Exception) {
            Attempt.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun finish(ok: Attempt.Ok, fromBackup: Boolean): ProjectLoad.Loaded {
        val newer = ok.parsed.version > Project.FORMAT_VERSION
        readOnly = newer
        val warnings = ok.parsed.warnings.toMutableList()
        if (newer) warnings += "工程文件来自更新版本（v${ok.parsed.version}），当前版本只读，不会覆盖它"
        if (fromBackup) warnings += "主文件无法读取，已用备份"
        return ProjectLoad.Loaded(ok.parsed.project, warnings, readOnly = newer, fromBackup = fromBackup)
    }

    private fun preserveCorrupt(): File? {
        val source = if (main.exists()) main else return null
        return try {
            File(dir, "$projectId.json.corrupt-${clock()}").also { Files.copy(source.toPath(), it.toPath()) }
        } catch (e: Exception) {
            null
        }
    }
}
