package app.pi.bridge

// 正文图片请求策略（`bridge/PiImageRequestPolicy.kt`）的裸 JVM 检查。由
// `tools/run-app-pure-checks.sh` 的 `image-request-policy` 项运行。
//
// 为什么需要它：R1（性能审计唯一判定为"严重"的那条）就是"失败不缓存 + 没有任何 in-flight
// 去重"在 `LazyColumn` 回收行时的后果 —— 一张坏 URL 每滚回来一次就重新出一网，行内图还
// 会在同一帧被库调两次。修法是一台状态机（共享正在飞的请求 + TTL 负缓存），而这台状态机
// 的四条性质**编译器一条都看不见**，错了也全是安静的：
//
//   1. 并发的两个请求只调用 loader 一次（错了：同一张图并发两条连接）；
//   2. 加载结束后条目被移除（错了：内存泄漏，或者一张旧结果永远盖住新的）；
//   3. 失败在 TTL 内不再 loader、TTL 过了重新 loader（错了：坏 URL 每帧重打，或者失败被
//      永久缓存成空白）；
//   4. 等待方被取消不取消生产者（错了：滚快一点就把别人正在下的图掐了）。
//
// 时钟是注入的（`now = { clock }`），所以 TTL 的检查不靠 `Thread.sleep`、也不会有 flaky。
// 每个用例都自己 new 一个 `PiImageRequestPolicy`（各自一个内部作用域），互相不串味。
//
// 手工跑：
//
//   KOTLINC_CP="$(find -L build/pure-checks/kotlinc -name '*.jar' | tr '\n' ':')"; KOTLINC_CP=${KOTLINC_CP%:}
//   LIB_CP="$(find -L build/pure-checks/lib -name '*.jar' | tr '\n' ':')"; LIB_CP=${LIB_CP%:}
//   java -cp "$KOTLINC_CP:$LIB_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib \
//     -jvm-target 17 -classpath "$LIB_CP" -d /tmp/pir/out \
//     app/src/test/kotlin/app/pi/bridge/PiImageRequestPolicyCheck.kt \
//     app/src/main/kotlin/app/pi/bridge/PiImageRequestPolicy.kt
//   java -cp "/tmp/pir/out:$LIB_CP" app.pi.bridge.PiImageRequestPolicyCheckKt

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private const val URL_A = "https://example.invalid/a.png"
private const val URL_B = "https://example.invalid/b.png"

/** 一个只在这次调用里活着的 key，避免用例之间靠常量互相影响。 */
private fun key(url: String = URL_A, width: Int = 1080) = PiImageRequestKey(url, width)

/** 等一个可能永远不完成的等待方：挂死要报成失败，而不是把 CI 卡住。 */
private suspend fun <T> bounded(block: suspend () -> T): T? = withTimeoutOrNull(5_000) { block() }

private fun policy(
    negativeTtlMs: Long = PI_IMAGE_NEGATIVE_TTL_MS,
    producerBudgetMs: Long = PI_IMAGE_PRODUCER_BUDGET_MS,
    maxNegativeEntries: Int = PI_IMAGE_MAX_NEGATIVE_ENTRIES,
    now: () -> Long = { 0L },
) = PiImageRequestPolicy<PiImageRequestKey, String>(
    negativeTtlMs = negativeTtlMs,
    producerBudgetMs = producerBudgetMs,
    maxNegativeEntries = maxNegativeEntries,
    now = now,
    dispatcher = Dispatchers.Default,
)

