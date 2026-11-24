package app.pi.ui.tree

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.protocol.SessionTreeNode
import app.pi.session.tree.TreeFilter
import app.pi.session.tree.TreeItem
import app.pi.session.tree.TreeModel
import app.pi.session.tree.TreeRow
import app.pi.session.tree.TreeTone
import app.pi.ui.chat.BranchSummaryChoice
import app.pi.ui.components.PiEmptyState
import app.pi.ui.theme.contrastOn
import app.pi.ui.components.PiSheet
import app.pi.ui.theme.PiPalette
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.launch

/**
 * 官方 Pi `/tree` 移动端会话树视图：
 *  - 底层逻辑 100% 委托给 [TreeModel]（已对照官方 SDK `tree-selector.js` 逐行验证）；
 *  - 视觉与交互移动端重构：顶栏共用搜索键可折叠搜索、深度上限防窄屏挤压 + 深度角标、
 *    矢量折叠开关（32dp 热区）、活动路径高亮轨道与圆点、工具/书签/摘要角色胶囊、
 *    一键定位当前位置与分支跳转、加载中空态区分、平滑动效操作面板。
 */
@Composable
fun SessionTreeView(
    roots: List<SessionTreeNode>,
    leafId: String?,
    loading: Boolean,
    busy: String?,
    streaming: Boolean,
    searchOpen: Boolean,
    showStamps: Boolean,
    jumpCurrentTick: Int,
    bottomPadding: Dp,
    initialFilter: TreeFilter,
    reselect: String?,
    onReselected: () -> Unit,
    onFork: (String) -> Unit,
    onNavigate: (String, BranchSummaryChoice, String?) -> Unit,
    onLabel: (String, String?) -> Unit,
    onHideFromContext: (String) -> Unit,
    onReplaceInContext: (String, String) -> Unit,
    onAbortSummary: () -> Unit,
    skipSummaryPrompt: () -> Boolean,
    modifier: Modifier = Modifier,
    homeDir: String? = null,
    onScrolledChange: ((Boolean) -> Unit)? = null,
) {
    var filterName by rememberSaveable { mutableStateOf(initialFilter.name) }
    val filter = TreeFilter.entries.firstOrNull { it.name == filterName } ?: initialFilter
    var query by rememberSaveable { mutableStateOf("") }
    var folded by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() }),
    ) { mutableStateOf(emptySet<String>()) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(searchOpen) {
        if (!searchOpen && query.isNotEmpty()) query = ""
    }

    LaunchedEffect(reselect) {
        if (reselect != null) {
            selectedId = reselect
            onReselected()
        }
    }

    val view = remember(roots, leafId, filter, query, folded, homeDir) {
        TreeModel.view(roots, leafId, filter, query, folded, homeDir)
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    if (onScrolledChange != null) {
        val treeScrolled by remember(listState) {
            androidx.compose.runtime.derivedStateOf { listState.canScrollBackward }
        }
        LaunchedEffect(treeScrolled, onScrolledChange) { onScrolledChange(treeScrolled) }
    }

    val branchIndices = remember(view.rows) {
        view.rows.mapIndexedNotNull { index, r ->
            if (r.item.foldable || r.showConnector && r.indent > 0) index else null
        }
    }

    LaunchedEffect(filter, leafId, view.rows.isNotEmpty()) {
        val at = view.rows.indexOfFirst { it.item.id == view.nearestToLeafId }
        if (at >= 0) listState.scrollToItem((at - 2).coerceAtLeast(0))
    }

    LaunchedEffect(jumpCurrentTick) {
        if (jumpCurrentTick > 0) {
            val at = view.rows.indexOfFirst { it.item.id == view.nearestToLeafId }
            if (at >= 0) listState.animateScrollToItem((at - 2).coerceAtLeast(0))
        }
    }

    Column(modifier.fillMaxWidth()) {
        // 统一筛选胶囊条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = PiSpacing.pageHorizontal, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (f in TreeFilter.entries) {
                val baseLabel = treeFilterLabel(f)
                val label = if (f == TreeFilter.Labeled && view.bookmarkCount > 0) "$baseLabel ${view.bookmarkCount}" else baseLabel
                TreeFilterPill(
                    text = label,
                    active = f == filter,
                    onClick = { filterName = f.name; folded = emptySet() },
                )
            }
        }

        AnimatedVisibility(
            visible = searchOpen,
            enter = fadeIn(tween(150)) + expandVertically(tween(180)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(150)),
        ) {
            TreeSearchField(
                query = query,
                onQuery = { query = it; folded = emptySet() },
            )
        }

        if (busy != null) SummaryBanner(busy, onAbortSummary)

        // 精简状态 + 快捷跳转栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.pageHorizontal, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (view.rows.size == view.totalEntries) {
                    tr("${view.totalEntries} 条 · ${treeFilterHint(filter)}", "${view.totalEntries} · ${treeFilterHint(filter)}")
                } else {
                    tr("${view.rows.size}/${view.totalEntries} 条 · ${treeFilterHint(filter)}", "${view.rows.size}/${view.totalEntries} · ${treeFilterHint(filter)}")
                },
                modifier = Modifier.weight(1f),
                style = PiTheme.text.meta,
                color = PiTheme.palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (view.rows.isNotEmpty() && view.nearestToLeafId != null) {
                MiniJumpPill(
                    icon = Icons.Filled.MyLocation,
                    label = tr("当前", "Now"),
                    onClick = {
                        val at = view.rows.indexOfFirst { it.item.id == view.nearestToLeafId }
                        if (at >= 0) scope.launch { listState.animateScrollToItem((at - 2).coerceAtLeast(0)) }
                    },
                )
            }
            if (branchIndices.size > 1) {
                MiniJumpIcon(
                    icon = Icons.Filled.KeyboardArrowUp,
                    contentDescription = tr("上一分支", "Previous branch"),
                    onClick = {
                        val cur = listState.firstVisibleItemIndex
                        val prev = branchIndices.lastOrNull { it < cur } ?: branchIndices.last()
                        scope.launch { listState.animateScrollToItem((prev - 1).coerceAtLeast(0)) }
                    },
                )
                MiniJumpIcon(
                    icon = Icons.Filled.KeyboardArrowDown,
                    contentDescription = tr("下一分支", "Next branch"),
                    onClick = {
                        val cur = listState.firstVisibleItemIndex
                        val next = branchIndices.firstOrNull { it > cur + 1 } ?: branchIndices.first()
                        scope.launch { listState.animateScrollToItem((next - 1).coerceAtLeast(0)) }
                    },
                )
            }
        }

        when {
            roots.isEmpty() && loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    PiEmptyState(
                        icon = Icons.Filled.Refresh,
                        title = tr("正在读取会话树…", "Loading session tree…"),
                        body = tr("正在解析当前会话的分支与节点。", "Reading branches and entries for the active session."),
                        modifier = Modifier.padding(top = 72.dp),
                    )
                }
            }
            view.rows.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    PiEmptyState(
                        icon = if (filter == TreeFilter.Labeled && query.isBlank()) Icons.Filled.BookmarkBorder else Icons.Filled.Search,
                        title = when {
                            query.isNotBlank() -> tr("没有匹配的条目", "No matching entries")
                            filter == TreeFilter.Labeled -> tr("还没有书签", "No labeled entries yet")
                            else -> tr("这个过滤下没有条目", "No entries in this filter")
                        },
                        body = if (filter == TreeFilter.Labeled && query.isBlank()) {
                            tr("点开任意一条，给它起个标签，之后在这里一键回到那里。", "Tap any entry to add a label, then jump back to it from here.")
                        } else {
                            tr("换个过滤或关键词试试。", "Try a different filter or search query.")
                        },
                        modifier = Modifier.padding(top = 72.dp),
                    )
                }
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(
                        top = 4.dp,
                        bottom = bottomPadding + PiSpacing.scrollBottom,
                    ),
                ) {
                    itemsIndexed(view.rows, key = { _, r -> r.item.id }) { _, row ->
                        TreeRowView(
                            row = row,
                            showStamps = showStamps,
                            onClick = { selectedId = row.item.id },
                            onToggleFold = {
                                folded = if (row.item.id in folded) folded - row.item.id else folded + row.item.id
                            },
                        )
                    }
                }
            }
        }
    }

    val picked = selectedId
    val fallbackPickedItem = remember(roots, leafId, picked) {
        if (picked == null) {
            null
        } else {
            TreeModel.view(roots, leafId, TreeFilter.All, "", emptySet(), homeDir).rows
                .firstOrNull { it.item.id == picked }?.item
        }
    }
    if (picked != null) {
        val item = view.rows.firstOrNull { it.item.id == picked }?.item ?: fallbackPickedItem
        if (item == null) {
            selectedId = null
        } else {
            EntrySheet(
                item = item,
                streaming = streaming,
                skipSummaryPrompt = skipSummaryPrompt,
                onDismiss = { selectedId = null },
                onFork = { selectedId = null; onFork(picked) },
                onNavigate = { choice, instructions -> selectedId = null; onNavigate(picked, choice, instructions) },
                onLabel = { label -> selectedId = null; onLabel(picked, label) },
                onHide = { selectedId = null; onHideFromContext(picked) },
                onReplace = { text -> selectedId = null; onReplaceInContext(picked, text) },
            )
        }
    }
}

