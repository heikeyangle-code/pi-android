package app.pi.terminal

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

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

    private val decoders: ThreadLocal<CharsetDecoder> = ThreadLocal.withInitial {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
    }

    /**
     * Decode [length] bytes of [bytes], carrying an incomplete trailing sequence
     * over to the next call.
     *
     * `endOfInput` must be true on the final call so a dangling partial sequence
     * becomes a replacement character instead of being buffered forever.
     */
    fun decode(bytes: ByteArray, length: Int, endOfInput: Boolean = false): String {
        if (length <= 0 && !endOfInput) return ""
        val decoder = decoders.get()!!
        val out = CharBuffer.allocate(length * 2 + 8)
        val result = decoder.decode(ByteBuffer.wrap(bytes, 0, length.coerceAtLeast(0)), out, endOfInput)
        if (result.isError) decoder.reset()
        out.flip()
        return out.toString()
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
                        payload.setLength(0)
                    }
                    char == '_' -> {
                        state = APC
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
                    ESC -> state = STRING_ESCAPE
                    else -> payload.append(char)
                }

                APC -> when (char) {
                    BEL -> {
                        state = GROUND
                        out += Input.Apc(payload.toString())
                    }
                    ESC -> state = STRING_ESCAPE
                    else -> payload.append(char)
                }

                STRING -> when (char) {
                    BEL -> {
                        state = GROUND
                        out += Input.IgnoredString(stringIntroducer, payload.toString())
                    }
                    ESC -> state = STRING_ESCAPE
                    else -> payload.append(char)
                }

                STRING_ESCAPE -> when (char) {
                    '\\' -> {
                        state = GROUND
                        when (stringIntroducer) {
                            ']' -> out += Input.Osc(payload.toString())
                            '_' -> out += Input.Apc(payload.toString())
                            else -> out += Input.IgnoredString(stringIntroducer, payload.toString())
                        }
                    }
                    // Not a proper string terminator: go back to collecting.
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
