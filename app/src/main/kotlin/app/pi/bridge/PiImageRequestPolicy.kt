package app.pi.bridge

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一张正文图片的请求：**正在飞的只跑一次，刚失败过的 45 秒内不再跑**。
 *
 * ## 为什么这段策略必须是纯 Kotlin
 *
 * 它回答的是"同一张图被并发要了两次，谁去取字节"、"上一次失败了，这一次要不要再出网"
 * 这类**状态机**问题，和 Android 一点关系都没有。放进 `PiGuestImageTransformer`（一个
 * Android/Compose 类）的话，本机（没有 Android SDK）就一行都跑不起来，而这四条性质
 * **编译器一条都看不见**：
 *
 *  1. 并发的第二个请求共享第一个；
 *  2. 加载结束（成功或失败）后条目被移除；
 *  3. 失败只在 [negativeTtlMs] 内挡住重试，过了照旧重试；
 *  4. 等待方被取消**不**牵连生产者。
 *
 * 四条错了都是安静的：多打一次网、条目泄漏、坏 URL 永久空白、或者"滚快一点就把别人的
 * 下载掐了"。所以状态机被抽到这里，由
 * `app/src/test/kotlin/app/pi/bridge/PiImageRequestPolicyCheck.kt` 在裸 JVM 上真跑一遍
 * （`tools/run-app-pure-checks.sh` 的 `image-request-policy` 项），时钟是注入的
 * [now]，所以 TTL 的判定不靠 `Thread.sleep`。
 *
 * 这个文件**不许**出现 `android.*`：这里长出 Android import，就是它不再是纯策略的信号，
 * 而纯检查会因此编译失败 —— 那正是目的。
 *
 * ## 它保证什么，以及每一条为什么
 *
 *  - **进程级去重，键是 `(link, 目标宽度)`。** [PiImageRequestKey] 与
 *    `PiGuestImageTransformer` 的位图缓存是**同一个**键类型，所以"正在飞的那一份"和
 *    "已经画出来那一份"指着同一张图，不会出现一边按 1080 去重、一边按 720 命中的错配。
 *    键里带宽度是因为位图按宽度采样（旋转/分屏必须重解）。
 *  - **成功不进这里。** 成功的位图仍然只进原来那个 24 项 / 32 MiB 的位图缓存；本类只
 *    持有"正在飞的这一次"的完成信号，用完即删。这里再存一份位图就是第二份缓存，两份
 *    上限一定会漂移。
 *  - **失败记 [negativeTtlMs]，不是永久。** 永久负缓存会把一次瞬时失败变成永久空白：
 *    图片文件可能就在这一轮才被 agent 写出来，URL 可能只是瞬断，服务的 502 也可能
 *    下一秒就好。这里要的只是"滚动期间别反复重打"，不是"以后都别试" —— 所以 TTL 一过
 *    照旧重试一次，成功路径完全不经由这张表（[load] 只在**动手之前**查它）。
 *  - **等待方各自可取消，生产者不可。** 生产者跑在本类自己的进程级作用域里（
 *    [dispatcher]，默认 [Dispatchers.IO]），等待方等的是 `CompletableDeferred.await()`：
 *    取消等待方只取消它自己的等待，不会取消那个 Deferred，所以"滚快一点"不会把别人
 *    正在下载的那一份掐掉。
 *  - **生产者绝不无限跑。** [producerBudgetMs] 是兜底上限：`read` 阻塞在 socket 上时
 *    协程取消是进不去的（`GuestImageBytes` 只能在每块之间查取消），所以这条超时保证
 *    条目终究会被清掉、等待方不会被永远挂住。
 *  - **负缓存表有界。** 它按 URL 增长，一次长会话里每个失败 link 都是一个 key + 一个
 *    原因字符串。封顶 [maxNegativeEntries]，插入时先清过期的、再按插入序丢最旧的一条
 *    （TTL 是常量而 [now] 不回退，所以插入序 == 到期序）。
 *
 * ## 有意的行为变化：节点离开组合后下载**不再**当场停
 *
 * 这条取代了 `PiGuestImageBytes`/`PiGuestImageTransformer` 原来那句"节点离开组合下载
 * 当场停"。原因是这套策略的取舍方向变了：`LazyColumn` 回收行会让 `produceState` 重跑，
 * 而"失败不被记住"就意味着**一张坏 URL 每滚回来一次就重新出一网**（每次都带 5 s 连接 +
 * 5 s 读 + 15 s 总预算），行内图还会在同一帧被库调两次、各自开一条连接。
 *
 * 现在的取舍是：生产者跑在进程级作用域里、有自己的预算，所以节点离开组合之后它会跑完，
 * 把字节写进磁盘缓存 —— 用户滚回来时那是**缓存命中，不出网**。代价是划过一张网络图时
 * 这一次下载不会当场停（最坏情况是这个预算），换来的是"滚回来不再重打"。这不是疏忽，
 * 是上面那些重复请求的直接后果；要"当场停"就必须回到"每次组合各自取一份"。
 *
 * ## 分工
 *
 *  - [GuestImageBytes]：**怎么取字节**（磁盘缓存、8 MiB 上限、5 s/5 s/15 s 超时、64 KiB
 *    分块），以及"取字节"这一小段的并发上限（那把信号量在那边，不在这里）。
 *  - `PiGuestImageTransformer`：字节 → 位图（`piImageDecodeGate`）→ 位图缓存。
 *  - 本类：上面那件事**要不要现在做、要不要第二次做**。
 *
 * @param negativeTtlMs 失败结果在内存里记住多久；见类注释"失败记 TTL"。
 * @param producerBudgetMs 生产者的兜底预算；见类注释"生产者绝不无限跑"。
 * @param maxNegativeEntries 负缓存表的条数上限；见类注释"负缓存表有界"。
 * @param now 时钟，单位毫秒、必须单调不回退（默认 `System.nanoTime()`）。注入是为了让
 *   检查不靠真实时间等待；生产环境不要传 `System.currentTimeMillis()`（它会跳）。
 * @param dispatcher 生产者跑在哪个调度器上；[Dispatchers.IO] 是因为生产者最终一定落到
 *   阻塞的文件/网络读上。
 */
