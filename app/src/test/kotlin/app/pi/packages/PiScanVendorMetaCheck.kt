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
            silent.reasoning,
            silent.costInputPerMillion,
            silent.costOutputPerMillion,
            silent.costCacheReadPerMillion,
            silent.costCacheWritePerMillion,
        ),
        listOf(null, null, null, null, null, null, null, null),
    )
    check("and that is not 'metadata was supplied'", silent.any, false)

    val junk = meta("""{"id":"x","context_length":"a lot","pricing":{"prompt":"free"}}""")
    check(
        "a non-numeric value is ignored, not zeroed",
        listOf(junk.contextWindow, junk.costInputPerMillion),
        listOf(null, null),
    )

    // -- 其余几种真实形状：同一件事在别家的拼法 -------------------------------
    //
    // vLLM 的 `/v1/models`：`max_model_len` 是它唯一的上下文读数。
    val vllm = meta("""{"id":"Qwen3-32B","max_model_len":32768,"max_output_tokens":8192}""")
    check("vLLM max_model_len is the context", vllm.contextWindow, 32768L)
    check("vLLM max_output_tokens is the max output", vllm.maxTokens, 8192L)

    // LM Studio 的 `/api/v0/models`。
    val lmStudio = meta(
        """{"id":"qwen3-8b","type":"llm","max_context_length":16384,
            "capabilities":["completion","vision","tools"]}""",
    )
    check("LM Studio max_context_length is the context", lmStudio.contextWindow, 16384L)
    check("a vision capability means images", lmStudio.acceptsImages, true)

    // Google 的 `models.list`：驼峰，两个上限。
    val google = meta("""{"name":"models/gemini-2.0-flash","inputTokenLimit":1048576,"outputTokenLimit":8192}""")
    check("Google inputTokenLimit is the context", google.contextWindow, 1048576L)
    check("Google outputTokenLimit is the max output", google.maxTokens, 8192L)

    // models.dev 的形状：limit + 每百万美元 cost + modalities。
    val modelsDev = meta(
        """{"id":"claude-sonnet-4-5","limit":{"context":200000,"output":64000},
            "cost":{"input":3,"output":15},"modalities":{"input":["text","image"]},
            "reasoning":true}""",
    )
    check("models.dev limit.context is the context", modelsDev.contextWindow, 200000L)
    check("models.dev limit.output is the max output", modelsDev.maxTokens, 64000L)
    check("models.dev cost is already per million", modelsDev.costInputPerMillion, 3.0)
    check("models.dev cost output is already per million", modelsDev.costOutputPerMillion, 15.0)
    check("models.dev modalities.input with image means images", modelsDev.acceptsImages, true)

    // LiteLLM 的形状：per-token 价格 + supports_vision。
    val liteLlm = meta(
        """{"id":"gpt-4o","max_input_tokens":128000,"max_output_tokens":16384,
            "input_cost_per_token":0.0000025,"output_cost_per_token":0.00001,
            "supports_vision":true}""",
    )
    check("LiteLLM max_input_tokens is the context", liteLlm.contextWindow, 128000L)
    check("LiteLLM per-token input price becomes per-million", liteLlm.costInputPerMillion, 2.5)
    check("LiteLLM per-token output price becomes per-million", liteLlm.costOutputPerMillion, 10.0)
    check("supports_vision is read", liteLlm.acceptsImages, true)

    val textOnlyFlag = meta("""{"id":"x","supports_vision":false}""")
    check("an explicit supports_vision=false is a no, not unknown", textOnlyFlag.acceptsImages, false)

    // OpenRouter 的调用方式在别处已经钉过，这里只钉"两种价格不会互相污染"：
    // `cost` 是每百万，`pricing` 是每 token，同一个数字不能既乘又不乘。
    val both = meta("""{"pricing":{"prompt":"0.000003"},"cost":{"output":15}}""")
    check("pricing wins for input", both.costInputPerMillion, 3.0)
    check("cost fills output", both.costOutputPerMillion, 15.0)

    // -- 思考能力与缓存价：三个最容易被"拉不到"的字段 ---------------------------
    //
    // OpenRouter：`supported_parameters` 里有 reasoning / include_reasoning。
    val orReasoning = meta(
        """{"id":"x","supported_parameters":["temperature","reasoning","include_reasoning"]}""",
    )
    check("supported_parameters with reasoning means thinking", orReasoning.reasoning, true)
    check(
        "and the cache prices are per-token, converted once",
        meta(
            """{"id":"x","pricing":{"input_cache_read":"0.0000003","input_cache_write":"0.00000375"}}""",
        ).let { it.costCacheReadPerMillion to it.costCacheWritePerMillion },
        0.3 to 3.75,
    )

    // Ollama 的 capabilities 数组 / 厂商的对象形状。
    check(
        "capabilities with thinking means thinking",
        meta("""{"id":"x","capabilities":["completion","thinking"]}""").reasoning,
        true,
    )
    check(
        "capabilities object with reasoning means thinking",
        meta("""{"id":"x","capabilities":{"reasoning":true,"vision":false}}""").reasoning,
        true,
    )

    // models.dev 的形状：reasoning 布尔 + 每百万美元缓存价。
    val dev = meta("""{"id":"x","reasoning":true,"cost":{"cache_read":0.3,"cache_write":3.75}}""")
    check("models.dev reasoning is read", dev.reasoning, true)
    check("models.dev cache read is already per million", dev.costCacheReadPerMillion, 0.3)
    check("models.dev cache write is already per million", dev.costCacheWritePerMillion, 3.75)

    // 一个只会说话、不会思考的模型：`reasoning: false` 是明确的 no，不是未知。
    check(
        "an explicit reasoning=false is a no",
        meta("""{"id":"x","reasoning":false}""").reasoning,
        false,
    )

    // OpenRouter 的默认采样参数 → pi 的 samplingParams：厂商说了才带，原样保留。
    val sampled = meta("""{"id":"x","default_parameters":{"temperature":1,"top_p":0.95}}""")
    check(
        "default_parameters becomes samplingParams",
        sampled.samplingParams?.get("top_p")?.toString(),
        "0.95",
    )
    check("absent default_parameters stays null", silent.samplingParams, null)

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
