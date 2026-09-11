package app.pi.packages

/**
 * The vendors the import screen offers, with pi's own `baseUrl`/`api` for each.
 *
 * Every entry that says "pi" below was read off pi's provider factory, not from a
 * vendor's homepage:
 *
 * | provider | `baseUrl` | `api` | source |
 * |---|---|---|---|
 * | openai | `https://api.openai.com/v1` | `openai-responses` | `packages/ai/src/providers/openai.ts:8-13` |
 * | anthropic | `https://api.anthropic.com` | `anthropic-messages` | `providers/anthropic.ts:45-57` |
 * | google | `https://generativelanguage.googleapis.com/v1beta` | `google-generative-ai` | `providers/google.ts:8-13` |
 * | deepseek | `https://api.deepseek.com` | `openai-completions` | `providers/deepseek.ts:8-13` |
 * | groq | `https://api.groq.com/openai/v1` | `openai-completions` | `providers/groq.ts:8-13` |
 * | openrouter | `https://openrouter.ai/api/v1` | `openai-completions` | `providers/openrouter.ts:10-16` |
 * | xai | `https://api.x.ai/v1` | `openai-responses` | `providers/xai.ts:9-22` |
 * | cerebras | `https://api.cerebras.ai/v1` | `openai-completions` | `providers/cerebras.ts:8-13` |
 * | zai | `https://api.z.ai/api/coding/paas/v4` | `openai-completions` | `providers/zai.ts:8-13` |
 * | mistral | `https://api.mistral.ai` | `mistral-conversations` | `providers/mistral.ts:8-13` |
 *
 * The `api` value is not decoration: it decides which streaming implementation pi
 * uses, and it is a `KnownApi` literal (`packages/ai/src/types.ts:17-27`). Guessing
 * it wrong makes the provider register and then fail on the first message, which is
 * exactly the class of failure the import screen exists to avoid.
 *
 * Two entries are **app-side** and are marked as such: `ollama` (pi has no built-in
 * Ollama provider; the docs' own example adds it via `models.json`) and the
 * catch-all custom row.
 */
object PiProviderPresets {

    /**
     * How the app probes a vendor for its model list. This is the app's knowledge,
     * not pi's — see [PiModelScanner] for why that distinction is stated out loud.
     */
    enum class ScanStyle {
        /** `GET {base}/models` with `Authorization: Bearer <key>`. */
        OpenAiCompatible,

        /** `GET {base}/v1/models` with `x-api-key` and `anthropic-version`. */
        Anthropic,

        /** `GET {base}/models?key=<key>`. */
        Google,

        /** `GET {base}/models`, no credential (llama.cpp, LM Studio, vLLM). */
        Keyless,
    }

    data class Preset(
        /** The provider id written into `models.json` and `auth.json`. */
        val id: String,
        /** What the user sees. pi's own `name` where it has one. */
        val displayName: String,
        val baseUrl: String,
        val api: String,
        val scanStyle: ScanStyle,
        /** `authHeader`: true adds `Authorization: Bearer`. */
        val authHeader: Boolean,
        /** True for pi's built-ins; false for entries the app adds. */
        val builtInPi: Boolean,
        /** Grouping in the picker. */
        val group: String,
    )

    val all: List<Preset> = listOf(
        Preset("openai", "OpenAI", "https://api.openai.com/v1", "openai-responses", ScanStyle.OpenAiCompatible, true, true, "云端"),
        Preset("anthropic", "Anthropic", "https://api.anthropic.com", "anthropic-messages", ScanStyle.Anthropic, false, true, "云端"),
        Preset("google", "Google AI Studio", "https://generativelanguage.googleapis.com/v1beta", "google-generative-ai", ScanStyle.Google, false, true, "云端"),
        Preset("deepseek", "DeepSeek", "https://api.deepseek.com", "openai-completions", ScanStyle.OpenAiCompatible, true, true, "云端"),
        Preset("xai", "xAI", "https://api.x.ai/v1", "openai-responses", ScanStyle.OpenAiCompatible, true, true, "云端"),
        Preset("groq", "Groq", "https://api.groq.com/openai/v1", "openai-completions", ScanStyle.OpenAiCompatible, true, true, "云端"),
        Preset("cerebras", "Cerebras", "https://api.cerebras.ai/v1", "openai-completions", ScanStyle.OpenAiCompatible, true, true, "云端"),
        Preset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openai-completions", ScanStyle.OpenAiCompatible, true, true, "聚合"),
        Preset("mistral", "Mistral", "https://api.mistral.ai", "mistral-conversations", ScanStyle.OpenAiCompatible, true, true, "云端"),
        Preset("zai", "Z.AI", "https://api.z.ai/api/coding/paas/v4", "openai-completions", ScanStyle.OpenAiCompatible, true, true, "云端"),
        Preset("ollama", "Ollama（本机/局域网）", "http://localhost:11434/v1", "openai-completions", ScanStyle.Keyless, true, false, "本地"),
        Preset("llamacpp", "llama.cpp / LM Studio / vLLM", "http://localhost:8080/v1", "openai-completions", ScanStyle.Keyless, true, false, "本地"),
        Preset("custom", "自定义（OpenAI 兼容）", "", "openai-completions", ScanStyle.OpenAiCompatible, true, false, "自定义"),
    )

    fun byId(id: String): Preset? = all.firstOrNull { it.id == id }

    /**
     * Keyless rows still need *a* credential in `auth.json`, or pi will not list the
     * provider's models:
     *
     * > pi still treats models as requiring auth before they appear in `/model`, so
     * > keyless local servers should keep a dummy value
     * > — `docs/models.md:34-36`
     *
     * This is that dummy value, written only when the user left the key empty on a
     * keyless preset.
     */
    const val KEYLESS_PLACEHOLDER = "local"
}
