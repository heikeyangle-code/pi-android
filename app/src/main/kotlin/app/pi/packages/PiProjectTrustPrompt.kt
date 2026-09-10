package app.pi.packages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pi.ui.theme.PiShapes
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
    /** Why this prompt is being shown; from [PackageStrings.rationale]. */
    explanation: String,
    /** Set while a trust decision is being written, to prevent double submission. */
    busy: Boolean = false,
    onChoose: (ProjectTrust.Option) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = PiTheme.palette
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                text = "信任这个项目？",
                style = MaterialTheme.typography.titleMedium,
                color = palette.text,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 420.dp)) {
                // pi's exact prompt, kept as preformatted text: the second line is
                // the path pi hashed, and a user may need to copy it.
                Text(
                    text = ProjectTrust.promptText(cwd),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.warning,
                )
                Spacer(Modifier.height(14.dp))
                options.forEach { option ->
                    Surface(
                        color = palette.selectedBg.copy(alpha = 0.45f),
                        shape = PiShapes.cardInner,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable(enabled = !busy) { onChoose(option) },
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (option.trusted) palette.success else palette.error,
                            )
                            val subtitle = PackageStrings.trustSubtitle(option.label)
                            if (subtitle.isNotEmpty()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.muted,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = PackageStrings.TRUST_SESSION_ONLY_NOTE,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.dim,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(PackageStrings.CANCEL) }
        },
        containerColor = palette.cardBg,
        titleContentColor = palette.text,
        textContentColor = palette.muted,
    )
}
