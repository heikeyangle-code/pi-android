/**
 * Controller：宿主的核心，持有「运行时 → 当前会话」这条链，并把它接到 Hub 上。
 *
 * 一个进程 = 一个工作区（与 App 的 PiEngineHost 对齐）。但一个进程里的会话会被替换很多次
 * （new / switch / fork / clone / 扩展触发），所以：
 *  - 持有的是 `AgentSessionRuntime`，不是 `AgentSession`；
 *  - 订阅与扩展绑定都在 `#bind` 里，由运行时在每次替换后回调（`setRebindSession`），
 *    **只此一处**：rpc-mode 在命令处理里又手动绑一次，是历史遗留，这里不重复；
 *  - 每次 `#bind` 把 epoch +1，旧会话残留的事件在 App 侧按 epoch 丢弃。
 *
 * 首次绑定特殊：`bindExtensions` 会等每个扩展的 `session_start` 处理器，而处理器里常见
 * `await ctx.ui.select(...)`（等用户作答）。若 `session.open` 等它，一个扩展就能把「建立会话」
 * 拖死（原型版冒烟测试踩到的）。所以首次绑定是后台的，完成后发 `ext.bound` 事件；
 * 后续替换由命令发起，命令本来就是异步应答，直接等。
 */

import { realpathSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { loadSdk, sdkVia } from "./sdk.mjs";
import { ERR, HostError } from "./protocol.mjs";
import { STATE_EVENTS, createStatePusher, snapshotOf } from "./state.mjs";

/** 去掉 message_update 里每个增量都带着的整条 partial（pi 的 `modes/json-event.js` 同款，体积差一个数量级）。 */
export function toJsonEvent(event) {
  if (event.type !== "message_update") return event;
  const { partial: _drop, ...rest } = event.assistantMessageEvent ?? {};
  let assistantMessageEvent = rest;
  if (event.assistantMessageEvent?.type === "toolcall_start") {
    const call = event.assistantMessageEvent.partial?.content?.[event.assistantMessageEvent.contentIndex];
    if (call?.type === "toolCall") assistantMessageEvent = { ...rest, id: call.id, toolName: call.name };
  }
  return { type: "message_update", usage: event.message?.usage, assistantMessageEvent };
}

/**
 * 对照 Pi 1.6.0 `interactive-mode.js` `REASONING_DROP_DIAGNOSTICS` / `getDroppedReasoning`：
 * 当 Anthropic 或 OpenAI 丢弃请求中的思考/推理块时，解析新增丢弃计数与原因。
 */
const REASONING_DROP_DIAGNOSTICS = new Map([
  ["anthropic_input_transformations", { provider: "Anthropic", droppedType: "thinking_dropped", noun: "个思考块" }],
  ["openai_input_transformations", { provider: "OpenAI", droppedType: "reasoning", noun: "条推理记录" }],
]);

export function getDroppedReasoning(message) {
  for (const diagnostic of message?.diagnostics ?? []) {
    const kind = REASONING_DROP_DIAGNOSTICS.get(diagnostic?.type);
    if (!kind) continue;
    const transformations = diagnostic?.details?.transformations;
    if (!Array.isArray(transformations)) continue;
    const dropped = transformations.filter(
      (t) => typeof t === "object" && t !== null && t.type === kind.droppedType,
    );
    if (dropped.length === 0) continue;
    const reasons = new Set();
    for (const t of dropped) {
      if (typeof t.reason === "string" && t.reason.trim()) reasons.add(t.reason.trim());
    }
    return { diagnostic: kind, count: dropped.length, reasons: [...reasons] };
  }
  return null;
}

export function formatDroppedReasoningNotice(session, message) {
  const notices = session?.settingsManager?.getNotices?.();
  const enabled = notices ? Boolean(notices.reasoningDrops) : Boolean(session?.settingsManager?.getShowCacheMissNotices?.());
  if (!enabled) return null;
  const dropped = getDroppedReasoning(message);
  if (!dropped) return null;
  // 与官方一致：供应商在后续回放请求里会重复报告旧丢弃，仅当数量超过上一条助手消息时提示。
  const branch = session.sessionManager?.getBranch?.() ?? [];
  for (let i = branch.length - 1; i >= 0; i--) {
    const entry = branch[i];
    if (entry?.type === "message" && entry.message?.role === "assistant" && entry.message !== message) {
      const prev = getDroppedReasoning(entry.message);
      if (prev?.diagnostic === dropped.diagnostic && dropped.count <= prev.count) return null;
      break;
    }
  }
  const { provider, noun } = dropped.diagnostic;
  const reasonText = dropped.reasons.length > 0 ? `：${dropped.reasons.join(", ")}` : "";
  return `${provider} 丢弃了 ${dropped.count} ${noun}${reasonText}`;
}

/**
 * 对照 Pi 1.6.1 `interactive-mode.js#addCacheWarmingUsage`：
 * 当 `notices.cacheWarming` 开启且会话记录了 `usage (kind="cache_warm")` 条目时生成简短提示。
 */
export function formatCacheWarmingNotice(session, entry) {
  if (!session?.settingsManager?.getNotices?.()?.cacheWarming) return null;
  if (entry?.type !== "usage" || entry?.kind !== "cache_warm") return null;
  const totalCost = Number(entry?.usage?.cost?.total ?? 0);
  const costText = Number.isFinite(totalCost) && totalCost >= 0.01 ? ` (~$${totalCost.toFixed(2)})` : "";
  const note = typeof entry?.note === "string" && entry.note.trim() ? ` · ${entry.note.trim()}` : "";
  return `缓存预热已执行${costText}${note}`;
}

export function formatCacheMissNotice(miss, cacheTtlMs = 5 * 60_000) {
  if (!miss || (miss.missedTokens < 20_000 && miss.missedCost < 0.1)) return null;
  const tokensText = miss.missedTokens >= 1000 ? `${(miss.missedTokens / 1000).toFixed(1)}k` : String(miss.missedTokens);
  const costText = miss.missedCost >= 0.01 ? ` (~$${miss.missedCost.toFixed(2)})` : "";
  let label = "缓存未命中";
  if (miss.modelChanged) {
    label = "切换模型后缓存未命中";
  } else if (typeof miss.idleMs === "number" && miss.idleMs >= cacheTtlMs) {
    label = `空闲 ${Math.round(miss.idleMs / 60_000)} 分钟后缓存未命中`;
  }
  return `${label}：重新计费 ${tokensText} tokens${costText}`;
}

/**
 * 与 CLI 一致的内置扩展：Code Mode、Tool Search、MCP。
 * 缺口：CLI 还有 `llama.cpp` 内置扩展，SDK 没有导出它的工厂（只能 import dist/extensions/index.js，
 * 那是未打包的模块图，会拖慢启动），先不接，见 docs/sdk-host/capabilities.md。
 */
export function builtinExtensions(sdk, enabled = {}) {
  const table = [
    ["codemode", sdk.createCodemodeExtension],
    ["tool-search", sdk.createToolSearchExtension],
    ["mcp", sdk.createMcpExtension],
  ];
  return table
    .filter(([name, make]) => typeof make === "function" && enabled[name] !== false)
    .map(([name, make]) => ({ name, factory: make(), replaceable: true, builtin: true }));
}

export class Controller {
  /**
   * @param {{hub: import("./hub.mjs").Hub, transport: any, ui: any, surfaces: any, cfg: any}} deps
   */
  constructor({ hub, transport, ui, surfaces, cfg, themeHandle = null }) {
    this.hub = hub;
    this.transport = transport;
    this.ui = ui;
    this.surfaces = surfaces;
    this.cfg = cfg;
    this.themeHandle = themeHandle;
    this.sdk = null;
    this.runtime = null;
    this.bus = null;
    this.opened = false;
    this.shutdownRequested = false;
    this.unsubscribe = null;
    this.unsubscribeBackpressure = null;
    this.firstBind = true;
    this.extensionsBound = false;
    this.pushState = createStatePusher(this);
    this.trustOverride = undefined;
    /** 表态只对表态时的项目有效；会话换到别的 cwd 后，那个项目走自己的信任库记录。 */
    this.trustOverrideCwd = undefined;
    this.lastOpenArgs = null;
    this.footerData = null;
    this.httpDispatcherMod = null;
    this.cacheStatsMod = null;
    this.httpSettingsPromise = null;
    this.httpSettingsApplied = false;
    this.keybindingsPromise = null;
    this.footerDataPromise = null;
    this.ui.setLazyLoaders?.({
      ensureKeybindings: () => this.ensureKeybindings(),
      ensureFooterData: () => this.ensureFooterData(),
    });
    this.ui.setSessionHooks?.({
      getSdk: () => this.sdk,
      getResourceLoader: () => this.session?.resourceLoader ?? null,
      getSettingsManager: () => this.session?.settingsManager ?? null,
      emitThemeChanged: (name) => this.hub.emit("settings.changed", { key: "theme", value: name }),
    });
  }

  /** 延迟加载官方 KeybindingsManager：仅在扩展打开 custom TUI / editor 时加载，避免启动阻塞 ~1.3s（proot 下 5-15s）。 */
  ensureKeybindings() {
    if (this.keybindingsPromise) return this.keybindingsPromise;
    if (!this.cfg.piDir) return Promise.resolve(null);
    this.keybindingsPromise = (async () => {
      try {
        const { pathToFileURL } = await import("node:url");
        const kbMod = await import(pathToFileURL(join(this.cfg.piDir, "dist", "core", "keybindings.js")).href);
        if (kbMod?.KeybindingsManager?.create) {
          const kb = kbMod.KeybindingsManager.create(this.cfg.agentDir);
          this.keybindings = kb;
          this.ui.setKeybindings(kb);
          try {
            const tuiMod = await import("@earendil-works/pi-tui");
            tuiMod.setKeybindings?.(kb);
          } catch {
            // 打包入口下如果外部包名不可解析，局部 keybindings 实例仍完整工作。
          }
          return kb;
        }
      } catch (error) {
        this.hub.emit("host.log", { level: "warn", message: `keybindings init failed: ${error?.message ?? error}` });
      }
      return null;
    })();
    return this.keybindingsPromise;
  }

  /** 延迟加载官方 FooterDataProvider：仅在扩展注册自定义 footer 时加载并挂 git 监听。 */
  ensureFooterData() {
    if (this.footerData) return Promise.resolve(this.footerData);
    if (this.footerDataPromise) return this.footerDataPromise;
    if (!this.cfg.piDir) return Promise.resolve(null);
    this.footerDataPromise = (async () => {
      try {
        const { pathToFileURL } = await import("node:url");
        const fdpMod = await import(pathToFileURL(join(this.cfg.piDir, "dist", "core", "footer-data-provider.js")).href);
        if (fdpMod?.FooterDataProvider) {
          this.footerData = new fdpMod.FooterDataProvider(this.cwd);
          this.ui.setFooterData(this.footerData);
          this.syncFooterProviders();
          return this.footerData;
        }
      } catch (error) {
        this.hub.emit("host.log", { level: "warn", message: `footer-data-provider init failed: ${error?.message ?? error}` });
      }
      return null;
    })();
    return this.footerDataPromise;
  }

  /** 同步先写入代理环境变量（零额外 import），供任何同步读取 env 的逻辑即时可见。 */
  #applyProxyEnvFromSettings(sm) {
    if (!sm) return "";
    const globalSettings = sm.getGlobalSettings?.() ?? {};
    const proxy = typeof globalSettings.httpProxy === "string" ? globalSettings.httpProxy.trim() : "";
    if (proxy) {
      process.env.HTTP_PROXY = proxy;
      process.env.HTTPS_PROXY = proxy;
    } else if (this.appliedHttpProxy) {
      delete process.env.HTTP_PROXY;
      delete process.env.HTTPS_PROXY;
    }
    this.appliedHttpProxy = proxy || null;
    return proxy;
  }

  /** 确保官方 http-dispatcher 已配置：启动时后台异步执行，首次发起网络请求前等待它落地。 */
  ensureHttpSettings() {
    if (this.httpSettingsApplied) return Promise.resolve();
    if (!this.httpSettingsPromise) {
      this.httpSettingsPromise = this.applyHttpSettings();
    }
    return this.httpSettingsPromise;
  }

  #hasCustomHttpSettings(sm) {
    if (this.appliedHttpProxy) return true;
    const rawGlobal = sm?.getGlobalSettings?.() ?? {};
    const rawProject = sm?.getProjectSettings?.() ?? {};
    return rawGlobal.httpIdleTimeoutMs !== undefined || rawProject.httpIdleTimeoutMs !== undefined;
  }

  /** 应用全局设置里的 httpProxy 与 httpIdleTimeoutMs（对照 Pi `main.ts` 调用官方 `http-dispatcher.js`）。 */
  async applyHttpSettings() {
    if (!this.cfg.piDir) {
      this.httpSettingsApplied = true;
      return;
    }
    try {
      const sm = this.runtime?.services?.settingsManager
        ?? (this.sdk ? this.sdk.SettingsManager.create(this.cwd, this.cfg.agentDir, { projectTrusted: false }) : null);
      if (!sm) return;
      const hadProxy = Boolean(this.appliedHttpProxy);
      const proxy = this.#applyProxyEnvFromSettings(sm);
      if (!hadProxy && !this.#hasCustomHttpSettings(sm) && !this.httpDispatcherMod) {
        this.httpSettingsApplied = true;
        return;
      }
      if (!this.httpDispatcherMod) {
        const { pathToFileURL } = await import("node:url");
        this.httpDispatcherMod = await import(pathToFileURL(join(this.cfg.piDir, "dist", "core", "http-dispatcher.js")).href);
      }
      if (proxy || hadProxy) {
        this.httpDispatcherMod.applyHttpProxySettings?.(proxy || undefined);
      }
      const idle = sm.getHttpIdleTimeoutMs?.();
      if (idle !== undefined) {
        this.httpDispatcherMod.configureHttpDispatcher?.(idle);
      } else {
        this.httpDispatcherMod.configureHttpDispatcher?.(this.httpDispatcherMod.DEFAULT_HTTP_IDLE_TIMEOUT_MS);
      }
      this.httpSettingsApplied = true;
    } catch (error) {
      this.hub.emit("host.log", { level: "warn", message: `http-dispatcher setup failed: ${error?.message ?? error}` });
    }
  }

  /** 刷新 FooterDataProvider 的可用供应商计数（登录/退出/刷新目录后调用）。 */
  syncFooterProviders() {
    const rt = this.session?.modelRuntime;
    if (!this.footerData || !rt) return;
    try {
      const uniqueProviders = new Set(rt.getAvailableSnapshot().map((m) => m.provider));
      this.footerData.setAvailableProviderCount(uniqueProviders.size);
      this.surfaces.repaintKey("footer");
    } catch {
      // ignore
    }
  }

  get session() {
    return this.runtime?.session ?? null;
  }

  requireSession() {
    const session = this.session;
    if (!session) throw new HostError(ERR.NOT_OPEN, "会话还没有建立：先发 session.open");
    return session;
  }

  // ------------------------------------------------------------------ open

  /** 当前会话的工作目录。SDK 里 cwd 是会话的属性（会话头里记着），换会话就可能换；启动参数只是第一个会话的默认值。 */
  get cwd() {
    return this.runtime?.cwd ?? this.cfg.cwd;
  }

  async open(args = {}) {
    if (this.opened) throw new HostError(ERR.BUSY, "session.open 只能调用一次；换会话用 session.new / session.switch");
    this.opened = true;
    this.lastOpenArgs = args;
    const sdk = (this.sdk = await loadSdk(this.cfg.piDir));
    this.themeHandle?.attachSdk?.(sdk);
    const { cwd, agentDir } = this.cfg;
    const sessionDir = args.sessionDir ?? this.cfg.sessionDir ?? join(agentDir, "sessions");
    /** 实际生效的会话目录（平铺：会话文件直接放在里面）。列表、搜索、路径校验都以它为准。 */
    this.sessionDir = sessionDir;

    this.bus = sdk.createEventBus();
    const modelRuntime = await sdk.ModelRuntime.create({
      authPath: join(agentDir, "auth.json"),
      modelsPath: join(agentDir, "models.json"),
    });
    const trustStore = new sdk.ProjectTrustStore(agentDir);
    this.trustStore = trustStore;
    if (typeof args.trust === "boolean") {
      this.trustOverride = args.trust;
      this.trustOverrideCwd = this.cfg.cwd;
    }

    const flagValues = new Map(Object.entries(args.flags ?? {}));
    const resources = args.resources ?? {};

    const factory = async ({ cwd: targetCwd, sessionManager, sessionStartEvent }) => {
      const trusted = this.#projectTrusted(targetCwd);
      const settingsManager = sdk.SettingsManager.create(targetCwd, agentDir, { projectTrusted: trusted.value });
      this.#applyProxyEnvFromSettings(settingsManager);
      const services = await sdk.createAgentSessionServices({
        cwd: targetCwd,
        agentDir,
        settingsManager,
        modelRuntime,
        extensionFlagValues: flagValues,
        resourceLoaderOptions: {
          eventBus: this.bus,
          additionalExtensionPaths: this.cfg.extensions,
          // CLI 的内置扩展（pi 的 `extensions/index.js` 里 builtInExtensions）：SDK 会话**默认不带**，
          // 要自己装。同样 `builtin: true` + `replaceable: true`，于是 `pi config` / settings 里的
          // `-builtin:mcp` 这类开关继续有效，第三方 MCP 扩展也能照旧「接管」内置的。
          extensionFactories: [
            ...builtinExtensions(sdk, args.builtins),
            ...this.#androidExtensionFactories(),
          ],
          noExtensions: resources.noExtensions,
          noSkills: resources.noSkills,
          noPromptTemplates: resources.noPromptTemplates,
          noThemes: resources.noThemes,
          noContextFiles: resources.noContextFiles,
          // Pi 1.6.0 (#11264)：捕获资源加载器自动安装 npm/git 包时的子进程输出并禁用交互提示，
          // 防止 git/npm 写坏协议 stdout 或在无终端环境下挂死。
          packageCommandOutput: "capture",
          onPackageProgress: (event) => {
            if (event?.message) {
              this.hub.emit("host.log", { level: event.type === "error" ? "warn" : "info", message: String(event.message) });
            }
          },
        },
      });
      const created = await sdk.createAgentSessionFromServices({
        services,
        sessionManager,
        sessionStartEvent,
        ...(Array.isArray(args.tools) ? { tools: args.tools.map(String) } : {}),
        ...(Array.isArray(args.excludeTools) ? { excludeTools: args.excludeTools.map(String) } : {}),
        ...(args.noTools === true || args.noTools === "all"
          ? { noTools: "all" }
          : args.noTools === "builtin"
            ? { noTools: "builtin" }
            : {}),
      });
      if (trusted.undecided) {
        // 项目里有需要信任才加载的资源，而用户还没表态：先按「不信任」起，让 App 去问。
        this.hub.emit("project.trust.required", { cwd: targetCwd });
      }
      // 对照 Pi 1.6.0 `main.js:762`：当恢复会话因旧模型不可用而回退到其它模型时，
      // 把回退提示并入 diagnostics，这样 `takeUnreportedDiagnostics()` 在启动与每次 /resume /fork 后都能统一上报一次。
      const diagnostics = [...(services.diagnostics ?? [])];
      if (created.modelFallbackMessage && created.session?.model) {
        diagnostics.push({ type: "warning", message: created.modelFallbackMessage });
      }
      return { ...created, services, diagnostics };
    };

    const sessionManager = this.#pickSessionManager(sdk, cwd, sessionDir, args.session ?? {});
    this.runtime = await sdk.createAgentSessionRuntime(factory, { cwd, agentDir, sessionManager });
    this.runtime.setBeforeSessionInvalidate?.(() => {
      this.ui.reset();
      this.clearToolRenderCache?.();
      this.hub.emit("ui.reset", {});
    });
    this.runtime.setRebindSession(async () => {
      await this.#bind({ background: false });
    });

    this.#bind({ background: true }); // 同步段：订阅 + epoch；扩展绑定在后台
    const startupDiagnostics = this.#takeUnreportedDiagnostics();
    if (startupDiagnostics.length > 0) {
      setImmediate(() => {
        this.#emitDiagnostics(startupDiagnostics);
      });
    }

    const session = this.session;
    const initialState = snapshotOf(this);
    return {
      protocol: 1,
      sdkVia: sdkVia(),
      piVersion: sdk.VERSION ?? null,
      cwd,
      agentDir,
      sessionDir,
      epoch: this.hub.epoch,
      sessionId: session.sessionId,
      sessionFile: session.sessionFile ?? null,
      modelFallbackMessage: this.runtime.modelFallbackMessage ?? null,
      diagnostics: startupDiagnostics,
      state: initialState,
    };
  }

  #takeUnreportedDiagnostics() {
    if (!this.runtime) return [];
    if (typeof this.runtime.takeUnreportedDiagnostics === "function") {
      return this.runtime.takeUnreportedDiagnostics();
    }
    return [...(this.runtime.diagnostics ?? [])];
  }

  #emitDiagnostics(diagnostics) {
    if (!Array.isArray(diagnostics) || diagnostics.length === 0) return;
    this.hub.emit("runtime.diagnostics", { diagnostics });
    for (const d of diagnostics) {
      const msg = typeof d?.message === "string" ? d.message.trim() : "";
      if (!msg) continue;
      const tone = d.type === "error" ? "error" : d.type === "warning" ? "warning" : "info";
      this.hub.emit("ui.notify", { message: msg, tone });
    }
  }

  /**
   * 这个项目信不信任——与官方 `resolveProjectTrusted`（`core/project-trust.ts`）同一个顺序：
   *  1. 本次会话的表态（`--trust` 或「仅本次」）；
   *  2. 信任库里**最近**的一条记录（`ProjectTrustStore.get` 会沿父目录往上找，所以信任一个父目录就覆盖整棵树）；
   *  3. 项目里没有任何需要信任才加载的东西 → 信任（无所谓）；
   *  4. 设置 `defaultProjectTrust`：`always` / `never` 直接定；
   *  5. `ask`：先按「不信任」起，`undecided = true`，让 App 去问。
   * 官方在 3 与 4 之间还会让扩展的 `project_trust` 事件表态；宿主在会话建好之前没有扩展可问，所以没有这一步。
   * `source` 说明结论从哪来，界面据此写「为什么」。
   */
  /**
   * 预编译的 Android 设备桥内置扩展（`pi-android-bridge`）。
   * 以官方 SDK `builtin: true` + `replaceable: true` 注册进 DefaultResourceLoader，
   * 与 codemode / tool-search / mcp 同等地位：
   *  - 零 jiti / 零 Babel 编译开销（直接运行预编译 V8 原生工厂）
   *  - 在「设置 → 资源 → 扩展」以 `builtin:pi-android-bridge` 可见且支持开关（`-builtin:pi-android-bridge`）
   *  - 完整尊重 `--no-extensions`、项目信任与第三方同名扩展替换
   */
  #androidExtensionFactories() {
    if (!this.cfg.androidExtensions) return [];
    const bundled = globalThis.__piAndroidBundledExtensions;
    if (!bundled || typeof bundled !== "object") return [];
    if (typeof bundled.androidBridge !== "function") return [];
    return [{ name: "pi-android-bridge", factory: bundled.androidBridge, replaceable: true, builtin: true }];
  }

  #projectTrusted(cwd, defaultTrustOverride = undefined) {
    const sdk = this.sdk;
    if (this.trustOverride !== undefined && this.trustOverrideCwd === cwd) {
      return { value: this.trustOverride, undecided: false, source: "session" };
    }
    const stored = this.trustStore.get(cwd);
    if (stored === true || stored === false) return { value: stored, undecided: false, source: "store" };
    if (!sdk.hasTrustRequiringProjectResources(cwd)) return { value: true, undecided: false, source: "nothing-to-trust" };
    const fallback = defaultTrustOverride ?? this.#defaultProjectTrust(cwd);
    if (fallback === "always") return { value: true, undecided: false, source: "default" };
    if (fallback === "never") return { value: false, undecided: false, source: "default" };
    return { value: false, undecided: true, source: "ask" };
  }

  /** 全局设置 `defaultProjectTrust`（SDK 自己把非法值归成 `ask`）。每次读盘（含刚 flush 的 settings.set 与外部手改）。 */
  #defaultProjectTrust(cwd) {
    try {
      return this.sdk.SettingsManager.create(cwd, this.cfg.agentDir, { projectTrusted: false }).getDefaultProjectTrust();
    } catch {
      return "ask";
    }
  }

  /** 当前项目是否被信任（含 App 已表态的覆盖）。资源 / 包命令按它决定读不读项目层设置。 */
  isProjectTrusted() {
    return this.#projectTrusted(this.cwd).value;
  }

  /**
   * 信任的完整读数：结论、来源、信任库里哪一条在起作用（可能是父目录的）、项目里有没有需要信任的东西。
   * 「继承」= 起作用的记录不是这个目录自己的（比如信任了它的父目录）。
   */
  trustState() {
    this.requireSession();
    const cwd = this.cwd;
    const defaultTrust = this.#defaultProjectTrust(cwd);
    const t = this.#projectTrusted(cwd, defaultTrust);
    const entry = this.trustStore.getEntry(cwd);
    let canonical = cwd;
    try {
      canonical = realpathSync(cwd);
    } catch {
      canonical = resolve(cwd);
    }
    const parent = dirname(canonical);
    return {
      cwd,
      trusted: t.value,
      undecided: t.undecided,
      source: t.source,
      entry: entry ? { path: entry.path, decision: entry.decision, inherited: entry.path !== canonical } : null,
      needsTrust: this.sdk.hasTrustRequiringProjectResources(cwd),
      defaultProjectTrust: defaultTrust,
      parent: parent === canonical ? null : parent,
    };
  }

  /**
   * 一批目录各自的信任结论（项目切换列表用）。同一个 `#projectTrusted` 顺序，所以与切过去之后
   * 看到的一致；只读，不建会话、不动信任库。目录名不是字符串或不是绝对路径的忽略。
   * 全局 `defaultProjectTrust` 仅读一次，避免目录多时重复初始化 N 次 SettingsManager。
   */
  trustOf(cwds) {
    const out = {};
    const list = Array.isArray(cwds) ? cwds.slice(0, 200) : [];
    const defaultTrust = list.length > 0 ? this.#defaultProjectTrust(this.cwd || "/") : "ask";
    for (const cwd of list) {
      if (typeof cwd !== "string" || !cwd.startsWith("/")) continue;
      const t = this.#projectTrusted(cwd, defaultTrust);
      out[cwd] = { trusted: t.value, undecided: t.undecided, source: t.source };
    }
    return out;
  }

  /** 按需懒加载官方 `cache-stats.js`：仅在 `notices.cacheMisses` 开启且收到助手回复时才导入，冷启动零开销。 */
  async #emitCacheMissNotice(session, message) {
    if (!this.cfg.piDir) return;
    if (message.stopReason === "aborted" || message.stopReason === "error") return;
    try {
      if (!this.cacheStatsMod) {
        const { pathToFileURL } = await import("node:url");
        this.cacheStatsMod = await import(pathToFileURL(join(this.cfg.piDir, "dist", "core", "cache-stats.js")).href);
      }
      const { detectCacheMiss, CACHE_TTL_MS } = this.cacheStatsMod;
      if (typeof detectCacheMiss !== "function") return;
      const miss = detectCacheMiss(session.sessionManager.getEntries(), message, session.modelRuntime);
      const text = formatCacheMissNotice(miss, CACHE_TTL_MS);
      if (text) this.hub.emit("ui.notify", { message: text, tone: "warning" });
    } catch {
      // 忽略诊断计算异常，绝不干扰正常对话流。
    }
  }

  #pickSessionManager(sdk, cwd, sessionDir, sel) {
    const { SessionManager } = sdk;
    if (typeof sel.file === "string") return SessionManager.open(sel.file, sessionDir);
    if (typeof sel.id === "string") {
      // pi 的 `--session-id` 语义：找得到就接着写，找不到就用这个 id 新建。
      const found = SessionManager.findById(cwd, sel.id, sessionDir);
      return found ? SessionManager.open(found, sessionDir) : SessionManager.create(cwd, sessionDir, { id: sel.id });
    }
    if (sel.continue === true) return SessionManager.continueRecent(cwd, sessionDir);
    return SessionManager.create(cwd, sessionDir);
  }

  // ------------------------------------------------------------------ bind

  async #bind({ background }) {
    const session = this.runtime.session;
    const hub = this.hub;

    this.unsubscribe?.();
    this.unsubscribeBackpressure?.();
    this.extensionsBound = false;
    this.ui.reset();
    this.clearToolRenderCache?.();
    this.footerData?.setCwd?.(this.cwd);
    this.syncFooterProviders();
    hub.bumpEpoch();
    // 官方 `resetExtensionUI`：会话被替换时，扩展挂的状态 / 小组件 / 自定义编辑器 / 工作提示 / 思考标签全部收回。
    // 宿主这边的 reset() 只清了自己的表，App 那份投影得到通知才会清（否则 /new、切会话后上一个会话的提示会一直挂着）。
    hub.emit("ui.reset", {});
    hub.emit("session.bound", { sessionId: session.sessionId, sessionFile: session.sessionFile ?? null, first: this.firstBind });

    this.unsubscribe = session.subscribe((event) => {
      hub.emit("agent", toJsonEvent(event));
      if (
        STATE_EVENTS.has(event.type) ||
        (event.type === "entry_appended" &&
          (event.entry?.type === "model_change" ||
            event.entry?.type === "thinking_level_change" ||
            event.entry?.type === "session_info"))
      ) {
        this.pushState(event.type);
      }
      if (event.type === "entry_appended") {
        const warmNotice = formatCacheWarmingNotice(session, event.entry);
        if (warmNotice) hub.emit("ui.notify", { message: warmNotice, tone: "info" });
      }
      if (event.type === "message_end" && event.message?.role === "assistant") {
        const dropNotice = formatDroppedReasoningNotice(session, event.message);
        if (dropNotice) hub.emit("ui.notify", { message: dropNotice, tone: "warning" });
        if (session?.settingsManager?.getNotices?.()?.cacheMisses) {
          void this.#emitCacheMissNotice(session, event.message);
        }
      }
      if (event.type === "agent_end" || event.type === "agent_settled") {
        // 新会话在首条助手回复后落盘，老会话也会增长消息数/修改时间；使列表缓存失效，确保立刻可见。
        this.invalidateSessionList?.();
      }
      if (event.type === "agent_settled" && this.shutdownRequested) void this.shutdown(0);
    });
    // 事件产生得比管道消化得快时，让 agent 停一停，而不是在内存里堆帧。
    // 同步快路径：低水位时不分配 Promise，消除高频 token 下的额外微任务。
    this.unsubscribeBackpressure = session.agent.subscribe(() =>
      this.transport.isBackpressured() ? this.transport.drained() : undefined,
    );

    const bindings = {
      uiContext: this.ui.context,
      // 官方交互模式固定传 "tui"（modes/interactive/interactive-mode.js）。不传时 SDK 回落到 "print"，
      // 扩展里的 `ctx.mode !== "tui"` 守卫会把本宿主当成非交互环境而拒绝运行（"… requires interactive mode"）。
      mode: "tui",
      commandContextActions: this.#commandActions(),
      shutdownHandler: () => {
        this.shutdownRequested = true;
      },
      abortHandler: () => {
        void session.abort().catch(() => {});
      },
      onError: (err) => hub.emit("ext.error", { extensionPath: err.extensionPath, event: err.event, error: err.error }),
    };
    const bind = () => session.bindExtensions(bindings);
    const wasFirst = this.firstBind;
    this.firstBind = false;

    if (!background) {
      this.bindPromise = bind().finally(() => {
        this.bindPromise = null;
      });
      await this.bindPromise;
      this.extensionsBound = true;
      this.pushState("rebind");
      // Pi 1.6.0：每次会话替换（/new /resume /fork /clone）后上报本轮新增诊断（每条仅报一次）。
      this.#emitDiagnostics(this.#takeUnreportedDiagnostics());
      return;
    }
    // `setImmediate`：`bindExtensions` 的同步段会加载扩展并跑 `session_start`，实测能把
    // 紧随其后的应答推迟 8 秒。让 `open` 的应答先写出去。
    this.bindPromise = new Promise((resolveBind) => {
      setImmediate(() => {
        bind()
          .then(() => {
            this.extensionsBound = true;
            hub.emit("ext.bound", { ok: true, first: wasFirst });
            this.pushState("extensions");
            // 仅当用户配置了代理或自定义空闲超时时，才在启动后异步加载官方 http-dispatcher（undici，proot 下约 1.5~3s）；
            // 否则留给首次发起网络请求（prompt / auth）时由 `ensureHttpSettings()` 按需加载，避免拖慢冷启动。
            const sm = this.runtime?.services?.settingsManager;
            const hasCustomHttp = this.#hasCustomHttpSettings(sm);
            if (hasCustomHttp) {
              setImmediate(() => void this.ensureHttpSettings());
            }
          })
          .catch((error) => {
            hub.emit("ext.bound", { ok: false, first: wasFirst, error: String(error?.stack ?? error) });
          })
          .finally(() => {
            this.bindPromise = null;
            resolveBind();
          });
      });
    });
  }

  #commandActions() {
    const runtime = this.runtime;
    return {
      waitForIdle: () => this.session.waitForIdle(),
      newSession: async (options) => runtime.newSession(options),
      fork: async (entryId, forkOptions) => {
        const res = await runtime.fork(entryId, forkOptions);
        if (!res.cancelled && typeof res.selectedText === "string" && res.selectedText.length > 0) {
          this.ui.context.setEditorText(res.selectedText);
        }
        return { cancelled: res.cancelled };
      },
      navigateTree: async (targetId, options) => {
        const res = await this.session.navigateTree(targetId, {
          summarize: options?.summarize,
          customInstructions: options?.customInstructions,
          replaceInstructions: options?.replaceInstructions,
          label: options?.label,
        });
        if (!res.cancelled && typeof res.editorText === "string" && res.editorText.length > 0) {
          this.ui.context.setEditorText(res.editorText);
        }
        return { cancelled: res.cancelled };
      },
      switchSession: async (sessionPath, options) => runtime.switchSession(sessionPath, options),
      reload: async () => {
        await this.reloadSession();
      },
    };
  }

  /**
   * **所有**重载都走这一个口：`session.reload()` 会重建扩展 runner、重读技能 / 提示模板 / 主题 /
   * 上下文文件，于是**命令表、工具表、资源目录都可能变了**，而 App 缓存着它们（命令面板）。
   * 此前 App 的四个入口（命令面板的 /reload、包管理、资源中心、MCP）和扩展自己调的 `ctx.reload()`
   * 各自重载、各自忘了刷新，面板就一直显示旧命令（包管理页是现读，所以那边看得到）。
   * 现在重载一完成就发 `catalog.changed`，谁触发的重载都一样刷新。
   */
  async reloadSession() {
    const session = this.requireSession();
    if (this.reloading) return { reloaded: false, reason: "正在重新加载扩展与资源，请稍候。" };
    // 若启动后的首次后台 `bindExtensions()` 尚未结束，先等它完成，避免 `session.reload()` 时
    // `hasBindings` 为 false 而跳过 `session_start(reason="reload")`（这正是此前要点两次才生效的根因）。
    if (this.bindPromise) {
      try {
        await this.bindPromise;
      } catch {
        // 首次绑定失败也不阻止手动重载
      }
    }
    // 官方 `handleReloadCommand`（interactive-mode.js）先拦两种情况：流式中 / 压缩中不重载，只提示。
    // `AgentSession.reload()` 自己没有这道闸，重建 runner 会撞上正在跑的一轮，所以所有入口共用这里的判断。
    if (session.isStreaming) return { reloaded: false, reason: "Wait for the current response to finish before reloading." };
    if (session.isCompacting) return { reloaded: false, reason: "Wait for compaction to finish before reloading." };
    this.reloading = true;
    // 官方 `handleReloadCommand` 在 reload 之前先 `resetExtensionUI()`：reload 会换掉所有扩展，旧扩展挂的东西要先收。
    this.ui.reset();
    this.clearToolRenderCache?.();
    this.hub.emit("ui.reset", {});
    try {
      await session.reload();
      this.extensionsBound = true;
      try {
        this.keybindings?.reload?.();
      } catch {
        // ignore keybindings reload error
      }
      this.syncFooterProviders();
      await this.applyHttpSettings?.();
      this.pushState("reload");
      return { reloaded: true };
    } finally {
      this.reloading = false;
      // 失败也发：reload 中途抛错时 runner 可能已经换了一半，让 App 读到真实现状而不是旧缓存。
      this.hub.emit("catalog.changed", { reason: "reload" });
    }
  }

  /**
   * 项目信任表态：写进 pi 的信任库（`trust.json`），并让**已经在跑的会话**读到新结论。
   *
   * `remember`（对应官方弹窗的几个选项，`getProjectTrustOptions`）：
   *  - `folder`：记在这个目录（信任 / 不信任）；
   *  - `parent`：信任它的父目录并清掉这个目录自己的记录——只对「信任」有意义，官方也只给这一个选项；
   *  - `session`：只管这一次，不写信任库；
   *  - `reset`：清掉这个目录自己的记录，回到「没表态」（之后按 `defaultProjectTrust` 走）；
   *  - `reset-inherited`：起作用的是父目录的记录时，清掉那一条。
   *
   * **必须把结论交给会话的 `SettingsManager.setProjectTrusted`**：`session.reload()` 明确「保留
   * SettingsManager 当时的信任态」（`resource-loader.ts` reload 注释），只写信任库再 reload，
   * 会话里项目层的设置与资源仍然按旧结论加载。
   */
  async setProjectTrust(trusted, remember = "folder") {
    const cwd = this.cwd;
    const parent = dirname(cwd);
    switch (remember) {
      case "folder":
        this.trustStore.set(cwd, trusted);
        this.#clearTrustOverride();
        break;
      case "parent":
        if (!trusted) throw new HostError(ERR.BAD_ARGS, "「信任父目录」只用于信任。");
        if (parent === cwd) throw new HostError(ERR.BAD_ARGS, "这个目录没有父目录。");
        this.trustStore.setMany([
          { path: parent, decision: true },
          { path: cwd, decision: null },
        ]);
        this.#clearTrustOverride();
        break;
      case "session":
        this.trustOverride = trusted;
        this.trustOverrideCwd = cwd;
        break;
      case "reset":
        this.trustStore.set(cwd, null);
        this.#clearTrustOverride();
        break;
      case "reset-inherited": {
        // 起作用的是父目录的记录：清掉那一条（会影响它下面所有没自己记录的项目，界面要把话说清）。
        const entry = this.trustStore.getEntry(cwd);
        if (!entry) throw new HostError(ERR.BAD_ARGS, "没有可清除的信任记录。");
        this.trustStore.setMany([{ path: entry.path, decision: null }]);
        this.#clearTrustOverride();
        break;
      }
      default:
        throw new HostError(ERR.BAD_ARGS, `未知的 remember：${remember}`);
    }
    const session = this.requireSession();
    const now = this.#projectTrusted(cwd);
    session.settingsManager.setProjectTrusted(now.value);
    await this.reloadSession();
    if (now.undecided) this.hub.emit("project.trust.required", { cwd });
    return this.trustState();
  }

  /**
   * 当 `defaultProjectTrust` 变更（或 `settings.reload`）时，重新评估当前项目的有效信任态：
   * 若该项目未单独表态且有效信任态发生变化，立即同步给运行中会话的 `SettingsManager.setProjectTrusted` 并热重载资源。
   */
  async reconcileProjectTrust() {
    const session = this.session;
    if (!session) return null;
    const cwd = this.cwd;
    const now = this.#projectTrusted(cwd);
    const prevTrusted = session.settingsManager?.isProjectTrusted?.();
    if (typeof prevTrusted === "boolean" && now.value !== prevTrusted) {
      session.settingsManager.setProjectTrusted(now.value);
      await this.reloadSession();
    }
    if (now.undecided) this.hub.emit("project.trust.required", { cwd });
    return this.trustState();
  }

  #clearTrustOverride() {
    this.trustOverride = undefined;
    this.trustOverrideCwd = undefined;
  }

  // ------------------------------------------------------------------ shutdown

  async shutdown(code = 0) {
    if (this.shuttingDown) return;
    this.shuttingDown = true;
    try {
      this.unsubscribe?.();
      this.unsubscribeBackpressure?.();
      this.footerData?.dispose?.();
      this.hub.cancelAllAsks();
      await Promise.race([this.runtime?.dispose(), new Promise((resolve) => setTimeout(resolve, 3000))]);
      await Promise.race([this.transport.flush(), new Promise((resolve) => setTimeout(resolve, 1000))]);
    } finally {
      process.exit(code);
    }
  }
}
