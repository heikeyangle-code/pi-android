/**
 * 认证：供应商、API Key、OAuth 登录，直接是 `ModelRuntime` 的方法。
 *
 * 以前：`pi-oauth.mjs`（另起 node 子进程，自己拼回调服务器、自己写 auth.json）。
 * 现在：`modelRuntime.login(providerId, type, interaction)`，官方负责所有协议细节
 * （回调服务器、设备码、刷新、落盘到 auth.json）。宿主只把 `interaction` 译成两种东西：
 *  - `interaction.prompt(p)`  → 向 App 提问（`req` method `auth.prompt`，等 `ui.respond`）；
 *  - `interaction.notify(e)`  → 事件 `auth.event`（打开链接、设备码、进度、信息）。
 *
 * 登录是长事务（要等人去浏览器授权），所以 `auth.login` 用「先挂起、完成才应答」：
 * App 自己给 `loginId`，之后可用 `auth.cancel{loginId}` 中止。一个供应商同时只允许一个登录。
 */

import { ERR, HostError, bad, str, optStr } from "../protocol.mjs";

/** 供应商的认证方式清单：App 的登录页据此画「填 Key」/「网页登录」。 */
function describeProvider(runtime, provider) {
  const methods = [];
  if (provider.auth?.apiKey) methods.push({ type: "api_key", name: provider.auth.apiKey.name });
  if (provider.auth?.oauth) {
    methods.push({
      type: "oauth",
      name: provider.auth.oauth.name,
      isSubscription: provider.auth.oauth.isSubscription === true,
      loginLabel: provider.auth.oauth.loginLabel,
    });
  }
  return {
    id: provider.id,
    name: provider.name,
    methods,
    configured: runtime.hasConfiguredAuth(provider.id),
    usingOAuth: runtime.isUsingOAuth(provider.id),
    status: runtime.getProviderAuthStatus(provider.id),
  };
}

export function register(reg, { controller, hub }) {
  const logins = new Map(); // loginId → { providerId, abort }

  reg.add("auth.providers", () => {
    const rt = controller.requireSession().modelRuntime;
    return { providers: rt.getProviders().map((p) => describeProvider(rt, p)) };
  });

  reg.add("auth.credentials", async () => ({ credentials: await controller.requireSession().modelRuntime.listCredentials() }));

  // 存 API Key（**持久化**，写进 auth.json）。
  // 不能用 `setRuntimeApiKey`：它只改本进程内存（测试里实测 auth.json 仍是 `{}`），重启就没了。
  // 官方 TUI 的 `/login`→API key 走的是 `login(providerId, "api_key", interaction)`：由供应商自己的
  // `apiKey.login` 提示输入并校验，再交给凭据库落盘。这里用同一条路，只是把「提示输入」直接答成参数里的 Key。
  reg.add("auth.setApiKey", async (args) => {
    const rt = controller.requireSession().modelRuntime;
    const providerId = str(args, "providerId");
    const apiKey = str(args, "apiKey");
    if (!apiKey.trim()) throw bad("apiKey 不能为空");
    const provider = rt.getProvider(providerId);
    if (!provider) throw new HostError(ERR.NOT_FOUND, `没有这个供应商：${providerId}`);
    if (!provider.auth?.apiKey) throw new HostError(ERR.UNSUPPORTED, `${provider.name} 不支持 API Key`);
    const interaction = {
      signal: new AbortController().signal,
      prompt: async () => apiKey.trim(),
      notify: () => {},
    };
    await rt.login(providerId, "api_key", interaction);
    hub.emit("auth.changed", { providerId, action: "setApiKey" });
    return { provider: describeProvider(rt, provider) };
  });

  reg.add("auth.removeApiKey", async (args) => {
    const rt = controller.requireSession().modelRuntime;
    const providerId = str(args, "providerId");
    await rt.logout(providerId); // 删凭据库里的条目（持久化）
    hub.emit("auth.changed", { providerId, action: "removeApiKey" });
    const p = rt.getProvider(providerId);
    return p ? { provider: describeProvider(rt, p) } : {};
  });

  reg.add("auth.login", async (args, call) => {
    const rt = controller.requireSession().modelRuntime;
    const providerId = str(args, "providerId");
    const type = optStr(args, "type") ?? "oauth";
    if (type !== "oauth" && type !== "api_key") throw bad("type 只能是 oauth 或 api_key");
    const provider = rt.getProvider(providerId);
    if (!provider) throw new HostError(ERR.NOT_FOUND, `没有这个供应商：${providerId}`);
    if (!provider.auth?.[type === "oauth" ? "oauth" : "apiKey"]) {
      throw new HostError(ERR.UNSUPPORTED, `${provider.name} 不支持 ${type} 登录`);
    }
    const loginId = optStr(args, "loginId") ?? `l${Math.random().toString(36).slice(2, 10)}`;
    if ([...logins.values()].some((l) => l.providerId === providerId)) {
      throw new HostError(ERR.BUSY, `${provider.name} 已经有一个登录在进行`);
    }

    const abort = new AbortController();
    logins.set(loginId, { providerId, abort });
    const done = call.defer();

    const interaction = {
      signal: abort.signal,
      prompt: (prompt) =>
        hub.ask("auth.prompt", { loginId, providerId, prompt: stripSignal(prompt) }, undefined, { signal: abort.signal }).then((value) => {
          if (value === undefined) throw new Error("已取消");
          return String(value);
        }),
      notify: (event) => hub.emit("auth.event", { loginId, providerId, event }),
    };

    // 后台跑；应答在登录结束时给。
    (async () => {
      try {
        // 与官方 TUI 一致（interactive-mode.js loginProvider）：OAuth 需要「本机安装」的设备 ID
        // （ChatGPT 订阅登录没有它直接报错），由 SettingsManager 生成并持久化。
        const settings = controller.runtime.services.settingsManager;
        const credential = await rt.login(providerId, type, interaction, { getDeviceId: () => settings.getOrCreateDeviceId() });
        hub.emit("auth.changed", { providerId, action: "login" });
        done.ok({ loginId, providerId, credentialType: credential?.type ?? type, provider: describeProvider(rt, provider) });
      } catch (error) {
        const cancelled = abort.signal.aborted;
        done.fail(new HostError(cancelled ? ERR.REJECTED : ERR.INTERNAL, cancelled ? "登录已取消" : String(error?.message ?? error)));
      } finally {
        logins.delete(loginId);
      }
    })();
  });

  reg.add("auth.cancel", (args) => {
    const login = logins.get(str(args, "loginId"));
    if (!login) return { cancelled: false };
    login.abort.abort();
    return { cancelled: true };
  });

  reg.add("auth.logout", async (args) => {
    const rt = controller.requireSession().modelRuntime;
    const providerId = str(args, "providerId");
    await rt.logout(providerId);
    hub.emit("auth.changed", { providerId, action: "logout" });
    const p = rt.getProvider(providerId);
    return p ? { provider: describeProvider(rt, p) } : {};
  });

  // 重新拉各供应商的模型目录（要网络）。
  reg.add("auth.refreshModels", async () => {
    const rt = controller.requireSession().modelRuntime;
    const result = await rt.refresh();
    hub.emit("auth.changed", { action: "refresh" });
    return { result: JSON.parse(JSON.stringify(result ?? null, (_k, v) => (v instanceof Error ? { message: v.message } : v))) };
  });
}

function stripSignal(prompt) {
  const { signal: _signal, ...rest } = prompt ?? {};
  return rest;
}
