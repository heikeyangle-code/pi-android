/**
 * 交给扩展组件的 `theme` 对象。
 *
 * pi-tui 组件写的是 `theme.fg("accent", text)` 这种语义色调用，最后得到带 ANSI 转义的字符串。
 * 原型版的 `themeStub` 对任何调用都返回空串，于是**所有带颜色的文本都变成空的**。
 * 这里做一个真能出色的实现：语义名 → RGB（App 可用 `ui.palette` 命令整体替换，随 App 主题走），
 * 输出 24 位 ANSI，App 的 ANSI 渲染器（`app.pi.protocol.Ansi`）本来就认。
 *
 * 不认识的方法不抛错：返回最后一个字符串参数（组件宁可没颜色，也不能崩）。
 */

/** 语义色的默认值（暗色）。键名对齐 Pi 1.6.0 `theme-tokens.ts` 全套 token。 */
export const DEFAULT_PALETTE = Object.freeze({
  text: "#d4d4d8", muted: "#a1a1aa", dim: "#71717a", accent: "#7dd3fc", border: "#3f3f46",
  borderAccent: "#7dd3fc", borderMuted: "#27272a", success: "#86efac", error: "#fca5a5",
  warning: "#fde68a", thinkingText: "#a1a1aa", userMessageText: "#e4e4e7", customMessageText: "#e4e4e7",
  customMessageLabel: "#c4b5fd", toolTitle: "#e4e4e7", toolOutput: "#a1a1aa", mdHeading: "#fde68a",
  mdLink: "#7dd3fc", mdLinkUrl: "#71717a", mdCode: "#fda4af", mdCodeBlock: "#a1a1aa",
  mdCodeBlockBorder: "#3f3f46", mdQuote: "#a1a1aa", mdQuoteBorder: "#3f3f46", mdHr: "#3f3f46",
  mdListBullet: "#7dd3fc",
  toolDiffAdded: "#86efac", toolDiffRemoved: "#fca5a5", toolDiffContext: "#71717a",
  diffAdded: "#86efac", diffRemoved: "#fca5a5", diffContext: "#71717a",
  selectedBg: "#27272a", userMessageBg: "#18181b", customMessageBg: "#18181b", toolPendingBg: "#18181b",
  toolSuccessBg: "#14231a", toolErrorBg: "#2a1717",
  bashMode: "#86efac",
  thinkingOff: "#52525b", thinkingMinimal: "#71717a", thinkingLow: "#7dd3fc",
  thinkingMedium: "#93c5fd", thinkingHigh: "#c4b5fd", thinkingXhigh: "#f0abfc",
  syntaxComment: "#71717a", syntaxKeyword: "#c4b5fd", syntaxFunction: "#7dd3fc",
  syntaxVariable: "#e4e4e7", syntaxString: "#86efac", syntaxNumber: "#fde68a",
  syntaxType: "#93c5fd", syntaxOperator: "#a1a1aa", syntaxPunctuation: "#a1a1aa",
});

const RESET_FG = "\x1b[39m";
const RESET_BG = "\x1b[49m";

const THINKING_BORDER_TOKENS = {
  off: "thinkingOff",
  minimal: "thinkingMinimal",
  low: "thinkingLow",
  medium: "thinkingMedium",
  high: "thinkingHigh",
  xhigh: "thinkingXhigh",
};

function rgb(hex) {
  const m = /^#?([0-9a-f]{6})$/i.exec(String(hex));
  if (!m) return null;
  const n = parseInt(m[1], 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

function isLightHex(hex) {
  const c = rgb(hex);
  if (!c) return false;
  return (0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]) > 140;
}

export function createTheme(initial = DEFAULT_PALETTE) {
  let palette = { ...DEFAULT_PALETTE, ...initial };
  const resolveToken = (name) => {
    if (palette[name]) return palette[name];
    if (name === "toolDiffAdded") return palette.diffAdded;
    if (name === "toolDiffRemoved") return palette.diffRemoved;
    if (name === "toolDiffContext") return palette.diffContext;
    if (name === "diffAdded") return palette.toolDiffAdded;
    if (name === "diffRemoved") return palette.toolDiffRemoved;
    if (name === "diffContext") return palette.toolDiffContext;
    return undefined;
  };
  const fgOf = (name) => {
    const c = rgb(resolveToken(name));
    return c ? `\x1b[38;2;${c[0]};${c[1]};${c[2]}m` : "";
  };
  const bgOf = (name) => {
    const c = rgb(resolveToken(name));
    return c ? `\x1b[48;2;${c[0]};${c[1]};${c[2]}m` : "";
  };
  const applyFg = (name, text) => {
    const ansi = fgOf(name);
    return ansi ? `${ansi}${text}${RESET_FG}` : String(text);
  };
  const applyBg = (name, text) => {
    const ansi = bgOf(name);
    return ansi ? `${ansi}${text}${RESET_BG}` : String(text);
  };
  const real = {
    name: "app",
    get appearance() {
      return isLightHex(palette.userMessageBg ?? palette.selectedBg) ? "light" : "dark";
    },
    get colors() {
      return { ...palette };
    },
    fg: applyFg,
    bg: applyBg,
    bold: (text) => `\x1b[1m${text}\x1b[22m`,
    dim: (text) => `\x1b[2m${text}\x1b[22m`,
    italic: (text) => `\x1b[3m${text}\x1b[23m`,
    underline: (text) => `\x1b[4m${text}\x1b[24m`,
    strikethrough: (text) => `\x1b[9m${text}\x1b[29m`,
    inverse: (text) => `\x1b[7m${text}\x1b[27m`,
    style: (text, opts = {}) => {
      let out = String(text ?? "");
      if (!opts || typeof opts !== "object") return out;
      if (opts.bold) out = `\x1b[1m${out}\x1b[22m`;
      if (opts.dim) out = `\x1b[2m${out}\x1b[22m`;
      if (opts.italic) out = `\x1b[3m${out}\x1b[23m`;
      if (opts.underline) out = `\x1b[4m${out}\x1b[24m`;
      if (opts.strikethrough) out = `\x1b[9m${out}\x1b[29m`;
      if (opts.inverse) out = `\x1b[7m${out}\x1b[27m`;
      if (opts.fg) out = applyFg(opts.fg, out);
      if (opts.bg) out = applyBg(opts.bg, out);
      return out;
    },
    getFgAnsi: (name) => fgOf(name),
    getBgAnsi: (name) => bgOf(name),
    getThinkingBorderColor: (level) => {
      const token = THINKING_BORDER_TOKENS[level] ?? "borderMuted";
      return (str) => applyFg(token, str);
    },
    getBashModeBorderColor: () => (str) => applyFg("bashMode", str),
    getColorMode: () => "truecolor",
  };
  const theme = new Proxy(real, {
    get(target, prop, receiver) {
      if (prop in target) return Reflect.get(target, prop, receiver);
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
      if (!next || typeof next !== "object") return;
      palette = { ...palette, ...next };
      // 双向兼容 diffAdded / toolDiffAdded 键名
      if (next.diffAdded && !next.toolDiffAdded) palette.toolDiffAdded = next.diffAdded;
      if (next.diffRemoved && !next.toolDiffRemoved) palette.toolDiffRemoved = next.diffRemoved;
      if (next.diffContext && !next.toolDiffContext) palette.toolDiffContext = next.diffContext;
    },
    getPalette: () => ({ ...palette }),
  };
}
