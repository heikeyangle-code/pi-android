package app.pi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 全 App 唯一的弹层外壳。
 *
 * 为什么要有它：`ChatSheets` 里六个 sheet、工作区五个 sheet、设置三个弹层各自直接调
 * `ModalBottomSheet`，于是圆角、把手、遮罩、底色各随各的默认值——M3 默认的 28dp 圆角与
 * 带拖拽条的把手，和页面手绘的 8/10 圆角、无 M3 视觉并排放着，一打开弹层就像换了一个 App。
 *
 * 这一层把外壳固定成一套：顶部圆角 20、36×3 把手、遮罩 `0x8C000000`、底色
 * `surfaceContainerHigh`、一次展开到底（`skipPartiallyExpanded`）。内容由调用方给，
 * 外壳不再由每个 sheet 自己搭。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = SheetScrim,
        dragHandle = { PiSheetHandle() },
        content = content,
    )
}

/** 36×3 的把手，全 App 同一个。 */
@Composable
fun PiSheetHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 36.dp, height = 3.dp)
                .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
        )
    }
}

/** 顶部圆角 20：与 `PiShapes` 的卡片/输入不同，弹层是另一档。 */
private val SheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

/** 比 M3 默认更沉一点：弹层后面的转录仍然可读。 */
private val SheetScrim = Color(0x8C000000)
