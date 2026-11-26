package app.pi.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import app.pi.ui.NavRequest
import app.pi.ui.PiSessionViewModel
import app.pi.ui.chat.ActiveSystemPromptSheet
import app.pi.ui.chat.ChangelogSheet
import app.pi.ui.chat.LogoutPickerSheet
import app.pi.ui.chat.ContextSheet
import app.pi.ui.chat.ForkPickerSheet
import app.pi.ui.chat.ModelPickerSheet
import app.pi.ui.chat.RenameSessionDialog
import app.pi.ui.chat.SessionStatsSheet
import app.pi.ui.chat.SessionToolsSheet
import app.pi.ui.chat.ThinkingPickerSheet

/** Which bottom sheet the chat screen has open, if any. */
internal enum class ChatSheet { Model, Thinking, Tools, Stats, Context, Fork, Rename, SystemPrompt, Changelog, Logout }

/**
 * 聊天页所有底部弹层的唯一出口：[sheet] 说开哪一个，[onSheet] 是切换/关闭。
 *
 * 从 `ChatBody` 里拆出来——弹层只依赖 `state` 和 `session`，不碰列表/输入框的任何局部状态，
 * 所以放在页面树顶层、而不是某一行里面（见 `ChatBody` 里 `viewedImage` 的说明）。
 */
@Composable
internal fun ChatSheetHost(
    sheet: ChatSheet?,
    onSheet: (ChatSheet?) -> Unit,
    state: PiSessionViewModel.UiState,
    session: PiSessionViewModel,
) {
    val context = LocalContext.current
    when (sheet) {
        ChatSheet.Model -> ModelPickerSheet(
            initialFilter = session.modelPickerSeed.collectAsStateWithLifecycle().value,
            models = state.models,
            current = state.meta.model,
            busy = state.busy != null,
            onPick = { model, persist ->
                session.setModel(model, persist = persist)
                onSheet(null)
            },
            onRefresh = { session.refreshModelsCatalog() },
            onManageProviders = { session.requestNav(NavRequest.SettingsFocus("hub:models")) },
            onDismiss = { onSheet(null) },
        )

        ChatSheet.Thinking -> ThinkingPickerSheet(
            levels = state.meta.thinkingLevels,
            current = state.meta.thinkingLevel,
            onPick = { level, persist -> session.setThinkingLevel(level, persist = persist) },
            onDismiss = { onSheet(null) },
        )

        ChatSheet.Tools -> SessionToolsSheet(
            state = state,
            onSteeringMode = { session.setSteeringMode(it) },
            onFollowUpMode = { session.setFollowUpMode(it) },
            onAutoCompaction = { session.setAutoCompaction(it) },
            onAutoRetry = { session.setAutoRetry(it) },
            onAbortRetry = { session.abortRetry() },
            onCompact = {
                session.compact()
                onSheet(null)
            },
            onOpenSettings = { key -> session.requestNav(NavRequest.SettingsFocus(key)) },
            onDismiss = { onSheet(null) },
        )

        ChatSheet.Stats -> SessionStatsSheet(state = state, onDismiss = { onSheet(null) })

        ChatSheet.Context -> ContextSheet(
            state = state,
            onDismiss = { onSheet(null) },
            onCompact = { instructions -> session.compact(instructions) },
            onOpenCompactionSettings = { session.requestNav(NavRequest.SettingsFocus("compaction.enabled")) },
        )

        ChatSheet.Fork -> ForkPickerSheet(
            messages = state.forkMessages,
            onPick = { entryId -> session.forkFrom(entryId) },
            onDismiss = { onSheet(null) },
        )

        ChatSheet.Rename -> RenameSessionDialog(
            initial = state.meta.sessionName,
            onConfirm = { name ->
                session.renameSession(name)
                onSheet(null)
            },
            onDismiss = { onSheet(null) },
        )

        ChatSheet.SystemPrompt -> ActiveSystemPromptSheet(
            session = session,
            onEditPrompt = { session.requestNav(NavRequest.SettingsFocus("hub:prompts")) },
            onDismiss = { onSheet(null) },
        )
        ChatSheet.Changelog -> ChangelogSheet(
            session = session,
            onDismiss = { onSheet(null) },
        )
        ChatSheet.Logout -> LogoutPickerSheet(
            session = session,
            onManageProviders = { session.requestNav(NavRequest.SettingsFocus("hub:models")) },
            onDismiss = { onSheet(null) },
        )

        null -> Unit
    }
}

/** `get_last_assistant_text`, then the system clipboard; this is pi's `/copy`. */
internal fun copyLastAssistant(session: PiSessionViewModel, context: Context) {
    session.copyLastAssistantText { text ->
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("pi", text))
    }
}

