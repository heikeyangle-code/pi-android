package app.pi.ui.settings

import kotlinx.serialization.json.JsonElement

/**
 * The read/write surface for pi's settings documents.
 *
 * pi keeps two JSON files (`~/.pi/agent/settings.json` for the global scope and
 * `.pi/settings.json` for the project scope) and merges the project document
 * recursively over the global one before a session reads a value. That merge,
 * the file IO and the credential store are the runtime's business; this
 * interface is deliberately only what the settings UI needs.
 *
 * A value is addressed by its dotted path exactly as it appears in JSON
 * (`compaction.reserveTokens`) and the whole value tree crosses the boundary as
 * a [JsonElement] rather than a string. That matters for two settings the spec
 * calls out: array-valued settings keep their glob syntax (`!pattern` exclude,
 * `+path` force-include, `-path` force-exclude) and are never normalised into a
 * plain path list, and `theme` round-trips the literal `"lightTheme/darkTheme"`
 * automatic form unchanged instead of being split into two fields.
 *
 * ## Why [remove] exists
 *
 * "Unset" and "set to null" are different things to pi, and only one of them is safe.
 * pi's own writers express "unset" by writing `undefined`, which `JSON.stringify` drops,
 * so the key disappears from that scope's file and the other scope shows through
 * (`core/settings-manager.ts:632-661`). A `null` in the file is *present* — and for
 * `httpIdleTimeoutMs`/`websocketConnectTimeoutMs`/`compaction.reserveTokens` its parser
 * throws on it (`:186-196`, `:854-880`), which is how the settings UI used to stop
 * `pi --mode rpc` from starting at all (`docs/settings-audit-impl.md` §B1). So every
 * "restore the default" / "clear" path in the UI goes through [remove]; none of them
 * may write `JsonNull`.
 *
 * This file has no Android or Compose import on purpose: the bare-JVM `settings-store`
 * harness compiles it together with `app/pi/settings/PiSettingsFileStore.kt`. The
 * in-memory implementations live in `PiSettingsInMemory.kt`, which does need Compose.
 */
interface PiSettingsStore {
    /** Returns the raw value at [key], or null when the key is not set. */
    fun read(key: String): JsonElement?

    /** Stores [value] at [key] verbatim. The store must not re-serialise it. */
    fun write(key: String, value: JsonElement)

    /**
     * Drops [key] so it is no longer explicitly set — pi's "use the built-in default"
     * (`undefined`), never a `null` in the file.
     *
     * The scope is part of the contract, and it is pi's rule: delete the key in the
     * document that explicitly carries it (a project override is removed from the
     * project document, which is what lets the global value show through again),
     * otherwise delete it from the global document.
     */
    fun remove(key: String)
}
