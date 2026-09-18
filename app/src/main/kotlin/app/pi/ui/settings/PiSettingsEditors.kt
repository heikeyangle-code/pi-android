package app.pi.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.pi.ui.components.EffectiveKind
import app.pi.ui.components.PiAutoFocus
import app.pi.ui.components.PiMixedLine
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiThemeEntry
import app.pi.ui.theme.PiThemeScope
import kotlinx.serialization.json.JsonPrimitive

/**
 * Level-2 editors for the settings stack (spec §6.1).
 *
 * Everything is a `ModalBottomSheet`, because on a phone a sheet is easier to
 * reach and dismiss than a system dropdown (spec §7.1). The one rule all four
 * editors obey: they hand back the value the user chose, and never normalise it.
 * In particular a list editor writes entries back verbatim, so `!pattern`,
 * `+path` and `-path` markers survive a round trip.
 */

/** Automatic theme mode stores one literal string, so parse it for editing only. */
internal fun splitAutoTheme(raw: String): Pair<String, String>? {
    val slash = raw.indexOf('/')
    if (slash <= 0) return null
    val lightTheme = raw.substring(0, slash).trim()
    val darkTheme = raw.substring(slash + 1).trim()
    if (lightTheme.isEmpty() || darkTheme.isEmpty()) return null
    return lightTheme to darkTheme
}

