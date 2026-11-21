package app.pi.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
import app.pi.ui.components.PiAutoFocus
import app.pi.ui.components.PiDialog
import app.pi.ui.components.PiDialogAction
import app.pi.ui.components.PiDialogActions
import app.pi.ui.components.PiDialogBody
import app.pi.ui.components.PiDialogTitle
import app.pi.ui.theme.PiSpacing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.runtime.WorkspaceChoice
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.StateTone
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「从设备目录选择」——把一个**设备上的真目录**登记成工作区。
 *
 * ## 为什么是「所有文件访问」，不是 SAF
 *
 * 工作区必须是**真实文件路径**：`PiEngineHost` 把 `workspace.absolutePath` 绑进 guest 并把它当
 * 进程的 cwd（`PiEngineHost.kt:407`、`:451`），proot/proroot 的绑定点只吃真路径。SAF 给的是
 * `content://` 树 URI，它能经 `/app/saf` 下的通道读写，但**不能当工作区根** —— 引擎要的是一个能
 * `bind` 的目录。所以这一屏走 `MANAGE_EXTERNAL_STORAGE`（API 30+ 的「所有文件访问」），而不是
 * 让用户以为随便给个 SAF 授权就够了。这句话在界面上也印出来（[WorkspaceExternalIntro]）。
 *
 * ## 权限的三条路，都是诚实的
 *
 *  - **API 30+**：`Environment.isExternalStorageManager()` 是唯一的判据，没有就去
 *    `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`（本应用的授权页）。回来重新判定 —— 用户
 *    可能在那一页上没开就退出来，那时**不能**当作成功。
 *  - **API 26–29**：没有那个开关，`MANAGE_EXTERNAL_STORAGE` 也没意义（老式存储权限还管用），
 *    所以走 `READ/WRITE_EXTERNAL_STORAGE` 的运行时申请（manifest 里两条都带 `maxSdkVersion`）。
 *  - 判据统一是「**这个应用现在能不能列出主共享存储**」（[storageAccessGranted]），而不是
 *    「某个开关的当前值」：前者才是后面那个浏览器真正需要的东西。
 *
 * 旧式 `READ/WRITE_EXTERNAL_STORAGE` **不**用来糊 API 30+ —— 从 30 起系统直接忽略它。
 *
 * ## 浏览的是真目录树
 *
 * 从 [WorkspaceChoice.volumeRoots]（主共享存储 + 可移除卷）出发逐级往下。每一行印的是「里面
 * 有几个条目」与「读不到」两种真实读数之一，而「就选这个目录」按
 * [WorkspaceChoice.pickRefusal] 决定能不能按 —— **每一次拒绝都有一句话**，不静默置灰。
 *
 * 列一遍目录要 N 次 `listFiles()`（每个子目录数一次条目），所以整段在 IO 线程上跑，一次导航
 * 算一次，不在组合里算。
 */
@Composable
internal fun WorkspaceExternalPicker(
    onClose: () -> Unit,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(storageAccessGranted(context)) }

    // 从系统设置页回来：重新判定，而不是假定用户开了。`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`
    // 在少数 ROM 上不存在，那时退回全局的「所有文件访问」列表页 —— 两者都在系统设置里，都不是
    // 本应用能替用户点的。
    // 回到前台就重查一次权限：不少系统（MIUI / ColorOS …）把「所有文件访问」页开在另一个任务里，
    // `StartActivityForResult` 的回调在页面刚打开时就触发了，用户授权完回来没有任何回调；
    // 只靠回调会永远停在「需要授权」那一屏。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                granted = storageAccessGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { granted = storageAccessGranted(context) }
    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = storageAccessGranted(context) }

    fun askForAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appPage = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            val listed = runCatching { settingsLauncher.launch(appPage) }
            if (listed.isFailure) {
                runCatching { settingsLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        } else {
            permissionsLauncher.launch(
                arrayOf(
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                ),
            )
        }
    }

    if (!granted) {
        WorkspaceExternalIntro(onClose = onClose, onAsk = { askForAccess() })
        return
    }
    WorkspaceExternalBrowser(
        context = context,
        onClose = onClose,
        onPick = onPick,
    )
}

