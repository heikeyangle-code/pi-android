package app.pi.ui.blocks

import kotlin.random.Random
import kotlin.system.exitProcess

// A bare-JVM harness for `ByteBoundedLru` — the cache that `PiImageCache` (the decoded
// transcript images) is built on. Registered in `tools/run-app-pure-checks.sh` as
// `pi-image-cache`.
//
// Why this is worth pinning: the cache exists because decoding a transcript picture is
// expensive *and* because `produceState` throws the result away whenever the `LazyColumn`
// disposes the row — so a scroll past a screenshot and back used to decode it again under
// the two-permit gate. A cache that is wrong is worse than none: one that evicts the entry
// it just stored, or that lets its accounting drift, either stops working silently or grows
// without bound in a process that also runs the pi engine. Neither failure is visible on
// screen, and nothing else in this repository would see them.
//
// Two things it can and cannot test. `ByteBoundedLru` is Android-free on purpose (it lives
// in `ImageSize.kt`, the module's pure-arithmetic file) so its eviction rule, its byte
// accounting and its bounds are all executed here — including a model-based fuzz that
// compares it against a reference implementation of the same policy after every operation.
// `PiImageCache` itself imports `android.graphics.Bitmap` and cannot be compiled on this
// machine; what it adds on top of the map (a key of payload + box, a weight of payload
// bytes + `allocationByteCount`) is read as source text below, and the *rule* that matters
// is here: the map never hashes a key, which is the one property that keeps a megabyte-sized
// payload off the frame thread's `hashCode` (22.8 ms per 4 MiB, measured — see
// `docs/scroll-perf-items.md` §2.2).
//
// `pi.repo.root` is what `tools/run-app-pure-checks.sh` passes; a plain `java` invocation
// falls back to this file's own location.

private var checks = 0
private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    checks++
    if (actual != expected) {
        failures++
        println("FAIL  $name: actual=$actual expected=$expected")
    } else {
        println("PASS  $name")
    }
}

private fun checkTrue(name: String, condition: Boolean, detail: () -> String = { "" }) {
    checks++
    if (!condition) {
        failures++
        println("FAIL  $name  ${detail()}")
    } else {
        println("PASS  $name")
    }
}

/** [checkTrue] without its `PASS` line: the fuzz runs thousands of these. */
private fun checkSilent(name: String, condition: Boolean, detail: () -> String = { "" }) {
    checks++
    if (!condition) {
        failures++
        println("FAIL  $name  ${detail()}")
    }
}

/** A key whose `hashCode` is counted, and would be expensive if it ever ran. */
private class PayloadKey(val payload: String) {
    var hashCalls = 0
    override fun hashCode(): Int {
        hashCalls++
        return 0
    }

    override fun equals(other: Any?): Boolean = other is PayloadKey && payload == other.payload
}

private fun lru(maxBytes: Long, weigh: (String, String) -> Long = { key, _ -> key.length.toLong() }) =
    ByteBoundedLru<String, String>(maxBytes, weigh)

/** The policy `ByteBoundedLru` implements, written independently, for the fuzz below. */
private class ReferenceLru(private val maxBytes: Long) {
    private val keys = ArrayList<String>() // most recently used first
    private val weights = HashMap<String, Long>()
    var bytes: Long = 0
        private set

    val order: List<String> get() = keys

    fun get(key: String): String? {
        val at = keys.indexOf(key)
        if (at < 0) return null
        if (at > 0) {
            keys.removeAt(at)
            keys.add(0, key)
        }
        return key
    }

    fun put(key: String, weight: Long) {
        if (maxBytes <= 0) return
        val w = weight.coerceAtLeast(0)
        if (w > maxBytes) {
            remove(key)
            return
        }
        remove(key)
        keys.add(0, key)
        weights[key] = w
        bytes += w
        while (bytes > maxBytes && keys.size > 1) {
            val evicted = keys.removeAt(keys.size - 1)
            bytes -= weights.remove(evicted) ?: 0L
        }
    }

    private fun remove(key: String) {
        if (keys.remove(key)) bytes -= weights.remove(key) ?: 0L
    }
}