internal class PiImageRequestPolicy<K : Any, V : Any>(
    private val negativeTtlMs: Long = PI_IMAGE_NEGATIVE_TTL_MS,
    private val producerBudgetMs: Long = PI_IMAGE_PRODUCER_BUDGET_MS,
    private val maxNegativeEntries: Int = PI_IMAGE_MAX_NEGATIVE_ENTRIES,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val lock = Any()

    /**
     * 进程级作用域。`SupervisorJob` 是必须的：一个生产者失败（或超预算被取消）不许连带
     * 取消别的生产者 —— 它们是互不相关的图片请求。这个作用域**从不取消**（活到进程结束），
     * 所以持有它的实例必须也是进程级的（`PiGuestImageTransformer` 的 companion）。
     */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** 正在飞的请求，按 [PiImageRequestKey] 索引；加载一结束就删。 */
    private val inFlight = HashMap<K, Pending<V>>()

    /** 刚失败过的 key → 到期时刻与原因。见类注释"负缓存表有界"。 */
    private val negative = LinkedHashMap<K, Negative>()

    /**
     * 一次正在飞的请求。等待方等的是 [result]，它**没有父 Job** —— 所以取消任何一个等待方
     * 都不会顺带取消它（这正是"等待方可取消、生产者不可"的结构性原因）。
     */
    private class Pending<V> {
        val result = CompletableDeferred<Result<V>>()
    }

    private class Negative(val expiresAt: Long, val reason: String?)

    /**
     * 取一次结果：命中负缓存就直接失败（**不出网**），有正在飞的就跟它共享，否则自己
     * 起一个生产者。
     *
     * [loader] 是用**第一个**发起者的那一个：同一个 key 上并发的其它 [loader] 在这个窗口
     * 里不会被调用。这是去重的语义本身 —— 同一个 key 本来就应该算出同一样东西，所以
     * "用谁的 loader"不是可观察的行为差别（键里已经带着 link 与宽度）。
     *
     * 取消语义：本函数是普通 `suspend`，等待方被取消只影响它自己的 [CompletableDeferred.await]。
     */
    suspend fun load(key: K, loader: suspend (K) -> Result<V>): Result<V> {
        val pending: Pending<V>
        synchronized(lock) {
            val remembered = negative[key]
            if (remembered != null) {
                val remaining = remembered.expiresAt - now()
                if (remaining > 0) {
                    return Result.failure(PiImageNegativeCacheHit(remembered.reason, remaining))
                }
                // 到期了：删掉，走下面正常的路再试一次。
                negative.remove(key)
            }
            val running = inFlight[key]
            if (running != null) {
                pending = running
            } else {
                val fresh = Pending<V>()
                inFlight[key] = fresh
                val job = scope.launch { produce(key, fresh, loader) }
                // 生产者"根本没跑起来"的那条路：作用域已经取消时，`launch` 创建的协程会被
                // 直接取消、body 一句都不执行，于是 `produce` 的收尾也不会发生 —— 等待方
                // 会永远挂在 Deferred 上。这里补一次收尾，保证条目被删、等待方被唤醒。
                job.invokeOnCompletion { cause ->
                    if (cause != null) {
                        finish(key, fresh, null)
                        fresh.result.completeExceptionally(cause)
                    }
                }
                pending = fresh
            }
        }
        return pending.result.await()
    }

    /**
     * 生产者：跑一次 [loader]，然后把"条目移除 + 失败记 TTL"一次做完。
     *
     * `withTimeoutOrNull` 只包 [loader]，所以超预算取消的是这一次加载，不是作用域；被取消
     * 时**照常**记负缓存（这是一次失败），而生产者自己被外部取消（作用域销毁）时**不**记
     * ——那次取消不是这张图的结论，记了会给 45 秒后的重试埋一个假答案。
     */
    private suspend fun produce(key: K, pending: Pending<V>, loader: suspend (K) -> Result<V>) {
        val outcome: Result<V> = try {
            withTimeoutOrNull(producerBudgetMs.coerceAtLeast(1L)) { loader(key) }
                ?: Result.failure(PiImageProducerTimeout(producerBudgetMs))
        } catch (cancel: CancellationException) {
            finish(key, pending, null)
            pending.result.completeExceptionally(cancel)
            return
        } catch (error: Exception) {
            // loader 自己抛（而不是返回 Result.failure）也算一次失败：下面的 finish 会把它
            // 记进负缓存，而不是让它变成一条永远悬着的条目。
            Result.failure(error)
        }
        finish(key, pending, outcome)
        pending.result.complete(outcome)
    }

    /**
     * 生产者收尾。[outcome] 是 `null` 时只移除条目（取消，不算失败）。
     *
     * 移除带身份校验（`inFlight[key] === pending`）：条目可能已经被后来的一个生产者换成
     * 新的了（TTL 到期后重试正好撞上旧生产者收尾），那就不能把新的那条删掉。
     */
    private fun finish(key: K, pending: Pending<V>, outcome: Result<V>?) {
        synchronized(lock) {
            if (inFlight[key] === pending) inFlight.remove(key)
            if (outcome != null && outcome.isFailure) {
                rememberNegative(key, outcome.exceptionOrNull()?.message)
            }
        }
    }

    /** 插入一条负缓存记录，先把过期的清掉、再把表压回 [maxNegativeEntries]。 */
    private fun rememberNegative(key: K, reason: String?) {
        // TTL <= 0 表示"失败不记住"（检查用得上，生产不用）：不写表也就不会占位。
        if (negativeTtlMs <= 0L) return
        val moment = now()
        val iterator = negative.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.expiresAt <= moment) iterator.remove()
        }
        negative[key] = Negative(moment + negativeTtlMs, reason)
        while (negative.size > maxNegativeEntries) {
            val oldest = negative.keys.firstOrNull() ?: break
            negative.remove(oldest)
        }
    }
}