/**
 * What the effective badge means, plus the action that applies it (spec §6.5).
 * pi has four different timings; a badge without this explanation is just noise.
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
            "需要重载",
            "改动已保存，重载后生效。",
            "立即重载",
        )

        EffectiveKind.NewSession -> Triple(
            "需要新会话",
            "当前会话不会改变，开一个新会话之后生效。",
            "新建会话",
        )

        EffectiveKind.RestartEngine -> Triple(
            "需要重启引擎",
            "改动已保存，重启引擎后生效。重启会终止正在进行的回合，已写入磁盘的会话不会丢失。",
            "重启引擎",
        )

        EffectiveKind.RestartApp -> Triple(
            "需要重启 App",
            "这个值在 App 启动时读取，必须先结束 App 再启动。会话与文件不会受影响。",
            "重启 App",
        )

        EffectiveKind.Immediate -> return
    }
    // v2 的生效说明弹窗（`phone45`）：**标题是设置名**，副行才是生效徽标那个词，
    // 正文用「「设置名」…」讲清楚，动作是「稍后 / 立即重载」。原先这里把徽标词当标题、
    // 把设置名塞进正文，结构与稿子正好反过来。
    PiSettingsDialog(
        onDismissRequest = onDismiss,
        title = settingTitle,
        sub = label,
        body = "「$settingTitle」$explanation",
        confirmationLabel = if (onRunAction != null) actionLabel else "知道了",
        onConfirm = {
            onRunAction?.invoke()
            onDismiss()
        },
        dismissalLabel = if (onRunAction != null) "稍后" else null,
        onDismissButton = onDismiss,
    )
}

/** Enum picker (spec §6.2 ValueRow). One tap, then it closes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiOptionPickerSheet(
    title: String,
    description: String,
    options: List<PiOption>,
    currentWire: String,
    allowCustom: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var custom by remember { mutableStateOf("") }
    PiSettingsSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSettingsMetrics.sheetHeadTop,
                ),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(PiSpacing.small))
            Text(
                description,
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSettingsMetrics.sheetHeadBottom))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = PiSettingsMetrics.sheetBodyMax)
                    .verticalScroll(rememberScrollState()),
            ) {
                // v2 的选项行（`phone40` / HTML:2283-2293）：`padding:11px 14px`、
                // 前导 `✓/○`（等宽 12，选中用正文色、未选中 muted）、标签 14、选中行
                // 铺 `--selected-bg` + 右侧一枚「当前」徽标，**行间没有分隔线**。
                options.forEach { option ->
                    val selected = option.wire == currentWire
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (selected) PiTheme.palette.selectedBg else Color.Transparent,
                            )
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
                            color = if (selected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                PiTheme.palette.muted
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                option.label,
                                style = MaterialTheme.typography.bodyMedium,
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
                            PiSettingsBadge(label = "当前", tone = PiTheme.palette.accent)
                        }
                    }
                }
            }
            if (allowCustom) {
                Spacer(Modifier.height(PiSpacing.unit))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PiEditorField(
                        value = custom,
                        onValueChange = { custom = it },
                        modifier = Modifier.weight(1f),
                        label = "自定义值",
                        height = PiNumberFieldHeight,
                    )
                    Spacer(Modifier.width(PiSpacing.inline))
                    TextButton(
                        onClick = {
                            val trimmed = custom.trim()
                            if (trimmed.isNotEmpty()) {
                                onPick(trimmed)
                                onDismiss()
                            }
                        },
                    ) { Text("使用") }
                }
            } else {
                Spacer(Modifier.height(PiSpacing.unit))
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
        }
    }
}

/**
 * Number editor (spec §6.2 NumberRow): a slider for coarse movement, a text field
 * for an exact value, and a stepper for the small ranges such as the +/-2sp font
 * adjustment. "恢复默认" writes the registry default, which is often null.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    // Material draws one tick per step; a million-token range would draw
    // hundreds of them, so only quantise when the count stays readable.
    val rawSteps = (((high - low) / step) - 1).coerceAtLeast(0)
    val sliderSteps = if (rawSteps <= 24) rawSteps else 0

    // The field's text is the only source of truth: it starts as what the store holds — `""`
    // when the key is not set, which *is* the truth, with pi's built-in default shown as a
    // hint beside it — and every save parses that text. There used to be a second `Int` that
    // only moved when the text parsed, so typing `abc` (or a number too large for `Int`) and
    // tapping 保存 wrote the **previous** value with no message at all, and typing an
    // out-of-range number showed one value while saving a clamped one
    // (`docs/settings-audit-impl.md` §B6).
    var typed by remember(initial) { mutableStateOf(initial?.toString() ?: "") }
    var problem by remember(initial) { mutableStateOf<String?>(null) }
    val parsed = parseEditedInt(typed)
    val inRange = parsed == null || parsed in low..high
    val sliderValue = (parsed ?: start).coerceIn(low, high)
    // 同文本编辑器：`phone41` 的数字编辑器画的就是「框已聚焦」（1px borderAccent + 光标）。
    val focusRequester = remember { FocusRequester() }
    PiAutoFocus(focusRequester)

    /** The slider and ± are **range widgets**: they stay inside the row's bounds. Only the
     *  hand-typed value is exempt from clamping (it is reported, never rewritten). */
    fun setWithinRange(next: Int) {
        typed = next.coerceIn(low, high).toString()
        problem = null
    }

    PiSettingsSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSettingsMetrics.sheetHeadTop,
                ),
        ) {
            Text(
                setting.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(PiSpacing.small))
            Text(
                setting.description,
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSettingsMetrics.sheetHeadBottom))
            // v2 的数字编辑器（`phone41` / HTML:2297-2312）：两端读数夹着一条 2px 轨道，
            // 下面是 44 高的等宽框（单位在框内右端）。原来那行 headline 读数没有对应物，
            // 值本身就在框里，所以收掉了。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PiSliderLabelGap),
            ) {
                Text(
                    "$low",
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.muted,
                )
                Slider(
                    value = sliderValue.toFloat(),
                    onValueChange = { raw -> setWithinRange(raw.toInt()) },
                    modifier = Modifier.weight(1f),
                    valueRange = range,
                    steps = sliderSteps,
                    thumb = { PiSliderThumb() },
                    track = { state -> PiSliderTrack(state) },
                )
                Text(
                    "$high",
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(PiSettingsMetrics.rowGap))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { setWithinRange(sliderValue - step) }) { Text("−") }
                PiEditorField(
                    value = typed,
                    onValueChange = { text ->
                        typed = text
                        problem = null
                    },
                    modifier = Modifier.weight(1f),
                    focusRequester = focusRequester,
                    height = PiNumberFieldHeight,
                    // v2 的数字框是等宽 17（`mono` 角色是 13，所以在这里就地定尺寸）。
                    textStyle = PiTheme.text.mono.copy(fontSize = 17.sp, lineHeight = 22.sp),
                    // v2 的数字框上没有 label（值就在框里、两端已有范围读数），所以这里
                    // 不再挂「精确值」那一行。
                    trailing = {
                        if (setting.unit != null) {
                            Text(
                                setting.unit,
                                style = PiTheme.text.mono,
                                color = PiTheme.palette.muted,
                            )
                        }
                    },
                )
                TextButton(onClick = { setWithinRange(sliderValue + step) }) { Text("+") }
            }
            Spacer(Modifier.height(PiSpacing.small))
            // 读数行同时承担三件事：范围的建议值、pi 自带默认值（键没设时）、以及"这串字
            // 不能保存"的原因。越界**只提示不夹值**：pi 接受的值必须能写进去。
            Text(
                problem ?: when {
                    typed.isBlank() -> {
                        val fallback = setting.defaultValue?.intValueOrNull()
                        if (fallback != null) {
                            "未设置，pi 用自带的 $fallback${setting.unit?.let { " $it" } ?: ""}。"
                        } else {
                            "未设置。留空保存就是把这个键删掉。"
                        }
                    }

                    !inRange -> "建议 $low 到 $high；保存会原样写入 ${parsed}，不会替你改。"
                    else -> "取值范围 $low 到 $high"
                },
                style = PiTheme.text.meta,
                color = if (problem != null) {
                    PiTheme.palette.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            // v2 的 sheet 页脚：1px 上边（`borderMuted` 55%）+ `10px 14px 14px` 内边距。
            HorizontalDivider(
                thickness = PiSettingsMetrics.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        // "Restore the default" is a **deletion** (`PiSettingsStore.remove`), which
                        // is pi's own "unset": the file must not end up with `"key": null`, because
                        // pi's `parseTimeoutSetting` throws on `null` and `pi --mode rpc` then fails
                        // to start (`docs/settings-audit-impl.md` §B1).
                        onSet(null)
                        onDismiss()
                    },
                ) { Text("恢复默认") }
                TextButton(
                    onClick = {
                        val text = typed.trim()
                        // 「留空」= remove（pi 里的 `undefined`），不是写 0：0 在 pi 侧是一个**值**
                        // （`retry.provider.timeoutMs` 写 0 就是把超时设成 0）。
                        if (text.isEmpty()) {
                            onSet(null)
                            onDismiss()
                            return@TextButton
                        }
                        val next = parseEditedInt(text)
                        if (next == null) {
                            problem = "要一个整数：不接受小数、文字或超出整数范围的值。"
                            return@TextButton
                        }
                        when (val verdict = piValueVerdict(setting.key, JsonPrimitive(next))) {
                            is PiValueVerdict.Rejected -> problem = verdict.message
                            else -> {
                                onSet(next)
                                onDismiss()
                            }
                        }
                    },
                ) { Text("保存") }
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
        }
    }
}

