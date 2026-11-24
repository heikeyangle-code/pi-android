package app.pi.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import app.pi.engine.PiEngineSession
import app.pi.i18n.AppLanguage
import app.pi.i18n.PiLocale
import app.pi.i18n.tr
import app.pi.packages.EngineRestartCoordinator
import app.pi.runtime.RuntimePreferences
import app.pi.runtime.RuntimeSelection
import app.pi.settings.PiSettingsFileStore
import app.pi.settings.readBoolean
import app.pi.settings.readString
import app.pi.ui.settings.RuntimeSwitchAction
import app.pi.ui.theme.PiSystemPaletteSource
import app.pi.ui.theme.PiSystemPalettes
import app.pi.ui.theme.PiThemeLoader
import app.pi.ui.theme.PiTypographyProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.pi.packages.PiResourceCatalog
import app.pi.ui.theme.PiThemeEntry
import app.pi.ui.theme.PiThemeScope
import java.io.File
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.flow.update

/**
 * Seed the app-side preferences and palette before the engine is up.
 *
 * Called by `MainActivity` for the first frame and again whenever the system
 * appearance changes, because the automatic theme form `light/dark` is
 * resolved against it.
 */
internal fun PiSessionViewModel.startUiPreferencesImpl(systemDark: Boolean) {
    lastSystemDark = systemDark
    refreshPrefs()
    refreshTheme(systemDark)
}

/** Re-read pi's theme selection and re-resolve the palette. */
internal fun PiSessionViewModel.refreshThemeImpl(systemDark: Boolean = lastSystemDark) {
    lastSystemDark = systemDark
    viewModelScope.launch {
        val configured = configuredThemePaths()
        val setting = runCatching { settingsStore.readString("theme") }.getOrNull()
        val packageThemes: List<PiThemeEntry> = if (session?.state?.value == PiEngineSession.EngineState.Ready) {
            val loaded = withTimeoutOrNull(400) {
                runCatching { extensionsAccess()?.resources?.loaded() }.getOrNull()
            }
            val rawThemes = when (loaded) {
                is PiResourceCatalog.Parsed.Ok -> loaded.value.themes
                else -> emptyList()
            }
            val rootfs = host.paths().rootfs
            rawThemes.mapNotNull { t ->
                val name = t.name?.trim()?.takeIf { it.isNotEmpty() && !it.contains('/') } ?: return@mapNotNull null
                val guestPath = t.path?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val hostFile = if (guestPath.startsWith("/")) File(rootfs, guestPath.removePrefix("/")) else File(defaultWorkspace(), guestPath)
                if (!hostFile.isFile) return@mapNotNull null
                PiThemeEntry(name = name, path = hostFile.absolutePath, scope = PiThemeScope.Package)
            }
        } else {
            emptyList()
        }
        val resolved = withContext(Dispatchers.IO) {
            // 「配色方案」必须**先**灌进注入点，再让 loader 读它：`system` 分支的颜色
            // （`resolveSystemTheme`）就是从那个值推导的，写晚一步就会拿上一档的调色板
            // 渲染一次。放进同一个 IO 段里也是必须的 —— 首次读 `app-prefs.json` 会落盘，
            // 而 [refreshTheme] 的调用方都在主线程（启动的 `startUiPreferences` 与写完
            // 设置后的 `onSettingWritten`）。
            applySystemPalette()
            PiThemeLoader.load(
                agentDir = host.paths().agentDir,
                workspace = defaultWorkspace(),
                configured = configured,
                setting = setting,
                systemDark = systemDark,
                extraThemes = packageThemes,
            )
        }
        val entries = withContext(Dispatchers.IO) {
            runCatching {
                PiThemeLoader.discover(host.paths().agentDir, defaultWorkspace(), configured, packageThemes)
            }.getOrDefault(emptyList())
        }
        _theme.value = resolved
        _themeEntries.value = entries
    }
}