/**
 * 没有权限时的说明页。
 *
 * 它必须把**两件事**说清，否则用户点完授权回来会以为 SAF/别的授权也行：
 * 一是要哪一项权限，二是为什么必须是真路径（工作区就是引擎的 cwd 与 bind 点）。
 */
@Composable
private fun WorkspaceExternalIntro(onClose: () -> Unit, onAsk: () -> Unit) {
    WsSheet(
        title = tr("需要「所有文件访问」权限", "\"All files access\" Required"),
        onClose = onClose,
        subtitle = tr("用于在手机存储中直接打开或新建项目目录。", "Needed to open or create project folders on device storage."),
        footer = tr("应用沙盒内的项目不需要此权限。", "App-private projects do not require this permission."),
    ) {
        WsNotice(
            text = tr(
                "Pi 需要真实目录路径作为工作目录（cwd），因此需要「所有文件访问」权限。",
                "Pi requires a real directory path as its working directory (cwd), which requires \"All files access\".",
            ),
            tone = StateTone.Warning,
            glyph = "!",
        )
        WsCard {
            WsRow(
                title = tr("前往系统设置授权", "Grant in System Settings"),
                strong = true,
                titleColor = PiTheme.palette.accent,
                lead = { PickerFolderGlyph() },
                meta = tr("开启后返回此页即可浏览与新建目录。", "Return here after enabling to browse and create folders."),
                onClick = onAsk,
            )
        }
    }
}

/** 一行要显示的目录。 */
private class ExternalChild(
    val file: File,
    /** 里面有几个条目；null = 读不到。 */
    val count: Int?,
)

/** 浏览状态：当前目录 + 它的子目录读数。 */
private class ExternalLevel(
    val dir: File,
    val children: List<ExternalChild>,
    /** 被截断时说明为什么 —— 一个静默变短的列表看起来就像「这个目录是空的」。 */
    val truncated: Boolean,
    /** 这个目录读得到吗；读不到时列表是空的，但**不**代表目录空。 */
    val readable: Boolean,
    /** 可写才能当工作区（pi 要在里面建文件）。 */
    val writable: Boolean,
)

