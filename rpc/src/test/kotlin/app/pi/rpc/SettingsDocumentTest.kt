package app.pi.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings editing is the code most able to destroy a user's configuration
 * silently, so it is pinned against pi's own semantics: recursive object merge,
 * wholesale array replacement (with pi's one `defaultTools` exception),
 * sparse documents, and byte-preservation of keys the app does not know about.
 */
class SettingsDocumentTest {

    private fun doc(json: String): JsonObject = PiJson.parseObjectOrNull(json)!!

    private fun arr(vararg items: String): JsonArray = JsonArray(items.map { JsonPrimitive(it) })

    @Test
    fun `lookup walks dotted paths`() {
        val d = doc("""{"compaction":{"reserveTokens":16384,"enabled":true}}""")
        assertEquals(16384, SettingsDocument.lookup(d, "compaction.reserveTokens")!!.jsonPrimitive.content.toInt())
        assertEquals(true, SettingsDocument.lookup(d, "compaction.enabled")!!.jsonPrimitive.boolean)
    }

    @Test
    fun `lookup returns null for sparse documents rather than a default`() {
        val d = doc("""{"theme":"dark"}""")
        assertNull(SettingsDocument.lookup(d, "compaction.reserveTokens"))
        assertNull(SettingsDocument.lookup(d, "nothing.at.all"))
        // An intermediate node that is not an object must not throw.
        assertNull(SettingsDocument.lookup(d, "theme.whatever"))
    }

    @Test
    fun `setPath creates missing parents and preserves siblings`() {
        val d = doc("""{"theme":"dark","packages":["npm:a"],"unknownKey":{"keep":1}}""")
        val next = SettingsDocument.setPath(d, "compaction.reserveTokens", JsonPrimitive(32768))
        assertEquals(32768, SettingsDocument.lookup(next, "compaction.reserveTokens")!!.jsonPrimitive.content.toInt())
        // Everything the app does not own survives.
        assertEquals("dark", SettingsDocument.lookup(next, "theme")!!.jsonPrimitive.content)
        assertEquals(1, SettingsDocument.lookup(next, "unknownKey.keep")!!.jsonPrimitive.content.toInt())
        assertTrue(next.containsKey("packages"))
    }

    @Test
    fun `setPath replaces a scalar with an object and vice versa`() {
        val d = doc("""{"theme":"dark"}""")
        val asObject = SettingsDocument.setPath(d, "theme", doc("""{"light":"light","dark":"dark"}"""))
        assertTrue(SettingsDocument.lookup(asObject, "theme") is JsonObject)
        val back = SettingsDocument.setPath(asObject, "theme", JsonPrimitive("light"))
        assertEquals("light", SettingsDocument.lookup(back, "theme")!!.jsonPrimitive.content)
    }

    @Test
    fun `setPath does not mutate the input document`() {
        val d = doc("""{"theme":"dark"}""")
        SettingsDocument.setPath(d, "compaction.enabled", JsonPrimitive(true))
        assertFalse("setPath must be pure", d.containsKey("compaction"))
    }

    @Test
    fun `removePath prunes empty parents`() {
        val d = doc("""{"compaction":{"reserveTokens":16384,"enabled":true}}""")
        val a = SettingsDocument.removePath(d, "compaction.reserveTokens")
        assertFalse(a["compaction"]!!.jsonObject.containsKey("reserveTokens"))
        assertTrue(a["compaction"]!!.jsonObject.containsKey("enabled"))

        val b = SettingsDocument.removePath(a, "compaction.enabled")
        // The now-empty parent goes away entirely.
        assertFalse(b.containsKey("compaction"))
    }

    @Test
    fun `merge lets the override win for scalars`() {
        val global = doc("""{"theme":"dark","httpProxy":"http://a"}""")
        val project = doc("""{"theme":"light"}""")
        val merged = SettingsDocument.merge(global, project)
        assertEquals("light", SettingsDocument.lookup(merged, "theme")!!.jsonPrimitive.content)
        assertEquals("http://a", SettingsDocument.lookup(merged, "httpProxy")!!.jsonPrimitive.content)
    }

    @Test
    fun `merge recurses into objects, matching pi's deepMergeObjects`() {
        val global = doc("""{"compaction":{"enabled":true,"reserveTokens":16384,"keepRecentTokens":20000}}""")
        val project = doc("""{"compaction":{"reserveTokens":4096}}""")
        val merged = SettingsDocument.merge(global, project)
        assertEquals(4096, SettingsDocument.lookup(merged, "compaction.reserveTokens")!!.jsonPrimitive.content.toInt())
        // Untouched siblings survive the recursion.
        assertEquals(20000, SettingsDocument.lookup(merged, "compaction.keepRecentTokens")!!.jsonPrimitive.content.toInt())
        assertEquals(true, SettingsDocument.lookup(merged, "compaction.enabled")!!.jsonPrimitive.boolean)
    }

    @Test
    fun `merge replaces arrays wholesale, as pi's isMergeableObject requires`() {
        // pi excludes arrays from merging, so a project list replaces the global
        // one instead of appending. Getting this wrong silently doubles a user's
        // tool list. (`defaultTools` is the one exception; see the tests below.)
        val global = doc("""{"defaultTools":["read","bash","edit","write"]}""")
        val project = doc("""{"defaultTools":["read","grep"]}""")
        val merged = SettingsDocument.merge(global, project)
        val tools = SettingsDocument.lookup(merged, "defaultTools")!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("read", "grep"), tools)
    }

    @Test
    fun `merge appends a project defaultTools list made only of modifiers`() {
        // pi's `mergeDefaultTools` (settings-manager.ts:223-228, applied at :249-251):
        // `+name`/`-name` modify the *inherited* selection instead of replacing it,
        // so these two layers resolve to ["grep","codemode"] in pi. Treating the
        // project list as a replacement made the app's "effective value" show
        // ["+codemode"] — the opposite of what pi ran. The modifiers are kept here
        // (this mirrors pi's stored merge); `PiQuickAdd.effective` is what expands
        // them for display.
        val global = doc("""{"defaultTools":["grep"]}""")
        val project = doc("""{"defaultTools":["+codemode"]}""")
        val merged = SettingsDocument.merge(global, project)
        val tools = SettingsDocument.lookup(merged, "defaultTools")!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("grep", "+codemode"), tools)
    }

    @Test
    fun `merge replaces defaultTools when the project list mixes plain names and modifiers`() {
        // Only an *all-modifier* list appends (`overrides.every(isToolModifier)`),
        // and this is the mixed shape that must never be produced by the app:
        // pi reads the plain name as the whole whitelist (settings-manager.ts:236),
        // so `["+codemode","grep"]` disables read/bash/edit/write.
        val global = doc("""{"defaultTools":["read","bash","edit","write"]}""")
        val project = doc("""{"defaultTools":["+codemode","grep"]}""")
        val merged = SettingsDocument.merge(global, project)
        val tools = SettingsDocument.lookup(merged, "defaultTools")!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("+codemode", "grep"), tools)
    }

    @Test
    fun `merge leaves defaultTools alone when the project layer does not set it`() {
        val global = doc("""{"defaultTools":["grep"]}""")
        val project = doc("""{"theme":"light"}""")
        val merged = SettingsDocument.merge(global, project)
        val tools = SettingsDocument.lookup(merged, "defaultTools")!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("grep"), tools)
    }

    @Test
    fun `mergeDefaultTools pins every shape pi's rule distinguishes`() {
        // Absent override = absent result (`overrides === undefined` → base).
        assertNull(SettingsDocument.mergeDefaultTools(null, null))
        assertEquals(arr("read"), SettingsDocument.mergeDefaultTools(arr("read"), null))
        // Not an array on either side: the override replaces.
        assertEquals(arr("+codemode"), SettingsDocument.mergeDefaultTools(null, arr("+codemode")))
        assertEquals(JsonPrimitive("grep"), SettingsDocument.mergeDefaultTools(arr("read"), JsonPrimitive("grep")))
        // Only `+`/`-` strings count as modifiers; a number makes the list replace.
        assertEquals(
            JsonArray(listOf(JsonPrimitive(1))),
            SettingsDocument.mergeDefaultTools(arr("read"), JsonArray(listOf(JsonPrimitive(1)))),
        )
        // All modifiers append, in order. An empty override is vacuously
        // "all modifiers" in pi (`[].every(...)`), so it keeps the base.
        assertEquals(
            arr("read", "+codemode", "-bash"),
            SettingsDocument.mergeDefaultTools(arr("read"), arr("+codemode", "-bash")),
        )
        assertEquals(arr("read"), SettingsDocument.mergeDefaultTools(arr("read"), arr()))
    }

    @Test
    fun `merge applies the defaultTools rule at the top level only, as pi does`() {
        // pi runs the generic deep merge first and patches top-level `defaultTools`
        // afterwards (settings-manager.ts:249-251), so a nested key that happens to
        // be called `defaultTools` still gets the generic array replacement.
        val global = doc("""{"nested":{"defaultTools":["read"]}}""")
        val project = doc("""{"nested":{"defaultTools":["+codemode"]}}""")
        val merged = SettingsDocument.merge(global, project)
        val tools = SettingsDocument.lookup(merged, "nested.defaultTools")!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("+codemode"), tools)
    }

    @Test
    fun `merge keeps unknown keys from both sides`() {
        val global = doc("""{"knownGlobal":1,"extensionKeyGlobal":true}""")
        val project = doc("""{"knownProject":2,"extensionKeyProject":true}""")
        val merged = SettingsDocument.merge(global, project)
        assertEquals(4, merged.size)
    }

    @Test
    fun `the automatic theme form round-trips as one literal string`() {
        // pi persists "lightTheme/darkTheme" in a single field; splitting it into
        // two would make the file unreadable by pi.
        val d = doc("""{"theme":"light/dark"}""")
        val next = SettingsDocument.setPath(d, "theme", JsonPrimitive("light/dark"))
        assertEquals("light/dark", SettingsDocument.lookup(next, "theme")!!.jsonPrimitive.content)
    }

    @Test
    fun `a realistic settings document survives a single-key edit byte-for-byte apart from that key`() {
        val d = doc(
            """{"theme":"dark","defaultTools":["read","bash"],"compaction":{"enabled":true},
                "app":{"density":"comfortable"},"someFutureKey":{"nested":[1,2,3]}}""".trimIndent().replace("\n", ""),
        )
        val next = SettingsDocument.setPath(d, "compaction.reserveTokens", JsonPrimitive(16384))
        assertEquals(d.size + 0, next.size)
        assertTrue(next.containsKey("someFutureKey"))
        assertEquals(
            SettingsDocument.lookup(d, "someFutureKey.nested")!!.jsonArray.size,
            SettingsDocument.lookup(next, "someFutureKey.nested")!!.jsonArray.size,
        )
    }
}
