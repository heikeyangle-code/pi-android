package app.pi.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pi.i18n.PiLocale
import app.pi.i18n.tr
import app.pi.ui.PiTopBar
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.serialization.json.JsonPrimitive

/**
 * 现代「对话与消息」专页（100% 对齐 Pi 1.6.0 `AgentSession` / `SettingsManager`）：
 * - 消息队列：`steeringMode`、`followUpMode` 全部改为卡片内 1-tap 分段切换，实时同步至当前会话；
 * - 会话与树：`treeFilterMode` 内联 1-tap 快捷选择 + `app.sessions.resumeLast` 启动续接开关；
 * - 图片处理：`images.autoResize` / `images.blockImages` 开关，屏蔽图片时给出温和醒目提示防止遗忘。
 */
@Composable
internal fun ChatMessagesScreen(
    store: PiSettingsStore,
    freshness: Int,
    contentPadding: PaddingValues,
    onSettingWritten: (String) -> Unit,
    onBack: () -> Unit,
    highlightKey: String? = null,
) {
    val palette = PiTheme.palette
    val listState = rememberLazyListState()
    val scrolled by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    var explaining by remember { mutableStateOf<PiSetting?>(null) }

    val byKey = remember(freshness, PiLocale.isZh) { PiSettingsCatalog.byKey }
    val steeringSetting = byKey["steeringMode"]
    val followUpSetting = byKey["followUpMode"]
    val treeFilterSetting = byKey["treeFilterMode"]
    val resumeLastSetting = byKey["app.sessions.resumeLast"]
    val autoResizeSetting = byKey["images.autoResize"]
    val blockImagesSetting = byKey["images.blockImages"]

    val steeringMode = remember(store, freshness) { steeringSetting?.textIn(store, "one-at-a-time") ?: "one-at-a-time" }
    val followUpMode = remember(store, freshness) { followUpSetting?.textIn(store, "one-at-a-time") ?: "one-at-a-time" }
    val treeFilterMode = remember(store, freshness) { treeFilterSetting?.textIn(store, "default") ?: "default" }
    val resumeLast = remember(store, freshness) { resumeLastSetting?.boolIn(store, true) ?: true }
    val autoResize = remember(store, freshness) { autoResizeSetting?.boolIn(store, true) ?: true }
    val blockImages = remember(store, freshness) { blockImagesSetting?.boolIn(store, false) ?: false }

    LaunchedEffect(highlightKey) {
        val targetIndex = when (highlightKey) {
            "steeringMode", "followUpMode" -> 0
            "treeFilterMode", "app.sessions.resumeLast" -> 1
            "images.autoResize", "images.blockImages" -> 2
            else -> -1
        }
        if (targetIndex >= 0) listState.animateScrollToItem(targetIndex)
    }

    fun writeString(key: String, value: String) {
        store.write(key, JsonPrimitive(value))
        onSettingWritten(key)
    }

    fun writeBool(key: String, value: Boolean) {
        store.write(key, JsonPrimitive(value))
        onSettingWritten(key)
    }

    val deliveryChoices = remember(PiLocale.isZh) {
        listOf(
            "one-at-a-time" to tr("逐条送达", "One at a time"),
            "all" to tr("全部一起", "All at once"),
        )
    }
    val treeFilterChoices = remember(PiLocale.isZh) {
        listOf(
            "default" to tr("默认", "Default"),
            "no-tools" to tr("无工具", "No tools"),
            "user-only" to tr("仅用户", "User only"),
            "labeled-only" to tr("仅书签", "Labeled"),
            "all" to tr("全部", "All"),
        )
    }

    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(
            title = tr("对话与消息", "Chat & Messages"),
            onBack = onBack,
            scrolled = scrolled,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = contentPadding.calculateBottomPadding() + PiSpacing.scrollBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupGap),
        ) {
            // 1. 消息队列送达方式（实时同步 AgentSession）
            item(key = "queue") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupHeaderGap)) {
                    PiSettingsSectionHeader(tr("消息队列", "Message Queue"))
                    PiSettingsCard {
                        PiInlineSegmentRow(
                            title = steeringSetting?.title ?: tr("引导模式", "Steering Mode"),
                            description = tr(
                                "回复流式输出途中继续发消息时，逐条插入还是合并一起送达。",
                                "When sending messages mid-stream, deliver one at a time or all together.",
                            ),
                            options = deliveryChoices,
                            selected = steeringMode,
                            onSelect = { writeString("steeringMode", it) },
                            highlighted = highlightKey == "steeringMode",
                        )
                        PiSettingsHairline()
                        PiInlineSegmentRow(
                            title = followUpSetting?.title ?: tr("后续模式", "Follow-up Mode"),
                            description = tr(
                                "排队等待本轮结束的后续消息，按逐条还是合并方式送达。",
                                "For messages queued until the turn ends, deliver one at a time or all at once.",
                            ),
                            options = deliveryChoices,
                            selected = followUpMode,
                            onSelect = { writeString("followUpMode", it) },
                            highlighted = highlightKey == "followUpMode",
                        )
                    }
                }
            }

            // 2. 会话树与启动行为
            item(key = "session-tree") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupHeaderGap)) {
                    PiSettingsSectionHeader(tr("会话与分支树", "Sessions & Tree"))
                    PiSettingsCard {
                        val treeValueLabel = treeFilterChoices.firstOrNull { it.first == treeFilterMode }?.second ?: treeFilterMode
                        PiInlinePresetRow(
                            title = treeFilterSetting?.title ?: tr("会话树默认过滤", "Default Tree Filter"),
                            description = tr(
                                "打开 /tree 时默认显示的节点范围，在树界面内仍可随时切换。",
                                "Initial node filter when opening /tree; can be switched anytime inside the tree.",
                            ),
                            valueText = treeValueLabel,
                            presets = treeFilterChoices,
                            selected = treeFilterMode,
                            onSelect = { writeString("treeFilterMode", it) },
                            highlighted = highlightKey == "treeFilterMode",
                        )
                        if (resumeLastSetting != null) {
                            PiSettingsHairline()
                            PiSettingRow(
                                setting = resumeLastSetting,
                                valueText = resumeLastSetting.display(resumeLastSetting.current(store)),
                                checked = resumeLast,
                                highlighted = highlightKey == "app.sessions.resumeLast",
                                onToggle = { writeBool(resumeLastSetting.key, it) },
                                onOpen = {},
                                onExplainEffect = { explaining = resumeLastSetting },
                            )
                        }
                    }
                }
            }

            // 3. 图片处理
            item(key = "images") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupHeaderGap)) {
                    PiSettingsSectionHeader(tr("图片处理", "Images"))
                    PiSettingsCard {
                        if (autoResizeSetting != null) {
                            PiSettingRow(
                                setting = autoResizeSetting,
                                valueText = autoResizeSetting.display(autoResizeSetting.current(store)),
                                checked = autoResize,
                                highlighted = highlightKey == "images.autoResize",
                                enabled = !blockImages,
                                onToggle = { writeBool(autoResizeSetting.key, it) },
                                onOpen = {},
                                onExplainEffect = { explaining = autoResizeSetting },
                            )
                        }
                        if (blockImagesSetting != null) {
                            PiSettingsHairline()
                            PiSettingRow(
                                setting = blockImagesSetting,
                                valueText = blockImagesSetting.display(blockImagesSetting.current(store)),
                                checked = blockImages,
                                highlighted = highlightKey == "images.blockImages",
                                onToggle = { writeBool(blockImagesSetting.key, it) },
                                onOpen = {},
                                onExplainEffect = { explaining = blockImagesSetting },
                            )
                        }
                    }
                    AnimatedVisibility(visible = blockImages) {
                        PiInfoNote(
                            text = tr(
                                "已开启屏蔽图片：发送消息或 read 工具读取的图片不会传给模型。",
                                "Block Images is on: attached images and images read by tools will not be sent to providers.",
                            ),
                            tone = palette.warning,
                        )
                    }
                }
            }
        }
    }

    explaining?.let { setting ->
        PiEffectiveDialog(
            kind = setting.effective,
            settingTitle = setting.title,
            onDismiss = { explaining = null },
            onRunAction = null,
        )
    }
}
