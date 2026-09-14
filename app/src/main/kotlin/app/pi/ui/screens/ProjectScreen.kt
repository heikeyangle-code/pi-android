package app.pi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.packages.AgentLayout
import app.pi.packages.PiAutoExtensions
import app.pi.packages.PiResourceDiscovery
import app.pi.packages.ProjectTrust
import app.pi.packages.TrustRepository
import app.pi.rpc.ToolCall
import app.pi.rpc.ToolDiff
import app.pi.rpc.ToolStatus
import app.pi.rpc.TranscriptItem
import app.pi.runtime.PtyLauncher
import app.pi.ui.BashRun
import app.pi.ui.PiSessionViewModel
import app.pi.ui.PiTopBar
import app.pi.ui.PiTopBarIcon
import app.pi.ui.blocks.DiffBlock
import app.pi.ui.blocks.argString
import app.pi.ui.blocks.lineCount
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.settings.PiInfoNote
import app.pi.ui.settings.PiSettingsSectionHeader
import app.pi.ui.settings.PiSettingsCard
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.StateChip
import app.pi.ui.theme.StateTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * 工作区 —— 这个会话的**项目现场**（`03-navigation-decision.md` 的裁决）。
 *
 * ## 这一屏回答什么
 *
 * 「agent 在这个目录里到底在干什么？」用户在对话页看的是**过程**，在这里看的是**现场**：
 * 当前目录、这次会话碰过哪些文件、这个目录自己带什么资源、现在有没有命令在跑。它取代的是
 * 原来那个「整屏一个 PTY 终端」的工作区——终端已降为设置首页里的一行入口
 * （`06-v2-construction-reference.md` §5 的第一条）。
 *
 * ## 数据从哪来（一个都不能是编的）
 *
 * | 区块 | 来源 |
 * |---|---|
 * | 当前目录 | 当前会话的 `cwd`（pi 写在会话头里的 guest 写法），经 [PiProject.workspaceName] 转成可读名 |
 * | 正在跑 | `UiState.bash`（`!` 命令）与转录里 `status = Pending` 的 shell 工具卡——两者都是 pi 自己在跑的东西 |
 * | 这次会话碰过的文件 | 转录里 `read`/`write`/`edit` 的路径参数 + `ToolDiff` 的 `+N −M`（[PiProject.touchedFiles]） |
 * | 这个目录的资源 | `<workspace>/.pi` 下的 `skills`/`prompts`/`themes`，按 pi 自己的收集规则读（`PiResourceDiscovery`） |
 *
 * **没有** git 分支、**没有** git 改动数。理由写在 [PiProject] 的 KDoc 里：pi 没有这条
 * 通道（`rpc-types.ts:20-74` 里没有任何 branch/status/diff 命令），而工作区
 * （`<files>/pi/workspaces/workspace-1`）本身不是 git 仓库，在这上面跑 `git status` 只会
 * 拿到「not a git repository」——把那个失败画成「没有未提交的改动」就是把一次失败说成
 * 一条事实。拿不到就不显示。
 *
 * ## 它不是什么
 *
 * 它**不挂** `ExtensionUiHost`（全局只挂一次，在 `PiRoot`，那边的 KDoc 说明了为什么），
 * 它也**不碰** `ui/terminal` 这个目录——这一屏没有任何终端内容。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    contentPadding: PaddingValues,
    session: PiSessionViewModel,
) {
    val state by session.state.collectAsState()
    val sessions by session.sessions.collectAsState()
    val context = LocalContext.current

    // 当前会话的 cwd。`sessionFile` 是唯一说明「pi 现在在写哪个文件」的线上字段
    // （`SessionsScreen.kt:117` 用同一招判当前行），而 cwd 只存在于会话列表的摘要里——
    // 那是磁盘上会话头的读数，不是猜的。
    val activeFile = state.meta.sessionFile?.substringAfterLast('/')
    val currentCwd = remember(sessions, activeFile) {
        activeFile?.let { name -> sessions.firstOrNull { it.file.name == name }?.cwd }
    }

    // 项目资源是磁盘读取，不能放在 composition 里跑。key 是刷新计数，所以顶栏那个刷新
    // 键就是唯一的重新读取入口（v2 的顶栏也正好有一个刷新键）。
    var refreshTick by remember { mutableIntStateOf(0) }
    var resources by remember { mutableStateOf<List<PiResourceDiscovery.Found>>(emptyList()) }
    var extensions by remember { mutableStateOf<List<PiAutoExtensions.Found>>(emptyList()) }
    var projectUntrusted by remember { mutableStateOf(false) }
    LaunchedEffect(refreshTick, context, session) {
        withContext(Dispatchers.IO) {
            // 工作区主目录由应用固定（`GuestWorkspacePath.RELATIVE`），`AgentLayout` 的
            // `hostProjectConfigDir()` 就是 `<workspace>/.pi`——pi 的项目资源根。
            val workspace = PtyLauncher.workspaceHost(context)
            val layout = AgentLayout(context.applicationContext, workspace)
            val configDir = layout.hostProjectConfigDir()
            resources = runCatching { PiProject.projectResources(configDir) }
                .getOrDefault(emptyList())
            // 第四个资源种类：**扩展**。它不在 `PiResourceDiscovery` 里（pi 用另一套规则
            // 收集，`PiAutoExtensions` 就是那个读取器），所以这一屏原来一个扩展都不列：
            // 用户把扩展放进 `.pi/extensions` 之后，工作区屏看不到它。
            extensions = runCatching { PiProject.projectExtensions(configDir) }
                .getOrDefault(emptyList())
            // 信任：pi 只加载**受信任**项目的 `.pi` 资源（`interactive-mode.ts:3877`、
            // `:3888-3907`），未受信任时它照旧启动但忽略这些目录 —— 而这一屏原来照常
            // 把它们列出来，等于承诺了 pi 不会兑现的事。
            //
            // 判据与设置里的「扩展包与项目信任」卡同源（`PiPackagesHost.readTrust`）：
            // `trust.json` 的存档决定 + 设置里的 `defaultProjectTrust`，交给纯逻辑
            // `ProjectTrust.resolve` 裁决。`hasTrustRequiringResources` 在这里用**宿主侧
            // 同一批目录**判断（`.pi/` 下四个入口是否有东西），pi 还会额外看
            // `<cwd>/.agents/skills`；漏掉那一处只会让提示**少出现**，不会凭空出现。
            val requiresTrust = ProjectTrust.TRUST_REQUIRING_CONFIG_ENTRIES.any {
                File(configDir, it).exists()
            }
            if (requiresTrust) {
                val repository = TrustRepository(layout)
                val saved = runCatching { repository.decisionFor(layout.guestWorkspace) }.getOrNull()
                val defaultTrust = runCatching {
                    (session.settingsStore.read("defaultProjectTrust") as? JsonPrimitive)?.content
                }.getOrNull() ?: "ask"
                projectUntrusted = runCatching {
                    !ProjectTrust.resolve(
                        ProjectTrust.Inputs(
                            cwd = layout.guestWorkspace,
                            hasTrustRequiringResources = true,
                            savedDecision = saved,
                            defaultProjectTrust = defaultTrust,
                            // 这一屏不是那个对话框：不给答案（null），让 resolve 按存档
                            // 与默认值裁决，而不是替用户点一个。
                            hasUI = true,
                            userAnswer = null,
                        ),
                    ).trusted
                }.getOrDefault(false)
            } else {
                projectUntrusted = false
            }
        }
    }

    val touches = remember(state.transcript) { PiProject.touchedFiles(state.transcript) }
    val runs = remember(state.transcript, state.bash) { runningCommands(state.transcript, state.bash) }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        PiTopBar(
            title = "工作区",
            // v2 的副行就是这一屏的定义（`phone29`）：工作区 = 这个会话的项目现场。
            meta = "这个会话的项目现场",
            actions = {
                PiTopBarIcon(
                    onClick = { refreshTick++ },
                    contentDescription = "重新读取这个目录的资源",
                    icon = Icons.Filled.Refresh,
                )
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = PiSpacing.scrollBottom),
        ) {
            item {
                CurrentDirectoryCard(
                    name = currentCwd?.let { PiProject.workspaceName(it) } ?: "工作目录未记录",
                )
            }

            if (runs.isNotEmpty()) {
                item {
                    PiSettingsSectionHeader(
                        label = "正在跑",
                        count = "${runs.size} 条命令",
                        aside = "进入上下文",
                    )
                }
                runs.forEach { run ->
                    item(key = "run:${run.key}") { RunningCommandRow(run) }
                }
            }

            // 「碰过的文件」与「目录的资源」是两块不同的东西，各自有自己的空态——空表是
            // 真答案（确实没有），不是加载失败。
            item {
                PiSettingsSectionHeader(
                    label = "这次会话碰过的文件",
                    count = "${touches.size}",
                    aside = if (touches.isEmpty()) null else "来自工具调用",
                )
            }
            if (touches.isEmpty()) {
                item {
                    ProjectNoteRow(
                        lead = {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
                                tint = PiTheme.palette.muted,
                            )
                        },
                        title = "这次会话还没有碰过文件",
                        supporting = "agent 读过或改过文件之后，这里会按时间倒序列出它们。",
                    )
                }
            } else {
                touches.forEach { file ->
                    item(key = "file:${file.path}") {
                        TouchedFileRow(
                            file = file,
                            diff = remember(state.transcript, file.path) {
                                diffFor(state.transcript, file.path)
                            },
                        )
                    }
                }
            }

            // 项目目录里的**扩展**：第四个资源种类，pi 会加载它（受信任时），而这一屏
            // 原来只列技能/提示词/主题三种 —— 「我放了扩展但看不到」正是这一类的抱怨。
            item {
                PiSettingsSectionHeader(
                    label = "这个目录的扩展",
                    count = "${extensions.size} 项",
                    aside = ".pi/extensions",
                )
            }
            if (extensions.isEmpty()) {
                item {
                    ProjectNoteRow(
                        lead = {
                            Icon(
                                Icons.Filled.Extension,
                                contentDescription = null,
                                modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
                                tint = PiTheme.palette.muted,
                            )
                        },
                        title = "这个目录还没有扩展",
                        supporting = "把扩展放进这个目录的 extensions/ 之后重新打开这一屏；" +
                            "pi 启动时会连同它一起加载。",
                    )
                }
            } else {
                item {
                    PiSettingsCard {
                        extensions.forEach { extension ->
                            ProjectNoteRow(
                                lead = {
                                    Icon(
                                        Icons.Filled.Extension,
                                        contentDescription = null,
                                        modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
                                        tint = PiTheme.palette.muted,
                                    )
                                },
                                title = extension.name,
                                supporting = extension.relativePath,
                            )
                        }
                    }
                }
            }

            item {
                PiSettingsSectionHeader(
                    label = "这个目录的资源",
                    count = "${resources.size} 项",
                    aside = ".pi",
                )
            }
            // pi 只加载**受信任**项目的 `.pi` 资源（`interactive-mode.ts:3877`、
            // `:3888-3907`）。未受信任时它照旧启动、忽略这些目录，所以这一屏不能只是把它们
            // 列出来就完事 —— 那是在承诺 pi 不会兑现的事。措辞只说「本应用在这个目录里看到
            // 了什么」，不承诺 pi 会加载；受信任与否另有一句话说明在哪儿改。
            if (projectUntrusted) {
                item {
                    PiInfoNote(
                        text = "这个项目还没有被信任，pi 现在会忽略这个目录里的资源与扩展。" +
                            "要让它生效，去 设置 → 扩展包与项目信任 里对这个工作目录作出信任决定。",
                        tone = PiTheme.palette.warning,
                    )
                }
            }
            if (resources.isEmpty()) {
                item {
                    ProjectNoteRow(
                        lead = {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
                                tint = PiTheme.palette.muted,
                            )
                        },
                        title = "这个目录还没有 .pi 资源",
                        supporting = "技能、提示词、主题都会出现在这里；放进这个目录之后重新打开这一屏。",
                    )
                }
            } else {
                PiResourceDiscovery.Kind.entries.forEach { kind ->
                    val names = resources.filter { it.kind == kind }.map { it.name }
                    if (names.isNotEmpty()) {
                        item(key = "res:${kind.name}") {
                            ResourceRow(kind = kind, names = names)
                        }
                    }
                }
            }

            item {
                Text(
                    "这一屏是这个会话的项目现场：当前目录、这次会话碰过的文件，" +
                        "以及这个目录自己带的资源。",
                    modifier = Modifier.padding(
                        start = PiSettingsMetrics.pageHorizontal,
                        end = PiSettingsMetrics.pageHorizontal,
                        top = PiSettingsMetrics.footerTop,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 正在跑

/** One command the session has running, from either of the two places it can be. */
private data class RunningCommand(
    val key: String,
    val command: String,
    /** Null while pi has reported no duration (a `!` run never reports one). */
    val elapsedMs: Long?,
    val lines: Int,
    val inContext: Boolean,
)

/**
 * 正在跑的命令，取自**两个**真实来源，而不是一个。
 *
 * 1. `state.bash` —— `!` / `!!` 前缀的命令（[BashRun]，与 `PiSessionViewModel` 同一个
 *    文件里的顶层类型）。pi 不为它记
 *    时长，所以 `elapsedMs` 是 null，进度段也就不画（不画比画一条假的强）。
 * 2. 转录里 `status = Pending` 的 `bash`/`powershell` 工具卡 —— 模型让 pi 跑的命令。
 *    它的时长本来来自 `ToolCall.elapsedMs`（= `endedAt - ts`），pending 时还没结束，
 *    所以同样是 null；这一屏**不自己计时**，因为那量的是手机的重组，不是命令。
 *
 * 顺序：`BashRun` 在前（它是最新一次），其余按转录顺序；总数封顶 [MAX_RUNNING_ROWS]。
 */
private fun runningCommands(
    items: List<TranscriptItem>,
    bash: BashRun?,
): List<RunningCommand> {
    val out = mutableListOf<RunningCommand>()
    if (bash != null && bash.running) {
        out += RunningCommand(
            key = "bash",
            command = bash.command,
            elapsedMs = null,
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
                elapsedMs = item.elapsedMs,
                lines = lineCount(item.output),
                inContext = true,
            )
        }
    }
    return out.take(MAX_RUNNING_ROWS)
}

/** 顶多这么多条——「正在跑」超过四条的时候，它已经不是「正在跑」了。 */
private const val MAX_RUNNING_ROWS = 4

// ---------------------------------------------------------------- 区块

/**
 * 当前目录卡（v2：`surf-low` 底、圆角 10、左缘 2dp accent 条、标题 17/600、副行 12、
 * 右侧一枚徽章）。
 *
 * accent 在这里是**语义**用法：这一屏的主语就是这个目录（`06 §4` 已撤回「一屏只有一个
 * accent」，accent 按语义用）。徽章写「本次会话在用」，因为整屏就是「当前会话的项目
 * 现场」——它不是装饰，是这一屏的定义。
 */
@Composable
private fun CurrentDirectoryCard(name: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PiSettingsMetrics.pageHorizontal,
                end = PiSettingsMetrics.pageHorizontal,
                top = PiSpacing.cardPadding,
            ),
        shape = RoundedCornerShape(PiSettingsMetrics.cardRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 左缘 2dp accent 条，上下各缩进 8。
            Box(
                Modifier
                    .padding(start = 0.dp, top = PiSettingsMetrics.currentBarInset, bottom = PiSettingsMetrics.currentBarInset)
                    .width(PiSettingsMetrics.currentBarWidth)
                    .height(CURRENT_BAR_HEIGHT)
                    .clip(RoundedCornerShape(1.dp))
                    .background(PiTheme.palette.accent),
            )
            Row(
                modifier = Modifier.padding(
                    start = PiSettingsMetrics.cardPadding,
                    end = PiSettingsMetrics.cardPaddingLoose,
                    top = PiSettingsMetrics.cardPadding,
                    bottom = PiSettingsMetrics.cardPadding,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
            ) {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(PiSettingsMetrics.cardIconSize),
                    tint = PiTheme.palette.muted,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "会话的工作目录由 PI 固定，与 pi 共用",
                        modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StateChip(label = "本次会话在用", tone = StateTone.Accent, glyph = "●")
            }
        }
    }
}

/** 左缘 accent 条的高度（v2 的 `top:8;bottom:8` 撑出来的那一段）。 */
private val CURRENT_BAR_HEIGHT = 40.dp

/** 一条正在跑的命令：标题行 + 状态徽章 + 页脚读数 + 进度段。 */
@Composable
private fun RunningCommandRow(run: RunningCommand) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSettingsMetrics.pageHorizontal),
        shape = RoundedCornerShape(PiSettingsMetrics.cardRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = PiSettingsMetrics.rowPaddingVertical,
            ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
            ) {
                Icon(
                    Icons.Filled.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(PiSettingsMetrics.cardIconSize),
                    tint = PiTheme.palette.muted,
                )
                Text(
                    run.command,
                    modifier = Modifier.weight(1f),
                    style = PiTheme.text.mono,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StateChip(label = "运行中", tone = StateTone.Warning, glyph = "…")
            }
            Text(
                buildString {
                    append("运行中")
                    // 只有 pi 报了时长才写「已运行」——`!` 命令没有这一项，写 0.0 秒是假读数。
                    run.elapsedMs?.let { append(" · 已运行 ").append(secondsText(it)) }
                    if (run.lines > 0) append(" · ").append(run.lines).append(" 行")
                    append(" · ").append(if (run.inContext) "进入上下文" else "不进上下文")
                },
                modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                style = PiTheme.text.meta,
                color = PiTheme.palette.muted,
            )
            val elapsed = run.elapsedMs
            if (elapsed != null) {
                Row(
                    modifier = Modifier.padding(top = PiSettingsMetrics.notePaddingVertical),
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
}

/** v2 的进度条是 24 段（`direction-b-v2.html` 的 `Array.from({length:24})`）。 */
private const val TOTAL_PROGRESS_SEGMENTS = 24

/** 段高 3px、段间 3px（v2 的 `height:3` + `gap:3`）。 */
private val PROGRESS_SEGMENT_HEIGHT = 3.dp

/** 段间距，v2 用 `gap:3`。 */
private val PROGRESS_SEGMENT_GAP = 3.dp

/**
 * 已填充的段数。
 *
 * v2 的原型是 12.3 秒填 9 段（`i<9`），即每段约 1.5 秒。这里照这个比例推：
 * `elapsed / 1500`，封顶到满。它**不是**进度百分比——pi 不报这个命令跑到哪了——所以
 * 注满之后不再动，而不是假装 100% 完成。
 */
private fun progressSegments(elapsedMs: Long): Int =
    (elapsedMs / 1500L).toInt().coerceIn(0, TOTAL_PROGRESS_SEGMENTS)

/**
 * v2 的页脚写 `已运行 12.3 秒`：一位小数 + 「秒」。
 *
 * 与工具卡页脚那句「耗时 6.4 秒」同一套读数（`blocks/ToolOutputParse.kt:251` 的
 * `已运行`/`耗时` 语义），所以这里不另造措辞。
 */
private fun secondsText(ms: Long): String =
    String.format(Locale.US, "%.1f 秒", ms / 1000.0)

// ---------------------------------------------------------------- 碰过的文件

/**
 * 一行「这次会话碰过的文件」。
 *
 * 状态是三重编码（`06 §4`）：`读到` 用中性 `muted` + `✓`，`写入`/`编辑` 用 accent + `✎`，
 * 符号与文字都在。`+N −M` 用 pi 的 diff 两色（`toolDiffAdded`/`toolDiffRemoved`），**只在
 * pi 真的给了 diff 时**才出现——读操作没有这个读数，所以不会出现「+0 −0」。
 *
 * 有 diff 的行可点：展开就是那次 `edit` 的 diff，复用 `blocks/DiffBlock`（那一块不归本批
 * 改，所以只调用）。
 */
@Composable
private fun TouchedFileRow(file: ProjectFile, diff: ToolDiff?) {
    var expanded by rememberSaveable(file.path) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (diff != null) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(
                    horizontal = PiSettingsMetrics.pageHorizontal,
                    vertical = PiSettingsMetrics.rowPaddingVertical,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(PiSettingsMetrics.cardIconSize),
                tint = PiTheme.palette.muted,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    file.path,
                    style = PiTheme.text.monoSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(file.action.label)
                        append(" · ").append(relativeTime(file.at))
                        if (diff != null) {
                            append(if (expanded) " · 收起 diff" else " · 就地看这次 diff")
                        }
                    },
                    modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (file.hasDiff) {
                Row(horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.badgeGap)) {
                    Text(
                        "+${file.added}",
                        style = PiTheme.text.monoSmall,
                        color = PiTheme.palette.toolDiffAdded,
                    )
                    Text(
                        "−${file.removed}",
                        style = PiTheme.text.monoSmall,
                        color = PiTheme.palette.toolDiffRemoved,
                    )
                }
            }
            StateChip(
                label = file.action.label,
                tone = if (file.action == ProjectFileAction.Read) StateTone.Muted else StateTone.Accent,
                glyph = if (file.action == ProjectFileAction.Read) "✓" else "✎",
            )
        }
        if (expanded && diff != null) {
            DiffBlock(
                item = diff,
                modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal),
                defaultExpanded = true,
            )
        }
        InsetHairline()
    }
}

