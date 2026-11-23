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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pi.host.ExtPanel
import app.pi.i18n.tr
import app.pi.protocol.PiImage
import app.pi.ui.PiSessionViewModel
import app.pi.ui.chat.ArgumentContext
import app.pi.ui.chat.ArgumentPalette
import app.pi.ui.chat.BashPanel
import app.pi.ui.chat.ExtensionAutocompletePalette
import app.pi.ui.chat.MentionPalette
import app.pi.ui.chat.PiFileMentions
import app.pi.ui.chat.PiSlashCommand
import app.pi.ui.chat.SessionExportDelivery
import app.pi.ui.chat.SlashPalette
import app.pi.ui.chat.mergeRestoredQueue
import app.pi.ui.extension.ExtensionWidgetStack
import app.pi.ui.extension.ExtensionWorkingStrip
import app.pi.ui.extension.PLACEMENT_BELOW
import app.pi.ui.theme.PiSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 底部工作台里输入框上方的持久状态区（对齐官方 `interactive-mode.js` 容器顺序）：
 *  - `pendingMessagesContainer`：压缩中止 / 队列 / 导出交付 / Bash 面板 / 已选附件；
 *  - `statusContainer`：流式工作进度条（`setWorkingMessage` / `setWorkingIndicator`，仅流式运行中显示）；
 *  - `widgetContainerAbove`：`setWidget`（默认 `aboveEditor`）直接紧凑显示，进度条/Todo/调试组件第一时间可见，
 *    支持每张卡片一键「收纳」进角落芯片，且软键盘弹起时直接平滑让位（绝不再变形成跳动药丸条）。
 *  - 常驻状态（`setStatus`）与顶/底栏统一收在输入框角落芯片与扩展中心，零占高。
 */
@Composable
internal fun ChatDockStatusAndWidgets(
    state: PiSessionViewModel.UiState,
    session: PiSessionViewModel,
    draft: String,
    onDraft: (String) -> Unit,
    attachments: List<PiImage>,
    onAttachments: (List<PiImage>) -> Unit,
    imeVisible: Boolean,
    hiddenWidgetKeys: Set<String>,
    onHideWidget: (String) -> Unit,
    onInteractWidget: (ExtPanel) -> Unit,
) {
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    val aboveWidgets = remember(state.ext.widgets, hiddenWidgetKeys) {
        state.ext.widgets.filter { it.placement != PLACEMENT_BELOW && it.key !in hiddenWidgetKeys }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        androidx.compose.animation.AnimatedVisibility(
            visible = state.meta.compacting,
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(170)) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(140)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
        ) {
            StatusActionRow(
                text = tr("正在压缩上下文…", "Compacting context…"),
                actionLabel = tr("中止", "Abort"),
                onAction = { session.abortCompaction() },
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = state.queueSteering > 0 || state.queueFollowUp > 0,
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(170)) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(140)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
        ) {
            QueueRow(
                steering = state.queueSteering,
                followUp = state.queueFollowUp,
                steeringTexts = state.queueSteeringTexts,
                followUpTexts = state.queueFollowUpTexts,
                onRestore = {
                    session.restoreQueue { restored -> onDraft(mergeRestoredQueue(restored, draft)) }
                },
            )
        }

        val exported = state.exported
        androidx.compose.animation.AnimatedVisibility(
            visible = exported != null,
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(170)) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(140)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
        ) {
            exported?.let { exp ->
                ExportDeliveryRow(
                    exported = exp,
                    onSave = {
                        exportScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                SessionExportDelivery.saveToDownloads(context, exp)
                            }
                            session.notifyUser(result.sentence, warning = result.warning)
                        }
                    },
                    onShare = {
                        exportScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                SessionExportDelivery.share(context, exp)
                            }
                            session.notifyUser(result.sentence, warning = result.warning)
                        }
                    },
                    onDismiss = { session.dismissExport() },
                )
            }
        }

        // 流式工作指示器（官方 statusContainer，仅 AI 运行中显示）
        ExtensionWorkingStrip(working = state.ext.working, streaming = state.streaming)

        // 0dp 列宽探测常驻（即使软键盘打开或组件为空，也把真实列宽同步给宿主）
        ExtensionWidgetStack(
            widgets = emptyList(),
            onColumns = session::setPanelColumns,
        )

        // 官方 widgetContainerAbove：默认直接显示（进度条/Todo/调试器第一时间可见），软键盘弹起时平滑让位
        androidx.compose.animation.AnimatedVisibility(
            visible = !imeVisible && aboveWidgets.isNotEmpty(),
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(150)) +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(130)),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(130)) +
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(110)),
        ) {
            ExtensionWidgetStack(
                widgets = aboveWidgets,
                onInteract = onInteractWidget,
                onHide = { panel -> onHideWidget(panel.key) },
                maxStackHeight = 132.dp,
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = state.bash != null,
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(170)) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(140)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
        ) {
            state.bash?.let { bashRun ->
                BashPanel(
                    run = bashRun,
                    onAbort = { session.abortBash() },
                    onDismiss = { session.dismissBash() },
                    modifier = Modifier.padding(horizontal = PiSpacing.composerPageMargin, vertical = 4.dp),
                )
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = attachments.isNotEmpty(),
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(160)) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(140)),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(130)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(110)),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
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
}

