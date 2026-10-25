package app.pi.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.pi.ui.PiTopBar
import androidx.compose.foundation.layout.padding

/**
 * 设置首页的「其他」：不属于 pi 的设置键、也不属于日常操作的几扇门。
 *
 * 用户裁定：首页只保留「模型与供应商」「分组」「其他」三张卡，其他里的东西点进来再看。
 * 这一屏的行形状与分组页一致（`PiSettingsEntryRow`：标题 + 一行说明 + chevron），
 * **全部不带前置图标** —— 首页此前一半有图标一半没有，正是用户报的那处不一致。
 */
@Composable
internal fun OtherSettingsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenTerminal: (() -> Unit)?,
    onOpenPiFiles: () -> Unit,
    onOpenDeviceCapabilities: (() -> Unit)?,
    onOpenPackages: (() -> Unit)?,
    onOpenSecurity: () -> Unit,
    onOpenLicenses: (() -> Unit)?,
) {
    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(title = "其他", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        ) {
            val rows = buildList {
                if (onOpenTerminal != null) add(
                    Triple(Icons.Filled.Terminal, "终端", { onOpenTerminal() }),
                )
                add(Triple(Icons.AutoMirrored.Filled.InsertDriveFile, "Pi 文件", onOpenPiFiles))
                if (onOpenDeviceCapabilities != null) add(
                    Triple(Icons.Filled.PhoneAndroid, "设备能力", { onOpenDeviceCapabilities() }),
                )
                if (onOpenPackages != null) add(
                    Triple(Icons.Filled.Extension, "扩展包与项目信任", { onOpenPackages() }),
                )
                add(Triple(Icons.Filled.Lock, "安全与隐私", onOpenSecurity))
                if (onOpenLicenses != null) add(
                    Triple(Icons.Filled.Info, "开源许可", { onOpenLicenses() }),
                )
            }
            rows.forEachIndexed { index, (icon, title, action) ->
                item(key = title) {
                    PiSettingsCard(
                        modifier = if (index == 0) {
                            Modifier.padding(top = PiSettingsMetrics.groupGap)
                        } else {
                            Modifier
                        },
                    ) {
                        PiSettingsEntryRow(
                            title = title,
                            supporting = null,
                            onClick = action,
                            leadingIcon = icon,
                        )
                    }
                }
            }
        }
    }
}
