/**
 * 「表面」：一个扩展组件（`Component`）在 App 里的投影。
 *
 * 对照 Pi 官方 `packages/tui/src/tui.ts` 与 `packages/tui/src/terminal.ts`：
 *  - 组件渲染 `render(width) → string[]`，键盘输入 `handleInput(data)`，鼠标事件 `handleMouse(event)`；
 *  - 支持 `tui.requestRender()` / `tui.renderNow(force)`、`tui.setFocus()`、`tui.addInputListener()` / `removeInputListener()`、
 *    `tui.showOverlay()` / `hideOverlay()` / `hasOverlay()`、`tui.terminal.write()` / `setTitle()` / `setProgress()`；
 *  - 维护 `Focusable.focused` 属性（`Input`/`Editor` 等组件靠它决定是否画光标、接输入）；
 *  - 渲染输出剥离 `CURSOR_MARKER`（`\x1b_pi:c\x07`），将 `FAKE_CURSOR`（`\x1b_pi:fc\x07...\x1b_pi:/fc\x07`）
 *    转为反白 SGR（`\x1b[7m...\x1b[27m`），同时捕获独立 BEL（`\x07`）并发出 `ui.bell` 提示音事件；
 *  - 捕获扩展通过 `tui.terminal.write()` 或 `process.stdout.write()` 发出的 BEL（`\x07`）、OSC 777/99/9 通知、
 *    OSC 0/2 标题与 OSC 9;4 进度指示，分别转为 `ui.bell` / `ui.notify` / `ui.title` / `ui.workingVisible`；
 *  - 完整支持 `OverlayHandle`（`hide` / `setHidden` / `isHidden` / `focus` / `unfocus` / `isFocused` / `getBounds`）
 *    与 `OverlayOptions`（`visible(w, h)` / `nonCapturing` / 尺寸与锚点）。
 */

import { randomUUID } from "node:crypto";

// 默认按标准 80×24 终端启动，避免首帧在 App 回传 `ui.resize` 前被压成 40 列导致截断。
const DEFAULT_WIDTH = 80;
const DEFAULT_HEIGHT = 24;
const REPAINT_DELAY_MS = 16;
const BELL_THROTTLE_MS = 60;

// 与 `packages/tui/src/tui.ts` 一致的零宽光标标记。
const CURSOR_MARKER_RE = /\x1b_pi:c\x07/g;
const FAKE_CURSOR_RE = /\x1b_pi:fc\x07(.*?)(?:\x1b_pi:\/fc\x07|$)/gs;

/**
 * 扫描原始字符串中的独立 BEL（`\x07`）：排除作为 OSC (`\x1b]...`) 或 APC (`\x1b_...`) 终止符的 `\x07`。
 * 返回 `{ text, bellCount }`，其中 `text` 已移除独立 `\x07`（避免在客户端渲染出缺字方块）。
 */
function stripStandaloneBell(s) {
  if (!s.includes("\x07")) return { text: s, bellCount: 0 };
  let out = "";
  let bellCount = 0;
  let i = 0;
  while (i < s.length) {
    const ch = s.charCodeAt(i);
    if (ch === 0x1b && i + 1 < s.length && (s[i + 1] === "]" || s[i + 1] === "_")) {
      // OSC 或 APC 序列：保留到 BEL 或 ST (`\x1b\\`)，其中的 BEL 是终止符而非响铃。
      const start = i;
      i += 2;
      while (i < s.length) {
        if (s.charCodeAt(i) === 0x07) {
          i += 1;
          break;
        }
        if (s.charCodeAt(i) === 0x1b && i + 1 < s.length && s[i + 1] === "\\") {
          i += 2;
          break;
        }
        i += 1;
      }
      out += s.slice(start, i);
      continue;
    }
    if (ch === 0x07) {
      bellCount += 1;
      i += 1;
      continue;
    }
    out += s[i];
    i += 1;
  }
  return { text: out, bellCount };
}