/**
 * 瞬时自动补全浮层（`/` 命令面板、参数面板、`@` 文件提及、扩展补全）：
 * 悬浮在聊天流底部正上方，不推挤列表高度、不引发滚动跳动。
 */
@Composable
internal fun ChatTransientPalettes(
    state: PiSessionViewModel.UiState,
    session: PiSessionViewModel,
    draft: String,
    onDraft: (String) -> Unit,
    mentionPrefix: String?,
    onPickCommand: (PiSlashCommand, String) -> Unit,
    argContext: ArgumentContext?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val paletteQuery = paletteQueryOf(draft)
        if (paletteQuery != null) {
            val paletteCommands = remember(state.commands, state.commandsLoaded) {
                if (state.commandsLoaded || state.commands.isNotEmpty()) state.commands
                else app.pi.ui.chat.builtinRows(emptyList())
            }
            SlashPalette(
                commands = paletteCommands,
                query = paletteQuery,
                onPick = { command ->
                    val shouldPrefill = when (command.source) {
                        app.pi.ui.chat.PiCommandSource.Builtin ->
                            app.pi.ui.chat.CLIENT_BUILTINS[command.name]?.prefillOnTap == true
                        else -> command.argumentHint != null || command.completes
                    }
                    if (shouldPrefill) {
                        onDraft("${command.invocation} ")
                    } else {
                        onDraft("")
                        onPickCommand(command, "")
                    }
                },
                modifier = Modifier.padding(horizontal = PiSpacing.composerPageMargin, vertical = 4.dp),
            )
        }

        if (argContext != null) {
            val answer = state.argCompletions?.takeIf {
                it.command == argContext.command.name && it.prefix == argContext.prefix
            }
            ArgumentPalette(
                command = argContext.command,
                prefix = argContext.prefix,
                completions = answer?.items.orEmpty(),
                onPick = { item ->
                    // 内建单参数命令（/model /thinking /login）点选补全后填齐可直接发送，不强塞多余尾空格。
                    val suffix = if (argContext.command.source == app.pi.ui.chat.PiCommandSource.Builtin) "" else " "
                    onDraft("/${argContext.command.name} ${item.value}$suffix")
                },
                modifier = Modifier.padding(horizontal = PiSpacing.composerPageMargin, vertical = 4.dp),
            )
        }

        val mentions = state.mentions
        if (mentionPrefix != null && mentions != null && mentions.query == mentionPrefix && mentions.items.isNotEmpty()) {
            val prefix = mentionPrefix
            MentionPalette(
                candidates = mentions.items,
                onPick = { item -> onDraft(PiFileMentions.apply(draft, prefix, item)) },
                modifier = Modifier.padding(horizontal = PiSpacing.composerPageMargin, vertical = 4.dp),
            )
        }

        val extComp = state.extCompletions
        if (
            paletteQuery == null &&
            argContext == null &&
            mentionPrefix == null &&
            extComp != null &&
            extComp.draft == draft &&
            extComp.items.isNotEmpty()
        ) {
            ExtensionAutocompletePalette(
                items = extComp.items,
                onPick = { item ->
                    session.applyExtensionCompletion(
                        draft = draft,
                        item = item,
                        prefix = extComp.prefix,
                        onApplied = onDraft,
                    )
                },
                modifier = Modifier.padding(horizontal = PiSpacing.composerPageMargin, vertical = 4.dp),
            )
        }
    }
}
