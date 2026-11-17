package app.pi.ui.screens

import app.pi.engine.PiEngineSession
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.ui.ExportedSession
import app.pi.ui.chat.ChatChip
import app.pi.ui.chat.SessionExportDelivery
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/** 输入框上方的状态行：压缩中止、队列、导出交付。从 ChatScreen.kt 拆出。 */


/**
 * How many queued-message lines [QueueRow] draws before it stops.
 *
 * pi prints every queued message (`updatePendingMessagesDisplay`,
 * `interactive-mode.js:3873-3889`); the cap is this screen's, and it is what keeps a
 * long queue from walking the composer up the screen. Nothing is hidden by it: the
 * two count chips above the lines are the complete reading.
 */
private const val PENDING_MESSAGE_LINES = 2


/**
 * What is waiting behind the running turn, plus the one action pi offers on it.
 *
 * pi draws the same thing above its editor (`updatePendingMessagesDisplay`,
 * `interactive-mode.js:3873-3889`): one `dim` line per queued message —
 * `Steering: ${message}` (`:3879`) and `Follow-up: ${message}` (`:3883`) — plus one
 * hint, `↳ <key> to edit all queued messages` (`:3887`), because
 * `app.message.dequeue` restores **all** of them (`restoreQueuedMessagesToEditor`,
 * `:3891-3910`) and `clear_queue` has no per-message form
 * (`rpc-types.d.ts:34-36`). This row keeps the app's existing count chips and adds
 * that one action; it deliberately does not pretend each message can be taken back
 * alone, because nothing on the wire can do that.
 *
 * ## The message lines
 *
 * The text is pi's own. `queue_update` carries `{steering: string[],
 * followUp: string[]}` (`core/agent-session.js:655`; emitted on a steer enqueue at
 * `:1767`, on a follow-up at `:1783`, and by `clearQueue` at `:1920`) and RPC
 * forwards the whole event verbatim (`modes/rpc/rpc-mode.js:265-270`). The app used
 * to keep only `.size`, which left the queue the one state whose *content* the user
 * could not read — and since a queued message is deliberately not drawn in the
 * transcript until pi delivers it (`PiEngineSession.prompt`), the count was all
 * there was. The reading of each line is pi's too: steering first and then
 * follow-ups (the two loops at `interactive-mode.js:3878-3885`), each truncated to a
 * single visual line (`TruncatedText(text, 1, 0)` at `:3880`/`:3884`).
 *
 * `PiTheme.palette.dim` is the same token pi paints these lines with —
 * `theme.fg("dim", …)` at `:3879`/`:3883`/`:3887` — and it is resolved from the
 * user's own theme file (`PiThemeFiles.kt:1020`, the `dim` key), so a theme switch
 * moves both together. pi draws these lines on its canvas; this app now floats them on a
 * `surfaceContainerLow` card at the same 12 dp edge as the composer, because the composer
 * itself floats — bare lines over the transcript would read as debris rather than as a
 * queue waiting above the input box. At most [PENDING_MESSAGE_LINES] are drawn, unlike
 * pi's uncapped list.
 *
 * v2's `QueueRow` (`direction-b-v2.html:1381-1390`, phone11/phone12) is the shape of
 * the count row: a `⇢` chip for the steering count and a `⇣` chip for the follow-up
 * count — the symbol is the queue's own third channel, coloured by meaning (`⇢`
 * warning, `⇣` muted) while the words and the number stay in the normal text colour
 * — and 「收回并编辑」 in the accent at the far right. The app used to print the two
 * counts as bare words in surface-coloured pills, which left the queue the only state
 * on the screen without a symbol. The v2 board also draws a per-message card carrying
 * its own 「编辑」 (`direction-b-v2.html:2702-2718`); that half is **not** built,
 * because a per-message dequeue does not exist on the wire. The ruling is
 * `design/ui-refactor/14-illustration-and-queue-diff.md:230` option (a), and the
 * message text itself was adjudicated in row 6 of that table (`:181`).
 */
/** 输入框上方的一行状态卡：一句话 + 一个文字动作。队列行、压缩中止共用同一套外观。 */
@Composable
internal fun StatusActionRow(text: String, actionLabel: String, onAction: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.composerPageMargin)
            .padding(bottom = PiSpacing.small),
        shape = PiShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(PiSpacing.inline),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = PiTheme.text.meta,
                color = PiTheme.palette.dim,
            )
            Text(
                text = actionLabel,
                modifier = Modifier
                    .clickable(onClickLabel = actionLabel, onClick = onAction)
                    .padding(horizontal = PiSpacing.small, vertical = PiSpacing.tiny),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}


