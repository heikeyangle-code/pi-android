package app.pi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.pi.ui.screens.ChatScreen
import app.pi.ui.screens.SessionsScreen
import app.pi.ui.screens.SettingsScreen
import app.pi.ui.screens.WorkbenchScreen

/**
 * The four top-level destinations.
 *
 * pi itself has one surface (the transcript) and reaches everything else through
 * slashes; on a phone those need permanent homes. These four are the mapping of
 * "pick a session / talk / work / configure" onto a bottom bar
 * (docs/pi-android-ui-spec.md §3.1). Deliberately four — more than four
 * destinations on a phone means mis-taps.
 */
enum class PiDestination(val label: String, val icon: ImageVector) {
    Sessions("会话", Icons.Filled.Forum),
    Chat("对话", Icons.Filled.ChatBubble),
    Workbench("工作区", Icons.Filled.Terminal),
    Settings("设置", Icons.Filled.Settings),
}

@Composable
fun PiRoot(
    onToggleTheme: () -> Unit,
    isDark: Boolean,
) {
    var destination by rememberSaveable { mutableStateOf(PiDestination.Chat.ordinal) }
    val current = PiDestination.entries[destination]

    Scaffold(
        bottomBar = {
            NavigationBar {
                PiDestination.entries.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = destination == index,
                        onClick = { destination = index },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            when (current) {
                PiDestination.Sessions -> SessionsScreen(
                    contentPadding = padding,
                    onOpenChat = { destination = PiDestination.Chat.ordinal },
                )

                PiDestination.Chat -> ChatScreen(contentPadding = padding)

                PiDestination.Workbench -> WorkbenchScreen(contentPadding = padding)

                PiDestination.Settings -> SettingsScreen(
                    contentPadding = padding,
                    isDark = isDark,
                    onToggleTheme = onToggleTheme,
                )
            }
        }
    }
}
