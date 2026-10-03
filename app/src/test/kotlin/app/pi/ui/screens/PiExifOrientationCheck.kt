package app.pi.ui.screens

import java.io.File

// Bare-JVM harness for `PiExifOrientation`. 注册进 `tools/run-app-pure-checks.sh`（父 agent 做），
// 用法与 `AttachmentBudgetCheck` 相同：编译 `PiExifOrientation.kt` + 本文件，`main()` 真跑。
// 需要 `-Dpi.repo.root=<repo>`（源文本那几节要用，与 `AttachmentBudgetCheck` 同法）。
//
// ## 它在钉什么，为什么每一组都值得钉
//
//  1. **方向值本身与 pi 逐条一致。** 26 个夹具（14 个真 EXIF + 2 个非法取值 + 10 个畸形/截断）的
//     期望值**不是手写的**，是 pi 1.0.1 自己的
//     `dist/utils/exif-orientation.js` 跑出来的：把 tag `0x0112` 依次写成 1..8 得到 8 个
//     「探针」，每个探针经过 `applyExifOrientation` 后的像素置换就是这个取值的**可观测行为**；
//     夹具的期望值 = 与它置换相同的那个探针编号。所以「期望 6」不是一个抄来的数字，而是
//     「pi 在这份字节上做的置换与 pi 在 orientation 6 上做的置换完全相同」。
//     重新生成的办法（`/tmp/pi-exif-work/fixtures.mjs`，用 `--kotlin` 打印本文件的字面量）：
//
//         node fixtures.mjs --kotlin
//
//  2. **映射表是 pi 那张。** [permutationSignature] 拿 `matrixFor` 的六个数字对 4×3 网格做
//     正向映射，输出形如 `3x4:8,4,0,…` 的置换字符串；字符串来自 pi，比对在这里。它同时证明
//     方向 1–8 每一个都是**双射**（每个目标像素恰好被写一次，没有落空的格子）。
//  3. **「读不出来 = 1」而不是整张失败。** 那 12 个非法/畸形输入的夹具（非法取值 0/9、
//     entryCount=0、没有 `0x0112`、TIFF 头被截、IFD 表被截、IFD 偏移溢出/为负/超出文件、
//     APP1 段长被截、`Exif\0\0` 被截、WebP 没有 EXIF chunk）期望全是 1 —— 与 pi 的兜底一致。
//  4. **换向与尺寸。** `swapsAxes`/`orientedSize` 是「用摆正后的长宽去算目标尺寸」这句的
//     全部实现，方向 5/6/7/8 必须换、1/2/3/4 必须不换。
//  5. **顺序**（源文本）。`ChatScreen` 导 Compose/Android，本机编不了，所以「先读方向、再决定
//     快速路径、再进编码计划」只能读源文本 —— 与 `AttachmentBudgetCheck` 的 (e) 节同法。
//     断言的是**形状**：`PiExifOrientation.read` 出现且只有一次、它排在原字节快速返回之前、
//     也排在 `attemptPlan` 之前，而 `attemptPlan` 拿到的两个尺寸参数是 `width, height`
//     （摆正后的），不是 `storedWidth, storedHeight`。
//
// ## 它查不了什么
//
//  - `Matrix` 自己。本机没有 Android，`Bitmap.createBitmap(..., matrix, false)` 那一步只能在
//    设备/CI 上验；这里能钉的是**喂给它的数字**（第 2 组），那是唯一有判断余地的一半。
//  - `BitmapFactory` 对同一份字节报出来的宽高。夹具是**只有 marker 骨架**的 JPEG，没有像素
//    数据 —— 解析器只走 marker/IFD，所以够；解码要真图，那是设备问题。
//
// 纯 Kotlin stdlib：`PiExifOrientation.kt` 一个 import 都没有，本文件只用 `java.io.File`
// 读源文本。任何一个长出 Android import，这里就编译失败，这正是目的。

private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private fun checkTrue(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        println("PASS $name")
        return
    }
    failures++
    if (detail.isEmpty()) println("FAIL $name") else println("FAIL $name\n  $detail")
}

/** 一个夹具：名字、字节（hex）、pi 判定的方向。 */
private class Fixture(val name: String, val hex: String, val orientation: Int)

