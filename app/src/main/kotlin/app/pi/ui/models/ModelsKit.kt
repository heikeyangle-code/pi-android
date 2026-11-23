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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/*
 * 模型与供应商页统一设计语言：
 *  - 0.5dp 发丝边 + 16dp 柔和圆角卡片 + 清晰的标题/副标题/操作层级；
 *  - 状态靠绿/主色圆点与简洁徽标说话，连接方式（订阅登录 / 网页登录 / API Key）一眼可见；
 *  - 主操作用实底按钮、次操作用柔和描边、危险操作（退出登录/删除）用红调柔和按钮，绝不把所有操作堆成灰标签。
 */

internal val ModelsCardPadding: Dp = 14.dp
internal val ModelsCardShape = RoundedCornerShape(16.dp)
private val ModelsButtonShape = RoundedCornerShape(12.dp)

@Composable
internal fun ModelsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Boolean = false,
    content: @Composable () -> Unit,
) {
    val border = if (accent) {
        PiTheme.palette.borderAccent.copy(alpha = 0.50f)
    } else {
        PiTheme.palette.borderMuted.copy(alpha = 0.55f)
    }
    val base = modifier
        .fillMaxWidth()
        .padding(horizontal = PiSettingsMetrics.pageHorizontal)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = base,
            shape = ModelsCardShape,
            border = BorderStroke(0.5.dp, border),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) { content() }
    } else {
        Surface(
            modifier = base,
            shape = ModelsCardShape,
            border = BorderStroke(0.5.dp, border),
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
            .padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = PiTheme.text.meta,
                color = PiTheme.palette.dim,
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
        idleBorder = PiTheme.palette.borderMuted.copy(alpha = 0.65f),
        leading = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = PiTheme.palette.muted,
                modifier = Modifier.size(18.dp),
            )
        },
        trailing = if (value.isNotEmpty()) {
            {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable { onValueChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = tr("清空搜索", "Clear search"),
                        tint = PiTheme.palette.muted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        } else {
            null
        },
    )
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
            .border(0.5.dp, tone.copy(alpha = 0.28f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            avatarInitial(name.ifBlank { id }),
            style = if (size >= 46.dp) MaterialTheme.typography.titleMedium else PiTheme.text.prose,
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
    Connection.WebLogin -> PiTheme.palette.borderAccent
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
        tone.copy(alpha = 0.13f)
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

/** 供应商行：头像 + 名字 + 认证方式提示 + 状态/数量；整行可点。 */
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
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    facts.title,
                    style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                    color = PiTheme.palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!c.connected) {
                    when {
                        facts.offersSubscription -> TagPill(tr("订阅", "Sub"), tone = PiTheme.palette.accent)
                        facts.offersWebLogin -> TagPill(tr("网页登录", "OAuth"), tone = PiTheme.palette.borderAccent)
                    }
                }
            }
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
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = PiTheme.palette.dim,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 模型行：名字 + 小标签；当前模型打标；[onClick] 为空表示只读（供应商没连接时）。 */
@Composable
internal fun ModelRow(
    model: ModelFacts,
    current: Boolean,
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    showProvider: String? = null,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = ModelsCardPadding, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                model.title,
                style = PiTheme.text.prose.copy(fontWeight = if (current || selected) FontWeight.Medium else FontWeight.Normal),
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

/** 一条发丝线（卡内行与行之间）。 */
@Composable
internal fun ModelsDivider(startIndent: Dp = 0.dp) {
    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(0.5.dp)
            .background(PiTheme.palette.borderMuted.copy(alpha = 0.48f)),
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
                color = PiTheme.palette.text.copy(alpha = 0.82f),
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
            container = MaterialTheme.colorScheme.surfaceContainer,
            idleBorder = PiTheme.palette.borderMuted.copy(alpha = 0.72f),
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
                                    tint = PiTheme.palette.muted,
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
                color = PiTheme.palette.dim,
            )
        }
    }
}

// ------------------------------------------------------------------ 按钮层级

/** 主操作按钮：accent 实底。支持行内胶囊或整宽主 CTA。 */
@Composable
internal fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fullWidth: Boolean = false,
) {
    val shape = if (fullWidth) ModelsButtonShape else RoundedCornerShape(50)
    Box(
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth().heightIn(min = 42.dp) else Modifier.heightIn(min = 36.dp))
            .clip(shape)
            .background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = if (fullWidth) 10.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = if (fullWidth) PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold) else PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
            color = if (enabled) MaterialTheme.colorScheme.onPrimary else PiTheme.palette.muted,
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
    val shape = if (fullWidth) ModelsButtonShape else RoundedCornerShape(50)
    Box(
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth().heightIn(min = 40.dp) else Modifier.heightIn(min = 36.dp))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(0.5.dp, PiTheme.palette.borderMuted.copy(alpha = 0.65f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = if (fullWidth) 9.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = if (enabled) PiTheme.palette.text else PiTheme.palette.muted,
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
    val shape = if (fullWidth) ModelsButtonShape else RoundedCornerShape(50)
    val err = PiTheme.palette.error
    Box(
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth().heightIn(min = 40.dp) else Modifier.heightIn(min = 36.dp))
            .clip(shape)
            .background(if (enabled) err.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(0.5.dp, if (enabled) err.copy(alpha = 0.35f) else PiTheme.palette.borderMuted.copy(alpha = 0.4f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = if (fullWidth) 9.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = if (enabled) err else PiTheme.palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
