package app.pi.highlight

import app.pi.ui.render.PiMermaidArt

/**
 * The mermaid side of the guest seam: pi's own `grok-mermaid`, asked over the same
 * loopback service as the highlighter.
 *
 * Contract, identical in shape to [PiNodeCodeHighlighter]'s:
 *
 *  - **it never throws.** Every failure — engine not running, no token, a timeout,
 *    a malformed reply, a source `grok-mermaid` refuses to draw — is `null`, which
 *    the caller renders as the fence's own source. That is not a compromise: pi
 *    does the same thing whenever `render()` returns `null`
 *    (`components/mermaid.ts:75-76`), so a diagram we cannot draw reads exactly
 *    like a diagram pi cannot draw;
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
 * Deliberately absent, like its sibling: no timer, no polling, no warm-up. The
 * renderer's module is not even located until the first fence asks for one.
 */
internal object PiNodeMermaidRenderer {

    fun render(source: String): PiMermaidArt? = try {
        PiNodeCodeHighlighter.attached()?.mermaid(source)
    } catch (error: Throwable) {
        // A `Throwable` here would otherwise surface inside composition; see the
        // class contract above.
        null
    }
}
