#!/usr/bin/env node
/**
 * The **tool-result fixtures**: what pi's built-in tools actually return, captured once
 * per pinned engine so the app's parser can be checked against real bytes instead of
 * against sentences someone read out of pi's source.
 *
 * ## What problem this solves
 *
 * `app/src/main/kotlin/app/pi/ui/blocks/ToolOutputParse.kt` turns a tool result's
 * `content` text and `details` into the rows the eight tool blocks paint, by matching pi's
 * own wording: the `read` footers, `grep`'s two row shapes and its empty answer, `find`'s
 * and `ls`'s lists and notices, the shell's exit sentence, `details.truncation`'s keys.
 * `tools/pi-contract.mjs`'s `tooltext` group asserts those *fragments still exist in pi's
 * files* — necessary, not sufficient: it cannot tell whether the app still parses the
 * result the fragment appears in.
 *
 * So this script runs pi's **real tool implementations** against a fixed workspace and
 * writes what they returned. `app/src/test/kotlin/app/pi/ui/blocks/ToolOutputFixtureCheck.kt`
 * then feeds each capture to the app's parser on a bare JVM. The two halves together are
 * the claim: *the bytes the pinned engine produces are bytes the app still understands.*
 *
 * ## Why it can run with no model, no network and no npm install
 *
 * pi's tools are plain objects with an `execute(toolCallId, args, signal, onUpdate, ctx)`
 * (`dist/core/tools/index.js`), so the collector imports the package's tool factories and
 * calls them directly. That is also why it is deterministic: there is no provider, no
 * model, no turn loop and no clock in the path.
 *
 * The engine comes from one of three places, in order — none of them network:
 *
 *   1. `--pi <packageDir>` (CI passes the engine the `contract` job already installed);
 *   2. `build/pi-contract/node_modules/@earendil-works/pi-coding-agent`, if that job ran;
 *   3. `app/src/main/assets/runtime/pi-engine.tgz`, unpacked into a temp dir — the payload
 *      `node tools/fetch-runtime.mjs` assembles, which is what the APK ships.
 *
 * ## Determinism
 *
 * The same engine must produce the same bytes twice. What that takes, and what is
 * normalised away *before* the fixture is written (each case records which normalisations
 * it needed, in `normalized`):
 *
 *   - a fixed workspace, fixed file contents, fixed args, fixed case order;
 *   - `TZ=UTC`, `LANG=C`, `LC_ALL=C`, a fixed `HOME` — the shell tool inherits the
 *     process environment, so these are set before any tool runs;
 *   - the temp workspace path, and any other throwaway path pi prints (a `write`'s target,
 *     `edit`'s patch headers, the shell's `Full output: <tmp>` footer), become
 *     `<workspace>` / `<full-output>`;
 *   - `details.truncation.content` is **dropped**: it is the truncated result text again
 *     (megabytes of it) and `ToolOutputParse.truncationOf` reads four other fields;
 *   - file *ordering* is not something pi promises for a multi-file search, so the two
 *     cases that span more than one file are sorted and flagged `["sorted"]`.
 *
 * ## Usage
 *
 *   node tools/collect-tool-fixtures.mjs            # (re)write the fixture
 *   node tools/collect-tool-fixtures.mjs --check    # re-collect and compare, for CI
 *   node tools/collect-tool-fixtures.mjs --pi <dir> # use an already-installed engine
 *
 * `--check` is the CI half and never writes: a mismatch means the pinned engine's tool
 * output changed, and it prints the case, the field, both values and **which pi file to
 * re-read and which App parse point to change** — the same discipline as the contract.
 */

import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const PI_PACKAGE = "@earendil-works/pi-coding-agent";
const OUT = join(ROOT, "app", "src", "test", "resources", "pi-tool-fixtures", "tools.json");
const PAYLOAD = join(ROOT, "app", "src", "main", "assets", "runtime", "pi-engine.tgz");
const STAGED_ENGINE = join(ROOT, "build", "pi-contract", "node_modules", PI_PACKAGE);

const args = process.argv.slice(2);
const checkOnly = args.includes("--check");
const piArg = args.indexOf("--pi");
const piExplicit = piArg >= 0 ? resolve(args[piArg + 1] ?? "") : null;

