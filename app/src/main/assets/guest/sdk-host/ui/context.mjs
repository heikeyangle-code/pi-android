/**
 * 完整的 `ExtensionUIContext`：每一个方法都接到 App，没有「RPC 模式不支持」的空桩。
 *
 * 对照 Pi 官方 `packages/coding-agent/src/core/extensions/types.ts`：
 *  1. **对话框**（select / confirm / input / editor）：`req` 帧向 App 提问，等回答，
 *     支持超时和 AbortSignal，收回时发 `ui.cancel` 通知 App；
 *  2. **状态面**（notify / setStatus / setTitle / setWidget 行 / working* / toolsExpanded …）：
 *     `evt` 单向推送，同时同步给真实的 `FooterDataProvider`；
 *  3. **组件面**（setWidget 工厂 / setFooter / setHeader / custom）：变成「表面」，见 surfaces.mjs；
 *     传入真实的 `KeybindingsManager`、`FooterDataProvider`、`OverlayHandle` 与 `overlayOptions`；
 *  4. **自动补全与终端输入**（`addAutocompleteProvider` / `onTerminalInput`）：
 *     通过 `ui.autocomplete` 和 `ui.terminalInput` 命令与 App 输入框打通。
 */

/** 内置兜底键位匹配表（在 session.open 加载官方 KeybindingsManager 前也能匹配常用 TUI 键位）。 */
const FALLBACK_KEY_MAP = {
  "tui.select.up": ["\x1b[A", "\x1bOA"],
  "tui.select.down": ["\x1b[B", "\x1bOB"],
  "tui.select.pageUp": ["\x1b[5~"],
  "tui.select.pageDown": ["\x1b[6~"],
  "tui.select.confirm": ["\r", "\n"],
  "tui.select.cancel": ["\x1b", "\x03"],
  "tui.input.submit": ["\r", "\n"],
  "tui.input.tab": ["\t"],
  "tui.editor.cursorUp": ["\x1b[A", "\x1bOA"],
  "tui.editor.cursorDown": ["\x1b[B", "\x1bOB"],
  "tui.editor.cursorLeft": ["\x1b[D", "\x1bOD"],
  "tui.editor.cursorRight": ["\x1b[C", "\x1bOC"],
  "tui.editor.deleteCharBackward": ["\x7f", "\b"],
  "tui.editor.cursorLineStart": ["\x1b[H", "\x01"],
  "tui.editor.cursorLineEnd": ["\x1b[F", "\x05"],
  "app.interrupt": ["\x1b"],
  "app.clear": ["\x03"],
  "app.exit": ["\x04"],
  "app.thinking.cycle": ["\x1b[Z"],
};

function createFallbackKeybindings() {
  return {
    matches(data, keybinding) {
      const seqs = FALLBACK_KEY_MAP[keybinding];
      return Array.isArray(seqs) ? seqs.includes(data) : false;
    },
    getKeys(keybinding) {
      return FALLBACK_KEY_MAP[keybinding] ?? [];
    },
    getEffectiveConfig: () => ({}),
  };
}

/** `addAutocompleteProvider` 链最底层的空基座（内置 `/` 和 `@` 由 App 原生处理，这里承接扩展追加的补全）。 */
const BASE_AUTOCOMPLETE_PROVIDER = {
  getSuggestions: async () => null,
  applyCompletion: (lines, cursorLine, cursorCol) => ({ lines, cursorLine, cursorCol }),
};

const AUTOCOMPLETE_TIMEOUT_MS = 1500;

