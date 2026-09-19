package app.pi.ui.settings

import android.os.FileObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.pi.packages.PiFileStamps
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * **外部改动 → 界面更新**的统一做法，一个 Composable 就是全部。
 *
 * ## 问题
 *
 * App 的读取层普遍是"读一次就缓存"（`PiSettingsFileStore` 的两份文档、包管理器 controller
 * 的 `entries`、ViewModel 的 `availableModels`），而 pi 的文件可以被 App 之外的东西改：pi
 * 自己、终端里的命令、AI 通过工具改配置。外部改完之后界面会**安静地显示旧值**——
 * `docs/pi-sourced-lists.md` 说的那个形状。
 *
 * ## 两个时机，都不是定时器
 *
 *  1. **`FileObserver`**（inotify）：只在**这个 Composable 在组合里**的这段时间装，
 *     `onDispose` 就停。目录级（不是文件级，见下），掩码是写入/创建/改名/删除/属性。
 *     **没有事件就没有唤醒**：不动文件的时候它的开销是零，而不是"每秒检查一次"。
 *  2. **`ON_RESUME`**：切回前台时用 [PiFileStamps] 比一次 `(大小, mtime)`。这是"用户刚在
 *     别的 App 或终端里改完"的典型时刻，而 inotify 不保证在这些情况下都醒着（Android 在
 *     内存紧张时会静默丢掉监视，这是**已知的平台行为**——所以生命周期这一层不是冗余，
 *     是兜底）。
 *
 * ## 一次改动只报一次，且不在主线程上碰文件
 *
 * 上面两个来源都**只往一个 `CONFLATED` 通道里投一个信号**，真正的判定在一个消费协程里做：
 *
 *  - **合并**：App 自己写一次文件会产生**两个**事件（临时文件 `CREATE` + 正式名
 *    `MOVED_TO`），`ATTRIB`/`CLOSE_WRITE` 还会再添几个。以前每个事件都回调一次，于是
 *    "改一个开关"会让整页重读两遍。现在通道是 conflated（最多留一个待处理信号），消费端
 *    再等 [COALESCE_WINDOW_MS] 把同一串事件收成一个。
 *  - **读盘不在主线程**：`PiFileStamps.Baseline` 的构造与 `consume()` 都会对每个被监视的
 *    文件做 `stat`。以前前者发生在**组合期**、后者发生在 **`ON_RESUME` 的观察者里**，都在
 *    主线程；现在两者都在 `Dispatchers.IO` 上，回调本身回到组合的调度器（主线程）。
 *
 * 判据仍然是"变了才回调"，回调本身不做解析：解析是页面收到通知之后按需做的。
 *
 * ## 为什么监视**目录**而不是文件
 *
 * App 自己写这些文件是原子的（写临时文件 + `rename`，`PiConfigFiles.write`）。`rename` 把
 * 目标 inode 换掉，而监视挂在 inode 上——所以监视文件会**在自己写完之后就再也收不到事件**。
 * 目录级监视不受影响（事件带着子文件名来）。
 *
 * ## 它不做什么
 *
 *  - 不区分是哪个文件变的：回调只带一个"变了"的信号。要分辨就再比一次 [PiFileStamps] 的
 *    单项指纹（`PiModelsScreen` 里就是这么读那四个文件的）。
 *  - 不认识"改了但大小和 mtime 都没变"的情况。取舍写在 [PiFileStamps] 的头部。
 *  - 不管写入是不是本进程做的：App 自己写文件也会回调一次，处理方式就是再读一遍（读不写
 *    文件，所以不会成环）。
 *  - 不消除"页面收到通知后在组合期读 store"这件事：那是行自己的读取（缓存已被宿主丢掉了），
 *    不由本文件决定；`docs/settings-audit-impl.md` §B10 给了宿主的预热点补法。
 */
