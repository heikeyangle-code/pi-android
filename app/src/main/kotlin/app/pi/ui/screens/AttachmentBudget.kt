package app.pi.ui.screens

import app.pi.rpc.JsonlFramer

/**
 * The arithmetic behind one message's inline images, and **only** the arithmetic.
 *
 * ## Why this is not in `ChatScreen`
 *
 * Decoding, scaling and encoding a picture is `Bitmap`/`BitmapFactory`/`Base64` work and
 * cannot leave the UI layer; deciding *which* sizes and encodings to try, how many
 * characters a base64 payload costs, and whether one more image fits in the message is
 * arithmetic, and arithmetic is the half that can be executed without a phone.
 * `ChatScreen` keeps the codec and calls this object for every decision — it contains no
 * threshold of its own, so the numbers below have one home and the
 * `image-attachment-budget` harness compiles this file **without** Android or Compose.
 *
 * ## pi's own limits, transcribed
 *
 * pi normalizes every inline image through `resizeImageInProcess`
 * (`packages/coding-agent/src/utils/image-resize-core.ts`, pinned engine 0.86.1; the
 * `:82-93`/`:95-106`/`:112-114`/`:122`/`:146-150` ranges below were re-checked against
 * the 0.86.1 source, where they are unchanged):
 *
 *  1. if the picture is already within `maxWidth`/`maxHeight` **and** its
 *     `ceil(bytes / 3) * 4` base64 is under `maxBytes`, pi sends the original bytes
 *     unchanged — no re-encode at all (`:82-93`);
 *  2. otherwise it clamps the long edge to [PI_MAX_DIMENSION] (`:95-106`), then loops:
 *     encode at the current size and take the first candidate under `maxBytes`, else
 *     shrink both axes by three quarters ([SHRINK_NUMERATOR]/[SHRINK_DENOMINATOR],
 *     `:146-150`) and try again, down to 1×1.
 *
 * The candidate order at one size is pi's: PNG first, then JPEG at
 * [PI_JPEG_QUALITIES] (`:112-114`, `:122`). **One deliberate difference**, and only one:
 * pi pushes that PNG candidate for **every** source that reaches the resize loop,
 * including an opaque camera JPEG, while [pngFirst] asks for it only when the source MIME
 * is `image/png` **or** the decoded bitmap reports alpha.
 *
 * The reason is Android's encoder: a PNG encode of a 2000×2000 photo is both slow and
 * larger than the JPEG the loop would fall through to, so paying it unconditionally costs
 * one full-size encode per picked photo to almost never win. The residual, user-visible
 * difference is therefore exactly this: an **opaque, non-PNG source that needs a
 * re-encode** — a camera JPEG, a HEIC or BMP or WebP that pi would have converted to PNG
 * first (`image-process.ts:49-65`) — is offered the JPEG ladder straight away, so the app
 * may send a JPEG where pi would have sent a PNG. For a photograph that is a difference
 * in name only: it is the one class that could not have benefited, because the PNG of it
 * is larger. Fast path 1 is untouched by this: a PNG/JPEG/GIF/WebP already within both
 * limits is still forwarded **byte for byte**, so an opaque PNG under the limits is still
 * sent as the original PNG.
 *
 * `maxBytes` is 4.5 MB of **base64 characters** — `4.5 * 1024 * 1024`, compared against
 * the encoded string's length, not the picture's byte count (`:22`, `:129`) — and the
 * encoded-to-raw conversion everywhere is `chars / 4 * 3`.
 *
 * ## The message budget, derived from the transport cap
 *
 * An attachment is echoed back inside the records the app has to read: the
 * `message_start`/`message_end` events, and one `get_entries` entry. [JsonlFramer]
 * discards any single record longer than [JsonlFramer.DEFAULT_MAX_RECORD_CHARS], so a
 * message whose own echo the app cannot read is a message the app must not send — and
 * the same message becomes **one line** of the session file, which
 * `SessionFileReader.DEFAULT_MAX_LINE_CHARS` (the same value) has to admit when the
 * conversation is opened again.
 *
 * So the limit is [MESSAGE_BASE64_CHARS] of base64 **for the whole message**, not per
 * image: the record cap minus [FRAMING_SLACK_CHARS] for everything in the record that is
 * not the payload — entry id, role, timestamp, MIME types, the `message` wrapper and the
 * JSON punctuation. [MESSAGE_BYTES] is the same budget in the unit the copy speaks
 * ([base64CharsToBytes]). The slack is also all the room the message **text** has; a
 * prompt longer than 64 KiB is not charged to this budget, exactly as it was not before
 * (the composer has no length limit of its own).
 *
 * The end-to-end numbers at today's [JsonlFramer.DEFAULT_MAX_RECORD_CHARS] of 32 MiB:
 *
 *  - [MESSAGE_BASE64_CHARS] = 33 488 896 base64 characters = 31.94 MiB;
 *  - [MESSAGE_BYTES] = 25 116 672 bytes = 23.95 MiB — the number the copy shows;
 *  - one image at pi's own ceiling is [PI_MAX_BASE64_CHARS] = 4 718 592 base64
 *    characters = [PI_MAX_BYTES] = 3 538 944 bytes = 3.375 MiB of file, and
 *    [MAX_PI_SIZED_IMAGES] = **7** of them fit in one message
 *    (7 × 4 718 592 = 33 030 144 ≤ 33 488 896; 8 × = 37 748 736 > 33 488 896);
 *  - an ordinary 1–3 MB phone photo is one image and costs 1.4–4 MB of base64, so the
 *    composer can stage seven of them and the eighth is refused with the remaining room.
 *
 * Before this existed the cap was per image and 5.95 MB, and nothing re-encoded: two
 * 5 MB photos in one message were 16 MB of base64, over the framer's old 8 MiB record
 * cap *and* over the reader's old 8 MiB line cap, so the user's own message could not be
 * read back and the conversation would not open. The per-message sum is the number that
 * has to be checked; a per-image ceiling alone cannot express it.
 */
