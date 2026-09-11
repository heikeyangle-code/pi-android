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
 * workspace this chat session actually works in. It starts its own proot, which is
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
     * An empty list is a real answer: fd found nothing, or fd is not there. pi
     * behaves the same way when it has no fd to run - no suggestions, and its
     * autocomplete closes instead of showing an empty list
     * (`autocomplete.ts:305`, `:372`, `:741`).
     */
    suspend fun query(prefix: String, superseded: () -> Boolean): List<PiFileMentions.Item>? {
        if (superseded()) return null
        return withContext(Dispatchers.IO) {
            lock.withLock {
                if (superseded()) null else run(prefix)
            }
        }
    }

    private fun run(prefix: String): List<PiFileMentions.Item> {
        // No runtime, no fd: pi without fd offers no file suggestions at all.
        if (!layout.runtimeReady()) return emptyList()

        val plan = PiFileMentions.plan(prefix, layout.guestWorkspace, layout.guestHome)
        val outcome = guest.run(
            guestCommand = plan.shell,
            cwd = layout.guestWorkspace,
            timeoutMs = TIMEOUT_MS,
        )
        // fd exits non-zero when it matches nothing, and pi treats any non-zero exit
        // as "no candidates" (`autocomplete.ts:196-200`), so this is the same
        // outcome rather than an error the user needs to see. A timeout or a proot
        // launch failure lands here too; the composer simply shows no list.
        if (!outcome.ok) return emptyList()

        return PiFileMentions.items(outcome.stdout, outcome.stderr, plan, prefix)
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
