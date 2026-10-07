package app.pi.ui.screens

/**
 * pi 的 EXIF 方向：**读**（JPEG APP1 / WebP EXIF chunk → TIFF IFD → tag `0x0112`）与
 * **摆正变换**（orientation 1–8 → 一个仿射映射）。
 *
 * ## 为什么这个文件存在，而且是纯 Kotlin
 *
 * `BitmapFactory` 解码**不看 EXIF**，`Bitmap.compress` 重编码**不写 EXIF**。于是手机竖拍的
 * 那张照片（像素 4032×3024 + orientation 6）一旦超过 2000 被 App 重编码，模型收到的就是
 * **侧倒**的图 —— 送出去的方向由这里的两个回答决定，而它们都不需要 `Bitmap`：
 *
 *  1. **像素里写的方向**是什么（[read]）；
 *  2. 把它摆正要做**哪个**置换（[transformFor] → [matrixFor]）。
 *
 * pi 在算尺寸与缩放**之前**就摆正（`utils/image-resize-core.ts:74-76` 调
 * `utils/exif-orientation.ts` 的 `applyExifOrientation`），所以尺寸、3/4 收缩、质量阶梯
 * 全都作用在摆正后的图上。App 要一致，就必须在同一步得到同一个方向值 —— 这就是本文件。
 *
 * 纯 Kotlin（零 Android import）是刻意的：Android 层只能用 `Matrix` 施加 [matrixFor] 那几个
 * 数字，而「读出来的方向对不对」「映射表是不是 pi 那张」这两件事可以在本机 kotlinc 上真跑
 * （`app/src/test/kotlin/app/pi/ui/screens/PiExifOrientationCheck.kt`，期望值全部取自 pi 1.0.3
 * 的 `dist/utils/exif-orientation.js`；1.0.1→1.0.3 该文件逐字节未变）。
 *
 * 没有引入 `androidx.exifinterface`，也没有引入任何解析 EXIF 的库：本仓库对随包依赖逐条登记
 * 许可证，为一个 IFD 走一遍许可证流水线不划算；而且这里要的是**与 pi 逐字节一致**的判定
 * （包括畸形输入上「读不出来就是 1」），不是「更正确」的判定。
 *
 * ## 逐句照抄的是哪一段
 *
 * [read] 是 `exif-orientation.ts:5-121`（`readOrientationFromTiff` /
 * `findJpegTiffOffset` / `findWebpTiffOffset` / `hasExifHeader` / `getExifOrientation`）
 * 的逐行移植，一处刻意的差异写在 [findWebpTiffOffset] 里。语法上要注意的是 JS 的取字节与
 * 位运算语义：**越界读是 0**（`undefined | x` → 0）、小端 `read32` 是**有符号**的 32 位、
 * 大端 `read32` 因为 `>>> 0` 是**无符号**的。两者在畸形文件上会分叉，所以 [read] 用
 * `Long` 表示偏移、并把这两种语义分别照抄，而不是「顺手写正确」。
 *
 * [transformFor] / [matrixFor] 是 `exif-orientation.ts:154-181` 那张 switch 的同一张表：
 * `2/hflip`、`3/hflip+vflip`、`4/vflip`、`5/转置`、`6/顺时针 90°`、`7/反对角`、
 * `8/逆时针 90°`。pi 用 `rotate90` 的 dst 下标函数表达旋转（`:166`、`:171-178`），
 * [matrixFor] 表达的是同一个置换，只是写成 Android `Matrix.setValues` 的仿射矩阵。
 * 两者相等不是断言出来的，是 check 拿 pi 自己跑出来的像素置换逐条比的。
 */
internal object PiExifOrientation {

    // ------------------------------------------------------------------ 读

