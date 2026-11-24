package app.pi.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.i18n.tr
import app.pi.ui.components.EffectiveKind
import app.pi.ui.components.PiAutoFocus
import app.pi.ui.components.PiDivider
import app.pi.ui.components.PiIconSquareButton
import app.pi.ui.components.PiPrimaryButton
import app.pi.ui.components.PiSecondaryButton
import app.pi.ui.components.PiSheet
import app.pi.ui.components.PiSheetActionBar
import app.pi.ui.components.PiSheetChromeMetrics
import app.pi.ui.components.PiSlider
import app.pi.ui.components.PiTextAction
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.serialization.json.JsonPrimitive

/**
 * Level-2 Studio editors for the settings stack (zero stock M3 buttons/sliders).
 */
@Composable
fun PiEffectiveDialog(
    kind: EffectiveKind,
    settingTitle: String,
    onDismiss: () -> Unit,
    onRunAction: (() -> Unit)?,
) {
    if (kind == EffectiveKind.Immediate) return
    val (label, explanation, actionLabel) = when (kind) {
        EffectiveKind.Reload -> Triple(
            tr("需要重载", "Reload required"),
            tr("改动已保存，重载后生效。", "Changes are saved and take effect after reload."),
            tr("立即重载", "Reload now"),
        )

        EffectiveKind.NewSession -> Triple(
            tr("需要新会话", "New session required"),
            tr("当前会话不会改变，开一个新会话之后生效。", "Current session is unchanged; takes effect in a new session."),
            tr("新建会话", "New session"),
        )

        EffectiveKind.RestartEngine -> Triple(
            tr("需要重启引擎", "Engine restart required"),
            tr(
                "改动已保存，重启引擎后生效。重启会终止正在进行的回合，已写入磁盘的会话不会丢失。",
                "Changes are saved and take effect after restarting the engine. Active turns will be interrupted, but saved sessions will not be lost.",
            ),
            tr("重启引擎", "Restart engine"),
        )

        EffectiveKind.AutoRestartEngine -> Triple(
            tr("自动重启引擎", "Auto-restarts engine"),
            tr(
                "改动已保存，并且这个开关会自己重启引擎来让它生效——不用你再动手。" +
                    "探针没通过、或重启被正在运行的回合拒绝时，行上会写明发生了什么。",
                "Changes are saved and automatically restart the engine to apply. If a probe fails or an active turn blocks the restart, the status row will explain why.",
            ),
            tr("知道了", "OK"),
        )

        EffectiveKind.RestartApp -> Triple(
            tr("需要重启 App", "App restart required"),
            tr(
                "这个值在 App 启动时读取，必须先结束 App 再启动。会话与文件不会受影响。",
                "This setting is read at app launch. Close and reopen the app to apply; sessions and files are unaffected.",
            ),
            tr("重启 App", "Restart app"),
        )

        EffectiveKind.Immediate -> return
    }
    PiSettingsDialog(
        onDismissRequest = onDismiss,
        title = settingTitle,
        sub = label,
        body = tr("「$settingTitle」$explanation", "\"$settingTitle\": $explanation"),
        confirmationLabel = if (onRunAction != null) actionLabel else tr("知道了", "OK"),
        onConfirm = {
            onRunAction?.invoke()
            onDismiss()
        },
        dismissalLabel = if (onRunAction != null) tr("稍后", "Later") else null,
        onDismissButton = onDismiss,
    )
}

