package app.pi.ui.chat

/**
 * Transcript "follow the newest row" state machine.
 *
 * Rules (matching Pi's ScrollView `follow: "end"` semantics):
 *  1. A rebuilt transcript (fewer rows than before) re-arms follow.
 *  2. A user drag/flick backwards pauses follow immediately; dragging back to the bottom re-arms it.
 *  3. Reaching the bottom (`atBottom`) re-arms unless paused explicitly by programmatic navigation (`pausedByNavigation`).
 *  4. Content growth, async markdown height settle, keyboard resize, or extension dock resize NEVER pause follow —
 *     they always issue a fresh pin to the true bottom.
 */
internal class TailFollow(initiallyFollowing: Boolean = true) {

    var following: Boolean = initiallyFollowing
        private set

    var unseenRows: Int = 0
        private set

    private var pausedByNavigation = false
    private var previousRows: Int = -1
    private var previousAnchor: TailAnchor? = null
    private var previousScrolling: Boolean = false

    /**
     * Tracks the last pin together with the geometry it was issued against.
     * Comparing the pin + geometry signature prevents infinite per-frame remeasure loops
     * while GUARANTEEING that a growing tall tail item or shrinking viewport (IME / dock)
     * always re-pins to the new bottom instead of being swallowed as a "repeated" PIN_TO_END_PX.
     */
    private var lastPinKey: PinGuardKey? = null

    fun reArm() {
        following = true
        unseenRows = 0
        pausedByNavigation = false
        lastPinKey = null
    }

    fun pause() {
        following = false
        unseenRows = 0
        pausedByNavigation = true
        lastPinKey = null
    }

    fun onSnapshot(snapshot: TailSnapshot): TailDecision {
        val viewport = snapshot.viewport
        val rows = snapshot.transcriptRows

        if (previousRows >= 0 && rows < previousRows) {
            reArm()
        }

        val anchor = viewport.anchor
        val previous = previousAnchor
        val movedBackwards = previous != null && anchor.isBefore(previous)
        val gesture = viewport.isScrollInProgress && anchor != previous
        val releasedBackwards = previousScrolling && !viewport.isScrollInProgress && movedBackwards

        if (viewport.atBottom) {
            if (!pausedByNavigation || gesture) {
                following = true
                pausedByNavigation = false
            }
        } else if (releasedBackwards) {
            following = false
            pausedByNavigation = false
        } else if (gesture) {
            pausedByNavigation = false
            if (movedBackwards) following = false
        }

        unseenRows = when {
            following -> 0
            previousRows < 0 -> unseenRows
            else -> unseenRows + (rows - previousRows).coerceAtLeast(0)
        }

        val pin = when {
            !following -> null
            viewport.isScrollInProgress -> null
            viewport.totalItems <= 0 -> null
            viewport.atBottom -> null
            else -> viewport.pinToTail()
        }

        val pinKey = pin?.let {
            PinGuardKey(
                totalItems = viewport.totalItems,
                lastVisibleIndex = viewport.lastVisibleIndex,
                lastVisibleOffsetPx = viewport.lastVisibleOffsetPx,
                lastVisibleSizePx = viewport.lastVisibleSizePx,
                viewportEndOffsetPx = viewport.viewportEndOffsetPx,
                bottomContentPaddingPx = viewport.bottomContentPaddingPx,
                poke = snapshot.poke,
            )
        }
        val repeated = pinKey != null && pinKey == lastPinKey
        val issued = if (repeated) null else pin
        lastPinKey = pinKey

        previousRows = rows
        previousAnchor = anchor
        previousScrolling = viewport.isScrollInProgress
        return TailDecision(following = following, unseenRows = unseenRows, pin = issued)
    }

    fun savedState(): List<Int> = listOf(if (following) 1 else 0, unseenRows, previousRows)

    private data class PinGuardKey(
        val totalItems: Int,
        val lastVisibleIndex: Int,
        val lastVisibleOffsetPx: Int,
        val lastVisibleSizePx: Int,
        val viewportEndOffsetPx: Int,
        val bottomContentPaddingPx: Int,
        val poke: Long,
    )