export function sanitizeRenderedLine(raw, onBell) {
  let s = String(raw);
  if (s.includes("\x1b_pi:")) {
    s = s
      .replace(CURSOR_MARKER_RE, "")
      .replace(FAKE_CURSOR_RE, (_m, inner) => `\x1b[7m${inner || " "}\x1b[27m`);
  }
  if (s.includes("\x07")) {
    const { text, bellCount } = stripStandaloneBell(s);
    if (bellCount > 0) onBell?.("render");
    s = text;
  }
  return s;
}

/**
 * 解析扩展直接写入终端的控制序列（`tui.terminal.write(...)` 或 `process.stdout.write(...)`）：
 *  - OSC 777: `\x1b]777;notify;<title>;<body>(\x07|\x1b\\)` → 通知 + 提示音（官方 `notify.ts`）
 *  - OSC 99:  Kitty 通知 `\x1b]99;...;<text>(\x07|\x1b\\)` → 通知 + 提示音
 *  - OSC 9;4: 终端进度条 `\x1b]9;4;<state>(;<pct>)?(\x07|\x1b\\)` → workingVisible
 *  - OSC 9:   iTerm2 通知 `\x1b]9;<message>(\x07|\x1b\\)` → 通知 + 提示音
 *  - OSC 0/2: 窗口标题 `\x1b](0|2);<title>(\x07|\x1b\\)` → ui.title
 *  - 独立 `\x07` BEL → ui.bell
 */
export function inspectTerminalOutput(raw, handlers) {
  const s = String(raw ?? "");
  if (!s) return;
  let i = 0;
  let sawBell = false;
  while (i < s.length) {
    const ch = s.charCodeAt(i);
    if (ch === 0x1b && i + 1 < s.length && (s[i + 1] === "]" || s[i + 1] === "_")) {
      const isOsc = s[i + 1] === "]";
      const bodyStart = i + 2;
      i = bodyStart;
      let bodyEnd = s.length;
      while (i < s.length) {
        if (s.charCodeAt(i) === 0x07) {
          bodyEnd = i;
          i += 1;
          break;
        }
        if (s.charCodeAt(i) === 0x1b && i + 1 < s.length && s[i + 1] === "\\") {
          bodyEnd = i;
          i += 2;
          break;
        }
        i += 1;
      }
      if (!isOsc) continue;
      const body = s.slice(bodyStart, bodyEnd);
      if (body.startsWith("0;") || body.startsWith("2;")) {
        handlers?.onTitle?.(body.slice(2));
      } else if (body.startsWith("777;notify;")) {
        const rest = body.slice("777;notify;".length);
        const sep = rest.indexOf(";");
        const title = sep >= 0 ? rest.slice(0, sep) : rest;
        const msg = sep >= 0 ? rest.slice(sep + 1) : "";
        const combined = [title, msg].filter(Boolean).join("：") || "通知";
        handlers?.onNotify?.(combined, "info");
        sawBell = true;
      } else if (body.startsWith("99;")) {
        const sep = body.indexOf(";", 3);
        const text = sep >= 0 ? body.slice(sep + 1) : "";
        if (text) {
          handlers?.onNotify?.(text, "info");
          sawBell = true;
        }
      } else if (body.startsWith("9;4;")) {
        const stateCode = Number(body.slice(4).split(";")[0]);
        if (Number.isFinite(stateCode)) handlers?.onProgress?.(stateCode !== 0);
      } else if (body.startsWith("9;")) {
        const text = body.slice(2);
        if (text) {
          handlers?.onNotify?.(text, "info");
          sawBell = true;
        }
      }
      continue;
    }
    if (ch === 0x07) {
      sawBell = true;
    }
    i += 1;
  }
  if (sawBell) handlers?.onBell?.();
}

function setComponentFocus(component, focused) {
  if (!component || typeof component !== "object") return;
  if ("focused" in component) {
    try {
      component.focused = Boolean(focused);
    } catch {
      // 只读 focused 属性不阻塞渲染。
    }
  }
}