fun main() = runBlocking {
    // ---------------------------------------------------------------- 1. 共享正在飞的一次
    run {
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loader: suspend (PiImageRequestKey) -> Result<String> = {
            calls.incrementAndGet()
            started.complete(Unit)
            release.await()
            Result.success("bytes")
        }
        val requests = policy()
        // UNDISPATCHED 让 `load` 同步跑到第一个挂起点：条目的登记在 `load` 返回之前就发生了，
        // 所以第二个请求一定看见"有一条正在飞" —— 这是这个检查不 flaky 的原因。
        val first = async(start = CoroutineStart.UNDISPATCHED) { requests.load(key(), loader) }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { requests.load(key(), loader) }
        release.complete(Unit)
        val firstResult = first.await()
        val secondResult = second.await()

        check("A1 并发的两个请求只跑一次 loader", calls.get(), 1)
        check("A2 第二个请求拿到第一个的结果", secondResult.getOrNull(), "bytes")
        check("A3 第一个请求也拿到结果", firstResult.getOrNull(), "bytes")
    }

    // ------------------------------------------- 2. 加载结束（成功）后条目被移除，可以再加载
    run {
        val calls = AtomicInteger()
        val loader: suspend (PiImageRequestKey) -> Result<String> = {
            calls.incrementAndGet()
            Result.success("v${calls.get()}")
        }
        val requests = policy()
        check("B1 第一次加载成功", requests.load(key(), loader).getOrNull(), "v1")
        check("B2 成功之后条目被移除：第二次会重新加载（不是拿旧条目）", requests.load(key(), loader).getOrNull(), "v2")
        check("B3 成功路径不被负缓存挡住", calls.get(), 2)
    }

    // ---------------------------------------------------- 3. 失败：TTL 内不出网、过了再试
    run {
        var clock = 0L
        val calls = AtomicInteger()
        val loader: suspend (PiImageRequestKey) -> Result<String> = {
            calls.incrementAndGet()
            Result.failure(RuntimeException("boom-${calls.get()}"))
        }
        val requests = policy(negativeTtlMs = 45_000L, now = { clock })
        val first = requests.load(key(), loader)
        check("C1 第一次失败", first.isFailure, true)
        check("C2 失败的这一次真的调了 loader", calls.get(), 1)

        clock = 44_999L
        val second = requests.load(key(), loader)
        check("C3 TTL 内不再出网", calls.get(), 1)
        check("C4 命中负缓存", second.exceptionOrNull() is PiImageNegativeCacheHit, true)
        check(
            "C5 负缓存带着上一次的真实原因（lastFailure 不会撒谎）",
            (second.exceptionOrNull() as? PiImageNegativeCacheHit)?.originalReason,
            "boom-1",
        )

        clock = 45_000L // 正好到期
        val third = requests.load(key(), loader)
        check("C6 TTL 到期后重新 loader", calls.get(), 2)
        check("C7 到期后的这次是真实失败，不是负缓存", third.exceptionOrNull() is PiImageNegativeCacheHit, false)
        check("C8 到期后的失败原因是新的那一次", third.exceptionOrNull()?.message, "boom-2")
    }

    // ------------------------------------- 4. 失败后条目也被移除（TTL = 0 时立刻可以重新加载）
    run {
        val calls = AtomicInteger()
        val loader: suspend (PiImageRequestKey) -> Result<String> = {
            calls.incrementAndGet()
            Result.failure(RuntimeException("down"))
        }
        val requests = policy(negativeTtlMs = 0L)
        check("D1 失败", requests.load(key(), loader).isFailure, true)
        check("D2 失败也把条目移除了（TTL=0 时第二次立刻重新 loader）", requests.load(key(), loader).isFailure, true)
        check("D3 loader 被调了两次", calls.get(), 2)
    }

    // -------------------------------------------- 5. 等待方被取消不取消生产者，也不重跑一次
    run {
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loader: suspend (PiImageRequestKey) -> Result<String> = {
            calls.incrementAndGet()
            started.complete(Unit)
            release.await()
            Result.success("shared")
        }
        val requests = policy()
        val waiterA = launch { requests.load(key(), loader) }
        started.await()
        // 等待方 A 走了（模拟：滚得快、行被回收）。生产者必须不受影响。
        waiterA.cancelAndJoin()
        val waiterB = async(start = CoroutineStart.UNDISPATCHED) { requests.load(key(), loader) }
        release.complete(Unit)

        val resultB = bounded { waiterB.await() }
        check("E1 取消等待方之后，生产者仍把结果交付给第二个等待方", resultB?.getOrNull(), "shared")
        check("E2 等待方被取消没有让生产者重跑一次", calls.get(), 1)
    }

    // ----------------------------------------------- 6. 成功与失败混合：互不串味、不互相挡
    run {
        val callsA = AtomicInteger()
        val callsB = AtomicInteger()
        val okA: suspend (PiImageRequestKey) -> Result<String> = {
            callsA.incrementAndGet()
            Result.success("A")
        }
        val failB: suspend (PiImageRequestKey) -> Result<String> = {
            callsB.incrementAndGet()
            Result.failure(RuntimeException("B-down"))
        }
        val requests = policy()
        check("F1 A 成功", requests.load(key(URL_A), okA).getOrNull(), "A")
        check("F2 B 失败", requests.load(key(URL_B), failB).isFailure, true)
        check("F3 A 的第二次仍然真的加载（成功没有被任何负缓存挡住）", requests.load(key(URL_A), okA).getOrNull(), "A")
        check("F4 A 被调了两次", callsA.get(), 2)
        check("F5 B 仍在 TTL 内，不再出网", requests.load(key(URL_B), failB).isFailure, true)
        check("F6 B 只被调了一次", callsB.get(), 1)
    }

    // -------------------------------------- 7. 生产者绝不无限跑：超预算后条目要被清掉
    run {
        val calls = AtomicInteger()
        val hanging: suspend (PiImageRequestKey) -> Result<String> = {
            calls.incrementAndGet()
            // 永不完成的等待：只有 `withTimeoutOrNull` 能把它结束掉。
            CompletableDeferred<Result<String>>().await()
        }
        val requests = policy(negativeTtlMs = 0L, producerBudgetMs = 120L)
        val first = bounded { requests.load(key(), hanging) }
        check("G1 超预算返回失败，不是永远挂着", first?.exceptionOrNull() is PiImageProducerTimeout, true)
        val second = bounded { requests.load(key(), hanging) }
        check("G2 超预算之后条目被移除（TTL=0 时第二次重新 loader）", second?.exceptionOrNull() is PiImageProducerTimeout, true)
        check("G3 loader 被调了两次（说明条目真的没了）", calls.get(), 2)
    }

    // ---------------------------------------------------------------- 8. 负缓存表有界
    run {
        val cap = 4
        var clock = 0L
        val calls = HashMap<String, Int>()
        val loader: suspend (PiImageRequestKey) -> Result<String> = { requested ->
            calls[requested.link] = (calls[requested.link] ?: 0) + 1
            Result.failure(RuntimeException("down"))
        }
        val requests = policy(negativeTtlMs = 60_000L, maxNegativeEntries = cap, now = { clock })
        val keys = (0 until cap + 1).map { key("https://example.invalid/$it.png") }
        keys.forEach { requests.load(it, loader) }
        check("H1 每个 key 都试过一次", calls.size, cap + 1)

        requests.load(keys.first(), loader)
        check("H2 封顶：最早的那条被淘汰，TTL 内也会重新 loader", calls[keys.first().link], 2)
        requests.load(keys.last(), loader)
        check("H3 淘汰只丢最旧的：最近那条仍在 TTL 内", calls[keys.last().link], 1)
    }

    // ------------------------------------------------------- 9. 键就是 (link, 目标宽度)
    run {
        val calls = AtomicInteger()
        val loader: suspend (PiImageRequestKey) -> Result<String> = {
            calls.incrementAndGet()
            Result.success("v")
        }
        val requests = policy()
        requests.load(key(URL_A, 1080), loader)
        requests.load(key(URL_A, 720), loader)
        check("I1 同一个 link 的不同目标宽度是两个请求（位图按宽度采样）", calls.get(), 2)

        val keyed = PiImageRequestKey("https://user:secret@host/x.png", 1080)
        check("I2 键不打印 link（可能是几 MB base64 或带凭据的 URL）", keyed.toString().contains("secret"), false)
        check("I3 同值的两个键相等", PiImageRequestKey(URL_A, 1080) == PiImageRequestKey(URL_A, 1080), true)
        check(
            "I4 同值的两个键哈希相同",
            PiImageRequestKey(URL_A, 1080).hashCode(),
            PiImageRequestKey(URL_A, 1080).hashCode(),
        )
    }

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
