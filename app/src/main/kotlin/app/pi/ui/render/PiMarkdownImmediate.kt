package app.pi.ui.render

import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the markdown in this row must be parsed **before its first layout** — the
 * library's `Markdown(…, immediate = true)`, which calls
 * `MarkdownStateImpl.parseBlocking()` right after `rememberMarkdownState` builds its state
 * (renderer 0.45.0, `model/MarkdownState.kt`: the branch is `if (immediate)
 * state.parseBlocking()` at the `MarkdownStateImpl(input)` construction, so the state is
 * already `State.Success` when the first frame is drawn).
 *
 * ## The defect this exists for
 *
 * `rememberMarkdownState` builds its state with a plain `remember`, and a fresh state is
 * `State.Loading`, whose slot is an **empty `Box`** — zero height. A row therefore gets its
 * real height one or more frames *after* its first composition, and where that happens
 * decides whether anyone sees it:
 *
 *  - a row entering the viewport from **below** (scrolling towards the newest message) grows
 *    under the fold: the rows it pushes are off-screen and nothing the reader is looking at
 *    moves — this is why 「上滑看下面的消息」 has never been reported as a problem;
 *  - a row entering from **above** (scrolling towards older messages, which is what
 *    「加载更早」 hands you) is the *page break*: its growth pushes every visible row down by
 *    its own height. That is `docs/scroll-diagnosis.md` §1.2 P3 and one half of
 *    「有时候会跳、会刷会蹦」.
 *
 * `RowHeightCache`/`rememberedRowHeight` answer the same defect for a row that has been
 * measured *before* (a destination switch, a scroll back into known content). They cannot
 * answer it for a row that has never been measured at all — a row a batch has just brought
 * in — because there is no height to restore. The only way to get that height is to have the
 * content ready on the first frame, which is what this local asks for.
 *
 * ## The bound, which is what keeps it from being "parse everything on the frame thread"
 *
 * `true` is provided for **one row at a time, at most once per row, never for a streaming
 * row, and only while the frame has parse budget left** (`ChatScreen`'s item lambda):
 *
 *  - the rows the last batch brought into the window, until each has been measured once
 *    (`RowHeightCache` is the latch: a row with a remembered height never asks again);
 *  - the rows visible on the frame a composition is restored (a destination switch), so the
 *    reader does not get a column of correctly-sized *blank* rows for a frame;
 *  - never a row whose text is still streaming: its content changes on every token, and a
 *    synchronous parse per token is the one thing this must not become;
 *  - and never more per frame than [MarkdownParseBudget] allows. The budget is billed with
 *    the **measured** cost of each eager row (the timing wraps the `Markdown(state = …)`
 *    call in `PiMarkdown.kt`), so a frame that has already spent its 4 ms hands the rest of
 *    its new rows to the asynchronous path. One frame can therefore overshoot by at most the
 *    single row that crosses the line (5.27 ms for a 20 KB card, measured), and the debt is
 *    not carried into the next frame.
 *
 * So the cost is one parse per row, on the frame that row first appears, bounded by the batch
 * size (`TRANSCRIPT_WINDOW_STEP`) **and** by the frame's budget. It is *not* cheap for a very
 * large row (a 20 KB answer is a parse measured in milliseconds), which is why the gate is a
 * set of keys plus a measured-time budget, and not `!streaming` alone.
 *
 * A `compositionLocalOf` rather than a `static` one: it differs per row, and a static local
 * would invalidate every reader in the subtree whenever the gate changes.
 */
internal val LocalPiMarkdownImmediate = compositionLocalOf { false }

/**
 * The row's "this markdown has finished parsing" callback: provided by the row, invoked by
 * [PiMarkdownText] when the library's state reaches `State.Success`.
 *
 * It exists so that the height floor a re-composed row is held at (`rememberedRowHeight`)
 * can be released by the **parse itself** rather than by a proxy for it. Before this, the
 * only signals `TranscriptRowHeight` had were "the content measured a non-zero height" (one
 * recomposition later) and "N frames have passed" (a race with `Dispatchers.Default`) — so a
 * row whose parse lost the race could have its floor dropped while its content was still
 * empty, collapse, and grow back: two geometry changes where the floor was meant to remove
 * one. With this signal the floor is dropped on the frame the parse lands, whoever won the
 * race.
 *
 * A `compositionLocalOf` for the same reason as [LocalPiMarkdownImmediate]: it is one lambda
 * per row, and a static local would invalidate every markdown reader in the subtree whenever
 * one row's provider changed. It is `null` for a row with no markdown at all (which needs no
 * signal: such a row measures its natural height on its first frame).
 */
internal val LocalPiMarkdownParsed = compositionLocalOf<(() -> Unit)?> { null }
