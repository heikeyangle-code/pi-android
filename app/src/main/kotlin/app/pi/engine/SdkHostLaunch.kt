package app.pi.engine

import app.pi.runtime.ShellQuote

/**
 * 启动 sdk-host 的 guest 命令行（纯字符串，不碰 Android / 进程）。
 *
 * 这是 docs/sdk-host/runtimes.md「接入点」的那一处：proot 与 proroot **共用这一条命令**，
 * 差别只在外面包的 proot/proroot 前缀与 `NODE_OPTIONS`（scandir 兜底，`ProrootCommand` 注入），
 * 不在这里。
 *
 * 为什么 `--pi-dir` 必须给：宿主是 ESM，ESM 的包解析**忽略 NODE_PATH**，宿主目录
 * （`/opt/pi/sdk-host`）也不在 `node_modules` 之下，所以 `import "@earendil-works/pi-coding-agent"`
 * 找不到引擎。显式给引擎包目录，宿主用绝对路径加载（`sdk.mjs` 的 `loadSdk`）。
 */
object SdkHostLaunch {

    /** 宿主在 rootfs 里的位置。整棵树由 [SdkHostInstaller] 复制过去。 */
    const val GUEST_DIR = "/opt/pi/sdk-host"
    const val GUEST_ENTRY = "$GUEST_DIR/main.mjs"

    /** 引擎包在 rootfs 里的位置（`tools/fetch-runtime.mjs` 装的）。 */
    const val GUEST_PI_DIR = "/opt/pi/node_modules/@earendil-works/pi-coding-agent"

    const val NODE = "/opt/node/bin/node"

    /**
     * @param cwd 引擎的工作目录（guest 路径）。
     * @param agentDir pi 的家（guest 路径），`--session-dir` 缺省为其下 `sessions`。
     * @param extensions 额外加载的扩展入口（guest 路径），对应 `--extension`。
     */
    fun command(
        cwd: String,
        agentDir: String,
        sessionDir: String = "$agentDir/sessions",
        extensions: List<String> = emptyList(),
        piDir: String = GUEST_PI_DIR,
        entry: String = GUEST_ENTRY,
    ): String = buildString {
        append("exec ").append(NODE).append(' ').append(ShellQuote.quote(entry))
        append(" --cwd ").append(ShellQuote.quote(cwd))
        append(" --agent-dir ").append(ShellQuote.quote(agentDir))
        append(" --session-dir ").append(ShellQuote.quote(sessionDir))
        append(" --pi-dir ").append(ShellQuote.quote(piDir))
        for (ext in extensions) append(" --extension ").append(ShellQuote.quote(ext))
    }
}
