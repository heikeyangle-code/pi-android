package app.pi.runtime

import java.io.File
import java.io.RandomAccessFile
import java.time.Instant

/**
 * 耐久区里的**启动审计**：一次升级一行，回答「这次升级前后工作区根目录有什么」。
 *
 * ## 为什么要有这个文件
 *
 * 用户报了两件事：「升级完软件，很多东西、很多文件都会没有」和「工作区根目录的东西每次
 * 升级都会被删掉」。而用户手机上装的那份构建与我们手上的源码不是同一版（它的数据目录里
 * 有 `files/pi/persist/{cache,npm}`，源码里没有 `persist`），所以「升级时到底删了什么」
 * 只能由**设备自己**在升级那一刻记下来，再让用户导出诊断报告带出来。这一行就是那条证据：
 * 升级前的直接子项、升级后的直接子项、以及这次 APK 的 lastUpdateTime/versionCode 是否变过。
 *
 * ## 只在「升级了」的时候记，不是每次启动都遍历
 *
 * 触发条件是**这次启动相对上次审计至少有一个变了**：APK 的
 * `lastUpdateTime + versionCode`（由调用方读 `PackageManager` 并拼成字符串传进来，本文件
 * 因此不依赖 Android），或本次 APK 的 runtime revision。两者都记在耐久区的
 * [STATE_NAME] 里。所以一台设备每周升一次版就是每周一行；普通冷启动只读一个几十字节的
 * 状态文件、发现没变、直接返回——不是「每次启动全树遍历」。
 *
 * 首次运行（没有状态文件）会记一行基线：没有基线的话，下一次升级就没有「升级前」可比。
 *
 * ## 成本与代价（都是明码标价的）
 *
 *  - 主证据是工作区根与 agentDir 的**直接子项**（[TreeSnapshot.root]），O(顶层条目数)；
 *  - 量级用 [TreeSnapshot.walk] 的有界遍历，最多 [TreeSnapshot.WALK_BUDGET_ENTRIES] 个
 *    节点或 [TreeSnapshot.WALK_BUDGET_MS] 毫秒，停在哪条上限上会写进那一行；
 *  - **不算文件内容哈希**：大树上那是几十秒到几分钟的 IO，用户会当成卡死（实测经 proot
 *    的 459 MiB 树 `stat` 遍历就要 30 s）；
 *  - 文件本身有界（[MAX_LINES] 行），被挤掉的旧行数写在头部，不冒充；
 *  - 全部跑在后台 dispatcher 上（调用方：`PiEngineHost` 的 `scope`），不进首帧路径；
 *  - 读不到就写「读不到」，与「0 个」「不存在」三者在行内是三个不同的词。
 *
 * ## 它在哪里
 *
 * `<files>/pi/persist/boot-audit.log`（[DurableLayout.PERSIST_RELATIVE]）。**必须在耐久区**：
 * 它的整个用途是活过升级并与升级前对比，放进 rootfs 就自相矛盾了（那个目录由
 * `DurablePreserve` 保护，但它存在的意义正是给「修复」重建）。也不放进
 * `<rootfs>/root/.pi/agent`——那是 pi 自己的 home，本 App 的审计日志不该混进 pi 的目录。
 * `DiagnosticsReport` 会打印这个文件的路径和内容。
 *
 * `append` / `entry` / `shouldRecord` 是纯函数，由 bare-JVM harness 驱动；只有
 * [recordIfUpgraded] 和 [readTail] 碰文件系统。
 */
object BootAudit {

    /** 审计文件的名字，位于 `<files>/pi/persist/`。 */
    const val FILE_NAME: String = "boot-audit.log"

    /** 记录「上次审计对应的 APK 与 revision」的状态文件，位于同一目录。 */
    const val STATE_NAME: String = "boot-audit.state"

    /** 审计文件保留的行数上限；更早的行被挤掉，条数写在头部。 */
    const val MAX_LINES: Int = 40

    /** 头部前缀，也是「这一行是版本头而不是条目」的判据。 */
    const val HEADER_PREFIX: String = "# pi-android 启动审计 v1"

