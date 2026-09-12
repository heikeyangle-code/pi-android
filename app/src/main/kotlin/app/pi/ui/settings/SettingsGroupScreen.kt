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
import app.pi.ui.components.EffectiveKind
import app.pi.ui.components.PiSectionHeader
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiThemeEntry
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
    knownThemes: List<PiThemeEntry> = emptyList(),
    themeNotes: List<String> = emptyList(),
    themeError: String? = null,
    onSettingWritten: (String) -> Unit = {},
    onRunAction: ((PiSetting) -> Unit)? = null,
    /**
     * Rows whose work is not a one-shot command but a screen of their own, by key:
     * the credential form and the local-model endpoint form, which are Action rows,
     * plus the read-only `packages` row, which must open the package manager rather
     * than a list editor (see `PiSettingsStack.hostActions`). Such a row opens that
     * screen directly: its description already is the explanation, so an extra
     * 执行/取消 confirmation would be one tap that decides nothing.
     *
     * Empty by default, which leaves every row on the [onRunAction] path — the
     * behaviour every existing caller has.
     */
    hostActions: Map<String, () -> Unit> = emptyMap(),
    /**
     * Values that do not live in the settings store, by key: the read-only
     * 运行时 rows, whose facts come from the runtime tree and the running
     * service (see [RuntimeFacts]). An override is rendered verbatim instead of
     * `display(store value)`, so a row can never fall back to a default that
     * looks like a reading — the host always supplies either the real value or a
     * sentence saying why it could not be read.
     *
     * Empty by default: every other row keeps reading the store.
     */
    valueOverrides: Map<String, String> = emptyMap(),
    /**
     * The host's engine restart, offered by the badge explanation of a
     * [EffectiveKind.RestartEngine] row ("重启引擎"). Null hides that button, which
     * is right where no engine hook exists: the row's own explanation still says
     * what has to happen.
     */
    onRestartEngine: (() -> Unit)? = null,
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
                            valueText = valueOverrides[setting.key]
                                ?: setting.display(setting.current(store)),
                            checked = setting.boolIn(store, false),
                            onToggle = { next ->
                                store.write(setting.key, JsonPrimitive(next))
                                // Switch rows write in place, so they never reach
                                // the editor sheet's callback; a switch the app
                                // reads (the thinking toggle, timestamps, tool
                                // expansion) would otherwise stay inert.
                                onSettingWritten(setting.key)
                            },
                            onOpen = {
                                val hostAction = hostActions[setting.key]
                                if (hostAction != null) {
                                    hostAction()
                                } else if (setting.kind == PiRowKind.Action) {
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
            knownThemes = knownThemes,
            themeNotes = themeNotes,
            themeError = themeError,
            onDismiss = { editing = null },
            // Every write is announced, not just the theme: several keys
            // (`hideThinkingBlock`, the `app.appearance.*` rows) are read by the
            // app itself, and this screen is the only place that knows one
            // changed. Without this they would be inert again.
            onWritten = { written -> onSettingWritten(written.key) },
        )
    }

    val openExplanation = explaining
    if (openExplanation != null) {
        val run = onRunAction
        PiEffectiveDialog(
            kind = openExplanation.effective,
            settingTitle = openExplanation.title,
            onDismiss = { explaining = null },
            // The badge's button is "the action that applies this value". For
            // `RestartEngine` that action is the engine restart itself, which the
            // host owns (设置 → 进程 → 重启引擎) — the generic action channel is for
            // `PiRowKind.Action` rows and would answer "not implemented" here.
            onRunAction = when {
                openExplanation.effective == EffectiveKind.RestartEngine -> onRestartEngine
                run != null -> { { run(openExplanation) } }
                else -> null
            },
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
                    // A caller that passes no dispatcher gets the row's own text plus
                    // one neutral sentence saying the entry does not work here. It must
                    // not describe the host wiring: that is internal, and `PiRoot` — the
                    // only production caller — always passes a dispatcher, so this
                    // branch is a preview/test path.
                    if (run == null) {
                        openConfirmation.description + "\n\n这个入口当前不可用。"
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