private fun treeFilterLabel(filter: TreeFilter): String = when (filter) {
    TreeFilter.Default -> tr("默认", "Default")
    TreeFilter.NoTools -> tr("无工具", "No tools")
    TreeFilter.UserOnly -> tr("仅用户", "User")
    TreeFilter.Labeled -> tr("书签", "Labeled")
    TreeFilter.All -> tr("全部", "All")
}

private fun treeFilterHint(filter: TreeFilter): String = when (filter) {
    TreeFilter.Default -> tr("隐藏模型/档位等设置条目", "Hides setting entries")
    TreeFilter.NoTools -> tr("不显示工具结果", "Excludes tool results")
    TreeFilter.UserOnly -> tr("只看你的消息", "User messages only")
    TreeFilter.Labeled -> tr("只看书签条目", "Labeled entries only")
    TreeFilter.All -> tr("全部条目", "All entries")
}

private fun localizedTypeTitle(raw: String): String = when (raw) {
    "用户消息" -> tr("用户消息", "User Message")
    "助手消息" -> tr("助手消息", "Assistant Message")
    "工具结果" -> tr("工具结果", "Tool Result")
    "命令" -> tr("终端命令", "Bash Command")
    "消息" -> tr("消息", "Message")
    "压缩" -> tr("上下文压缩", "Compaction")
    "分支摘要" -> tr("分支摘要", "Branch Summary")
    "扩展消息" -> tr("扩展消息", "Extension Message")
    "扩展状态" -> tr("扩展状态", "Extension State")
    "切换模型" -> tr("切换模型", "Model Change")
    "思考档位" -> tr("思考档位", "Thinking Level")
    "标签" -> tr("书签标签", "Label")
    "会话信息" -> tr("会话信息", "Session Info")
    "用量记录" -> tr("用量记录", "Usage")
    "上下文编辑" -> tr("上下文编辑", "Context Edit")
    "未知条目" -> tr("未知条目", "Unknown Entry")
    else -> raw
}

