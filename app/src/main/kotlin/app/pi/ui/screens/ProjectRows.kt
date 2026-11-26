package app.pi.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.i18n.tr
import app.pi.protocol.ToolCall
import app.pi.protocol.ToolDiff
import app.pi.protocol.ToolStatus
import app.pi.protocol.TranscriptItem
import app.pi.ui.BashRun
import app.pi.ui.PiSessionViewModel
import app.pi.ui.blocks.DiffBlock
import app.pi.ui.blocks.argString
import app.pi.ui.blocks.lineCount
import app.pi.ui.components.PiMenu
import app.pi.ui.components.PiMenuItem
import app.pi.ui.components.PiMenuPlacement
import app.pi.ui.components.PiType
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.StateChip
import app.pi.ui.theme.StateTone
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay

// ================================================================ 工作区路径条

/** 芯片那一条的上下留白。 */
private val WS_CHIP_TOP = 10.dp
private val WS_CHIP_BOTTOM = 6.dp

/** 「文件 / 资源」分段那一条的上下留白。 */


@Composable
internal fun FolderGlyph() {
    Icon(
        Icons.Filled.Folder,
        contentDescription = null,
        modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
        tint = PiTheme.palette.muted,
    )
}

/**
 * 文件图标。稿子给「读不到改动」那条空态与视图里的文件行都是 `file` 图标
 * （`workspace-final.html:1476`），它说的是「这是一份文件」，不是一个目录。
 */
@Composable
private fun FileGlyph() {
    Icon(
        Icons.AutoMirrored.Filled.InsertDriveFile,
        contentDescription = null,
        modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
        tint = PiTheme.palette.muted,
    )
}

/** 稿子空目录块的 `padding:'20px 14px 18px'` + 内部 `marginTop:12` / `:10`。 */
internal val WS_EMPTY_DIR_TOP = 20.dp
internal val WS_EMPTY_DIR_BOTTOM = 18.dp
internal val WS_EMPTY_DIR_INSET = 14.dp
internal val WS_EMPTY_DIR_BUTTON_GAP = 12.dp
internal val WS_EMPTY_DIR_HINT_GAP = 10.dp

// ================================================================ ② 正在跑

/** One command the session has running, from either of the two places it can be. */
internal data class RunningCommand(
    val key: String,
    val command: String,
    /**
     * 行开始的那一刻，用来算**流式中的**读数。
     *
     * `ToolCall.elapsedMs` 是 `endedAt - ts`（`protocol/.../Transcript.kt`），调用还在跑时
     * `endedAt` 为 null，所以它**在流式中恒为 null** —— 稿子那种 running 态一定要有点阵与
     * 刻度，不能只靠它。转录里的 `bash` / `powershell` 调用带着自己的 `ts`，于是耗时按
     * **ViewModel 推来的那个粗时钟**算（`UiState.nowMs - ts`，见 [RunningCommandRow]）。
     * **不要在组合里现读表**：`System.currentTimeMillis() - ts` 算出来的是「这一行上一次重组
     * 是什么时候」，一条什么都不打印的命令既没有输出块也没有发布，也就没有重组，读数会冻在
     * 那儿直到有人点一下 —— 这正是 `blocks/ShellBlock` 修掉的那个错，别把它搬回来。
     * `!` / `!!` 那条来自 `BashRun`，它没有时间戳可算，所以那一条仍然没有读数（见
     * [runningCommands]）。
     */
    val startedAt: Long?,
    /** pi 报过的时长（调用结束后才有值）；有值时以它为准，不再算。 */
    val reportedMs: Long?,
    val lines: Int,
    val inContext: Boolean,
) {
    /**
     * 该显示给用户的时长：pi 报过的优先，其次按 [startedAt] 与传进来的读数算。
     *
     * `now` 由 [RunningCommandRow] 从 `UiState.nowMs` 取得，**不是**本帧现读的表。
     */
    fun elapsedMs(now: Long): Long? = reportedMs ?: startedAt?.let { (now - it).coerceAtLeast(0) }
}

/**
 * 段头右侧那句话（稿子 `aside="进入上下文"`）。
 *
 * 稿子只有一条命令，所以那句话是常量；这一屏同时可能有四条，而「进不进上下文」是**每一条
 * 各自的**事实。所以这里只在**全部一致**时才把它提到段头（这时行里不再重复写 —— 稿子的行副行
 * 本来也没有这半句：`:1471` 是 `'运行中 · 已运行 12.3 秒 · ' + RUNNING.out`），不一致时
 * 段头不表态，各行自己说自己的。这样既不会同一信息写两遍，也不会在混着 `!` 与普通 bash 时
 * 出现「段头说进入上下文、行里说不进」那种直接矛盾。
 */
