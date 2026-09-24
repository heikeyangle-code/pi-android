package app.pi.packages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

// A bare-JVM harness for `PiScanVendorMeta.kt` — the vendor metadata a `/models` response
// volunteers, registered in `tools/run-app-pure-checks.sh` as `scan-vendor-meta`.
//
// Why this must be pinned: every rule in that file fails **silently**. Forget the
// per-token→per-million conversion and a price is a million times too small — still a
// number on screen; read `input_modalities` the wrong way and a text-only model claims
// images. Neither is visible to a compiler, and neither shows up as an error anywhere.
// The shapes below are OpenRouter's real `/models` fields, which is the vendor that
// actually supplies them.

var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private fun meta(json: String): ScanVendorMeta =
    scanVendorMeta(Json.parseToJsonElement(json).jsonObject)

fun main() {
    val openRouter = meta(
        """{"id":"anthropic/claude-sonnet-4","context_length":200000,
            "top_provider":{"max_completion_tokens":64000},
            "architecture":{"input_modalities":["text","image"]},
            "pricing":{"prompt":"0.000003","completion":"0.000015"}}""",
    )
    check("context_length is read", openRouter.contextWindow, 200000L)
    check("top_provider.max_completion_tokens is the max output", openRouter.maxTokens, 64000L)
    check("input_modalities with image means images", openRouter.acceptsImages, true)
    check("per-token prompt price converts to per-million", openRouter.costInputPerMillion, 3.0)
    check("per-token completion price converts to per-million", openRouter.costOutputPerMillion, 15.0)
    check("and the vendor is recorded as having spoken", openRouter.any, true)

    val textOnly = meta("""{"id":"x","architecture":{"input_modalities":["text"]}}""")
    check("modalities without image is an explicit no", textOnly.acceptsImages, false)

    val otherSpelling = meta("""{"id":"x","context_window":131072}""")
    check("the other spelling of context is honoured", otherSpelling.contextWindow, 131072L)

    val silent = meta("""{"id":"x","name":"X"}""")
    check(
        "nothing volunteered stays null (never zero)",
        listOf(
            silent.contextWindow,
            silent.maxTokens,
            silent.acceptsImages,
            silent.costInputPerMillion,
            silent.costOutputPerMillion,
        ),
        listOf(null, null, null, null, null),
    )
    check("and that is not 'metadata was supplied'", silent.any, false)

    val junk = meta("""{"id":"x","context_length":"a lot","pricing":{"prompt":"free"}}""")
    check(
        "a non-numeric value is ignored, not zeroed",
        listOf(junk.contextWindow, junk.costInputPerMillion),
        listOf(null, null),
    )

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
