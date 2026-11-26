package app.pi.ui.models

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.ui.settings.PiEditorField
import app.pi.ui.settings.PiNumberFieldHeight
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.contrastOn

/*
 * 模型与供应商页旗舰视觉构件（与设置页统一）：
 *  - surfaceContainerLow 柔和卡片 + 0.5dp 发丝描边 + 克制单色图标槽；
 *  - 状态由细圆点与克制微标传达，不堆砌高饱和彩虹徽章；
 *  - 选中态统一使用 accent 低透明度底色 + Check 矢量对勾（杜绝 M3 primaryContainer 紫染）；
 *  - 主 / 次 / 危险操作均带轻触反馈。
 */

internal val ModelsCardPadding: Dp = 14.dp
internal val ModelsCardShape = PiShapes.card

@Composable
internal fun ModelsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Boolean = false,
    content: @Composable () -> Unit,
) {
    val border = if (accent) {
        PiTheme.palette.accent.copy(alpha = 0.42f)
    } else {
        PiTheme.palette.borderMuted.copy(alpha = 0.45f)
    }
    val base = modifier
        .fillMaxWidth()
        .padding(horizontal = PiSettingsMetrics.pageHorizontal)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = base,
            shape = ModelsCardShape,
            border = BorderStroke(PiSpacing.hairlineThin, border),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) { content() }
    } else {
        Surface(
            modifier = base,
            shape = ModelsCardShape,
            border = BorderStroke(PiSpacing.hairlineThin, border),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) { content() }
    }
}

@Composable
internal fun ModelsSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = PiSettingsMetrics.pageHorizontal + PiSpacing.small,
                end = PiSettingsMetrics.pageHorizontal,
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text,
                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                color = PiTheme.palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            trailing?.invoke()
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 带搜索图标与一键清除的搜索栏。 */
@Composable
internal fun ModelsSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    PiEditorField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = placeholder,
        height = PiSettingsMetrics.searchFieldHeight,
        textStyle = PiTheme.text.prose,
        container = MaterialTheme.colorScheme.surfaceContainerLow,
        idleBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
        leading = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        },
        trailing = if (value.isNotEmpty()) {
            {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onValueChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = tr("清空搜索", "Clear search"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        } else {
            null
        },
    )
}

// ------------------------------------------------------------------ 头像（克制单色字标槽）

