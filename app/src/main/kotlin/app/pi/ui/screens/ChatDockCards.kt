package app.pi.ui.screens

import androidx.compose.animation.togetherWith
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
import app.pi.ui.extension.ExtensionDockSummaryBar
import app.pi.ui.extension.ExtensionStatusStrip
import app.pi.ui.extension.ExtensionWidgetStack
import app.pi.ui.extension.ExtensionWorkingStrip
import app.pi.ui.extension.PLACEMENT_BELOW
import app.pi.ui.theme.PiSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 底部工作台里输入框上方的持久状态区：
 *  - 压缩中止 / 队列 / 导出交付 / Bash 面板 / 已选附件；
 *  - 键盘感知的扩展状态与组件区：软键盘弹起或用户主动收起时自动折叠为紧凑单行摘要条，
 *    彻底杜绝扩展组件堆积后挤占聊天视口与输入区。
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
    widgetsCollapsedByUser: Boolean,
    onToggleWidgetsCollapse: () -> Unit,
    onOpenWidgetsInspector: () -> Unit,
    onInteractWidget: (ExtPanel) -> Unit,
) {
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()

    val aboveWidgets = remember(state.ext.widgets) {
        state.ext.widgets.filter { it.placement != PLACEMENT_BELOW }
    }
    val belowWidgets = remember(state.ext.widgets) {
        state.ext.widgets.filter { it.placement == PLACEMENT_BELOW }
    }
    val totalWidgetCount = state.ext.widgets.size
    val hasChrome = state.ext.header != null || state.ext.footer != null
    val hasAnyExtDockContent = totalWidgetCount > 0 ||
        state.ext.statuses.isNotEmpty() ||
        state.ext.editorTakeover ||
        hasChrome ||
        (state.streaming && state.ext.working.visible && !state.ext.working.message.isNullOrBlank())
    val firstInteractive = remember(state.ext.widgets, state.ext.header, state.ext.footer) {
        (state.ext.widgets + listOfNotNull(state.ext.header, state.ext.footer))
            .firstOrNull { it.interactive && it.surfaceId != null }
    }
    // 软键盘弹出时或用户手动折叠时，自动用紧凑单行摘要条替代大块扩展组件，保证聊天和输入始终通畅
    val compactExtMode = imeVisible || widgetsCollapsedByUser

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

        // 始终保留列宽探测（即使收起也把最新列数同步给宿主）
        if (hasAnyExtDockContent) {
            if (compactExtMode) {
                ExtensionWidgetStack(
                    widgets = emptyList(),
                    onColumns = session::setPanelColumns,
                )
            }
            androidx.compose.animation.AnimatedContent(
                targetState = compactExtMode,
                transitionSpec = {
                    (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(140)))
                        .togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(110)))
                },
                label = "ext-dock-mode",
            ) { compact ->
                Column(Modifier.fillMaxWidth()) {
                    if (compact) {
                        ExtensionDockSummaryBar(
                            working = state.ext.working,
                            streaming = state.streaming,
                            statusCount = state.ext.statuses.size,
                            widgetCount = totalWidgetCount,
                            hasChrome = hasChrome,
                            interactivePanel = firstInteractive,
                            collapsedByUser = widgetsCollapsedByUser,
                            imeVisible = imeVisible,
                            onToggleCollapse = onToggleWidgetsCollapse,
                            onOpenInspector = onOpenWidgetsInspector,
                            onInteract = onInteractWidget,
                        )
                    } else {
                        if (totalWidgetCount >= 2 || (totalWidgetCount >= 1 && (belowWidgets.isNotEmpty() || state.ext.footer != null))) {
                            ExtensionDockSummaryBar(
                                working = state.ext.working,
                                streaming = state.streaming,
                                statusCount = state.ext.statuses.size,
                                widgetCount = totalWidgetCount,
                                hasChrome = hasChrome,
                                interactivePanel = null,
                                collapsedByUser = false,
                                imeVisible = false,
                                onToggleCollapse = onToggleWidgetsCollapse,
                                onOpenInspector = onOpenWidgetsInspector,
                                onInteract = onInteractWidget,
                            )
                        }
                        ExtensionWorkingStrip(working = state.ext.working, streaming = state.streaming)
                        ExtensionStatusStrip(statuses = state.ext.statuses, editorTakeover = state.ext.editorTakeover)
                        ExtensionWidgetStack(
                            widgets = aboveWidgets,
                            onColumns = session::setPanelColumns,
                            onInteract = onInteractWidget,
                            maxStackHeight = 132.dp,
                        )
                    }
                }
            }
        } else {
            ExtensionWidgetStack(
                widgets = emptyList(),
                onColumns = session::setPanelColumns,
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
        if (paletteQuery != null && state.commandsLoaded) {
            SlashPalette(
                commands = state.commands,
                query = paletteQuery,
                onPick = { command ->
                    if (command.argumentHint != null || command.completes) {
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
                onPick = { item -> onDraft("/${argContext.command.name} ${item.value} ") },
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
                    val replaced = if (extComp.prefix.isNotEmpty() && draft.endsWith(extComp.prefix)) {
                        draft.removeSuffix(extComp.prefix) + item.value
                    } else {
                        draft + item.value
                    }
                    onDraft(replaced)
                    session.dismissExtensionAutocomplete()
                },
                modifier = Modifier.padding(horizontal = PiSpacing.composerPageMargin, vertical = 4.dp),
            )
        }
    }
}
