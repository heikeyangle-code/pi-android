package app.pi.ui.screens

import app.pi.packages.PiConfigFiles
import app.pi.rpc.ToolCall
import app.pi.rpc.TranscriptItem
import app.pi.rpc.ToolStatus
import app.pi.ui.blocks.argString
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 工作区目录树的一层，读在磁盘上，画在「全部文件」里。
 *
 * ## 它为什么不问引擎
 *
 * `design/ui-refactor/design-demos/workspace-final.html` 的顶栏下一行写的是
 * 「文件走宿主 File I/O，引擎没起来也能打开 · 编辑 · 删除」。工作区是 App 私有目录里的
 * 一个文件夹（`GuestWorkspacePath.RELATIVE`），所以这一层能力只有 `java.io.File`，
 * 没有 RPC、没有 root、没有 SAF。
 *
 * ## 为什么是纯 Kotlin（无 Compose、无 Context）
 *
 * 与 `ProjectResources.kt` 同一个理由：目录读取必须能脱离设备被检查，而这一层最容易
 * 悄悄编造数据（一个读失败的目录看起来与一个空目录一模一样）。所有函数只吃 `File`。
 */

/** 一个目录项长什么样，决定用哪个图标、能不能在查看器里打开。 */
internal enum class WorkspaceEntryKind {
    /** 目录：点进去。 */
    Directory,

    /** 文本：走只读文本查看器（可编辑）。 */
    Text,

    /** `.html`：走系统 WebView 渲染预览（本批新增的唯一一条能力）。 */
    Html,

    /** 二进制：查看器不展开，只给类型与大小。 */
    Binary,
}

/**
 * 目录树里的一个条目。
 *
 * [path] 是**相对工作区根**的路径，`/` 分隔 —— 它是 pi 工具调用参数里那种拼法
 * （`ProjectFile.path`），所以「本次会话改过」的标记可以直接按它匹配。
 */
internal data class WorkspaceEntry(
    val name: String,
    val path: String,
    val kind: WorkspaceEntryKind,
    /** 0 for a directory: a directory's size would be the sum of its children. */
    val sizeBytes: Long,
    val modifiedAt: Long,
    /** Directory only. */
    val childCount: Int,
) {
    val isDirectory: Boolean get() = kind == WorkspaceEntryKind.Directory
}

/**
 * 打开一个文件的四种结局，每一种在界面上都有一个不重样的状态。
 *
 * 这是 design 稿 `binary` / `toolarge` / `viewing` 三个状态加一个失败态的数据面：
 * 二进制不给内容、超大文件只给前几行、读失败给原因 —— 三者都不是「空文件」。
 */
internal sealed interface WorkspaceOpen {
    /** 文本，整份（或行数封顶后截断）。 */
    data class Text(
        val lines: List<String>,
        /** null when the file was read whole and the count is therefore exact. */
        val totalLines: Int?,
        val truncated: Boolean,
    ) : WorkspaceOpen

    /** 二进制：不展开内容。 */
    data class Binary(val sizeBytes: Long, val typeLabel: String) : WorkspaceOpen

    /** 超大：只看前 [previewLines] 行，[omittedLines] 是没显示的那些。 */
    data class TooLarge(
        val sizeBytes: Long,
        val lines: List<String>,
        val omittedLines: Int?,
    ) : WorkspaceOpen

    /** 读失败：原因是给人看的，详情给复制。 */
    data class Failed(val reason: String, val detail: String?) : WorkspaceOpen
}

/**
 * 工作区里的文件读写。
 *
 * ## 大小与阈值（design 稿自己填的占位，见稿子底部第 8 条）
 *
 * 稿子写「超过 1 MB 只给前 25 行」，并自己注明这两个数字是占位。这里照抄这两个数，
 * 并再加一条**硬上限**（[MAX_TEXT_LINES]）：一个 50 MB 的 `.jsonl` 就算只读前 25 行也
 * 要先判断它是不是文本，所以每一条读取路径都封顶，绝不让 UI 线程之外的调用把整份文件
 * 拉进内存。
 */
internal object WorkspaceFiles {

    /** 工作区在界面上的名字（与会话列表的 `工作区` 同一套词）。 */
    const val ROOT_LABEL = "工作区"

    /** design 稿：`文件 2.4 MB。查看器不整份载入，只给前 25 行`。 */
    const val PREVIEW_LINES = 25

    /** design 稿第 8 条：这个数字是占位。1 MB 之上走「超大文件」看待。 */
    const val LARGE_BYTES = 1_000_000L

