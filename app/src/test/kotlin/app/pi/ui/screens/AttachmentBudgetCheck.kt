package app.pi.ui.screens

import java.io.File

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Bare-JVM harness for the composer's image budget. Registered in
// `tools/run-app-pure-checks.sh` as `image-attachment-budget`.
//
// ## What it pins, and why each one is worth pinning
//
//  1. **pi's own limits, transcribed.** Every number in `AttachmentBudget` is a claim
//     about `packages/coding-agent/src/utils/image-resize-core.ts` in the pinned engine,
//     and a claim about another program is exactly the kind that stops being true
//     without anything failing. The dimension limit, the 4.5 MB base64 ceiling, the
//     quality ladder in its exact order and the three-quarter shrink are all asserted
//     literally, so a transcription error is a red harness rather than a picture that
//     quietly loses quality or fails to fit.
//  2. **pi's resize arithmetic.** `initialTarget`'s two sequential clamps (which can
//     round the aspect ratio once) and `shrink`'s `floor(axis * 0.75)`, floored at 1, are
//     pinned on the values pi's own loop produces. The `attemptPlan` sequence is pinned
//     as a sequence: same order pi tries, monotonically shrinking, ending at 1×1, and the
//     PNG candidate present for **every** source, at **every** size
//     (`image-resize-core.ts:112-114`) — the App used to ask for it only when the source
//     was a PNG or its bitmap carried alpha, and that difference is gone; the assertions
//     below are the ones that would catch it coming back. The fast path that must *not* be
//     affected by that order is pinned too, as source text.
//  3. **The per-message budget and its derivation.** The budget is
//     `JsonlFramer.DEFAULT_MAX_RECORD_CHARS - FRAMING_SLACK_CHARS`; the line cap the
//     session reader uses must be at least the record cap; and the end-to-end claim the
//     copy makes — [AttachmentBudget.MAX_PI_SIZED_IMAGES] images at pi's ceiling fit in
//     one message and one more does not — is checked as arithmetic, at the strict
//     inequality pi itself uses (`encodedSize < maxBytes`).
//  4. **The decision, not just the numbers.** `decide` is the one place that answers
//     "does this image fit", and its fields are what the user is told *when it does not*:
//     section 8 pins that the outcome is no longer a refusal but a downgrade to
//     "copied into the workspace, hand over the path" (`ChatScreen`'s `fallbackInline`),
//     with the verdict's own three figures in the sentence. The checks drive it at both
//     boundaries (exactly full, one character over) and with an empty list, so the copy's
//     numbers cannot come from anywhere but the decision.
//
//  5. **The workspace copy's bound is the disk, not the message** (`AttachmentBudget.workspaceSpace`).
//     The two limits are unrelated: `MESSAGE_BYTES` is about one JSONL record's inline base64,
//     the workspace copy is about free space. Section 8 pins that a file larger than
//     `MESSAGE_BYTES` is still copied when the disk has room, that a shortage reports the
//     space figures rather than "too large", and that the margin stays a bounded number
//     rather than a second provisioning threshold.
//
// ## What it cannot check
//
//  - Anything about a real picture. `BitmapFactory`/`Bitmap`/`Bitmap.compress` need a
//    device; this file never sees one. Whether JPEG at 80 gets a 12 MP photo under
//    4.5 MB, what Android's PNG encoder costs for a full-size frame (the price the PNG-first
//    order pays on every re-encode), and the peak memory of one decode are all device
//    questions, listed as such in the change report.
//  - `ChatScreen`'s wiring as *code*. It imports Compose and Android, so it cannot be
//    compiled here; the harness pins the numbers and the verdict it consumes, and reads the
//    one call site it has to (the fast path's early return, and that it precedes the encoding
//    plan) as source text — the same thing `ImageSizeCheck` does for the decode sites.
//
// Android-free: `kotlin` stdlib only, plus `:rpc`'s `JsonlFramer` and
// `SessionFileReader` for the two caps it cross-checks.

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
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

