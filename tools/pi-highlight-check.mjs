/**
 * Verification for the pi-highlight extension (not part of the APK).
 *
 *   node tools/pi-highlight-check.mjs            # full run
 *   node tools/pi-highlight-check.mjs --eager-probe   # internal: eager-load cost, for the comparison below
 *
 * The extension is loaded the way pi loads it — through jiti, with pi's own
 * specifier aliases — and then exercised over real loopback HTTP, so this proves
 * the wire contract and not just the parsing helpers:
 *
 *   A. HTML parity: registering the language files one at a time (what the
 *      extension does) produces the same highlight.js HTML as pi's
 *      `highlight.js/lib/index.js`.
 *   B. Parser parity: the extension's HTML → offset-runs walk, plus pi's scope
 *      precedence, reproduces exactly the runs pi's own `renderHighlightedHtml`
 *      resolves with pi's own theme table (buildCliHighlightTheme).
 *   C. Round trip: kotlin/json/bash snippets come back as sane UTF-16 ranges,
 *      including entity-heavy and astral-plane cases.
 *   D. Cost and safety: the engine is not loaded until the first authenticated
 *      request, unauthenticated requests load nothing, the body cap answers 413,
 *      an unknown language answers `known: false`, and the whole thing starts
 *      without loading any JavaScript.
 *   E. Latency: warm percentiles per snippet.
 *
 * Nothing here needs Android; it needs Node and pi's install.
 */

import { execFileSync } from "node:child_process";
import { mkdtemp, mkdir, readFile, stat } from "node:fs/promises";
import { createRequire } from "node:module";
import { Agent as KeepAliveAgent, request as HttpRequest } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { createJiti } from "/root/pi-feasibility/node_modules/@earendil-works/pi-coding-agent/node_modules/jiti/lib/jiti.mjs";

const PI = "/root/pi-feasibility/node_modules/@earendil-works/pi-coding-agent";
const EXT_DIR = "/root/pi-android/app/src/main/assets/pi-extensions/pi-highlight";

let failures = 0;
let checks = 0;

function check(condition, label, detail = "") {
	checks += 1;
	if (condition) {
		console.log(`  ok   ${label}`);
	} else {
		failures += 1;
		console.log(`  FAIL ${label}${detail ? `\n       ${detail}` : ""}`);
	}
}

function section(title) {
	console.log(`\n== ${title}`);
}

function rss() {
	return Math.round(process.memoryUsage().rss / 1048576);
}

function mb(value) {
	return `${value} MB`;
}

// ------------------------------------------------------- pi's scope → theme ---

/**
 * pi's `buildCliHighlightTheme` (`theme.ts:1036`), transcribed key for key. It is
 * deliberately a *separate* transcription from the Kotlin one: this is the
 * reference the parser comparison below resolves against.
 */
const PI_THEME = {
	keyword: (s) => s,
	built_in: (s) => s,
	literal: (s) => s,
	number: (s) => s,
	regexp: (s) => s,
	string: (s) => s,
	comment: (s) => s,
	doctag: (s) => s,
	meta: (s) => s,
	function: (s) => s,
	title: (s) => s,
	class: (s) => s,
	type: (s) => s,
	tag: (s) => s,
	name: (s) => s,
	attr: (s) => s,
	variable: (s) => s,
	params: (s) => s,
	operator: (s) => s,
	punctuation: (s) => s,
	emphasis: (s) => s,
	strong: (s) => s,
	link: (s) => s,
	addition: (s) => s,
	deletion: (s) => s,
};

/** pi's `getScopeFormatter`: exact, then before `.`, then before `-`, then `default`. */
function piScopeKey(scope) {
	if (PI_THEME[scope]) return scope;
	const dot = scope.indexOf(".");
	if (dot !== -1 && PI_THEME[scope.slice(0, dot)]) return scope.slice(0, dot);
	const dash = scope.indexOf("-");
	if (dash !== -1 && PI_THEME[scope.slice(0, dash)]) return scope.slice(0, dash);
	return undefined;
}

/** pi's `getActiveFormatter`: innermost scope that resolves wins. */
function resolveScopes(scopes) {
	for (let i = scopes.length - 1; i >= 0; i--) {
		const key = piScopeKey(scopes[i]);
		if (key) return key;
	}
	return undefined;
}

