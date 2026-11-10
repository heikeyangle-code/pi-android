/**
 * 交给扩展组件的 `theme` 对象。
 *
 * pi-tui 组件写的是 `theme.fg("accent", text)` 这种语义色调用，最后得到带 ANSI 转义的字符串。
 * 原型版的 `themeStub` 对任何调用都返回空串，于是**所有带颜色的文本都变成空的**。
 * 这里做一个真能出色的实现：语义名 → RGB（App 可用 `ui.palette` 命令整体替换，随 App 主题走），
 * 输出 24 位 ANSI，App 的 ANSI 渲染器（`app.pi.rpc.Ansi`）本来就认。
 *
 * 不认识的方法不抛错：返回最后一个字符串参数（组件宁可没颜色，也不能崩）。
 */

/** 语义色的默认值（暗色）。键名取自 pi 的 theme schema 常用项。 */
export const DEFAULT_PALETTE = Object.freeze({
  text: "#d4d4d8", muted: "#a1a1aa", dim: "#71717a", accent: "#7dd3fc", border: "#3f3f46",
  borderAccent: "#7dd3fc", borderMuted: "#27272a", success: "#86efac", error: "#fca5a5",
  warning: "#fde68a", thinkingText: "#a1a1aa", userMessageText: "#e4e4e7", customMessageText: "#e4e4e7",
  customMessageLabel: "#c4b5fd", toolTitle: "#e4e4e7", toolOutput: "#a1a1aa", mdHeading: "#fde68a",
  mdLink: "#7dd3fc", mdLinkUrl: "#71717a", mdCode: "#fda4af", mdCodeBlock: "#a1a1aa",
  mdCodeBlockBorder: "#3f3f46", mdQuote: "#a1a1aa", mdQuoteBorder: "#3f3f46", mdHr: "#3f3f46",
  mdListBullet: "#7dd3fc", diffAdded: "#86efac", diffRemoved: "#fca5a5", diffContext: "#71717a",
  selectedBg: "#27272a", userMessageBg: "#18181b", customMessageBg: "#18181b", toolPendingBg: "#18181b",
  toolSuccessBg: "#14231a", toolErrorBg: "#2a1717",
});

const RESET_FG = "\x1b[39m";
const RESET_BG = "\x1b[49m";

function rgb(hex) {
  const m = /^#?([0-9a-f]{6})$/i.exec(String(hex));
  if (!m) return null;
  const n = parseInt(m[1], 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

export function createTheme(initial = DEFAULT_PALETTE) {
  let palette = { ...initial };
  const fgOf = (name) => {
    const c = rgb(palette[name]);
    return c ? `\x1b[38;2;${c[0]};${c[1]};${c[2]}m` : "";
  };
  const bgOf = (name) => {
    const c = rgb(palette[name]);
    return c ? `\x1b[48;2;${c[0]};${c[1]};${c[2]}m` : "";
  };
  const real = {
    name: "app",
    fg: (name, text) => (fgOf(name) ? `${fgOf(name)}${text}${RESET_FG}` : String(text)),
    bg: (name, text) => (bgOf(name) ? `${bgOf(name)}${text}${RESET_BG}` : String(text)),
    bold: (text) => `\x1b[1m${text}\x1b[22m`,
    italic: (text) => `\x1b[3m${text}\x1b[23m`,
    underline: (text) => `\x1b[4m${text}\x1b[24m`,
    strikethrough: (text) => `\x1b[9m${text}\x1b[29m`,
    inverse: (text) => `\x1b[7m${text}\x1b[27m`,
    getFgAnsi: (name) => fgOf(name),
    getBgAnsi: (name) => bgOf(name),
    getColorMode: () => "truecolor",
  };
  const theme = new Proxy(real, {
    get(target, prop) {
      if (prop in target) return target[prop];
      if (typeof prop === "symbol") return undefined;
      return (...args) => {
        const last = args[args.length - 1];
        return typeof last === "string" ? last : "";
      };
    },
  });
  return {
    theme,
    /** App 主题变化时调用：整体换色板，下一次渲染生效。 */
    setPalette(next) {
      palette = { ...palette, ...next };
    },
    getPalette: () => ({ ...palette }),
  };
}
