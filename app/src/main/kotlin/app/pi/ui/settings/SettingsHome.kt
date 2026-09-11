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
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pi.bridge.DeviceCapabilityStore
import app.pi.packages.PiPackagesEntryRow
import app.pi.ui.components.PiSectionHeader
import app.pi.ui.device.DeviceCapabilityEntryRow
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * Settings level 0 (spec §6.1).
 *
 * This supersedes the flat list in `SettingsScreen.kt`: the same 14 groups, but
 * each one now carries a live summary read from the store, a prominent search
 * entry across every registered key, and a shortcut card for the two settings
 * users change most (model and thinking level).
 *
 * The screen is stateless apart from the store it reads; navigation is the
 * caller's job, which is why it takes three callbacks rather than owning routes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHome(
    store: PiSettingsStore,
    contentPadding: PaddingValues,
    onOpenGroup: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSetting: (String) -> Unit,
    /** `null` hides the row, which is what a preview or a test wants. */
    onOpenDeviceCapabilities: (() -> Unit)? = null,
    /**
     * The package/extension manager ([app.pi.packages.PiPackagesHost]). `null`
     * hides the row for the same reason as the capability one: pi has no package
     * screen to mirror — `pi install` is CLI-only in this version — so the entry
     * exists only where the host can mount it.
     */
    onOpenPackages: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("设置") },
            actions = {
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Filled.Search, contentDescription = "搜索设置")
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        ) {
            item {
                SearchEntry(onOpenSearch)
            }
            item {
                CurrentModelCard(store, onOpenSetting)
            }
            // The device capability bridge is the one settings surface that is
            // not a pi setting: pi has no notion of the phone it runs on, so this
            // is the only screen for it. The catalog used to carry a second,
            // disconnected copy of these switches under `app.device.*`; those rows
            // were removed from `PiSettingsRegistry.kt` so there is one authority,
            // `DeviceCapabilityStore`.
            if (onOpenDeviceCapabilities != null) {
                item {
                    PiSectionHeader("设备")
                }
                item {
                    DeviceCapabilityEntryRow(
                        store = DeviceCapabilityStore.get(context),
                        onClick = onOpenDeviceCapabilities,
                    )
                }
            }
            // Extension packages, next to the capability row because they answer
            // the same kind of question — "what can the agent use here?" — and
            // neither is a pi setting. `pi install` is CLI-only in v0.85.1, so
            // this screen is the app's own, not a transcription of pi's.
            if (onOpenPackages != null) {
                item {
                    PiSectionHeader("扩展")
                }
                item {
                    PiPackagesEntryRow(onClick = onOpenPackages)
                }
            }
            item {
                PiSectionHeader("全部设置")
            }
            items(PiSettingsCatalog.groups) { group ->
                GroupEntry(
                    group = group,
                    store = store,
                    onClick = { onOpenGroup(group.id) },
                )
            }
            item {
                Text(
                    "共 ${PiSettingsCatalog.settings.size} 项设置。搜索同时匹配标题、说明与字段名，" +
                        "输入 reserveTokens 或 /compact 都能直达。",
                    modifier = Modifier.padding(
                        start = PiSpacing.screen,
                        end = PiSpacing.screen,
                        top = PiSpacing.unit,
                        bottom = PiSpacing.unit * 2,
                    ),
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Prominent search entry, because search is the fastest path to any of ~130 keys. */
@Composable
private fun SearchEntry(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.screen, vertical = 8.dp)
            .clickable(onClick = onClick),
        shape = PiShapes.chip,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "搜索设置",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${PiSettingsCatalog.settings.size} 项",
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Shortcut card for the current model and thinking level (spec §6.1 快捷卡). */
@Composable
private fun CurrentModelCard(store: PiSettingsStore, onOpenSetting: (String) -> Unit) {
    val model = PiSettingsCatalog.summaryText(store, "defaultModel")
    val level = PiSettingsCatalog.summaryText(store, "defaultThinkingLevel")
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.screen, vertical = 8.dp)
            .clickable { onOpenSetting("defaultModel") },
        shape = PiShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Psychology,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "当前模型",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "$model · ◐ $level",
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GroupEntry(
    group: PiSettingsGroup,
    store: PiSettingsStore,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PiSpacing.screen, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            group.icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                group.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                group.summary(store),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
