package app.pi.ui.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsPresentationTest {

    @Test
    fun unifiedProvidersOrdersActiveAndConnectedFirstThenCustomAndOAuthWithoutDuplicates() {
        val providers = listOf(
            ProviderFacts("deepseek", "DeepSeek", configured = false, usingOAuth = false, usingSubscription = false, source = null, offersSubscription = false, modelCount = 2, custom = false, offersWebLogin = false, offersApiKey = true),
            ProviderFacts("openrouter", "OpenRouter", configured = false, usingOAuth = false, usingSubscription = false, source = null, offersSubscription = false, modelCount = 120, custom = false, offersWebLogin = true, offersApiKey = true),
            ProviderFacts("anthropic", "Anthropic", configured = false, usingOAuth = false, usingSubscription = false, source = null, offersSubscription = true, modelCount = 8, custom = false, offersWebLogin = false, offersApiKey = true),
            ProviderFacts("my-proxy", "My Proxy", configured = false, usingOAuth = false, usingSubscription = false, source = null, offersSubscription = false, modelCount = 3, custom = true, customBaseUrl = "https://proxy.example.com/v1"),
            ProviderFacts("openai-codex", "ChatGPT", configured = true, usingOAuth = true, usingSubscription = true, source = "stored", offersSubscription = true, modelCount = 5, custom = false),
            ProviderFacts("openai", "OpenAI", configured = true, usingOAuth = false, usingSubscription = false, source = "stored", offersSubscription = false, modelCount = 15, custom = false, isActiveProvider = true),
        )

        val view = buildUnifiedProviders(providers, query = "", filter = ProviderFilter.All, expanded = true)

        assertEquals(
            listOf("openai", "openai-codex", "my-proxy", "anthropic", "openrouter", "deepseek"),
            view.visible.map { it.id },
        )
        assertEquals(6, view.counts.all)
        assertEquals(2, view.counts.connected)
        assertEquals(3, view.counts.account)
        assertEquals(1, view.counts.custom)
        assertEquals(providers.size, view.visible.map { it.id }.toSet().size)
    }

    @Test
    fun unifiedProvidersFiltersAndMatchesCustomEndpointUrl() {
        val openrouter = ProviderFacts(
            id = "openrouter",
            name = "OpenRouter",
            configured = true,
            usingOAuth = true,
            usingSubscription = false,
            source = "stored",
            offersSubscription = false,
            modelCount = 120,
            custom = false,
            offersWebLogin = true,
        )
        val proxy = ProviderFacts(
            id = "corp-gw",
            name = "Corp Gateway",
            configured = true,
            usingOAuth = false,
            usingSubscription = false,
            source = "stored",
            offersSubscription = false,
            modelCount = 4,
            custom = true,
            customBaseUrl = "https://llm.internal.corp/v1",
        )
        val anthropic = ProviderFacts(
            id = "anthropic",
            name = "Anthropic",
            configured = false,
            usingOAuth = false,
            usingSubscription = false,
            source = null,
            offersSubscription = true,
            modelCount = 8,
            custom = false,
        )

        val list = listOf(anthropic, openrouter, proxy)
        assertEquals(
            listOf("openrouter", "anthropic"),
            buildUnifiedProviders(list, query = "", filter = ProviderFilter.Account).visible.map { it.id },
        )
        assertEquals(
            listOf("corp-gw"),
            buildUnifiedProviders(list, query = "internal.corp", filter = ProviderFilter.All).visible.map { it.id },
        )
        assertEquals(
            listOf("corp-gw"),
            buildUnifiedProviders(list, query = "", filter = ProviderFilter.Custom).visible.map { it.id },
        )
        assertEquals("llm.internal.corp", shortEndpointHost(proxy.customBaseUrl))
        assertEquals(Connection.WebLogin, connectionOf(openrouter))
    }

    @Test
    fun unifiedProvidersNeverFoldsConnectedCustomOrOAuthProvidersByDefault() {
        val oauthAndConnected = (1..12).map { i ->
            ProviderFacts(
                id = "oauth-$i",
                name = "OAuth $i",
                configured = i <= 2,
                usingOAuth = i <= 2,
                usingSubscription = false,
                source = if (i <= 2) "stored" else null,
                offersSubscription = true,
                modelCount = 3,
                custom = false,
            )
        }
        val tailApi = (1..8).map { i ->
            ProviderFacts(
                id = "tail-$i",
                name = "Tail $i",
                configured = false,
                usingOAuth = false,
                usingSubscription = false,
                source = null,
                offersSubscription = false,
                modelCount = 2,
                custom = false,
                offersApiKey = true,
            )
        }
        val view = buildUnifiedProviders(oauthAndConnected + tailApi, query = "", filter = ProviderFilter.All, expanded = false)
        assertTrue(view.visible.map { it.id }.containsAll(oauthAndConnected.map { it.id }))
        assertEquals(8, view.hiddenCount)
    }
}
