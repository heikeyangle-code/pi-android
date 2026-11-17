package app.pi.ui.resources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.pi.ui.PiTopBar
import app.pi.ui.models.ModelsCard
import app.pi.ui.models.ModelsCardPadding
import app.pi.ui.models.ModelsDivider
import app.pi.ui.models.ModelsSectionLabel
import app.pi.ui.models.TagPill
import app.pi.ui.settings.PiInfoNote
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * 「上下文与系统提示」：pi **这次实际读到**的 `AGENTS.md` / `CLAUDE.md`（从工作目录一路往上找，
 * 全部拼进系统提示）、`SYSTEM.md`（替换默认系统提示）、`APPEND_SYSTEM.md`（追加）。
 * 这些文件在项目的 `.pi` 下的，要项目被信任才会读（所以没读到时先看项目信任）。
 * 每一份都能直接编辑（宿主按 SDK 的位置表判定），没有的可以新建；改完在枢纽页「重新加载」。
 */
@Composable
internal fun ContextScreen(
    model: ResourcesModel,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    val rows = contextRows(model.loaded)
    Column(Modifier.fillMaxSize()) {
        PiTopBar(title = "上下文与系统提示", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + PiSpacing.scrollBottom),
            verticalArrangement = Arrangement.spacedBy(PiSpacing.inline + PiSpacing.small),
        ) {
            item {
                PiInfoNote(
                    "系统提示由这些拼成：默认提示（或 SYSTEM.md 替换它）→ APPEND_SYSTEM.md 追加 → AGENTS.md / CLAUDE.md 上下文文件 → 技能清单。下面按这个顺序列出 pi 此刻读到的。",
                )
            }
            if (model.trust?.trusted == false) {
                item { PiInfoNote("项目还没被信任：项目里 .pi 下的 SYSTEM.md / APPEND_SYSTEM.md 不会被读。", tone = PiTheme.palette.warning) }
            }
            if (rows.isEmpty()) {
                item { PiInfoNote("没有读到任何上下文文件或系统提示文件。在项目根放一个 AGENTS.md，pi 就会把它带给模型。") }
            } else {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                        ModelsSectionLabel("${rows.size} 个文件")
                        ModelsCard {
                            Column {
                                rows.forEachIndexed { i, row ->
                                    if (i > 0) ModelsDivider()
                                    Row(
                                        Modifier.fillMaxWidth().clickable { onEdit(row.path) }.padding(horizontal = ModelsCardPadding, vertical = PiSpacing.inline + PiSpacing.small),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PiSpacing.tiny)) {
                                            Text(shortPath(row.path), style = PiTheme.text.mono, color = PiTheme.palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            if (row.size > 0) Text("${row.size} 字符" + if (row.truncated) "（预览已截断）" else "", style = PiTheme.text.meta, color = PiTheme.palette.muted)
                                        }
                                        TagPill(row.role)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal)) {
                    PiSettingsChip(text = "新建上下文文件", onClick = { creating = true })
                }
            }
        }
    }

    if (creating) {
        NewContextDialog(model, onDismiss = { creating = false }, onCreated = { path -> creating = false; model.refresh(); onEdit(path) })
    }
}