internal object AttachmentBudget {

    // ---------------------------------------------------------------- pi's limits

    /** pi's `maxWidth`/`maxHeight` (`image-resize-core.ts:5-6`, `:24-29`). */
    const val PI_MAX_DIMENSION = 2000

    /**
     * pi's `DEFAULT_MAX_BYTES`: 4.5 MiB counted in **base64 characters**
     * (`image-resize-core.ts:22`).
     */
    const val PI_MAX_BASE64_CHARS = 4_718_592 // 4.5 * 1024 * 1024

    /** pi's default JPEG quality, tried first (`image-resize-core.ts:8`, `:122`). */
    const val PI_JPEG_QUALITY = 80

    /**
     * pi's quality ladder, in pi's order: `Array.from(new Set([jpegQuality, 85, 70, 55,
     * 40]))` with `jpegQuality = 80` (`image-resize-core.ts:122`) — so 80 first and then
     * the fixed steps, 85 included because it is the second element of the set.
     */
    val PI_JPEG_QUALITIES = intArrayOf(80, 85, 70, 55, 40)

    /** pi's per-round shrink factor: `Math.floor(axis * 0.75)`, floored at 1 (`:146-147`). */
    const val SHRINK_NUMERATOR = 3
    const val SHRINK_DENOMINATOR = 4

    // ---------------------------------------------------------- the record's budget

    /**
     * Headroom for everything in a record that is not the base64 payload. 64 KiB:
     * entry id, role, timestamp, MIME type, the `message` wrapper, JSON punctuation,
     * and the message's own text.
     */
    const val FRAMING_SLACK_CHARS = 64 * 1024

    /**
     * Base64 characters **one message's** images may occupy. See the class KDoc.
     *
     * Derived, never chosen: raising [JsonlFramer.DEFAULT_MAX_RECORD_CHARS] raises this,
     * because a budget that stayed behind would allow a message whose own echo the
     * framer drops.
     */
    const val MESSAGE_BASE64_CHARS = JsonlFramer.DEFAULT_MAX_RECORD_CHARS - FRAMING_SLACK_CHARS

    /** [MESSAGE_BASE64_CHARS] in the bytes it encodes — the number the copy shows. */
    const val MESSAGE_BYTES = MESSAGE_BASE64_CHARS / 4 * 3

    /** How many pi-maximum images fit in one message. 7 at the 32 MiB record cap. */
    const val MAX_PI_SIZED_IMAGES = MESSAGE_BASE64_CHARS / PI_MAX_BASE64_CHARS

    /**
     * One image at pi's ceiling, in bytes: 3 538 944 (3.375 MiB).
     *
     * Not `const` because it is computed from [PI_MAX_BASE64_CHARS] by the same
     * conversion every other number here uses.
     */
    val PI_MAX_BYTES = PI_MAX_BASE64_CHARS / 4 * 3

