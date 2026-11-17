package app.pi.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pi.rpc.PiImage
import app.pi.ui.PiSessionViewModel
import app.pi.ui.chat.ArgumentContext
import app.pi.ui.chat.ArgumentPalette
import app.pi.ui.chat.BashPanel
import app.pi.ui.chat.MentionPalette
import app.pi.ui.chat.PiFileMentions
import app.pi.ui.chat.PiSlashCommand
import app.pi.ui.chat.SessionExportDelivery
import app.pi.ui.chat.SlashPalette
import app.pi.ui.chat.mergeRestoredQueue
import app.pi.ui.extension.ExtensionStatusStrip
import app.pi.ui.extension.ExtensionWidgetStack
import app.pi.ui.extension.PLACEMENT_BELOW
import app.pi.ui.theme.PiSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 输入框上方的整组卡片：压缩中止、队列、导出交付、扩展状态/组件、`!` 输出、
 * `/` 命令面板、`@` 提及列表、附件缩略图。
 *
 * 从 `ChatBody` 拆出来：这一摞只读 `state`，对 `draft` / `attachments` 的修改全部走回调，
 * 自己不持有任何会话状态。第一组（到 `!` 输出为止）的高度通过 [onAboveHeight] 交回，
 * 列表据此留出底边空白。
 */
