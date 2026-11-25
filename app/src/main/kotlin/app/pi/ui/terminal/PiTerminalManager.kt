package app.pi.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.pi.i18n.tr
import app.pi.runtime.GuestEngine
import app.pi.runtime.ProrootLaunchHandle
import app.pi.runtime.PtyLauncher
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 进程级持久化多标签终端管理器：
 * - 退出终端页回到对话/项目时**绝不杀死**正在运行的 Shell（开发服务器、`npm run dev`、`git`、`vim`、`pi` TUI 全部后台存活）；
 * - 基于 Termux 原生 `TerminalSession` + `libtermux.so` 真内核 `forkpty` / `TIOCSWINSZ` / `SIGWINCH`，
 *   软键盘弹出、旋转屏幕、双指缩放时真实重排终端行列，彻底告别旧版 `script(1)` 冻结网格缩字；
 * - 磁盘准备与命令拼接在 `Dispatchers.IO` 完成，仅在主线程构造 `TerminalSession`（同时满足 `tawcroot` 的
 *   `PR_SET_PDEATHSIG` 绑定常驻主线程的约束，杜绝 60 秒后误杀）。
 */
object PiTerminalManager {

    private const val TAG = "PiTerminal"
    const val MAX_TABS = 6
    private const val TRANSCRIPT_ROWS = TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS

    data class Tab(
        val id: Int,
        val initialGuestCwd: String,
        val engine: GuestEngine,
        val session: TerminalSession,
        val prorootTmp: File? = null,
        val launchToken: String? = null,
        val prorootHandle: ProrootLaunchHandle? = null,
    ) {
        var title by mutableStateOf(shortDirLabel(initialGuestCwd))
        var running by mutableStateOf(true)
        var exitCode by mutableStateOf<Int?>(null)
    }

    private val nextId = AtomicInteger(1)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val tabs = mutableStateListOf<Tab>()
    var activeTabId by mutableStateOf<Int?>(null)
        private set
    var spawning by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var fontSizeSp by mutableFloatStateOf(TerminalPreferences.DEFAULT_FONT_SP)
        private set
    var keepScreenOn by mutableStateOf(false)
    var toastMessage by mutableStateOf<String?>(null)
    var toastEpoch by mutableIntStateOf(0)
        private set

    /** 当前活跃（运行中）的后台终端数，供对话顶栏显示状态点。 */
    val runningCount: Int
        get() = tabs.count { it.running }

    val activeTab: Tab?
        get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.lastOrNull()

    private var attachedViewRef: WeakReference<TerminalView>? = null
    private var lastAttachedSessionRef: WeakReference<TerminalSession>? = null
    private var lastPalette: TerminalPalette? = null
    private var appContext: Context? = null
    private var fontLoaded = false

    fun attachView(view: TerminalView?, palette: TerminalPalette) {
        if (view == null) {
            attachedViewRef = null
            lastAttachedSessionRef = null
            return
        }
        val currentSession = activeTab?.session
        val sameView = attachedViewRef?.get() === view
        val sameSession = lastAttachedSessionRef?.get() === currentSession
        val samePalette = lastPalette == palette
        if (sameView && sameSession && samePalette) return
        attachedViewRef = WeakReference(view)
        lastAttachedSessionRef = if (currentSession != null) WeakReference(currentSession) else null
        lastPalette = palette
        if (!samePalette || !sameSession) {
            palette.applyTo(currentSession?.emulator)
        }
        view.onScreenUpdated()
    }

    fun applyPalette(palette: TerminalPalette) {
        val changed = lastPalette != palette
        lastPalette = palette
        var applied = false
        for (tab in tabs) {
            val emu = tab.session.emulator ?: continue
            palette.applyTo(emu)
            applied = true
        }
        if (changed || applied) {
            attachedViewRef?.get()?.onScreenUpdated()
        }
    }

    fun ensureFontLoaded(context: Context) {
        val app = context.applicationContext ?: context
        appContext = app
        if (fontLoaded) return
        fontLoaded = true
        scope.launch {
            val prefs = withContext(Dispatchers.IO) {
                runCatching { TerminalPreferences.read(terminalSettingsStore(app)) }.getOrNull()
            }
            if (prefs != null) {
                fontSizeSp = prefs.fontSizeSp
            }
        }
    }

    fun setFontSize(context: Context, nextSp: Float, showToast: Boolean = false) {
        val clamped = nextSp.coerceIn(TerminalPreferences.MIN_FONT_SP, TerminalPreferences.MAX_FONT_SP)
        fontSizeSp = clamped
        if (showToast) {
            showToast("${clamped.toInt()}sp")
        }
        val app = context.applicationContext ?: context
        scope.launch(Dispatchers.IO) {
            runCatching { TerminalPreferences.saveFontSize(terminalSettingsStore(app), clamped) }
        }
    }

    fun showToast(message: String) {
        toastMessage = message
        toastEpoch++
    }

    fun clearToast() {
        toastMessage = null
    }