// ------------------------------------------------------------------ the engine

/** Where the engine to import lives, unpacking the shipped payload if nothing else is there. */
function resolveEngine() {
	const usable = (dir) => dir && existsSync(join(dir, "dist", "core", "tools", "index.js"));
	if (piExplicit) {
		if (!usable(piExplicit)) {
			console.error(`not a pi package (no dist/core/tools/index.js): ${piExplicit}`);
			process.exit(2);
		}
		return { dir: piExplicit, cleanup: () => {} };
	}
	if (usable(STAGED_ENGINE)) return { dir: STAGED_ENGINE, cleanup: () => {} };
	if (!existsSync(PAYLOAD)) {
		console.error(
			`no engine to run the tools from.\n` +
				`  none of: --pi <dir>, ${STAGED_ENGINE.replace(`${ROOT}/`, "")},\n` +
				`  ${PAYLOAD.replace(`${ROOT}/`, "")}\n` +
				`  Run \`node tools/fetch-runtime.mjs\` (assembles the payload) or\n` +
				`  \`node tools/pi-contract.mjs\` (installs the pinned engine), then re-run.`,
		);
		process.exit(2);
	}
	// The payload is the archive the APK ships: `./node_modules/@earendil-works/pi-coding-agent/…`
	// with its dependency tree nested inside it, so it can be imported directly.
	const stage = mkdtempSync(join(tmpdir(), "pi-fixture-engine-"));
	execFileSync("tar", ["-xzf", PAYLOAD, "-C", stage], { stdio: "inherit" });
	const dir = join(stage, "node_modules", PI_PACKAGE);
	if (!usable(dir)) {
		console.error(`the payload has no ${PI_PACKAGE} tools module: ${PAYLOAD}`);
		process.exit(2);
	}
	return { dir, cleanup: () => rmSync(stage, { recursive: true, force: true }) };
}

// ------------------------------------------------------------------ the cases

/**
 * One captured case.
 *
 * `probe` is not decoration: it is what `--check` prints when the capture changes, and it
 * names the pi file that produced the wording and the App function that parses it. A case
 * whose failure cannot name those two has no business being here.
 */
const WORKSPACE_FILES = {
	"hello.txt": "alpha\nbeta\ngamma\n",
	"notes.txt": "needle one\nplain\nneedle two\nneedle three\n",
	"sub/inner.txt": "inner\n",
};

/** > pi's `read` line cap (2000), so its own truncation footer is captured for real. */
const MANY_LINES = Array.from({ length: 3000 }, (_, i) => `l${i}`).join("\n") + "\n";