@Composable
fun PiDirectoryWatch(
    /** 要看的目录：`<agentDir>`，以及工作区的 `.pi`。 */
    directories: List<File>,
    /**
     * 只关心这些名字（前缀）的事件；null 表示这个目录里的一切都算。
     *
     * 用前缀而不是全名，是因为同名文件会带后缀出现：`.tmp-<pid>`、`.lock`、
     * `.corrupt-<时间>`——那些正是"有人正在写"的信号。
     */
    names: List<String>? = null,
    onChanged: () -> Unit,
) {
    val callback = rememberUpdatedState(onChanged)
    // Read here, in composition, rather than inside the effect: a CompositionLocal read
    // inside a `DisposableEffect` block works, but it is one more thing to have to be
    // right about on a machine where Compose cannot be compiled.
    val owner = LocalLifecycleOwner.current
    // Keyed on *what* is watched, so a different agent dir rebuilds the observers, the
    // coalescing channel and the resume baseline together.
    val key = directories.joinToString("|") { it.absolutePath } + "#" + (names?.joinToString(",") ?: "*")
    val files = remember(key) { watchedFiles(directories, names) }
    // `CONFLATED` is the whole point: a burst of events (temp file, rename, attrib)
    // leaves at most one pending signal, so one user-visible change is one refresh.
    val signals = remember(key) { Channel<Unit>(Channel.CONFLATED) }

    DisposableEffect(key) {
        val observers = directories.mapNotNull { directory ->
            if (!directory.isDirectory) return@mapNotNull null
            val observer = fileObserver(directory, names) { signals.trySend(Unit) }
            runCatching { observer.startWatching() }
            observer
        }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            // Goes through the same channel as inotify: one code path, one coalescing
            // rule, and the `(size, mtime)` comparison runs on IO (below), not here.
            if (event == Lifecycle.Event.ON_RESUME) signals.trySend(Unit)
        }
        owner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            owner.lifecycle.removeObserver(lifecycleObserver)
            observers.forEach { runCatching { it.stopWatching() } }
            signals.close()
        }
    }

    LaunchedEffect(key) {
        // The baseline is taken on IO: its constructor stats every watched file. It is
        // taken as the consumer starts (it used to be taken during composition), so a
        // change landing inside that first hop is still covered by the page's own
        // initial read of the document.
        var baseline = withContext(Dispatchers.IO) { PiFileStamps.Baseline(files) }
        for (unused in signals) {
            delay(COALESCE_WINDOW_MS)
            // `consume()` is where the judgement happens, and it stats every file: off
            // the main thread, every time. `unused` is the conflated signal itself.
            val changed = withContext(Dispatchers.IO) { baseline.consume() }
            // Back on the composition's dispatcher: callers write Compose state here.
            if (changed) callback.value()
        }
    }
}

/**
 * How long a burst of events may accumulate before one refresh runs.
 *
 * 100 ms is chosen against the two things that matter: the shortest real burst (the
 * `rename` half of an atomic write lands microseconds after the temp file's `CREATE`)
 * and the longest a stale value can be shown to a human who is looking at the page.
 * `PiModelsScreen` already waits 200 ms for the same reason; this window is shorter
 * because these are the rows the user is looking at while they type.
 */
private const val COALESCE_WINDOW_MS = 100L

/** `FileObserver(String, Int)` is the deprecated-but-universal constructor; the `File` one is API 29. */
@Suppress("DEPRECATION")
private fun fileObserver(directory: File, names: List<String>?, signal: () -> Unit): FileObserver =
    object : FileObserver(directory.absolutePath, MASK) {
        override fun onEvent(event: Int, path: String?) {
            if (path == null || names == null || names.any { path.startsWith(it) }) signal()
        }
    }

/** The files the resume check looks at: the ones [names] selects, or the directory itself. */
private fun watchedFiles(directories: List<File>, names: List<String>?): List<File> =
    directories.flatMap { directory ->
        if (names == null) listOf(directory) else names.map { File(directory, it) }
    }

/**
 * `FileObserver.ALL_EVENTS` would be the short way to write this and is the wrong
 * one: it includes `OPEN`/`ACCESS`/`CLOSE_NOWRITE`, i.e. every read this app itself
 * performs — a watch that fires on its own reader is a loop.
 */
private const val MASK = FileObserver.CREATE or
    FileObserver.DELETE or
    FileObserver.MOVED_TO or
    FileObserver.MOVED_FROM or
    FileObserver.CLOSE_WRITE or
    FileObserver.ATTRIB
