package app.pi.ui.terminal

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import app.pi.R
import app.pi.i18n.tr
import app.pi.ui.theme.PiMonoFamily
import app.pi.ui.theme.PiTheme
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * 原生 60fps 终端核心画布 + 底部辅助键条：
 * - 直接承载 Termux 原生 `TerminalView`（硬件加速 Canvas + 完整中文 IME 组合 + 长按选字手柄 + 双指缩放）；
 * - `TerminalView` 尺寸变化（弹出软键盘、横竖屏、双指缩放字号）自动触发内核 `TIOCSWINSZ` + `SIGWINCH`，
 *   Shell / `vim` / `htop` / `pi` TUI 实时重排，字体永远保持清晰可读；
 * - 离开界面只解绑视图引用、**绝不杀死后台终端进程**。
 */
@Composable
fun TerminalPane(
    palette: TerminalPalette,
    viewHolder: TerminalViewRef,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activeTab = PiTerminalManager.activeTab
    val fontSizeSp = PiTerminalManager.fontSizeSp
    val keepScreenOn = PiTerminalManager.keepScreenOn
    val spawning = PiTerminalManager.spawning
    val lastError = PiTerminalManager.lastError
    val toastMessage = PiTerminalManager.toastMessage
    val toastEpoch = PiTerminalManager.toastEpoch

    var ctrlState by remember { mutableStateOf(ModifierState.Off) }
    var altState by remember { mutableStateOf(ModifierState.Off) }

    val typeface = remember(context) {
        runCatching { ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular) }.getOrNull()
            ?: Typeface.MONOSPACE
    }

    val settingsStore = remember(context) { terminalSettingsStore(context) }
    val keyBarRows = remember(settingsStore) {
        runCatching { TerminalPreferences.read(settingsStore).keyBar }
            .getOrDefault(TerminalBarKey.defaultRows)
    }

    LaunchedEffect(palette) {
        PiTerminalManager.applyPalette(palette)
    }

    LaunchedEffect(toastEpoch) {
        if (toastMessage != null) {
            delay(1800)
            PiTerminalManager.clearToast()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            PiTerminalManager.attachView(null, palette)
            viewHolder.view = null
        }
    }

    val viewClient = remember(context) {
        object : TerminalViewClient {
            private var scaleAccumulator = 1.0f

            override fun onScale(scale: Float): Float {
                if (scale < 0.9f || scale > 1.1f) {
                    val isUp = scale > 1.0f
                    val next = (PiTerminalManager.fontSizeSp + if (isUp) 1f else -1f)
                        .coerceIn(TerminalPreferences.MIN_FONT_SP, TerminalPreferences.MAX_FONT_SP)
                    if (next != PiTerminalManager.fontSizeSp) {
                        PiTerminalManager.setFontSize(context, next, showToast = true)
                    }
                    scaleAccumulator = 1.0f
                    return 1.0f
                }
                scaleAccumulator = scale
                return scaleAccumulator
            }

            override fun onSingleTapUp(e: MotionEvent) {
                val view = viewHolder.view ?: return
                view.requestFocus()
                showSoftKeyboard(context, view)
            }

            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            override fun shouldEnforceCharBasedInput(): Boolean = true
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = true
            override fun copyModeChanged(copyMode: Boolean) = Unit

            override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
            override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
            override fun onLongPress(event: MotionEvent): Boolean = false

            override fun readControlKey(): Boolean {
                val active = ctrlState.isActive
                if (ctrlState == ModifierState.Armed) ctrlState = ModifierState.Off
                return active
            }

            override fun readAltKey(): Boolean {
                val active = altState.isActive
                if (altState == ModifierState.Armed) altState = ModifierState.Off
                return active
            }

            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false

            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

            override fun onEmulatorSet() {
                PiTerminalManager.applyPalette(palette)
            }

            override fun logError(tag: String?, message: String?) {
                Log.e(tag ?: "PiTerminalView", message ?: "")
            }

            override fun logWarn(tag: String?, message: String?) {
                Log.w(tag ?: "PiTerminalView", message ?: "")
            }

            override fun logInfo(tag: String?, message: String?) {
                Log.i(tag ?: "PiTerminalView", message ?: "")
            }

            override fun logDebug(tag: String?, message: String?) {
                Log.d(tag ?: "PiTerminalView", message ?: "")
            }

            override fun logVerbose(tag: String?, message: String?) = Unit

            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
                Log.e(tag ?: "PiTerminalView", message ?: "", e)
            }

            override fun logStackTrace(tag: String?, e: Exception?) {
                Log.e(tag ?: "PiTerminalView", "view exception", e)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(palette.background),
        ) {
            if (activeTab != null) {
                val textSizePx = remember(fontSizeSp, context) {
                    TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP,
                        fontSizeSp,
                        context.resources.displayMetrics,
                    ).roundToInt().coerceAtLeast(12)
                }

                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        TerminalView(ctx, null).apply {
                            setBackgroundColor(palette.background.toArgb())
                            isFocusable = true
                            isFocusableInTouchMode = true
                            setTerminalViewClient(viewClient)
                            // 必须先 setTextSize 再 setTypeface（Termux TerminalView.setTypeface 依赖 mRenderer 非空）
                            setTextSize(textSizePx)
                            setTypeface(typeface)
                            tag = textSizePx
                            setTerminalCursorBlinkerRate(550)
                            setTerminalCursorBlinkerState(true, true)
                            this.keepScreenOn = keepScreenOn
                            attachSession(activeTab.session)
                            viewHolder.view = this
                            PiTerminalManager.attachView(this, palette)
                            post { requestFocus() }
                        }
                    },
                    update = { view ->
                        viewHolder.view = view
                        view.setBackgroundColor(palette.background.toArgb())
                        view.keepScreenOn = keepScreenOn
                        if (view.currentSession !== activeTab.session) {
                            view.attachSession(activeTab.session)
                        }
                        if (view.tag != textSizePx) {
                            view.setTextSize(textSizePx)
                            view.setTypeface(typeface)
                            view.tag = textSizePx
                        }
                        PiTerminalManager.attachView(view, palette)
                    },
                )
            } else if (spawning) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = palette.accent,
                    )
                    Text(
                        text = tr("正在启动终端…", "Starting terminal…"),
                        color = palette.foreground.copy(alpha = 0.7f),
                        fontFamily = PiMonoFamily,
                        fontSize = 13.sp,
                    )
                }
            } else {
                TerminalEmptyOrErrorState(
                    palette = palette,
                    error = lastError,
                    onLaunch = { PiTerminalManager.createTab(context) },
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // 悬浮轻提示胶囊（OSC 52 复制、缩放字号等反馈）
            androidx.compose.animation.AnimatedVisibility(
                visible = toastMessage != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp),
            ) {
                val msg = toastMessage ?: return@AnimatedVisibility
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.chromeBg.copy(alpha = 0.95f))
                        .border(0.5.dp, palette.border, RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = msg,
                        color = palette.foreground,
                        fontFamily = PiMonoFamily,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        // 进程已退出横幅（用户输 exit 或命令结束后一键重启或关闭标签）
        if (activeTab != null && !activeTab.running) {
            ExitedBanner(
                palette = palette,
                exitCode = activeTab.exitCode,
                onRestart = { PiTerminalManager.restartTab(context, activeTab.id) },
                onClose = { PiTerminalManager.closeTab(activeTab.id) },
            )
        }

        // 底部辅助键条（位于软键盘上方）
        TerminalKeyBar(
            palette = palette,
            ctrlState = ctrlState,
            altState = altState,
            rows = keyBarRows,
            onModifierTap = { mod ->
                when (mod) {
                    StickyModifier.Ctrl -> ctrlState = if (ctrlState == ModifierState.Off) ModifierState.Armed else ModifierState.Off
                    StickyModifier.Alt -> altState = if (altState == ModifierState.Off) ModifierState.Armed else ModifierState.Off
                }
            },
            onModifierDoubleTap = { mod ->
                when (mod) {
                    StickyModifier.Ctrl -> ctrlState = if (ctrlState == ModifierState.Locked) ModifierState.Off else ModifierState.Locked
                    StickyModifier.Alt -> altState = if (altState == ModifierState.Locked) ModifierState.Off else ModifierState.Locked
                }
            },
            onKey = { key ->
                val view = viewHolder.view ?: return@TerminalKeyBar
                val ctrlDown = ctrlState.isActive || (key.termuxKeyMod and KeyHandler.KEYMOD_CTRL) != 0
                val altDown = altState.isActive || (key.termuxKeyMod and KeyHandler.KEYMOD_ALT) != 0
                var mods = key.termuxKeyMod
                if (ctrlDown) mods = mods or KeyHandler.KEYMOD_CTRL
                if (altDown) mods = mods or KeyHandler.KEYMOD_ALT

                val ch = key.character
                if (ch != null) {
                    view.inputCodePoint(ch.code, ctrlDown, altDown)
                } else if (key.androidKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    if (!view.handleKeyCode(key.androidKeyCode, mods)) {
                        view.onKeyDown(key.androidKeyCode, KeyEvent(KeyEvent.ACTION_DOWN, key.androidKeyCode))
                    }
                }

                if (ctrlState == ModifierState.Armed) ctrlState = ModifierState.Off
                if (altState == ModifierState.Armed) altState = ModifierState.Off
            },
            onPaste = { PiTerminalManager.pasteToActive(context) },
        )
    }
}