function cases(ws) {
	const p = (...parts) => join(ws, ...parts);
	return [
		{
			id: "bash-plain",
			tool: "bash",
			args: { command: "printf 'one\\ntwo\\n'" },
			probe:
				"plain shell output; core/tools/bash.ts's `formatOutput` returns it verbatim, and " +
				"ToolOutputParse.stripFullOutputFooter must leave it alone",
		},
		{
			id: "bash-exit-3",
			tool: "bash",
			args: { command: "printf 'partial\\n'; exit 3" },
			probe:
				"a non-zero exit THROWS (core/tools/bash.ts:363) and the number survives only in the " +
				"appended sentence; ToolOutputParse.shellExitCode reads it (ToolOutputParse.kt's EXIT_CODE)",
		},
		{
			id: "bash-no-output",
			tool: "bash",
			args: { command: "true" },
			probe: "pi's empty-output sentence (`(no output)`); ShellBlock paints it as the body",
		},
		{
			id: "bash-truncated",
			tool: "bash",
			args: { command: "awk 'BEGIN{for(i=0;i<2500;i++)print \"l\" i}'" },
			probe:
				"bash's own truncation: the `[Showing lines X-Y of N. Full output: <path>]` footer " +
				"(core/tools/bash.ts's formatOutput) and `details.truncation`; " +
				"ToolOutputParse.truncationOf + fullOutputPathOf + stripFullOutputFooter",
		},
		{
			id: "read-plain",
			tool: "read",
			args: { path: p("hello.txt") },
			probe: "core/tools/read.ts's no-footer path; ToolOutputParse.readBody must leave the body whole",
		},
		{
			id: "read-offset",
			tool: "read",
			args: { path: p("hello.txt"), offset: 2, limit: 2 },
			probe:
				"the `[N more lines in file. Use offset=Z to continue.]` footer (read.ts); " +
				"ToolOutputParse.splitReadFooter (MORE_LINES) + readRange",
		},
		{
			id: "read-truncated",
			tool: "read",
			args: { path: p("many.txt") },
			probe:
				"pi's own line cap: the `[Showing lines X-Y of N. Use offset=Z to continue.]` footer " +
				"(read.ts); the OTHER shape ToolOutputParse.splitReadFooter accepts (isReadFooter)",
		},
		{
			id: "grep-match",
			tool: "grep",
			args: { pattern: "needle", path: p("notes.txt") },
			probe:
				"grep's match row `${rel}:${line}: ${text}` (core/tools/grep.ts); " +
				"ToolOutputParse's GREP_MATCH + grepBody",
		},
		{
			id: "grep-context",
			tool: "grep",
			args: { pattern: "needle two", path: p("notes.txt"), context: 1 },
			probe:
				"grep's context row `${rel}-${line}- ${text}` (grep.ts); ToolOutputParse's GREP_CONTEXT — " +
				"and that a context row is NOT reported as a match",
		},
		{
			id: "grep-none",
			tool: "grep",
			args: { pattern: "zzz-not-there", path: p("notes.txt") },
			probe: "grep's empty answer (grep.ts's NO_MATCHES); GrepBody.empty",
		},
		{
			id: "grep-limit",
			tool: "grep",
			args: { pattern: "needle", path: p("notes.txt"), limit: 2 },
			probe:
				"grep's `[N matches limit reached …]` notice (grep.ts); GrepBody.notice + " +
				"ToolOutputParse.splitNotice (NOTICE_SUBJECT)",
		},
		{
			id: "find-one",
			tool: "find",
			args: { pattern: "hello.txt", path: ws },
			probe: "find's bare path rows (core/tools/find.ts); PathBody(Find) with Unknown entry kinds",
		},
		{
			id: "find-none",
			tool: "find",
			args: { pattern: "*.nothing-here", path: ws },
			probe: "find's empty answer (find.ts); PathBody.empty",
		},
		// There is deliberately no `find`/`ls` case that *reaches* a limit: pi stops at the
		// limit before it has an order to promise, so which of several candidates comes back
		// is not stable run to run (measured: `*.txt` with `limit: 1` returned `sub/inner.txt`
		// once and `many.txt` the next). The `limit reached` phrasing those tools share with
		// `grep` is pinned by `tools/pi-contract.mjs`'s `tooltext` group, and `grep-limit`
		// below captures the notice shape for real from a single-file search.
		{
			id: "ls-mixed",
			tool: "ls",
			args: { path: ws },
			probe:
				"ls marks directories with a `/` suffix (core/tools/ls.ts); PathEntry.kind = Directory",
			normalize: ["sorted"],
		},
		{
			id: "ls-empty",
			tool: "ls",
			args: { path: p("empty") },
			probe: "ls's `(empty directory)` (ls.ts); PathBody.empty",
		},
		{
			id: "write-ok",
			tool: "write",
			args: { path: p("written.txt"), content: "written\n" },
			probe: "write's success sentence, which names the path it wrote (core/tools/write.ts)",
		},
		{
			id: "edit-ok",
			tool: "edit",
			args: { path: p("hello.txt"), edits: [{ oldText: "beta", newText: "BETA" }] },
			probe:
				"edit's `details.diff` + `details.patch` (core/tools/edit.ts); Transcript's " +
				"detailsDiffText and DiffBlock's parser",
		},
	];
}

// ------------------------------------------------------------------ capture

/** Text a tool returned, or the message it threw with (a failing shell has no result). */
function contentOf(outcome) {
	if (outcome.threw) return outcome.message;
	return (outcome.result.content ?? [])
		.filter((block) => block.type === "text")
		.map((block) => block.text)
		.join("");
}

