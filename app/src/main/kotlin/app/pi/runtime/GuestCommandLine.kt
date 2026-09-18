package app.pi.runtime

import java.io.File

/**
 * One place that answers "argv and env for this engine".
 *
 * Both builders return the same pair of shapes, so every launch path can be written
 * once and parameterised by [GuestEngine] instead of choosing a builder itself.
 * That matters more than it looks: there are five guest entry points (the engine,
 * the two PTY probes, the package commands and the self-check), and the whole
 * reason `PiRuntime` is the single construction point for argv/env is that a
 * divergent recipe is invisible until something behaves differently in the guest.
 *
 * Note what this object does **not** do: it does not decide *which* engine to use.
 * That decision is [RuntimeChoice] fed by [RuntimeSelection], and the install and
 * maintenance paths deliberately bypass both by asking for [GuestEngine.Proot]
 * explicitly (`RuntimeSelfCheck`, `GuestToolProbe` when called by the provisioner).
 *
 * Android-free — `java.io.File` only — so the `proroot` harness compiles it and
 * pins that the two engines produce the same shared tail and different flags.
 */
object GuestCommandLine {

    fun build(
        paths: PiPaths,
        engine: GuestEngine,
        guestCommand: String,
        cwd: String,
        storage: File?,
        extraBinds: List<Pair<String, String>> = emptyList(),
    ): List<String> = when (engine) {
        GuestEngine.Proot -> ProotCommand.build(paths, guestCommand, cwd, storage, extraBinds)
        GuestEngine.Proroot -> ProrootCommand.build(paths, guestCommand, cwd, storage, extraBinds)
    }

    fun environment(
        paths: PiPaths,
        engine: GuestEngine,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> = when (engine) {
        GuestEngine.Proot -> ProotCommand.environment(paths, extra)
        GuestEngine.Proroot -> ProrootCommand.environment(paths, extra)
    }
}