private fun runChecks() {
    println("== 1. basics ==")
    val empty = lru(100)
    check("empty: get misses", empty.get("a"), null)
    check("empty: size", empty.size, 0)
    check("empty: bytes", empty.bytes, 0L)

    val simple = lru(100)
    simple.put("a", "A")
    check("put/get round trip", simple.get("a"), "A")
    check("size after one put", simple.size, 1)
    check("bytes after one put", simple.bytes, 1L)
    check("miss stays a miss", simple.get("zzz"), null)

    println("\n== 2. replacement does not double count ==")
    simple.put("a", "A2")
    check("replaced value", simple.get("a"), "A2")
    check("size after replace", simple.size, 1)
    check("bytes after replace", simple.bytes, 1L)

    println("\n== 3. least recently used is the one evicted ==")
    // Weights are the key's length: "aaa" = 3 bytes, budget 12.
    val three = lru(12)
    three.put("aaa", "1")
    three.put("bbb", "2")
    three.put("ccc", "3") // [ccc, bbb, aaa] = 9
    check("three fit", three.size, 3)
    check("hits the oldest (aaa)", three.get("aaa"), "1") // recency is now [aaa, ccc, bbb]
    three.put("dddd", "4") // 9 + 4 = 13 > 12 -> evict bbb (the least recently used) -> 10
    check("evicted the least recently used (bbb)", three.get("bbb"), null)
    check("kept the recently used (aaa)", three.get("aaa"), "1")
    check("kept ccc", three.get("ccc"), "3")
    check("kept the new one (dddd)", three.get("dddd"), "4")
    check("size after one eviction", three.size, 3)
    check("bytes after one eviction", three.bytes, 10L)

    println("\n== 3b. one put may evict more than one entry ==")
    // The same sequence with a tighter budget: dropping only the first victim still leaves
    // the map over its bound, so the loop keeps going. This is the property whose absence
    // would make a byte bound a hope rather than an invariant.
    val tightest = lru(9)
    tightest.put("aaa", "1")
    tightest.put("bbb", "2")
    tightest.put("ccc", "3") // [ccc, bbb, aaa] = 9
    tightest.get("aaa") // [aaa, ccc, bbb]
    tightest.put("dddd", "4") // 13 -> evict bbb -> 10 -> evict ccc -> 7
    check("the least recent was evicted", tightest.get("bbb"), null)
    check("and so was the next one (the loop kept going)", tightest.get("ccc"), null)
    check("the most recent hit survives", tightest.get("aaa"), "1")
    check("the new entry survives", tightest.get("dddd"), "4")
    check("size after two evictions", tightest.size, 2)
    check("bytes after two evictions", tightest.bytes, 7L)
    checkTrue("and the bound holds", tightest.bytes <= 9) { "bytes=${tightest.bytes}" }

    println("\n== 4. limits and degenerate budgets ==")
    val off = lru(0)
    off.put("a", "A")
    check("maxBytes = 0 stores nothing", off.get("a"), null)
    check("maxBytes = 0 keeps size 0", off.size, 0)

    val negative = lru(-5)
    negative.put("a", "A")
    check("negative budget stores nothing", negative.size, 0)

    val tight = lru(4)
    tight.put("aaa", "1")
    tight.put("bbbbb", "2") // 5 bytes: heavier than the whole budget
    check("an over-budget entry is not stored", tight.get("bbbbb"), null)
    check("and it does not evict what fit", tight.get("aaa"), "1")
    check("bytes unchanged by the refused entry", tight.bytes, 3L)

    val replaceWithOverBudget = lru(5)
    replaceWithOverBudget.put("aa", "1")
    replaceWithOverBudget.put("aa", "2")
    check("replace keeps the entry", replaceWithOverBudget.get("aa"), "2")
    val heavy = ByteBoundedLru<String, String>(2, { key, _ -> key.length.toLong() })
    heavy.put("aa", "1") // exactly the budget: stored
    check("an entry of exactly the budget is stored", heavy.get("aa"), "1")
    check("bytes equal the budget", heavy.bytes, 2L)

    println("\n== 5. weight clamping and clearing ==")
    val clamped = ByteBoundedLru<String, String>(10, { _, _ -> -7L })
    clamped.put("a", "A")
    check("a negative weight is clamped to zero", clamped.bytes, 0L)
    check("and the entry is still stored", clamped.get("a"), "A")

    clamped.clear()
    check("clear empties the map", clamped.size, 0)
    check("clear resets the accounting", clamped.bytes, 0L)
    check("clear makes every key miss", clamped.get("a"), null)

    println("\n== 6. the map never hashes a key ==")
    // The whole reason this class exists instead of a LinkedHashMap: `get` on a
    // megabyte-sized payload must not run `String.hashCode()` (22.8 ms per 4 MiB, measured).
    val keyed = ByteBoundedLru<PayloadKey, String>(1000) { key, _ -> key.payload.length.toLong() }
    val first = PayloadKey("payload-one")
    val sameContent = PayloadKey("payload-one")
    val other = PayloadKey("payload-two")
    keyed.put(first, "one")
    check("identity hit", keyed.get(first), "one")
    check("equal-but-distinct hit", keyed.get(sameContent), "one")
    check("miss on a different payload", keyed.get(other), null)
    check("hashCode was never called on any key", first.hashCalls + sameContent.hashCalls + other.hashCalls, 0)

    println("\n== 7. model-based fuzz: order, accounting and the byte bound ==")
    val random = Random(20240918)
    for (round in 0 until 40) {
        val budget = random.nextLong(0, 40)
        val model = ReferenceLru(budget)
        val real = ByteBoundedLru<String, String>(budget) { key, _ -> key.length.toLong() }
        repeat(60) {
            val key = listOf("a", "bb", "ccc", "dddd", "eeeee", "ffffff").random(random)
            if (random.nextBoolean()) {
                val hit = real.get(key)
                checkSilent("fuzz get agrees with the model", (hit != null) == (model.get(key) != null)) {
                    "round=$round key=$key budget=$budget"
                }
            } else {
                real.put(key, key)
                model.put(key, key.length.toLong())
            }
            checkSilent("fuzz: bytes stay inside the budget", real.bytes <= budget.coerceAtLeast(0)) {
                "round=$round budget=$budget bytes=${real.bytes}"
            }
            checkSilent("fuzz: size agrees with the model", real.size == model.order.size) {
                "round=$round budget=$budget subject=${real.size} model=${model.order.size}"
            }
            checkSilent("fuzz: accounting agrees with the model", real.bytes == model.bytes) {
                "round=$round budget=$budget subject=${real.bytes} model=${model.bytes}"
            }
            checkSilent("fuzz: recency order agrees with the model", real.keys() == model.order) {
                "round=$round budget=$budget subject=${real.keys()} model=${model.order}"
            }
        }
        println("PASS  40x60 fuzz round ${round + 1}/40 against the reference policy")
    }

    println("\n== 8. the wiring `ByteBoundedLru` cannot see ==")
    val root = repoRoot()
    val cacheFile = root?.let { java.io.File(it, "app/src/main/kotlin/app/pi/ui/blocks/PiImageCache.kt") }
    val cacheText = if (cacheFile != null && cacheFile.isFile) cacheFile.readText() else ""
    checkTrue("PiImageCache exists and is bounded by the app's 32 MiB", cacheText.contains("MAX_TOTAL_BYTES: Long = 32L * 1024 * 1024")) {
        "file=${cacheFile?.absolutePath}"
    }
    checkTrue("PiImageCache charges the payload to the entry", cacheText.contains("payload.length.toLong() * 2")) {
        "the key's own bytes have to count, or the bound is a lie"
    }
    checkTrue("PiImageCache never hashes the payload", !cacheText.contains("payload.hashCode()")) {
        "String.hashCode() on a payload is the thing this cache exists to avoid"
    }
    for (relative in listOf(
        "app/src/main/kotlin/app/pi/ui/blocks/ImageGridBlock.kt",
        "app/src/main/kotlin/app/pi/ui/blocks/PiImageViewer.kt",
    )) {
        val file = root?.let { java.io.File(it, relative) }
        val text = if (file != null && file.isFile) file.readText() else ""
        checkTrue("${relative.substringAfterLast('/')} decodes through the cache", text.contains("PiImageCache.readThrough(")) {
            "a cache nobody calls is the same as no cache"
        }
    }
}

private fun repoRoot(): java.io.File? {
    System.getProperty("pi.repo.root")?.let { return java.io.File(it) }
    var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".")
    repeat(6) {
        val candidate = dir ?: return null
        if (java.io.File(candidate, "tools/run-app-pure-checks.sh").isFile) return candidate
        dir = candidate.parentFile
    }
    return null
}

fun main() {
    runChecks()
    println()
    if (failures == 0) {
        println("harness: OK ($checks checks)")
    } else {
        println("harness: FAILED ($failures of $checks checks failed)")
        exitProcess(1)
    }
}
