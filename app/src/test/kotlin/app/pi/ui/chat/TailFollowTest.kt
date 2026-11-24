package app.pi.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailFollowTest {

    @Test
    fun `pins strictly to top of floating composer, never screen bottom`() {
        val follow = TailFollow(initiallyFollowing = true)
        // Viewport height = 2000px (viewportEndOffsetPx = 1980px), floating composer + 12dp clearance = 400px.
        // Resting bottom of last item is contentEndPx = 1980 - 400 = 1580px (above the floating composer).
        // Last item currently extends 50px behind the composer (offset 1230 + size 400 = 1630 > 1580).
        val decision = follow.onSnapshot(
            TailSnapshot(
                transcriptRows = 10,
                viewport = TailViewport(
                    totalItems = 10,
                    firstVisibleIndex = 5,
                    firstVisibleOffsetPx = 100,
                    lastVisibleIndex = 9,
                    lastVisibleOffsetPx = 1230,
                    lastVisibleSizePx = 400,
                    viewportEndOffsetPx = 1980,
                    bottomContentPaddingPx = 400,
                    isScrollInProgress = false,
                    atBottom = false,
                ),
            ),
        )
        assertTrue(decision.following)
        assertEquals(TailPin(index = 5, offsetPx = 150), decision.pin)
    }

    @Test
    fun `re-pins immediately when floating composer grows taller`() {
        val follow = TailFollow(initiallyFollowing = true)
        // Initially settled at bottom above 400px composer.
        val settled = follow.onSnapshot(
            TailSnapshot(
                transcriptRows = 10,
                viewport = TailViewport(
                    totalItems = 10,
                    firstVisibleIndex = 5,
                    firstVisibleOffsetPx = 150,
                    lastVisibleIndex = 9,
                    lastVisibleOffsetPx = 1180,
                    lastVisibleSizePx = 400,
                    viewportEndOffsetPx = 1980,
                    bottomContentPaddingPx = 400,
                    isScrollInProgress = false,
                    atBottom = true,
                ),
            ),
        )
        assertNull(settled.pin)

        // Composer grows by 80px (1 -> 3 lines or attachment bar added): bottomContentPaddingPx becomes 480.
        // Now contentEndPx = 1980 - 480 = 1500; last item ends at 1580 -> 80px hidden behind composer.
        val afterGrowth = follow.onSnapshot(
            TailSnapshot(
                transcriptRows = 10,
                viewport = TailViewport(
                    totalItems = 10,
                    firstVisibleIndex = 5,
                    firstVisibleOffsetPx = 150,
                    lastVisibleIndex = 9,
                    lastVisibleOffsetPx = 1180,
                    lastVisibleSizePx = 400,
                    viewportEndOffsetPx = 1980,
                    bottomContentPaddingPx = 480,
                    isScrollInProgress = false,
                    atBottom = false,
                ),
            ),
        )
        assertTrue(afterGrowth.following)
        assertEquals(TailPin(index = 5, offsetPx = 230), afterGrowth.pin)
    }

    @Test
    fun `re-pins when tail is pushed off viewport bottom even with same totalItems and viewport`() {
        val follow = TailFollow(initiallyFollowing = true)
        val first = follow.onSnapshot(
            TailSnapshot(
                transcriptRows = 10,
                viewport = TailViewport(
                    totalItems = 10,
                    firstVisibleIndex = 4,
                    firstVisibleOffsetPx = 0,
                    lastVisibleIndex = 8,
                    lastVisibleOffsetPx = 1200,
                    lastVisibleSizePx = 400,
                    viewportEndOffsetPx = 1980,
                    bottomContentPaddingPx = 400,
                    isScrollInProgress = false,
                    atBottom = false,
                ),
            ),
        )
        assertEquals(TailPin(index = 9, offsetPx = PIN_TO_END_PX), first.pin)

        // Suppose an earlier row shifts layout so item 8 is now at offset 1260 and tail (9) is still off-screen.
        val second = follow.onSnapshot(
            TailSnapshot(
                transcriptRows = 10,
                viewport = TailViewport(
                    totalItems = 10,
                    firstVisibleIndex = 4,
                    firstVisibleOffsetPx = 0,
                    lastVisibleIndex = 8,
                    lastVisibleOffsetPx = 1260,
                    lastVisibleSizePx = 400,
                    viewportEndOffsetPx = 1980,
                    bottomContentPaddingPx = 400,
                    isScrollInProgress = false,
                    atBottom = false,
                ),
            ),
        )
        assertNotNull(second.pin)
        assertEquals(TailPin(index = 9, offsetPx = PIN_TO_END_PX), second.pin)
    }

    @Test
    fun `user dragging up on a single tall streaming message pauses follow immediately`() {
        val follow = TailFollow(initiallyFollowing = true)
        // Tall single message (3000px) pinned at bottom.
        follow.onSnapshot(
            TailSnapshot(
                transcriptRows = 1,
                viewport = TailViewport(
                    totalItems = 1,
                    firstVisibleIndex = 0,
                    firstVisibleOffsetPx = 1420,
                    lastVisibleIndex = 0,
                    lastVisibleOffsetPx = -1420,
                    lastVisibleSizePx = 3000,
                    viewportEndOffsetPx = 1980,
                    bottomContentPaddingPx = 400,
                    isScrollInProgress = false,
                    atBottom = true,
                ),
            ),
        )
        // User drags up 60px to read earlier part of the same streaming message.
        val duringDrag = follow.onSnapshot(
            TailSnapshot(
                transcriptRows = 1,
                viewport = TailViewport(
                    totalItems = 1,
                    firstVisibleIndex = 0,
                    firstVisibleOffsetPx = 1360,
                    lastVisibleIndex = 0,
                    lastVisibleOffsetPx = -1360,
                    lastVisibleSizePx = 3050,
                    viewportEndOffsetPx = 1980,
                    bottomContentPaddingPx = 400,
                    isScrollInProgress = true,
                    atBottom = false,
                ),
            ),
        )
        assertFalse(duringDrag.following)
        assertNull(duringDrag.pin)

        // Finger released while streaming keeps growing: must stay paused and never snap back!
        val afterRelease = follow.onSnapshot(
            TailSnapshot(
                transcriptRows = 1,
                viewport = TailViewport(
                    totalItems = 1,
                    firstVisibleIndex = 0,
                    firstVisibleOffsetPx = 1360,
                    lastVisibleIndex = 0,
                    lastVisibleOffsetPx = -1360,
                    lastVisibleSizePx = 3200,
                    viewportEndOffsetPx = 1980,
                    bottomContentPaddingPx = 400,
                    isScrollInProgress = false,
                    atBottom = false,
                ),
            ),
        )
        assertFalse(afterRelease.following)
        assertNull(afterRelease.pin)
    }

    @Test
    fun `never re-pins in a tight loop when scrollPosition updates before layoutInfo remeasures`() {
        val follow = TailFollow(initiallyFollowing = true)
        val first = follow.onSnapshot(
            TailSnapshot(
                transcriptRows = 10,
                viewport = TailViewport(
                    totalItems = 10,
                    firstVisibleIndex = 5,
                    firstVisibleOffsetPx = 100,
                    lastVisibleIndex = 9,
                    lastVisibleOffsetPx = 1230,
                    lastVisibleSizePx = 400,
                    viewportEndOffsetPx = 1980,
                    bottomContentPaddingPx = 400,
                    isScrollInProgress = false,
                    atBottom = false,
                ),
            ),
        )
        assertEquals(TailPin(index = 5, offsetPx = 150), first.pin)

        // Simulate requestScrollToItem immediately mutating firstVisibleOffsetPx to 150
        // BEFORE Choreographer runs the measure pass (layoutInfo is still stale!).
        val beforeMeasure = follow.onSnapshot(
            TailSnapshot(
                transcriptRows = 10,
                viewport = TailViewport(
                    totalItems = 10,
                    firstVisibleIndex = 5,
                    firstVisibleOffsetPx = 150,
                    lastVisibleIndex = 9,
                    lastVisibleOffsetPx = 1230,
                    lastVisibleSizePx = 400,
                    viewportEndOffsetPx = 1980,
                    bottomContentPaddingPx = 400,
                    isScrollInProgress = false,
                    atBottom = false,
                ),
            ),
        )
        assertTrue(beforeMeasure.following)
        assertNull(beforeMeasure.pin)
    }

    @Test
    fun `expanding and collapsing cards without touch-scrolling keeps tail-follow armed`() {
        val follow = TailFollow(initiallyFollowing = true)
        // Settled at bottom with 10 items.
        val settled = follow.onSnapshot(
            TailSnapshot(
                transcriptRows = 10,
                viewport = TailViewport(
                    totalItems = 10,
                    firstVisibleIndex = 6,
                    firstVisibleOffsetPx = 120,
                    lastVisibleIndex = 9,
                    lastVisibleOffsetPx = 1180,
                    lastVisibleSizePx = 400,
                    viewportEndOffsetPx = 1980,
                    bottomContentPaddingPx = 400,
                    isScrollInProgress = false,
                    atBottom = true,
                ),
            ),
        )
        assertTrue(settled.following)
        assertNull(settled.pin)

        // User taps an earlier tool/thinking card (item 7) to expand it (+350px) without dragging.
        // Tail item 9 shifts down by 350px, pushing it below contentEndPx (1580).
        val afterExpand = follow.onSnapshot(
            TailSnapshot(
                transcriptRows = 10,
                viewport = TailViewport(
                    totalItems = 10,
                    firstVisibleIndex = 6,
                    firstVisibleOffsetPx = 120,
                    lastVisibleIndex = 9,
                    lastVisibleOffsetPx = 1530,
                    lastVisibleSizePx = 400,
                    viewportEndOffsetPx = 1980,
                    bottomContentPaddingPx = 400,
                    isScrollInProgress = false,
                    atBottom = false,
                ),
            ),
        )
        assertTrue(afterExpand.following)
        assertEquals(TailPin(index = 6, offsetPx = 470), afterExpand.pin)

        // User collapses that card again without dragging -> anchor shifts back up while still at bottom.
        val afterCollapse = follow.onSnapshot(
            TailSnapshot(
                transcriptRows = 10,
                viewport = TailViewport(
                    totalItems = 10,
                    firstVisibleIndex = 6,
                    firstVisibleOffsetPx = 120,
                    lastVisibleIndex = 9,
                    lastVisibleOffsetPx = 1180,
                    lastVisibleSizePx = 400,
                    viewportEndOffsetPx = 1980,
                    bottomContentPaddingPx = 400,
                    isScrollInProgress = false,
                    atBottom = true,
                ),
            ),
        )
        assertTrue(afterCollapse.following)
        assertNull(afterCollapse.pin)

        // Subsequent streaming output arrives -> MUST still follow automatically!
        val nextStream = follow.onSnapshot(
            TailSnapshot(
                transcriptRows = 11,
                viewport = TailViewport(
                    totalItems = 11,
                    firstVisibleIndex = 6,
                    firstVisibleOffsetPx = 120,
                    lastVisibleIndex = 9,
                    lastVisibleOffsetPx = 1180,
                    lastVisibleSizePx = 400,
                    viewportEndOffsetPx = 1980,
                    bottomContentPaddingPx = 400,
                    isScrollInProgress = false,
                    atBottom = false,
                ),
            ),
        )
        assertTrue(nextStream.following)
        assertEquals(TailPin(index = 10, offsetPx = PIN_TO_END_PX), nextStream.pin)
    }
}