    /**
     * The most bytes the app will read from one picked file before decoding it.
     *
     * **Not** pi's limit and not the message's: pi's `resizeImageInProcess` is handed a
     * byte slice and has no input bound at all, and a picture larger than this can still
     * shrink to a few hundred kilobytes. This is the app's own memory guard, because
     * `BitmapFactory` needs the encoded bytes in hand and a cloud provider will happily
     * offer hundreds of megabytes (the read used to `readBytes()` the whole stream and
     * allocate it twice). The value is the widest bound already in the system — one whole
     * frame — rather than a new number.
     */
    const val MAX_PICKED_IMAGE_BYTES = JsonlFramer.DEFAULT_MAX_RECORD_CHARS

    // ------------------------------------------------- 工作区复制：上限只有磁盘

    /**
     * 往工作区复制时要留出的磁盘余量：16 MiB。
     *
     * **为什么要有余量，而不是「写到写不动为止」。** 目标目录是**整个 App 私有存储**：
     * 引擎（proot 的 rootfs、会话 JSONL、临时文件）、主题、附件共用它。把可用空间读到最后一
     * 字节再写，等于替 pi 把它的下一次写盘挤掉 —— 用户看到的是「复制成功了，然后引擎在别处
     * 报错」。16 MiB 是「一次会话落盘 + 一份 rootfs 临时文件」的量级。
     *
     * **为什么不复用 `runtime/RuntimeSpaceBudget`。** 读过它（只读）：那个 `MIN_REQUIRED_BYTES`
     * 是 480 MiB，回答的是「这三个解包产物能不能铺开」——一个与附件无关的问题。拿它当这里的
     * 余量，会在还有 400 MiB 空闲的机器上拒绝复制一个 2 KB 的文件，把「留点余量」变成
     * 「不许用磁盘」。两个数各有各的读者，所以不引用、也不改那个文件（它在别的批次手里）。
     */
    const val WORKSPACE_SPACE_MARGIN_BYTES: Long = 16L * 1024 * 1024

    /**
     * 工作区复制**现在**还能不能继续写。这是这条路唯一的上限。
     *
     * **它与 [MESSAGE_BYTES] 无关，两者不是同一个数、也不是同一件事。** [MESSAGE_BYTES]
     * 管的是「多少 base64 字符会随消息**内联**进 JSONL」，理由是单条记录超过
     * [JsonlFramer.DEFAULT_MAX_RECORD_CHARS] 会被丢弃；而复制进工作区的产物只是 composer 里
     * 的**一个相对路径**（`attachments/<name>`，十几个字节），agent 是自己从磁盘读它的。
     * 拿内联预算去卡一次工作区复制，就是「这条消息装不下这张图，所以文件也不给你放进
     * agent 能读到的地方」——用户报的正是这个：「发个文件还说太大，复制不进去」。
     *
     * 三种回答，第三种是诚实的那一半：文件系统读不出可用空间时**不假装知道**
     * （[Unknown]），照写 —— 真的写不下时 `write` 会抛出来，那是
     * `WorkspaceCopy.WriteFailed`，好过一个编出来的数字。
     *
     * @param neededBytes 这一次写入之后的总字节数（已写入 + 本块读到的字节数）。
     * @param availableBytes 目标目录所在文件系统的可用字节数；null 表示读不出来。
     * @param marginBytes 见 [WORKSPACE_SPACE_MARGIN_BYTES]。
     */
    sealed interface DiskSpace {
        /** [neededBytes] 装得下，而且留得住余量。 */
        data object Room : DiskSpace

        /**
         * 装不下。两个数都来自调用方读到的事实，所以「至少需要 X，可用 Y」这句话与判定
         * 不可能对不上；[neededBytes] 是**下界**（源确实给出了这么多字节），所以文案里说的是
         * 「至少」。
         */
        data class Insufficient(val neededBytes: Long, val availableBytes: Long) : DiskSpace

        /** 文件系统没说可用空间是多少（`StatFs` 读不到）——不据此拒绝。 */
        data object Unknown : DiskSpace
    }

    /**
     * 这一块能不能写下去的**唯一**判定：[DiskSpace]。
     *
     * 规则就是 `neededBytes <= availableBytes - marginBytes`。余量比可用空间还大时 `room` 是 0，
     * 于是空闲不足 [WORKSPACE_SPACE_MARGIN_BYTES] 的机器会拒绝每一次复制，而不是把自己写满。
     */
    fun workspaceSpace(
        neededBytes: Long,
        availableBytes: Long?,
        marginBytes: Long = WORKSPACE_SPACE_MARGIN_BYTES,
    ): DiskSpace {
        if (availableBytes == null) return DiskSpace.Unknown
        val room = (availableBytes - marginBytes).coerceAtLeast(0L)
        return if (neededBytes <= room) {
            DiskSpace.Room
        } else {
            DiskSpace.Insufficient(neededBytes, availableBytes)
        }
    }

