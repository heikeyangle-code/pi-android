package app.pi.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pi.ui.terminal.TerminalPane
import app.pi.ui.theme.PiSpacing

/**
 * 终端页 —— 一个薄包装，**不是**重设计。
 *
 * ## Why this file exists
 *
 * The terminal used to be the third bottom-bar destination. The user retired it
 * ("终端基本不用管它了。现在是个废品那个功能。"), so it moved to **one plain row on
 * the settings home** (`03-navigation-decision.md:24`). The screen itself is the
 * one it always was: this file is the frame that the settings row opens — a title
 * bar with the way back, the one sentence that says what the terminal is for, and
 * the unchanged [TerminalPane] taking the rest.
 *
 * ## What was deliberately not done
 *
 * Nothing under `ui/terminal` was touched. There is no restyle, no new geometry,
 * no status book, no key-bar redesign, no empty state — `06-v2-construction-reference.md`
 * §5 的第一条 says so explicitly (「终端页：没做界面、没进状态册、没有键条/键盘/
 * 空态；只在设置首页留一行普通入口行」), and `05-compose-migration-plan.md` §3.8
 * narrowed the whole terminal to navigation only. The one sentence below is the
 * only copy this batch moved, and it is moved verbatim from the old
 * `WorkbenchScreen.kt:72` — it is where the app explains that the surfaces RPC
 * cannot carry (subscription login, session import, terminal-only extensions)
 * live, and losing it would silently remove the only pointer to them.
 *
 * ## Why the back arrow is required here
 *
 * It is opened as an **overlay** (`PiRoot`'s `PiOverlay.Terminal`), so the app's
 * single overlay `BackHandler` already closes it by keypress. The arrow is not
 * redundant with that: a full-screen surface with no visible way out made the
 * terminal feel like a trap when it was a destination, and the settings row it now
 * hangs off is one level down, so the return path has to be on screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            title = { Text("终端") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        // The one sentence about pi's own TUI, moved here from the workbench. It is
        // not repeated per settings row on purpose: with it on every such row, each
        // row became a signpost instead of a setting (`docs/settings-review.md` §9).
        Text(
            "输入 pi 回车进入原版 TUI：订阅登录、会话导入、以及需要终端的扩展都在那边。",
            // 页水平 14（D1：`PiSpacing.pageHorizontal` 是唯一事实源）。这一屏 v2 没有
            // 画过（终端已退役），但页水平是全应用一致的取值，所以它跟着 14，而不是旧
            // 的 16。
            modifier = Modifier.padding(horizontal = PiSpacing.pageHorizontal, vertical = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TerminalPane(modifier = Modifier.weight(1f))
    }
}