internal fun runningAside(runs: List<RunningCommand>): (@Composable () -> Unit)? = when {
    runs.all { it.inContext } -> {
        {
            Text(
                tr("进入上下文", "In context"),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    runs.none { it.inContext } -> {
        {
            Text(
                tr("不进上下文", "Excluded from context"),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    else -> null
}

/**
 * 正在跑的命令，取自**两个**真实来源：`state.bash`（`!` / `!!` 前缀的 [BashRun]）与转录里
 * `status = Pending` 的 `bash`/`powershell` 工具卡。时长优先用 `ToolCall.elapsedMs`
 * （= `endedAt - ts`，结束后才有），流式中则按行自己的 `ts` 与 **ViewModel 每秒推一次**的
 * `UiState.nowMs` 算（判据 `UiState.hasPendingToolClock` 收的正是这里这批 Pending 行）——
 * 这一屏**不自己读表**，现读出来的只是「上一次重组到现在」。
 */
internal fun runningCommands(
    items: List<TranscriptItem>,
    bash: BashRun?,
): List<RunningCommand> {
    val out = mutableListOf<RunningCommand>()
    if (bash != null && bash.running) {
        out += RunningCommand(
            key = "bash",
            command = bash.command,
            // `BashRun`（`ui/PiSessionViewModel.kt`）没有时间戳字段：这一条算不出「已运行
            // 多久」，于是它没有刻度也没有进度段（拿不到就不画，不编一个数）。要补上它需要
            // 给 `BashRun` 加一个 `startedAt`，那个文件不在这一批的边界里。
            startedAt = null,
            reportedMs = null,
            lines = lineCount(bash.output),
            inContext = !bash.excludeFromContext,
        )
    }
    items.forEach { item ->
        if (item is ToolCall &&
            (item.toolName == "bash" || item.toolName == "powershell") &&
            item.status == ToolStatus.Pending
        ) {
            out += RunningCommand(
                key = item.key,
                command = argString(item.args, "command") ?: item.argsSummary,
                startedAt = item.ts,
                reportedMs = item.elapsedMs,
                lines = lineCount(item.output),
                inContext = true,
            )
        }
    }
    return out.take(MAX_RUNNING_ROWS)
}

/** 顶多这么多条——「正在跑」超过四条的时候，它已经不是「正在跑」了。 */
private const val MAX_RUNNING_ROWS = 4

/**
 * 一条正在跑的命令（稿子 ②）：行 + 卡下的 24 段进度条。
 *
 * 多条命令时每一条占**同一张卡的一片**（[WsCardSlice]）：稿子是一个 `Rows` 一张卡带行间
 * 线（`workspace-final.html:1455-1466`），原来每条命令各起一张卡，于是三四条命令会画出三四张
 * 圆角卡片，而它们其实是同一段信息。
 *
 * @param sliceIndex 这一条在 ② 里的序号（决定卡的上/下圆角与上方的行间线）。
 * @param nowMs 那个粗时钟（`UiState.nowMs`）：耗时 = 行的时间戳到 `nowMs`。`null` 时退回
 *   「这一帧现读一次表」，只在时钟首跳还没落地的那一次组合、或调用方没传时钟时发生 —— 它是
 *   回退读数，不是「没有读数」。**改这里时不要把回退当成主路**：直接在这儿
 *   `System.currentTimeMillis()` 会把读数变回「上一次重组」的函数，也就是这一屏原来那个
 *   「安静跑着的命令数字冻住」的错（见 [RunningCommand] 的 KDoc）。
 */
@Composable
internal fun RunningCommandRow(
    run: RunningCommand,
    sliceIndex: Int,
    lastSliceIndex: Int,
    nowMs: Long? = null,
    hairlineStart: Dp = PiSettingsMetrics.rowPaddingHorizontal,
) {
    val elapsed = run.elapsedMs(nowMs ?: System.currentTimeMillis())
    Column(Modifier.fillMaxWidth()) {
        WsCardSlice(index = sliceIndex, lastIndex = lastSliceIndex, hairlineStart = hairlineStart) {
            WsRow(
                title = run.command,
                mono = true,
                strong = true,
                lead = {
                    Icon(
                        Icons.Filled.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
                        tint = PiTheme.palette.muted,
                    )
                },
                badge = {
                    StateChip(label = tr("运行中", "Running"), tone = StateTone.Warning, glyph = "…")
                },
                // 稿子的副行只有这三个读数（`workspace-final.html:1471`）：`运行中 · 已运行
                // 12.3 秒 · 18 行`。「进入上下文/不进上下文」不在这里 —— 它由段头的 aside 说一次
                // （见 [runningAside]）。
                meta = buildString {
                    append(tr("运行中", "Running"))
                    elapsed?.let { append(tr(" · 已运行 ", " · ")).append(secondsText(it)) }
                    if (run.lines > 0) append(" · ").append(run.lines).append(tr(" 行", if (run.lines == 1) " line" else " lines"))
                },
                metaMono = true,
                trail = {
                    app.pi.ui.theme.DurationMeter(
                        ms = elapsed,
                        color = PiTheme.palette.warning,
                        contentDescription = tr("这条命令已运行 ", "Command running for ") +
                            (elapsed?.let { secondsText(it) } ?: tr("未知", "unknown")),
                    )
                },
            )
        }
        if (elapsed != null) {
            // 进度条在**卡外**，是它的兄弟：稿子把它画在 `Rows` 之后
            // （`workspace-final.html:1467-1475`），`padding:'10px 14px 0'`。
            Row(
                modifier = Modifier.padding(
                    start = WS_RUNNING_METER_INSET,
                    end = WS_RUNNING_METER_INSET,
                    top = WS_RUNNING_METER_TOP,
                ),
                horizontalArrangement = Arrangement.spacedBy(PROGRESS_SEGMENT_GAP),
            ) {
                val filled = progressSegments(elapsed)
                repeat(TOTAL_PROGRESS_SEGMENTS) { index ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(PROGRESS_SEGMENT_HEIGHT)
                            .clip(RoundedCornerShape(1.dp))
                            .background(
                                if (index < filled) {
                                    PiTheme.palette.warning
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                            ),
                    )
                }
            }
        }
    }
}

/** 稿子进度条的 `padding:'10px 14px 0'`。 */
private val WS_RUNNING_METER_INSET = 14.dp
private val WS_RUNNING_METER_TOP = 10.dp

/** v2 的进度条是 24 段（`direction-b-v2.html` 的 `Array.from({length:24})`）。 */
private const val TOTAL_PROGRESS_SEGMENTS = 24

/** 段高 3px、段间 3px（v2 的 `height:3` + `gap:3`）。 */
private val PROGRESS_SEGMENT_HEIGHT = 3.dp

/** 段间距，v2 用 `gap:3`。 */
private val PROGRESS_SEGMENT_GAP = 3.dp

/**
 * 已填充的段数：v2 的原型是 12.3 秒填 9 段，即每段约 1.5 秒，这里照这个比例推。
 * 它**不是**进度百分比——pi 不报这个命令跑到哪了——所以注满之后不再动。
 */
private fun progressSegments(elapsedMs: Long): Int =
    (elapsedMs / 1500L).toInt().coerceIn(0, TOTAL_PROGRESS_SEGMENTS)

/** v2 的页脚写 `已运行 12.3 秒`：一位小数 + 「秒」。 */
private fun secondsText(ms: Long): String =
    String.format(Locale.US, tr("%.1f 秒", "%.1fs"), ms / 1000.0)

// ================================================================ ③ 本次会话改过

/**
 * pi 正在写的那个文件（稿子 ③ 里的 pending 行）：`--tool-pending` 底 + 上下各 1px 状态色
 * + 一行说明。
 *
 * 「已收到 24 行」那半句只在 pi 真的流出了行的时候写 —— 给不出就只留「正在写入」，
 * 右侧的刻度也一并撤掉（稿子底部第 3 条自己就是这么要求的）。
 *
 * 卡壳与行间线由调用方的 [WsCardSlice] 提供，所以这里既不自己加页边、也不在末尾收一条
 * hairline（原来那条会把 ③ 的最后一行下面多画一条线，稿子只在行之间画）。
 */
@Composable
internal fun PendingWriteRow(pending: WorkspacePendingWrite) {
    var expanded by rememberSaveable(pending.path) { mutableStateOf(false) }
    // 稿子这一块是 `bg:--tool-pending` + `borderTop/Bottom:1px solid rgba(255,255,0,.35)`
    // （`workspace-final.html:1303-1304`）。`rgba(255,255,0,…)` 就是 pi 的 `warning`
    // （深色主题里 `warning = #FFFF00`），`.35` 也正是本 App 的状态描边 alpha
    // （`06 §2`「描边 1px 状态色 35%」），所以不写死颜色字面量。
    val pendingEdge = PiTheme.palette.warning.copy(alpha = 0.35f)
    val palette = PiTheme.palette
    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.toolPendingBg)
            .drawBehind {
                val line = PiSettingsMetrics.hairline.toPx()
                drawRect(pendingEdge, size = Size(size.width, line))
                drawRect(
                    pendingEdge,
                    topLeft = Offset(0f, size.height - line),
                    size = Size(size.width, line),
                )
            },
    ) {
        WsRow(
            title = pending.path,
            mono = true,
            strong = true,
            lead = { FileGlyph() },
            meta = null,
            trail = {
                if (pending.receivedLines > 0) {
                    app.pi.ui.theme.DurationMeter(
                        ms = MID_WRITE_TICK_MS,
                        color = palette.warning,
                        contentDescription = tr("pi 正在写入这个文件", "pi is writing this file"),
                    )
                }
            },
            onClick = { expanded = !expanded },
        )
        Row(
            // 稿子的说明行是 `padding:'0 12px 9px'`。
            modifier = Modifier.padding(
                start = PiSettingsMetrics.rowPaddingHorizontal,
                bottom = WS_PENDING_NOTE_BOTTOM,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
        ) {
            Text("…", style = PiTheme.text.monoSmall, color = palette.warning, maxLines = 1)
            Text(
                buildString {
                    append(
                        if (pending.action == ProjectFileAction.Edited) tr("pi 正在编辑这个文件", "pi is editing this file")
                        else tr("pi 正在写入这个文件", "pi is writing this file"),
                    )
                    if (pending.receivedLines > 0) {
                        append(tr(" · 已收到 ", " · ")).append(pending.receivedLines).append(tr(" 行", if (pending.receivedLines == 1) " line received" else " lines received"))
                    }
                },
                modifier = Modifier.weight(1f),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (expanded) {
            Text(
                tr("pi 还在写这个文件；落盘之后这块 diff 才会出现。", "pi is still writing this file; the diff will appear once saved."),
                modifier = Modifier.padding(
                    start = PiSettingsMetrics.rowPaddingHorizontal,
                    bottom = PiSettingsMetrics.rowPaddingVertical,
                ),
                style = PiTheme.text.meta,
                color = palette.muted,
            )
        }
    }
}

/** 稿子 pending 说明行的 `padding:'0 12px 9px'`。 */
private val WS_PENDING_NOTE_BOTTOM = 9.dp

/** 写入中的刻度用它的时长（稿子画的是 `Tick ms={1800}`）。 */
private const val MID_WRITE_TICK_MS = 1_800L

/**
 * 一行「本次会话改过」。
 *
 * 点行**就地展开那块 diff**（`blocks/DiffBlock`，这一批不许改它，所以只调用）；行尾 ⋮
 * 才是打开 / 编辑 / 重命名 / 删除 / 定位到对话。状态是三重编码：`本次会话` 徽标 + `+N −M`
 * 两色 + 符号，颜色从来不是唯一信号。
 *
 * 标题是**叶子名**、目录写进副行 —— 稿子的 `changedRow` 就是
 * `title={baseName(c.p)}` + `meta={目录 + ' · ' + tool + ' · ' + ms + ' · ' + when}`
 * （`workspace-final.html:1300-1309`）。整条内部路径当标题会把行挤成一句没人读完的长串，
 * 而且和 ④ 的目录树读起来是两种东西。
 *
 * 行末**不画** hairline：行间线由调用方的 [WsCardSlice] 在卡内画（稿子只在行**之间**画），
 * 否则这一段最后一行下面会多出一条悬空的线。
 */
@Composable
internal fun ChangedFileRow(
    file: ProjectFile,
    diff: ToolDiff?,
    durationMs: Long?,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    var expanded by rememberSaveable(file.path) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        WsRow(
            // 文件名无衬线 15/Medium；等宽只留给路径（第二行那段目录）。
            title = changedTitle(file.path),
            mono = false,
            strong = false,
            lead = { FileGlyph() },
            // **没有「本次会话」徽标**：整段就叫「本次会话改过」，每行再说一遍是同一个词
            // 印 N 次 —— 与资源页去掉重复来源徽标同一条规矩。
            meta = buildString {
                // 目录前缀（稿子的 `c.p.split('/').slice(0,-1).join('/') + ' · '`）。pi 的
                // path 可能是 guest 拼法（`ProjectResources.kt:165-166`），所以这里只按分隔符
                // 取，不改写它。
                changedDirectory(file.path)?.let { append(it).append(" · ") }
                append(file.action.label)
                // pi 报过耗时才写它 —— 稿子的副行是 `edit · 214ms · 12 分钟前`。
                durationMs?.takeIf { it > 0 }?.let {
                    append(" · ").append(app.pi.ui.blocks.formatDuration(it))
                }
                append(" · ").append(relativeTime(file.at))
                // 这里原来自造过一句「· 就地看这次 diff / · 收起 diff」：稿子的副行没有它
                // （`workspace-final.html:1303-1305` 只写 `目录 · tool · ms · when`），而
                // 「这点一下会展开」已经由整行可点与 `+N −M` 说出了。删除于本轮审查。
            },
            value = if (file.hasDiff) {
                {
                    Row(horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.badgeGap)) {
                        Text(
                            "+${file.added}",
                            style = PiTheme.text.monoSmall,
                            color = PiTheme.palette.toolDiffAdded,
                        )
                        // 稿子的 `diffValue`：只有真的删了行才写 −N，不画 `−0`。
                        if (file.removed > 0) {
                            Text(
                                "−${file.removed}",
                                style = PiTheme.text.monoSmall,
                                color = PiTheme.palette.toolDiffRemoved,
                            )
                        }
                    }
                }
            } else {
                null
            },
            trail = { WsMoreButton(onClick = onMenu) },
            onClick = { if (diff != null) expanded = !expanded else onClick() },
        )
        if (expanded && diff != null) {
            DiffBlock(
                item = diff,
                modifier = Modifier.padding(horizontal = PiSettingsMetrics.cardPadding),
                defaultExpanded = true,
            )
        }
    }
}

/** 稿子的 `baseName(c.p)`。 */
private fun changedTitle(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

/** 稿子把路径的目录那半写进副行；工作区根部没有目录，返回 null。 */
private fun changedDirectory(path: String): String? =
    path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { null }

/**
 * 这次会话为某个路径留下的 diff，或 null。
 *
 * `edit` 卡与 `diff` 卡在转录里是**两条** item（`protocol/.../Transcript.kt`），所以这里
 * 按路径把后者找回来；取最后一条：同一路径被改过两次时，用户要看的是最新那次。
 */
internal fun diffFor(items: List<TranscriptItem>, path: String): ToolDiff? =
    items.filterIsInstance<ToolDiff>().lastOrNull { it.path == path && it.diffText.isNotBlank() }

/** [ProjectScreen.resolveSession] 的结果：打开谁，以及把它叫什么。 */
internal class WorkspaceSessionFile(val file: File, val displayPath: String)

// ================================================================ ④ 全部文件

@Composable
private fun Crumb(text: String, mono: Boolean, current: Boolean, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier
            .then(if (current) Modifier else Modifier.clickable(onClick = onClick))
            // 面包屑原来只有字那么高，手指几乎点不中；上下补到约 32dp 的点击高度。
            .padding(vertical = 8.dp)
            // `widthIn(max = …)`，不是 `width(…)`：稿子每一段是 `maxWidth:170`
            // （`workspace-final.html:1500` + `.w-crumb`）：短名字按内容宽排，一行放不下就折行。
            // 定宽 170 让「工作区 / src / session」恒占 534dp，当前目录必然被挤出屏外。
            .widthIn(max = CRUMB_MAX_WIDTH),
        style = (if (mono) PiTheme.text.monoSmall else PiTheme.text.meta).let {
            if (current) it.copy(fontWeight = FontWeight.SemiBold) else it
        },
        color = if (current) MaterialTheme.colorScheme.onSurface else PiTheme.palette.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 稿子给每一段面包屑 `maxWidth:170`。 */
private val CRUMB_MAX_WIDTH = 170.dp

/**
 * 面包屑第一段的字：浏览的根是 **guest 的 `/`**（`<rootfs>`），所以它不叫「工作区」——
 * 工作区只是它下面 `workspace/pi/workspaces/<name>` 那一层 —— 所以它不叫「工作区」，
 * 那会把根和根下面的一层混成一个词。
 */
internal val ROOTFS_CRUMB_LABEL: String get() = tr("根", "Root")

/**
 * 目录树里的一行：目录在前、文件夹行点进去、文件行点开查看。
 *
 * 本次会话改过的行同样带「本次会话」徽标与 `+N −M` —— 这正是用户点名要的那条
 * （「最近文件的 diff，之前都没有」）：标记复用 ③ 的同一份数据，图标按
 * 文件夹 / 文本 / 二进制分三种（稿子为二进制单加了一个图标态）。
 */
internal enum class WorkspaceSearchScope {
    Folder,
    Names,
    Content;

    val label: String
        get() = when (this) {
            Folder -> tr("当前层", "Folder")
            Names -> tr("文件名", "Names")
            Content -> tr("搜内容", "Content")
        }
}

@Composable
internal fun WorkspaceEntryRow(
    entry: WorkspaceEntry,
    sessionChanged: String?,
    diff: ToolDiff?,
    subtitlePrefix: String? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    // 最近 10 分钟内动过的行：左缘一条 2.5dp 强调色细条（卡片圆角会把它裁成贴边的样子）。
    val recent = remember(entry.modifiedAt) {
        System.currentTimeMillis() - entry.modifiedAt in 0..RECENT_ROW_MS
    }
    val barColor = PiTheme.palette.accent
    val rowBg = if (selectionMode && selected) {
        barColor.copy(alpha = 0.065f)
    } else {
        Color.Transparent
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(rowBg)
            .then(
                if (recent && !selectionMode) {
                    Modifier.drawBehind {
                        drawRect(
                            barColor,
                            size = Size(2.5.dp.toPx(), size.height),
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        WsRow(
            title = WorkspaceFiles.middleEllipsis(entry.name, ENTRY_NAME_MAX_CHARS),
            mono = false,
            strong = false,
            lead = {
                if (selectionMode) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                            )
                            .border(
                                width = if (selected) 0.dp else 1.5.dp,
                                color = if (selected) Color.Transparent else MaterialTheme.colorScheme.outline,
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = tr("已选中", "Selected"),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.surface,
                            )
                        }
                    }
                } else {
                    EntryGlyph(entry.kind, entry.name)
                }
            },
            leadBackground = if (selectionMode) Color.Transparent else null,
            badge = if (sessionChanged != null && !selectionMode) {
                {
                    WsBadge(text = tr("本次会话", "This session"), tone = StateTone.Accent, dot = true)
                }
            } else {
                null
            },
            meta = buildString {
                if (!subtitlePrefix.isNullOrEmpty()) append(subtitlePrefix).append(" · ")
                if (entry.isDirectory) {
                    append(tr("${entry.childCount}项 · ${relativeTime(entry.modifiedAt)}", "${entry.childCount} ${if (entry.childCount == 1) "item" else "items"} · ${relativeTime(entry.modifiedAt)}"))
                } else {
                    append(relativeTime(entry.modifiedAt))
                }
            },
            value = if (selectionMode) {
                if (!entry.isDirectory) {
                    {
                        Text(
                            WorkspaceFiles.formatSize(entry.sizeBytes),
                            style = PiTheme.text.monoSmall,
                            color = PiTheme.palette.muted,
                            maxLines = 1,
                        )
                    }
                } else {
                    null
                }
            } else if (diff != null && (diff.added > 0 || diff.removed > 0)) {
                {
                    Row(horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.badgeGap)) {
                        Text(
                            "+${diff.added}",
                            style = PiTheme.text.monoSmall,
                            color = PiTheme.palette.toolDiffAdded,
                        )
                        // 稿子的 `diffValue`：只有真的删了行才写 −N，不画 `−0`。
                        if (diff.removed > 0) {
                            Text(
                                "−${diff.removed}",
                                style = PiTheme.text.monoSmall,
                                color = PiTheme.palette.toolDiffRemoved,
                            )
                        }
                    }
                }
            } else if (entry.isDirectory) {
                // 目录行尾的 ›：告诉人「点一下是走进去」，与文件行（点一下是打开）区分。
                {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = PiTheme.palette.muted,
                    )
                }
            } else {
                {
                    Text(
                        WorkspaceFiles.formatSize(entry.sizeBytes),
                        style = PiTheme.text.monoSmall,
                        color = PiTheme.palette.muted,
                        maxLines = 1,
                    )
                }
            },
            trail = if (selectionMode) null else { { WsMoreButton(onClick = onMenu) } },
            onClick = {
                if (selectionMode) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onOpen()
            },
            onLongClick = if (onLongPress != null) {
                {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                }
            } else {
                null
            },
        )
    }
}

/** 全项目内容搜索命中行：展示文件名、行号徽标、目录路径与匹配行代码摘要。 */
@Composable
internal fun ContentSearchHitRow(
    hit: ContentHit,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
) {
    val palette = PiTheme.palette
    val parentDir = hit.path.substringBeforeLast('/', "").ifEmpty { null }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(
                start = PiSettingsMetrics.rowPaddingHorizontal,
                end = 8.dp,
                top = 10.dp,
                bottom = 10.dp,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
        ) {
            Box(
                modifier = Modifier
                    .size(PiSpacing.glyphBox)
                    .clip(RoundedCornerShape(WsShape.small))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                EntryGlyph(hit.entry.kind, hit.entry.name)
            }
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = WorkspaceFiles.middleEllipsis(hit.entry.name, ENTRY_NAME_MAX_CHARS),
                        modifier = Modifier.weight(1f, fill = false),
                        style = PiType.rowTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "L${hit.lineNumber}",
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.Medium),
                        color = palette.accent,
                        maxLines = 1,
                    )
                }
                if (parentDir != null) {
                    Text(
                        text = parentDir,
                        modifier = Modifier.padding(top = 1.dp),
                        style = PiTheme.text.monoSmall,
                        color = palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            WsMoreButton(onClick = onMenu)
        }
        Text(
            text = hit.preview,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = PiSpacing.glyphBox + PiSettingsMetrics.rowGap, end = 6.dp, top = 5.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            style = PiTheme.text.monoSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 文件列表的排序方式。除「最近修改」外，目录排在文件前面。 */
/** pi 的文件发现（`@` 补全、`find`）默认排除的目录；其余点开头的项 pi 照列。 */
internal val PI_IGNORED_DIRS: Set<String> = setOf(".git", "node_modules")

internal enum class WorkspaceSort(comparatorOf: () -> Comparator<WorkspaceEntry>) {
    Name({ compareBy<WorkspaceEntry>({ !it.isDirectory }, { it.name.lowercase(java.util.Locale.ROOT) }) }),
    // 最近修改：目录和文件混排，真正最近动过的在最上面（默认）。
    Newest({ compareByDescending<WorkspaceEntry> { it.modifiedAt }.thenBy { it.name.lowercase(java.util.Locale.ROOT) } }),
    Largest({ compareBy<WorkspaceEntry> { !it.isDirectory }.thenByDescending { it.sizeBytes } }),
    Type({
        compareBy<WorkspaceEntry>(
            { !it.isDirectory },
            { it.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) },
            { it.name.lowercase(java.util.Locale.ROOT) },
        )
    }),
    ;

    val label: String
        get() = when (this) {
            Name -> tr("名称", "Name")
            Newest -> tr("最近修改", "Modified")
            Largest -> tr("大小", "Size")
            Type -> tr("类型", "Type")
        }

    val comparator: Comparator<WorkspaceEntry> by lazy(comparatorOf)
}


@Composable
internal fun WorkspaceFilterField(
    value: String,
    onChange: (String) -> Unit,
    searchScope: WorkspaceSearchScope? = null,
    onSelectScope: ((WorkspaceSearchScope) -> Unit)? = null,
) {
    val palette = PiTheme.palette
    val isDark = PiTheme.isDark
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = PiSettingsMetrics.pageHorizontal,
                vertical = 6.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(
                PiSpacing.hairlineThin,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.55f else 0.75f),
            ),
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = palette.muted,
                )
                androidx.compose.foundation.text.BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = PiTheme.text.prose.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(palette.accent),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty()) {
                                Text(
                                    when (searchScope) {
                                        WorkspaceSearchScope.Names -> tr("全项目递归搜索文件名…", "Search file names across project…")
                                        WorkspaceSearchScope.Content -> tr("搜索项目内文本文件内容…", "Search text inside project files…")
                                        else -> tr("筛选当前目录的文件名…", "Filter files in this folder…")
                                    },
                                    style = PiTheme.text.prose,
                                    color = palette.muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )
                if (value.isNotEmpty()) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable { onChange("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = tr("清除筛选", "Clear filter"),
                            modifier = Modifier.size(16.dp),
                            tint = palette.muted,
                        )
                    }
                }
            }
        }
        if (searchScope != null && onSelectScope != null) {
            val trackBg = if (isDark) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer
            val activeBg = if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else Color.White
            val trackBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.55f else 0.72f)
            val activeBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.75f else 0.90f)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = trackBg,
                border = BorderStroke(PiSpacing.hairlineThin, trackBorder),
            ) {
                Row(
                    modifier = Modifier.padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    WorkspaceSearchScope.entries.forEach { scope ->
                        val active = scope == searchScope
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(7.dp),
                            color = if (active) activeBg else Color.Transparent,
                            border = if (active) BorderStroke(PiSpacing.hairlineThin, activeBorder) else null,
                            onClick = {
                                if (!active) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelectScope(scope)
                                }
                            },
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = scope.label,
                                    style = PiTheme.text.meta.copy(
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                    ),
                                    color = if (active) MaterialTheme.colorScheme.onSurface else palette.muted,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 「最近动过」的判据：10 分钟。 */
private const val RECENT_ROW_MS = 10 * 60 * 1000L

/**
 * Interactive project switcher mounted directly inside `PiTopBar(titleContent = ...)`.
 * Replaces the old 160dp WorkspaceHeroCard so files take center stage immediately.
 */
@Composable
internal fun ProjectTopBarSwitcher(
    workspaceName: String,
    relativePath: String,
    isExternal: Boolean,
    trustLabel: String?,
    trustTrusted: Boolean?,
    changedCount: Int,
    runningCount: Int,
    onSwitchProject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val trustDotColor = when {
        trustLabel == null -> null
        trustTrusted == true -> palette.success
        trustTrusted == false -> palette.error
        else -> palette.warning
    }
    val scopePrefix = if (isExternal) tr("设备", "Device") else tr("沙盒", "Sandbox")
    val metaLine = buildString {
        append(scopePrefix)
        append(" · ")
        append(relativePath)
        if (runningCount > 0) {
            append(tr(" · ${runningCount} 运行中", " · $runningCount running"))
        } else if (changedCount > 0) {
            append(tr(" · ${changedCount} 改动", " · $changedCount changed"))
        }
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onSwitchProject)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = workspaceName,
                style = PiType.topBarTitle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (trustDotColor != null) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(trustDotColor),
                )
            }
            if (runningCount > 0) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(palette.warning),
                )
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = tr("切换项目", "Switch Project"),
                modifier = Modifier.size(18.dp),
                tint = palette.muted,
            )
        }
        Text(
            text = metaLine,
            style = PiTheme.text.monoSmall,
            color = palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Sleek single-row inline Files section header with count, dot-file visibility toggle,
 * sort dropdown, and search toggle.
 */
@Composable
internal fun WorkspaceInlineSearchSortBar(
    fileCount: Int?,
    sortMode: WorkspaceSort,
    sortMenuExpanded: Boolean,
    onSortMenuToggle: (Boolean) -> Unit,
    onSelectSort: (WorkspaceSort) -> Unit,
    showIgnored: Boolean,
    onToggleIgnored: () -> Unit,
    hideDotFiles: Boolean,
    onToggleDotFiles: () -> Unit,
    filterOpen: Boolean,
    filterActive: Boolean,
    onToggleFilter: () -> Unit,
    selectionMode: Boolean = false,
    selectedCount: Int = 0,
    allSelected: Boolean = false,
    onToggleSelectionMode: (() -> Unit)? = null,
    onSelectAll: (() -> Unit)? = null,
) {
    val palette = PiTheme.palette
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PiSettingsMetrics.pageHorizontal + 2.dp,
                end = PiSettingsMetrics.pageHorizontal - 2.dp,
                top = 12.dp,
                bottom = 6.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selectionMode) {
            Text(
                text = tr("已选 $selectedCount 项", "$selectedCount selected"),
                style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            if (onSelectAll != null) {
                WsChip(
                    text = if (allSelected) tr("取消全选", "Deselect all") else tr("全选", "Select all"),
                    solid = false,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelectAll()
                    },
                )
            }
            if (onToggleSelectionMode != null) {
                WsChip(
                    text = tr("完成", "Done"),
                    solid = true,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleSelectionMode()
                    },
                )
            }
            return
        }
        Text(
            text = tr("文件", "Files"),
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = palette.muted,
            maxLines = 1,
        )
        if (fileCount != null) {
            Text(
                text = "$fileCount",
                style = PiTheme.text.monoSmall,
                color = palette.dim,
                maxLines = 1,
            )
        }
        Spacer(Modifier.weight(1f))
        if (onToggleSelectionMode != null && (fileCount ?: 0) > 0) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleSelectionMode()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Checklist,
                    contentDescription = tr("多选文件", "Select multiple files"),
                    modifier = Modifier.size(18.dp),
                    tint = palette.muted,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggleDotFiles),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (hideDotFiles) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = if (hideDotFiles) {
                    tr("显示隐藏文件", "Show hidden files")
                } else {
                    tr("隐藏点文件", "Hide dotfiles")
                },
                modifier = Modifier.size(18.dp),
                tint = if (hideDotFiles) MaterialTheme.colorScheme.onSurface else palette.muted,
            )
        }
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSortMenuToggle(true) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = sortMode.label,
                    style = PiTheme.text.meta,
                    color = palette.muted,
                    maxLines = 1,
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = tr("排序方式", "Sort order"),
                    modifier = Modifier.size(14.dp),
                    tint = palette.muted,
                )
            }
            if (sortMenuExpanded) PiMenu(
                expanded = true,
                onDismiss = { onSortMenuToggle(false) },
                placement = PiMenuPlacement.Below,
                items = WorkspaceSort.entries.map { mode ->
                    PiMenuItem(mode.label) {
                        onSelectSort(mode)
                        onSortMenuToggle(false)
                    }
                } + PiMenuItem(
                    if (showIgnored) tr("收起 .git / node_modules", "Hide .git / node_modules")
                    else tr("显示 .git / node_modules", "Show .git / node_modules"),
                ) {
                    onToggleIgnored()
                    onSortMenuToggle(false)
                },
            )
        }
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggleFilter),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = tr("搜索文件与内容", "Search files & content"),
                modifier = Modifier.size(19.dp),
                tint = if (filterOpen || filterActive) MaterialTheme.colorScheme.onSurface else palette.muted,
            )
        }
    }
}