    /**
     * 进入终端页时的入口：
     * - 若指定了 `guestCwd`（如在项目文件页点击「在此打开终端」），自动在该目录新建一个标签（或聚焦已有标签）；
     * - 否则若已有存活标签则直接恢复上次所在的标签，零重启、零丢失现场；
     * - 若尚无标签则自动创建首个工作区终端。
     */
    fun openOrFocus(
        context: Context,
        guestCwd: String? = null,
        forceNewTab: Boolean = false,
    ) {
        val app = context.applicationContext ?: context
        appContext = app
        ensureFontLoaded(app)
        if (!forceNewTab && guestCwd == null && tabs.isNotEmpty()) {
            if (activeTabId == null || tabs.none { it.id == activeTabId }) {
                activeTabId = tabs.last().id
            }
            return
        }
        if (!forceNewTab && guestCwd != null) {
            val existing = tabs.firstOrNull { it.running && it.initialGuestCwd == guestCwd }
            if (existing != null) {
                activeTabId = existing.id
                return
            }
        }
        createTab(app, guestCwd)
    }

    fun selectTab(tabId: Int) {
        if (tabs.any { it.id == tabId }) {
            activeTabId = tabId
        }
    }

    fun createTab(context: Context, guestCwd: String? = null) {
        val app = context.applicationContext ?: context
        appContext = app
        if (tabs.size >= MAX_TABS) {
            showToast(tr("最多同时打开 $MAX_TABS 个终端标签", "Up to $MAX_TABS terminal tabs open"))
            return
        }
        if (spawning) return
        spawning = true
        lastError = null
        scope.launch {
            try {
                val plan = withContext(Dispatchers.IO) {
                    PtyLauncher.prepareDirectPty(context = app, guestCwdOverride = guestCwd)
                }
                val id = nextId.getAndIncrement()
                val client = TabSessionClient(app, id)
                val prorootHandle = if (plan.engine == GuestEngine.Proroot && plan.prorootTmp != null) {
                    runCatching { ProrootLaunchHandle.arm(plan.prorootTmp, plan.launchToken) }.getOrNull()
                } else {
                    null
                }
                val session = TerminalSession(
                    plan.executablePath,
                    plan.hostCwd,
                    plan.argv,
                    plan.env,
                    TRANSCRIPT_ROWS,
                    client,
                )
                session.mSessionName = shortDirLabel(plan.guestCwd)
                val tab = Tab(
                    id = id,
                    initialGuestCwd = plan.guestCwd,
                    engine = plan.engine,
                    session = session,
                    prorootTmp = plan.prorootTmp,
                    launchToken = plan.launchToken,
                    prorootHandle = prorootHandle,
                )
                tabs.add(tab)
                activeTabId = id
                lastPalette?.applyTo(session.emulator)
            } catch (t: Throwable) {
                Log.w(TAG, "创建终端标签失败", t)
                lastError = t.message ?: t::class.java.simpleName
            } finally {
                spawning = false
            }
        }
    }

