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
    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(title = "其他", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = PiSettingsMetrics.groupGap,
                bottom = contentPadding.calculateBottomPadding(),
            ),
        ) {
            val rows = buildList {
                if (onOpenTerminal != null) add(
                    OtherEntry(Icons.Filled.Terminal, palette.accent, "终端", "打开内置全屏终端环境", onOpenTerminal),
                )
                if (onOpenDeviceCapabilities != null) add(
                    OtherEntry(Icons.Filled.PhoneAndroid, palette.success, "设备能力", "管理通知、无障碍、文件与系统桥接授权", onOpenDeviceCapabilities),
                )
                add(OtherEntry(Icons.Filled.Lock, palette.warning, "安全与隐私", "项目默认信任策略与安装遥测", onOpenSecurity))
                if (onOpenLicenses != null) add(
                    OtherEntry(Icons.Filled.Info, palette.muted, "开源许可", "查看第三方依赖与开源协议声明", onOpenLicenses),
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
