package app.pi.rpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcLocaleTest {

    @Test
    fun `english locale translates date labels, extension errors, and turn aborts`() {
        val prev = RpcLocale.isChinese
        try {
            RpcLocale.isChinese = false
            val now = 1_700_000_000_000L
            assertEquals("Today", dayLabel(now, now))
            assertEquals("Yesterday", dayLabel(now - 24L * 60 * 60 * 1000, now))
            assertEquals("Extension error: demo.ts", extensionErrorHeadline("/a/b/demo.ts"))
            assertEquals("Extension error", extensionErrorHeadline(null))

            val parsed = ExtensionFlagArgs.parse("-x")
            assertTrue(parsed.refusal?.startsWith("Extension flags ignored:") == true)
        } finally {
            RpcLocale.isChinese = prev
        }
    }
}