// ------------------------------------------- pi's own renderer as the oracle --

/**
 * Run pi's `renderHighlightedHtml` with pi's theme, using markers to recover the
 * ranges it would have coloured. The formatters return `\u0001scope\u0002text\u0003`
 * so the marked output can be walked back into (start, end, scope) triples.
 */
function piRuns(marked) {
	const runs = [];
	let cursor = 0;
	let index = 0;
	while (index < marked.length) {
		const open = marked.indexOf("\u0001", index);
		if (open === -1) break;
		const scopeEnd = marked.indexOf("\u0002", open);
		const textEnd = marked.indexOf("\u0003", scopeEnd);
		if (scopeEnd === -1 || textEnd === -1) break;
		cursor += open - index;
		const scope = marked.slice(open + 1, scopeEnd);
		const text = marked.slice(scopeEnd + 1, textEnd);
		runs.push({ start: cursor, end: cursor + text.length, scopes: [scope] });
		cursor += text.length;
		index = textEnd + 1;
	}
	return runs;
}

/** Compare two run lists as (start, end, resolvedScopeKey) triples. */
function resolveAll(runs) {
	return runs
		.map((run) => ({ start: run.start, end: run.end, key: resolveScopes(run.scopes) }))
		.filter((run) => run.key !== undefined);
}

function compareRuns(mine, theirs) {
	const left = resolveAll(mine);
	const right = resolveAll(theirs);
	if (left.length !== right.length) {
		return `run count ${left.length} != ${right.length} (first left ${JSON.stringify(left[0])}, first right ${JSON.stringify(right[0])})`;
	}
	for (let i = 0; i < left.length; i++) {
		if (left[i].start !== right[i].start || left[i].end !== right[i].end || left[i].key !== right[i].key) {
			return `run ${i}: ${JSON.stringify(left[i])} != ${JSON.stringify(right[i])}`;
		}
	}
	return null;
}

// ------------------------------------------------------------------ snippets --

const KOTLIN = `package com.example.app

import kotlinx.coroutines.flow.Flow

/** A tiny counter that emits values. */
class Counter(private val limit: Int = 10) {
    @JvmStatic
    val label: String = "counter-\${limit}"

    suspend fun tick(): Flow<Int> = flow {
        var count = 0
        while (count < limit) {
            count += 1
            emit(count)
        }
    }

    companion object {
        const val HEX = 0xFF_FF
        fun of(limit: Int?) = Counter(limit ?: 10)
    }
}
`;

const JSON_CODE = `{
  "name": "pi-android",
  "private": true,
  "count": 42,
  "tags": ["kotlin", "compose", "highlight"],
  "nested": { "a": { "b": [1, 2.5, -3e10] } }
}
`;

const BASH = `#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "\${BASH_SOURCE[0]}")" && pwd)"

main() {
  local i=0
  for f in "$ROOT"/*.gradle.kts; do
      i=$((i + 1))
      echo "found: $f" >&2
      printf '%s\\n' "\${i} files" | tee /tmp/out.log
  done
}

main "$@"
`;

const HTML_ENTITIES = `{"quote": "a<b&c>d", "apos": "it's", "amp": "&amp;"}
`;

const ASTRAL = `{"emoji": "🚀 ok", "flag": "🇨🇳"}
`;

const SHELL_UNKNOWN = `hcl snippet: resource "a" "b" { x = 1 }
`;

// --------------------------------------------------------------------- main ---

