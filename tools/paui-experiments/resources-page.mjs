// 实验 5（guest 侧）：把工作区「资源」页用 PAUI 词表重画一遍，并按 App 的限额自检。
// 用法：node resources-page.mjs <pi-resources.mjs> <引擎目录> <cwd> <agentDir>
// 输出：PAUI JSON（stdout 最后一行）+ 统计（stderr）。判据：只用 PauiCodec.KNOWN_TYPES 里的类型、深度≤24、节点≤2000。
import { execFileSync } from "node:child_process";

const [script, engine, cwd, agent] = process.argv.slice(2);
const KNOWN = new Set(["column","row","box","spacer","divider","text","markdown","code","ansi","button","toggle","list","notice","badge","progress"]);

const raw = execFileSync("node", [script, engine, cwd, agent, "1", "list"], { encoding: "utf8" });
const snap = JSON.parse(raw.slice(raw.indexOf("{")));

const page = {
  type: "column", gap: 12, children: [
    { type: "text", text: "资源", tone: "accent", bold: true },
    ...(snap.installFailures?.length ? [{ type: "notice", tone: "warning", text: "有包没装上：" + snap.installFailures.join("；") }] : []),
    ...snap.global.flatMap((g) => [
      { type: "row", children: [{ type: "text", text: g.label, bold: true }, { type: "badge", text: g.scope ?? "global" }] },
      ...g.subgroups.flatMap((s) => [
        { type: "text", text: s.type, tone: "muted", mono: true },
        ...s.items.map((i) => ({ type: "toggle", id: `${g.key}|${s.type}|${i.path}`, label: i.name, detail: i.path, checked: !!i.enabled })),
      ]),
      { type: "divider" },
    ]),
    { type: "button", id: "refresh", label: "重新读取", primary: true },
  ],
};

let nodes = 0, depth = 0; const types = new Set();
(function walk(n, d) { nodes++; depth = Math.max(depth, d); types.add(n.type); (n.children ?? []).forEach((c) => walk(c, d + 1)); })(page, 1);
const unknown = [...types].filter((t) => !KNOWN.has(t));
const json = JSON.stringify(page);
console.error(`nodes=${nodes} depth=${depth} bytes=${json.length} types=${[...types].join(",")} unknown=${unknown.length ? unknown : "none"}`);
if (unknown.length || depth > 24 || nodes > 2000) process.exit(1);
console.log(json);