function resolveOverlayOptions(opts, termWidth, termHeight) {
  if (!opts) return { options: null, visible: true, nonCapturing: false };
  try {
    const raw = typeof opts === "function" ? opts() : opts;
    if (!raw || typeof raw !== "object") return { options: null, visible: true, nonCapturing: false };
    let visible = true;
    if (typeof raw.visible === "function") {
      try {
        visible = Boolean(raw.visible(termWidth, termHeight));
      } catch {
        visible = true;
      }
    }
    const nonCapturing = Boolean(raw.nonCapturing);
    const out = {};
    for (const k of ["width", "minWidth", "maxHeight", "anchor", "margin", "offsetX", "offsetY", "row", "col", "nonCapturing"]) {
      if (raw[k] !== undefined) out[k] = raw[k];
    }
    return { options: Object.keys(out).length ? out : null, visible, nonCapturing };
  } catch {
    return { options: null, visible: true, nonCapturing: false };
  }
}

export class Surfaces {
  constructor(hub, theme) {
    this.hub = hub;
    this.theme = theme;
    /** @type {Map<string, any>} surfaceId → surface */
    this.byId = new Map();
    /** @type {Map<string, string>} key → surfaceId */
    this.byKey = new Map();
    this.width = DEFAULT_WIDTH;
    this.height = DEFAULT_HEIGHT;
    this.focusedId = null;
    this.lastBellAt = 0;
  }

  /** 向 App 发送一次终端响铃事件（带 60ms 节流，防止游戏高频触发刷爆协议流）。 */
  emitBell(source = "terminal") {
    const now = Date.now();
    if (now - this.lastBellAt < BELL_THROTTLE_MS) return;
    this.lastBellAt = now;
    this.hub.emit("ui.bell", { source, ts: now });
  }

  /** 处理来自 `tui.terminal.write` 或 `process.stdout.write` 的终端控制序列。 */
  handleTerminalOutput(data, source = "terminal") {
    inspectTerminalOutput(data, {
      onBell: () => this.emitBell(source),
      onNotify: (message, tone) => this.hub.emit("ui.notify", { message, tone }),
      onTitle: (title) => this.hub.emit("ui.title", { title }),
      onProgress: (visible) => this.hub.emit("ui.workingVisible", { visible }),
    });
  }

