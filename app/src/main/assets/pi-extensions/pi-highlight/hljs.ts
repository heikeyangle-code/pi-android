/**
 * Lazily loaded highlight.js — the real thing, the version pi pins.
 *
 * Why this file exists at all: pi highlights with `highlight.js` **10.7.3**
 * (`packages/coding-agent/package.json`), and the guest this extension runs in
 * already contains that exact module as a dependency of pi itself. Loading it
 * here — rather than shipping an Android-side reimplementation — buys pi's
 * output byte for byte, including the grammar quirks a hand-written highlighter
 * gets wrong.
 *
 * ### Lazier than pi
 *
 * `pi` registers 20 languages when it starts and pulls in the other 171 in the
 * background (`loadAllHighlightLanguages`). Measured on this host, neither is
 * worth paying on a phone:
 *
 *   - `require("highlight.js/lib/core.js")`             ~ 46 ms
 *   - + the 20 eager language modules                   ~ 273 ms total
 *   - + the other 171 (`lib/index.js`)                  ~ 2432 ms total
 *   - one small language module on demand               ~ 2-15 ms
 *
 * So this loader registers **nothing** until a request names a language, then
 * loads core plus that one file and memoises both. A conversation with no code
 * blocks costs zero JavaScript parsing; the first real block costs ~60 ms
 * instead of ~2.5 s, and later blocks ~1-3 ms. Registering the language files
 * one by one was verified equivalent to `lib/index.js`: 191/191 registered and
 * byte-identical HTML for a 26-language sample
 * (`tools/pi-highlight-check.mjs`). A name that is an *alias* rather than a file
 * (`html`, `toml`, `c++`, `c#`, `golang`, …) resolves through [LANGUAGE_ALIASES]
 * to the file that declares it, so the one-file-per-language rule still holds.
 *
 * `highlightAuto` is never called. Over 191 languages it takes 9.7–14.7 s
 * (`docs/syntax-highlight-eval.md` §3.4), and pi's own rule is to render plain
 * text rather than guess a language (`theme.ts` → `highlightCode`: "auto-detection
 * is unreliable and can misidentify prose as AppleScript").
 */

import { createRequire } from "node:module";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { LANGUAGE_ALIASES } from "./aliases";
import { htmlToRuns, type HighlightRun } from "./html-runs";

/** The slice of highlight.js this service uses. Structural, so nothing is imported eagerly. */
interface HljsInstance {
	highlight(code: string, options: { language: string; ignoreIllegals?: boolean }): { value: string };
	getLanguage(name: string): unknown;
	registerLanguage(name: string, definition: unknown): void;
	listLanguages(): string[];
}

type NodeRequire = (id: string) => unknown;

/** Where pi's highlight.js lives. Resolving this executes no JavaScript. */
interface Location {
	require: NodeRequire;
	corePath: string;
	languagesDir: string;
	version: string;
}

let location: Location | null = null;
let engine: HljsInstance | null = null;
let engineError: string | null = null;
let names: Set<string> | null = null;
const registered = new Set<string>();

/** Diagnostics for `/health`; reading this never loads anything. */
export interface LoadState {
	located: boolean;
	engineLoaded: boolean;
	hljsVersion: string | null;
	registeredLanguages: number;
	error: string | null;
}

export function loadState(): LoadState {
	return {
		located: location !== null,
		engineLoaded: engine !== null,
		hljsVersion: location?.version ?? null,
		registeredLanguages: registered.size,
		error: engineError,
	};
}

/**
 * Anchors for resolving pi's own `highlight.js`, cheapest and most specific
 * first. All of them are plain path resolution — none executes JavaScript.
 *
 *  - `process.argv[1]` is pi's own CLI entry (`PiEngineHost` starts the engine as
 *    `node .../pi-coding-agent/dist/cli.js --mode rpc`), so Node's resolution from
 *    that file finds exactly the highlight.js pi itself would use. This is the
 *    anchor that matters in the app.
 *  - `PI_PACKAGE_DIR` is pi's documented override for unusual layouts.
 *  - `process.execPath` covers a compiled/bun layout.
 *  - `PI_ANDROID_HIGHLIGHT_HLJS` exists so the verification harness can point at a
 *    checkout without pretending to be pi.
 *
 * Note there is deliberately **no** import of `@earendil-works/pi-coding-agent`
 * here: the extension must stay cheap to activate, and pulling pi's index through
 * jiti at load time would transform a very large module graph for a value that
 * path resolution already provides.
 */
function anchors(): string[] {
	const list: string[] = [];
	const explicit = process.env.PI_ANDROID_HIGHLIGHT_HLJS;
	if (explicit && explicit.trim().length > 0) {
		list.push(join(explicit.trim(), "package.json"));
	}
	const packageDir = process.env.PI_PACKAGE_DIR;
	if (packageDir && packageDir.trim().length > 0) {
		list.push(join(packageDir.trim(), "package.json"));
	}
	const entry = process.argv[1];
	if (entry) {
		list.push(entry);
	}
	if (process.execPath) {
		list.push(process.execPath);
	}
	return list;
}

