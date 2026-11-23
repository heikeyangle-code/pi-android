package app.pi.ui.render.rich.svg

import android.webkit.WebView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared lazy one-time WebView bootstrap for rich renderers (Mermaid / MathJax / Animated SVG).
 *
 * `WebView.enableSlowWholeDocumentDraw()` ONLY takes effect if invoked BEFORE the very first
 * WebView instance is created in the process. Calling it here (instead of `Application.onCreate`)
 * preserves zero WebView overhead on cold startup while guaranteeing Chromium renders full
 * offscreen Mermaid stages even if MathJax or animated SVG creates the first WebView.
 */
internal object PiWebViewBootstrap {
    private val wholeDocEnabled = AtomicBoolean(false)

    fun ensureWholeDocumentDraw() {
        if (wholeDocEnabled.compareAndSet(false, true)) {
            runCatching { WebView.enableSlowWholeDocumentDraw() }
        }
    }
}