fun main() {
    // `AttachmentBudget.kt` 的源文本，本节与第 2 节两处要用（版本标签、以及「刻意差异」真的删掉了）。
    // 与 `chatText` 同法：`pi.repo.root` 由 `tools/run-app-pure-checks.sh` 传给每个 harness。
    val budgetText = System.getProperty("pi.repo.root")
        ?.let { File(it, "app/src/main/kotlin/app/pi/ui/screens/AttachmentBudget.kt") }
        ?.takeIf { it.isFile }?.readText().orEmpty()

    // ---------------------------------------- 1. pi's limits, transcribed literally
    check("pi's longest edge", AttachmentBudget.PI_MAX_DIMENSION, 2000)
    check("pi's base64 ceiling is 4.5 MiB", AttachmentBudget.PI_MAX_BASE64_CHARS, 4_718_592)
    check("pi's default JPEG quality", AttachmentBudget.PI_JPEG_QUALITY, 80)
    check(
        "pi's quality ladder, in pi's order",
        AttachmentBudget.PI_JPEG_QUALITIES.toList(),
        listOf(80, 85, 70, 55, 40),
    )
    check("pi's shrink factor", AttachmentBudget.SHRINK_NUMERATOR to AttachmentBudget.SHRINK_DENOMINATOR, 3 to 4)

    // --- 1b. KDoc 里那个版本号必须跟着 pin。
    //
    // `AttachmentBudget` 的类 KDoc 写「pin 住的引擎 **1.0.1**」，而 `tools/pi-engine.lock.json`
    // 早就换成别的版本时，这一整套「逐值钉死 pi」的断言就变成对一个**已经不存在的 pi** 的主张 ——
    // 那正是 0.86.1 → 1.0.1 那次漏掉的东西（`PiLatexCheck` 用「夹具版本 vs lock」钉同一件事）。
    // 版本从 lock 里**读**，不写死在这里：写死的话它自己也会过期。
    val lockText = System.getProperty("pi.repo.root")
        ?.let { File(it, "tools/pi-engine.lock.json") }
        ?.takeIf { it.isFile }?.readText().orEmpty()
    val pinnedVersion = runCatching {
        Json.parseToJsonElement(lockText).jsonObject["packages"]?.jsonObject
            ?.get("node_modules/@earendil-works/pi-coding-agent")?.jsonObject
            ?.get("version")?.jsonPrimitive?.content
    }.getOrNull()
    checkTrue("tools/pi-engine.lock.json 里的 pi-coding-agent 版本读得到", !pinnedVersion.isNullOrBlank(), "pinned=$pinnedVersion")
    checkTrue(
        "AttachmentBudget 的 KDoc 标的正是 lock 里那个版本",
        pinnedVersion != null && budgetText.contains("引擎 **$pinnedVersion**"),
        "pinned=$pinnedVersion kdocHas=${pinnedVersion != null && budgetText.contains(pinnedVersion.orEmpty())}",
    )

    // ------------------------------------------------ 2. pi's base64 arithmetic
    check("base64 of 0 bytes", AttachmentBudget.base64Chars(0), 0)
    check("base64 of 1 byte is padded to 4", AttachmentBudget.base64Chars(1), 4)
    check("base64 of 2 bytes is padded to 4", AttachmentBudget.base64Chars(2), 4)
    check("base64 of 3 bytes is 4", AttachmentBudget.base64Chars(3), 4)
    check("base64 of 4 bytes is 8", AttachmentBudget.base64Chars(4), 8)
    check(
        "pi's ceiling in bytes is the same conversion the budget uses",
        AttachmentBudget.base64Chars(AttachmentBudget.PI_MAX_BYTES),
        AttachmentBudget.PI_MAX_BASE64_CHARS,
    )
    check("pi's ceiling in bytes", AttachmentBudget.PI_MAX_BYTES, 3_538_944)

    // ------------------------------------------------ 3. pi's resize arithmetic
    check("a source within the box is not resized", AttachmentBudget.initialTarget(1600, 900), AttachmentBudget.Target(1600, 900))
    check("the long edge is clamped", AttachmentBudget.initialTarget(4000, 1000), AttachmentBudget.Target(2000, 500))
    check("a portrait source is clamped on height", AttachmentBudget.initialTarget(1000, 4000), AttachmentBudget.Target(500, 2000))
    check(
        "the second clamp is measured against the first's result",
        AttachmentBudget.initialTarget(3000, 2000),
        AttachmentBudget.Target(2000, 1333),
    )
    check(
        "an exactly-square extreme clamps both ways",
        AttachmentBudget.initialTarget(4000, 4000),
        AttachmentBudget.Target(2000, 2000),
    )
    check("shrink is three quarters, floored", AttachmentBudget.shrink(AttachmentBudget.Target(2000, 1333)), AttachmentBudget.Target(1500, 999))
    check("an axis of 1 stays at 1", AttachmentBudget.shrink(AttachmentBudget.Target(1, 5)), AttachmentBudget.Target(1, 3))
    check("1x1 is the fixed point", AttachmentBudget.shrink(AttachmentBudget.Target(1, 1)), AttachmentBudget.Target(1, 1))

    // encodings: pi's candidate order for **every** source — PNG first, then the JPEG
    // ladder (`image-resize-core.ts:112-114`, `:122`)。这里以前钉的是「PNG 只在源是 PNG 或
    // 位图带 alpha 时才试」那条刻意差异；它删掉之后断言必须**反过来钉住新行为**（不是放宽）：
    // 候选序与源无关，PNG 恒在第一格。
    check(
        "pi's candidate order: PNG first, then the ladder in pi's order",
        AttachmentBudget.encodings().map(::describe),
        listOf("png", "jpeg80", "jpeg85", "jpeg70", "jpeg55", "jpeg40"),
    )
    check(
        "PNG 领先每一个模型档位的第一格",
        listOf(40, 42, 70, 80, 100).map { AttachmentBudget.encodings(it).first() },
        List(5) { AttachmentBudget.Encoding.Png },
    )
    check(
        "JPEG 阶梯本身没动（模型的首格 + pi 的固定四步）",
        listOf(42, 80, 70).map { q ->
            AttachmentBudget.encodings(q).filterIsInstance<AttachmentBudget.Encoding.Jpeg>().map { it.quality }
        },
        listOf(listOf(42, 85, 70, 55, 40), listOf(80, 85, 70, 55, 40), listOf(70, 85, 55, 40)),
    )
    // 「唯一刻意差异」必须真的不在代码里了 —— 只改注释不删代码等于差异还在，所以这两条读源文本
    // （`budgetText` 在 main 开头读了一次）。`ChatScreen` 那条分流调用点由下面的 (e) 节按新形状钉住。
    check("AttachmentBudget.kt 读得到（源文本断言的前提）", budgetText.isNotEmpty(), true)
    check("pngFirst 已经不在了", budgetText.contains("pngFirst"), false)
    check("encodings/attemptPlan 不再按源分流（没有 hasAlpha 参数）", budgetText.contains("hasAlpha"), false)
    check("attemptPlan 不再收 sourceMime", budgetText.contains("sourceMime"), false)

    // The inline MIME list is pi's `normalizeSupportedImageMimeType`
    // (`image-process.ts:33-47`): exactly these four families go on the wire unchanged,
    // and everything else is converted to PNG first — so the fast path must not forward
    // an HEIC under the limits.
    check(
        "pi's inline MIME list",
        listOf("image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp").map(AttachmentBudget::piInlineSupported),
        listOf(true, true, true, true, true),
    )
    check(
        "everything pi converts is refused by the fast path",
        listOf("image/heic", "image/bmp", "image/tiff", "image/svg+xml", "application/pdf", "", "image/png8")
            .map(AttachmentBudget::piInlineSupported),
        List(7) { false },
    )
    check(
        "a MIME parameter and casing are reduced the way pi reduces them",
        listOf("IMAGE/PNG", "image/jpeg; charset=binary", "image/JPEG ; x=1")
            .map(AttachmentBudget::piInlineSupported),
        listOf(true, true, true),
    )

    // attemptPlan: pi's order, shrinking monotonically, ending at 1x1, with PNG first at
    // every size (one size offers 1 + len(ladder) candidates, not just the ladder).
    val plan = AttachmentBudget.attemptPlan(width = 4000, height = 1000).toList()
    check("the plan starts at the clamped target and offers PNG first", plan.first(), AttachmentBudget.Attempt(2000, 500, AttachmentBudget.Encoding.Png))
    check("the plan's last attempt is the smallest JPEG at 1x1", plan.last(), AttachmentBudget.Attempt(1, 1, AttachmentBudget.Encoding.Jpeg(40)))
    check("the plan has one PNG plus one JPEG per quality per size", plan.size, 6 * plan.map { it.width to it.height }.distinct().size)
    checkTrue(
        "每一个尺寸的第一个候选都是 PNG（与源无关）",
        plan.map { it.encoding }.chunked(6).all { it.first() == AttachmentBudget.Encoding.Png },
    )
    checkTrue(
        "sizes never grow and each is the previous shrink",
        plan.map { it.width to it.height }.distinct().zipWithNext().all { (a, b) ->
            b == AttachmentBudget.shrink(AttachmentBudget.Target(a.first, a.second)).let { it.width to it.height }
        },
    )
    checkTrue(
        "the plan is lazy: taking the first attempt encodes once, not six times",
        AttachmentBudget.attemptPlan(width = 8000, height = 6000)
            .take(1).toList().size == 1,
    )

    // (e) 快速路径不受影响。这是源文本断言：`ChatScreen` 导 Compose/Android，本 harness 编不了它，
    // 而「限制内 + MIME 受支持 → 原字节直接返回」正是这次候选序改动**不该**碰到的那条路径。
    // 断言的是**两处**原字节转发各就各位：`compressAttachment` 的快速路径（档位判断之后、任何编码
    // 计划之前），以及 `images.autoResize: false` 那一支（`withAutoResizeOff`，它本来就不建计划）。
    // 两处文案一模一样，所以数量是 2 而不是 1 —— 少了哪一处都会红。
    // `pi.repo.root` 由 `tools/run-app-pure-checks.sh` 传给每个 harness（与 `ImageSizeCheck` 同法）。
    val chatScreen = System.getProperty("pi.repo.root")?.let {
        File(it, "app/src/main/kotlin/app/pi/ui/screens/ChatScreen.kt")
    }
    val chatText = if (chatScreen != null && chatScreen.isFile) chatScreen.readText() else ""
    val fastPathReturn = Regex(
        "return PiImage\\(Base64\\.encodeToString\\(bytes, Base64\\.NO_WRAP\\), mime\\)",
    ).findAll(chatText).toList()
    check(
        "原字节转发恰好两处：档位内的快速路径 + images.autoResize 关掉那一支",
        fastPathReturn.size,
        2,
    )
    // 调用点的形状也一起钉：改完候选序之后 `attemptPlan` 只收尺寸与档位（不再收源），而
    // `ChatScreen` 送进去的必须是**摆正后**的 `width`/`height`（EXIF 那一笔，见
    // `PiExifOrientationCheck` 的第 4 节）。
    val planCall = Regex(
        "for \\(attempt in AttachmentBudget\\.attemptPlan\\(width, height, limits\\)\\)",
    ).find(chatText)
    checkTrue(
        "第一处是快速路径，仍排在**任何**编码计划之前",
        fastPathReturn.size == 2 && planCall != null &&
            fastPathReturn.first().range.first < planCall.range.first,
        "fastPathReturns=${fastPathReturn.size} planAt=${planCall?.range?.first}",
    )
    checkTrue(
        "第二处在 withAutoResizeOff 的函数体里（那条路不建计划）",
        fastPathReturn.size == 2 &&
            fastPathReturn.last().range.first > chatText.indexOf("private fun withAutoResizeOff("),
        "secondAt=${fastPathReturn.lastOrNull()?.range?.first} fnAt=${chatText.indexOf("private fun withAutoResizeOff(")}",
    )
    check("候选序不看源：调用点里没有 hasAlpha/mime 参数", chatText.contains("attemptPlan(mime"), false)

    // ------------------------------------- 3b. the per-model profile (Tier 4.1)
    //
    // Everything above pins pi's *defaults*. This section pins the profile the App actually
    // works against, which is no longer a constant: `limitsFor` folds the **current model's**
    // `inputLimits.images.resize` over those defaults, with the record's budget as a ceiling.
    // Three things only this harness can check without a device:
    //
    //  (a) the JSON→Kotlin parse reads pi's exact key path (a renamed key would leave every
    //      model on the defaults and nothing else in the build would notice);
    //  (b) a profile *smaller* than pi's default is honoured (the case that used to be lost:
    //      the App pre-resized to 2000 and pi resized again);
    //  (c) a profile *larger* than the record budget is clamped, because a message whose
    //      images exceed MESSAGE_BASE64_CHARS is one the App cannot read back.
    val limitsJson = kotlinx.serialization.json.Json.parseToJsonElement(
        """
        {"models":[{"id":"m1","name":"M1","provider":"p","input":["text","image"],
          "inputLimits":{"maxRequestBytes":123456,
            "images":{"resize":{"maxWidth":1024,"maxHeight":768,"maxBytes":999,"jpegQuality":42},
                      "maxPerMessage":3,"maxPerRequest":9}}}]}
        """.trimIndent(),
    )
    val parsed = app.pi.rpc.PiResponses.availableModels(
        app.pi.rpc.PiEvent.Response(
            id = "r1",
            command = "get_available_models",
            success = true,
            error = null,
            data = limitsJson,
        ),
    ).firstOrNull()
    check("the model list parses", parsed?.id, "m1")
    check("inputLimits.maxRequestBytes is read", parsed?.inputLimits?.maxRequestBytes, 123_456L)
    check("inputLimits.images.resize.maxWidth is read", parsed?.inputLimits?.images?.resize?.maxWidth, 1024)
    check("inputLimits.images.resize.maxHeight is read", parsed?.inputLimits?.images?.resize?.maxHeight, 768)
    check("inputLimits.images.resize.maxBytes is read", parsed?.inputLimits?.images?.resize?.maxBytes, 999L)
    check("inputLimits.images.resize.jpegQuality is read", parsed?.inputLimits?.images?.resize?.jpegQuality, 42)
    check("inputLimits.images.maxPerMessage is read", parsed?.inputLimits?.images?.maxPerMessage, 3)
    check("inputLimits.images.maxPerRequest is read", parsed?.inputLimits?.images?.maxPerRequest, 9)

    // A model that said nothing is not a model that said "pi's defaults": the type reports
    // `null` and the *resolution* applies the defaults. Keeping those two apart is what lets
    // a future caller distinguish "the engine published a profile" from "nobody knows".
    val silent = app.pi.rpc.PiResponses.availableModels(
        app.pi.rpc.PiEvent.Response(
            id = "r2",
            command = "get_available_models",
            success = true,
            error = null,
            data = kotlinx.serialization.json.Json.parseToJsonElement("""{"models":[{"id":"m2","name":"M2"}]}"""),
        ),
    ).firstOrNull()
    check("a model without inputLimits parses to null", silent?.inputLimits, null)

    check("no profile at all means pi's defaults", AttachmentBudget.limitsFor(null), AttachmentBudget.PI_DEFAULT_LIMITS)
    check(
        "a model's own profile is what the pass uses",
        AttachmentBudget.limitsFor(parsed?.inputLimits),
        AttachmentBudget.Limits(maxWidth = 1024, maxHeight = 768, maxBase64Chars = 999, jpegQuality = 42),
    )
    // The input must be **derived from** the budget, not a plausible-looking number: this
    // case used to say `20 MiB`, which is *below* the record budget (64 MiB in base64
    // characters), so `min` kept the model's smaller number and the clamp was never
    // exercised — a green-looking assertion that tested the opposite of its own name.
    // pi's `maxBytes` is counted in base64 characters, so doubling the budget is above it.
    check(
        "a profile bigger than the record budget is clamped to it",
        AttachmentBudget.limitsFor(
            app.pi.rpc.PiResponses.InputLimits(
                maxRequestBytes = null,
                images = app.pi.rpc.PiResponses.ImageLimits(
                    resize = app.pi.rpc.PiResponses.ImageResizeLimits(
                        maxWidth = null,
                        maxHeight = null,
                        maxBytes = AttachmentBudget.MESSAGE_BASE64_CHARS.toLong() * 2,
                        jpegQuality = null,
                    ),
                    maxPerMessage = null,
                    maxPerRequest = null,
                ),
            ),
        ).maxBase64Chars,
        AttachmentBudget.MESSAGE_BASE64_CHARS.toLong(),
    )
    check(
        "a partial profile keeps pi's default for the fields it omits",
        AttachmentBudget.limitsFor(
            app.pi.rpc.PiResponses.InputLimits(
                maxRequestBytes = null,
                images = app.pi.rpc.PiResponses.ImageLimits(
                    resize = app.pi.rpc.PiResponses.ImageResizeLimits(
                        maxWidth = null,
                        maxHeight = null,
                        maxBytes = null,
                        jpegQuality = 55,
                    ),
                    maxPerMessage = null,
                    maxPerRequest = null,
                ),
            ),
        ),
        AttachmentBudget.Limits(
            maxWidth = AttachmentBudget.PI_MAX_DIMENSION,
            maxHeight = AttachmentBudget.PI_MAX_DIMENSION,
            maxBase64Chars = AttachmentBudget.PI_MAX_BASE64_CHARS.toLong(),
            jpegQuality = 55,
        ),
    )

    // The ladder is pi's `new Set([jpegQuality, 85, 70, 55, 40])`, so a model's first
    // quality changes the first element only. The constant and the function must agree at
    // pi's own default or one of them is a second, drifting answer.
    check(
        "the constant ladder is the function at pi's default quality",
        AttachmentBudget.jpegQualities(AttachmentBudget.PI_JPEG_QUALITY).toList(),
        AttachmentBudget.PI_JPEG_QUALITIES.toList(),
    )
    check(
        "a model's jpegQuality leads pi's fixed steps",
        AttachmentBudget.jpegQualities(42).toList(),
        listOf(42, 85, 70, 55, 40),
    )
    check(
        "a model's quality that is already in the ladder does not duplicate it",
        AttachmentBudget.jpegQualities(70).toList(),
        listOf(70, 85, 55, 40),
    )

    // The two axes are separate limits (pi's own shape), and the profile reaches the plan.
    check(
        "a wide picture keeps its width when the profile allows it",
        AttachmentBudget.initialTarget(
            4000,
            1000,
            AttachmentBudget.Limits(maxWidth = 4000, maxHeight = 2000, maxBase64Chars = 1, jpegQuality = 80),
        ),
        AttachmentBudget.Target(4000, 1000),
    )
    check(
        "the default profile still clamps both axes to 2000",
        AttachmentBudget.initialTarget(4000, 1000),
        AttachmentBudget.initialTarget(
            4000,
            1000,
            AttachmentBudget.Limits(
                maxWidth = AttachmentBudget.PI_MAX_DIMENSION,
                maxHeight = AttachmentBudget.PI_MAX_DIMENSION,
                maxBase64Chars = AttachmentBudget.PI_MAX_BASE64_CHARS.toLong(),
                jpegQuality = AttachmentBudget.PI_JPEG_QUALITY,
            ),
        ),
    )
    check(
        "the plan's first attempt is the model's clamped size, not pi's default",
        AttachmentBudget.attemptPlan(
            width = 4000,
            height = 1000,
            limits = AttachmentBudget.Limits(maxWidth = 1024, maxHeight = 1024, maxBase64Chars = 1, jpegQuality = 42),
        ).first(),
        AttachmentBudget.Attempt(1024, 256, AttachmentBudget.Encoding.Png),
    )
    check(
        "档位的第一个质量仍然是这张尺寸上 JPEG 阶梯的第一格",
        AttachmentBudget.attemptPlan(
            width = 4000,
            height = 1000,
            limits = AttachmentBudget.Limits(maxWidth = 1024, maxHeight = 1024, maxBase64Chars = 1, jpegQuality = 42),
        ).first { it.encoding is AttachmentBudget.Encoding.Jpeg }.encoding,
        AttachmentBudget.Encoding.Jpeg(42),
    )

    // ------------------------------------- 4. the per-message budget, derived
    check("the slack", AttachmentBudget.FRAMING_SLACK_CHARS, 65_536)
    check(
        "the budget plus the slack is exactly the record cap",
        AttachmentBudget.MESSAGE_BASE64_CHARS + AttachmentBudget.FRAMING_SLACK_CHARS,
        app.pi.rpc.JsonlFramer.DEFAULT_MAX_RECORD_CHARS,
    )
    check("the record cap is 64 MiB", app.pi.rpc.JsonlFramer.DEFAULT_MAX_RECORD_CHARS, 67_108_864)
    check("the message budget in base64 characters", AttachmentBudget.MESSAGE_BASE64_CHARS, 67_043_328)
    check("the message budget in bytes", AttachmentBudget.MESSAGE_BYTES, 50_282_496)
    check(
        "the budget in bytes is the same conversion",
        AttachmentBudget.base64CharsToBytes(AttachmentBudget.MESSAGE_BASE64_CHARS),
        AttachmentBudget.MESSAGE_BYTES,
    )
    // The coupling the change is about: one legal session line is strictly smaller than
    // one legal single-message record (the record carries an envelope the line does not),
    // so the reader's line cap must be at least the record cap. A line cap below it can
    // only ever drop a line the framer would have delivered.
    check(
        "the session reader's line cap is the record cap",
        app.pi.session.SessionFileReader.DEFAULT_MAX_LINE_CHARS,
        app.pi.rpc.JsonlFramer.DEFAULT_MAX_RECORD_CHARS,
    )
    checkTrue(
        "a legal line is strictly smaller than a legal record",
        AttachmentBudget.MESSAGE_BASE64_CHARS < app.pi.session.SessionFileReader.DEFAULT_MAX_LINE_CHARS,
    )

    // ------------------------------------- 5. the end-to-end claim the copy makes
    check("pi-maximum images per message", AttachmentBudget.MAX_PI_SIZED_IMAGES, 14)
    // pi's own test is strict (`candidate.encodedSize < opts.maxBytes`), so the largest
    // image pi will emit is one character under the ceiling.
    val largest = AttachmentBudget.PI_MAX_BASE64_CHARS - 1
    checkTrue(
        "14 pi-maximum images fit",
        14 * largest <= AttachmentBudget.MESSAGE_BASE64_CHARS,
    )
    checkTrue(
        "15 do not",
        15 * largest > AttachmentBudget.MESSAGE_BASE64_CHARS,
    )
    checkTrue("the budget is under the record cap, so the envelope always has room", AttachmentBudget.MESSAGE_BASE64_CHARS < 64 * 1024 * 1024)
    checkTrue(
        "one pi-maximum image plus the envelope still leaves room for a second",
        AttachmentBudget.MESSAGE_BASE64_CHARS - largest >= largest,
    )

    // ------------------------------------- 6. the decision and its copy numbers
    check("an empty message fits one pi-maximum image", AttachmentBudget.decide(emptyList(), largest), AttachmentBudget.Verdict.Fits)
    check(
        "exactly full still fits",
        AttachmentBudget.decide(listOf(AttachmentBudget.MESSAGE_BASE64_CHARS - 10), 10),
        AttachmentBudget.Verdict.Fits,
    )
    val refused = AttachmentBudget.decide(listOf(AttachmentBudget.MESSAGE_BASE64_CHARS - 10), 11)
    checkTrue("one character over is refused", refused is AttachmentBudget.Verdict.MessageFull)
    val full = refused as AttachmentBudget.Verdict.MessageFull
    check("the refusal reports how many images are staged", full.stagedImages, 1)
    check("the refusal reports the whole-message limit in bytes", full.limitBytes, AttachmentBudget.MESSAGE_BYTES)
    check(
        "the refusal reports what is already spent",
        full.usedBytes,
        AttachmentBudget.base64CharsToBytes(AttachmentBudget.MESSAGE_BASE64_CHARS - 10),
    )
    check(
        "the refusal reports the 10 characters still free, converted to bytes",
        full.remainingBytes,
        AttachmentBudget.base64CharsToBytes(10),
    )
    check("the refusal names how many pi-sized images a whole message holds", full.piSizedImages, 14)
    check(
        "the refusal reports the image that did not fit",
        full.candidateBytes,
        AttachmentBudget.base64CharsToBytes(11),
    )
    // The reading of the fields the copy performs, written out so a change to the
    // sentence has to come here too: "本条消息还能放约 N MB" for a half-full message.
    val half = AttachmentBudget.decide(listOf(AttachmentBudget.MESSAGE_BASE64_CHARS / 2), AttachmentBudget.MESSAGE_BASE64_CHARS) as AttachmentBudget.Verdict.MessageFull
    checkTrue(
        "remaining room is what the copy says it is",
        half.remainingBytes == AttachmentBudget.base64CharsToBytes(AttachmentBudget.MESSAGE_BASE64_CHARS / 2),
        "remaining=${half.remainingBytes}",
    )

    // ------------------------------------- 7. the read guard is a guard, not the limit
    check(
        "the picked-file read guard is one frame",
        AttachmentBudget.MAX_PICKED_IMAGE_BYTES,
        app.pi.rpc.JsonlFramer.DEFAULT_MAX_RECORD_CHARS,
    )
    checkTrue(
        "the read guard is above the message budget, so it never stands in for it",
        AttachmentBudget.MAX_PICKED_IMAGE_BYTES > AttachmentBudget.MESSAGE_BYTES,
    )

    // ------------------------- 8. 工作区复制：上限只有磁盘，**不是**内联预算
    //
    // 用户原话：「附件现在本来就是发啥都复制到工作区，现在发个文件还说太大，复制不进去，太傻逼了。」
    // 那条拒绝来自 `copyIntoWorkspace` 把 `MESSAGE_BYTES`（23.95 MB，内联预算）当成了复制的上限。
    // 下面四组断言把这条裁决钉住：上限与内联预算**无关**、空间不足报的是空间、任何内联不成的结局
    // 都是「放进工作区 + 给路径」、以及余量不许长成第二个「解包门槛」。
    val roomy = 64L * 1024 * 1024 * 1024 // 64 GiB：任何真机上都是「装得下」
    check(
        "① 比内联预算多一个字节的文件，磁盘装得下就允许复制",
        AttachmentBudget.workspaceSpace(AttachmentBudget.MESSAGE_BYTES + 1L, roomy),
        AttachmentBudget.DiskSpace.Room,
    )
    check(
        "① 内联额度的大小在复制这条路上不出现（恰好在预算上也是 Room）",
        AttachmentBudget.workspaceSpace(AttachmentBudget.MESSAGE_BYTES.toLong() * 8, roomy),
        AttachmentBudget.DiskSpace.Room,
    )
    checkTrue(
        "① 余量是一个有界的数，不是第二个解包门槛",
        AttachmentBudget.WORKSPACE_SPACE_MARGIN_BYTES in 1..(64L * 1024 * 1024),
        "margin=${AttachmentBudget.WORKSPACE_SPACE_MARGIN_BYTES}",
    )
    // 边界：恰好等于「可用 − 余量」装得下，多一个字节装不下（严格不等）。
    val free = AttachmentBudget.WORKSPACE_SPACE_MARGIN_BYTES + 10_000L
    check("② 恰好用完余量之外的每一字节：Room", AttachmentBudget.workspaceSpace(10_000L, free), AttachmentBudget.DiskSpace.Room)
    check(
        "② 多一个字节：Insufficient，且两个数就是那次测量的数",
        AttachmentBudget.workspaceSpace(10_001L, free),
        AttachmentBudget.DiskSpace.Insufficient(neededBytes = 10_001L, availableBytes = free),
    )
    check(
        "② 可用空间比余量还小 → 需要的下界照实报",
        AttachmentBudget.workspaceSpace(4_096L, 1_024L),
        AttachmentBudget.DiskSpace.Insufficient(neededBytes = 4_096L, availableBytes = 1_024L),
    )
    check(
        "② 文件系统读不出可用空间时不假装知道（不据此拒绝）",
        AttachmentBudget.workspaceSpace(Long.MAX_VALUE, null),
        AttachmentBudget.DiskSpace.Unknown,
    )
    check(
        "② 显式余量参数被用上，不是被忽略",
        AttachmentBudget.workspaceSpace(2_000L, 3_000L, marginBytes = 1_500L),
        AttachmentBudget.DiskSpace.Insufficient(neededBytes = 2_000L, availableBytes = 3_000L),
    )
    checkTrue(
        "③ 内联被拒的那张图，复制这条路照样允许（两条判定互相独立）",
        AttachmentBudget.decide(listOf(AttachmentBudget.MESSAGE_BASE64_CHARS), 1) is AttachmentBudget.Verdict.MessageFull &&
            AttachmentBudget.workspaceSpace(AttachmentBudget.MESSAGE_BYTES + 1L, roomy) == AttachmentBudget.DiskSpace.Room,
    )

    // ③/① 的连线：`ChatScreen` 编不了（Compose + Android），所以这两条读源文本 —— 与 (e) 同法。
    // 断言的是**形状**，不是措辞：旧的有界复制（`copyBounded(..., MESSAGE_BYTES)`）必须不在，
    // 复制的判定必须只有一处（`workspaceSpace`），而「内联不成 → 放进工作区」这条降级必须在。
    check("旧的内联预算不再被当成复制的上限", chatText.contains("copyBounded(input, sink, AttachmentBudget.MESSAGE_BYTES)"), false)
    check(
        "工作区复制的空间判定只有一个入口",
        Regex("AttachmentBudget\\.workspaceSpace\\(").findAll(chatText).count(),
        1,
    )
    checkTrue(
        "内联额度不够时走的是「放进工作区 + 给路径」，不是拒绝",
        Regex("is AttachmentBudget\\.Verdict\\.MessageFull -> fallbackInline\\(").containsMatchIn(chatText) &&
            !chatText.contains("messageFullText("),
        "degrade=${Regex("fallbackInline\\(").findAll(chatText).count()} messageFullText=${chatText.contains("messageFullText(")}",
    )
    checkTrue(
        "空间不足的那句话说的是空间，而不是「文件太大」",
        chatText.contains("磁盘空间不足：这个文件至少需要") && !chatText.contains("文件太大：上限是"),
    )
    checkTrue(
        "降级说明里同时有：工作区路径、没有内联、模型能读到",
        chatText.contains("已放进工作区 \$relativePath —— \$why，所以没有随消息内联") &&
            chatText.contains("模型可以用工具读取这个文件"),
    )
    checkTrue(
        "半文件必删：两条失败路径都删（空间与写入失败）",
        Regex("runCatching \\{ target\\.file\\.delete\\(\\) \\}").findAll(chatText).count() == 2,
        "deletes=${Regex("target\\.file\\.delete\\(\\)").findAll(chatText).count()}",
    )

    // ------------------------- 9. `images.autoResize` 的消费者（源文本）
    //
    // `images.autoResize` 曾经**只有设置项的展示、没有消费者**：用户把它关掉之后 App 照压。
    // 这一节钉住两件事，都是读 `ChatScreen.kt` 源文本 —— 本机编不了 Compose：
    //  (a) 设置真的被读了，默认值是 pi 的 `true`（`settings-manager.ts:68`、`:1404`），且
    //      「跳过预压缩」这个值真的传进了 `compressAttachment`；
    //  (b) **消息预算没有跟着关掉**：`AttachmentBudget.decide` 仍在，且仍在读设置之后 ——
    //      暂存的 base64 会进 composer 状态，超了 `JsonlFramer` 的记录上限就是 App 自己都读不回来
    //      的一条记录。关掉自动缩放不等于允许发那样一条消息。
    val autoResizeReads = Regex("readBoolean\\(\"images\\.autoResize\"\\) \\?: true").findAll(chatText).toList()
    check("images.autoResize 被读，且缺省是 pi 的 true", autoResizeReads.size, 1)
    check(
        "读到的值传进了 compressAttachment",
        Regex("compressAttachment\\(bytes, mime, limits, autoResize\\)").findAll(chatText).count(),
        1,
    )
    check(
        "跳过预压缩的那一支在 compressAttachment 里",
        Regex("if \\(!autoResize\\) return withAutoResizeOff\\(bytes, mime, transform\\)").findAll(chatText).count(),
        1,
    )
    val decides = Regex("AttachmentBudget\\.decide\\(").findAll(chatText).toList()
    check("消息预算仍在判定（decide 恰好一处）", decides.size, 1)
    if (autoResizeReads.size == 1 && decides.size == 1) {
        checkTrue(
            "预算判定排在读设置之后（关掉预压缩也没把预算一起去掉）",
            autoResizeReads.first().range.first < decides.first().range.first,
            "read=${autoResizeReads.first().range.first} decide=${decides.first().range.first}",
        )
    }

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}

/** One [AttachmentBudget.Encoding] as text, so a failure prints something readable. */
private fun describe(encoding: AttachmentBudget.Encoding): String = when (encoding) {
    is AttachmentBudget.Encoding.Png -> "png"
    is AttachmentBudget.Encoding.Jpeg -> "jpeg${encoding.quality}"
}
