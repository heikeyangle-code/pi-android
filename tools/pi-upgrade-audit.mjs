#!/usr/bin/env node
/**
 * 上游 Pi 升级影响自动审计工具（SDK Host 时代）。
 *
 * 每次上游 `@earendil-works/pi-coding-agent` 发新版本时，先跑它：
 * 自动对比「当前锁定版本」与「目标版本」的真实包内容，逐项检查从 SDK 宿主层到 Android App 层的
 * 9 条集成接缝，直接列出哪些文件必须改动、哪些可选增强、哪些逐字节未变。
 *
 * 用法：
 *   node tools/pi-upgrade-audit.mjs                                   # 对比当前 PI_VERSION 与 npm latest
 *   node tools/pi-upgrade-audit.mjs --check-only                      # 仅检查是否有新版本（有更新退出码 2）
 *   node tools/pi-upgrade-audit.mjs --target 1.6.0                    # 对比当前 PI_VERSION 与指定版本
 *   node tools/pi-upgrade-audit.mjs --from 1.5.2 --target 1.6.0       # 指定任意两个版本对比
 *   node tools/pi-upgrade-audit.mjs --old-dir <dir> --new-dir <dir>   # 纯离线对比两个已安装目录（含 node_modules）
 *   node tools/pi-upgrade-audit.mjs --report /tmp/pi-upgrade.md       # 同时写出完整 Markdown 报告
 *
 * 完整 SOP 见 docs/sdk-host/upgrading.md。
 */

import { execFileSync, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, "..");
const FETCH_RUNTIME = join(ROOT, "tools", "fetch-runtime.mjs");
const ENGINE_LOCK = join(ROOT, "tools", "pi-engine.lock.json");
const LICENSE_SCRIPT = join(ROOT, "tools", "build-license-assets.py");
const CONTRACT_SCRIPT = join(ROOT, "tools", "sdk-host", "contract.mjs");
const SDK_HOST_DIR = join(ROOT, "app", "src", "main", "assets", "guest", "sdk-host");
const SETTINGS_REGISTRY = join(ROOT, "app", "src", "main", "kotlin", "app", "pi", "ui", "settings", "PiSettingsRegistry.kt");
const WORK_DIR = join(ROOT, "build", "pi-upgrade-audit");

function parseArgs(argv) {
  const out = { checkOnly: false, from: null, target: null, oldDir: null, newDir: null, report: null };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--check-only") out.checkOnly = true;
    else if (a === "--from" && argv[i + 1]) out.from = argv[++i];
    else if (a === "--target" && argv[i + 1]) out.target = argv[++i];
    else if (a === "--old-dir" && argv[i + 1]) out.oldDir = resolve(argv[++i]);
    else if (a === "--new-dir" && argv[i + 1]) out.newDir = resolve(argv[++i]);
    else if (a === "--report" && argv[i + 1]) out.report = resolve(argv[++i]);
    else if (a === "-h" || a === "--help") {
      console.log(readFileSync(fileURLToPath(import.meta.url), "utf8").split("\n").slice(1, 22).join("\n"));
      process.exit(0);
    }
  }
  return out;
}

function readPinnedPiVersion() {
  const text = readFileSync(FETCH_RUNTIME, "utf8");
  const m = /^const PI_VERSION = "([^"]+)";/m.exec(text);
  if (!m) throw new Error("Cannot find `const PI_VERSION = \"...\"` in tools/fetch-runtime.mjs");
  return m[1];
}

function readPinnedNodeVersion() {
  const text = readFileSync(FETCH_RUNTIME, "utf8");
  const m = /nodejs\.org\/dist\/v([0-9]+\.[0-9]+\.[0-9]+)\//.exec(text);
  return m ? m[1] : null;
}

function readPinnedLicenseSha() {
  if (!existsSync(LICENSE_SCRIPT)) return null;
  const text = readFileSync(LICENSE_SCRIPT, "utf8");
  const m = /^PI_LICENCE_SHA256 = "([0-9a-f]+)"/m.exec(text);
  return m ? m[1] : null;
}

function readLicenseNoTextTable() {
  if (!existsSync(LICENSE_SCRIPT)) return new Map();
  const text = readFileSync(LICENSE_SCRIPT, "utf8");
  const map = new Map();
  const re = /\("([^"]+)",\s*"([^"]+)",\s*"([^"]+)"\)/g;
  const start = text.indexOf("PI_ENGINE_NO_LICENCE_TEXT = [");
  const end = text.indexOf("]", start);
  if (start < 0 || end < 0) return map;
  const slice = text.slice(start, end);
  let m;
  while ((m = re.exec(slice)) !== null) {
    map.set(m[1], m[2]);
  }
  return map;
}

async function fetchJson(url) {
  const res = await fetch(url, { headers: { "User-Agent": "pi-android-upgrade-audit" } });
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`);
  return res.json();
}

async function fetchText(url) {
  const res = await fetch(url, { headers: { "User-Agent": "pi-android-upgrade-audit" } });
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`);
  return res.text();
}