@Composable
private fun TreeFilterPill(text: String, active: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(10.dp)
    val bg by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "tree-pill-bg",
    )
    val fg by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "tree-pill-fg",
    )
    Box(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(shape)
            .background(bg)
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 11.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = PiTheme.text.meta.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium),
            color = fg,
            maxLines = 1,
        )
    }
}

@Composable
private fun MiniJumpPill(icon: ImageVector, label: String, onClick: () -> Unit) {
    val accent = PiTheme.palette.accent
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = accent)
        Text(label, style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium), color = accent, maxLines = 1)
    }
}

@Composable
private fun MiniJumpIcon(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(16.dp), tint = PiTheme.palette.muted)
    }
}

@Composable
private fun TreeSearchField(query: String, onQuery: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.pageHorizontal, vertical = 4.dp)
            .height(40.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp), tint = PiTheme.palette.muted)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        tr("搜索会话树（空格分词，全部命中）", "Search tree (space-separated)"),
                        style = PiTheme.text.prose,
                        color = PiTheme.palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = PiTheme.text.prose.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(PiTheme.palette.accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClickLabel = tr("清除", "Clear")) { onQuery("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = tr("清除", "Clear"), modifier = Modifier.size(16.dp), tint = PiTheme.palette.muted)
                }
            }
        }
    }
}

