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
        // The offset is not 0: it asks for the tail row's **end**
        // (`PIN_TO_END_PX`). Asking for its top put the newest lines of a tall row one
        // viewport below the fold and relied on a second snapshot to correct it — and that
        // second snapshot only exists if one of the caller's effect keys changes, which in
        // this branch it does not (see `pinToTail`'s KDoc). The user's report was
        // 「下点了它到不了屏幕最底部」.
        check(
            "F2 a tail that is not on screen is pinned to the end in one step",
            notVisible.pinToTail(),
            TailPin(19, PIN_TO_END_PX),
        )
        // The one-shot property itself: the geometry the request above produces — the tail
        // row is now the last visible row and fully inside the viewport, which is what the
        // measure pass clamps to — asks for nothing more. F2 + F2b together are "no second
        // frame needed", the thing the old pin got wrong.
        val landedAtEnd = viewport(
            totalItems = 20,
            atBottom = true,
            firstVisibleIndex = 19,
            lastVisibleOffsetPx = 0,
            lastVisibleSizePx = 500,
            viewportEndOffsetPx = 1000,
        )
        check("F2b and that position needs no second snapshot", landedAtEnd.pinToTail(), null)
        // A guard on the constant itself: it has to be past any plausible content
        // (a 1000 px viewport × thousands of rows) and still far from `Int.MAX_VALUE`, so
        // the measure pass cannot wrap when it adds it to a row's own offset.
        check("F2c the end offset is large but not at the int boundary", PIN_TO_END_PX in (1 shl 20)..(1 shl 28), true)

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
        // When the tail row is taller than the viewport the pin is a *fixed*
        // `TailPin(tail, PIN_TO_END_PX)`: it does not depend on the offsets, and the list
        // cannot satisfy it exactly (the requested position is clamped to the content end),
        // so the remeasure reports a different geometry with the same pin. Requiring an
        // identical geometry as well let that through once per frame — a remeasure loop with
        // nothing left to fix, which is the reported "卡住不动 / 没有响应".
        val unreachable = TailFollow()
        val asked = unreachable.onSnapshot(
            TailSnapshot(6, viewport(6, atBottom = false, lastVisibleIndex = 2)),
        )
        check("G9 a tail above the viewport is pinned to the end", asked.pin, TailPin(5, PIN_TO_END_PX))
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
        check("G11 an explicit re-arm pins again", afterReArm.pin, TailPin(5, PIN_TO_END_PX))

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
    // never reached an end. `mayLoadEarlier` is the rule.
    //
    // The second half used to be `prependAnchoredIndex`: a compensating scroll request,
    // because 「加载更早」 was the list's item 0, kept its key at index 0 across a prepend,
    // and therefore defeated the `LazyColumn`'s own key anchoring exactly when it was the
    // first visible item. The row now lives **outside** the list (`docs/scroll-diagnosis.md`
    // §3.4, landed as D51) and its band is reserved in `contentPadding.top`, so the first
    // item of the list is a real transcript row whose key survives a prepend and the
    // anchoring is simply correct. That is what the checks below pin instead: the arithmetic
    // of the band the affordance reserves, and the fact that a batch inserted at the
    // transcript's head moves the reader's row by the number of rows inserted **and no
    // further** — with `headerRows = 0`, which is what the screen passes now.
    run {
        // The rule itself.
        check("H1 a flick in flight loads nothing", mayLoadEarlier(true, true, 500, true), false)
        check("H2 the batch loads once the gesture is over", mayLoadEarlier(true, true, 500, false), true)
        check("H3 away from the window top nothing loads", mayLoadEarlier(false, true, 500, false), false)
        check("H4 an un-armed window top loads nothing", mayLoadEarlier(true, false, 500, false), false)
        check("H5 nothing hidden loads nothing", mayLoadEarlier(true, true, 0, false), false)

        // The band the 「加载更早」 row reserves in the list's top padding: the row is
        // `Icon(24dp)` + `Spacer(6dp)` + `Text(meta)` in a `Row(padding(vertical = 8dp))`, so
        // its height is `max(icon, the label's line box) + 16`. The overlay is placed one band
        // above the list's first item, so this number *is* the affordance's position: the two
        // have to agree exactly or the row overlaps the first message (too tall a reservation
        // is a visible gap, too short an overlap), on the first frame, with no measure → pad →
        // measure pass.
        check("H6 the band is the icon plus the row's own padding when the label is short", earlierRowHeightPx(iconPx = 66, textHeightPx = 40, verticalPaddingPx = 44), 110)
        check("H7 a taller label raises the band", earlierRowHeightPx(iconPx = 66, textHeightPx = 90, verticalPaddingPx = 44), 134)
        check("H8 a wrapped label raises it by whole line boxes", earlierRowHeightPx(iconPx = 66, textHeightPx = 132, verticalPaddingPx = 44), 176)
        check("H9 the icon alone is never below the font's own floor", earlierRowHeightPx(iconPx = 66, textHeightPx = 1, verticalPaddingPx = 44), 110)
        check("H10 with no padding it is exactly the taller of the two", earlierRowHeightPx(iconPx = 66, textHeightPx = 90, verticalPaddingPx = 0), 90)

        // The sentinel-free list: the reader's row keeps its identity across a batch inserted
        // at the *transcript's* head, and the item index it ends up on is old + the number of
        // rows inserted — no correction, and `headerRows = 0` is what the screen passes.
        val before = (1..400).map { "row-$it" }
        val reader = "row-358"
        val readerIndex = itemIndexOfVisibleRow(before.indexOf(reader), before.size, 4000, false, 0)
        val grown = (-3..-1).map { "earlier-$it" } + before
        val grownIndex = itemIndexOfVisibleRow(grown.indexOf(reader), grown.size, 4000, false, 0)
        check("H11 the reader's index moves by exactly the rows inserted", grownIndex - readerIndex, 3)
        check(
            "H12 and the row at that index is still the reader's row",
            grown.getOrNull(visibleRowOfItemIndex(grownIndex, grown.size, 4000, false, 0)),
            reader,
        )
        check(
            "H13 while the old index would show older content",
            grown.getOrNull(visibleRowOfItemIndex(readerIndex, grown.size, 4000, false, 0)) != reader,
            true,
        )

        // The eager-parse gate's bound (P3): the keys a batch has just brought in are
        // remembered until each has been measured, and the set may not grow with the session.
        // One batch fits whole — a load moves the window by `TRANSCRIPT_WINDOW_STEP`, and
        // the bound holds several batches so a reader who loads twice before composing the
        // first batch's rows still gets the parses.
        run {
            val one = freshRowKeysAfter(emptyList(), (1..50).map { "k-$it" }, 200)
            check("H14 one batch fits whole", one.size, 50)

            // Four batches are exactly the bound, so every one of them still fits; the
            // fifth has to push the *oldest* one out — in whole 50-key batches, because the
            // trim runs from the front of an insertion-ordered set.
            var keys: Set<String> = emptySet()
            for (batch in 0 until 4) {
                keys = freshRowKeysAfter(keys, (1..50).map { "b$batch-$it" }, 200)
            }
            check("H15 four batches fill the bound exactly", keys.size, 200)
            check("H16 the newest batch is held", keys.contains("b3-50"), true)
            check("H17 the oldest batch is still inside the bound", keys.contains("b0-1"), true)
            keys = freshRowKeysAfter(keys, (1..50).map { "b4-$it" }, 200)
            check("H18 a fifth batch does not grow the set", keys.size, 200)
            check("H19 and it is the oldest batch that is dropped", keys.contains("b0-1"), false)
            check("H20 while the newest is held", keys.contains("b4-50"), true)

            // A batch larger than the bound (a file read can move the window further) keeps
            // the newest keys, which are the ones the reader reaches last and consumes first.
            val big = freshRowKeysAfter(emptyList(), (1..500).map { "g-$it" }, 200)
            check("H21 a batch larger than the bound is trimmed", big.size, 200)
            check("H22 keeping the newest keys", big.contains("g-500"), true)
            check("H23 and dropping the oldest", big.contains("g-1"), false)

            // Nothing added is not the same as a reset: the keys already waiting for their
            // first layout must survive a publication that brought none.
            val kept = freshRowKeysAfter(one, emptyList(), 200)
            check("H24 an empty batch leaves the gate alone", kept == one, true)
            check("H25 and it is still bounded", kept.size, 50)

            // A non-positive bound is refused rather than silently meaning "no bound" or
            // "remember nothing", either of which would be invisible until it mattered.
            val refused = try {
                freshRowKeysAfter(emptySet(), listOf("a"), 0)
                "accepted"
            } catch (error: IllegalArgumentException) {
                "refused"
            }
            check("H26 a zero bound is refused", refused, "refused")
        }

        // The **second arming edge** (`reArmsEarlier`), and the wedged window it exists
        // for: a transcript whose rows are short — collapsing tool cards is exactly that —
        // is shorter than the viewport, so the list cannot scroll, `atTop` is true for
        // ever, and the original single edge (「the user scrolled away」) can never fire
        // again. One batch was prepended, `hiddenCount` stayed above zero permanently, and
        // because reaching the session **file** is gated on `hiddenCount == 0` that never
        // ran either. The report was 「屏幕最上方，如果消息被折叠的话，加载历史对话根本就
        // 加载不出来」.
        check("H12 leaving the top re-arms", reArmsEarlier(atWindowTop = false, canScrollForward = true), true)
        check("H13 a viewport with nowhere to scroll re-arms", reArmsEarlier(true, false), true)
        check("H14 the ordinary case does not", reArmsEarlier(true, true), false)
        // The two halves, executed together: the rule above is what makes the load
        // possible at all in the wedged state…
        check(
            "H15 the second edge is what un-wedges the window",
            mayLoadEarlier(true, reArmsEarlier(true, false), 500, false),
            true,
        )
        // …and this is the old behaviour, kept as the statement of the defect: armed only
        // by the away-from-top edge, the same frame loads nothing, for ever.
        check("H16 the old single edge leaves it wedged", mayLoadEarlier(true, false, 500, false), false)
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
        check("I11 the tail is pinned once", forgotten.onSnapshot(longTail).pin, TailPin(5, PIN_TO_END_PX))
        forgotten.pause()
        forgotten.reArm()
        check(
            "I12 a pause does not remember the old pin",
            forgotten.onSnapshot(longTail).pin,
            TailPin(5, PIN_TO_END_PX),
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

    // ============================================================ J. the reader's place
    //
    // 「切到别的屏，再切回来，为什么会跳一下」 and 「往上滑看上面的消息，有时候会跳」 are one
    // arithmetic. The screen renders a **suffix** of the transcript
    // (`renderedItems = visibleItems.takeLast(renderWindow)`), while `LazyListState.Saver`
    // restores only `(firstVisibleItemIndex, firstVisibleItemScrollOffset)` — and an index
    // is a position only while the list under it is the same list. Rows the engine appends
    // while the chat destination is not composed therefore move the content under the
    // restored index towards the newest row; rows the session-file read inserts at the
    // *head* move it the other way; and a brand-new `LazyListState` reports
    // `canScrollForward == false` until its first measure, which armed the "nowhere left to
    // scroll" edge on the first frame of every re-entered destination.
    //
    // Two pure rules answer it and are pinned here: the window arithmetic the anchor
    // resolves a row **key** through (`hiddenRows`, `itemIndexOfVisibleRow`,
    // `visibleRowOfItemIndex`), and `mayArmEarlier`. Report: `docs/scroll-diagnosis.md`
    // §2.2 (S2-a/S2-b), §3.1, §3.2, §3.5.
    run {
        // The screen's own list comprehension, and the pure rule that has to agree with it:
        // the rendered rows are the transcript's last `renderWindow`, so that is exactly how
        // many rows are hidden.
        val rows = (1..400).map { "row-$it" }
        val step = 50
        check(
            "J1 hiddenRows is the suffix window's shortfall",
            hiddenRows(rows.size, step, false),
            rows.size - rows.takeLast(step).size,
        )
        check("J2 a window taller than the list hides nothing", hiddenRows(rows.size, 4000, false), 0)
        check("J3 an opened window hides nothing", hiddenRows(rows.size, 1, true), 0)
        check(
            "J4 the two index maps are inverses",
            visibleRowOfItemIndex(itemIndexOfVisibleRow(7, 400, 50, false, 1), 400, 50, false, 1),
            7,
        )

        // The helpers the screen's arithmetic is exercised through: `visibleItems` as a plain
        // list of keys, and the two directions the screen uses — a row `key` to the item
        // index it has to ask for (`indexOfKey`, what the anchor's correction computes), and
        // an item index back to the row that is actually drawn there (`keyAt`, what the
        // reader sees). Both take and return a **`visibleItems` index**, not a window index;
        // the window is what `hiddenRows` subtracts.
        fun keyAt(source: List<String>, renderWindow: Int, itemIndex: Int): String? {
            val visibleRow = visibleRowOfItemIndex(itemIndex, source.size, renderWindow, false, 1)
            // An item index above the list's first item is the sentinel, not a row.
            if (visibleRow < hiddenRows(source.size, renderWindow, false)) return null
            return source.getOrNull(visibleRow)
        }

        fun indexOfKey(source: List<String>, renderWindow: Int, key: String): Int {
            val visibleRow = source.indexOf(key)
            return if (visibleRow < 0) -1 else itemIndexOfVisibleRow(visibleRow, source.size, renderWindow, false, 1)
        }

        // The reader is on this row, and this is the item index the saver will hand back.
        val readerRow = 358
        val savedIndex = indexOfKey(rows, step, "row-$readerRow")
        check("J5 the saved item index is the reader's row", savedIndex, 8)
        check("J6 and the row at that index is the reader's", keyAt(rows, step, savedIndex), "row-$readerRow")

        // Rows arrived while the destination was not composed: the transcript's collector
        // runs in the ViewModel, so a chat screen that is not composed still grows.
        val afterArrival = rows + (401..403).map { "row-$it" }
        check(
            "J7 an index restore drifts by exactly the rows that arrived",
            keyAt(afterArrival, step, savedIndex),
            "row-${readerRow + 3}",
        )
        check(
            "J8 a key restore still shows the reader's row",
            keyAt(afterArrival, step, indexOfKey(afterArrival, step, "row-$readerRow")),
            "row-$readerRow",
        )
        check(
            "J9 and it is an index the correction has to move to",
            indexOfKey(afterArrival, step, "row-$readerRow") == savedIndex,
            false,
        )
        // Nothing arrived: the correction must ask for nothing, or every re-entry would
        // issue a `requestScrollToItem` (a remeasure) at the position already in place.
        check("J10 an unmoved window needs no correction", indexOfKey(rows, step, "row-$readerRow"), savedIndex)

        // The session-file read (`expandEarlierHistory`): it only fires with the window fully
        // open (`hiddenCount == 0`, i.e. `renderWindow >= rows`), and it inserts *older* rows
        // at the transcript's head. The reader's row keeps its identity and moves up the
        // list, so the same item index would show older content.
        val openWindow = 4000
        val openIndex = indexOfKey(rows, openWindow, "row-$readerRow")
        val withEarlier = (-3..-1).map { "earlier-$it" } + rows
        check(
            "J11 the head-inserted rows sit between the sentinel and the reader",
            indexOfKey(withEarlier, openWindow, "row-$readerRow") - openIndex,
            withEarlier.size - rows.size,
        )
        check(
            "J12 the key restore lands on the reader's row",
            keyAt(withEarlier, openWindow, indexOfKey(withEarlier, openWindow, "row-$readerRow")),
            "row-$readerRow",
        )
        check(
            "J13 without a correction the same index shows older content",
            keyAt(withEarlier, openWindow, openIndex) != "row-$readerRow",
            true,
        )

        // `mayArmEarlier`: the second arming edge must not fire before the list has been
        // measured. `LazyListState` seeds `canScrollForward = false` and only a measure pass
        // writes it — verified against `foundation-android:1.8.3`'s bytecode — so on the
        // first frame of every fresh state that edge is true for a reason that has nothing to
        // do with the content.
        check(
            "J14 a state that has not laid out cannot arm",
            mayArmEarlier(atWindowTop = true, canScrollForward = false, hasLaidOut = false),
            false,
        )
        check(
            "J15 the old rule armed exactly that frame",
            reArmsEarlier(atWindowTop = true, canScrollForward = false),
            true,
        )
        check("J16 a measured, scrollable list at the top does not arm", mayArmEarlier(true, true, true), false)
        check("J17 a measured list away from the top arms", mayArmEarlier(false, true, true), true)
        check(
            "J18 a measured list that cannot scroll still arms (the wedged-window fix)",
            mayArmEarlier(true, false, true),
            true,
        )

        // One batch per gesture, with the effect's own body: `earlierArmed` is cleared
        // *before* the load and nothing re-arms it while the viewport stays at the top, so a
        // second pass on the same frame state must load nothing.
        var armed = false
        var loads = 0
        fun frame(atTop: Boolean, canScrollForward: Boolean, hasLaidOut: Boolean, hidden: Int, scrolling: Boolean) {
            if (mayArmEarlier(atTop, canScrollForward, hasLaidOut)) armed = true
            if (!atTop) return
            if (!mayLoadEarlier(atWindowTop = atTop, armed = armed, hiddenRows = hidden, isScrollInProgress = scrolling)) return
            armed = false
            loads++
        }
        // The user scrolls away from the top (that is the arming edge) and back to it.
        frame(atTop = false, canScrollForward = true, hasLaidOut = true, hidden = 350, scrolling = false)
        frame(atTop = true, canScrollForward = true, hasLaidOut = true, hidden = 350, scrolling = false)
        check("J19 a batch is loaded once", loads, 1)
        frame(atTop = true, canScrollForward = true, hasLaidOut = true, hidden = 300, scrolling = false)
        check("J20 and not again while the viewport is still at the top", loads, 1)
        frame(atTop = false, canScrollForward = true, hasLaidOut = true, hidden = 300, scrolling = false)
        frame(atTop = true, canScrollForward = true, hasLaidOut = true, hidden = 300, scrolling = false)
        check("J21 leaving the top and coming back loads the next batch", loads, 2)

        // The first frame of a re-entered destination, end to end: no measure yet, so no arm,
        // so no batch and no 50-row teleport (S2-b).
        var freshArmed = false
        var freshLoads = 0
        if (mayArmEarlier(atWindowTop = true, canScrollForward = false, hasLaidOut = false)) freshArmed = true
        if (mayLoadEarlier(atWindowTop = true, armed = freshArmed, hiddenRows = 350, isScrollInProgress = false)) freshLoads++
        check("J22 a re-entered destination loads nothing before its first measure", freshLoads, 0)
    }

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