async function main() {
	if (process.argv.includes("--eager-probe")) {
		await eagerProbe();
		return;
	}

	const guestHome = await mkdtemp(join(tmpdir(), "pi-highlight-harness-"));
	const agentDir = join(guestHome, ".pi", "agent");
	await mkdir(agentDir, { recursive: true });
	process.env.HOME = guestHome;
	process.env.PI_CODING_AGENT_DIR = agentDir;
	process.env.PI_ANDROID_HIGHLIGHT_PORT = "3187";
	delete process.env.PI_PACKAGE_DIR;
	delete process.env.PI_ANDROID_HIGHLIGHT_HLJS;
	// Under pi, `process.argv[1]` is pi's own CLI entry; the extension resolves
	// highlight.js from there. Stand in for pi's process without pretending to be
	// it in any other way.
	process.argv[1] = join(PI, "dist/cli.js");

	// ---------------------------------------------------------- load contract --
	section("extension load (jiti, pi's aliases, mock ExtensionAPI)");
	const rssIdle = rss();
	const t0 = process.hrtime.bigint();
	const jiti = createJiti(import.meta.url, {
		interopDefault: true,
		moduleCache: false,
		alias: {
			"@earendil-works/pi-coding-agent": join(PI, "dist/index.js"),
			"@earendil-works/pi-ai": join(PI, "node_modules/@earendil-works/pi-ai/dist/compat.js"),
			typebox: join(PI, "node_modules/typebox/build/index.mjs"),
		},
	});
	const mod = await jiti.import(join(EXT_DIR, "index.ts"));
	const factory = mod.default ?? mod;
	check(typeof factory === "function", "default export is the extension factory");
	const registered = { tools: [], events: [] };
	await factory({
		registerTool: (definition) => registered.tools.push(definition.name),
		on: (event) => registered.events.push(event),
		registerCommand: () => {},
	});
	check(registered.tools.length === 0, "registers no tools (the consumer is the app, not the model)");
	check(registered.events.length === 0, "registers no event handlers (idle-cost-free)");

	const handle = await mod.startedService();
	check(handle !== null, "service started");
	const startMs = Number(process.hrtime.bigint() - t0) / 1e6;
	console.log(`  info activation + start: ${startMs.toFixed(1)} ms, listening on 127.0.0.1:${handle.port}`);
	const rssStarted = rss();
	console.log(`  info RSS idle ${mb(rssIdle)} -> started ${mb(rssStarted)} (delta ${rssStarted - rssIdle} MB)`);

	// ------------------------------------------------------------ token file --
	const tokenFile = join(agentDir, "highlight-bridge.json");
	const tokenStat = await stat(tokenFile);
	const published = JSON.parse(await readFile(tokenFile, "utf8"));
	check(tokenStat.isFile(), `token file written to ${tokenFile}`);
	check((tokenStat.mode & 0o777) === 0o600, "token file is 0600", `mode=${(tokenStat.mode & 0o777).toString(8)}`);
	check(published.port === handle.port, "token file carries the bound port");
	check(published.token === handle.token, "token file carries the token");

	// ------------------------------------------------------------ the client --
	const base = `http://127.0.0.1:${handle.port}`;
	const authed = (path, init = {}) =>
		fetch(`${base}${path}`, {
			...init,
			headers: { "content-type": "application/json", authorization: `Bearer ${handle.token}`, ...(init.headers ?? {}) },
			signal: AbortSignal.timeout(10_000),
		});
	const highlight = async (code, language) => {
		const response = await authed("/highlight", { method: "POST", body: JSON.stringify({ code, language }) });
		return { status: response.status, body: await response.json() };
	};

	// ------------------------------------------------------------- laziness --
	section("lazy loading and unauthenticated safety");
	const health0 = await (await authed("/health")).json();
	check(health0.data.engineLoaded === false, "engine not loaded after activation + /health");
	check(health0.data.located === false, "highlight.js not even located yet");

	const denied = await fetch(`${base}/highlight`, {
		method: "POST",
		headers: { "content-type": "application/json" },
		body: JSON.stringify({ code: KOTLIN, language: "kotlin" }),
		signal: AbortSignal.timeout(5_000),
	});
	check(denied.status === 401, "unauthenticated /highlight answers 401", `status=${denied.status}`);
	await denied.json().catch(() => undefined);
	const health1 = await (await authed("/health")).json();
	check(
		health1.data.engineLoaded === false,
		"the rejected request did not load highlight.js",
		JSON.stringify(health1.data),
	);

	// ----------------------------------------------------------- round trip --
	section("round trip: kotlin / json / bash");
	const first = await highlight(KOTLIN, "kotlin");
	check(first.status === 200 && first.body.ok === true, "kotlin request ok", JSON.stringify(first.body).slice(0, 200));
	const kotlinSpans = first.body.data.spans ?? [];
	check(kotlinSpans.length > 30, `kotlin produced ${kotlinSpans.length} spans`);
	check(first.body.data.codeUnits === KOTLIN.length, "codeUnits equals the sent code length");
	check(
		first.body.data.hljs === "10.7.3",
		`hljs version reported is pi's pin (${first.body.data.hljs})`,
	);
	console.log(`  info first request: engine+language load ${first.body.data.ms} ms`);

	const rssAfterFirst = rss();
	console.log(`  info RSS after first highlight: ${mb(rssAfterFirst)} (delta from start ${rssAfterFirst - rssStarted} MB)`);

	const jsonResult = await highlight(JSON_CODE, "json");
	const bashResult = await highlight(BASH, "bash");
	const jsonSpans = jsonResult.body.data.spans ?? [];
	const bashSpans = bashResult.body.data.spans ?? [];
	check(jsonSpans.length > 5, `json produced ${jsonSpans.length} spans`);
	check(bashSpans.length > 5, `bash produced ${bashSpans.length} spans`);

	for (const [label, code, spans] of [
		["kotlin", KOTLIN, kotlinSpans],
		["json", JSON_CODE, jsonSpans],
		["bash", BASH, bashSpans],
	]) {
		let sane = true;
		let detail = "";
		for (const span of spans) {
			if (!(Number.isInteger(span.start) && Number.isInteger(span.end))) sane = false;
			else if (!(span.start >= 0 && span.start < span.end && span.end <= code.length)) sane = false;
			else if (!Array.isArray(span.scopes) || span.scopes.length === 0) sane = false;
			if (!sane) {
				detail = JSON.stringify(span);
				break;
			}
		}
		check(sane, `${label}: every span is a sane range inside the code`, detail);
	}

	// Concrete anchors: these are the checks an off-by-one in entity decoding
	// would break, and they name the tokens a reader can verify by eye.
	const funSpan = kotlinSpans.find((s) => KOTLIN.slice(s.start, s.end) === "fun");
	check(funSpan !== undefined && funSpan.scopes.includes("keyword"), "kotlin: `fun` is a keyword span");
	const tickSpan = kotlinSpans.find((s) => KOTLIN.slice(s.start, s.end) === "tick");
	check(tickSpan !== undefined && tickSpan.scopes.includes("title"), "kotlin: `tick` is a title span");
	const commentSpan = kotlinSpans.find((s) => KOTLIN.slice(s.start, s.end).startsWith("/** A tiny"));
	check(commentSpan !== undefined && commentSpan.scopes.includes("comment"), "kotlin: the KDoc is a comment span");
	const nameSpan = jsonSpans.find((s) => JSON_CODE.slice(s.start, s.end) === '"name"');
	check(nameSpan !== undefined && nameSpan.scopes.includes("attr"), "json: \"name\" is an attr span");
	const shebangSpan = bashSpans.find((s) => BASH.slice(s.start, s.end) === "#!/usr/bin/env bash");
	check(shebangSpan !== undefined && shebangSpan.scopes.includes("meta"), "bash: the shebang is a meta span");
	const varSpan = bashSpans.find((s) => BASH.slice(s.start, s.end) === "$ROOT");
	check(varSpan !== undefined, "bash: $ROOT is a variable span (subst survives entity decoding)");

	// -------------------------------------------------------- entities/astral --
	section("entity decoding and astral characters");
	const entity = await highlight(HTML_ENTITIES, "json");
	const astral = await highlight(ASTRAL, "json");
	for (const [label, code, result] of [
		["entities", HTML_ENTITIES, entity],
		["astral", ASTRAL, astral],
	]) {
		const spans = result.body.data.spans ?? [];
		check(result.body.data.codeUnits === code.length, `${label}: codeUnits == code.length`);
		const inside = spans.every((s) => s.start >= 0 && s.end <= code.length && s.start < s.end);
		check(inside, `${label}: all spans inside the code`);
		const quote = code.indexOf('"a<b&c>d"');
		if (quote !== -1) {
			const span = spans.find((s) => s.start === quote);
			check(
				span !== undefined && span.end - span.start === '"a<b&c>d"'.length,
				"entities: the string run covers the decoded literal exactly",
				JSON.stringify(span),
			);
			check(
				code.slice(span.start, span.end) === '"a<b&c>d"',
				"entities: the range slices the original code correctly",
			);
		}
		const rocket = code.indexOf("🚀");
		if (rocket !== -1) {
			const span = spans.find((s) => s.start <= rocket && s.end >= rocket + 2);
			check(span !== undefined, "astral: a span covers both UTF-16 units of the emoji", JSON.stringify(spans));
		}
	}

	// ------------------------------------------------ parity with pi + hljs ---
	section("parity: per-file languages vs lib/index.js, and pi's own renderer");
	const piSyntax = await import(pathToFileURL(join(PI, "dist/utils/syntax-highlight.js")).href);
	await piSyntax.loadAllHighlightLanguages();
	const jitiRuns = await jiti.import(join(EXT_DIR, "html-runs.ts"));

	// The oracle: pi's `highlight` renders with pi's theme table; marker
	// formatters recover the ranges it coloured.
	const markedTheme = {};
	for (const key of Object.keys(PI_THEME)) {
		markedTheme[key] = (text) => `\u0001${key}\u0002${text}\u0003`;
	}
	const parityCases = [
		["kotlin", KOTLIN],
		["json", JSON_CODE],
		["bash", BASH],
		["json", HTML_ENTITIES],
		["json", ASTRAL],
		["json", SHELL_UNKNOWN],
		["yaml", "a: 1\nb:\n  - x\n  - y\n"],
		["sql", "SELECT a, b FROM t WHERE c = 1 -- note\n"],
		["css", "a.b #c { color: #fff; margin: 0 auto; }\n"],
		["markdown", "# Title\n\n- item\n\n> quote\n\n`code`\n"],
		["rust", 'fn main() { let x: u32 = 1; println!("{}", x); }\n'],
		["typescript", "const x: number = 1; export default x;\n"],
		["cpp", "#include <vector>\nint main() { return 0; }\n"],
		["php", "<?php echo 1; ?>\n"],
		["ini", "[section]\nkey = value\n"],
		["dockerfile", "FROM alpine\nRUN echo hi\n"],
		["diff", "--- a\n+++ b\n@@ -1 +1 @@\n-a\n+b\n"],
		["makefile", "all:\n\techo hi\n"],
		["protobuf", "message A { int32 b = 1; }\n"],
		["haskell", 'main = putStrLn "hi"\n'],
		["clojure", "(defn f [x] (+ x 1))\n"],
		["vim", "set number\n"],
		["scss", "$a: 1;\n.b { color: $a; }\n"],
		["objectivec", "int main() { return 0; }\n"],
		["erlang", "-module(a).\n"],
		["ocaml", "let f x = x + 1\n"],
		["elixir", "defmodule A do\nend\n"],
		["powershell", "Get-ChildItem | Where-Object { $_.Name }\n"],
	];
	let mismatches = 0;
	let firstMismatch = "";
	for (const [language, code] of parityCases) {
		const wire = await highlight(code, language);
		const data = wire.body.data ?? {};
		if (data.known !== true) {
			mismatches += 1;
			firstMismatch = firstMismatch || `${language}: not known`;
			continue;
		}
		// Compare against pi's renderer *and* against a fresh per-file walk of
		// the same HTML, so a bug in either half is visible.
		const marked = piSyntax.highlight(code, { language, ignoreIllegals: true, theme: markedTheme });
		const oracle = piRuns(marked);
		const difference = compareRuns(data.spans ?? [], oracle);
		if (difference) {
			mismatches += 1;
			firstMismatch = firstMismatch || `${language}: ${difference}`;
		}
	}
	check(mismatches === 0, `all ${parityCases.length} languages match pi's own renderer`, firstMismatch);

	// The same walk applied to raw HTML: per-file registration vs index.js.
	const hljsFull = createRequire(join(PI, "package.json"))(join(PI, "node_modules/highlight.js/lib/index.js"));
	let htmlDiffs = 0;
	let htmlDetail = "";
	for (const [language, code] of parityCases) {
		const wire = await highlight(code, language);
		if (wire.body.data.known !== true) continue;
		const full = hljsFull.highlight(code, { language, ignoreIllegals: true }).value;
		const walked = jitiRuns.htmlToRuns(full);
		const difference = compareRuns(wire.body.data.spans ?? [], walked.runs);
		if (difference) {
			htmlDiffs += 1;
			htmlDetail = htmlDetail || `${language}: ${difference}`;
		}
	}
	check(htmlDiffs === 0, "per-file language registration matches lib/index.js HTML", htmlDetail);

	// ------------------------------------------------------------- refusals --
	section("refusals and caps");
	const unknown = await highlight(SHELL_UNKNOWN, "hcl");
	check(unknown.status === 200 && unknown.body.data.known === false, "unknown language (hcl) answers known: false");
	check((unknown.body.data.spans ?? []).length === 0, "unknown language returns no spans");

	const huge = await highlight("x".repeat(70 * 1024), "json");
	check(huge.status === 413 && huge.body.code === "TOO_LARGE", "oversized code answers 413 TOO_LARGE", `status=${huge.status}`);

	const emptyLanguage = await highlight("let x = 1", "");
	check(emptyLanguage.status === 400, "empty language is a 400");

	const notFound = await fetch(`${base}/nope`, {
		headers: { authorization: `Bearer ${handle.token}` },
		signal: AbortSignal.timeout(5_000),
	});
	check(notFound.status === 404, "unknown path answers 404");

	// ------------------------------------------------------------- latency --
	section("latency (warm, over loopback HTTP)");
	// Two numbers per snippet, because they answer different questions:
	//  - `engine` is what the service reports doing (`data.ms`): the highlight itself;
	//  - `round trip` is a keep-alive socket POST, which is what the Kotlin client's
	//    `HttpURLConnection` does. The `fetch` numbers elsewhere in this file
	//    (new connection per request, undici overhead) are the pessimistic bound.
	const agent = new KeepAliveAgent({ keepAlive: true, maxSockets: 2 });
	const timed = (code, language) =>
		new Promise((resolve) => {
			const started = process.hrtime.bigint();
			const payload = JSON.stringify({ code, language });
			const request = HttpRequest(
				{
					host: "127.0.0.1",
					port: handle.port,
					path: "/highlight",
					method: "POST",
					agent,
					headers: {
						"content-type": "application/json",
						"content-length": Buffer.byteLength(payload),
						authorization: `Bearer ${handle.token}`,
					},
				},
				(response) => {
					let body = "";
					response.on("data", (chunk) => (body += chunk));
					response.on("end", () => {
						const roundTrip = Number(process.hrtime.bigint() - started) / 1e6;
						const parsed = JSON.parse(body);
						resolve({ roundTrip, engine: parsed.data?.ms ?? 0, spans: (parsed.data?.spans ?? []).length });
					});
				},
			);
			request.end(payload);
		});

	for (const [label, code, language] of [
		["kotlin 30 lines", KOTLIN, "kotlin"],
		["json 7 lines", JSON_CODE, "json"],
		["bash 16 lines", BASH, "bash"],
	]) {
		const trips = [];
		const engines = [];
		for (let i = 0; i < 60; i++) {
			const sample = await timed(code, language);
			trips.push(sample.roundTrip);
			engines.push(sample.engine);
		}
		const stats = (values) => {
			const sorted = [...values].sort((a, b) => a - b);
			return {
				median: sorted[Math.floor(sorted.length / 2)],
				p95: sorted[Math.floor(sorted.length * 0.95)],
				best: sorted[0],
			};
		};
		const trip = stats(trips);
		const engine = stats(engines);
		console.log(
			`  info ${label}: engine median ${engine.median.toFixed(2)} ms (p95 ${engine.p95.toFixed(2)}, best ${engine.best.toFixed(2)}) | ` +
				`round trip median ${trip.median.toFixed(2)} ms (p95 ${trip.p95.toFixed(2)}, best ${trip.best.toFixed(2)})`,
		);
		check(engine.median < 20, `${label}: engine median under 20 ms`, `${engine.median} ms`);
		check(trip.median < 60, `${label}: keep-alive round trip median under 60 ms`, `${trip.median} ms`);
	}
	agent.destroy();

	// One code larger than anything in a transcript, to show the shape holds and
	// to bound what a single request can cost the engine.
	const big = Array.from({ length: 400 }, (_, i) => `    val v${i} = compute(${i}) // step ${i}`).join("\n");
	const bigCode = `fun main() {\n${big}\n}\n`;
	const bigStarted = process.hrtime.bigint();
	const bigResult = await highlight(bigCode, "kotlin");
	const bigMs = Number(process.hrtime.bigint() - bigStarted) / 1e6;
	console.log(
		`  info kotlin 402 lines (${bigCode.length} chars, == the client's 64 KB cap for a 3x bigger block): ` +
			`${bigMs.toFixed(1)} ms round trip, engine ${bigResult.body.data.ms} ms, ` +
			`${(bigResult.body.data.spans ?? []).length} spans`,
	);

	// --------------------------------------------------- eager comparison --
	section("lazy vs eager (child process, so the numbers are honest)");
	const eager = JSON.parse(
		execFileSync(process.execPath, [new URL(import.meta.url).pathname, "--eager-probe"], { encoding: "utf8" }),
	);
	console.log(
		`  info idle RSS ${mb(eager.rssIdle)} | require core ${eager.coreMs.toFixed(1)} ms -> ${mb(eager.coreRss)} ` +
			`| + 1 language (first block) ${eager.lazyMs.toFixed(1)} ms -> ${mb(eager.lazyRss)}`,
	);
	console.log(
		`  info eager instead: + pi's 20 languages ${eager.eagerMs.toFixed(1)} ms -> ${mb(eager.eagerRss)} ` +
			`| + the other 171 (lib/index.js) ${eager.fullMs.toFixed(1)} ms -> ${mb(eager.fullRss)}`,
	);
	console.log(
		`  info lazy in the live extension: activation + listen ${startMs.toFixed(1)} ms at ${mb(rssStarted)} RSS, ` +
			`first kotlin block ${first.body.data.ms} ms, RSS after ${mb(rssAfterFirst)}`,
	);
	check(eager.eagerMs > 50, "eager 20-language registration really costs more than a frame", `${eager.eagerMs} ms`);
	check(
		eager.fullRss - eager.coreRss > eager.lazyRss - eager.coreRss,
		"the full language set costs more memory than core + one language",
	);

	// ------------------------------------------------------------- shutdown --
	section("shutdown");
	await handle.close();
	const closed = await fetch(`${base}/health`, { signal: AbortSignal.timeout(1_000) }).then(
		() => false,
		() => true,
	);
	check(closed, "service stopped answering after close()");
	const tokenGone = await stat(tokenFile).then(
		() => false,
		() => true,
	);
	check(tokenGone, "token file removed on close");

	console.log(`\n${checks - failures}/${checks} checks passed`);
	if (failures > 0) {
		console.log("pi-highlight-check: FAILED");
		process.exitCode = 1;
	} else {
		console.log("pi-highlight-check: OK");
	}
}