class TerminalViewRef {
    var view: TerminalView? = null
}

@Composable
private fun ExitedBanner(
    palette: TerminalPalette,
    exitCode: Int?,
    onRestart: () -> Unit,
    onClose: () -> Unit,
) {
    val label = if (exitCode != null) {
        tr("进程已退出 (退出码 $exitCode)", "Process exited (code $exitCode)")
    } else {
        tr("进程已结束", "Process ended")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.chromeBg)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            color = palette.foreground.copy(alpha = 0.72f),
            fontFamily = PiMonoFamily,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        SmallBannerButton(
            label = tr("重启", "Restart"),
            icon = Icons.Filled.Refresh,
            accent = true,
            palette = palette,
            onClick = onRestart,
        )
        SmallBannerButton(
            label = tr("关闭", "Close"),
            icon = Icons.Filled.Close,
            accent = false,
            palette = palette,
            onClick = onClose,
        )
    }
}

@Composable
private fun SmallBannerButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Boolean,
    palette: TerminalPalette,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(7.dp)
    val bg = if (accent) palette.accent.copy(alpha = 0.18f) else palette.chip
    val fg = if (accent) palette.accent else palette.foreground.copy(alpha = 0.85f)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(bg, shape)
            .border(0.5.dp, if (accent) palette.accent.copy(alpha = 0.4f) else palette.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TerminalEmptyOrErrorState(
    palette: TerminalPalette,
    error: String?,
    onLaunch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (error != null) {
                tr("终端启动失败", "Failed to start terminal")
            } else {
                tr("暂无活动终端", "No active terminal")
            },
            color = palette.foreground,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (error != null) {
            Text(
                text = error,
                color = palette.foreground.copy(alpha = 0.68f),
                fontFamily = PiMonoFamily,
                fontSize = 12.sp,
            )
        }
        val shape = RoundedCornerShape(10.dp)
        Row(
            modifier = Modifier
                .clip(shape)
                .background(palette.accent.copy(alpha = 0.18f), shape)
                .border(0.5.dp, palette.accent.copy(alpha = 0.5f), shape)
                .clickable(onClick = onLaunch)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, tint = palette.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = tr("新建终端会话", "New Terminal Session"),
                color = palette.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

internal fun showSoftKeyboard(context: Context, view: TerminalView) {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
    imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
}

internal fun toggleSoftKeyboard(context: Context, view: TerminalView?) {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
    if (view != null) {
        view.requestFocus()
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }
}
