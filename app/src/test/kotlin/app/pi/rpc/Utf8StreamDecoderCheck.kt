package app.pi.rpc

// A bare-JVM harness for the engine's incremental UTF-8 decoding
// (`rpc/src/main/kotlin/app/pi/rpc/Utf8StreamDecoder.kt`), compiled and run by
// `tools/run-app-pure-checks.sh`.
//
// Why this harness and not a device: the defect is *arithmetic on bytes*, so it is
// fully testable here, and the failure mode it prevents is invisible in any log — a
// `\uFFFD` inside a JSON string is legal JSON, so the record parses and the corruption
// reaches the transcript as a character the model never wrote. The measured baseline
// is stated in the class's KDoc: `new String(bytes, 0, n, UTF_8)` turns "中文" split
// after byte 1 into "\uFFFD\uFFFD\uFFFD文".
//
// The checks split one byte stream at every possible offset (and into three parts),
// which is what a pipe does to a 16 KiB read buffer for real: the boundaries are
// arbitrary, and for CJK text most of them fall inside a character.
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

/** Decode one stream in the given chunk sizes, in order, then flush. */
private fun decodeSizes(text: String, vararg sizes: Int): String {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val decoder = Utf8StreamDecoder()
    val out = StringBuilder()
    var index = 0
    for (size in sizes) {
        if (index >= bytes.size) break
        val take = minOf(size, bytes.size - index)
        out.append(decoder.decode(bytes.copyOfRange(index, index + take)))
        index += take
    }
    if (index < bytes.size) out.append(decoder.decode(bytes.copyOfRange(index, bytes.size)))
    out.append(decoder.flush())
    return out.toString()
}

fun main() {
    // ------------------------------------------------------------------ the baseline
    //
    // The defect itself, so this harness fails loudly if Java's behaviour (or the
    // premise of the whole file) ever changes.
    run {
        val text = "中文"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val naive = String(bytes, 0, 1, Charsets.UTF_8) + String(bytes, 1, bytes.size - 1, Charsets.UTF_8)
        check("B1 the naive split corrupts a character", naive == text, false)
        check("B2 and it is corruption, not a dropped byte", naive.length, 4)
    }

    // ------------------------------------------------------- every single split point
    run {
        val text = "中文标题：流式输出正常吗？emoji 🚀 和 ASCII ok"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val bad = mutableListOf<Int>()
        for (split in 1 until bytes.size) {
            if (decodeSizes(text, split, bytes.size - split) != text) bad += split
        }
        check("C1 every two-part split decodes exactly", bad, emptyList<Int>())

        val bad3 = mutableListOf<Int>()
        for (a in 1 until bytes.size - 1) {
            for (b in a + 1 until bytes.size) {
                if (decodeSizes(text, a, b - a, bytes.size - b) != text) bad3 += (a * 1000 + b)
            }
        }
        check("C2 every three-part split decodes exactly", bad3, emptyList<Int>())
    }

    // --------------------------------------------------------------- one byte at a time
    run {
        val text = "逐字节切分也不能坏：一个字三字节、一个 emoji 四字节"
        val decoder = Utf8StreamDecoder()
        val out = StringBuilder()
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            out.append(decoder.decode(byteArrayOf(byte)))
        }
        out.append(decoder.flush())
        check("D1 byte-at-a-time decoding is exact", out.toString(), text)
        check("D2 and nothing was carried at the end", decoder.carriedBytes, 0)
    }

    // ------------------------------------------------- a real read pattern, record by record
    run {
        // One JSONL record with Chinese text, cut at the arbitrary offsets a pipe read
        // produces, with alternating chunk sizes.
        val record = """{"type":"message_update","delta":{"type":"text_delta","delta":"这是一段中文流式输出 🚀 with ASCII"}}"""
        val stream = (record + "\n" + record + "\n").toByteArray(Charsets.UTF_8)
        val framer = JsonlFramer()
        val decoder = Utf8StreamDecoder()
        val records = mutableListOf<String>()
        var at = 0
        var chunk = 7
        while (at < stream.size) {
            val take = minOf(chunk, stream.size - at)
            records += framer.feed(decoder.decode(stream.copyOfRange(at, at + take)))
            at += take
            chunk = if (chunk == 7) 13 else 7
        }
        records += framer.feed(decoder.flush())
        records += framer.flush()
        check("E1 both records survive the chopping", records.size, 2)
        check("E2 with the exact JSON", records.firstOrNull(), record)
        check("E3 and no replacement characters", records.any { it.contains('\uFFFD') }, false)
    }

    // ------------------------------------------------------------ malformed and truncated
    run {
        // A lead byte, then a byte that cannot continue it, then valid text: `REPLACE`
        // says one replacement character and keep going - never a hole in the stream.
        val decoder = Utf8StreamDecoder()
        val out = StringBuilder()
        out.append(decoder.decode(byteArrayOf(0xE4.toByte())))
        out.append(decoder.decode(byteArrayOf(0x41))) // 'A'
        out.append(decoder.decode("中".toByteArray(Charsets.UTF_8)))
        check("F1 a broken sequence becomes a replacement and the stream continues", out.toString(), "\uFFFDA中")
        check("F2 and nothing is carried afterwards", decoder.carriedBytes, 0)

        // Truncated at EOF: only `flush` may replace, and it must.
        val truncated = Utf8StreamDecoder()
        val head = "中".toByteArray(Charsets.UTF_8)
        check("F3 a partial sequence is carried, not replaced", truncated.decode(head, 2), "")
        check("F4 and it is visibly carried", truncated.carriedBytes, 2)
        check("F5 flush reports the truncation once", truncated.flush(), "\uFFFD")
        check("F6 and the decoder stays usable", truncated.decode("中".toByteArray(Charsets.UTF_8)), "中")
    }

    // -------------------------------------------------------------------- the edge cases
    run {
        val decoder = Utf8StreamDecoder()
        check("G1 an empty read is empty", decoder.decode(ByteArray(0)), "")
        check("G2 a shorter length reads only that much", decoder.decode("abc".toByteArray(), 2), "ab")
        check("G3 flush on a clean stream adds nothing", Utf8StreamDecoder().flush(), "")
        var threw = false
        try {
            Utf8StreamDecoder().decode(ByteArray(1), 5)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        check("G4 a length past the array is rejected", threw, true)
    }

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
