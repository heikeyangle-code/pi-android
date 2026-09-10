package app.pi.rpc

/**
 * ANSI SGR parser.
 *
 * pi's `theme.fg()` / `bg()` emit raw SGR sequences, and so does every tool that
 * colourises its output (`npm`, `git`, `pytest`, `ls --color`). pi's HTML
 * exporter converts them with `ansi-to-html.ts`; the TUI lets the terminal do it.
 * A GUI has to do it itself, or tool output arrives as garbage interspersed with
 * escape codes.
 *
 * Deliberately narrower than a terminal: only SGR (`ESC [ … m`) is interpreted,
 * because that is the only sequence pi and its tools emit into captured output.
 * Cursor movement, screen clearing and other control sequences are *dropped*
 * rather than painted — a captured stream is not a screen.
 *
 * Supported, matching `ansi-to-html.ts`:
 *  - 16 colours (30–37 / 40–47) and their bright variants (90–97 / 100–107)
 *  - 256-colour palette (`38;5;N` / `48;5;N`)
 *  - true colour (`38;2;R;G;B` / `48;2;R;G;B`)
 *  - bold (1), dim (2), italic (3), underline (4), and reset (0)
 */
object Ansi {

    /** One run of text sharing a single style. */
    data class Span(
        val text: String,
        /** Packed 0xRRGGBB, or null for the surface's default foreground. */
        val foreground: Int? = null,
        val background: Int? = null,
        val bold: Boolean = false,
        val dim: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
    )

    /** True when the text contains anything this parser would act on. */
    fun containsEscapes(text: String): Boolean = text.indexOf(ESC) >= 0

