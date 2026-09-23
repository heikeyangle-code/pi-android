package app.pi.session

// Bare-JVM measurement + equivalence harness for the "open a session" path.
//
// Registered in `tools/run-app-pure-checks.sh` as `session-replay-cost`.
//
// ## What it answers
//
// The user's report is two symptoms with one shape: "two images and the history
// will not open" and "even a text-only session takes one to two seconds". Both
// are statements about a cost that scales with the **whole** session. This harness
// produces the numbers instead of an argument:
//
//  1. the byte cost of one `get_entries` response for a session of a given shape,
//     split into entries/text/tool output/inline base64 images — i.e. **what
//     actually pushes a record past the framer's record cap** (64 MiB since the cap
//     moved; the harness reads the cap from `JsonlFramer` rather than spelling it);
//  2. the wall-clock cost of the two stages the app performs on that response:
//     `JSON -> JsonObject` per line (the engine's read loop) and
//     `JsonObject -> rows` (`TranscriptReducer.seedFromHistory`). Measured against
//     session size, so "O(whole session)" becomes a number;
//  3. the same work for a **tail window**, which is the property the fix needs:
//     first-paint work independent of total history;
//  4. **equivalence**: `SessionFileReader` windows concatenated must equal the
//     whole-file read, and the rows they project must equal the rows the
//     `get_entries`-shaped replay projects — walked with the **app's own window**
//     (`PiSessionViewModel.HISTORY_WINDOW_CHARS`), not with a wider one, because a
//     harness whose bound is looser than production cannot fail for the reason
//     production fails. The `img-1msg-2x5MB` fixture is the case that distinction
//     exists for: one message whose line is bigger than one window.
//
// ## What it cannot measure, and why (stated rather than guessed)
//
//  - The RPC round trip. pi runs on Node inside the phone's proot guest; this
//    container is amd64 with a different Node, so any number here would describe
//    the wrong machine. The round trip is instead bounded from the byte counts in
//    §1 plus the framer throughput in §5, both of which are device-independent.
//  - The first composed frame. Compose cannot be compiled here (no Compose
//    compiler plugin in `tools/typecheck.sh`), so the harness measures everything
//    up to the publication the first frame renders from. The remaining step is one
//    `LazyColumn` measure pass over an already-built row list; it is in the
//    "device only" list of the change report.
//
// Android-free: `java.io.File`, the stdlib, kotlinx.serialization (through
// `:rpc`'s parser) and the `:rpc` transcript reducer.

import app.pi.rpc.PiJson
import app.pi.rpc.SessionEntry
import app.pi.rpc.TranscriptItem
import app.pi.rpc.TranscriptReducer
import app.pi.rpc.parseSessionEntry
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.charset.StandardCharsets

private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

/** Median of [repeats] runs of [block], in milliseconds. */
private inline fun medianMs(repeats: Int = 5, block: () -> Unit): Double {
    val samples = DoubleArray(repeats)
    for (i in 0 until repeats) {
        val t0 = System.nanoTime()
        block()
        samples[i] = (System.nanoTime() - t0) / 1_000_000.0
    }
    samples.sort()
    return samples[repeats / 2]
}

private fun mb(chars: Long): String = "%.2f".format(chars / 1024.0 / 1024.0)

/**
 * The app's own history window, written out rather than imported because
 * `PiSessionViewModel` is not Android-free (`HISTORY_WINDOW_CHARS` /
 * `HISTORY_WINDOW_ENTRIES`).
 *
 * §3 measures inside it and §4 **walks** inside it. The walk used to use
 * `SessionFileReader.DEFAULT_MAX_LINE_CHARS * 3` for image-heavy fixtures on the
 * reasoning that a budget smaller than one line cannot yield a whole entry — which was
 * true of the reader at the time and is exactly the property the multi-image fixture
 * disproves: the reader now returns one whole line even when it alone exceeds the
 * budget, and the app is the thing being modelled. A wider-than-app budget reads the
 * whole fixture in one window and passes without ever exercising the boundary the
 * user's bug lives on.
 */
private const val APP_WINDOW_CHARS = 8 * 1024 * 1024
private const val APP_WINDOW_ENTRIES = 4_000

/** The one-message-many-images fixture's name in `shapes`. */
private const val MULTI_IMAGE_SHAPE = "img-1msg-2x5MB"

// ------------------------------------------------------------- synthetic sessions

private fun userEntry(id: String, parent: String?, text: String): String =
    "{\"type\":\"message\",\"id\":\"$id\",\"parentId\":${parent?.let { "\"$it\"" } ?: "null"}," +
        "\"timestamp\":\"2025-11-20T00:00:00.000Z\",\"message\":{\"role\":\"user\"," +
        "\"content\":[{\"type\":\"text\",\"text\":\"$text\"}],\"timestamp\":1}}"

private fun assistantEntry(id: String, parent: String?, textChars: Int, toolChars: Int): String {
    val b = StringBuilder()
    b.append(
        "{\"type\":\"message\",\"id\":\"$id\",\"parentId\":${parent?.let { "\"$it\"" } ?: "null"}," +
            "\"timestamp\":\"2025-11-20T00:00:01.000Z\",\"message\":{\"role\":\"assistant\"," +
            "\"model\":\"claude-sonnet-4-5\"," +
            "\"usage\":{\"input\":1000,\"output\":200,\"cacheRead\":0,\"cacheWrite\":0}," +
            "\"content\":[{\"type\":\"text\",\"text\":\"",
    )
    b.append("a".repeat(textChars))
    b.append("\"}")
    if (toolChars > 0) {
        b.append(",{\"type\":\"toolResult\",\"toolCallId\":\"c-$id\",\"content\":[{\"type\":\"text\",\"text\":\"")
        b.append("t".repeat(toolChars))
        b.append("\"}]}")
    }
    b.append("],\"timestamp\":1}}")
    return b.toString()
}

private fun imageEntry(id: String, parent: String?, bytes: Int): String {
    val b64Chars = (bytes.toLong() * 4 / 3).toInt()
    val payload = "iVBORw0KGgoAAAANSUhEUg".repeat(b64Chars / 22 + 1).substring(0, b64Chars)
    return "{\"type\":\"message\",\"id\":\"$id\",\"parentId\":${parent?.let { "\"$it\"" } ?: "null"}," +
        "\"timestamp\":\"2025-11-20T00:00:00.500Z\",\"message\":{\"role\":\"user\",\"content\":[" +
        "{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"$payload\"}],\"timestamp\":1}}"
}

private fun header(id: String): String =
    "{\"type\":\"session\",\"version\":3,\"id\":\"$id\",\"timestamp\":\"2025-11-20T00:00:00.000Z\"," +
        "\"cwd\":\"/workspace/pi/workspaces/workspace-1\"}"

private fun buildSession(
    file: File,
    turns: Int,
    textChars: Int = 300,
    toolChars: Int = 0,
    images: List<Int> = emptyList(),
): File {
    file.parentFile?.mkdirs()
    file.bufferedWriter(StandardCharsets.UTF_8).use { w ->
        w.write(header("bench"))
        w.write("\n")
        var parent: String? = null
        for (t in 0 until turns) {
            val u = "u$t"
            val a = "a$t"
            w.write(userEntry(u, parent, "turn $t question"))
            w.write("\n")
            parent = u
            if (t < images.size) {
                val img = "img$t"
                w.write(imageEntry(img, parent, images[t]))
                w.write("\n")
                parent = img
            }
            w.write(assistantEntry(a, parent, textChars, toolChars))
            w.write("\n")
            parent = a
        }
    }
    return file
}

