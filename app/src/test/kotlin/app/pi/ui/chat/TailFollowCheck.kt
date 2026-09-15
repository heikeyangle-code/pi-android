package app.pi.ui.chat

// A bare-JVM harness for the transcript's follow-the-tail state machine
// (`ui/chat/TailFollow.kt`), compiled and run by `tools/run-app-pure-checks.sh`.
// The closure imports nothing from `android.*` or Compose — only the Kotlin stdlib —
// which is what lets it run here at all: the Compose UI it drives cannot be compiled
// on this machine (`tools/typecheck.sh` runs no Compose compiler plugin).
//
// Why this harness exists, in one line: the defect it pins is "the follow turned
// itself off and could never turn itself on again", which needs no device to check
// once the rules are out of the `LaunchedEffect`s and in a pure function. See
// `docs/streaming-review.md` §2.1 for the report and §5 for what the harness does
// and does not prove.
//
// The checks are grouped by the rule they pin, and every group names the pi rule it
// mirrors — `packages/tui/src/components/scroll-view.ts` is the authority for all of
// them, with the App's own decisions called out where they are App decisions.
//
// The geometry in these checks is a coherent model, not a fixture: a 1000 px
// viewport, 200 px rows, and a *history* of anchor positions (because "the user
// scrolled up" is a fact about a movement, not about one frame).
//
// Run it by hand:
//
//   tools/run-app-pure-checks.sh

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

/**
 * One observation of a 1000 px viewport whose rows are 200 px unless a check says
 * otherwise. The defaults describe "the transcript is scrolled to its end": the last
 * visible row is the tail, its bottom is on the viewport's end line.
 */
private fun viewport(
    totalItems: Int,
    atBottom: Boolean,
    firstVisibleIndex: Int = 0,
    firstVisibleOffsetPx: Int = 0,
    lastVisibleIndex: Int = totalItems - 1,
    lastVisibleOffsetPx: Int = 800,
    lastVisibleSizePx: Int = 200,
    viewportEndOffsetPx: Int = 1000,
    isScrollInProgress: Boolean = false,
) = TailViewport(
    totalItems = totalItems,
    firstVisibleIndex = firstVisibleIndex,
    firstVisibleOffsetPx = firstVisibleOffsetPx,
    lastVisibleIndex = lastVisibleIndex,
    lastVisibleOffsetPx = lastVisibleOffsetPx,
    lastVisibleSizePx = lastVisibleSizePx,
    viewportEndOffsetPx = viewportEndOffsetPx,
    isScrollInProgress = isScrollInProgress,
    atBottom = atBottom,
)

/** The state of the machine as a single comparable value. */
private fun state(decision: TailDecision) = "${decision.following}/${decision.unseenRows}"

