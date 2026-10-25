package app.pi.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
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
            item {
                Column(Modifier.padding(top = PiSettingsMetrics.groupGap)) {
                    PiSettingsCard {
                        if (onOpenTerminal != null) {
                            PiSettingsEntryRow(
                                title = "终端",
                                supporting = "输入 pi 回车进入原版 TUI：会话导入、需要终端的扩展。",
                                onClick = onOpenTerminal,
                            )
                            PiSettingsHairline()
                        }
                        PiSettingsEntryRow(
                            title = "Pi 文件",
                            supporting = "看 pi 读的那些文件：设置、模型、提示词、主题、技能。",
                            onClick = onOpenPiFiles,
                        )
                        if (onOpenDeviceCapabilities != null) {
                            PiSettingsHairline()
                            PiSettingsEntryRow(
                                title = "设备能力",
                                supporting = "这台手机允许 agent 做什么：root / Shizuku / ADB、截图、点击。",
                                onClick = onOpenDeviceCapabilities,
                            )
                        }
                        if (onOpenPackages != null) {
                            PiSettingsHairline()
                            PiSettingsEntryRow(
                                title = "扩展包与项目信任",
                                supporting = "agent 能装什么、项目资源能不能信。",
                                onClick = onOpenPackages,
                            )
                        }
                        PiSettingsHairline()
                        PiSettingsEntryRow(
                            title = "安全与隐私",
                            supporting = "项目信任策略与安装遥测。",
                            onClick = onOpenSecurity,
                        )
                        if (onOpenLicenses != null) {
                            PiSettingsHairline()
                            PiSettingsEntryRow(
                                title = "开源许可",
                                supporting = "随包分发的 Linux 用户态、Node、git、proot 与库的许可证。",
                                onClick = onOpenLicenses,
                            )
                        }
                    }
                }
            }
        }
    }
}
