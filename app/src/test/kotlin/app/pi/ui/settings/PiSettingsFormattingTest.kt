package app.pi.ui.settings

import app.pi.ui.theme.PiThemeStudio
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiSettingsFormattingTest {

    @Test
    fun `disabled string on timeout keys displays as disabled and parses to 0`() {
        val store = InMemoryPiSettingsStore(
            initial = mapOf(
                "httpIdleTimeoutMs" to JsonPrimitive("disabled"),
                "websocketConnectTimeoutMs" to JsonPrimitive("DISABLED"),
            ),
        )
        val httpSetting = PiSettingsCatalog.byKey["httpIdleTimeoutMs"]
        val wsSetting = PiSettingsCatalog.byKey["websocketConnectTimeoutMs"]
        assertNotNull(httpSetting)
        assertNotNull(wsSetting)

        assertEquals(0, httpSetting!!.timeoutMsIn(store, 300_000))
        assertEquals(0, wsSetting!!.timeoutMsIn(store, 15_000))

        val httpDisplay = httpSetting.display(httpSetting.current(store))
        val wsDisplay = wsSetting.display(wsSetting.current(store))
        assertTrue("Expected disabled display, got: $httpDisplay", httpDisplay == "不超时" || httpDisplay == "Disabled")
        assertTrue("Expected disabled display, got: $wsDisplay", wsDisplay == "不限时" || wsDisplay == "Disabled")
    }

    @Test
    fun `duration and token formatters produce human readable labels`() {
        val fiveMin = formatDurationMs(300_000)
        assertTrue(fiveMin == "5 分钟" || fiveMin == "5 min")
        val fifteenSec = formatDurationMs(15_000)
        assertTrue(fifteenSec == "15 秒" || fifteenSec == "15s")
        assertEquals("500 ms", formatDurationMs(500))

        assertEquals("0", formatTokenCount(0))
        assertEquals("16K", formatTokenCount(16_384))
        assertEquals("20K", formatTokenCount(20_000))
        assertEquals("3K", formatTokenCount(3_000))
        assertEquals("1.5K", formatTokenCount(1_500))
        assertEquals("1M", formatTokenCount(1_000_000))
    }

    @Test
    fun `new official Pi 1_6_0 settings keys are registered with expected defaults`() {
        val extraUsage = PiSettingsCatalog.byKey["warnings.anthropicExtraUsage"]
        assertNotNull(extraUsage)
        assertEquals(JsonPrimitive(true), extraUsage!!.defaultValue)

        val analytics = PiSettingsCatalog.byKey["enableAnalytics"]
        assertNotNull(analytics)
        assertEquals(JsonPrimitive(false), analytics!!.defaultValue)

        val branchReserve = PiSettingsCatalog.byKey["branchSummary.reserveTokens"]
        assertNotNull(branchReserve)
        assertTrue("Outdated 4096 cap should be removed", !branchReserve!!.description.contains("4096"))

        for (noticeKey in listOf(
            "notices.cacheMisses",
            "notices.cacheWarming",
            "notices.compactionCosts",
            "notices.reasoningDrops",
        )) {
            val s = PiSettingsCatalog.byKey[noticeKey]
            assertNotNull("Expected Pi 1.6.1 setting $noticeKey", s)
            assertEquals(JsonPrimitive(false), s!!.defaultValue)
        }
        assertEquals(null, PiSettingsCatalog.byKey["showCacheMissNotices"])
    }

    @Test
    fun `theme studio exposes twelve iconic dual-mode master presets`() {
        assertEquals(12, PiThemeStudio.masterPresets.size)
        val ids = PiThemeStudio.masterPresets.map { it.id }.toSet()
        assertTrue(
            ids.containsAll(
                listOf(
                    "claude", "solarized", "vesper", "kanagawa", "everforest", "rose-pine",
                    "catppuccin", "tokyo-night", "nord", "gruvbox", "flexoki", "mono",
                ),
            ),
        )
        for (canvas in PiThemeStudio.allCanvases) {
            val counterpart = PiThemeStudio.counterpartCanvas(canvas)
            assertTrue("Counterpart of ${canvas.id} should invert dark mode", counterpart.dark != canvas.dark)
        }
    }

    @Test
    fun `defaultTools setting declares builtinToolDefaults baseline so quick-add preserves core tools`() {
        val setting = PiSettingsCatalog.byKey["defaultTools"]
        assertNotNull("defaultTools must be registered for settings search", setting)
        assertEquals(PiQuickAdd.builtinToolDefaults, setting!!.presetBaseline)

        val afterAdd = PiQuickAdd.add(emptyList(), "grep", setting.presetBaseline, setting.presets)
        assertEquals(listOf("read", "bash", "edit", "write", "grep"), afterAdd)

        val persistedDefault = PiQuickAdd.persist(PiQuickAdd.builtinToolDefaults, setting.presetBaseline)
        assertEquals(emptyList<String>(), persistedDefault)
    }
}
