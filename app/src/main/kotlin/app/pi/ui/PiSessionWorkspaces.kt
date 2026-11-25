package app.pi.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import app.pi.engine.EngineFailureClassify
import app.pi.engine.PiEngineHost
import app.pi.engine.PiEngineSession
import app.pi.i18n.tr
import app.pi.protocol.Notice
import app.pi.runtime.GuestWorkspacePath
import app.pi.runtime.PiPaths
import app.pi.runtime.PtyLauncher
import app.pi.runtime.WorkspaceChoice
import app.pi.runtime.WorkspaceStore
import app.pi.ui.PiSessionViewModel.UiState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

/**
 * The workspace is app-private for speed; `/sdcard` goes through FUSE. The
 * directory comes from the runtime layer's accessor rather than from a second
 * copy of `workspace-1`, so the terminal, this ViewModel and the device
 * shell's write boundary cannot end up pointing at different directories.
 */
internal fun PiSessionViewModel.defaultWorkspace(): File = PtyLauncher.workspaceHost(getApplication<Application>())

/**
 * Where pi sees [defaultWorkspace].
 *
 * The rule is `GuestWorkspacePath`'s — `PiEngineHost` binds
 * `workspace.absolutePath` at that path and starts pi with it as its cwd.
 * Paths inside a `bash` or `export_html` argument are resolved **in the
 * guest**, so they must be written in the guest's spelling. This used to be a
 * literal here, which is one more place the engine's cwd could drift.
 */
internal fun PiSessionViewModel.guestWorkspace(): String = GuestWorkspacePath.hostToGuest(
    guestRootfs(),
    defaultWorkspace(),
)

private fun PiSessionViewModel.guestRootfs(): java.io.File {
    val application = getApplication<Application>()
    return PiPaths(
        filesDir = application.filesDir,
        nativeLibDir = java.io.File(application.applicationInfo.nativeLibraryDir),
    ).rootfs
}

// ------------------------------------------------------------ workspaces

/**
 * The workspaces the user can choose between, for a screen to render.
 *
 * A pass-through to [WorkspaceStore] on purpose: the store owns the layout and
 * the safety rules, and this ViewModel method exists so a screen mounted with a
 * `PiSessionViewModel` needs no second import to list them. Blocking file IO,
 * called from a composition-driven refresh path — the same shape the settings
 * stack already uses for pi's own directories.
 */
internal fun PiSessionViewModel.workspaceEntriesImpl(): List<WorkspaceStore.Entry> = WorkspaceStore.list(getApplication<Application>())

/** Create project directory [name] under the app-private projects root. Does **not** switch to it; see [switchWorkspace]. */
internal fun PiSessionViewModel.createWorkspaceImpl(name: String? = null): WorkspaceStore.Create = WorkspaceStore.create(getApplication<Application>(), name)

/** Create a new project folder [folderName] inside any writable [parentDir] and register it. */
internal fun PiSessionViewModel.createProjectInDirectoryImpl(parentDir: File, folderName: String): WorkspaceStore.Create =
    WorkspaceStore.createInDirectory(getApplication<Application>(), parentDir, folderName)

/** Register any existing host directory [dir] as a project without copying or moving files. */
internal fun PiSessionViewModel.registerProjectDirectoryImpl(dir: File): WorkspaceStore.Create =
    WorkspaceStore.registerDirectory(getApplication<Application>(), dir)

/** Give a project a display label. Never moves the directory — see [WorkspaceStore.rename]. */
internal fun PiSessionViewModel.renameWorkspaceImpl(name: String, label: String): WorkspaceStore.Rename {
    val result = WorkspaceStore.rename(getApplication<Application>(), name, label)
    if (result is WorkspaceStore.Rename.Ok && name == _state.value.workspace.name) {
        publishWorkspaceState(name, note = null, bumped = false)
    }
    return result
}

/**
 * Count what deleting [name] would destroy. The returned
 * [WorkspaceStore.DeleteConfirmation] is the only thing [deleteWorkspace]
 * accepts, which is how "report the file count and ask again" stops being a
 * convention and becomes the API shape.
 */
