package app.pi.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFormTest {

    @Test
    fun compatAndPerModelOverridesRoundTripLossless() {
        val raw = Json.parseToJsonElement(
            """
            {
              "name": "My Gateway",
              "baseUrl": "https://gw.example.com/v1",
              "api": "openai-completions",
              "apiKey": "${'$'}GW_KEY",
              "authHeader": true,
              "compat": {
                "thinkingFormat": "deepseek",
                "maxTokensField": "max_completion_tokens",
                "supportsDeveloperRole": false,
                "supportsReasoningEffort": true,
                "supportsStore": false,
                "supportsUsageInStreaming": false,
                "openRouterRouting": { "allow_fallbacks": false }
              },
              "modelOverrides": {
                "claude-sonnet-4-5": { "contextWindow": 200000 },
                "claude-opus-4-5": { "maxTokens": 16384 }
              },
              "models": [
                {
                  "id": "r1",
                  "name": "DeepSeek R1",
                  "api": "openai-responses",
                  "baseUrl": "https://r1.example.com/v1",
                  "reasoning": true,
                  "input": ["text", "image"],
                  "contextWindow": 131072,
                  "maxTokens": 16384,
                  "compat": {
                    "thinkingFormat": "qwen",
                    "supportsStore": true,
                    "vllmPriority": 2
                  }
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val form = ProviderForm.from("my-gw", raw)
        assertEquals(2, form.modelOverridesCount)
        assertEquals("deepseek", form.compat.thinkingFormat)
        assertEquals("max_completion_tokens", form.compat.maxTokensField)
        assertEquals(false, form.compat.supportsDeveloperRole)
        assertEquals(true, form.compat.supportsReasoningEffort)
        assertEquals(false, form.compat.supportsStore)
        assertTrue(form.compat.extra.containsKey("supportsUsageInStreaming"))
        assertTrue(form.compat.extra.containsKey("openRouterRouting"))

        assertEquals(1, form.models.size)
        val m = form.models.first()
        assertEquals("openai-responses", m.api)
        assertEquals("https://r1.example.com/v1", m.baseUrl)
        assertEquals("qwen", m.compat.thinkingFormat)
        assertEquals(true, m.compat.supportsStore)
        assertNull(m.compat.supportsDeveloperRole)
        assertTrue(m.compat.extra.containsKey("vllmPriority"))

        // Mutate a compat toggle and serialize back
        val edited = form.copy(
            compat = form.compat.copy(supportsDeveloperRole = null, thinkingFormat = "qwen"),
            models = listOf(m.copy(compat = m.compat.copy(maxTokensField = "max_tokens"))),
        )
        val result = edited.toJson()
        assertTrue(result is ProviderForm.Result.Ok)
        val out = (result as ProviderForm.Result.Ok).json

        // Provider compat preserves unknown keys and applies UI edits
        val outCompat = out["compat"]!!.jsonObject
        assertEquals("qwen", outCompat["thinkingFormat"]!!.jsonPrimitive.content)
        assertNull(outCompat["supportsDeveloperRole"])
        assertEquals(true, outCompat["supportsReasoningEffort"]!!.jsonPrimitive.boolean)
        assertEquals(false, outCompat["supportsUsageInStreaming"]!!.jsonPrimitive.boolean)
        assertTrue(outCompat.containsKey("openRouterRouting"))

        // modelOverrides preserved lossless
        assertEquals(2, out["modelOverrides"]!!.jsonObject.size)

        // Per-model api/baseUrl/compat preserved
        val outModel = out["models"]!!.jsonArray.first().jsonObject
        assertEquals("openai-responses", outModel["api"]!!.jsonPrimitive.content)
        assertEquals("https://r1.example.com/v1", outModel["baseUrl"]!!.jsonPrimitive.content)
        val outModelCompat = outModel["compat"]!!.jsonObject
        assertEquals("qwen", outModelCompat["thinkingFormat"]!!.jsonPrimitive.content)
        assertEquals("max_tokens", outModelCompat["maxTokensField"]!!.jsonPrimitive.content)
        assertEquals("2", outModelCompat["vllmPriority"]!!.jsonPrimitive.content)
    }

    @Test
    fun emptyCompatIsOmittedFromSerializedJson() {
        val form = ProviderForm(
            id = "clean",
            baseUrl = "https://api.example.com/v1",
            api = "openai-completions",
            models = listOf(ProviderForm.ModelForm(id = "gpt-4o")),
        )
        val out = (form.toJson() as ProviderForm.Result.Ok).json
        assertNull(out["compat"])
        val m = out["models"]!!.jsonArray.first().jsonObject
        assertNull(m["compat"])
        assertNull(m["api"])
        assertNull(m["baseUrl"])
    }
}
