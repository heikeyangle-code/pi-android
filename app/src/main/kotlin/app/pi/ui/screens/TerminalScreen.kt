package app.pi.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.pi.ui.PiTopBar
import app.pi.ui.terminal.TerminalPane

/**
 * 终端页：统一使用 [PiTopBar]（与全应用一致的 56dp 高度与表面色）+ 占满剩余高度的终端画布。
 * IME 仍由 `PiRoot` 的 `Scaffold` 统一消费，这里只消费顶部状态栏与底部导航条 inset。
 */
@Composable
fun TerminalScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(bottom = contentPadding.calculateBottomPadding()),
    ) {
        PiTopBar(title = "终端", onBack = onBack)
        TerminalPane(modifier = Modifier.weight(1f))
    }
}
