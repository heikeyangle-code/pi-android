package app.pi.packages

import java.io.File

/**
 * 外部改动检测的**轻量判据**：`(大小, mtime)`。
 *
 * ## 它解决什么
 *
 * pi 的文件可以被 App 之外的东西改：pi 自己、终端里的命令、AI 通过工具改配置。App 的
 * 读取层普遍是"读一次就缓存"（最典型的是 `PiSettingsFileStore` 的两份文档缓存），于是
 * 外部改动之后界面会**安静地显示旧值**——这正是 `docs/pi-sourced-lists.md` 说的形状：
 * 相信一份副本，副本过期了就静默地做错事。
 *
 * ## 什么时候用它（不要无条件调用）
 *
 * 本对象只提供**判据**，不提供时机。调用时机必须由生命周期事件给出，不许用定时器：
 *
 *  - 进入需要它的那个页面时；
 *  - `ON_RESUME`（用户切回 App），这是"外部编辑器/另一个 App 刚改完"的典型时刻；
 *  - `FileObserver` 真的报了一个事件之后（那是唯一"实时"的来源，只有在页面可见时才装）。
 *
 * 代价说清楚：一个文件一次 `File.exists()` + `length()` + `lastModified()`，在 Android 上
 * 就是 `stat`（缓存里的一次系统调用，微秒级）。对比一下：**解析**一次 `settings.json` 要
 * 读整个文件并建 JSON 树，比 stat 贵三个数量级。所以判据用 stat、内容按需解析，是这个
 * 设计里唯一省得下来的部分。
 *
 * ## 它不知道的事（必须说清，否则会被当成"没变"）
 *
 *  - **内容变了但 `(大小, mtime)` 一模一样**（手工复制一个内容不同但大小相同的文件、并在
 *    秒级内保留 mtime）→ 判为"没变"。这是有意的取舍：换成内容哈希就等于每次都读文件，
 *    而这台机器上的一切都建立在"不要空浪费"上。**同一个进程内的 App 写入因此必须自己
 *    通知**（`PiSettingsFileStore.write` 就是这么做的）。
 *  - **目录**不比 mtime：目录的 mtime 只在增删条目时变，改一个已有文件的内容不会动它。
 *    要目录内容，就直接列目录（`File.list()`），不要拿它的 mtime 当"内容没变"的证据。
 */
object PiFileStamps {

    /** 一个文件的指纹；不存在就是 null（"没有"和"空"必须是两件事）。 */
    fun of(file: File): String? =
        if (!file.exists()) null else "${file.length()}:${file.lastModified()}"

    /**
     * 一组文件的基线。
     *
     * [consume] 是**消费式**的：它比较并同时更新基线，所以"同一次变化"只会被报一次，
     * 而调用者不需要自己记住上次的指纹（那种状态是 bug 的温床）。
     */
    class Baseline(private val files: List<File>) {

        private var stamps: List<String?> = files.map { of(it) }

        /** true 表示自上次调用以来至少有一个文件变了（或出现/消失）。 */
        fun consume(): Boolean {
            val next = files.map { of(it) }
            val changed = next != stamps
            stamps = next
            return changed
        }

        /** 放弃当前基线，下一次 [consume] 一定会报"变了"。 */
        fun reset() {
            // A sentinel that no real stamp can equal — `null` would *not* work, because
            // `null` is also the stamp of an absent file, so resetting a baseline over a
            // deleted file would report "unchanged" on the very call that follows.
            stamps = files.map { RESET }
        }

        private companion object {
            const val RESET = "\u0000reset"
        }
    }
}
