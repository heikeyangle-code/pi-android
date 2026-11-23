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

import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { pathToFileURL } from "node:url";
import { ERR, HostError, bad, bool, optStr, str } from "../protocol.mjs";

function isRecord(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function serverToWire(e) {
  return {
    name: e.name,
    scope: e.scope ?? "global",
    source: e.source,
    override: e.override ?? null,
    enabled: e.config.enabled !== false,
    exposure: e.config.exposure ?? "codemode",
    url: e.config.url ?? null,
    command: e.config.command ?? null,
    args: Array.isArray(e.config.args) ? e.config.args : [],
    env: e.config.env ?? null,
    cwd: e.config.cwd ?? null,
    headers: e.config.headers ?? null,
    timeout: e.config.timeout ?? null,
    toolExposure: e.config.toolExposure ?? null,
    oauth: e.config.oauth ?? null,
    auth: e.config.auth ?? null,
    type: e.config.type ?? null,
    description: e.config.description ?? null,
  };
}

function convertForeignMcpEntry(entry) {
  const type = typeof entry.type === "string" ? entry.type : null;
  const out = { ...entry };
  if (type === "local") {
    if (!Array.isArray(entry.command) || entry.command.some((p) => typeof p !== "string") || entry.command.length === 0) return null;
    delete out.type;
    out.command = entry.command[0];
    if (entry.command.length > 1) out.args = entry.command.slice(1);
    if (isRecord(out.environment)) {
      const env = {};
      for (const [k, v] of Object.entries(out.environment)) {
        env[k] = String(v ?? "").replace(/\{env:([A-Za-z0-9_]+)\}/g, "${$1}");
      }
      out.env = env;
      delete out.environment;
    }
  } else if (type === "remote") {
    delete out.type;
  }
  if (out.command === undefined && out.url === undefined) return null;
  return out;
}

export function register(reg, { controller, hub }) {
  const dist = (rel) => import(pathToFileURL(join(controller.cfg.piDir, "dist", "extensions", "mcp", rel)).href);
  const coreMcp = () => import(pathToFileURL(join(controller.cfg.piDir, "dist", "core", "mcp-servers.js")).href);
  const ctx = () => {
    if (!controller.opened) throw new HostError(ERR.NOT_OPEN, "先 session.open");
    return { cwd: controller.cwd, agentDir: controller.cfg.agentDir };
  };
  const filePath = (local) => (local ? join(controller.cwd, ".pi", "mcp.json") : join(controller.cfg.agentDir, "mcp.json"));

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
    const trusted = controller.isProjectTrusted();
    const loaded = loadMcpConfig({ ...ctx(), projectTrusted: trusted });
    const globalOnly = loadMcpConfig({ ...ctx(), projectTrusted: false });
    return {
      servers: loaded.servers.map(serverToWire),
      autoEnableCodemode: loaded.autoEnableCodemode !== false,
      global: {
        servers: globalOnly.servers.map(serverToWire),
        autoEnableCodemode: globalOnly.autoEnableCodemode !== false,
        errors: globalOnly.errors ?? [],
      },
      projectTrusted: trusted,
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
    const local = bool(args, "local", false);
    const cli = ["add", name];
    if (local) cli.push("--local");
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
    hub.emit("mcp.changed", { name, action: "add", local });
    return { message: out.join("\n") };
  });

  // 保存一条服务器（新增 / 改名 / 编辑）：完整配置校验和落盘全交给官方 SDK helpers。
  reg.add("mcp.save", async (args) => {
    const name = str(args, "name").trim();
    const oldName = optStr(args, "oldName")?.trim();
    const local = bool(args, "local", false);
    if (!isRecord(args.config)) throw bad("config 必须是对象");
    const { loadMcpConfig, addMcpServerConfig, removeMcpServerConfig } = await dist("config.js");
    const { validateMcpServerConfig } = await coreMcp();
    const validated = validateMcpServerConfig(name, args.config);
    if (typeof validated === "string") throw new HostError(ERR.REJECTED, validated);

    const existing = loadMcpConfig({ ...ctx(), projectTrusted: local });
    const canonical = (s) => s.replace(/-/g, "_");
    const targetScope = local ? "project" : "global";
    const others = existing.servers.filter((s) => s.scope === targetScope && s.name !== (oldName ?? name));
    const clash = others.find((s) => canonical(s.name) === canonical(name));
    if (clash) throw new HostError(ERR.REJECTED, `服务器 "${name}" 与已有的 "${clash.name}" 冲突`);

    const targetPath = filePath(local);
    try {
      addMcpServerConfig(targetPath, name, validated);
      if (oldName && oldName !== name) removeMcpServerConfig(targetPath, oldName);
    } catch (error) {
      throw new HostError(ERR.REJECTED, String(error?.message ?? error));
    }
    hub.emit("mcp.changed", { name, action: "save", local });
    return { name, path: targetPath, config: validated };
  });

  reg.add("mcp.remove", async (args) => {
    const name = str(args, "name");
    const local = bool(args, "local", false);
    const cli = ["remove", name];
    if (local) cli.push("--local");
    const { code, out, err } = await runCli(cli);
    if (code !== 0) throw fail(err, "删除失败");
    hub.emit("mcp.changed", { name, action: "remove", local });
    return { message: out.join("\n") };
  });

  // 启停 / 暴露方式：直接改配置文件里那一条（官方 `updateMcpServerConfig`）。
  reg.add("mcp.update", async (args) => {
    const name = str(args, "name");
    const local = bool(args, "local", false);
    const { loadMcpConfig, updateMcpServerConfig } = await dist("config.js");
    const loaded = loadMcpConfig({ ...ctx(), projectTrusted: local ? controller.isProjectTrusted() : false });
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
    const override = local && entry.scope !== "project";
    const path = override ? join(controller.cwd, ".pi", "mcp.json") : entry.source;
    updateMcpServerConfig(path, name, patch, { override });
    hub.emit("mcp.changed", { name, action: "update", local });
    return { name, patch, path };
  });

  reg.add("mcp.setAutoEnableCodemode", async (args) => {
    ctx();
    const enabled = bool(args, "enabled");
    const local = bool(args, "local", false);
    const path = filePath(local);
    const raw = existsSync(path) ? readFileSync(path, "utf8").replace(/^\uFEFF/, "") : undefined;
    let parsed;
    try {
      parsed = raw === undefined ? {} : JSON.parse(raw);
    } catch (error) {
      throw new HostError(ERR.REJECTED, `${path}: ${error?.message ?? error}`);
    }
    if (!isRecord(parsed)) throw new HostError(ERR.REJECTED, `${path}: 根对象必须是 JSON 对象`);
    if (enabled) delete parsed.autoEnableCodemode;
    else parsed.autoEnableCodemode = false;
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, `${JSON.stringify(parsed, null, 2)}\n`);
    hub.emit("mcp.changed", { action: "autoEnableCodemode", local, enabled });
    return { enabled, path };
  });

  reg.add("mcp.import", async (args) => {
    const text = str(args, "text").trim();
    const local = bool(args, "local", false);
    const fallbackName = optStr(args, "fallbackName") ?? "imported";
    let parsed;
    try {
      parsed = JSON.parse(text);
    } catch {
      throw new HostError(ERR.REJECTED, "粘贴的内容不是合法 JSON。");
    }
    if (!isRecord(parsed)) throw new HostError(ERR.REJECTED, "粘贴的内容不是 JSON 对象。");
    const table = isRecord(parsed.mcpServers) ? parsed.mcpServers : isRecord(parsed.servers) ? parsed.servers : null;
    const entries = table
      ? Object.entries(table)
      : parsed.command !== undefined || parsed.url !== undefined || parsed.type !== undefined
        ? [[fallbackName, parsed]]
        : null;
    if (!entries) throw new HostError(ERR.REJECTED, "没找到 mcpServers（或 servers）。");

    const { loadMcpConfig, addMcpServerConfig } = await dist("config.js");
    const { validateMcpServerConfig } = await coreMcp();
    const targetPath = filePath(local);
    const existing = loadMcpConfig({ ...ctx(), projectTrusted: local });
    const targetScope = local ? "project" : "global";
    const taken = new Set(existing.servers.filter((s) => s.scope === targetScope).map((s) => s.name.replace(/-/g, "_")));
    const added = [];
    const skipped = [];

    for (const [rawName, value] of entries) {
      const name = rawName.replace(/[^A-Za-z0-9_-]/g, "_");
      if (!isRecord(value)) {
        skipped.push(`${rawName}（不是对象）`);
        continue;
      }
      if (JSON.stringify(value).includes("${input:")) {
        skipped.push(`${rawName}（使用了 \${input:…} 提示变量，请改成 \${环境变量名} 再导入）`);
        continue;
      }
      const converted = convertForeignMcpEntry(value);
      if (!converted) {
        skipped.push(`${rawName}（无法识别：需要 command 或 url）`);
        continue;
      }
      if (value.type === "sse" || (typeof converted.url === "string" && converted.url.trim().replace(/\/+$/, "").endsWith("/sse"))) {
        skipped.push(`${rawName}（pi 不支持 SSE；请换成以 /mcp 结尾的 HTTP 地址）`);
        continue;
      }
      const canon = name.replace(/-/g, "_");
      if (taken.has(canon)) {
        skipped.push(`${rawName}（已存在同名服务器）`);
        continue;
      }
      const validated = validateMcpServerConfig(name, converted);
      if (typeof validated === "string") {
        skipped.push(`${rawName}（${validated}）`);
        continue;
      }
      addMcpServerConfig(targetPath, name, validated);
      taken.add(canon);
      added.push(name);
    }

    if (added.length > 0) hub.emit("mcp.changed", { action: "import", local, added });
    return { added, skipped, path: targetPath };
  });

  // OAuth 登录：长事务，先挂起、完成才应答。授权链接走 `mcp.auth` 事件；浏览器到不了本机回调时，
  // 宿主向 App 提问（`mcp.redirect`）让用户贴回地址栏里的重定向 URL —— 官方 CLI 非交互时没有这条
  // 兜底，手机上（浏览器和引擎可能不在同一个网络命名空间）正需要它。
  const logins = new Map();
  reg.add("mcp.login", async (args, call) => {
    await controller.ensureHttpSettings?.();
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
    const name = str(args, "name");
    const local = bool(args, "local", false);
    const { code, out, err } = await runCli(["logout", name, ...(local ? ["--local"] : [])]);
    if (code !== 0) throw fail(err, "退出失败");
    hub.emit("mcp.changed", { name, action: "logout", local });
    return { message: out.join("\n") };
  });
}