/** 行之间那根 inset hairline（与设置页那条同值、同一根线）。 */
@Composable
private fun InsetHairline() {
    HorizontalDivider(
        modifier = Modifier.padding(
            start = PiSettingsMetrics.pageHorizontal + PiSettingsMetrics.dividerInset,
        ),
        thickness = PiSettingsMetrics.hairline,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * 这次会话为某个路径留下的 diff，或 null。
 *
 * `edit` 卡与 `diff` 卡在转录里是**两条** item（`rpc/Transcript.kt:1405-1440`），所以这里
 * 按路径把后者找回来；`DiffBlock` 需要的 `ToolDiff` 本来就是它。取最后一条：同一路径被改
 * 过两次时，用户要看的是最新那次。
 */
private fun diffFor(items: List<TranscriptItem>, path: String): ToolDiff? =
    items.filterIsInstance<ToolDiff>().lastOrNull { it.path == path && it.diffText.isNotBlank() }

// ---------------------------------------------------------------- 资源

/** 一类项目资源：标题是 pi 的 kind，副行是 pi 自己收集出来的名字。 */
@Composable
private fun ResourceRow(kind: PiResourceDiscovery.Kind, names: List<String>) {
    ProjectNoteRow(
        lead = {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
                tint = PiTheme.palette.muted,
            )
        },
        title = kindLabel(kind),
        supporting = names.joinToString(" · "),
        trailing = "${names.size} 项",
    )
}

/** pi 的三个资源种类在界面上的名字（与设置里的资源页同一套词）。 */
private fun kindLabel(kind: PiResourceDiscovery.Kind): String = when (kind) {
    PiResourceDiscovery.Kind.Skills -> "技能"
    PiResourceDiscovery.Kind.Prompts -> "提示词"
    PiResourceDiscovery.Kind.Themes -> "主题"
}

// ---------------------------------------------------------------- 通用行

/**
 * 一栏里的一行：前置图标 + 标题 + 副行 + 可选尾部读数。
 *
 * 与设置首页的行同构（`padding:10px 12px`、前置图标 16 灰、标题 15/500、副行 12 灰），
 * 因为这一屏和设置页是同一个视觉语言。它不复用设置页的内部构件，是因为那些是
 * `internal`（`ui/settings/PiSettingsStyle.kt`），而本批不许改那个文件。
 */
@Composable
private fun ProjectNoteRow(
    lead: @Composable () -> Unit,
    title: String,
    supporting: String? = null,
    trailing: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = PiSettingsMetrics.pageHorizontal,
                vertical = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
    ) {
        lead()
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(
                    supporting,
                    modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Text(
                trailing,
                style = PiTheme.text.monoSmall,
                color = PiTheme.palette.muted,
            )
        }
    }
}

/**
 * 粗粒度相对时间，与会话列表**同一套措辞**（`SessionsScreen.kt:391-402`：`刚刚` /
 * `N 分钟前` / `N 小时前` / `N 天前` / `N 周前`）。规则重复一次而不是复用，因为那个函数
 * 是私有的、而且那个文件属于 B4（本轮不许碰）。
 */
private fun relativeTime(epochMillis: Long): String {
    val delta = System.currentTimeMillis() - epochMillis
    if (delta < 0) return "刚刚"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)} 天前"
        else -> "${minutes / (60 * 24 * 7)} 周前"
    }
}
