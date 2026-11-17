package app.pi.ui.screens

import android.animation.ValueAnimator
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.focus.onFocusChanged
import app.pi.rpc.PiResponses
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import app.pi.ui.components.PiType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.ui.chat.SlashPalette
import app.pi.ui.chat.thinkingLabelOf
import app.pi.ui.components.PiContextRing
import app.pi.ui.components.PiMenu
import app.pi.ui.components.PiMenuItem
import app.pi.ui.components.PiMenuPlacement
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiProseFamily
import app.pi.ui.theme.PiTheme

/** 聊天页输入框：文字区、工具行（/、附件、⋮、上下文环、模型、思考、发送）及其小件。从 ChatScreen.kt 拆出。 */


/**
 * 悬浮输入框的几何（用户 2026-10-26 的裁决）。
 *
 * 圆角 26、左右 12、底 8；描边平时 1.5、聚焦 2，150 ms 过渡；运行中只让**现有描边
 * 颜色**的 alpha 在 1 → 0.45 之间呼吸（1.6 s，`FastOutSlowInEasing`，
 * `RepeatMode.Reverse`），聚焦时暂停、进出各 150 ms，宽度不跟着动。系统关掉动画时
 * 不呼吸。颜色来源一行没改。
 *
 * 工具行从左到右：`/`、回形针、⋮、流式时「引导 / 后续」、弹性空白、上下文环 20 dp
 * （2 dp 笔、30 dp 热区）、模型 chip（28 高、72–140 宽、13 sp Medium、无底色、中间省略）、
 * 思考等级文字（宽度够时）、发送 40 dp。
 *
 * 尺寸 token 在 `PiSpacing`（`composer*`）里，屏幕侧只引用它们；这里剩下的是**时间与
 * 行数**，不是 dp：150 ms 的描边/进出、1.6 s 的呼吸、6 行上限、20 sp 行高。
 * 列表底边留白 = 输入框实测高度 + [PiSpacing.composerListClearance]；输入框上方的
 * 渐隐 = [PiSpacing.composerGradientFade]。
 */
private const val COMPOSER_BORDER_MS = 150

private const val COMPOSER_FADE_MS = 150

private const val COMPOSER_PULSE_MS = 1600

private const val COMPOSER_PULSE_MIN = 0.45f

private const val COMPOSER_MAX_LINES = 6


