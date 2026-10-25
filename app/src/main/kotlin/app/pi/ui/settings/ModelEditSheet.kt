package app.pi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pi.ui.components.PiSheet
import app.pi.packages.PiModelsFile
import app.pi.ui.components.PiType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * 一条模型在编辑器里能改的那几项。
 *
 * `null` 的语义是**"不写这个键"**，不是 0：pi 的 `models.json` 里缺席的字段由它自己补
 * （上下文 128000、最大输出 16384、仅文本、价格 0）。所以表单里留空 = 交还给 pi，
 * 这与"填 0"是两件事（价格 0 是一句"免费"的断言）。
 *
 * 单位只在这里统一一次：`contextWindow`/`maxTokens` 是 **token 数**；`costInput`/
 * `costOutput` 是 **USD / 每百万 token**（pi 的 `cost` 就是这个单位，模型选择器显示
 * `$3 / M`）。表单的标签把单位写在脸上，换算一律不做 —— 厂商给的 per-token 价在
 * [app.pi.packages.PiScanVendorMeta] 里已经乘过一次了。
 */
internal data class ModelFields(
    val name: String? = null,
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
    val images: Boolean? = null,
    val costInput: Double? = null,
    val costOutput: Double? = null,
    val costCacheRead: Double? = null,
    val costCacheWrite: Double? = null,
    val reasoning: Boolean? = null,
    /**
     * 这两个**不在表单里**，只跟着值走：编辑一个官方目录里的模型会把它整条复制进
     * `models.json`，复制时漏掉它们就等于把这个模型的思考档位/采样参数抹平。
     */
    val samplingParams: JsonObject? = null,
    val thinkingLevelMap: JsonObject? = null,
    /** 长上下文档位价：同 samplingParams，不在表单里，只跟着值走。 */
    val costTiers: JsonArray? = null,
) {
    /** 变成 `models.json` 里的一条定义。只带知道的字段，未知的留空。 */
    fun toModel(id: String): PiModelsFile.Model = PiModelsFile.Model(
        id = id,
        name = name,
        reasoning = reasoning,
        contextWindow = contextWindow,
        maxTokens = maxTokens,
        // 图片是 pi 的 `input` 数组：知道支持才写 ["text","image"]，否则省略 —— 省略时
        // pi 按纯文本处理，等于"没替厂商断言"。
        input = if (images == true) listOf("text", "image") else emptyList(),
        costInput = costInput,
        costOutput = costOutput,
        costCacheRead = costCacheRead,
        costCacheWrite = costCacheWrite,
        samplingParams = samplingParams,
        thinkingLevelMap = thinkingLevelMap,
        costTiers = costTiers,
    )

    companion object {
        fun of(model: PiModelsFile.Model): ModelFields = ModelFields(
            name = model.name,
            contextWindow = model.contextWindow,
            maxTokens = model.maxTokens,
            images = when {
                model.input.any { it.equals("image", ignoreCase = true) } -> true
                model.input.isEmpty() -> null
                else -> false
            },
            costInput = model.costInput,
            costOutput = model.costOutput,
            costCacheRead = model.costCacheRead,
            costCacheWrite = model.costCacheWrite,
            reasoning = model.reasoning,
            samplingParams = model.samplingParams,
            thinkingLevelMap = model.thinkingLevelMap,
            costTiers = model.costTiers,
        )
    }
}

/**
 * 模型能力的表单：上下文、最大输出、图片、两条价格、推理。
 *
 * 输入框自己留一份字符串状态（数字解析失败时不能把框清掉，那会让用户没法改 midway），
 * 每次输入都尝试解析并把**合法的那份**交给 [onChanged]；解析不了就不交、只标红。
 */
