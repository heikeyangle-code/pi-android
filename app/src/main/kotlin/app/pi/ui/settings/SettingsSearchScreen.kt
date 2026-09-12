package app.pi.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.ui.components.PiEmptyState
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * Settings level 2: global search (spec §6.3).
 *
 * Matching runs across the title, the description AND the raw dotted key, so
 * `reserveTokens` finds `compaction.reserveTokens` even though that word never
 * appears in Chinese prose. Slash commands are aliases on the relevant rows, so
 * `/compact` lands on 自动压缩. Every result shows its breadcrumb and current
 * value, and tapping it asks the caller to open that row.
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
) {
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    val hits = remember(query) { PiSettingsCatalog.search(query) }
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
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.screen, vertical = 8.dp)
                .focusRequester(focusRequester),
            singleLine = true,
            label = { Text("标题 · 说明 · 字段名") },
            trailingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
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
                            start = PiSpacing.screen,
                            end = PiSpacing.screen,
                            top = 4.dp,
                            bottom = 4.dp,
                        ),
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(hits) { hit ->
                    SearchResultRow(
                        hit = hit,
                        valueText = valueOverrides[hit.setting.key]
                            ?: hit.setting.display(hit.setting.current(store)),
                        onClick = { onOpenSetting(hit.setting.key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchHints(onPick: (String) -> Unit) {
    val examples = listOf(
        "reserveTokens",
        "sessionDir",
        "thinkingBudgets",
        "theme",
        "代理",
        "压缩",
        "/compact",
        "/tree",
        "llama",
    )
    Column(Modifier.fillMaxWidth()) {
        Text(
            "搜索全部 ${PiSettingsCatalog.settings.size} 项设置。匹配标题、说明与字段名，斜杠命令也能用。",
            modifier = Modifier.padding(horizontal = PiSpacing.screen, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        examples.forEach { example ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiSpacing.screen, vertical = 4.dp)
                    .clickable { onPick(example) },
                shape = PiShapes.chip,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    example,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = PiTheme.text.mono,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    hit: PiSearchHit,
    valueText: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PiSpacing.screen, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                hit.breadcrumb,
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                hit.setting.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(4.dp))
            PiKeyLabel(hit.setting.key)
            Spacer(Modifier.size(4.dp))
            Text(
                hit.setting.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                valueText,
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