@Composable
private fun WorkspaceExternalBrowser(
    context: Context,
    onClose: () -> Unit,
    onPick: (String) -> Unit,
) {
    val volumes = remember { volumeRoots(context) }
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(volumes.firstOrNull()) }
    var level by remember { mutableStateOf<ExternalLevel?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var createFolderOpen by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(current, reloadTick) {
        val path = current ?: return@LaunchedEffect
        loading = true
        level = withContext(Dispatchers.IO) { readLevel(File(path)) }
        loading = false
    }

    val here = current ?: run {
        WsSheet(title = tr("选择设备目录", "Select Device Folder"), onClose = onClose) {
            WsNotice(text = tr("这台设备没有报告任何可用的存储卷。", "No available storage volumes reported on this device."), tone = StateTone.Warning, glyph = "!")
        }
        return
    }
    val levelHere = level?.takeIf { it.dir.absolutePath == here }
    val refusal = when {
        levelHere == null -> WorkspaceChoice.pickRefusal(here, volumes, readable = true)
            ?: tr("正在读这个目录…", "Reading directory…")
        else -> WorkspaceChoice.pickRefusal(here, volumes, levelHere.readable)
            ?: if (!levelHere.writable) {
                tr("这个目录不可写，Pi 无法在其中创建或编辑文件。", "This directory is not writable; Pi cannot create or edit files here.")
            } else {
                null
            }
    }

    val volumeRoot = volumes.firstOrNull { here == it || here.startsWith(it.trimEnd('/') + "/") }
    // Quick shortcuts for common storage roots so users don't have to hunt through 30 Android folders.
    val quickPlaces = remember(volumes) {
        buildList {
            volumes.forEachIndexed { idx, root ->
                add((if (idx == 0) tr("内部存储", "Internal") else root.substringAfterLast('/')) to root)
            }
            val primary = volumes.firstOrNull()
            if (primary != null) {
                listOf("Download" to tr("下载", "Download"), "Documents" to tr("文档", "Documents")).forEach { (dir, label) ->
                    val f = File(primary, dir)
                    if (f.isDirectory) add(label to f.absolutePath)
                }
            }
        }
    }

    WsSheet(
        title = tr("设备目录", "Device Folder"),
        onClose = onClose,
        subtitle = here,
        subtitleMono = true,
        trailing = {
            Button(onClick = { onPick(here) }, enabled = refusal == null) { Text(tr("设为项目", "Use Folder")) }
        },
        footer = tr(
            "直接将所选目录设为 Pi 项目目录（cwd），不复制或移动任何文件。",
            "Uses this folder directly as Pi's project directory (cwd) without copying files.",
        ),
        maxBodyHeight = EXTERNAL_SHEET_BODY_MAX,
    ) {
        // Quick location chips (Internal / Download / Documents / SD Card)
        if (quickPlaces.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(
                        horizontal = PiSettingsMetrics.pageHorizontal,
                        vertical = 6.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
            ) {
                quickPlaces.forEach { (label, targetPath) ->
                    WsChip(
                        text = label,
                        solid = here == targetPath,
                        onClick = {
                            createError = null
                            current = targetPath
                        },
                    )
                }
            }
        }

        // Breadcrumbs + "+ 新建文件夹" button
        if (volumeRoot != null) {
            val rootLabel = if (volumes.indexOf(volumeRoot) <= 0) tr("内部存储", "Internal") else volumeRoot.substringAfterLast('/')
            val segments = here.removePrefix(volumeRoot).trim('/').split('/').filter { it.isNotEmpty() }
            val scroll = rememberScrollState()
            LaunchedEffect(here) { scroll.animateScrollTo(scroll.maxValue) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(scroll),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PickerCrumb(rootLabel, current = segments.isEmpty()) { current = volumeRoot }
                    var acc = volumeRoot.trimEnd('/')
                    segments.forEachIndexed { index, segment ->
                        acc = "$acc/$segment"
                        val target = acc
                        Text(
                            "›",
                            modifier = Modifier.padding(horizontal = PiSpacing.tiny),
                            style = PiTheme.text.monoSmall,
                            color = PiTheme.palette.muted,
                        )
                        PickerCrumb(segment, current = index == segments.lastIndex) { current = target }
                    }
                }
                if (levelHere?.writable == true) {
                    WsChip(
                        text = tr("新建文件夹", "New Folder"),
                        glyphIcon = Icons.Filled.CreateNewFolder,
                        onClick = {
                            createError = null
                            createFolderOpen = true
                        },
                    )
                }
            }
        }

        if (createError != null) {
            WsNotice(text = createError.orEmpty(), tone = StateTone.Warning, glyph = "!")
        }

        if (refusal != null) {
            WsNotice(
                text = refusal,
                tone = if (levelHere == null) StateTone.Muted else StateTone.Warning,
                glyph = if (levelHere == null) "·" else "!",
            )
        }

        if (loading && levelHere == null) {
            WsNotice(text = tr("正在读这个目录…", "Reading directory…"), tone = StateTone.Muted, glyph = "·")
        }

        val children = levelHere?.children
        if (children != null && children.isEmpty()) {
            WsNotice(
                text = tr("此目录下暂无子文件夹，可直接点击右上角「设为项目」或新建文件夹。", "No subfolders here. Tap \"Use Folder\" above or create a new folder."),
                tone = StateTone.Muted,
                glyph = "·",
            )
        }
        if (children != null && children.isNotEmpty()) {
            WsSectionHeader(label = tr("子文件夹", "Subfolders"), count = "${children.size}")
            WsCard {
                children.forEachIndexed { index, child ->
                    if (index > 0) WsHairline(startInset = WsIconHairlineStart)
                    WsRow(
                        title = child.file.name,
                        mono = false,
                        lead = { PickerFolderGlyph() },
                        badge = if (child.count == null) {
                            { WsBadge(text = tr("读不到", "Unreadable"), tone = StateTone.Warning) }
                        } else {
                            null
                        },
                        meta = when {
                            child.count == null -> tr("没有权限或存储不可用", "Permission denied or unavailable")
                            child.count == 0 -> tr("空目录", "Empty")
                            else -> tr("${child.count} 项", if (child.count == 1) "1 item" else "${child.count} items")
                        },
                        trail = if (child.count == null) {
                            null
                        } else {
                            {
                                Icon(
                                    Icons.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
                                    tint = PiTheme.palette.muted,
                                )
                            }
                        },
                        onClick = if (child.count == null) null else {
                            {
                                createError = null
                                current = child.file.absolutePath
                            }
                        },
                    )
                }
            }
        }
        if (children != null && levelHere?.truncated == true) {
            WsNotice(
                text = tr("仅展示前 $EXTERNAL_MAX_CHILDREN 个子文件夹。", "Showing first $EXTERNAL_MAX_CHILDREN subfolders."),
                tone = StateTone.Muted,
                glyph = "·",
            )
        }
    }

    if (createFolderOpen) {
        ExternalNewFolderDialog(
            parentPath = here,
            onDismiss = { createFolderOpen = false },
            onCreate = { folderName ->
                createFolderOpen = false
                scope.launch {
                    val created = withContext(Dispatchers.IO) {
                        val target = File(here, folderName)
                        when {
                            target.exists() -> Result.failure(IllegalStateException(tr("已有同名文件夹「$folderName」。", "Folder \"$folderName\" already exists.")))
                            !target.mkdirs() -> Result.failure(IllegalStateException(tr("创建文件夹失败，请检查存储权限。", "Failed to create folder; check storage permission.")))
                            else -> Result.success(target)
                        }
                    }
                    created.fold(
                        onSuccess = { dir ->
                            createError = null
                            current = dir.absolutePath
                            reloadTick++
                        },
                        onFailure = { err ->
                            createError = err.message ?: tr("创建文件夹失败", "Failed to create folder")
                        },
                    )
                }
            },
        )
    }
}

