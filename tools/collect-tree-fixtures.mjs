#!/usr/bin/env node
/**
 * Golden data for the session-tree view model, produced by pi's OWN `/tree` component.
 *
 * `app/src/main/kotlin/app/pi/session/tree/TreeModel.kt` is a port of `TreeList` in the
 * SDK's `dist/modes/interactive/components/tree-selector.js`. A port is only as good as
 * its proof, so this script feeds fixture trees to the real `TreeSelectorComponent`
 * (the code `/tree` runs), reads back what it decided — which rows are visible, in what
 * order, at what indent, with which connector and gutters, and the whole rendered row
 * text with colours stripped — and writes that down. `TreeModelCheck` then feeds the same
 * trees to the Kotlin port and demands the same answer, row for row.
 *
 * Nothing here is from memory of the SDK: if the SDK changes its rules, regenerating
 * changes the fixture and the Kotlin check fails until the port is updated.
 *
 *   node tools/collect-tree-fixtures.mjs --pi <pi-coding-agent dir>   # (re)write
 *   node tools/collect-tree-fixtures.mjs --pi <dir> --check           # compare, never write
 *
 * Engine lookup when --pi is absent: $PI_ENGINE, then
 * build/pi-contract/node_modules/@earendil-works/pi-coding-agent.
 *
 * Determinism: TZ=UTC, a fixed HOME (the tool-call display shortens `$HOME` to `~`), fixed
 * "now" for label timestamps (the SDK reads the clock; the fixture records the instant it
 * used so the Kotlin side can be given the same one).
 */
process.env.TZ = "UTC";
process.env.HOME = "/home/u";
delete process.env.USERPROFILE;

import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const OUT = join(ROOT, "app", "src", "test", "resources", "pi-tree-fixtures", "tree.json");

const args = process.argv.slice(2);
const check = args.includes("--check");
const piIdx = args.indexOf("--pi");
const candidates = [
	piIdx >= 0 ? args[piIdx + 1] : null,
	process.env.PI_ENGINE,
	join(ROOT, "build/pi-contract/node_modules/@earendil-works/pi-coding-agent"),
].filter(Boolean);
const engine = candidates.find((p) => existsSync(join(p, "dist/modes/interactive/components/tree-selector.js")));
if (!engine) {
	console.error(`no pi engine found (tried: ${candidates.join(", ") || "nothing"}); pass --pi <dir>`);
	process.exit(2);
}

const sdk = await import(pathToFileURL(join(engine, "dist/index.js")).href);
const theme = await import(pathToFileURL(join(engine, "dist/modes/interactive/theme/theme.js")).href);
theme.initTheme("dark");
const { TreeSelectorComponent } = sdk;
const engineVersion = JSON.parse(readFileSync(join(engine, "package.json"), "utf8")).version;

// ---------------------------------------------------------------- fixtures
const ts = (n) => new Date(Date.UTC(2026, 0, 1, 0, 0, n)).toISOString();
const text = (t) => [{ type: "text", text: t }];
const msg = (id, parent, message, n = 1) => ({ type: "message", id, parentId: parent, timestamp: ts(n), message });
const user = (id, parent, t) => msg(id, parent, { role: "user", content: text(t), timestamp: 1 });
const asst = (id, parent, t, extra = {}) => msg(id, parent, { role: "assistant", content: text(t), stopReason: "stop", timestamp: 2, ...extra });
const call = (id, parent, tcId, name, argv, t = "") =>
	msg(id, parent, { role: "assistant", content: [...(t ? text(t) : []), { type: "toolCall", id: tcId, name, arguments: argv }], stopReason: "toolUse", timestamp: 2 });
const result = (id, parent, tcId, name, t = "ok") => msg(id, parent, { role: "toolResult", toolCallId: tcId, toolName: name, content: text(t), isError: false, timestamp: 3 });
const node = (entry, children = [], label, labelTimestamp) => {
	const n = { entry, children };
	if (label !== undefined) n.label = label;
	if (labelTimestamp !== undefined) n.labelTimestamp = labelTimestamp;
	return n;
};

// Fixed "now" so the label-timestamp cases are reproducible.
const NOW = "2026-06-15T12:00:00.000Z";

const fixtures = [];

