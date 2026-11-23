package app.pi.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.pi.i18n.tr
import app.pi.ui.PiTopBar
import app.pi.ui.theme.PiTheme

/**
 * 设置首页的「其他」：系统工具、设备权限、安全与法律声明，统一收进一张分组卡片中展示。
 */
@Composable
internal fun OtherSettingsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenTerminal: (() -> Unit)?,
    onOpenDeviceCapabilities: (() -> Unit)?,
    onOpenLicenses: (() -> Unit)?,
) {
    val palette = PiTheme.palette
    val context = androidx.compose.ui.platform.LocalContext.current
    val capStore = androidx.compose.runtime.remember(context) { app.pi.bridge.DeviceCapabilityStore.get(context) }
    val capStates = capStore.states()
    val capUsable = capStates.count { it.usable }
    val capSupporting = if (capUsable == 0) {
        tr("未启用（0 Token 占用）· 屏幕自动化、通知与设备 Shell", "Disabled (0 Token) · Screen automation, notifications & device shell")
    } else {
        tr("$capUsable/${capStates.size} 组就绪 · 屏幕自动化、通知与设备 Shell", "$capUsable/${capStates.size} active · Screen automation, notifications & device shell")
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrolled = remember(listState) {
        androidx.compose.runtime.derivedStateOf { listState.canScrollBackward }
    }.value
    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(title = tr("其他", "More"), onBack = onBack, scrolled = scrolled)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = PiSettingsMetrics.groupGap,
                bottom = contentPadding.calculateBottomPadding() + app.pi.ui.theme.PiSpacing.scrollBottom,
            ),
        ) {
            val rows = buildList {
                if (onOpenTerminal != null) add(
                    OtherEntry(
                        Icons.Filled.Terminal,
                        palette.accent,
                        tr("终端", "Terminal"),
                        tr("打开内置原生多标签终端（后台常驻）", "Open built-in native multi-tab terminal (persistent)"),
                        onOpenTerminal,
                    ),
                )
                if (onOpenDeviceCapabilities != null) add(
                    OtherEntry(
                        Icons.Filled.PhoneAndroid,
                        palette.success,
                        tr("设备能力", "Device Capabilities"),
                        capSupporting,
                        onOpenDeviceCapabilities,
                    ),
                )
                if (onOpenLicenses != null) add(
                    OtherEntry(
                        Icons.Filled.Info,
                        palette.muted,
                        tr("开源许可", "Open Source Licenses"),
                        tr("查看第三方依赖与开源协议声明", "View third-party dependencies and license notices"),
                        onOpenLicenses,
                    ),
                )
            }
            item(key = "other-card") {
                PiSettingsCard {
                    rows.forEachIndexed { index, entry ->
                        if (index > 0) PiSettingsHairline()
                        PiSettingsEntryRow(
                            title = entry.title,
                            supporting = entry.supporting,
                            onClick = entry.action,
                            leadingIcon = entry.icon,
                            leadingTint = entry.tint,
                        )
                    }
                }
            }
        }
    }
}

private class OtherEntry(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tint: androidx.compose.ui.graphics.Color,
    val title: String,
    val supporting: String,
    val action: () -> Unit,
)
