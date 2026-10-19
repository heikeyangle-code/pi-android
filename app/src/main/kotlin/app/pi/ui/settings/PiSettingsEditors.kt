package app.pi.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.mutableStateMapOf
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
import app.pi.ui.theme.PiThinkingLevel
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
 *
 * `AutoRestartEngine` is the fifth and the only one that needs no action from the
 * user: the write itself restarts the engine (`RuntimeSwitchAction`), so the body
 * says so and `SettingsGroupScreen` passes no action — the dialog is a 知道了.
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

        EffectiveKind.AutoRestartEngine -> Triple(
            "自动重启引擎",
            "改动已保存，并且这个开关会自己重启引擎来让它生效——不用你再动手。" +
                "探针没通过、或重启被正在运行的回合拒绝时，行上会写明发生了什么。",
            "知道了",
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

/**
 * 枚举选择器（spec §6.2 的 ValueRow）：点一项就写、然后关掉。
 *
 * 后面四个可选参数是给「选项来自清单」的两行（默认模型 / 默认厂商）用的，默认全关 ——
 * 所以枚举型选择器的几何一个像素都不变。
 *
 * @param searchHint 选项多到一屏翻不完时的搜索框标签（默认模型的清单可能有上百个模型）。
 *   null = 没有搜索框。
 * @param warning 正文里的一行警告，**写前**说清这次选择会落成什么样（例如「厂商和模型对不上」）。
 *   null = 不显示。
 * @param customCheck 手输值的**写前**校验：返回要显示的那句话，此时**不落盘**（等用户点
 *   「仍然使用」）；返回 null = 这个值可以直接用。回调为 null = 这一行不校验。
 *   没有它的时候，手输一个清单里不存在的 `defaultModel` 会被原样写进 settings.json，而 pi 对
 *   这种情况**不报错**、静默退回占位模型（`docs/engine-exit-review.md:55`：`defaultModel` 写成
 *   不存在的模型，退出码 0、stderr 空，模型退回 `contextWindow: 0` 的占位模型）。
 * @param clearLabel 底部「清除 / 跟随默认」动作的文案。选择器的第一等公民是"选一个"，但
 *   「回到 pi 的默认」同样是合法选择（默认模型那行对应的是两个键一起删）。
 * @param onClear 「清除」要执行的动作；与 [clearLabel] 同时非空时那一行才出现 —— 只有文案没有
 *   动作会是一个点不动的按钮。
 */
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
    searchHint: String? = null,
    warning: String? = null,
    customCheck: ((String) -> String?)? = null,
    clearLabel: String? = null,
    onClear: (() -> Unit)? = null,
) {
    var custom by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    // 手输了清单里没有的值：先给一行警告 + 「仍然使用 / 取消」，点了才落盘（见 [customCheck]）。
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
            warning?.let { text ->
                Spacer(Modifier.height(PiSpacing.small))
                Text(
                    text,
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.warning,
                )
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
                // v2 的选项行（`phone40` / HTML:2283-2293）：`padding:11px 14px`、
                // 前导 `✓/○`（等宽 12，选中用正文色、未选中 muted）、标签 14、选中行
                // 铺 `--selected-bg` + 右侧一枚「当前」徽标，**行间没有分隔线**。
                visible.forEach { option ->
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
                if (searchHint != null && visible.isEmpty()) {
                    Text(
                        "没有匹配的选项",
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
            if (allowCustom) {
                Spacer(Modifier.height(PiSpacing.unit))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PiEditorField(
                        value = custom,
                        onValueChange = {
                            custom = it
                            // 改了字就把上一条警告撤掉：它说的是刚才那串字，不是现在这串。
                            pending = null
                            refusal = null
                        },
                        modifier = Modifier.weight(1f),
                        label = "自定义值",
                        height = PiNumberFieldHeight,
                    )
                    Spacer(Modifier.width(PiSpacing.inline))
                    TextButton(
                        onClick = {
                            val trimmed = custom.trim()
                            if (trimmed.isEmpty()) return@TextButton
                            val message = customCheck?.invoke(trimmed)
                            if (message == null) {
                                onPick(trimmed)
                                onDismiss()
                            } else {
                                pending = trimmed
                                refusal = message
                            }
                        },
                    ) { Text("使用") }
                }
            } else {
                Spacer(Modifier.height(PiSpacing.unit))
            }
            val pendingWire = pending
            val refusalText = refusal
            if (pendingWire != null && refusalText != null) {
                Spacer(Modifier.height(PiSpacing.small))
                Text(
                    refusalText,
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.error,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            pending = null
                            refusal = null
                        },
                    ) { Text("取消") }
                    TextButton(
                        onClick = {
                            pending = null
                            refusal = null
                            onPick(pendingWire)
                            onDismiss()
                        },
                    ) { Text("仍然使用") }
                }
            }
            if (clearLabel != null && onClear != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            onClear()
                            onDismiss()
                        },
                    ) { Text(clearLabel) }
                }
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
                // 点号键是默认标签，但文件型的行（系统提示词那两行）写的是**真正被写的
                // 文件**：它没有 pi 的键，标一个像键的字符串只是在指认不存在的东西。
                label = setting.keyLabel ?: setting.key,
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
    var entries by remember(initialEntries) {
        // `presetBaseline` 非空的行（`defaultTools`）先过一遍 `PiQuickAdd.effective`：未设置显示
        // 默认四件套，"只写了可选项"的那份写错的值还原成并集。其余行原样返回。
        mutableStateOf(PiQuickAdd.effective(initialEntries, setting.presetBaseline, setting.presets))
    }
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
            // 这里原来有一块 `PiInfoNote`，逐行讲解值的写法（键=值 / glob 排除标记 / JSON 解析 /
            // 「清空并保存 = 删键」）。用户裁定删掉：**这一屏要的是能用的编辑器，不是语法手册**。
            // 真正必须知道的两件事仍在行自己的 `description` 里（默认值是什么、清空等于回到默认），
            // 所以删掉这块没有让任何承诺消失；值的语法本来也由 `Pi 文件` 屏与 pi 自己的文档负责。
            // 读旧版请见 `git log -S"每行一项" -- app/src/main/kotlin/app/pi/ui/settings/PiSettingsEditors.kt`。
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
                                    // 加/减都走 `PiQuickAdd`：加在基线之上（`defaultTools` 的
                                    // 「只写了可选项」不可能再被写出来），减到基线时塌回未设置。
                                    entries = if (selected) {
                                        PiQuickAdd.remove(
                                            entries,
                                            preset,
                                            setting.presetBaseline,
                                            setting.presets,
                                        )
                                    } else {
                                        PiQuickAdd.add(
                                            entries,
                                            preset,
                                            setting.presetBaseline,
                                            setting.presets,
                                        )
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
            // 落盘的值：一份正好等于基线的显式列表和「未设置」是同一个状态，`persist` 把它
            // 塌回空列表；空列表在 store 里走 `remove`（键删掉），不是写 `[]`。
            val toSave = PiQuickAdd.persist(entries, setting.presetBaseline)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        // 「清空」= 回到这一行的默认：有基线的行就是基线本身（落盘时塌回未设置），
                        // 其余行仍是空列表（键删掉）。
                        entries = setting.presetBaseline
                        problem = null
                    },
                ) { Text("清空") }
                TextButton(
                    onClick = {
                        val rejected = validate(toSave)
                        if (rejected == null) {
                            onSet(toSave)
                            onDismiss()
                        } else {
                            problem = rejected
                        }
                    },
                ) {
                    // The button names the outcome before it happens: an empty list removes the
                    // key (= pi's default), it does not store an empty list.
                    Text(if (toSave.isEmpty()) "恢复默认" else "保存")
                }
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
    val auto = remember(currentRaw) { splitAutoTheme(currentRaw) }
    var followSystem by remember(currentRaw) { mutableStateOf(auto != null) }
    var light by remember(currentRaw) { mutableStateOf(auto?.first ?: "light") }
    var dark by remember(currentRaw) { mutableStateOf(auto?.second ?: "dark") }
    var fixed by remember(currentRaw) { mutableStateOf(if (auto == null) currentRaw else "dark") }
    var picking by remember { mutableStateOf<ThemePick?>(null) }

    val entries = buildList {
        add(PiThemeEntry("dark", null, PiThemeScope.Builtin))
        add(PiThemeEntry("light", null, PiThemeScope.Builtin))
        knownThemes.forEach { entry -> if (none { it.name == entry.name }) add(entry) }
    }
    fun applyPair() {
        onSet(light.trim() + "/" + dark.trim())
    }

    PiSettingsSheet(onDismiss = onDismiss, fullHeight = true) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSettingsMetrics.sheetHeadTop,
                ),
        ) {
            when (val target = picking) {
                null -> {
                    Text(
                        "主题",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(PiSpacing.small))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                followSystem = !followSystem
                                if (followSystem) applyPair() else onSet(fixed)
                            }
                            .padding(vertical = PiSpacing.inline),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "跟随系统",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "系统浅色与深色各用一套主题。",
                                modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                                style = PiTheme.text.meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = followSystem, onCheckedChange = { on ->
                            followSystem = on
                            if (on) applyPair() else onSet(fixed)
                        })
                    }
                    if (followSystem) {
                        ThemeSlotRow(
                            label = "浅色用",
                            value = light,
                            onClick = { picking = ThemePick.Light },
                        )
                        ThemeSlotRow(
                            label = "深色用",
                            value = dark,
                            onClick = { picking = ThemePick.Dark },
                        )
                    }
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
                        "固定主题",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(PiSpacing.gutter))
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        items(entries) { entry ->
                            val selected = if (followSystem) {
                                entry.name == light || entry.name == dark
                            } else {
                                entry.name == fixed
                            }
                            ThemeEntryRow(
                                entry = entry,
                                selected = selected,
                                onClick = {
                                    followSystem = false
                                    fixed = entry.name
                                    onSet(entry.name)
                                },
                            )
                        }
                    }
                }

                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { picking = null }) { Text("返回") }
                        Text(
                            if (target == ThemePick.Light) "浅色用哪个主题" else "深色用哪个主题",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(PiSpacing.gutter))
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        items(entries) { entry ->
                            val selected = entry.name == if (target == ThemePick.Light) light else dark
                            ThemeEntryRow(
                                entry = entry,
                                selected = selected,
                                onClick = {
                                    if (target == ThemePick.Light) light = entry.name else dark = entry.name
                                    applyPair()
                                    picking = null
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
        }
    }
}