    /** 非超大文件的行数上限，防止一份巨大文件把内存拉爆。 */
    const val MAX_TEXT_LINES = 5_000

    /** 判定二进制时只看头部这么多字节。 */
    private const val SNIFF_BYTES = 8_192

    /** 行数扫描的上限：超过就报「还有更多」，不再往下数。 */
    private const val COUNT_CAP = 200_000

    /**
     * 数行数时的字符预算：32 MB。
     *
     * 一份「还有 N 行」的提示不值得为它读完一个几百 MB 的文件。读满就走 null（见
     * [countLines]），与超过 [COUNT_CAP] 时同一种答案。
     */
    private const val COUNT_BUDGET_CHARS = 32 * 1024 * 1024

    /** [countLines] 每次读的块大小。 */
    private const val COUNT_CHUNK_CHARS = 8 * 1024

    /** 已知的二进制后缀（`.so` / 图片 / 压缩包 / 数据库 …）。 */
    private val BINARY_SUFFIXES = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic", "avif",
        "so", "dylib", "dll", "a", "o", "class", "dex", "jar", "apk", "aar", "zip",
        "gz", "tgz", "bz2", "xz", "zst", "7z", "rar", "tar",
        "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "mp4", "mkv", "webm", "mov",
        "ttf", "otf", "woff", "woff2", "pdf", "db", "sqlite", "sqlite3", "bin", "dat",
        "keystore", "jks", "p12", "pfx",
    )

    /** A directory's newest mtime would need a walk; the board shows the directory's own. */
    fun list(dir: File): Result<List<WorkspaceEntry>> = runCatching {
        val children = dir.listFiles()
            ?: throw java.io.FileNotFoundException("无法读取目录：${dir.absolutePath}")
        val base = dir.absolutePath
        children
            .map { child -> entryOf(child, child.absolutePath.removePrefix(base).trimStart('/')) }
            // design 稿：「目录在前」，其后各按名字排序。
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.US) }))
    }

    private fun entryOf(file: File, relativePath: String): WorkspaceEntry = WorkspaceEntry(
        name = file.name,
        path = relativePath,
        kind = if (file.isDirectory) WorkspaceEntryKind.Directory else kindOf(file),
        sizeBytes = if (file.isDirectory) 0L else file.length(),
        modifiedAt = file.lastModified(),
        childCount = if (file.isDirectory) file.listFiles()?.size ?: 0 else 0,
    )

    /**
     * 一个文件是文本、HTML 还是二进制。
     *
     * 后缀先判（`.so` 一眼就是二进制），后缀不确定时**闻一下头部**：出现 NUL 字节就
     * 当二进制。这个判据来自 `file(1)` 的经典做法，也是 pi 自己区分文本行的做法。
     */
    fun kindOf(file: File): WorkspaceEntryKind {
        val name = file.name.lowercase(Locale.US)
        val suffix = name.substringAfterLast('.', "")
        if (suffix == "html" || suffix == "htm") return WorkspaceEntryKind.Html
        if (suffix in BINARY_SUFFIXES) return WorkspaceEntryKind.Binary
        return if (looksBinary(file)) WorkspaceEntryKind.Binary else WorkspaceEntryKind.Text
    }

    private fun looksBinary(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val buffer = ByteArray(SNIFF_BYTES)
            val read = input.read(buffer)
            if (read <= 0) return@use false
            for (index in 0 until read) {
                if (buffer[index] == 0.toByte()) return@use true
            }
            false
        }
    }.getOrDefault(false)

    /** 二进制文件在查看器里的类型标签：后缀大写，没有后缀就说「无后缀」。 */
    fun binaryLabel(file: File): String {
        val suffix = file.name.substringAfterLast('.', "").uppercase(Locale.US)
        return if (suffix.isEmpty()) "无后缀" else "$suffix 文件"
    }

    /**
     * 读一个文本文件，按 design 稿的三档分流：整份 / 只预览前 25 行 / 二进制。
     *
     * 调用方保证这跑在 IO 线程（`withContext(Dispatchers.IO)`）。
     */
    fun open(file: File, kind: WorkspaceEntryKind): WorkspaceOpen {
        if (!file.isFile) {
            return WorkspaceOpen.Failed(
                reason = "这个文件已经不在了，可能被重命名或删掉。",
                detail = "java.io.FileNotFoundException: ${file.absolutePath}\n  (No such file or directory)",
            )
        }
        if (kind == WorkspaceEntryKind.Binary) {
            return WorkspaceOpen.Binary(file.length(), binaryLabel(file))
        }
        val size = file.length()
        return runCatching {
            if (size > LARGE_BYTES) {
                val head = file.bufferedReader().use { reader ->
                    buildList {
                        repeat(PREVIEW_LINES) {
                            val line = reader.readLine() ?: return@repeat
                            add(line)
                        }
                    }
                }
                WorkspaceOpen.TooLarge(
                    sizeBytes = size,
                    lines = head,
                    omittedLines = countLines(file)?.minus(head.size),
                )
            } else {
                val lines = file.readLines()
                if (lines.size > MAX_TEXT_LINES) {
                    WorkspaceOpen.Text(lines.take(MAX_TEXT_LINES), lines.size, true)
                } else {
                    WorkspaceOpen.Text(lines, lines.size, false)
                }
            }
        }.getOrElse { error ->
            WorkspaceOpen.Failed(
                reason = "读不到这个文件：" + (error.message ?: error::class.java.simpleName),
                detail = error.stackTraceToString(),
            )
        }
    }

    /**
     * 行数，**读到文件末尾**才算数；到不了末尾就是 null。
     *
     * 两处都不许编数字：
     *
     *  - 到不了 EOF（超过 [COUNT_CAP] 行或 [COUNT_BUDGET_CHARS] 个字符）就返回 null。
     *    原来的实现在到达上限时把**已经数到的**那个数当成总数返回，而调用方拿它算
     *    「还有 N 行」（`WorkspaceViewer` 的 `totalLines`）—— 一个 90 万行的日志会被
     *    报成 20 万行。KDoc 一直写着「不编一个数字回填」，代码在这一点上没有照做。
     *  - 顺带按**字节**封顶：原来用 `readLine()` 逐行数，等于为一句提示把整个文件读完，
     *    而且每行都分配一个 `String`（200 MB 的日志就是 200 MB 的临时对象）。按块扫字节
     *    既没有逐行分配，也在读满预算时立刻停下。
     *
     * `omittedLines`/`totalLines` 在 UI 上本来就是可空处理的（`TooLargeBody` 用
     * `?.plus`），所以 null 只是少一行数字，不会少一块界面。
     */
    private fun countLines(file: File): Int? = runCatching {
        var count = 0
        var consumed = 0L
        var openLine = false
        var reachedEnd = false
        file.bufferedReader().use { reader ->
            val buffer = CharArray(COUNT_CHUNK_CHARS)
            while (true) {
                if (consumed >= COUNT_BUDGET_CHARS || count >= COUNT_CAP) break
                val read = reader.read(buffer)
                if (read < 0) {
                    reachedEnd = true
                    break
                }
                consumed += read
                for (index in 0 until read) {
                    if (buffer[index] == '\n') {
                        count++
                        openLine = false
                    } else {
                        openLine = true
                    }
                }
            }
        }
        if (!reachedEnd) null else if (openLine) count + 1 else count
    }.getOrNull()

    // ------------------------------------------------------------------ 写 ----

    /** 新建一个空文件；已存在就是失败（不覆盖）。 */
    fun createFile(parent: File, name: String): Result<File> = runCatching {
        val target = File(parent, name)
        // 新建出来的是**空**文件，而 pi 会严格解析 `.pi/` 里的 JSON 文档：空文件就是"pi 读不了
        // 它"。新建这条路上没有任何内容可校验，所以拒绝（判定在 `WorkspacePiWrite.isPiJsonTarget`，
        // 基于真实路径），并给一句指向能改这类文件的地方的提示。
        if (WorkspacePiWrite.isPiJsonTarget(target)) {
            throw java.io.IOException(WorkspacePiWrite.creationRefusalSentence())
        }
        if (target.exists()) throw java.io.IOException("这个目录里已经有「$name」了。")
        target.parentFile?.mkdirs()
        if (!target.createNewFile()) throw java.io.IOException("无法在这个目录里新建文件。")
        target
    }

    /** 新建一个目录；已存在就是失败（不合并）。 */
    fun createDir(parent: File, name: String): Result<File> = runCatching {
        val target = File(parent, name)
        if (target.exists()) throw java.io.IOException("这个目录里已经有「$name」了。")
        if (!target.mkdirs()) throw java.io.IOException("无法在这个目录里新建文件夹。")
        target
    }

    /** 改名（只动名字，内容不动）。目标已存在就是失败。 */
    fun rename(file: File, newName: String): Result<File> = runCatching {
        val target = File(file.parentFile, newName)
        // 改名搬的是磁盘上已有的字节，同样不经过写前校验：把 `notes.json` 改成 `settings.json`
        // 就等于让 pi 开始读一份没人校验过的设置。目的地是 `.pi/` 下的 JSON 文档就拒绝。
        if (WorkspacePiWrite.isPiJsonTarget(target)) {
            throw java.io.IOException(WorkspacePiWrite.creationRefusalSentence())
        }
        if (target.exists()) throw java.io.IOException("这个目录里已经有「$newName」了。")
        if (!file.renameTo(target)) throw java.io.IOException("重命名失败：${file.name} → $newName")
        target
    }

    /** 永久删除：目录连同内容一起删。design 稿的确认框就是这么说的。 */
    fun delete(file: File): Result<Unit> = runCatching {
        val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (!deleted || file.exists()) throw java.io.IOException("删除失败：${file.absolutePath}")
    }

    /**
     * 把编辑器的内容写回整个文件（覆盖写）。
     *
     * **这是「工作区里任何普通文件」的写法**：无锁、非原子、没有内容校验 —— 对用户自己的
     * 文档正是编辑器的意义。写 pi 会读的文件请用 [save]：它对工作区自己的 `.pi/` 目录换用
     * pi 的锁 + 原子替换（必要时还先过写前校验）。这个函数不再被编辑器的保存路径直接调用。
     */
    fun writeText(file: File, text: String): Result<Unit> = runCatching {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    /**
     * 编辑器保存一次改动：走对的那条写入路径。
     *
     * `<workspace>/.pi/` 里的文件是 pi 会读的文件。以前这里和「Pi 文件」屏是两个写者：这一屏
     * 整份覆盖（`writeText`），那一屏是 `PiConfigFiles.withLock` + 原子替换 + 写前校验。
     * 同一个文件两条写法就是第二份真相，而且第二份还是坏的那份 —— 非原子的覆盖可以和 pi
     * 自己的写交织，把键丢掉；也没有任何东西拦住「写下去 pi 会抛」的内容。
     *
     * 现在只有一条：`.pi/` 里的路径一律走 [PiConfigFiles]（锁与原子替换的唯一实现，这里不写第二
     * 套），并且先过 [WorkspacePiWrite.problem]（`checkPiFileWrite`，与「Pi 文件」屏同一份
     * 判据）。其余路径保持原来的整份覆盖。
     *
     * @param relativePath 相对工作区根、`/` 分隔的路径（树里的显示路径）；由它决定这次写是
     *   不是 pi 的文件，**不是**由文件对象猜。
     * @return 失败时带一句给用户看的原因：校验不通过（原来写下去 pi 会抛/会整份忽略）、锁拿不
     *   到、或原子替换失败。三种都在磁盘上什么都没改。
     */
    fun save(
        file: File,
        relativePath: String,
        text: String,
        openedStamp: String? = null,
    ): Result<Unit> = runCatching {
        WorkspacePiWrite.problem(relativePath, file, text)?.let { throw java.io.IOException(it) }
        if (!WorkspacePiWrite.isPiDocumentPath(relativePath, file)) {
            writeText(file, text).getOrThrow()
            return@runCatching
        }
        val written = PiConfigFiles.withLock(file) {
            // 变更检测在锁**里面**：这样「比指纹 → 写」对 pi 自己的写是原子的，否则检查过了、
            // 写完之前 pi 还是能插进来。指纹就是 [stampOf]（与「Pi 文件」屏同一个判据），
            // [openedStamp] 为 null（打开时没记下）时跳过检查。
            if (WorkspacePiWrite.stampChanged(openedStamp, stampOf(file))) {
                throw java.io.IOException(WorkspacePiWrite.staleStampSentence())
            }
            PiConfigFiles.write(file, text, mode600 = WorkspacePiWrite.restrictToOwner(relativePath, file))
        }
        if (!written) {
            throw java.io.IOException("写盘失败：${file.name}（原子替换没有成功，磁盘上的内容没有被改）")
        }
    }

    /**
     * `(size, mtime)` 指纹 —— 只用来发现「变过」，不追求唯一。
     *
     * 与 `PiFilesScreen` 的 `stampOf` 同一种判据（那边写在文件里，这里提出来给 [save] 的变更
     * 检测用）：同一种形状换来的是两个界面不会对「这个文件变了吗」给出不同答案。
     */
    fun stampOf(file: File): String = "${file.length()}:${file.lastModified()}"

    /**
     * 一个名字能不能用：非空、不是 `.`/`..`、不含路径分隔符、不以空格结尾。
     *
     * 返回 null 表示可用，否则是给用户看的原因。它拦的是「名字里带 `/` 就等于在别处
     * 建东西」这一类事，而不是替用户决定命名风格。
     */
    fun nameProblem(name: String): String? = when {
        name.isBlank() -> "名字不能为空。"
        name == "." || name == ".." -> "这个名字不能用。"
        name.contains('/') || name.contains('\\') -> "名字里不能有路径分隔符。"
        name.any { it.code < 0x20 } -> "名字里不能有控制字符。"
        name != name.trim() -> "名字首尾不能有空格。"
        else -> null
    }

    // ------------------------------------------------------------ 展示格式 ----

    /** `2.4 MB` / `1.1 KB` / `301 B`（design 稿副行的写法）。 */
    fun formatSize(bytes: Long): String = when {
        bytes < 1_000L -> "$bytes B"
        bytes < 1_000_000L -> oneDecimal(bytes / 1_000.0) + " KB"
        bytes < 1_000_000_000L -> oneDecimal(bytes / 1_000_000.0) + " MB"
        else -> oneDecimal(bytes / 1_000_000_000.0) + " GB"
    }

    private fun oneDecimal(value: Double): String =
        String.format(Locale.US, "%.1f", value)

    /**
     * `今天 14:26` / `昨天 21:04` / **`前天 18:40`** / `9月3日 14:12` / `2025年9月3日`。
     *
     * 「前天」这一档是稿子写死的：`workspace-final.html:908` 的
     * `'src/session/fixtures/golden-order.txt'` 就是 `前天 18:40`。少了它，昨天以前、今年以内
     * 的日期直接从「昨天」跳到「9月3日」，两天前与三周前读起来一样模糊。
     */
    fun formatTime(millis: Long, now: Long = System.currentTimeMillis()): String {
        if (millis <= 0L) return "时间未知"
        val stamp = SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))
        val then = Calendar.getInstance().apply { timeInMillis = millis }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val yesterday = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val dayBefore = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -2)
        }
        val sameYear = then.get(Calendar.YEAR) == today.get(Calendar.YEAR)
        return when {
            sameDay(then, today) -> "今天 $stamp"
            sameDay(then, yesterday) -> "昨天 $stamp"
            sameDay(then, dayBefore) -> "前天 $stamp"
            sameYear -> "${then.get(Calendar.MONTH) + 1}月${then.get(Calendar.DAY_OF_MONTH)}日 $stamp"
            else -> "${then.get(Calendar.YEAR)}年${then.get(Calendar.MONTH) + 1}月" +
                "${then.get(Calendar.DAY_OF_MONTH)}日"
        }
    }

    private fun sameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    /**
     * 叶子名太长时中间省略（design 稿的面包屑：`mid(n, 16)`）。
     *
     * 与稿子的 `mid()` 逐字同规则：留头 `ceil((max-1)/2)`、留尾 `floor((max-1)/2)`，
     * 中间一个省略号。省略中间而不是末尾，是因为路径的两端才是有信息的那两端。
     */
    fun middleEllipsis(text: String, max: Int): String {
        if (text.length <= max) return text
        val keep = max - 1
        val head = (keep + 1) / 2
        val tail = keep / 2
        return text.take(head) + "…" + text.takeLast(tail)
    }

    // --------------------------------------------------- 本次会话改过（③） ----

    /**
     * 「本次会话改过」：转录里 `write` / `edit` 的路径，最新改动在上。
     *
     * ## 与 `PiProject.touchedFiles` 的关系
     *
     * 那个函数是**读到 + 写入 + 编辑**的合集（「碰过」），这一屏上一版列的就是它。
     * design 稿这一段的标题是「本次会话改过」，数据 `W_CHANGED` 里也只有 `write`/`edit`，
     * 稿子底部第 2 条自己写明口径是「write / edit 过的文件 + 你自己的 File I/O 编辑」。
     * 所以这里是它的一次筛选，**不是**另一套解析：`argString` 的键、`ToolDiff` 的
     * `+N −M` 全部沿用 `ProjectResources.kt` 里那套。
     *
     * @param extraPaths 本次会话里通过这一屏的编辑器亲手保存过的路径 —— 稿子第 2 条
     *   把「你自己的 File I/O 编辑」算进这一列。
     */
    fun changedFiles(items: List<TranscriptItem>, extraPaths: Set<String> = emptySet()): List<ProjectFile> {
        val byPath = LinkedHashMap<String, ProjectFile>()
        for (item in items) {
            when (item) {
                is ToolCall -> {
                    val path = argString(item.args, "file_path", "path") ?: continue
                    val action = ProjectFileAction.of(item.toolName) ?: continue
                    // `read` 是「碰到」不是「改过」，这一列不收。
                    if (action == ProjectFileAction.Read) continue
                    val previous = byPath[path]
                    byPath[path] = ProjectFile(
                        path = path,
                        action = action,
                        at = maxOf(item.ts, previous?.at ?: 0L),
                        added = previous?.added ?: 0,
                        removed = previous?.removed ?: 0,
                    )
                }

                is app.pi.rpc.ToolDiff -> {
                    if (item.path.isEmpty()) continue
                    val previous = byPath[item.path]
                    byPath[item.path] = ProjectFile(
                        path = item.path,
                        action = previous?.action ?: ProjectFileAction.Edited,
                        at = maxOf(item.ts, previous?.at ?: 0L),
                        added = item.added,
                        removed = item.removed,
                    )
                }

                else -> Unit
            }
        }
        extraPaths.forEach { path ->
            if (byPath[path] == null) {
                byPath[path] = ProjectFile(
                    path = path,
                    action = ProjectFileAction.Edited,
                    at = System.currentTimeMillis(),
                    added = 0,
                    removed = 0,
                )
            }
        }
        return byPath.values.sortedByDescending { it.at }
    }

    /**
     * pi 正在写的那个文件（design 稿 ③ 里的 pending 行）。
     *
     * 判据是转录里 `status = Pending` 的 `write` / `edit` 工具卡 —— pi 一次只跑一个工具，
     * 所以取最后一条就是当下那一条。**已收到多少行**用 `ui.blocks.lineCount` 读 pi 已经
     * 流出来的输出（与「正在跑」的命令行同一个读数），拿不到就是 0，界面上就不写这半句。
     */
    fun pendingWrite(items: List<TranscriptItem>): WorkspacePendingWrite? {
        val call = items.filterIsInstance<ToolCall>().lastOrNull { item ->
            (item.toolName == "write" || item.toolName == "edit") &&
                item.status == ToolStatus.Pending
        } ?: return null
        return WorkspacePendingWrite(
            path = argString(call.args, "file_path", "path") ?: call.argsSummary,
            action = ProjectFileAction.of(call.toolName) ?: ProjectFileAction.Wrote,
            receivedLines = app.pi.ui.blocks.lineCount(call.output),
            at = call.ts,
        )
    }

    /**
     * pi 为这个路径报的耗时（`ToolCall.elapsedMs` = `endedAt - ts`），或者 null。
     *
     * design 稿 ③ 的副行写的是 `edit · 214ms · 12 分钟前` —— 那个 `214ms` 是 pi 自己的
     * 读数（工具卡页脚用的同一个字段 `blocks/ToolOutputParse.kt:251`），不是这一屏计出来的。
     * 只有 pi 报了才写：一个没被计过时的工具调用不该显示 `0ms`。
     */
    fun durationFor(items: List<TranscriptItem>, path: String, guestWorkspace: String): Long? {
        val call = items.filterIsInstance<ToolCall>().lastOrNull { item ->
            if (ProjectFileAction.of(item.toolName) == null) return@lastOrNull false
            val touched = argString(item.args, "file_path", "path") ?: return@lastOrNull false
            sameFile(path, touched, guestWorkspace)
        } ?: return null
        return call.elapsedMs
    }

    /**
     * 一个「本次会话改过」的路径 ↔ 目录树里的相对路径是否指同一个文件。
     *
     * pi 的 `read`/`write`/`edit` 参数既可能是工作区相对路径（cwd 就是工作区），也可能是
     * `/workspace/pi/workspaces/workspace-1/...` 这种 guest 拼法。两种都认，别的拼法不猜。
     */
    fun sameFile(relativePath: String, touchedPath: String, guestWorkspace: String): Boolean {
        val a = relativePath.trimStart('/')
        val b = touchedPath.trimStart('/')
        if (a == b) return true
        val prefix = guestWorkspace.trimEnd('/') + "/"
        return b.startsWith(prefix) && a == b.removePrefix(prefix)
    }
}

/** ③ 里那条「pi 正在写这个文件」的行。 */
internal data class WorkspacePendingWrite(
    val path: String,
    val action: ProjectFileAction,
    val receivedLines: Int,
    val at: Long,
)