/**
 * 正文图片请求的键：`(link, 目标宽度)`。
 *
 * 它是**同一个**类型被两处用：本文件的"正在飞"表，以及 `PiGuestImageTransformer` 的位图
 * 缓存。两处必须指同一张图，而"同一张图"的定义只能是这两个值 —— 所以键不许各写一份。
 *
 * [hashCode] 故意不碰 link 的字符：`data:` URI 的 link 是几 MB 的 base64，
 * `String.hashCode()` 是 O(长度)，仓库里量到过新造的 4 MiB 字符串花 22.8 ms
 * （`docs/scroll-perf-items.md` §2.2），而位图缓存是在**组合里**同步查的。所以哈希只由
 * 宽度和长度算（都是 O(1)），link 本身只在哈希撞上之后才比较 —— 同一个 `String` 实例的
 * 直接命中在 `String.equals` 的第一行就返回。
 *
 * [toString] 也不打印 link：它可能是几 MB 的 base64，也可能是带用户名/密码的 URL
 * （`GuestImageBytes` 对后者一律拒绝、且从不回显）。
 */
internal class PiImageRequestKey(
    val link: String,
    val widthPx: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is PiImageRequestKey && widthPx == other.widthPx && link == other.link

    override fun hashCode(): Int = 31 * widthPx + link.length

    override fun toString(): String = "PiImageRequestKey(linkChars=${link.length}, widthPx=$widthPx)"
}

