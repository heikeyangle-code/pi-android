package app.pi.ui.blocks

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.protocol.SkillInvocation
import app.pi.ui.render.PiMarkdownText
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiSpacing

/**
 * `skill-invocation` (docs/pi-android-ui-spec.md §7.4): a card headed
 * 技能 /skill:name with an expand arrow; expanding shows the SKILL.md body as
 * markdown — pi's expanded branch is a `Markdown`
 * (`packages/coding-agent/src/modes/interactive/components/skill-invocation-message.ts:36-45`)
 * and the spec asks for the same. Collapsed it shows the header only, which is
 * what pi's collapsed branch does too (one `[skill] name (… to expand)` line,
 * `skill-invocation-message.ts:46-53`) — so, unlike its neighbours, this block
 * has no collapsed text preview and therefore no `maxLines` to preserve.
 *
 * The three pi decisions this card follows literally, all from that one file:
 * the `customMessageBg` surface (`:17`), the `customMessageLabel` accents
 * (`:38` expanded, `:49` collapsed), and `customMessageText` for the body
 * markdown (`:43`).
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
    var expanded by rememberSaveable(defaultExpanded) { mutableStateOf(defaultExpanded) }

    BlockColumn(modifier) {
        BlockCard(
            // pi's skill box paints the *custom message* surface, not the generic
            // card one: `super(1, 1, (t) => theme.bg("customMessageBg", t))`
            // (`packages/coding-agent/src/modes/interactive/components/skill-invocation-message.ts:17`),
            // with the file's own comment saying it is the "same background color
            // as custom messages for visual consistency". It is not
            // interchangeable with `cardBg`: pi's dark theme has
            // `customMessageBg` `#2d2838` vs `cardBg` `#1e1e24`, light has
            // `#ede7f6` vs `#ffffff` (`interactive/theme/dark.json:20,88`,
            // `light.json:19,87`).
            color = palette.customMessageBg,
            borderColor = null,
        ) {
            // F28: pi's only toggle is the content region (see `ToggleContent`);
            // the header row is not a separate hit target.
            ToggleContent(expanded = expanded, onToggle = { expanded = !expanded }) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(PiSpacing.inline),
                ) {
                    CardChip(text = tr("技能", "Skill"), color = palette.customMessageLabel)
                    Text(
                        text = "/skill:" + item.skillName.ifEmpty { tr("未知技能", "unknown") },
                        modifier = Modifier.weight(1f),
                        style = PiTheme.text.mono,
                        // pi paints this name with `customMessageText`:
                        //   `theme.fg("customMessageLabel", "[skill] ") + theme.fg("customMessageText",
                        //    this.skillBlock.name) + theme.fg("dim", " (… to expand)")`
                        // (`components/skill-invocation-message.js:39-42`). This cell carries that
                        // name, and its two neighbours already use their own tokens
                        // (`customMessageLabel` for 「技能」), so `text` was the one value on this
                        // row that pi's own line does not use.
                        color = palette.customMessageText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ExpandLabel(expanded)
                }
                if (expanded) {
                    PiMarkdownText(
                        markdown = item.body.ifEmpty { tr("（技能没有正文）", "(Empty skill body)") },
                        modifier = Modifier.fillMaxWidth(),
                        textColor = palette.customMessageText,
                        immediate = true,
                    )
                }
            }
        }
    }
}
