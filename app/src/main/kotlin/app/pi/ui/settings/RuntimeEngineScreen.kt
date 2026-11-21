package app.pi.ui.settings

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.BuildConfig
import app.pi.engine.EngineExitCause
import app.pi.engine.PiEngineSession
import app.pi.i18n.PiLocale
import app.pi.i18n.tr
import app.pi.packages.EngineRestartCoordinator
import app.pi.runtime.GuestEngine
import app.pi.runtime.GuestToolProbe
import app.pi.runtime.PiPaths
import app.pi.runtime.ProrootProbeNarrative
import app.pi.runtime.RuntimeChoice
import app.pi.runtime.RuntimePreferences
import app.pi.runtime.RuntimeSelection
import app.pi.ui.PiTopBar
import app.pi.ui.PiTopBarIcon
import app.pi.ui.models.ModelsCard
import app.pi.ui.models.ModelsCardPadding
import app.pi.ui.models.ModelsDivider
import app.pi.ui.models.ModelsSectionLabel
import app.pi.ui.models.TagPill
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File
import java.util.Locale

/**
 * 全新重构的「引擎与运行环境」专页：统一承载引擎实时状态、容器运行时切换（proot / proroot / tawcroot）、
 * 后台保活控制、异常与工具链自检、脱敏诊断报告预览导出、以及安全缓存清理与环境修复。
 *
 * 设计原则：
 *  1. 彻底移除旧版 `G_RUNTIME` 设置注册表行与 `AppOnlySettingsStore` 假键值映射；
 *  2. 引擎已退出或启动失败时，本页所有只读状态、崩溃诊断、工具链自检与导出报告依然可用；
 *  3. 重度操作（完整诊断报告、实时工具链探针、缓存清理）全部按需在 `Dispatchers.IO` 异步执行，进页零卡顿。
 */
