package app.pi.ui.screens

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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.pi.ui.components.PiSectionHeader
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * Settings level 0.
 *
 * pi exposes ~80 keys across `settings.json`, `keybindings.json`, theme files
 * and auth. The navigation model is four levels deep at most
 * (docs/pi-android-ui-spec.md §6.1), and every group carries a live summary so
 * the answer is often readable without opening it. Row order follows the spec's
 * 14 groups exactly, so nothing pi can configure is missing a home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("设置") })
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        ) {
            item {
                PiSectionHeader("当前")
                PiGroupRow(
                    Group(
                        Icons.Filled.Psychology,
                        "模型与推理",
                        "未配置提供商",
                    ),
                )
            }
            item { PiSectionHeader("引擎") }
            items(engineGroups) { PiGroupRow(it) }
            item { PiSectionHeader("界面") }
            items(uiGroups) { PiGroupRow(it) }
            item { PiSectionHeader("平台") }
            items(platformGroups) { PiGroupRow(it) }
            item { Spacer(Modifier.size(PiSpacing.unit)) }
            item {
                PiGroupRow(
                    Group(
                        Icons.Filled.Settings,
                        "隐私与关于",
                        "版本 · 日志 · 原始配置",
                    ),
                )
            }
            item { Spacer(Modifier.size(PiSpacing.unit)) }
            // Kept reachable while the rest of the stack is being built.
            item {
                PiGroupRow(
                    Group(Icons.Filled.Visibility, "主题", if (isDark) "深色" else "浅色"),
                    onClick = onToggleTheme,
                )
            }
            item { Spacer(Modifier.size(PiSpacing.unit * 2)) }
        }
    }
}

private data class Group(
    val icon: ImageVector,
    val title: String,
    val summary: String,
)

private val engineGroups = listOf(
    Group(Icons.Filled.CompareArrows, "消息与队列", "穿插：逐条 · 后续：逐条"),
    Group(Icons.Filled.Compress, "上下文与压缩", "自动 · 保留 16384 / 20000"),
    Group(Icons.Filled.Refresh, "重试与网络", "开 · 最多 3 次 · 无代理"),
    Group(Icons.Filled.Build, "工具", "read · bash · edit · write"),
    Group(Icons.Filled.Folder, "会话与工作区", "默认工作区"),
    Group(Icons.Filled.Extension, "扩展与资源", "0 个扩展 · 0 个资源包"),
    Group(Icons.Filled.Cloud, "提供商与凭据", "未添加"),
    Group(Icons.Filled.Storage, "本地模型", "未启用"),
)

private val uiGroups = listOf(
    Group(Icons.Filled.ColorLens, "外观", "跟随系统 · 等宽 13sp"),
    Group(Icons.Filled.Terminal, "终端与 Shell", "/bin/bash · 回滚 2000 行"),
    Group(Icons.Filled.TouchApp, "交互", "双击返回：会话树"),
)

private val platformGroups = listOf(
    Group(Icons.Filled.Security, "安全与信任", "项目信任：询问"),
    Group(Icons.Filled.PhoneAndroid, "设备能力", "全部未授权"),
    Group(Icons.Filled.Memory, "运行时与诊断", "内置运行时未安装"),
)

@Composable
private fun PiGroupRow(group: Group, onClick: () -> Unit = {}) {
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
                group.summary,
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