@Composable
fun PiOptionPickerSheet(
    title: String,
    description: String,
    options: List<PiOption>,
    currentWire: String,
    allowCustom: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    searchHint: String? = null,
    warning: String? = null,
    customCheck: ((String) -> String?)? = null,
    clearLabel: String? = null,
    onClear: (() -> Unit)? = null,
    grouped: Boolean = false,
) {
    var custom by remember { mutableStateOf("") }
    var customOpen by remember { mutableStateOf(options.isEmpty()) }
    var query by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }
    var refusal by remember { mutableStateOf<String?>(null) }
    val visible = remember(options, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            options
        } else {
            options.filter { option ->
                option.wire.contains(needle, ignoreCase = true) || option.label.contains(needle, ignoreCase = true)
            }
        }
    }
    PiSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSettingsMetrics.sheetHeadTop,
                ),
        ) {
            Text(title, style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(PiSpacing.small))
            Text(description, style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            warning?.let { text ->
                Spacer(Modifier.height(PiSpacing.small))
                Text(text, style = PiTheme.text.meta, color = PiTheme.palette.warning)
            }
            if (searchHint != null) {
                Spacer(Modifier.height(PiSpacing.small))
                PiEditorField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = searchHint,
                    height = PiNumberFieldHeight,
                )
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetHeadBottom))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = PiSettingsMetrics.sheetBodyMax)
                    .verticalScroll(rememberScrollState()),
            ) {
                var lastGroup: String? = null
                visible.forEach { option ->
                    val selected = option.wire == currentWire
                    val showGroups = grouped && query.isBlank() && option.label.contains(" / ")
                    if (showGroups) {
                        val group = option.label.substringBefore(" / ")
                        if (group != lastGroup) {
                            lastGroup = group
                            Text(
                                group,
                                modifier = Modifier.padding(
                                    start = PiSettingsMetrics.pageHorizontal,
                                    end = PiSettingsMetrics.pageHorizontal,
                                    top = PiSpacing.gutter,
                                    bottom = 2.dp,
                                ),
                                style = PiTheme.text.meta,
                                color = PiTheme.palette.muted,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) PiTheme.palette.selectedBg else Color.Transparent)
                            .clickable {
                                onPick(option.wire)
                                onDismiss()
                            }
                            .padding(
                                start = PiSettingsMetrics.pageHorizontal,
                                end = PiSettingsMetrics.pageHorizontal,
                                top = PiOptionRowPadding,
                                bottom = PiOptionRowPadding,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
                    ) {
                        Text(
                            if (selected) "✓" else "○",
                            style = PiTheme.text.monoSmall,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else PiTheme.palette.muted,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (showGroups) option.label.substringAfter(" / ") else option.label,
                                style = PiType.rowTitle,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (option.description != null) {
                                Text(
                                    option.description,
                                    modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                                    style = PiTheme.text.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (selected) {
                            PiSettingsBadge(label = tr("当前", "Current"), tone = PiTheme.palette.accent)
                        }
                    }
                }
                if (searchHint != null && visible.isEmpty()) {
                    Text(
                        tr("没有匹配的选项", "No matching options"),
                        modifier = Modifier.padding(
                            start = PiSettingsMetrics.pageHorizontal,
                            end = PiSettingsMetrics.pageHorizontal,
                            top = PiOptionRowPadding,
                            bottom = PiOptionRowPadding,
                        ),
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (allowCustom && !customOpen) {
                Spacer(Modifier.height(6.dp))
                PiTextAction(
                    label = tr("手动输入其它值", "Enter custom value"),
                    onClick = { customOpen = true },
                )
            } else if (allowCustom) {
                Spacer(Modifier.height(PiSpacing.unit))
                Row(verticalAlignment = Alignment.Bottom) {
                    PiEditorField(
                        value = custom,
                        onValueChange = {
                            custom = it
                            pending = null
                            refusal = null
                        },
                        modifier = Modifier.weight(1f),
                        label = tr("自定义值", "Custom value"),
                        height = PiNumberFieldHeight,
                    )
                    Spacer(Modifier.width(PiSpacing.inline))
                    PiPrimaryButton(
                        label = tr("使用", "Use"),
                        enabled = custom.isNotBlank(),
                        onClick = {
                            val trimmed = custom.trim()
                            if (trimmed.isEmpty()) return@PiPrimaryButton
                            val message = customCheck?.invoke(trimmed)
                            if (message == null) {
                                onPick(trimmed)
                                onDismiss()
                            } else {
                                pending = trimmed
                                refusal = message
                            }
                        },
                    )
                }
            } else {
                Spacer(Modifier.height(PiSpacing.unit))
            }
            val pendingWire = pending
            val refusalText = refusal
            if (pendingWire != null && refusalText != null) {
                Spacer(Modifier.height(PiSpacing.small))
                Text(refusalText, style = PiTheme.text.meta, color = PiTheme.palette.error)
                Spacer(Modifier.height(8.dp))
                PiSheetActionBar {
                    PiSecondaryButton(
                        label = tr("取消", "Cancel"),
                        onClick = {
                            pending = null
                            refusal = null
                        },
                    )
                    PiPrimaryButton(
                        label = tr("仍然使用", "Use anyway"),
                        onClick = {
                            pending = null
                            refusal = null
                            onPick(pendingWire)
                            onDismiss()
                        },
                    )
                }
            }
            if (clearLabel != null && onClear != null) {
                Spacer(Modifier.height(6.dp))
                PiSheetActionBar {
                    PiTextAction(
                        label = clearLabel,
                        tone = PiTheme.palette.muted,
                        onClick = {
                            onClear()
                            onDismiss()
                        },
                    )
                }
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
        }
    }
}

@Composable
fun PiNumberEditorSheet(
    setting: PiSetting,
    initial: Int?,
    onSet: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val low = setting.min ?: 0
    val start = initial ?: setting.defaultValue?.intValueOrNull() ?: low
    val high = setting.max ?: (start + 1000).coerceAtLeast(start + 1)
    val step = (setting.step ?: 1).coerceAtLeast(1)
    val range = low.toFloat()..high.toFloat()
    val rawSteps = (((high - low) / step) - 1).coerceAtLeast(0)
    val sliderSteps = if (rawSteps <= 24) rawSteps else 0

    var typed by remember(initial) { mutableStateOf(initial?.toString() ?: "") }
    var problem by remember(initial) { mutableStateOf<String?>(null) }
    val parsed = parseEditedInt(typed)
    val inRange = parsed == null || parsed in low..high
    val sliderValue = (parsed ?: start).coerceIn(low, high)
    val focusRequester = remember { FocusRequester() }
    PiAutoFocus(focusRequester)

    fun setWithinRange(next: Int) {
        typed = next.coerceIn(low, high).toString()
        problem = null
    }

    PiSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSettingsMetrics.sheetHeadTop,
                ),
        ) {
            Text(setting.title, style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(PiSpacing.small))
            Text(setting.description, style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(PiSettingsMetrics.sheetHeadBottom))
            val quickPresets = numberQuickPresetsFor(setting.key)
            if (quickPresets.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    quickPresets.forEach { (ms, label) ->
                        PiChoiceChip(
                            label = label,
                            selected = parsed == ms,
                            onClick = { setWithinRange(ms) },
                        )
                    }
                }
                Spacer(Modifier.height(PiSpacing.unit))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PiSliderLabelGap),
            ) {
                Text("$low", style = PiTheme.text.monoSmall, color = PiTheme.palette.muted)
                PiSlider(
                    value = sliderValue.toFloat(),
                    onValueChange = { raw -> setWithinRange(raw.toInt()) },
                    modifier = Modifier.weight(1f),
                    valueRange = range,
                    steps = sliderSteps,
                )
                Text("$high", style = PiTheme.text.monoSmall, color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.height(PiSettingsMetrics.rowGap))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PiIconSquareButton(
                    icon = Icons.Filled.Remove,
                    contentDescription = tr("减少", "Decrease"),
                    containerColor = PiSheetChromeMetrics.sheetControlBg,
                    borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                    tint = MaterialTheme.colorScheme.onSurface,
                    size = PiNumberFieldHeight,
                    onClick = { setWithinRange(sliderValue - step) },
                )
                PiEditorField(
                    value = typed,
                    onValueChange = { text ->
                        typed = text
                        problem = null
                    },
                    modifier = Modifier.weight(1f),
                    focusRequester = focusRequester,
                    height = PiNumberFieldHeight,
                    textStyle = PiTheme.text.mono.copy(fontSize = 17.sp, lineHeight = 22.sp),
                    trailing = {
                        if (setting.unit != null) {
                            Text(setting.unit, style = PiTheme.text.mono, color = PiTheme.palette.muted)
                        }
                    },
                )
                PiIconSquareButton(
                    icon = Icons.Filled.Add,
                    contentDescription = tr("增加", "Increase"),
                    containerColor = PiSheetChromeMetrics.sheetControlBg,
                    borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                    tint = MaterialTheme.colorScheme.onSurface,
                    size = PiNumberFieldHeight,
                    onClick = { setWithinRange(sliderValue + step) },
                )
            }
            Spacer(Modifier.height(PiSpacing.small))
            Text(
                problem ?: when {
                    typed.isBlank() -> {
                        val fallback = setting.defaultValue?.intValueOrNull()
                        if (fallback != null) {
                            val unitSuffix = setting.unit?.let { " $it" } ?: ""
                            tr(
                                "未设置，pi 用自带的 $fallback$unitSuffix。",
                                "Not set; pi uses its default of $fallback$unitSuffix.",
                            )
                        } else {
                            tr("未设置。留空保存就是把这个键删掉。", "Not set. Saving blank removes this key.")
                        }
                    }

                    !inRange -> tr(
                        "建议 $low 到 $high；保存会原样写入 ${parsed}，不会替你改。",
                        "Recommended range is $low to $high; saving writes $parsed verbatim.",
                    )
                    else -> tr("取值范围 $low 到 $high", "Range: $low to $high")
                },
                style = PiTheme.text.meta,
                color = if (problem != null) PiTheme.palette.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSpacing.inline))
            PiDivider()
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
            PiSheetActionBar {
                PiTextAction(
                    label = tr("恢复默认", "Reset default"),
                    tone = PiTheme.palette.muted,
                    onClick = {
                        onSet(null)
                        onDismiss()
                    },
                )
                PiSecondaryButton(label = tr("取消", "Cancel"), onClick = onDismiss)
                PiPrimaryButton(
                    label = tr("保存", "Save"),
                    onClick = {
                        val text = typed.trim()
                        if (text.isEmpty()) {
                            onSet(null)
                            onDismiss()
                            return@PiPrimaryButton
                        }
                        val next = parseEditedInt(text)
                        if (next == null) {
                            problem = tr(
                                "要一个整数：不接受小数、文字或超出整数范围的值。",
                                "Enter an integer: decimals, text, or out-of-range numbers are not accepted.",
                            )
                            return@PiPrimaryButton
                        }
                        when (val verdict = piValueVerdict(setting.key, JsonPrimitive(next))) {
                            is PiValueVerdict.Rejected -> problem = verdict.message
                            else -> {
                                onSet(next)
                                onDismiss()
                            }
                        }
                    },
                )
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
        }
    }
}