/**
 * 把「配色方案」（`app.appearance.systemPalette`）写进 `system` 主题的注入点。
 *
 * ## 为什么就在这里
 *
 * [refreshTheme] 是全树**唯一**会重新 `PiThemeLoader.load` 的入口：启动时的
 * [startUiPreferences]、写完设置后的 [onSettingWritten]（`app.appearance.` 前缀本来就会
 * 走到它）、以及系统深浅变化那一路，最后都落到这里。所以「先灌输入、再读 loader」只需要
 * 一个顺序点。放在 Application/Activity 里会多一条和它赛跑的路径，而且系统深浅变化那一刻
 * 不会重读；起协程轮询更不行 —— 这是个每次主题刷新读一次就够的值。
 *
 * ## 读不到 / 读坏了怎么办
 *
 * 权限、文件损坏、写到一半：一律回默认档（`PiSystemPalettes.presetOf(null)` =
 * pi 自己的默认）。设置值是一个字符串档位 id，一个拼错的 id 不该把界面变成“没有主题”；
 * `presetOf` 把所有未知 id 都归到同一档，所以行为是可预期的。
 *
 * 只在 IO 段里调用（见 [refreshTheme]）：首次读 `app-prefs.json` 会落盘。
 */
private fun PiSessionViewModel.applySystemPalette() {
    val id = runCatching { settingsStore.readString(PiSystemPalettes.SETTING_KEY) }.getOrNull()
    val background = runCatching { settingsStore.readString(PiSystemPalettes.BACKGROUND_KEY) }.getOrNull()
    val saturation = runCatching { settingsStore.readString(PiSystemPalettes.SATURATION_KEY) }.getOrNull()
    PiSystemPaletteSource.palette = PiSystemPalettes.paletteOf(id, background)
    PiSystemPaletteSource.saturation = PiSystemPalettes.saturationOf(saturation)
}

/** The `themes` setting, verbatim; used for discovery and for name lookup. */
private fun PiSessionViewModel.configuredThemePaths(): List<String> =
    runCatching {
        (settingsStore.read("themes") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }
            .orEmpty()
    }.getOrDefault(emptyList())

/**
 * Re-read [UiPrefs] from the settings documents.
 *
 * Runs off the main thread because the first read of each document hits the
 * disk; `PiSettingsFileStore` caches afterwards, so the settings stack's own
 * per-frame reads are unaffected.
 */
internal fun PiSessionViewModel.refreshPrefsImpl() {
    viewModelScope.launch {
        val prefs = withContext(Dispatchers.IO) { readPrefs() }
        _state.update { s -> s.copy(prefs = prefs) }
    }
}

