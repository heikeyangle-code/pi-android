package app.pi.ui.render

// 正文图片"点一下打开查看器"这条接线在裸 JVM 上的检查。由
// `tools/run-app-pure-checks.sh` 的一项运行（闭包：本文件 + `PiMarkdownImageTap.kt` +
// `:rpc` 的 `Commands.kt`，也就是 `PiImage` 那一行）。
//
// 为什么要有它：用户报的是「URL 的图能渲染，为什么点不了」。修法只有一句话 —— 把
// "这张图的字节"变成查看器要的 `PiImage(base64, mimeType)`，然后交给**已经存在**的那个出口
// （`ChatScreen` 的 `viewedImage`）。这段接线有三个地方编译器看不见，而且错了全是安静的：
//
//   1. 交给查看器的**身份**：`PiImageViewerSource.Wire.cacheKey` 就是 base64 本身，所以
//      "同一批字节 → 同一个串"是"关掉再打开是缓存命中"的前提；空字节必须**没有**可开的图
//      （否则会打开一个 0 字节的文件，查看器画不出来、也没有回退文案）；
//   2. **不许出现第二条取字节的路**：点一次图只能复用 `bridge/GuestImageBytes.kt` 那条
//      （3 许可、磁盘缓存、45 s 负缓存、5/5/15 s 超时）。这一条只能在源码文本上验 —— 本机
//      没有 Android，`GuestImageBytes` 编译不了；
//   3. 那条路上的**数字**这次一个都不许动。同样是读源码文本。
//
// 第 3、4 段的判据是源码文本，所以那两个文件里连注释都不许写出被禁的符号（`HttpURLConnection`
// 之类）；这是有意的：一个真正长出第二网络路径的文件，首先会在 import 上出现那些词。
//
// 手工跑：
//
//   KOTLINC_CP="$(find -L build/pure-checks/kotlinc -name '*.jar' | tr '\n' ':')"; KOTLINC_CP=${KOTLINC_CP%:}
//   LIB_CP="$(find -L build/pure-checks/lib -name '*.jar' | tr '\n' ':')"; LIB_CP=${LIB_CP%:}
//   java -cp "$KOTLINC_CP:$LIB_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib \
//     -jvm-target 17 -classpath "$LIB_CP" -d /tmp/pitap/out \
//     app/src/test/kotlin/app/pi/ui/render/PiMarkdownImageTapCheck.kt \
//     app/src/main/kotlin/app/pi/ui/render/PiMarkdownImageTap.kt \
//     rpc/src/main/kotlin/app/pi/rpc/Commands.kt
//   java -Dpi.repo.root=. -cp "/tmp/pitap/out:$LIB_CP" app.pi.ui.render.PiMarkdownImageTapCheckKt

import java.io.File
import java.util.Base64
import kotlin.system.exitProcess

private val ROOT: File = File(System.getProperty("pi.repo.root") ?: ".")

private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

/** 源码文本的比较要无视缩进 —— 判据是"这个声明还在、数字还是那个"，不是排版。 */
private fun normalized(text: String): String = text.replace(Regex("[ \t]+"), " ")

private fun source(relative: String, section: String): String? {
    val file = File(ROOT, relative)
    if (!file.isFile) {
        failures++
        println("FAIL $section — 读不到源码 $relative（pi.repo.root=${ROOT.absolutePath}）")
        return null
    }
    return normalized(file.readText())
}