/**
 * 输入框工具行里的模型 chip（2026-10-26：从顶栏挪下来）。
 *
 * 顶栏把宽度还给会话标题；模型名现在和环 / 发送放在同一条能看见输入状态的行里。
 * **没有底色**：纯文字 + `▾`，13sp Medium 无衬线，视觉高 28；只有按下时才有
 * 圆角 pill 的涟漪（`onSurface` 的 12%，M3 ripple 的 pressed alpha）。文字取色
 * 逻辑原样不动（`palette.text`），未选中态也没有边框。最小 72，最大由
 * `BoxWithConstraints` 按剩余宽度算（上限 140）。
 *
 * 长 id **从中间省略**：`deepseek-v4.1-flash-1017` 的区别在尾巴，从末尾省略只剩
 * 一串 `deepseek-v4…`，分不出是哪一个。Compose 1.8 的
 * `TextOverflow.MiddleEllipsis` 对单行可用（多行会退回 Clip），这里 maxLines = 1。
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun ComposerModelChip(
    label: String?,
    maxWidth: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(PiSpacing.composerModelChipHeight)
            .widthIn(
                min = PiSpacing.composerModelChipMinWidth,
                max = maxWidth,
            )
            .clip(RoundedCornerShape(percent = 50))
            // 无底色：只有按下时的 pill 形涟漪，颜色 onSurface（M3 ripple 按
            // pressed alpha 12% 画），不再有 surfaceContainerHigh 的灰块。
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = MaterialTheme.colorScheme.onSurface),
                onClickLabel = "选择模型",
                onClick = onClick,
                onLongClickLabel = "切换到下一个模型",
                onLongClick = onLongClick,
            )
            .padding(horizontal = PiSpacing.inner),
    ) {
        Text(
            text = label ?: "选择模型",
            // 右边给 `▾` 留出位置；文字自己的 max 由 Box 的 maxWidth 约束，超长时
            // MiddleEllipsis 在**中间**断开，尾巴（版本/日期）仍然可见。
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(end = PiSpacing.cardPadding + PiSpacing.tiny),
            style = PiType.chipLabel,
            color = PiTheme.palette.text,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        Text(
            text = "▾",
            modifier = Modifier.align(Alignment.CenterEnd),
            style = PiType.chipLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


@Composable
internal fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    thinkingLevel: String,
    streaming: Boolean,
    /** True while a follow-up could actually be queued: streaming, non-blank draft. */
    canFollowUp: Boolean,
    /** True while a `steer` could actually be delivered: streaming, and something to send. */
    canSteer: Boolean,
    /** 工具行那颗模型 chip 的文字；null 时显示「选择模型」。 */
    modelLabel: String?,
    /** 点模型 chip：拉模型列表，再开模型面板。 */
    onOpenModel: () -> Unit,
    /** 长按模型芯片：切到下一个（作用域）模型，不用进选择器。 */
    onCycleModel: () -> Unit,
    /** 点思考等级的文字：循环切到下一档。 */
    onCycleThinking: () -> Unit,
    /** 长按思考等级：打开选择列表（pi 的 `thinking.levels`），不用循环到想要的那一档。 */
    onPickThinking: () -> Unit,
    onOpenPalette: () -> Unit,
    onOpenBash: () -> Unit,
    /** pi's `!!`: run the command without putting it in the model's context. */
    onOpenBashExcluded: () -> Unit,
    onOpenMention: () -> Unit,
    /** The paperclip: an image goes on the wire, any other file in as its path. */
    onPickAttachment: () -> Unit,
    /** pi's context occupancy as it reports it; null draws the ring's `?`. */
    contextUsage: PiResponses.ContextUsage?,
    /** 点环：先补读 stats，再打开**原来那张**「上下文与用量」sheet。 */
    onOpenContext: () -> Unit,
    /** pi's `app.clear`: empty editor. True while there is something to clear. */
    canClear: Boolean,
    onClear: () -> Unit,
    /** 引导: pi's steer — the message joins the running turn. */
    onSteer: () -> Unit,
    /** 后续: pi's followUp — the message waits for the turn to finish. */
    onFollowUp: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    // A state channel, not decoration: pi paints its editor with the current
    // thinking level's token and switches to `bashMode` when the line starts with
    // `!` (`interactive-mode.ts` `updateEditorBorderColor`).
    val borderColor = if (bashModeOf(draft)) {
        PiTheme.palette.bashMode
    } else {
        PiTheme.palette.thinking(thinkingLevel)
    }
    // 外观：26dp 圆角、1.5 → 2 的描边；不画 elevation 投影（6dp 投影落在输入框
    // 下面那行上，浅色下就是输入框与导航条之间的一条灰带）。描边颜色仍完全来自
    // 上面的 `borderColor`，色相与取色逻辑一个字节没改。
    val inputShape = RoundedCornerShape(PiSpacing.composerRadius)
    // 6 行是**按行高算**的，不是写死 120dp：系统字体放大后写死的 120 只能放 4 行半。
    //
    // 输入框文字（所有档位）：与发出去后的用户气泡同一套阅读宋体、同字号，行高取字号的 1.5 倍。
    // 原来写死 20 sp，而非经典档的正文是 16 sp：1.25 的行距，汉字多行输入又挤又闷，
    // 敲出来是黑体、一发送变宋体也不连贯。经典档仍是 14/20。
    val composerProse = PiTheme.text.prose
    val composerLineHeight = (composerProse.fontSize.value * 1.5f).sp
    val composerTextStyle =
        composerProse.copy(fontFamily = PiProseFamily, fontWeight = FontWeight.Normal, lineHeight = composerLineHeight)
    val lineHeightDp = with(LocalDensity.current) { composerLineHeight.toDp() }
    val fieldMaxHeight = lineHeightDp * COMPOSER_MAX_LINES
    var focused by remember { mutableStateOf(false) }
    val borderWidth by animateDpAsState(
        targetValue = if (focused) PiSpacing.composerBorderFocused else PiSpacing.composerBorderIdle,
        animationSpec = tween(COMPOSER_BORDER_MS),
        label = "composerBorderWidth",
    )

    // 运行中呼吸：只在 alpha 上往返，不改宽度、不改色相。聚焦（正在打字）时目标回到
    // 1，进出各走一次 [COMPOSER_FADE_MS]；系统关掉动画时 `pulse` 根本不进目标。
    val animationsEnabled = ValueAnimator.areAnimatorsEnabled()
    val pulse by rememberInfiniteTransition(label = "composerPulse").animateFloat(
        initialValue = 1f,
        targetValue = COMPOSER_PULSE_MIN,
        animationSpec = infiniteRepeatable(
            animation = tween(COMPOSER_PULSE_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "composerPulseAlpha",
    )
    val factor by animateFloatAsState(
        targetValue = if (streaming && !focused && animationsEnabled) pulse else 1f,
        animationSpec = tween(COMPOSER_FADE_MS),
        label = "composerPulseFactor",
    )
    val shownBorder = borderColor.copy(alpha = borderColor.alpha * factor)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 外观的两层在**外**、`animateContentSize` 在**内**：动画改变的是卡片自己的
            // 高度，background / border 画在动画后的尺寸上，行数增长时不会出现
            // "底已经跳到位、边框还在半路"的错位。
            //
            // 不再用 elevation 投影：6dp 的投影落在输入框下面那行卡片上，浅色主题里
            // 就是输入框与导航条之间的一条灰带（用户 2026-10-26 要求去掉）。卡片靠
            // `surfaceContainerLow` 与 1.5dp 描边浮起来，页面底色一直铺到屏底。
            .background(MaterialTheme.colorScheme.surfaceContainerLow, inputShape)
            .border(borderWidth, shownBorder, inputShape)
            // 1–6 行的高度变化在这里补齐；超过 6 行由字段自己内部滚动。
            .animateContentSize()
            .padding(
                start = PiSpacing.inner,
                end = PiSpacing.inner,
                top = 9.dp,
                // 底面从 9 收到 4、键行前间距从 8 加到 14：键行（含发送）再往下
                // 让一点，和输入框拉开距离，减少误触；行内仍是中心对齐。
                bottom = PiSpacing.small,
            ),
    ) {
            // 这个字段必须自己拿 `TextFieldValue`，不能用 `value: String` 那个重载。
            // 库的 String 重载把光标位置存在自己内部
            // （`androidx/compose/foundation/text/BasicTextField.kt:713-717`：
            // `val textFieldValue = textFieldValueState.copy(text = value)` —— **只换文本、
            // 不动 selection**，而 `textFieldValueState` 的初始 selection 是 `TextRange.Zero`），
            // 而这里 `draft` 会被**代码**改掉：命令面板与菜单（`draft = "/"`）、`!` / `!!`、
            // `@` 提及（`draft += "@"`）。于是程序化塞进去的 `/` 后面光标仍停在 0 ——
            // 再打字就落在斜杠**前面**（用户报的正是这个；用输入法敲 `/` 没事，因为那条路走
            // `CoreTextField` 自己的编辑，selection 是它算出来的）。
            // 所以：**文本不是这一次用户敲出来的时候，一律把光标放到末尾**，这也是按这些按钮
            // 的人接下来要打字的位置。
            val field = remember {
                mutableStateOf(TextFieldValue(text = draft, selection = TextRange(draft.length)))
            }
            if (field.value.text != draft) {
                field.value = TextFieldValue(text = draft, selection = TextRange(draft.length))
            }
            BasicTextField(
                value = field.value,
                // 用户输入这条路上，selection/composition 都来自库，原样存回，不能重建
                // （重建会丢掉输入法的 composing 区间，中文输入时可见地闪）。**但只有文本真的
                // 变了才写回 `draft`**，见下。
                onValueChange = { updated ->
                    // 这道**文本**一级的闸必须自己补，`value: TextFieldValue` 重载里没有。
                    // 库的 `value: String` 重载内部有一道 `lastTextValue != new.text`
                    // （`androidx/compose/foundation/text/BasicTextField.kt:727-742`），所以只有
                    // 文本真的变了的更新才到达 `onValueChange`；`TextFieldValue` 重载的闸是整值
                    // 比较 `if (value != it)`（同文件 `:868-874`），而 `TextFieldValue.equals`
                    // 把 composition/selection 也算进去（`ui-text` 的 `TextFieldValue.kt:123-132`）。
                    // 库在**失去焦点**时会主动发一次
                    // `onValueChange(editProcessor.toTextFieldValue().copy(composition = null))`
                    // ——**文本没变**（`TextFieldDelegate.kt:395-404`，`onBlur`）。发送路径正好是
                    // 这个形状：先 `draft = ""`，紧接着 `dismissKeys()` 的 `focus.clearFocus()`
                    // （本文件 `:499-502` 与 `:2606`/`:2617`），而 `onBlur` 读的是 EditProcessor，
                    // 它只在 `CoreTextField` 组合时才被重置（`CoreTextField.kt:280`），于是那次
                    // 回调交回来的是**清空之前**的文本，`onDraftChange` 把它写回 `draft` —— 发送
                    // 的清空就这么被抹掉了（用户报的「发出去了、字还在输入框里、还能再发一遍」）。
                    // 只有在 composing 区间存在时 `value != it` 才为真、闸放行，所以它只是「有时候」。
                    // 注意不能拿 `draft` 当基准比较：这个 bug 里 `draft` 已经是 `""`，那反而会放行。
                    val textChanged = updated.text != field.value.text
                    field.value = updated
                    if (textChanged) onDraftChange(updated.text)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = lineHeightDp, max = fieldMaxHeight)
                    // 聚焦时描边加粗、呼吸暂停；两个都只读这一个布尔。
                    .onFocusChanged { focused = it.isFocused },
                // `06 §2` 输入区「输入 14/20」: the composer's own text is the 14 sp
                // chat-text step with a 20 sp lead, not M3's larger field default.
                textStyle = composerTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                // 1–6 行；第 7 行起由字段自己内部滚动，外面不再长高。
                maxLines = COMPOSER_MAX_LINES,
                cursorBrush = SolidColor(PiTheme.palette.accent),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty()) {
                            Text(
                                text = "输入消息，/ 选命令，! 直接跑命令，@ 提及文件",
                                style = composerTextStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.height(13.dp))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val configuration = LocalConfiguration.current
                // 思考等级的文字：流式时键行还要多出「引导 / 后续」两枚，所以只有
                // 当模型 chip 仍能保住最小宽度时才占位；否则先收起（顶栏 ⋮ 仍可改，
                // 输入框描边也仍然跟着等级）。字体放大到 1.3 以上时同理。
                val measurableThinking = configuration.fontScale <= 1.3f
                val thinkingText = thinkingLabelOf(thinkingLevel)
                val thinkingStyle = PiType.chipLabel
                val textMeasurer = rememberTextMeasurer()
                val density = LocalDensity.current
                val thinkingWidth = if (measurableThinking) {
                    with(density) {
                        textMeasurer.measure(thinkingText, style = thinkingStyle).size.width.toDp()
                    } + PiSpacing.small * 2f
                } else {
                    0.dp
                }
                // 右侧固定项：三枚工具 + 流式时两枚 + 环 + 两段间距 + 发送。
                val base = PiSpacing.composerToolSize * 3f +
                    (if (streaming) PiSpacing.composerToolSize * 2f + PiSpacing.tiny else 0.dp) +
                    PiSpacing.composerRingTouch +
                    PiSpacing.composerChipGap +
                    PiSpacing.composerChipSendGap +
                    PiSpacing.composerSendSize
                // 只有给 chip 留得下最小宽度时，思考等级文字才真的画出来。
                val showThinking = measurableThinking &&
                    (maxWidth - base - thinkingWidth - PiSpacing.small) >=
                    PiSpacing.composerModelChipMinWidth
                val fixed = base +
                    (if (showThinking) thinkingWidth + PiSpacing.composerChipGap else 0.dp)
                // chip 吃掉剩下的宽度（至少 72、至多 132），再给弹性空白留 4dp。
                val chipMax = (maxWidth - fixed - PiSpacing.small)
                    .coerceIn(
                        PiSpacing.composerModelChipMinWidth,
                        PiSpacing.composerModelChipMaxWidth,
                    )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 左侧三枚：30dp 的圆形热区、20dp 字形，平时没有底，按下才起涟漪。
                    ComposerIconButton(onClickLabel = "命令面板", onClick = onOpenPalette) {
                        Text(
                            text = "/",
                            style = PiTheme.text.mono.copy(
                                fontSize = with(LocalDensity.current) { PiSpacing.composerToolIcon.toSp() },
                            ),
                            color = PiTheme.palette.text,
                        )
                    }
                    ComposerIconButton(onClickLabel = "添加附件", onClick = onPickAttachment) {
                        Icon(
                            imageVector = Icons.Filled.AttachFile,
                            contentDescription = null,
                            modifier = Modifier.size(PiSpacing.composerToolIcon),
                            tint = PiTheme.palette.text,
                        )
                    }
                    // `@` / `!` / `!!` / 清空在这里；引导 / 后续不在菜单里，见下。
                    ComposerMenu(
                        onOpenMention = onOpenMention,
                        onOpenBash = onOpenBash,
                        onOpenBashExcluded = onOpenBashExcluded,
                        canClear = canClear,
                        onClear = onClear,
                    )
                    // 引导 / 后续：只在流式时出现，不常驻，所以留在键行里而不是 ⋮。
                    if (streaming) {
                        ComposerDeliveryChip(label = "引导", enabled = canSteer, onClick = onSteer)
                        Spacer(Modifier.width(PiSpacing.tiny))
                        ComposerDeliveryChip(label = "后续", enabled = canFollowUp, onClick = onFollowUp)
                    }
                    Spacer(Modifier.weight(1f))
                    // 一直显示：拿不到 percent 时环里是 pi 自己的 `?`，不是把整枚藏掉。
                    // 总量在 pi 没给 contextWindow 时回退到当前模型自己的窗口（pi 的
                    // footer 也是这么取的：`contextUsage?.contextWindow ?? state.model?.contextWindow`）。
                    ContextRingButton(
                        usage = contextUsage,
                        onOpenContext = onOpenContext,
                    )
                    Spacer(Modifier.width(PiSpacing.composerChipGap))
                    // 模型 chip：从顶栏挪下来，排在环和发送之间。
                    ComposerModelChip(
                        label = modelLabel,
                        maxWidth = chipMax,
                        onClick = onOpenModel,
                        onLongClick = onCycleModel,
                    )
                    // 思考等级：**只删了 ◐ 图标**，文字按钮本身还在；宽度够就显示。
                    if (showThinking) {
                        Spacer(Modifier.width(PiSpacing.composerChipGap))
                        ThinkingLevelButton(level = thinkingLevel, onClick = onCycleThinking, onLongClick = onPickThinking)
                    }
                    Spacer(Modifier.width(PiSpacing.composerChipSendGap))
                    Box(
                        modifier = Modifier
                            .size(PiSpacing.composerSendSize)
                            .clip(CircleShape)
                            .background(PiTheme.palette.accent)
                            .clickable(
                                onClickLabel = if (streaming) "停止" else "发送",
                                onClick = { if (streaming) onStop() else onSend() },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (streaming) Icons.Filled.Stop else Icons.Filled.Send,
                            contentDescription = if (streaming) "停止" else "发送",
                            modifier = Modifier.size(PiSpacing.composerSendIcon),
                            tint = PiTheme.palette.pageBg,
                        )
                    }
                }
            }
        }
}


