package app.pi.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pi.i18n.PiLocale
import app.pi.i18n.tr
import app.pi.ui.PiTopBar
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.serialization.json.JsonPrimitive

private val ADVANCED_NETWORK_KEYS = setOf(
    "websocketConnectTimeoutMs",
    "retry.provider.timeoutMs",
    "retry.provider.maxRetries",
    "retry.provider.maxRetryDelayMs",
)

/**
 * 现代「网络与重试」专页（100% 对齐 Pi 1.6.0 `http-dispatcher` / `sdk.js` / `AgentSession`）：
 * - 连接卡：`transport` 1-tap 内联分段切换、`httpIdleTimeoutMs` 对齐官方 5 档选择（30秒 / 1分 / 2分 / 5分 / 不超时）
 *   且完整兼容桌面 Pi `"disabled"` 字符串、`httpProxy` 代理入口（经官方 `http-dispatcher` 实时生效）；
 * - 自动重试卡：总开关 + 次数 / 退避起始延迟 / 单次等待上限 1-tap 快捷胶囊（人类可读秒/分）；
 * - 高级网络与 Provider 底层参数：默认折叠（避免 11 行平铺信息过载与误调 `retry.provider.maxRetries`），
 *   已自定义时显示角标，搜索命中时自动展开。
 */