// 1. branches, tool calls of every shape the display knows, hidden bookkeeping, label.
fixtures.push({
	name: "branches-and-tools",
	leafId: "f",
	tree: [
		node(user("a", null, "hello\nworld\twith tab"), [
			node(asst("b", "a", "hi"), [
				node(user("c", "b", "do x"), [
					node(call("d", "c", "t1", "read", { path: "/home/u/proj/y.ts", offset: 10, limit: 5 }), [
						node(result("e", "d", "t1", "read"), [
							node(call("e2", "e", "t2", "bash", { command: "ls -la /very/long/path/that/goes/on/and/on/and/on/and/on/forever" }), [
								node(result("e3", "e2", "t2", "bash"), [
									node(call("e4", "e3", "t3", "grep", { pattern: "foo", path: "/home/u/src" }), [
										node(result("e5", "e4", "t3", "grep"), [
											node(call("e6", "e5", "t4", "custom_tool", { a: 1, b: "two", c: [1, 2, 3], d: "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" }), [
												node(result("e7", "e6", "t4", "custom_tool"), [node(asst("f", "e7", "done x"))]),
											]),
										]),
									]),
								]),
							]),
						]),
					]),
				]),
				node(user("g", "b", "do y"), [
					node(asst("h", "g", "done y"), [], "bm", ts(9)),
					node({ type: "model_change", id: "m", parentId: "g", timestamp: ts(8), provider: "p", modelId: "mm" }, [
						node(asst("i", "m", "after model"), [
							node({ type: "thinking_level_change", id: "th", parentId: "i", timestamp: ts(11), thinkingLevel: "high" }, [node(user("i2", "th", "more"))]),
						]),
					]),
				]),
				node(user("j", "b", "do z"), [
					node(asst("k", "j", "", { stopReason: "aborted" })),
				]),
				node(user("j2", "b", "do w"), [
					node(asst("k2", "j2", "", { stopReason: "error", errorMessage: "429 rate limited\nretry later" })),
				]),
				node(user("j3", "b", "empty one"), [node(asst("k3", "j3", "", { stopReason: "stop" }))]),
			]),
		]),
	],
});

// 2. several roots (virtual root), labels, usage, custom, compaction, branch summary, bash, string content.
fixtures.push({
	name: "multi-root-and-misc",
	leafId: "r2b",
	tree: [
		node(user("r1", null, "first root"), [
			node(asst("r1a", "r1", "answer"), [
				node({ type: "compaction", id: "cp", parentId: "r1a", timestamp: ts(3), summary: "compacted summary", firstKeptEntryId: "r1a", tokensBefore: 12500 }, [
					node(user("r1b", "cp", "after compaction")),
				]),
				node({ type: "branch_summary", id: "bs", parentId: "r1a", timestamp: ts(4), fromId: "r1a", summary: "went down a path\nand came back" }, [
					node(msg("bash1", "bs", { role: "bashExecution", command: "echo   hi\nthere", output: "hi", exitCode: 0, cancelled: false, truncated: false, timestamp: 4 }), [
						node({ type: "custom_message", id: "cm", parentId: "bash1", timestamp: ts(5), customType: "my-ext", content: "plain string content\nsecond line", display: true }),
					]),
				]),
			], "important", ts(100)),
		]),
		node(user("r2", null, "second root"), [
			node(asst("r2a", "r2", "reply"), [
				node({ type: "usage", id: "us", parentId: "r2a", timestamp: ts(6), kind: "x", provider: "p", model: "m" }, [
					node(user("r2b", "us", "after usage")),
				]),
				node({ type: "session_info", id: "si", parentId: "r2a", timestamp: ts(6), name: "My Title" }, [node(user("r2c", "si", "after title"))]),
				node({ type: "session_info", id: "si2", parentId: "r2a", timestamp: ts(7) }, []),
				node({ type: "custom", id: "cu", parentId: "r2a", timestamp: ts(7), customType: "state", data: { a: 1 } }, []),
				node({ type: "context_edit", id: "ce", parentId: "r2a", timestamp: ts(8), targetId: "r2a", replacement: null }, []),
				node({ type: "context_edit", id: "ce2", parentId: "r2a", timestamp: ts(8), targetId: "r2a", replacement: { role: "user", content: [{ type: "text", text: "x" }] } }, []),
				node({ type: "label", id: "lb", parentId: "r2a", timestamp: ts(9), targetId: "r2a", label: "mark" }, []),
				node({ type: "label", id: "lb2", parentId: "r2a", timestamp: ts(9), targetId: "r2a" }, []),
			]),
		]),
		node(user("r3", null, "third root, long: " + "z".repeat(260))),
	],
});

// 3. a deep single chain with one late fork: nothing may drift right on a chain.
{
	const chain = (from, parent, n, last) => {
		let cur = last;
		for (let i = n; i >= 1; i--) {
			const id = `${from}${i}`;
			const pid = i === 1 ? parent : `${from}${i - 1}`;
			cur = node(i % 2 ? user(id, pid, `q${i}`) : asst(id, pid, `a${i}`), cur ? [cur] : []);
		}
		return cur;
	};
	const tail = node(user("fork", "c6", "fork here"), [node(asst("fx", "fork", "x1")), node(asst("fy", "fork", "y1"), [node(user("fy2", "fy", "y2"))])]);
	fixtures.push({ name: "deep-chain-late-fork", leafId: "fx", tree: [chain("c", null, 6, tail)] });
}

// 4. the current leaf is a tool-call-only assistant (kept visible), and one that is hidden by a filter.
fixtures.push({
	name: "leaf-is-tool-call",
	leafId: "tc",
	tree: [node(user("u", null, "go"), [node(call("tc", "u", "x1", "read", { path: "/a/b" })), node(asst("ok", "u", "other branch"))])],
});
fixtures.push({
	name: "leaf-hidden-by-filter",
	leafId: "m2",
	tree: [node(user("u", null, "go"), [node(asst("a", "u", "hi"), [node({ type: "model_change", id: "m2", parentId: "a", timestamp: ts(2), provider: "p", modelId: "x" }, [node(user("w", "m2", "next"))])])])],
});

