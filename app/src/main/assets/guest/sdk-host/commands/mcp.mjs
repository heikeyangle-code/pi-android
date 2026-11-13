/**
 * MCP 服务器管理：检测、增删、启停 / 暴露方式、OAuth 登录退出。
 *
 * 以前：在 guest 里起子进程跑 `pi mcp list --json`（每次点「检测」冷启动一个 node + 整个引擎）。
 * 现在：官方把 `pi mcp …` 做成了**可直接调用的函数**（`runMcpCommand`）与一组导出（`loadMcpConfig`、
 * `updateMcpServerConfig`、`McpServerConnection`、`signInMcpServer`），宿主在进程里直接用，
 * 校验、落盘格式、错误文案都是官方的 —— 宿主不重写任何 MCP 逻辑。
 *
 * 注意两点：
 *  - 这些函数在 `dist/extensions/mcp/` 下，**不是 SDK 的公开导出**（只有 `createMcpExtension` 是）。
 *    与 resources.mjs 同一个理由与同一个防线：contract 测试断言它们存在，pi 升级挪了位置 CI 立刻红。
 *  - 运行中的会话下一轮会自己读到新的凭据 / 配置；要让**新增的服务器**进当前会话，调 `session.reload`。
 */

import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { ERR, HostError, bad, bool, optStr, str } from "../protocol.mjs";

