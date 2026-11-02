package app.pi.ui.render.rich

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.delay

/**
 * The Compose **stack-trace card** (`§十.1` of
 * `docs/plans/rich-rendering-upgrade.md`).
 *
 * The recognizer, the five dialects and the "file/line only when the trace spells
 * it out" rule live in `PiStackTrace.kt`; this file only draws
 * [ParsedStackTrace]. The split is the same one `PiJsonTreeView.kt` uses: Compose
 * cannot be compiled by the bare-JVM harnesses, so the testable half stays pure.
 *
 * ## What is shown
 *
 * - the dialect (`Java / Kotlin`, `Python`, …) as a small badge;
 * - the exception type and its message, verbatim; Rust and Go panics have no type
 *   in the source, so the card says `panic` (the marker's own word) rather than
 *   inventing one;
 * - up to [PI_STACKTRACE_MAX_VISIBLE_FRAMES] numbered frames, each with its
 *   function and — **only when the model has one** — `file:line`. No frame is
 *   clickable: mapping a basename to a workspace file needs a verified workspace
 *   lookup the plan reserves for a later phase, and guessing it here is exactly
 *   what `§十.1` forbids.
 * - `复制` copies the **original trace text**, not the card's rendering.
 *
 * The card is drawn without any image, WebView or syntax highlighter: it is a
 * few Texts in a bordered column, matching the transcript's existing cards.
 *
 * ## Fallback
 *
 * [PiStackTraceView] parses first. Anything that is not a recognized trace —
 * ordinary prose, a streaming block, an oversized body — renders the **original
 * source** (with a one-line reason for the size/streaming cases), so a message
 * that merely contains the word "traceback" cannot lose its text to an empty
 * card.
 */
@Composable
fun PiStackTraceView(
    source: String,
    settled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val result = remember(source, settled) { PiStackTrace.parse(source, settled) }
    val parsed = result as? StackTraceResult.Parsed
    if (parsed != null) {
        PiStackTraceCard(trace = parsed.trace, source = source, modifier = modifier)
    } else {
        PiTreeRawSource(source = source, notice = stackTraceFailureNotice(result))
    }
}

/**
 * The card for an already-parsed trace.
 *
 * Public so a caller that parsed the trace itself (e.g. a tool-output block that
 * wants the card without the fallback) can draw just the card. [source] is only
 * used by `复制`; it is never re-parsed here.
 */
@Composable
fun PiStackTraceCard(
    trace: ParsedStackTrace,
    source: String,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(8.dp)
    val clipboard = LocalClipboardManager.current
    var copied by remember(source) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    val visibleFrames = trace.frames.take(PI_STACKTRACE_MAX_VISIBLE_FRAMES)
    val hiddenFrames = trace.frames.size - visibleFrames.size

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, palette.borderMuted.copy(alpha = 0.55f), shape)
            .background(palette.cardBg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = trace.kind.displayName,
                style = PiTheme.text.monoSmall,
                color = palette.accent,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            ) {
                Text(
                    text = stackTraceTitle(trace),
                    style = PiTheme.typography.styles.prose.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val message = trace.message
                if (!message.isNullOrEmpty()) {
                    Text(
                        text = message,
                        style = PiTheme.typography.styles.prose,
                        color = palette.text,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Text(
                text = if (copied) "已复制" else "复制",
                style = PiTheme.text.monoSmall,
                color = if (copied) palette.accent else palette.muted,
                maxLines = 1,
                modifier = Modifier
                    .clickable(onClickLabel = "复制原始堆栈") {
                        clipboard.setText(AnnotatedString(source))
                        copied = true
                    }
                    .padding(start = 8.dp, top = 1.dp, bottom = 2.dp),
            )
        }

        if (visibleFrames.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            visibleFrames.forEachIndexed { index, frame ->
                StackFrameRow(index = index, frame = frame)
            }
            if (hiddenFrames > 0) {
                Text(
                    text = "… 还有 $hiddenFrames 帧（共 ${trace.frames.size} 帧）",
                    style = PiTheme.text.monoSmall,
                    color = palette.muted,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp),
                )
            }
            if (trace.framesTruncated) {
                Text(
                    text = "堆栈超过 ${PiStackTrace.DEFAULT_LIMITS.maxFrames} 帧，只解析了前 ${trace.frames.size} 帧；原文仍可复制",
                    style = PiTheme.text.monoSmall,
                    color = palette.muted,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StackFrameRow(index: Int, frame: StackFrame) {
    val palette = PiTheme.palette
    val body = PiTheme.typography.styles.code
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "$index",
            style = PiTheme.text.monoSmall,
            color = palette.dim,
            maxLines = 1,
            modifier = Modifier.width(26.dp),
        )
        Text(
            text = frame.function ?: "—",
            style = body.copy(color = palette.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val file = frame.file
        if (file != null) {
            val line = frame.line
            Text(
                text = if (line != null) "$file:$line" else file,
                style = PiTheme.text.monoSmall,
                color = palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** Type if the trace has one; Rust/Go panics have no type in the source, only the marker. */
private fun stackTraceTitle(trace: ParsedStackTrace): String = trace.exceptionType ?: when (trace.kind) {
    StackTraceKind.Rust, StackTraceKind.Go -> "panic"
    else -> trace.kind.displayName
}

/** A one-line reason for the cases that keep the source; `null` for "not a trace". */
private fun stackTraceFailureNotice(result: StackTraceResult): String? = when (result) {
    is StackTraceResult.Parsed -> null
    is StackTraceResult.Failure -> when (result.reason) {
        StackTraceFailure.NotSettled -> "堆栈还在生成，先显示原文"
        StackTraceFailure.Empty -> null
        StackTraceFailure.TooLarge -> "堆栈超过 ${PiStackTrace.DEFAULT_LIMITS.maxChars / 1024} KiB，已回退原文"
        StackTraceFailure.NotRecognized -> null
    }
}

/** Frames drawn before the stated "还有 N 帧" row; `复制` still has the whole source. */
private const val PI_STACKTRACE_MAX_VISIBLE_FRAMES = 60