// ---------------------------------------------------------------- scenarios
const FILTERS = ["default", "no-tools", "user-only", "labeled-only", "all"];
const strip = (s) => s.replace(/\x1b\[[0-9;]*m/g, "");

function rowsOf(list) {
	const lines = list.render(4000).map(strip);
	// last line is the "(i/n) [mode]" status; the rest are rows with a 2-column cursor gutter.
	const body = list.filteredNodes.length === 0 ? [] : lines.slice(0, -1);
	return list.filteredNodes.map((n, i) => ({
		id: n.node.entry.id,
		indent: n.indent,
		showConnector: n.showConnector,
		isLast: n.isLast,
		virtualRoot: n.isVirtualRootChild,
		gutters: n.gutters.map((g) => [g.position, g.show]),
		foldable: list.isFoldable(n.node.entry.id),
		line: body[i].slice(2).replace(/\s+$/, ""),
	}));
}

function scenario(fx, filter, { query = "", folded = [], timestamps = false } = {}) {
	const sel = new TreeSelectorComponent(structuredClone(fx.tree), fx.leafId, 100000, () => {}, () => {}, () => {}, undefined, filter);
	const list = sel.treeList;
	list.maxVisibleLines = 100000;
	const nearest = list.filteredNodes[list.selectedIndex]?.node.entry.id ?? null;
	const pristine = !query && folded.length === 0;
	if (!pristine || timestamps) {
		list.searchQuery = query;
		for (const id of folded) list.foldedNodes.add(id);
		list.showLabelTimestamps = timestamps;
		list.applyFilter();
	}
	return { filter, query, folded, timestamps, nearest: pristine ? nearest : null, rows: rowsOf(list) };
}

const RealDate = Date;
// Pin "now" inside the SDK's formatLabelTimestamp (it calls `new Date()`).
globalThis.Date = class extends RealDate {
	constructor(...a) { a.length ? super(...a) : super(NOW); }
	static now() { return new RealDate(NOW).getTime(); }
};

const out = { engineVersion, now: NOW, home: "/home/u", fixtures: [], labelTimestamps: [] };
for (const fx of fixtures) {
	const scenarios = [];
	for (const f of FILTERS) scenarios.push(scenario(fx, f));
	// Every foldable node in the default and "all" views, one at a time, then all of them.
	for (const f of ["default", "all"]) {
		const base = scenario(fx, f);
		const foldables = base.rows.filter((r) => r.foldable).map((r) => r.id);
		for (const id of foldables) scenarios.push(scenario(fx, f, { folded: [id] }));
		if (foldables.length > 1) scenarios.push(scenario(fx, f, { folded: foldables }));
	}
	for (const q of ["x", "DO", "do y", "user hello", "assistant", "nomatch", "bm", "important", "model", "title", "custom state", "read", "mark label", "  "]) {
		for (const f of ["default", "all"]) scenarios.push(scenario(fx, f, { query: q }));
	}
	scenarios.push(scenario(fx, "labeled-only", { timestamps: true }));
	scenarios.push(scenario(fx, "all", { timestamps: true }));
	out.fixtures.push({ name: fx.name, leafId: fx.leafId, tree: fx.tree, scenarios });
}

// label timestamp formatting: today / this year / earlier year / midnight edges
{
	const sel = new TreeSelectorComponent([node(user("u", null, "x"))], "u", 10, () => {}, () => {}, () => {}, undefined, "default");
	for (const iso of ["2026-06-15T09:05:00.000Z", "2026-06-15T00:00:00.000Z", "2026-06-14T23:59:00.000Z", "2026-01-02T03:04:00.000Z", "2025-12-31T23:59:00.000Z", "2024-02-29T10:00:00.000Z", "1999-07-04T05:06:00.000Z"]) {
		out.labelTimestamps.push({ iso, text: sel.treeList.formatLabelTimestamp(iso) });
	}
}
globalThis.Date = RealDate;

const text_ = JSON.stringify(out, null, 1) + "\n";
if (check) {
	if (!existsSync(OUT)) { console.error(`${OUT} is missing`); process.exit(1); }
	if (readFileSync(OUT, "utf8") !== text_) {
		console.error(`tree fixture is stale against pi ${engineVersion}: re-run without --check and port whatever changed into TreeModel.kt`);
		process.exit(1);
	}
	console.log(`tree fixture matches pi ${engineVersion}`);
} else {
	mkdirSync(dirname(OUT), { recursive: true });
	writeFileSync(OUT, text_);
	const n = out.fixtures.reduce((s, f) => s + f.scenarios.length, 0);
	console.log(`wrote ${OUT.replace(`${ROOT}/`, "")}: ${out.fixtures.length} trees, ${n} scenarios from pi ${engineVersion}`);
}
