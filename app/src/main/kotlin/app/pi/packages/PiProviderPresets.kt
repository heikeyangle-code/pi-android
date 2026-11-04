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
 * **Two 0.86.1 providers are deliberately not rows here, and both are reachable
 * anyway.** `meta` (Meta Muse subscription, `providers/meta.ts:8-24`) and `radius`
 * (offline Radius catalog, `providers/radius.ts`) are OAuth/subscription providers;
 * their login flow lives in the app now (`ui/settings/SubscriptionLogin.kt` runs pi's
 * own `auth.oauth.login()` inside the guest), so they do not need an API-key row
 * here — the 「订阅账号」 card lists them straight from pi. A
 * `meta` row for the **API-key** half (`META_API_KEY`) is a real, separate decision
 * and needs one fact this file does not have: which [ScanStyle] `api.meta.ai/v1`
 * answers, because pi's `openai-responses` says nothing about a `GET /models`
 * endpoint, and this table's whole discipline is that its scan style is read off a
 * working probe rather than inferred from the chat API.
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
        /**
         * The provider id written into `models.json` and `auth.json`.
         *
         * For a provider pi already ships this has to be **pi's own id** — the one string
         * pi matches on — not this app's spelling: `llama.cpp`, never `llamacpp`. See the
         * local-model entry in [all].
         */
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
        // **id 用 pi 自己的 `llama.cpp`，不是一个 App 自造的短名。**
        // 这个字符串会原样写进 `models.json` 的厂商块与 `auth.json` 的凭证条目，即
        // pi 的 provider id —— 而 pi 内建的那个就叫 `llama.cpp`（注册表 `providerOptions`
        // 里同一串，"llama.cpp" to "本地 llama.cpp"）。写 `llamacpp` 会在 pi 眼里造出
        // **第二个厂商**：内建的 `llama.cpp` 一个模型都没有，用户配的那个只在 App 的拼写
        // 下存在，模型选择器里于是同时有两条互不相干的本地厂商。这里不做迁移（这个 App
        // 还没发布过，`llamacpp` 只存在于本轮之前的源码里）。
        Preset("llama.cpp", "llama.cpp / LM Studio / vLLM", "http://localhost:8080/v1", "openai-completions", ScanStyle.Keyless, true, false, "本地"),
        // 「新建一个」的入口，不是一个具体的厂商：`id` 是占位（[NEW_CUSTOM_ID]），真正写进
        // `models.json` / `auth.json` 的 id 在保存时按名字现生成（[uniqueCustomProviderId]），所以
        // 可以导入任意多个自定义厂商。以前这里固定写 `custom`，第二个自定义厂商只能覆盖第一个。
        Preset(NEW_CUSTOM_ID, "新建自定义厂商（OpenAI 兼容）", "", "openai-completions", ScanStyle.OpenAiCompatible, true, false, "自定义"),
    )

    /** 「新建自定义厂商」入口的占位 id：不是合法的厂商 id，永远不会被写进文件。 */
    const val NEW_CUSTOM_ID = "+custom"

    /**
     * 为新建的自定义厂商生成**不与任何已有 id 冲突**的厂商 id：名字（或端点主机名）转成
     * 小写 ASCII slug（`api.foo.com` → `api-foo-com`），撞了就加 `-2`、`-3`。
     * 只含 `[a-z0-9-]`：id 会出现在 `enabledModels` 的 `provider/model` 模式里，不能带 `/`、`:`、空格。
     * 取不出任何字符（纯中文名）时退回 `custom`。
     */
    fun uniqueCustomProviderId(name: String, taken: Set<String>): String {
        val slug = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(32)
            .trim('-')
            .ifEmpty { "custom" }
        if (slug !in taken) return slug
        var n = 2
        while ("$slug-$n" in taken) n++
        return "$slug-$n"
    }

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
