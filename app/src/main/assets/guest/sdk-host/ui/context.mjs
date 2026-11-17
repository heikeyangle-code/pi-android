/**
 * 完整的 `ExtensionUIContext`：每一个方法都接到 App，没有「RPC 模式不支持」的空桩。
 *
 * 分三类：
 *  1. **对话框**（select / confirm / input / editor）：`req` 帧向 App 提问，等回答，
 *     支持超时和 AbortSignal，收回时通知 App；
 *  2. **状态面**（notify / setStatus / setTitle / setWidget 行 / working* …）：`evt` 单向推送，
 *     App 持有状态；
 *  3. **组件面**（setWidget 工厂 / setFooter / setHeader / custom）：变成「表面」，见 surfaces.mjs。
 *
 * 仍然不能原样支持的（终端专属）：`setEditorComponent`（自定义终端编辑器）和
 * `onTerminalInput`（原始按键）。前者只通知 App「有扩展想接管输入框」，后者在 App 把输入框
 * 文本推过来（`ui.editorState`）时回调一次文本 —— 不是真按键，文档里写明了降级方式。
 */

export function createUiContext({ hub, surfaces, theme, flags }) {
  const state = {
    editorText: "",
    statuses: new Map(),
    terminalInputHandlers: new Set(),
    autocompleteProviders: [],
    toolsExpanded: false,
  };

  const keybindings = new Proxy({}, {
    get: (_t, prop) => (prop === "matches" ? () => false : prop === "getKeys" ? () => [] : () => false),
  });

  const footerData = {
    getGitBranch: () => undefined,
    getExtensionStatuses: () => new Map(state.statuses),
    getAvailableProviderCount: () => 0,
    onBranchChange: () => () => {},
  };

  const context = {
    // ---- 对话框 ---------------------------------------------------------
    async select(title, options, opts) {
      const value = await hub.ask("ui.select", { title, options, timeout: opts?.timeout }, undefined, opts);
      return typeof value === "string" ? value : undefined;
    },
    async confirm(title, message, opts) {
      return (await hub.ask("ui.confirm", { title, message, timeout: opts?.timeout }, false, opts)) === true;
    },
    async input(title, placeholder, opts) {
      const value = await hub.ask("ui.input", { title, placeholder, timeout: opts?.timeout }, undefined, opts);
      return typeof value === "string" ? value : undefined;
    },
    async editor(title, prefill) {
      const value = await hub.ask("ui.editor", { title, prefill }, undefined);
      return typeof value === "string" ? value : undefined;
    },

    // ---- 状态面 ---------------------------------------------------------
    notify(message, type) {
      hub.emit("ui.notify", { message: String(message), tone: type ?? "info" });
    },
    setStatus(key, text) {
      if (text === undefined) state.statuses.delete(String(key));
      else state.statuses.set(String(key), String(text));
      hub.emit("ui.status", { key: String(key), text: text === undefined ? null : String(text) });
    },
    setTitle(title) {
      hub.emit("ui.title", { title: String(title) });
    },
    setEditorText(text) {
      state.editorText = String(text);
      hub.emit("ui.editorText", { text: state.editorText });
    },
    getEditorText: () => state.editorText,
    pasteToEditor(text) {
      hub.emit("ui.paste", { text: String(text) });
    },
    setWorkingMessage(message) {
      hub.emit("ui.working", { message: message === undefined ? null : String(message) });
    },
    setWorkingVisible(visible) {
      hub.emit("ui.workingVisible", { visible: Boolean(visible) });
    },
    setWorkingIndicator(options) {
      hub.emit("ui.workingIndicator", { frames: options?.frames ?? null, intervalMs: options?.intervalMs ?? null });
    },
    setHiddenThinkingLabel(label) {
      hub.emit("ui.thinkingLabel", { label: label ?? null });
    },
    getToolsExpanded: () => state.toolsExpanded,
    setToolsExpanded(expanded) {
      state.toolsExpanded = Boolean(expanded);
      hub.emit("ui.toolsExpanded", { expanded: state.toolsExpanded });
    },

    // ---- 组件面 ---------------------------------------------------------
    setWidget(key, content, options) {
      const k = String(key);
      const placement = options?.placement ?? null;
      if (content === undefined) {
        surfaces.closeKey(`widget:${k}`);
        hub.emit("ui.widget", { key: k, lines: null, surfaceId: null, placement });
        return;
      }
      if (Array.isArray(content)) {
        surfaces.closeKey(`widget:${k}`);
        hub.emit("ui.widget", { key: k, lines: content.map(String), surfaceId: null, placement });
        return;
      }
      if (typeof content === "function") {
        surfaces
          .mount({ mode: "widget", key: `widget:${k}`, build: (tui) => content(tui, theme) })
          .then((surface) => hub.emit("ui.widget", { key: k, lines: null, surfaceId: surface.id, placement }))
          .catch((error) => hub.emit("host.log", { level: "error", message: `widget ${k}: ${error?.message ?? error}` }));
      }
    },
    setFooter(factory) {
      mountChrome("footer", factory && ((tui) => factory(tui, theme, footerData)));
    },
    setHeader(factory) {
      mountChrome("header", factory && ((tui) => factory(tui, theme)));
    },
    custom(factory, options) {
      return new Promise((resolve) => {
        let surfaceRef = null;
        let done = false;
        const finish = (value) => {
          if (done) return;
          done = true;
          if (surfaceRef) surfaces.close(surfaceRef.id);
          resolve(value);
        };
        surfaces
          .mount({
            mode: "custom",
            overlay: Boolean(options?.overlay),
            // 被外部关掉（用户点了 ✕ / 会话被替换）：以 undefined 结束，扩展的 await 不能悬着。
            onClose: () => finish(undefined),
            build: (tui, surface) => {
              surfaceRef = surface;
              return factory(tui, theme, keybindings, finish);
            },
          })
          .then((surface) => {
            surfaceRef = surface;
            if (done) surfaces.close(surface.id);
          })
          .catch(() => finish(undefined));
      });
    },
    getEditorComponent: () => undefined,
    setEditorComponent(factory) {
      // 终端编辑器组件无法映射成手机输入框；只让 App 知道有扩展想接管。
      hub.emit("ui.chrome", { slot: "editor", attached: Boolean(factory), supported: false });
    },
    addAutocompleteProvider(factory) {
      state.autocompleteProviders.push(factory);
      hub.emit("ui.autocomplete", { registered: state.autocompleteProviders.length });
    },
    onTerminalInput(handler) {
      state.terminalInputHandlers.add(handler);
      return () => state.terminalInputHandlers.delete(handler);
    },

    // ---- 主题 -----------------------------------------------------------
    get theme() {
      return theme;
    },
    getAllThemes: () => [],
    getTheme: () => undefined,
    setTheme: () => ({ success: false, error: "主题由 App 管理（命令 ui.palette）" }),
  };

  function mountChrome(slot, build) {
    if (!build) {
      surfaces.closeKey(slot);
      hub.emit("ui.chrome", { slot, attached: false, supported: true });
      return;
    }
    surfaces
      .mount({ mode: slot, key: slot, build })
      .then((surface) => hub.emit("ui.chrome", { slot, attached: true, supported: true, surfaceId: surface.id }))
      .catch((error) => hub.emit("host.log", { level: "error", message: `${slot}: ${error?.message ?? error}` }));
  }

  return {
    context,
    state,
    /** 会话被替换时：扩展已经被拆掉，它们挂的一切都要收。 */
    reset() {
      surfaces.closeAll();
      hub.cancelAllAsks();
      state.statuses.clear();
      state.terminalInputHandlers.clear();
      state.autocompleteProviders.length = 0;
    },
  };
}