private enum class ThemePick { Light, Dark }

@Composable
private fun ThemeSlotRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = PiSettingsMetrics.pageHorizontal,
                end = PiSettingsMetrics.pageHorizontal,
                top = PiOptionRowPadding,
                bottom = PiOptionRowPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            value,
            style = PiTheme.text.mono,
            color = PiTheme.palette.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.chevronSize),
            tint = PiTheme.palette.muted,
        )
    }
}

@Composable
private fun ThemeEntryRow(entry: PiThemeEntry, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) PiTheme.palette.selectedBg else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(
                start = PiSettingsMetrics.pageHorizontal,
                end = PiSettingsMetrics.pageHorizontal,
                top = PiOptionRowPadding,
                bottom = PiOptionRowPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
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
    /** 主题列表要一屏：真全高，并直接展开到顶（不再有半屏那一档）。 */
    fullHeight: Boolean = false,
    // M3 的 sheet 正文槽是 `ColumnScope.() -> Unit`，所以这里照它的类型收：
    // 传一个 `() -> Unit` 是**不兼容**的（接收者算一个参数），编译期就会报错。
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = if (fullHeight) Modifier.fillMaxHeight() else Modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = fullHeight),
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
 *   是把「我在改哪个键」从这一屏抹掉，所以它留在框上沿，用 v2 自己的 12 灰；文件型的行
 *   （系统提示词那两行）用 `setting.keyLabel` 换成真正被写的文件路径。
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

