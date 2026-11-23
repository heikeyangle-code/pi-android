package app.pi.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.viewModelScope
import app.pi.engine.PiEngineHost
import app.pi.engine.PiEngineSession
import app.pi.host.HostCommandException
import app.pi.host.HostEngineApi
import app.pi.i18n.tr
import app.pi.protocol.Notice
import app.pi.protocol.PiResponses
import app.pi.protocol.UserMessage
import app.pi.runtime.GuestWorkspacePath
import app.pi.runtime.WorkspaceStore
import app.pi.session.SessionExportNaming
import app.pi.session.SessionFileReader
import app.pi.session.SessionImport
import app.pi.session.SessionLibrary
import app.pi.session.SessionSummary
import app.pi.settings.readBoolean
import app.pi.ui.PiSessionViewModel.CwdMissingAction
import app.pi.ui.PiSessionViewModel.CwdMissingPrompt
import app.pi.ui.PiSessionViewModel.SessionHints
import app.pi.ui.PiSessionViewModel.UiState
import app.pi.ui.chat.BranchSummaryChoice
import app.pi.ui.chat.NavigateOutcome
import app.pi.ui.chat.activeBranch
import app.pi.ui.chat.navigateOutcome
import app.pi.ui.chat.wantsSummary
import app.pi.ui.extension.ComposerFill
import app.pi.ui.settings.PiSettingsCatalog
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.flow.update

/**
 * 新建会话（宿主 `session.new` → SDK `newSession`）。官方 `/new` 不带父会话参数，这里也不带。
 *
 * pi answers `cancelled` when an extension vetoes it.
 */
internal fun PiSessionViewModel.newSessionImpl() {
    // **引擎还没 attach 时，这一次点击排到 attach 之后重放，而不是交给 `call` 的未就绪分支。**
    //
    // 用户原话：「我新建对话，点第一下新建不了，再点第二下。」根因就是这个窗口：`Boot.Idle`
    // 那一两秒（D31：对话页在这一段是**可用**的，接管页只画 `Boot.Working`）里 `api == null`，
    // 而 `call` 在 `api == null` 时只推一条通知就 `return` —— 第一次点击**没有执行**，第二次
    // （引擎已 attach）才成。`send` / `sendFollowUp` / `runPromptCommand` 三条走的是
    // [parkUntilAttached]（D31 为同一个窗口加的），这里沿用同一套机制，不新造：
    // 闭包重入本函数，所以重放跑的是**同一条**路径，而不是它的副本。
    //
    // 2026-09-23 补：当时 [parkUntilAttached] 判的是 `session` 而 `call` 判的是 `api`，
    // 两个字段在引擎死亡那条路上是**反序**置空的，所以窗口只是变窄、没有关掉（用户的新读数
    // 是「点 1 次、2 次、3 次」）。现在两者判同一个字段，见 [parkUntilAttached] 的 KDoc。
    if (parkUntilAttached { newSession() }) {
        // **排队也要看得见。** 会话列表那枚「＋ 新建会话」点完就关掉覆盖层（`onOpenChat`），
        // 用户落在对话页上；没有这句话，他看到的还是「点了没反应」—— 那正是这次要消灭的形状。
        pushNotice(tr("引擎还在启动：新建会话已经排队，启动完成后会立刻执行。", "Engine is starting: new session queued and will open once ready."), Notice.Tone.Info)
        requestNav(NavRequest.Chat)
        return
    }
    call(tr("新建会话", "New session")) { api ->
        val result = api.newSession()
        if (result.cancelled) {
            pushNotice(tr("扩展取消了新建会话", "An extension cancelled creating a new session"), Notice.Tone.Warning)
            return@call
        }
        afterSessionReplaced(brandNew = true)
        requestNav(NavRequest.Chat)
    }
}

/**
 * Delete one session file from the on-disk index.
 *
 * pi's picker offers delete behind a confirmation (`docs/sessions.md:48`) and
 * its store has no delete command either — the file *is* the session — so this
 * removes the JSONL the same way the desktop UI does. The screen refuses the
 * active session before ever calling this: pi is appending to that file, and
 * unlinking it under the engine would keep it writing to a removed inode.
 *
 * 删除走宿主的 `session.delete`（[SessionLibrary.delete]）：宿主只认会话目录之内的路径、
 * 拒绝删当前会话；[SessionSummary] 是数据，数据不能指名任意路径去 unlink。
 */
internal fun PiSessionViewModel.deleteSessionImpl(summary: SessionSummary) {
    viewModelScope.launch {
        val outcome = withContext(Dispatchers.IO) { sessionLibrary.delete(summary.file) }
        if (outcome.ok) {
            // 官方两句提示：Session moved to trash / Session deleted（trash 命令可用与否决定）。
            pushNotice(
                (if (outcome.method == "trash") tr("已移到回收站：", "Moved to trash: ") else tr("已删除：", "Deleted: ")) + summary.displayName,
                Notice.Tone.Info,
            )
            // 先把这一行从手上的列表拿掉（官方 `onDeleteSession` 同样先过滤再重读），再让宿主重读。
            _sessionList.value = _sessionList.value.copy(
                rows = _sessionList.value.rows.filter { it.file != summary.file },
                total = (_sessionList.value.total - 1).coerceAtLeast(0),
            )
            refreshSessions()
        } else {
            pushNotice(tr("删除失败：${outcome.error ?: summary.file.name}", "Delete failed: ${outcome.error ?: summary.file.name}"), Notice.Tone.Warning)
        }
    }
}

/**
 * `switch_session` with a path from the on-disk index.
 *
 * The path has to be the one **pi** can open: pi resolves it inside the guest,
 * and the app's `agentDir` is bind-mounted there verbatim
 * (`PiEngineHost.kt:123`, `guestAgentDir`), so host-to-guest is a prefix
 * substitution. Switching through the command rather than by editing files
 * under pi is what lets a `session_before_switch` extension veto it
 * (audit §6.9).
 */
