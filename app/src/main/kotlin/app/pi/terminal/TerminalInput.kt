package app.pi.terminal

/** One decoded terminal input event. */
internal sealed interface Input {

    /** Visible characters, already decoded from UTF-8. */
    class Print(val text: String) : Input

    /** A C0 control character (BEL, BS, HT, LF, CR, …). */
    class Control(val code: Int) : Input

    /** A two-character `ESC x` sequence. */
    class Escape(val final: Char) : Input

    /** `ESC [ params final`, with the private marker and intermediate kept. */
    class Csi(
        val params: List<Int>,
        val privateMarker: Char,
        val intermediate: Char,
        val final: Char,
    ) : Input

    /** `ESC ] payload BEL|ST`. */
    class Osc(val payload: String) : Input

    /**
     * `ESC _ payload BEL|ST` — the Application Program Command.
     *
     * pi uses this for `CURSOR_MARKER` (`ESC _ pi:c BEL`), which is how a TUI that
     * draws its own cursor tells the terminal where the hardware cursor belongs.
     */
    class Apc(val payload: String) : Input

    /** `ESC P … ST`, `ESC X`, `ESC ^`: consumed, never rendered. */
    class IgnoredString(val introducer: Char, val payload: String) : Input
}

/**
 * Turns a PTY byte stream into [Input] events.
 *
 * Two jobs, and they are separate on purpose:
 *
 *  1. **UTF-8 decoding.** The reader hands us whatever a `read()` returned, which
 *     will happily split a multi-byte character in half. Decoding each chunk with
 *     `String(bytes, UTF_8)` is the classic way to get replacement characters in
 *     the middle of CJK output; a stateful [CharsetDecoder] carries the partial
 *     sequence into the next chunk instead.
 *  2. **Escape parsing.** Terminal input is a byte-oriented language, and a
 *     sequence can be split across reads, so this is a small state machine whose
 *     state lives in [Scanner] — one scanner per emulator, for the whole session.
 *
 * It is `internal` and pure, so the parser is unit-testable by feeding strings.
 * The supported subset is documented on [TerminalEmulator].
 */
internal object TerminalInput {

    /**
     * Incremental UTF-8 decoding with an explicit carry-over.
     *
     * A `read()` returns whatever is available, which will happily split a
     * multi-byte character: the reader can get `E4` and then `B8 AD` in two
     * chunks. Decoding each chunk on its own is the classic way to print `??`
     * where CJK should be, so a sequence that is incomplete at the end of a chunk
     * is *held* until the rest arrives.
     *
     * This is written out rather than delegated to a `CharsetDecoder` because the
     * decoder's `decode(in, out, endOfInput)` was observed in this project to
     * consume an incomplete trailing sequence and report it as malformed on the
     * next call — i.e. it did not carry over — and an explicit buffer is both
     * easier to reason about and directly testable. The ranges on the lead byte
     * reject overlong forms and surrogates; an overlong sequence that still passes
     * the range check (e.g. `E0 80 80`) decodes to U+0000 rather than being
     * flagged, which is noted rather than pretended away.
     *
     * One instance per stream: the carry-over *is* the state.
     */
    class Utf8 {
        private val pending = ByteArray(4)
        private var pendingLength = 0
        private var expected = 0

        fun decode(bytes: ByteArray, length: Int, endOfInput: Boolean = false): String {
            if (length <= 0 && pendingLength == 0) return ""
            val out = StringBuilder(length)
            var i = 0
            while (i < length) {
                if (pendingLength == 0) {
                    val lead = bytes[i].toInt() and 0xFF
                    when {
                        lead < 0x80 -> {
                            out.append(lead.toChar())
                            i++
                            continue
                        }
                        lead in 0xC2..0xDF -> expected = 2
                        lead in 0xE0..0xEF -> expected = 3
                        lead in 0xF0..0xF4 -> expected = 4
                        else -> {
                            out.append(REPLACEMENT)
                            i++
                            continue
                        }
                    }
                    pending[0] = bytes[i]
                    pendingLength = 1
                    i++
                }
                while (i < length && pendingLength < expected) {
                    val next = bytes[i].toInt() and 0xFF
                    if (next !in 0x80..0xBF) break
                    pending[pendingLength++] = bytes[i]
                    i++
                }
                if (pendingLength == expected) {
                    out.append(String(pending, 0, expected, Charsets.UTF_8))
                    pendingLength = 0
                } else if (i < length) {
                    // The next byte is not a continuation byte, so this sequence
                    // can never complete: replace it and reprocess that byte.
                    out.append(REPLACEMENT)
                    pendingLength = 0
                }
                // Otherwise the sequence is merely incomplete: hold it.
            }
            if (endOfInput && pendingLength > 0) {
                out.append(REPLACEMENT)
                pendingLength = 0
            }
            return out.toString()
        }

        private companion object {
            const val REPLACEMENT = '\uFFFD'
        }
    }

    /**
     * A stateful escape-sequence parser. One instance per emulator: the state has
     * to survive between reads, because a read boundary can fall anywhere.
     */
    class Scanner {

        private var state = GROUND
        private val params = ArrayList<Int>(8)
        private var privateMarker = ' '
        private var intermediate = ' '
        private val payload = StringBuilder(64)
        private val printBuffer = StringBuilder(64)
        private var stringIntroducer: Char = ' '
        private var skipCharSetDesignation = false
        /** True when the ESC we are looking at continues a string, not input. */
        private var stringOpen = false

        /** UTF-8 state for this stream; see [Utf8]. */
        private val utf8 = Utf8()