/** pi: `isBashMode = text.trimStart().startsWith("!")`. */
private fun bashModeOf(draft: String): Boolean = draft.trimStart().startsWith("!")


/**
 * The composer's ⋮ button: `@` / `!` / `!!` / 清空, plus 引导 / 后续 while a turn runs.
 *
 * 30 dp 圆形热区、20 dp 手绘三点，和 `/`、回形针同一套；平时没有底色，只有按下时
 * 的涟漪。菜单仍然**向上**开（[PiMenuPlacement.Above]）：按钮在屏幕底部，向下会被
 * 键盘挡住。
 *
 * 引导 / 后续 **不在这里**：它们是流式时最急的两个动作，保留在键行里（见
 * `ComposerDeliveryChip`）。这个菜单只放触发符和清空。
 */
@Composable
internal fun ComposerMenu(
    onOpenMention: () -> Unit,
    onOpenBash: () -> Unit,
    onOpenBashExcluded: () -> Unit,
    canClear: Boolean,
    onClear: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val palette = PiTheme.palette
    Box(
        modifier = Modifier
            .size(PiSpacing.composerToolSize)
            .clip(CircleShape)
            .clickable(onClickLabel = "更多输入方式", onClick = { open = true }),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = THREE_DOT_GLYPH,
            contentDescription = null,
            modifier = Modifier.size(PiSpacing.composerToolIcon),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        // The same rule as the top bar's menu: the item list is built at this call site,
        // so a closed menu must not build it. See that site's note.
        if (open) PiMenu(
            expanded = true,
            onDismiss = { open = false },
            placement = PiMenuPlacement.Above,
            items = buildList {
                add(
                    PiMenuItem(
                        symbol = "@",
                        label = "提及文件",
                        note = "插入文件路径",
                        symbolColor = palette.text,
                        onSelect = onOpenMention,
                    ),
                )
                // `!` and `!!` sit next to each other and differ **only** in the
                // right-hand note, because that is the only difference in pi: `!!`
                // runs the command without putting it in the model's context
                // (`modes/interactive/interactive-mode.ts:933`, `:3107`; the
                // execution side honours it through `ui/chat/SlashPalette.kt`'s
                // `excludeFromContext`). The symbol of both is the shell mode colour
                // pi paints a `!` line with.
                add(
                    PiMenuItem(
                        symbol = "!",
                        label = "跑命令",
                        note = "进上下文",
                        symbolColor = palette.bashMode,
                        onSelect = onOpenBash,
                    ),
                )
                add(
                    PiMenuItem(
                        symbol = "!!",
                        label = "跑命令",
                        note = "不进上下文",
                        symbolColor = palette.bashMode,
                        onSelect = onOpenBashExcluded,
                    ),
                )
                if (canClear) {
                    add(
                        PiMenuItem(
                            label = "清空",
                            // pi's `app.clear` (`interactive-mode.ts:919`, ctrl+c).
                            a11yLabel = "清空草稿",
                            dividerBefore = true,
                            onSelect = onClear,
                        ),
                    )
                }
            },
        )
    }
}