@Composable
fun PiTextEditorSheet(
    setting: PiSetting,
    initial: String,
    onSet: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    PiSheet(
        onDismiss = {
            if (!setting.readOnly && text != initial) onSet(text)
            onDismiss()
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSettingsMetrics.sheetHeadTop,
                ),
        ) {
            Text(setting.title, style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(PiSpacing.small))
            Text(setting.description, style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            setting.editorNote?.let { note ->
                Spacer(Modifier.height(PiSpacing.inline))
                PiInfoNote(note, horizontalInset = 0.dp)
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetHeadBottom))
            if (!setting.readOnly) PiAutoFocus(focusRequester)
            val multiline = setting.multiline
            PiEditorField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = setting.keyLabel ?: setting.key,
                singleLine = !multiline,
                maxLines = if (multiline) PiTextEditorMaxLines else 1,
                readOnly = setting.readOnly,
                enabled = !setting.readOnly,
                focusRequester = focusRequester,
                height = if (multiline) null else PiNumberFieldHeight,
                minHeight = if (multiline) PiTextFieldMinHeight else null,
                contentPadding = if (multiline) PiFieldPaddingBlock else PiFieldPaddingSingleLine,
            )
            Spacer(Modifier.height(PiSpacing.inline))
            Text(
                if (setting.readOnly) {
                    tr("这一项只读。", "This item is read-only.")
                } else {
                    tr("返回会自动保存；清空后保存 = 删除。", "Dismissing auto-saves; saving empty deletes the key.")
                },
                style = PiTheme.text.meta,
                color = PiTheme.palette.muted,
            )
            Spacer(Modifier.height(PiSpacing.inline))
            PiDivider()
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
            PiSheetActionBar {
                PiTextAction(
                    label = tr("清除", "Clear"),
                    tone = PiTheme.palette.muted,
                    enabled = !setting.readOnly,
                    onClick = {
                        onSet("")
                        onDismiss()
                    },
                )
                PiSecondaryButton(label = tr("取消", "Cancel"), onClick = onDismiss)
                PiPrimaryButton(
                    label = tr("保存", "Save"),
                    enabled = !setting.readOnly,
                    onClick = {
                        onSet(text)
                        onDismiss()
                    },
                )
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
        }
    }
}

