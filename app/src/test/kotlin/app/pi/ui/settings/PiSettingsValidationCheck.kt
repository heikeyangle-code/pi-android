package app.pi.ui.settings

// A bare-JVM harness for `PiSettingsValidation.kt`, the pure "can pi accept this
// value?" checks behind the settings editors.
//
// Why it exists: the validators encode *pi's* parsing semantics, and every case
// below is a value that pi either throws on or silently mis-uses. Getting one of
// them wrong is how `docs/settings-audit-impl.md` §B1 happened — the settings UI
// wrote `httpIdleTimeoutMs: null` believing "null means default", while pi's
// `parseTimeoutSetting` treats `null` as "not undefined, cannot parse" and throws
// (`core/settings-manager.ts:186-196`), killing `pi --mode rpc` at startup
// (`main.ts:850-851`). So the assertions here are stated in pi's own terms:
//
//   - `Rejected` = pi throws (the editor must refuse to save),
//   - `Suspicious` = pi accepts but the value is mis-used (string where a number
//     belongs, an override key that can never match),
//   - `Ok` = pi takes it as-is.
//
// `PiSettingsValidation.kt` is deliberately Android-free and free of `PiSetting`
// so this harness can compile it together with this file under
// `tools/run-app-pure-checks.sh` style compilation (kotlin stdlib +
// kotlinx-serialization-json only). If it ever grows an Android or Compose
// import, this file stops compiling — which is the point.
//
//   settings-validation   app.pi.ui.settings.PiSettingsValidationCheckKt

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.system.exitProcess

private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name${if (detail.isEmpty()) "" else "\n  $detail"}")
    }
}

private fun verdictName(verdict: PiValueVerdict): String = when (verdict) {
    is PiValueVerdict.Ok -> "Ok"
    is PiValueVerdict.Rejected -> "Rejected"
    is PiValueVerdict.Suspicious -> "Suspicious"
}

private fun expect(name: String, expected: String, verdict: PiValueVerdict) {
    check(name, verdictName(verdict) == expected, "expected $expected, got ${verdictName(verdict)}: $verdict")
}

private fun num(value: Int) = JsonPrimitive(value)
private fun str(value: String) = JsonPrimitive(value)

private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(pairs.toMap())

