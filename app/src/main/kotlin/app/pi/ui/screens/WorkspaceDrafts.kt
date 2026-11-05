package app.pi.ui.screens

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 编辑器草稿的落盘：进程被系统杀掉（后台回收、被划掉）时，没保存的改动还在。
 *
 * 写进 `cacheDir/workspace-drafts/`（系统缺空间时可以清，丢草稿不丢文件）。
 * 草稿首行记的是**打开编辑时的文件指纹**：恢复时只有文件自那以后没被动过才用，
 * 否则丢弃——绝不拿旧草稿去盖 Agent 刚改过的内容。
 */
internal object WorkspaceDrafts {

    sealed interface Restore {
        data class Found(val text: String) : Restore
        data object Stale : Restore
    }

    private const val MAX_AGE_MS = 14L * 24 * 3600 * 1000

    private fun dir(context: Context) = File(context.cacheDir, "workspace-drafts")

    private fun fileFor(context: Context, target: File): File {
        val key = runCatching { target.canonicalPath }.getOrElse { target.absolutePath }
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }.take(24)
        return File(dir(context), "$name.draft")
    }

    fun save(context: Context, target: File, stamp: String?, text: String) {
        runCatching {
            val d = dir(context)
            d.mkdirs()
            fileFor(context, target).writeText((stamp ?: "") + "\n" + text)
            val now = System.currentTimeMillis()
            d.listFiles()?.forEach { if (now - it.lastModified() > MAX_AGE_MS) it.delete() }
        }
    }

    /** 没有草稿返回 null；有但文件已被改动返回 [Restore.Stale]（并删掉它）。 */
    fun load(context: Context, target: File, currentStamp: String): Restore? = runCatching {
        val f = fileFor(context, target)
        if (!f.isFile) return@runCatching null
        val raw = f.readText()
        val nl = raw.indexOf('\n')
        if (nl < 0) { f.delete(); return@runCatching null }
        val stamp = raw.substring(0, nl)
        if (stamp != currentStamp) {
            f.delete()
            return@runCatching Restore.Stale
        }
        Restore.Found(raw.substring(nl + 1))
    }.getOrNull()

    fun clear(context: Context, target: File) {
        runCatching { fileFor(context, target).delete() }
    }
}