    /**
     * Strip every escape sequence, leaving plain text.
     *
     * Used where colour cannot be honoured — a one-line summary, a notification,
     * a search index — so those never show raw bytes.
     */
    fun strip(text: String): String {
        if (!containsEscapes(text)) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == ESC) {
                i = skipEscape(text, i)
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /**
     * Parse into styled runs.
     *
     * Returns an empty list for empty input, and a single default-styled span for
     * text with no escapes, so callers can treat both cases uniformly.
     */
    fun parse(text: String): List<Span> {
        if (text.isEmpty()) return emptyList()
        if (!containsEscapes(text)) return listOf(Span(text))

        val spans = ArrayList<Span>(8)
        val buffer = StringBuilder()
        var fg: Int? = null
        var bg: Int? = null
        var bold = false
        var dim = false
        var italic = false
        var underline = false


        fun flush() {
            if (buffer.isEmpty()) return
            spans += Span(
                text = buffer.toString(),
                foreground = fg,
                background = bg,
                bold = bold,
                dim = dim,
                italic = italic,
                underline = underline,
            )
            buffer.setLength(0)
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != ESC) {
                buffer.append(c)
                i++
                continue
            }
            val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
            if (next != '[') {
                // Not a control sequence we understand; drop the ESC, keep going.
                i++
                continue
            }
            // CSI: parameters, then a final byte in 0x40..0x7E.
            var j = i + 2
            while (j < text.length && text[j] !in '@'..'~') j++
            if (j >= text.length) {
                // Unterminated sequence: a truncated stream. Drop the rest rather
                // than print half an escape.
                break
            }
            val finalByte = text[j]
            val params = text.substring(i + 2, j)
            if (finalByte == 'm') {
                flush()

                val codes = params.split(';').mapNotNull { it.toIntOrNull() }
                val (nfg, nbg) = applySgr(
                    codes = if (params.isEmpty()) listOf(0) else codes,
                    fg = fg,
                    bg = bg,
                    setBold = { bold = it },
                    setDim = { dim = it },
                    setItalic = { italic = it },
                    setUnderline = { underline = it },
                )
                fg = nfg
                bg = nbg
            }
            // Every other final byte is a control we intentionally ignore.
            i = j + 1
        }
        flush()

        // Input with escapes but no visible text (e.g. a bare clear-screen) must
        // yield nothing — falling back to the raw string would print the escape.
        return spans
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Apply one SGR parameter list. Colour parameters consume the following
     * parameters (`38;5;N`, `38;2;R;G;B`), so this walks rather than maps.
     */
    private fun applySgr(
        codes: List<Int>,
        fg: Int?,
        bg: Int?,
        setBold: (Boolean) -> Unit,
        setDim: (Boolean) -> Unit,
        setItalic: (Boolean) -> Unit,
        setUnderline: (Boolean) -> Unit,
    ): Pair<Int?, Int?> {
        var outFg = fg
        var outBg = bg
        var k = 0
        while (k < codes.size) {
            when (val code = codes[k]) {
                0 -> {
                    outFg = null; outBg = null
                    setBold(false); setDim(false); setItalic(false); setUnderline(false)
                }
                1 -> setBold(true)
                2 -> setDim(true)
                3 -> setItalic(true)
                4 -> setUnderline(true)
                22 -> { setBold(false); setDim(false) }
                23 -> setItalic(false)
                24 -> setUnderline(false)
                39 -> outFg = null
                49 -> outBg = null
                in 30..37 -> outFg = BASIC_16[code - 30]
                in 90..97 -> outFg = BASIC_16[code - 90 + 8]
                in 40..47 -> outBg = BASIC_16[code - 40]
                in 100..107 -> outBg = BASIC_16[code - 100 + 8]
                38, 48 -> {
                    val isFg = code == 38
                    when (codes.getOrNull(k + 1)) {
                        5 -> {
                            val index = codes.getOrNull(k + 2)
                            if (index != null) {
                                val rgb = xterm256(index)
                                if (isFg) outFg = rgb else outBg = rgb
                            }
                            k += 2
                        }
                        2 -> {
                            val r = codes.getOrNull(k + 2)
                            val g = codes.getOrNull(k + 3)
                            val b = codes.getOrNull(k + 4)
                            if (r != null && g != null && b != null) {
                                val rgb = pack(r, g, b)
                                if (isFg) outFg = rgb else outBg = rgb
                            }
                            k += 4
                        }
                    }
                }
            }
            k++
        }
        return outFg to outBg
    }

    private fun pack(r: Int, g: Int, b: Int): Int =
        (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** The xterm 256-colour table: 16 system colours, a 6×6×6 cube, then greys. */
    private fun xterm256(index: Int): Int {
        val i = index.coerceIn(0, 255)
        if (i < 16) return BASIC_16[i]
        if (i < 232) {
            val n = i - 16
            val r = n / 36
            val g = (n % 36) / 6
            val b = n % 6
            // The cube's channel values are 0,95,135,175,215,255 — not 0..255/5.
            fun channel(v: Int) = if (v == 0) 0 else 55 + v * 40
            return pack(channel(r), channel(g), channel(b))
        }
        val level = 8 + (i - 232) * 10
        return pack(level, level, level)
    }

    private fun skipEscape(text: String, start: Int): Int {
        val next = if (start + 1 < text.length) text[start + 1] else return start + 1
        if (next != '[') return start + 1
        var j = start + 2
        while (j < text.length && text[j] !in '@'..'~') j++
        return if (j < text.length) j + 1 else text.length
    }

    private const val ESC = '\u001b'

    /** Standard ANSI palette (0–15), matching `ansi-to-html.ts`. */
    private val BASIC_16 = intArrayOf(
        0x000000, // 0 black
        0x800000, // 1 red
        0x008000, // 2 green
        0x808000, // 3 yellow
        0x000080, // 4 blue
        0x800080, // 5 magenta
        0x008080, // 6 cyan
        0xC0C0C0, // 7 white
        0x808080, // 8 bright black
        0xFF0000, // 9 bright red
        0x00FF00, // 10 bright green
        0xFFFF00, // 11 bright yellow
        0x0000FF, // 12 bright blue
        0xFF00FF, // 13 bright magenta
        0x00FFFF, // 14 bright cyan
        0xFFFFFF, // 15 bright white
    )
}