private fun hex(text: String): ByteArray {
    require(text.length % 2 == 0) { "hex 长度必须是偶数: ${text.length}" }
    return ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

// ---------------------------------------------------------------- 夹具（期望值取自 pi 1.0.1）
//
// 生成方式见文件头。分三类：真 EXIF（1..8、大端、WebP、XMP 在前、0xFF 填充）、
// 非法取值（0/9）、畸形/截断（期望全是 1）。

private val FIXTURES = listOf(
    // ---- 真 EXIF ----
    Fixture("无 EXIF 的普通 JPEG（SOI + JFIF + SOS）", "ffd8ffe000104a46494600010100000100010000ffda0008010203040506ffd9", 1),
    Fixture("orientation=6 的竖拍 JPEG（2200×1400 那张的形状）", "ffd8ffe1002245786966000049492a0008000000010012010300010000000600000000000000ffda0008010203040506ffd9", 6),
    Fixture("orientation=3", "ffd8ffe1002245786966000049492a0008000000010012010300010000000300000000000000ffda0008010203040506ffd9", 3),
    Fixture("orientation=8", "ffd8ffe1002245786966000049492a0008000000010012010300010000000800000000000000ffda0008010203040506ffd9", 8),
    Fixture("orientation=1（显式写 1）", "ffd8ffe1002245786966000049492a0008000000010012010300010000000100000000000000ffda0008010203040506ffd9", 1),
    Fixture("orientation=2", "ffd8ffe1002245786966000049492a0008000000010012010300010000000200000000000000ffda0008010203040506ffd9", 2),
    Fixture("orientation=4", "ffd8ffe1002245786966000049492a0008000000010012010300010000000400000000000000ffda0008010203040506ffd9", 4),
    Fixture("orientation=5", "ffd8ffe1002245786966000049492a0008000000010012010300010000000500000000000000ffda0008010203040506ffd9", 5),
    Fixture("orientation=7", "ffd8ffe1002245786966000049492a0008000000010012010300010000000700000000000000ffda0008010203040506ffd9", 7),
    Fixture("大端 TIFF（MM），orientation=6", "ffd8ffe100224578696600004d4d002a00000008000101120003000000010006000000000000ffda0008010203040506ffd9", 6),
    Fixture("0xFF 填充字节之后才是 APP1，orientation=6", "ffd8ffffffffe1002245786966000049492a0008000000010012010300010000000600000000000000ffda0008010203040506ffd9", 6),
    Fixture("先一个非 EXIF 的 APP1（XMP 形状），再带 EXIF 的 APP1，6", "ffd8ffe10011687474703a2f2f6e732e61646f6265ffe1002245786966000049492a0008000000010012010300010000000600000000000000ffda0008010203040506ffd9", 6),
    Fixture("WebP EXIF chunk（裸 TIFF），6", "524946463200000057454250565038200400000001020304455849461a00000049492a0008000000010012010300010000000600000000000000", 6),
    Fixture("WebP EXIF chunk（带 \"Exif\\0\\0\" 前缀），6", "524946462c00000057454250455849462000000045786966000049492a0008000000010012010300010000000600000000000000", 6),
    Fixture("WebP 没有 EXIF chunk", "524946461000000057454250565038200400000001020304", 1),
    // ---- 非法取值 ----
    Fixture("orientation=9（超出 1–8）", "ffd8ffe1002245786966000049492a0008000000010012010300010000000900000000000000ffda0008010203040506ffd9", 1),
    Fixture("orientation=0", "ffd8ffe1002245786966000049492a0008000000010012010300010000000000000000000000ffda0008010203040506ffd9", 1),
    // ---- 畸形 / 截断（pi 全部回答 1）----
    Fixture("IFD entryCount=0", "ffd8ffe1001645786966000049492a0008000000000000000000ffda0008010203040506ffd9", 1),
    Fixture("TIFF 头被截断（只有 4 字节）", "ffd8ffe1000c45786966000049492a00ffda0008010203040506ffd9", 1),
    Fixture("IFD 表被截断（说 3 条 entry，只给一条半）", "ffd8ffe1001845786966000049492a00080000000300130103000100ffda0008010203040506ffd9", 1),
    Fixture("有 IFD 但没有 0x0112（只有 0x0113）", "ffd8ffe1002245786966000049492a0008000000010013010300010000000600000000000000ffda0008010203040506ffd9", 1),
    Fixture("IFD 偏移 0xFFFFFFFF（大端，JS 读到 4294967295）", "ffd8ffe100224578696600004d4d002affffffff000101120003000000010006000000000000ffda0008010203040506ffd9", 1),
    Fixture("IFD 偏移 0xFFFFFFFF（小端，JS 读到 -1）", "ffd8ffe1002245786966000049492a00ffffffff010012010300010000000600000000000000ffda0008010203040506ffd9", 1),
    Fixture("IFD 偏移指到文件末尾之外", "ffd8ffe1002245786966000049492a00f0000000010012010300010000000600000000000000ffda0008010203040506ffd9", 1),
    Fixture("APP1 头/段长被截断（FF D8 FF E1 00 10 45 78）", "ffd8ffe100104578", 1),
    Fixture("APP1 的 \"Exif\\0\\0\" 头被截断", "ffd8ffe1000645786966ffda0008010203040506ffd9", 1),
)

// ---------------------------------------------------------------- pi 的像素置换期望值
//
// 4×3 源网格，R 通道 = 线性下标；`applyExifOrientation` 之后把它按 (目标宽×高) 读成一串下标。
// 这 8 行是 pi 1.0.1 在探针 1..8 上的原样输出（文件头的 node 命令可重生成）。

private val PERMUTATIONS = mapOf(
    1 to "4x3:0,1,2,3,4,5,6,7,8,9,10,11",
    2 to "4x3:3,2,1,0,7,6,5,4,11,10,9,8",
    3 to "4x3:11,10,9,8,7,6,5,4,3,2,1,0",
    4 to "4x3:8,9,10,11,4,5,6,7,0,1,2,3",
    5 to "3x4:0,4,8,1,5,9,2,6,10,3,7,11",
    6 to "3x4:8,4,0,9,5,1,10,6,2,11,7,3",
    7 to "3x4:11,7,3,10,6,2,9,5,1,8,4,0",
    8 to "3x4:3,7,11,2,6,10,1,5,9,0,4,8",
)

private const val SRC_W = 4
private const val SRC_H = 3

/**
 * `matrixFor` 的六个数字作用在 4×3 网格上的结果，形如 `3x4:8,4,0,…`。
 *
 * 落点不是整数、或落到目标网格之外，就返回 `null` —— 那是「常数项写错了」的可见形态，
 * 比拿着一个凑出来的字符串去比对诚实。
 */
private fun permutationSignature(orientation: Int): String? {
    val transform = PiExifOrientation.transformFor(orientation)
    val m = PiExifOrientation.matrixFor(transform, SRC_W, SRC_H)
    val (dstW, dstH) = PiExifOrientation.orientedSize(SRC_W, SRC_H, transform)
    val slots = IntArray(dstW * dstH) { -1 }
    for (y in 0 until SRC_H) {
        for (x in 0 until SRC_W) {
            val dstX = m[0] * x + m[1] * y + m[2]
            val dstY = m[3] * x + m[4] * y + m[5]
            if (dstX % 1f != 0f || dstY % 1f != 0f) return null
            val index = dstY.toInt() * dstW + dstX.toInt()
            if (index !in slots.indices) return null
            slots[index] = y * SRC_W + x
        }
    }
    return "${dstW}x${dstH}:" + slots.joinToString(",")
}

fun main() {
    // ---------------------------------------- 1. 方向值：夹具 → pi 判定值
    println("--- 1. 夹具的 pi 判定值 vs PiExifOrientation.read ---")
    for (fixture in FIXTURES) {
        check("${fixture.name} → ${fixture.orientation}", PiExifOrientation.read(hex(fixture.hex)), fixture.orientation)
    }

    // ---------------------------------------- 2. 映射表：与 pi 的像素置换逐条比
    println("--- 2. matrixFor 的置换 vs pi 的置换 ---")
    for (orientation in 1..8) {
        val signature = permutationSignature(orientation)
        check("orientation $orientation 的置换", signature, PERMUTATIONS[orientation])
    }
    // 双射：每个目标像素恰好被写一次（置换字符串相等时也成立，但这条给出独立的失败信息）。
    for (orientation in 1..8) {
        val transform = PiExifOrientation.transformFor(orientation)
        val (dstW, dstH) = PiExifOrientation.orientedSize(SRC_W, SRC_H, transform)
        val slots = IntArray(dstW * dstH) { -1 }
        val m = PiExifOrientation.matrixFor(transform, SRC_W, SRC_H)
        for (y in 0 until SRC_H) {
            for (x in 0 until SRC_W) {
                slots[(m[3] * x + m[4] * y + m[5]).toInt() * dstW + (m[0] * x + m[1] * y + m[2]).toInt()] = y * SRC_W + x
            }
        }
        checkTrue(
            "orientation $orientation 是双射（目标网格每个格子都被写到，且只用一次）",
            slots.toSet() == (0 until SRC_W * SRC_H).toSet(),
            "slots=$slots",
        )
    }

    // ---------------------------------------- 3. 换向 / 尺寸
    println("--- 3. swapsAxes / orientedSize ---")
    check(
        "只有 5/6/7/8 换宽高",
        (1..8).map { PiExifOrientation.swapsAxes(PiExifOrientation.transformFor(it)) },
        listOf(false, false, false, false, true, true, true, true),
    )
    check(
        "竖拍 2200×1400 + EXIF 6 → 摆正后 1400×2200",
        PiExifOrientation.orientedSize(2200, 1400, PiExifOrientation.transformFor(6)),
        1400 to 2200,
    )
    check(
        "方向 1 不动尺寸",
        PiExifOrientation.orientedSize(2200, 1400, PiExifOrientation.transformFor(1)),
        2200 to 1400,
    )
    check(
        "非法方向当作 1",
        PiExifOrientation.transformFor(0) to PiExifOrientation.transformFor(9),
        PiExifOrientation.Transform.NONE to PiExifOrientation.Transform.NONE,
    )

    // ---------------------------------------- 4. 顺序（源文本）
    println("--- 4. ChatScreen 的顺序（源文本） ---")
    val root = System.getProperty("pi.repo.root")
    checkTrue("pi.repo.root 已传入（与 AttachmentBudgetCheck 同法）", !root.isNullOrBlank(), "root=$root")
    val chatText = root?.let { File(it, "app/src/main/kotlin/app/pi/ui/screens/ChatScreen.kt") }
        ?.takeIf { it.isFile }?.readText().orEmpty()
    checkTrue("ChatScreen.kt 读得到", chatText.isNotEmpty())

    val readCalls = Regex("PiExifOrientation\\.read\\(bytes\\)").findAll(chatText).toList()
    check("读方向恰好一处", readCalls.size, 1)
    check("读方向恰好一次", Regex("PiExifOrientation\\.transformFor\\(orientation\\)").findAll(chatText).count(), 1)
    check(
        "摆正后的尺寸恰好算一次",
        Regex("PiExifOrientation\\.orientedSize\\(storedWidth, storedHeight, transform\\)").findAll(chatText).count(),
        1,
    )
    check(
        "Matrix 的九个数字来自 matrixFor",
        Regex("setValues\\(PiExifOrientation\\.matrixFor\\(transform, source\\.width, source\\.height\\)\\)").findAll(chatText).count(),
        1,
    )
    check(
        "摆正用 filter=false（置换不该被重采样）",
        Regex("Bitmap\\.createBitmap\\(source, 0, 0, source\\.width, source\\.height, matrix, false\\)").findAll(chatText).count(),
        1,
    )
    val fastPathReturns = Regex(
        "return PiImage\\(Base64\\.encodeToString\\(bytes, Base64\\.NO_WRAP\\), mime\\)",
    ).findAll(chatText).toList()
    // 两处：`compressAttachment` 的快速路径，与 `images.autoResize: false` 那一支
    // （`withAutoResizeOff`）。两处都原字节转发；第一处用的是摆正后的长宽，第二处虽然不摆正
    // （pi 关掉缩放时也不摆正），方向仍在它之前就解析出来、并传了进去。所以下面比的是**第一处**。
    check("原字节转发恰好两处（快速路径 + autoResize 关掉那一支）", fastPathReturns.size, 2)
    // `.+` 而不是 `[^)]*`：参数里可能有括号，而 `.` 不跨行，所以这一条钉在**一行** call site 上。
    val planCalls = Regex("for \\(attempt in AttachmentBudget\\.attemptPlan\\((.+)\\)\\)").findAll(chatText).toList()
    check("编码计划恰好一处", planCalls.size, 1)

    if (readCalls.size == 1 && fastPathReturns.size == 2 && planCalls.size == 1) {
        val readAt = readCalls.first().range.first
        checkTrue(
            "先读方向，再决定快速路径（快速路径用的是摆正后的长宽）",
            readAt < fastPathReturns.first().range.first,
            "read=$readAt fastPath=${fastPathReturns.first().range.first}",
        )
        checkTrue(
            "先读方向，再进编码计划",
            readAt < planCalls.first().range.first,
            "read=$readAt plan=${planCalls.first().range.first}",
        )
        val args = planCalls.first().groupValues[1]
        checkTrue(
            "编码计划拿到的是摆正后的 width/height",
            args.contains("width") && args.contains("height") && !args.contains("stored"),
            "attemptPlan($args)",
        )
    } else {
        failures++
        println("FAIL 顺序断言：调用点的数量不是期望值，无法比较先后")
    }

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
