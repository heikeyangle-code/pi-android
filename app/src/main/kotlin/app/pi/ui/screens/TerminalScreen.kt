package app.pi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.i18n.tr
import app.pi.ui.components.PiDialog
import app.pi.ui.components.PiDialogAction
import app.pi.ui.components.PiDialogActions
import app.pi.ui.components.PiDialogBody
import app.pi.ui.components.PiDialogTitle
import app.pi.ui.components.PiMenu
import app.pi.ui.components.PiType
import app.pi.ui.components.PiMenuItem
import app.pi.ui.components.PiMenuPlacement
import app.pi.ui.terminal.PiTerminalManager
import app.pi.ui.terminal.TerminalPalette
import app.pi.ui.terminal.TerminalPane
import app.pi.ui.terminal.TerminalPreferences
import app.pi.ui.terminal.TerminalViewRef
import app.pi.ui.terminal.hideSoftKeyboard
import app.pi.ui.terminal.showSoftKeyboard
import app.pi.ui.terminal.toggleSoftKeyboard
import app.pi.ui.theme.PiMonoFamily
import app.pi.ui.theme.PiTheme

/**
 * Pi 官方风格全屏多标签终端页：
 * - 紧凑 48dp 顶栏（左侧返回按钮仅最小化、绝不杀死后台终端；中间可横滑多标签胶囊 + 实时运行状态点；
 *   右侧 `+` 新建标签与 `⋮` 终端菜单：字号调节、粘贴、复制屏幕、清屏、重启、键盘开关、常亮）；
 * - 中间全屏承载 Termux 原生 60fps `TerminalView` + JetBrains Mono；
 * - 底部紧贴软键盘的 2×8 满宽触感辅助键条。
 */