@Composable
internal fun ProjectConfigQuickCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = PiTheme.palette
    val isDark = PiTheme.isDark
    val shape = RoundedCornerShape(WsShape.medium)
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            PiSpacing.hairlineThin,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.45f else 0.70f),
        ),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = PiType.rowTitle.copy(fontSize = 14.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = PiTheme.text.meta,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = palette.dim,
            )
        }
    }
}

/**
 * Sticky full-width Studio path bar pinned directly beneath `PiTopBar` (outside `LazyColumn`).
 * Features always-working Up button (except at `/`), Location Jump pill (`~ ▾` / `/ ▾` / `SD ▾`),
 * Home shortcut when outside the active project, horizontally scrollable breadcrumbs,
 * and right-pinned `设为项目` pill for any writable directory.
 */
@Composable
internal fun WorkspacePathBar(
    workspaceName: String,
    inside: Boolean,
    homeSize: Int,
    crumbs: List<String>,
    rootLabel: String,
    locationLabel: String,
    canSetAsProject: Boolean,
    onUp: () -> Unit,
    onOpenLocations: () -> Unit,
    onHome: () -> Unit,
    onGo: (Int) -> Unit,
    onSetAsProject: () -> Unit,
) {
    val palette = PiTheme.palette
    val isDark = PiTheme.isDark
    val scroll = rememberScrollState()
    val atRoot = inside && crumbs.size == homeSize
    val canStepUp = crumbs.isNotEmpty()
    val hairlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.45f else 0.65f)

    LaunchedEffect(crumbs, locationLabel) {
        delay(50)
        scroll.animateScrollTo(scroll.maxValue)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .drawBehind {
                val strokePx = PiSpacing.hairlineThin.toPx()
                drawLine(
                    color = hairlineColor,
                    start = Offset(0f, size.height - strokePx / 2f),
                    end = Offset(size.width, size.height - strokePx / 2f),
                    strokeWidth = strokePx,
                )
            }
            .padding(
                start = PiSettingsMetrics.pageHorizontal,
                end = PiSettingsMetrics.pageHorizontal,
                top = 6.dp,
                bottom = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (canStepUp) {
            PathBarIconButton(
                icon = Icons.Filled.ArrowUpward,
                description = tr("返回上一级目录", "Up one folder"),
                emphasize = true,
                onClick = onUp,
            )
        }

        // Always-visible Location Jump pill (`~ ▾` / `/ ▾` / `SD ▾`)
        val locShape = RoundedCornerShape(8.dp)
        Row(
            modifier = Modifier
                .height(30.dp)
                .clip(locShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(
                    PiSpacing.hairlineThin,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.55f else 0.72f),
                    locShape,
                )
                .clickable(onClick = onOpenLocations)
                .padding(start = 8.dp, end = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = locationLabel,
                style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = tr("快速跳转位置", "Jump to location"),
                modifier = Modifier.size(14.dp),
                tint = palette.muted,
            )
        }

        if (!atRoot) {
            PathBarIconButton(
                icon = Icons.Filled.Home,
                description = tr("回到当前项目", "Back to project root"),
                emphasize = false,
                onClick = onHome,
            )
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scroll),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val sep: @Composable () -> Unit = {
                Text(
                    "›",
                    modifier = Modifier.padding(horizontal = 4.dp),
                    style = PiTheme.text.monoSmall,
                    color = palette.muted.copy(alpha = 0.65f),
                    maxLines = 1,
                )
            }
            if (inside) {
                Crumb(
                    text = workspaceName,
                    mono = false,
                    current = atRoot,
                    onClick = onHome,
                )
                crumbs.drop(homeSize).forEachIndexed { i, segment ->
                    sep()
                    Crumb(
                        text = segment,
                        mono = true,
                        current = homeSize + i == crumbs.lastIndex,
                        onClick = { onGo(homeSize + i + 1) },
                    )
                }
            } else {
                Crumb(text = rootLabel, mono = true, current = crumbs.isEmpty(), onClick = { onGo(0) })
                crumbs.forEachIndexed { i, segment ->
                    sep()
                    Crumb(
                        text = segment,
                        mono = true,
                        current = i == crumbs.lastIndex,
                        onClick = { onGo(i + 1) },
                    )
                }
            }
        }

        if (canSetAsProject) {
            WsChip(
                text = tr("设为项目", "Set Project"),
                solid = true,
                onClick = onSetAsProject,
            )
        }
    }
}

