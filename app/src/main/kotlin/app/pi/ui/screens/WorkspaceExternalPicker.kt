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
import app.pi.runtime.WorkspaceChoice
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.StateTone
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「从设备目录选择」——把一个**设备上的真目录**登记成工作区。
 *
 * ## 为什么是「所有文件访问」，不是 SAF
 *
 * 工作区必须是**真实文件路径**：`PiEngineHost` 把 `workspace.absolutePath` 绑进 guest 并把它当
 * 进程的 cwd（`PiEngineHost.kt:407`、`:451`），proot 的绑定点只吃真路径。SAF 给的是
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
        title = "需要「所有文件访问」",
        onClose = onClose,
        subtitle = "只有把设备目录当工作区时才需要它。",
        footer = "授权之后回到这里就能浏览设备目录；App 私有目录里的工作区不需要任何权限。",
    ) {
        WsNotice(
            text = "工作区必须是设备的**真实目录**：引擎要把它 bind 进 guest 并作为工作目录，" +
                "而系统文档选择器给的是内容 URI，绑不了。所以这一项要的是「所有文件访问」。",
            tone = StateTone.Warning,
            glyph = "!",
        )
        WsCard {
            WsRow(
                title = "去系统设置授权",
                strong = true,
                titleColor = PiTheme.palette.accent,
                lead = { PickerFolderGlyph() },
                meta = "打开本应用的「所有文件访问」页；回来时会重新检查，没开就还是这一屏。",
                onClick = onAsk,
            )
        }
        Text(
            "授权页由系统提供：本应用无法替你打开那个开关。Android 11 起这一项在「设置 → 应用 → " +
                "本应用 → 特殊应用权限 → 所有文件访问」；Android 10 及更早用的是普通存储权限，" +
                "点上面那一行会直接弹系统的授权框。",
            modifier = Modifier.padding(
                start = PiSettingsMetrics.pageHorizontal,
                end = PiSettingsMetrics.pageHorizontal,
                top = PiSettingsMetrics.cardPaddingLoose,
                bottom = PiSettingsMetrics.groupGap,
            ),
            style = PiTheme.text.meta,
            color = PiTheme.palette.muted,
        )
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
)

@Composable
private fun WorkspaceExternalBrowser(
    context: Context,
    onClose: () -> Unit,
    onPick: (String) -> Unit,
) {
    val volumes = remember { volumeRoots(context) }
    // 从第一个卷根开始。没有卷根（理论上不会发生）时说清楚，而不是显示一个空列表。
    var current by remember { mutableStateOf(volumes.firstOrNull()) }
    var level by remember { mutableStateOf<ExternalLevel?>(null) }
    var loading by remember { mutableStateOf(false) }

    // 上溯到卷根为止：卷根以上（`/storage`、`/`）是拒绝选的，所以面包屑到卷根就是顶。
    val atVolumeRoot = current != null && volumes.any { it == current }
    val parent = current?.let { File(it).parent }

    LaunchedEffect(current) {
        val path = current ?: return@LaunchedEffect
        loading = true
        level = withContext(Dispatchers.IO) { readLevel(File(path)) }
        loading = false
    }

    val here = current ?: run {
        WsSheet(title = "选择设备目录", onClose = onClose) {
            WsNotice(text = "这台设备没有报告任何可用的存储卷。", tone = StateTone.Warning, glyph = "!")
        }
        return
    }
    val readable = level?.children != null
    val refusal = WorkspaceChoice.pickRefusal(here, volumes, readable)

    WsSheet(
        title = "选择设备目录",
        onClose = onClose,
        subtitle = here,
        subtitleMono = true,
        footer = "选中的目录会成为 pi 的现场目录：项目设置、技能、提示词都从它的 .pi 里读，" +
            "在里面写文件就是写到设备上这个目录。",
        maxBodyHeight = EXTERNAL_SHEET_BODY_MAX,
    ) {
        // 当前目录的读数与「就选这个目录」。它在滚动区**顶部**：用户往上翻了一遍之后要能立刻
        // 看到自己刚才是在哪一层做的决定。
        WsCard {
            WsRow(
                title = "就选这个目录",
                strong = true,
                titleColor = if (refusal == null) PiTheme.palette.accent else PiTheme.palette.muted,
                lead = { PickerFolderGlyph() },
                badge = if (refusal != null) {
                    { WsBadge(text = "不能选", tone = StateTone.Warning) }
                } else {
                    null
                },
                meta = refusal ?: "点一下把它登记成工作区；文件不会被移动或改动。",
                onClick = if (refusal == null) {
                    { onPick(here) }
                } else {
                    // 拒绝也要给一句话（`meta` 就是那句），所以这里留一个可点的壳，点一下把
                    // 原因再念一遍 —— 静默不可点的按钮是这一屏最不该有的东西。
                    null
                },
            )
        }

        if (!atVolumeRoot && parent != null) {
            WsCard {
                WsRow(
                    title = "上一级",
                    lead = { PickerFolderGlyph() },
                    meta = parent,
                    metaMono = true,
                    onClick = { current = parent },
                )
            }
        }

        if (loading && level == null) {
            WsNotice(text = "正在读这个目录…", tone = StateTone.Muted, glyph = "·")
        }

        val children = level?.children
        if (children != null && children.isEmpty()) {
            WsNotice(
                text = "这个目录里没有子目录（空目录也可以当工作区）。",
                tone = StateTone.Muted,
                glyph = "·",
            )
        }
        if (children != null && children.isNotEmpty()) {
            // 一张卡、`forEachIndexed` 的行 —— 与 `WorkspaceRootSheet` 同一形状，**不是**
            // `LazyColumn`：`WsSheet` 的正文本身是 `verticalScroll` + 有上限的 `Column`，在它
            // 里面再放一个纵向懒列表就是「无限高约束下测量可滚动组件」，那是崩溃不是慢。
            // 子目录数上面有 `EXTERNAL_MAX_CHILDREN` 的封顶，所以这一列是有界的。
            Text(
                "子目录",
                modifier = Modifier.padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSettingsMetrics.groupGap,
                    bottom = PiSettingsMetrics.groupHeaderGap,
                ),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurface,
            )
            WsCard {
                children.forEachIndexed { index, child ->
                    if (index > 0) WsHairline()
                    WsRow(
                        title = child.file.name,
                        mono = true,
                        lead = { PickerFolderGlyph() },
                        badge = if (child.count == null) {
                            { WsBadge(text = "读不到", tone = StateTone.Warning) }
                        } else {
                            null
                        },
                        meta = when {
                            child.count == null -> "没有权限，或者这个卷现在不可用。"
                            child.count == 0 -> "空目录"
                            else -> "${child.count} 个条目"
                        },
                        onClick = if (child.count == null) null else {
                            { current = child.file.absolutePath }
                        },
                    )
                }
            }
        }
        if (children != null && level?.truncated == true) {
            WsNotice(
                text = "这个目录里还有更多子目录，这里只列了前 $EXTERNAL_MAX_CHILDREN 个；" +
                    "从上面逐级下去，或者用「上一级」换一条路。",
                tone = StateTone.Muted,
                glyph = "·",
            )
        }
    }
}