/**
 * `details`, reduced to the fields the app reads.
 *
 * `truncation.content` is the truncated text again — hundreds of kilobytes — and
 * `ToolOutputParse.truncationOf` reads `truncatedBy`/`outputLines`/`totalLines`/`maxBytes`.
 * Everything else at the top level is kept: `diff`, `patch`, `firstChangedLine`,
 * `matchLimitReached`, `resultLimitReached`, `fullOutputPath` are all either displayed or
 * counted by the app, and a capture that quietly dropped them would stop noticing a change.
 */
function prunedDetails(details) {
	if (details === undefined || details === null) return null;
	const copy = JSON.parse(JSON.stringify(details));
	if (copy.truncation && typeof copy.truncation === "object") delete copy.truncation.content;
	return copy;
}

function withStableKeys(value) {
	if (Array.isArray(value)) return value.map(withStableKeys);
	if (value && typeof value === "object") {
		return Object.fromEntries(Object.keys(value).sort().map((key) => [key, withStableKeys(value[key])]));
	}
	return value;
}

async function capture(toolsModule, engineVersion) {
	const ws = mkdtempSync(join(tmpdir(), "pi-fixture-ws-"));
	const home = mkdtempSync(join(tmpdir(), "pi-fixture-home-"));
	// The shell tool inherits the process environment, so these are set before it runs.
	process.env.TZ = "UTC";
	process.env.LANG = "C";
	process.env.LC_ALL = "C";
	process.env.HOME = home;

	for (const [rel, text] of Object.entries(WORKSPACE_FILES)) {
		const target = join(ws, rel);
		mkdirSync(dirname(target), { recursive: true });
		writeFileSync(target, text);
	}
	writeFileSync(join(ws, "many.txt"), MANY_LINES);
	mkdirSync(join(ws, "empty"), { recursive: true });

	const factory = {
		bash: "createBashTool",
		read: "createReadTool",
		write: "createWriteTool",
		edit: "createEditTool",
		grep: "createGrepTool",
		find: "createFindTool",
		ls: "createLsTool",
	};
	const ctx = { cwd: ws, sessionManager: { getSessionId: () => "fixture", getSessionFile: () => null } };
	const captured = [];
	for (const item of cases(ws)) {
		const tool = toolsModule[factory[item.tool]](ws);
		const outcome = { threw: false, result: null, message: null };
		try {
			outcome.result = await tool.execute(`fixture-${item.id}`, item.args, undefined, undefined, ctx);
		} catch (error) {
			outcome.threw = true;
			outcome.message = error instanceof Error ? error.message : String(error);
		}
		const normalized = [...(item.normalize ?? [])];
		let content = contentOf(outcome);
		if (content.includes(ws) || content.includes(home)) {
			content = content.split(ws).join("<workspace>").split(home).join("<home>");
			normalized.push("paths");
		}
		if (/\/tmp\/[^\s\]]+/.test(content)) {
			content = content.replace(/\/tmp\/[^\s\]]+/g, "<temp>");
			normalized.push("temp-paths");
		}
		if (normalized.includes("sorted")) {
			const lines = content.split("\n");
			const body = [];
			const footers = [];
			for (const line of lines) {
				(footers.length === 0 && line.startsWith("[") ? footers : body).push(line);
			}
			content = [...body.filter((line) => line !== "").sort(), ...footers].join("\n");
		}
		const details = prunedDetails(outcome.result?.details);
		if (details) {
			// Both shapes matter: the workspace path in an `edit` patch header and a `write`
			// target, and the throwaway `/tmp/pi-bash-<random>.log` a truncated shell names.
			// The latter is the one that made two otherwise identical runs differ.
			const scrub = (value) =>
				value.split(ws).join("<workspace>").split(home).join("<home>").replace(/\/tmp\/[^\s"\\]+/g, "<temp>");
			for (const [key, value] of Object.entries(details)) {
				if (typeof value === "string" && scrub(value) !== value) {
					details[key] = scrub(value);
					if (key === "fullOutputPath" && !normalized.includes("temp-paths")) normalized.push("temp-paths");
					else if (!normalized.includes("paths")) normalized.push("paths");
				}
			}
		}
		captured.push(
			withStableKeys({
				id: item.id,
				tool: item.tool,
				args: JSON.parse(JSON.stringify(item.args).split(ws).join("<workspace>")),
				probe: item.probe,
				normalized: [...new Set(normalized)].sort(),
				threw: outcome.threw,
				content,
				details,
			}),
		);
	}
	rmSync(ws, { recursive: true, force: true });
	rmSync(home, { recursive: true, force: true });
	return { pi: engineVersion, cases: captured };
}

