package app.pi.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
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
    onOpenSecurity: () -> Unit,
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
    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(title = tr("其他", "More"), onBack = onBack)
        LazyColumn(
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
                        tr("打开内置全屏终端环境", "Open the built-in full-screen terminal"),
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
                add(
                    OtherEntry(
                        Icons.Filled.Lock,
                        palette.warning,
                        tr("安全与隐私", "Security & Privacy"),
                        tr("项目默认信任策略与安装遥测", "Default project trust policy and install telemetry"),
                        onOpenSecurity,
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
