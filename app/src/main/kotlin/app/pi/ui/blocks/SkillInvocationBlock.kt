package app.pi.ui.blocks

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.rpc.SkillInvocation
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
    var expanded by remember(defaultExpanded) { mutableStateOf(defaultExpanded) }

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
            // pi draws this box with **no frame at all** — `super(1, 1, …)` at
            // `skill-invocation-message.ts:17` sets a background, not a border — so
            // the card border is this app's own addition, the same kind of "已定妥协"
            // the other block cards carry. The token is `customMessageLabel` at 35 %
            // because that is what the two blocks built on the same `customMessageBg`
            // surface use (`ui/blocks/HookMessageBlock.kt:41`,
            // `ui/blocks/BranchSummaryBlock.kt:44`); this card used `borderMuted`
            // before, which left three sibling blocks with three different frames for
            // no reason. A consistency choice inside this app, not a pi value.
            borderColor = palette.customMessageLabel.copy(alpha = 0.35f),
        ) {
            // F28: pi's only toggle is the content region (see `ToggleContent`);
            // the header row is not a separate hit target.
            ToggleContent(expanded = expanded, onToggle = { expanded = !expanded }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AccentStripe(palette.customMessageLabel, PiSpacing.accentStripe)
                    Spacer(Modifier.width(PiSpacing.inner))
                    Text(
                        text = "技能",
                        style = PiTheme.text.monoSmall,
                        color = palette.customMessageLabel,
                    )
                    Spacer(Modifier.width(PiSpacing.inline))
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
                // (`packages/coding-agent/src/modes/interactive/components/skill-invocation-message.ts:42-45`),
                // so headings, lists and fences inside SKILL.md read as structure
                // instead of as source.
                //
                // `textColor = customMessageText` is exactly the option pi passes
                // (`skill-invocation-message.ts:43`: `color: (text) => theme.fg("customMessageText", text)`).
                // pi's `Markdown` applies it as the *base* foreground
                // (`components/markdown.ts:385`, inside `applyDefaultStyle` at
                // `:377-403`), so headings, links and code keep their own
                // `md*` tokens on top of it — which is what `PiMarkdownText`'s
                // `textColor` does. It cannot be folded into a constant: a
                // hand-written theme may set `customMessageText` differently from
                // `text`.
                //
                // One deliberate deviation, kept from before: pi prepends `**name**`
                // as a bold markdown header inside this document
                // (`skill-invocation-message.ts:40`), because in pi the name
                // otherwise appears only in the collapsed line. This card's header
                // row carries `/skill:name` in *both* states (spec §7.4), so
                // prepending it here would print the name twice.
                    PiMarkdownText(
                        markdown = item.body.ifEmpty { "（技能没有正文）" },
                        modifier = Modifier.fillMaxWidth(),
                        textColor = palette.customMessageText,
                    )
                }
            }
        }
    }
}
