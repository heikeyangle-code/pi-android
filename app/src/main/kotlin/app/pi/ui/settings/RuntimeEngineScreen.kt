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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import app.pi.ui.components.PiSpinner
import app.pi.ui.models.ModelsCard
import app.pi.ui.models.ModelsCardPadding
import app.pi.ui.models.ModelsDivider
import app.pi.ui.models.ModelsSectionLabel
import app.pi.ui.models.TagPill
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.contrastOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File
import java.util.Locale

/**
 * 「引擎与运行环境」专页：
 *  1. 实时引擎状态主卡（毫秒级快读，绝不被磁盘扫描阻塞）；
 *  2. 独立「存储与环境占用」可视化分解卡（单次硬链接去重遍历，分离系统/引擎/用户数据/可清理缓存，一键无损清缓存）；
 *  3. 容器运行时三选一（proot / proroot / tawcroot）与逐阶段探针日志；
 *  4. 后台保活与设备环境参数；
 *  5. 崩溃取证、rg/fd 工具链实测、脱敏诊断报告导出、安全无损修复与重建。
 */
@Composable
fun RuntimeEngineScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    store: PiSettingsStore,
    freshness: Int = 0,
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
    val haptics = LocalHapticFeedback.current
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

    var refreshEpoch by remember { mutableIntStateOf(0) }
    var storageEpoch by remember { mutableIntStateOf(0) }

    var fastFacts by remember { mutableStateOf<RuntimeFacts.FastSnapshot?>(null) }
    var storage by remember { mutableStateOf<RuntimeFacts.StorageBreakdown?>(null) }
    var storageScanning by remember { mutableStateOf(true) }

    var selectedEngine by remember { mutableStateOf(GuestEngine.Proot) }
    var statuses by remember { mutableStateOf<Map<GuestEngine, RuntimeSelection.Status>>(emptyMap()) }
    var keepAlive by remember { mutableStateOf(true) }

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

    // 1) 快路径元数据（< 5ms）：绝不等待 3 万文件遍历，进页立刻渲染版本、服务状态、运行时与保活开关。
    LaunchedEffect(refreshEpoch, runtimeSwitch, freshness, PiLocale.isZh) {
        val loaded = withContext(Dispatchers.IO) {
            val fast = RuntimeFacts(paths).readFast()
            val sel = runtimeSelection.selectedEngine
            val map = GuestEngine.entries.associateWith { runtimeSelection.statusFor(it) }
            val keep = (store.read("app.runtime.keepAlive") as? JsonPrimitive)?.booleanOrNull ?: true
            FastLoadResult(fast, sel, map, keep)
        }
        fastFacts = loaded.fast
        selectedEngine = loaded.selected
        statuses = loaded.statuses
        keepAlive = loaded.keepAlive
    }

    // 2) 异步单遍硬链接去重磁盘统计：独立协程运行，仅更新「存储与环境占用」卡。
    LaunchedEffect(storageEpoch, refreshEpoch, runtimeSwitch) {
        storageScanning = true
        val measured = withContext(Dispatchers.IO) {
            runCatching { RuntimeFacts(paths).measureStorage(context.cacheDir) }.getOrNull()
        }
        if (measured != null) storage = measured
        storageScanning = false
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

    fun toggleKeepAlive(next: Boolean) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        keepAlive = next
        scope.launch {
            withContext(Dispatchers.IO) {
                store.write("app.runtime.keepAlive", JsonPrimitive(next))
            }
            onSettingWritten("app.runtime.keepAlive")
        }
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
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        clearingCache = true
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                RuntimeFacts(paths).clearSafeCaches(
                    appCacheDir = context.cacheDir,
                    sweepProrootConfigs = { runCatching { runtimeSelection.sweepProrootConfigs().dead }.getOrDefault(0) },
                )
            }
            clearingCache = false
            reportText = null
            storageEpoch++
            refreshEpoch++
            val freedText = if (res.freedBytes > 0L) RuntimeFacts.formatBytes(res.freedBytes) else "0 B"
            val extras = buildList {
                if (res.removedConfigs > 0) add(tr("${res.removedConfigs} 份过期配置", "${res.removedConfigs} stale configs"))
                if (res.removedProotTmp > 0) add(tr("${res.removedProotTmp} 项临时残留", "${res.removedProotTmp} temp entries"))
            }
            val extraSuffix = if (extras.isEmpty()) "" else tr("，以及 ${extras.joinToString("、")}", ", plus ${extras.joinToString(", ")}")
            setNotice(
                tr(
                    "已安全清理缓存（释放 $freedText$extraSuffix），并重置探针缓存。",
                    "Cleared caches ($freedText freed$extraSuffix) and reset probe caches.",
                ),
            )
        }
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrolled by remember(listState) {
        derivedStateOf { listState.canScrollBackward }
    }
    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(
            title = tr("引擎与运行环境", "Engine & Runtime"),
            onBack = onBack,
            scrolled = scrolled,
            actions = {
                PiTopBarIcon(
                    icon = Icons.Filled.Refresh,
                    contentDescription = tr("刷新状态", "Refresh status"),
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        reportText = null
                        refreshEpoch++
                        storageEpoch++
                    },
                )
            },
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(PiSpacing.small),
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
                        color = tone.copy(alpha = 0.10f),
                        border = BorderStroke(0.5.dp, tone.copy(alpha = 0.30f)),
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
            // 1. 实时引擎状态主卡（毫秒级快读，绝不阻塞）
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
                val startupText = formatStartupMs(fastFacts?.engineStartupMs)
                val serviceText = when {
                    fastFacts == null -> "…"
                    fastFacts!!.serviceRunning && fastFacts!!.wakeLockHeld ->
                        tr("运行中 · 唤醒锁", "Active · WakeLock")
                    fastFacts!!.serviceRunning ->
                        tr("运行中 · 待机", "Active · Idle")
                    else -> tr("未启动", "Stopped")
                }
                val revText = when {
                    fastFacts == null -> "…"
                    !fastFacts!!.runtimeUnpacked -> tr("未解包", "Not unpacked")
                    else -> fastFacts!!.stampRevision?.take(8) ?: tr("已解包", "Unpacked")
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

                        // 2×2 即时遥测指标（全快读，0ms 阻塞）
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MetricTile(
                                label = "Pi SDK",
                                value = buildString {
                                    append(fastFacts?.piVersion ?: "—")
                                    if (entryShort != null) append(" · $entryShort")
                                },
                                modifier = Modifier.weight(1f),
                            )
                            MetricTile(
                                label = "Node.js",
                                value = fastFacts?.nodeVersion ?: "—",
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MetricTile(
                                label = tr("前台服务", "Background Service"),
                                value = serviceText,
                                modifier = Modifier.weight(1f),
                            )
                            MetricTile(
                                label = tr("运行载荷版本", "Runtime Revision"),
                                value = revText,
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
                            RuntimeActionButton(
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
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                            RuntimeActionButton(
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
            // 2. 存储与环境占用（全新重做：单遍硬链接去重 + 4 项可视化拆解 + 一键清缓存）
            // ------------------------------------------------------------------
            item(key = "label:storage") {
                ModelsSectionLabel(tr("存储与环境占用", "Storage & Disk Usage"), Modifier.padding(top = 10.dp))
            }
            item(key = "storage-card") {
                RuntimeStorageCard(
                    breakdown = storage,
                    scanning = storageScanning,
                    clearingCache = clearingCache,
                    runtimeUnpacked = fastFacts?.runtimeUnpacked != false,
                    onRescan = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        storageEpoch++
                    },
                    onClearCaches = ::clearRuntimeCaches,
                )
            }

            // ------------------------------------------------------------------
            // 3. 容器运行时选择（三张交互式单选卡）
            // ------------------------------------------------------------------
            item(key = "label:runtime") {
                ModelsSectionLabel(tr("容器运行时", "Container Runtime"), Modifier.padding(top = 10.dp))
            }

            if (runtimeSwitch.inFlight) {
                item(key = "switch-progress") {
                    val switchTarget = when (runtimeSwitch) {
                        RuntimeSwitchAction.Step.RestartingToProot -> RuntimeSwitchAction.Target.Proot
                        RuntimeSwitchAction.Step.RestartingToProroot -> RuntimeSwitchAction.Target.Proroot
                        RuntimeSwitchAction.Step.RestartingToTawcroot -> RuntimeSwitchAction.Target.Tawcroot
                        else -> runCatching { RuntimeSwitchAction.Target.valueOf(selectedEngine.name) }
                            .getOrDefault(RuntimeSwitchAction.Target.Proot)
                    }
                    val stepText = RuntimeSwitchAction.statusLine(runtimeSwitch, switchTarget)
                        ?: tr("正在切换运行时…", "Switching runtime…")
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                        shape = RoundedCornerShape(12.dp),
                        color = palette.accent.copy(alpha = 0.10f),
                        border = BorderStroke(0.5.dp, palette.accent.copy(alpha = 0.28f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            PiSpinner(
                                size = 18.dp,
                                strokeWidth = 1.8.dp,
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
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSelectEngine(engine)
                                        refreshEpoch++
                                    }
                                },
                                onToggleDetail = {
                                    expandedProbeEngine = if (expanded) null else engine
                                },
                                onReprobe = {
                                    if (!runtimeSwitch.inFlight) {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSelectEngine(engine)
                                        refreshEpoch++
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // ------------------------------------------------------------------
            // 4. 进程与保活设置
            // ------------------------------------------------------------------
            item(key = "label:process") {
                ModelsSectionLabel(tr("进程与设备环境", "Process & Environment"), Modifier.padding(top = 10.dp))
            }
            item(key = "process-card") {
                ModelsCard {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { toggleKeepAlive(!keepAlive) }
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
                            PiSettingsSwitch(
                                checked = keepAlive,
                                onCheckedChange = ::toggleKeepAlive,
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
                            label = tr("完整修订号", "Full Revision"),
                            value = fastFacts?.stampRevision ?: tr("未解包", "Not unpacked"),
                        )
                    }
                }
            }

            // ------------------------------------------------------------------
            // 5. 诊断与日志（引擎退出后依然完整可用）
            // ------------------------------------------------------------------
            item(key = "label:diag") {
                ModelsSectionLabel(tr("诊断与排障", "Diagnostics & Troubleshooting"), Modifier.padding(top = 10.dp))
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
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 220.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    ) {
                                        SelectionContainer {
                                            Text(
                                                stderrText.takeLast(3000),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .verticalScroll(rememberScrollState())
                                                    .padding(10.dp),
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
                                    PiSpinner(
                                        size = 16.dp,
                                        strokeWidth = 1.8.dp,
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
                                PiSpinner(
                                    size = 18.dp,
                                    strokeWidth = 1.8.dp,
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
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable(enabled = !reportBusy) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                                SelectionContainer {
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
            }

            // ------------------------------------------------------------------
            // 6. 安全维护与修复（克制单色图标井 + 仅高危项用红）
            // ------------------------------------------------------------------
            item(key = "label:maintenance") {
                ModelsSectionLabel(tr("维护与修复", "Maintenance & Repair"), Modifier.padding(top = 10.dp))
            }
            item(key = "maintenance-card") {
                ModelsCard {
                    Column(Modifier.fillMaxWidth()) {
                        MaintenanceActionRow(
                            icon = Icons.Filled.Build,
                            title = tr("修复内置运行载荷（无损）", "Repair Built-in Payloads (Safe)"),
                            subtitle = tr(
                                "停止当前引擎并重新解包内置 Node、Pi 与基础工具，保留你自己安装的 apt / npm 包与全部用户数据。",
                                "Stops the engine and re-extracts bundled Node, Pi & core tools while keeping your apt/npm packages and user data.",
                            ),
                            enabled = !runtimeSwitch.inFlight,
                            onClick = { confirmRepair = true },
                        )
                        ModelsDivider()
                        MaintenanceActionRow(
                            icon = Icons.Filled.Warning,
                            title = tr("重建 Linux 运行环境", "Rebuild Linux Runtime"),
                            subtitle = tr(
                                "停止当前引擎并清空重建 Linux 系统树（重置自行安装的 apt/npm 全局包）；项目、对话、设置与凭证均保留。",
                                "Stops the engine and rebuilds the Linux rootfs (resets custom apt/npm packages); projects, chats, settings & keys are preserved.",
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
            confirmationTone = palette.warning,
            onConfirm = {
                confirmRestart = false
                runRestart(allowInterrupt = true)
            },
            dismissalLabel = tr("取消", "Cancel"),
            onDismissButton = { confirmRestart = false },
        )
    }

    if (confirmRepair) {
        val runningTurn = isTurnRunning()
        PiSettingsDialog(
            onDismissRequest = { confirmRepair = false },
            title = tr("修复内置运行载荷", "Repair Built-in Payloads"),
            body = buildString {
                if (runningTurn) {
                    append(
                        tr(
                            "注意：当前有对话回合正在运行，修复会先安全停止当前引擎。\n\n",
                            "Note: A turn is currently running and will be stopped before repair.\n\n",
                        ),
                    )
                }
                append(
                    tr(
                        "将重新解包内置的 Linux / Node / Pi 载荷并重启引擎。这个操作不会清空你安装的 apt/npm 包，也不会删除项目、会话、设置或凭证。",
                        "Re-extracts bundled Linux / Node / Pi payloads and restarts the engine. Your apt/npm packages, projects, sessions, settings, and credentials stay intact.",
                    ),
                )
            },
            confirmationLabel = tr("开始修复", "Repair"),
            onConfirm = {
                confirmRepair = false
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onRepairRuntime()
            },
            dismissalLabel = tr("取消", "Cancel"),
            onDismissButton = { confirmRepair = false },
        )
    }

    if (confirmRebuild) {
        val runningTurn = isTurnRunning()
        PiSettingsDialog(
            onDismissRequest = { confirmRebuild = false },
            title = tr("重建 Linux 运行环境", "Rebuild Linux Runtime"),
            body = buildString {
                if (runningTurn) {
                    append(
                        tr(
                            "注意：当前有对话回合正在运行，重建会先安全停止当前引擎。\n\n",
                            "Note: A turn is currently running and will be stopped before rebuilding.\n\n",
                        ),
                    )
                }
                append(
                    tr(
                        "会安全停止引擎后清空易失 Linux 系统目录并重新解包（耗时约 1~2 分钟）。\n\n" +
                            "• 会清空：在终端里自行安装的 apt / pip / npm -g 系统工具\n" +
                            "• 完整保留：全部项目文件、所有对话会话、设置与 API Key / 登录凭证",
                        "Stops the engine, resets the volatile Linux rootfs, and re-extracts (~1–2 minutes).\n\n" +
                            "• Reset: custom apt / pip / global npm packages\n" +
                            "• Preserved: all projects, sessions, settings, and API keys / logins",
                    ),
                )
            },
            confirmationLabel = tr("确认重建", "Rebuild"),
            confirmationTone = palette.error,
            onConfirm = {
                confirmRebuild = false
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onRebuildRuntime()
            },
            dismissalLabel = tr("取消", "Cancel"),
            onDismissButton = { confirmRebuild = false },
        )
    }
}

private data class FastLoadResult(
    val fast: RuntimeFacts.FastSnapshot,
    val selected: GuestEngine,
    val statuses: Map<GuestEngine, RuntimeSelection.Status>,
    val keepAlive: Boolean,
)

@Composable
private fun RuntimeStorageCard(
    breakdown: RuntimeFacts.StorageBreakdown?,
    scanning: Boolean,
    clearingCache: Boolean,
    runtimeUnpacked: Boolean,
    onRescan: () -> Unit,
    onClearCaches: () -> Unit,
) {
    val palette = PiTheme.palette
    val linuxColor = palette.accent
    val engineColor = palette.success
    val userColor = palette.warning
    val cacheColor = palette.muted

    ModelsCard {
        Column(
            modifier = Modifier.padding(ModelsCardPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 顶部总占用 + 扫描状态/重测
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = when {
                            breakdown != null -> RuntimeFacts.formatBytes(breakdown.totalBytes)
                            !runtimeUnpacked -> tr("未解包", "Not unpacked")
                            else -> tr("正在统计…", "Calculating…")
                        },
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = palette.text,
                    )
                    Text(
                        text = tr(
                            "按实际物理块去重统计（与 du 一致，不重复计硬链接）",
                            "Allocated blocks with hardlink deduplication (matches du)",
                        ),
                        style = PiTheme.text.meta,
                        color = palette.muted,
                    )
                }
                if (scanning) {
                    PiSpinner(size = 16.dp, strokeWidth = 1.8.dp, color = palette.accent)
                } else {
                    Surface(
                        onClick = onRescan,
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                tint = palette.muted,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                tr("重测", "Rescan"),
                                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                                color = palette.text,
                            )
                        }
                    }
                }
            }

            // 4 段比例可视化进度条
            val total = (breakdown?.totalBytes ?: 0L).coerceAtLeast(1L)
            val segments = remember(breakdown, linuxColor, engineColor, userColor, cacheColor) {
                if (breakdown == null || breakdown.totalBytes <= 0L) {
                    emptyList()
                } else {
                    listOf(
                        breakdown.linuxSystemBytes to linuxColor,
                        breakdown.engineRuntimeBytes to engineColor,
                        breakdown.userDataBytes to userColor,
                        breakdown.cleanableCacheBytes to cacheColor,
                    ).filter { it.first > 0L }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                if (segments.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        segments.forEach { (bytes, color) ->
                            val weight = (bytes.toFloat() / total.toFloat()).coerceAtLeast(0.025f)
                            Box(
                                modifier = Modifier
                                    .weight(weight)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(color),
                            )
                        }
                    }
                }
            }

            // 4 类明细分项
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StorageBreakdownRow(
                    dotColor = linuxColor,
                    title = tr("Linux 基础系统", "Linux Base System"),
                    subtitle = tr("Ubuntu 用户态、Git 与终端工具", "Ubuntu userland, Git & CLI tools"),
                    sizeText = breakdown?.let { RuntimeFacts.formatBytes(it.linuxSystemBytes) } ?: "—",
                )
                StorageBreakdownRow(
                    dotColor = engineColor,
                    title = tr("Node.js 与 Pi 引擎", "Node.js & Pi Engine"),
                    subtitle = tr("/opt/node 运行时与内置 Pi SDK", "/opt/node runtime & bundled Pi SDK"),
                    sizeText = breakdown?.let { RuntimeFacts.formatBytes(it.engineRuntimeBytes) } ?: "—",
                )
                StorageBreakdownRow(
                    dotColor = userColor,
                    title = tr("用户数据与项目", "User Data & Projects"),
                    subtitle = tr("会话记录、设置、扩展与本地项目目录", "Sessions, settings, extensions & projects"),
                    badge = tr("重建保留", "Preserved"),
                    sizeText = breakdown?.let { RuntimeFacts.formatBytes(it.userDataBytes) } ?: "—",
                )
                StorageBreakdownRow(
                    dotColor = cacheColor,
                    title = tr("可清理缓存", "Cleanable Caches"),
                    subtitle = buildCacheSubtitle(breakdown),
                    sizeText = breakdown?.let { RuntimeFacts.formatBytes(it.cleanableCacheBytes) } ?: "—",
                )
            }

            if (breakdown != null && !breakdown.complete) {
                Text(
                    text = buildString {
                        append(tr("注：", "Note: "))
                        val parts = buildList {
                            if (breakdown.unmeasured > 0) {
                                add(tr("${breakdown.unmeasured} 项无法读取块信息", "${breakdown.unmeasured} entries unmeasured"))
                            }
                            if (breakdown.unreadableDirectories > 0) {
                                add(tr("${breakdown.unreadableDirectories} 个目录无权限枚举", "${breakdown.unreadableDirectories} unreadable dirs"))
                            }
                        }
                        append(parts.joinToString(tr("；", "; ")))
                    },
                    style = PiTheme.text.meta,
                    color = palette.muted,
                )
            }

            ModelsDivider()

            // 底部：设备可用空间 + 一键清理缓存
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        tr("设备可用存储", "Device Free Space"),
                        style = PiTheme.text.meta,
                        color = palette.muted,
                    )
                    Text(
                        text = if (breakdown != null && breakdown.deviceAvailableBytes > 0L) {
                            val free = RuntimeFacts.formatBytes(breakdown.deviceAvailableBytes)
                            if (breakdown.deviceTotalBytes > 0L) {
                                "$free / ${RuntimeFacts.formatBytes(breakdown.deviceTotalBytes)}"
                            } else {
                                free
                            }
                        } else {
                            "—"
                        },
                        style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.Medium),
                        color = palette.text,
                    )
                }

                val cacheBytes = breakdown?.cleanableCacheBytes ?: 0L
                val cleanLabel = when {
                    clearingCache -> tr("清理中…", "Cleaning…")
                    cacheBytes >= 1024L -> tr("清理缓存 (${RuntimeFacts.formatBytes(cacheBytes)})", "Clear (${RuntimeFacts.formatBytes(cacheBytes)})")
                    else -> tr("清理缓存", "Clear Caches")
                }
                Surface(
                    onClick = onClearCaches,
                    enabled = !clearingCache,
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (clearingCache) {
                            PiSpinner(size = 14.dp, strokeWidth = 1.6.dp, color = palette.accent)
                        } else {
                            Icon(
                                Icons.Filled.CleaningServices,
                                contentDescription = null,
                                tint = palette.text,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        Text(
                            cleanLabel,
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                            color = if (clearingCache) palette.muted else palette.text,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private fun buildCacheSubtitle(breakdown: RuntimeFacts.StorageBreakdown?): String {
    if (breakdown == null || breakdown.cleanableCacheBytes <= 0L) {
        return tr(
            "V8 编译缓存、apt/npm 缓存、临时目录与更新安装包",
            "V8 compile cache, apt/npm caches, tmp & update APKs",
        )
    }
    val parts = buildList {
        if (breakdown.compileCacheBytes > 0L) {
            add(tr("编译 ${RuntimeFacts.formatBytes(breakdown.compileCacheBytes)}", "V8 ${RuntimeFacts.formatBytes(breakdown.compileCacheBytes)}"))
        }
        if (breakdown.pkgCacheBytes > 0L) {
            add(tr("包缓存 ${RuntimeFacts.formatBytes(breakdown.pkgCacheBytes)}", "pkgs ${RuntimeFacts.formatBytes(breakdown.pkgCacheBytes)}"))
        }
        if (breakdown.scratchCacheBytes > 0L) {
            add(tr("临时 ${RuntimeFacts.formatBytes(breakdown.scratchCacheBytes)}", "tmp ${RuntimeFacts.formatBytes(breakdown.scratchCacheBytes)}"))
        }
    }
    return parts.joinToString(" · ")
}

@Composable
private fun StorageBreakdownRow(
    dotColor: Color,
    title: String,
    subtitle: String,
    sizeText: String,
    badge: String? = null,
) {
    val palette = PiTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    title,
                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null) {
                    TagPill(badge, tone = palette.muted)
                }
            }
            Text(
                subtitle,
                style = PiTheme.text.meta,
                color = palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            sizeText,
            style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.Medium),
            color = palette.text,
            maxLines = 1,
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
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f))
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
            style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.Medium),
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RuntimeActionButton(
    icon: ImageVector,
    text: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerHigh
        primary -> palette.accent
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val fg = when {
        !enabled -> palette.muted
        primary -> palette.contrastOn(palette.accent)
        else -> palette.text
    }
    val border = if (!primary || !enabled) BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant) else null
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = bg,
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
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
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
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

    val fellBackStatus = status?.takeIf { selected && engine != GuestEngine.Proot && it.engine != engine }
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

        if (fellBackStatus != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp),
                shape = RoundedCornerShape(8.dp),
                color = palette.warning.copy(alpha = 0.10f),
            ) {
                Text(
                    tr("已自动回退到 proot：", "Fell back to proot: ") + fellBackStatus.summary,
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
    title: String,
    subtitle: String,
    dangerous: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = PiTheme.palette
    val iconColor = when {
        !enabled -> palette.muted
        dangerous -> palette.error
        else -> palette.text
    }
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
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (dangerous) palette.error.copy(alpha = 0.10f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                .border(
                    width = 0.5.dp,
                    color = if (dangerous) palette.error.copy(alpha = 0.28f)
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(10.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(17.dp))
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