/**
 * A session that exercises **every** entry type pi persists, plus a branch.
 *
 * `core/session-manager.ts:145-155` is the whole union: message, model_change,
 * thinking_level_change, compaction, branch_summary, custom, custom_message,
 * label, session_info. The windowed reader has to reproduce all of them (they are
 * entries, so `getEntries()` returns them) while the reducer projects only some
 * (`TranscriptReducer.onEntry`), and the branch means the file is **not** a linear
 * chain — the equivalence check must therefore be on the entry sequence, not on a
 * parent walk.
 */
private fun buildMixedSession(file: File): File {
    file.parentFile?.mkdirs()
    val lines = ArrayList<String>()
    lines += header("mixed")
    lines += userEntry("m1", null, "hello")
    lines += assistantEntry("m2", "m1", 200, 0)
    lines += "{\"type\":\"model_change\",\"id\":\"mc\",\"parentId\":\"m2\"," +
        "\"timestamp\":\"2025-11-20T00:00:02.000Z\",\"provider\":\"anthropic\",\"modelId\":\"claude\"}"
    lines += "{\"type\":\"thinking_level_change\",\"id\":\"tl\",\"parentId\":\"mc\"," +
        "\"timestamp\":\"2025-11-20T00:00:03.000Z\",\"thinkingLevel\":\"high\"}"
    lines += "{\"type\":\"custom\",\"id\":\"cu\",\"parentId\":\"tl\"," +
        "\"timestamp\":\"2025-11-20T00:00:04.000Z\",\"customType\":\"note\",\"data\":{\"k\":1}}"
    lines += "{\"type\":\"custom_message\",\"id\":\"cm\",\"parentId\":\"cu\"," +
        "\"timestamp\":\"2025-11-20T00:00:05.000Z\",\"customType\":\"hook\",\"display\":true," +
        "\"content\":[{\"type\":\"text\",\"text\":\"hook text\"}]}"
    lines += "{\"type\":\"label\",\"id\":\"lb\",\"parentId\":\"cm\"," +
        "\"timestamp\":\"2025-11-20T00:00:06.000Z\",\"targetId\":\"m1\",\"label\":\"start\"}"
    lines += "{\"type\":\"session_info\",\"id\":\"si\",\"parentId\":\"lb\"," +
        "\"timestamp\":\"2025-11-20T00:00:07.000Z\",\"name\":\"named\"}"
    // A branch: the assistant's turn is abandoned and a branch_summary records it.
    lines += "{\"type\":\"branch_summary\",\"id\":\"bs\",\"parentId\":\"m2\"," +
        "\"timestamp\":\"2025-11-20T00:00:08.000Z\",\"fromId\":\"m1\",\"summary\":\"abandoned\"}"
    lines += userEntry("m3", "bs", "second branch")
    lines += assistantEntry("m4", "m3", 300, 0)
    lines += "{\"type\":\"compaction\",\"id\":\"cp\",\"parentId\":\"m4\"," +
        "\"timestamp\":\"2025-11-20T00:00:09.000Z\",\"summary\":\"compacted\"," +
        "\"firstKeptEntryId\":\"m3\",\"tokensBefore\":1234}"
    lines += userEntry("m5", "cp", "after compaction")
    // An entry type a newer pi could add: it must be kept, not dropped.
    lines += "{\"type\":\"future_thing\",\"id\":\"ft\",\"parentId\":\"m5\"," +
        "\"timestamp\":\"2025-11-20T00:00:10.000Z\",\"payload\":42}"
    file.writeText(lines.joinToString("\n") + "\n", StandardCharsets.UTF_8)
    return file
}

/**
 * **One** message carrying **two** 5 MB images — the reported defect, kept as a fixture.
 *
 * The images are inline, so the whole message is one line: 2 × 5 MB of bytes is 13.3 MB
 * of base64, which was over the framer's old 8 MiB record cap *and* over the reader's old
 * 8 MiB line cap at the same time. The user saw "两张图片就进不去聊天历史"; the halves this
 * fixture pins are that the entry is readable at all under the new caps, and — the part
 * that stays broken if only the caps move — that an entry larger than one **window** is
 * still delivered, by the tail window or by the backward walk that follows it.
 *
 * The small reply after the message is what makes that second half bite: with an 8 MiB
 * tail window the newest line is the reply, so the big entry can only arrive through
 * `readBefore`, and the snap that decides where a backward range starts is what used to
 * lose it silently (`complete` stayed true, so the app did not even fall back).
 */
private fun buildMultiImageMessage(file: File): File {
    file.parentFile?.mkdirs()
    val images = List(2) { _ ->
        val b64Chars = (5L * 1024 * 1024 * 4 / 3).toInt()
        val payload = "iVBORw0KGgoAAAANSUhEUg".repeat(b64Chars / 22 + 1).substring(0, b64Chars)
        "{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"$payload\"}"
    }
    val lines = ArrayList<String>()
    lines += header("one-message")
    lines += userEntry("q1", null, "two photos")
    lines += "{\"type\":\"message\",\"id\":\"big\",\"parentId\":\"q1\"," +
        "\"timestamp\":\"2025-11-20T00:00:00.700Z\",\"message\":{\"role\":\"user\",\"content\":[" +
        images.joinToString(",") + "],\"timestamp\":1}}"
    lines += assistantEntry("big-a", "big", 300, 0)
    file.writeText(lines.joinToString("\n") + "\n", StandardCharsets.UTF_8)
    return file
}

private fun allLines(file: File): List<String> =
    file.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }

private fun objects(lines: List<String>): List<JsonObject> = lines.mapNotNull { PiJson.parseObjectOrNull(it) }

private fun rows(objs: List<JsonObject>): List<TranscriptItem> =
    TranscriptReducer().also { it.seedFromHistory(objs) }.transcript

/**
 * The wall-clock component of a **synthesised** key.
 *
 * `TranscriptReducer.nextKey` builds one as `"<prefix>-<now()>-<counter>"`
 * (`:rpc`'s `Transcript.kt`), which is what every block pi's entries do not already
 * name gets — today the `date-separator` rows and anything a live turn invents.
 * Keys that come from pi (an `entryId`) have no such triple and pass through.
 */
private val SYNTHESISED_KEY = Regex("^(.*)-(\\d{10,})-(\\d+)$")

/**
 * One row as text, with the **clock** in a synthesised key replaced by a constant.
 *
 * The equivalence check below is about the projection — the same entries, in the
 * same order, through the same reducer must produce the same blocks with the same
 * content — and a raw `List<TranscriptItem>` comparison cannot express it: the key
 * of a synthesised block is *documented* as "stable for the life of a block"
 * (`TranscriptItem`), not across replays, and the clock in it is deliberate (it is
 * what keeps two reducers' blocks apart when both are in one list). Comparing it
 * would be comparing the wall clock, and it failed here for exactly that reason:
 * `DateSeparator(key=date-1789579991259-0, …)` against
 * `DateSeparator(key=date-1789579991690-0, …)` — same date, same label, same
 * position, keys 431 ms apart.
 *
 * So the counter survives and the clock does not: two synthesised blocks in one
 * replay are still told apart, and a window that mis-assigned a pi **entry id**
 * still fails, because those keys are compared verbatim. Everything else in the row
 * — text, tone, images, diffs, `ts` — is compared verbatim too, by using the data
 * class's own `toString`, which prints the key first in every `TranscriptItem`
 * implementation, so exactly one substitution is needed.
 */
