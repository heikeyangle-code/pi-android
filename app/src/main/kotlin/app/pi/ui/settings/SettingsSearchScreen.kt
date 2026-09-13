package app.pi.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import app.pi.ui.components.PiEmptyState
import app.pi.ui.theme.PiTheme

/**
 * Settings level 2: global search (spec §6.3), 按 v2 的搜索壳重排。
 *
 * 匹配仍然跑标题、说明与原始点号键三处，所以 `reserveTokens` 能找到
 * `compaction.reserveTokens`（那个词在中文说明里从不出现）；斜杠命令是相关行的
 * 别名，`/compact` 落在「自动压缩」上。每条结果显示所在分组与当前值，点它由调用方
 * 打开那一行。
 *
 * v2 的取值：搜索框高 40、圆角 9、`surfaceContainerLow` 底、有输入时描边为
 * `borderAccent`（06 §2 的输入框规格）；结果数是 12 灰；结果行是标准行
 * （`padding:10px 12px`、标题 15/500、尾部值 13 等宽），命中词在标题里高亮
 * ——用的是 pi 自己的 `searchMatchBg` / `searchMatchText` 两个令牌，
 * 也就是对话内查找用的同一对色，本批不改它们的取值。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSearchScreen(
    store: PiSettingsStore,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenSetting: (String) -> Unit,
    initialQuery: String = "",
    /**
     * Values that do not live in the store (the 运行时 facts), by key. Passed
     * through so a search hit shows the same text as the row does — see
     * [SettingsGroupScreen.valueOverrides].
     */
    valueOverrides: Map<String, String> = emptyMap(),
    /**
     * Changes when the settings files changed outside this app, so the result rows
     * re-read their values — the same mechanism as [SettingsGroupScreen.freshness],
     * and the same reason: a cache drop alone does not recompose anything.
     */
    freshness: Int = 0,
) {
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    // `freshness` is part of the key so a new epoch rebuilds the hit list, which is
    // what re-reads `setting.current(store)` in every row below.
    val hits = remember(query, freshness) { PiSettingsCatalog.search(query) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (initialQuery.isEmpty()) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("搜索设置") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        SearchField(
            query = query,
            onQueryChange = { query = it },
            focusRequester = focusRequester,
        )
        when {
            query.isBlank() -> SearchHints(onPick = { query = it })

            hits.isEmpty() -> PiEmptyState(
                icon = Icons.Filled.Search,
                title = "没有匹配的设置",
                body = "试试字段名（reserveTokens、sessionDir），或者斜杠命令（/compact、/tree）。",
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
            ) {
                item {
                    Text(
                        "${hits.size} 条结果",
                        modifier = Modifier.padding(
                            start = PiSettingsMetrics.pageHorizontal,
                            end = PiSettingsMetrics.pageHorizontal,
                            top = PiSettingsMetrics.rowGap,
                            bottom = PiSettingsMetrics.supportingGap,
                        ),
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    PiSettingsCard {
                        hits.forEachIndexed { index, hit ->
                            if (index > 0) PiSettingsHairline()
                            SearchResultRow(
                                hit = hit,
                                query = query,
                                valueText = valueOverrides[hit.setting.key]
                                    ?: hit.setting.display(hit.setting.current(store)),
                                onClick = { onOpenSetting(hit.setting.key) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 搜索框：v2 的输入框规格（高 40、圆角 9、左图标、右侧「关闭」），
 * 有输入时 1px 描边走 `borderAccent`。
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
) {
    val searching = query.isNotEmpty()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PiSettingsMetrics.pageHorizontal,
                end = PiSettingsMetrics.pageHorizontal,
                top = PiSettingsMetrics.cardPadding,
            )
            .height(PiSettingsMetrics.searchFieldHeight),
        shape = PiSettingsFieldShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            PiSettingsMetrics.hairline,
            if (searching) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.outline
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = PiSettingsMetrics.rowPaddingHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
                tint = PiTheme.palette.muted,
            )
            Spacer(Modifier.width(PiSettingsMetrics.searchIconGap))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        "标题 · 说明 · 字段名",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiTheme.palette.muted,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(PiTheme.palette.accent),
                )
            }
            if (query.isNotEmpty()) {
                Spacer(Modifier.width(PiSettingsMetrics.searchIconGap))
                Text(
                    "关闭",
                    modifier = Modifier.clickable { onQueryChange("") },
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.muted,
                )
            }
        }
    }
}

@Composable
private fun SearchHints(onPick: (String) -> Unit) {
    // Every chip has to resolve to at least one row, and `settings-audit` enforces
    // it: a chip that matches nothing is a dead row one layer up. `/tree`,
    // `sessionDir`, `导入` and `/import` all stopped resolving when the rows behind
    // them were dropped (`docs/settings-review.md` §1.2, §7.5, §9), so the chips
    // follow the rows.
    val examples = listOf(
        "reserveTokens",
        "thinkingBudgets",
        "theme",
        "代理",
        "压缩",
        "保活",
        "/compact",
        "/login",
        "llama",
    )
    Column(Modifier.fillMaxWidth()) {
        Text(
            "搜索全部 ${PiSettingsCatalog.settings.size} 项设置。匹配标题、说明与字段名，斜杠命令也能用。",
            modifier = Modifier.padding(
                start = PiSettingsMetrics.pageHorizontal,
                end = PiSettingsMetrics.pageHorizontal,
                top = PiSettingsMetrics.cardPadding,
                bottom = PiSettingsMetrics.supportingGap,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // v2 的快捷词是 chip（高 26、圆角 999、`padding:0 9px`、12 等宽），
        // 不是整行的按钮——它们只是把词填进搜索框。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(
                    horizontal = PiSettingsMetrics.pageHorizontal,
                    vertical = PiSettingsMetrics.supportingGap,
                ),
            horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.titleGap),
        ) {
            examples.forEach { example ->
                PiSettingsChip(text = example, onClick = { onPick(example) })
            }
        }
    }
}

/**
 * 结果行：标题（命中词高亮）、所在分组 + 说明各一行、右侧当前值。
 * v2 的命中的行不给 chevron（`chevron={false}`），因为点它是跳转不是展开。
 */
@Composable
private fun SearchResultRow(
    hit: PiSearchHit,
    query: String,
    valueText: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.titleGap),
            ) {
                val title = highlightMatch(hit.setting.title, query)
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                PiKeyLabel(hit.setting.key)
            }
            Text(
                "${hit.breadcrumb} · ${hit.setting.description}",
                modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            valueText,
            style = PiTheme.text.mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 命中词高亮（v2 的搜索页只画了结果行，命中反馈沿用 pi 自己的两个搜索令牌：
 * `searchMatchBg` 底 + `searchMatchText` 字，与对话内查找同一对色）。
 *
 * 只对标题做高亮：说明与字段名里的命中出现在高亮之外会让这一行读起来更乱，
 * 而字段名本身已经作为键标签整段显示。
 */
@Composable
private fun highlightMatch(title: String, query: String): AnnotatedString {
    val needle = query.trim().removePrefix("/")
    if (needle.isEmpty()) return AnnotatedString(title)
    val background = PiTheme.palette.searchMatchBg
    val foreground = PiTheme.palette.searchMatchText
    return buildAnnotatedString {
        var cursor = 0
        while (cursor < title.length) {
            val found = title.indexOf(needle, cursor, ignoreCase = true)
            if (found < 0) {
                append(title.substring(cursor))
                return@buildAnnotatedString
            }
            append(title.substring(cursor, found))
            withStyle(SpanStyle(color = foreground, background = background)) {
                append(title.substring(found, found + needle.length))
            }
            cursor = found + needle.length
        }
    }
}