internal fun PiSessionViewModel.previewWorkspaceDeleteImpl(name: String): WorkspaceStore.Preview =
    WorkspaceStore.previewDelete(getApplication<Application>(), name)

/** Delete what [confirmation] described. Never the current workspace. */
internal fun PiSessionViewModel.deleteWorkspaceImpl(confirmation: WorkspaceStore.DeleteConfirmation): WorkspaceStore.Delete =
    WorkspaceStore.delete(getApplication<Application>(), confirmation)

/**
 * Move the engine into another workspace: persist the choice, stop the current
 * engine, boot a new one in the target directory.
 *
 * ## Ordering, and every failure in it
 *
 * 1. **Resolve the target first, with the old engine still alive.** A name we do
 *    not own, a directory that is gone, or one this app cannot write into is a
 *    refusal ([WorkspaceSwitch.Refused]) and changes nothing at all.
 * 2. **Move the engine through [PiEngineHost.restart]**, not by hand: that is
 *    the process-wide `PROCESS_LOCK` and the stop-before-start ordering the host
 *    exists to enforce, and it is the same path the extension-install restart
 *    uses. The provider passed is the **target directory**, so the engine's cwd
 *    comes from this decision rather than from a settings file that has not been
 *    written yet — which is what makes the next step safe.
 * 3. **Persist only after the new engine is up** ([WorkspaceStore.setCurrent]).
 *    A crash between 2 and 3 therefore leaves the stored choice pointing at the
 *    previous workspace, which is the one a fresh boot will use; the app is never
 *    left with a setting that claims a workspace the engine never entered.
 * 4. **Re-key everything bound to the old cwd** ([rebuildWorkspaceScopedCaches]).
 * 5. **Bump [WorkspaceState.revision]**, which clears the composer's draft and
 *    staged images alongside it ([afterWorkspaceChanged] →
 *    [publishWorkspaceState] → [alignComposerToWorkspace]) — see [WorkspaceState]
 *    and [ComposerDraft].
 *
 * When [PiEngineHost.restart] fails it has already stopped the old engine. The
 * old workspace's engine is then booted again, so the outcome is either "back to
 * where you were" ([WorkspaceSwitch.Failed.rolledBackTo] non-null) or an explicit
 * stopped state with the reason (null) — never "setting says A, engine runs B".
 * A refusal changes nothing by construction.
 *
 * ## A running turn
 *
 * [allowInterrupt] = false turns a busy engine into a refusal
 * ([PiEngineHost.Restart.RefusedTurnRunning]), which is what a caller that wants
 * to confirm first should pass. The default is true — this is an explicit "move
 * me to that workspace" tap, and a composer that silently did nothing while the
 * tap looked accepted would be worse. The "before" half of the warning is
 * [wouldInterruptTurn], which the screen asks before offering the choice; the
 * "after" half is the notice this method pushes plus
 * [WorkspaceSwitch.Ok.interruptedTurn] — so the user is never left wondering why
 * the answer they were waiting for stopped arriving.
 *
 * Call from a main-dispatcher coroutine (`viewModelScope`), like
 * [restartEngine].
 */