/** 供应商单色字标槽：与旗舰设置页图标槽完全一致的中性层级，当前激活时轻点 accent 描边。 */
@Composable
internal fun ProviderAvatar(
    id: String,
    name: String,
    size: Dp = 38.dp,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(size * 0.28f)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(
                if (active) palette.accent.copy(alpha = 0.11f)
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .border(
                PiSpacing.hairlineThin,
                if (active) palette.accent.copy(alpha = 0.42f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            avatarInitial(name.ifBlank { id }),
            style = if (size >= 46.dp) MaterialTheme.typography.titleMedium else PiTheme.text.prose,
            color = if (active) palette.accent else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ------------------------------------------------------------------ 状态与标签

@Composable
internal fun connectionColor(c: Connection): Color = when (c) {
    Connection.None -> PiTheme.palette.dim
    Connection.Subscription -> PiTheme.palette.accent
    Connection.WebLogin -> PiTheme.palette.accent
    else -> PiTheme.palette.success
}

/** 「● 已连接」：细圆点 + 克制状态文案。 */
@Composable
internal fun ConnectionPill(c: Connection, modifier: Modifier = Modifier) {
    val color = connectionColor(c)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (c.connected) color else color.copy(alpha = 0.50f)))
        Text(
            c.label,
            style = PiTheme.text.meta.copy(fontWeight = if (c.connected) FontWeight.Medium else FontWeight.Normal),
            color = if (c.connected) color else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 克制发丝微标：默认中性灰阶，显式传语义色时用低饱和 tint + 发丝边。 */
@Composable
internal fun TagPill(text: String, modifier: Modifier = Modifier, tone: Color = PiTheme.palette.muted) {
    val isNeutral = tone == PiTheme.palette.muted
    val shape = RoundedCornerShape(6.dp)
    val bg = if (isNeutral) MaterialTheme.colorScheme.surfaceContainerHigh else tone.copy(alpha = 0.10f)
    val borderColor = if (isNeutral) {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    } else {
        tone.copy(alpha = 0.32f)
    }
    Text(
        text,
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(PiSpacing.hairlineThin, borderColor, shape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
        color = if (isNeutral) MaterialTheme.colorScheme.onSurfaceVariant else tone,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 自适应换行的微标组，窄屏不截断模型价格或上下文标签。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagRow(tags: List<String>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        tags.forEach { TagPill(it) }
    }
}

// ------------------------------------------------------------------ 行

/** 统一供应商行：单色字标 + 标题 + 单一主微标 + 清晰副行状态/方式/域名；整行可点。 */
@Composable
internal fun ProviderRow(
    facts: ProviderFacts,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = connectionOf(facts)
    val palette = PiTheme.palette
    val hostHint = remember(facts.customBaseUrl) { shortEndpointHost(facts.customBaseUrl) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ModelsCardPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProviderAvatar(facts.id, facts.title, active = facts.isActiveProvider)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    facts.title,
                    style = PiTheme.text.prose.copy(fontWeight = if (facts.isActiveProvider || c.connected) FontWeight.SemiBold else FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                when {
                    facts.isActiveProvider -> TagPill(tr("当前", "Active"), tone = palette.accent)
                    facts.custom && facts.overridesBuiltin -> TagPill(tr("已覆盖", "Override"))
                    facts.custom -> TagPill(tr("自定义", "Custom"))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                ConnectionPill(c)
                val authHint = if (!c.connected) {
                    when {
                        facts.offersSubscription && facts.offersApiKey -> tr("订阅 / API Key", "Sub / API Key")
                        facts.offersSubscription -> tr("订阅账号", "Subscription")
                        facts.offersWebLogin && facts.offersApiKey -> tr("OAuth / API Key", "OAuth / API Key")
                        facts.offersWebLogin -> tr("网页授权", "OAuth")
                        facts.offersApiKey -> "API Key"
                        facts.hasAmbientAuth -> tr("环境凭据", "Env Auth")
                        else -> null
                    }
                } else {
                    null
                }
                val extra = listOfNotNull(
                    if (facts.modelCount > 0) tr("${facts.modelCount} 模型", "${facts.modelCount} models") else null,
                    authHint,
                    hostHint,
                ).joinToString(" · ")
                if (extra.isNotEmpty()) {
                    Text(
                        extra,
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 模型行：名字 + 能力/价格微标 + 清晰矢量勾选态；[onClick] 为空表示只读（供应商未连接时）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelRow(
    model: ModelFacts,
    current: Boolean,
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    showProvider: String? = null,
) {
    val palette = PiTheme.palette
    val bg = if (selected) palette.accent.copy(alpha = 0.08f) else Color.Transparent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = ModelsCardPadding, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    model.title,
                    style = PiTheme.text.prose.copy(fontWeight = if (current || selected) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (onClick == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (current) TagPill(tr("当前", "Active"), tone = palette.accent)
            }
            val tags = capabilityTags(model) + listOfNotNull(priceLabel(model.inputCost, model.outputCost)?.let { "$it /M" })
            if (tags.isNotEmpty() || showProvider != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (showProvider != null) {
                        Text(showProvider, style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    tags.forEach { TagPill(it) }
                }
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 一条发丝线（卡内行与行之间）。 */
@Composable
internal fun ModelsDivider(startIndent: Dp = 0.dp) {
    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(PiSpacing.hairlineThin)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
    )
}

// ------------------------------------------------------------------ 输入

/** 带清晰标题、可选提示语与密钥明文切换的表单输入框。 */
@Composable
internal fun LabeledField(
    label: String,
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    secret: Boolean = false,
    supporting: String? = null,
    labelTrailing: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    var revealed by remember(secret) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                label,
                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.84f),
            )
            labelTrailing?.invoke()
        }
        PiEditorField(
            value = value,
            onValueChange = onChange,
            placeholder = placeholder,
            singleLine = singleLine,
            height = if (singleLine) PiNumberFieldHeight else null,
            minHeight = if (singleLine) null else 80.dp,
            container = MaterialTheme.colorScheme.surfaceContainerLowest,
            idleBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
            visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            trailing = if (secret || trailing != null) {
                {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (secret) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { revealed = !revealed },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (revealed) tr("隐藏密钥", "Hide key") else tr("显示密钥", "Show key"),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        trailing?.invoke()
                    }
                }
            } else {
                null
            },
        )
        if (!supporting.isNullOrBlank()) {
            Text(
                supporting,
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------ 按钮层级（带轻触反馈）

/** 主操作按钮：accent 实底。支持行内胶囊或整宽主 CTA。 */
@Composable
internal fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fullWidth: Boolean = false,
) {
    val palette = PiTheme.palette
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth().heightIn(min = 40.dp) else Modifier.heightIn(min = 36.dp))
            .clip(shape)
            .background(if (enabled) palette.accent else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = if (fullWidth) 10.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = if (fullWidth) PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold) else PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
            color = if (enabled) palette.contrastOn(palette.accent) else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 次操作按钮：柔和灰底 + 发丝描边，与 PrimaryAction 配套。 */
@Composable
internal fun SecondaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fullWidth: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth().heightIn(min = 40.dp) else Modifier.heightIn(min = 36.dp))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(PiSpacing.hairlineThin, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f), shape)
            .clickable(enabled = enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = if (fullWidth) 9.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 危险操作按钮（退出登录 / 删除凭据 / 删除自定义供应商）。 */
@Composable
internal fun DestructiveAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fullWidth: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(10.dp)
    val err = PiTheme.palette.error
    Box(
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth().heightIn(min = 40.dp) else Modifier.heightIn(min = 36.dp))
            .clip(shape)
            .background(if (enabled) err.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                PiSpacing.hairlineThin,
                if (enabled) err.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                shape,
            )
            .clickable(enabled = enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = if (fullWidth) 9.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = if (enabled) err else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