fun main() {
    // ---------------------------------------------------------------- timeouts
    // pi: `parseTimeoutSetting` throws whenever the value is present and
    // unparseable — and `null` is present (`null !== undefined`). This is the P0.
    for (key in listOf(KEY_HTTP_IDLE_TIMEOUT_MS, KEY_WEBSOCKET_CONNECT_TIMEOUT_MS)) {
        expect("$key: null is rejected (pi throws, engine may not start)", "Rejected", piValueVerdict(key, JsonNull))
        expect("$key: negative is rejected", "Rejected", piValueVerdict(key, num(-1)))
        expect("$key: empty string is rejected (not undefined)", "Rejected", piValueVerdict(key, str("")))
        expect("$key: non-numeric string is rejected", "Rejected", piValueVerdict(key, str("abc")))
        expect("$key: boolean is rejected", "Rejected", piValueVerdict(key, JsonPrimitive(true)))
        expect("$key: zero is accepted (pi 0 = no timeout)", "Ok", piValueVerdict(key, num(0)))
        expect("$key: 300000 is accepted", "Ok", piValueVerdict(key, num(300_000)))
        expect("$key: \"disabled\" is accepted", "Ok", piValueVerdict(key, str("disabled")))
        expect("$key: \"DISABLED\" is accepted (case-insensitive)", "Ok", piValueVerdict(key, str("DISABLED")))
        expect("$key: numeric string is accepted (pi runs Number())", "Ok", piValueVerdict(key, str("15000")))
        expect("$key: object is rejected", "Rejected", piValueVerdict(key, obj("a" to num(1))))
    }
    expect(
        "1e999 (Infinity) is rejected: parseHttpIdleTimeoutMs requires isFinite",
        "Rejected",
        piValueVerdict(KEY_HTTP_IDLE_TIMEOUT_MS, JsonPrimitive(Double.POSITIVE_INFINITY)),
    )

    // --------------------------------------------------- compaction token counts
    // pi: `ordinary !== undefined && (typeof !== "number" || !Number.isSafeInteger || < 0)` -> throw.
    for (key in listOf(KEY_COMPACTION_RESERVE_TOKENS, KEY_COMPACTION_KEEP_RECENT_TOKENS)) {
        expect("$key: null is rejected (pi throws)", "Rejected", piValueVerdict(key, JsonNull))
        expect("$key: string is rejected", "Rejected", piValueVerdict(key, str("16384")))
        expect("$key: negative is rejected", "Rejected", piValueVerdict(key, num(-1)))
        expect("$key: fractional is rejected (not a safe integer)", "Rejected", piValueVerdict(key, JsonPrimitive(1.5)))
        expect("$key: zero is accepted", "Ok", piValueVerdict(key, num(0)))
        expect("$key: 16384 is accepted", "Ok", piValueVerdict(key, num(16_384)))
        expect(
            "$key: beyond 2^53-1 is rejected (not a safe integer)",
            "Rejected",
            piValueVerdict(key, JsonPrimitive(1.0E18)),
        )
    }

    // ------------------------------------------------ compaction.modelOverrides
    expect(
        "modelOverrides: a well-formed override is accepted",
        "Ok",
        piValueVerdict(
            KEY_COMPACTION_MODEL_OVERRIDES,
            obj("openrouter/anthropic/claude-sonnet-4" to obj("reserveTokens" to num(12_000))),
        ),
    )
    expect(
        "modelOverrides: a non-object entry is rejected (pi throws)",
        "Rejected",
        piValueVerdict(KEY_COMPACTION_MODEL_OVERRIDES, obj("openrouter/x" to str("abc"))),
    )
    expect(
        "modelOverrides: a negative token inside an entry is rejected",
        "Rejected",
        piValueVerdict(KEY_COMPACTION_MODEL_OVERRIDES, obj("openrouter/x" to obj("keepRecentTokens" to num(-5)))),
    )
    expect(
        "modelOverrides: an unknown field inside an entry is only suspicious (pi ignores it)",
        "Suspicious",
        piValueVerdict(KEY_COMPACTION_MODEL_OVERRIDES, obj("openrouter/x" to obj("reserveToken" to num(1)))),
    )
    expect(
        "modelOverrides: a key without a slash never matches, so it is suspicious",
        "Suspicious",
        piValueVerdict(KEY_COMPACTION_MODEL_OVERRIDES, obj("claude" to obj("reserveTokens" to num(1)))),
    )
    expect(
        "modelOverrides: null is accepted (pi reads nothing and does not throw)",
        "Ok",
        piValueVerdict(KEY_COMPACTION_MODEL_OVERRIDES, JsonNull),
    )

    // --------------------------------------------------------- thinkingBudgets
    expect(
        "thinkingBudgets: the four levels with numbers are accepted",
        "Ok",
        piValueVerdict(KEY_THINKING_BUDGETS, obj("minimal" to num(1_000), "high" to num(9_000))),
    )
    expect(
        "thinkingBudgets: an unknown level is suspicious (pi never reads it)",
        "Suspicious",
        piValueVerdict(KEY_THINKING_BUDGETS, obj("max" to num(1_000))),
    )
    expect(
        "thinkingBudgets: a string budget is suspicious (the provider gets a non-number)",
        "Suspicious",
        piValueVerdict(KEY_THINKING_BUDGETS, obj("low" to str("1000"))),
    )

    // ------------------------------------------------------ modelThinkingLevels
    expect(
        "modelThinkingLevels: provider/model -> level is accepted",
        "Ok",
        piValueVerdict(KEY_MODEL_THINKING_LEVELS, obj("anthropic/claude-sonnet-4" to str("high"))),
    )
    expect(
        "modelThinkingLevels: 'off' is accepted (ModelThinkingLevel includes it)",
        "Ok",
        piValueVerdict(KEY_MODEL_THINKING_LEVELS, obj("anthropic/x" to str("off"))),
    )
    expect(
        "modelThinkingLevels: a misspelled level is suspicious",
        "Suspicious",
        piValueVerdict(KEY_MODEL_THINKING_LEVELS, obj("anthropic/x" to str("highest"))),
    )
    expect(
        "modelThinkingLevels: a key without a slash is suspicious",
        "Suspicious",
        piValueVerdict(KEY_MODEL_THINKING_LEVELS, obj("claude" to str("high"))),
    )
    check(
        "PI_THINKING_LEVELS is exactly pi's ThinkingLevel union",
        PI_THINKING_LEVELS == setOf("minimal", "low", "medium", "high", "xhigh", "max"),
        PI_THINKING_LEVELS.joinToString(),
    )
    check(
        "PI_MODEL_THINKING_LEVELS adds 'off' only",
        PI_MODEL_THINKING_LEVELS == PI_THINKING_LEVELS + "off",
        PI_MODEL_THINKING_LEVELS.joinToString(),
    )

    // ------------------------------------------------------------------ outputPad
    // pi: `settings.outputPad === 0 ? 0 : 1` — never throws, everything but 0 means 1.
    expect("outputPad: 0 is accepted", "Ok", piValueVerdict(KEY_OUTPUT_PAD, num(0)))
    expect("outputPad: 1 is accepted", "Ok", piValueVerdict(KEY_OUTPUT_PAD, num(1)))
    expect("outputPad: the string \"0\" is suspicious (pi reads it as 1)", "Suspicious", piValueVerdict(KEY_OUTPUT_PAD, str("0")))
    expect("outputPad: null is suspicious (pi reads it as 1)", "Suspicious", piValueVerdict(KEY_OUTPUT_PAD, JsonNull))

    // ------------------------------------ keys pi does not validate are not "Ok" by accident
    expect("retry.maxRetries takes anything pi does not validate", "Ok", piValueVerdict("retry.maxRetries", num(99)))
    expect("branchSummary.reserveTokens is unvalidated by pi", "Ok", piValueVerdict("branchSummary.reserveTokens", num(-1)))
    expect("an unregistered key is left alone", "Ok", piValueVerdict("theme", str("dark")))

    // ------------------------------------------------------- object editor lines
    expect(
        "line verdict: a bad compaction override line is rejected",
        "Rejected",
        piObjectLineVerdict(KEY_COMPACTION_MODEL_OVERRIDES, "openrouter/x", str("abc")),
    )
    expect(
        "line verdict: a good compaction override line is accepted",
        "Ok",
        piObjectLineVerdict(KEY_COMPACTION_MODEL_OVERRIDES, "openrouter/x", obj("reserveTokens" to num(1))),
    )
    expect(
        "line verdict: a bad thinking-budget line is suspicious",
        "Suspicious",
        piObjectLineVerdict(KEY_THINKING_BUDGETS, "max", num(1)),
    )
    expect(
        "line verdict: a good thinking-budget line is accepted",
        "Ok",
        piObjectLineVerdict(KEY_THINKING_BUDGETS, "high", num(1)),
    )

    // -------------------------------------------------------- number editor text
    // The editor must not repeat `intValueOrNull`'s tolerance: "1.5" is *not* 1.
    check("parseEditedInt accepts an integer", parseEditedInt(" 16384 ") == 16_384)
    check("parseEditedInt rejects a fraction", parseEditedInt("1.5") == null)
    check("parseEditedInt rejects text", parseEditedInt("abc") == null)
    check("parseEditedInt rejects empty", parseEditedInt("") == null)
    check(
        "parseEditedInt rejects overflow instead of wrapping",
        parseEditedInt("99999999999") == null,
    )
    check(
        "number verdict: in range parses",
        piNumberTextVerdict("300000", 0, 3_600_000) == PiNumberVerdict.Parsed(300_000),
    )
    check(
        "number verdict: out of range is reported with the bounds",
        piNumberTextVerdict("99999999", 0, 3_600_000) == PiNumberVerdict.OutOfRange(99_999_999, 0, 3_600_000),
    )
    check(
        "number verdict: a fraction is NotAnInteger, not a silent floor",
        piNumberTextVerdict("1.5", 0, 10) == PiNumberVerdict.NotAnInteger,
    )
    check(
        "number verdict: negative is out of range where min is 0",
        piNumberTextVerdict("-1", 0, 10) == PiNumberVerdict.OutOfRange(-1, 0, 10),
    )

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) exitProcess(1)
}
