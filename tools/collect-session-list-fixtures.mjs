#!/usr/bin/env node
/**
 * 会话列表（`/resume` 选择器）的标准答案，由 pi **自己的** `SessionSelectorComponent` 生成。
 *
 * 宿主 `commands/sessionlist.mjs` 的 `viewOf` 移植了官方里未导出的 `buildSessionTree` / `flattenSessionTree`，
 * Kotlin `session/list/SessionListModel.kt` 移植了行文本的规则（树前缀、年龄、右侧文字、显示文本清洗）。
 * 这里把固定的一批会话喂给真组件，读回它渲染出的每一行（去色），写成 JSON；
 * `tools/sdk-host/test.mjs` 与 `SessionListModelCheck` 各自拿同一批会话、要求给出同样的行。
 *
 *   node tools/collect-session-list-fixtures.mjs --pi <引擎目录>           # 重写
 *   node tools/collect-session-list-fixtures.mjs --pi <引擎目录> --check   # 只比对
 *
 * 确定性：TZ=UTC、HOME=/home/u（右侧的 cwd 会缩成 ~）；时间用「距现在多少毫秒」表示，偏移都取在
 * 年龄档位的中间，所以真组件读 `new Date()` 也不会跨档。
 */
process.env.TZ = "UTC";
process.env.HOME = "/home/u";
delete process.env.USERPROFILE;

import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const OUT = join(ROOT, "app", "src", "test", "resources", "pi-session-list-fixtures", "list.json");
const args = process.argv.slice(2);
const check = args.includes("--check");
const piIdx = args.indexOf("--pi");
const candidates = [piIdx >= 0 ? args[piIdx + 1] : null, process.env.PI_ENGINE, join(ROOT, "build/pi-contract/node_modules/@earendil-works/pi-coding-agent")].filter(Boolean);
const engine = candidates.find((p) => existsSync(join(p, "dist/modes/interactive/components/session-selector.js")));
if (!engine) {
	console.error(`no pi engine found (tried: ${candidates.join(", ") || "nothing"}); pass --pi <dir>`);
	process.exit(2);
}
const dist = (...p) => pathToFileURL(join(engine, "dist", ...p)).href;
const theme = await import(dist("modes/interactive/theme/theme.js"));
theme.initTheme("dark");
const { SessionSelectorComponent } = await import(dist("modes/interactive/components/session-selector.js"));
const engineVersion = JSON.parse(readFileSync(join(engine, "package.json"), "utf8")).version;

const MIN = 60_000, H = 3_600_000, D = 86_400_000;
// 偏移取在档位中间：30s → now；5min → 5m；3h → 3h；2.5d → 2d；10d → 1w；60d → 2mo；400d → 1y
const S = (id, o) => ({ id, path: `/s/${id}.jsonl`, cwd: "/home/u/app", name: undefined, firstMessage: `msg ${id}`, messageCount: 4, agoMs: 5 * MIN, parentSessionPath: undefined, ...o });
const sessions = [
	S("root-a", { name: "项目 A 重构", agoMs: 3 * H, messageCount: 12 }),
	S("a-child1", { parentSessionPath: "/s/root-a.jsonl", agoMs: 30_000, messageCount: 7, firstMessage: "继续 A 的第一支线" }),
	S("a-grand", { parentSessionPath: "/s/a-child1.jsonl", agoMs: 5 * MIN, messageCount: 2, firstMessage: "孙会话 zebra-quartz" }),
	S("a-child2", { parentSessionPath: "/s/root-a.jsonl", agoMs: 2.5 * D, messageCount: 9, name: "A 的第二支线" }),
	S("root-b", { agoMs: 10 * D, messageCount: 1, firstMessage: "line one\nline\ttwo \u0007bell", cwd: "/home/u" }),
	S("root-c", { name: "C 文档", agoMs: 60 * D, messageCount: 30, cwd: "/data/elsewhere" }),
	S("orphan", { parentSessionPath: "/s/gone.jsonl", agoMs: 400 * D, messageCount: 3, firstMessage: "父文件已不在，应当当作根" }),
	S("root-d", { agoMs: 1 * H + 30 * MIN, messageCount: 5, firstMessage: "最近的一条 1h" }),
];
const CWD = "/home/u/app";

const views = [
	{ name: "threaded · current", scope: "current", sort: "threaded", named: "all", query: "" },
	{ name: "threaded · all", scope: "all", sort: "threaded", named: "all", query: "" },
	{ name: "threaded · all · named", scope: "all", sort: "threaded", named: "named", query: "" },
	{ name: "recent · all", scope: "all", sort: "recent", named: "all", query: "" },
	{ name: "relevance · all · 子串", scope: "all", sort: "relevance", named: "all", query: "zebra" },
	{ name: "threaded · all · 有查询则平铺", scope: "all", sort: "threaded", named: "all", query: "项目" },
	{ name: "recent · all · 短语", scope: "all", sort: "recent", named: "all", query: '"line one"' },
	{ name: "relevance · all · 正则", scope: "all", sort: "relevance", named: "all", query: "re:^msg root-[ab]$" },
	{ name: "threaded · all · 没有命中", scope: "all", sort: "threaded", named: "all", query: "不存在的词" },
];

