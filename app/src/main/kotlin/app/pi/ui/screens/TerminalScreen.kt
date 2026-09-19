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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.pi.ui.terminal.TerminalPane

/**
 * 终端页 —— 一栏顶栏 + 一个吃满剩余高度的终端，**没有别的**。
 *
 * ## Why this file exists
 *
 * The terminal used to be the third bottom-bar destination. The user retired it
 * ("终端基本不用管它了。现在是个废品那个功能。"), so it moved to **one plain row on
 * the settings home** (`03-navigation-decision.md:24`). The screen itself is this
 * frame: a title bar with the way back, and [TerminalPane] taking everything else.
 *
 * ## The explanatory sentence is gone, on purpose
 *
 * This screen used to open with 「输入 pi 回车进入原版 TUI：订阅登录、会话导入、以及需要
 * 终端的扩展都在那边。」 and a paragraph here argued for keeping it. The user's own
 * verdict on the running build was 「把这些小的说明文字删掉，光留最顶端的那两个字和返回
 * 按钮」: a terminal that opens with a paragraph is a terminal with less terminal in it,
 * the sentence is discoverable from `pi`'s own help once you are inside, and the
 * surfaces it named (subscription login, session import) each have a real entry of
 * their own now. So the screen is a bar and a terminal — **no copy anywhere on it** —
 * and that argument lives here rather than on the panel.
 *
 * ## Insets: who consumes what, and why the top is *not* padded
 *
 * This is the rule the layout is built on, and every line of it was wrong in the
 * build the user reported ("顶栏放到最上面" / "下面一大片是空的，终端框却很小"):
 *
 *  - the soft keyboard is consumed **once, by `PiRoot`'s `Scaffold`**, which carries
 *    `Modifier.imePadding()` for the whole app. A child that also called
 *    `imePadding()` would pad twice, because a parent's `imePadding()` does not
 *    consume the inset for its children — that is what once squeezed the terminal
 *    into a band. [TerminalPane] therefore never touches the IME inset.
 *  - the **status bar** is consumed by the `TopAppBar` itself: Material3's default
 *    `TopAppBarDefaults.windowInsets` is the status-bar inset, and this screen is
 *    drawn edge-to-edge underneath it. Adding the scaffold's top padding *as well*
 *    would reserve a second, empty band above the title — the "bar is not at the
 *    very top" half of the report.
 *  - the **bottom** inset is the one this screen must not drop: the app's bottom
 *    navigation bar sits under the overlay, so the pane is inset by exactly
 *    `contentPadding`'s bottom and nothing else.
 *
 * `contentPadding` is read and applied per edge rather than wholesale for exactly
 * that reason; see `03-navigation-decision.md` and `PiRoot`'s overlay host for who
 * produces it.
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
    Column(
        Modifier
            .fillMaxSize()
            // Bottom only. The top is the TopAppBar's own inset and the IME is the
            // scaffold's — see the KDoc above for what each double-pad did.
            .padding(bottom = contentPadding.calculateBottomPadding()),
    ) {
        TopAppBar(
            title = { Text("终端") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        // Everything below the bar, and the only other thing on this screen: the
        // terminal takes the whole remainder (its own Column is the pane and the key
        // bar, so `weight(1f)` here is the height the key bar does not need).
        TerminalPane(modifier = Modifier.weight(1f))
    }
}