@Composable
fun TerminalScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    initialGuestCwd: String? = null,
) {
    val context = LocalContext.current
    val piPalette = PiTheme.palette
    val palette = remember(piPalette) { TerminalPalette.of(piPalette) }
    val viewHolder = remember { TerminalViewRef() }

    LaunchedEffect(initialGuestCwd) {
        PiTerminalManager.openOrFocus(
            context = context,
            guestCwd = initialGuestCwd,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .windowInsetsPadding(
                WindowInsets.navigationBars
                    .union(WindowInsets.ime)
                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            ),
    ) {
        TerminalTabHeader(
            palette = palette,
            viewHolder = viewHolder,
            onBack = onBack,
        )
        TerminalPane(
            palette = palette,
            viewHolder = viewHolder,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TerminalTabHeader(
    palette: TerminalPalette,
    viewHolder: TerminalViewRef,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val tabs = PiTerminalManager.tabs
    val activeId = PiTerminalManager.activeTabId
    val fontSizeSp = PiTerminalManager.fontSizeSp
    val keepScreenOn = PiTerminalManager.keepScreenOn
    var menuOpen by remember { mutableStateOf(false) }
    var closingTab by remember { mutableStateOf<PiTerminalManager.Tab?>(null) }
    val tabListState = rememberLazyListState()

    LaunchedEffect(activeId, tabs.size) {
        val idx = tabs.indexOfFirst { it.id == activeId }
        if (idx >= 0) {
            tabListState.animateScrollToItem(idx)
        }
    }

    val hairline = palette.border
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.chromeBg)
            .statusBarsPadding()
            .height(56.dp)
            .drawBehind {
                drawLine(
                    color = hairline,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 0.5.dp.toPx(),
                )
            }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 返回（最小化到对话/项目，保持后台终端存活）
        HeaderIconButton(
            contentDescription = tr("返回（保持终端后台运行）", "Back (keep terminal running)"),
            onClick = onBack,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = palette.foreground.copy(alpha = 0.88f),
                modifier = Modifier.size(22.dp),
            )
        }

        // 多标签条（自动滚到当前活动标签）
        if (tabs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = tr("终端", "Terminal"),
                    style = PiType.topBarTitle,
                    color = palette.foreground.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            LazyRow(
                state = tabListState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(tabs, key = { it.id }) { tab ->
                    val selected = tab.id == activeId
                    TerminalTabPill(
                        tab = tab,
                        selected = selected,
                        palette = palette,
                        onSelect = { PiTerminalManager.selectTab(tab.id) },
                        onClose = {
                            if (tab.running) {
                                closingTab = tab
                            } else {
                                PiTerminalManager.closeTab(tab.id)
                            }
                        },
                    )
                }
            }
        }

        // 一键收起 / 弹出软键盘（响应真实 IME 状态，直接走 WindowInsetsControllerCompat，无菜单焦点竞争）
        val density = LocalDensity.current
        val imeVisible = WindowInsets.ime.getBottom(density) > 0
        HeaderIconButton(
            contentDescription = if (imeVisible) {
                tr("收起软键盘", "Hide keyboard")
            } else {
                tr("弹出软键盘", "Show keyboard")
            },
            onClick = {
                val view = viewHolder.view
                if (imeVisible) {
                    hideSoftKeyboard(context, view)
                } else if (view != null) {
                    showSoftKeyboard(context, view)
                }
            },
        ) {
            Icon(
                imageVector = if (imeVisible) Icons.Filled.KeyboardHide else Icons.Filled.Keyboard,
                contentDescription = null,
                tint = if (imeVisible) palette.accent else palette.foreground.copy(alpha = 0.88f),
                modifier = Modifier.size(21.dp),
            )
        }

        // 新建标签
        HeaderIconButton(
            contentDescription = tr("新建终端标签", "New terminal tab"),
            onClick = { PiTerminalManager.createTab(context) },
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = palette.foreground.copy(alpha = 0.88f),
                modifier = Modifier.size(22.dp),
            )
        }

        // 更多操作菜单
        Box {
            HeaderIconButton(
                contentDescription = tr("终端选项", "Terminal options"),
                onClick = { menuOpen = true },
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = null,
                    tint = palette.foreground.copy(alpha = 0.88f),
                    modifier = Modifier.size(22.dp),
                )
            }
            if (menuOpen) {
                val activeTab = PiTerminalManager.activeTab
                PiMenu(
                    expanded = true,
                    onDismiss = { menuOpen = false },
                    placement = PiMenuPlacement.Below,
                    items = buildList {
                        add(
                            PiMenuItem(tr("粘贴剪贴板", "Paste clipboard")) {
                                menuOpen = false
                                PiTerminalManager.pasteToActive(context)
                            },
                        )
                        add(
                            PiMenuItem(tr("复制当前屏幕", "Copy visible screen")) {
                                menuOpen = false
                                PiTerminalManager.copyVisibleScreen(context)
                            },
                        )
                        add(
                            PiMenuItem(tr("放大字号 (${(fontSizeSp + 1f).coerceAtMost(TerminalPreferences.MAX_FONT_SP).toInt()}sp)", "Zoom in (${(fontSizeSp + 1f).coerceAtMost(TerminalPreferences.MAX_FONT_SP).toInt()}sp)")) {
                                menuOpen = false
                                PiTerminalManager.setFontSize(context, fontSizeSp + 1f, showToast = true)
                            },
                        )
                        add(
                            PiMenuItem(tr("缩小字号 (${(fontSizeSp - 1f).coerceAtLeast(TerminalPreferences.MIN_FONT_SP).toInt()}sp)", "Zoom out (${(fontSizeSp - 1f).coerceAtLeast(TerminalPreferences.MIN_FONT_SP).toInt()}sp)")) {
                                menuOpen = false
                                PiTerminalManager.setFontSize(context, fontSizeSp - 1f, showToast = true)
                            },
                        )
                        add(
                            PiMenuItem(tr("清屏", "Clear screen")) {
                                menuOpen = false
                                PiTerminalManager.clearActiveScreen()
                            },
                        )
                        add(
                            PiMenuItem(
                                if (imeVisible) {
                                    tr("收起软键盘", "Hide keyboard")
                                } else {
                                    tr("弹出软键盘", "Show keyboard")
                                },
                            ) {
                                menuOpen = false
                                toggleSoftKeyboard(context, viewHolder.view, fromMenu = true)
                            },
                        )
                        add(
                            PiMenuItem(
                                if (keepScreenOn) {
                                    tr("关闭屏幕常亮 ✓", "Keep screen on ✓")
                                } else {
                                    tr("保持屏幕常亮", "Keep screen on")
                                },
                            ) {
                                menuOpen = false
                                PiTerminalManager.keepScreenOn = !keepScreenOn
                                PiTerminalManager.showToast(
                                    if (PiTerminalManager.keepScreenOn) {
                                        tr("已开启屏幕常亮", "Keep screen on enabled")
                                    } else {
                                        tr("已关闭屏幕常亮", "Keep screen on disabled")
                                    },
                                )
                            },
                        )
                        if (activeTab != null) {
                            add(
                                PiMenuItem(tr("重启当前终端", "Restart current tab")) {
                                    menuOpen = false
                                    PiTerminalManager.restartTab(context, activeTab.id)
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    closingTab?.let { target ->
        PiDialog(onDismissRequest = { closingTab = null }) {
            PiDialogTitle(
                title = tr("关闭运行中的终端？", "Close Running Terminal?"),
                glyph = "!",
                glyphTone = PiTheme.palette.warning,
            )
            PiDialogBody(
                tr(
                    "「${target.title}」中的 Shell 进程仍在运行，关闭标签将结束该进程。",
                    "The shell process in "${target.title}" is still running. Closing this tab will terminate it.",
                ),
            )
            PiDialogActions {
                PiDialogAction(
                    label = tr("取消", "Cancel"),
                    primary = false,
                    onClick = { closingTab = null },
                )
                PiDialogAction(
                    label = tr("关闭终端", "Close Tab"),
                    primary = true,
                    tone = PiTheme.palette.error,
                    onClick = {
                        closingTab = null
                        PiTerminalManager.closeTab(target.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun TerminalTabPill(
    tab: PiTerminalManager.Tab,
    selected: Boolean,
    palette: TerminalPalette,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val shape = RoundedCornerShape(9.dp)
    val bg = if (selected) palette.background else palette.chip.copy(alpha = 0.55f)
    val borderColor = if (selected) palette.accent.copy(alpha = 0.55f) else palette.border
    val dotColor = if (tab.running) Color(palette.ansi16[2]) else palette.foreground.copy(alpha = 0.35f)

    Row(
        modifier = Modifier
            .height(32.dp)
            .widthIn(min = 72.dp, max = 168.dp)
            .clip(shape)
            .background(bg, shape)
            .border(if (selected) 1.dp else 0.5.dp, borderColor, shape)
            .clickable(onClick = onSelect)
            .padding(start = 9.dp, end = if (selected) 2.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = tab.title,
            color = palette.foreground.copy(alpha = if (selected) 0.96f else 0.65f),
            fontFamily = PiMonoFamily,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (selected) {
            Spacer(Modifier.width(2.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = tr("关闭终端标签", "Close terminal tab"),
                        onClick = onClose,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = palette.foreground.copy(alpha = 0.58f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun HeaderIconButton(
    contentDescription: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