    // -------------------------------------------------------------- the arithmetic

    /**
     * pi's `inputBase64Size`: `Math.ceil(byteCount / 3) * 4` (`image-resize-core.ts:65`),
     * which is the exact length of the padded base64 of [byteCount] bytes.
     */
    fun base64Chars(byteCount: Int): Int = (byteCount + 2) / 3 * 4

    /** [chars] of base64 back in bytes: `chars / 4 * 3`, rounded down. */
    fun base64CharsToBytes(chars: Int): Int = chars / 4 * 3

    /** One resize target, in pixels. */
    data class Target(val width: Int, val height: Int)

    /** One encoding of one resize target. */
    sealed interface Encoding {
        /** pi's `resized.get_bytes()` — lossless, and the only alpha-preserving choice. */
        data object Png : Encoding

        /** pi's `resized.get_bytes_jpeg(quality)`. */
        data class Jpeg(val quality: Int) : Encoding
    }

    /** One thing to try: resize to ([width], [height]) and encode as [encoding]. */
    data class Attempt(val width: Int, val height: Int, val encoding: Encoding)

    /**
     * pi's first resize target, copied step for step from `image-resize-core.ts:95-106`:
     * clamp the width, then clamp the height against the already-clamped width, so the
     * second clamp can round the aspect ratio once. [PI_MAX_DIMENSION] is both the width
     * and the height limit.
     */
    fun initialTarget(width: Int, height: Int): Target {
        var w = width
        var h = height
        if (w > PI_MAX_DIMENSION) {
            h = Math.round(h.toDouble() * PI_MAX_DIMENSION / w).toInt()
            w = PI_MAX_DIMENSION
        }
        if (h > PI_MAX_DIMENSION) {
            w = Math.round(w.toDouble() * PI_MAX_DIMENSION / h).toInt()
            h = PI_MAX_DIMENSION
        }
        return Target(w, h)
    }

    /**
     * One shrink round, pi's `:146-150`: three quarters on each axis, never below 1.
     * An axis already at 1 stays at 1, which is what makes the loop terminate.
     */
    fun shrink(target: Target): Target = Target(
        width = shrinkAxis(target.width),
        height = shrinkAxis(target.height),
    )

    private fun shrinkAxis(axis: Int): Int =
        if (axis <= 1) 1 else maxOf(1, axis * SHRINK_NUMERATOR / SHRINK_DENOMINATOR)

    /**
     * Whether this source gets pi's PNG candidate before the JPEG ladder.
     *
     * `true` for a PNG source (any variant — `image/png` is what pi's own conversion
     * emits, `image-process.ts:56-60`) and for anything whose decoded bitmap carries
     * alpha, which is the only case where a JPEG re-encode would lose information. `false`
     * for every other source, including a camera JPEG: see the class KDoc for why that is
     * the app's one deliberate difference from pi and what it costs the user.
     *
     * The MIME is reduced the way pi reduces it (`baseMimeType`, `image-process.ts:29-31`):
     * everything from the first `;`, trimmed and lower-cased, so `image/png; charset=binary`
     * qualifies.
     */
    fun pngFirst(sourceMime: String, hasAlpha: Boolean): Boolean =
        hasAlpha || baseMimeType(sourceMime) == "image/png"

    /**
     * The candidate encodings at one size, in pi's order — PNG first when [pngFirst], then
     * JPEG at every quality step. See the class KDoc for the one deliberate difference
     * from pi (PNG for a PNG source or an alpha-carrying bitmap, not for every source).
     */
    fun encodings(sourceMime: String, hasAlpha: Boolean): List<Encoding> {
        val out = ArrayList<Encoding>(PI_JPEG_QUALITIES.size + 1)
        if (pngFirst(sourceMime, hasAlpha)) out += Encoding.Png
        for (quality in PI_JPEG_QUALITIES) out += Encoding.Jpeg(quality)
        return out
    }