fun main() {
    // ------------------------------------------------- 1. 交给查看器的那份 wire 值
    run {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        )

        val payload = PiMarkdownImageTap.viewerImage(png, "image/png")
        check("A1 有字节就有可开的图", payload != null, true)
        check("A2 mime 原样带过去（不在这里推断）", payload?.mimeType, "image/png")
        check(
            "A3 base64 解回来与源字节逐字节相同",
            payload?.let { Base64.getDecoder().decode(it.base64) }?.toList(),
            png.toList(),
        )
        check(
            "A4 字节通道能给的最坏 mime（image/*）也原样带过去",
            PiMarkdownImageTap.viewerImage(png, "image/*")?.mimeType,
            "image/*",
        )

        // 身份：查看器内部把它折成 `PiImageViewerSource.Wire`，而那个来源的 `cacheKey` 就是
        // 这个 base64（`ui/blocks/PiImageViewer.kt:90-92`）；解码的 `produceState` 键里带着它
        // （`:298`）。所以"同一批字节 → 相同的值"= 关掉再打开同一张图是缓存命中。
        val again = PiMarkdownImageTap.viewerImage(png, "image/png")
        check("A5 同一批字节 → 相等的 wire 值（data class）", payload, again)
        check("A6 同一批字节 → 同一个身份（base64）", payload?.base64, again?.base64)
        val other = png.copyOf().also { it[0] = 0x00 }
        check(
            "A7 差一个字节就是另一个身份",
            payload?.base64 != PiMarkdownImageTap.viewerImage(other, "image/png")?.base64,
            true,
        )

        // 空字节 = 没有可打开的图：字节通道自己就是"空 = 失败"
        // （`bridge/GuestImageBytes.kt:346`、`:365`、`:484`），这里照抄，免得出现
        // "打开了一张 0 字节的图、查看器报无法解码"。
        check("A8 空字节没有可开的图", PiMarkdownImageTap.viewerImage(ByteArray(0), "image/png"), null)
    }

    // --------------------------------------------- 2. 编码本身：单行、标准字母表、不截断
    run {
        val single = PiMarkdownImageTap.viewerImage(byteArrayOf(0x41), "image/png")!!.base64
        val two = PiMarkdownImageTap.viewerImage(byteArrayOf(0x41, 0x42), "image/png")!!.base64
        val three = PiMarkdownImageTap.viewerImage(byteArrayOf(0x41, 0x42, 0x43), "image/png")!!.base64

        check("B1 1 字节 → 4 字符 + ==", single, "QQ==")
        check("B2 2 字节 → 4 字符 + =", two, "QUI=")
        check("B3 3 字节 → 4 字符无填充", three, "QUJD")

        // `PiImageViewer` 那一侧用 `android.util.Base64.decode(…, DEFAULT)`（标准字母表、
        // 容忍空白）；这里编出来的串不含任何空白，也没有 URL-safe 的两个字符。
        val big = ByteArray(64 * 1024) { (it * 31 and 0xFF).toByte() }
        val bigPayload = PiMarkdownImageTap.viewerImage(big, "image/jpeg")!!.base64
        check("B4 标准字母表里没有 - 和 _", bigPayload.none { it == '-' || it == '_' }, true)
        check(
            "B5 单行：没有任何空白（CR/LF/TAB/空格）",
            bigPayload.none { it == '\r' || it == '\n' || it == '\t' || it == ' ' },
            true,
        )
        check(
            "B6 长度就是 4×⌈n/3⌉（64 KiB 不截断）",
            bigPayload.length,
            4 * ((big.size + 2) / 3),
        )
        check(
            "B7 64 KiB 解回来逐字节相同",
            Base64.getDecoder().decode(bigPayload).contentEquals(big),
            true,
        )

        // 最贵的那张：字节通道的单张上限是 8 MiB（下面 D 段钉着它），所以点一次图最多
        // 编码这么多字节。这是**真的**把上限那么大的字节交给生产函数走一遍 —— 结果必须是
        // 完整的、单行的、长度正好 4×⌈n/3⌉，它是这笔内存账的上界。
        val capBytes = ByteArray(8 * 1024 * 1024)
        val capPayload = PiMarkdownImageTap.viewerImage(capBytes, "image/png")!!.base64
        check("B8 8 MiB（通道上限）编出来正好 4×⌈n/3⌉ 个字符", capPayload.length, 4 * ((capBytes.size + 2) / 3))
        check(
            "B9 8 MiB 那份解回来逐字节相同（没有截断）",
            Base64.getDecoder().decode(capPayload).contentEquals(capBytes),
            true,
        )

        val all = ByteArray(256) { it.toByte() }
        check(
            "B10 0x00..0xFF 全字节表都编得出来、且解得回来",
            Base64.getDecoder().decode(
                PiMarkdownImageTap.viewerImage(all, "image/bmp")!!.base64,
            ).contentEquals(all),
            true,
        )
    }

    // ------------------------------------- 3. 点一次图不许长出第二条取字节的路（源码文本）
    run {
        val open = "app/src/main/kotlin/app/pi/ui/render/PiMarkdownImageOpen.kt"
        source(open, "C1")?.let { text ->
            check(
                "C1 点按走的还是 GuestImageBytes.load（渲染那条同一个入口）",
                text.contains("GuestImageBytes.load("),
                true,
            )
            // 出现下面任何一个符号，就说明这个文件自己开始取字节/出网了 —— 那正是这次明令
            // 不许的新路。
            for (banned in listOf(
                "openConnection",
                "HttpURLConnection",
                "OkHttp",
                "ContentResolver",
                "openInputStream",
                "java.io.File",
                "java.net.",
            )) {
                check("C2 没有第二条取字节的路：$banned", text.contains(banned), false)
            }
        }

        val components = "app/src/main/kotlin/app/pi/ui/render/PiMarkdownComponents.kt"
        source(components, "C3")?.let { text ->
            check("C3 两个图片槽都接上了点按局部", text.contains("LocalPiImageClick.current"), true)
            check(
                "C4 组件不自己调字节通道（调用只许在 PiMarkdownImageOpen.kt 里）",
                text.contains("GuestImageBytes.load"),
                false,
            )
            check(
                "C5 点按把解析出来、非空的 link 交给那一个取字节函数",
                text.contains("piMarkdownImageViewerImage(context, openLink)"),
                true,
            )
            check(
                "C6 图片槽只在 decoded != null 时包点按（回退文案保持不可点）",
                text.contains("if (decoded != null) {"),
                true,
            )
        }
    }

    // ------------------------------------ 4. 那条路上的数字（源码文本）
    //
    // 这一段原来的标题是「这次一个都不许动」。**两个数后来确实动了**，各有算得出来的理由
    // （下面 D4/D9 各自写着），所以这里改成"钉住当前值"：动这两个数必须同时改这一行，
    // 也就是必须在这里写下一句为什么。其余的数字与结构仍然是"不许动"。
    run {
        val bytes = "app/src/main/kotlin/app/pi/bridge/GuestImageBytes.kt"
        source(bytes, "D1")?.let { text ->
            check("D1 单张上限还是 8 MiB", text.contains("MAX_BYTES = 8 * 1024 * 1024"), true)
            check("D2 连接超时还是 5 s", text.contains("CONNECT_TIMEOUT_MS = 5_000"), true)
            check("D3 读超时还是 5 s", text.contains("READ_TIMEOUT_MS = 5_000"), true)
            // D4：15 s → 45 s。用户截图里那批 Wikimedia 图实测 45 KB–540 KB，而状态栏是
            // 4–18 KB/s，15 s 最多到 270 KB —— 大图在旧预算下**必然**失败，这正是他报的
            // 「有的成功、有的不成功」。
            check("D4 总预算 45 s（15 s 装不下那批量级的图）", text.contains("TOTAL_TIMEOUT_MS = 45_000L"), true)
            check("D5 磁盘缓存还是 64 MiB", text.contains("MAX_DISK_BYTES = 64L * 1024 * 1024"), true)
            check(
                "D6 取字节闸门还是 3 个许可",
                text.contains("MAX_CONCURRENT_REMOTE_FETCHES = 3"),
                true,
            )
            check(
                "D7 闸门真的只用在那 3 个许可上",
                text.contains("Semaphore(MAX_CONCURRENT_REMOTE_FETCHES)"),
                true,
            )
        }

        val policy = "app/src/main/kotlin/app/pi/bridge/PiImageRequestPolicy.kt"
        source(policy, "D8")?.let { text ->
            check("D8 失败负缓存还是 45 s", text.contains("PI_IMAGE_NEGATIVE_TTL_MS: Long = 45_000L"), true)
            // D9：60 s → 300 s。那个计时从生产者**启动**开始，而生产者可能排在取字节闸门
            // 后面（3 个许可，40 张图 = 4 轮），排队时间被算成"加载超时"，于是一张正常的图
            // 被记成失败 —— 用户报的「表格的图渲染出来了，一会再进对话又没了」。
            check("D9 生产者兜底预算 300 s（排队不该算成超时）", text.contains("PI_IMAGE_PRODUCER_BUDGET_MS: Long = 300_000L"), true)
            // D9b：与之配对的一半 —— 超预算**不写负缓存**，否则一次"没轮到我"会换来
            // 45 s 的空白，而那 45 s 之后重试又会排到队尾。
            check(
                "D9b 超预算不写负缓存",
                text.contains("cause != null && cause !is PiImageProducerTimeout"),
                true,
            )
        }


        val transformer = "app/src/main/kotlin/app/pi/bridge/PiGuestImageTransformer.kt"
        source(transformer, "D10")?.let { text ->
            check(
                "D10 渲染那条路的进程级策略还是同一个（这次没动它）",
                text.contains("requestPolicy = PiImageRequestPolicy<PiImageRequestKey, Bitmap>()"),
                true,
            )
        }
    }

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) exitProcess(1)
}
