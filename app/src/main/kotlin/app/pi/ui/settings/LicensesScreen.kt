package app.pi.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Third-party licence notices for what this app distributes.
 *
 * This screen is **not** a transcription of a pi screen: pi has no licence
 * surface, and it has no obligation to publish one. The obligation is the
 * distributor's, so the screen, the component list and the licence texts are all
 * this app's own (`docs/known-gaps.md` §L).
 *
 * ## Where the content comes from
 *
 * `assets/licenses/manifest.txt` indexes the directory: one `file<TAB>title<TAB>section`
 * line per notice. Everything the screen shows is data, so adding or re-generating
 * a licence never needs a Kotlin change — and the texts themselves are assembled
 * from the very artifacts the app ships (`tools/build-license-assets.py`), never
 * typed in by hand.
 *
 * The files are read with [produceState] on [Dispatchers.IO]: the manifest is a few
 * lines, but an individual copyright file can be ~60 KB, and reading that on the
 * composition thread would drop frames on open.
 */
private const val ASSET_DIR = "licenses"
private const val MANIFEST = "manifest.txt"

/** One row of [MANIFEST]. */
private data class LicenceNotice(
    val file: String,
    val title: String,
    val section: String,
)

/**
 * Settings-home entry. `null` in the caller hides it, the same way the capability
 * and package rows do, so a preview or a test can render the home without it.
 */
@Composable
fun PiLicensesEntryRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 设置首页卡片里的一行（v2 的 Row：`padding:10px 12px`、前置图标 16 灰、
    // 标题 15/500、尾部值 + chevron 14）。
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
    ) {
        Icon(
            Icons.Filled.Info,
            contentDescription = null,
            tint = PiTheme.palette.muted,
            modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
        )
        Column(Modifier.weight(1f)) {
            Text(
                "开源许可",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "App 内分发的第三方组件、许可证原文，以及 GPL / LGPL 程序的源代码获取方式",
                modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "打开",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.chevronSize),
            tint = PiTheme.palette.muted,
        )
    }
}

/**
 * The notices themselves: a grouped list of every bundled licence, and the full
 * text of whichever one the user opened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val notices by produceState<List<LicenceNotice>>(emptyList()) {
        value = withContext(Dispatchers.IO) { readManifest(context) }
    }
    var open by remember { mutableStateOf<LicenceNotice?>(null) }

    // One level deeper than the settings stack, so back closes the text before it
    // leaves the screen; the stack's own handler stays responsible for the rest.
    BackHandler(enabled = open != null) { open = null }

    val current = open
    if (current != null) {
        LicenceTextScreen(
            notice = current,
            contentPadding = contentPadding,
            onBack = { open = null },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("开源许可") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        if (notices.isEmpty()) {
            Text(
                "正在读取…",
                modifier = Modifier.padding(
                    horizontal = PiSettingsMetrics.pageHorizontal,
                    vertical = PiSettingsMetrics.groupGap,
                ),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        ) {
            // Grouping is by the manifest's own section field, in the order the
            // sections first appear, so the statement, the component list, the
            // licence texts and the per-package copyright files stay in the
            // sequence the manifest author put them in.
            notices.groupBy { it.section }.forEach { (section, entries) ->
                // v2 的许可页：分区头带计数，行装在一张圆角 10 的卡里，行间 1px
                // inset hairline。一个分区是一个 LazyColumn item，所以卡片与它的
                // 行不会被懒加载拆开。
                item(key = "section-$section") {
                    PiSettingsSectionHeader(label = section, count = "${entries.size} 项")
                    PiSettingsCard {
                        entries.forEachIndexed { index, notice ->
                            if (index > 0) PiSettingsHairline()
                            NoticeRow(notice, onClick = { open = notice })
                        }
                    }
                }
            }
        }
    }
}

/** One notice row: title, and the file it opens. */
@Composable
private fun NoticeRow(notice: LicenceNotice, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
    ) {
        Text(
            notice.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "阅读",
            style = PiTheme.text.mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.chevronSize),
            tint = PiTheme.palette.muted,
        )
    }
}

/** The full text of one notice, read on demand. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicenceTextScreen(
    notice: LicenceNotice,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val body by produceState<String?>(null, notice.file) {
        value = withContext(Dispatchers.IO) { readAsset(context, notice.file) }
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(notice.title, maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        val text = body
        if (text == null) {
            Text(
                "正在读取…",
                modifier = Modifier.padding(
                    horizontal = PiSettingsMetrics.pageHorizontal,
                    vertical = PiSettingsMetrics.groupGap,
                ),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    bottom = contentPadding.calculateBottomPadding() + PiSettingsMetrics.groupGap,
                ),
        ) {
            // Monospace, because these are licence and copyright files: the line
            // breaks, the indented warranty clauses and the ASCII rules in the
            // GPL texts are part of how they read.
            Text(
                text,
                style = PiTheme.text.monoSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Parse `manifest.txt`; a malformed line is skipped rather than crashing the screen. */
private fun readManifest(context: android.content.Context): List<LicenceNotice> {
    val raw = readAsset(context, MANIFEST) ?: return emptyList()
    return raw.lineSequence()
        .map { it.trimEnd('\n', '\r') }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3) return@mapNotNull null
            val file = parts[0].trim()
            if (file.isEmpty()) return@mapNotNull null
            LicenceNotice(file = file, title = parts[1].trim(), section = parts[2].trim())
        }
        .toList()
}

/** Read one text asset, or null when it is absent. */
private fun readAsset(context: android.content.Context, name: String): String? =
    runCatching {
        context.assets.open("$ASSET_DIR/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()