/** 数一个目录的子目录与可读性。IO 线程上调用。 */
private fun readLevel(dir: File): ExternalLevel {
    val listed = runCatching { dir.listFiles() }.getOrNull()
    if (listed == null) return ExternalLevel(dir, emptyList(), truncated = false)
    val dirs = listed
        .filter { it.isDirectory && !it.name.startsWith(".") }
        .sortedBy { it.name.lowercase() }
    val truncated = dirs.size > EXTERNAL_MAX_CHILDREN
    val shown = if (truncated) dirs.take(EXTERNAL_MAX_CHILDREN) else dirs
    val children = shown.map { child ->
        // 每个子目录数一次条目：这是「里面有没有东西」唯一的真实读数。读不到（Android/data
        // 那种被系统挡住的位置）就是 null，界面印「读不到」而不是「0 个条目」。
        val count = runCatching { child.listFiles()?.size }.getOrNull()
        ExternalChild(child, count)
    }
    return ExternalLevel(dir, children, truncated)
}

/**
 * 这个应用现在能不能列出共享存储。
 *
 * 它是**能力**判据，不是某个开关的读数：API 30+ 看 `isExternalStorageManager()`，更早的版本
 * 看主共享存储能不能读（`MANAGE_EXTERNAL_STORAGE` 在那些版本上不参与，老式存储权限才管用）。
 */
private fun storageAccessGranted(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    } else {
        runCatching { File(Environment.getExternalStorageDirectory(), "").canRead() }.getOrDefault(false)
    }

/**
 * 可以出发的卷根：主共享存储 + `StorageManager` 报告的可移除卷。
 *
 * 顺序与去重由 [WorkspaceChoice.volumeRoots] 决定（主存储第一，重复的根只留一次）。
 */
private fun volumeRoots(context: Context): List<String> {
    val primary = runCatching { Environment.getExternalStorageDirectory()?.absolutePath }.getOrNull()
    val removable = runCatching {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        manager?.storageVolumes.orEmpty().mapNotNull { volume ->
            // 只列可移除卷：内置卷就是主共享存储，重复列一次只会多一行一样的字。
            if (!volume.isRemovable) return@mapNotNull null
            if (volume.state != Environment.MEDIA_MOUNTED) return@mapNotNull null
            volume.directory?.absolutePath
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