@Composable
fun PiListEditorSheet(
    setting: PiSetting,
    initialEntries: List<String>,
    onSet: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    validate: (List<String>) -> String? = { null },
) {
    var entries by remember(initialEntries) {
        mutableStateOf(PiQuickAdd.effective(initialEntries, setting.presetBaseline, setting.presets))
    }
    var draft by remember { mutableStateOf("") }
    var problem by remember(initialEntries) { mutableStateOf<String?>(null) }

    PiSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSettingsMetrics.sheetHeadTop,
                ),
        ) {
            Text(setting.title, style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(PiSpacing.small))
            Text(setting.description, style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(PiSpacing.inline))
            if (setting.presets.isNotEmpty()) {
                Text(tr("快捷添加", "Quick add"), style = PiTheme.text.meta, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(PiSpacing.gutter))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    setting.presets.forEach { preset ->
                        val selected = entries.contains(preset)
                        PiChoiceChip(
                            label = preset,
                            selected = selected,
                            onClick = {
                                entries = if (selected) {
                                    PiQuickAdd.remove(entries, preset, setting.presetBaseline, setting.presets)
                                } else {
                                    PiQuickAdd.add(entries, preset, setting.presetBaseline, setting.presets)
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(PiSpacing.unit))
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = PiSettingsMetrics.sheetListMax)
                    .verticalScroll(rememberScrollState()),
            ) {
                entries.forEachIndexed { index, entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PiEditorField(
                            value = entry,
                            onValueChange = { changed ->
                                entries = entries.toMutableList().also { list -> list[index] = changed }
                            },
                            modifier = Modifier.weight(1f),
                            height = PiNumberFieldHeight,
                            textStyle = PiTheme.text.mono.copy(lineHeight = 22.sp),
                        )
                        PiIconSquareButton(
                            icon = Icons.Filled.Delete,
                            contentDescription = tr("删除这一项", "Remove item"),
                            tint = PiTheme.palette.muted,
                            onClick = { entries = entries.filterIndexed { position, _ -> position != index } },
                        )
                    }
                    Spacer(Modifier.height(PiSpacing.small))
                }
            }
            Spacer(Modifier.height(PiSpacing.inline))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PiEditorField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    label = tr("新增一项", "Add item"),
                    height = PiNumberFieldHeight,
                    textStyle = PiTheme.text.mono.copy(lineHeight = 22.sp),
                )
                PiIconSquareButton(
                    icon = Icons.Filled.Add,
                    contentDescription = tr("添加", "Add"),
                    tint = PiTheme.palette.accent,
                    containerColor = PiTheme.palette.accent.copy(alpha = 0.14f),
                    size = PiNumberFieldHeight,
                    enabled = draft.isNotBlank(),
                    onClick = {
                        val trimmed = draft.trim()
                        if (trimmed.isNotEmpty()) {
                            entries = entries + trimmed
                            draft = ""
                        }
                    },
                )
            }
            Spacer(Modifier.height(PiSpacing.inline))
            PiDivider()
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
            val refusal = problem
            if (refusal != null) {
                Text(refusal, style = PiTheme.text.meta, color = PiTheme.palette.error)
                Spacer(Modifier.height(PiSpacing.small))
            }
            val toSave = PiQuickAdd.persist(entries, setting.presetBaseline)
            PiSheetActionBar {
                PiTextAction(
                    label = tr("清空", "Clear"),
                    tone = PiTheme.palette.muted,
                    onClick = {
                        entries = setting.presetBaseline
                        problem = null
                    },
                )
                PiSecondaryButton(label = tr("取消", "Cancel"), onClick = onDismiss)
                PiPrimaryButton(
                    label = if (toSave.isEmpty()) tr("恢复默认", "Reset default") else tr("保存", "Save"),
                    onClick = {
                        val rejected = validate(toSave)
                        if (rejected == null) {
                            onSet(toSave)
                            onDismiss()
                        } else {
                            problem = rejected
                        }
                    },
                )
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
        }
    }
}