/**
 * Text editor (spec §6.2 TextRow).
 *
 * One line or many is [PiSetting.multiline], never a reading of `depth`: `depth`
 * is the navigation property, and reading it here is what gave the several-
 * thousand-character 自定义系统提示 a one-line box whose layout only ever draws
 * the first line — the paste looked like it had only taken a few dozen
 * characters. A multi-line value gets a field that is bounded to
 * [PiTextEditorMaxLines] lines and **scrolls internally** from there (see that
 * constant), so the whole document is reachable without pushing 保存 / 清除 off
 * the sheet.
 *
 * The value itself is handed over verbatim in both directions: `initial` is the
 * stored string exactly as `PiSettingsStore.read` returned it, this screen only
 * replaces the state with what `onValueChange` gives back, and 保存 calls
 * [onSet] with that string unchanged — no `trim`, no line joining, no
 * truncation, so newlines, leading/trailing whitespace and Markdown structure
 * survive the round trip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiTextEditorSheet(
    setting: PiSetting,
    initial: String,
    onSet: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    // 光标落在这个框里并弹键盘：`phone42` 画的就是这个状态（框带 borderAccent 环、末尾有
    // 光标）。用户打开这一页就是为了改这一格，多一次点击没有意义。
    val focusRequester = remember { FocusRequester() }
    PiSettingsSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSettingsMetrics.sheetHeadTop,
                ),
        ) {
            Text(
                setting.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(PiSpacing.small))
            Text(
                setting.description,
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSettingsMetrics.sheetHeadBottom))
            // 只读行不抢焦点：那里没有可输入的东西，弹键盘等于骗人。
            if (!setting.readOnly) PiAutoFocus(focusRequester)
            // 多行态照 v2（HTML:2313-2321）：`minHeight:80`、圆角 9、`surf-low`、内
            // `10px 12px`、等宽 13。单行/多行由 `setting.multiline` 决定 —— 不再从
            // `depth` 读（见这个函数的 KDoc）：单行用 v2 的单行框高，多行用 v2 的
            // `minHeight:80`，并**封顶到 `PiTextEditorMaxLines` 行**，让框自己滚。
            val multiline = setting.multiline
            PiEditorField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = setting.key,
                singleLine = !multiline,
                maxLines = if (multiline) PiTextEditorMaxLines else 1,
                readOnly = setting.readOnly,
                enabled = !setting.readOnly,
                focusRequester = focusRequester,
                height = if (multiline) null else PiNumberFieldHeight,
                minHeight = if (multiline) PiTextFieldMinHeight else null,
                contentPadding = if (multiline) {
                    PiFieldPaddingBlock
                } else {
                    PiFieldPaddingSingleLine
                },
            )
            if (setting.readOnly) {
                Spacer(Modifier.height(PiSpacing.gutter))
                Text(
                    "这一项只读。",
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // v2 的 sheet 页脚：1px 上边（`borderMuted` 55%）+ `10px 14px 14px` 内边距。
            HorizontalDivider(
                thickness = PiSettingsMetrics.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        onSet("")
                        onDismiss()
                    },
                    enabled = !setting.readOnly,
                ) { Text("清除") }
                TextButton(
                    onClick = {
                        onSet(text)
                        onDismiss()
                    },
                    enabled = !setting.readOnly,
                ) { Text("保存") }
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
        }
    }
}

/**
 * List editor (spec §6.2 ListRow, "arrays keep their glob syntax").
 *
 * Array settings are edited as raw lines so `!pattern`, `+path` and `-path` are
 * preserved exactly; object settings (the per-model maps) are edited as
 * `key = value` lines, because a phone cannot render a nested JSON tree
 * usefully. Preset chips toggle membership for settings with a known universe,
 * such as the eight built-in tools.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiListEditorSheet(
    setting: PiSetting,
    initialEntries: List<String>,
    onSet: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    /**
     * Returns the sentence to show **instead of saving**, or null when the lines are
     * acceptable. Supplied by the host from `PiSetting.validateEntries`, which applies pi's
     * own rules for the object-valued rows: a `compaction.modelOverrides` entry that pi cannot
     * read makes pi throw on every turn's compaction check, and a line without `=` was silently
     * dropped by our own parser (`docs/settings-audit-impl.md` §B5).
     */
    validate: (List<String>) -> String? = { null },
) {
    var entries by remember(initialEntries) { mutableStateOf(initialEntries) }
    var draft by remember { mutableStateOf("") }
    var problem by remember(initialEntries) { mutableStateOf<String?>(null) }

    PiSettingsSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSettingsMetrics.sheetHeadTop,
                ),
        ) {
            Text(
                setting.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(PiSpacing.small))
            Text(
                setting.description,
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSpacing.inline))
            PiInfoNote(
                if (setting.container == PiValueContainer.Object) {
                    "每行一项，写成「键 = 值」，键按精确匹配不认通配符。值以 { 或 [ 开头时按 JSON 解析，" +
                        "例如逐模型压缩覆盖写成 model-id = {\"reserveTokens\": 400000}，其余按字符串/数字/布尔解析。"
                } else {
                    "每行一项，支持 glob 与排除标记：!pattern 排除、+path 强制包含、-path 强制排除。" +
                        "值以 { 或 [ 开头时按 JSON 解析，例如 packages 的对象形式 " +
                        "{\"source\": \"pi-skills\", \"autoload\": false}。"
                },
            )
            Spacer(Modifier.height(PiSpacing.inline))
            if (setting.presets.isNotEmpty()) {
                Text(
                    "快捷添加",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(PiSpacing.gutter))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    setting.presets.forEach { preset ->
                        val selected = entries.contains(preset)
                        // v2 的 chip：高 26、圆角 999、`padding:0 9px`、1px `borderMuted`
                        // 描边；选中的那一枚换成 `surfaceContainerHigh` 底（§2「chip」）。
                        Surface(
                            modifier = Modifier
                                .padding(end = PiSpacing.inline)
                                .height(PiSettingsMetrics.chipHeight)
                                .clickable {
                                    entries = if (selected) {
                                        entries.filter { it != preset }
                                    } else {
                                        entries + preset
                                    }
                                },
                            shape = PiShapes.badge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            border = BorderStroke(
                                PiSettingsMetrics.hairline,
                                MaterialTheme.colorScheme.outline,
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(
                                    horizontal = PiSettingsMetrics.chipPaddingHorizontal,
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    preset,
                                    style = PiTheme.text.monoSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
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
                    ) {
                        PiEditorField(
                            value = entry,
                            onValueChange = { changed ->
                                entries = entries.toMutableList().also { list -> list[index] = changed }
                            },
                            modifier = Modifier.weight(1f),
                            height = PiNumberFieldHeight,
                            // v2 的列表框是 `surf-highest` + `borderMuted`（HTML:2321-2325），
                            // 与其余几型的 `surf-low` 不同档；聚焦时仍换 `borderAccent`。
                            container = MaterialTheme.colorScheme.surfaceContainerHighest,
                            textStyle = PiTheme.text.mono.copy(lineHeight = 22.sp),
                        )
                        IconButton(
                            onClick = {
                                entries = entries.filterIndexed { position, _ -> position != index }
                            },
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "删除这一项",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(PiSpacing.small))
                }
            }
            Spacer(Modifier.height(PiSpacing.inline))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PiEditorField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    label = "新增一项",
                    height = PiNumberFieldHeight,
                    container = MaterialTheme.colorScheme.surfaceContainerHighest,
                    textStyle = PiTheme.text.mono.copy(lineHeight = 22.sp),
                )
                IconButton(
                    onClick = {
                        val trimmed = draft.trim()
                        if (trimmed.isNotEmpty()) {
                            entries = entries + trimmed
                            draft = ""
                        }
                    },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "添加")
                }
            }
            // v2 的 sheet 页脚：1px 上边（`borderMuted` 55%）+ `10px 14px 14px` 内边距。
            HorizontalDivider(
                thickness = PiSettingsMetrics.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
            // The refusal is shown where the decision is made, not as a toast that vanishes:
            // a value pi cannot read must not reach the file (`PiSetting.validateEntries`).
            val refusal = problem
            if (refusal != null) {
                Text(
                    refusal,
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.error,
                )
                Spacer(Modifier.height(PiSpacing.small))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        entries = emptyList()
                        problem = null
                    },
                ) { Text("清空") }
                TextButton(
                    onClick = {
                        val rejected = validate(entries)
                        if (rejected == null) {
                            onSet(entries)
                            onDismiss()
                        } else {
                            problem = rejected
                        }
                    },
                ) { Text("保存") }
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
        }
    }
}

