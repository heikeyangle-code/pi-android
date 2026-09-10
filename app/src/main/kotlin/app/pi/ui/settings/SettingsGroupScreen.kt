package app.pi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.pi.ui.components.PiSectionHeader
import app.pi.ui.theme.PiSpacing
import kotlinx.serialization.json.JsonPrimitive

/**
 * Settings level 1: one group, rendered straight from the registry.
 *
 * Rows are grouped by their `section`, so a long group such as 终端与 Shell keeps
 * "basic vs advanced" legible. The screen owns its editor state, so it is usable
 * on its own, and it accepts a [highlightKey] so a global-search hit can jump to
 * a row and tint it (spec §6.3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsGroupScreen(
    groupId: String,
    store: PiSettingsStore,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    highlightKey: String? = null,
    onThemeChanged: (String) -> Unit = {},
    onRunAction: ((PiSetting) -> Unit)? = null,
) {
    val group = PiSettingsCatalog.group(groupId)
    val rows = remember(groupId) { buildGroupRows(groupId) }
    val listState = rememberLazyListState()

    var editing by remember { mutableStateOf<PiSetting?>(null) }
    var explaining by remember { mutableStateOf<PiSetting?>(null) }
    var confirming by remember { mutableStateOf<PiSetting?>(null) }

    LaunchedEffect(highlightKey, groupId) {
        if (highlightKey == null) return@LaunchedEffect
        val index = rows.indexOfFirst { row -> row.setting?.key == highlightKey }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(group?.title ?: "设置") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        ) {
            items(rows) { row ->
                val header = row.header
                val setting = row.setting
                if (header != null) {
                    PiSectionHeader(header)
                } else if (setting != null) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (setting.key == highlightKey) {
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            ),
                    ) {
                        PiSettingRow(
                            setting = setting,
                            valueText = setting.display(setting.current(store)),
                            checked = setting.boolIn(store, false),
                            onToggle = { next ->
                                store.write(setting.key, JsonPrimitive(next))
                            },
                            onOpen = {
                                if (setting.kind == PiRowKind.Action) {
                                    confirming = setting
                                } else if (setting.kind != PiRowKind.Switch) {
                                    editing = setting
                                }
                            },
                            onExplainEffect = { explaining = setting },
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(PiSpacing.unit))
            }
        }
    }

    val openEditor = editing
    if (openEditor != null) {
        PiSettingEditorSheet(
            setting = openEditor,
            store = store,
            onDismiss = { editing = null },
            onWritten = { written ->
                if (written.key == "theme") onThemeChanged(written.textIn(store, "dark"))
            },
        )
    }

    val openExplanation = explaining
    if (openExplanation != null) {
        val run = onRunAction
        PiEffectiveDialog(
            kind = openExplanation.effective,
            settingTitle = openExplanation.title,
            onDismiss = { explaining = null },
            onRunAction = run?.let { action -> { action(openExplanation) } },
        )
    }

    val openConfirmation = confirming
    if (openConfirmation != null) {
        val run = onRunAction
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(openConfirmation.title) },
            text = {
                Text(
                    if (run == null) {
                        openConfirmation.description + "\n\n这个入口由运行时接管，当前宿主还没有接入对应的实现。"
                    } else {
                        openConfirmation.description
                    },
                )
            },
            confirmButton = {
                if (run == null) {
                    TextButton(onClick = { confirming = null }) { Text("知道了") }
                } else {
                    TextButton(
                        onClick = {
                            run(openConfirmation)
                            confirming = null
                        },
                    ) { Text(if (openConfirmation.dangerous) "确认执行" else "执行") }
                }
            },
            dismissButton = if (run == null) {
                null
            } else {
                { TextButton(onClick = { confirming = null }) { Text("取消") } }
            },
        )
    }
}

/** A section header or a setting, flattened for one LazyColumn. */
private class GroupRow(val header: String?, val setting: PiSetting?)

private fun buildGroupRows(groupId: String): List<GroupRow> {
    val settings = PiSettingsCatalog.settingsIn(groupId)
    val rows = mutableListOf<GroupRow>()
    for (section in PiSettingsCatalog.sectionsIn(groupId)) {
        rows.add(GroupRow(section, null))
        for (setting in settings) {
            if (setting.section == section) rows.add(GroupRow(null, setting))
        }
    }
    return rows
}