@Composable
private fun ExternalNewFolderDialog(
    parentPath: String,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val problem = if (name.isEmpty()) null else WorkspaceChoice.nameProblem(trimmed)
    val canSubmit = trimmed.isNotEmpty() && problem == null
    val focus = remember { FocusRequester() }
    PiAutoFocus(focus)

    PiDialog(onDismissRequest = onDismiss) {
        PiDialogTitle(title = tr("新建文件夹", "New Folder"))
        PiDialogBody(parentPath, mono = true)
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = PiSettingsMetrics.cardPadding)
                .height(52.dp)
                .clip(RoundedCornerShape(WsShape.medium))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(
                    PiSettingsMetrics.hairline,
                    if (problem != null) PiTheme.palette.error else PiTheme.palette.borderAccent,
                    RoundedCornerShape(WsShape.medium),
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canSubmit) onCreate(trimmed) }),
                textStyle = PiTheme.text.mono.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(PiTheme.palette.accent),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (name.isEmpty()) {
                            Text(
                                tr("文件夹名称，例如 my-app", "Folder name, e.g. my-app"),
                                style = PiTheme.text.mono.copy(fontSize = 16.sp),
                                color = PiTheme.palette.muted,
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        if (problem != null) {
            Text(
                problem,
                modifier = Modifier.padding(top = PiSpacing.inline),
                style = PiTheme.text.meta,
                color = PiTheme.palette.error,
            )
        }
        PiDialogActions {
            PiDialogAction(label = tr("取消", "Cancel"), primary = false, onClick = onDismiss)
            PiDialogAction(
                label = tr("创建并进入", "Create & Open"),
                primary = true,
                enabled = canSubmit,
                onClick = { onCreate(trimmed) },
            )
        }
    }
}

