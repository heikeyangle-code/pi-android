package app.pi.packages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import app.pi.ui.settings.PiSettingsDialog
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * pi's project-trust prompt, as a real prompt that writes a real record.
 *
 * ## This is not a design choice about dialog style
 *
 * pi's own prompt cannot appear in RPC mode: `project-trust.ts:86-88` returns false
 * the moment `hasUI` is false, and `hasUI` is
 * `isInitialRuntime && trustPromptMode === "interactive"` (`main.ts:753`), which RPC
 * never satisfies. So the app's prompt is the *only* prompt — and if it did not
 * exist, `.pi/extensions` would be skipped with no event, no stderr line and no
 * error at all (`loader.ts:634-637`, `main.ts:775-782`).
 *
 * ## Fidelity
 *
 *  - The heading is [ProjectTrust.promptText], pi's `formatProjectTrustPrompt`
 *    (`project-trust.ts:24-26`) character for character, so a user comparing this
 *    screen with pi's TUI sees the same question.
 *  - The choices are [ProjectTrust.options], pi's five
 *    (`trust-manager.ts:66-96`) — including `Trust parent folder`, which is the one
 *    a shorter menu silently drops, and the two session-only escapes.
 *  - The Chinese line under each label is explanation only. Selecting an option
 *    writes exactly what pi would write; see [TrustRepository.apply].
 *
 * Dismissal maps to pi's own dismissal semantics — `resolveProjectTrusted` returns
 * false when the select is dismissed (`project-trust.ts:90-95`) — so the app treats
 * 取消 as "no decision this time", states that nothing was written, and leaves
 * [ProjectTrust.Rationale.NoUiRefused] explaining the skip.
 */
@Composable
fun PiProjectTrustPrompt(
    cwd: String,
    options: List<ProjectTrust.Option>,
    /**
     * Why this prompt is being shown. The caller assembles it — `PiPackagesHost` from
     * `ProjectTrust.resolve(...)`.
     */
    explanation: String,
    /** Set while a trust decision is being written, to prevent double submission. */
    busy: Boolean = false,
    onChoose: (ProjectTrust.Option) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = PiTheme.palette
    // 外壳走 v2 的对话框规格（06 §2 的 .b-dlg：330 / 圆角 14 / surf-high / 1px
    // borderMuted，见 `ui/components/PiDialog.kt`）；对话框的
    // 选项行是 v2 的列表行：等宽符号 + 正文标签 + 12 灰副行，`11px 14px` 内边距。
    PiSettingsDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = "信任这个项目？",
        dismissalLabel = PackageStrings.CANCEL,
        onDismissButton = { if (!busy) onDismiss() },
        content = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = PiSettingsMetrics.sheetBodyMax),
            ) {
                // pi's exact prompt, kept as preformatted text: the second line is
                // the path pi hashed, and a user may need to copy it.
                Text(
                    text = ProjectTrust.promptText(cwd),
                    style = PiTheme.text.meta,
                    color = palette.muted,
                )
                Spacer(Modifier.height(PiSpacing.inner))
                Text(
                    text = explanation,
                    style = PiTheme.text.meta,
                    color = palette.warning,
                )
                Spacer(Modifier.height(PiSettingsMetrics.cardPaddingLoose))
                options.forEach { option ->
                    val tone = if (option.trusted) palette.success else palette.error
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = PiSettingsMetrics.supportingGap)
                            .clip(RoundedCornerShape(PiSettingsMetrics.cardRadius))
                            .background(palette.selectedBg.copy(alpha = 0.45f))
                            .clickable(enabled = !busy) { onChoose(option) }
                            .padding(
                                horizontal = PiSettingsMetrics.cardPaddingLoose,
                                vertical = PiSettingsMetrics.rowPaddingVertical,
                            ),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.titleGap),
                        ) {
                            // 状态三重编码（06 §4）：符号 + 字 + 颜色。
                            Text(
                                text = if (option.trusted) "✓" else "⊘",
                                style = PiTheme.text.monoSmall,
                                color = tone,
                            )
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = tone,
                            )
                        }
                        val subtitle = PackageStrings.trustSubtitle(option.label)
                        if (subtitle.isNotEmpty()) {
                            Spacer(Modifier.height(PiSettingsMetrics.supportingGap))
                            Text(
                                text = subtitle,
                                style = PiTheme.text.meta,
                                color = palette.muted,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(PiSpacing.gutter))
                Text(
                    text = PackageStrings.TRUST_SESSION_ONLY_NOTE,
                    style = PiTheme.text.meta,
                    color = palette.dim,
                )
            }
        },
    )
}