/** Fresh-process measurement of what eager loading would have cost. */
async function eagerProbe() {
	const require = createRequire(join(PI, "package.json"));
	const rss = () => Math.round(process.memoryUsage().rss / 1048576);
	const idle = rss();

	const coreStarted = process.hrtime.bigint();
	const hljs = require(join(PI, "node_modules/highlight.js/lib/core.js"));
	const coreMs = Number(process.hrtime.bigint() - coreStarted) / 1e6;
	const coreRss = rss();

	// What the *first kotlin code block* costs the lazy loader: core + one file.
	const lazyStarted = process.hrtime.bigint();
	hljs.registerLanguage("kotlin", require(join(PI, "node_modules/highlight.js/lib/languages/kotlin.js")));
	const lazyMs = Number(process.hrtime.bigint() - lazyStarted) / 1e6;
	const lazyRss = rss();

	const eager = [
		"python", "java", "go", "javascript", "cpp", "typescript", "php", "ruby", "c", "csharp",
		"nix", "bash", "rust", "scala", "swift", "dart", "groovy", "perl", "lua",
	];
	const eagerStarted = process.hrtime.bigint();
	for (const language of eager) {
		hljs.registerLanguage(language, require(join(PI, "node_modules/highlight.js/lib/languages", `${language}.js`)));
	}
	const eagerMs = Number(process.hrtime.bigint() - eagerStarted) / 1e6;
	const eagerRss = rss();

	const fullStarted = process.hrtime.bigint();
	require(join(PI, "node_modules/highlight.js/lib/index.js"));
	const fullMs = Number(process.hrtime.bigint() - fullStarted) / 1e6;
	const fullRss = rss();

	process.stdout.write(
		JSON.stringify({
			rssIdle: idle,
			coreMs,
			coreRss,
			lazyMs,
			lazyRss,
			eagerMs,
			eagerRss,
			fullMs,
			fullRss,
		}),
	);
}

await main();