    /**
     * [readTail] 最多读入的字节数。
     *
     * 128 KiB 远大于 [MAX_LINES] 行正文（即使每行几 KB），所以正常文件总是完整读入；
     * 这个上限只为「文件被别的构建写成了巨物」兜底，而一旦真的截断，读入的内容里会带上
     * 一句截断说明（见 [readTail]），不会悄悄少读。
     */
    const val MAX_READ_CHARS: Int = 128 * 1024

    fun file(persistDir: File): File = File(persistDir, FILE_NAME)

    fun stateFile(persistDir: File): File = File(persistDir, STATE_NAME)

    /** 状态文件的正文：两行，`apk=` 与 `revision=`，值按原样写出（不转义、不截断）。 */
    fun stateText(apk: String, revision: String): String = "apk=$apk\nrevision=$revision\n"

    /** [stateText] 的读回：`(apk, revision)`，缺哪一半就是 null。 */
    fun parseState(text: String?): Pair<String?, String?> {
        if (text.isNullOrBlank()) return null to null
        var apk: String? = null
        var revision: String? = null
        text.lineSequence().forEach { line ->
            when {
                line.startsWith("apk=") -> apk = line.removePrefix("apk=").trim()
                line.startsWith("revision=") -> revision = line.removePrefix("revision=").trim()
            }
        }
        return apk to revision
    }

    /**
     * 这次要不要记。
     *
     * 没有状态 → 记（首次基线）；APK 变了 → 记；revision 变了 → 记；两个都没变 → 不记。
     * `apk`/`revision` 为空串（读不到）时保守地记：宁可多一行，也不要因为读不到一个值就
     * 在一次真实升级后什么证据都没有。
     */
    fun shouldRecord(state: String?, apk: String, revision: String): Boolean {
        val (lastApk, lastRevision) = parseState(state)
        if (lastApk == null && lastRevision == null) return true
        if (apk.isBlank() || revision.isBlank()) return true
        return lastApk != apk || lastRevision != revision
    }

    /**
     * 把一行追加进已有的审计正文，返回**新的完整正文**（头部 + 最近 [maxLines] 条）。
     *
     * 头部带 `total` / `kept` / `dropped` / `max` 四个计数：`total` 是这份文件记过的总行数，
     * `dropped` 是被 [maxLines] 挤掉的行数。这就是「可见的截断必须说得出」——读者不需要
     * 猜有没有旧行不见了。
     *
     * 头部不可识别时（例如文件被别的构建写过），已有的非头部行被当作历史条目计入 `total`，
     * 所以计数仍然是诚实的「我看得见的行」。
     */
    fun append(existing: String, line: String, maxLines: Int = MAX_LINES): String {
        val lines = existing.lineSequence().map { it.trimEnd('\r') }.toList()
        val header = lines.firstOrNull { it.startsWith(HEADER_PREFIX) }
        val prior = lines.filter { it.isNotBlank() && !it.startsWith(HEADER_PREFIX) }
        val counters = header?.let { text ->
            Regex("(\\w+)=(\\d+)").findAll(text).associate { it.groupValues[1] to it.groupValues[2].toLong() }
        }
        val baseline = maxOf(counters?.get("total") ?: 0L, prior.size.toLong())
        val total = baseline + 1L
        val entries = (prior + line).takeLast(maxLines)
        val kept = entries.size.toLong()
        val dropped = maxOf(0L, total - kept)
        return buildString {
            append(HEADER_PREFIX)
            append(" total=").append(total)
            append(" kept=").append(kept)
            append(" dropped=").append(dropped)
            append(" max=").append(maxLines)
            append('\n')
            entries.forEach { append(it).append('\n') }
        }
    }

