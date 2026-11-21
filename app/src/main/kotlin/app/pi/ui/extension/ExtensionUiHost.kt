package app.pi.ui.extension

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pi.i18n.tr
import app.pi.protocol.Notice
import app.pi.ui.PiSessionViewModel
import app.pi.ui.theme.PiTheme

/**
 * 全应用扩展交互与顶部通知宿主（由 PiRoot 唯一挂载）：
 *  - 顶部悬浮通知条（TopCenter，绝不遮挡输入框、工具栏与软键盘，点击即可立即收起）；
 *  - 阻塞式扩展弹窗（select / confirm / input / editor）；
 *  - 扩展自定义交互表面及最小化悬浮胶囊；
 *  - 终端响铃（BEL / OSC 通知）音频播放。
 */
@Composable
fun ExtensionUiHost(
    session: PiSessionViewModel = viewModel(),
    modifier: Modifier = Modifier.fillMaxSize(),
    snackbarBottomPadding: Dp = 0.dp,
) {
    val state by session.state.collectAsState()
    val hostState = remember { SnackbarHostState() }
    var bellMuted by remember { mutableStateOf(false) }
    var minimizedSurfaceId by remember { mutableStateOf<String?>(null) }
    var promotedFloatingId by remember { mutableStateOf<String?>(null) }

    val floatingCustom = state.ext.nonCapturingCustom
    val activeCustom = state.ext.custom
        ?: floatingCustom?.takeIf { it.surfaceId == promotedFloatingId }
    val activeSurfaceId = activeCustom?.surfaceId
    LaunchedEffect(activeSurfaceId) {
        if (activeSurfaceId == null || activeSurfaceId != minimizedSurfaceId) {
            minimizedSurfaceId = null
        }
    }
    LaunchedEffect(floatingCustom?.surfaceId) {
        if (floatingCustom == null || floatingCustom.surfaceId != promotedFloatingId) {
            promotedFloatingId = null
        }
    }

    LaunchedEffect(state.ext.bellSeq) {
        if (state.ext.bellSeq > 0L && !bellMuted) {
            ExtensionSound.playBell()
        }
    }

    var shownTone by remember { mutableStateOf(Notice.Tone.Info) }
    val head = state.notices.firstOrNull()

    LaunchedEffect(head?.seq) {
        val current = head ?: return@LaunchedEffect
        shownTone = current.tone
        hostState.showSnackbar(
            message = current.message,
            actionLabel = if (current.tone == Notice.Tone.Error) tr("知道了", "Got it") else null,
            duration = if (current.tone == Notice.Tone.Error) {
                SnackbarDuration.Long
            } else {
                SnackbarDuration.Short
            },
        )
        session.consumeNotice(current.seq)
    }

    Box(modifier) {
        // 顶部悬浮通知条：贴在顶栏下方，永远不盖住底部输入框和软键盘；点条本体即可收起。
        SnackbarHost(
            hostState = hostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(
                    start = ToastSideInset,
                    end = ToastSideInset,
                    top = ToastTopOffset,
                ),
        ) { data ->
            val tone = state.notices.firstOrNull { it.message == data.visuals.message }?.tone ?: shownTone
            ExtensionSnack(
                tone = tone,
                message = data.visuals.message,
                actionLabel = data.visuals.actionLabel,
                onAction = { data.performAction() },
                onDismiss = { data.dismiss() },
            )
        }

        ExtensionDialogHost(
            dialog = state.extensionDialog,
            backlog = state.extensionDialogBacklog,
            onAnswer = { id, answer -> session.answerDialog(id, answer) },
        )

        // 非拦截型悬浮 HUD（OverlayOptions.nonCapturing = true）：不遮挡聊天和输入框；点开可升格成全功能面板。
        if (floatingCustom != null && activeCustom?.surfaceId != floatingCustom.surfaceId) {
            ExtensionFloatingOverlayCard(
                custom = floatingCustom,
                bottomPadding = snackbarBottomPadding + MinimizedSurfaceClearance,
                onExpand = { promotedFloatingId = floatingCustom.surfaceId },
                onClose = { id ->
                    if (promotedFloatingId == id) promotedFloatingId = null
                    session.closeSurface(id)
                },
            )
        }

        ExtensionSurfaceHost(
            custom = activeCustom,
            stackCount = state.ext.customStack.count { !it.hidden && !it.nonCapturing }.coerceAtLeast(if (activeCustom != null) 1 else 0),
            minimized = activeSurfaceId != null && minimizedSurfaceId == activeSurfaceId,
            minimizedBottomPadding = snackbarBottomPadding + MinimizedSurfaceClearance,
            soundMuted = bellMuted,
            onToggleSound = { bellMuted = !bellMuted },
            onMinimize = {
                if (activeCustom != null && activeCustom.nonCapturing) {
                    promotedFloatingId = null
                } else {
                    activeSurfaceId?.let { minimizedSurfaceId = it }
                }
            },
            onRestore = { minimizedSurfaceId = null },
            onInput = session::sendSurfaceInput,
            onMouse = session::sendSurfaceMouse,
            onResize = session::resizeSurface,
            onClose = { id ->
                if (minimizedSurfaceId == id) minimizedSurfaceId = null
                if (promotedFloatingId == id) promotedFloatingId = null
                session.closeSurface(id)
            },
        )
    }
}

@Composable
private fun ExtensionSnack(
    tone: Notice.Tone,
    message: String,
    actionLabel: String?,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = PiTheme.palette
    val container = when (tone) {
        Notice.Tone.Info -> MaterialTheme.colorScheme.surfaceContainerHigh
        Notice.Tone.Warning -> palette.infoBg
        Notice.Tone.Error -> palette.toolErrorBg
    }
    val accent = when (tone) {
        Notice.Tone.Info -> palette.accent
        Notice.Tone.Warning -> palette.warning
        Notice.Tone.Error -> palette.error
    }
    val glyph = when (tone) {
        Notice.Tone.Info -> "i"
        Notice.Tone.Warning -> "!"
        Notice.Tone.Error -> "✗"
    }
    val shape = RoundedCornerShape(ToastRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 6.dp, shape = shape, clip = false)
            .clip(shape)
            .background(container)
            .border(1.dp, accent.copy(alpha = 0.32f), shape)
            .clickable(onClickLabel = tr("关闭提示", "Dismiss notice"), onClick = onDismiss)
            .padding(horizontal = ToastPaddingHorizontal, vertical = ToastPaddingVertical),
        horizontalArrangement = Arrangement.spacedBy(ToastGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = glyph,
                style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.Bold),
                color = accent,
            )
        }
        ExtensionSpans(
            spans = remember(message) { chromeSpans(message) },
            defaultColor = palette.text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            richStyle = true,
        )
        if (actionLabel != null) {
            Text(
                text = actionLabel,
                modifier = Modifier
                    .clip(RoundedCornerShape(ToastActionRadius))
                    .background(accent.copy(alpha = 0.16f))
                    .clickable(role = Role.Button, onClick = onAction)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = accent,
            )
        }
    }
}

private val ToastSideInset = 14.dp
private val ToastTopOffset = 60.dp
private val MinimizedSurfaceClearance = 96.dp
private val ToastRadius = 14.dp
private val ToastPaddingHorizontal = 12.dp
private val ToastPaddingVertical = 10.dp
private val ToastGap = 10.dp
private val ToastActionRadius = 8.dp
