/**
 * 「表面」：一个扩展组件（`Component`）在 App 里的投影。
 *
 * 对照 Pi 官方 `packages/tui/src/tui.ts`：
 *  - 组件渲染 `render(width) → string[]`，键盘输入 `handleInput(data)`，鼠标点击 `handleMouse(event)`；
 *  - 支持 `tui.requestRender()` 定时/动画重绘、`tui.setFocus()`、`tui.showOverlay()` / `hideOverlay()` / `hasOverlay()`；
 *  - 维护 `Focusable.focused` 属性（`Input`/`Editor` 等组件靠它决定是否画光标、接输入）；
 *  - 渲染输出剥离 `CURSOR_MARKER`（`\x1b_pi:c\x07`），将 `FAKE_CURSOR`（`\x1b_pi:fc\x07...\x1b_pi:/fc\x07`）
 *    转为反白 SGR（`\x1b[7m...\x1b[27m`），避免控制序列泄漏并在手机上清晰显示光标；
 *  - 支持 `OverlayHandle`（`hide` / `setHidden` / `isHidden` / `focus` / `unfocus` / `isFocused`），
 *    供 `ctx.ui.custom(factory, { overlay: true, onHandle })` 完整控制浮层显隐与焦点。
 */

import { randomUUID } from "node:crypto";

const DEFAULT_WIDTH = 40;
const DEFAULT_HEIGHT = 24;
const REPAINT_DELAY_MS = 16;

// 与 `packages/tui/src/tui.ts` 一致的零宽光标标记。
const CURSOR_MARKER_RE = /\x1b_pi:c\x07/g;
const FAKE_CURSOR_RE = /\x1b_pi:fc\x07(.*?)(?:\x1b_pi:\/fc\x07|$)/gs;

function sanitizeRenderedLine(raw) {
  const s = String(raw);
  if (!s.includes("\x1b_pi:")) return s;
  return s
    .replace(CURSOR_MARKER_RE, "")
    .replace(FAKE_CURSOR_RE, (_m, inner) => `\x1b[7m${inner || " "}\x1b[27m`);
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

function resolveOverlayOptions(opts) {
  if (!opts) return null;
  try {
    const raw = typeof opts === "function" ? opts() : opts;
    if (!raw || typeof raw !== "object") return null;
    const out = {};
    for (const k of ["width", "minWidth", "maxHeight", "anchor", "margin"]) {
      if (raw[k] !== undefined) out[k] = raw[k];
    }
    return Object.keys(out).length ? out : null;
  } catch {
    return null;
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
    };
    surface.handle = handle;

    const tui = {
      requestRender() {
        surfaces.#schedule(surface);
      },
      setFocus(component) {
        if (surface.closed) return;
        if (component === surface.component) {
          surfaces.#setFocusId(surface.id);
        } else if (component && typeof component === "object" && "focused" in component) {
          setComponentFocus(component, true);
        }
      },
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
        return { columns: surfaces.width, rows: surfaces.height };
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
    if (mode === "custom") {
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
    try {
      const raw = surface.component.render(this.width) ?? [];
      lines = Array.isArray(raw) ? raw.map(sanitizeRenderedLine) : [];
    } catch (error) {
      lines = [`[render failed] ${error?.message ?? error}`];
    }
    this.hub.emit("ui.surface", {
      surfaceId: surface.id,
      key: surface.key,
      mode: surface.mode,
      overlay: surface.overlay,
      overlayOptions: resolveOverlayOptions(surface.overlayOptions),
      hidden: surface.hidden,
      focused: surface.focused,
      blocked: surface.blocked ?? null,
      rev: ++surface.rev,
      interactive: typeof surface.component.handleInput === "function" || typeof surface.component.handleMouse === "function",
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

  /** App → 组件键盘输入。返回是否有组件接了这次输入。 */
  input(id, data) {
    const surface = this.byId.get(id);
    if (!surface || surface.closed || typeof surface.component?.handleInput !== "function") return false;
    if (surface.mode === "custom" && this.focusedId !== surface.id) {
      this.#setFocusId(surface.id);
    }
    try {
      surface.component.handleInput(String(data));
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
