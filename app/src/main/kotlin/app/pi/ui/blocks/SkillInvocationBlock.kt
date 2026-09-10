package app.pi.ui.blocks

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.rpc.SkillInvocation
import app.pi.ui.theme.PiTheme

/**
 * `skill-invocation` (docs/pi-android-ui-spec.md §7.4): a card headed
 * 技能 /skill:name with an expand arrow; expanding shows the SKILL.md body
 * (human prose, so the system font, not mono).
 */
@Composable
fun SkillInvocationBlock(
    item: SkillInvocation,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
) {
    val palette = PiTheme.palette
    // Keyed on the parameter so the AppBar's expand/collapse-all switch (pi's
    // `app.tools.expand`, interactive-mode.ts `setToolsExpanded`) reaches every
    // row, exactly as pi re-applies expansion to all of its children.
    var expanded by remember(defaultExpanded) { mutableStateOf(defaultExpanded) }

    BlockColumn(modifier) {
        BlockCard(
            color = palette.cardBg,
            borderColor = palette.borderMuted.copy(alpha = 0.4f),
        ) {
            ToggleRow(expanded = expanded, onToggle = { expanded = !expanded }) {
                AccentStripe(palette.customMessageLabel, 20.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "技能",
                    style = PiTheme.text.monoSmall,
                    color = palette.customMessageLabel,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "/skill:" + item.skillName.ifEmpty { "未知技能" },
                    modifier = Modifier.weight(1f),
                    style = PiTheme.text.mono,
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ExpandLabel(expanded)
            }
            if (expanded) {
                ProseText(
                    text = item.body.ifEmpty { "（技能没有正文）" },
                    color = palette.text,
                )
            }
        }
    }
}