fun main() {
    // ============================================================ A. the pause latch
    //
    // The regression this whole change exists for. `following` used to be paused by
    // `LaunchedEffect(atBottom) { if (!atBottom) following = false }`, i.e. by one
    // frame of a two-row approximation of "at the bottom". Appending a row, a row
    // growing past the fold, or a window change makes that approximation false
    // without the user touching anything — and the old code never re-armed.
    run {
        val tail = TailFollow()
        val first = tail.onSnapshot(TailSnapshot(transcriptRows = 10, viewport = viewport(10, atBottom = true)))
        check("A1 at the bottom: following, nothing to pin", state(first), "true/0")
        check("A2 at the bottom: no pin", first.pin, null)

        // A new row arrives: the list no longer reaches its end, and *nobody scrolled*.
        val grew = tail.onSnapshot(
            TailSnapshot(
                transcriptRows = 11,
                viewport = viewport(
                    totalItems = 11,
                    atBottom = false,
                    firstVisibleIndex = 6,
                    lastVisibleOffsetPx = 900,
                ),
            ),
        )
        check("A3 content growth does NOT pause the follow", state(grew), "true/0")
        check("A4 and it pins the 100 px that are below the fold", grew.pin, TailPin(6, 100))

        // The old approximation's other blind spot: a row taller than the viewport is
        // "at the bottom" by index while its newest text is a viewport below the fold.
        val tall = tail.onSnapshot(
            TailSnapshot(
                transcriptRows = 11,
                viewport = viewport(
                    totalItems = 11,
                    atBottom = false,
                    firstVisibleIndex = 10,
                    lastVisibleOffsetPx = 0,
                    lastVisibleSizePx = 4000,
                ),
            ),
        )
        check("A5 a tall tail row keeps the follow armed", state(tall), "true/0")
        check("A6 and the pin is the 4000-1000 px that are below the fold", tall.pin, TailPin(10, 3000))

        // Layout churn: the flag flapping for several frames, no gesture anywhere.
        val churn = TailFollow()
        var last: TailDecision? = null
        for (rows in 1..6) {
            last = churn.onSnapshot(
                TailSnapshot(
                    transcriptRows = rows,
                    viewport = viewport(totalItems = rows, atBottom = rows % 2 == 0),
                ),
            )
        }
        check("A7 a flapping at-bottom never pauses the follow", state(last!!), "true/0")
        check("A8 unseen rows stay 0 while following", last.unseenRows, 0)
    }

    // ========================================================= B. the user's gesture
    run {
        val tail = TailFollow()
        // A long transcript parked at its end: 50 rows, the last five on screen.
        tail.onSnapshot(TailSnapshot(50, viewport(50, atBottom = true, firstVisibleIndex = 45)))

        // Dragging towards history: a scroll session is in progress and the viewport
        // moved *backwards* (a smaller first-visible index).
        val up = tail.onSnapshot(
            TailSnapshot(50, viewport(50, atBottom = false, firstVisibleIndex = 30, isScrollInProgress = true)),
        )
        check("B1 a user up-scroll pauses the follow", state(up), "false/0")
        check("B2 and nothing is pinned while paused", up.pin, null)

        // Growth while paused: never scrolled back, so the machine must stay out of
        // the way - and count what arrived.
        val grew = tail.onSnapshot(TailSnapshot(53, viewport(53, atBottom = false, firstVisibleIndex = 30)))
        check("B3 growth while paused stays paused", state(grew), "false/3")
        check("B4 and never yanks the scroll", grew.pin, null)

        // The user scrolls back down to the very end: pi's rule re-arms
        // (`scroll-view.ts:127-157`: followingEnd = followEnd && next === maxScrollTop).
        val back = tail.onSnapshot(
            TailSnapshot(53, viewport(53, atBottom = true, firstVisibleIndex = 48, isScrollInProgress = true)),
        )
        check("B5 returning to the bottom re-arms", state(back), "true/0")

        // A fling that travels: the frames that leave the end, then the frame that
        // lands back on it. The position wins, as it does in pi.
        val fling = TailFollow()
        fling.onSnapshot(TailSnapshot(50, viewport(50, atBottom = true, firstVisibleIndex = 45)))
        val mid = fling.onSnapshot(
            TailSnapshot(50, viewport(50, atBottom = false, firstVisibleIndex = 10, isScrollInProgress = true)),
        )
        val landed = fling.onSnapshot(
            TailSnapshot(50, viewport(50, atBottom = true, firstVisibleIndex = 45, isScrollInProgress = true)),
        )
        check("B6 a fling away pauses mid-flight", state(mid), "false/0")
        check("B7 landing at the end re-arms it", state(landed), "true/0")

        // Anchor movement without a scroll session: a window prepend, a window shrink,
        // a session switch, an inset change. None of them is a gesture.
        val passive = TailFollow()
        passive.onSnapshot(TailSnapshot(60, viewport(60, atBottom = true, firstVisibleIndex = 55)))
        val shifted = passive.onSnapshot(
            TailSnapshot(60, viewport(60, atBottom = false, firstVisibleIndex = 5)),
        )
        check("B8 an anchor jump without a gesture does not pause", state(shifted), "true/0")

        // ... and an anchor move *backwards* without a session is not an up-scroll
        // either: a window shrink moves the anchor back, and must not read as "the
        // user asked to look at history".
        val shrunk = passive.onSnapshot(TailSnapshot(60, viewport(60, atBottom = false, firstVisibleIndex = 55)))
        check("B9 a window shrink is not a gesture", state(shrunk), "true/0")
    }

    // ================================================= C. the render window (F34)
    //
    // A long session renders its last `renderWindow` rows and grows that window
    // upwards; the *transcript* row count is what this machine sees, so a window
    // change is an ordinary layout change. Neither direction may flip the state, and
    // while paused neither direction may scroll.
    run {
        val tail = TailFollow()
        tail.onSnapshot(TailSnapshot(500, viewport(51, atBottom = true, firstVisibleIndex = 46)))
        val grewWindow = tail.onSnapshot(
            TailSnapshot(500, viewport(101, atBottom = true, firstVisibleIndex = 96)),
        )
        check("C1 a window prepend does not pause the follow", state(grewWindow), "true/0")
        check("C2 and needs no pin, the end is still the end", grewWindow.pin, null)

        val paused = TailFollow()
        paused.onSnapshot(TailSnapshot(500, viewport(51, atBottom = true, firstVisibleIndex = 46)))
        paused.onSnapshot(
            TailSnapshot(500, viewport(51, atBottom = false, firstVisibleIndex = 20, isScrollInProgress = true)),
        )
        val windowGrew = paused.onSnapshot(
            TailSnapshot(500, viewport(101, atBottom = false, firstVisibleIndex = 70)),
        )
        val windowShrank = paused.onSnapshot(
            TailSnapshot(500, viewport(51, atBottom = false, firstVisibleIndex = 20)),
        )
        check("C3 a window prepend does not resume a paused follow", state(windowGrew), "false/0")
        check("C4 and does not scroll", windowGrew.pin, null)
        check("C5 a window shrink does not resume it either", state(windowShrank), "false/0")
        check("C6 and does not scroll", windowShrank.pin, null)
    }

    // ================================================== D. a rebuilt transcript
    //
    // Fewer rows cannot come from streaming: the reducer appends, and the window is a
    // slice. It means a different session, a fork, a clone or a replay - and a new
    // conversation opens following its tail (spec §4.5).
    run {
        val tail = TailFollow()
        tail.onSnapshot(TailSnapshot(100, viewport(50, atBottom = true, firstVisibleIndex = 45)))
        tail.onSnapshot(
            TailSnapshot(100, viewport(50, atBottom = false, firstVisibleIndex = 10, isScrollInProgress = true)),
        )
        val rebuilt = tail.onSnapshot(TailSnapshot(12, viewport(12, atBottom = true)))
        check("D1 a rebuilt transcript re-arms", state(rebuilt), "true/0")

        // The very first snapshot must not read as a shrink.
        val fresh = TailFollow()
        val first = fresh.onSnapshot(TailSnapshot(7, viewport(7, atBottom = true)))
        check("D2 the first snapshot neither re-arms nor counts", state(first), "true/0")
    }

    // ============================================ E. a navigation pause (pi's disableFollow)
    //
    // Jumping to a search hit or to a previous prompt is not the user asking to
    // follow, and pi keeps the follow off even when the target is the end
    // (`scroll-view.ts:19`, `:131`; the search reveal at `tui-alt-screen.ts:636`).
    // The App's reveals are direct position changes too (`requestScrollToItem`, which
    // starts no scroll session), so they are not observed as gestures at all.
    run {
        val tail = TailFollow()
        tail.onSnapshot(TailSnapshot(40, viewport(40, atBottom = true, firstVisibleIndex = 35)))
        tail.pause()
        val revealed = tail.onSnapshot(
            TailSnapshot(40, viewport(40, atBottom = false, firstVisibleIndex = 2)),
        )
        check("E1 a reveal keeps the follow paused", state(revealed), "false/0")
        check("E2 and reveals do not scroll to the tail", revealed.pin, null)

        // A hit in the last block: the reveal lands on the end. pi's disableFollow
        // means that does not re-arm.
        val landedOnEnd = tail.onSnapshot(TailSnapshot(40, viewport(40, atBottom = true, firstVisibleIndex = 35)))
        check("E3 a navigation that lands on the end does not re-arm", state(landedOnEnd), "false/0")

        // Growth after it stays paused - this is the "scrolled up and never pulled
        // back" half of the requirement.
        val grew = tail.onSnapshot(TailSnapshot(42, viewport(42, atBottom = false, firstVisibleIndex = 35)))
        check("E4 growth after a navigation stays paused", state(grew), "false/2")
        check("E5 and still does not scroll", grew.pin, null)

        // The user's own hand clears the suppression; reaching the end then re-arms.
        tail.onSnapshot(
            TailSnapshot(42, viewport(42, atBottom = false, firstVisibleIndex = 40, isScrollInProgress = true)),
        )
        val userBack = tail.onSnapshot(TailSnapshot(42, viewport(42, atBottom = true, firstVisibleIndex = 40)))
        check("E6 a user gesture clears the suppression, the end re-arms", state(userBack), "true/0")

        // The explicit affordance re-arms from anywhere, including mid-stream.
        val fab = TailFollow()
        fab.onSnapshot(TailSnapshot(30, viewport(30, atBottom = true, firstVisibleIndex = 25)))
        fab.pause()
        fab.onSnapshot(TailSnapshot(30, viewport(30, atBottom = false, firstVisibleIndex = 10)))
        fab.reArm()
        val afterFab = fab.onSnapshot(
            TailSnapshot(
                32,
                viewport(
                    totalItems = 32,
                    atBottom = false,
                    firstVisibleIndex = 10,
                    lastVisibleIndex = 31,
                    lastVisibleOffsetPx = 0,
                    lastVisibleSizePx = 2000,
                ),
            ),
        )
        check("E7 the affordance re-arms", state(afterFab), "true/0")
        check("E8 and pins to the tail", afterFab.pin, TailPin(10, 1000))
    }

    // ================================================================= F. the pin
    //
    // `scrollToItem(lastIndex)` parks the last row's *top* at the top of the
    // viewport, which hides the newest text of anything taller than the viewport.
    // The pin is a pixel delta on the current anchor instead, so it needs no row
    // heights; these checks pin the arithmetic.
    run {
        val visibleTall = viewport(
            totalItems = 20,
            atBottom = false,
            firstVisibleIndex = 17,
            firstVisibleOffsetPx = 40,
            lastVisibleIndex = 19,
            lastVisibleOffsetPx = 300,
            lastVisibleSizePx = 800,
            viewportEndOffsetPx = 1000,
        )
        // 300 + 800 - 1000 = 100 px of the tail are below the fold.
        check("F1 the pin is the hidden pixel count", visibleTall.pinToTail(), TailPin(17, 140))

        val notVisible = viewport(totalItems = 20, atBottom = false, lastVisibleIndex = 18)
        check("F2 a tail that is not on screen is pinned by index", notVisible.pinToTail(), TailPin(19, 0))

        val fullyVisible = viewport(
            totalItems = 20,
            atBottom = false,
            lastVisibleIndex = 19,
            lastVisibleOffsetPx = 200,
            lastVisibleSizePx = 800,
        )
        check("F3 a tail that is already fully visible is not pinned", fullyVisible.pinToTail(), null)

        val empty = viewport(totalItems = 0, atBottom = true, lastVisibleIndex = -1)
        check("F4 an empty list has nothing to pin", empty.pinToTail(), null)

        // Convergence: after the measure applies the pin, the tail's bottom sits on
        // the viewport's end line and the next decision asks for nothing. This is what
        // keeps a per-frame pin from fighting itself.
        val settled = viewport(
            totalItems = 20,
            atBottom = false,
            firstVisibleIndex = 17,
            firstVisibleOffsetPx = 140,
            lastVisibleIndex = 19,
            lastVisibleOffsetPx = 200,
            lastVisibleSizePx = 800,
            viewportEndOffsetPx = 1000,
        )
        check("F5 the pinned position asks for nothing more", settled.pinToTail(), null)

        // The 「加载更早的 N 条」row is part of the list the machine indexes, which is
        // why a header does not shift anything: the pin speaks in the list's own
        // coordinates and needs no `headerRows` arithmetic.
        val withHeader = viewport(
            totalItems = 52,
            atBottom = false,
            firstVisibleIndex = 47,
            firstVisibleOffsetPx = 0,
            lastVisibleIndex = 51,
            lastVisibleOffsetPx = 0,
            lastVisibleSizePx = 2000,
        )
        check("F6 the header row does not shift the pin", withHeader.pinToTail(), TailPin(47, 1000))
    }

    // ====================================== G. same-frame sequences and persistence
    run {
        // Several observations between two frames. The old code flipped on each of
        // them; the machine has to end in one state, with no oscillation.
        val tail = TailFollow()
        val seq = listOf(true, false, true, false, true).map { atBottom ->
            tail.onSnapshot(TailSnapshot(10, viewport(10, atBottom = atBottom)))
        }
        check("G1 a flapping sequence ends following", seq.last().following, true)
        check("G2 and never counted anything as unseen", seq.map { it.unseenRows }.distinct(), listOf(0))

        // A pin is asked for once per geometry. Repeating it is not just redundant:
        // `requestScrollToItem` schedules a remeasure even for a position that is
        // already in place, and that remeasure re-emits the flow that feeds this
        // machine, so a repeated pin would be a per-frame remeasure loop.
        val stable = TailFollow()
        val snapshot = TailSnapshot(12, viewport(12, atBottom = false, lastVisibleSizePx = 1500))
        val one = stable.onSnapshot(snapshot)
        val two = stable.onSnapshot(snapshot)
        check("G3 the first snapshot pins", one.pin, TailPin(0, 1300))
        check("G4 an identical repeat is not re-requested", two.pin, null)
        check("G5 and the state is unchanged", state(two), state(one))

        // A poke changes nothing by itself: it is the caller's re-evaluation trigger.
        val poked = stable.onSnapshot(snapshot.copy(poke = 99))
        check("G6 a poke does not change the state", state(poked), state(two))
        check("G7 and does not re-issue the same pin", poked.pin, null)

        // A geometry that *did* move is pinned again: the row grew by 40 px, so the
        // follow has 40 more to go.
        val grown = stable.onSnapshot(
            TailSnapshot(12, viewport(12, atBottom = false, lastVisibleSizePx = 1540)),
        )
        check("G8 new geometry is pinned again", grown.pin, TailPin(0, 1340))

        // The loop the repeat guard exists for, in the shape that used to defeat it.
        // When the tail row is taller than the viewport the pin is the *fixed*
        // `TailPin(tail, 0)`: it does not depend on the offsets, and the list cannot
        // satisfy it (the requested position is clamped), so the remeasure reports a
        // different geometry with the same pin. Requiring an identical geometry as
        // well let that through once per frame — a remeasure loop with nothing left to
        // fix, which is the reported "卡住不动 / 没有响应".
        val unreachable = TailFollow()
        val asked = unreachable.onSnapshot(
            TailSnapshot(6, viewport(6, atBottom = false, lastVisibleIndex = 2)),
        )
        check("G9 a tail above the viewport pins to its index", asked.pin, TailPin(5, 0))
        val remeasured = unreachable.onSnapshot(
            TailSnapshot(6, viewport(6, atBottom = false, firstVisibleIndex = 1, lastVisibleIndex = 2)),
        )
        check("G10 the same unsatisfiable pin is not re-issued", remeasured.pin, null)

        // Convergence, made executable. A pin is a request for a position, so the
        // question "does the follow ever stop asking?" is a question about the
        // geometry that applying the pin produces. The taller-than-viewport case is
        // the one that matters (a long streaming answer), and `pinToTail`'s third
        // branch is pure arithmetic on it:
        //   hidden = lastVisibleOffsetPx + lastVisibleSizePx - viewportEndOffsetPx
        // With the tail row's top at the viewport top (offset 0, size 2000, viewport
        // end 1000) the follow asks for 1000 px more; at the position it asked for,
        // the row's top is 1000 px above the viewport top and `hidden` is exactly 0,
        // so the next observation asks for nothing. If either of these two checks
        // ever fails, the "it converges" argument is wrong and the follow really can
        // drive itself frame after frame - which is the signal worth having.
        val tall = TailFollow()
        val tallAsked = tall.onSnapshot(
            TailSnapshot(
                6,
                viewport(
                    6,
                    atBottom = false,
                    firstVisibleIndex = 5,
                    lastVisibleOffsetPx = 0,
                    lastVisibleIndex = 5,
                    lastVisibleSizePx = 2000,
                    viewportEndOffsetPx = 1000,
                ),
            ),
        )
        check("G14 a tall tail row pins by the pixels below the fold", tallAsked.pin, TailPin(5, 1000))
        val tallSettled = tall.onSnapshot(
            TailSnapshot(
                6,
                viewport(
                    6,
                    atBottom = true,
                    firstVisibleIndex = 5,
                    lastVisibleOffsetPx = -1000,
                    lastVisibleIndex = 5,
                    lastVisibleSizePx = 2000,
                    viewportEndOffsetPx = 1000,
                ),
            ),
        )
        check("G15 the position that pin asked for asks for nothing", tallSettled.pin, null)

        // And the guard must not survive an explicit re-arm: the affordance means
        // "go to the newest now", so the remembered pin cannot suppress it.
        val reArmed = TailFollow()
        reArmed.onSnapshot(TailSnapshot(6, viewport(6, atBottom = false, lastVisibleIndex = 2)))
        reArmed.pause()
        reArmed.reArm()
        val afterReArm = reArmed.onSnapshot(
            TailSnapshot(6, viewport(6, atBottom = false, lastVisibleIndex = 2)),
        )
        check("G11 an explicit re-arm pins again", afterReArm.pin, TailPin(5, 0))

        // Rotation: the paused state and its count survive, the anchors do not.
        val paused = TailFollow()
        paused.onSnapshot(TailSnapshot(50, viewport(50, atBottom = true, firstVisibleIndex = 45)))
        paused.onSnapshot(
            TailSnapshot(50, viewport(50, atBottom = false, firstVisibleIndex = 30, isScrollInProgress = true)),
        )
        paused.onSnapshot(TailSnapshot(55, viewport(55, atBottom = false, firstVisibleIndex = 30)))
        val restored = TailFollow.fromSavedState(paused.savedState())!!
        check("G9 a restored pause is still paused, with its count", "${restored.following}/${restored.unseenRows}", "false/5")
        val restoredFirst = restored.onSnapshot(
            TailSnapshot(55, viewport(55, atBottom = false, firstVisibleIndex = 30)),
        )
        check("G10 and the restore is not read as a gesture", state(restoredFirst), "false/5")

        val armed = TailFollow()
        armed.onSnapshot(TailSnapshot(8, viewport(8, atBottom = true)))
        check("G11 a restored follow is still a follow", TailFollow.fromSavedState(armed.savedState())!!.following, true)
        check("G12 a malformed saved state is rejected", TailFollow.fromSavedState(emptyList()), null)

        // A restored *armed* machine that comes back scrolled away from the end must
        // resume following, not sit there paused: the count is 0 and the user never
        // expressed a pause.
        val restoredArmed = TailFollow.fromSavedState(listOf(1, 0))!!
        val resumed = restoredArmed.onSnapshot(TailSnapshot(20, viewport(20, atBottom = false)))
        check("G13 a restored follow keeps following", state(resumed), "true/0")
    }

    // ================================================ H. loading earlier rows (window)
    //
    // The defect these pin is not in the follow machine but in the *rendering window*
    // it feeds: a firm flick toward history walked to the first row of the whole
    // conversation (「不管聊天有多长，我往下稍微用力划一下，它直接回到聊天最顶部」). The
    // cause was that a batch of rows was prepended **while the fling was still
    // running**, so the list kept growing in the direction of travel and the fling
    // never reached an end. `mayLoadEarlier` is the rule; `prependAnchoredIndex` is
    // what keeps the prepend from moving the rows under the viewport, because the
    // window's head row (「加载更早的 N 条」) keeps a constant key at index 0 and so
    // defeats the `LazyColumn`'s own key anchoring exactly when it is the first
    // visible item.
    run {
        // The rule itself.
        check("H1 a flick in flight loads nothing", mayLoadEarlier(true, true, 500, true), false)
        check("H2 the batch loads once the gesture is over", mayLoadEarlier(true, true, 500, false), true)
        check("H3 away from the window top nothing loads", mayLoadEarlier(false, true, 500, false), false)
        check("H4 an un-armed window top loads nothing", mayLoadEarlier(true, false, 500, false), false)
        check("H5 nothing hidden loads nothing", mayLoadEarlier(true, true, 0, false), false)

        // The anchor. 50 rows are prepended; the "load earlier" row stays at index 0
        // (headerRowsBefore = headerRowsAfter = 1), so a viewport parked on it has to
        // move to index 51 — the sentinel is 0, the 50 new rows are 1..50, and the row
        // the user was reading (old content row 0) is now 51. Parking at 50 would land
        // on the *newest* row of the batch and slide the content by 49 rows.
        check("H6 the sentinel row anchors to the batch's first row", prependAnchoredIndex(0, 50, 1, 1), 51)
        // Reading real content: content row 9 was at index 10 and must stay there.
        check("H7 a content row keeps its position", prependAnchoredIndex(10, 50, 1, 1), 60)
        // The last batch: nothing is hidden afterwards, so the sentinel is gone and
        // every index drops by one.
        check("H8 the final batch accounts for the vanished sentinel", prependAnchoredIndex(10, 50, 1, 0), 59)
        // No sentinel at either end (a window that was already complete).
        check("H9 no sentinel, no shift", prependAnchoredIndex(10, 0, 0, 0), 10)
        // A viewport scrolled inside a row keeps its pixel offset; the caller passes
        // `firstVisibleItemScrollOffset` through untouched, which this pins as a
        // statement about the function's contract: it only ever moves the *index*.
        check("H10 nothing prepended anchors to itself", prependAnchoredIndex(3, 0, 1, 1), 3)
        // Defensive: a negative first-visible index cannot happen from
        // `LazyListState`, but the function must not invent a row above 0 — it clamps
        // to "the sentinel was the first visible item", which is the same answer H6
        // pins.
        check("H11 a negative index clamps to the sentinel case", prependAnchoredIndex(-1, 50, 1, 1), 51)
    }

    // ================================ I. "I was at the bottom, I sent, and it stopped" ===
    //
    // The user's own reproduction, verbatim: 「有时候我明明就在屏幕底部给它发消息，发完了它
    // 就不跟随，还得手滑。」 No up-scroll ever happened, so every one of these observations
    // has the viewport at its end, and the only reason the follow can be lost is that
    // something *stopped observing the machine* at the moment the message went out.
    //
    // The rules themselves already say the right thing (rule 3 re-arms at the end, and a
    // gesture that ends at the end re-arms regardless of the `pausedByNavigation`
    // suppression). What these checks pin is the **caller's obligation** those rules
    // depend on, which is the half that was wrong in `ChatScreen`: a paused machine that
    // is not fed can never re-arm, and a pause written by the caller from a layout-driven
    // `isScrollInProgress` (rather than from an anchor movement) latches
    // `pausedByNavigation` and can then never be undone by the position alone.
    run {
        // The wiring, in the shape the fixed effect has: feed the machine, then mirror
        // its answer. `following` is the state the UI shows; `stale` would be the state
        // the removed `if (!following) return` produced by skipping the feed.
        fun feed(machine: TailFollow, snapshot: TailSnapshot): Boolean = machine.onSnapshot(snapshot).following

        // At the end, the follow armed, the user sends.
        val send = TailFollow()
        val before = feed(send, TailSnapshot(10, viewport(10, atBottom = true)))
        check("I1 at the bottom before sending, following", before, true)

        // The optimistic echo arrives, so the list no longer reaches its end, and a
        // layout pass (the keyboard collapsing is the one that does it) starts what
        // *looks* like a scroll session while the anchor has not moved at all.
        val duringSend = feed(
            send,
            TailSnapshot(
                11,
                viewport(11, atBottom = false, firstVisibleIndex = 6, isScrollInProgress = true),
            ),
        )
        check("I2 a layout-driven scroll session with a still anchor does not pause", duringSend, true)

        // Same session, one frame later, still nothing moved, and the tail is taller
        // than the viewport (a long streaming answer): still the follow's job.
        val tallDuringSend = feed(
            send,
            TailSnapshot(
                11,
                viewport(
                    11,
                    atBottom = false,
                    firstVisibleIndex = 10,
                    lastVisibleOffsetPx = 0,
                    lastVisibleSizePx = 4000,
                    isScrollInProgress = true,
                ),
            ),
        )
        check("I3 and a tall streaming tail keeps it armed", tallDuringSend, true)

        // The session ends where it started — at the end. This is the observation the
        // old effect could not make, because it had already returned on `!following`.
        val afterSend = feed(send, TailSnapshot(11, viewport(11, atBottom = true, firstVisibleIndex = 6)))
        check("I4 the session ending at the bottom keeps following", afterSend, true)

        // And the token that follows the send is pinned, not left a few pixels short:
        // the list grew by a row while the follow was armed.
        val firstToken = feed(
            send,
            TailSnapshot(
                11,
                viewport(11, atBottom = false, firstVisibleIndex = 6, lastVisibleOffsetPx = 900),
            ),
        )
        check("I5 the next token is still following", firstToken, true)

        // The stale-wiring counter-example, executed: a pause stamped by the *caller*
        // for that same layout-driven session. `pause()` sets the navigation
        // suppression, and then rule 3 can no longer undo it even though the viewport
        // never left the end — this is the permanent "发完就不跟随". The follow flag is
        // the point; the count is the row that arrived after the pause (`pause()` zeroed
        // the machine's own `unseenRows`, and this observation adds the one new row).
        val callerPaused = TailFollow()
        callerPaused.onSnapshot(TailSnapshot(10, viewport(10, atBottom = true)))
        callerPaused.pause()
        val afterCallerPause = callerPaused.onSnapshot(TailSnapshot(11, viewport(11, atBottom = true)))
        check("I6 a caller-paused follow stays paused at the end", state(afterCallerPause), "false/1")

        // Whereas a pause the *machine* derived from a real gesture is the one the user
        // can undo by coming back down: the anchor moved, and then the end was reached.
        val userPaused = TailFollow()
        userPaused.onSnapshot(TailSnapshot(10, viewport(10, atBottom = true)))
        userPaused.onSnapshot(
            TailSnapshot(10, viewport(10, atBottom = false, firstVisibleIndex = 3, isScrollInProgress = true)),
        )
        val userBackAtEnd = userPaused.onSnapshot(TailSnapshot(10, viewport(10, atBottom = true)))
        check("I7 the machine's own gesture pause re-arms at the end", state(userBackAtEnd), "true/0")

        // A pause that came from the machine must also *accumulate* while the user is
        // away (the badge's `transcriptRows - pausedRows`), which is why the caller
        // stamps its baseline on the pause transition and not on every snapshot.
        val counting = TailFollow()
        counting.onSnapshot(TailSnapshot(20, viewport(20, atBottom = true, firstVisibleIndex = 15)))
        counting.onSnapshot(
            TailSnapshot(20, viewport(20, atBottom = false, firstVisibleIndex = 5, isScrollInProgress = true)),
        )
        val grew = counting.onSnapshot(TailSnapshot(25, viewport(25, atBottom = false, firstVisibleIndex = 5)))
        check("I8 a paused follow counts the rows that arrive", state(grew), "false/5")
        val grewMore = counting.onSnapshot(TailSnapshot(29, viewport(29, atBottom = false, firstVisibleIndex = 5)))
        check("I9 and keeps counting them", state(grewMore), "false/9")

        // The end reached by the user's *hand* after a navigation pause clears the
        // suppression (pi's `disableFollow` is undone by a gesture, and the harness's
        // existing E6 covers the same rule from the other direction). Pinned here for
        // the send path, where a reveal and a send can land in the same second.
        val navigated = TailFollow()
        navigated.onSnapshot(TailSnapshot(30, viewport(30, atBottom = true, firstVisibleIndex = 25)))
        navigated.pause()
        navigated.onSnapshot(TailSnapshot(30, viewport(30, atBottom = false, firstVisibleIndex = 8)))
        val gestureAtEnd = navigated.onSnapshot(
            TailSnapshot(30, viewport(30, atBottom = true, firstVisibleIndex = 25, isScrollInProgress = true)),
        )
        check("I10 a gesture that ends at the bottom clears the navigation pause", state(gestureAtEnd), "true/0")

        // `pause()` forgets the tail it was following, so a pin that was already issued
        // cannot suppress the one the position needs after an explicit re-arm.
        val forgotten = TailFollow()
        val longTail = TailSnapshot(
            6,
            viewport(6, atBottom = false, lastVisibleIndex = 2, lastVisibleSizePx = 2000),
        )
        check("I11 the tail is pinned once", forgotten.onSnapshot(longTail).pin, TailPin(5, 0))
        forgotten.pause()
        forgotten.reArm()
        check(
            "I12 a pause does not remember the old pin",
            forgotten.onSnapshot(longTail).pin,
            TailPin(5, 0),
        )

        // The exact end geometry this whole group turns on: at `!canScrollForward` the
        // machine asks for nothing, so "I am at the bottom and nothing is happening" is
        // never a stuck pin — it is the state the follow is supposed to be in.
        val atEnd = TailFollow()
        val atEndDecision = atEnd.onSnapshot(TailSnapshot(11, viewport(11, atBottom = true, firstVisibleIndex = 6)))
        check("I13 the end is a state with nothing to pin", atEndDecision.pin, null)
        val atEndAgain = atEnd.onSnapshot(TailSnapshot(12, viewport(12, atBottom = true, firstVisibleIndex = 7)))
        check("I14 and another row at the end still pins nothing", atEndAgain.pin, null)
        check("I15 while the follow stays armed", state(atEndAgain), "true/0")
    }

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
