#!/usr/bin/env node
/**
 * i18n static guard for PI Android:
 *  1. Verifies res/values/strings.xml (English default) and res/values-zh-rCN/strings.xml
 *     have 100% matching keys, valid XML escaping (no raw unescaped apostrophes), and
 *     identical printf format specifiers (%1$s, %2$d, etc.).
 *  2. Reports counts of bilingual tr(...) usages and remaining bare CJK string literals
 *     in Kotlin UI code so translation progress is measurable on every commit.
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repo = resolve(fileURLToPath(new URL("..", import.meta.url)));
const enFile = join(repo, "app/src/main/res/values/strings.xml");
const zhFile = join(repo, "app/src/main/res/values-zh-rCN/strings.xml");

function parseStringsXml(path) {
  const xml = readFileSync(path, "utf8");
  const stripped = xml.replace(/<!--[\s\S]*?-->/g, "");
  const map = new Map();
  const errors = [];
  const re = /<string\s+name="([^"]+)"[^>]*>([\s\S]*?)<\/string>/g;
  let m;
  while ((m = re.exec(stripped)) !== null) {
    const [, key, rawVal] = m;
    if (map.has(key)) errors.push(`duplicate key "${key}" in ${path}`);
    // Check for unescaped apostrophes outside double-quoted XML values
    const trimmed = rawVal.trim();
    if (!(trimmed.startsWith('"') && trimmed.endsWith('"'))) {
      for (let i = 0; i < rawVal.length; i++) {
        if (rawVal[i] === "'" && rawVal[i - 1] !== "\\") {
          errors.push(`unescaped apostrophe in "${key}" (${path})`);
          break;
        }
      }
    }
    map.set(key, rawVal);
  }
  return { map, errors };
}

function formatSpecifiers(value) {
  const specs = [];
  const re = /%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]/g;
  let m;
  while ((m = re.exec(value)) !== null) {
    if (m[0] !== "%%") specs.push(m[0]);
  }
  return specs.sort();
}

const en = parseStringsXml(enFile);
const zh = parseStringsXml(zhFile);
const failures = [...en.errors, ...zh.errors];

for (const key of en.map.keys()) {
  if (!zh.map.has(key)) failures.push(`missing in values-zh-rCN/strings.xml: "${key}"`);
}
for (const key of zh.map.keys()) {
  if (!en.map.has(key)) failures.push(`missing in values/strings.xml: "${key}"`);
}
for (const [key, enVal] of en.map.entries()) {
  const zhVal = zh.map.get(key);
  if (zhVal === undefined) continue;
  const sEn = formatSpecifiers(enVal);
  const sZh = formatSpecifiers(zhVal);
  if (JSON.stringify(sEn) !== JSON.stringify(sZh)) {
    failures.push(`format specifier mismatch for "${key}": en=${JSON.stringify(sEn)} zh=${JSON.stringify(sZh)}`);
  }
}

// Also count tr(...) calls vs remaining unwrapped CJK string literals in Kotlin UI files
function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    const st = statSync(full);
    if (st.isDirectory()) walk(full, out);
    else if (name.endsWith(".kt")) out.push(full);
  }
  return out;
}

const ktFiles = walk(join(repo, "app/src/main/kotlin/app/pi/ui"));
let trCalls = 0;
let bareCjkLines = 0;
for (const file of ktFiles) {
  const text = readFileSync(file, "utf8");
  const trMatches = text.match(/\btr\s*\(/g);
  if (trMatches) trCalls += trMatches.length;
  for (const line of text.split("\n")) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;
    const codePart = trimmed.replace(/\/\/.*$/, "");
    if (/[\u4e00-\u9fff]/.test(codePart) && /"[^"]*[\u4e00-\u9fff]/.test(codePart) && !/\btr\s*\(/.test(codePart) && !/\bpiChoice\s*\(/.test(codePart)) {
      bareCjkLines += 1;
    }
  }
}

if (failures.length > 0) {
  console.error("i18n check FAILED:");
  for (const f of failures) console.error("  - " + f);
  process.exit(1);
}

console.log(`i18n: OK (${en.map.size} XML keys 100% EN/ZH matched; UI stats: ${trCalls} tr() calls, ${bareCjkLines} remaining bare CJK UI lines)`);
