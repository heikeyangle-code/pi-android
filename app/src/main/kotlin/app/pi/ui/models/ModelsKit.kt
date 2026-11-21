package app.pi.ui.models

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.ui.settings.PiEditorField
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/*
 * 模型与供应商页自己的一组小部件。页面的语气：
 *  - 一张卡一件事：当前模型是「主卡」，供应商是一行一块「头像 + 名字 + 状态」，不再把所有东西
 *    都压成同一种设置行；
 *  - 状态靠颜色和一个词说话（已连接是绿点 + 词，没连接是灰），不靠读一整句 supporting；
 *  - 能力（推理 / 看图 / 上下文）和价格是模型行上的小标签，不是拼在一起的一长串字。
 * 颜色全部来自主题令牌，不写死色值。
 */

/** 页面统一的卡内边距（卡自己画，不再套 `PiSettingsCard` 造成两层缩进）。 */
internal val ModelsCardPadding: Dp = 14.dp
internal val ModelsCardShape = RoundedCornerShape(16.dp)

/** 一张页面级的卡：整行宽，14dp 页边，`surfaceContainerLow` + 1px 细线，圆角 16。 */
@Composable
internal fun ModelsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Boolean = false,
    content: @Composable () -> Unit,
) {
    val border = if (accent) PiTheme.palette.borderAccent.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant
    val base = modifier
        .fillMaxWidth()
        .padding(horizontal = PiSettingsMetrics.pageHorizontal)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = base,
            shape = ModelsCardShape,
            border = BorderStroke(1.dp, border),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) { content() }
    } else {
        Surface(
            modifier = base,
            shape = ModelsCardShape,
            border = BorderStroke(1.dp, border),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) { content() }
    }
}

@Composable
internal fun ModelsSectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
            color = PiTheme.palette.muted,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

// ------------------------------------------------------------------ 头像

@Composable
private fun toneColor(seed: Int): Color {
    val p = PiTheme.palette
    return when (seed.coerceIn(0, AVATAR_TONES - 1)) {
        0 -> p.accent
        1 -> p.success
        2 -> p.warning
        3 -> p.error
        4 -> p.borderAccent
        else -> p.muted
    }
}

/** 供应商头像：同一个 id 永远同一种颜色（[avatarSeed]），里面是名字首字母。 */
@Composable
internal fun ProviderAvatar(id: String, name: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val tone = toneColor(avatarSeed(id))
    val shape = RoundedCornerShape(size * 0.3f)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(tone.copy(alpha = 0.14f))
            .border(1.dp, tone.copy(alpha = 0.25f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            avatarInitial(name.ifBlank { id }),
            style = PiTheme.text.prose,
            color = tone,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ------------------------------------------------------------------ 状态与标签

@Composable
internal fun connectionColor(c: Connection): Color = when (c) {
    Connection.None -> PiTheme.palette.dim
    Connection.Subscription -> PiTheme.palette.accent
    else -> PiTheme.palette.success
}

/** 「● 已连接」：一个点加一个词。 */
@Composable
internal fun ConnectionPill(c: Connection, modifier: Modifier = Modifier) {
    val color = connectionColor(c)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(color))
        Text(c.label, style = PiTheme.text.meta, color = if (c.connected) color else PiTheme.palette.muted, maxLines = 1)
    }
}

/** 模型的能力 / 价格小标签：柔和色块胶囊，字号同 meta。 */
@Composable
internal fun TagPill(text: String, modifier: Modifier = Modifier, tone: Color = PiTheme.palette.muted) {
    val bg = if (tone == PiTheme.palette.muted) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        tone.copy(alpha = 0.14f)
    }
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
        color = tone,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 一行小标签；放不下的由调用方控制个数（最多 4 个），不做换行。 */
@Composable
internal fun TagRow(tags: List<String>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        tags.forEach { TagPill(it) }
    }
}

// ------------------------------------------------------------------ 行

/** 供应商行：头像 + 名字 + 一行状态/数量；整行可点。 */
@Composable
internal fun ProviderRow(
    facts: ProviderFacts,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = connectionOf(facts)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ModelsCardPadding, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProviderAvatar(facts.id, facts.title)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                facts.title,
                style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                color = PiTheme.palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ConnectionPill(c)
                val extra = listOfNotNull(
                    if (facts.modelCount > 0) tr("${facts.modelCount} 个模型", "${facts.modelCount} models") else null,
                    if (facts.custom) tr("自定义", "Custom") else null,
                ).joinToString(" · ")
                if (extra.isNotEmpty()) {
                    Text(extra, style = PiTheme.text.meta, color = PiTheme.palette.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = PiTheme.palette.dim,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 模型行：名字 + 小标签；当前模型打一个勾；[onClick] 为空表示只读（供应商没连接时）。 */
@Composable
internal fun ModelRow(
    model: ModelFacts,
    current: Boolean,
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    showProvider: String? = null,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = ModelsCardPadding, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                model.title,
                style = PiTheme.text.prose,
                color = if (onClick == null) PiTheme.palette.muted else PiTheme.palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (current) TagPill(tr("当前", "Active"), tone = PiTheme.palette.accent)
        }
        val tags = capabilityTags(model) + listOfNotNull(priceLabel(model.inputCost, model.outputCost)?.let { "$it /M" })
        if (tags.isNotEmpty() || showProvider != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showProvider != null) Text(showProvider, style = PiTheme.text.meta, color = PiTheme.palette.dim, maxLines = 1)
                tags.forEach { TagPill(it) }
            }
        }
    }
}

/** 一条发丝线（卡内行与行之间），整幅宽，不缩进——行自己已有 14dp 内边距。 */
@Composable
internal fun ModelsDivider() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

// ------------------------------------------------------------------ 输入

/** 带标签的输入框；[secret] 为真时屏幕上不显示明文。 */
@Composable
internal fun LabeledField(
    label: String,
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = PiTheme.text.meta, color = PiTheme.palette.muted)
        PiEditorField(
            value = value,
            onValueChange = onChange,
            placeholder = placeholder,
            singleLine = singleLine,
            minHeight = if (singleLine) null else 72.dp,
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        )
    }
}

/** 主按钮：accent 实底（整页每个面板最多一个）。 */
@Composable
internal fun PrimaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = PiTheme.text.meta,
            color = if (enabled) MaterialTheme.colorScheme.onPrimary else PiTheme.palette.muted,
            fontWeight = FontWeight.Medium,
        )
    }
}
