package app.pi.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.pi.i18n.tr
import app.pi.ui.PiTopBar
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy

/**
 * Third-party licence notices for what this app distributes.
 *
 * This screen is **not** a transcription of a pi screen: pi has no licence
 * surface, and it has no obligation to publish one. The obligation is the
 * distributor's, so the screen, the component list and the licence texts are all
 * this app's own.
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
 * The notices themselves: a grouped, searchable list of every bundled licence, and the full
 * text of whichever one the user opened.
 */
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
    var query by remember { mutableStateOf("") }

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

    val q = query.trim()
    val filtered = remember(notices, q) {
        if (q.isEmpty()) notices else notices.filter {
            it.title.contains(q, ignoreCase = true) || it.section.contains(q, ignoreCase = true)
        }
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrolled by remember(listState) {
        androidx.compose.runtime.derivedStateOf { listState.canScrollBackward }
    }
    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(title = tr("开源许可", "Open Source Licenses"), onBack = onBack, scrolled = scrolled)
        if (notices.isEmpty()) {
            Text(
                tr("正在读取…", "Loading…"),
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
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = app.pi.ui.theme.PiSpacing.inline,
                bottom = contentPadding.calculateBottomPadding() + PiSettingsMetrics.groupGap,
            ),
        ) {
            item(key = "license-search") {
                app.pi.ui.models.ModelsSearchBar(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = tr("搜索组件或协议名称", "Search package or license"),
                    modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal),
                )
            }
            if (filtered.isEmpty() && q.isNotEmpty()) {
                item(key = "license-empty") {
                    Column(modifier = Modifier.padding(top = PiSettingsMetrics.groupGap)) {
                        PiSettingsCard {
                            PiSettingsEntryRow(
                                title = tr("没有匹配的许可条目", "No matching licenses"),
                                supporting = tr("试试换一个组件名或协议关键字。", "Try a different package name or license keyword."),
                            )
                        }
                    }
                }
            }
            filtered.groupBy { it.section }.forEach { (section, entries) ->
                item(key = "section-$section") {
                    Column(modifier = Modifier.padding(top = PiSettingsMetrics.groupGap)) {
                        if (section.isNotBlank()) {
                            Text(
                                text = section,
                                modifier = Modifier.padding(
                                    start = PiSettingsMetrics.pageHorizontal + app.pi.ui.theme.PiSpacing.small,
                                    end = PiSettingsMetrics.pageHorizontal,
                                    bottom = PiSettingsMetrics.groupHeaderGap,
                                ),
                                style = PiTheme.text.meta,
                                color = PiTheme.palette.muted,
                            )
                        }
                        PiSettingsCard {
                            entries.forEachIndexed { index, notice ->
                                if (index > 0) PiSettingsHairline()
                                PiSettingsEntryRow(
                                    title = notice.title,
                                    supporting = null,
                                    onClick = { open = notice },
                                    monoTitle = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The full text of one notice, read on demand. */
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
    val bodyScroll = rememberScrollState()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var copied by remember(notice.file) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(
            title = notice.title,
            onBack = onBack,
            scrolled = bodyScroll.canScrollBackward,
            actions = {
                val copyable = body
                if (copyable != null) {
                    app.pi.ui.PiTopBarIcon(
                        onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(copyable))
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            copied = true
                        },
                        contentDescription = if (copied) tr("已复制许可文本", "License text copied") else tr("复制许可文本", "Copy license text"),
                        icon = if (copied) androidx.compose.material.icons.Icons.Filled.Check else androidx.compose.material.icons.Icons.Filled.ContentCopy,
                        highlighted = copied,
                    )
                }
            },
        )
        val text = body
        if (text == null) {
            Text(
                tr("正在读取…", "Loading…"),
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
                .verticalScroll(bodyScroll)
                .padding(
                    top = PiSettingsMetrics.notePaddingVertical,
                    bottom = contentPadding.calculateBottomPadding() + PiSettingsMetrics.groupGap,
                ),
        ) {
            PiSettingsCard {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        text,
                        modifier = Modifier.padding(PiSettingsMetrics.cardPadding),
                        style = PiTheme.text.monoSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
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