/** Resolve the module's location once. Path resolution only — nothing is executed. */
function locate(): Location {
	if (location) {
		return location;
	}
	const tried: string[] = [];
	for (const anchor of anchors()) {
		tried.push(anchor);
		let hljsDir: string;
		try {
			hljsDir = join(createRequire(anchor).resolve("highlight.js/package.json"), "..");
		} catch {
			continue;
		}
		let version = "unknown";
		try {
			const pkg = JSON.parse(readFileSync(join(hljsDir, "package.json"), "utf8")) as { version?: string };
			version = pkg.version ?? "unknown";
		} catch {
			// A missing version only costs a diagnostic string.
		}
		location = {
			require: createRequire(join(hljsDir, "package.json")),
			corePath: join(hljsDir, "lib", "core.js"),
			languagesDir: join(hljsDir, "lib", "languages"),
			version,
		};
		return location;
	}
	engineError = `找不到 highlight.js（它应当是 pi 自己的依赖）。已尝试：${tried.join("、")}`;
	throw new Error(engineError);
}

/**
 * Every language *file* highlight.js ships, read from the directory listing — no
 * file is parsed and no grammar is registered. This is the cheap half of "is this
 * language known?"; [resolveLanguageFile] adds the other half, the aliases.
 *
 * Kept as "files only" on purpose: an empty listing is the one signal that
 * highlight.js itself could not be found next to pi, which is a configuration
 * failure worth naming rather than an unknown language (see the `service`).
 */
export function knownLanguages(): Set<string> {
	if (names) {
		return names;
	}
	const set = new Set<string>();
	try {
		for (const entry of readdirSync(locate().languagesDir)) {
			if (entry.endsWith(".js")) {
				set.add(entry.slice(0, -3));
			}
		}
	} catch (error) {
		engineError = error instanceof Error ? error.message : String(error);
	}
	names = set;
	return set;
}

/**
 * The language file that provides `name`.
 *
 * A name with a file of its own is that file — the common case, and the only one
 * that costs nothing. Anything else goes through [LANGUAGE_ALIASES], highlight.js's
 * own alias table; see that file for why a directory listing alone is not enough
 * (`html`, `toml`, `c++`, `c#`, `golang`, `docker`, `bat` and 167 more names
 * resolve to a language in pi but have no file of their own).
 *
 * @return the file to load, or `null` when highlight.js has no such language.
 */
export function resolveLanguageFile(name: string): string | null {
	if (knownLanguages().has(name)) {
		return name;
	}
	return LANGUAGE_ALIASES[name] ?? null;
}

/**
 * Whether highlight.js can highlight `name` — pi's own question, asked the same
 * way (`supportsLanguage` -> `getLanguage`, `utils/syntax-highlight.ts:210-212`).
 */
export function knowsLanguage(name: string): boolean {
	return resolveLanguageFile(name) !== null;
}

/**
 * Load (once) whatever JavaScript `language` needs — core plus the one language
 * file that provides it. Returns `false` when highlight.js does not ship it.
 *
 * `registered` is keyed by the *file*, not by the requested name: registering a
 * definition installs its own aliases inside highlight.js, so asking for `html`
 * and then for `xml` must not register the same file twice, and `xml` is enough
 * to make `html` resolve.
 */
export function ensureLanguage(language: string): boolean {
	const file = resolveLanguageFile(language);
	if (file === null) {
		return false;
	}
	if (registered.has(file)) {
		return true;
	}
	try {
		const at = locate();
		if (!engine) {
			engine = at.require(at.corePath) as HljsInstance;
		}
		const definition = at.require(join(at.languagesDir, `${file}.js`));
		engine.registerLanguage(file, definition);
		registered.add(file);
		return true;
	} catch (error) {
		engineError = error instanceof Error ? error.message : String(error);
		return false;
	}
}

export interface HighlightResult {
	runs: HighlightRun[];
	/** UTF-16 length of the highlighted text; compared with the input by the caller. */
	codeUnits: number;
	hljsVersion: string;
	ms: number;
}

/**
 * Highlight `code` as `language` and return offset runs, or `null` when
 * highlight.js does not know the language — aliases count as known, exactly as
 * they do for pi. The name is handed to highlight.js unchanged, which resolves it
 * through the registry [ensureLanguage] just populated.
 *
 * `ignoreIllegals: true` matches pi (`theme.ts` → `highlightCode`): a grammar
 * that trips over a half-typed construct must colour what it can, not throw.
 */
export function highlightToRuns(code: string, language: string): HighlightResult | null {
	if (!ensureLanguage(language)) {
		return null;
	}
	const started = Date.now();
	const active = engine as HljsInstance;
	const html = active.highlight(code, { language, ignoreIllegals: true }).value;
	const result = htmlToRuns(html);
	return {
		runs: result.runs,
		codeUnits: result.codeUnits,
		hljsVersion: (location as Location).version,
		ms: Date.now() - started,
	};
}
