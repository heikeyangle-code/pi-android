package app.pi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pi.ui.PiTopBar
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * 设置首页（SDK 重构版），按清晰职责分为五节，彻底去除旧版「模型与推理」和冗余的「引擎」入口：
 *  1. **模型与连接**：模型与供应商（含订阅登录、API Key、自定义模型、推理与缓存）、MCP 服务器；
 *  2. **扩展与工具**：扩展与资源（含项目信任、扩展/技能/提示/主题/包与运行时 flag）、工具；
 *  3. **对话与提示词**：系统提示词、对话与消息、上下文与压缩、网络与重试；
 *  4. **外观**：主题、配色、排版、思考块显示；
 *  5. **系统**：运行时与诊断（含导出 JSONL / 问题报告）、其他（终端、设备能力、安全与隐私、开源许可）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHome(
    store: PiSettingsStore,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenGroup: (String) -> Unit,
    onOpenModels: (() -> Unit)? = null,
    freshness: Int = 0,
    onOpenOther: (() -> Unit)? = null,
    onOpenMcp: (() -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val hits = remember(q) {
        if (q.isEmpty()) emptyList() else PiSettingsCatalog.settings.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.description.contains(q, ignoreCase = true) ||
                it.key.contains(q, ignoreCase = true)
        }.take(40)
    }
    val palette = PiTheme.palette
    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(title = "设置", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = contentPadding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupGap),
        ) {
            item(key = "search") {
                PiEditorField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                    placeholder = "搜索设置：名称、说明或键名",
                    height = PiSettingsMetrics.searchFieldHeight,
                )
            }
            if (q.isNotEmpty()) {
                if (hits.isEmpty()) {
                    item(key = "no-hits") {
                        PiSettingsCard { PiSettingsEntryRow(title = "没有匹配的设置", supporting = "换个词，或清空搜索看分组。") }
                    }
                } else {
                    item(key = "hits") {
                        PiSettingsCard {
                            hits.forEachIndexed { index, hit ->
                                if (index > 0) PiSettingsHairline()
                                PiSettingsEntryRow(
                                    title = hit.title,
                                    supporting = PiSettingsCatalog.groupTitle(hit.group) + " · " + hit.description,
                                    onClick = {
                                        if (hit.group == G_MODELS_SCREEN && onOpenModels != null) {
                                            onOpenModels()
                                        } else {
                                            onOpenGroup(hit.group)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                return@LazyColumn
            }

            fun groupCards(
                label: String,
                ids: List<String>,
                lead: List<HomeEntry> = emptyList(),
                tint: androidx.compose.ui.graphics.Color = palette.accent,
            ) {
                val groups = ids.mapNotNull { id -> PiSettingsCatalog.groups.firstOrNull { it.id == id } }
                if (groups.isEmpty() && lead.isEmpty()) return
                item(key = "label:$label") { HomeSectionLabel(label) }
                item(key = "card:$label") {
                    PiSettingsCard {
                        var first = true
                        lead.forEach { entry ->
                            if (!first) PiSettingsHairline()
                            first = false
                            PiSettingsEntryRow(
                                title = entry.title,
                                supporting = entry.supporting,
                                onClick = entry.onClick,
                                leadingIcon = entry.icon,
                                leadingTint = entry.tint,
                            )
                        }
                        groups.forEach { group ->
                            if (!first) PiSettingsHairline()
                            first = false
                            GroupEntry(group, store, freshness, tint) { onOpenGroup(group.id) }
                        }
                    }
                }
            }

            groupCards(
                "模型与连接",
                emptyList(),
                lead = listOfNotNull(
                    onOpenModels?.let { HomeEntry("模型与供应商", "订阅登录、API Key、自定义模型、思考等级与缓存", Icons.Filled.Timeline, palette.accent, it) },
                    onOpenMcp?.let { HomeEntry("MCP 服务器", "接入外部工具服务器：连接检测、OAuth 登录、暴露方式", Icons.Filled.Extension, palette.border, it) },
                ),
            )
            groupCards("扩展与工具", listOf("resources", "tools"), tint = palette.success)
            groupCards("对话与提示词", listOf("prompts", "messages", "compaction", "retry"), tint = palette.mdLink)
            groupCards("外观", listOf("appearance"), tint = palette.warning)
            item(key = "label:系统") { HomeSectionLabel("系统") }
            item(key = "card:系统") {
                PiSettingsCard {
                    PiSettingsEntryRow(
                        title = PiSettingsCatalog.groupTitle("runtime"),
                        supporting = PiSettingsCatalog.groups.firstOrNull { it.id == "runtime" }?.summary?.invoke(store),
                        onClick = { onOpenGroup("runtime") },
                        leadingIcon = Icons.Filled.Build,
                        leadingTint = palette.muted,
                    )
                    if (onOpenOther != null) {
                        PiSettingsHairline()
                        PiSettingsEntryRow(
                            title = "其他",
                            supporting = "终端、设备能力、安全与隐私、开源许可",
                            onClick = onOpenOther,
                            leadingIcon = Icons.Filled.MoreVert,
                            leadingTint = palette.muted,
                        )
                    }
                }
            }
        }
    }
}

private class HomeEntry(
    val title: String,
    val supporting: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tint: androidx.compose.ui.graphics.Color,
    val onClick: () -> Unit,
)

@Composable
private fun HomeSectionLabel(text: String) {
    androidx.compose.material3.Text(
        text,
        modifier = Modifier.padding(
            start = PiSettingsMetrics.pageHorizontal + PiSpacing.small,
            end = PiSettingsMetrics.pageHorizontal,
        ),
        style = PiTheme.text.meta,
        color = PiTheme.palette.muted,
    )
}

@Composable
private fun GroupEntry(
    group: PiSettingsGroup,
    store: PiSettingsStore,
    freshness: Int,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val summary = remember(store, group.id, freshness) { group.summary(store) }
    PiSettingsEntryRow(
        title = group.title,
        supporting = summary,
        onClick = onClick,
        leadingIcon = group.icon,
        leadingTint = tint,
    )
}
