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
        GuestEngine.Tawcroot -> TawcrootCommand.build(paths, guestCommand, cwd, storage, extraBinds)
    }

    /** Direct exec without `/bin/bash -c` for static argv targets like sdk-host. */
    fun buildDirect(
        paths: PiPaths,
        engine: GuestEngine,
        guestArgv: List<String>,
        cwd: String,
        storage: File?,
        extraBinds: List<Pair<String, String>> = emptyList(),
    ): List<String> = when (engine) {
        GuestEngine.Proot -> ProotCommand.buildDirect(paths, guestArgv, cwd, storage, extraBinds)
        GuestEngine.Proroot -> ProrootCommand.buildDirect(paths, guestArgv, cwd, storage, extraBinds)
        GuestEngine.Tawcroot -> TawcrootCommand.buildDirect(paths, guestArgv, cwd, storage, extraBinds)
    }

    /**
     * 宿主进程的 cwd。tawcroot 必须在 rootfs 里：它的 `getcwd` 是把内核 cwd 反向翻译回
     * guest 路径，cwd 在 rootfs 外时 guest 的每条相对路径都从 ENOENT 开始（上游 TAWC 也把
     * 它的 cwd 放在 `<rootfs>/tmp`）。proot / proroot 保持 `runtime`，不改既有行为。
     */
    fun hostCwd(paths: PiPaths, engine: GuestEngine): File =
        if (engine == GuestEngine.Tawcroot) TawcrootCommand.hostCwd(paths) else paths.runtime

    fun environment(
        paths: PiPaths,
        engine: GuestEngine,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> = when (engine) {
        GuestEngine.Proot -> ProotCommand.environment(paths, extra)
        GuestEngine.Proroot -> ProrootCommand.environment(paths, extra)
        GuestEngine.Tawcroot -> TawcrootCommand.environment(paths, extra)
    }
}