function ensureInstalled(version) {
  const dir = join(WORK_DIR, `pi-${version}`);
  const pkgJson = join(dir, "node_modules", "@earendil-works", "pi-coding-agent", "package.json");
  if (existsSync(pkgJson)) {
    const installed = JSON.parse(readFileSync(pkgJson, "utf8")).version;
    if (installed === version) return dir;
  }
  mkdirSync(dir, { recursive: true });
  writeFileSync(
    join(dir, "package.json"),
    JSON.stringify({ name: "pi-audit-stage", private: true, dependencies: { "@earendil-works/pi-coding-agent": version } }, null, 2) + "\n",
  );
  console.error(`[audit] installing @earendil-works/pi-coding-agent@${version} into ${relative(ROOT, dir)} ...`);
  execFileSync("npm", ["install", "--ignore-scripts", "--omit=dev", "--omit=optional", "--no-audit", "--no-fund"], {
    cwd: dir,
    stdio: "inherit",
  });
  return dir;
}

function pkgDir(root, name) {
  return join(root, "node_modules", ...name.split("/"));
}

function walkFiles(dir, base = dir) {
  const out = [];
  if (!existsSync(dir)) return out;
  for (const ent of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, ent.name);
    if (ent.isDirectory()) {
      if (ent.name === "node_modules") continue;
      out.push(...walkFiles(p, base));
    } else {
      out.push(relative(base, p));
    }
  }
  return out;
}

function filesEqual(a, b) {
  if (!existsSync(a) || !existsSync(b)) return false;
  return readFileSync(a).equals(readFileSync(b));
}

function parseSemver(v) {
  const m = /(\d+)\.(\d+)\.(\d+)/.exec(String(v || ""));
  return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : [0, 0, 0];
}

function semverGte(a, b) {
  const pa = parseSemver(a), pb = parseSemver(b);
  for (let i = 0; i < 3; i++) {
    if (pa[i] !== pb[i]) return pa[i] > pb[i];
  }
  return true;
}

/** 从 settings-schema.js（运行时 TypeBox schema）提取所有顶层设置键，回退到 settings-manager.d.ts。 */
async function extractSettingsKeys(piDir) {
  const schemaJs = join(piDir, "dist/core/settings-schema.js");
  if (existsSync(schemaJs)) {
    try {
      const mod = await import(pathToFileURL(schemaJs).href);
      const props = mod?.SettingsSchema?.properties;
      if (props && typeof props === "object") return Object.keys(props).sort();
    } catch {
      // fall through
    }
  }
  const dtsPath = join(piDir, "dist/core/settings-manager.d.ts");
  if (!existsSync(dtsPath)) return [];
  const src = readFileSync(dtsPath, "utf8");
  const m = /export interface Settings\s*\{([\s\S]*?)\n\}/.exec(src);
  if (!m) return [];
  const keys = [];
  for (const line of m[1].split("\n")) {
    const km = /^\s+([a-zA-Z0-9_]+)\??:/.exec(line);
    if (km) keys.push(km[1]);
  }
  return [...new Set(keys)].sort();
}

/** 从 *.d.ts 类声明提取公开方法/getter 名。 */
function extractClassMembers(dtsPath, className) {
  if (!existsSync(dtsPath)) return [];
  const src = readFileSync(dtsPath, "utf8");
  const start = src.indexOf(`export declare class ${className}`);
  if (start < 0) return [];
  const end = src.indexOf("\n}", start);
  const body = end > start ? src.slice(start, end) : src.slice(start);
  const members = new Set();
  for (const line of body.split("\n")) {
    if (/^\s*(private|protected)\b/.test(line)) continue;
    const m = /^\s*(?:static\s+)?(?:get\s+|set\s+|readonly\s+)?([a-zA-Z0-9_]+)\s*(?:<[^>]*>)?\s*[:(]/.exec(line);
    if (m && m[1] !== "constructor") members.add(m[1]);
  }
  return [...members].sort();
}

/** 提取 cli/args.js 中的 `--flag` 集合。 */
function extractCliFlags(jsPath) {
  if (!existsSync(jsPath)) return [];
  const src = readFileSync(jsPath, "utf8");
  const flags = new Set();
  const re = /"(--[a-z0-9-]+)"/g;
  let m;
  while ((m = re.exec(src)) !== null) flags.add(m[1]);
  return [...flags].sort();
}

/** 扫描 sdk-host 中所有直接 import pi dist 子路径的文件，用于核对 contract.mjs 是否漏守。 */
function scanHostDistImports() {
  const found = [];
  for (const rel of walkFiles(SDK_HOST_DIR)) {
    if (!rel.endsWith(".mjs")) continue;
    const src = readFileSync(join(SDK_HOST_DIR, rel), "utf8");
    const re = /join\([^)]*"dist"([^)]*)\)/g;
    let m;
    while ((m = re.exec(src)) !== null) {
      const parts = [...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1]);
      if (parts.length > 0 && parts[parts.length - 1].endsWith(".js")) {
        found.push({ file: rel, distRel: parts.join("/") });
      }
    }
  }
  return found;
}

/** 统计 pi-ai 官方模型目录条目数与 schemaVersion。 */
function readCatalogStats(root) {
  const dir = join(pkgDir(root, "@earendil-works/pi-ai"), "dist", "providers", "data");
  if (!existsSync(dir)) return null;
  const manifestPath = join(dir, ".manifest.json");
  const manifest = existsSync(manifestPath) ? JSON.parse(readFileSync(manifestPath, "utf8")) : {};
  const files = readdirSync(dir).filter((f) => f.endsWith(".json") && !f.startsWith(".")).sort();
  let chat = 0, image = 0, classifier = 0;
  for (const f of files) {
    const raw = JSON.parse(readFileSync(join(dir, f), "utf8"));
    const walkObj = (o) => {
      if (!o || typeof o !== "object" || Array.isArray(o)) return;
      if (typeof o.type === "string" && typeof o.id === "string") {
        if (o.type === "chat") chat++;
        else if (o.type === "image") image++;
        else if (o.type === "classifier") classifier++;
        return;
      }
      for (const v of Object.values(o)) walkObj(v);
    };
    walkObj(raw);
  }
  return { schemaVersion: manifest.schemaVersion ?? null, files, chat, image, classifier };
}

