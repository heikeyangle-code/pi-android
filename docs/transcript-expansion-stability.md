# Transcript expansion and scroll stability

This note records the invariants for the transcript's expandable rows. It is a regression guide, not a claim that the current branch has completed the whole repair.

## Scope

The shared behavior applies to every transcript item that changes height when toggled: built-in tool cards (`read`, `write`, `edit`, `grep`, `find`, `ls`, shells), extension/MCP tool cards, diff, thinking, error, skill, hook/custom messages, compaction, branch summaries, and second-level "show all" controls. A fix in the generic extension card alone is not a fix for this surface.

## Failure modes to guard against

1. **Never re-render on progress ticks.** Pending tool output and `liveDetails` are snapshots, not settled render inputs. A renderer key that changes with each progress publication causes a guest RPC every ~200 ms. Completion/status changes must invalidate the key and carry the final result.
2. **Do not discard the last renderer result when a request starts.** Clearing it to `null` on an expand/key change makes the row briefly use its fallback layout before the guest response arrives. That creates two height changes for one tap. A retained result must be scoped to the tool-call identity, and a late response must not overwrite a newer key.
3. **One scroll-position owner per height mutation.** The current `TranscriptRowAnchor.onCollapse()` requests the collapsing row at offset zero when its top is above the viewport. That changes the reader's chosen position by design. The correct contract is to preserve the same visible row and pixel offset through the next measure, except when tail-follow is armed (then tail-follow owns the position). Do not combine independent anchor and follow requests.
4. **Route every height-changing interaction through the shared policy.** Card taps, error/branch actions, nested "show all" controls, global expand/collapse, and any visibility preference must use the same anchor transaction. A helper that only wraps the primary card click leaves alternate paths unstable.
5. **Bound work, not only cache entries.** Caches prevent repeated parsing but do not make a huge eager `Column` cheap to compose/layout. Every expanded body needs an explicit rendering budget or lazy/viewport-based presentation; the omitted amount must remain visible. Avoid laying out an unbounded body on the frame thread.
6. **List identity is stable identity.** Lazy item keys must remain the transcript item's stable key. Never key a row by mutable output length, expansion, or position. Save disclosure state by item identity, not by a list index.
7. **No scroll-time guest work.** A row entering/leaving the viewport must not create one guest render request per card. Requests need lifecycle/cancellation policy and must not serialize unrelated cards behind a global lock.

## Required validation before calling the repair complete

- Exercise a short and a very long body at list start, middle, end, and with the toggled row partly clipped above/below the viewport.
- Toggle repeatedly and use all expand/collapse controls while tail-follow is armed and paused; assert the intended anchor does not change and no two controllers issue competing scroll requests.
- Fling through a transcript with many extension cards and verify no progress-sized host render storm, unbounded queued requests, or main-thread parsing/layout of the full output.
- Cover row disposal/re-entry, session switch, rotation, width change, and renderer completion racing with a newer request.
- Run pure logic tests, Android compile/tests, and device frame/scroll profiling. A repository-side test cannot prove a device has zero missed frames; report measured results instead of promising perfect performance.

## Current branch note

The first branch commit changed the extension renderer key/cache only. It does **not** implement the shared anchor transaction or the complete scope above. Do not treat it as the final fix or download it as a finished build. The branch must be revised and validated against this checklist before release.