@Composable
private fun SummaryBanner(label: String, onAbort: () -> Unit) {
    val p = PiTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.pageHorizontal, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(p.warning.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium), color = p.warning)
        Text(
            tr("停止摘要", "Abort"),
            modifier = Modifier
                .clip(CircleShape)
                .background(p.warning.copy(alpha = 0.18f))
                .clickable(onClick = onAbort)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
            color = p.warning,
        )
    }
}

private fun toneColor(tone: TreeTone, p: PiPalette): Color = when (tone) {
    TreeTone.Plain -> p.text
    TreeTone.User -> p.accent
    TreeTone.Assistant -> p.success
    TreeTone.Muted -> p.muted
    TreeTone.Dim -> p.dim
    TreeTone.Error -> p.error
    TreeTone.Warning -> p.warning
    TreeTone.Accent -> p.borderAccent
    TreeTone.Custom -> p.customMessageLabel
}

/**
 * 移动端优化后的会话树节点行：
 *  - 视觉缩进封顶 [MAX_VISUAL_DEPTH] 级（14dp/级），超深层用紧凑 `L7` 徽标提示，绝不挤扁正文；
 *  - 圆角弯折 Canvas 连线，活动路径用主题色半透明轨道高亮；
 *  - 32dp 触控折叠键（矢量 ChevronRight / ExpandMore）；
 *  - 角色分级渲染（工具结果等宽灰底胶囊、书签琥珀色图标胶囊、当前叶子高亮卡 + 左侧指示条）。
 */