/**
 * The three dots of [ComposerMenu], drawn here rather than taken from
 * `Icons.Filled.MoreVert`.
 *
 * The viewport is 24 (M3's glyph is drawn for 18 and scaled), the dots are solid
 * circles of `r = 2` centred at `y = 6 / 12 / 18`, and the path is the six half-arcs
 * that are those three circles — an `ImageVector` path builder has no `circle()`
 * helper. The fill is
 * the same unreachable placeholder every vector here uses: `Icon`'s `tint` is what
 * colours it, because this vector carries no `tintColor` of its own.
 */
private val THREE_DOT_GLYPH: ImageVector = ImageVector.Builder(
    name = "PiComposerMenuDots",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = PathParser().parsePathString(
        "M10 6a2 2 0 104 0a2 2 0 10-4 0" +
            "M10 12a2 2 0 104 0a2 2 0 10-4 0" +
            "M10 18a2 2 0 104 0a2 2 0 10-4 0",
    ).toNodes(),
    fill = SolidColor(Color.Black),
).build()


/**
 * 悬浮输入框工具行里的一枚圆形图标按钮。
 *
 * 30 dp 热区、20 dp 字形，平时**没有底色**（旧版的灰方块被去掉了），只有
 * `clickable` 自带的涟漪在按下时出现。`onClickLabel` 始终独立传入：图标按钮没有
 * 可见文字，没有它读屏就什么都没有。
 */