  /**
   * 挂载一个组件。`build(tui, surface)` 返回组件（custom() 允许返回 Promise）。
   * @returns {Promise<any>} surface
   */
  async mount({ mode, key, overlay = false, overlayOptions = null, blocked = null, build, onClose, onHandle }) {
    if (key) this.closeKey(key);
    const surface = {
      id: randomUUID(),
      mode,
      key: key ?? null,
      overlay,
      overlayOptions,
      blocked,
      hidden: false,
      focused: false,
      component: null,
      rev: 0,
      timer: null,
      closed: false,
      onClose,
      childOverlays: new Set(),
      inputListeners: new Set(),
      lastLineCount: 0,
      fullRedraws: 0,
      showHardwareCursor: false,
      clearOnShrink: false,
    };
    const surfaces = this;

    const handle = {
      hide() {
        surfaces.close(surface.id);
      },
      setHidden(hidden) {
        if (surface.closed) return;
        const next = Boolean(hidden);
        if (surface.hidden === next) return;
        surface.hidden = next;
        if (next && surfaces.focusedId === surface.id) {
          surfaces.#setFocusId(null);
        } else if (!next && surface.mode === "custom") {
          surfaces.#setFocusId(surface.id);
        }
        surfaces.paint(surface);
      },
      isHidden: () => surface.closed || surface.hidden,
      focus() {
        if (surface.closed) return;
        surface.hidden = false;
        surfaces.#setFocusId(surface.id);
        surfaces.paint(surface);
      },
      unfocus() {
        if (surfaces.focusedId === surface.id) {
          surfaces.#setFocusId(null);
          surfaces.paint(surface);
        }
      },
      isFocused: () => !surface.closed && !surface.hidden && surfaces.focusedId === surface.id,
      getBounds: () => ({
        row: 0,
        col: 0,
        width: surfaces.width,
        height: surface.lastLineCount || surfaces.height,
      }),
    };
    surface.handle = handle;

    const terminal = {
      get columns() {
        return surfaces.width;
      },
      get rows() {
        return surfaces.height;
      },
      get kittyProtocolActive() {
        return false;
      },
      write(data) {
        surfaces.handleTerminalOutput(data, "terminal");
      },
      setTitle(title) {
        surfaces.hub.emit("ui.title", { title: String(title ?? "") });
      },
      setProgress(active) {
        surfaces.hub.emit("ui.workingVisible", { visible: Boolean(active) });
      },
      hideCursor() {},
      showCursor() {},
      clearLine() {},
      clearFromCursor() {},
      clearScreen() {},
      moveBy() {},
      start() {},
      stop() {},
      drainInput: () => Promise.resolve(),
    };

    const tui = {
      mode: "regular",
      get fullRedraws() {
        return surface.fullRedraws;
      },
      requestRender(force = false) {
        if (force) {
          surface.lastFrameKey = null;
          surfaces.paint(surface);
        } else {
          surfaces.#schedule(surface);
        }
      },
      renderNow(force = false) {
        if (force) surface.lastFrameKey = null;
        surfaces.paint(surface);
      },
      setFocus(component) {
        if (surface.closed) return;
        if (component === surface.component) {
          surfaces.#setFocusId(surface.id);
        } else if (component && typeof component === "object" && "focused" in component) {
          setComponentFocus(component, true);
        }
      },
      addInputListener(listener) {
        if (typeof listener !== "function") return () => {};
        surface.inputListeners.add(listener);
        return () => surface.inputListeners.delete(listener);
      },
      removeInputListener(listener) {
        surface.inputListeners.delete(listener);
      },
      getShowHardwareCursor: () => surface.showHardwareCursor,
      setShowHardwareCursor(enabled) {
        surface.showHardwareCursor = Boolean(enabled);
      },
      getClearOnShrink: () => surface.clearOnShrink,
      setClearOnShrink(enabled) {
        surface.clearOnShrink = Boolean(enabled);
      },
      onTerminalColorSchemeChange: () => () => {},
      queryTerminalColors: () => Promise.resolve(null),
      showOverlay(component, opts) {
        // 允许组件内部通过 `tui.showOverlay(child, opts)` 叠加二级浮层，关闭父表面时一并清理。
        let childHandle = null;
        let childId = null;
        const placeholder = {
          hide: () => {
            if (childHandle) childHandle.hide();
          },
          setHidden: (h) => childHandle?.setHidden(h),
          isHidden: () => (childHandle ? childHandle.isHidden() : true),
          focus: () => childHandle?.focus(),
          unfocus: () => childHandle?.unfocus(),
          isFocused: () => (childHandle ? childHandle.isFocused() : false),
          getBounds: () => (childHandle ? childHandle.getBounds() : { row: 0, col: 0, width: surfaces.width, height: surfaces.height }),
        };
        void surfaces
          .mount({
            mode: "custom",
            overlay: true,
            overlayOptions: opts ?? null,
            build: () => component,
            onClose: () => {
              if (childId) surface.childOverlays.delete(childId);
            },
          })
          .then((child) => {
            childId = child.id;
            childHandle = child.handle;
            if (surface.closed) {
              surfaces.close(child.id);
            } else {
              surface.childOverlays.add(child.id);
            }
          });
        return placeholder;
      },
      hideOverlay() {
        const last = [...surface.childOverlays].pop();
        if (last) surfaces.close(last);
      },
      hasOverlay: () => surface.childOverlays.size > 0,
      start() {},
      stop() {},
      get terminal() {
        return terminal;
      },
      get columns() {
        return surfaces.width;
      },
      get rows() {
        return surfaces.height;
      },
    };

    surface.component = await build(tui, surface);
    if (surface.closed) {
      // build 期间（例如 custom 的 done() 同步调用）已经被关掉了。
      surface.component?.dispose?.();
      return surface;
    }
    this.byId.set(surface.id, surface);
    if (key) this.byKey.set(key, surface.id);
    const resolvedOpts = resolveOverlayOptions(surface.overlayOptions, this.width, this.height);
    if (mode === "custom" && !resolvedOpts.nonCapturing) {
      this.#setFocusId(surface.id);
    }
    if (typeof onHandle === "function") {
      try {
        onHandle(handle);
      } catch (error) {
        process.stderr.write(`[host] onHandle failed: ${error?.stack ?? error}\n`);
      }
    }
    if (!surface.closed) this.paint(surface);
    return surface;
  }