@Composable
internal fun QueueRow(
    steering: Int,
    followUp: Int,
    steeringTexts: List<String>,
    followUpTexts: List<String>,
    onRestore: () -> Unit,
) {
    // pi's order, capped by [PENDING_MESSAGE_LINES]. Built here rather than in the
    // caller so the two lists cannot be paired with the wrong counts.
    //
    // A blank text is skipped rather than drawn as an empty line. An attachment-only
    // mid-turn message queues with an empty string — `_queueSteer` pushes the *text*
    // (`core/agent-session.js:1765-1766`) and this app offers 引导 for an attachment
    // with no caption (the composer's `canSteer`), a state official pi cannot reach
    // at all because its submit rejects empty text
    // (`interactive-mode.js:2542-2544`). Skipping keeps such a queue showing exactly
    // what it showed before these lines existed: the count chip. The gap behind it —
    // a queued attachment cannot be taken back, because `clear_queue` returns
    // `string[]` only (`rpc-types.d.ts:194-199`) — is already recorded and needs a
    // product ruling: `design/ui-refactor/14-illustration-and-queue-diff.md:187` row 12.
    val pendingLines = buildList {
        steeringTexts.filter { it.isNotBlank() }.forEach { add("引导：$it") }
        followUpTexts.filter { it.isNotBlank() }.forEach { add("后续：$it") }
    }.take(PENDING_MESSAGE_LINES)
    // 悬浮输入框的邻居：同一条 12 dp 边、同一族的圆角卡片，叠在输入框上方。
    // 输入框浮起来之后，再把这几行裸文字直接画在聊天流上会像悬空的碎片 —— 给它一张
    // 和输入框同色的底，队列才读成"在输入框上面等着"，而不是"聊天流里的几行字"。
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.composerPageMargin)
            .padding(bottom = 6.dp),
        shape = PiShapes.card,
        // 比输入框（surfaceContainerLow）高一级：两张卡叠在一起时，队列是**浮在**
        // 输入框上面的那一张，不该和它融成一块。
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(PiSpacing.tiny),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // The two words are pi's two waiting modes and are **not** synonyms:
            // 引导 is `steeringMode`/steer (this turn is steered by the message: pi
            // delivers it after the turn's tool calls finish and before the next model
            // call, `core/agent-session.js:1741-1742`), 后续 is `followUpMode`/followUp
            // (processed once the agent would otherwise stop, `:1753-1754`). The glyphs
            // and tones are the queue's own third channel (`06 §4`: `⇢` warning,
            // `⇣` muted). The words are the same two the composer chips and the settings
            // page use, and the same two pi prints above its editor
            // (`interactive-mode.js:3881`/`:3885`).
            if (steering > 0) QueueChip(glyph = "⇢", tone = PiTheme.palette.warning, text = "引导 $steering")
            if (steering > 0 && followUp > 0) Spacer(Modifier.width(PiSpacing.inline))
            if (followUp > 0) QueueChip(glyph = "⇣", tone = PiTheme.palette.muted, text = "后续 $followUp")
            Spacer(Modifier.weight(1f))
            // The consequence, not the mechanism: the text goes back into the input box
            // and the turn that is running is not touched.
            Text(
                "收回并编辑",
                modifier = Modifier
                    .clickable(onClickLabel = "收回并编辑", onClick = onRestore)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        // One read-only line per queued message, in pi's `dim` and on the canvas —
        // the same shape as official, and the only place the user can read what is
        // actually waiting (`PiThemeFiles.kt:1020` resolves `dim` from their theme).
        pendingLines.forEach { line ->
            Text(
                text = line,
                modifier = Modifier.fillMaxWidth(),
                style = PiTheme.text.meta,
                color = PiTheme.palette.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    }
}


/**
 * One count chip: `06 §2` 的 chip（高 26 圆角 999），加上队列自己的符号。
 *
 * Both halves are monospace — a queue count is a machine reading, and the symbol
 * belongs to the machine face in v2 too (`<Chip glyph="⇢" … mono>`). The two counts
 * are the family's one pair with a **per-glyph tone** (`⇢` warning, `⇣` muted), which
 * is what [ChatChip]'s `glyphTone` carries; the words and the number stay in the text
 * colour. Its `surfaceContainerHigh` fill is gone: the chip is the same `1px
 * borderMuted` ring on the page ground as the model and search chips, which is what
 * `06 §2` draws and what made this one's fill read as a nested card.
 */
@Composable
private fun QueueChip(glyph: String, tone: Color, text: String) {
    ChatChip(
        text = text,
        glyph = glyph,
        glyphTone = tone,
    )
}


/**
 * The delivery row for a finished `/export`.
 *
 * pi's export lands in the user's own cwd (`core/export-html/index.ts:274-281`), so
 * on a desktop the file is simply there. This app's export lands in its private
 * workspace, where no file manager can reach it — so "where is it and how do I get
 * it out" is the only question the row has to answer. It shows the file name (what
 * the user will look for) and the two sinks the app already owns for its own
 * diagnostic report ([SessionExportDelivery]). No location is shown, because the
 * only location the app could print is one the user cannot open.
 */
@Composable
internal fun ExportDeliveryRow(
    exported: ExportedSession,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PiSpacing.composerPageMargin, vertical = 4.dp),
        shape = PiShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = PiSpacing.cardPadding, vertical = 8.dp)) {
            Text(
                "会话已导出",
                style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                exported.name,
                style = PiTheme.text.monoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeliveryAction("保存到 Download", onSave)
                Spacer(Modifier.width(14.dp))
                DeliveryAction("分享", onShare)
                Spacer(Modifier.weight(1f))
                Text(
                    "关闭",
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}


@Composable
private fun DeliveryAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.primary,
    )
}