    fun restartTab(context: Context, tabId: Int) {
        val app = context.applicationContext ?: context
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) {
            createTab(app)
            return
        }
        val old = tabs[index]
        terminateSession(old)
        tabs.removeAt(index)
        if (activeTabId == tabId) {
            activeTabId = tabs.getOrNull(index.coerceAtMost(tabs.lastIndex))?.id
        }
        createTab(app, guestCwd = old.initialGuestCwd)
    }

    fun closeTab(tabId: Int) {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return
        val tab = tabs.removeAt(index)
        terminateSession(tab)
        if (activeTabId == tabId) {
            activeTabId = tabs.getOrNull(index.coerceAtMost(tabs.lastIndex))?.id
        }
    }

    fun pasteToActive(context: Context) {
        val tab = activeTab ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = runCatching {
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        }.getOrDefault("")
        if (text.isEmpty()) return
        val emulator = tab.session.emulator
        if (emulator != null) {
            emulator.paste(text)
        } else {
            tab.session.write(text)
        }
    }

    fun copyVisibleScreen(context: Context) {
        val emulator = activeTab?.session?.emulator ?: return
        val text = runCatching {
            emulator.getSelectedText(0, 0, (emulator.mColumns - 1).coerceAtLeast(0), (emulator.mRows - 1).coerceAtLeast(0))
        }.getOrNull()?.trimEnd().orEmpty()
        if (text.isEmpty()) {
            showToast(tr("当前屏幕没有可复制的内容", "Nothing on screen to copy"))
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("pi terminal", text))
        showToast(tr("已复制当前屏幕内容", "Copied visible screen"))
    }

    fun clearActiveScreen() {
        val tab = activeTab ?: return
        tab.session.reset()
        tab.session.write("\u000c") // Ctrl+L 让 shell 重绘提示符
        attachedViewRef?.get()?.onScreenUpdated()
    }

    private fun terminateSession(tab: Tab) {
        val pid = tab.session.pid
        tab.running = false
        runCatching { tab.session.finishIfRunning() }
        // libtermux.so 的 createSubprocess 已调用 setsid()，所以 pid 同时是整个子进程组的 pgid；
        // 向 -pid 补发 SIGHUP/SIGKILL 确保 proot / proroot / tawcroot 及其子进程树干净退出、不留孤儿。
        if (pid > 0) {
            val handle = tab.prorootHandle
            Thread({
                runCatching { Os.kill(-pid, OsConstants.SIGHUP) }
                runCatching { Thread.sleep(120) }
                runCatching { Os.kill(-pid, OsConstants.SIGKILL) }
                runCatching { handle?.deleteConfig() }
            }, "pi-term-reap-$pid").apply {
                isDaemon = true
                start()
            }
        } else {
            tab.prorootHandle?.let { h ->
                Thread({ runCatching { h.deleteConfig() } }, "pi-term-cfg-clean").apply {
                    isDaemon = true
                    start()
                }
            }
        }
    }

    private fun shortDirLabel(guestCwd: String): String {
        val cleaned = guestCwd.trimEnd('/')
        if (cleaned.isEmpty() || cleaned == "/") return "bash · /"
        val leaf = cleaned.substringAfterLast('/')
        return if (leaf.isBlank()) "bash" else "bash · $leaf"
    }

    /**
     * Strips noisy `user@host:` OSC title prefixes (e.g. `root@localhost: /root/projects/demo`)
     * and shortens home/long paths so narrow tab pills never clip to `root@localho…`.
     */
    internal fun formatTabTitle(raw: String, fallback: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return fallback
        val stripped = USER_HOST_PREFIX.replaceFirst(trimmed, "").trim()
        val candidate = stripped.ifEmpty { trimmed }
        val shortened = if (candidate.startsWith("/")) {
            app.pi.runtime.GuestWorkspacePath.shortenForDisplay(candidate)
        } else {
            candidate
        }
        val clean = shortened.trimEnd('/').ifEmpty { "/" }
        if (clean.length <= 20) return clean
        val segments = clean.split('/').filter { it.isNotEmpty() }
        if (segments.size >= 2) {
            val leaf = segments.last()
            val parent = segments[segments.lastIndex - 1]
            val compact = if (clean.startsWith("~/") && segments.size == 2) "~/$leaf" else "$parent/$leaf"
            return if (compact.length <= 22) compact else leaf
        }
        return clean
    }

    private val USER_HOST_PREFIX = Regex("^[A-Za-z0-9._-]+@[A-Za-z0-9._-]+:\s*")

    private class TabSessionClient(
        private val app: Context,
        private val tabId: Int,
    ) : TerminalSessionClient {

        private fun tabOrNull(): Tab? = tabs.firstOrNull { it.id == tabId }

        override fun onTextChanged(changedSession: TerminalSession) {
            if (activeTabId == tabId) {
                attachedViewRef?.get()?.onScreenUpdated()
            }
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            val raw = changedSession.title?.trim().orEmpty()
            if (raw.isNotEmpty()) {
                mainHandler.post {
                    val tab = tabOrNull() ?: return@post
                    tab.title = formatTabTitle(raw, tab.title)
                }
            }
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            val code = finishedSession.exitStatus
            mainHandler.post {
                val tab = tabOrNull()
                if (tab != null) {
                    tab.running = false
                    tab.exitCode = code
                    tab.prorootHandle?.let { h ->
                        Thread({ runCatching { h.deleteConfig() } }, "pi-term-exit-clean").apply {
                            isDaemon = true
                            start()
                        }
                    }
                }
                if (activeTabId == tabId) {
                    attachedViewRef?.get()?.onScreenUpdated()
                }
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (text.isNullOrEmpty()) return
            mainHandler.post {
                val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("pi terminal", text))
                showToast(tr("已复制到剪贴板", "Copied to clipboard"))
            }
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            mainHandler.post { pasteToActive(app) }
        }

        override fun onBell(session: TerminalSession) {
            // 静默无扰动（不震动骚扰）
        }

        override fun onColorsChanged(session: TerminalSession) {
            mainHandler.post {
                lastPalette?.applyTo(session.emulator)
                if (activeTabId == tabId) {
                    attachedViewRef?.get()?.onScreenUpdated()
                }
            }
        }

        override fun onTerminalCursorStateChange(state: Boolean) {
            if (activeTabId == tabId) {
                attachedViewRef?.get()?.setTerminalCursorBlinkerState(state, true)
            }
        }

        override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

        override fun logError(tag: String?, message: String?) {
            Log.e(tag ?: TAG, message ?: "")
        }

        override fun logWarn(tag: String?, message: String?) {
            Log.w(tag ?: TAG, message ?: "")
        }

        override fun logInfo(tag: String?, message: String?) {
            Log.i(tag ?: TAG, message ?: "")
        }

        override fun logDebug(tag: String?, message: String?) {
            Log.d(tag ?: TAG, message ?: "")
        }

        override fun logVerbose(tag: String?, message: String?) = Unit

        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(tag ?: TAG, message ?: "", e)
        }

        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.e(tag ?: TAG, "terminal exception", e)
        }
    }
}
