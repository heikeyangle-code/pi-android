package app.pi.highlight

import app.pi.ui.render.PiMermaidReply

/**
 * The mermaid side of the guest seam: pi's own `grok-mermaid`, asked over the same
 * loopback service as the highlighter.
 *
 * Contract, identical in shape to [PiNodeCodeHighlighter]'s:
 *
 *  - **it never throws.** Every failure — engine not running, no token, a timeout,
 *    a malformed reply — is [PiMermaidReply.Unavailable]; a source `grok-mermaid`
 *    refuses to draw is [PiMermaidReply.NoArt]. Both leave the caller drawing the
 *    fence's own source, which is exactly what pi does whenever `render()` returns
 *    `null` (`components/mermaid.ts:75-76`);
 *  - **the two failures are told apart**, because only one of them is worth another
 *    request: the guest imports `grok-mermaid` on the first `/mermaid` call, so a
 *    first-request timeout is expected on a cold engine and the caller retries it a
 *    bounded number of times, while `NoArt` is final;
 *  - **it is reachable only through [PiNodeCodeHighlighter.attach].** There is one
 *    client instance for one engine, and this renderer borrows it rather than
 *    keeping a second token read and a second set of credentials;
 *  - **no queue of its own.** The request is bounded by the client's own socket
 *    timeouts (100 ms connect, 150 ms read) and the caller runs it off the main
 *    thread, so a slow engine costs one frame's worth of patience and then falls
 *    back. Highlighting has a bounded worker pool because a transcript can compose
 *    dozens of blocks at once; a mermaid fence is one block, and paying the
 *    bookkeeping for it would be cost without benefit.
 *
 * Deliberately absent, like its sibling: no timer, no polling, no work until a fence
 * asks. **Nothing here is on the engine's startup path** — this object is only reached
 * from composition, and the guest's `grok-mermaid` is still imported lazily by the
 * first `/mermaid` request. The cold-start cost of that import is absorbed by the
 * caller's bounded retry (`rememberPiMermaidArt`), not by warming the engine up: a
 * warm-up would run an ESM import plus a layout engine's module body during engine
 * start, competing for exactly the CPU and IO that startup latency is made of, and
 * would be paid by every session including the ones that never draw a diagram.
 */
internal object PiNodeMermaidRenderer {

    fun render(source: String): PiMermaidReply = try {
        PiNodeCodeHighlighter.attached()?.mermaid(source) ?: PiMermaidReply.Unavailable
    } catch (error: Throwable) {
        // A `Throwable` here would otherwise surface inside composition; see the
        // class contract above.
        PiMermaidReply.Unavailable
    }
}