/**
 * Unified Studio text input field used across settings & dialogs.
 */
@Composable
internal fun PiEditorField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    focusRequester: FocusRequester? = null,
    height: Dp? = null,
    minHeight: Dp? = null,
    container: Color = PiSheetChromeMetrics.sheetCardBg,
    idleBorder: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
    textStyle: TextStyle = PiTheme.text.mono,
    contentPadding: PaddingValues = PiFieldPaddingSingleLine,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val effectiveFocus = focusRequester ?: remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val effectiveMinHeight = when {
        height != null -> null
        minHeight != null -> minHeight
        singleLine -> 44.dp
        else -> 108.dp
    }
    val effectivePadding = if (!singleLine && contentPadding == PiFieldPaddingSingleLine) {
        PiFieldPaddingBlock
    } else if (singleLine && height == null && contentPadding == PiFieldPaddingSingleLine) {
        PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    } else {
        contentPadding
    }
    val innerMinHeight = when {
        singleLine -> null
        effectiveMinHeight != null -> (effectiveMinHeight - 20.dp).coerceAtLeast(24.dp)
        height != null -> (height - 20.dp).coerceAtLeast(24.dp)
        else -> null
    }
    Column(modifier) {
        if (label != null) {
            Text(
                label,
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(PiFieldLabelGap))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (height != null) Modifier.height(height) else Modifier)
                .then(if (effectiveMinHeight != null) Modifier.heightIn(min = effectiveMinHeight) else Modifier)
                .clip(PiFieldShape)
                .background(container)
                .border(
                    if (focused) 1.dp else PiSpacing.hairlineThin,
                    if (focused) PiTheme.palette.borderAccent else idleBorder,
                    PiFieldShape,
                )
                .clickable(
                    enabled = enabled && !readOnly,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    runCatching { effectiveFocus.requestFocus() }
                    keyboard?.show()
                }
                .padding(effectivePadding),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
        ) {
            if (leading != null) leading()
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .then(if (innerMinHeight != null) Modifier.heightIn(min = innerMinHeight) else Modifier)
                    .focusRequester(effectiveFocus),
                enabled = enabled,
                readOnly = readOnly,
                singleLine = singleLine,
                maxLines = maxLines,
                visualTransformation = visualTransformation,
                textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(PiTheme.palette.accent),
                interactionSource = interaction,
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (innerMinHeight != null) Modifier.heightIn(min = innerMinHeight) else Modifier),
                        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                    ) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                placeholder,
                                style = textStyle,
                                color = PiTheme.palette.muted,
                                maxLines = if (singleLine) 1 else 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (trailing != null) trailing()
        }
    }
}