/**
 * Theme picker.
 *
 * `theme` may hold a single name, or the literal `"lightTheme/darkTheme"` pair
 * that turns on automatic light/dark switching. That pair is ONE string in the
 * file, so this editor writes it back as one string: the two name fields only
 * compose the literal, they are never stored separately, and the raw field
 * shows exactly what will be written.
 *
 * [knownThemes] is every theme the app can actually load, not just the ones the
 * `themes` setting names: pi discovers the agent dir's `themes` directory and the
 * project's `.pi/themes` on its own (`resource-loader.ts:872-880`), so limiting
 * the list to the setting would hide the very theme the terminal is using.
 * [notes] are the caveats of the theme currently in effect — a value the app
 * could not honour is stated here instead of being ignored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiThemeEditorSheet(
    currentRaw: String,
    knownThemes: List<PiThemeEntry>,
    notes: List<String> = emptyList(),
    error: String? = null,
    onSet: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val auto = splitAutoTheme(currentRaw)
    var raw by remember(currentRaw) { mutableStateOf(currentRaw) }
    var lightTheme by remember(currentRaw) { mutableStateOf(auto?.first ?: "light") }
    var darkTheme by remember(currentRaw) { mutableStateOf(auto?.second ?: "dark") }
    val entries = buildList {
        add(PiThemeEntry("dark", null, PiThemeScope.Builtin))
        add(PiThemeEntry("light", null, PiThemeScope.Builtin))
        knownThemes.forEach { entry -> if (none { it.name == entry.name }) add(entry) }
    }

    PiSettingsSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSettingsMetrics.sheetHeadTop,
                ),
        ) {
            Text(
                "主题",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(PiSpacing.small))
            Text(
                "自动模式需要分别填写浅色与深色主题名。" +
                    "下面的自定义主题来自 pi 的主题目录与 themes 设置。",
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error != null) {
                Spacer(Modifier.height(PiSpacing.inline))
                PiInfoNote(error)
            }
            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(PiSpacing.inline))
                PiInfoNote("当前主题有部分颜色无法照搬：\n" + notes.joinToString("\n") { "· $it" })
            }
            Spacer(Modifier.height(PiSpacing.unit))
            Text(
                "单主题",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(PiSpacing.gutter))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = PiSettingsMetrics.sheetThemeMax)
                    .verticalScroll(rememberScrollState()),
            ) {
                // v2 的主题行（`phone44` / HTML:2337-2344）：`padding:11px 14px`、
                // 主题名**等宽 13**、右侧「当前」徽标、副行 12 灰 mt3、选中行铺
                // `--selected-bg`，行间没有分隔线，也不用 ✓ 图标。
                entries.forEach { entry ->
                    // 自动模式的值是一个**配对**字面量 `lightTheme/darkTheme`，所以
                    // `currentRaw == entry.name` 在自动模式下永远为假 —— 选中的那一行
                    // 不显示「当前」，用户看不出现在到底在用哪个主题（`phone44` 画的是
                    // 选中行铺 `--selected-bg` + 「当前」徽标）。这里把配对拆开比：
                    // 两个成员各自都会被标出来，这正是自动模式的实情。
                    val selected = currentRaw == entry.name ||
                        splitAutoTheme(currentRaw)?.let { (light, dark) ->
                            entry.name == light || entry.name == dark
                        } == true
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (selected) PiTheme.palette.selectedBg else Color.Transparent,
                            )
                            .clickable {
                                onSet(entry.name)
                                onDismiss()
                            }
                            .padding(
                                start = PiSettingsMetrics.pageHorizontal,
                                end = PiSettingsMetrics.pageHorizontal,
                                top = PiOptionRowPadding,
                                bottom = PiOptionRowPadding,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        // v2 的主题行是 `rw gap:8`。
                        horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.name,
                                style = PiTheme.text.mono,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                entry.path?.let { "${entry.scope.label} · ${it.substringAfterLast('/')}" }
                                    ?: entry.scope.label,
                                modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                                style = PiTheme.text.meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selected) {
                            PiSettingsBadge(label = "当前", tone = PiTheme.palette.accent)
                        }
                    }
                }
            }
            Spacer(Modifier.height(PiSpacing.unit))
            Text(
                "自动模式",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(PiSpacing.gutter))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PiEditorField(
                    value = lightTheme,
                    onValueChange = { lightTheme = it },
                    modifier = Modifier.weight(1f),
                    label = "浅色主题名",
                    height = PiNumberFieldHeight,
                )
                Spacer(Modifier.width(PiSpacing.inline))
                PiEditorField(
                    value = darkTheme,
                    onValueChange = { darkTheme = it },
                    modifier = Modifier.weight(1f),
                    label = "深色主题名",
                    height = PiNumberFieldHeight,
                )
            }
            Spacer(Modifier.height(PiSpacing.gutter))
            // 两段声音（规则 #7）：「保存为 」是我们的词 → 系统字；`dark/light` 是主题 id，
            // 是这一行唯一的机器值 → 等宽。裁决 ②-2：混排行一律拆两段，不许整行 mono。
            PiMixedLine(
                prefix = "保存为 ",
                machine = lightTheme.trim() + "/" + darkTheme.trim(),
                suffix = "",
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSettingsMetrics.sheetHeadBottom))
            Text(
                "原始值",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(PiSpacing.gutter))
            PiEditorField(
                value = raw,
                onValueChange = { raw = it },
                modifier = Modifier.fillMaxWidth(),
                label = "theme",
                height = PiNumberFieldHeight,
            )
            // v2 的 sheet 页脚：1px 上边（`borderMuted` 55%）+ `10px 14px 14px` 内边距。
            HorizontalDivider(
                thickness = PiSettingsMetrics.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        val pair = lightTheme.trim() + "/" + darkTheme.trim()
                        if (!pair.startsWith("/") && !pair.endsWith("/")) {
                            onSet(pair)
                            onDismiss()
                        }
                    },
                ) { Text("应用自动模式") }
                TextButton(
                    onClick = {
                        if (raw.isNotBlank()) {
                            onSet(raw.trim())
                            onDismiss()
                        }
                    },
                ) { Text("使用原始值") }
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
        }
    }
}

/**
 * v2 的 sheet 外壳（`06 §2`）：顶部圆角 16、抓手 `32×3`、头 `12px 14px 8px`、
 * 正文最大 420、页脚 `10px 14px 14px` + 1px 上边，scrim 用 M3 默认的
 * `rgba(0,0,0,.32)`（与 v2 同值），**不加阴影**。
 *
 * 只统一外壳：五个编辑器的字段、按钮与写盘逻辑一个字都没动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PiSettingsSheet(
    onDismiss: () -> Unit,
    // M3 的 sheet 正文槽是 `ColumnScope.() -> Unit`，所以这里照它的类型收：
    // 传一个 `() -> Unit` 是**不兼容**的（接收者算一个参数），编译期就会报错。
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = PiSettingsSheetShape,
        // `06 §2` / `.b-sheet`：底色是 `--surf-high`（`surfaceContainerHigh`），不是
        // `surf-low` —— 对话框与 sheet 在 v2 里是同一个表面阶，这也是本次对话框合并
        // 一并收掉的那处不一致。
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = {
            // 抓手：32×3，只画不响应（M3 默认的 4dp 抓手与 22dp 顶距都超出 v2 的规格）。
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = PiSettingsMetrics.badgeGap),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(
                            width = PiSettingsMetrics.sheetHandleWidth,
                            height = PiSettingsMetrics.sheetHandleHeight,
                        )
                        .clip(PiShapes.badge)
                        .background(MaterialTheme.colorScheme.outline),
                )
            }
        },
        content = content,
    )
}


// ------------------------------------------------ v2 的字段框体与滑块（五型共用）
//
// 五型编辑器原来用 M3 的 `OutlinedTextField`：56 高、浮动 label，与 v2 的自绘框差着
// 一整档。v2 的框体在 `EditorSheetBody` 里给了两副样子（`direction-b-v2.html:2297-2335`）：
//
//  - 单行框（数字）：`height:44`、圆角 9、`surf-low` 底、1px 边、内 `0 12px`，
//    字是等宽 17，单位在框内右端（`mono t14 c-muted`）；
//  - 多行框（文本）：`minHeight:80`、圆角 9、`surf-low` 底、1px 边、内 `10px 12px`，
//    字是等宽 13；
//  - 列表框：`surf-highest` 底 + 1px `borderMuted` 边、圆角 9、内 `10px 12px`、
//    等宽 13 / 行高 22。
//
// 边色：v2 把**已聚焦**的输入框画成 `border-accent`（数字、文本两处都是），没聚焦时是
// `borderMuted`。这里照这个规则做焦点环，而不是照 M3 的「描边永远一个色 + 变粗」。
//
// 五型的写盘逻辑、取值范围、条目语义一个字都没动：换掉的只是包装它们的那个盒子。

/**
 * 一个 v2 形状的输入框，五型编辑器共用。
 *
 * @param label 框上方的一行 12 灰说明。M3 的浮动 label 在 v2 的框体里没有槽位，而原文
 *   显示的是**字段名/点号键**（`setting.key`、`浅色主题名`）——丢掉它不是排版问题，
 *   是把「我在改哪个键」从这一屏抹掉，所以它留在框上沿，用 v2 自己的 12 灰。
 * @param trailing 框内右端的读数（数字编辑器的单位）：v2 `mono t14 c-muted`。
 * @param idleBorder 未聚焦时的边色；v2 的列表框用它自己的 `borderMuted`。
 * @param container 框底色；v2 列表框是 `surf-highest`，其余是 `surf-low`。
 * @param maxLines 多行框的可见行数上限（`singleLine = false` 时才有意义）。它交给
 *   `BasicTextField` 自己的 `maxLines`：`CoreTextField` 会经 `heightInLines` 把框高
 *   封顶到这么多行，并挂上 `textFieldScroll`（`VerticalScrollLayoutModifier`），
 *   于是**框内部可滚动、且光标始终在视野内** —— 比外面再套一个 `verticalScroll`
 *   正确（自己套那个的话，输入到框底时光标会跑到视野外）。单行框固定 1。
 */