export function createUiContext({ hub, surfaces, theme, themeHandle = null, flags }) {
  const state = {
    editorText: "",
    statuses: new Map(),
    terminalInputHandlers: new Set(),
    autocompleteProviders: [],
    toolsExpanded: false,
    customEditorFactory: undefined,
  };

  let activeKeybindings = createFallbackKeybindings();
  let activeFooterData = null;
  let lazyLoaders = {};
  let sessionHooks = {};

  // 代理到当前生效的真实 `KeybindingsManager`，确保扩展在 `open` 期间拿到的引用也会自动升级。
  const keybindings = {
    matches: (data, id) => Boolean(activeKeybindings?.matches?.(data, id)),
    getKeys: (id) => activeKeybindings?.getKeys?.(id) ?? [],
    getEffectiveConfig: () => activeKeybindings?.getEffectiveConfig?.() ?? {},
  };

  const branchListeners = new Set();
  const footerData = {
    getGitBranch: () => (activeFooterData ? activeFooterData.getGitBranch() : null),
    getExtensionStatuses: () => new Map(state.statuses),
    getAvailableProviderCount: () => (activeFooterData ? activeFooterData.getAvailableProviderCount() : 0),
    onBranchChange: (cb) => {
      branchListeners.add(cb);
      const unsubReal = activeFooterData ? activeFooterData.onBranchChange(cb) : null;
      return () => {
        branchListeners.delete(cb);
        unsubReal?.();
      };
    },
  };

  let composedAutocomplete = null;
  function getAutocompleteChain() {
    if (state.autocompleteProviders.length === 0) return null;
    if (composedAutocomplete) return composedAutocomplete;
    let current = BASE_AUTOCOMPLETE_PROVIDER;
    for (const factory of state.autocompleteProviders) {
      try {
        const next = factory(current);
        if (next && typeof next.getSuggestions === "function") current = next;
      } catch (error) {
        hub.emit("host.log", { level: "warn", message: `autocomplete provider factory failed: ${error?.message ?? error}` });
      }
    }
    composedAutocomplete = current;
    return current;
  }

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
      const k = String(key);
      if (text === undefined) {
        state.statuses.delete(k);
        activeFooterData?.setExtensionStatus?.(k, undefined);
      } else {
        const v = String(text);
        state.statuses.set(k, v);
        activeFooterData?.setExtensionStatus?.(k, v);
      }
      hub.emit("ui.status", { key: k, text: text === undefined ? null : String(text) });
      // Footer 组件常依赖 `footerData.getExtensionStatuses()`，状态变更时触发重绘。
      surfaces.repaintKey("footer");
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
      const chunk = String(text);
      state.editorText += chunk;
      hub.emit("ui.paste", { text: chunk });
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
      if (!factory) {
        mountChrome("footer", null);
        return;
      }
      Promise.resolve(activeFooterData ? null : lazyLoaders.ensureFooterData?.())
        .then(() => mountChrome("footer", (tui) => factory(tui, theme, footerData)))
        .catch((error) => hub.emit("host.log", { level: "error", message: `footer: ${error?.message ?? error}` }));
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
        const blocked =
          typeof options?.blocked === "string"
            ? options.blocked || null
            : typeof options?.blocked?.message === "string" && options.blocked.message
              ? options.blocked.message
              : null;
        const blockedKind = typeof options?.blocked?.kind === "string" ? options.blocked.kind : null;
        Promise.resolve(lazyLoaders.ensureKeybindings?.())
          .then(() =>
            surfaces.mount({
              mode: "custom",
              overlay: Boolean(options?.overlay),
              overlayOptions: options?.overlayOptions ?? null,
              blocked,
              blockedKind,
              onHandle: options?.onHandle,
              // 被外部关掉（用户点了 ✕ / 会话被替换）：以 undefined 结束，扩展的 await 不能悬着。
              onClose: () => finish(undefined),
              build: (tui, surface) => {
                surfaceRef = surface;
                return factory(tui, theme, keybindings, finish);
              },
            }),
          )
          .then((surface) => {
            surfaceRef = surface;
            if (done) surfaces.close(surface.id);
          })
          .catch(() => finish(undefined));
      });
    },
    getEditorComponent: () => state.customEditorFactory,
    setEditorComponent(factory) {
      state.customEditorFactory = typeof factory === "function" ? factory : undefined;
      if (state.customEditorFactory) void lazyLoaders.ensureKeybindings?.();
      // 终端编辑器组件无法直接替换原生手机输入框；通知 App 展示状态说明。
      hub.emit("ui.chrome", { slot: "editor", attached: Boolean(factory), supported: false });
    },
    addAutocompleteProvider(factory) {
      if (typeof factory !== "function") return;
      state.autocompleteProviders.push(factory);
      composedAutocomplete = null;
      hub.emit("ui.autocomplete", { registered: state.autocompleteProviders.length });
    },
    onTerminalInput(handler) {
      state.terminalInputHandlers.add(handler);
      hub.emit("ui.terminalInput", { registered: state.terminalInputHandlers.size });
      return () => {
        state.terminalInputHandlers.delete(handler);
        hub.emit("ui.terminalInput", { registered: state.terminalInputHandlers.size });
      };
    },

    // ---- 主题 -----------------------------------------------------------
    get theme() {
      return theme;
    },
    getAllThemes() {
      const seen = new Set(["dark", "light", "system"]);
      const out = [
        { name: "dark", path: undefined },
        { name: "light", path: undefined },
        { name: "system", path: undefined },
      ];
      try {
        const themes = sessionHooks.getResourceLoader?.()?.getThemes?.()?.themes ?? [];
        for (const t of themes) {
          const name = typeof t?.name === "string" ? t.name : null;
          if (!name || seen.has(name)) continue;
          seen.add(name);
          out.push({ name, path: t.sourcePath ?? t.path ?? undefined });
        }
      } catch {
        // ignore if resourceLoader unavailable
      }
      return out;
    },
    getTheme(name) {
      if (!name || name === theme.name || name === "app") return theme;
      try {
        const themes = sessionHooks.getResourceLoader?.()?.getThemes?.()?.themes ?? [];
        const matched = themes.find((t) => t?.name === name);
        if (matched) return matched;
      } catch {
        // ignore if resourceLoader unavailable
      }
      if (name === "dark" || name === "light" || name === "system") {
        return themeHandle?.getBuiltinTheme?.(name) ?? theme;
      }
      return undefined;
    },
    setTheme(themeOrName) {
      const name = typeof themeOrName === "string" ? themeOrName.trim() : typeof themeOrName?.name === "string" ? themeOrName.name.trim() : "";
      if (!name) return { success: false, error: "主题名不能为空" };
      const all = this.getAllThemes();
      if (typeof themeOrName === "string" && !all.some((t) => t.name === name)) {
        return { success: false, error: `未知主题：${name}` };
      }
      const sm = sessionHooks.getSettingsManager?.();
      if (!sm || typeof sm.setTheme !== "function") {
        return { success: false, error: "会话尚未就绪，无法切换主题" };
      }
      try {
        sm.setTheme(name);
        themeHandle?.setName?.(name);
        const flushed = typeof sm.flush === "function" ? sm.flush() : null;
        if (flushed && typeof flushed.then === "function") {
          flushed
            .then(() => sessionHooks.emitThemeChanged?.(name))
            .catch(() => sessionHooks.emitThemeChanged?.(name));
        } else {
          sessionHooks.emitThemeChanged?.(name);
        }
        return { success: true };
      } catch (error) {
        return { success: false, error: String(error?.message ?? error) };
      }
    },
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

  /** 将按键/输入走过所有 `onTerminalInput` 监听器，严格遵守 `{ consume, data }` 协议。 */
  function dispatchTerminalInput(rawData) {
    let data = String(rawData);
    for (const handler of state.terminalInputHandlers) {
      try {
        const res = handler(data);
        if (res?.consume) return { consumed: true, data };
        if (typeof res?.data === "string") data = res.data;
      } catch {
        // 订阅者的异常不影响 App。
      }
    }
    return { consumed: false, data };
  }

  /** 向扩展注册的 `AutocompleteProvider` 链请求补全建议（带超时保护）。 */
  async function queryAutocomplete({ lines, cursorLine, cursorCol, force = false }) {
    const provider = getAutocompleteChain();
    if (!provider) return { prefix: "", items: [] };
    const ac = new AbortController();
    let timer;
    try {
      const result = await Promise.race([
        Promise.resolve(provider.getSuggestions(lines, cursorLine, cursorCol, { signal: ac.signal, force: Boolean(force) })),
        new Promise((resolve) => {
          timer = setTimeout(() => {
            ac.abort();
            resolve("timeout");
          }, AUTOCOMPLETE_TIMEOUT_MS);
        }),
      ]);
      if (result === "timeout") return { prefix: "", items: [], error: "补全超时" };
      if (!result || !Array.isArray(result.items)) return { prefix: "", items: [] };
      return {
        prefix: typeof result.prefix === "string" ? result.prefix : "",
        items: result.items.slice(0, 50).map((i) => ({
          value: String(i.value ?? ""),
          label: String(i.label ?? i.value ?? ""),
          description: i.description ?? null,
        })),
      };
    } catch (error) {
      return { prefix: "", items: [], error: String(error?.message ?? error) };
    } finally {
      clearTimeout(timer);
      ac.abort();
    }
  }

  /** 调用扩展补全链的官方 `applyCompletion`，精确计算替换后的文本与光标位置。 */
  function applyAutocomplete({ lines, cursorLine, cursorCol, item, prefix }) {
    const provider = getAutocompleteChain();
    const safeLines = Array.isArray(lines) && lines.length > 0 ? lines.map(String) : [""];
    const safeLine = Math.max(0, Math.min(safeLines.length - 1, Math.floor(Number(cursorLine) || 0)));
    const safeCol = Math.max(0, Math.min(safeLines[safeLine].length, Math.floor(Number(cursorCol) || 0)));
    if (provider && typeof provider.applyCompletion === "function") {
      try {
        const applied = provider.applyCompletion(safeLines, safeLine, safeCol, item, String(prefix ?? ""));
        if (applied && Array.isArray(applied.lines)) {
          return {
            lines: applied.lines.map(String),
            cursorLine: Number.isFinite(Number(applied.cursorLine)) ? Math.max(0, Math.floor(Number(applied.cursorLine))) : safeLine,
            cursorCol: Number.isFinite(Number(applied.cursorCol)) ? Math.max(0, Math.floor(Number(applied.cursorCol))) : safeCol,
          };
        }
      } catch {
        // fallback below
      }
    }
    const cur = safeLines[safeLine];
    const pfx = String(prefix ?? "");
    const before = cur.slice(0, safeCol);
    const after = cur.slice(safeCol);
    const val = String(item?.value ?? "");
    const replacedBefore = pfx && before.endsWith(pfx) ? before.slice(0, before.length - pfx.length) + val : before + val;
    const nextLines = [...safeLines];
    nextLines[safeLine] = replacedBefore + after;
    return { lines: nextLines, cursorLine: safeLine, cursorCol: replacedBefore.length };
  }

  /** 按需挂载扩展注册的 `setEditorComponent`（保留手机原生输入法为默认，同时给 VimEditor 等扩展提供完整可交互表面）。 */
  async function openCustomEditor() {
    if (typeof state.customEditorFactory !== "function") {
      return { opened: false, reason: "当前没有扩展注册自定义编辑器" };
    }
    await Promise.resolve(lazyLoaders.ensureKeybindings?.());
    const sdk = sessionHooks.getSdk?.();
    const selectList =
      typeof sdk?.getSelectListTheme === "function" && themeHandle?.ensureOfficialTheme?.(sdk)
        ? sdk.getSelectListTheme()
        : {
            selectedPrefix: (s) => theme.fg("accent", s),
            selectedText: (s) => theme.fg("accent", s),
            description: (s) => theme.fg("muted", s),
            scrollInfo: (s) => theme.fg("muted", s),
            noMatch: (s) => theme.fg("muted", s),
          };
    const editorTheme = {
      borderColor: (s) => theme.fg("borderMuted", s),
      selectList,
    };
    let compRef = null;
    const syncBack = (explicitText) => {
      try {
        const next =
          typeof explicitText === "string"
            ? explicitText
            : typeof compRef?.getText === "function"
              ? String(compRef.getText())
              : null;
        if (next !== null && next !== state.editorText) {
          state.editorText = next;
          hub.emit("ui.editorText", { text: state.editorText });
        }
      } catch {
        // ignore getter failure on dispose
      }
    };
    const surface = await surfaces.mount({
      mode: "custom",
      overlay: false,
      onClose: () => syncBack(null),
      build: (tui, surf) => {
        const comp = state.customEditorFactory(tui, editorTheme, keybindings);
        compRef = comp;
        if (comp && typeof comp.setText === "function" && state.editorText) {
          try {
            comp.setText(state.editorText);
          } catch {
            // ignore initial text failure
          }
        }
        if (comp && typeof comp === "object") {
          const prevSubmit = typeof comp.onSubmit === "function" ? comp.onSubmit.bind(comp) : null;
          comp.onSubmit = (value) => {
            syncBack(typeof value === "string" ? value : null);
            prevSubmit?.(value);
            surfaces.close(surf.id);
          };
        }
        return comp;
      },
    });
    return { opened: true, surfaceId: surface.id };
  }

  return {
    context,
    state,
    keybindings,
    dispatchTerminalInput,
    queryAutocomplete,
    applyAutocomplete,
    openCustomEditor,
    setLazyLoaders(loaders) {
      lazyLoaders = loaders ?? {};
    },
    setSessionHooks(hooks) {
      sessionHooks = hooks ?? {};
    },
    setKeybindings(kb) {
      if (kb && typeof kb.matches === "function") activeKeybindings = kb;
    },
    setFooterData(fdp) {
      activeFooterData = fdp;
      if (fdp) {
        for (const [k, v] of state.statuses) fdp.setExtensionStatus?.(k, v);
        fdp.onBranchChange?.(() => {
          for (const cb of branchListeners) {
            try {
              cb();
            } catch {
              // ignore
            }
          }
          surfaces.repaintKey("footer");
        });
      }
    },
    /** 会话被替换时：扩展已经被拆掉，它们挂的一切都要收。 */
    reset() {
      surfaces.closeAll();
      hub.cancelAllAsks();
      state.statuses.clear();
      branchListeners.clear();
      activeFooterData?.clearExtensionStatuses?.();
      state.terminalInputHandlers.clear();
      state.autocompleteProviders.length = 0;
      state.customEditorFactory = undefined;
      composedAutocomplete = null;
      hub.emit("ui.autocomplete", { registered: 0 });
      hub.emit("ui.terminalInput", { registered: 0 });
    },
  };
}