@Composable
private fun TreeRowView(row: TreeRow, showStamps: Boolean, onClick: () -> Unit, onToggleFold: () -> Unit) {
    val item = row.item
    val p = PiTheme.palette
    val rawLevels = row.prefix.length / 3
    val levels = rawLevels.coerceAtMost(MAX_VISUAL_DEPTH)
    val railColor = if (item.onPath) p.accent.copy(alpha = 0.55f) else p.borderMuted.copy(alpha = 0.72f)
    val connector = row.showConnector && !row.isVirtualRootChild && levels > 0
    val isToolRow = item.head.isEmpty() && item.text.startsWith("[") && item.text.endsWith("]")
    val roleTag = item.head.trim().removeSuffix(":").takeIf { it.isNotEmpty() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (item.isLeaf) p.accent.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .drawBehind {
                val stroke = 1.4.dp.toPx()
                val unit = LEVEL.toPx()
                val pageStart = 8.dp.toPx()
                val mid = size.height / 2f
                val r = 5.dp.toPx()

                if (item.isLeaf) {
                    val barW = 3.dp.toPx()
                    drawRoundRect(
                        color = p.accent,
                        topLeft = Offset(0f, 6.dp.toPx()),
                        size = Size(barW, (size.height - 12.dp.toPx()).coerceAtLeast(0f)),
                        cornerRadius = CornerRadius(barW / 2f),
                    )
                }
                for (g in row.gutters) {
                    if (!g.show || g.position >= MAX_VISUAL_DEPTH) continue
                    val x = pageStart + (g.position + 0.5f) * unit
                    drawLine(p.borderMuted.copy(alpha = 0.65f), Offset(x, 0f), Offset(x, size.height), stroke, cap = StrokeCap.Round)
                }
                if (connector) {
                    val x = pageStart + (levels - 1 + 0.5f) * unit
                    val endX = pageStart + levels * unit - 1.dp.toPx()
                    if (!row.isLast) {
                        drawLine(railColor, Offset(x, 0f), Offset(x, size.height), stroke, cap = StrokeCap.Round)
                        drawLine(railColor, Offset(x, mid), Offset(endX, mid), stroke, cap = StrokeCap.Round)
                    } else {
                        val elbow = Path().apply {
                            moveTo(x, 0f)
                            lineTo(x, (mid - r).coerceAtLeast(0f))
                            quadraticTo(x, mid, (x + r).coerceAtMost(endX), mid)
                            lineTo(endX, mid)
                        }
                        drawPath(elbow, color = railColor, style = Stroke(width = stroke, cap = StrokeCap.Round))
                    }
                }
            }
            .padding(start = 8.dp + LEVEL * levels, end = 8.dp)
            .heightIn(min = ROW_MIN),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 折叠开关或活动路径圆点（统一 28dp 槽宽，对齐所有节点正文）
        Box(
            modifier = Modifier
                .size(width = FOLD_SLOT, height = ROW_MIN)
                .then(
                    if (item.foldable || item.folded) {
                        Modifier.clickable(
                            onClickLabel = if (item.folded) tr("展开分支", "Expand branch") else tr("折叠分支", "Collapse branch"),
                            onClick = onToggleFold,
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                item.foldable || item.folded -> {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (item.folded) p.accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (item.folded) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (item.folded) p.accent else p.muted,
                        )
                    }
                }
                item.isLeaf -> {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(p.accent.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(5.dp).clip(CircleShape).background(p.accent))
                    }
                }
                item.onPath -> {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(p.accent.copy(alpha = 0.85f)))
                }
                else -> {
                    Box(Modifier.size(4.dp).clip(CircleShape).background(p.borderMuted))
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .alpha(if (item.hiddenFromContext) 0.52f else 1f)
                .padding(vertical = ROW_PAD, horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // 第一行微标签条（书签 / 角色 / 深层角标 / 当前叶子）
            val hasTopMeta = item.label != null || roleTag != null || rawLevels > MAX_VISUAL_DEPTH || item.isLeaf
            if (hasTopMeta) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (roleTag != null) {
                        val roleColor = toneColor(item.headTone, p)
                        Text(
                            text = roleTag,
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                            color = roleColor,
                            maxLines = 1,
                        )
                    }
                    if (item.label != null) {
                        Row(
                            modifier = Modifier
                                .clip(PiShapes.tag)
                                .background(p.warning.copy(alpha = 0.16f))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(Icons.Filled.Bookmark, contentDescription = null, modifier = Modifier.size(11.dp), tint = p.warning)
                            Text(
                                text = item.label,
                                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                                color = p.warning,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (showStamps && item.labelTimestamp != null) {
                                val stamp = TreeModel.formatLabelTimestamp(item.labelTimestamp)
                                if (stamp.isNotEmpty()) {
                                    Text(stamp, style = PiTheme.text.meta, color = p.muted, maxLines = 1)
                                }
                            }
                        }
                    }
                    if (rawLevels > MAX_VISUAL_DEPTH) {
                        Text(
                            text = "L$rawLevels",
                            modifier = Modifier
                                .clip(PiShapes.tag)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                            style = PiTheme.text.monoSmall,
                            color = p.muted,
                            maxLines = 1,
                        )
                    }
                    if (item.isLeaf) {
                        Text(
                            text = tr("当前", "Current"),
                            modifier = Modifier
                                .clip(PiShapes.tag)
                                .background(p.accent.copy(alpha = 0.16f))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                            color = p.accent,
                            maxLines = 1,
                        )
                    }
                }
            }

            // 正文或工具胶囊
            if (isToolRow) {
                Text(
                    text = item.text.removeSurrounding("[", "]"),
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    style = PiTheme.text.monoSmall,
                    color = toneColor(item.textTone, p),
                    textDecoration = if (item.hiddenFromContext) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = item.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = PiTheme.text.prose,
                    color = toneColor(item.textTone, p),
                    textDecoration = if (item.hiddenFromContext) TextDecoration.LineThrough else null,
                    fontWeight = if (item.isLeaf) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }

        if (item.hiddenFromContext) {
            Icon(
                imageVector = Icons.Filled.VisibilityOff,
                contentDescription = tr("不再发给模型", "Excluded from context"),
                tint = p.dim,
                modifier = Modifier.padding(start = 6.dp).size(15.dp),
            )
        }
    }
}

private enum class Step { Actions, SummaryChoice, SummaryInstructions, Label, Replace }

@Composable
private fun EntrySheet(
    item: TreeItem,
    streaming: Boolean,
    skipSummaryPrompt: () -> Boolean,
    onDismiss: () -> Unit,
    onFork: () -> Unit,
    onNavigate: (BranchSummaryChoice, String?) -> Unit,
    onLabel: (String?) -> Unit,
    onHide: () -> Unit,
    onReplace: (String) -> Unit,
) {
    var step by remember(item.id) { mutableStateOf(Step.Actions) }
    var labelText by remember(item.id) { mutableStateOf(item.label.orEmpty()) }
    var instructions by remember(item.id) { mutableStateOf("") }
    val fullText = item.copyText ?: (item.head + item.text)
    var replacement by remember(item.id) { mutableStateOf(item.copyText.orEmpty()) }
    val clipboard = LocalClipboardManager.current
    val p = PiTheme.palette

    PiSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.pageHorizontal)
                .padding(bottom = PiSpacing.unit),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    localizedTypeTitle(item.typeTitle),
                    style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
                    color = p.text,
                )
                if (item.isLeaf) {
                    Text(
                        tr("当前位置", "Current"),
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(p.accent.copy(alpha = 0.16f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                        color = p.accent,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    fullText,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    style = PiTheme.text.prose,
                    color = p.text,
                )
            }
            AnimatedContent(
                targetState = step,
                transitionSpec = { fadeIn(tween(150)).togetherWith(fadeOut(tween(110))) },
                label = "tree-sheet-step",
            ) { currentStep ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (currentStep) {
                        Step.Actions -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                            ) {
                                var hasRow = false
                                fun dividerIfNeeded() = hasRow
                                if (!item.isLeaf) {
                                    val title = if (item.rewinds) {
                                        tr("回到此消息并改写重发", "Rewind & edit message")
                                    } else {
                                        tr("切换到此节点继续对话", "Switch branch here")
                                    }
                                    val hint = buildString {
                                        append(
                                            if (item.rewinds) tr("撤回到此条之前，原文放回输入框（Pi 官方 /tree 语义）", "Rewinds before this entry and puts its text into the editor")
                                            else tr("保留到此条为止的对话上下文，从这里继续", "Continue conversation from this entry"),
                                        )
                                        if (streaming) append(tr("（将先停止本轮）", " (will abort current turn)"))
                                    }
                                    ActionRow(
                                        title = title,
                                        hint = hint,
                                        icon = Icons.Filled.PlayArrow,
                                        tint = p.accent,
                                    ) {
                                        if (skipSummaryPrompt()) onNavigate(BranchSummaryChoice.NoSummary, null) else step = Step.SummaryChoice
                                    }
                                    hasRow = true
                                }
                                if (item.forkable) {
                                    if (dividerIfNeeded()) SheetRowDivider()
                                    ActionRow(
                                        title = tr("从这里分叉", "Fork from here"),
                                        hint = tr("新建独立会话文件，原会话不变", "Create a new session file; original stays intact"),
                                        icon = Icons.Filled.AccountTree,
                                        tint = p.success,
                                        onClick = onFork,
                                    )
                                    hasRow = true
                                }
                                if (dividerIfNeeded()) SheetRowDivider()
                                ActionRow(
                                    title = if (item.label == null) tr("加标签（书签）", "Add label") else tr("改标签", "Edit label"),
                                    hint = item.label?.let { tr("当前：「$it」", "Current: \"$it\"") } ?: tr("方便在「书签」里一键定位", "Quickly locate under Labeled"),
                                    icon = Icons.Filled.BookmarkBorder,
                                    tint = p.warning,
                                ) { step = Step.Label }
                                hasRow = true
                                if (item.contextEditable && !item.hiddenFromContext) {
                                    SheetRowDivider()
                                    ActionRow(
                                        title = tr("不再发给模型", "Exclude from context"),
                                        hint = tr("对话记录保留，后续上下文不再携带", "Keep in transcript, omit from future model context"),
                                        icon = Icons.Filled.VisibilityOff,
                                        tint = p.muted,
                                        onClick = onHide,
                                    )
                                    SheetRowDivider()
                                    ActionRow(
                                        title = tr("改写模型看到的内容", "Edit in context"),
                                        hint = tr("仅改上下文版本，原文不变", "Modify context version only; original stays intact"),
                                        icon = Icons.Filled.Edit,
                                        tint = p.mdLink,
                                    ) { step = Step.Replace }
                                }
                                if (item.copyText != null) {
                                    SheetRowDivider()
                                    ActionRow(
                                        title = tr("复制文本", "Copy text"),
                                        hint = null,
                                        icon = Icons.Filled.ContentCopy,
                                        tint = p.muted,
                                    ) { clipboard.setText(AnnotatedString(fullText)); onDismiss() }
                                }
                            }
                        }
                        Step.SummaryChoice -> {
                            Text(tr("要摘要被放弃的那一段吗？", "Summarize abandoned branch?"), style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold), color = p.text)
                            Text(tr("摘要会调用一次模型，可随时中止；关闭面板回到树。", "Summarizing calls the model and can be aborted; dismiss to return to tree."), style = PiTheme.text.meta, color = p.muted)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                            ) {
                                ActionRow(tr("不摘要", "No summary"), tr("直接切换，不消耗 token", "Switch immediately without using tokens")) { onNavigate(BranchSummaryChoice.NoSummary, null) }
                                SheetRowDivider(indent = 12.dp)
                                ActionRow(tr("自动摘要", "Summarize"), tr("使用默认摘要提示词", "Use default summary prompt")) { onNavigate(BranchSummaryChoice.Summarize, null) }
                                SheetRowDivider(indent = 12.dp)
                                ActionRow(tr("用自定义指令摘要…", "Summarize with custom prompt…"), tr("指定需要保留的关键信息", "Specify key details to preserve")) { step = Step.SummaryInstructions }
                            }
                        }
                        Step.SummaryInstructions -> {
                            TextBox(instructions, { instructions = it }, tr("摘要指令：要留住什么", "Summary instructions: what to preserve"))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            ) {
                                TreePillButton(tr("返回", "Back"), primary = false) { step = Step.SummaryChoice }
                                TreePillButton(tr("开始摘要", "Summarize"), primary = true) { onNavigate(BranchSummaryChoice.SummarizeWithPrompt, instructions.trim().ifEmpty { null }) }
                            }
                        }
                        Step.Label -> {
                            TextBox(labelText, { labelText = it }, tr("标签，例如「方案 A 之前」", "Label, e.g. \"Before plan A\""), singleLine = true)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            ) {
                                if (item.label != null) {
                                    TreePillButton(tr("清除标签", "Clear label"), primary = false) { onLabel(null) }
                                }
                                TreePillButton(tr("返回", "Back"), primary = false) { step = Step.Actions }
                                TreePillButton(tr("保存", "Save"), primary = true) { onLabel(labelText.trim().ifEmpty { null }) }
                            }
                        }
                        Step.Replace -> {
                            TextBox(replacement, { replacement = it }, tr("模型将看到的内容", "Content sent to model"))
                            Text(tr("只影响之后发给模型的上下文，不改原始记录。", "Only affects future model context; original transcript is unchanged."), style = PiTheme.text.meta, color = p.muted)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            ) {
                                TreePillButton(tr("返回", "Back"), primary = false) { step = Step.Actions }
                                TreePillButton(tr("保存改写", "Save edit"), primary = true) { onReplace(replacement) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetRowDivider(indent: Dp = 54.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = indent),
        thickness = PiSpacing.hairlineThin,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
}

@Composable
private fun TreePillButton(label: String, primary: Boolean, onClick: () -> Unit) {
    val p = PiTheme.palette
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (primary) p.accent else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
            color = if (primary) p.contrastOn(p.accent) else p.text,
            maxLines = 1,
        )
    }
}