private val PiFieldShape = RoundedCornerShape(10.dp)
private val PiFieldPaddingSingleLine = PaddingValues(horizontal = 12.dp)
internal val PiFieldPaddingBlock = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
internal val PiNumberFieldHeight = 44.dp
internal val PiTextFieldMinHeight = 80.dp
private const val PiTextEditorMaxLines = 12
private val PiFieldLabelGap = 4.dp
private val PiSliderLabelGap = 10.dp
internal val PiOptionRowPadding = 11.dp

private fun numberQuickPresetsFor(key: String): List<Pair<Int, String>> = when (key) {
    "httpIdleTimeoutMs" -> listOf(
        30_000 to tr("30秒", "30s"),
        60_000 to tr("1分钟", "1 min"),
        120_000 to tr("2分钟", "2 min"),
        300_000 to tr("5分钟", "5 min"),
        0 to tr("不超时", "Disabled"),
    )
    "websocketConnectTimeoutMs" -> listOf(
        5_000 to tr("5秒", "5s"),
        15_000 to tr("15秒", "15s"),
        30_000 to tr("30秒", "30s"),
        60_000 to tr("60秒", "60s"),
        0 to tr("不限时", "Disabled"),
    )
    else -> emptyList()
}

@Composable
internal fun PiChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = PiTheme.palette
    Surface(
        modifier = Modifier
            .padding(end = PiSpacing.inline)
            .height(28.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) palette.accent.copy(alpha = 0.14f) else PiSheetChromeMetrics.sheetCardBg,
        border = BorderStroke(
            if (selected) 1.dp else PiSpacing.hairlineThin,
            if (selected) palette.accent.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = PiTheme.text.monoSmall,
                color = if (selected) palette.accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