/** 审计新版本依赖树中不带 LICENSE 文件的包。 */
function auditNoLicensePackages(root) {
  const lockPath = join(root, "package-lock.json");
  if (!existsSync(lockPath)) return null;
  const lock = JSON.parse(readFileSync(lockPath, "utf8"));
  const noLic = new Map();
  let total = 0;
  for (const [k, v] of Object.entries(lock.packages ?? {})) {
    if (!k || v.dev || v.optional) continue;
    const dir = join(root, k);
    if (!existsSync(dir)) continue;
    total++;
    const files = readdirSync(dir);
    const hasLic =
      files.some((f) => /^licen[cs]e/i.test(f)) ||
      (existsSync(join(dir, "THIRD_PARTY_NOTICES")) && readdirSync(join(dir, "THIRD_PARTY_NOTICES")).length > 0) ||
      (existsSync(join(dir, "LICENSES")) && readdirSync(join(dir, "LICENSES")).length > 0);
    if (!hasLic) {
      const name = k.replace(/^.*node_modules\//, "");
      noLic.set(name, { version: v.version, license: v.license ?? "" });
    }
  }
  return { total, noLic };
}

function diffSets(oldArr, newArr) {
  const o = new Set(oldArr), n = new Set(newArr);
  return {
    added: [...n].filter((x) => !o.has(x)).sort(),
    removed: [...o].filter((x) => !n.has(x)).sort(),
  };
}

function diffJsonFlat(oldObj, newObj) {
  const keys = new Set([...Object.keys(oldObj ?? {}), ...Object.keys(newObj ?? {})]);
  const added = [], removed = [], changed = [];
  for (const k of [...keys].sort()) {
    if (!(k in (oldObj ?? {}))) added.push(k);
    else if (!(k in (newObj ?? {}))) removed.push(k);
    else if (JSON.stringify(oldObj[k]) !== JSON.stringify(newObj[k])) changed.push(k);
  }
  return { added, removed, changed };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const pinnedVersion = readPinnedPiVersion();
  const pinnedNode = readPinnedNodeVersion();

  let targetVersion = args.target;
  if (!targetVersion && !args.newDir) {
    const latestMeta = await fetchJson("https://registry.npmjs.org/@earendil-works%2Fpi-coding-agent/latest");
    targetVersion = latestMeta.version;
  }

  if (args.checkOnly) {
    console.log(`Pinned PI_VERSION : ${pinnedVersion}`);
    console.log(`npm latest        : ${targetVersion}`);
    if (pinnedVersion === targetVersion) {
      console.log("状态: 已是最新，无需升级。");
      process.exit(0);
    }
    console.log(`状态: 上游有新版本 ${pinnedVersion} → ${targetVersion}。运行 \`node tools/pi-upgrade-audit.mjs --target ${targetVersion}\` 开始审计。`);
    process.exit(2);
  }

  const fromVersion = args.from ?? pinnedVersion;
  const oldRoot = args.oldDir ?? (
    existsSync(join(ROOT, "build", "pi-contract", "node_modules", "@earendil-works", "pi-coding-agent", "package.json")) &&
    JSON.parse(readFileSync(join(ROOT, "build", "pi-contract", "node_modules", "@earendil-works", "pi-coding-agent", "package.json"), "utf8")).version === fromVersion
      ? join(ROOT, "build", "pi-contract")
      : ensureInstalled(fromVersion)
  );
  const newRoot = args.newDir ?? ensureInstalled(targetVersion);

  const oldPi = pkgDir(oldRoot, "@earendil-works/pi-coding-agent");
  const newPi = pkgDir(newRoot, "@earendil-works/pi-coding-agent");
  const oldPkg = JSON.parse(readFileSync(join(oldPi, "package.json"), "utf8"));
  const newPkg = JSON.parse(readFileSync(join(newPi, "package.json"), "utf8"));
  const vOld = oldPkg.version;
  const vNew = newPkg.version;

  const sections = [];
  const addSection = (id, title, level, summary, details = [], files = []) => {
    sections.push({ id, title, level, summary, details, files });
  };

  // ---- Gate 1: 版本、Node 要求、依赖与许可证 ----
  {
    const details = [];
    const files = ["tools/fetch-runtime.mjs", "tools/pi-engine.lock.json"];
    let level = "INFO";

    const nodeReq = newPkg.engines?.node ?? "";
    const minNodeMatch = /(\d+\.\d+\.\d+)/.exec(nodeReq);
    if (minNodeMatch && pinnedNode && !semverGte(pinnedNode, minNodeMatch[1])) {
      level = "ACTION";
      details.push(`🔴 Node 最低版本要求 ${nodeReq}，当前锁定 Node ${pinnedNode} 不满足，必须升级 fetch-runtime.mjs 的 Node 载荷！`);
    } else {
      details.push(`Node 版本要求: \`${nodeReq}\`（当前锁定 \`${pinnedNode ?? "unknown"}\`，满足）`);
    }

    // Direct dependency diffs
    const depDiff = diffJsonFlat(oldPkg.dependencies, newPkg.dependencies);
    const nonPiChanged = depDiff.changed.filter((d) => !d.startsWith("@earendil-works/"));
    if (depDiff.added.length) details.push(`新增直接依赖: ${depDiff.added.map((d) => `${d}@${newPkg.dependencies[d]}`).join(", ")}`);
    if (depDiff.removed.length) details.push(`移除直接依赖: ${depDiff.removed.join(", ")}`);
    if (nonPiChanged.length) {
      details.push(`第三方直接依赖变化: ${nonPiChanged.map((d) => `${d} ${oldPkg.dependencies[d]} → ${newPkg.dependencies[d]}`).join(", ")}`);
    }

    // License no-text audit
    const licAudit = auditNoLicensePackages(newRoot);
    if (licAudit) {
      const pinnedTable = readLicenseNoTextTable();
      const drifted = [];
      for (const [name, info] of licAudit.noLic.entries()) {
        if (name.startsWith("@earendil-works/")) continue;
        const prev = pinnedTable.get(name);
        if (!prev) drifted.push(`新增无许可文本包: ${name}@${info.version} (${info.license})`);
        else if (prev !== info.version) drifted.push(`无许可文本包版本变化: ${name} ${prev} → ${info.version}`);
      }
      for (const [name] of pinnedTable.entries()) {
        if (!name.startsWith("@earendil-works/") && !licAudit.noLic.has(name)) {
          drifted.push(`不再属于无许可文本表: ${name}`);
        }
      }
      if (drifted.length) {
        level = "ACTION";
        details.push(...drifted.map((x) => `🟡 ${x}`));
        files.push("tools/build-license-assets.py", "app/src/main/assets/licenses/");
      } else {
        details.push(`许可证缺口表核对: ${licAudit.total} 个包，非 @earendil-works 缺口项无变化`);
      }
    }

    // Verify upstream LICENSE sha256
    const expectedSha = readPinnedLicenseSha();
    if (expectedSha) {
      try {
        const licText = await fetchText(`https://raw.githubusercontent.com/earendil-works/pi/v${vNew}/LICENSE`);
        const actualSha = createHash("sha256").update(licText).digest("hex");
        if (actualSha !== expectedSha) {
          level = "ACTION";
          details.push(`🔴 上游 v${vNew}/LICENSE SHA-256 变化！pinned=${expectedSha.slice(0, 12)}… actual=${actualSha.slice(0, 12)}…`);
          files.push("tools/build-license-assets.py", "app/src/main/assets/licenses/pi-license.txt");
        } else {
          details.push(`上游 v${vNew}/LICENSE SHA-256 与已锁定完全一致（${actualSha.slice(0, 12)}…）`);
        }
      } catch (e) {
        details.push(`⚠️ 未能联网校验 v${vNew}/LICENSE（${e.message}），请手动复核`);
      }
    }

    if (vOld !== vNew) {
      files.push("app/src/main/assets/licenses/component-list.txt", "app/src/main/assets/licenses/manifest.txt");
    }
    addSection(1, "版本、依赖与许可证", level, `${vOld} → ${vNew}（直接依赖变动 ${depDiff.added.length + depDiff.removed.length + nonPiChanged.length} 项）`, details, [...new Set(files)]);
  }

  // ---- Gate 2: SDK Host 契约测试 ----
  {
    const proc = spawnSync(process.execPath, [CONTRACT_SCRIPT, newPi], { encoding: "utf8" });
    const out = `${proc.stdout ?? ""}${proc.stderr ?? ""}`.trim();
    const contractSrc = readFileSync(CONTRACT_SCRIPT, "utf8");
    const hostImports = scanHostDistImports();
    const unguarded = hostImports.filter(
      (x) => !x.distRel.startsWith("bundle/") && x.distRel !== "index.js" && !contractSrc.includes(x.distRel),
    );
    const details = out.split("\n").map((l) => `  ${l}`);
    const files = [];
    let level = proc.status === 0 ? "OK" : "ACTION";
    if (proc.status !== 0) files.push("app/src/main/assets/guest/sdk-host/", "tools/sdk-host/contract.mjs");
    if (unguarded.length > 0) {
      if (level === "OK") level = "REVIEW";
      details.push(`🟡 以下 sdk-host 内部 dist 引用未写入 contract.mjs: ${unguarded.map((u) => `${u.file} -> dist/${u.distRel}`).join("; ")}`);
      files.push("tools/sdk-host/contract.mjs");
    }
    addSection(2, "SDK Host 契约（公开导出 + 内部路径）", level, proc.status === 0 ? "contract.mjs 全部通过" : "contract.mjs 发现破坏性变化！", details, files);
  }

  // ---- Gate 3: Settings Schema ----
  {
    const oDts = join(oldPi, "dist/core/settings-manager.d.ts");
    const nDts = join(newPi, "dist/core/settings-manager.d.ts");
    const oJs = join(oldPi, "dist/core/settings-manager.js");
    const nJs = join(newPi, "dist/core/settings-manager.js");
    const schemaEq = filesEqual(join(oldPi, "dist/core/settings-schema.js"), join(newPi, "dist/core/settings-schema.js"));
    const dtsEq = filesEqual(oDts, nDts);
    const jsEq = filesEqual(oJs, nJs);
    const oKeys = await extractSettingsKeys(oldPi);
    const nKeys = await extractSettingsKeys(newPi);
    const kd = diffSets(oKeys, nKeys);
    const details = [];
    const files = [];
    let level = "OK";
    if (kd.added.length || kd.removed.length) {
      level = "ACTION";
      if (kd.added.length) details.push(`新增设置键: ${kd.added.join(", ")}`);
      if (kd.removed.length) details.push(`移除设置键: ${kd.removed.join(", ")}`);
      files.push("app/src/main/kotlin/app/pi/ui/settings/PiSettingsRegistry.kt", "app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt", "app/src/main/assets/guest/sdk-host/commands/settings.mjs");
    } else if (!dtsEq || !jsEq || !schemaEq) {
      level = "REVIEW";
      details.push(`设置键集合未变（${nKeys.length} 个），但 settings-manager / settings-schema 实现有修改（请检查默认值/setter 语义）`);
      files.push("app/src/main/kotlin/app/pi/ui/settings/PiSettingsRegistry.kt");
    } else {
      details.push(`settings-schema.js 与 settings-manager.{d.ts,js} 逐字节相同（${nKeys.length} 个顶层设置键）`);
    }
    // Check if any key referenced in PiSettingsRegistry disappeared
    if (existsSync(SETTINGS_REGISTRY)) {
      const regText = readFileSync(SETTINGS_REGISTRY, "utf8");
      const removedInApp = kd.removed.filter((k) => regText.includes(`"${k}"`));
      if (removedInApp.length) {
        level = "ACTION";
        details.push(`🔴 App PiSettingsRegistry.kt 引用了已删除的键: ${removedInApp.join(", ")}`);
      }
    }
    addSection(3, "设置 Schema（settings.json）", level, dtsEq && jsEq && schemaEq ? `逐字节未变（${nKeys.length} 键）` : `键 +${kd.added.length}/-${kd.removed.length}，实现${jsEq && schemaEq ? "未变" : "有变"}`, details, files);
  }

  // ---- Gate 4: 主题令牌与配色 ----
  {
    const darkEq = filesEqual(join(oldPi, "dist/modes/interactive/theme/dark.json"), join(newPi, "dist/modes/interactive/theme/dark.json"));
    const lightEq = filesEqual(join(oldPi, "dist/modes/interactive/theme/light.json"), join(newPi, "dist/modes/interactive/theme/light.json"));
    const themeJsEq = filesEqual(join(oldPi, "dist/modes/interactive/theme/theme.js"), join(newPi, "dist/modes/interactive/theme/theme.js"));
    const details = [];
    const files = [];
    let level = "OK";
    if (!darkEq || !lightEq) {
      level = "ACTION";
      for (const name of ["dark.json", "light.json"]) {
        const o = JSON.parse(readFileSync(join(oldPi, "dist/modes/interactive/theme", name), "utf8"));
        const n = JSON.parse(readFileSync(join(newPi, "dist/modes/interactive/theme", name), "utf8"));
        const cd = diffJsonFlat(o.colors, n.colors);
        if (cd.added.length || cd.removed.length || cd.changed.length) {
          details.push(`${name} colors: +${cd.added.length} -${cd.removed.length} ~${cd.changed.length} ${[...cd.added, ...cd.removed].slice(0, 10).join(", ")}`);
        }
      }
      files.push(
        "app/src/main/kotlin/app/pi/ui/theme/PiPalette.kt",
        "app/src/main/kotlin/app/pi/ui/theme/PiThemeFiles.kt",
        "app/src/main/assets/guest/sdk-host/ui/theme.mjs",
      );
    } else if (!themeJsEq) {
      level = "REVIEW";
      details.push("dark.json / light.json 逐字节相同；theme.js 有改动（例如语法高亮映射或 system 主题规则，建议 diff 复核）");
      files.push("app/src/main/assets/guest/sdk-host/ui/theme.mjs", "app/src/main/assets/pi-extensions/pi-highlight/");
    } else {
      details.push("dark.json / light.json / theme.js 全部逐字节相同");
    }
    addSection(4, "主题令牌与配色", level, darkEq && lightEq ? (themeJsEq ? "逐字节未变" : "色表未变，theme.js 有更新") : "官方色值或令牌有变化！", details, files);
  }

  // ---- Gate 5: 内建斜杠命令 & CLI Flags ----
  {
    const slashEq = filesEqual(join(oldPi, "dist/core/slash-commands.js"), join(newPi, "dist/core/slash-commands.js"));
    const argsEq = filesEqual(join(oldPi, "dist/cli/args.js"), join(newPi, "dist/cli/args.js"));
    const oFlags = extractCliFlags(join(oldPi, "dist/cli/args.js"));
    const nFlags = extractCliFlags(join(newPi, "dist/cli/args.js"));
    const fd = diffSets(oFlags, nFlags);
    const details = [];
    const files = [];
    let level = "OK";
    if (!slashEq) {
      level = "REVIEW";
      try {
        const oMod = await import(pathToFileURL(join(oldPi, "dist/core/slash-commands.js")).href);
        const nMod = await import(pathToFileURL(join(newPi, "dist/core/slash-commands.js")).href);
        const sd = diffSets(
          (oMod.BUILTIN_SLASH_COMMANDS ?? []).map((c) => c.name),
          (nMod.BUILTIN_SLASH_COMMANDS ?? []).map((c) => c.name),
        );
        details.push(`内建斜杠命令变化: +[${sd.added.join(", ")}] -[${sd.removed.join(", ")}]`);
        if (sd.added.length || sd.removed.length) level = "ACTION";
      } catch {
        details.push("core/slash-commands.js 有改动（请手动 diff）");
      }
      files.push("app/src/main/kotlin/app/pi/ui/chat/PiSlashCommands.kt", "app/src/main/assets/guest/sdk-host/commands/catalog.mjs");
    } else {
      details.push("core/slash-commands.js 逐字节相同");
    }
    if (fd.added.length || fd.removed.length) {
      level = "ACTION";
      details.push(`CLI flags 变化: +[${fd.added.join(", ")}] -[${fd.removed.join(", ")}]`);
      files.push("protocol/src/main/kotlin/app/pi/protocol/PiPreSpawnConfig.kt", "app/src/main/kotlin/app/pi/host/OpenArgsFactory.kt");
    } else if (!argsEq) {
      details.push(`CLI flags 集合未变（${nFlags.length} 个），cli/args.js 仅文案/环境变量帮助变化`);
    } else {
      details.push(`cli/args.js 逐字节相同（${nFlags.length} 个 flag）`);
    }
    addSection(5, "内建斜杠命令与 CLI Flags", level, slashEq && fd.added.length === 0 && fd.removed.length === 0 ? "命令与 Flag 集合未变" : "命令或 Flag 集合有变", details, files);
  }

  // ---- Gate 6: 内置工具、渲染器、图片压缩、项目信任 ----
  {
    const toolMap = {
      "core/tools/read.js": "app/src/main/kotlin/app/pi/ui/blocks/ReadBlock.kt",
      "core/tools/renderers/read.js": "app/src/main/kotlin/app/pi/ui/blocks/ReadBlock.kt",
      "core/tools/bash.js": "app/src/main/kotlin/app/pi/ui/blocks/ShellBlock.kt",
      "core/tools/renderers/bash.js": "app/src/main/kotlin/app/pi/ui/blocks/ShellBlock.kt",
      "core/tools/edit.js": "app/src/main/kotlin/app/pi/ui/blocks/EditBlock.kt",
      "core/tools/renderers/edit.js": "app/src/main/kotlin/app/pi/ui/blocks/EditBlock.kt",
      "core/tools/write.js": "app/src/main/kotlin/app/pi/ui/blocks/WriteBlock.kt",
      "core/tools/renderers/write.js": "app/src/main/kotlin/app/pi/ui/blocks/WriteBlock.kt",
      "core/tools/grep.js": "app/src/main/kotlin/app/pi/ui/blocks/GrepBlock.kt",
      "core/tools/renderers/grep.js": "app/src/main/kotlin/app/pi/ui/blocks/GrepBlock.kt",
      "core/tools/find.js": "app/src/main/kotlin/app/pi/ui/blocks/FindBlock.kt",
      "core/tools/renderers/find.js": "app/src/main/kotlin/app/pi/ui/blocks/FindBlock.kt",
      "core/tools/ls.js": "app/src/main/kotlin/app/pi/ui/blocks/LsBlock.kt",
      "core/tools/renderers/ls.js": "app/src/main/kotlin/app/pi/ui/blocks/LsBlock.kt",
      "core/tools/truncate.js": "app/src/main/kotlin/app/pi/ui/blocks/ToolOutputParse.kt",
      "core/tools/output-accumulator.js": "app/src/main/kotlin/app/pi/ui/blocks/ToolOutputParse.kt",
      "utils/image-resize-core.js": "app/src/main/kotlin/app/pi/ui/screens/AttachmentBudget.kt",
      "utils/image-process.js": "app/src/main/kotlin/app/pi/ui/screens/ChatAttachments.kt",
      "core/project-trust.js": "app/src/main/assets/guest/sdk-host/controller.mjs",
      "core/trust-manager.js": "app/src/main/assets/guest/sdk-host/controller.mjs",
    };
    const changed = [];
    const files = new Set();
    for (const [rel, target] of Object.entries(toolMap)) {
      if (!filesEqual(join(oldPi, "dist", rel), join(newPi, "dist", rel))) {
        changed.push(rel);
        files.add(target);
      }
    }
    const level = changed.some((c) => c.includes("image-resize") || c.includes("project-trust") || c.includes("renderers/"))
      ? "REVIEW"
      : changed.length > 0
        ? "INFO"
        : "OK";
    const details = changed.length
      ? changed.map((c) => `有改动: dist/${c} → 核对 ${toolMap[c]}`)
      : ["所有内置工具、渲染器、图片缩放与项目信任模块逐字节未变"];
    addSection(6, "内置工具、渲染器、图片预算与信任", level, changed.length ? `${changed.length} 个文件有更新` : "逐字节未变", details, [...files]);
  }

  // ---- Gate 7: Runtime / AgentSession / SessionManager / Events ----
  {
    const rtDiff = diffSets(
      extractClassMembers(join(oldPi, "dist/core/agent-session-runtime.d.ts"), "AgentSessionRuntime"),
      extractClassMembers(join(newPi, "dist/core/agent-session-runtime.d.ts"), "AgentSessionRuntime"),
    );
    const sesDiff = diffSets(
      extractClassMembers(join(oldPi, "dist/core/agent-session.d.ts"), "AgentSession"),
      extractClassMembers(join(newPi, "dist/core/agent-session.d.ts"), "AgentSession"),
    );
    const smDtsEq = filesEqual(join(oldPi, "dist/core/session-manager.d.ts"), join(newPi, "dist/core/session-manager.d.ts"));
    const rpcDtsEq = filesEqual(join(oldPi, "dist/modes/rpc/rpc-types.d.ts"), join(newPi, "dist/modes/rpc/rpc-types.d.ts"));
    const coreWatch = [
      "core/agent-session-runtime.js",
      "core/agent-session.js",
      "core/resource-loader.js",
      "core/package-manager.js",
      "core/extensions/runner.js",
      "modes/rpc/rpc-mode.js",
      "modes/interactive/interactive-mode.js",
    ].filter((rel) => !filesEqual(join(oldPi, "dist", rel), join(newPi, "dist", rel)));

    const details = [];
    const files = [];
    let level = "OK";
    if (rtDiff.added.length || rtDiff.removed.length) {
      level = "REVIEW";
      details.push(`AgentSessionRuntime 成员变动: +[${rtDiff.added.join(", ")}] -[${rtDiff.removed.join(", ")}]`);
      files.push("app/src/main/assets/guest/sdk-host/controller.mjs", "tools/sdk-host/contract.mjs");
    }
    if (sesDiff.added.length || sesDiff.removed.length) {
      level = "REVIEW";
      details.push(`AgentSession 成员变动: +[${sesDiff.added.join(", ")}] -[${sesDiff.removed.join(", ")}]`);
      files.push("app/src/main/assets/guest/sdk-host/commands/session.mjs", "tools/sdk-host/contract.mjs");
    }
    if (!smDtsEq) {
      level = "REVIEW";
      details.push("session-manager.d.ts 有变动（检查 SessionEntry / Transcript.kt）");
      files.push("protocol/src/main/kotlin/app/pi/protocol/Transcript.kt", "protocol/src/main/kotlin/app/pi/protocol/SessionEntries.kt");
    }
    if (!rpcDtsEq) {
      level = "REVIEW";
      details.push("rpc-types.d.ts 有变动（检查 Events.kt / Responses.kt）");
      files.push("protocol/src/main/kotlin/app/pi/protocol/Events.kt", "protocol/src/main/kotlin/app/pi/protocol/Responses.kt");
    }
    if (coreWatch.length) {
      if (level === "OK") level = "INFO";
      details.push(`核心实现变更: ${coreWatch.join(", ")}`);
    } else {
      details.push("核心运行时、会话、资源加载器逐字节未变");
    }
    addSection(7, "Runtime / Session / 事件协议面", level, `Runtime +${rtDiff.added.length}/-${rtDiff.removed.length}, Session +${sesDiff.added.length}/-${sesDiff.removed.length}, 核心变动 ${coreWatch.length} 文件`, details, [...new Set(files)]);
  }

  // ---- Gate 8: 官方模型目录与 Provider (@earendil-works/pi-ai) ----
  {
    const oCat = readCatalogStats(oldRoot);
    const nCat = readCatalogStats(newRoot);
    const envEq = filesEqual(
      join(pkgDir(oldRoot, "@earendil-works/pi-ai"), "dist/env-api-keys.js"),
      join(pkgDir(newRoot, "@earendil-works/pi-ai"), "dist/env-api-keys.js"),
    );
    const mrEq = filesEqual(join(oldPi, "dist/core/model-resolver.js"), join(newPi, "dist/core/model-resolver.js"));
    const details = [];
    const files = [];
    let level = "OK";
    if (oCat && nCat) {
      if (oCat.schemaVersion !== nCat.schemaVersion) {
        level = "ACTION";
        details.push(`🔴 模型目录 schemaVersion 变化: ${oCat.schemaVersion} → ${nCat.schemaVersion}！`);
        files.push("app/src/main/assets/guest/sdk-host/commands/model.mjs");
      }
      const pd = diffSets(oCat.files, nCat.files);
      if (pd.added.length || pd.removed.length) {
        if (level === "OK") level = "REVIEW";
        details.push(`Provider 文件变动: +[${pd.added.join(", ")}] -[${pd.removed.join(", ")}]`);
        files.push("app/src/main/kotlin/app/pi/ui/models/");
      }
      details.push(`目录规模: schemaVersion=${nCat.schemaVersion}, ${nCat.files.length} 个文件, chat ${oCat.chat}→${nCat.chat} / image ${oCat.image}→${nCat.image} / classifier ${oCat.classifier}→${nCat.classifier}`);
    }
    if (!envEq) {
      if (level === "OK") level = "INFO";
      details.push("pi-ai/dist/env-api-keys.js 有更新（新增/调整供应商环境变量）");
    }
    if (!mrEq) {
      if (level === "OK") level = "INFO";
      details.push("core/model-resolver.js 有更新（默认模型或解析逻辑变化；确认 findExactModelReferenceMatch 签名）");
    }
    addSection(8, "官方模型目录与 Provider（pi-ai）", level, oCat && nCat ? `schema v${nCat.schemaVersion}, chat ${oCat.chat}→${nCat.chat}` : "无法读取目录", details, files);
  }

  // ---- Gate 9: TUI 组件 / 内置扩展兼容性 ----
  {
    const tuiDtsEq = filesEqual(
      join(pkgDir(oldRoot, "@earendil-works/pi-tui"), "dist/tui.d.ts"),
      join(pkgDir(newRoot, "@earendil-works/pi-tui"), "dist/tui.d.ts"),
    );
    const mcpDtsEq = filesEqual(
      join(pkgDir(oldRoot, "@earendil-works/pi-mcp"), "dist/index.d.ts"),
      join(pkgDir(newRoot, "@earendil-works/pi-mcp"), "dist/index.d.ts"),
    );
    const mermaidOld = oldPkg.dependencies?.["lovely-mermaid"] ?? oldPkg.dependencies?.["grok-mermaid"];
    const mermaidNew = newPkg.dependencies?.["lovely-mermaid"] ?? newPkg.dependencies?.["grok-mermaid"];
    const hljsOld = oldPkg.dependencies?.["highlight.js"];
    const hljsNew = newPkg.dependencies?.["highlight.js"];
    const details = [];
    const files = [];
    let level = "OK";
    if (!tuiDtsEq) {
      level = "REVIEW";
      details.push("pi-tui/dist/tui.d.ts 有变动（检查 Component.render / Container / Overlay 签名与 surfaces.mjs 兼容性）");
      files.push("app/src/main/assets/guest/sdk-host/ui/surfaces.mjs", "app/src/main/assets/guest/sdk-host/commands/catalog.mjs");
    }
    if (!mcpDtsEq) {
      if (level === "OK") level = "INFO";
      details.push("pi-mcp/dist/index.d.ts 有变动（检查 commands/mcp.mjs）");
      files.push("app/src/main/assets/guest/sdk-host/commands/mcp.mjs");
    }
    if (mermaidOld !== mermaidNew) {
      level = "ACTION";
      details.push(`Mermaid 渲染库变动: ${mermaidOld} → ${mermaidNew}`);
      files.push("app/src/main/assets/pi-extensions/pi-highlight/mermaid.ts");
    }
    if (hljsOld !== hljsNew) {
      level = "REVIEW";
      details.push(`highlight.js 变动: ${hljsOld} → ${hljsNew}`);
      files.push("app/src/main/assets/pi-extensions/pi-highlight/hljs.ts");
    }
    if (details.length === 0) details.push("pi-tui / pi-mcp / lovely-mermaid / highlight.js 接口均未变");
    addSection(9, "TUI 组件与内置扩展兼容性", level, details[0], details, files);
  }

  // ---- Fetch GitHub Release Notes (best effort) ----
  let releaseNotes = null;
  try {
    const rel = await fetchJson(`https://api.github.com/repos/earendil-works/pi/releases/tags/v${vNew}`);
    if (typeof rel?.body === "string" && rel.body.trim()) releaseNotes = rel.body.trim();
  } catch {
    // offline or tag missing
  }

  // ---- Render Markdown Report ----
  const badge = (l) => (l === "ACTION" ? "🔴 必须处理" : l === "REVIEW" ? "🟡 建议复核" : l === "INFO" ? "🔵 信息变更" : "✅ 无变化");
  const lines = [
    `# Pi 上游升级影响审计报告：\`${vOld}\` → \`${vNew}\``,
    "",
    `> 生成时间：${new Date().toISOString()}`,
    `> 对比基线：\`${oldRoot}\``,
    `> 目标版本：\`${newRoot}\``,
    "",
    "## 总览",
    "",
    "| # | 接缝 | 结论 | 摘要 |",
    "|---|---|---|---|",
    ...sections.map((s) => `| ${s.id} | ${s.title} | ${badge(s.level)} | ${s.summary} |`),
    "",
  ];

  const allFiles = [...new Set(sections.filter((s) => s.level === "ACTION" || s.level === "REVIEW").flatMap((s) => s.files))];
  if (allFiles.length > 0) {
    lines.push("## 需要重点检查/改动的文件清单", "");
    for (const f of allFiles) lines.push(`- \`${f}\``);
    lines.push("");
  }

  lines.push("## 逐项明细", "");
  for (const s of sections) {
    lines.push(`### ${s.id}. ${s.title} — ${badge(s.level)}`, "");
    for (const d of s.details) lines.push(`- ${d}`);
    if (s.files.length) lines.push(`- 相关文件: ${s.files.map((f) => `\`${f}\``).join(", ")}`);
    lines.push("");
  }

  if (releaseNotes) {
    lines.push(`## 上游 v${vNew} Release Notes（摘录）`, "", releaseNotes.slice(0, 6000), "");
  }

  lines.push(
    "## 下一步执行清单",
    "",
    "```bash",
    `# 1. 修改 tools/fetch-runtime.mjs 的 PI_VERSION = "${vNew}" 并补充审计记录`,
    "node tools/fetch-runtime.mjs --refresh-engine-lock",
    "# 2. 按上方清单适配 sdk-host / pi-extensions / rpc / app 及许可证资产",
    "# 3. 更新本地契约包并跑全量验证",
    `mkdir -p build/pi-contract && (cd build/pi-contract && npm install --ignore-scripts --no-audit --no-fund @earendil-works/pi-coding-agent@${vNew})`,
    "node tools/sdk-host/contract.mjs",
    "node tools/sdk-host/test.mjs",
    "node tools/sdk-host/test.mjs --no-bundle",
    "node tools/sdk-host/test.mjs --scandir-fix",
    "bash tools/check-android-apis.sh",
    "python3 tools/check-nested-comments.py",
    "```",
    "",
  );

  const markdown = lines.join("\n");
  if (args.report) {
    mkdirSync(dirname(args.report), { recursive: true });
    writeFileSync(args.report, markdown);
    console.error(`[audit] report written to ${args.report}`);
  }
  process.stdout.write(markdown);

  const hasAction = sections.some((s) => s.level === "ACTION");
  process.exit(hasAction ? 0 : 0);
}

main().catch((err) => {
  console.error(`[audit] FAILED: ${err?.stack ?? err}`);
  process.exit(1);
});