@Composable
private fun PiEditorField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    focusRequester: FocusRequester? = null,
    height: Dp? = null,
    minHeight: Dp? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    idleBorder: Color = MaterialTheme.colorScheme.outline,
    textStyle: TextStyle = PiTheme.text.mono,
    contentPadding: PaddingValues = PiFieldPaddingSingleLine,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier) {
        if (label != null) {
            Text(
                label,
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.height(PiFieldLabelGap))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (height != null) Modifier.height(height) else Modifier)
                .then(if (minHeight != null) Modifier.heightIn(min = minHeight) else Modifier)
                .clip(PiFieldShape)
                .background(container)
                .border(
                    PiSettingsMetrics.hairline,
                    if (focused) PiTheme.palette.borderAccent else idleBorder,
                    PiFieldShape,
                )
                .padding(contentPadding),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
                    ),
                enabled = enabled,
                readOnly = readOnly,
                singleLine = singleLine,
                maxLines = maxLines,
                textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(PiTheme.palette.accent),
                interactionSource = interaction,
            )
            if (trailing != null) trailing()
        }
    }
}

/**
 * 数字编辑器的滑块：v2 是 2px 的轨道 + 16 的旋钮（`background:var(--text)`），已选段是
 * accent，两端各有一个读数（`t13`）。
 *
 * 手势仍交给 M3 的 `Slider`（只换 thumb/track 两个槽），这样拖动、无障碍、`steps` 的
 * 吸附都还是原来那一套；换掉的只是画出来的那两件东西。
 */
