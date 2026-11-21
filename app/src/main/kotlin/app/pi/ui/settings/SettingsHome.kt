package app.pi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Memory
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
import app.pi.i18n.PiLocale
import app.pi.i18n.tr
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
    onOpenKey: ((String) -> Unit)? = null,
    onOpenModels: (() -> Unit)? = null,
    freshness: Int = 0,
    onOpenRuntime: (() -> Unit)? = null,
    runtimeSummary: String? = null,
    onOpenOther: (() -> Unit)? = null,
    onOpenMcp: (() -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val customHits = remember(q, PiLocale.isZh) {
        if (q.isEmpty()) emptyList() else buildCustomSearchEntries().filter { entry ->
            entry.title.contains(q, ignoreCase = true) ||
                entry.subtitle.contains(q, ignoreCase = true) ||
                entry.keywords.contains(q, ignoreCase = true)
        }
    }
    val settingHits = remember(q, PiLocale.isZh) {
        if (q.isEmpty()) emptyList() else PiSettingsCatalog.settings.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.description.contains(q, ignoreCase = true) ||
                it.key.contains(q, ignoreCase = true)
        }.take(40)
    }
    val palette = PiTheme.palette
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrolled by remember(listState) {
        androidx.compose.runtime.derivedStateOf { listState.canScrollBackward }
    }
    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(title = tr("设置", "Settings"), onBack = onBack, scrolled = scrolled)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = contentPadding.calculateBottomPadding() + PiSpacing.scrollBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupGap),
        ) {
            item(key = "search") {
                app.pi.ui.models.ModelsSearchBar(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = tr("搜索设置、功能、MCP 或键名", "Search settings, features, MCP, or key"),
                    modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal),
                )
            }
            if (q.isNotEmpty()) {
                if (customHits.isEmpty() && settingHits.isEmpty()) {
                    item(key = "no-hits") {
                        PiSettingsCard {
                            PiSettingsEntryRow(
                                title = tr("没有匹配的设置", "No matching settings"),
                                supporting = tr("换个词，或清空搜索看分组。", "Try another term, or clear search to browse groups."),
                            )
                        }
                    }
                } else {
                    item(key = "hits") {
                        PiSettingsCard {
                            var rendered = 0
                            customHits.forEach { hit ->
                                if (rendered > 0) PiSettingsHairline()
                                rendered++
                                PiSettingsEntryRow(
                                    title = hit.title,
                                    supporting = hit.subtitle,
                                    onClick = { onOpenKey?.invoke(hit.routeKey) },
                                )
                            }
                            settingHits.forEach { hit ->
                                if (rendered > 0) PiSettingsHairline()
                                rendered++
                                PiSettingsEntryRow(
                                    title = hit.title,
                                    supporting = PiSettingsCatalog.groupTitle(hit.group) + " · " + hit.description,
                                    onClick = {
                                        if (onOpenKey != null) {
                                            onOpenKey(hit.key)
                                        } else if (hit.group == G_MODELS_SCREEN && onOpenModels != null) {
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
                sectionKey: String,
                label: String,
                ids: List<String>,
                lead: List<HomeEntry> = emptyList(),
                tint: androidx.compose.ui.graphics.Color = palette.accent,
            ) {
                val groups = ids.mapNotNull { id -> PiSettingsCatalog.groups.firstOrNull { it.id == id } }
                if (groups.isEmpty() && lead.isEmpty()) return
                item(key = "label:$sectionKey") { HomeSectionLabel(label) }
                item(key = "card:$sectionKey") {
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
                sectionKey = "models-conn",
                label = tr("模型与连接", "Models & Connections"),
                ids = emptyList(),
                lead = listOfNotNull(
                    onOpenModels?.let {
                        HomeEntry(
                            tr("模型与供应商", "Models & Providers"),
                            tr("订阅登录、API Key、自定义模型、思考等级与缓存", "Subscriptions, API keys, custom models, thinking level, and caching"),
                            Icons.Filled.Timeline,
                            palette.accent,
                            it,
                        )
                    },
                    onOpenMcp?.let {
                        HomeEntry(
                            tr("MCP 服务器", "MCP Servers"),
                            tr("接入外部工具服务器：连接检测、OAuth 登录、暴露方式", "External tool servers: health checks, OAuth login, tool exposure"),
                            Icons.Filled.Extension,
                            palette.mdLink,
                            it,
                        )
                    },
                ),
            )
            groupCards("ext-tools", tr("扩展与工具", "Extensions & Tools"), listOf("resources", "tools"), tint = palette.success)
            groupCards("chat-prompts", tr("对话与外观", "Chat & Appearance"), listOf("prompts", "messages", "compaction", "retry", "appearance"), tint = palette.mdLink)
            item(key = "label:system") { HomeSectionLabel(tr("系统", "System")) }
            item(key = "card:system") {
                PiSettingsCard {
                    if (onOpenRuntime != null) {
                        PiSettingsEntryRow(
                            title = tr("引擎与运行环境", "Engine & Runtime"),
                            supporting = runtimeSummary ?: tr(
                                "引擎状态、容器运行时（proot / proroot / tawcroot）、工具链自检与诊断报告",
                                "Engine status, container runtime (proot / proroot / tawcroot), self-check & diagnostics",
                            ),
                            onClick = onOpenRuntime,
                            leadingIcon = Icons.Filled.Memory,
                            leadingTint = palette.accent,
                        )
                    }
                    if (onOpenOther != null) {
                        if (onOpenRuntime != null) PiSettingsHairline()
                        PiSettingsEntryRow(
                            title = tr("其他", "More"),
                            supporting = tr("终端、设备能力、安全与隐私、开源许可", "Terminal, device capabilities, security & privacy, licenses"),
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

private class CustomSearchEntry(
    val routeKey: String,
    val title: String,
    val subtitle: String,
    val keywords: String,
)

private fun buildCustomSearchEntries(): List<CustomSearchEntry> = listOf(
    CustomSearchEntry(
        routeKey = "hub:models",
        title = tr("模型与供应商", "Models & Providers"),
        subtitle = tr("订阅登录（OAuth）、API Key、自定义供应商、缓存", "OAuth subscriptions, API keys, custom providers, caching"),
        keywords = "model provider oauth api key login defaultModel defaultProvider custom models.json auth",
    ),
    CustomSearchEntry(
        routeKey = "hub:mcp",
        title = tr("MCP 服务器", "MCP Servers"),
        subtitle = tr("配置外部 MCP 工具服务器与 OAuth 登录", "Configure external MCP servers and OAuth login"),
        keywords = "mcp server json oauth tool stdio http sse",
    ),
    CustomSearchEntry(
        routeKey = "hub:resources",
        title = tr("扩展与资源", "Extensions & Resources"),
        subtitle = tr("管理扩展、技能、提示模板、主题与 npm 包", "Manage extensions, skills, prompt templates, themes, and packages"),
        keywords = "extension skill prompt theme package npm trust project resources",
    ),
    CustomSearchEntry(
        routeKey = "hub:tools",
        title = tr("默认工具与工具开关", "Tools & Default Tools"),
        subtitle = tr("实时启停内置/扩展工具，写入 defaultTools", "Toggle active tools live and save defaultTools"),
        keywords = "defaultTools bash read edit write grep find ls codemode shell",
    ),
    CustomSearchEntry(
        routeKey = "hub:runtime",
        title = tr("引擎与运行环境", "Engine & Runtime"),
        subtitle = tr("proot / proroot / tawcroot、后台保活、自检与诊断报告", "proot / proroot / tawcroot, keep-alive, self-check & diagnostics"),
        keywords = "proot proroot tawcroot runtime engine keepAlive app.runtime.keepAlive diagnostics report",
    ),
    CustomSearchEntry(
        routeKey = "hub:device",
        title = tr("设备能力", "Device Capabilities"),
        subtitle = tr("无障碍屏幕自动化、通知监听、Shizuku 设备 Shell", "Accessibility automation, notification access, Shizuku device shell"),
        keywords = "device capability accessibility shizuku notification bridge permission",
    ),
    CustomSearchEntry(
        routeKey = "hub:terminal",
        title = tr("内置终端", "Built-in Terminal"),
        subtitle = tr("打开容器全屏 Shell 终端", "Open the full-screen guest shell terminal"),
        keywords = "terminal shell pty bash console",
    ),
    CustomSearchEntry(
        routeKey = "hub:licenses",
        title = tr("开源许可", "Open Source Licenses"),
        subtitle = tr("查看第三方组件与协议声明", "View third-party dependency license notices"),
        keywords = "license apache mit gpl notice third party",
    ),
)

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
    val summary = remember(store, group.id, freshness, PiLocale.isZh) { group.summary(store) }
    PiSettingsEntryRow(
        title = group.title,
        supporting = summary,
        onClick = onClick,
        leadingIcon = group.icon,
        leadingTint = tint,
    )
}
