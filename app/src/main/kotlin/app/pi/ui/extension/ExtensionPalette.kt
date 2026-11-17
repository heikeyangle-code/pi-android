package app.pi.ui.extension

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.pi.ui.theme.PiPalette

/**
 * App 色板 → 宿主 `ui.palette` 的语义色表（键名就是 pi 主题里扩展组件写的 `theme.fg("accent", …)`）。
 *
 * 宿主默认是一套暗色；不推这张表，亮色主题下扩展组件（状态栏、面板、自定义渲染的工具卡）
 * 会是浅字落在浅底上。键只列宿主 `DEFAULT_PALETTE` 认识的那些，多的宿主也不会拒绝，但没有意义。
 */
fun PiPalette.toExtensionPalette(): Map<String, String> {
    fun Color.hex() = "#%06x".format(toArgb() and 0xFFFFFF)
    return mapOf(
        "text" to text.hex(), "muted" to muted.hex(), "dim" to dim.hex(), "accent" to accent.hex(),
        "border" to border.hex(), "borderAccent" to borderAccent.hex(), "borderMuted" to borderMuted.hex(),
        "success" to success.hex(), "error" to error.hex(), "warning" to warning.hex(),
        "thinkingText" to thinkingText.hex(), "userMessageText" to userMessageText.hex(),
        "customMessageText" to customMessageText.hex(), "customMessageLabel" to customMessageLabel.hex(),
        "toolTitle" to toolTitle.hex(), "toolOutput" to toolOutput.hex(),
        "mdHeading" to mdHeading.hex(), "mdLink" to mdLink.hex(), "mdLinkUrl" to mdLinkUrl.hex(),
        "mdCode" to mdCode.hex(), "mdCodeBlock" to mdCodeBlock.hex(), "mdCodeBlockBorder" to mdCodeBlockBorder.hex(),
        "mdQuote" to mdQuote.hex(), "mdQuoteBorder" to mdQuoteBorder.hex(), "mdHr" to mdHr.hex(),
        "mdListBullet" to mdListBullet.hex(),
        "diffAdded" to toolDiffAdded.hex(), "diffRemoved" to toolDiffRemoved.hex(), "diffContext" to toolDiffContext.hex(),
        "selectedBg" to selectedBg.hex(), "userMessageBg" to userMessageBg.hex(),
        "customMessageBg" to customMessageBg.hex(), "toolPendingBg" to toolPendingBg.hex(),
        "toolSuccessBg" to toolSuccessBg.hex(), "toolErrorBg" to toolErrorBg.hex(),
    )
}
