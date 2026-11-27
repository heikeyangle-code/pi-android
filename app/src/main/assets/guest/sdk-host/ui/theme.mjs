/**
 * 宿主主题桥：直接委托官方 Pi SDK 的 `sdk.Theme` 实例，并同步安装到 Pi 全局主题槽
 * (`Symbol.for("@earendil-works/pi-coding-agent:theme")`)。
 *
 * 这样：
 *  1. 扩展侧拿到的 `ctx.ui.theme` 与官方 `ToolExecutionComponent` / `CustomMessageComponent` /
 *     `renderDiff` / `highlightCode` / `getSelectListTheme` 全部共享**同一个官方 `sdk.Theme` 实例**；
 *  2. App 每次通过 `ui.palette` 推送当前主题色板（内置 Studio 浅/深、system、或任意自定义 `.json` 主题）时，
 *     宿主立即重建官方 `sdk.Theme` 并替换全局槽，不再退回硬编码 `initTheme("dark"|"light")`；
 *  3. 在 `controller.open()` 完成 `loadSdk()` 之前的极短启动窗口内，保留轻量 truecolor 兜底，
 *     既不提前加载 SDK 拖慢冷启动，也保证扩展组件永不因未知 token 崩溃。
 */
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

const THEME_KEY = Symbol.for("@earendil-works/pi-coding-agent:theme");
const THEME_KEY_OLD = Symbol.for("@mariozechner/pi-coding-agent:theme");

/** 官方 Pi 1.6.0 `theme-tokens.ts` 的 49 个前景 token。 */
const OFFICIAL_FG_TOKENS = Object.freeze([
  "accent", "border", "borderAccent", "borderMuted",
  "success", "error", "warning", "muted", "dim", "text", "thinkingText",
  "scrollbarTrack", "scrollbarThumb", "searchMatchText",
  "userMessageText", "customMessageText", "customMessageLabel",
  "toolTitle", "toolOutput",
  "mdHeading", "mdLink", "mdLinkUrl", "mdCode", "mdCodeBlock", "mdCodeBlockBorder",
  "mdQuote", "mdQuoteBorder", "mdHr", "mdListBullet",
  "toolDiffAdded", "toolDiffRemoved", "toolDiffContext",
  "syntaxComment", "syntaxKeyword", "syntaxFunction", "syntaxVariable",
  "syntaxString", "syntaxNumber", "syntaxType", "syntaxOperator", "syntaxPunctuation",
  "thinkingOff", "thinkingMinimal", "thinkingLow", "thinkingMedium",
  "thinkingHigh", "thinkingXhigh", "thinkingMax",
  "bashMode",
]);

/** 官方 Pi 1.6.0 `theme-tokens.ts` 的 7 个背景 token。 */
const OFFICIAL_BG_TOKENS = Object.freeze([
  "selectedBg", "searchMatchBg", "userMessageBg", "customMessageBg",
  "toolPendingBg", "toolSuccessBg", "toolErrorBg",
]);

const FG_TOKEN_SET = new Set(OFFICIAL_FG_TOKENS);
const BG_TOKEN_SET = new Set(OFFICIAL_BG_TOKENS);

