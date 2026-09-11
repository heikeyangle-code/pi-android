package app.pi.ui.blocks

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import app.pi.ui.render.PiMarkdownText
import app.pi.ui.theme.PiTheme

/**
 * `skill-invocation` (docs/pi-android-ui-spec.md §7.4): a card headed
 * 技能 /skill:name with an expand arrow; expanding shows the SKILL.md body as
 * markdown — pi's expanded branch is a `Markdown`
 * (`packages/coding-agent/src/modes/interactive/components/skill-invocation-message.ts:40-45`)
 * and the spec asks for the same. Collapsed it shows the header only, which is
 * what pi's collapsed branch does too (one `[skill] name (… to expand)` line,
 * `skill-invocation-message.ts:47-52`) — so, unlike its neighbours, this block
 * has no collapsed text preview and therefore no `maxLines` to preserve.
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
                // Expanded: markdown, not prose. pi's expanded branch builds a
                // `Markdown` over the skill body
                // (`packages/coding-agent/src/modes/interactive/components/skill-invocation-message.ts:40-45`),
                // so headings, lists and fences inside SKILL.md read as structure
                // instead of as source.
                //
                // Two deliberate deviations, recorded rather than hidden:
                //
                // 1. pi prepends `**name**` as a bold markdown header inside that
                //    document (`skill-invocation-message.ts:41-42`), because in pi
                //    the name otherwise appears only in the collapsed line. This
                //    card's header row carries `/skill:name` in *both* states
                //    (spec §7.4), so prepending it here would print the name twice.
                // 2. pi colours this markdown with `customMessageText`
                //    (`skill-invocation-message.ts:44`); `PiMarkdownText` draws it
                //    in `palette.text`. Both of pi's built-in themes define
                //    `customMessageText` as `text` (`interactive/theme/dark.json:43`,
                //    `light.json:42`), so the two coincide for the shipped themes
                //    and differ only for a hand-written theme.
                PiMarkdownText(
                    markdown = item.body.ifEmpty { "（技能没有正文）" },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
