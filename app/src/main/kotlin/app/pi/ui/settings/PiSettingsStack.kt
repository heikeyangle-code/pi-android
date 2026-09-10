package app.pi.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.pi.bridge.DeviceCapabilityStore
import app.pi.ui.device.DeviceCapabilityScreen

/**
 * The whole settings stack in one composable: level 0 home, level 1 group,
 * level 2 search (spec §6.1).
 *
 * It exists so the parent can wire the 设置 destination with a single call and
 * get correct back behaviour for free — predictive back walks the stack instead
 * of leaving the app, which is the rule the spec sets in §3.1. The individual
 * screens are still public, so a nav-graph based host can call them directly.
 *
 * Pass [store] to bind the real runtime store; without it the stack falls back to
 * an in-memory store seeded with pi's defaults.
 */
@Composable
fun PiSettingsStack(
    contentPadding: PaddingValues,
    store: PiSettingsStore? = null,
    onThemeChanged: (String) -> Unit = {},
    onRunAction: ((PiSetting) -> Unit)? = null,
) {
    val activeStore = store ?: rememberInMemoryPiSettingsStore()
    var groupId by remember { mutableStateOf<String?>(null) }
    var highlightKey by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var backReturnsToSearch by remember { mutableStateOf(false) }
    // The device capability screen is the one branch of this stack that is not a
    // group in pi's settings catalog — the switches grant the *phone's* abilities
    // to the agent, and pi has no such settings (docs/pi-android-app-design.md §21).
    var deviceCapabilities by remember { mutableStateOf(false) }

    val openSetting: (String) -> Unit = { key ->
        val setting = PiSettingsCatalog.byKey[key]
        if (setting != null) {
            backReturnsToSearch = searching
            groupId = setting.group
            highlightKey = key
            searching = false
        }
    }

    BackHandler(enabled = searching || groupId != null || deviceCapabilities) {
        if (searching) {
            searching = false
        } else if (deviceCapabilities) {
            deviceCapabilities = false
        } else {
            if (backReturnsToSearch) searching = true
            groupId = null
            highlightKey = null
        }
    }

    val currentGroup = groupId
    Box(Modifier.fillMaxSize()) {
        when {
            deviceCapabilities -> DeviceCapabilityScreen(
                store = DeviceCapabilityStore.get(LocalContext.current),
                onBack = { deviceCapabilities = false },
                contentPadding = contentPadding,
            )

            searching -> SettingsSearchScreen(
                store = activeStore,
                contentPadding = contentPadding,
                onBack = { searching = false },
                onOpenSetting = openSetting,
            )

            currentGroup != null -> SettingsGroupScreen(
                groupId = currentGroup,
                store = activeStore,
                contentPadding = contentPadding,
                onBack = {
                    if (backReturnsToSearch) searching = true
                    groupId = null
                    highlightKey = null
                },
                highlightKey = highlightKey,
                onThemeChanged = onThemeChanged,
                onRunAction = onRunAction,
            )

            else -> SettingsHome(
                store = activeStore,
                contentPadding = contentPadding,
                onOpenGroup = { id ->
                    groupId = id
                    highlightKey = null
                    backReturnsToSearch = false
                },
                onOpenSearch = { searching = true },
                onOpenSetting = openSetting,
                onOpenDeviceCapabilities = { deviceCapabilities = true },
            )
        }
    }
}
