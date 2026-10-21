package app.pi.packages

import kotlinx.serialization.json.Json

// A bare-JVM harness for `PiModelsDev.kt` — the models.dev index that fills in what a
// vendor's `/models` never publishes, above all the thinking-level map. Registered in
// `tools/run-app-pure-checks.sh` as `models-dev`.
//
// Why it must be pinned: the map is derived from `reasoning_options`, and getting it
// wrong is silent — pi would offer a level the endpoint rejects, or hide one it
// supports. The shapes below are models.dev's real ones (`api.json`), including the
// relay-only providers.

var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private fun map(vararg pairs: Pair<String, String?>) =
    pairs.associate { (k, v) -> k to v }

fun main() {
    val api = """
        {
          "anthropic": {
            "models": {
              "claude-opus-4-6": {
                "id": "claude-opus-4-6",
                "name": "Claude Opus 4.6",
                "reasoning": true,
                "limit": {"context": 200000, "output": 64000},
                "cost": {
                  "input": 15, "output": 75, "cache_read": 1.5, "cache_write": 18.75,
                  "tiers": [
                    {"tier": {"type": "context", "size": 512000},
                     "input": 30, "output": 150, "cache_read": 3, "cache_write": 37.5}
                  ]
                },
                "modalities": {"input": ["text", "image"]},
                "reasoning_options": [
                  {"type": "toggle"},
                  {"type": "effort", "values": ["none", "low", "medium", "high", "max"]}
                ]
              }
            }
          },
          "some-relay": {
            "models": {
              "gpt-5": {
                "id": "gpt-5",
                "reasoning": true,
                "limit": {"context": 400000, "output": 128000},
                "cost": {"input": 1.25, "output": 10},
                "modalities": {"input": ["text"]}
              }
            }
          }
        }
    """.trimIndent()

    val (exact, bare) = PiModelsDev.parse(api)
    val opus = PiModelsDev.lookup(exact to bare, "anthropic", "claude-opus-4-6")
    check("exact provider/model is found", opus?.id, "claude-opus-4-6")
    check("context comes from limit.context", opus?.contextWindow, 200000L)
    check("max output comes from limit.output", opus?.maxTokens, 64000L)
    check("images come from modalities.input", opus?.acceptsImages, true)
    check("cost is already per million", opus?.costInput, 15.0)
    check("cache read is already per million", opus?.costCacheRead, 1.5)
    check("reasoning is read", opus?.reasoning, true)
    check("the display name is read", opus?.name, "Claude Opus 4.6")

    // 长上下文档位价 → pi 的 cost.tiers（inputTokensAbove + 四项费率）。
    val tier = opus?.costTiers?.firstOrNull() as? kotlinx.serialization.json.JsonObject
    check("tier threshold is the context size", tier?.get("inputTokensAbove")?.toString(), "512000")
    check("tier input rate is kept", tier?.get("input")?.toString(), "30.0")
    check("tier cache write rate is kept", tier?.get("cacheWrite")?.toString(), "37.5")

    // 中转站：厂商名不在 models.dev 里，按裸模型 id 兜底找到。
    val relay = PiModelsDev.lookup(exact to bare, "my-relay", "gpt-5")
    check("a relay model is found by bare id", relay?.contextWindow, 400000L)
    check("and the relay's model name is not rewritten", relay?.id, "gpt-5")

    // 思考档位：照着 pi 的 `getEffortThinkingLevelMap`。
    val mapValues = opus?.thinkingLevelMap?.mapValues { (_, v) ->
        if (v is kotlinx.serialization.json.JsonNull) null else v.toString().trim('"')
    }
    check(
        "effort values become pi's levels",
        mapValues,
        map(
            "off" to "none",
            "minimal" to null,
            "low" to "low",
            "medium" to "medium",
            "high" to "high",
            "xhigh" to null,
            "max" to "max",
        ),
    )
    check(
        "a model with no reasoning_options gets no map",
        relay?.thinkingLevelMap,
        null,
    )
    check(
        "toggle-only options are not a level map",
        PiModelsDev.thinkingLevelMap(
            Json.parseToJsonElement("""[{"type":"toggle"}]"""),
        ),
        null,
    )
    check(
        "effort without any pi level is not a level map",
        PiModelsDev.thinkingLevelMap(
            Json.parseToJsonElement("""[{"type":"effort","values":["default"]}]"""),
        ),
        null,
    )

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