    /**
     * pi 的 `getExifOrientation`：1–8，**任何**读不出来的情况都回答 1。
     *
     * 「读不出来 = 1」是兜底而不是错误：pi 的 `applyExifOrientation` 对 1 原样返回
     * （`exif-orientation.ts:152`），所以一张没有 EXIF 的图不会被这里判死 —— 这一点必须保持，
     * 否则 App 会因为「读不出方向」而整张失败，那是比侧倒更糟的回归。
     */
    fun read(bytes: ByteArray): Int {
        val tiffOffset = when {
            // JPEG：SOI `FF D8`
            bytes.size >= 2 && at(bytes, 0L) == 0xFF && at(bytes, 1L) == 0xD8 -> findJpegTiffOffset(bytes)
            // WebP：`RIFF....WEBP`
            bytes.size >= 12 &&
                at(bytes, 0L) == 0x52 && at(bytes, 1L) == 0x49 && at(bytes, 2L) == 0x46 && at(bytes, 3L) == 0x46 &&
                at(bytes, 8L) == 0x57 && at(bytes, 9L) == 0x45 && at(bytes, 10L) == 0x42 && at(bytes, 11L) == 0x50 ->
                findWebpTiffOffset(bytes)

            else -> -1L
        }
        if (tiffOffset == -1L) return 1
        return readOrientationFromTiff(bytes, tiffOffset)
    }

    /**
     * `exif-orientation.ts:5-37`。
     *
     * `tiffStart` 用 `Long`：小端 `read32` 可以读出**负**的 IFD 偏移（JS 的 `|` 链是 32 位有
     * 符号），那个负数会被加回 `tiffStart`，于是「负的 IFD 起点」在 JS 里是一条真实路径
     * （越界读 0 → entryCount 0 → 1）。用 `Int` 存偏移会在 4 GiB 那侧把它变成另一条路径。
     */
    private fun readOrientationFromTiff(bytes: ByteArray, tiffStart: Long): Int {
        if (tiffStart + 8 > bytes.size) return 1

        val byteOrder = (at(bytes, tiffStart) shl 8) or at(bytes, tiffStart + 1)
        val le = byteOrder == 0x4949 // "II"；"MM" 与任何别的值都走大端读法（pi 也是）

        // JS 的越界读是 undefined，位运算把它当 0；[at] 就是那个语义。
        fun read16(pos: Long): Int =
            if (le) at(bytes, pos) or (at(bytes, pos + 1) shl 8)
            else (at(bytes, pos) shl 8) or at(bytes, pos + 1)

        fun read32(pos: Long): Long =
            if (le) {
                // 有符号 32 位（JS 不加 `>>> 0`），所以能是负数。
                (at(bytes, pos) or (at(bytes, pos + 1) shl 8) or (at(bytes, pos + 2) shl 16) or (at(bytes, pos + 3) shl 24)).toLong()
            } else {
                // `>>> 0`：无符号 32 位。
                ((at(bytes, pos) shl 24) or (at(bytes, pos + 1) shl 16) or (at(bytes, pos + 2) shl 8) or at(bytes, pos + 3)).toLong() and 0xFFFFFFFFL
            }

        val ifdOffset = read32(tiffStart + 4)
        val ifdStart = tiffStart + ifdOffset
        if (ifdStart + 2 > bytes.size) return 1

        val entryCount = read16(ifdStart)
        var i = 0
        while (i < entryCount) {
            val entryPos = ifdStart + 2 + i * 12L
            if (entryPos + 12 > bytes.size) return 1

            if (read16(entryPos) == 0x0112) {
                val value = read16(entryPos + 8)
                return if (value in 1..8) value else 1
            }
            i++
        }

        return 1
    }

    /**
     * `exif-orientation.ts:39-62`：从 SOI 之后逐段走 marker，遇到第一个带 `Exif\0\0`
     * 的 APP1（`0xE1`）就返回 TIFF 起点（`Exif\0\0` 之后）。
     *
     * 没找到就返回 -1（= 方向 1），没有 EXIF 的普通 JPEG 走的就是这条。
     */
    private fun findJpegTiffOffset(bytes: ByteArray): Long {
        var offset = 2L
        while (offset < bytes.size - 1) {
            if (at(bytes, offset) != 0xFF) return -1
            val marker = at(bytes, offset + 1)
            if (marker == 0xFF) {
                // 填充字节：只前进一个字节（`:44-47`）
                offset++
                continue
            }

            if (marker == 0xE1) {
                if (offset + 4 >= bytes.size) return -1
                val segmentStart = offset + 4
                if (segmentStart + 6 > bytes.size) return -1
                if (hasExifHeader(bytes, segmentStart)) return segmentStart + 6
            }

            if (offset + 4 > bytes.size) return -1
            val length = (at(bytes, offset + 2) shl 8) or at(bytes, offset + 3)
            offset += 2 + length
        }

        return -1
    }

