package app.pi.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.pi.i18n.tr
import app.pi.ui.components.PiSheet
import app.pi.ui.components.PiType
import app.pi.ui.models.DestructiveAction
import app.pi.ui.models.PrimaryAction
import app.pi.ui.models.SecondaryAction
import app.pi.ui.models.TagPill
import app.pi.ui.render.PiMarkdownText
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.update.AppUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * 原生应用内更新面板：
 *  - 自动对比当前版本与 GitHub Releases 最新版本，自动识别当前安装变体（`sideload28` / `modern36`）；
 *  - 原生富渲染更新日志（复用 `PiMarkdownText` 离线 Markdown 管线）；
 *  - 流式下载进度条、字节进度与一键取消、SHA-256 完整性校验；
 *  - 自动检测「允许安装未知应用」权限并在用户授权返回后自动继续拉起系统安装器。
 */
@Composable
internal fun AppUpdateSheet(
    onDismiss: () -> Unit,
    onOutcomeUpdated: (AppUpdater.CheckOutcome) -> Unit = {},
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val palette = PiTheme.palette
    val lifecycleOwner = LocalLifecycleOwner.current

    var checking by remember { mutableStateOf(AppUpdater.peekCached() == null) }
    var outcome by remember { mutableStateOf(AppUpdater.peekCached()) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<AppUpdater.DownloadProgress?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var readyApk by remember { mutableStateOf<File?>(null) }
    var waitingPermissionForApk by remember { mutableStateOf<File?>(null) }

    fun triggerInstall(apk: File) {
        if (!AppUpdater.canInstallPackages(context)) {
            waitingPermissionForApk = apk
            val opened = AppUpdater.openInstallPermissionSettings(context)
            if (!opened) {
                error = tr(
                    "无法打开未知来源安装授权页，请在系统设置中允许本应用安装更新包。",
                    "Could not open permission settings. Allow \"Install unknown apps\" for Pi in system settings.",
                )
            }
            return
        }
        waitingPermissionForApk = null
        AppUpdater.launchApkInstaller(context, apk).onFailure {
            error = tr("拉起系统安装器失败：${it.message}", "Failed to launch installer: ${it.message}")
        }
    }

    // 用户从系统「允许安装未知应用」页面授权返回时，自动无缝拉起安装器，省去二次点击
    DisposableEffect(lifecycleOwner, waitingPermissionForApk) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val pending = waitingPermissionForApk
                if (pending != null && pending.isFile && AppUpdater.canInstallPackages(context)) {
                    waitingPermissionForApk = null
                    AppUpdater.launchApkInstaller(context, pending).onFailure {
                        error = tr("拉起系统安装器失败：${it.message}", "Failed to launch installer: ${it.message}")
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            downloadJob?.cancel()
        }
    }

    fun runCheck(force: Boolean) {
        if (downloadJob?.isActive == true) return
        scope.launch {
            checking = true
            error = null
            AppUpdater.checkForUpdate(context, force = force)
                .onSuccess { res ->
                    outcome = res
                    onOutcomeUpdated(res)
                }
                .onFailure { err ->
                    error = err.message ?: tr("检查更新失败，请检查网络连接", "Update check failed. Check your network connection.")
                }
            checking = false
        }
    }

    LaunchedEffect(Unit) {
        runCheck(force = AppUpdater.peekCached() == null)
    }

    val currentVer = remember(context) { AppUpdater.installedVersionName(context) }
    val targetSdk = remember(context) { AppUpdater.installedTargetSdk(context) }
    val variantLabel = remember(targetSdk) { AppUpdater.expectedVariantLabel(targetSdk) }
    val downloading = downloadJob?.isActive == true

    PiSheet(
        onDismiss = {
            downloadJob?.cancel()
            onDismiss()
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PiSpacing.pageHorizontal)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 顶部标题栏 + 刷新按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(palette.accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = tr("应用更新", "App Update"),
                        style = PiType.title,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TagPill("v$currentVer")
                        TagPill(variantLabel, tone = palette.accent)
                    }
                }
                if (checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = palette.accent,
                    )
                } else if (!downloading) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { runCheck(force = true) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = tr("重新检查", "Check again"),
                            tint = palette.muted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            error?.let { msg ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = palette.error.copy(alpha = 0.10f),
                    border = BorderStroke(0.5.dp, palette.error.copy(alpha = 0.35f)),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(msg, style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium), color = palette.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryAction(
                                text = tr("重试", "Retry"),
                                onClick = { runCheck(force = true) },
                            )
                            SecondaryAction(
                                text = tr("打开 GitHub Releases", "Open GitHub Releases"),
                                onClick = { runCatching { uriHandler.openUri(AppUpdater.RELEASES_PAGE_URL) } },
                            )
                        }
                    }
                }
            }

            val res = outcome
            if (res != null) {
                val latest = res.latest
                val asset = res.matchedAsset
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(
                        0.5.dp,
                        if (res.hasUpdate) palette.borderAccent.copy(alpha = 0.55f) else palette.borderMuted.copy(alpha = 0.55f),
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (latest == null) {
                            Text(
                                tr("暂未在 GitHub 仓库发现已发布的版本。", "No published releases found on GitHub yet."),
                                style = PiTheme.text.prose,
                                color = palette.text,
                            )
                        } else if (res.hasUpdate) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    tr("发现新版本 v${latest.version}", "New Version v${latest.version}"),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = palette.text,
                                    modifier = Modifier.weight(1f),
                                )
                                TagPill(tr("可升级", "Update Ready"), tone = palette.success)
                            }
                            val metaParts = buildList {
                                latest.publishedAt?.let { add(it) }
                                asset?.let {
                                    add(it.name)
                                    if (it.sizeBytes > 0L) add(AppUpdater.formatBytes(it.sizeBytes))
                                    if (it.sha256 != null) add("SHA-256")
                                }
                            }
                            if (metaParts.isNotEmpty()) {
                                Text(
                                    metaParts.joinToString(" · "),
                                    style = PiTheme.text.meta,
                                    color = palette.dim,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = palette.success,
                                    modifier = Modifier.size(20.dp),
                                )
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        tr("当前已是最新版本（v$currentVer）", "You're up to date (v$currentVer)"),
                                        style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
                                        color = palette.text,
                                    )
                                    Text(
                                        tr("最新线上版本：${latest.tagName}${latest.publishedAt?.let { " · $it" }.orEmpty()}", "Latest GitHub release: ${latest.tagName}${latest.publishedAt?.let { " · $it" }.orEmpty()}"),
                                        style = PiTheme.text.meta,
                                        color = palette.dim,
                                    )
                                }
                            }
                        }

                        // 下载进度条
                        val prog = progress
                        if (downloading && prog != null) {
                            val targetFraction = prog.fraction ?: 0.15f
                            val animatedFraction by animateFloatAsState(
                                targetValue = targetFraction,
                                animationSpec = tween(120),
                                label = "apkDownloadProgress",
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        tr("正在下载更新包…", "Downloading update APK…"),
                                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                                        color = palette.accent,
                                    )
                                    Text(
                                        if (prog.totalBytes > 0L) {
                                            "${AppUpdater.formatBytes(prog.downloadedBytes)} / ${AppUpdater.formatBytes(prog.totalBytes)}"
                                        } else {
                                            AppUpdater.formatBytes(prog.downloadedBytes)
                                        },
                                        style = PiTheme.text.monoSmall,
                                        color = palette.muted,
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(animatedFraction)
                                            .height(6.dp)
                                            .clip(CircleShape)
                                            .background(palette.accent),
                                    )
                                }
                            }
                        }

                        // 待授权提示
                        if (waitingPermissionForApk != null && !AppUpdater.canInstallPackages(context)) {
                            Text(
                                tr(
                                    "更新包已下载完毕。请在系统设置中开启「允许来自此来源的应用」，返回后将自动启动安装。",
                                    "APK downloaded. Enable \"Allow from this source\" in system settings — installation will start automatically on return.",
                                ),
                                style = PiTheme.text.meta,
                                color = palette.warning,
                            )
                        }

                        // 操作按钮组
                        when {
                            downloading -> {
                                DestructiveAction(
                                    text = tr("取消下载", "Cancel Download"),
                                    fullWidth = true,
                                    onClick = {
                                        downloadJob?.cancel()
                                        downloadJob = null
                                        progress = null
                                    },
                                )
                            }
                            readyApk?.isFile == true -> {
                                PrimaryAction(
                                    text = if (AppUpdater.canInstallPackages(context)) {
                                        tr("立即覆盖安装 v${latest?.version.orEmpty()}", "Install v${latest?.version.orEmpty()} Now")
                                    } else {
                                        tr("授权并安装更新", "Grant Permission & Install")
                                    },
                                    fullWidth = true,
                                    onClick = { readyApk?.let(::triggerInstall) },
                                )
                            }
                            res.hasUpdate && asset != null -> {
                                PrimaryAction(
                                    text = tr(
                                        "下载并安装更新${if (asset.sizeBytes > 0L) "（${AppUpdater.formatBytes(asset.sizeBytes)}）" else ""}",
                                        "Download & Install${if (asset.sizeBytes > 0L) " (${AppUpdater.formatBytes(asset.sizeBytes)})" else ""}",
                                    ),
                                    fullWidth = true,
                                    onClick = {
                                        error = null
                                        progress = AppUpdater.DownloadProgress(0L, asset.sizeBytes)
                                        downloadJob = scope.launch {
                                            AppUpdater.downloadApk(context, asset) { p ->
                                                progress = p
                                            }.onSuccess { file ->
                                                readyApk = file
                                                triggerInstall(file)
                                            }.onFailure { err ->
                                                if (err !is CancellationException) {
                                                    error = err.message ?: tr("下载失败", "Download failed")
                                                }
                                            }
                                            downloadJob = null
                                        }
                                    },
                                )
                            }
                            res.hasUpdate && asset == null -> {
                                val variant = AppUpdater.expectedVariantLabel(res.targetSdk)
                                Text(
                                    tr(
                                        "此版本暂未上传匹配当前变体（$variant）的安装包，请前往 GitHub Release 页面查看。",
                                        "No compatible $variant APK found in this release. Check the GitHub Release page.",
                                    ),
                                    style = PiTheme.text.meta,
                                    color = palette.warning,
                                )
                            }
                        }

                        if (latest != null) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { runCatching { uriHandler.openUri(latest.htmlUrl) } }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    tr("在浏览器查看 GitHub Release 页面", "View release on GitHub"),
                                    style = PiTheme.text.meta,
                                    color = palette.mdLink,
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    tint = palette.mdLink,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                    }
                }

                // 更新日志卡片（原生 Markdown 富渲染）
                if (latest != null && latest.notes.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            tr("更新日志 · ${latest.tagName}", "Release Notes · ${latest.tagName}"),
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                            color = palette.muted,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(0.5.dp, palette.borderMuted.copy(alpha = 0.55f)),
                        ) {
                            Box(modifier = Modifier.padding(14.dp)) {
                                PiMarkdownText(markdown = latest.notes)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))
                Text(
                    tr(
                        "覆盖升级采用同一签名密钥，您的全部对话记录、登录凭据（auth.json）、扩展包与 Linux 容器环境均完整保留。",
                        "In-place updates use the same signing key — all sessions, credentials (auth.json), packages, and your Linux container remain intact.",
                    ),
                    style = PiTheme.text.meta,
                    color = palette.dim,
                )
            }
        }
    }
}