/**
 * 这次的答案来自负缓存：同一个 `(link, 宽)` 在 [retryAfterMs] 毫秒内失败过，所以**没有
 * 出网**。原因带着上一次的真实失败，靠它 `GuestImageBytes.lastFailure` 才不会撒谎。
 */
internal class PiImageNegativeCacheHit(
    val originalReason: String?,
    val retryAfterMs: Long,
) : Exception(
    "远端图片最近失败过（${retryAfterMs} ms 内不重试，这次不出网）：" +
        (originalReason ?: "上次没留下原因"),
)

/** 生产者超过兜底预算。见 `PiImageRequestPolicy` 的类注释"生产者绝不无限跑"。 */
internal class PiImageProducerTimeout(budgetMs: Long) :
    Exception("图片加载超过兜底预算 ${budgetMs} ms（取消这次加载并记 45 s 负缓存）")

/**
 * 失败结果在内存里记住多久：45 s。
 *
 * 落在要求的 30–60 s 中间：短到"滚动期间别反复重打"够用，长到比一次最坏取字节
 * （15 s 预算 + 5 s 读超时）明显更长 —— 否则用户停在同一个位置时还是会按预算周期重打。
 */
internal const val PI_IMAGE_NEGATIVE_TTL_MS: Long = 45_000L

/**
 * 生产者的兜底预算：60 s。
 *
 * **不是**第二个网络预算：网络那段仍然是 `GuestImageBytes` 的 5 s 连接 / 5 s 读 /
 * 15 s 总预算，这里故意比它宽得多（15 s + 5 s 读超时，再给解码与 `piImageDecodeGate`
 * 排队留余量），所以它绝不会截断一次合法的取字节。它的唯一作用是把"生产者绝不无限跑"
 * 变成这条状态机自身的性质：`read` 阻塞在 socket 上时取消进不去，卡住的那一条终究会被
 * 清掉。
 */
internal const val PI_IMAGE_PRODUCER_BUDGET_MS: Long = 60_000L

/**
 * 负缓存表的条数上限：64。
 *
 * 它按 URL 增长，一次长会话里每个失败 link 都会留下一个 key —— 位图缓存有 24 项 / 32 MiB
 * 的上限，这张表也不能无界。64 条远大于"一屏坏 URL"的量级，所以淘汰只会在病态输入下发生。
 */
internal const val PI_IMAGE_MAX_NEGATIVE_ENTRIES: Int = 64
