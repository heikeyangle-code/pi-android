package app.pi.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pi.ui.terminal.TerminalPane
import app.pi.ui.theme.PiSpacing

/**
 * The workbench (destination 3): the terminal.
 *
 * It used to be four segments — terminal · files · git · tasks — over one shared
 * context (the current working directory). The other three are gone, and they were
 * deleted rather than built because pi has no capability behind any of them:
 *
 *  - **files** — pi has no file tree or explorer; the way it offers files is `@`
 *    mention completion (`packages/tui/src/autocomplete.ts:289-311`), which this app
 *    already implements (`ui/chat/PiFileMentions.kt`), plus the `ls`/`read` tools.
 *  - **git** — pi reads exactly one thing from git, the current branch name, and only
 *    to paint its own TUI footer (`core/footer-data-provider.ts:127`,
 *    `modes/interactive/components/footer.ts:117`). There is no changes list, no diff
 *    view and no checkpoint concept; `examples/extensions/git-checkpoint.ts` is an
 *    example extension, not a capability, and it ships with neither pi's runtime nor
 *    this app. Branch, changes, diff and checkpoints have no RPC channel either
 *    (`modes/rpc/rpc-types.ts:20-74`).
 *  - **tasks** — pi has no cron, no scheduler and no background-task concept at all.
 *    Its bash tool takes only a command and a timeout and waits for the child to exit
 *    (`core/tools/bash.ts:35-38`, `:127`); a command that backgrounds itself with `&`
 *    is not tracked by pi and is only killed when pi exits (`utils/shell.ts:198-211`).
 *    There is nothing to list, so a task list could only be an invention.
 *
 * Deleting them is the repository's own rule (`docs/pi-sourced-lists.md`): a surface
 * with no source of truth is where the next silent wrong answer comes from. The
 * empty-state copy promised three capabilities this app cannot have, and the honest
 * version of that promise is no surface at all.
 *
 * The **terminal** segment is the one that is load-bearing rather than a
 * convenience: it runs the *original* pi TUI in a real PTY, which is the only way
 * the extension APIs that draw terminal cells (`ctx.ui.custom()`, overlays,
 * custom footers, `registerMessageRenderer`, `renderCall`/`renderResult`) stay
 * reachable. That is why this app never has to claim "we reimplemented 95% of the
 * API" — see [TerminalPane].
 *
 * The RPC engine keeps running while the user watches the terminal, so a
 * blocking `ctx.ui.confirm()` still needs an answerable dialog on this
 * destination. PiRoot mounts the extension UI host once, above whichever
 * destination is active, and this screen is rendered inside it — deliberately not
 * mounting its own, which would compose a second dialog and a second snackbar
 * host for the same request.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchScreen(
    contentPadding: PaddingValues,
) {
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(title = { Text("工作区") })
        // The one sentence about pi's own TUI lives here, not in the settings.
        // Without it, the capabilities that only the TUI has (subscription
        // login, session import, terminal-only extensions) would be invisible;
        // with it repeated per settings row, every such row became a signpost
        // instead of a setting (`docs/settings-review.md` §9).
        Text(
            "输入 pi 回车进入原版 TUI：订阅登录、会话导入、以及需要终端的扩展都在那边。",
            modifier = Modifier.padding(horizontal = PiSpacing.screen, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TerminalPane(modifier = Modifier.weight(1f))
    }
}
