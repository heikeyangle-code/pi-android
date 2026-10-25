package app.pi.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 全 App 唯一的 M3 弹层外壳（CI 只允许这个文件直调 `ModalBottomSheet`）。
 *
 * ## 为什么这一层存在
 *
 * `ChatSheets` 里六个 sheet、工作区五个面板、设置三个弹层原来各自直接调
 * `ModalBottomSheet`，于是圆角、把手、遮罩、底色各随各的默认值——M3 默认的 28dp 圆角与
 * 带拖拽条的把手，和页面手绘的 8/10 圆角并排放着，一打开弹层就像换了一个 App。
 * 这一层把外壳固定成一套：顶部圆角 16、把手 32×3（下面 6dp 间距）、遮罩 `0x52000000`、
 * 底色 `surfaceContainerHigh`、一次展开到底（`skipPartiallyExpanded`）。内容由调用方给。
 *
 * ## 这些数字现在在 `PiSheetChrome.kt`
 *
 * 视觉常量、把手、视觉壳都收进了同包的 `PiSheetChrome.kt`，因为 App 还有第二套自绘外壳
 * （`WorkspaceChrome.kt` 的 `WsSheet`：`Dialog` + 自绘遮罩 + 手写动画 + 拖拽）。两套壳的
 * **行为**不同是合理的——M3 白送下滑/点遮罩/返回键关闭，工作区那套要 ✕ 与页脚槽——但
 * **外观**必须是同一份常量，否则「所有弹层看起来是同一个东西」这件事每一批都会被重写一遍。
 *
 * 这里**没有**套 [PiSheetChromeFrame]：`ModalBottomSheet` 用自己的 `shape` /
 * `containerColor` 参数画表面，再在 content 里套一层 frame 等于同一个表面画两遍。自绘的
 * `WsSheet` 才需要 frame；两条路读的是 `PiSheetChromeMetrics` 里同一份数。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** 主题列表这类"一屏列完"的弹层要全高，并直接展开到顶。 */
    fullHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = if (fullHeight) modifier.fillMaxHeight() else modifier,
        shape = PiSheetChromeMetrics.shape,
        containerColor = PiSheetChromeMetrics.containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = PiSheetChromeMetrics.scrim,
        dragHandle = { PiSheetHandle() },
        content = content,
    )
}
