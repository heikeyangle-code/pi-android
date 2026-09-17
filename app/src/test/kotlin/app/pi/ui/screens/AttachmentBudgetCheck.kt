package app.pi.ui.screens

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
//     as a sequence: same order pi tries, monotonically shrinking, ending at 1×1, and
//     PNG present only when the source has alpha — the one deliberate difference from pi,
//     asserted here so it cannot be mistaken for an accident later.
//  3. **The per-message budget and its derivation.** The budget is
//     `JsonlFramer.DEFAULT_MAX_RECORD_CHARS - FRAMING_SLACK_CHARS`; the line cap the
//     session reader uses must be at least the record cap; and the end-to-end claim the
//     copy makes — [AttachmentBudget.MAX_PI_SIZED_IMAGES] images at pi's ceiling fit in
//     one message and one more does not — is checked as arithmetic, at the strict
//     inequality pi itself uses (`encodedSize < maxBytes`).
//  4. **The decision, not just the numbers.** `decide` is the one place that answers
//     "does this image fit", and the refusal's fields are what the user is told. The
//     checks drive it at both boundaries (exactly full, one character over) and with an
//     empty list, so the copy's numbers cannot come from anywhere but the decision.
//
// ## What it cannot check
//
//  - Anything about a real picture. `BitmapFactory`/`Bitmap`/`Bitmap.compress` need a
//    device; this file never sees one. Whether JPEG at 80 gets a 12 MP photo under
//    4.5 MB, how `Bitmap.hasAlpha()` reports an opaque PNG on a given Android version,
//    and the peak memory of one decode are all device questions, listed as such in the
//    change report.
//  - `ChatScreen`'s wiring. It imports Compose and Android, so it cannot be compiled
//    here; the harness pins the numbers and the verdict it consumes, not the call.
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

    // encodings: PNG only for alpha; JPEG at every step, in pi's order, for both.
    check(
        "an alpha source tries PNG first, then JPEG",
        AttachmentBudget.encodings(hasAlpha = true).map(::describe),
        listOf("png", "jpeg80", "jpeg85", "jpeg70", "jpeg55", "jpeg40"),
    )
    check(
        "an opaque source skips PNG (the one deliberate difference from pi)",
        AttachmentBudget.encodings(hasAlpha = false).map(::describe),
        listOf("jpeg80", "jpeg85", "jpeg70", "jpeg55", "jpeg40"),
    )

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

    // attemptPlan: pi's order, shrinking monotonically, ending at 1x1.
    val plan = AttachmentBudget.attemptPlan(hasAlpha = false, width = 4000, height = 1000).toList()
    check("the plan starts at the clamped target", plan.first(), AttachmentBudget.Attempt(2000, 500, AttachmentBudget.Encoding.Jpeg(80)))
    check("the plan's last attempt is the smallest JPEG at 1x1", plan.last(), AttachmentBudget.Attempt(1, 1, AttachmentBudget.Encoding.Jpeg(40)))
    check("the plan has one JPEG per quality per size", plan.size, 5 * plan.map { it.width to it.height }.distinct().size)
    checkTrue(
        "sizes never grow and each is the previous shrink",
        plan.map { it.width to it.height }.distinct().zipWithNext().all { (a, b) ->
            b == AttachmentBudget.shrink(AttachmentBudget.Target(a.first, a.second)).let { it.width to it.height }
        },
    )
    checkTrue(
        "the plan is lazy: taking the first attempt encodes once, not six times",
        AttachmentBudget.attemptPlan(hasAlpha = true, width = 8000, height = 6000).take(1).toList().size == 1,
    )

    // ------------------------------------- 4. the per-message budget, derived
    check("the slack", AttachmentBudget.FRAMING_SLACK_CHARS, 65_536)
    check(
        "the budget plus the slack is exactly the record cap",
        AttachmentBudget.MESSAGE_BASE64_CHARS + AttachmentBudget.FRAMING_SLACK_CHARS,
        app.pi.rpc.JsonlFramer.DEFAULT_MAX_RECORD_CHARS,
    )
    check("the record cap is 32 MiB", app.pi.rpc.JsonlFramer.DEFAULT_MAX_RECORD_CHARS, 33_554_432)
    check("the message budget in base64 characters", AttachmentBudget.MESSAGE_BASE64_CHARS, 33_488_896)
    check("the message budget in bytes", AttachmentBudget.MESSAGE_BYTES, 25_116_672)
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
    check("pi-maximum images per message", AttachmentBudget.MAX_PI_SIZED_IMAGES, 7)
    // pi's own test is strict (`candidate.encodedSize < opts.maxBytes`), so the largest
    // image pi will emit is one character under the ceiling.
    val largest = AttachmentBudget.PI_MAX_BASE64_CHARS - 1
    checkTrue(
        "7 pi-maximum images fit",
        7 * largest <= AttachmentBudget.MESSAGE_BASE64_CHARS,
    )
    checkTrue(
        "8 do not",
        8 * largest > AttachmentBudget.MESSAGE_BASE64_CHARS,
    )
    checkTrue("the budget is under the record cap, so the envelope always has room", AttachmentBudget.MESSAGE_BASE64_CHARS < 32 * 1024 * 1024)
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
    check("the refusal names how many pi-sized images a whole message holds", full.piSizedImages, 7)
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
    check("the picked-file read guard is one frame", AttachmentBudget.MAX_PICKED_IMAGE_BYTES, 33_554_432)
    checkTrue(
        "the read guard is above the message budget, so it never stands in for it",
        AttachmentBudget.MAX_PICKED_IMAGE_BYTES > AttachmentBudget.MESSAGE_BYTES,
    )

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}

/** One [AttachmentBudget.Encoding] as text, so a failure prints something readable. */
private fun describe(encoding: AttachmentBudget.Encoding): String = when (encoding) {
    is AttachmentBudget.Encoding.Png -> "png"
    is AttachmentBudget.Encoding.Jpeg -> "jpeg${encoding.quality}"
}
