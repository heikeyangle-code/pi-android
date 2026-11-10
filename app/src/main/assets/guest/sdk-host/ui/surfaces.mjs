/**
 * 「表面」：一个扩展组件（`Component`）在 App 里的投影。
 *
 * 组件只有两个方法对我们有意义：`render(width) → string[]` 和 `handleInput(data)`。宿主按 App
 * 给的**字符宽度**渲染，把行（可含 ANSI）发给 App；App 把按键/文本作为 `ui.input` 发回来，
 * 宿主喂给 `handleInput` 后重绘。组件自己调 `tui.requestRender()` 时（动画、定时刷新）也会重绘 ——
 * 原型版把它写成空函数，所有会自己动的组件在手机上都是冻住的。
 *
 * 一个表面有稳定的 `key`（widget 用扩展给的 key；footer/header 用固定名；custom 用随机 id）。
 * 同一个 key 再挂一个组件，会先 dispose 并关闭旧的 —— 原型版会泄漏旧组件的定时器。
 */

import { randomUUID } from "node:crypto";

const DEFAULT_WIDTH = 40;
const DEFAULT_HEIGHT = 24;
const REPAINT_DELAY_MS = 16;

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
  }

  /**
   * 挂载一个组件。`build(tui)` 返回组件（custom() 允许返回 Promise）。
   * @returns {Promise<any>} surface
   */
  async mount({ mode, key, overlay = false, build }) {
    if (key) this.closeKey(key);
    const surface = { id: randomUUID(), mode, key: key ?? null, overlay, component: null, rev: 0, timer: null, closed: false };
    const surfaces = this;
    const tui = {
      requestRender() {
        surfaces.#schedule(surface);
      },
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
    this.paint(surface);
    return surface;
  }

  paint(surface) {
    if (surface.closed) return;
    let lines;
    try {
      lines = surface.component.render(this.width) ?? [];
    } catch (error) {
      lines = [`[render failed] ${error?.message ?? error}`];
    }
    this.hub.emit("ui.surface", {
      surfaceId: surface.id,
      key: surface.key,
      mode: surface.mode,
      overlay: surface.overlay,
      rev: ++surface.rev,
      interactive: typeof surface.component.handleInput === "function",
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
    surface.closed = true;
    if (surface.timer) clearTimeout(surface.timer);
    this.byId.delete(surface.id);
    if (surface.key && this.byKey.get(surface.key) === surface.id) this.byKey.delete(surface.key);
    try {
      surface.component?.dispose?.();
    } catch {
      // 扩展的 dispose 抛错不应影响关闭。
    }
    this.hub.emit("ui.close", { surfaceId: surface.id, key: surface.key, mode: surface.mode });
  }

  /** App → 组件。返回是否有组件接了这次输入。 */
  input(id, data) {
    const surface = this.byId.get(id);
    if (!surface || typeof surface.component?.handleInput !== "function") return false;
    try {
      surface.component.handleInput(String(data));
    } catch (error) {
      process.stderr.write(`[host] handleInput failed: ${error?.stack ?? error}\n`);
    }
    this.paint(surface);
    return true;
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