    /**
     * Every attempt pi's loop would make for a [width]×[height] source, in order: all
     * encodings at the clamped size, then all of them at each shrunk size, ending at
     * 1×1. The caller returns at the **first** encoding whose base64 is under
     * [PI_MAX_BASE64_CHARS]; this sequence is lazy so the encodings after that point are
     * never produced, which is the difference between one encode per picked photo and
     * six.
     *
     * A source already within both limits never reaches this: `ChatScreen` keeps pi's
     * fast path and sends the original bytes.
     */
    fun attemptPlan(sourceMime: String, hasAlpha: Boolean, width: Int, height: Int): Sequence<Attempt> = sequence {
        var target = initialTarget(width, height)
        while (true) {
            for (encoding in encodings(sourceMime, hasAlpha)) {
                yield(Attempt(target.width, target.height, encoding))
            }
            if (target.width == 1 && target.height == 1) break
            val next = shrink(target)
            if (next == target) break
            target = next
        }
    }

    /**
     * Whether pi forwards this MIME type inline **unchanged**.
     *
     * `image-process.ts:33-47` is the whole list — png, jpeg/jpg, gif and webp — and
     * anything else (`image/heic`, `image/bmp`, `image/tiff`) is converted to PNG before
     * the resize (`:49-65`). It matters on this side because the fast path sends the
     * original bytes with the original MIME: without this test an HEIC under both limits
     * would be forwarded as HEIC, which pi can never do and some providers reject.
     *
     * The MIME is reduced the way pi reduces it (`baseMimeType`, `:29-31`): everything
     * from the first `;`, trimmed and lower-cased, so `image/jpeg; charset=binary`
     * qualifies. `lowercase()` is locale-independent, unlike JS's `toLowerCase()`.
     */
    fun piInlineSupported(mime: String): Boolean = when (baseMimeType(mime)) {
        "image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp" -> true
        else -> false
    }

    /** pi's `baseMimeType`: drop the parameters, trim, lower-case (`image-process.ts:29-31`). */
    private fun baseMimeType(mime: String): String = mime.substringBefore(';').trim().lowercase()

    /** Base64 characters the staged images occupy, i.e. what the record has to carry. */
    fun usedChars(base64Lengths: List<Int>): Int = base64Lengths.sum()
    /** What is left of [MESSAGE_BASE64_CHARS]; never negative. */
    fun remainingChars(base64Lengths: List<Int>): Int =
        (MESSAGE_BASE64_CHARS - usedChars(base64Lengths)).coerceAtLeast(0)

    /** [remainingChars] in the bytes they encode — the copy's unit. */
    fun remainingBytes(base64Lengths: List<Int>): Int =
        base64CharsToBytes(remainingChars(base64Lengths))

    /** The verdict for the image the user just picked, already compressed to pi's limit. */
    sealed interface Verdict {
        /** The message can carry one more: the sum stays within [MESSAGE_BASE64_CHARS]. */
        data object Fits : Verdict

        /**
         * It cannot, and every number the user is told comes from here rather than from
         * the UI, so the sentence and the decision can never disagree.
         *
         * @param stagedImages how many images are already staged.
         * @param limitBytes [MESSAGE_BYTES], the whole message's budget.
         * @param usedBytes what the staged images already cost.
         * @param remainingBytes what is still free, in the same unit.
         * @param piSizedImages [MAX_PI_SIZED_IMAGES], for "about N images like this".
         * @param candidateBytes the image that did not fit.
         */
        data class MessageFull(
            val stagedImages: Int,
            val limitBytes: Int,
            val usedBytes: Int,
            val remainingBytes: Int,
            val piSizedImages: Int,
            val candidateBytes: Int,
        ) : Verdict
    }

    /**
     * Whether one more image of [candidateBase64Chars] base64 characters fits alongside
     * [base64Lengths].
     *
     * The comparison is `characters`, not bytes, because that is exactly what the framer
     * counts and what pi's own `maxBytes` counts; the bytes in [Verdict.MessageFull] are
     * a presentation of the same numbers.
     */
    fun decide(base64Lengths: List<Int>, candidateBase64Chars: Int): Verdict {
        val used = usedChars(base64Lengths)
        if (used + candidateBase64Chars <= MESSAGE_BASE64_CHARS) return Verdict.Fits
        return Verdict.MessageFull(
            stagedImages = base64Lengths.size,
            limitBytes = MESSAGE_BYTES,
            usedBytes = base64CharsToBytes(used),
            remainingBytes = base64CharsToBytes((MESSAGE_BASE64_CHARS - used).coerceAtLeast(0)),
            piSizedImages = MAX_PI_SIZED_IMAGES,
            candidateBytes = base64CharsToBytes(candidateBase64Chars),
        )
    }
}