internal fun PiSessionViewModel.switchSessionImpl(summary: SessionSummary) {
    val path = guestSessionPath(summary.file)
    if (path == null) {
        fail(tr("找不到会话文件：${summary.file.name}", "Session file not found: ${summary.file.name}"))
        return
    }
    val generation = ++sessionOpenGeneration
    call(tr("切换会话", "Switch session")) { api ->
        SessionHints.file = summary.file
        val result = try {
            api.switchSession(path)
        } catch (error: HostCommandException) {
            if (error.code == "cwd_missing") {
                SessionHints.file = null
                val issue = ((error.details as? JsonObject)?.get("issue") as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content
                _cwdMissing.value = CwdMissingPrompt(
                    reason = error.reason,
                    issue = issue,
                    action = CwdMissingAction.Switch(summary, path),
                )
                return@call
            }
            throw error
        }
        if (result.cancelled) {
            SessionHints.file = null
            pushNotice(tr("扩展取消了切换会话", "An extension cancelled switching sessions"), Notice.Tone.Warning)
            return@call
        }
        afterSessionReplaced()
        requestNav(NavRequest.Chat)
        viewModelScope.launch { adoptSessionWorkspace(summary, generation) }
    }
}

/** 从任意会话文件在当前工作区创建分支并切换进去（SDK `SessionManager.forkFrom`）。 */
internal fun PiSessionViewModel.forkSessionToCurrentWorkspaceImpl(summary: SessionSummary) {
    val path = guestSessionPath(summary.file)
    if (path == null) {
        fail(tr("找不到会话文件：${summary.file.name}", "Session file not found: ${summary.file.name}"))
        return
    }
    call(tr("分支会话", "Branch session")) { api ->
        val result = api.forkFrom(path, guestWorkspace())
        if (result.cancelled) {
            pushNotice(tr("扩展取消了分支会话", "An extension cancelled branching the session"), Notice.Tone.Warning)
            return@call
        }
        SessionHints.file = hostSessionFile(result.sessionFile)
        afterSessionReplaced()
        requestNav(NavRequest.Chat)
        pushNotice(tr("已分支到当前工作区", "Branched into current workspace"), Notice.Tone.Info)
    }
}

internal fun PiSessionViewModel.resolveCwdMissingContinueImpl() {
    val prompt = _cwdMissing.value ?: return
    _cwdMissing.value = null
    when (val action = prompt.action) {
        is CwdMissingAction.Switch -> {
            val generation = ++sessionOpenGeneration
            call(tr("继续会话", "Resume session")) { api ->
                SessionHints.file = action.summary.file
                val result = api.switchSession(action.guestPath, cwd = guestWorkspace())
                if (result.cancelled) {
                    SessionHints.file = null
                    pushNotice(tr("扩展取消了切换会话", "An extension cancelled switching sessions"), Notice.Tone.Warning)
                    return@call
                }
                afterSessionReplaced()
                requestNav(NavRequest.Chat)
                viewModelScope.launch { adoptSessionWorkspace(action.summary, generation) }
            }
        }
        is CwdMissingAction.Import -> {
            val api = this.api ?: run {
                runCatching { action.stagedFile.delete() }
                fail(tr("引擎还没有就绪，请重试导入。", "Engine is not ready yet; retry import."))
                return
            }
            viewModelScope.launch {
                val token = claimBusy(tr("导入会话", "Importing session"))
                try {
                    val result = api.importSessionFile(action.guestPath, cwd = guestWorkspace())
                    if (result.cancelled) {
                        pushNotice(tr("扩展取消了导入会话", "An extension cancelled importing the session"), Notice.Tone.Warning)
                    } else {
                        afterSessionReplaced()
                        requestNav(NavRequest.Chat)
                        pushNotice(tr("已导入会话：${action.displayName}", "Imported session: ${action.displayName}"), Notice.Tone.Info)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    fail(SessionImport.failureSentence((error as? HostCommandException)?.reason ?: error.message))
                } finally {
                    runCatching { action.stagedFile.delete() }
                    releaseBusy(token)
                }
            }
        }
    }
}

internal fun PiSessionViewModel.resolveCwdMissingForkImpl() {
    val prompt = _cwdMissing.value ?: return
    _cwdMissing.value = null
    when (val action = prompt.action) {
        is CwdMissingAction.Switch -> forkSessionToCurrentWorkspace(action.summary)
        is CwdMissingAction.Import -> {
            val api = this.api ?: run {
                runCatching { action.stagedFile.delete() }
                fail(tr("引擎还没有就绪，请重试导入。", "Engine is not ready yet; retry import."))
                return
            }
            viewModelScope.launch {
                val token = claimBusy(tr("分支会话", "Branching session"))
                try {
                    val result = api.forkFrom(action.guestPath, guestWorkspace())
                    if (result.cancelled) {
                        pushNotice(tr("扩展取消了分支会话", "An extension cancelled branching the session"), Notice.Tone.Warning)
                    } else {
                        SessionHints.file = hostSessionFile(result.sessionFile)
                        afterSessionReplaced()
                        requestNav(NavRequest.Chat)
                        pushNotice(tr("已从导入文件分支到当前工作区", "Branched from imported file into current workspace"), Notice.Tone.Info)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    fail(error.message ?: tr("分支会话失败", "Failed to branch session"))
                } finally {
                    runCatching { action.stagedFile.delete() }
                    releaseBusy(token)
                }
            }
        }
    }
}

internal fun PiSessionViewModel.dismissCwdMissingImpl() {
    val prompt = _cwdMissing.value ?: return
    _cwdMissing.value = null
    if (prompt.action is CwdMissingAction.Import) {
        runCatching { prompt.action.stagedFile.delete() }
    }
}

/**
 * 打开一段**别的工作区**的会话之后，把 App 的当前工作区改到它所属的那个。
 *
 * 为什么需要：「全部」范围（官方 `listAll`）列出**所有** cwd 的会话，所以用户可以在 B 的引擎里点开一段
 * cwd=A 的会话。pi 自己会切（见调用点），App 不跟就会说两套话。
 *
 * 为什么**不重启引擎**：pi 已经按会话的 cwd 重建了运行时，重启没有任何信息可补，
 * 只会多停一次进程、多丢一次上下文。这里只做三件便宜事：
 *  1. 把选择写回设置（`WorkspaceStore.setCurrent`，一次原子小文件写）；
 *  2. 把 `GuestWorkspacePath.RELATIVE` 指向新工作区（`setCurrent` 内部顺带）；
 *  3. 让设置/主题缓存失效、把工作区状态发布给界面（芯片、`@`、包命令随之而变）。
 *
 * 会话列表**不重读**：「全部」范围不随当前工作区变；「当前文件夹」范围由宿主按它自己的 cwd
 * 判断，宿主的 cwd 已由 pi 切好。
 *
 * 映射不出来（用户自己在会话里 `cd` 出去的目录、别的安装留下的会话）就**什么都不做** ——
 * 宁可保持现状，也不要猜一个工作区。
 */
private suspend fun PiSessionViewModel.adoptSessionWorkspace(summary: SessionSummary, generation: Long) {
    val app = getApplication<Application>()
    var name = WorkspaceStore.nameForGuestCwd(summary.cwd)
    if (name != null && name == _state.value.workspace.name) return
    if (generation != sessionOpenGeneration) return
    val entry = withContext(Dispatchers.IO) {
        val existing = name?.let { WorkspaceStore.existing(app, it) }
        if (existing != null) {
            existing
        } else {
            val hostDir = WorkspaceStore.hostDirForGuestCwd(app, summary.cwd)
            if (hostDir != null) {
                (WorkspaceStore.registerDirectory(app, hostDir) as? WorkspaceStore.Create.Ok)?.entry
            } else {
                null
            }
        }
    } ?: return
    name = entry.name
    if (generation != sessionOpenGeneration) return
    if (_state.value.workspace.name == name) return
    val before = _state.value.workspace.name
    val saved = withContext(Dispatchers.IO) { WorkspaceStore.setCurrent(app, name) }
    if (generation != sessionOpenGeneration) {
        withContext(Dispatchers.IO) { WorkspaceStore.setCurrent(app, before) }
        return
    }
    rebuildWorkspaceScopedCaches()
    publishWorkspaceState(name, note = null, bumped = true)
    refreshPrefs()
    refreshTheme()
    if (!saved) {
        pushNotice(
            tr(
                "这段对话属于项目「${entry.displayName}」，但设置文件不可写；重启应用后会回到上一个项目。",
                "This conversation belongs to project \"${entry.displayName}\", but the switch could not be saved (settings file not writable); restarting will return to the previous project.",
            ),
            Notice.Tone.Warning,
        )
    }
}

/**
 * `/import <path.jsonl>` — adopt a session file the user picked, then continue
 * in it.
 *
 * pi's own `/import` is a TUI command (`interactive-mode.ts:6107-6119`) and RPC
 * has no import command, but it does not need one: `switch_session` takes a
 * session **file path** (`rpc-types.ts:61`) and its handler runs the same
 * pipeline (`rpc-mode.ts:605-611` → `agent-session-runtime.ts:197-224`):
 * `emitBeforeSwitch` (an extension can veto), `SessionManager.open` (the header
 * is validated, `session-manager.ts:905-908`) and `assertSessionCwdExists`
 * (the recorded cwd must exist, `session-cwd.ts:54-58`). So this method does
 * only what pi's `importFromJsonl` does *before* that call
 * (`agent-session-runtime.ts:361-405`): check the file, choose the destination
 * name, copy it into the session directory.
 *
 * **One deliberate deviation**: pi copies first and validates afterwards, so a
 * file it rejects stays in the session directory; this app validates the head
 * before copying, so a wrong pick leaves nothing behind. The observable
 * difference is only that (the file is not a session either way).
 *
 * The cwd case is the one this app cannot fully reproduce: pi's TUI offers
 * "continue in current cwd" (`interactive-mode.ts:2545` →
 * `formatMissingSessionCwdPrompt`) and re-imports with a `cwdOverride`, while
 * `switch_session` has no such parameter (`rpc-types.ts:61`). That path fails
 * with a sentence that says so and what to do instead — never silently.
 *
 * All work that touches the picked document or the session directory happens on
 * [Dispatchers.IO], because the source can be a cloud provider and the session
 * file can be hundreds of megabytes.
 */
internal fun PiSessionViewModel.importSessionImpl(source: Uri) {
    val api = this.api ?: run {
        fail(tr("引擎还没有就绪，等它启动完成后再导入。", "Engine is not ready yet; wait for startup to finish before importing."))
        return
    }
    viewModelScope.launch {
        val importToken = claimBusy(tr("导入会话", "Importing session"))
        var stagedFile: File? = null
        var deferCleanup = false
        try {
            when (val prepared = withContext(Dispatchers.IO) { prepareImport(source) }) {
                is ImportPrep.Rejected -> fail(prepared.sentence)

                is ImportPrep.Ready -> {
                    stagedFile = prepared.stagedFile
                    val result = try {
                        api.importSessionFile(prepared.guestPath)
                    } catch (error: HostCommandException) {
                        if (error.code == "cwd_missing") {
                            val issue = ((error.details as? JsonObject)?.get("issue") as? JsonPrimitive)
                                ?.takeIf { it.isString }?.content
                            _cwdMissing.value = CwdMissingPrompt(
                                reason = error.reason,
                                issue = issue,
                                action = CwdMissingAction.Import(
                                    stagedFile = prepared.stagedFile,
                                    guestPath = prepared.guestPath,
                                    displayName = prepared.displayName,
                                ),
                            )
                            deferCleanup = true
                            return@launch
                        }
                        throw error
                    }
                    if (result.cancelled) {
                        pushNotice(tr("扩展取消了导入会话", "An extension cancelled importing the session"), Notice.Tone.Warning)
                    } else {
                        afterSessionReplaced()
                        requestNav(NavRequest.Chat)
                        pushNotice(tr("已导入会话：${prepared.displayName}", "Imported session: ${prepared.displayName}"), Notice.Tone.Info)
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            fail(SessionImport.failureSentence((error as? HostCommandException)?.reason ?: error.message))
        } finally {
            if (!deferCleanup) {
                runCatching { stagedFile?.delete() }
            }
            releaseBusy(importToken)
        }
    }
}

/** What [importSession] decided before it hands the staged path to pi's `session.import`. */
private sealed interface ImportPrep {
    data class Ready(val stagedFile: File, val guestPath: String, val displayName: String) : ImportPrep
    data class Rejected(val sentence: String) : ImportPrep
}

/**
 * Read the picked document's head, decide whether it is a session, and stage it into
 * `<agentDir>/tmp-import/` so pi's official `importFromJsonl` copies and opens it.
 */
private fun PiSessionViewModel.prepareImport(source: Uri): ImportPrep {
    val resolver = getApplication<Application>().contentResolver
    val tmpDir = File(host.paths().agentDir, "tmp-import")
    val sourceName = displayNameOf(source)

    if (sourceName != null && !sourceName.endsWith(SessionImport.SUFFIX, ignoreCase = true)) {
        return ImportPrep.Rejected(SessionImport.wrongSuffixSentence(sourceName))
    }
    val destinationSeed = sourceName
        ?: "session-import-${System.currentTimeMillis()}${SessionImport.SUFFIX}"

    val head = runCatching {
        resolver.openInputStream(source)?.use { input ->
            String(readBoundedBytes(input, SessionImport.HEAD_SCAN_CHARS), Charsets.UTF_8)
        }
    }.getOrNull() ?: return ImportPrep.Rejected(SessionImport.unreadableSentence())
    if (SessionImport.verdictOf(head) is SessionImport.Verdict.NotASession) {
        return ImportPrep.Rejected(SessionImport.failureSentence("not a valid"))
    }

    tmpDir.mkdirs()
    val keepPath = (_cwdMissing.value?.action as? CwdMissingAction.Import)?.stagedFile?.absolutePath
    tmpDir.listFiles()?.forEach { old ->
        if (old.isFile && old.absolutePath != keepPath) runCatching { old.delete() }
    }
    val stagedName = "${sessionOpSeq.incrementAndGet()}-${destinationSeed.substringAfterLast('/')}"
    val stagedFile = File(tmpDir, stagedName)

    val copied = runCatching {
        val input = resolver.openInputStream(source) ?: return@runCatching false
        input.use { from ->
            stagedFile.outputStream().use { to -> from.copyTo(to) }
        }
        true
    }.getOrDefault(false)
    if (!copied) {
        runCatching { stagedFile.delete() }
        return ImportPrep.Rejected(SessionImport.unreadableSentence())
    }

    val guestPath = "${host.guestAgentDir}/tmp-import/$stagedName"
    return ImportPrep.Ready(stagedFile = stagedFile, guestPath = guestPath, displayName = destinationSeed)
}

/**
 * The picked document's name, or null when the provider does not publish one.
 *
 * `OpenableColumns.DISPLAY_NAME` is the only name a content URI has —
 * `Uri.lastPathSegment` is a provider-internal id (`…/document/1234`) and using
 * it as a file name was the reason a first attempt at this rejected every pick
 * that came from the Downloads provider.
 */
private fun PiSessionViewModel.displayNameOf(source: Uri): String? {
    val resolver = getApplication<Application>().contentResolver
    val published = runCatching {
        resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    val cleaned = published
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    return cleaned
}

/**
 * Read at most [limit] bytes off [input].
 *
 * Bounded because this reads a file the app did not choose the size of: a
 * `.jsonl` whose first line is a multi-megabyte tool result or an inline image
 * would otherwise be allocated in full before any budget could be consulted —
 * the same defect `docs/hang-and-crash-review.md` §A4 fixed for the session
 * list. The bytes are decoded as UTF-8 afterwards; a multi-byte character split
 * by the limit can only appear at the very end, by which point the header line
 * has been seen.
 */
private fun PiSessionViewModel.readBoundedBytes(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
    val buffer = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/**
 * `fork` from an entry id pi itself handed us: the fork picker
 * ([refreshForkMessages] → `get_fork_messages`) and the session tree
 * (`get_tree` node ids). Both are already pi's entry ids, so nothing is
 * resolved here.
 */
internal fun PiSessionViewModel.forkFromImpl(entryId: String) = forkAt(entryId, row = null)

/**
 * `fork` for a row the user long-pressed in the transcript.
 *
 * **Why this needs its own resolver.** The block-action menu only knows the
 * row's *transcript key* (`UserMessage.key`), and that key is pi's entry id
 * only on the replay path: `TranscriptReducer.seedFromHistory` keys a
 * projected block off the entry it came from (`protocol/.../Transcript.kt`),
 * but the live path that draws the bubble the instant the user hits send
 * mints a synthetic one — `nextKey("user")` = `user-<epochMillis>-<n>`
 * (`Transcript.kt:982`, `:1010`). pi's `fork` looks the id up in the session
 * manager and throws `Invalid entry ID for forking` for anything it does not
 * know (`core/agent-session-runtime.ts:274-284`), so long-pressing a message
 * sent in *this* app run used to fail while long-pressing a message that came
 * back from `get_entries` worked. That is the whole of "有时候报错，有时候不报错":
 * it is deterministic, keyed on which of the two paths produced the row.
 *
 * The fix does not need a new wire field. pi already publishes the complete,
 * authoritative list of forkable points ([HostEngineApi.getForkMessages] →
 * `session.getUserMessagesForForking()`, `core/agent-session.ts:3309-3327`),
 * so the row is resolved against that list — see [resolveForkPoint].
 *
 * @param key the row's transcript key, used as an exact entry id when it is one.
 * @param ordinal the row's 0-based index among the transcript's user-message
 *   rows, or -1 when the caller cannot supply it.
 * @param text the row's own text, the last handle on a live (synthetic) row.
 */
internal fun PiSessionViewModel.forkFromMessageImpl(key: String, ordinal: Int, text: String) =
    forkAt(key, row = ForkRow(key = key, ordinal = ordinal, text = text))

/**
 * A long-pressed transcript row, as the three things [resolveForkPoint] can use.
 *
 * `ordinal` is the same order pi lists the messages in, because
 * `getUserMessagesForForking` walks `getEntries()` in file order and the
 * transcript's user rows are projected from those same entries in the same
 * order — the one exception is a `/skill:` prompt with no trailing user text,
 * which projects no user row at all (`Transcript.kt:2353-2368`). That is why
 * the ordinal is only trusted together with the text.
 */
private data class ForkRow(val key: String, val ordinal: Int, val text: String)

/**
 * The one fork path: resolve (when the caller had no id), fork, rebuild.
 *
 * pi's `/fork` forks **before** the chosen message — `position: "before"` is
 * the default and sets the new leaf to that entry's `parentId`
 * (`agent-session-runtime.ts:264-287`) — and returns the message's text as
 * `selectedText` (`rpc-mode.ts:613-618`). pi's own picker then puts that text
 * back in the editor so it can be edited and resent
 * (`interactive-mode.ts:5157-5165`), which is what the block menu's label
 * 编辑并从此分叉 promises and what this now does through the same composer
 * fill channel `set_editor_text` uses.
 */
private fun PiSessionViewModel.forkAt(entryId: String, row: ForkRow?) {
    call(tr("创建分叉", "Fork session"), errorText = ::forkFailureText) { api ->
        val target = if (row == null) {
            entryId
        } else {
            val messages = api.getForkMessages()
            resolveForkPoint(row, messages) ?: run {
                fail(forkPointNotFoundText(messages.isEmpty()))
                return@call
            }
        }
        val result = api.fork(target)
        if (result.cancelled) {
            pushNotice(tr("扩展取消了分叉", "An extension cancelled forking"), Notice.Tone.Warning)
            return@call
        }
        afterSessionReplaced(brandNew = true)
        result.text?.takeIf { it.isNotBlank() }?.let(::fillComposer)
        // pi 的 TUI 在分叉成功后会打一行 `Forked to new session`
        // （`modes/interactive/interactive-mode.ts:5457`），而本屏的 `cloneSession`
        // 早就有对应的 `Cloned to new session`（`:5487`、这里 `:5264` 的「已复制为新会话」）。
        // 少了它，用户看到的是整段对话被换掉、没有任何一句解释 —— 那正是用户说的
        // 「一点也没有安全感」。这条提示与「已复制为新会话」同一个出口、同一档语气。
        pushNotice(tr("已分叉为新会话", "Forked into a new session"), Notice.Tone.Info)
        requestNav(NavRequest.Chat)
    }
}

/**
 * Which forkable message a long-pressed transcript row means, or null.
 *
 * Three handles, tried in decreasing strength. The first is exact; the other
 * two exist only because pi gives the app no way to learn the entry id of a
 * message it has just sent — `message_end` carries the message but no entry id
 * (`core/agent-session.ts:1524`, `:791-793`) and `entry_appended` is emitted
 * for extension `appendEntry` calls alone (`agent-session.ts:2593-2599`).
 *
 *  1. the transcript key **is** an entry id (the replay path) — exact;
 *  2. the row's position among the user rows, accepted only when pi's text for
 *     that position is the row's text, because a skipped row (`/skill:` with no
 *     trailing text) shifts every later ordinal by one;
 *  3. the row's text, preferring the **last** match: for a live row the last
 *     occurrence is the one just sent, and for a duplicated text either
 *     occurrence is a legal fork point, so the nearest to the leaf is the
 *     useful one.
 */
private fun PiSessionViewModel.resolveForkPoint(row: ForkRow, messages: List<PiResponses.ForkMessage>): String? {
    messages.firstOrNull { it.entryId == row.key }?.let { return it.entryId }
    if (row.ordinal >= 0) {
        messages.getOrNull(row.ordinal)
            ?.takeIf { it.text == row.text }
            ?.let { return it.entryId }
    }
    return messages.lastOrNull { it.text == row.text }?.entryId
}

/** Nothing to fork from at all is a different answer from "not this row". */
private fun PiSessionViewModel.forkPointNotFoundText(noForkableMessages: Boolean): String = if (noForkableMessages) {
    tr(
        "这个会话还没有可分叉的用户消息（pi：No messages to fork from）。先发一条消息，等 pi 把它写进会话文件后再分叉。",
        "No forkable user messages yet (pi: No messages to fork from). Send a message and wait for pi to persist it first.",
    )
} else {
    tr(
        "这条消息不在 pi 的可分叉点里。pi 只允许从已经写进会话文件的用户消息分叉（getUserMessagesForForking），刚发出去还没落盘的一条就是这种情况：等这一轮结束，或从 ⋮ 菜单的「从历史消息分叉」里选一条。",
        "This message is not a valid pi fork point yet (getUserMessagesForForking). Wait for the turn to finish or pick one from \"Fork from message\" in the menu.",
    )
}

/**
 * pi's failures on the fork path, said with the next step attached.
 *
 * The reasons are pi's own, verbatim and quoted, because the app cannot
 * translate a fact it does not own — but each one has a known cause and a
 * known way out (`core/agent-session-runtime.ts:274-324`), and leaving that
 * out is what made the old failure read as a bare "engine said no".
 */
private fun PiSessionViewModel.forkFailureText(error: Throwable): String {
    val reason = error.message?.takeIf { it.isNotBlank() } ?: tr("原因未知", "Unknown reason")
    val next = when {
        reason.contains("Invalid entry ID for forking") ->
            tr("请从 ⋮ 菜单的「从历史消息分叉」里选一条消息；那条列表就是 pi 认可的全部分叉点。", "Pick a message from \"Fork from message\" in the menu.")

        reason.contains("has not been saved yet") ->
            tr("pi 还没有把这个会话写到磁盘上的会话文件。先发一条消息、等模型回复一句，再分叉。", "Session has not been persisted to disk yet. Send a message and wait for a reply first.")

        reason.contains("Persisted session is missing a session file") ->
            tr("pi 认为这个会话是持久化的，却没有会话文件路径。新开一个会话再试。", "Session has no file path. Start a new session and try again.")

        reason.contains("Failed to create forked session") ->
            tr("pi 没能写出分叉后的会话文件。检查 pi 工作目录是否可写（空间、权限），再试一次。", "Could not write the forked session file. Check storage space and permissions.")

        else -> tr("可以重新打开这个会话再试一次。", "Reopen this session and try again.")
    }
    return tr("创建分叉失败：$reason。$next", "Fork failed: $reason. $next")
}

/**
 * Hand [text] to the composer, exactly as an extension's `set_editor_text`
 * does — one channel, one sequence counter, so a later fill always wins.
 */
private fun PiSessionViewModel.fillComposer(text: String) {
    composerFillSeq += 1
    _state.update { s -> s.copy(composerFill = ComposerFill(composerFillSeq, text)) }
}

/** `clone` — duplicate the active branch at the current position. */
internal fun PiSessionViewModel.cloneSessionImpl() {
    call(tr("复制会话", "Clone session")) { api ->
        val result = api.cloneSession()
        if (result.cancelled) {
            pushNotice(tr("扩展取消了复制", "An extension cancelled cloning"), Notice.Tone.Warning)
            return@call
        }
        afterSessionReplaced(brandNew = true)
        requestNav(NavRequest.Chat)
        pushNotice(tr("已复制为新会话", "Cloned into a new session"), Notice.Tone.Info)
    }
}

// ------------------------------------- pi 的会话内跳转（`navigateTree`）

/**
 * `/tree` 里选中一个点并确认：SDK `AgentSession.navigateTree`（宿主 `session.navigate`），
 * 流程逐步对照 `interactive-mode.js` 的 `showTreeSelector`：
 *
 *  1. 选中的就是当前叶子 → 只提示 "Already at this point"，不调用 SDK。
 *  2. 摘要问题由界面问完（含 `branchSummary.skipPrompt`），答案经 [choice] 传进来。
 *  3. 「用户已经决定要跳了」：正在输出就先停（`restoreQueuedMessagesToEditor` + `abort`，
 *     即 [stopAndDrainQueue]）——SDK 的 `navigateTree` 自己在流式中会直接抛错，TUI 才会先停。
 *  4. 结果：`aborted`（摘要被中止）→ 提示并 [onAborted]（TUI 回到树、保持选中）；
 *     `cancelled`（扩展取消）→ 提示；否则重建对话、刷新树，`editorText`
 *     （跳到用户消息 / 扩展消息时，SDK 把它的文字交回来）**仅在输入框为空时**填入。
 *
 * 「有没有移动」仍以 `get_tree` 的叶子为准（[navigateOutcome]）：SDK 不发事件，叶子是唯一的权威信号。
 */
internal fun PiSessionViewModel.navigateToImpl(
    entryId: String,
    choice: BranchSummaryChoice,
    customInstructions: String? = null,
    onAborted: () -> Unit = {},
) {
    val engine = session
    if (engine == null) {
        fail(tr("引擎未就绪：pi 现在不在运行，跳转没有执行。", "Engine not ready: navigation skipped."))
        return
    }
    if (entryId.isBlank()) return
    call(NAVIGATE_BUSY_LABEL, errorText = ::navigateFailureText) { api ->
        val before = api.getTree().leafId
        if (before == entryId) {
            pushNotice(tr("已经在这个位置", "Already at this point"), Notice.Tone.Info)
            return@call
        }
        if (engine.transcript.streaming) {
            val restored = runCatching { engine.stopAndDrainQueue() }.getOrDefault(emptyList())
            if (restored.isNotEmpty()) {
                composerFillSeq += 1
                _state.update { s -> s.copy(
                    composerFill = ComposerFill(composerFillSeq, restored.joinToString("\n\n"), append = true),
                ) }
            }
            syncTranscript(engine, engine.publication.value)
        }
        val summarize = wantsSummary(choice, branchSummarySkipPrompt())
        val result = engine.commands.navigate(
            targetId = entryId,
            summarize = summarize,
            customInstructions = customInstructions,
        )
        fun flag(key: String) = (result.data[key] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
        if (flag("aborted")) {
            pushNotice(tr("已取消分支摘要", "Branch summary cancelled"), Notice.Tone.Info)
            onAborted()
            return@call
        }
        val cancelled = flag("cancelled")
        if (cancelled) {
            pushNotice(tr("跳转已取消", "Navigation cancelled"), Notice.Tone.Warning)
            return@call
        }

        val editorText = (result.data["editorText"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

        // 唯一权威的「动没动」：`get_tree` / `get_entries` 报的是内存里的叶子。
        // 注意：当选中的是「当前叶子的直接子用户消息」时，官方 `navigateTree` 会把叶子保持在父节点
        // 并把该用户消息放回 `editorText`；这种情况 `leafAfter == before` 但 `editorText != null`，
        // 必须与官方 TUI 一样把文字放回输入框并收起会话树，不能静默无响应。
        val outcome = navigateOutcome(before, api.getTree().leafId, refused = false)
        if (outcome == NavigateOutcome.Moved) {
            replayActiveBranch(engine)
            refreshTree()
            refreshStats()
        }
        if (outcome == NavigateOutcome.Moved || editorText != null) {
            if (editorText != null) {
                composerFillSeq += 1
                _state.update { s -> s.copy(
                    composerFill = ComposerFill(composerFillSeq, editorText, onlyIfEmpty = true),
                ) }
            }
            requestNav(NavRequest.Chat)
            pushNotice(
                if (editorText != null) {
                    tr("已回到此消息之前，原文已放回输入框", "Rewound before message; text restored to input")
                } else {
                    tr("已切换到所选分支位置", "Switched to selected branch point")
                },
                Notice.Tone.Info,
            )
        } else {
            pushNotice(tr("已经在这个位置", "Already at this point"), Notice.Tone.Info)
        }
    }
}

/**
 * Rebuild the transcript from the **active branch** — pi's `getBranch(leafId)`.
 *
 * This is deliberately not [replayHistory] and not [replayHistoryOverRpc]. Both fold the
 * entries they are given in order, and neither walks `parentId`:
 * [SessionFileReader.readTail] returns the file's *physical* tail, and
 * `TranscriptReducer.seedFromHistory` folds whatever list it receives
 * (`protocol/.../Transcript.kt`). After `navigateTree` the file still holds the
 * abandoned path — `branch()`/`branchWithSummary()` never delete entries
 * (`session-manager.ts:1374-1416`) — so either path would paint messages that are **not**
 * in pi's context, which is exactly the "half a screen of old data" this refetch exists
 * to prevent. `get_entries` carries pi's in-memory `leafId` (`rpc-mode.ts:648`), so the
 * branch can be filtered from the same answer.
 *
 * The history cursor is set to `reachedStart = true` for the same reason: the rebuild
 * starts at the branch's root, so there is nothing above it to fetch — and letting
 * [expandEarlierHistory] read further back would prepend the abandoned branch's entries
 * onto the new context.
 */
private suspend fun PiSessionViewModel.replayActiveBranch(engine: PiEngineSession) {
    val page = try {
        // 同 [replayHistoryOverRpc]：分支过滤要吃 `id` / `parentId`，那是原始条目才有的字段。
        engine.api.getEntriesRaw()
    } catch (e: HostCommandException) {
        fail(tr("跳转成功，但读回会话内容失败：${e.reason}。重新打开会话即可看到新的位置。", "Navigated, but failed to reload transcript: ${e.reason}. Reopen session to view."))
        return
    } catch (e: Exception) {
        fail(tr("跳转成功，但没能读回这个会话的内容；重新打开会话即可看到新的位置。", "Navigated, but failed to reload transcript. Reopen session to view."))
        return
    }
    val branch = activeBranch(page.entries, page.leafId)
    val chars = withContext(Dispatchers.IO) { retainedChars(branch) }
    engine.seedHistory(branch)
    loadedHistoryChars = chars
    // Same as the whole-session fallback above: a branch replay starts at the
    // branch's root and the cursor is `reachedStart`, so no byte range is ever read
    // back and there is no evicted copy.
    retainedChunks = listOf(RetainedChunk(branch, 0L, chars))
    evictedFrom = 0L
    loadedUntil = 0L
    evictedEntries = 0
    _state.update { s -> s.copy(
        history = HistoryCursor(startOffset = 0L, reachedStart = true),
        transcriptResetSeq = s.transcriptResetSeq + 1L,
    ) }
    syncTranscript(engine, engine.publication.value)
}

/**
 * `branchSummary.skipPrompt`, read from the settings document.
 *
 * The key has no row in [app.pi.ui.settings.PiSettingsCatalog] because pi reads it only
 * in its own TUI (`interactive-mode.ts:5236`) — but the flow it governs is the one
 * [navigateTo] now implements, so ignoring it would make the app ask a question the
 * user has already turned off. Reading it (rather than exposing it) is the same
 * treatment `ProjectScreen` gives `defaultProjectTrust`.
 */
/** pi 的 `settings.treeFilterMode`：打开会话树时的初始过滤（`getTreeFilterMode`，非法值回 default）。 */
internal fun PiSessionViewModel.treeFilterModeImpl(): app.pi.session.tree.TreeFilter =
    app.pi.session.tree.TreeFilter.fromWire(
        (settingsStore.read("treeFilterMode") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content,
    )

internal fun PiSessionViewModel.branchSummarySkipPromptImpl(): Boolean =
    settingsStore.readBoolean("branchSummary.skipPrompt") ?: false

/**
 * pi's failures on the navigation path, said with the next step attached.
 *
 * Same shape as [forkFailureText]: the reason is pi's own sentence, quoted, and each one
 * has a knowable cause. The states are the ones `navigateTree` refuses
 * (`core/agent-session.ts:3140-3147`、`:3157-3159`) plus the two the RPC layer can add.
 */
private fun PiSessionViewModel.navigateFailureText(error: Throwable): String {
    val reason = error.message?.takeIf { it.isNotBlank() } ?: tr("原因未知", "Unknown reason")
    val next = when {
        reason.contains("Wait for the current response") ->
            tr("这一轮还在回复。等它结束（或按停止）之后再跳。", "Wait for the current response to finish (or stop it) before navigating.")

        reason.contains("compaction or tree navigation") ->
            tr("pi 正在压缩上下文或处理上一次跳转。等它结束再试。", "Pi is compacting or handling a previous navigation. Try again once finished.")

        reason.contains("No model available for summarization") ->
            tr("要摘要就得有一个可用的模型。先在「模型」里选一个，或改成「不摘要」再跳。", "Summarizing requires an active model. Pick a model or navigate without summarizing.")

        reason.contains("not found") ->
            tr("这个会话点已经不在 pi 的会话文件里了。点「刷新」重读会话树再选。", "Entry no longer exists in the session file. Refresh the session tree and try again.")

        reason.contains("timed out") ->
            tr("这次跳转（含摘要）超过了等待时间。摘要可能仍在 pi 里进行，稍后用「刷新」看结果。", "Navigation timed out while summarizing. Refresh shortly to check the result.")

        else -> tr("可以重新打开这个会话再试一次。", "Reopen this session and try again.")
    }
    return tr("跳转失败：$reason。$next", "Navigation failed: $reason. $next")
}

/**
 * `set_session_name`. pi trims and rejects an empty name with
 * `success: false` (`rpc-mode.ts:661-668`), which the façade turns into an
 * error, so no empty-name check is duplicated here.
 */
internal fun PiSessionViewModel.renameSessionImpl(name: String) {
    val trimmed = name.trim()
    call(tr("重命名会话", "Rename session")) { api ->
        api.setSessionName(trimmed)
        _state.update { s -> s.copy(
            meta = s.meta.copy(sessionName = trimmed),
        ) }
        applyActiveRow()
        if (sessionListLoadedOnce) refreshSessions()
        pushNotice(tr("已重命名为「$trimmed」", "Renamed to "$trimmed""), Notice.Tone.Info)
    }
}

/**
 * 会话列表里长按「重命名」：当前正在用的会话（含尚未落盘的 0 消息新会话）统一走 SDK `setSessionName`，
 * 其余会话由宿主用 `SessionManager.open(path).appendSessionInfo(name)` 追加一条 `session_info`（[SessionLibrary.rename]），
 * 并立即乐观更新列表行再静默与磁盘对账。
 */
internal fun PiSessionViewModel.renameSessionFromListImpl(summary: SessionSummary, name: String) {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return
    val open = _state.value.meta.sessionFile?.substringAfterLast('/')
    val activeId = _state.value.meta.sessionId?.takeIf { it.isNotBlank() }
    val isCurrent = summary.isCurrent ||
        (open != null && open == summary.file.name) ||
        (activeId != null && summary.id == activeId)
    if (isCurrent) {
        renameSession(trimmed)
        return
    }
    viewModelScope.launch {
        val ok = sessionLibrary.rename(summary.file, trimmed)
        if (ok) {
            _sessionList.value = _sessionList.value.copy(
                rows = _sessionList.value.rows.map { row ->
                    if (row.file.absolutePath == summary.file.absolutePath) row.copy(name = trimmed) else row
                },
            )
            pushNotice(tr("已重命名为「$trimmed」", "Renamed to "$trimmed""), Notice.Tone.Info)
            refreshSessions()
        } else {
            pushNotice(tr("重命名失败：${summary.file.name}", "Rename failed: ${summary.file.name}"), Notice.Tone.Warning)
        }
    }
}

/**
 * `/export [path]`。
 *
 * **给了名字**（`/export 名字.xxx`）＝ **pi 的分派，1:1**：后缀决定写哪一种，
 * **只写那一份**，名字就是你给的那个（`interactive-mode.ts:6062-6066`：`.jsonl` 结尾 → JSONL，
 * 其余 → HTML）。
 *
 * **没给名字**（菜单/面板里那个「导出会话」）＝ 两份一起：pi 的 HTML（默认名）＋
 * **会话文件本身**（逐字节复制、原名，见 [exportedSessionFile]）。HTML 导不回来，会话文件能导回来，
 * 而它一直就在磁盘上（pi 在写它，App 手上有 `meta.sessionFile`）。
 *
 * HTML 那一份一个字没动：仍走 RPC `export_html`（`rpc-types.ts:60`）写进工作区 ——
 * pi 的默认落点也是它的 cwd（`core/export-html/index.ts:191-196`）。
 *
 * 确认只说文件、不说路径：落点是 App 私有目录，能打印的路径用户都打不开 —— 那份导出因此以
 * **交付句柄**收尾（[ExportedSession]）。一份都没写出来时说没写出来。
 */
internal fun PiSessionViewModel.exportSessionImpl(fileName: String? = null) {
    val raw = fileName?.trim()?.takeIf { it.isNotEmpty() }
    val name = raw?.let { sanitizeExportFileName(it) }
    if (raw != null && name == null) {
        pushNotice(
            tr("导出文件名非法（不允许包含路径分隔符或 ..）。", "Invalid export filename (path separators and '..' are not allowed)."),
            Notice.Tone.Warning,
        )
        return
    }
    when {
        name != null && SessionExportNaming.isJsonl(name) -> exportJsonlOnly(name)
        name != null -> exportHtmlOnly(name)
        else -> exportBoth()
    }
}

/** Restrict export file names to a single safe basename inside the workspace directory. */
private fun PiSessionViewModel.sanitizeExportFileName(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return null
    if (trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains('\u0000') || trimmed.contains("..")) {
        return null
    }
    return trimmed
}

/** `/export <path>` 的 HTML 分支（pi 的分派）。 */
private fun PiSessionViewModel.exportHtmlOnly(htmlName: String) {
    call(tr("导出会话", "Export session")) { api ->
        api.exportHtml("${guestWorkspace()}/$htmlName")
        publish(listOfNotNull(exportedHtml(htmlName)))
    }
}

/** `/export <名字>.jsonl`：只给会话文件那一份（内容仍是磁盘上那份，逐字节；名字用你给的）。 */
private fun PiSessionViewModel.exportJsonlOnly(jsonlName: String) {
    call(tr("导出会话", "Export session")) { _ ->
        publish(listOfNotNull(exportedSessionFile(jsonlName)))
    }
}

/** 普通导出：HTML（默认名）与会话文件（原名）各一份。 */
private fun PiSessionViewModel.exportBoth() {
    val htmlName = SessionExportNaming.defaultHtmlName(_state.value.meta.sessionFile, System.currentTimeMillis())
    call(tr("导出会话", "Export session")) { api ->
        api.exportHtml("${guestWorkspace()}/$htmlName")
        publish(listOfNotNull(exportedHtml(htmlName), exportedSessionFile()))
    }
}

/** 导出目录里那份 HTML，真写出来了才回答一个交付项。 */
private fun PiSessionViewModel.exportedHtml(htmlName: String): ExportedFile? =
    File(defaultWorkspace(), htmlName).takeIf { it.isFile }?.let {
        ExportedFile(htmlName, SessionExportNaming.mimeTypeFor(htmlName), it.absolutePath)
    }

/** 登记这一次导出，并说清哪一份能导回来。 */
internal fun PiSessionViewModel.publish(files: List<ExportedFile>) {
    if (files.isEmpty()) {
        pushNotice(tr("导出没有写出文件，请重试。", "Export wrote no file; please try again."), Notice.Tone.Warning)
        return
    }
    _state.update { s -> s.copy(exported = ExportedSession(files)) }
    val withSessionFile = files.any { it.name.endsWith(SessionExportNaming.JSONL_SUFFIX, ignoreCase = true) }
    pushNotice(
        if (withSessionFile) tr("会话已导出：`.jsonl` 那份是会话文件本身，可以导回来。", "Session exported: the .jsonl file can be re-imported later.") else tr("会话已导出。", "Session exported."),
        Notice.Tone.Info,
    )
}

/**
 * 会话文件本身，**逐字节**复制一份到导出目录，回答一个交付项 —— 这份就是能 `importFromJsonl`
 * 导回来的那份。
 *
 * 不重新序列化：pi 的 `/export x.jsonl` 写的是 `getBranch()` 的重新序列化
 * （`core/session-export.js:7-42`），而"导出一个分支"就是**后缀副本**的来源（与完整会话文件顶着
 * 同一个 `id`，列表合并时会顶掉一半对话）。复制磁盘上那个文件则字节相同、`id` 不变、每条分支都在。
 *
 * **名字**：没给（普通导出）就用磁盘上那份自己的名字（`<ISO>_<id>.jsonl`）；给了名字
 * （`/export x.jsonl`）就用你给的 —— pi 那条路就是"写到你说的地方"。
 * 还没有文件时（pi 在第一条 assistant 消息落盘前不建文件）回答 null。
 */
internal suspend fun PiSessionViewModel.exportedSessionFile(name: String? = null): ExportedFile? {
    val source = hostSessionFile(_state.value.meta.sessionFile) ?: return null
    val safeName = if (name != null) sanitizeExportFileName(name) ?: return null else source.name
    val base = defaultWorkspace()
    val target = File(base, safeName)
    val copied = withContext(Dispatchers.IO) {
        runCatching {
            val baseCanon = base.canonicalFile.toPath()
            val targetCanon = target.canonicalFile.toPath()
            if (!targetCanon.startsWith(baseCanon) || targetCanon == baseCanon) return@runCatching null
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        }.getOrNull()?.takeIf { it.isFile }
    } ?: return null
    return ExportedFile(copied.name, SessionExportNaming.mimeTypeFor(copied.name), copied.absolutePath)
}

/** The user is done with the export row. */
internal fun PiSessionViewModel.dismissExportImpl() {
    _state.update { s -> s.copy(exported = null) }
}

/**
 * `get_last_assistant_text`, handed to [onText] for the clipboard.
 *
 * pi answers `{"text": null}` when there is no assistant message yet
 * (`rpc-mode.ts:656-659`) — a normal answer, not a failure, so it is reported
 * as "nothing to copy" rather than as an error.
 */
internal fun PiSessionViewModel.copyLastAssistantTextImpl(onText: (String) -> Unit) {
    call(tr("读取最后一条回复", "Copy last response")) { api ->
        val text = api.getLastAssistantText()
        if (text.isNullOrBlank()) {
            pushNotice(tr("还没有模型回复可以复制", "No assistant response to copy yet"), Notice.Tone.Warning)
        } else {
            onText(text)
        }
    }
}

/**
 * Everything that must happen when pi replaces the session in place.
 *
 * `switch_session`, `new_session`, `fork` and `clone` all rebind the session
 * inside the same process (`rpc-mode.ts:437-444`, `:605-631`) and emit no
 * replay, so the app re-reads pi's records itself. Nothing is reset
 * optimistically: the transcript is rebuilt from the session file and the
 * facts come back from `get_state`.
 *
 * ## Which session facts have to be re-read here, and which must not be
 *
 * Re-read:
 *
 *  - the transcript and its `streaming` flag ([replayHistory] → `seedHistory`,
 *    then [syncTranscript]). The reducer resets, so `lastUsage` comes back as the
 *    new session's too — `:1376` reads it off the reducer.
 *  - [UiState.meta] ([refreshState]), the `/` palette ([refreshCommands]), the
 *    session list ([refreshSessions]).
 *  - **[UiState.stats]** ([refreshStats]). It is per-session: pi's
 *    `getSessionStats` sums every entry **in this session file**
 *    (`session-manager.ts:1315-1316`), so leaving the old value on screen made
 *    the status row report the previous session's 输出 / 缓存读 / 费用 until the
 *    next turn finished — which is what made the user ask whether those figures
 *    were "all my sessions added up". (The token totals are cumulative *within* a
 *    session; `contextUsage` is the current branch's `getBranch()` reading. Two
 *    different scopes, one row.)
 *  - [UiState.queueSteering] / [UiState.queueFollowUp]. They are written only by
 *    `queue_update`, so without a reset a message queued in the session the user
 *    just left keeps its chip on the new session — a count of messages pi is not
 *    holding for this conversation. Zero is the honest value; pi's next
 *    `queue_update` (if it has anything queued) corrects it.
 *
 * Deliberately *not* re-read, with the reason:
 *
 *  - `tree` / `entries` / `forkMessages`: per-session, but each is read by the
 *    screen that shows it every time that screen opens ([refreshTree],
 *    [refreshForkMessages]) and the overlays close on a pick, so a stale copy is
 *    never the authority. Re-reading them here would add two RPC round trips to
 *    every switch for data nobody is looking at.
 *  - `bash`: a `!` command is a *workspace* job, not a session one — pi keeps
 *    running it across a switch and the app's row reports that process, so
 *    clearing it would hide a command that is still running.
 *  - the extension surfaces (`ext`,
 *    `windowTitle`, `notices`) and above all `extensionDialog`: pi re-sends them
 *    when they change and there is no RPC to re-read them. Dropping the dialog
 *    would be worse than stale — pi is *blocked* on that answer
 *    (`modes/rpc-mode.ts:254-271`), so clearing it would wedge the engine.
 *  - `exported`, which is already handled just below.
 *
 * @param brandNew the session was created **in this engine, in place**: the
 *   `new_session` / fork / clone paths, which rebind without emitting an event. They are
 *   also exactly the paths where [meta] may still name the session the user just left,
 *   *and* where the new session has **no file at all** (pi writes one on its first
 *   `message_end`). Reordering below cannot make a non-existent file trustworthy, so on
 *   these three the file question is **skipped** and the engine answers instead — see
 *   [replayHistory]'s `brandNew` for the defect this closes.
 */
internal suspend fun PiSessionViewModel.afterSessionReplaced(brandNew: Boolean = false) {
    val engine = session ?: return
    PiMemoryTrimmer.onSessionReplaced()
    // `refreshState` **before** the replay, not after: on the file-backed paths
    // (`switch_session` / import) the only trustworthy name for that file is the one
    // `get_state` just reported. Reading it first would use the meta of the session the
    // user just left. It cannot fix the in-place rebind paths — nothing can, because
    // there is no file yet — which is what `brandNew` is for.
    // 同上：`refreshState` 是 fire-and-forget，replay 会拿着**上一条会话**的 meta 去读
    // 文件（`switch_session`/import 之后屏幕上还是旧对话，直到下一帧 publication）。
    // 清除上一条会话的会话树缓存并自增重建序号，避免切换/新建会话后打开会话树瞬间看到旧会话分支。
    _state.update { s -> s.copy(
        tree = null,
        transcriptResetSeq = s.transcriptResetSeq + 1L,
    ) }
    api?.let { refreshStateNow(it) }
    replayHistory(engine, brandNew)
    refreshStats()
    refreshCommands()
    // 立刻把当前新会话/切换后的会话更新到会话列表（哪怕是刚新建的 0 消息会话也马上置顶可见）。
    applyActiveRow()
    if (sessionListLoadedOnce && !_sessionList.value.loading) refreshSessions()
    // 会话换了之后的列表更新不在这里调：`watchSessionListChanges` 盯着「当前会话文件 / 工作区 /
    // 消息数（到 2 为止）」，变了就在后台静默重读，不论列表屏开没开。
    //
    // One-shot: a hint is only valid for the command that set it. Left behind, it
    // would let a later attach replay the file of a session pi is no longer on.
    SessionHints.file = null
    if (_state.value.queueSteering != 0 || _state.value.queueFollowUp != 0) {
        // The texts go with the counts: both are written by the same
        // `queue_update` arm ([onEvent]), so clearing one without the other
        // would leave the pending row drawing a message pi no longer holds.
        _state.update { s -> s.copy(
            queueSteering = 0,
            queueFollowUp = 0,
            queueSteeringTexts = emptyList(),
            queueFollowUpTexts = emptyList(),
        ) }
    }
    // A different session's export is no longer the thing on screen: the row
    // would offer to save a file that belongs to the conversation the user just
    // left. The file itself stays on disk — this only drops the handle.
    if (_state.value.exported != null) {
        _state.update { s -> s.copy(exported = null) }
    }
}