@Composable
private fun PathBarIconButton(
    icon: ImageVector,
    description: String,
    emphasize: Boolean,
    onClick: () -> Unit,
) {
    val isDark = PiTheme.isDark
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(30.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                PiSpacing.hairlineThin,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.55f else 0.72f),
                shape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(16.dp),
            tint = if (emphasize) MaterialTheme.colorScheme.onSurface else PiTheme.palette.muted,
        )
    }
}

/** 目录名太长时中间省略（与面包屑同一条规则）。 */
private const val ENTRY_NAME_MAX_CHARS = 28

/**
 * 图标家族：文件夹 / 压缩包 / 代码 / 文档 / 配置 / 图片 / HTML / 二进制。
 * 统一克制色阶，告别花哨彩虹图标块，与新版设置页中性高级感保持一致。
 */
private val CODE_EXTS = setOf(
    "kt", "kts", "java", "ts", "tsx", "js", "mjs", "cjs", "jsx", "py", "rs", "go",
    "c", "h", "cpp", "hpp", "cc", "sh", "bash", "zsh", "css", "scss", "sql", "rb", "php", "swift", "dart",
)
private val CONFIG_EXTS = setOf("json", "jsonc", "yaml", "yml", "toml", "xml", "properties", "gradle", "env", "ini", "lock")
private val DOC_EXTS = setOf("md", "markdown", "mdx", "txt", "rst")
private val ARCHIVE_EXTS = setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar")