@Composable
internal fun ModelFieldsForm(
    initial: ModelFields,
    onChanged: (ModelFields) -> Unit,
) {
    var contextText by remember { mutableStateOf(initial.contextWindow?.toString().orEmpty()) }
    var maxOutputText by remember { mutableStateOf(initial.maxTokens?.toString().orEmpty()) }
    var images by remember { mutableStateOf(initial.images == true) }
    var costInText by remember { mutableStateOf(costText(initial.costInput)) }
    var costOutText by remember { mutableStateOf(costText(initial.costOutput)) }
    var cacheReadText by remember { mutableStateOf(costText(initial.costCacheRead)) }
    var cacheWriteText by remember { mutableStateOf(costText(initial.costCacheWrite)) }
    var reasoning by remember { mutableStateOf(initial.reasoning == true) }
    // 没碰过的开关**不写**：把它们当成 false 写下去，等于替厂商断言"不支持图片 /
    // 不是推理模型"，而目录里那条可能本来就是 true（也可能根本没说过）。只改一个价格
    // 就把这两项改掉，是这类表单最典型的静默降级。
    var imagesTouched by remember { mutableStateOf(false) }
    var reasoningTouched by remember { mutableStateOf(false) }

    fun emit(
        context: Long? = parseToken(contextText),
        maxOutput: Long? = parseToken(maxOutputText),
        costIn: Double? = parseCost(costInText),
        costOut: Double? = parseCost(costOutText),
        cacheRead: Double? = parseCost(cacheReadText),
        cacheWrite: Double? = parseCost(cacheWriteText),
        imagesNow: Boolean = if (imagesTouched) images else initial.images == true,
        reasoningNow: Boolean = if (reasoningTouched) reasoning else initial.reasoning == true,
    ) {
        onChanged(
            ModelFields(
                name = initial.name,
                contextWindow = context,
                maxTokens = maxOutput,
                images = if (imagesTouched) imagesNow else initial.images,
                costInput = costIn,
                costOutput = costOut,
                costCacheRead = cacheRead,
                costCacheWrite = cacheWrite,
                reasoning = if (reasoningTouched) reasoningNow else initial.reasoning,
                samplingParams = initial.samplingParams,
                thinkingLevelMap = initial.thinkingLevelMap,
                costTiers = initial.costTiers,
            ),
        )
    }

    Column(Modifier.padding(horizontal = PiSettingsMetrics.cardPadding)) {
        PiEditorField(
            value = contextText,
            onValueChange = { contextText = it; emit() },
            label = "上下文窗口（token）",
            placeholder = "留空 = pi 的默认（128000）",
            height = 44.dp,
            modifier = Modifier.fillMaxWidth(),
        )
        if (contextText.isNotBlank() && parseToken(contextText) == null) {
            FieldProblem("要一个正整数（token 数），或留空。")
        }
        Spacer(Modifier.height(PiSpacing.small))
        PiEditorField(
            value = maxOutputText,
            onValueChange = { maxOutputText = it; emit() },
            label = "最大输出（token）",
            placeholder = "留空 = pi 的默认（16384）",
            height = 44.dp,
            modifier = Modifier.fillMaxWidth(),
        )
        if (maxOutputText.isNotBlank() && parseToken(maxOutputText) == null) {
            FieldProblem("要一个正整数（token 数），或留空。")
        }
        Spacer(Modifier.height(PiSpacing.small))
        PiEditorField(
            value = costInText,
            onValueChange = { costInText = it; emit() },
            label = "输入价（美元 / 百万 token）",
            placeholder = "如 3；留空 = pi 的默认（0）",
            height = 44.dp,
            modifier = Modifier.fillMaxWidth(),
        )
        if (costInText.isNotBlank() && parseCost(costInText) == null) {
            FieldProblem("要一个不小于 0 的数（美元 / 百万 token），或留空。")
        }
        Spacer(Modifier.height(PiSpacing.small))
        PiEditorField(
            value = costOutText,
            onValueChange = { costOutText = it; emit() },
            label = "输出价（美元 / 百万 token）",
            placeholder = "如 15；留空 = pi 的默认（0）",
            height = 44.dp,
            modifier = Modifier.fillMaxWidth(),
        )
        if (costOutText.isNotBlank() && parseCost(costOutText) == null) {
            FieldProblem("要一个不小于 0 的数（美元 / 百万 token），或留空。")
        }
        Spacer(Modifier.height(PiSpacing.small))
        PiEditorField(
            value = cacheReadText,
            onValueChange = { cacheReadText = it; emit() },
            label = "缓存读价（美元 / 百万 token）",
            placeholder = "留空 = 不写（pi 的默认）",
            height = 44.dp,
            modifier = Modifier.fillMaxWidth(),
        )
        if (cacheReadText.isNotBlank() && parseCost(cacheReadText) == null) {
            FieldProblem("要一个不小于 0 的数（美元 / 百万 token），或留空。")
        }
        Spacer(Modifier.height(PiSpacing.small))
        PiEditorField(
            value = cacheWriteText,
            onValueChange = { cacheWriteText = it; emit() },
            label = "缓存写价（美元 / 百万 token）",
            placeholder = "留空 = 不写（pi 的默认）",
            height = 44.dp,
            modifier = Modifier.fillMaxWidth(),
        )
        if (cacheWriteText.isNotBlank() && parseCost(cacheWriteText) == null) {
            FieldProblem("要一个不小于 0 的数（美元 / 百万 token），或留空。")
        }
        Spacer(Modifier.height(PiSpacing.small))
        PiSettingsToggleRow(
            title = "支持图片",
            supporting = "勾上才写 input = text,image；不勾 = 纯文本",
            checked = images,
            onCheckedChange = { on ->
                images = on
                imagesTouched = true
                emit(imagesNow = on)
            },
        )
        PiSettingsHairline()
        PiSettingsToggleRow(
            title = "推理模型",
            supporting = "会写 reasoning: true/false",
            checked = reasoning,
            onCheckedChange = { on ->
                reasoning = on
                reasoningTouched = true
                emit(reasoningNow = on)
            },
        )
    }
}