/** 面包屑的一段：当前层加粗不可点，其余可点。 */
@Composable
private fun PickerCrumb(text: String, current: Boolean, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier
            .then(if (current) Modifier else Modifier.clickable(onClick = onClick))
            .padding(vertical = PiSpacing.inline),
        style = PiTheme.text.monoSmall.let {
            if (current) it.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) else it
        },
        color = if (current) MaterialTheme.colorScheme.onSurface else PiTheme.palette.muted,
        maxLines = 1,
    )
}

/** 数一个目录的子目录与可读性。IO 线程上调用。 */
private fun readLevel(dir: File): ExternalLevel {
    val listed = runCatching { dir.listFiles() }.getOrNull()
    if (listed == null) {
        return ExternalLevel(dir, emptyList(), truncated = false, readable = false, writable = false)
    }
    val dirs = listed
        .filter { it.isDirectory && !it.name.startsWith(".") }
        .sortedBy { it.name.lowercase() }
    val truncated = dirs.size > EXTERNAL_MAX_CHILDREN
    val shown = if (truncated) dirs.take(EXTERNAL_MAX_CHILDREN) else dirs
    val children = shown.map { child ->
        val count = runCatching { child.list()?.size }.getOrNull()
        ExternalChild(child, count)
    }
    return ExternalLevel(
        dir, children, truncated,
        readable = true,
        writable = runCatching { dir.canWrite() }.getOrDefault(false),
    )
}

private fun storageAccessGranted(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    } else {
        // 以前用 `File(...).canRead()`：在 Android 10 上没有权限也可能读得出根目录，
        // 于是跳过授权直接进浏览器，里面却什么都列不出来。直接问权限本身。
        fun held(permission: String) = androidx.core.content.ContextCompat
            .checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        held(android.Manifest.permission.READ_EXTERNAL_STORAGE) &&
            held(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

private fun volumeRoots(context: Context): List<String> {
    val primary = runCatching { Environment.getExternalStorageDirectory()?.absolutePath }.getOrNull()
    val removable = runCatching {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        manager?.storageVolumes.orEmpty().mapNotNull { volume ->
            // 只列可移除卷：内置卷就是主共享存储，重复列一次只会多一行一样的字。
            if (!volume.isRemovable) return@mapNotNull null
            if (volume.state != Environment.MEDIA_MOUNTED) return@mapNotNull null
            // `StorageVolume.getDirectory()` 是 API 30 才有的；更早的系统上它不存在，
            // 存储卡就整个不出现在可选卷里。旧系统退回隐藏的 `getPath()`。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                volume.directory?.absolutePath
            } else {
                runCatching {
                    volume.javaClass.getMethod("getPath").invoke(volume) as? String
                }.getOrNull()
            }
        }
    }.getOrDefault(emptyList())
    return WorkspaceChoice.volumeRoots(primary, removable)
}

/** 这一屏的正文上限：比默认的 420 高，因为一屏要放下「就选这个目录」+ 一条路径列表。 */
private val EXTERNAL_SHEET_BODY_MAX = 480.dp

/** 一层最多列这么多子目录；再多就印一句说明而不是把列表悄悄截短。 */
private const val EXTERNAL_MAX_CHILDREN = 200

/**
 * 行前缀的目录图标。
 *
 * 它是这一屏自己的一个（`ProjectScreen.FolderGlyph` 是同名同事物的**另一份**，但那个是
 * `private`）：跨文件共用要先把它搬进 `WorkspaceChrome`，而这一屏不值得为一个小图标动那个文件
 * —— 图标是外观，两处都取 `PiTheme.palette.muted` 与 `searchIconSize`，改主题时一起动。
 */
@Composable
private fun PickerFolderGlyph() {
    Icon(
        Icons.Filled.Folder,
        contentDescription = null,
        modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
        tint = PiTheme.palette.muted,
    )
}