export function register(reg, { controller, hub }) {
  const dist = (rel) => import(pathToFileURL(join(controller.cfg.piDir, "dist", "extensions", "mcp", rel)).href);
  const ctx = () => {
    if (!controller.opened) throw new HostError(ERR.NOT_OPEN, "先 session.open");
    return { cwd: controller.cwd, agentDir: controller.cfg.agentDir };
  };

  /** 跑 `pi mcp <args>`，收集输出。 */
  async function runCli(args, extra = {}) {
    const { runMcpCommand } = await dist("cli.js");
    const out = [];
    const err = [];
    const code = await runMcpCommand(args, { ...ctx(), log: (l) => out.push(l), error: (l) => err.push(l), ...extra });
    return { code, out, err };
  }

  const fail = (err, fallback) => new HostError(ERR.REJECTED, err.join("\n").trim() || fallback);

  // 配置视图：只读配置，**不连接**任何服务器（秒回）。`mcp.list` 才会真的去连，只在用户点「检测」时用。
  reg.add("mcp.servers", async () => {
    const { loadMcpConfig } = await dist("config.js");
    const loaded = loadMcpConfig({ ...ctx(), projectTrusted: controller.isProjectTrusted() });
    return {
      servers: loaded.servers.map((e) => ({
        name: e.name,
        scope: e.scope ?? "global",
        source: e.source,
        override: e.override ?? null,
        enabled: e.config.enabled !== false,
        exposure: e.config.exposure ?? "codemode",
        url: e.config.url ?? null,
        command: e.config.command ?? null,
        args: Array.isArray(e.config.args) ? e.config.args : [],
        description: e.config.description ?? null,
      })),
      errors: loaded.errors ?? [],
    };
  });

  // 检测：会真的去连每个已启用的服务器，所以只在用户点「检测」时跑；超时由调用方给。
  reg.add("mcp.list", async () => {
    const { out, err } = await runCli(["list", "--json"]);
    const text = out.join("\n");
    try {
      return JSON.parse(text);
    } catch {
      throw new HostError(ERR.INTERNAL, err.join("\n").trim() || `无法解析 pi mcp list 的输出：${text.slice(0, 200)}`);
    }
  });

  // 加服务器：stdio（command + args）或 HTTP（url）。参数校验、写 mcp.json 都是官方的。
  reg.add("mcp.add", async (args) => {
    const name = str(args, "name");
    const url = optStr(args, "url");
    const command = optStr(args, "command");
    if (!url === !command) throw bad("url 与 command 必须二选一");
    const cli = ["add", name];
    if (bool(args, "local", false)) cli.push("--local");
    const pairs = (flag, obj) => Object.entries(obj ?? {}).forEach(([k, v]) => cli.push(flag, `${k}=${v}`));
    pairs("--env", args.env);
    pairs("--header", args.headers);
    for (const [flag, key] of [["--cwd", "cwd"], ["--exposure", "exposure"], ["--description", "description"], ["--bearer-token-env-var", "bearerTokenEnvVar"],
      ["--oauth-client-id", "oauthClientId"], ["--oauth-client-secret", "oauthClientSecret"], ["--oauth-callback-port", "oauthCallbackPort"],
      ["--oauth-client-name", "oauthClientName"], ["--oauth-client-registration", "oauthClientRegistration"]]) {
      const v = optStr(args, key) ?? (args[key] !== undefined ? String(args[key]) : undefined);
      if (v !== undefined) cli.push(flag, v);
    }
    if (url) cli.push("--url", url);
    else cli.push("--", command, ...(Array.isArray(args.args) ? args.args.map(String) : []));
    const { code, out, err } = await runCli(cli);
    if (code !== 0) throw fail(err, "添加失败");
    return { message: out.join("\n") };
  });

  reg.add("mcp.remove", async (args) => {
    const cli = ["remove", str(args, "name")];
    if (bool(args, "local", false)) cli.push("--local");
    const { code, out, err } = await runCli(cli);
    if (code !== 0) throw fail(err, "删除失败");
    return { message: out.join("\n") };
  });

  // 启停 / 暴露方式：直接改配置文件里那一条（官方 `updateMcpServerConfig`）。
  reg.add("mcp.update", async (args) => {
    const name = str(args, "name");
    const { loadMcpConfig, updateMcpServerConfig } = await dist("config.js");
    const loaded = loadMcpConfig({ ...ctx(), projectTrusted: controller.isProjectTrusted() });
    const entry = loaded.servers.find((s) => s.name === name);
    if (!entry) throw new HostError(ERR.NOT_FOUND, `没有这个 MCP 服务器：${name}`);
    if (entry.scope === "extension") throw new HostError(ERR.REJECTED, "这个服务器由扩展注册，不能在这里改。");
    const patch = {};
    if (args.enabled !== undefined) patch.enabled = bool(args, "enabled");
    if (args.exposure !== undefined) {
      if (!["codemode", "deferred", "direct", "hidden"].includes(args.exposure)) throw bad("exposure 只能是 codemode / deferred / direct / hidden");
      patch.exposure = args.exposure;
    }
    if (Object.keys(patch).length === 0) throw bad("没有要改的字段（enabled / exposure）");
    // 在项目里改一个「定义在全局」的服务器 = 项目层覆盖，不动全局文件。
    const override = bool(args, "local", false) && entry.scope !== "project";
    const path = override ? join(controller.cwd, ".pi", "mcp.json") : entry.source;
    updateMcpServerConfig(path, name, patch, { override });
    return { name, patch, path };
  });

  // OAuth 登录：长事务，先挂起、完成才应答。授权链接走 `mcp.auth` 事件；浏览器到不了本机回调时，
  // 宿主向 App 提问（`mcp.redirect`）让用户贴回地址栏里的重定向 URL —— 官方 CLI 非交互时没有这条
  // 兜底，手机上（浏览器和引擎可能不在同一个网络命名空间）正需要它。
  const logins = new Map();
  reg.add("mcp.login", async (args, call) => {
    const name = str(args, "name");
    const loginId = optStr(args, "loginId") ?? `m${Math.random().toString(36).slice(2, 8)}`;
    const timeoutMs = (Number(args.timeoutSeconds) > 0 ? Number(args.timeoutSeconds) : 300) * 1000;
    const { loadMcpConfig } = await dist("config.js");
    const rt = await dist("runtime.js");
    const loaded = loadMcpConfig({ ...ctx(), projectTrusted: controller.isProjectTrusted() });
    const entry = loaded.servers.find((s) => s.name === name);
    if (!entry) throw new HostError(ERR.NOT_FOUND, `没有这个 MCP 服务器：${name}`);
    if (logins.has(name)) throw new HostError(ERR.BUSY, `${name} 已经有一个登录在进行`);

    const credentials = new rt.McpOAuthCredentialStore();
    const connection = new rt.McpServerConnection({
      entry,
      cwd: ctx().cwd,
      createTransport: rt.createDefaultTransport,
      credentials,
      log: new rt.McpServerLog(join(ctx().agentDir, "mcp.log")),
      onTools: () => {},
    });
    const url = connection.oauthUrl;
    if (!url) {
      await connection.close();
      throw new HostError(ERR.UNSUPPORTED, `${name} 不使用 OAuth（只有不带 Authorization 头的 HTTP 服务器才用）。`);
    }
    const abort = new AbortController();
    logins.set(name, { loginId, abort });
    const done = call.defer();
    (async () => {
      try {
        try {
          await connection.getClient();
          return done.ok({ loginId, name, signedIn: true, alreadySignedIn: true, tools: connection.tools.map((t) => t.name) });
        } catch {
          if (connection.state !== "needs-auth") throw new Error(`连接失败：${connection.error ?? "未知错误"}`);
        }
        await rt.signInMcpServer({
          serverUrl: url,
          store: credentials.forServer(name, url),
          settings: connection.oauthSettings(),
          challenge: connection.challenge,
          prompt: {
            showAuthorizationUrl: (u) => hub.emit("mcp.auth", { loginId, name, url: u.href }),
            promptForRedirectUrl: (signal) =>
              hub.ask("mcp.redirect", { loginId, name }, undefined, { signal: AbortSignal.any([signal, abort.signal]) }).then((v) => (typeof v === "string" ? v : undefined)),
          },
          signal: AbortSignal.any([abort.signal, AbortSignal.timeout(timeoutMs)]),
        });
        connection.challenge = undefined;
        await connection.reconnect();
        hub.emit("mcp.changed", { name, action: "login" });
        done.ok({ loginId, name, signedIn: true, tools: connection.tools.map((t) => t.name) });
      } catch (error) {
        const cancelled = error instanceof rt.McpSignInCancelledError || abort.signal.aborted;
        done.fail(new HostError(cancelled ? ERR.REJECTED : ERR.INTERNAL, cancelled ? "登录已取消或超时" : String(error?.message ?? error)));
      } finally {
        logins.delete(name);
        await connection.close().catch(() => {});
      }
    })();
  });

  reg.add("mcp.cancel", (args) => {
    for (const login of logins.values()) {
      if (login.loginId === str(args, "loginId")) {
        login.abort.abort();
        return { cancelled: true };
      }
    }
    return { cancelled: false };
  });

  reg.add("mcp.logout", async (args) => {
    const { code, out, err } = await runCli(["logout", str(args, "name"), ...(bool(args, "local", false) ? ["--local"] : [])]);
    if (code !== 0) throw fail(err, "退出失败");
    hub.emit("mcp.changed", { name: str(args, "name"), action: "logout" });
    return { message: out.join("\n") };
  });
}
