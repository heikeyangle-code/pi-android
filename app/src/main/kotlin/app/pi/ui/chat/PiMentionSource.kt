package app.pi.ui.chat

import android.content.Context
import app.pi.packages.AgentLayout
import app.pi.packages.GuestCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The `@` mention candidates, read from the guest by running the guest's own `fd`.
 *
 * ## Why this runs a command instead of walking the workspace
 *
 * The workspace is a host directory (`<files>/pi/workspaces/workspace-1`) that the
 * engine binds into the guest at `/workspace` (`PiEngineHost.kt:548-555`), so the
 * app *could* list it in Kotlin. It is not done, because pi's candidate semantics
 * are fd's semantics: the layered ignore files (`.gitignore`, `.ignore`,
 * `.fdignore`, the global ignore, with negation), `--hidden` combined with the
 * `.git` exclusion, `--type f --type d --follow` and the 100-result cap are all
 * behaviours of fd itself (`/root/pi-src/packages/tui/src/autocomplete.ts:132-161`;
 * also `packages/tui/test/autocomplete.test.ts:315-335`). A Kotlin reimplementation
 * would approximate a mature ignore crate; the same binary with the same argv does
 * not. The runtime already ships that binary for pi's own `find` tool
 * (`runtime.lock.json`, `artifacts.fd`; installed by `RuntimeProvisioner.kt:106`,
 * `:455-471` at `/usr/local/bin/fd`).
 *
 * ## The channel
 *
 * [GuestCommand] is the app's existing "run one non-interactive command in the
 * guest" path: it reuses the engine's own proot argv and environment
 * (`ProotCommand.build`/`environment`, `GuestCommand.kt:105-115`) and carries the
 * same workspace and agent-dir binds the engine uses (`:198-206`), so fd sees the
 * workspace this chat session actually works in. **That is a property of the
 * instance, not of the class**: the `hostWorkspace` constructor argument is
 * captured once, so an instance built for one workspace keeps completing paths out
 * of it. A workspace switch
 * must therefore drop the instance rather than keep it —
 * `PiSessionViewModel.rebuildWorkspaceScopedCaches` is where that happens. It
 * starts its own proot, which is
 * the second reason it is used here rather than the RPC `bash` command: `bash`
 * joins the model's context on the next prompt (`PiEngineApi.kt:245-249`), competes
 * with the `!` panel's single running command and its `abort_bash`
 * (`PiEngineApi.kt:261-263`), and would require the engine to be running at all -
 * while `@` completion is useful before the first message. `GuestCommand` touches
 * no session state.
 *
 * ## Cost, honestly
 *
 * One proot process per query. There is no device measurement of that cost in this
 * repository; the only related figure is proot-plus-node cold start, "about
 * 0.3-1.5 s" (`docs/pi-android-app-design.md:1055`), which is not this. So the
 * caller debounces more conservatively than pi's own 20 ms
 * (`packages/tui/src/components/editor.ts:251`) and drops superseded requests, and
 * [TIMEOUT_MS] is a bound on a wedged process rather than a measured latency.
 */
class PiMentionSource(
    context: Context,
    hostWorkspace: File,
) {

    private val layout = AgentLayout(context.applicationContext, hostWorkspace)
    private val guest = GuestCommand(layout)

    /**
     * One command at a time: a second fd while the first is still running would
     * only queue behind the same workspace, and the caller's newest request is the
     * only one whose answer it still wants.
     */
    private val lock = Mutex()

    /**
     * The candidates for [prefix], or null when [superseded] became true before the
     * command ran - the caller uses that to keep a stale keystroke's result from
     * replacing the current list.
     *
     * An empty [MentionLookup.Candidates] is a real answer: fd found nothing, or fd is not
     * there. pi behaves the same way when it has no fd to run - no suggestions, and its
     * autocomplete closes instead of showing an empty list
     * (`autocomplete.ts:305`, `:372`, `:741`).
     *
     * **[MentionLookup.Unavailable] is the other half, and it used to be silent.** "fd
     * matched nothing", "the runtime is not provisioned" and "the fd run failed" all
     * answered `emptyList()` before, so the composer drew the same nothing for a real
     * answer and for a lookup that never happened. Which one it is comes from the guest's
     * own report (`GuestCommand.Outcome`), never from a guess here —
     * [mentionUnavailableSentence] holds that decision and the harness pins every arm.
     */
    // `internal` because its answer type is (`MentionLookup`), and the only caller is this
    // module's ViewModel — the alternative would be widening the type to public for a
    // class that no other module can construct.
    internal suspend fun query(prefix: String, superseded: () -> Boolean): MentionLookup? {
        if (superseded()) return null
        return withContext(Dispatchers.IO) {
            lock.withLock {
                if (superseded()) null else run(prefix)
            }
        }
    }

    private fun run(prefix: String): MentionLookup {
        // No runtime, no fd: pi without fd offers no file suggestions at all.
        if (!layout.runtimeReady()) {
            // A fact about the install, not about a command: the sentence exists before
            // anything is run, so this arm needs no classifier and no assertion.
            return MentionLookup.Unavailable(mentionRuntimeNotReadySentence())
        }

        val plan = PiFileMentions.plan(prefix, layout.guestWorkspace, layout.guestHome)
        val outcome = guest.run(
            guestCommand = plan.shell,
            cwd = layout.guestWorkspace,
            timeoutMs = TIMEOUT_MS,
        )
        // fd exits non-zero when it matches nothing, and pi treats any non-zero exit as
        // "no candidates" (`autocomplete.ts:196-200`), so a non-zero exit is still this
        // same answer rather than an error the user needs to see. What is *not* that
        // answer — a missing runtime, a proot that would not start, a timeout, a process
        // killed before it exited — is reported instead of being folded into it.
        // A `null` here means the guest **ran and answered** — including "nothing matched"
        // — so it is a real answer and becomes an (empty) candidate list, never a failure
        // sentence. The classifier is the only place that decides which is which.
        val unavailable = mentionUnavailableSentence(
            runtimeReady = true,
            timedOut = outcome.timedOut,
            exitCode = outcome.exitCode,
            launchError = outcome.launchError,
            stderr = outcome.stderr,
        )
        if (unavailable != null) return MentionLookup.Unavailable(unavailable)

        return MentionLookup.Candidates(
            PiFileMentions.items(outcome.stdout, outcome.stderr, plan, prefix),
        )
    }

    companion object {
        /**
         * A bound on a wedged proot (proot is always started with
         * `--kill-on-exit`, `PiRuntime.kt:113`), not a measured latency. It only has
         * to be shorter than "the user gives up on the list".
         */
        const val TIMEOUT_MS: Long = 10_000L
    }
}