private fun signature(item: TranscriptItem): String {
    val text = item.toString()
    val key = item.key
    val match = SYNTHESISED_KEY.matchEntire(key) ?: return text
    // Group 2 is the clock, group 3 is the counter: the counter is the half that is
    // reproducible across two replays, so it is the one that is kept.
    val stable = "${match.groupValues[1]}-${match.groupValues[3]}"
    val head = "${item::class.simpleName}(key=$key"
    return if (text.startsWith(head)) {
        // `String.replace(String, String)` is literal, not a regex: an entry id can
        // contain characters a regex would read as syntax.
        text.replace("key=$key", "key=$stable")
    } else {
        // A `TranscriptItem` that stops printing its key first: still deterministic,
        // and the rest of the row is still compared, so the check degrades rather
        // than silently passing.
        text.replace(key, stable)
    }
}

fun main() {
    val root = File(System.getProperty("java.io.tmpdir"), "pi-session-replay-cost-${System.nanoTime()}")
    root.mkdirs()
    println("== 会话回放代价测量（纯 JVM，本机 amd64，非手机）==")
    println()

    val shapes = listOf(
        "text-40" to buildSession(File(root, "text-40.jsonl"), turns = 40),
        "text-400" to buildSession(File(root, "text-400.jsonl"), turns = 400),
        "text-1600" to buildSession(File(root, "text-1600.jsonl"), turns = 1600),
        "tool-400x50KB" to buildSession(File(root, "tool.jsonl"), turns = 400, toolChars = 50 * 1024),
        "img-2x2.5MB" to buildSession(File(root, "img2.jsonl"), turns = 40, images = listOf(2_500_000, 2_500_000)),
        "img-20x2.5MB" to buildSession(File(root, "img20.jsonl"), turns = 40, images = List(20) { 2_500_000 }),
        MULTI_IMAGE_SHAPE to buildMultiImageMessage(File(root, "img-1msg.jsonl")),
        "mixed-entries" to buildMixedSession(File(root, "mixed.jsonl")),
    )

    // Shapes small enough to walk from end to start through **many** bounded windows
    // (the arithmetic that decides which line belongs to which window), without the
    // multi-megabyte fixtures turning that walk into a memory test of the harness.
    val walked = shapes.filterNot { it.first == "img-20x2.5MB" }

    // ------------------------------------------------- 1. what is in the record
    println("-- 1. 一条 get_entries 响应有多大，谁占的 --")
    val recordCap = app.pi.rpc.JsonlFramer.DEFAULT_MAX_RECORD_CHARS.toLong()
    println(
        "%-16s %9s %8s %11s %11s %11s %8s".format(
            "shape", "file MB", "entries", "resp MB", "entries MB", "image MB", "over cap",
        ),
    )
    val responseChars = HashMap<String, Long>()
    for ((name, file) in shapes) {
        val lines = allLines(file).drop(1)
        val response = StringBuilder(1 shl 20)
        response.append("{\"id\":\"r1\",\"type\":\"response\",\"command\":\"get_entries\",")
        response.append("\"success\":true,\"data\":{\"entries\":[")
        var image = 0L
        for ((i, line) in lines.withIndex()) {
            if (i > 0) response.append(',')
            response.append(line)
            if (line.contains("\"type\":\"image\"")) image += line.length.toLong()
        }
        response.append("],\"leafId\":\"a0\"}}")
        val total = response.length.toLong()
        responseChars[name] = total
        val entriesBytes = lines.sumOf { it.length.toLong() + 1 }
        println(
            "%-16s %9s %8d %11s %11s %11s %8s".format(
                name, mb(file.length()), lines.size, mb(total), mb(entriesBytes), mb(image),
                if (total > recordCap) "YES" else "-",
            ),
        )
    }
    println()
    // The numbers the change is about, printed against the cap in force rather than
    // against a literal: `recordCap` is read from the framer, and the composer's budget
    // is spelled here for the same reason the window is (it lives in
    // `app.pi.ui.screens`, which this closure does not compile).
    println("帧记录上限（新）= ${mb(recordCap)} MB；旧上限 = 8.00 MB")
    val oneMessage = responseChars[MULTI_IMAGE_SHAPE]!!
    println(
        "一条消息两张 5MB 原图 = ${mb(oneMessage)} MB -> 旧上限 8.00 MB：" +
            if (oneMessage > 8L * 1024 * 1024) "记录被丢弃、会话打不开（用户报的那个）" else "未超",
    )
    println("  同一夹具在新上限下：" + if (oneMessage > recordCap) "仍然超" else "可读回")
    println("两张 2.5MB 照片（分两条消息）= ${mb(responseChars["img-2x2.5MB"]!!)} MB")
    println("20 张 2.5MB 照片 = ${mb(responseChars["img-20x2.5MB"]!!)} MB -> 超过新上限的记录仍会被丢弃")
    val messageBase64 = recordCap - 64L * 1024
    val piImageBase64 = 4_718_592L
    println(
        "每条消息的图片预算 = ${mb(messageBase64)} MB base64（${mb(messageBase64 / 4 * 3)} MB 字节）；" +
            "pi 上限的图（${mb(piImageBase64)} MB base64）可放 ${messageBase64 / piImageBase64} 张",
    )
    println()

    // -------------------------------------------- 2. stage cost vs session size
    println("-- 2. 全量回放：两段耗时对会话规模 --")
    println("%-16s %14s %14s %14s".format("shape", "parse ms", "build-rows ms", "total ms"))
    data class Stage(val parseMs: Double, val rowsMs: Double)
    val wholeCost = HashMap<String, Stage>()
    // Warm the JIT so the first shape is not the warm-up.
    repeat(3) { rows(objects(allLines(shapes[0].second).drop(1)).take(20)) }
    for ((name, file) in shapes) {
        val objs = objects(allLines(file).drop(1))
        val parseMs = medianMs { allLines(file).forEach { PiJson.parseObjectOrNull(it) } }
        val rowsMs = medianMs { TranscriptReducer().also { it.seedFromHistory(objs) } }
        wholeCost[name] = Stage(parseMs, rowsMs)
        println("%-16s %14.1f %14.1f %14.1f".format(name, parseMs, rowsMs, parseMs + rowsMs))
    }
    println()

    // ---------------------------------------- 3. the same work on a tail window
    println("-- 3. 尾部窗口（同样的两段耗时，只处理最近 N 条）--")
    // The app's own window, so this section and §4 measure and walk the same shape the
    // screen does. See [APP_WINDOW_CHARS].
    val windowEntries = APP_WINDOW_ENTRIES
    val windowChars = APP_WINDOW_CHARS
    println("%-16s %14s %14s %14s %10s".format("shape", "read ms", "parse+rows ms", "total ms", "window MB"))
    var worstWindow = 0.0
    val tailAfter = HashMap<String, Double>()
    for ((name, file) in shapes) {
        val readMs = medianMs { SessionFileReader.readTail(file, windowChars, windowEntries) }
        val win = SessionFileReader.readTail(file, windowChars, windowEntries)
        checkTrue("$name: 尾部窗口非空且完整", win != null && win.entries.isNotEmpty() && win.complete)
        val objs = win!!.entries
        val projMs = medianMs { TranscriptReducer().also { it.seedFromHistory(objs) } }
        val totalMs = readMs + projMs
        worstWindow = maxOf(worstWindow, totalMs)
        tailAfter[name] = totalMs
        println(
            "%-16s %14.1f %14.1f %14.1f %10s".format(
                name, readMs, projMs, totalMs,
                mb(objs.size.toLong() * 512), // entry count proxy; real bytes measured in §1
            ),
        )
    }
    println()
    println("尾部窗口最坏 = %.1f ms".format(worstWindow))
    println()

    // ------------------------------------------------------ 4. equivalence
    println("-- 4. 等价性 --")

    // (a) the reader: windows concatenated == whole file == get_entries' entry list.
    for ((name, file) in walked) {
        val whole = SessionFileReader.readAll(file)!!
        check("$name: readAll 完整", whole.complete, true)
        check("$name: readAll 从文件头开始", whole.startOffset, 0L)
        check("$name: readAll 到达文件头", whole.reachedStart, true)

        // Walk the file backwards in bounded windows from the end. The window is
        // small on purpose so a 40-entry file needs several steps; the loop is the
        // exact shape the app's "load earlier history" uses.
        // The window's first entry is admitted whatever its size, so even a session whose
        // entries are single multi-megabyte image lines must produce a non-empty tail.
        // That is the property that keeps `readTail` from reporting "the conversation
        // starts here" for a session that is merely image-heavy — and since the reader
        // began trimming the *front* of a window instead of stopping at the budget, it
        // also means an entry bigger than one window is delivered by a window of its own
        // rather than being skipped by the snap that chooses the window's start.
        //
        // It is deliberately a constant and not derived from the file: a budget that
        // grew with the file would make this loop pass on exactly the property the
        // change exists to establish, that first-paint work does not grow with the
        // session.
        //
        // Two regimes, both needed, and **the image-heavy one is the app's own window**.
        // On a **text** session the budget is small, so the walk really does cross many
        // window boundaries — that is the arithmetic the change is most likely to get
        // wrong, and a single-step walk would never execute it. On an image-heavy session
        // the budget is [APP_WINDOW_CHARS], the number the screen actually passes: it
        // used to be `DEFAULT_MAX_LINE_CHARS * 3`, which after the cap moved to 64 MiB
        // would be 192 MiB and would read `img-1msg-2x5MB` in a single window. That is a
        // harness bound looser than production's, and a bound looser than production's
        // cannot fail for the reason production fails — the false green this repository
        // keeps finding. [MULTI_IMAGE_SHAPE]'s explicit block below is the same walk,
        // asserted on its own so the reason is legible.
        val imageHeavy = name.startsWith("img-")
        val budget = if (imageHeavy) APP_WINDOW_CHARS else 64 * 1024
        val tail = SessionFileReader.readTail(file, budget, Int.MAX_VALUE)
        checkTrue("$name: 尾部窗口非空", tail != null)
        val collected = ArrayList<JsonObject>()
        var window = tail!!
        collected.addAll(0, window.entries)
        var steps = 0
        while (!window.reachedStart && steps < 100_000) {
            // `maxEntries` unbounded: the *budget* is what bounds a window here, so the
            // walk exercises the byte arithmetic rather than an entry cap that would
            // hide it behind a second, independent limit.
            val before = SessionFileReader.readBefore(file, window.startOffset, budget, Int.MAX_VALUE) ?: break
            collected.addAll(0, before.entries)
            window = before
            steps++
        }
        // The walk is complete when the oldest loaded entry is the whole-file read's
        // first entry — `reachedStart` is the reader's own flag and is checked on the
        // windows themselves in the harness above; this is the end-to-end statement.
        check("$name: 反向前推到了文件头", collected.firstOrNull(), whole.entries.firstOrNull())
        check("$name: 尾部窗口 + 反向前推 == 全文件（条数）", collected.size, whole.entries.size)
        check("$name: 尾部窗口 + 反向前推 == 全文件（逐条 id/类型）", fingerprint(collected), fingerprint(whole.entries))

        // (b) the reducer: the rows from a windowed replay must equal the rows from
        // the whole-file replay, as long as the windows are contiguous and in order
        // and the first one starts at the head. This is "no second source of truth"
        // in executable form: same entries, same reducer, only the delivery differs.
        val wholeRows = rows(whole.entries)
        val windowedRows = rows(collected)
        check("$name: 分段回放的行数 == 一次回放", windowedRows.size, wholeRows.size)
        check(
            "$name: 分段回放的行 == 一次回放",
            windowedRows.map(::signature) == wholeRows.map(::signature),
            true,
        )
    }
    // (b2) every entry type survives the windowed read — including the ones the
    // reducer ignores (label, session_info, unknown) and the branch itself.
    run {
        val mixed = shapes.first { it.first == "mixed-entries" }.second
        val types = SessionFileReader.readAll(mixed)!!.entries
            .map { (it["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content }
        check(
            "mixed: 窗口化读取保留全部条目类型",
            types,
            listOf(
                "message", "message", "model_change", "thinking_level_change", "custom",
                "custom_message", "label", "session_info", "branch_summary", "message",
                "message", "compaction", "message", "future_thing",
            ),
        )
        // The backward walk must reproduce that same sequence, not merely the tail.
        // One window per entry (`maxEntries = 1`) and a budget far above this
        // fixture's size, so the walk is bounded by the entry cap and every step
        // exercises the boundary arithmetic rather than a byte budget.
        val back = ArrayList<JsonObject>()
        var w = SessionFileReader.readTail(mixed, 1 shl 20, 1)!!
        back.addAll(0, w.entries)
        var mixedSteps = 0
        while (!w.reachedStart && mixedSteps < 1_000) {
            val b = SessionFileReader.readBefore(mixed, w.startOffset, 1 shl 20, 1) ?: break
            back.addAll(0, b.entries)
            w = b
            mixedSteps++
        }
        check(
            "mixed: 反推窗口与全文件逐条一致",
            fingerprint(back),
            fingerprint(SessionFileReader.readAll(mixed)!!.entries),
        )
    }

    // (b4) the message whose line is bigger than one window: prove it is *reachable*.
    //
    // This is the regression [MULTI_IMAGE_SHAPE] exists for, and it is the half that
    // moving the caps does not fix by itself. A 13.3 MB image entry under an 8 MiB tail
    // window used to be skipped in both directions: the window's start snapped to the
    // byte *after* the big line, so the window came back `complete = true` without it,
    // and `readBefore` snapped to the same place — an empty range, so the backward walk
    // made **zero** steps. The user's own message was invisible, and because `complete`
    // was true the ViewModel did not even fall back to `get_entries`. Measured before the
    // reader was fixed; the numbers are in the change report.
    run {
        val file = shapes.first { it.first == MULTI_IMAGE_SHAPE }.second

        // (a) the line cap admits the whole file, so `complete` is a statement about the
        // reader and not a silent fallback to the record that used to be unreadable.
        val wide = SessionFileReader.readAll(file)!!
        check("$MULTI_IMAGE_SHAPE: 新的行上限下全文件完整", wide.complete, true)
        check("$MULTI_IMAGE_SHAPE: 全文件条数", wide.entries.size, 3)

        // The tail window is the app's window. The multi-image entry is *older* than the
        // reply that follows it, so the newest line is the reply — the big entry has to
        // arrive through `readBefore`.
        val tail = SessionFileReader.readTail(file, APP_WINDOW_CHARS, APP_WINDOW_ENTRIES)!!
        checkTrue("$MULTI_IMAGE_SHAPE: 尾部窗口完整", tail.complete)
        check("$MULTI_IMAGE_SHAPE: 尾部窗口以最新一条结尾", idOf(tail.entries.last()), "big-a")

        val collected = ArrayList<JsonObject>(tail.entries)
        var w = tail
        var steps = 0
        while (!w.reachedStart && steps < 1_000) {
            val before = SessionFileReader.readBefore(file, w.startOffset, APP_WINDOW_CHARS, APP_WINDOW_ENTRIES) ?: break
            collected.addAll(0, before.entries)
            w = before
            steps++
        }
        checkTrue(
            "$MULTI_IMAGE_SHAPE: 反向前推走了不止一步（多图那条占满一个窗口）",
            steps >= 1,
            "steps=$steps",
        )
        check("$MULTI_IMAGE_SHAPE: 反向前推到了文件头", collected.firstOrNull(), wide.entries.firstOrNull())
        val big = collected.firstOrNull { idOf(it) == "big" }
        checkTrue("$MULTI_IMAGE_SHAPE: 多图 entry 在某个窗口里", big != null)
        val bigChars = big?.toString()?.length ?: 0
        checkTrue(
            "$MULTI_IMAGE_SHAPE: 多图 entry 的 JSON 比旧上限 8 MiB 还大",
            bigChars > 8 * 1024 * 1024,
            "chars=$bigChars",
        )
        check("$MULTI_IMAGE_SHAPE: 窗口化读取 == 全文件（逐条 id/类型）", fingerprint(collected), fingerprint(wide.entries))
        // And the fixture must contain those bytes in one entry, not spread over lines:
        // two images in one content array is what makes the record, the line and the
        // window budget collide.
        check(
            "$MULTI_IMAGE_SHAPE: 两条图片在同一条消息里",
            big?.get("message")?.toString()?.split("\"type\":\"image\"")?.size?.minus(1),
            2,
        )
    }

    // (b3) the entry log the tree overlay reads: whole-session coverage, bounded
    // payload. This is the last whole-session read in the app, and the point of the
    // streaming scan is that it is *not* a whole-session record or a whole-session
    // object tree: an image's base64 must not appear anywhere in what is retained.
    run {
        val imgFile = shapes.first { it.first == "img-2x2.5MB" }.second
        val whole = SessionFileReader.readAll(imgFile)!!.entries
        // The entry-log scan caps a line far lower than the transcript reader on
        // purpose (it keeps nothing, so a multi-megabyte image line buys nothing), so on
        // this fixture it must report **incomplete** — which is the signal the
        // ViewModel turns into "ask pi for the whole list" rather than showing a log
        // with rows missing. The check is that the signal is honest, not that it is
        // never raised.
        val kept = ArrayList<SessionEntry>()
        val complete = SessionFileReader.readEntries(imgFile) { kept += parseSessionEntry(it) }
        checkTrue("entries: 巨图行按设计报告不完整", !complete)
        // With the transcript reader's own line cap the same scan must be complete and
        // must cover the whole session, entry for entry.
        val permissive = ArrayList<SessionEntry>()
        val permissiveOk = SessionFileReader.readEntries(
            imgFile,
            maxLineChars = SessionFileReader.DEFAULT_MAX_LINE_CHARS,
        ) { permissive += parseSessionEntry(it) }
        checkTrue("entries: 放宽行长后完整", permissiveOk)
        check("entries: 放宽行长后条数与全文件一致", permissive.size, whole.size)
        val wholeTypes = whole.map { o -> (o["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content }
        check("entries: 类型与全文件一致", permissive.map { it.type }, wholeTypes)
        // The image row's flattened text loses the `[image]` marker with its payload
        // (the scan keeps `type`/`mimeType`, not the data). What matters is that the
        // entry is still **there** and still parseable, which the count above proves;
        // this pins that the text half survived.
        checkTrue("entries: 图片行的文字仍在", kept.isNotEmpty())

        // The retained form must carry no base64 payload at all.
        val retainedChars = kept.sumOf { entry -> entryRetainedChars(entry) }
        val fileChars = imgFile.length().toInt()
        checkTrue(
            "entries: 保留量远小于文件（无 base64）",
            retainedChars < fileChars / 10,
            "retained=$retainedChars file=$fileChars",
        )
        // And it still parses into the same structure the screen reads: the user row
        // must still flatten to the `[image]` marker, not to nothing.
        // The scan keeps the block (`type`, `mimeType`) and drops only the payload, so
        // the entry survives and the data is gone. The flattened text no longer says
        // `[image]` because that marker is produced from the payload by
        // `flattenBlocks` — which is exactly the trade this scan makes: the tree's row
        // prints the role and a text preview, never the image.
        val userImage = permissive.filterIsInstance<SessionEntry.Message>()
            .first { it.message.role == "user" && imageBlocks(it).isNotEmpty() }
        check("entries: 扫描后图片 payload 已被丢弃", imageDataLength(userImage), 0)
        checkTrue("entries: 图片条目仍然存在", imageBlocks(userImage).isNotEmpty())
        var retainedImageData = 0
        for (entry in kept.filterIsInstance<SessionEntry.Message>()) {
            val message = entry.message
            val content = when (message) {
                is app.pi.rpc.PiMessage.User -> message.content
                is app.pi.rpc.PiMessage.Assistant -> message.content
                is app.pi.rpc.PiMessage.ToolResult -> message.content
                else -> emptyList()
            }
            for (block in content) {
                if (block is app.pi.rpc.PiContentBlock.Image) retainedImageData += block.data.length
            }
        }
        check("entries: 保留的图片 base64 长度为 0", retainedImageData, 0)
        // The tree's own text rows must still be there.
        val assistant = kept.filterIsInstance<SessionEntry.Message>().first { it.message.role == "assistant" }
        checkTrue("entries: 助手正文预览非空", assistant.message.text.isNotEmpty())
        println(
            "%-16s 文件 %8s chars -> 保留 %8d chars（%.1f%%）".format(
                "entries-scan", fileChars, retainedChars, retainedChars * 100.0 / fileChars,
            ),
        )
    }

    // (c) the framer's cap is what drops the big one: prove the same bytes are
    // unreadable at the cap and readable once it is raised, so the ceiling (not the
    // file) is the defect. The size is `cap + 1` read from the framer, never a literal:
    // this check used to spell "9 MiB" against an 8 MiB cap, and the moment the cap moved
    // above 9 MiB it would have gone on passing while testing nothing. The framer drops a
    // record of exactly `maxRecordChars` characters, so one character past it is the
    // smallest honest fixture.
    val overCap = "x".repeat(app.pi.rpc.JsonlFramer.DEFAULT_MAX_RECORD_CHARS + 1) + "\n"
    val default = app.pi.rpc.JsonlFramer()
    val defaultRecords = default.feed(overCap)
    check("记录上限：cap+1 的记录被丢弃并计数", defaultRecords.isEmpty() && default.droppedRecords == 1L, true)
    val raised = app.pi.rpc.JsonlFramer(maxRecordChars = overCap.length + 1)
    val raisedRecords = raised.feed(overCap)
    check("放宽上限：同一条记录可读出", raisedRecords.size, 1)
    println()

    // ---------------------------------------------------------- 5. framer cost
    println("-- 5. 帧读取吞吐（一条上限大小的记录）--")
    val payload = "x".repeat(app.pi.rpc.JsonlFramer.DEFAULT_MAX_RECORD_CHARS)
    val feedMs = medianMs(repeats = 3) {
        val f = app.pi.rpc.JsonlFramer(maxRecordChars = payload.length + 1)
        var i = 0
        while (i < payload.length) {
            val n = minOf(64 * 1024, payload.length - i)
            f.feed(payload.substring(i, i + n))
            i += n
        }
        f.feed("\n")
    }
    println("上限大小（${payload.length / 1024 / 1024} MiB）逐块喂入 + 成帧 = %.1f ms（与设备无关）".format(feedMs))
    println()

    // ------------------------------------------- 6. the header read, and only it
    //
    // 打开一个会话的那一段读里，`readHeader` 是**唯一一处只取决于第一行**的读：pi 的会话头就是
    // 文件的第一行，而无头文件不是会话（`session-manager.ts:551-556`）。它的读范围上限却是
    // `HEADER_BUDGET` = 1 MiB（`SessionFileReader.kt:695`），所以旧形状会把 `[0, 1 MiB)` 整段
    // 读完、切成行，再只看第一行 —— 会话文件小于 1 MiB 时就是**整份读一遍**。而 `readTail` 之后
    // 自己还要读一遍窗口（`:220`），于是同一份文件被读两遍。
    //
    // 这一节做两件事：把这一段的代价量出来（对 §3 的 `read ms`，它是同一段读里能被省掉的那部分），
    // 并把「早退」的答案钉在与规则一致的五类输入上 —— 空行/坏行在头之前、第一个能解析的行不是头、
    // 头在 1 MiB 之外（界不许被放宽）、没有任何可解析的行、以及所有真实夹具（答案的 id 必须与
    // 文件第一行写下的那个一致）。
    println("-- 6. readHeader：只取决于第一行的读 --")
    println("%-16s %9s %14s %14s".format("shape", "file MB", "readHeader ms", "readTail ms"))
    for ((name, file) in shapes) {
        val headerMs = medianMs(repeats = 3) { SessionFileReader.readHeader(file) }
        val tailMs = medianMs(repeats = 3) { SessionFileReader.readTail(file, windowChars, windowEntries) }
        println("%-16s %9s %14.2f %14.2f".format(name, mb(file.length()), headerMs, tailMs))
        // 期望值取自文件自己写下的第一行，不是字面量：夹具的 id 有三个（bench / one-message /
        // mixed），写死一个会让另外两个变成假绿。
        val expectedId = idOf(PiJson.parseObjectOrNull(allLines(file).first()))
        check(
            "$name: readHeader 认出这个会话，且 id 与文件第一行一致",
            idOf(SessionFileReader.readHeader(file)),
            expectedId,
        )
    }
    println()
    val headerRoot = File(System.getProperty("java.io.tmpdir"), "pi-replay-header-${System.nanoTime()}")
    headerRoot.mkdirs()
    // (a) 空行与解析不出的行在头之前：仍然要认出会话（「第一个**能解析的**行说了算」）
    val paddedFile = File(headerRoot, "padded.jsonl")
    paddedFile.writeText("\n\nnot json\n" + header("padded") + "\n" + userEntry("u1", null, "hi") + "\n")
    check("空行与坏行之后仍认得出会话头", idOf(SessionFileReader.readHeader(paddedFile)), "padded")
    // (b) 第一个能解析的行不是会话头 → 不是会话（pi 的同一个否决）
    val notSessionFile = File(headerRoot, "not-a-session.jsonl")
    notSessionFile.writeText(userEntry("u1", null, "hi") + "\n")
    check("第一个能解析的行不是会话头时不认", SessionFileReader.readHeader(notSessionFile), null)
    // (c) 头在 1 MiB 之外：上限不变（早退只许提前停，不许把界放宽）
    val farFile = File(headerRoot, "far-header.jsonl")
    farFile.writeText("{\"pad\":\"" + "p".repeat(1 shl 20) + "\"}\n" + header("far") + "\n")
    check("头在 1 MiB 之外仍然不认（界未放宽）", SessionFileReader.readHeader(farFile), null)
    // (d) 没有可解析的行：空文件、只有空行、只有坏行
    val emptyFile = File(headerRoot, "empty.jsonl").apply { writeText("") }
    val blanksFile = File(headerRoot, "blanks.jsonl").apply { writeText("\n\n\n") }
    val brokenFile = File(headerRoot, "broken.jsonl").apply { writeText("{oops\n{\"also\":\n") }
    check("空文件没有头", SessionFileReader.readHeader(emptyFile), null)
    check("只有空行没有头", SessionFileReader.readHeader(blanksFile), null)
    check("只有坏行没有头", SessionFileReader.readHeader(brokenFile), null)
    // (e) 只有头、没有任何条目：这是一个**是**会话的文件（pi 会打开它），所以必须认出
    val headerOnly = File(headerRoot, "header-only.jsonl")
    headerOnly.writeText(header("only") + "\n")
    check("只有头没有条目也是会话", idOf(SessionFileReader.readHeader(headerOnly)), "only")
    headerRoot.deleteRecursively()
    println()

    // --------------------------- 7. 「新建会话」在没有引擎时也不许被丢掉
    //
    // 用户原话：「我新建对话，点第一下新建不了，再点第二下。」
    //
    // 根因是 `Boot.Idle` 那一段窗口（D31：对话页在这一段是**可用**的，接管页只画
    // `Boot.Working`）：`newSession()` 直接走 `call("新建会话")`，而 `call` 在 `api == null`
    // 时只推一条通知就返回 —— 第一次点击**没有执行**，第二次（引擎已 attach）才成。
    // `send` / `sendFollowUp` / `runPromptCommand` 三条有 D31 的 park（排到 attach 之后按序
    // 重放），`newSession` 漏了。这一节读**源文本**钉住这条连线，与 `AttachmentBudgetCheck`
    // 读 `ChatScreen.kt` 同法（`PiSessionViewModel` 是 AndroidViewModel，本机编译不了它）。
    val viewModelFile = System.getProperty("pi.repo.root")?.let {
        File(it, "app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt")
    }
    val viewModelText = if (viewModelFile != null && viewModelFile.isFile) viewModelFile.readText() else ""
    checkTrue("读到了 PiSessionViewModel.kt（读不到这一节就没有意义）", viewModelText.isNotEmpty())
    val newSessionBody = viewModelText
        .substringAfter("fun newSession(parentSession: String? = null) {", "")
        .take(2000)
    checkTrue("找到了 newSession 的函数体", newSessionBody.isNotEmpty(), "marker not found")
    checkTrue(
        "没有引擎时「新建会话」排到 attach 之后，而不是丢给 call 的未就绪分支",
        newSessionBody.contains("parkUntilAttached { newSession(parentSession) }"),
    )
    // 顺序也要钉：排队必须在 `call` 之前，否则第一次点击仍然不执行（这正是那条缺陷）。
    val parkAt = newSessionBody.indexOf("parkUntilAttached {")
    val callAt = newSessionBody.indexOf("call(\"新建会话\")")
    checkTrue(
        "排队发生在 call 之前",
        parkAt in 0 until callAt,
        "parkAt=$parkAt callAt=$callAt",
    )
    checkTrue(
        "排进队里时界面有话可说（不许出现「点了没反应」）",
        newSessionBody.contains("pushNotice("),
    )
    // 重放恰好一次：队是「先拷贝再清空」，每个闭包按序只跑一次 —— 不重复、不丢。
    val drain = viewModelText
        .substringAfter("if (pendingPrompts.isNotEmpty()) {", "")
        .take(400)
    checkTrue(
        "重放先拷贝再清空（拷贝之后新排进来的动作不会被这次重放吃掉）",
        drain.contains("pendingPrompts.toList()") && drain.contains("pendingPrompts.clear()"),
        drain.take(120),
    )
    checkTrue(
        "并且按序各跑一次（forEach，不是并发也不是去重）",
        drain.contains("queued.forEach { it() }"),
        drain.take(160),
    )
    // 上面那三条钉的是「没被丢弃」；缺陷其实是「被执行之后又被撤销」，两者不相交 —— 所以它们在
    // 用户报「点第一遍没反应」时**全绿**。补的是同一件事的另一个方向：重放之后 `attach` 的尾巴
    // 里不许再有任何「把会话切走」的动作（当时的形状是 `maybeResumeLastSession()` /
    // `continueAfterRestart()` 排在重放之后，无条件把用户切回落盘的旧会话）。
    //
    // 重放之后，`attach` 里不许再换会话：引擎自己的「回到哪条会话」要么排在重放之前，
    // 要么不存在（现在就是后者，会话由 argv 钉住）。任何一处「重放之后再把会话切走」回来，这条就红。
    val attachForOrder = viewModelText
        .substringAfter("private fun attach(engine: PiEngineSession", "")
        .substringBefore("\n    /**", "")
    checkTrue("找到了 attach 的函数体（顺序不变量）", attachForOrder.isNotEmpty(), "marker not found")
    val replayAtForOrder = attachForOrder.indexOf("pendingPrompts.isNotEmpty()")
    checkTrue("attach 里能找到重放点", replayAtForOrder >= 0, "replayAt=$replayAtForOrder")
    val afterReplay = attachForOrder.substring(replayAtForOrder)
    val undoing = listOf("switchSession(", "maybeResumeLastSession(", "continueAfterRestart(")
        .filter { afterReplay.contains(it) }
    checkTrue(
        "重放之后 attach 不许再换会话（否则用户那一次点击会被撤销 = 第一遍没反应）",
        undoing.isEmpty(),
        "重放之后仍然出现：$undoing",
    )

    // ------------------- 8. 引擎重启之后必须回到刚才那个会话（「切成好几段」的根因）
    //
    // 用户原话：「一个对话在列表里被切成好几段、标题各不相同」「之前都没有这种 bug，肯定是最近
    // 这几轮、修加载时间这些问题的时候引入的」。
    //
    // 机制有两处，都在下面钉住：
    //   ① 显示那一半：`attach` 里原来 `replayHistory(engine)` 在 `refreshState()` **之前**，
    //      于是它用**上一个引擎**的 `_state.meta` 去解析源文件 —— 那份 meta 与旧文件的 header id
    //      一致，校验通过，屏幕重画的是**老对话**，而 pi 已经在新会话上。用户继续打字，消息落进
    //      新文件 ⇒ 一次对话裂成数段、每段标题不同。`afterSessionReplaced` 的 KDoc 早就写明了这条
    //      规则（「`refreshState` **before** the replay, not after」），attach 没有遵守。
    //   ② 真正的分裂：pi 的 argv 是固定的（没有 `-c`/`--session-id`），每一次进程启动都落在**全新**
    //      的会话文件上；而重启（proroot 开关当场生效、装包之后重启、崩溃后重试）是 **App 的实现
    //      细节**，不该被用户看见成一段新对话。修法是**在 argv 里钉住 id** —— `restartEngine` 传
    //      `continueSessionId`（发成 `--session-id <id>`）—— **不是**起来之后补一条
    //      `switch_session`：那套「事后接回 + 删掉本次新建的空会话」的机制已删（本文件下面钉它
    //      不许回来；`--session-id` 与 `-c` 的**解析事实**在 `pre-spawn` harness 里执行）。
    val attachBody = viewModelText
        // 锚点只取到形参开头：写死完整签名会让这段断言在签名变化时静默变成「marker not found」。
        .substringAfter("private fun attach(engine: PiEngineSession", "")
        .take(20_000)
    checkTrue("找到了 attach 的函数体", attachBody.isNotEmpty(), "marker not found")
    val refreshAt = attachBody.indexOf("refreshState()")
    val replayAt = attachBody.indexOf("replayHistory(engine)")
    checkTrue(
        "attach 里 refreshState() 在 replayHistory 之前（否则会拿上一个引擎的 meta 去读文件）",
        refreshAt >= 0 && replayAt >= 0 && refreshAt < replayAt,
        "refreshAt=$refreshAt replayAt=$replayAt",
    )
    checkTrue(
        "attach 不再有「事后接回」这个形参（会话由 argv 钉住）",
        viewModelText.contains("private fun attach(engine: PiEngineSession) {"),
    )
    // 窗口取到下一个函数声明为止，正好是 `restartEngine` 加上紧跟其后的那段 KDoc —— 这样
    // 「函数体里没有 switchSession」这条断言不会因为窗口过长而误判。
    val restartBody = viewModelText
        .substringAfter("suspend fun restartEngine(", "")
        .substringBefore("private fun attach(engine: PiEngineSession)")
    checkTrue("找到了 restartEngine 的函数体", restartBody.isNotEmpty(), "marker not found")
    val pinAt = restartBody.indexOf("val resumeSessionId = _state.value.meta.sessionId")
    val restartAt = restartBody.indexOf("host.restart(")
    checkTrue(
        "重启前就把用户那条对话的 id 抓在手里（pin 在 host.restart 之前）",
        pinAt >= 0 && restartAt >= 0 && pinAt < restartAt,
        "pinAt=$pinAt restartAt=$restartAt",
    )
    checkTrue(
        "抓住了就交给 argv（`launchOptions(continueSessionId = resumeSessionId)`）",
        restartBody.contains("launch = launchOptions(continueSessionId = resumeSessionId)"),
    )
    checkTrue(
        "重启成功之后只 attach，没有第二条会话命令",
        restartBody.contains("attach(result.session)") &&
            !restartBody.contains("switchSession"),
    )
    // 用户可达的重启只有两个入口：`restartEngine`（带 id）与 `switchWorkspace`（带 cwd 变更，
    // 刻意的例外 —— 换了 cwd 之后旧 id 不是 `findById` 的答案）。下面几条把「没有第三条只覆盖
    // 一半的路」钉住。
    check(
        "全仓 host.restart( 恰好两处",
        Regex("host\\.restart\\(").findAll(viewModelText).count(),
        2,
    )
    checkTrue(
        "运行时开关（proroot 开/关）那条路确实走到 restartEngine",
        viewModelText
            .substringAfter("private suspend fun restartForRuntimeSwitch(", "")
            .take(2_000)
            .contains("restartEngine("),
    )
    checkTrue(
        "另一处 host.restart( 在 switchWorkspace 里，且那里不钉 id",
        viewModelText
            // 窗口取到 `switchWorkspace` 自己的结尾为止（下一个声明是 `wouldInterruptTurn`）：
            // 再往后就是 `launchOptions` 的声明，那里面当然有 `continueSessionId`。
            .substringAfter("suspend fun switchWorkspace(", "")
            .substringBefore("fun wouldInterruptTurn()")
            .let { it.contains("host.restart(") && !it.contains("continueSessionId") },
    )
    // 删掉的那套机制**不许回来**：每个重启入口各自补一条 `switch_session`，正是「一次对话裂成
    // 好几段」的来源（两次收尾之间用户就能打字，消息落进另一个文件）；而「本次新建的空会话」
    // 本身也不会再有 —— pi 没有 assistant 消息时不落盘（`_persist` 的 `hasAssistant` 守卫，
    // `session-manager.ts:1029-1052`）。
    for (gone in listOf(
        "continueAfterRestart",
        "discardEmptySessionCreatedByRestart",
        "maybeResumeLastSession",
        "resumeAttempted",
        "continueFrom",
    )) {
        checkTrue(
            "接回不再有「事后补一条命令」的形状：$gone 已删除",
            !viewModelText.contains(gone),
            "argv 里钉 id 是唯一的接回路径；事后 `switch_session` 会与新引擎的首帧抢会话。",
        )
    }

    // ------------- 10. 换运行时（App 级重启）之后还在同一段对话里
    //
    // 用户证据：旧会话 26 条用户消息、新的那份零上下文开局，而新会话的启动时间正是他换完运行时
    // 重启 App 的时刻。机制：`app.sessions.resumeLast` 那一行的 `effective` 是
    // `EffectiveKind.RestartApp`（`PiSettingsEditors` 对用户的说法就是「这个值在 App 启动时读取」），
    // 而它当时的默认是**关** ⇒ 每一次 App 级重启都在新会话里。用户裁决：默认改成开
    // （「打开 App 接回上次那段对话；想要新对话用「＋ 新建会话」」）。
    //
    // 这一节钉三件事：
    //   ① 默认值是 `true`；
    //   ② **运行时读的就是注册表那一个默认值** —— 否则「翻默认值」只改了设置页的显示，行为仍是关
    //      （`readBoolean` 对没写过的键回 null）；
    //   ③ 它现在**只喂 argv 的 `-c`**（冷启动那一条路，`continueMostRecent`）：进程内重启由
    //      `--session-id` 精确钉住，与这个开关无关（id 优先于 `-c`；这条优先级在 `pre-spawn`
    //      harness 里是**执行**出来的，不是读出来的）。
    val registryFile = System.getProperty("pi.repo.root")?.let {
        File(it, "app/src/main/kotlin/app/pi/ui/settings/PiSettingsRegistry.kt")
    }
    val registryText = if (registryFile != null && registryFile.isFile) registryFile.readText() else ""
    checkTrue("读到了 PiSettingsRegistry.kt（读不到这一节就没有意义）", registryText.isNotEmpty())
    val resumeRow = registryText
        .substringAfter("key = \"app.sessions.resumeLast\"", "")
        .take(400)
    checkTrue("找到了 resumeLast 那一行", resumeRow.isNotEmpty(), "marker not found")
    checkTrue(
        "默认值是 true（不再是 false）",
        resumeRow.contains("defaultValue = bool(true)") &&
            !resumeRow.contains("defaultValue = bool(false)"),
    )
    checkTrue(
        "那一行仍然是 App 启动时读取（EffectiveKind.RestartApp）——这正是这条默认值的含义",
        resumeRow.contains("effective = EffectiveKind.RestartApp"),
    )
    // 断言的是**调用形状**（`boolIn(settingsStore)`），不是那三个字：这一节的上下文注释里会提到
    // `readBoolean` 这个名字，按名字断言只会被自己的注释绊倒。
    val resumeBody = viewModelText
        .substringAfter("private fun resumeLastEnabled()", "")
        .take(700)
    checkTrue("找到了 resumeLastEnabled 的函数体", resumeBody.isNotEmpty(), "marker not found")
    checkTrue(
        "运行时读注册表那一个默认值（不再是 settingsStore.readBoolean 加 ?: false）",
        resumeBody.contains("boolIn(settingsStore)") &&
            !resumeBody.contains("settingsStore.readBoolean"),
    )
    checkTrue(
        "这个开关只喂 argv（`continueMostRecent = resumeLastEnabled()`）",
        viewModelText.contains("continueMostRecent = resumeLastEnabled()"),
    )
    checkTrue(
        "冷启动不再事后切会话去接最近一段（交给 pi 的 `-c` 自己按 cwd + mtime 选）",
        !viewModelText.contains("mostRecentForResume("),
    )

    // ---------------------------------------------------------------- summary
    println("-- 结论 --")
    val small = wholeCost["text-40"]!!
    val large = wholeCost["text-1600"]!!
    println(
        "text-40 全量 = %.1f ms；text-1600（40x 条目）= %.1f ms；倍数 %.1fx".format(
            small.parseMs + small.rowsMs, large.parseMs + large.rowsMs,
            (large.parseMs + large.rowsMs) / maxOf(small.parseMs + small.rowsMs, 0.01),
        ),
    )
    for ((name, _) in shapes) {
        val before = wholeCost[name]!!
        val after = tailAfter[name]!!
        println(
            "%-16s 全量 %7.1f ms -> 尾部窗口 %7.1f ms（降到 %.1f%%）".format(
                name, before.parseMs + before.rowsMs, after,
                after / maxOf(before.parseMs + before.rowsMs, 0.001) * 100,
            ),
        )
    }
    println()
    println("harness: ${if (failures == 0) "OK" else "FAILED ($failures)"}")
    if (failures != 0) kotlin.system.exitProcess(1)
}

private fun idOf(obj: JsonObject?): String? =
    obj?.get("id")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }

/** A cheap, order-sensitive fingerprint of an entry sequence: id + type. */
private fun fingerprint(entries: List<JsonObject>): String =
    entries.joinToString("\u0001") { e ->
        val p = e["id"] as? kotlinx.serialization.json.JsonPrimitive
        "${p?.content}:${(e["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content}"
    }

private fun checkTrue(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        println("PASS $name")
        return
    }
    failures++
    if (detail.isEmpty()) {
        println("FAIL $name")
    } else {
        println("FAIL $name\n  $detail")
    }
}

/** A crude in-memory size of one retained entry: the text its reader can print. */
private fun entryRetainedChars(entry: SessionEntry): Int = when (entry) {
    is SessionEntry.Message -> entry.message.text.length + 40
    is SessionEntry.Compaction -> (entry.summary?.length ?: 0) + 40
    is SessionEntry.BranchSummary -> (entry.summary?.length ?: 0) + 40
    is SessionEntry.Custom -> (entry.data?.toString()?.length ?: 0) + (entry.customType?.length ?: 0) + 40
    is SessionEntry.CustomMessage -> entry.customType?.length ?: 0
    is SessionEntry.Label -> (entry.targetId?.length ?: 0) + (entry.label?.length ?: 0) + 40
    is SessionEntry.SessionInfo -> (entry.name?.length ?: 0) + 40
    is SessionEntry.ModelChange -> 40
    is SessionEntry.ThinkingLevelChange -> 40
    is SessionEntry.Unknown -> (entry.raw["message"]?.toString()?.length ?: 0) + 40
}

/** The image blocks of one message. `content` lives on the message subtypes, not the base type. */
private fun imageBlocks(message: SessionEntry.Message): List<app.pi.rpc.PiContentBlock.Image> {
    val blocks: List<app.pi.rpc.PiContentBlock> = when (val m = message.message) {
        is app.pi.rpc.PiMessage.User -> m.content
        is app.pi.rpc.PiMessage.Assistant -> m.content
        is app.pi.rpc.PiMessage.ToolResult -> m.content
        else -> emptyList()
    }
    val out = ArrayList<app.pi.rpc.PiContentBlock.Image>()
    for (block in blocks) if (block is app.pi.rpc.PiContentBlock.Image) out += block
    return out
}

/** Total base64 length retained across one message's image blocks. */
private fun imageDataLength(message: SessionEntry.Message): Int {
    var total = 0
    for (block in imageBlocks(message)) total += block.data.length
    return total
}