@Composable
internal fun ChatDockCards(
    state: PiSessionViewModel.UiState,
    session: PiSessionViewModel,
    draft: String,
    onDraft: (String) -> Unit,
    attachments: List<PiImage>,
    onAttachments: (List<PiImage>) -> Unit,
    mentionPrefix: String?,
    onPickCommand: (PiSlashCommand, String) -> Unit,
    argContext: ArgumentContext?,
    onAboveHeight: (Int) -> Unit,
) {
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    // 输入框上方的持久卡组：整组量一次高度，交给列表的底边留白。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { onAboveHeight(it.height) },
    ) {
    // 压缩在跑：pi 的终端里有「Compacting… (esc to cancel)」，这里同样给一张带中止的卡，
    // 不用翻到设置页（`session.abortCompaction`）。
    if (state.meta.compacting) {
        StatusActionRow(
            text = "正在压缩上下文…",
            actionLabel = "中止",
            onAction = { session.abortCompaction() },
        )
    }
    if (state.queueSteering > 0 || state.queueFollowUp > 0) {
        QueueRow(
            steering = state.queueSteering,
            followUp = state.queueFollowUp,
            // The texts come from the same `queue_update` that wrote the counts
            // (`PiSessionViewModel`'s `is PiEvent.QueueUpdate` arm), so the chips
            // and the lines can never disagree about how many are waiting.
            steeringTexts = state.queueSteeringTexts,
            followUpTexts = state.queueFollowUpTexts,
            // pi's `app.message.dequeue` (`alt+up`): the queue comes back to the
            // editor and **the turn keeps running** (`interactive-mode.js:3684-3693`
            // → `:3891-3910` with no `abort`). Stop is the other half — it drains
            // and aborts — and stays on the send button.
            onRestore = {
                session.restoreQueue { restored -> onDraft(mergeRestoredQueue(restored, draft)) }
            },
        )
    }

    // A finished export is a file the user cannot otherwise open: it lives in
    // this app's private workspace. The row is the delivery, and it is the
    // reason `exportSession` publishes `state.exported` at all.
    val exported = state.exported
    if (exported != null) {
        ExportDeliveryRow(
            exported = exported,
            onSave = {
                exportScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        SessionExportDelivery.saveToDownloads(context, exported)
                    }
                    session.notifyUser(result.sentence, warning = result.warning)
                }
            },
            onShare = {
                exportScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        SessionExportDelivery.share(context, exported)
                    }
                    session.notifyUser(result.sentence, warning = result.warning)
                }
            },
            onDismiss = { session.dismissExport() },
        )
    }

    ExtensionStatusStrip(statuses = state.ext.statuses, editorTakeover = state.ext.editorTakeover)

    ExtensionWidgetStack(
        // Remembered on the widget list, which only changes when an extension
        // pushes one: the filter used to allocate a new list on every recomposition
        // of this screen, i.e. once per streamed token, for an answer that had not
        // changed. `remember` rather than a cached field because the placement is a
        // property of the widget, not of this screen.
        widgets = remember(state.ext.widgets) {
            state.ext.widgets.filter { it.placement != PLACEMENT_BELOW }
        },
        onColumns = session::setPanelColumns,
    )

    state.bash?.let { bashRun ->
        BashPanel(
            run = bashRun,
            onAbort = { session.abortBash() },
            onDismiss = { session.dismissBash() },
            modifier = Modifier.padding(horizontal = PiSpacing.composerPageMargin, vertical = 4.dp),
        )
    }
    }

    // pi opens its autocomplete as soon as the line starts with `/` and closes
    // it once the command name is complete (its `CombinedAutocompleteProvider`
    // completes the first token only). The same rule keeps this list from
    // covering the transcript while arguments are being typed.
    // `commandsLoaded` gates the panel: before the first `get_commands` answer
    // the list is empty for a reason that has nothing to do with what the user
    // typed, so rendering here would flash the panel's "nothing here" state for
    // one frame every time `/` opens. Nothing is shown until the list it would
    // draw is real.
    //
    // The panel's own empty branch (`ui/chat/SlashPalette.kt`) is only *half*
    // unreachable, and that half is why this is a gate rather than a deletion:
    // `piCommandPalette` always returns `builtins + fromPi`
    // (`ui/chat/PiSlashCommands.kt:285`), so with a blank query the list can
    // never be empty and 「没有可用命令」 never renders — but a query nothing
    // matches renders 「没有匹配「…」的命令」 from the same branch, which is a
    // real state a user reaches by typing. Deleting the branch would delete that
    // state too.
    val paletteQuery = paletteQueryOf(draft)
    if (paletteQuery != null && state.commandsLoaded) {
        SlashPalette(
            commands = state.commands,
            query = paletteQuery,
            onPick = { command ->
                // A tap is pi's Enter-on-a-suggestion: the editor applies the
                // completion and then falls through to submit
                // (`packages/tui/src/components/editor.ts:784-802`,
                // `CombinedAutocompleteProvider.applyCompletion` inserts
                // `/name `), so selecting a command runs it. The exception is
                // a command pi itself marks as argument-taking
                // (`argumentHint`, which only the built-ins have): there the
                // name is completed and left in the composer, which is pi's Tab
                // behaviour, because submitting `/login` with no provider is
                // never what the user meant.
                //
                // An argument-taking command leaves its name in the composer
                // (pi's Tab behaviour). The two exceptions that used to be here —
                // `/model` and `/thinking`, whose argument the app picked itself —
                // are gone with their rows: they are no longer in this palette at
                // all, so the exception set is empty rather than wrong.
                if (command.argumentHint != null || command.completes) {
                    onDraft("${command.invocation} ")
                } else {
                    onDraft("")
                    onPickCommand(command, "")
                }
            },
            modifier = Modifier.padding(horizontal = PiSpacing.pageHorizontal, vertical = 4.dp),
        )
    }

    // The `@` mention list, next to the `/` palette and under the same two
    // rules: it belongs to what the composer holds right now (`mentions.query`
    // is the prefix the answer was computed for, so a late answer for an older
    // keystroke cannot appear), and **it is not drawn when there is nothing to
    // offer** — pi's autocomplete returns no suggestions at all in that case
    // (`packages/tui/src/autocomplete.ts:305`), it does not show an empty popup.
    //
    // A tap inserts and does not send: pi's `@` branch of `applyCompletion`
    // accepts the completion and stops, while only the `/` branch falls through
    // to submitting (`packages/tui/src/components/editor.ts:784-802`). This
    // app has no Enter-to-send for the composer to collide with, so a tap is
    // the whole gesture.
    // 命令名写完后轮到参数：用法提示 + 扩展给的候选。
    if (argContext != null) {
        val answer = state.argCompletions?.takeIf {
            it.command == argContext.command.name && it.prefix == argContext.prefix
        }
        ArgumentPalette(
            command = argContext.command,
            prefix = argContext.prefix,
            completions = answer?.items.orEmpty(),
            onPick = { item -> onDraft("/${argContext.command.name} ${item.value} ") },
            modifier = Modifier.padding(horizontal = PiSpacing.pageHorizontal, vertical = PiSpacing.small),
        )
    }

    val mentions = state.mentions
    if (mentionPrefix != null && mentions != null && mentions.query == mentionPrefix && mentions.items.isNotEmpty()) {
        val prefix = mentionPrefix
        MentionPalette(
            candidates = mentions.items,
            onPick = { item -> onDraft(PiFileMentions.apply(draft, prefix, item)) },
            modifier = Modifier.padding(horizontal = PiSpacing.pageHorizontal, vertical = 4.dp),
        )
    }

    if (attachments.isNotEmpty()) {
        // 一条消息能带的图片没有张数上限（只受合计体积限制），所以这一排要能横向滑。
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = PiSpacing.composerPageMargin, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            attachments.forEachIndexed { index, image ->
                AttachmentThumb(
                    image = image,
                    index = index,
                    onRemove = {
                        onAttachments(attachments.filterIndexed { i, _ -> i != index })
                    },
                )
            }
        }
    }
}