internal suspend fun PiSessionViewModel.switchWorkspaceImpl(name: String, allowInterrupt: Boolean = true): WorkspaceSwitch {
    val app = getApplication<Application>()
    val previous = WorkspaceStore.refresh(app).name
    if (name == previous) return WorkspaceSwitch.AlreadyCurrent(name)

    val target = WorkspaceStore.existing(app, name)
        ?: return WorkspaceSwitch.Refused(tr("项目「$name」不存在或未登记，未切换。", "Project \"$name\" does not exist or is not registered; switch cancelled."))
    if (!target.host.isDirectory || !target.host.canWrite()) {
        return WorkspaceSwitch.Refused(
            tr("项目「${target.displayName}」的目录不可写（${target.host.absolutePath}），未切换。", "Project \"${target.displayName}\" directory is not writable (${target.host.absolutePath}); switch cancelled."),
        )
    }
    // **热切换（pi 的原语义）。** SDK 里 cwd 是会话的属性：`session.new{cwd}` 在新项目里开一段
    // 新会话，宿主进程、模型、MCP、扩展都不动 —— 省掉十几秒冷启动。
    // 内部项目、任意 rootfs 目录、以及主共享存储（`/storage/emulated/0` 已随 GuestRecipe 挂载）
    // 全部天然可达，直接热切换；仅可移除 SD 卡等需额外 `-b` 挂载的卷在首次登记时重启一次。
    val liveApi = api
    val piPaths = PiPaths(
        filesDir = app.filesDir,
        nativeLibDir = File(app.applicationInfo.nativeLibraryDir),
    )
    val reachable = !GuestWorkspacePath.needsBind(piPaths.rootfs, target.host, target.guestPath) ||
        target.host.absolutePath in host.boundExternalHostPaths
    if (liveApi != null && reachable && !engineDownNow()) {
        val wasBusyLive = host.turnRunning
        if (wasBusyLive && !allowInterrupt) {
            val detail = tr("正在生成回答，未切换项目。", "Cannot switch project while generating a response.")
            pushNotice(detail, Notice.Tone.Warning)
            return WorkspaceSwitch.Refused(detail)
        }
        engineTransition = true
        try {
            val created = liveApi.newSession(cwd = target.guestPath)
            if (created.cancelled) {
                val detail = tr("扩展取消了这次切换，仍在「${WorkspaceChoice.displayName(previous, null)}」。", "An extension cancelled the project switch; staying in \"${WorkspaceChoice.displayName(previous, null)}\".")
                pushNotice(detail, Notice.Tone.Warning)
                return WorkspaceSwitch.Refused(detail)
            }
            val stored = WorkspaceStore.setCurrent(app, name)
            afterWorkspaceChanged(name, null)
            afterSessionReplaced(brandNew = true)
            requestNav(NavRequest.Chat)
            if (wasBusyLive) {
                pushNotice(tr("切换项目中断了正在运行的回合：上一轮未完成的模型调用与工具已停止。", "Switching project interrupted the active turn; unfinished model and tool calls were stopped."), Notice.Tone.Warning)
            }
            if (!stored) {
                pushNotice(tr("项目已切换，但设置文件不可写；重启应用后会回到上一个项目。", "Project switched, but could not be saved (settings file not writable); app restart will revert to the previous project."), Notice.Tone.Warning)
            }
            return WorkspaceSwitch.Ok(name, interruptedTurn = wasBusyLive)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val detail = error.message?.takeIf { it.isNotBlank() } ?: tr("切换项目失败", "Failed to switch project")
            pushNotice(detail, Notice.Tone.Warning)
            return WorkspaceSwitch.Refused(detail)
        } finally {
            engineTransition = false
        }
    }
    val oldHost = WorkspaceStore.currentHost(app)
    // Captured *before* the restart: `host.turnRunning` reads the live engine's
    // pi state, and the live engine after a successful restart is a different
    // process that has never run a turn.
    val wasBusy = host.turnRunning

    engineTransition = true
    val result = try {
        host.restart(
            reason = tr("切换工作区到「${target.displayName}」", "Switch workspace to \"${target.displayName}\""),
            // The target itself, not `::defaultWorkspace`: the stored choice is
            // written *after* this succeeds (step 3 above), so the provider
            // cannot be the thing that decides where the new engine starts.
            workspaceProvider = { target.host },
            allowInterrupt = allowInterrupt,
            // **新工作区开一段新对话。** 以前这里传 `launchOptions()`，其中
            // `continueMostRecent = app.sessions.resumeLast`（默认开）→ pi 用 `-c`
            // 在新 cwd 里挑"最近一段"恢复。结果就是用户说的那件事：切到 B，屏幕上
            // 突然换成 B 里另一段旧对话（或者干脆空白），而他刚才在 A 的那段像是
            // "被切掉了"。切换工作区是换项目，语义应该是**从新项目的空白开始**；
            // 想接着看旧对话，去会话列表里点那一段（同一个 cwd 的 `switch_session`）。
            launch = launchOptions().copy(continueMostRecent = false),
        )
    } finally {
        engineTransition = false
    }

    return when (result) {
        is PiEngineHost.Restart.Ok -> {
            // Persist, then adopt the new engine — that order, not the reverse:
            // `attach` (and everything after it) resolves `defaultWorkspace()`,
            // which reads the store, so the stored choice has to name the new
            // workspace by the time those run.
            val stored = WorkspaceStore.setCurrent(app, name)
            afterWorkspaceChanged(name, result.session)
            // 切换成功**不弹通知**（用户要求：这一步应该是安静的）。只有一种情况还说话：
            // 这一下真的打断了一个正在跑的回合，而那是用户刚在确认框里点过的事 ——
            // 说完"被停掉了"就够了，不再附带"对话在哪"之类的解释。
            if (wasBusy) {
                pushNotice(
                    tr(
                        "切换工作区中断了正在运行的回合：新引擎在「${target.displayName}」里，上一轮未完成的模型调用与工具都被停掉了。",
                        "Switching workspace interrupted the active turn: the new engine is in \"${target.displayName}\", and unfinished model calls and tools were stopped.",
                    ),
                    Notice.Tone.Warning,
                )
            }
            if (!stored) {
                pushNotice(
                    tr(
                        "工作区已切换，但这次选择没有保存成功（设置文件不可写）；重启应用后会回到「$previous」。",
                        "Workspace switched, but the selection could not be saved (settings file not writable); restarting will return to \"$previous\".",
                    ),
                    Notice.Tone.Warning,
                )
            }
            WorkspaceSwitch.Ok(name, interruptedTurn = wasBusy)
        }

        is PiEngineHost.Restart.RefusedTurnRunning -> {
            // Nothing changed: `restart` refuses before it stops anything.
            pushNotice(result.detail, Notice.Tone.Warning)
            WorkspaceSwitch.Refused(result.detail)
        }

        is PiEngineHost.Restart.RefusedNeedsProvisioning -> {
            pushNotice(result.detail, Notice.Tone.Warning)
            WorkspaceSwitch.Refused(result.detail)
        }

        is PiEngineHost.Restart.Failed -> rollBackWorkspace(app, previous, oldHost, target, result)
    }
}