  #setFocusId(nextId) {
    if (this.focusedId === nextId) return;
    if (this.focusedId) {
      const prev = this.byId.get(this.focusedId);
      if (prev) {
        prev.focused = false;
        setComponentFocus(prev.component, false);
      }
    }
    this.focusedId = nextId;
    if (nextId) {
      const curr = this.byId.get(nextId);
      if (curr) {
        curr.focused = true;
        setComponentFocus(curr.component, true);
      }
    }
  }

  /** 按 key 触发一次重绘（例如 footerData 变更时刷新 footer 表面）。 */
  repaintKey(key) {
    const id = this.byKey.get(key);
    const surface = id ? this.byId.get(id) : null;
    if (surface && !surface.closed) this.#schedule(surface);
  }

  paint(surface) {
    if (surface.closed) return;
    let lines;
    const onBell = (src) => this.emitBell(src);
    try {
      const raw = surface.component.render(this.width) ?? [];
      lines = Array.isArray(raw) ? raw.map((line) => sanitizeRenderedLine(line, onBell)) : [];
    } catch (error) {
      lines = [`[render failed] ${error?.message ?? error}`];
    }
    surface.lastLineCount = lines.length;
    const { options: overlayOptions, visible } = resolveOverlayOptions(surface.overlayOptions, this.width, this.height);
    const effectiveHidden = surface.hidden || !visible;
    const interactive = typeof surface.component.handleInput === "function" || typeof surface.component.handleMouse === "function";
    const frameKey = JSON.stringify([
      surface.overlay,
      overlayOptions,
      effectiveHidden,
      surface.focused,
      surface.blocked ?? null,
      interactive,
      this.width,
      lines,
    ]);
    if (surface.lastFrameKey === frameKey) return;
    surface.lastFrameKey = frameKey;
    surface.fullRedraws += 1;
    this.hub.emit("ui.surface", {
      surfaceId: surface.id,
      key: surface.key,
      mode: surface.mode,
      overlay: surface.overlay,
      overlayOptions,
      hidden: effectiveHidden,
      focused: surface.focused,
      blocked: surface.blocked ?? null,
      rev: ++surface.rev,
      interactive,
      width: this.width,
      lines,
    });
  }

  #schedule(surface) {
    if (surface.closed || surface.timer) return;
    surface.timer = setTimeout(() => {
      surface.timer = null;
      this.paint(surface);
    }, REPAINT_DELAY_MS);
  }

  close(id) {
    const surface = this.byId.get(id);
    if (!surface) return false;
    this.#finish(surface);
    return true;
  }

  closeKey(key) {
    const id = this.byKey.get(key);
    return id ? this.close(id) : false;
  }

  closeAll() {
    for (const surface of [...this.byId.values()]) this.#finish(surface);
  }

  #finish(surface) {
    if (surface.closed) return;
    surface.closed = true;
    if (surface.timer) clearTimeout(surface.timer);
    for (const childId of [...surface.childOverlays]) this.close(childId);
    surface.childOverlays.clear();
    surface.inputListeners.clear();
    this.byId.delete(surface.id);
    if (surface.key && this.byKey.get(surface.key) === surface.id) this.byKey.delete(surface.key);
    if (this.focusedId === surface.id) {
      surface.focused = false;
      setComponentFocus(surface.component, false);
      // 恢复上一个可见 custom surfaces 的焦点。
      const fallback = [...this.byId.values()].reverse().find((s) => s.mode === "custom" && !s.hidden && !s.closed);
      this.focusedId = null;
      if (fallback) {
        this.#setFocusId(fallback.id);
        this.paint(fallback);
      }
    }
    try {
      surface.component?.dispose?.();
    } catch {
      // 扩展的 dispose 抛错不应影响关闭。
    }
    this.hub.emit("ui.close", { surfaceId: surface.id, key: surface.key, mode: surface.mode });
    // 拥有者（例如 custom() 的 Promise）要知道自己被关了，否则从 App 侧关掉后它会永远挂着。
    try {
      surface.onClose?.();
    } catch {
      // 同上：拥有者的异常不影响关闭。
    }
  }

  /** App → 组件键盘输入。先过 `tui.addInputListener` 链，再交给 `component.handleInput`。 */
  input(id, data) {
    const surface = this.byId.get(id);
    if (!surface || surface.closed) return false;
    let payload = String(data);
    for (const listener of surface.inputListeners) {
      try {
        const res = listener(payload);
        if (res?.consume) {
          if (!surface.closed) this.paint(surface);
          return true;
        }
        if (typeof res?.data === "string") payload = res.data;
      } catch (error) {
        process.stderr.write(`[host] inputListener failed: ${error?.stack ?? error}\n`);
      }
    }
    if (typeof surface.component?.handleInput !== "function") return false;
    if (surface.mode === "custom" && this.focusedId !== surface.id) {
      this.#setFocusId(surface.id);
    }
    try {
      surface.component.handleInput(payload);
    } catch (error) {
      process.stderr.write(`[host] handleInput failed: ${error?.stack ?? error}\n`);
    }
    if (!surface.closed) this.paint(surface);
    return true;
  }

  /** App → 组件鼠标/点击事件（`Component.handleMouse`）。 */
  mouse(id, rawEvent) {
    const surface = this.byId.get(id);
    if (!surface || surface.closed || typeof surface.component?.handleMouse !== "function") return false;
    const x = Math.max(0, Math.floor(Number(rawEvent?.x ?? 0)));
    const y = Math.max(0, Math.floor(Number(rawEvent?.y ?? 0)));
    const event = {
      type: typeof rawEvent?.type === "string" ? rawEvent.type : "click",
      button: typeof rawEvent?.button === "string" ? rawEvent.button : "left",
      x,
      y,
      screenX: x,
      screenY: y,
      width: Math.max(1, Math.floor(Number(rawEvent?.width ?? this.width))),
      height: Math.max(1, Math.floor(Number(rawEvent?.height ?? this.height))),
      shift: Boolean(rawEvent?.shift),
      alt: Boolean(rawEvent?.alt),
      ctrl: Boolean(rawEvent?.ctrl),
      ...(Number.isFinite(rawEvent?.wheelDelta) ? { wheelDelta: Number(rawEvent.wheelDelta) } : {}),
      ...(Number.isFinite(rawEvent?.clickCount) ? { clickCount: Number(rawEvent.clickCount) } : { clickCount: 1 }),
    };
    let result;
    try {
      result = surface.component.handleMouse(event);
    } catch (error) {
      process.stderr.write(`[host] handleMouse failed: ${error?.stack ?? error}\n`);
      return false;
    }
    const handled = result === undefined ? true : Boolean(result && (result.handled || result.focus || result.capture || "target" in result));
    if (result?.focus && surface.mode === "custom") this.#setFocusId(surface.id);
    if (!surface.closed && result?.render !== false) this.paint(surface);
    return handled;
  }

  /** App 旋转 / 改字号。全部表面按新宽度重绘。 */
  resize(width, height) {
    if (Number.isFinite(width) && width > 0) this.width = Math.floor(width);
    if (Number.isFinite(height) && height > 0) this.height = Math.floor(height);
    for (const surface of this.byId.values()) {
      surface.component?.invalidate?.();
      this.paint(surface);
    }
  }
}