@Composable
fun RuntimeEngineScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    store: PiSettingsStore,
    onSettingWritten: (String) -> Unit,
    activeEngine: () -> GuestEngine?,
    engineState: () -> PiEngineSession.EngineState?,
    engineDiagnostics: () -> EngineDiagnostics?,
    recentFailures: () -> List<String>,
    engineEntry: () -> String?,
    runtimeSwitch: RuntimeSwitchAction.Step,
    onSelectEngine: (GuestEngine) -> Unit,
    restartEngine: (suspend (reason: String, allowInterrupt: Boolean) -> EngineRestartCoordinator.Outcome)?,
    isTurnRunning: () -> Boolean,
    onStartEngine: () -> Unit,
    onRepairRuntime: () -> Unit,
    onRebuildRuntime: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val palette = PiTheme.palette

    val paths = remember(context) {
        PiPaths(
            filesDir = context.filesDir,
            nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
        )
    }
    val runtimePrefs = remember(context) { RuntimePreferences.get(context) }
    val runtimeSelection = remember(paths, runtimePrefs) { RuntimeSelection(paths, runtimePrefs) }

    var refreshEpoch by remember { mutableStateOf(0) }
    var snapshot by remember { mutableStateOf<RuntimeFacts.Snapshot?>(null) }
    var selectedEngine by remember { mutableStateOf(runtimeSelection.selectedEngine) }
    var statuses by remember { mutableStateOf<Map<GuestEngine, RuntimeSelection.Status>>(emptyMap()) }
    var keepAlive by remember {
        mutableStateOf((store.read("app.runtime.keepAlive") as? JsonPrimitive)?.booleanOrNull ?: true)
    }

    var bannerText by remember { mutableStateOf<String?>(null) }
    var bannerIsWarning by remember { mutableStateOf(false) }

    var expandedProbeEngine by remember { mutableStateOf<GuestEngine?>(null) }
    var showStderr by remember { mutableStateOf(false) }

    var toolProbeRunning by remember { mutableStateOf(false) }
    var toolProbeEngine by remember { mutableStateOf<GuestEngine?>(null) }
    var toolProbeReport by remember { mutableStateOf<GuestToolProbe.Report?>(null) }

    var reportText by remember { mutableStateOf<String?>(null) }
    var reportBusy by remember { mutableStateOf(false) }
    var showReportPreview by remember { mutableStateOf(false) }

    var restarting by remember { mutableStateOf(false) }
    var clearingCache by remember { mutableStateOf(false) }

    var confirmRestart by remember { mutableStateOf(false) }
    var confirmRepair by remember { mutableStateOf(false) }
    var confirmRebuild by remember { mutableStateOf(false) }

    // 进页与切换结束时在 IO 线程读取文件系统事实与各运行时缓存状态（绝不阻塞主线程）。
    LaunchedEffect(refreshEpoch, runtimeSwitch, PiLocale.isZh) {
        val loaded = withContext(Dispatchers.IO) {
            val facts = RuntimeFacts(paths).read()
            val sel = runtimeSelection.selectedEngine
            val map = GuestEngine.entries.associateWith { runtimeSelection.statusFor(it) }
            Triple(facts, sel, map)
        }
        snapshot = loaded.first
        selectedEngine = loaded.second
        statuses = loaded.third
    }

    val liveState = engineState()
    val liveEngine = activeEngine()
    val lastExit = engineDiagnostics()
    val failures = recentFailures()
    val entryLabel = engineEntry()

    fun setNotice(text: String, warning: Boolean = false) {
        bannerText = text
        bannerIsWarning = warning
    }

    fun runRestart(allowInterrupt: Boolean) {
        val handler = restartEngine
        if (handler == null) {
            setNotice(
                tr("重启入口未连接，请返回对话页重试。", "Restart handler is not connected."),
                warning = true,
            )
            return
        }
        restarting = true
        scope.launch {
            val outcome = handler(tr("在引擎与运行环境页手动重启", "Manual restart from Engine & Runtime"), allowInterrupt)
            restarting = false
            when (outcome) {
                is EngineRestartCoordinator.Outcome.Ok -> {
                    setNotice(tr("引擎已重启并重新连接。", "Engine restarted and reconnected."))
                    refreshEpoch++
                }
                is EngineRestartCoordinator.Outcome.Refused -> setNotice(outcome.message, warning = true)
                is EngineRestartCoordinator.Outcome.Failed -> setNotice(outcome.message, warning = true)
            }
        }
    }

    fun runToolSelfCheck() {
        if (toolProbeRunning) return
        val target = liveEngine ?: statuses[selectedEngine]?.engine ?: GuestEngine.Proot
        toolProbeRunning = true
        toolProbeEngine = target
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    GuestToolProbe.run(
                        paths = paths,
                        storage = android.os.Environment.getExternalStorageDirectory(),
                        engine = target,
                    )
                }.getOrElse { err ->
                    GuestToolProbe.Report(
                        emptyList(),
                        launchError = tr(
                            "自检运行异常：${err::class.java.simpleName}: ${err.message}",
                            "Self-check threw: ${err::class.java.simpleName}: ${err.message}",
                        ),
                    )
                }
            }
            toolProbeReport = result
            toolProbeRunning = false
        }
    }

    suspend fun ensureReport(): String {
        val existing = reportText
        if (existing != null) return existing
        reportBusy = true
        val built = withContext(Dispatchers.IO) {
            DiagnosticsReport.build(context, paths, engineDiagnostics(), recentFailures(), engineEntry())
        }
        reportText = built
        reportBusy = false
        return built
    }

    fun clearRuntimeCaches() {
        if (clearingCache) return
        clearingCache = true
        scope.launch {
            val (freedBytes, deletedConfigs) = withContext(Dispatchers.IO) {
                var bytes = 0L
                val compileCache = File(paths.rootfs, RuntimeFacts.COMPILE_CACHE_REL)
                if (compileCache.exists()) {
                    bytes += dirBytes(compileCache)
                    compileCache.deleteRecursively()
                }
                val sweep = runCatching { runtimeSelection.sweepProrootConfigs() }.getOrNull()
                runCatching { paths.clearProrootProbeCache() }
                runCatching { paths.clearTawcrootProbeCache() }
                bytes to (sweep?.dead ?: 0)
            }
            clearingCache = false
            reportText = null
            refreshEpoch++
            val freedText = if (freedBytes > 0L) RuntimeFacts.formatBytes(freedBytes) else "0 B"
            setNotice(
                tr(
                    "已清理编译缓存（释放 $freedText）、$deletedConfigs 份过期配置与探针缓存。",
                    "Cleared compile cache ($freedText freed), $deletedConfigs stale configs, and probe caches.",
                ),
            )
        }
    }

    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(
            title = tr("引擎与运行环境", "Engine & Runtime"),
            onBack = onBack,
            actions = {
                PiTopBarIcon(
                    icon = Icons.Filled.Refresh,
                    contentDescription = tr("刷新状态", "Refresh status"),
                    onClick = {
                        reportText = null
                        refreshEpoch++
                    },
                )
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupGap),
        ) {
            // 顶部即时操作反馈条
            val notice = bannerText
            if (notice != null) {
                item(key = "banner") {
                    val tone = if (bannerIsWarning) palette.warning else palette.accent
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                        shape = RoundedCornerShape(12.dp),
                        color = tone.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, tone.copy(alpha = 0.30f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                if (bannerIsWarning) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = tone,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                notice,
                                style = PiTheme.text.meta,
                                color = palette.text,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            // ------------------------------------------------------------------
            // 1. 实时引擎状态主卡
            // ------------------------------------------------------------------
            item(key = "hero") {
                val isHealthy = liveState == PiEngineSession.EngineState.Ready ||
                    liveState == PiEngineSession.EngineState.Busy
                val statusColor = when (liveState) {
                    PiEngineSession.EngineState.Ready -> palette.success
                    PiEngineSession.EngineState.Busy -> palette.accent
                    PiEngineSession.EngineState.Starting -> palette.warning
                    PiEngineSession.EngineState.Failed -> palette.error
                    PiEngineSession.EngineState.Stopped, null -> palette.muted
                }
                val statusLabel = when (liveState) {
                    PiEngineSession.EngineState.Ready -> tr("运行中", "Running")
                    PiEngineSession.EngineState.Busy -> tr("回合执行中", "Turn Running")
                    PiEngineSession.EngineState.Starting -> tr("正在启动…", "Starting…")
                    PiEngineSession.EngineState.Failed -> tr("异常退出", "Exited with Error")
                    PiEngineSession.EngineState.Stopped -> tr("已停止", "Stopped")
                    null -> tr("未运行", "Not Running")
                }
                val entryShort = when {
                    entryLabel == null -> null
                    entryLabel.contains("bundle") -> "bundle"
                    entryLabel.contains("dist/index.js") -> "dist"
                    else -> null
                }
                val startupText = formatStartupMs(snapshot?.engineStartupMs)
                val serviceText = when {
                    snapshot == null -> "…"
                    snapshot!!.serviceRunning && snapshot!!.wakeLockHeld ->
                        tr("运行中 · 唤醒锁", "Active · WakeLock")
                    snapshot!!.serviceRunning ->
                        tr("运行中 · 待机", "Active · Idle")
                    else -> tr("未启动", "Stopped")
                }

                ModelsCard(accent = isHealthy) {
                    Column(
                        modifier = Modifier.padding(ModelsCardPadding),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(statusColor),
                            )
                            Text(
                                statusLabel,
                                style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
                                color = palette.text,
                                modifier = Modifier.weight(1f),
                            )
                            if (liveEngine != null) {
                                TagPill(liveEngine.name.lowercase(), tone = statusColor)
                            }
                            if (startupText != null) {
                                TagPill(startupText, tone = palette.muted)
                            }
                        }

                        // 2x2 环境关键指标
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MetricTile(
                                label = "Pi SDK",
                                value = buildString {
                                    append(snapshot?.piVersion ?: "—")
                                    if (entryShort != null) append(" · $entryShort")
                                },
                                modifier = Modifier.weight(1f),
                            )
                            MetricTile(
                                label = "Node.js",
                                value = snapshot?.nodeVersion ?: "—",
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MetricTile(
                                label = tr("环境占用", "Runtime Disk"),
                                value = snapshot?.runtimeUsage ?: "—",
                                modifier = Modifier.weight(1f),
                            )
                            MetricTile(
                                label = tr("前台服务", "Background Service"),
                                value = serviceText,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        // 主操作按钮栏
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val canStartOnly = liveState == null ||
                                liveState == PiEngineSession.EngineState.Stopped ||
                                liveState == PiEngineSession.EngineState.Failed
                            ActionPillButton(
                                icon = if (canStartOnly) Icons.Filled.PlayArrow else Icons.Filled.Refresh,
                                text = when {
                                    restarting -> tr("正在重启…", "Restarting…")
                                    canStartOnly -> tr("启动引擎", "Start Engine")
                                    else -> tr("重启引擎", "Restart Engine")
                                },
                                primary = true,
                                enabled = !restarting && !runtimeSwitch.inFlight,
                                onClick = {
                                    if (canStartOnly) {
                                        onStartEngine()
                                        setNotice(tr("已触发引擎启动。", "Engine startup requested."))
                                    } else if (isTurnRunning()) {
                                        confirmRestart = true
                                    } else {
                                        runRestart(allowInterrupt = false)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            ActionPillButton(
                                icon = Icons.Filled.Build,
                                text = if (toolProbeRunning) {
                                    tr("自检中…", "Checking…")
                                } else {
                                    tr("工具链自检", "Check Tools")
                                },
                                primary = false,
                                enabled = !toolProbeRunning && !runtimeSwitch.inFlight,
                                onClick = ::runToolSelfCheck,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            // ------------------------------------------------------------------
            // 2. 容器运行时选择（三张交互式单选卡）
            // ------------------------------------------------------------------
            item(key = "label:runtime") {
                ModelsSectionLabel(tr("容器运行时", "Container Runtime"))
            }

            if (runtimeSwitch.inFlight) {
                item(key = "switch-progress") {
                    val stepText = RuntimeSwitchAction.statusLine(
                        runtimeSwitch,
                        RuntimeSwitchAction.Target.valueOf(selectedEngine.name),
                    ) ?: tr("正在切换运行时…", "Switching runtime…")
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                        shape = RoundedCornerShape(12.dp),
                        color = palette.accent.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, palette.accent.copy(alpha = 0.28f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = palette.accent,
                            )
                            Text(
                                stepText,
                                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                                color = palette.text,
                            )
                        }
                    }
                }
            }

            item(key = "runtime-cards") {
                ModelsCard {
                    Column(Modifier.fillMaxWidth()) {
                        GuestEngine.entries.forEachIndexed { index, engine ->
                            if (index > 0) ModelsDivider()
                            val st = statuses[engine]
                            val isSelected = selectedEngine == engine
                            val isLive = liveEngine == engine
                            val expanded = expandedProbeEngine == engine
                            RuntimeEngineOptionRow(
                                engine = engine,
                                status = st,
                                selected = isSelected,
                                live = isLive,
                                busy = runtimeSwitch.inFlight,
                                expanded = expanded,
                                onSelect = {
                                    if (!runtimeSwitch.inFlight) {
                                        selectedEngine = engine
                                        onSelectEngine(engine)
                                    }
                                },
                                onToggleDetail = {
                                    expandedProbeEngine = if (expanded) null else engine
                                },
                                onReprobe = {
                                    if (!runtimeSwitch.inFlight) {
                                        selectedEngine = engine
                                        onSelectEngine(engine)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // ------------------------------------------------------------------
            // 3. 进程与保活设置
            // ------------------------------------------------------------------
            item(key = "label:process") {
                ModelsSectionLabel(tr("进程与设备环境", "Process & Environment"))
            }
            item(key = "process-card") {
                ModelsCard {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val next = !keepAlive
                                    keepAlive = next
                                    store.write("app.runtime.keepAlive", JsonPrimitive(next))
                                    onSettingWritten("app.runtime.keepAlive")
                                }
                                .padding(horizontal = ModelsCardPadding, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    tr("后台前台服务保活", "Background Keep-Alive Service"),
                                    style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                                    color = palette.text,
                                )
                                Text(
                                    tr(
                                        "空闲时保留前台通知，降低切后台被系统查杀的概率；对话运行中始终自动保活。",
                                        "Keeps a foreground notification while idle to prevent OS kills; active turns are always protected.",
                                    ),
                                    style = PiTheme.text.meta,
                                    color = palette.muted,
                                )
                            }
                            Switch(
                                checked = keepAlive,
                                onCheckedChange = { next ->
                                    keepAlive = next
                                    store.write("app.runtime.keepAlive", JsonPrimitive(next))
                                    onSettingWritten("app.runtime.keepAlive")
                                },
                            )
                        }
                        ModelsDivider()
                        InfoFactRow(
                            label = tr("应用版本", "App Version"),
                            value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        )
                        ModelsDivider()
                        InfoFactRow(
                            label = tr("系统与架构", "Android & ABI"),
                            value = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}",
                        )
                        ModelsDivider()
                        InfoFactRow(
                            label = tr("运行时修订", "Runtime Revision"),
                            value = snapshot?.stampRevision ?: tr("未解包", "Not unpacked"),
                        )
                    }
                }
            }

            // ------------------------------------------------------------------
            // 4. 诊断与日志（引擎退出后依然完整可用）
            // ------------------------------------------------------------------
            item(key = "label:diag") {
                ModelsSectionLabel(tr("诊断与排障", "Diagnostics & Troubleshooting"))
            }

            if (lastExit != null || failures.isNotEmpty()) {
                item(key = "exit-card") {
                    val cause = lastExit?.let { EngineExitCause.summary(it.exitCode, it.stderr) }
                    val stderrText = lastExit?.stderr?.trim().orEmpty()
                    ModelsCard {
                        Column(
                            modifier = Modifier.padding(ModelsCardPadding),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Error,
                                    contentDescription = null,
                                    tint = palette.error,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    tr("最近一次引擎异常", "Recent Engine Incident"),
                                    style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
                                    color = palette.text,
                                    modifier = Modifier.weight(1f),
                                )
                                lastExit?.exitCode?.let { code ->
                                    TagPill(
                                        tr("退出码 $code", "Exit $code"),
                                        tone = if (code == 0) palette.muted else palette.error,
                                    )
                                }
                            }
                            if (!cause.isNullOrBlank()) {
                                Text(
                                    cause,
                                    style = PiTheme.text.meta,
                                    color = palette.text,
                                )
                            }
                            if (failures.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    failures.take(4).forEach { msg ->
                                        Text(
                                            "• $msg",
                                            style = PiTheme.text.meta,
                                            color = palette.muted,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            if (stderrText.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { showStderr = !showStderr }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        if (showStderr) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                        contentDescription = null,
                                        tint = palette.accent,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        if (showStderr) {
                                            tr("收起 stderr 尾部输出", "Hide stderr tail")
                                        } else {
                                            tr("查看 stderr 尾部输出", "Show stderr tail")
                                        },
                                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                                        color = palette.accent,
                                    )
                                }
                                if (showStderr) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    ) {
                                        Text(
                                            stderrText.takeLast(3000),
                                            modifier = Modifier.padding(10.dp),
                                            style = PiTheme.text.monoSmall,
                                            color = palette.text,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (toolProbeReport != null || toolProbeRunning) {
                item(key = "tool-probe-card") {
                    val report = toolProbeReport
                    ModelsCard {
                        Column(
                            modifier = Modifier.padding(ModelsCardPadding),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    tr("工具链真调用结果", "Live Toolchain Check"),
                                    style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
                                    color = palette.text,
                                    modifier = Modifier.weight(1f),
                                )
                                toolProbeEngine?.let { TagPill(it.name.lowercase(), tone = palette.accent) }
                                if (report != null) {
                                    TagPill(
                                        if (report.ok) tr("通过", "Pass") else tr("异常", "Failed"),
                                        tone = if (report.ok) palette.success else palette.error,
                                    )
                                }
                            }
                            if (toolProbeRunning) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = palette.accent,
                                    )
                                    Text(
                                        tr("正在 guest 内实测 rg / fd 执行与路径解析…", "Running rg / fd inside guest…"),
                                        style = PiTheme.text.meta,
                                        color = palette.muted,
                                    )
                                }
                            } else if (report != null) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    report.describe().forEach { line ->
                                        val isFail = line.trimStart().startsWith("✗")
                                        Text(
                                            line,
                                            style = PiTheme.text.monoSmall,
                                            color = if (isFail) palette.error else palette.text,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item(key = "report-card") {
                ModelsCard {
                    Column(
                        modifier = Modifier.padding(ModelsCardPadding),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    tr("诊断报告", "Diagnostics Report"),
                                    style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
                                    color = palette.text,
                                )
                                Text(
                                    tr(
                                        "汇总引擎日志、运行时探针、载荷清单与设备状态；密钥自动脱敏，绝不发给模型。",
                                        "Bundles engine logs, runtime probes, payloads & device info. Secrets redacted; never sent to models.",
                                    ),
                                    style = PiTheme.text.meta,
                                    color = palette.muted,
                                )
                            }
                            if (reportBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = palette.accent,
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SmallActionChip(
                                icon = Icons.Filled.ContentCopy,
                                label = tr("复制", "Copy"),
                                enabled = !reportBusy,
                                onClick = {
                                    scope.launch {
                                        val text = ensureReport()
                                        clipboard.setText(AnnotatedString(text))
                                        setNotice(tr("已复制脱敏诊断报告到剪贴板。", "Redacted diagnostics report copied."))
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            SmallActionChip(
                                icon = Icons.Filled.Share,
                                label = tr("分享", "Share"),
                                enabled = !reportBusy,
                                onClick = {
                                    scope.launch {
                                        val text = ensureReport()
                                        val name = DiagnosticsExport.fileName()
                                        val result = withContext(Dispatchers.IO) {
                                            DiagnosticsExport.share(context, text, name)
                                        }
                                        setNotice(result)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            SmallActionChip(
                                icon = if (showReportPreview) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                label = if (showReportPreview) tr("收起", "Hide") else tr("预览", "Preview"),
                                enabled = !reportBusy,
                                onClick = {
                                    if (showReportPreview) {
                                        showReportPreview = false
                                    } else {
                                        scope.launch {
                                            ensureReport()
                                            showReportPreview = true
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        // 保存到 Download 单独快捷行，避免按钮文字拥挤
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable(enabled = !reportBusy) {
                                    scope.launch {
                                        val text = ensureReport()
                                        val name = DiagnosticsExport.fileName()
                                        val result = withContext(Dispatchers.IO) {
                                            DiagnosticsExport.saveToDownloads(context, text, name)
                                        }
                                        setNotice(result)
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                tr("导出到 Download 目录（.txt）", "Save to Downloads Folder (.txt)"),
                                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                                color = palette.accent,
                            )
                        }

                        if (showReportPreview && reportText != null) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Text(
                                    reportText.orEmpty(),
                                    modifier = Modifier.padding(12.dp),
                                    style = PiTheme.text.monoSmall,
                                    color = palette.text,
                                )
                            }
                        }
                    }
                }
            }

            // ------------------------------------------------------------------
            // 5. 安全维护与修复
            // ------------------------------------------------------------------
            item(key = "label:maintenance") {
                ModelsSectionLabel(tr("维护与修复", "Maintenance & Repair"))
            }
            item(key = "maintenance-card") {
                ModelsCard {
                    Column(Modifier.fillMaxWidth()) {
                        MaintenanceActionRow(
                            icon = Icons.Filled.Delete,
                            tint = palette.accent,
                            title = tr("清理编译缓存与探针缓存", "Clear Compile & Probe Caches"),
                            subtitle = buildString {
                                append(
                                    tr(
                                        "清理 V8 编译缓存、过期配置表与探针结论；不影响项目、会话、设置或已安装的包。",
                                        "Removes V8 compile cache, stale configs, and probe caches. Keeps projects, sessions, settings & packages.",
                                    ),
                                )
                                snapshot?.compileCacheUsage?.let { size ->
                                    append(tr("（当前缓存约 $size）", " (cache ~$size)"))
                                }
                            },
                            enabled = !clearingCache,
                            onClick = ::clearRuntimeCaches,
                        )
                        ModelsDivider()
                        MaintenanceActionRow(
                            icon = Icons.Filled.Build,
                            tint = palette.warning,
                            title = tr("修复内置运行载荷（无损）", "Repair Built-in Payloads (Safe)"),
                            subtitle = tr(
                                "重新解包随 App 内置的 Node、Pi 与基础工具，保留你自己安装的 apt / npm 包与全部用户数据。",
                                "Re-extracts bundled Node, Pi & core tools while preserving your apt/npm packages and all user data.",
                            ),
                            enabled = !runtimeSwitch.inFlight,
                            onClick = { confirmRepair = true },
                        )
                        ModelsDivider()
                        MaintenanceActionRow(
                            icon = Icons.Filled.Warning,
                            tint = palette.error,
                            title = tr("重建 Linux 运行环境", "Rebuild Linux Runtime"),
                            subtitle = tr(
                                "清空并重建 Linux 系统树（会重置自行安装的 apt/npm 全局包）；项目、对话、设置与 API Key 均保留。",
                                "Wipes and rebuilds the Linux rootfs (resets custom apt/npm packages); projects, chats, settings & keys are preserved.",
                            ),
                            dangerous = true,
                            enabled = !runtimeSwitch.inFlight,
                            onClick = { confirmRebuild = true },
                        )
                    }
                }
            }
        }
    }

    if (confirmRestart) {
        PiSettingsDialog(
            onDismissRequest = { confirmRestart = false },
            title = tr("中止当前回合并重启引擎？", "Interrupt Turn & Restart Engine?"),
            body = tr(
                "当前有对话回合正在执行。立即重启会中止这次模型响应与正在运行的工具命令；已保存的对话记录不会丢失。",
                "A turn is currently running. Restarting now will abort the active response and running tool commands. Saved sessions will not be lost.",
            ),
            confirmationLabel = tr("重启", "Restart"),
            onConfirm = {
                confirmRestart = false
                runRestart(allowInterrupt = true)
            },
            dismissalLabel = tr("取消", "Cancel"),
            onDismissButton = { confirmRestart = false },
        )
    }

    if (confirmRepair) {
        PiSettingsDialog(
            onDismissRequest = { confirmRepair = false },
            title = tr("修复内置运行载荷", "Repair Built-in Payloads"),
            body = tr(
                "将重新解包内置的 Linux / Node / Pi 载荷并重启引擎。这个操作不会清空你安装的 apt/npm 包，也不会删除项目、会话、设置或凭证。",
                "Re-extracts bundled Linux / Node / Pi payloads and restarts the engine. Your apt/npm packages, projects, sessions, settings, and credentials stay intact.",
            ),
            confirmationLabel = tr("开始修复", "Repair"),
            onConfirm = {
                confirmRepair = false
                onRepairRuntime()
                setNotice(tr("已开始修复内置运行载荷，请稍候…", "Repairing built-in runtime payloads…"))
            },
            dismissalLabel = tr("取消", "Cancel"),
            onDismissButton = { confirmRepair = false },
        )
    }

    if (confirmRebuild) {
        PiSettingsDialog(
            onDismissRequest = { confirmRebuild = false },
            title = tr("重建 Linux 运行环境", "Rebuild Linux Runtime"),
            body = tr(
                "会删除整个易失 Linux 系统目录并重新解包（耗时约 1~2 分钟）。\n\n" +
                    "• 会清空：在终端里自行安装的 apt / pip / npm -g 系统工具\n" +
                    "• 完整保留：全部项目文件、所有对话会话、设置与 API Key / 登录凭证",
                "Deletes and re-extracts the Linux rootfs (~1–2 minutes).\n\n" +
                    "• Reset: custom apt / pip / global npm packages\n" +
                    "• Preserved: all projects, sessions, settings, and API keys / logins",
            ),
            confirmationLabel = tr("确认重建", "Rebuild"),
            onConfirm = {
                confirmRebuild = false
                onRebuildRuntime()
                setNotice(tr("已开始重建 Linux 运行环境，请回到对话页查看进度。", "Rebuilding Linux runtime…"))
            },
            dismissalLabel = tr("取消", "Cancel"),
            onDismissButton = { confirmRebuild = false },
        )
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            style = PiTheme.text.meta,
            color = palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ActionPillButton(
    icon: ImageVector,
    text: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerHigh
        primary -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val fg = when {
        !enabled -> PiTheme.palette.muted
        primary -> MaterialTheme.colorScheme.onPrimary
        else -> PiTheme.palette.text
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SmallActionChip(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fg = if (enabled) PiTheme.palette.text else PiTheme.palette.muted
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RuntimeEngineOptionRow(
    engine: GuestEngine,
    status: RuntimeSelection.Status?,
    selected: Boolean,
    live: Boolean,
    busy: Boolean,
    expanded: Boolean,
    onSelect: () -> Unit,
    onToggleDetail: () -> Unit,
    onReprobe: () -> Unit,
) {
    val palette = PiTheme.palette
    val title = engine.name.lowercase()
    val subtitle = when (engine) {
        GuestEngine.Proot -> tr(
            "默认稳定 · 全机型兼容，兼容性最高。",
            "Stable default · Highest compatibility across all devices.",
        )
        GuestEngine.Proroot -> tr(
            "进程内路径翻译 · 启动与文件 I/O 更快。",
            "In-process path hook · Faster startup and file I/O.",
        )
        GuestEngine.Tawcroot -> tr(
            "单进程 seccomp 陷阱 · 无 ptrace 开销，适合高频命令与终端。",
            "Single-process seccomp trap · Zero ptrace overhead for fast commands.",
        )
    }

    val (badgeLabel, badgeTone) = when {
        live -> tr("运行中", "Live") to palette.success
        engine == GuestEngine.Proot -> tr("内置可用", "Built-in") to palette.muted
        status == null -> tr("读取中", "Loading") to palette.muted
        status.missingComponents.isNotEmpty() -> tr("缺组件", "Missing") to palette.error
        RuntimeChoice.exhausted(status.failures) -> tr("连续失败", "Tripped") to palette.error
        status.probePassed == false -> tr("探针未过", "Probe Failed") to palette.warning
        status.probePassed == true -> tr("探针通过", "Ready") to palette.accent
        else -> tr("首次需自检", "Untested") to palette.muted
    }

    val fellBack = selected && engine != GuestEngine.Proot && status != null && status.engine != engine
    val probeLines = remember(status) {
        if (status == null || engine == GuestEngine.Proot) {
            emptyList()
        } else {
            ProrootProbeNarrative.detailLines(
                fallback = status.fallback,
                probePassed = status.probePassed,
                probeDetail = status.probeDetail,
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onSelect)
            .padding(horizontal = ModelsCardPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Radio dot
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .border(
                        width = if (selected) 2.dp else 1.5.dp,
                        color = if (selected) palette.accent else MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(palette.accent),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        title,
                        style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
                        color = palette.text,
                    )
                    TagPill(badgeLabel, tone = badgeTone)
                }
                Text(
                    subtitle,
                    style = PiTheme.text.meta,
                    color = palette.muted,
                )
            }
        }

        if (fellBack && status != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp),
                shape = RoundedCornerShape(8.dp),
                color = palette.warning.copy(alpha = 0.10f),
            ) {
                Text(
                    tr("已自动回退到 proot：", "Fell back to proot: ") + status.summary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = PiTheme.text.meta,
                    color = palette.text,
                )
            }
        }

        if (engine != GuestEngine.Proot && status != null && (probeLines.isNotEmpty() || selected)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (probeLines.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onToggleDetail)
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            if (expanded) tr("收起探针记录", "Hide probe log") else tr("查看探针记录", "Probe log"),
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                            color = palette.accent,
                        )
                    }
                }
                if (selected && !busy && status.missingComponents.isEmpty()) {
                    Text(
                        tr("重新检测并切换", "Re-probe & apply"),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onReprobe)
                            .padding(vertical = 2.dp),
                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                        color = palette.accent,
                    )
                }
            }
        }

        if (expanded && probeLines.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    probeLines.joinToString("\n"),
                    modifier = Modifier.padding(10.dp),
                    style = PiTheme.text.monoSmall,
                    color = palette.muted,
                )
            }
        }
    }
}

@Composable
private fun InfoFactRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ModelsCardPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            style = PiTheme.text.meta,
            color = PiTheme.palette.muted,
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = PiTheme.palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MaintenanceActionRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    dangerous: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = PiTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = ModelsCardPadding, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(tint.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title,
                style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                color = if (!enabled) palette.muted else if (dangerous) palette.error else palette.text,
            )
            Text(
                subtitle,
                style = PiTheme.text.meta,
                color = palette.muted,
            )
        }
    }
}

private fun formatStartupMs(ms: Long?): String? = when {
    ms == null -> null
    ms < 1000L -> "${ms}ms"
    else -> String.format(Locale.US, "%.1fs", ms / 1000.0)
}

private fun dirBytes(file: File): Long {
    if (!file.exists()) return 0L
    if (file.isFile) return file.length()
    var sum = 0L
    file.listFiles()?.forEach { child -> sum += dirBytes(child) }
    return sum
}