@Composable
private fun PiSliderThumb() {
    Box(
        Modifier
            .size(PiSliderThumbSize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface),
    )
}

// `SliderState` 本身带 `@ExperimentalMaterial3Api`（数字编辑器的调用点已有 opt-in，
// 但这里的**签名**用了这个类型，所以声明处也要）。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PiSliderTrack(state: SliderState) {
    val start = state.valueRange.start
    val end = state.valueRange.endInclusive
    val fraction = if (end > start) ((state.value - start) / (end - start)).coerceIn(0f, 1f) else 0f
    Box(
        Modifier
            .fillMaxWidth()
            .height(PiSliderTrackHeight)
            .background(MaterialTheme.colorScheme.outline),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(PiSliderTrackHeight)
                .background(PiTheme.palette.accent),
        )
    }
}

/** `EditorSheetBody`：圆角 9（与设置页的搜索/输入框同一档）。 */
private val PiFieldShape = RoundedCornerShape(PiSettingsMetrics.searchFieldRadius)

/** 单行框 `padding:0 12px`；多行框 / 列表框 `padding:10px 12px`。 */
private val PiFieldPaddingSingleLine = PaddingValues(horizontal = 12.dp)
private val PiFieldPaddingBlock = PaddingValues(horizontal = 12.dp, vertical = 10.dp)