    /**
     * `exif-orientation.ts:64-84`：RIFF chunk 列表里找 `EXIF` chunk。
     *
     * **一处刻意的差异**：chunk 大小是 32 位有符号，负数会让 `offset` 往回走 —— JS 那边是
     * 死循环（永远不返回）。App 不能照抄死循环（用户选一个畸形 WebP 就等于卡死 IO 线程），
     * 所以这里在「没有前进」时按 pi 的兜底语义收尾：读不出来 = -1 = 方向 1。除了这种输入，
     * 两边返回同一个数。
     */
    private fun findWebpTiffOffset(bytes: ByteArray): Long {
        var offset = 12L
        while (offset + 8 <= bytes.size) {
            val chunkId = charArrayOf(
                at(bytes, offset).toChar(),
                at(bytes, offset + 1).toChar(),
                at(bytes, offset + 2).toChar(),
                at(bytes, offset + 3).toChar(),
            ).concatToString()
            val chunkSize = at(bytes, offset + 4) or
                (at(bytes, offset + 5) shl 8) or
                (at(bytes, offset + 6) shl 16) or
                (at(bytes, offset + 7) shl 24)
            val dataStart = offset + 8

            if (chunkId == "EXIF") {
                if (dataStart + chunkSize > bytes.size) return -1
                // 有些 WebP 的 EXIF chunk 前面带一个 "Exif\0\0"（`:74-76`）
                return if (chunkSize >= 6 && hasExifHeader(bytes, dataStart)) dataStart + 6 else dataStart
            }

            // RIFF chunk 补齐到偶数长度。负数 chunkSize 的 `% 2` 在 JS 与 Kotlin 里同号。
            val next = dataStart + chunkSize + (chunkSize % 2)
            if (next <= offset) return -1 // pi 在这里不返回；见本函数 KDoc
            offset = next
        }

        return -1
    }

    /** `exif-orientation.ts:86-95`；越界读是 0，所以天然返回 false。 */
    private fun hasExifHeader(bytes: ByteArray, offset: Long): Boolean =
        at(bytes, offset) == 0x45 &&
            at(bytes, offset + 1) == 0x78 &&
            at(bytes, offset + 2) == 0x69 &&
            at(bytes, offset + 3) == 0x66 &&
            at(bytes, offset + 4) == 0x00 &&
            at(bytes, offset + 5) == 0x00

    /**
     * JS 的 `bytes[pos]`：越界（含负数）读是 `undefined`，而 `undefined` 参加位运算时是 0。
     * 每一处读都走这里，才不会在畸形文件上「顺手写正确」而与 pi 分叉。
     */
    private fun at(bytes: ByteArray, pos: Long): Int =
        if (pos < 0 || pos >= bytes.size) 0 else bytes[pos.toInt()].toInt() and 0xFF

    // ------------------------------------------------------------------ 摆正

    /**
     * `exif-orientation.ts:154-181` 那张 switch 的**同一张表**，一个朝向一个枚举值：
     * 变换做的是「把源像素摆正到显示」，不是「再旋一次让人看着别扭」。
     *
     * 命名对照的是 EXIF 标准里那 8 个取向，[matrixFor] 把它们写成仿射映射。方向 1 与任何
     * 非法值都是 [NONE]（非法值在 [read] 里就已经归 1 了）。
     */
    enum class Transform {
        /** 1：不用动。 */
        NONE,

        /** 2：水平镜像（`photon.fliph`）。 */
        FLIP_H,

        /** 3：旋转 180°（`fliph` + `flipv`）。 */
        ROTATE_180,

        /** 4：垂直镜像（`photon.flipv`）。 */
        FLIP_V,

        /** 5：沿主对角线转置（顺时针 90° 后 `fliph`）。 */
        TRANSPOSE,

        /** 6：顺时针 90°（`rotate90` 的 `x*h + (h-1-y)`）。 */
        ROTATE_90_CW,

        /** 7：沿反对角线（逆时针 90° 后 `fliph`）。 */
        TRANSVERSE,

        /** 8：逆时针 90°（`rotate90` 的 `(w-1-x)*h + y`）。 */
        ROTATE_90_CCW,
    }