@Composable
private fun ComposerIconButton(
    onClickLabel: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(PiSpacing.composerToolSize)
            .clip(CircleShape)
            .clickable(onClickLabel = onClickLabel, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}


/**
 * 流式时的「引导 / 后续」：30 dp 的圆形文字按钮，只在 `streaming` 时出现。
 *
 * **和左边三枚图标按钮同一套形状/背景**：30 dp 圆、没有底色、按下才起涟漪；
 * 唯一的区别是内容 —— 这三个是图标，这两个是两字标签。禁用时文字转 muted，
 * 仍是同一个圆、同一个位置，不跳。
 *
 * 宽度：两枚圆合计 62 dp（原来 26 dp 的 pill 是 76 dp）。360 dp 屏流式时
 * 思考文字收起，模型 chip 仍有 78；393 dp 上思考文字与 chip(75) 能同时排下，
 * 更宽的屏上 chip 会自动长到 140 为止。
 */
@Composable
private fun ComposerDeliveryChip(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(PiSpacing.composerToolSize)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = PiTheme.text.monoSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else PiTheme.palette.muted,
            maxLines = 1,
        )
    }
}


/**
 * 上下文用量环。**点按仍然是原来那个「上下文与用量」sheet**，不是另造的气泡。
 *
 * 一直显示：`usage` 为空或 `percent == null` 时，环里是 `PiContextRing` 自己的 `?`
 * （pi 在压缩后到下一次回复前也是同一个窗口）。环 20 dp、2 dp 笔，进度弧颜色仍由
 * `PiContextRing` 的 `contextProgressColor` 决定。
 */