        /** Decode raw bytes for this scanner, carrying partial characters over. */
        fun decode(bytes: ByteArray, length: Int, endOfInput: Boolean = false): String =
            utf8.decode(bytes, length, endOfInput)

        /**
         * @param flush true at end of stream, so a truncated sequence is dropped
         *        rather than left half-parsed.
         */
        fun feed(text: String, flush: Boolean = false): List<Input> {
            val out = ArrayList<Input>(16)
            for (char in text) step(char, out)
            flushPrint(out)
            if (flush) {
                state = GROUND
                params.clear()
                payload.setLength(0)
            }
            return out
        }

        private fun flushPrint(out: MutableList<Input>) {
            if (printBuffer.isEmpty()) return
            out += Input.Print(printBuffer.toString())
            printBuffer.setLength(0)
        }

        private fun step(char: Char, out: MutableList<Input>) {
            when (state) {
                GROUND -> when {
                    char == ESC -> {
                        flushPrint(out)
                        state = ESCAPE
                    }
                    char.code < 0x20 || char.code == 0x7F -> {
                        flushPrint(out)
                        out += Input.Control(char.code)
                    }
                    else -> printBuffer.append(char)
                }

                ESCAPE -> when {
                    // `ESC \` terminates a string that was introduced earlier.
                    // pi's own output uses BEL, but `app.pi.terminal.TerminalEmulator`
                    // must survive both forms: real programs (and tmux) send ST.
                    stringOpen && char == '\\' -> {
                        stringOpen = false
                        state = GROUND
                        when (stringIntroducer) {
                            ']' -> out += Input.Osc(payload.toString())
                            '_' -> out += Input.Apc(payload.toString())
                            else -> out += Input.IgnoredString(stringIntroducer, payload.toString())
                        }
                    }
                    // `ESC ( B`: a character-set designation. Swallow the final.
                    skipCharSetDesignation -> {
                        skipCharSetDesignation = false
                        state = GROUND
                    }
                    char == '[' -> {
                        state = CSI
                        params.clear()
                        privateMarker = ' '
                        intermediate = ' '
                    }
                    char == ']' -> {
                        state = OSC
                        stringIntroducer = ']'
                        payload.setLength(0)
                    }
                    char == '_' -> {
                        state = APC
                        stringIntroducer = '_'
                        payload.setLength(0)
                    }
                    char == 'P' || char == 'X' || char == '^' -> {
                        state = STRING
                        stringIntroducer = char
                        payload.setLength(0)
                    }
                    char == '(' || char == ')' || char == '*' || char == '+' ||
                        char == '-' || char == '.' || char == '/' -> {
                        skipCharSetDesignation = true
                    }
                    else -> {
                        state = GROUND
                        out += Input.Escape(char)
                    }
                }

                CSI -> when {
                    char in '0'..'9' -> {
                        if (params.isEmpty()) params.add(0)
                        val next = params[params.size - 1] * 10 + (char - '0')
                        params[params.size - 1] = next.coerceAtMost(1_000_000)
                    }
                    char == ';' -> params.add(0)
                    char == ':' -> {
                        // Colon sub-parameters (e.g. `38:2::R:G:B`) are not
                        // interpreted; the `:` is treated as a separator so the
                        // sequence is still consumed as one CSI.
                        params.add(0)
                    }
                    char in '<'..'?' -> privateMarker = char
                    char in ' '..'/' -> intermediate = char
                    char in '@'..'~' -> {
                        state = GROUND
                        out += Input.Csi(ArrayList(params), privateMarker, intermediate, char)
                    }
                    else -> state = GROUND
                }

                OSC -> when (char) {
                    BEL -> {
                        state = GROUND
                        out += Input.Osc(payload.toString())
                    }
                    ESC -> {
                        stringOpen = true
                        state = STRING_ESCAPE
                    }
                    else -> payload.append(char)
                }

                APC -> when (char) {
                    BEL -> {
                        state = GROUND
                        out += Input.Apc(payload.toString())
                    }
                    ESC -> {
                        stringOpen = true
                        state = STRING_ESCAPE
                    }
                    else -> payload.append(char)
                }

                STRING -> when (char) {
                    BEL -> {
                        state = GROUND
                        out += Input.IgnoredString(stringIntroducer, payload.toString())
                    }
                    ESC -> {
                        stringOpen = true
                        state = STRING_ESCAPE
                    }
                    else -> payload.append(char)
                }

                STRING_ESCAPE -> when (char) {
                    '\\' -> {
                        // An ESC immediately followed by `\` is the string
                        // terminator (ST), whichever string state we came from.
                        stringOpen = false
                        state = GROUND
                        when (stringIntroducer) {
                            ']' -> out += Input.Osc(payload.toString())
                            '_' -> out += Input.Apc(payload.toString())
                            else -> out += Input.IgnoredString(stringIntroducer, payload.toString())
                        }
                    }
                    // Not a proper string terminator: go back to collecting. A
                    // lone ESC inside a string is payload in principle; it is
                    // dropped rather than kept, which matches what terminals do.
                    else -> {
                        state = when (stringIntroducer) {
                            ']' -> OSC
                            '_' -> APC
                            else -> STRING
                        }
                        if (char != ESC) payload.append(char)
                    }
                }
            }
        }

        private companion object {
            const val GROUND = 0
            const val ESCAPE = 1
            const val CSI = 2
            const val OSC = 3
            const val APC = 4
            const val STRING = 5
            const val STRING_ESCAPE = 6

            const val ESC = '\u001b'
            const val BEL = '\u0007'
        }
    }
}
