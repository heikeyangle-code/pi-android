package app.pi.ui.render.rich

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.ui.render.PiCardDivider
import app.pi.ui.render.PiRichCardFrame
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
    // 解析放后台并缓存：长堆栈第一次出现不卡主线程，滚回来不重解析。
    val result = rememberTreeParse("stack", source, settled) { PiStackTrace.parse(source, settled) }
    val parsed = result as? StackTraceResult.Parsed
    if (parsed != null) {
        PiStackTraceCard(trace = parsed.trace, source = source, modifier = modifier)
    } else {
        // 回退也套同一张卡（语言胶囊 + 复制）：裸文字和有边框的卡片混在一页里很突兀。
        val info = remember { FenceInfo.parse("stacktrace") }
        PiRichCardFrame(source, info, PiTheme.palette) {
            PiTreeRawSource(
                source = source,
                notice = if (result == null) "解析中…" else stackTraceFailureNotice(result),
            )
        }
    }
}

/**
 * The card for an already-parsed trace.
 *
 * Public so a caller that parsed the trace itself can draw just the card. [source] is only
 * used by `复制`; it is never re-parsed here.
 *
 * 外观与代码块 / 树 / CSV 同一套卡片语言（12 dp 圆角、淡底 + 28% 边线、发丝分隔线、
 * 左上胶囊 + 右上「复制」）。帧分两行：函数名（可换行，长类名不再被省略号吃掉方法名）和
 * `file:line`；标准库 / 框架帧调淡，自家代码帧保持正色，一眼找到是哪一行自己的代码出的事。
 * 一开始只排 [PI_STACKTRACE_FIRST_FRAMES] 帧，「显示更多」每次再加一页。
 */
@Composable
fun PiStackTraceCard(
    trace: ParsedStackTrace,
    source: String,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(12.dp)
    val clipboard = LocalClipboardManager.current
    var copied by remember(source) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    // 读者的展开状态：滚出屏幕再回来必须还在，且不依赖会流式变化的值之外的东西。
    var shownFrames by rememberSaveable(source) { mutableStateOf(PI_STACKTRACE_FIRST_FRAMES) }
    var messageExpanded by rememberSaveable(source) { mutableStateOf(false) }
    var messageOverflows by remember(source) { mutableStateOf(false) }

    val cap = minOf(trace.frames.size, PI_STACKTRACE_MAX_VISIBLE_FRAMES)
    val visibleFrames = trace.frames.take(minOf(cap, shownFrames))
    val remaining = cap - visibleFrames.size
    val beyondCap = trace.frames.size - cap

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.mdCodeBlockBorder.copy(alpha = 0.06f))
            .border(1.dp, palette.mdCodeBlockBorder.copy(alpha = 0.28f), shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = trace.kind.displayName,
                style = PiTheme.text.monoSmall,
                color = palette.accent,
                maxLines = 1,
                modifier = Modifier
                    .background(palette.accent.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (copied) "已复制" else "复制",
                style = PiTheme.text.monoSmall,
                color = if (copied) palette.accent else palette.muted,
                maxLines = 1,
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clickable(onClickLabel = "复制原始堆栈") {
                        clipboard.setText(AnnotatedString(source))
                        copied = true
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        PiCardDivider(palette)

        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
            Text(
                text = stackTraceTitle(trace),
                style = PiTheme.typography.styles.prose.copy(fontWeight = FontWeight.SemiBold),
                color = palette.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val message = trace.message
            if (!message.isNullOrEmpty()) {
                Text(
                    text = message,
                    style = PiTheme.typography.styles.prose,
                    color = palette.text,
                    maxLines = if (messageExpanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!messageExpanded) messageOverflows = it.hasVisualOverflow },
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .then(
                            if (messageOverflows || messageExpanded) {
                                Modifier.clickable(onClickLabel = if (messageExpanded) "收起消息" else "展开消息") {
                                    messageExpanded = !messageExpanded
                                }
                            } else {
                                Modifier
                            },
                        ),
                )
                if (messageOverflows || messageExpanded) {
                    Text(
                        text = if (messageExpanded) "收起 ▴" else "展开全部 ▾",
                        style = PiTheme.text.monoSmall,
                        color = palette.muted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        if (visibleFrames.isNotEmpty()) {
            PiCardDivider(palette)
            Column(Modifier.padding(vertical = 6.dp)) {
                visibleFrames.forEachIndexed { index, frame ->
                    StackFrameRow(index = index, frame = frame)
                }
            }
            if (remaining > 0) {
                PiCardDivider(palette)
                PiTreeMoreRow(depth = 0, remaining = remaining) { shownFrames += PI_STACKTRACE_PAGE }
            }
            if (beyondCap > 0 || trace.framesTruncated) {
                Text(
                    text = if (trace.framesTruncated) {
                        "堆栈超过 ${PiStackTrace.DEFAULT_LIMITS.maxFrames} 帧，只解析了前 ${trace.frames.size} 帧；原文仍可复制"
                    } else {
                        "… 还有 $beyondCap 帧未显示（共 ${trace.frames.size} 帧）；复制可拿到原文"
                    },
                    style = PiTheme.text.monoSmall,
                    color = palette.muted,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StackFrameRow(index: Int, frame: StackFrame) {
    val palette = PiTheme.palette
    val body = PiTheme.typography.styles.code
    val library = remember(frame) { isLibraryFrame(frame) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "$index",
            style = PiTheme.text.monoSmall,
            color = palette.dim,
            maxLines = 1,
            modifier = Modifier.width(28.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = frame.function ?: frame.raw.trim().ifEmpty { "—" },
                // 函数名允许换行：`com.example.很长的包名.Class.method` 被省略号截掉的恰恰是 method。
                style = body.copy(color = if (library) palette.muted else palette.text),
            )
            val file = frame.file
            if (file != null) {
                val line = frame.line
                Text(
                    text = if (line != null) "$file:$line" else file,
                    style = PiTheme.text.monoSmall,
                    color = if (library) palette.dim else palette.muted,
                )
            }
        }
    }
}

private val LIBRARY_FUNCTION_PREFIXES = listOf(
    "java.", "javax.", "jdk.", "sun.", "kotlin.", "kotlinx.", "android.", "androidx.", "com.android.",
    "dalvik.", "libcore.", "org.junit.", "org.jetbrains.", "internal/", "node:", "runtime.", "std::",
    "core::", "alloc::", "tokio::", "<frozen",
)
private val LIBRARY_PATH_PARTS = listOf(
    "node_modules", "site-packages", "dist-packages", "/usr/lib", "/rustc/", "/usr/local/go/",
    "node:", "<frozen", "/.cargo/registry",
)

/** 标准库 / 框架 / 第三方依赖帧：只靠函数名前缀与文件路径判断，不猜。拿不准就当自家代码（保持正色）。 */
private fun isLibraryFrame(frame: StackFrame): Boolean {
    val function = frame.function.orEmpty()
    val file = frame.file.orEmpty()
    return LIBRARY_FUNCTION_PREFIXES.any { function.startsWith(it) } ||
        LIBRARY_PATH_PARTS.any { file.contains(it) || function.contains(it) }
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

/** 一开始排版的帧数；「显示更多」每次再加 [PI_STACKTRACE_PAGE]。 */
private const val PI_STACKTRACE_FIRST_FRAMES = 12
private const val PI_STACKTRACE_PAGE = 30

/** Frames drawn at most, before the stated "还有 N 帧" row; `复制` still has the whole source. */
private const val PI_STACKTRACE_MAX_VISIBLE_FRAMES = 60