const toInfo = (s, now) => ({
	path: s.path, id: s.id, cwd: s.cwd, name: s.name, parentSessionPath: s.parentSessionPath,
	created: new Date(now - s.agoMs - 1000), modified: new Date(now - s.agoMs),
	messageCount: s.messageCount, firstMessage: s.firstMessage, allMessagesText: `${s.name ?? ""} ${s.firstMessage}`,
});
// eslint-disable-next-line no-control-regex
const ANSI = /\x1b\[[0-9;]*m/g;

async function render(view) {
	const now = Date.now();
	const infos = sessions.map((s) => toInfo(s, now));
	const loader = async (onProgress) => infos.filter((i) => i.cwd === CWD || true);
	const current = async () => infos.filter((i) => i.cwd === CWD);
	const sel = new SessionSelectorComponent(current, loader, () => {}, () => {}, () => {}, () => {}, { renameSession: async () => {} }, "/s/root-d.jsonl");
	await new Promise((r) => setTimeout(r, 30));
	if (view.scope === "all") {
		sel.toggleScope();
		await new Promise((r) => setTimeout(r, 30));
	}
	const want = view.sort === "threaded" ? 0 : view.sort === "recent" ? 1 : 2;
	for (let i = 0; i < want; i++) sel.toggleSortMode();
	if (view.named === "named") sel.toggleNameFilter();
	const list = sel.getSessionList();
	list.searchInput.setValue(view.query);
	list.filterSessions(view.query);
	const lines = list.render(200).map((l) => l.replace(ANSI, ""));
	const blank = lines.indexOf("");
	const body = lines.slice(blank + 1);
	const rows = [];
	let empty = null;
	for (const raw of body) {
		if (/^\s*\(\d+\/\d+\)\s*$/.test(raw)) continue;
		if (!raw.startsWith("› ") && !raw.startsWith("  ")) continue;
		const line = raw.slice(2);
		if (/^(No |  )/.test(raw.trim()) && body.length === 1) { empty = raw.trim(); continue; }
		const m = /^((?:│  |   )*(?:├─ |└─ ))?(.*)$/.exec(line);
		const parts = m[2].split(/\s{2,}/);
		const right = parts.pop().trim();
		const left = parts.join("  ");
		rows.push({ prefix: m[1] ?? "", text: left.trim(), right });
	}
	return { rows, empty, order: null };
}

const out = { engine: engineVersion, cwd: CWD, sessions, cases: [] };
for (const view of views) {
	const r = await render(view);
	// 行 ↔ 会话：用 text 反查太脆弱，这里让真组件的选中顺序给出 path 序
	out.cases.push({ ...view, rows: r.rows, empty: r.empty });
}

// path 序：再跑一遍，读 SessionList.filteredSessions（组件的内部结果，是「展开后的树」本身）
for (let i = 0; i < views.length; i++) {
	const view = views[i];
	const now = Date.now();
	const infos = sessions.map((s) => toInfo(s, now));
	const sel = new SessionSelectorComponent(async () => infos.filter((x) => x.cwd === CWD), async () => infos, () => {}, () => {}, () => {}, () => {}, {}, undefined);
	await new Promise((r) => setTimeout(r, 30));
	if (view.scope === "all") { sel.toggleScope(); await new Promise((r) => setTimeout(r, 30)); }
	for (let k = 0; k < (view.sort === "threaded" ? 0 : view.sort === "recent" ? 1 : 2); k++) sel.toggleSortMode();
	if (view.named === "named") sel.toggleNameFilter();
	const list = sel.getSessionList();
	list.searchInput.setValue(view.query);
	list.filterSessions(view.query);
	out.cases[i].paths = list.filteredSessions.map((n) => n.session.path);
	out.cases[i].nodes = list.filteredSessions.map((n) => ({ depth: n.depth, isLast: n.isLast, ancestorContinues: n.ancestorContinues }));
}

const json = JSON.stringify(out, null, 2) + "\n";
if (check) {
	const cur = existsSync(OUT) ? readFileSync(OUT, "utf8") : "";
	if (cur !== json) {
		console.error(`list.json 与真组件输出不一致（引擎 ${engineVersion}）。重新生成：node tools/collect-session-list-fixtures.mjs --pi <引擎目录>`);
		process.exit(1);
	}
	console.log(`ok：${out.cases.length} 个视图与真组件一致（引擎 ${engineVersion}）`);
} else {
	mkdirSync(dirname(OUT), { recursive: true });
	writeFileSync(OUT, json);
	console.log(`wrote ${OUT}（${out.cases.length} 个视图，引擎 ${engineVersion}）`);
}