/**
 * Whether a workspace switch right now would kill a turn. The screen asks this
 * before offering the choice, so `allowInterrupt = true` is an informed tap
 * rather than a surprise.
 */
internal fun PiSessionViewModel.wouldInterruptTurnImpl(): Boolean = host.turnRunning

private fun PiSessionViewModel.engineDownNow(): Boolean {
    val e = _state.value.engine
    return e == null || e == PiEngineSession.EngineState.Stopped || e == PiEngineSession.EngineState.Failed
}

/**
 * Everything that was resolved against the old cwd, refreshed for the new one.
 *
 * Four things are bound to a workspace and are easy to miss:
 *  - [settingsStoreCache] — pi's *project* settings are `<workspace>/.pi/settings.json`;
 *  - [mentionSourceCache] — `fd` runs inside the workspace;
 *  - the theme and its entries — project themes come from the workspace;
 *  - the session list — pi records a session's cwd, and the list is filtered by it
 *    (`SessionsScreen` compares against [GuestWorkspacePath.RELATIVE], which
 *    [WorkspaceStore.setCurrent] has already moved).
 */
private fun PiSessionViewModel.afterWorkspaceChanged(name: String, engine: PiEngineSession?) {
    // The caches first: `attach` reads `settingsStore` (to invalidate it), and
    // that getter would otherwise hand it a store still bound to the previous
    // workspace's project document.
    rebuildWorkspaceScopedCaches()
    // Clear any stale trust prompt & project-extension render caches from the previous project.
    _trustPrompt.value = null
    toolRenderCache.reset()
    messageRenderCache.reset()
    entryRenderCache.reset()
    markdownTransformCache.reset()
    _renderRev.value += 1
    // `attach` is not optional and not cosmetic: `PiEngineHost.restart` closed
    // the old engine before starting the new one, and every action here checks
    // `api != null`, so without it the UI would be inert until the next boot.
    if (engine != null) {
        attach(engine)
    } else {
        // Hot-switch via `session.new(cwd)`: refresh project-scoped models and trust state immediately.
        refreshModels()
        refreshTrustPrompt()
    }
    refreshPrefs()
    refreshTheme()
    publishWorkspaceState(name, note = null, bumped = true)
}

