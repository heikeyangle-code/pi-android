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

import { join } from "node:path";
import { loadSdk, sdkVia } from "./sdk.mjs";
import { ERR, HostError } from "./protocol.mjs";
import { STATE_EVENTS, createStatePusher } from "./state.mjs";

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
  constructor({ hub, transport, ui, surfaces, cfg }) {
    this.hub = hub;
    this.transport = transport;
    this.ui = ui;
    this.surfaces = surfaces;
    this.cfg = cfg;
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
          extensionFactories: builtinExtensions(sdk, args.builtins),
          noExtensions: resources.noExtensions,
          noSkills: resources.noSkills,
          noPromptTemplates: resources.noPromptTemplates,
          noThemes: resources.noThemes,
          noContextFiles: resources.noContextFiles,
        },
      });
      const created = await sdk.createAgentSessionFromServices({ services, sessionManager, sessionStartEvent });
      if (trusted.undecided) {
        // 项目里有需要信任才加载的资源，而用户还没表态：先按「不信任」起，让 App 去问。
        this.hub.emit("project.trust.required", { cwd: targetCwd });
      }
      return { ...created, services, diagnostics: services.diagnostics };
    };

    const sessionManager = this.#pickSessionManager(sdk, cwd, sessionDir, args.session ?? {});
    this.runtime = await sdk.createAgentSessionRuntime(factory, { cwd, agentDir, sessionManager });
    this.runtime.setRebindSession(async () => {
      await this.#bind({ background: false });
    });

    this.#bind({ background: true }); // 同步段：订阅 + epoch；扩展绑定在后台
    setImmediate(() => this.pushState("open")); // 应答先出去，状态紧随其后

    const session = this.session;
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
      diagnostics: this.runtime.diagnostics ?? [],
    };
  }

  #projectTrusted(cwd) {
    const sdk = this.sdk;
    if (this.trustOverride !== undefined && this.trustOverrideCwd === cwd) return { value: this.trustOverride, undecided: false };
    const stored = this.trustStore.get(cwd);
    if (stored === true || stored === false) return { value: stored, undecided: false };
    const needs = sdk.hasTrustRequiringProjectResources(cwd);
    // 没有需要信任的东西：信任与否无关紧要，按 pi 自己的默认（信任）。
    // 有而没表态：不信任，并通知 App 询问（`project.trust` 命令回写）。
    return needs ? { value: false, undecided: true } : { value: true, undecided: false };
  }

  /** 当前项目是否被信任（含 App 已表态的覆盖）。资源 / 包命令按它决定读不读项目层设置。 */
  isProjectTrusted() {
    return this.#projectTrusted(this.cwd).value;
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
    hub.bumpEpoch();
    hub.emit("session.bound", { sessionId: session.sessionId, sessionFile: session.sessionFile ?? null, first: this.firstBind });

    this.unsubscribe = session.subscribe((event) => {
      hub.emit("agent", toJsonEvent(event));
      if (STATE_EVENTS.has(event.type)) this.pushState(event.type);
      if (event.type === "agent_settled" && this.shutdownRequested) void this.shutdown(0);
    });
    // 事件产生得比管道消化得快时，让 agent 停一停，而不是在内存里堆帧。
    this.unsubscribeBackpressure = session.agent.subscribe(async () => {
      await this.transport.drained();
    });

    const bindings = {
      uiContext: this.ui.context,
      mode: this.cfg.mode,
      commandContextActions: this.#commandActions(),
      shutdownHandler: () => {
        this.shutdownRequested = true;
      },
      onError: (err) => hub.emit("ext.error", { extensionPath: err.extensionPath, event: err.event, error: err.error }),
    };
    const bind = () => session.bindExtensions(bindings);
    const wasFirst = this.firstBind;
    this.firstBind = false;

    if (!background) {
      await bind();
      this.extensionsBound = true;
      this.pushState("rebind");
      return;
    }
    // `setImmediate`：`bindExtensions` 的同步段会加载扩展并跑 `session_start`，实测能把
    // 紧随其后的应答推迟 8 秒。让 `open` 的应答先写出去。
    setImmediate(() => {
      bind()
        .then(() => {
          this.extensionsBound = true;
          hub.emit("ext.bound", { ok: true, first: wasFirst });
          this.pushState("extensions");
        })
        .catch((error) => hub.emit("ext.bound", { ok: false, first: wasFirst, error: String(error?.stack ?? error) }));
    });
  }

  #commandActions() {
    const runtime = this.runtime;
    return {
      waitForIdle: () => this.session.waitForIdle(),
      newSession: async (options) => runtime.newSession(options),
      fork: async (entryId, forkOptions) => ({ cancelled: (await runtime.fork(entryId, forkOptions)).cancelled }),
      navigateTree: async (targetId, options) => ({
        cancelled: (
          await this.session.navigateTree(targetId, {
            summarize: options?.summarize,
            customInstructions: options?.customInstructions,
            replaceInstructions: options?.replaceInstructions,
            label: options?.label,
          })
        ).cancelled,
      }),
      switchSession: async (sessionPath, options) => runtime.switchSession(sessionPath, options),
      reload: async () => {
        await this.session.reload();
      },
    };
  }

  /** 项目信任表态（App 的弹窗回答）：写回 pi 的信任库并重载。 */
  async setProjectTrust(trusted) {
    const cwd = this.cwd;
    this.trustStore.set(cwd, trusted);
    this.trustOverride = trusted;
    this.trustOverrideCwd = cwd;
    await this.requireSession().reload();
    return { trusted };
  }

  // ------------------------------------------------------------------ shutdown

  async shutdown(code = 0) {
    if (this.shuttingDown) return;
    this.shuttingDown = true;
    try {
      this.unsubscribe?.();
      this.unsubscribeBackpressure?.();
      this.hub.cancelAllAsks();
      await Promise.race([this.runtime?.dispose(), new Promise((resolve) => setTimeout(resolve, 3000))]);
      await Promise.race([this.transport.flush(), new Promise((resolve) => setTimeout(resolve, 1000))]);
    } finally {
      process.exit(code);
    }
  }
}