/** 数字框 `height:44`；文本多行框 `minHeight:80`。 */
private val PiNumberFieldHeight = 44.dp
private val PiTextFieldMinHeight = 80.dp

/**
 * 多行文本框最多显示多少行，超出部分由框自己滚（见 `PiEditorField` 的 `maxLines`）。
 *
 * 为什么必须封顶：系统提示词可以是几千字，而这个框是这一页里唯一会长高的东西 ——
 * 不封顶就会把 sheet 的「保存 / 清除」顶到屏幕外，用户既看不全文本也按不到按钮。
 * 12 行 × `mono` 的 20sp 行高 = 240，加上标题、说明与页脚仍在
 * `ModalBottomSheet` 的高度之内；框内部可滚动且光标跟随，所以内容一个字都看不
 * 到的情形不存在。
 */
private const val PiTextEditorMaxLines = 12

/** label 与框之间那一线。 */
private val PiFieldLabelGap = 4.dp

/** 滑块：轨道 2、旋钮 16。 */
private val PiSliderTrackHeight = 2.dp
private val PiSliderThumbSize = 16.dp

/** 数字输入框两端的读数之间的空隙。 */
private val PiSliderLabelGap = 10.dp

/** 选项行 / 主题行：v2 是 `padding:11px 14px`（横向 14 由 sheet 正文的页边给）。 */
private val PiOptionRowPadding = 11.dp