/** 默认色板：与 Android 端 `PiPalette.Dark`（Carbon Studio 纯中性碳黑 + 柔靛蓝）逐色对齐。 */
export const DEFAULT_PALETTE = Object.freeze({
  accent: "#7c9cf8", border: "#5d89e6", borderAccent: "#6b8ef0", borderMuted: "#262930",
  success: "#52c48d", error: "#f06f78", warning: "#e5b865", muted: "#8e94a0", dim: "#6b717d",
  text: "#e8eaf0", thinkingText: "#9096a3", selectedBg: "#1e222b",
  scrollbarTrack: "#1f2229", scrollbarThumb: "#5f6572",
  searchMatchBg: "#3e2f18", searchMatchText: "#f5c878",
  userMessageBg: "#1c1f26", userMessageText: "#eef0f5",
  customMessageBg: "#171a21", customMessageText: "#c6ccd8", customMessageLabel: "#7c9cf8",
  toolPendingBg: "#15171c", toolSuccessBg: "#12211a", toolErrorBg: "#281619",
  toolTitle: "#eceef3", toolOutput: "#969ca8",
  mdHeading: "#f4f6fa", mdLink: "#78a8fa", mdLinkUrl: "#8e94a0",
  mdCode: "#a2bdf9", mdCodeBlock: "#d8dce6", mdCodeBlockBorder: "#2f333c",
  mdQuote: "#a4aab6", mdQuoteBorder: "#545a66", mdHr: "#333740", mdListBullet: "#747a87",
  toolDiffAdded: "#52c48d", toolDiffRemoved: "#f06f78", toolDiffContext: "#8e94a0",
  diffAdded: "#52c48d", diffRemoved: "#f06f78", diffContext: "#8e94a0",
  syntaxComment: "#7a808e", syntaxKeyword: "#c49df5", syntaxFunction: "#7ab3fa",
  syntaxVariable: "#6bc7ce", syntaxString: "#8ad1a6", syntaxNumber: "#e8b478",
  syntaxType: "#e5c27a", syntaxOperator: "#989eab", syntaxPunctuation: "#8e94a0",
  thinkingOff: "#555b66", thinkingMinimal: "#638296", thinkingLow: "#5292bc",
  thinkingMedium: "#658ee6", thinkingHigh: "#9280eb", thinkingXhigh: "#d768be", thinkingMax: "#f36072",
  bashMode: "#4ec28a",
  pageBg: "#0d0e11", cardBg: "#15171b", infoBg: "#231c12",
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
  max: "thinkingMax",
};

const LEGACY_TOKEN_ALIASES = {
  diffAdded: "toolDiffAdded",
  diffRemoved: "toolDiffRemoved",
  diffContext: "toolDiffContext",
};

function canonicalToken(name) {
  return LEGACY_TOKEN_ALIASES[name] ?? name;
}

function parseHexRgb(hex) {
  const m = /^#?([0-9a-f]{6})$/i.exec(String(hex ?? ""));
  if (!m) return null;
  const n = parseInt(m[1], 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

function isLightHex(hex) {
  const c = parseHexRgb(hex);
  if (!c) return false;
  return 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2] > 140;
}

function resolvePaletteAppearance(palette) {
  return isLightHex(palette.pageBg ?? palette.userMessageBg ?? palette.selectedBg) ? "light" : "dark";
}

function normalizeHex(value, fallback) {
  const c = parseHexRgb(value);
  if (c) return `#${((c[0] << 16) | (c[1] << 8) | c[2]).toString(16).padStart(6, "0")}`;
  return fallback;
}

function buildSdkThemeInstance(sdk, palette, name) {
  if (!sdk || typeof sdk.Theme !== "function") return null;
  const fgColors = {};
  const bgColors = {};
  for (const token of OFFICIAL_FG_TOKENS) {
    fgColors[token] = normalizeHex(palette[token], DEFAULT_PALETTE[token]);
  }
  for (const token of OFFICIAL_BG_TOKENS) {
    bgColors[token] = normalizeHex(palette[token], DEFAULT_PALETTE[token]);
  }
  const appearance = resolvePaletteAppearance(palette);
  return new sdk.Theme(fgColors, bgColors, "truecolor", { name, appearance });
}

function installGlobalSdkTheme(instance) {
  if (!instance) return;
  globalThis[THEME_KEY] = instance;
  globalThis[THEME_KEY_OLD] = instance;
}

function resolveThemeJsonVars(value, vars, visited = new Set()) {
  if (typeof value === "number" || value === "" || value.startsWith("#") || /^ok(lch|hsl)\(/i.test(value)) {
    return value;
  }
  if (visited.has(value) || !(value in vars)) return value;
  visited.add(value);
  return resolveThemeJsonVars(vars[value], vars, visited);
}

function loadOfficialBuiltinTheme(sdk, name) {
  if (!sdk || typeof sdk.Theme !== "function" || typeof sdk.getPackageDir !== "function") return null;
  if (name !== "dark" && name !== "light") return null;
  const pkgDir = sdk.getPackageDir();
  const candidates = [
    join(pkgDir, "dist", "modes", "interactive", "theme", `${name}.json`),
    join(pkgDir, "dist", "theme", `${name}.json`),
  ];
  const file = candidates.find((p) => existsSync(p));
  if (!file) return null;
  try {
    const raw = JSON.parse(readFileSync(file, "utf8").replace(/^\uFEFF/, ""));
    const vars = raw.vars ?? {};
    const fgColors = {};
    const bgColors = {};
    for (const [k, v] of Object.entries(raw.colors ?? {})) {
      const resolved = resolveThemeJsonVars(v, vars);
      if (BG_TOKEN_SET.has(k)) bgColors[k] = resolved;
      else if (FG_TOKEN_SET.has(k)) fgColors[k] = resolved;
    }
    return new sdk.Theme(fgColors, bgColors, "truecolor", {
      name: raw.name ?? name,
      sourcePath: file,
      appearance: raw.appearance ?? name,
    });
  } catch {
    return null;
  }
}

export function createTheme(initial = DEFAULT_PALETTE, initialName) {
  let palette = { ...DEFAULT_PALETTE, ...initial };
  let themeName =
    typeof initialName === "string" && initialName.trim()
      ? initialName.trim()
      : resolvePaletteAppearance(palette);
  let sdkRef = null;
  let officialInstance = null;
  let customInstanceOverride = null;
  let fingerprint = "";
  const builtinCache = new Map();

  const computeFingerprint = () => {
    const parts = [themeName];
    for (const token of OFFICIAL_FG_TOKENS) parts.push(palette[token] ?? "");
    for (const token of OFFICIAL_BG_TOKENS) parts.push(palette[token] ?? "");
    parts.push(palette.pageBg ?? "", palette.cardBg ?? "", palette.infoBg ?? "");
    return parts.join("|");
  };
  fingerprint = computeFingerprint();

  const syncOfficial = () => {
    if (customInstanceOverride) {
      officialInstance = customInstanceOverride;
      installGlobalSdkTheme(officialInstance);
      return officialInstance;
    }
    if (!sdkRef) return null;
    process.env.COLORTERM ||= "truecolor";
    officialInstance = buildSdkThemeInstance(sdkRef, palette, themeName);
    installGlobalSdkTheme(officialInstance);
    return officialInstance;
  };

  const resolveTokenHex = (name) => {
    const canon = canonicalToken(name);
    return palette[canon] ?? palette[name];
  };

  const fallbackFgAnsi = (name) => {
    const c = parseHexRgb(resolveTokenHex(name));
    return c ? `\x1b[38;2;${c[0]};${c[1]};${c[2]}m` : "";
  };

  const fallbackBgAnsi = (name) => {
    const c = parseHexRgb(resolveTokenHex(name));
    return c ? `\x1b[48;2;${c[0]};${c[1]};${c[2]}m` : "";
  };

  const applyFg = (name, text) => {
    const str = String(text ?? "");
    const canon = canonicalToken(name);
    if (officialInstance && FG_TOKEN_SET.has(canon)) {
      return officialInstance.fg(canon, str);
    }
    const ansi = fallbackFgAnsi(canon);
    return ansi ? `${ansi}${str}${RESET_FG}` : str;
  };

  const applyBg = (name, text) => {
    const str = String(text ?? "");
    const canon = canonicalToken(name);
    if (officialInstance && BG_TOKEN_SET.has(canon)) {
      return officialInstance.bg(canon, str);
    }
    const ansi = fallbackBgAnsi(canon);
    return ansi ? `${ansi}${str}${RESET_BG}` : str;
  };

  const real = {
    get name() {
      return officialInstance?.name ?? themeName;
    },
    get appearance() {
      return officialInstance?.appearance ?? resolvePaletteAppearance(palette);
    },
    get colors() {
      return officialInstance ? officialInstance.colors : { ...palette };
    },
    fg: applyFg,
    bg: applyBg,
    bold: (text) => (officialInstance ? officialInstance.bold(text) : `\x1b[1m${text}\x1b[22m`),
    dim: (text) => `\x1b[2m${text}\x1b[22m`,
    italic: (text) => (officialInstance ? officialInstance.italic(text) : `\x1b[3m${text}\x1b[23m`),
    underline: (text) => (officialInstance ? officialInstance.underline(text) : `\x1b[4m${text}\x1b[24m`),
    strikethrough: (text) => (officialInstance ? officialInstance.strikethrough(text) : `\x1b[9m${text}\x1b[29m`),
    inverse: (text) => (officialInstance ? officialInstance.inverse(text) : `\x1b[7m${text}\x1b[27m`),
    style: (text, opts = {}) => {
      const str = String(text ?? "");
      if (!opts || typeof opts !== "object") return str;
      if (officialInstance) {
        const nextOpts = { ...opts };
        if (typeof nextOpts.fg === "string") {
          const canon = canonicalToken(nextOpts.fg);
          if (FG_TOKEN_SET.has(canon)) nextOpts.fg = canon;
          else delete nextOpts.fg;
        }
        if (typeof nextOpts.bg === "string") {
          const canon = canonicalToken(nextOpts.bg);
          if (BG_TOKEN_SET.has(canon)) nextOpts.bg = canon;
          else delete nextOpts.bg;
        }
        return officialInstance.style(str, nextOpts);
      }
      let out = str;
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
    getFgAnsi: (name) => {
      const canon = canonicalToken(name);
      if (officialInstance && FG_TOKEN_SET.has(canon)) return officialInstance.getFgAnsi(canon);
      return fallbackFgAnsi(canon);
    },
    getBgAnsi: (name) => {
      const canon = canonicalToken(name);
      if (officialInstance && BG_TOKEN_SET.has(canon)) return officialInstance.getBgAnsi(canon);
      return fallbackBgAnsi(canon);
    },
    getThinkingBorderColor: (level) => {
      if (officialInstance) return officialInstance.getThinkingBorderColor(level);
      const token = THINKING_BORDER_TOKENS[level] ?? "thinkingOff";
      return (str) => applyFg(token, str);
    },
    getBashModeBorderColor: () =>
      officialInstance ? officialInstance.getBashModeBorderColor() : (str) => applyFg("bashMode", str),
    getColorMode: () => (officialInstance ? officialInstance.getColorMode() : "truecolor"),
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
    /** SDK 加载完成后挂载：立刻用当前色板实例化官方 `sdk.Theme` 并写入全局槽。 */
    attachSdk(sdk) {
      if (!sdk) return null;
      sdkRef = sdk;
      return syncOfficial();
    },
    /** 确保全局官方主题槽已挂载同步自 Android 的 `sdk.Theme` 实例。 */
    ensureOfficialTheme(sdk) {
      if (sdk && !sdkRef) sdkRef = sdk;
      if (officialInstance && globalThis[THEME_KEY] === officialInstance) return officialInstance;
      return syncOfficial() ?? globalThis[THEME_KEY] ?? null;
    },
    /** App 主题变化时调用：更新色板并同步重建官方 `sdk.Theme`，返回是否实际发生变化。 */
    setPalette(next, name) {
      const hadCustomOverride = customInstanceOverride !== null;
      customInstanceOverride = null;
      if (typeof name === "string" && name.trim()) themeName = name.trim();
      if (next && typeof next === "object") {
        palette = { ...palette, ...next };
        if (next.diffAdded && !next.toolDiffAdded) palette.toolDiffAdded = next.diffAdded;
        if (next.diffRemoved && !next.toolDiffRemoved) palette.toolDiffRemoved = next.diffRemoved;
        if (next.diffContext && !next.toolDiffContext) palette.toolDiffContext = next.diffContext;
        if (!name && (themeName === "dark" || themeName === "light" || themeName === "app")) {
          themeName = resolvePaletteAppearance(palette);
        }
      }
      const nextFp = computeFingerprint();
      const changed = hadCustomOverride || nextFp !== fingerprint;
      fingerprint = nextFp;
      if (sdkRef && (changed || !officialInstance || globalThis[THEME_KEY] !== officialInstance)) {
        syncOfficial();
      }
      return changed;
    },
    setName(name) {
      if (typeof name !== "string" || !name.trim()) return;
      const next = name.trim();
      const hadCustomOverride = customInstanceOverride !== null;
      customInstanceOverride = null;
      if (!hadCustomOverride && next === themeName) return;
      themeName = next;
      fingerprint = computeFingerprint();
      if (sdkRef) syncOfficial();
    },
    /** 扩展调用 `ctx.ui.setTheme(themeInstance)` 时仅替换当前运行时主题实例（与官方一致，不持久化写入 settings.json）。 */
    setCustomThemeInstance(instance) {
      if (!instance || typeof instance !== "object" || typeof instance.fg !== "function") return false;
      customInstanceOverride = instance;
      officialInstance = instance;
      if (typeof instance.name === "string" && instance.name.trim()) {
        themeName = instance.name.trim();
      }
      installGlobalSdkTheme(instance);
      fingerprint = computeFingerprint() + "|custom:" + Date.now();
      return true;
    },
    /** 按官方 `TerminalColors` 契约返回当前前景/背景 RGB，供 `tui.queryTerminalColors()` 使用。 */
    getTerminalColors() {
      const fg = parseHexRgb(palette.text ?? DEFAULT_PALETTE.text);
      const bg = parseHexRgb(palette.pageBg ?? DEFAULT_PALETTE.pageBg);
      const out = {};
      if (fg) out.foreground = { r: fg[0], g: fg[1], b: fg[2] };
      if (bg) out.background = { r: bg[0], g: bg[1], b: bg[2] };
      return out;
    },
    /** 读取官方内置 `dark`/`light`（或 `system`）的独立只读 `sdk.Theme` 实例，供 `ctx.ui.getTheme(name)` 使用。 */
    getBuiltinTheme(name) {
      if (name === themeName) return theme;
      if (builtinCache.has(name)) return builtinCache.get(name);
      if (name === "dark" || name === "light") {
        const built = loadOfficialBuiltinTheme(sdkRef, name);
        if (built) {
          builtinCache.set(name, built);
          return built;
        }
      }
      if (name === "system" && sdkRef) {
        const built = buildSdkThemeInstance(sdkRef, DEFAULT_PALETTE, "system");
        if (built) {
          builtinCache.set(name, built);
          return built;
        }
      }
      return undefined;
    },
    getPalette: () => ({ ...palette }),
  };
}