internal fun PiSessionViewModel.readPrefs(): UiPrefs {
    fun string(key: String, fallback: String): String =
        settingsStore.readString(key)?.takeIf { it.isNotBlank() } ?: fallback

    fun bool(key: String, fallback: Boolean): Boolean =
        settingsStore.readBoolean(key) ?: fallback

    /** The raw primitive's own text, or null: used by the two migrations below. */
    fun raw(key: String): String? =
        (settingsStore.read(key) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    // 一次性读时迁移（**不是死代码**：这两个键已从注册表删除，这里是全仓唯一的读者，
    // 删掉它就等于把用户已经设过的排版偏好悄悄清零）。新键在场时永远优先，所以迁移
    // 不会覆盖之后的编辑；新键不在场时每次读都按旧值解析，两份文档因此不会各说各话。
    // 两个映射表与理由在 `PiTypographyProfile.migratedTypography` / `migratedFontSize`。
    // Mirror app.appearance.language from app-prefs.json into PiLocale on startup/refresh.
    val langWire = raw(PiLocale.SETTING_KEY)
    if (langWire != null) {
        val targetLang = AppLanguage.fromWire(langWire)
        if (targetLang != PiLocale.language) {
            PiLocale.setLanguage(getApplication<Application>(), targetLang)
        }
    }

    val typography = raw("app.appearance.typography")
        ?: PiTypographyProfile.migratedTypography(raw("app.appearance.messageDensity"))
    val fontSize = raw("app.appearance.fontSize")
        ?: PiTypographyProfile.migratedFontSize(raw("app.appearance.fontScaleDelta"))

    return UiPrefs(
        typographyProfile = PiTypographyProfile.forPreset(
            preset = typography,
            lineHeight = string("app.appearance.lineHeight", "normal"),
            fontSize = fontSize,
            proseFont = string("app.appearance.proseFont", PiTypographyProfile.FONT_SANS),
        ),
        showTimestamps = bool("app.appearance.showTimestamps", false),
        thinkingCollapsedByDefault = bool("app.appearance.thinkingCollapsedByDefault", true),
        expandToolsByDefault = bool("app.tools.expandByDefault", false),
        hideThinkingBlock = bool("hideThinkingBlock", false),
        // pi's own key, at the top level of the document, default false
        // (`core/settings-manager.ts:120`, `:965-967`). Gates the
        // compaction / branch-summary cost line (F18).
        showCacheMissNotices = bool("showCacheMissNotices", false),
        keepAlive = bool("app.runtime.keepAlive", true),
    )
}

/**
 * A setting the settings stack just wrote was read by the app itself.
 *
 * The settings screens write straight to the store (which is what keeps
 * pi's files authoritative), so app-side behaviour has to be told that the
 * document changed. Without this an appearance edit would only take effect
 * after a restart, i.e. the row would still look inert.
 *
 * Two things happen here: the app re-reads what *it* renders, and every pi
 * setting is forwarded live to sdk-host via `settings.set` / `settings.reload`
 * (and the live session accessors below) so changes take effect immediately. Everything else the
 * settings page writes is picked up by pi the next time it reads a settings
 * document — a new session, or a new process.
 *
 * The one exception to that last sentence is `app.runtime.proroot`: which binary
 * launches the guest is decided **per launch**, before pi exists, so no document
 * pi reads is involved — but the engine that is already running will not change
 * runtime on its own. That row used to require the user to exit the app
 * (「为什么还要退出软件重进呢？」), so the write is now the trigger for the
 * probe-then-restart sequence; see [applyRuntimeSwitch] and
 * `RuntimeSwitchAction` (the pure decision, executed by a bare-JVM harness).
 */
internal fun PiSessionViewModel.onSettingWrittenImpl(key: String) {
    if (key == PiLocale.SETTING_KEY) {
        val lang = AppLanguage.fromWire(settingsStore.readString(key))
        PiLocale.setLanguage(getApplication<Application>(), lang)
        refreshCommands()
        return
    }
    if (key == "theme") {
        refreshTheme()
        syncEngineSetting(key)
        return
    }
    // The four agent-level switches whose settings rows are a second editor of
    // a value the chat screen already changes live. Writing `settings.json`
    // alone would not reach the running process: pi loads its `SettingsManager`
    // once per process **and** once per session, so a file write is invisible
    // to the running session unless forwarded or reloaded via `settings.reload`.
    when (key) {
        "steeringMode" -> queueModeOf(settingsStore.readString(key))?.let(::setSteeringMode)
        "followUpMode" -> queueModeOf(settingsStore.readString(key))?.let(::setFollowUpMode)
        "compaction.enabled" -> settingsStore.readBoolean(key)?.let(::setAutoCompaction)
        "retry.enabled" -> settingsStore.readBoolean(key)?.let(::setAutoRetry)
        "cacheWarming" -> settingsStore.readString(key)?.let { mode ->
            val adv = session?.advanced ?: return@let
            viewModelScope.launch { runCatching { adv.setCacheMode(mode) } }
        }
    }
    if (key == "markdown.mermaid") {
        app.pi.ui.render.PiMermaidModeSource.invalidate()
    }
    if (
        key == "hideThinkingBlock" ||
        key == "markdown.mermaid" ||
        key.startsWith("app.appearance.") ||
        key.startsWith("app.tools.") ||
        key == "app.runtime.keepAlive" ||
        key == "showCacheMissNotices" ||
        key == "themes"
    ) {
        refreshPrefs()
        refreshTheme()
    }
    if (key == "enableSkillCommands") {
        refreshCommands()
    }
    if (
        key != "steeringMode" &&
        key != "followUpMode" &&
        key != "compaction.enabled" &&
        key != "retry.enabled" &&
        key != "cacheWarming" &&
        !key.startsWith("app.")
    ) {
        syncEngineSetting(key)
    }
}

/**
 * 将设置变更同步给运行中的 SDK `SettingsManager`：
 * 优先走宿主 `settings.set`（与 Pi TUI `SettingsSelectorComponent` 相同路径，带 SDK 校验、实时同步与 flush），
 * 键被删除或没有直接 setter 时走 `settings.reload` 让宿主从盘上重读最新文档。
 */
private fun PiSessionViewModel.syncEngineSetting(key: String? = null) {
    val c = session?.commands ?: return
    val sdkCall = key?.let { sdkSetterFor(it) }
    viewModelScope.launch {
        if (sdkCall != null) {
            val (method, params) = sdkCall
            val ok = runCatching { c.settingsSet(method, params) }.isSuccess
            if (ok) {
                invalidateSettingsCache()
                return@launch
            }
        }
        runCatching { c.settingsReload() }
    }
}

private fun PiSessionViewModel.sdkSetterFor(key: String): Pair<String, List<JsonElement>>? {
    val raw = settingsStore.read(key) ?: return null
    return when (key) {
        "theme" -> (raw as? JsonPrimitive)?.content?.let { "setTheme" to listOf(JsonPrimitive(it)) }
        "defaultThinkingLevel" -> (raw as? JsonPrimitive)?.content?.let { "setDefaultThinkingLevel" to listOf(JsonPrimitive(it)) }
        "treeFilterMode" -> (raw as? JsonPrimitive)?.content?.let { "setTreeFilterMode" to listOf(JsonPrimitive(it)) }
        "transport" -> (raw as? JsonPrimitive)?.content?.let { "setTransport" to listOf(JsonPrimitive(it)) }
        "httpIdleTimeoutMs" -> (raw as? JsonPrimitive)?.content?.toIntOrNull()?.let { "setHttpIdleTimeoutMs" to listOf(JsonPrimitive(it)) }
        "cacheWarming" -> (raw as? JsonPrimitive)?.content?.let { "setCacheWarmingMode" to listOf(JsonPrimitive(it)) }
        "showCacheMissNotices" -> (raw as? JsonPrimitive)?.content?.toBooleanStrictOrNull()?.let { "setShowCacheMissNotices" to listOf(JsonPrimitive(it)) }
        "hideThinkingBlock" -> (raw as? JsonPrimitive)?.content?.toBooleanStrictOrNull()?.let { "setHideThinkingBlock" to listOf(JsonPrimitive(it)) }
        "enableSkillCommands" -> (raw as? JsonPrimitive)?.content?.toBooleanStrictOrNull()?.let { "setEnableSkillCommands" to listOf(JsonPrimitive(it)) }
        "images.autoResize" -> (raw as? JsonPrimitive)?.content?.toBooleanStrictOrNull()?.let { "setImageAutoResize" to listOf(JsonPrimitive(it)) }
        "images.blockImages" -> (raw as? JsonPrimitive)?.content?.toBooleanStrictOrNull()?.let { "setBlockImages" to listOf(JsonPrimitive(it)) }
        "enableInstallTelemetry" -> (raw as? JsonPrimitive)?.content?.toBooleanStrictOrNull()?.let { "setEnableInstallTelemetry" to listOf(JsonPrimitive(it)) }
        "enableAnalytics" -> (raw as? JsonPrimitive)?.content?.toBooleanStrictOrNull()?.let { "setEnableAnalytics" to listOf(JsonPrimitive(it)) }
        "defaultProjectTrust" -> (raw as? JsonPrimitive)?.content?.let { "setDefaultProjectTrust" to listOf(JsonPrimitive(it)) }
        "shellPath" -> (raw as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { "setShellPath" to listOf(JsonPrimitive(it)) }
        "shellCommandPrefix" -> (raw as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { "setShellCommandPrefix" to listOf(JsonPrimitive(it)) }
        "enabledModels" -> (raw as? JsonArray)?.let { arr -> "setEnabledModels" to listOf(arr) }
        "markdown.mermaid" -> (raw as? JsonPrimitive)?.content?.let { "setMermaidRenderingMode" to listOf(JsonPrimitive(it)) }
        "npmCommand" -> (raw as? JsonArray)?.let { arr -> "setNpmCommand" to listOf(arr) }
        else -> null
    }
}

/** 当前实际运行中的容器运行时（proot / proroot / tawcroot），引擎未运行时为 null。 */
internal fun PiSessionViewModel.activeRuntimeEngineImpl(): app.pi.runtime.GuestEngine? = host.activeEngine

/** 当前引擎会话状态，未启动时为 null。 */
internal fun PiSessionViewModel.liveEngineStateImpl(): PiEngineSession.EngineState? = session?.state?.value

/**
 * 在「引擎与运行环境」页选中目标容器运行时：清探针缓存与失败计数 → 必要时跑探针 → 自动重启引擎。
 */
internal fun PiSessionViewModel.selectRuntimeEngineImpl(engine: app.pi.runtime.GuestEngine) {
    val context = getApplication<Application>()
    RuntimeSelection.of(context, host.paths()).setSelectedEngine(engine)
    applyRuntimeSwitch()
}

/** 无损修复内置运行载荷（重解包，保留用户安装的包与全部数据）。 */
internal fun PiSessionViewModel.repairRuntimeImpl() = boot(rebuild = false, forceRepair = true)

internal fun PiSessionViewModel.applyRuntimeSwitch() {
    val context = getApplication<Application>()
    val prefs = RuntimePreferences.get(context)
    val selection = RuntimeSelection.of(context, host.paths())
    val target = RuntimeSwitchAction.Target.valueOf(prefs.selectedEngine.name)
    val generation = ++runtimeSwitchGeneration
    // 用户可以在探针跑着的时候改选别的。上一次切换在挂起点上被取消；探针是阻塞调用，
    // 取消打断不了它，所以下面每一步都重新核对 `generation` 与此刻的选择——过期的结论
    // 绝不能再去重启引擎。
    runtimeSwitchJob?.cancel()
    runtimeSwitchJob = viewModelScope.launch {
        _runtimeSwitch.value = RuntimeSwitchAction.onWrite(target)
        if (target == RuntimeSwitchAction.Target.Proot) {
            notifyUser(tr("已选择 proot：正在重启引擎，把运行时换回 proot。", "Selected proot: restarting engine to switch runtime back to proot."))
            restartForRuntimeSwitch(generation, selection, prefs, target)
        } else {
            notifyUser(tr("已选择 ${target.name.lowercase()}：正在这台设备上测探针（最长约 60 秒），通过后会立刻重启引擎。", "Selected ${target.name.lowercase()}: probing device compatibility (up to ~60s); engine will restart automatically if passed."))
            probeThenSwitch(generation, selection, prefs, target)
        }
    }
}

/**
 * 现在跑一次所选运行时的门禁，再按结论决定要不要重启引擎。
 *
 * 门禁就在 `plan()` 后面：`plan()` 不启动任何进程，在选中的运行时上咨询或运行门禁
 * （`RuntimeSelection.gate`），revision / digest / 缓存 key 与真正那次启动一模一样，
 * 所以这里写下的结论 `status()` 读得到。
 */
private suspend fun PiSessionViewModel.probeThenSwitch(
    generation: Int,
    selection: RuntimeSelection,
    prefs: RuntimePreferences,
    target: RuntimeSwitchAction.Target,
) {
    val name = target.name.lowercase()
    val plan = withContext(Dispatchers.IO) {
        runCatching {
            selection.plan(
                // 永远不会被执行：`plan` 只拼 argv/env，签名要求一个 guest 命令而已。
                guestCommand = "true",
                cwd = "/",
                storage = android.os.Environment.getExternalStorageDirectory(),
            )
        }.getOrNull()
    }
    if (generation != runtimeSwitchGeneration) return
    if (plan == null) {
        _runtimeSwitch.value = RuntimeSwitchAction.Step.Idle
        notifyUser(tr("$name 探针没能跑完：仍然走 proot。稍后再选一次这个运行时可以重测。", "$name probe did not finish; staying on proot. Select this runtime again later to retry."), warning = true)
        return
    }
    val selectedNow = RuntimeSwitchAction.Target.valueOf(prefs.selectedEngine.name)
    val passed = plan.usingExperimental && plan.engine.name == target.name
    _runtimeSwitch.value = RuntimeSwitchAction.afterProbe(selectedNow, target, passed)
    if (!passed) {
        val rowSentence = withContext(Dispatchers.IO) {
            runCatching { selection.status() }.getOrNull()?.summary
        }
        notifyUser(
            tr(
                "没有切换到 $name。实际生效：${rowSentence ?: plan.summary}——可在「引擎与运行环境」页查看逐阶段探针记录。",
                "Did not switch to $name. Active runtime: ${rowSentence ?: plan.summary}. See per-stage probe log in Engine & Runtime.",
            ),
            warning = true,
        )
        return
    }
    restartForRuntimeSwitch(generation, selection, prefs, target)
}

/**
 * 重启引擎换运行时，再按结论定下选择的最终值。
 *
 * 走「重启引擎」同一条路（`restartEngine`）；能不能打断回合由目标决定
 * （`RuntimeSwitchAction.allowInterrupt`）：换回 proot 当场生效，换到实验性运行时不中断、
 * 被拒就落回 proot。最终值由 `RuntimeSwitchAction.settle` 定，这里只负责写下去。
 */
private suspend fun PiSessionViewModel.restartForRuntimeSwitch(
    generation: Int,
    selection: RuntimeSelection,
    prefs: RuntimePreferences,
    target: RuntimeSwitchAction.Target,
) {
    val name = target.name.lowercase()
    _runtimeSwitch.value = RuntimeSwitchAction.restartingTo(target)
    val outcome = restartEngine(
        reason = tr("在设置里把运行时选成了 $name", "Selected runtime $name in Settings"),
        allowInterrupt = RuntimeSwitchAction.allowInterrupt(target),
    )
    if (generation != runtimeSwitchGeneration) return
    val result = when (outcome) {
        is EngineRestartCoordinator.Outcome.Ok -> RuntimeSwitchAction.RestartResult.Ok
        is EngineRestartCoordinator.Outcome.Refused -> RuntimeSwitchAction.RestartResult.Refused
        is EngineRestartCoordinator.Outcome.Failed -> RuntimeSwitchAction.RestartResult.Failed
    }
    val finalTarget = RuntimeSwitchAction.settle(target, result)
    if (prefs.selectedEngine.name != finalTarget.name) {
        if (finalTarget == RuntimeSwitchAction.Target.Proot) {
            selection.setSelectedEngine(app.pi.runtime.GuestEngine.Proot)
        } else {
            // 只写偏好，不删探针结论：结论描述的是这台机器，不是这个选择。
            prefs.setSelectedEngine(app.pi.runtime.GuestEngine.valueOf(finalTarget.name))
        }
    }
    _runtimeSwitch.value = RuntimeSwitchAction.Step.Idle
    val message = when (outcome) {
        is EngineRestartCoordinator.Outcome.Ok -> tr("引擎已在 $name 上重启。", "Engine restarted on $name.")
        is EngineRestartCoordinator.Outcome.Refused -> outcome.message + if (target != RuntimeSwitchAction.Target.Proot) {
            tr("已退回 proot：等这一段跑完再选 $name。", " Reverted to proot: wait for this turn to finish before selecting $name.")
        } else {
            tr("已选 proot 并会保持；引擎这一次没有重启成功，仍在原来的运行时上跑，下次重启引擎就会走 proot。", " proot selected and kept; engine did not restart yet and will use proot next time.")
        }

        is EngineRestartCoordinator.Outcome.Failed -> outcome.message + if (target != RuntimeSwitchAction.Target.Proot) {
            tr("探针已经通过，下次启动引擎时会用 $name。", " Probe passed; $name will be used on next engine start.")
        } else {
            tr("已选 proot；下次启动引擎时会走 proot。", " proot selected; it will be used on next engine start.")
        }
    }
    notifyUser(message, warning = outcome !is EngineRestartCoordinator.Outcome.Ok)
}

/**
 * Drop the settings store's cached documents after a write that did not go
 * through it.
 *
 * `PiSettingsFileStore` caches each document after its first read
 * (`global`/`project`), and every other writer bypasses that cache: pi itself,
 * `pi install` (guest-side).
 * That last one invalidates a store instance of its *own*
 * (`PiConfigFiles.kt:579`), so the app's reader kept the pre-write values —
 * which is why this lives here, next to [settingsStore], rather than in the
 * settings UI, and why [attach] calls it as well: a restarted engine is
 * exactly the moment those files may have changed underneath us.
 */
internal fun PiSessionViewModel.invalidateSettingsCacheImpl() {
    (settingsStore as? PiSettingsFileStore)?.invalidate()
}
