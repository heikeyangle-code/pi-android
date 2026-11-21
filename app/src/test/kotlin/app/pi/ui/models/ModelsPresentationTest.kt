package app.pi.ui.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsPresentationTest {

    @Test
    fun sectionProvidersPromotesAllOAuthIncludingOpenRouterToTopSection() {
        val providers = listOf(
            ProviderFacts("deepseek", "DeepSeek", configured = false, usingOAuth = false, usingSubscription = false, source = null, offersSubscription = false, modelCount = 2, custom = false, offersWebLogin = false),
            ProviderFacts("openrouter", "OpenRouter", configured = false, usingOAuth = false, usingSubscription = false, source = null, offersSubscription = false, modelCount = 120, custom = false, offersWebLogin = true),
            ProviderFacts("anthropic", "Anthropic", configured = false, usingOAuth = false, usingSubscription = false, source = null, offersSubscription = true, modelCount = 8, custom = false, offersWebLogin = false),
            ProviderFacts("openai-codex", "ChatGPT", configured = true, usingOAuth = true, usingSubscription = true, source = "stored", offersSubscription = true, modelCount = 5, custom = false, offersWebLogin = false),
            ProviderFacts("openai", "OpenAI", configured = true, usingOAuth = false, usingSubscription = false, source = "stored", offersSubscription = false, modelCount = 15, custom = false, offersWebLogin = false),
        )

        val sections = sectionProviders(providers, query = "")

        // Connected OAuth first, then unconnected Subscription, then unconnected WebLogin (OpenRouter)
        assertEquals(
            listOf("openai-codex", "anthropic", "openrouter"),
            sections.accountProviders.map { it.id },
        )
        assertEquals(listOf("openai"), sections.connectedApi.map { it.id })
        assertEquals(listOf("deepseek"), sections.otherApi.map { it.id })
        // Mutually exclusive, no duplicates
        val allIds = sections.accountProviders.map { it.id } +
            sections.connectedApi.map { it.id } +
            sections.otherApi.map { it.id }
        assertEquals(providers.size, allIds.toSet().size)
    }

    @Test
    fun connectedOpenRouterStaysInTopSectionWithoutJumping() {
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
            offersWebLogin = false,
        )

        val sections = sectionProviders(listOf(anthropic, openrouter), query = "")
        assertEquals(listOf("openrouter", "anthropic"), sections.accountProviders.map { it.id })
        assertTrue(sections.connectedApi.isEmpty())
        assertTrue(sections.otherApi.isEmpty())
        assertEquals(Connection.WebLogin, connectionOf(openrouter))
    }
}