internal fun PiSessionViewModel.rebuildWorkspaceScopedCaches() {
    settingsStoreCache = null
    mentionSourceCache = null
}

/**
 * Put the workspace into [UiState], re-reading the directory for [hostPath].
 *
 * [bumped] is what tells the composer "this is a freshly switched session"; a call
 * that only re-reads the path (or reports a fallback) passes true only when the
 * workspace genuinely moved. Either way [alignComposerToWorkspace] runs here,
 * because this is the only place [WorkspaceState.revision] changes — see
 * [ComposerDraft] for why the clearing lives in the ViewModel now.
 */
internal fun PiSessionViewModel.publishWorkspaceState(name: String, note: String?, bumped: Boolean) {
    val current = _state.value.workspace
    val app = getApplication<Application>()
    val entry = runCatching { WorkspaceStore.existing(app, name) }.getOrNull()
    val owned = WorkspaceStore.isOwned(name)
    val guestPath = entry?.guestPath ?: if (owned) {
        "${GuestWorkspacePath.PROJECTS_ROOT_GUEST}/$name"
    } else {
        GuestWorkspacePath.hostToGuest(guestRootfs(), File(name))
    }
    val displayName = entry?.displayName ?: WorkspaceChoice.displayName(name, null)
    val revision = current.revision + if (bumped) 1 else 0
    _state.update { s -> s.copy(
        workspace = current.copy(
            name = name,
            displayName = displayName,
            guestPath = guestPath,
            hostPath = entry?.host?.absolutePath,
            isExternal = entry?.external ?: !owned,
            revision = revision,
            note = note,
        ),
    ) }
    alignComposerToWorkspace(revision)
    if (note != null) pushNotice(note, Notice.Tone.Warning)
}

/**
 * The switch failed after the old engine was already stopped. Put the app back
 * on its feet: boot the **previous** workspace again, and report honestly which
 * of the two outcomes happened.
 *
 * Nothing here writes the workspace setting — it still names [previous], and the
 * point of this recovery is to make that true of the running engine as well.
 */
private suspend fun PiSessionViewModel.rollBackWorkspace(
    app: Application,
    previous: String,
    oldHost: File,
    target: WorkspaceStore.Entry,
    failure: PiEngineHost.Restart.Failed,
): WorkspaceSwitch {
    engineTransition = true
    val back = try {
        host.boot(workspaceProvider = { oldHost }, launch = launchOptions()) { step ->
            _state.update { s -> s.copy(boot = Boot.Working(step)) }
        }
    } finally {
        engineTransition = false
    }
    val headline = tr("切换到「${target.displayName}」失败：${failure.message}", "Failed to switch to \"${target.displayName}\": ${failure.message}")
    return when (back) {
        is PiEngineHost.Boot.Ready -> {
            // No cache rebuild here: the workspace never moved — `setCurrent`
            // was not reached, so the stored choice and `GuestWorkspacePath` both
            // still name [previous], and every cwd-bound cache is still correct.
            attach(back.session)
            val message = tr("$headline，已回到「$previous」并重新启动引擎。", "$headline; reverted to \"$previous\" and restarted engine.")
            pushNotice(message, Notice.Tone.Warning)
            WorkspaceSwitch.Failed(message, failure.detail, rolledBackTo = previous)
        }

        else -> {
            val detail = (back as? PiEngineHost.Boot.Failed)?.detail ?: failure.detail
            val why = (back as? PiEngineHost.Boot.Failed)?.message ?: tr("引擎未能重新启动", "Engine failed to restart")
            _state.update { s -> s.copy(
                boot = Boot.Failed(EngineFailureClassify.workspaceSwitch(headline, detail)),
            ) }
            syncEngineService(engineAttached = false, bootInProgress = false)
            reportWakeLockNeed()
            val message = tr(
                "$headline；在「$previous」重新启动也失败了（$why）。引擎现在是停止的，工作区设置仍然是「$previous」。",
                "$headline; restarting in \"$previous\" also failed ($why). The engine is now stopped and workspace remains \"$previous\".",
            )
            pushNotice(message, Notice.Tone.Error)
            WorkspaceSwitch.Failed(message, detail, rolledBackTo = null)
        }
    }
}