@Composable
private fun ActionRow(
    title: String,
    hint: String?,
    icon: ImageVector? = null,
    tint: Color = PiTheme.palette.accent,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium), color = PiTheme.palette.text)
            if (hint != null) Text(hint, style = PiTheme.text.meta, color = PiTheme.palette.muted)
        }
    }
}

@Composable
private fun TextBox(value: String, onChange: (String) -> Unit, placeholder: String, singleLine: Boolean = false) {
    val focus = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    app.pi.ui.components.PiAutoFocus(focus)
    val boxMin = if (singleLine) 42.dp else 108.dp
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = boxMin)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) {
                runCatching { focus.requestFocus() }
                keyboard?.show()
            },
        shape = PiShapes.cardInner,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = boxMin).padding(12.dp)) {
            if (value.isEmpty()) Text(placeholder, style = PiTheme.text.meta, color = PiTheme.palette.dim)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = singleLine,
                textStyle = PiTheme.text.prose.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(PiTheme.palette.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = (boxMin - 24.dp).coerceAtLeast(20.dp))
                    .focusRequester(focus),
            )
        }
    }
}

private const val MAX_VISUAL_DEPTH = 5
private val ROW_PAD = 7.dp
private val ROW_MIN = 40.dp
private val LEVEL = 14.dp
private val FOLD_SLOT = 28.dp
