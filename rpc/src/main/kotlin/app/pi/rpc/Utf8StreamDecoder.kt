package app.pi.rpc

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CoderResult
import java.nio.charset.CodingErrorAction

/**
 * Incremental UTF-8 decoding for the engine's stdout reads.
 *
 * **Why this exists.** `PiEngineSession.readLoop` reads pi's stdout into a 16 KiB
 * `ByteArray` and hands each read to [JsonlFramer] as *text*. `String(bytes, 0,
 * read, UTF_8)` — the obvious way to get that text — decodes one read as a complete
 * stream, and it replaces a multi-byte character that straddles two reads with one
 * replacement character **per orphaned byte**:
 *
 * ```
 * "中文" split after byte 1  ->  "\uFFFD\uFFFD\uFFFD文"
 * ```
 *
 * (measured on this machine, `new String` with `UTF_8`, which is documented to
 * "always replace malformed-input and unmappable-character sequences"; the same
 * happens with `InputStreamReader` unless it is told to carry state). A pipe read
 * boundary is arbitrary, so for CJK text — three bytes per character — that is most
 * boundaries: every long Chinese answer loses characters to `\uFFFD`, and the damage
 * is permanent, because the framer has already buffered the decoded characters by the
 * time the rest of the character arrives. Nothing downstream can see it as an error:
 * a `\uFFFD` inside a JSON string is legal JSON.
 *
 * So the decoding has to be **incremental**: the bytes of an incomplete trailing
 * sequence are kept and prepended to the next read, and only [flush] (at EOF) turns a
 * genuinely truncated sequence into a replacement character. (`docs/streaming-review.md`
 * §2.0 is the report; `Utf8StreamDecoderCheck` is the proof.) That is what
 * [java.nio.charset.CharsetDecoder]'s three-argument `decode` is for, and this class
 * is the smallest wrapper around it that the read loop can drive: one call per read,
 * one [flush] at the end.
 *
 * [JsonlFramer]'s contract is unchanged — it still takes decoded `CharSequence` and
 * still splits on `'\n'` only ([JsonlFramer] documents why `U+2028`/`U+2029` must not
 * count as line breaks).
 *
 * Not thread-safe; drive one instance from one reader coroutine, like the framer.
 */
class Utf8StreamDecoder {

    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    /**
     * Bytes of an incomplete trailing sequence, waiting for the rest of their
     * character. At most three: UTF-8's longest sequence is four bytes.
     */
    private var carry: ByteArray = EMPTY

    /** Bytes currently carried into the next [decode] call. For diagnostics/tests. */
    val carriedBytes: Int get() = carry.size

    /**
     * Decode the next [length] bytes of the stream.
     *
     * Anything the decoder cannot use yet (an incomplete trailing sequence) is
     * carried, not replaced: it comes back attached to the following read.
     */
    fun decode(bytes: ByteArray, length: Int = bytes.size): String {
        require(length in 0..bytes.size) { "length $length is not within 0..${bytes.size}" }
        if (length == 0) return ""
        val input = input(bytes, length)
        val out = StringBuilder(length + 4)
        var result: CoderResult
        var chars = CharBuffer.allocate(length + 4)
        do {
            chars.clear()
            result = decoder.decode(input, chars, false)
            chars.flip()
            out.append(chars)
            if (result.isOverflow) chars = CharBuffer.allocate(chars.capacity() * 2)
        } while (result.isOverflow)
        // Whatever the decoder did not consume is an incomplete sequence (a `REPLACE`
        // decoder consumes everything it can), so it belongs to the next read.
        if (input.hasRemaining()) {
            carry = ByteArray(input.remaining()).also { input.get(it) }
        }
        return out.toString()
    }

    /**
     * Finish the stream: a sequence still incomplete here is genuinely truncated
     * input, and this is the only place a replacement character can legitimately
     * appear. The instance is reusable afterwards.
     */
    fun flush(): String {
        val out = StringBuilder(4)
        // The carried bytes never reached the decoder — an incomplete sequence is left
        // in the *input* buffer, which is why [decode] has to keep it — so they are
        // handed over now, with `endOfInput = true`: that flag is exactly what turns
        // "there may be more" into "there is not, so this is malformed".
        val input = ByteBuffer.wrap(carry)
        carry = EMPTY
        var result: CoderResult
        var chars = CharBuffer.allocate(8)
        do {
            chars.clear()
            result = decoder.decode(input, chars, true)
            chars.flip()
            out.append(chars)
            if (result.isOverflow) chars = CharBuffer.allocate(chars.capacity() * 2)
        } while (result.isOverflow)
        chars = CharBuffer.allocate(8)
        do {
            chars.clear()
            result = decoder.flush(chars)
            chars.flip()
            out.append(chars)
            if (result.isOverflow) chars = CharBuffer.allocate(chars.capacity() * 2)
        } while (result.isOverflow)
        decoder.reset()
        return out.toString()
    }

    /** The read plus any carried bytes, without copying in the common (no-carry) case. */
    private fun input(bytes: ByteArray, length: Int): ByteBuffer {
        if (carry.isEmpty()) return ByteBuffer.wrap(bytes, 0, length)
        val combined = ByteArray(carry.size + length)
        carry.copyInto(combined)
        bytes.copyInto(combined, carry.size, 0, length)
        carry = EMPTY
        return ByteBuffer.wrap(combined)
    }

    private companion object {
        private val EMPTY = ByteArray(0)
    }
}