    /** [read] 的 1–8 → 变换；非法值当作 1（与 [read] 一致，双保险）。 */
    fun transformFor(orientation: Int): Transform = when (orientation) {
        2 -> Transform.FLIP_H
        3 -> Transform.ROTATE_180
        4 -> Transform.FLIP_V
        5 -> Transform.TRANSPOSE
        6 -> Transform.ROTATE_90_CW
        7 -> Transform.TRANSVERSE
        8 -> Transform.ROTATE_90_CCW
        else -> Transform.NONE
    }

    /**
     * 摆正后是**交换宽高**的吗（5/6/7/8）。
     *
     * 与 [matrixFor] 分开，因为这两件事在不同地方用：尺寸算术（目标尺寸、快速路径）只需要
     * 这一个布尔，`Matrix` 需要那六个数字。
     */
    fun swapsAxes(transform: Transform): Boolean = when (transform) {
        Transform.TRANSPOSE, Transform.ROTATE_90_CW, Transform.TRANSVERSE, Transform.ROTATE_90_CCW -> true
        else -> false
    }

    /**
     * **存储像素**的尺寸 → **摆正后**的尺寸。摆正只改方向，不改像素数：这张图解码出来还是
     * `width × height` 个像素，只是它们被放到别的行/列上。
     *
     * 之所以要单独一个函数：目标尺寸必须用摆正后的长宽去算（pi 的
     * `image-resize-core.ts:78-79` 读的正是摆正后的 `get_width()`/`get_height()`），
     * 而 `BitmapFactory` 的 `inJustDecodeBounds` 给的是存储尺寸 —— 把这两个混在一起，
     * 「竖拍 4032×3024」会算出 2000×1500（横）而不是 2000×2667（竖）。
     */
    fun orientedSize(width: Int, height: Int, transform: Transform): Pair<Int, Int> =
        if (swapsAxes(transform)) height to width else width to height

    /**
     * 摆正映射，写成 Android `Matrix.setValues` 的 9 个值：
     * `dstX = a·srcX + b·srcY + c`、`dstY = d·srcX + e·srcY + f`，最后一行 `0 0 1`。
     *
     * 常数项（`c`/`f`）是 `w-1` / `h-1` 而不是 `w` / `h`：位移按**像素中心**对齐时，只有
     * `w-1` 才把源像素的中心映到目标像素的中心（源像素 `h-1` 的中心是 `h-0.5`，映射后是
     * `0.5` = 第 0 个像素的中心）。用 `w` 会整张图偏半个像素，用 `filter = false` 时表现为
     * 一整行的黑边。所以调用方必须 `Bitmap.createBitmap(src, 0, 0, w, h, matrix, false)`：
     * 这六个数字是纯置换，重采样只会把置换弄脏。
     *
     * 映射表本身与 pi 的 `rotate90` 下标函数一一对应，`PiExifOrientationCheck` 拿 pi 跑出来的
     * 像素置换逐条比对。
     */
    fun matrixFor(transform: Transform, width: Int, height: Int): FloatArray {
        val w = (width - 1).toFloat()
        val h = (height - 1).toFloat()
        return when (transform) {
            Transform.NONE -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            // X = w-1-x
            Transform.FLIP_H -> floatArrayOf(-1f, 0f, w, 0f, 1f, 0f, 0f, 0f, 1f)
            // X = w-1-x, Y = h-1-y
            Transform.ROTATE_180 -> floatArrayOf(-1f, 0f, w, 0f, -1f, h, 0f, 0f, 1f)
            // Y = h-1-y
            Transform.FLIP_V -> floatArrayOf(1f, 0f, 0f, 0f, -1f, h, 0f, 0f, 1f)
            // X = y, Y = x
            Transform.TRANSPOSE -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            // X = h-1-y, Y = x
            Transform.ROTATE_90_CW -> floatArrayOf(0f, -1f, h, 1f, 0f, 0f, 0f, 0f, 1f)
            // X = h-1-y, Y = w-1-x
            Transform.TRANSVERSE -> floatArrayOf(0f, -1f, h, -1f, 0f, w, 0f, 0f, 1f)
            // X = y, Y = w-1-x
            Transform.ROTATE_90_CCW -> floatArrayOf(0f, 1f, 0f, -1f, 0f, w, 0f, 0f, 1f)
        }
    }
}