@Composable
private fun FieldProblem(text: String) {
    Text(
        text,
        style = PiTheme.text.meta,
        color = PiTheme.palette.warning,
        modifier = Modifier.padding(top = PiSpacing.unit),
    )
}

/**
 * 一条模型的编辑浮层。导入时与导入后共用同一支表单 —— 两处各写一份就会出现
 * "导入时能填、导入后填了不生效"这种两套规则。
 *
 * @param canReset 这个模型现在是**申报过**的（`models.json` 里有它），所以能"恢复 pi 默认"；
 *        目录里本来就没有定义的模型（自建厂商）没有可恢复的东西。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelEditSheet(
    title: String,
    subtitle: String,
    initial: ModelFields,
    canReset: Boolean,
    onReset: (() -> Unit)?,
    /** 申报过、且 pi 目录里没有它 = 删掉这条申报模型就真正消失（自建厂商的模型）。 */
    canDelete: Boolean = false,
    onDelete: (() -> Unit)? = null,
    onClose: () -> Unit,
    onSave: (ModelFields) -> Unit,
) {
    var current by remember { mutableStateOf(initial) }
    PiSheet(onDismiss = onClose) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                title,
                style = PiType.title,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(
                    horizontal = PiSettingsMetrics.cardPadding,
                    vertical = PiSpacing.small,
                ),
            )
            Text(
                subtitle,
                style = PiTheme.text.monoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = PiSettingsMetrics.cardPadding),
            )
            Spacer(Modifier.height(PiSpacing.small))
            ModelFieldsForm(initial = initial, onChanged = { current = it })
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = PiSettingsMetrics.cardPadding,
                        vertical = PiSpacing.small,
                    ),
                horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
            ) {
                TextButton(onClick = { onSave(current) }) { Text("保存") }
                if (canReset && onReset != null) {
                    TextButton(onClick = onReset) { Text("恢复 pi 默认") }
                }
                if (canDelete && onDelete != null) {
                    TextButton(onClick = onDelete) { Text("删除这个模型") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("取消") }
            }
        }
    }
}

/** 空的输入框 = "不写这个键"，不是 0。 */
private fun parseToken(text: String): Long? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val value = trimmed.toLongOrNull() ?: return null
    return value.takeIf { it in 1..10_000_000L }
}

private fun parseCost(text: String): Double? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val value = trimmed.toDoubleOrNull() ?: return null
    return value.takeIf { it >= 0.0 && it <= 1_000_000.0 }
}

/** 价格回填：去掉尾随 0（`3.0` → `3`），与清单页同一套写法。 */
private fun costText(value: Double?): String {
    if (value == null) return ""
    return String.format(java.util.Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')
}