// =====================================================================================
// 「推理」里两个不再手填的专有编辑器（用户：点开只有一个输入框，我该填什么呢？）
// =====================================================================================

/**
 * 「逐模型思考等级」的专用编辑器：**没有任何文本输入** —— 模型从清单点、等级从枚举点。
 *
 * 为什么必须有它：这一行原来是 `container = Object` 的通用列表编辑器，用户看到的只有
 * 一个「新增一项」输入框，而答案是一行 `"provider/modelId" = level` 的 JSON 文本 ——
 * 用户原话「点了也是一个输入框让自己填，我该填什么呢？所有的我都不想自己填」。
 *
 * 两件事都是 pi 的事实，不是本编辑器的发明：
 *
 *  - 键是 `Record<"provider/modelId", ModelThinkingLevel>`，**精确、大小写敏感**，模型 id
 *    自己可以带斜杠（`core/settings-manager.ts:805`、`docs/settings-audit-pi-gap.md:471`）；
 *  - 值就是 `defaultThinkingLevel` 那 7 档（`packages/ai/src/types.ts:83-84` 的
 *    `ThinkingLevel | "off"`；注册表里 `thinkingLevelOptions` 也是同一份 [PiThinkingLevel]），
 *    所以两颗选择器不会各说各话。
 *
 * 写回的语义照旧交给 host 的 `PiSetting.elementFromEntries`：空列表 = 删键（回到默认），
 * 不是写 `{}`。
 *
 * @param models 清单里的模型，`wire` 是 `厂商/模型`（清单页 `modelPickerOptions` 的同一份）。
 * @param initialEntries 当前值，`名字 = 值` 原文行（`PiSetting.editableEntries`）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiModelThinkingLevelsSheet(
    title: String,
    description: String,
    initialEntries: List<String>,
    models: List<PiOption>,
    onSet: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    fun nameOf(line: String): String = line.substringBefore('=').trim()
    fun valueOf(line: String): String = line.substringAfter('=', "").trim()

    var entries by remember(initialEntries) {
        mutableStateOf(initialEntries.map { it.trim() }.filter { it.isNotEmpty() })
    }
    var query by remember { mutableStateOf("") }
    // 一次只展开一行模型的等级 chips：点模型 → 点等级 → 这一条就加好了。
    var expanded by remember { mutableStateOf<String?>(null) }
    val levelByKey = remember(entries) { entries.associate { nameOf(it) to valueOf(it) } }
    val shownModels = remember(models, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            models
        } else {
            models.filter {
                it.wire.contains(needle, ignoreCase = true) || it.label.contains(needle, ignoreCase = true)
            }
        }
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
                // 当前覆盖：一行一条，右侧一个删除。键不在清单里（厂商/模型被删掉了）
                // 也照实显示 —— 那是用户文件里真实存在的一条，不能凭空藏掉。
                if (entries.isEmpty()) {
                    Text(
                        "还没有覆盖。下面点一个模型、再点一个等级就加上了。",
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    entries.forEach { line ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    models.firstOrNull { it.wire == nameOf(line) }?.label ?: nameOf(line),
                                    style = PiTheme.text.mono,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "等级 ${valueOf(line)}",
                                    style = PiTheme.text.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = { entries = entries.filterNot { nameOf(it) == nameOf(line) } },
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "删除这一条",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
                Text(
                    "选一个模型，再点等级",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(PiSpacing.gutter))
                PiEditorField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "搜模型",
                    height = PiNumberFieldHeight,
                )
                Spacer(Modifier.height(PiSpacing.unit))
                shownModels.forEach { option ->
                    val level = levelByKey[option.wire]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = if (expanded == option.wire) null else option.wire }
                            .padding(vertical = PiSpacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                option.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                if (level != null) "等级 $level" else option.description.orEmpty(),
                                style = PiTheme.text.meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            if (expanded == option.wire) "−" else "+",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (expanded == option.wire) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(bottom = PiSpacing.small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PiThinkingLevel.entries.forEach { levelOption ->
                                PiChoiceChip(
                                    label = levelOption.label,
                                    selected = level == levelOption.wire,
                                    onClick = {
                                        entries = entries.filterNot { nameOf(it) == option.wire } +
                                            "${option.wire} = ${levelOption.wire}"
                                        expanded = null
                                    },
                                )
                            }
                        }
                    }
                }
                if (shownModels.isEmpty()) {
                    Text(
                        "清单里没有匹配的模型",
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(
                thickness = PiSettingsMetrics.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(
                    onClick = {
                        onSet(entries)
                        onDismiss()
                    },
                ) { Text("保存") }
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
        }
    }
}

/**
 * 「思考预算」的专用编辑器：**固定 4 行、只填数字**，键名不用手打。
 *
 * pi 的 `ThinkingBudgets` 只有四个键 `minimal` / `low` / `medium` / `high`，值是 token 数
 * （`packages/ai/src/types.ts:100-105`、`docs/settings-audit-pi-gap.md:263`）。pi **不给
 * 默认数字**：消费侧是 `?? 各厂商自己的默认`（`core/settings-manager.ts:1174-1176`、
 * `core/sdk.ts:370`），所以**留空就是正确用法** —— 这里不编造任何"官方数值"。
 *
 * 四行都空 = 整个键删掉（host 走 `store.remove`），不是写 `{}`。只接受非负整数：
 * 一个非数字写下去只会让这个思考等级退回厂商默认，静默无效（这就是以前手打那种行的
 * 下场）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiThinkingBudgetsSheet(
    title: String,
    description: String,
    initialEntries: List<String>,
    onSet: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val keys = THINKING_BUDGET_KEYS
    val values = remember(initialEntries) {
        mutableStateMapOf<String, String>().apply {
            initialEntries.forEach { line ->
                val name = line.substringBefore('=').trim()
                val value = line.substringAfter('=', "").trim()
                if (name.isNotEmpty()) put(name, value)
            }
        }
    }
    val invalid = keys.any { key ->
        val text = values[key].orEmpty().trim()
        text.isNotEmpty() && parseThinkingBudget(text) == null
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
            keys.forEach { key ->
                PiEditorField(
                    value = values[key].orEmpty(),
                    onValueChange = { values[key] = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = key,
                    height = PiNumberFieldHeight,
                    trailing = {
                        Text(
                            "token",
                            style = PiTheme.text.mono,
                            color = PiTheme.palette.muted,
                        )
                    },
                )
                Spacer(Modifier.height(PiSpacing.small))
            }
            Spacer(Modifier.height(PiSpacing.unit))
            Text(
                if (invalid) "只接受非负整数" else "留空 = 用厂商默认",
                style = PiTheme.text.meta,
                color = if (invalid) PiTheme.palette.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(
                thickness = PiSettingsMetrics.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(
                    enabled = !invalid,
                    onClick = {
                        // 只写有字的行；四行全空 = 空列表 = 删键（host 负责）。
                        onSet(
                            keys.mapNotNull { key ->
                                values[key]?.trim()?.takeIf { it.isNotEmpty() }?.let { "$key = $it" }
                            },
                        )
                        onDismiss()
                    },
                ) { Text("保存") }
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
        }
    }
}

/** pi 的 `ThinkingBudgets` 四个合法键，顺序按 token 从小到大（`types.ts:100-105`）。 */
private val THINKING_BUDGET_KEYS = listOf("minimal", "low", "medium", "high")

/** 思考预算的一格：空、非负整数，其它都不收。超出 `Long` 的数字同样不收（那是打字错误）。 */
private fun parseThinkingBudget(text: String): Long? =
    text.trim().toLongOrNull()?.takeIf { it >= 0 }

/**
 * v2 的 chip（与 `PiListEditorSheet` 的「快捷添加」同一副样子：高 26、圆角 999、
 * 1px `borderMuted` 描边，选中的那枚换 `surfaceContainerHigh` 底）。
 */
@Composable
private fun PiChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .padding(end = PiSpacing.inline)
            .height(PiSettingsMetrics.chipHeight)
            .clickable(onClick = onClick),
        shape = PiShapes.badge,
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = BorderStroke(PiSettingsMetrics.hairline, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = PiSettingsMetrics.chipPaddingHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = PiTheme.text.monoSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