@Composable
private fun ContextRingButton(
    usage: PiResponses.ContextUsage?,
    onOpenContext: () -> Unit,
) {
    Box(Modifier.size(PiSpacing.composerRingTouch), contentAlignment = Alignment.Center) {
        PiContextRing(
            percent = usage?.percent,
            diameter = PiSpacing.composerRingDiameter,
            stroke = PiSpacing.composerRingStroke,
            placeholderStyle = PiTheme.text.monoSmall,
            modifier = Modifier
                .size(PiSpacing.composerRingTouch)
                .clip(CircleShape)
                .clickable(
                    onClickLabel = "查看上下文与用量",
                    onClick = onOpenContext,
                ),
        )
    }
}


/**
 * 思考等级：**只剩文字**。2026-10-26 用户把 `◐` 图标删掉（「没什么用」），
 * 文字按钮本身保留：13sp Medium 无衬线、28 高、点一下循环到下一档。
 *
 * 流式时键行多出「引导 / 后续」两枚，这里由 [BoxWithConstraints] 统一决定是否
 * 显示（见 `Composer` 里的 `showThinking`），不是这枚按钮自己判断宽度。
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun ThinkingLevelButton(level: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(PiSpacing.composerThinkingHeight)
            .clip(RoundedCornerShape(6.dp))
            .combinedClickable(
                onClickLabel = "切换思考等级：${thinkingLabelOf(level)}",
                onClick = onClick,
                onLongClickLabel = "选择思考等级",
                onLongClick = onLongClick,
            )
            .padding(horizontal = PiSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = thinkingLabelOf(level),
            style = PiType.chipLabel,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}