@Composable
internal fun NetworkRetryScreen(
    store: PiSettingsStore,
    freshness: Int,
    contentPadding: PaddingValues,
    onSettingWritten: (String) -> Unit,
    onBack: () -> Unit,
    highlightKey: String? = null,
) {
    val palette = PiTheme.palette
    val listState = rememberLazyListState()
    val scrolled by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    var editing by remember { mutableStateOf<PiSetting?>(null) }
    var advancedExpanded by remember(highlightKey) { mutableStateOf(highlightKey in ADVANCED_NETWORK_KEYS) }

    val byKey = remember(freshness, PiLocale.isZh) { PiSettingsCatalog.byKey }
    val transportSetting = byKey["transport"]
    val httpIdleSetting = byKey["httpIdleTimeoutMs"]
    val wsTimeoutSetting = byKey["websocketConnectTimeoutMs"]
    val proxySetting = byKey["httpProxy"]
    val retryEnabledSetting = byKey["retry.enabled"]
    val retryMaxSetting = byKey["retry.maxRetries"]
    val retryBaseSetting = byKey["retry.baseDelayMs"]
    val retryMaxDelaySetting = byKey["retry.maxAgentDelayMs"]
    val providerTimeoutSetting = byKey["retry.provider.timeoutMs"]
    val providerRetriesSetting = byKey["retry.provider.maxRetries"]
    val providerMaxDelaySetting = byKey["retry.provider.maxRetryDelayMs"]

    val transport = remember(store, freshness) { transportSetting?.textIn(store, "auto") ?: "auto" }
    val httpIdleMs = remember(store, freshness) { httpIdleSetting?.timeoutMsIn(store, 300_000) ?: 300_000 }
    val wsTimeoutMs = remember(store, freshness) { wsTimeoutSetting?.timeoutMsIn(store, 15_000) ?: 15_000 }
    val proxyText = remember(store, freshness) { proxySetting?.textIn(store, "") ?: "" }
    val retryEnabled = remember(store, freshness) { retryEnabledSetting?.boolIn(store, true) ?: true }
    val retryMax = remember(store, freshness) { retryMaxSetting?.intIn(store, 3) ?: 3 }
    val retryBaseMs = remember(store, freshness) { retryBaseSetting?.intIn(store, 2_000) ?: 2_000 }
    val retryMaxDelayMs = remember(store, freshness) { retryMaxDelaySetting?.intIn(store, 60_000) ?: 60_000 }
    val providerTimeoutMs = remember(store, freshness) { store.read("retry.provider.timeoutMs")?.intValueOrNull() }
    val providerMaxRetries = remember(store, freshness) { providerRetriesSetting?.intIn(store, 0) ?: 0 }
    val providerMaxDelayMs = remember(store, freshness) { providerMaxDelaySetting?.intIn(store, 60_000) ?: 60_000 }

    val customizedAdvancedCount = remember(store, freshness) {
        listOf(
            wsTimeoutSetting?.isExplicit(store) == true && wsTimeoutMs != 15_000,
            providerTimeoutMs != null,
            providerRetriesSetting?.isExplicit(store) == true && providerMaxRetries != 0,
            providerMaxDelaySetting?.isExplicit(store) == true && providerMaxDelayMs != 60_000,
        ).count { it }
    }

    LaunchedEffect(highlightKey) {
        if (highlightKey in ADVANCED_NETWORK_KEYS) {
            advancedExpanded = true
        }
        val targetIndex = when (highlightKey) {
            "transport", "httpIdleTimeoutMs", "httpProxy" -> 0
            "retry.enabled", "retry.maxRetries", "retry.baseDelayMs", "retry.maxAgentDelayMs" -> 1
            in ADVANCED_NETWORK_KEYS -> 2
            else -> -1
        }
        if (targetIndex >= 0) listState.animateScrollToItem(targetIndex)
    }

    fun writeString(key: String, value: String) {
        store.write(key, JsonPrimitive(value))
        onSettingWritten(key)
    }

    fun writeBool(key: String, value: Boolean) {
        store.write(key, JsonPrimitive(value))
        onSettingWritten(key)
    }

    fun writeInt(key: String, value: Int) {
        store.write(key, JsonPrimitive(value))
        onSettingWritten(key)
    }

    val transportChoices = remember(PiLocale.isZh) {
        listOf(
            "auto" to tr("自动", "Auto"),
            "sse" to "SSE",
            "websocket" to "WS",
            "websocket-cached" to tr("WS 缓存", "WS Cache"),
        )
    }
    val httpIdleChoices = remember(PiLocale.isZh) {
        listOf(
            30_000 to tr("30秒", "30s"),
            60_000 to tr("1分钟", "1 min"),
            120_000 to tr("2分钟", "2 min"),
            300_000 to tr("5分钟", "5 min"),
            0 to tr("不超时", "Disabled"),
        )
    }
    val wsTimeoutChoices = remember(PiLocale.isZh) {
        listOf(
            5_000 to tr("5秒", "5s"),
            15_000 to tr("15秒", "15s"),
            30_000 to tr("30秒", "30s"),
            60_000 to tr("60秒", "60s"),
            0 to tr("不限时", "Disabled"),
        )
    }
    val maxRetriesChoices = remember(PiLocale.isZh) {
        listOf(
            1 to tr("1次", "1×"),
            3 to tr("3次", "3×"),
            5 to tr("5次", "5×"),
            8 to tr("8次", "8×"),
        )
    }
    val baseDelayChoices = remember(PiLocale.isZh) {
        listOf(
            1_000 to tr("1秒", "1s"),
            2_000 to tr("2秒", "2s"),
            5_000 to tr("5秒", "5s"),
            10_000 to tr("10秒", "10s"),
        )
    }
    val maxAgentDelayChoices = remember(PiLocale.isZh) {
        listOf(
            15_000 to tr("15秒", "15s"),
            30_000 to tr("30秒", "30s"),
            60_000 to tr("1分钟", "1 min"),
            120_000 to tr("2分钟", "2 min"),
        )
    }

    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(
            title = tr("网络与重试", "Network & Retry"),
            onBack = onBack,
            scrolled = scrolled,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = contentPadding.calculateBottomPadding() + PiSpacing.scrollBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupGap),
        ) {
            // 1. 连接与代理
            item(key = "connection") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupHeaderGap)) {
                    PiSettingsSectionHeader(tr("连接与代理", "Connection & Proxy"))
                    PiSettingsCard {
                        PiInlineSegmentRow(
                            title = transportSetting?.title ?: tr("传输方式", "Transport"),
                            description = tr(
                                "支持多种传输方式的厂商（如 OpenAI / Codex）优先使用的连接协议。",
                                "Preferred protocol for providers supporting multiple transports (e.g. SSE / WebSocket).",
                            ),
                            options = transportChoices,
                            selected = transport,
                            onSelect = { writeString("transport", it) },
                            highlighted = highlightKey == "transport",
                        )
                        PiSettingsHairline()
                        PiInlinePresetRow(
                            title = httpIdleSetting?.title ?: tr("HTTP 空闲超时", "HTTP Idle Timeout"),
                            description = tr(
                                "等待响应头或流式数据块的最大空闲间隔；本地推理模型停顿较久时可设为「不超时」。",
                                "Max idle gap waiting for HTTP headers or stream chunks. Disable for slow local models.",
                            ),
                            valueText = formatSettingNumber(KEY_HTTP_IDLE_TIMEOUT_MS, httpIdleMs, "ms"),
                            presets = httpIdleChoices,
                            selected = httpIdleMs,
                            onSelect = { writeInt(KEY_HTTP_IDLE_TIMEOUT_MS, it) },
                            onCustomClick = httpIdleSetting?.let { s -> { editing = s } },
                            highlighted = highlightKey == KEY_HTTP_IDLE_TIMEOUT_MS,
                        )
                        if (proxySetting != null) {
                            PiSettingsHairline()
                            PiSettingsEntryRow(
                                title = proxySetting.title,
                                supporting = if (proxyText.isBlank()) {
                                    tr("直连（未设置）· 可配置 http:// 或 socks 代理，即时生效", "Direct (not set) · Configure HTTP/HTTPS proxy URL, applies live")
                                } else {
                                    proxyText
                                },
                                onClick = { editing = proxySetting },
                                trailingBadge = if (proxyText.isNotBlank()) {
                                    { PiSettingsBadge(label = tr("已启用", "Active"), tone = palette.accent) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }

            // 2. 自动重试
            item(key = "retry") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupHeaderGap)) {
                    PiSettingsSectionHeader(tr("自动重试", "Auto-Retry"))
                    PiSettingsCard {
                        if (retryEnabledSetting != null) {
                            PiSettingRow(
                                setting = retryEnabledSetting,
                                valueText = retryEnabledSetting.display(retryEnabledSetting.current(store)),
                                checked = retryEnabled,
                                highlighted = highlightKey == "retry.enabled",
                                onToggle = { writeBool(retryEnabledSetting.key, it) },
                                onOpen = {},
                                onExplainEffect = {},
                            )
                        }
                        AnimatedVisibility(visible = retryEnabled) {
                            Column {
                                PiSettingsHairline()
                                PiInlinePresetRow(
                                    title = retryMaxSetting?.title ?: tr("最大重试次数", "Max Retries"),
                                    description = tr(
                                        "遇到 429 限流、5xx 或断流等瞬时错误时，整轮最多自动重试几次。",
                                        "Maximum retry attempts per turn on transient errors (429, 5xx, dropped streams).",
                                    ),
                                    valueText = formatSettingNumber("retry.maxRetries", retryMax, retryMaxSetting?.unit),
                                    presets = maxRetriesChoices,
                                    selected = retryMax,
                                    onSelect = { writeInt("retry.maxRetries", it) },
                                    onCustomClick = retryMaxSetting?.let { s -> { editing = s } },
                                    highlighted = highlightKey == "retry.maxRetries",
                                )
                                PiSettingsHairline()
                                PiInlinePresetRow(
                                    title = retryBaseSetting?.title ?: tr("退避起始延迟", "Base Retry Delay"),
                                    description = tr(
                                        "指数退避的第一跳等待时间（例如 2秒 → 4秒 → 8秒）。",
                                        "Initial exponential backoff delay (e.g. 2s → 4s → 8s).",
                                    ),
                                    valueText = formatDurationMs(retryBaseMs),
                                    presets = baseDelayChoices,
                                    selected = retryBaseMs,
                                    onSelect = { writeInt("retry.baseDelayMs", it) },
                                    onCustomClick = retryBaseSetting?.let { s -> { editing = s } },
                                    highlighted = highlightKey == "retry.baseDelayMs",
                                )
                                PiSettingsHairline()
                                PiInlinePresetRow(
                                    title = retryMaxDelaySetting?.title ?: tr("单次等待上限", "Max Agent Retry Delay"),
                                    description = tr(
                                        "单次重试退避等待的上限时间，默认 1 分钟。",
                                        "Upper bound on wait time for a single retry attempt (default 1 min).",
                                    ),
                                    valueText = formatDurationMs(retryMaxDelayMs),
                                    presets = maxAgentDelayChoices,
                                    selected = retryMaxDelayMs,
                                    onSelect = { writeInt("retry.maxAgentDelayMs", it) },
                                    onCustomClick = retryMaxDelaySetting?.let { s -> { editing = s } },
                                    highlighted = highlightKey == "retry.maxAgentDelayMs",
                                )
                            }
                        }
                    }
                }
            }

            // 3. 高级网络与 Provider 底层参数（默认折叠，防止误调）
            item(key = "advanced-network") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupHeaderGap)) {
                    PiSettingsSectionHeader(tr("高级参数", "Advanced"))
                    PiSettingsCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { advancedExpanded = !advancedExpanded }
                                .padding(
                                    horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                                    vertical = 12.dp,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = tr("WebSocket 与 Provider 底层重试", "WebSocket & Provider Retry"),
                                    style = PiType.rowTitle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = tr(
                                        "默认参数已由 Pi SDK 调优；非排查网络故障通常无需修改。",
                                        "Pre-tuned by Pi SDK; usually only needed when debugging provider timeouts.",
                                    ),
                                    style = PiTheme.text.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (customizedAdvancedCount > 0) {
                                PiSettingsBadge(
                                    label = tr("已改 $customizedAdvancedCount 项", "$customizedAdvancedCount custom"),
                                    tone = palette.accent,
                                )
                            }
                            Icon(
                                imageVector = if (advancedExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = palette.muted,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        AnimatedVisibility(visible = advancedExpanded) {
                            Column {
                                PiSettingsHairline()
                                PiInlinePresetRow(
                                    title = wsTimeoutSetting?.title ?: tr("WebSocket 连接超时", "WebSocket Connect Timeout"),
                                    description = tr(
                                        "WebSocket 建连握手超时，默认 15 秒；0 = 不限时。",
                                        "WebSocket connect/open handshake timeout (default 15s; 0 = disabled).",
                                    ),
                                    valueText = formatSettingNumber(KEY_WEBSOCKET_CONNECT_TIMEOUT_MS, wsTimeoutMs, "ms"),
                                    presets = wsTimeoutChoices,
                                    selected = wsTimeoutMs,
                                    onSelect = { writeInt(KEY_WEBSOCKET_CONNECT_TIMEOUT_MS, it) },
                                    onCustomClick = wsTimeoutSetting?.let { s -> { editing = s } },
                                    highlighted = highlightKey == KEY_WEBSOCKET_CONNECT_TIMEOUT_MS,
                                )
                                if (providerTimeoutSetting != null) {
                                    PiSettingsHairline()
                                    PiSettingRow(
                                        setting = providerTimeoutSetting,
                                        valueText = providerTimeoutMs?.let { formatDurationMs(it) } ?: tr("跟随空闲超时", "Follow idle"),
                                        checked = false,
                                        highlighted = highlightKey == providerTimeoutSetting.key,
                                        onToggle = {},
                                        onOpen = { editing = providerTimeoutSetting },
                                        onExplainEffect = {},
                                    )
                                }
                                if (providerRetriesSetting != null) {
                                    PiSettingsHairline()
                                    PiSettingRow(
                                        setting = providerRetriesSetting,
                                        valueText = providerRetriesSetting.display(providerRetriesSetting.current(store)),
                                        checked = false,
                                        highlighted = highlightKey == providerRetriesSetting.key,
                                        onToggle = {},
                                        onOpen = { editing = providerRetriesSetting },
                                        onExplainEffect = {},
                                    )
                                }
                                if (providerMaxDelaySetting != null) {
                                    PiSettingsHairline()
                                    PiSettingRow(
                                        setting = providerMaxDelaySetting,
                                        valueText = providerMaxDelaySetting.display(providerMaxDelaySetting.current(store)),
                                        checked = false,
                                        highlighted = highlightKey == providerMaxDelaySetting.key,
                                        onToggle = {},
                                        onOpen = { editing = providerMaxDelaySetting },
                                        onExplainEffect = {},
                                    )
                                }
                            }
                        }
                    }
                    AnimatedVisibility(visible = providerMaxRetries > 0) {
                        PiInfoNote(
                            text = tr(
                                "Provider 底层重试已设为 ${providerMaxRetries} 次：建议保持 0，让上层自动重试统一退避，避免限流等待被底层静默吞掉。",
                                "Provider retries set to $providerMaxRetries: keeping 0 is recommended so Agent-level retry handles backoff transparently.",
                            ),
                            tone = palette.warning,
                        )
                    }
                }
            }
        }
    }

    editing?.let { setting ->
        PiSettingEditorSheet(
            setting = setting,
            store = store,
            onDismiss = { editing = null },
            onWritten = { written -> onSettingWritten(written.key) },
        )
    }
}