@Composable
private fun entryIconTint(kind: WorkspaceEntryKind, name: String): Color {
    val isDark = PiTheme.isDark
    val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
    val calmNeutral = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
    return when {
        kind == WorkspaceEntryKind.Directory -> if (isDark) Color(0xFFD6A85C) else Color(0xFFA8721E)
        ext in ARCHIVE_EXTS -> if (isDark) Color(0xFFC29B6B) else Color(0xFF8C6239)
        kind == WorkspaceEntryKind.Binary -> PiTheme.palette.muted
        else -> calmNeutral
    }
}

@Composable
internal fun EntryGlyph(kind: WorkspaceEntryKind, name: String = "") {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
    val tint = entryIconTint(kind, name)
    when {
        kind == WorkspaceEntryKind.Directory -> Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
            tint = tint,
        )

        ext in ARCHIVE_EXTS -> Icon(
            Icons.Filled.FolderZip,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
            tint = tint,
        )

        kind == WorkspaceEntryKind.Binary -> Icon(
            imageVector = WsBinaryFileGlyph,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
            tint = tint,
        )

        kind == WorkspaceEntryKind.Image -> Icon(
            Icons.Filled.Image,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
            tint = tint,
        )

        kind == WorkspaceEntryKind.Html || ext == "svg" -> Icon(
            Icons.Filled.Language,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
            tint = tint,
        )

        ext in DOC_EXTS -> Icon(
            Icons.Filled.Description,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
            tint = tint,
        )

        ext in CONFIG_EXTS -> Icon(
            Icons.Filled.DataObject,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
            tint = tint,
        )

        ext in CODE_EXTS -> Icon(
            Icons.Filled.Code,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
            tint = tint,
        )

        else -> Icon(
            Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
            tint = tint,
        )
    }
}