// ------------------------------------------------------------------ compare

/** Field-by-field diff, named down to the field so a failure is actionable. */
function differences(expected, actual) {
	const out = [];
	const walk = (a, b, path) => {
		if (JSON.stringify(a) === JSON.stringify(b)) return;
		const aObj = a && typeof a === "object";
		const bObj = b && typeof b === "object";
		if (!aObj || !bObj || Array.isArray(a) !== Array.isArray(b)) {
			out.push({ path, expected: a, actual: b });
			return;
		}
		for (const key of [...new Set([...Object.keys(a), ...Object.keys(b)])].sort()) {
			walk(a[key], b[key], path ? `${path}.${key}` : key);
		}
	};
	walk(expected, actual, "");
	return out;
}

function main() {
	const { dir, cleanup } = resolveEngine();
	const engineVersion = JSON.parse(readFileSync(join(dir, "package.json"), "utf8")).version;
	return import(`${dir}/dist/core/tools/index.js`)
		.then((toolsModule) => capture(toolsModule, engineVersion))
		.then((fixture) => {
			const text = `${JSON.stringify(fixture, null, 2)}\n`;
			const casesWithProbes = new Map(fixture.cases.map((item) => [item.id, item.probe]));
			if (!checkOnly) {
				mkdirSync(dirname(OUT), { recursive: true });
				writeFileSync(OUT, text);
				console.log(
					`wrote ${OUT.replace(`${ROOT}/`, "")}: ${fixture.cases.length} cases from pi ${engineVersion}`,
				);
				return;
			}
			if (!existsSync(OUT)) {
				console.error(
					`${OUT.replace(`${ROOT}/`, "")} is missing.\n` +
						`  Run \`node tools/collect-tool-fixtures.mjs\` and commit the result: the Kotlin harness\n` +
						`  (app/src/test/kotlin/app/pi/ui/blocks/ToolOutputFixtureCheck.kt) reads it.`,
				);
				process.exit(1);
			}
			const expected = JSON.parse(readFileSync(OUT, "utf8"));
			// Diff by case **id**, not array position: a reordered or renamed case should read
			// as `read-offset.content`, not `5.content`, or the failure names nothing useful.
			const byId = (fixture) => ({
				pi: fixture.pi,
				cases: Object.fromEntries(fixture.cases.map((item) => [item.id, item])),
			});
			const diffs = differences(byId(expected), byId(fixture));
			if (diffs.length === 0) {
				console.log(
					`tool fixtures are current: ${fixture.cases.length} cases match pi ${engineVersion} exactly`,
				);
				return;
			}
			console.error(`\ntool fixtures are STALE (${diffs.length} field(s) differ):\n`);
			for (const diff of diffs.slice(0, 20)) {
				const path = diff.path.replace(/^cases\./, "");
				if (diff.path === "pi") {
					// Not a parser problem: the capture belongs to another release.
					console.error(`  captured from pi ${diff.expected}, this engine is ${diff.actual}`);
					console.error(
						`    the fixture is tied to the pinned engine — re-run without --check against the\n` +
							`    engine \`tools/fetch-runtime.mjs\` builds, and commit the result with the bump\n`,
					);
					continue;
				}
				const [caseId, ...rest] = path.split(".");
				console.error(`  ${path}`);
				console.error(`    committed: ${JSON.stringify(diff.expected)}`);
				console.error(`    engine:    ${JSON.stringify(diff.actual)}`);
				if (casesWithProbes.has(caseId)) console.error(`    protects:  ${casesWithProbes.get(caseId)}`);
				console.error("");
			}
			if (diffs.length > 20) console.error(`  … ${diffs.length - 20} more\n`);
			console.error(
				`  Read the pi file named above, then either update the App parser it points at or, if the\n` +
					`  App is right and only the wording moved, re-run \`node tools/collect-tool-fixtures.mjs\`\n` +
					`  and commit ${OUT.replace(`${ROOT}/`, "")} together with the App change.`,
			);
			process.exit(1);
		})
		.finally(cleanup);
}

await main();
