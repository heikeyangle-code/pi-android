package app.pi.engine

import app.pi.runtime.ShellQuote

/**
 * 启动 sdk-host 的 guest 命令行（纯参数数组与字符串形式，不碰 Android / 进程）。
 *
 * 三大运行时（proot / proroot / tawcroot）优先通过 [argv] 直接 `execve("/opt/node/bin/node", ...)`，
 * 彻底绕开外层 `/bin/bash -c` 进程启动开销；工作目录切换由宿主入口 `process.chdir(cfg.cwd)` 完成。
 *
 * 为什么 `--pi-dir` 必须给：宿主是 ESM，ESM 的包解析**忽略 NODE_PATH**，宿主目录
 * （`/opt/pi/sdk-host`）也不在 `node_modules` 之下，所以显式给官方引擎包目录，
 * 宿主直接用绝对路径加载官方 SDK（`sdk.mjs` 的 `loadSdk`）。
 */
object SdkHostLaunch {
    /** 宿主在 rootfs 里的位置。 */
    const val GUEST_DIR = "/opt/pi/sdk-host"
    const val BUNDLE_FILE = "host.bundle.mjs"
    const val GUEST_ENTRY = "$GUEST_DIR/$BUNDLE_FILE"

    /** 引擎包在 rootfs 里的位置（`tools/fetch-runtime.mjs` 装的）。 */
    const val GUEST_PI_DIR = "/opt/pi/node_modules/@earendil-works/pi-coding-agent"
    const val NODE = "/opt/node/bin/node"

    /**
     * 直接交给容器运行时 `execve` 的 argv（无 `/bin/bash -c` 中转层）。
     *
     * @param cwd 引擎的工作目录（guest 路径）。
     * @param agentDir pi 的家（guest 路径），`--session-dir` 缺省为其下 `sessions`。
     * @param extensions 额外加载的扩展入口（guest 路径），对应 `--extension`。
     * @param androidExtensions 是否通过官方 SDK `extensionFactories` 原生注入预编译的 Android 内置扩展。
     */
    fun argv(
        cwd: String,
        agentDir: String,
        sessionDir: String = "$agentDir/sessions",
        extensions: List<String> = emptyList(),
        piDir: String = GUEST_PI_DIR,
        entry: String = GUEST_ENTRY,
        androidExtensions: Boolean = true,
    ): List<String> = buildList {
        add(NODE)
        add(entry)
        add("--cwd"); add(cwd)
        add("--agent-dir"); add(agentDir)
        add("--session-dir"); add(sessionDir)
        add("--pi-dir"); add(piDir)
        if (androidExtensions) add("--android-extensions")
        for (ext in extensions) {
            add("--extension")
            add(ext)
        }
    }

    /** Shell 字符串形式（保留给需要 shell 封装的调用点或诊断展示）。 */
    fun command(
        cwd: String,
        agentDir: String,
        sessionDir: String = "$agentDir/sessions",
        extensions: List<String> = emptyList(),
        piDir: String = GUEST_PI_DIR,
        entry: String = GUEST_ENTRY,
        androidExtensions: Boolean = true,
    ): String = "exec " + argv(
        cwd = cwd,
        agentDir = agentDir,
        sessionDir = sessionDir,
        extensions = extensions,
        piDir = piDir,
        entry = entry,
        androidExtensions = androidExtensions,
    ).joinToString(" ") { arg -> if (arg == NODE) arg else ShellQuote.quote(arg) }
}