    /**
     * 一次启动的审计条目，**一行**。
     *
     * 字段顺序固定，空值各有各的词，不互相冒充：
     *  - `t=` 时间（UTC ISO-8601 + epoch 毫秒，两者都在，机器与人各取所需）；
     *  - `apk=` 触发判据（`lastUpdateTime-versionCode`）；`rev=` 本次 APK 的 runtime revision；
     *  - `payloads=` 各载荷的 per-payload 摘要（`名字:摘要`，缺失写 `名字:缺失`）；
     *  - `reextract=` 这次启动真正覆盖重解了哪些载荷（`none` / `provision-failed` / 名字表）；
     *  - `ws=` 工作区根：存在性、`lastModified`、直接子项（名字/类型/大小/mtime）与未记下的项数；
     *  - `wswalk=` 工作区根的量级（有预算，截断就写明）；
     *  - `agent=` / `agentwalk=` 同上，对象是 pi 的 agent 目录（`PiPaths.agentDir`）。
     */
    fun entry(
        timestamp: Instant,
        apk: String,
        revision: String,
        payloads: String,
        reextracted: String,
        workspace: TreeSnapshot.Root,
        workspaceWalk: TreeSnapshot.Walk,
        agent: TreeSnapshot.Root,
        agentWalk: TreeSnapshot.Walk,
    ): String = buildString {
        append("t=").append(timestamp.toString())
        append(" epochMs=").append(timestamp.toEpochMilli())
        append(" apk=").append(if (apk.isBlank()) "读不到" else apk)
        append(" rev=").append(if (revision.isBlank()) "读不到" else revision)
        append(" payloads=").append(payloads.ifBlank { "读不到" })
        append(" reextract=").append(reextracted.ifBlank { "none" })
        append(" ws=").append(TreeSnapshot.rootSummary(workspace))
        append(" wswalk=").append(TreeSnapshot.walkSummary(workspaceWalk))
        append(" agent=").append(TreeSnapshot.rootSummary(agent))
        append(" agentwalk=").append(TreeSnapshot.walkSummary(agentWalk))
    }

    /**
     * 读取已有正文；文件不存在返回 null，读失败也返回 null（调用方分不清这两者时按
     * 「读不到」写，见 [recordIfUpgraded] 的返回值）。
     *
     * 超过 [maxChars] 字节时只读入**尾部**（表头与更早的条目都不在），并在最前面插入一句
     * 说明它被截断了——所以丢弃计数会从截断处重新开始，读者能看到这是为什么。
     */
    fun readTail(file: File, maxChars: Int = MAX_READ_CHARS): String? {
        if (!file.isFile) return null
        val length = file.length()
        return runCatching {
            if (length <= maxChars) {
                file.readText()
            } else {
                RandomAccessFile(file, "r").use { handle ->
                    handle.seek(length - maxChars)
                    val buffer = ByteArray(maxChars)
                    val read = handle.read(buffer)
                    val tail = if (read <= 0) "" else String(buffer, 0, read, Charsets.UTF_8)
                    "（读取截断：文件 $length 字节，只读入最后 $maxChars 字节，" +
                        "更早的内容未读入，丢弃计数从这里重新开始）\n" +
                        tail.substringAfter('\n', tail)
                }
            }
        }.getOrNull()
    }

    /**
     * 升级触发下的记录入口：需要记就写一行并更新状态，否则什么都不做。
     *
     * @return null 表示「成功，或者本来就不需要记」；非 null 是一行**可读的中文失败原因**，
     *   调用方负责把它写进日志（`PiEngineHost` 用 `Log.w`）。返回值故意不是布尔：
     *   「不需要记」与「写失败」是两件不同的事，用同一个 false 表达会让一次丢了证据的
     *   升级看起来像一次普通启动。
     */
    fun recordIfUpgraded(
        persistDir: File,
        apk: String,
        revision: String,
        payloads: String,
        reextracted: String,
        workspaceRoot: File,
        agentDir: File,
    ): String? {
        val state = readTail(stateFile(persistDir), 4096)
        if (!shouldRecord(state, apk, revision)) return null

        val line = entry(
            timestamp = Instant.now(),
            apk = apk,
            revision = revision,
            payloads = payloads,
            reextracted = reextracted,
            workspace = TreeSnapshot.root(workspaceRoot),
            workspaceWalk = TreeSnapshot.walk(workspaceRoot),
            agent = TreeSnapshot.root(agentDir),
            agentWalk = TreeSnapshot.walk(agentDir),
        )
        val existing = readTail(file(persistDir)).orEmpty()
        persistDir.mkdirs()
        if (!writeStampAtomically(file(persistDir), append(existing, line))) {
            return "启动审计写入失败（${file(persistDir).path}）：这次的升级证据没有落盘。"
        }
        if (!writeStampAtomically(stateFile(persistDir), stateText(apk, revision))) {
            return "启动审计状态写入失败（${stateFile(persistDir).path}）：下次启动会再记一行。"
        }
        return null
    }
}