    companion object {
        fun fromSavedState(saved: List<Int>): TailFollow? {
            val following = saved.getOrNull(0) ?: return null
            val unseen = saved.getOrNull(1) ?: return null
            return TailFollow(initiallyFollowing = following != 0).also {
                if (following == 0) it.unseenRows = unseen.coerceAtLeast(0)
                it.previousRows = saved.getOrNull(2) ?: -1
            }
        }
    }
}

internal data class TailAnchor(val itemIndex: Int, val itemOffsetPx: Int) {
    fun isBefore(other: TailAnchor): Boolean =
        itemIndex < other.itemIndex || (itemIndex == other.itemIndex && itemOffsetPx < other.itemOffsetPx)
}

internal data class TailViewport(
    val totalItems: Int,
    val firstVisibleIndex: Int,
    val firstVisibleOffsetPx: Int,
    val lastVisibleIndex: Int,
    val lastVisibleOffsetPx: Int,
    val lastVisibleSizePx: Int,
    val viewportEndOffsetPx: Int,
    val bottomContentPaddingPx: Int,
    val isScrollInProgress: Boolean,
    val atBottom: Boolean,
) {
    val anchor: TailAnchor get() = TailAnchor(firstVisibleIndex, firstVisibleOffsetPx)
    val tailIndex: Int get() = totalItems - 1

    fun pinToTail(): TailPin? {
        if (totalItems <= 0) return null
        val tail = tailIndex
        if (lastVisibleIndex < tail) return TailPin(index = tail, offsetPx = PIN_TO_END_PX)
        if (lastVisibleIndex > tail) return null
        val contentEndPx = viewportEndOffsetPx - bottomContentPaddingPx
        val hidden = lastVisibleOffsetPx + lastVisibleSizePx - contentEndPx
        if (hidden <= 0) return null
        // When the tail item itself is taller than the viewport or near the end,
        // requesting its exact end ensures we never stall a few pixels short.
        return TailPin(index = firstVisibleIndex, offsetPx = firstVisibleOffsetPx + hidden)
    }
}

internal data class TailPin(val index: Int, val offsetPx: Int)

internal const val PIN_TO_END_PX = 1 shl 24

internal data class TailSnapshot(
    val transcriptRows: Int,
    val viewport: TailViewport,
    val poke: Long = 0L,
)

internal data class TailDecision(
    val following: Boolean,
    val unseenRows: Int,
    val pin: TailPin?,
)

internal fun mayLoadEarlier(
    atWindowTop: Boolean,
    armed: Boolean,
    hiddenRows: Int,
    isScrollInProgress: Boolean,
): Boolean = atWindowTop && armed && hiddenRows > 0 && !isScrollInProgress

internal fun reArmsEarlier(atWindowTop: Boolean, canScrollForward: Boolean): Boolean =
    !atWindowTop || !canScrollForward

internal fun mayArmEarlier(
    atWindowTop: Boolean,
    canScrollForward: Boolean,
    hasLaidOut: Boolean,
): Boolean = hasLaidOut && reArmsEarlier(atWindowTop, canScrollForward)

internal fun hiddenRows(visibleRows: Int, renderWindow: Int, windowOpen: Boolean): Int =
    if (windowOpen) 0 else (visibleRows - minOf(renderWindow, visibleRows)).coerceAtLeast(0)

internal fun itemIndexOfVisibleRow(
    visibleRow: Int,
    visibleRows: Int,
    renderWindow: Int,
    windowOpen: Boolean,
    headerRows: Int,
): Int = visibleRow - hiddenRows(visibleRows, renderWindow, windowOpen) + headerRows

internal fun visibleRowOfItemIndex(
    itemIndex: Int,
    visibleRows: Int,
    renderWindow: Int,
    windowOpen: Boolean,
    headerRows: Int,
): Int = itemIndex + hiddenRows(visibleRows, renderWindow, windowOpen) - headerRows

internal fun earlierRowHeightPx(iconPx: Int, textHeightPx: Int, verticalPaddingPx: Int): Int =
    maxOf(iconPx, textHeightPx) + verticalPaddingPx

internal fun freshRowKeysAfter(
    previous: Collection<String>,
    added: Collection<String>,
    max: Int,
): Set<String> {
    require(max > 0) { "max must be positive, was $max" }
    if (added.isEmpty()) return LinkedHashSet(previous)
    val next = LinkedHashSet<String>(previous)
    next += added
    while (next.size > max) next.remove(next.first())
    return next
}
