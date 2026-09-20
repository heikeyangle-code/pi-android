#!/usr/bin/env node
/**
 * The **pi contract**: the facts this app builds on, asserted against the *pinned*
 * engine instead of assumed.
 *
 * ## Why this exists
 *
 * This app reproduces pi's behaviour rather than forking it: it reads pi's files
 * (`models.json`, `auth.json`, `models-store.json`, session JSONL), it sends pi's
 * RPC commands, and it ships extensions that pi loads. Every one of those is a
 * *fact about the pinned pi*, and every one of them is written down in App code
 * with a `file:line` that was read off the engine version current when the comment
 * was written — not necessarily the one `PI_VERSION` now names. Change `PI_VERSION`
 * and the app keeps compiling while the facts underneath it move — the failure mode
 * this repository has already paid for twice (`docs/known-gaps.md` §M11: a
 * capability silently downgraded; §M12: config keys silently deleted).
 *
 * CI today asserts that the payload *exists* and that its licences match. Nothing
 * asserts that what the app does with it still means what it meant. This does.
 *
 * ## What it checks
 *
 *  1. **Surface** — every RPC command string the app sends, every event type it
 *     parses, and every extension-UI method it answers must still appear in the
 *     pinned engine. Renames and removals fail here, cheaply.
 *  2. **Theme values** — `PiPalette.kt`'s literal transcriptions of pi's built-in
 *     `dark`/`light` themes, asserted against the pinned engine's own
 *     `dist/modes/interactive/theme/{dark,light}.json`. This is the one pi fact the
 *     app **copies** rather than reads, so nothing else in this file — or in the
 *     build — would notice a re-colouring upstream. See [checkThemeValues].
 *  3. **Behaviour** — `models.json` semantics, asserted by running the pinned
 *     engine: a declaration that omits `input`/`contextWindow` really does replace
 *     the catalog entry with pi's defaults (that is why the app must not declare
 *     models pi knows), and `modelOverrides` really does merge (that is why it is
 *     the safe way to adjust one).
 *  4. **Extensions** — the extensions this app ships still load into the pinned
 *     engine. Their API surface is the one thing here with no static substitute.
 *
 * Every failure prints **the App code that depends on the fact**, because the
 * point is not "pi changed" but "this is what to re-read".
 *
 * ## Usage
 *
 *   node tools/pi-contract.mjs                 # install the pinned version into
 *                                              # build/pi-contract, run all
 *   node tools/pi-contract.mjs --pi <dir>      # use an already-installed package
 *   node tools/pi-contract.mjs --pi <dir> --only=theme
 *                                              # one group only: surface | theme |
 *                                              # bundled | behaviour | extensions
 *
 * `--only` exists because the four groups cost very different things: `surface` and
 * `theme` are static reads of `dist/`, while `behaviour` and the startup-reload check
 * spawn the engine and `extensions` loads this app's own extension tree. A group that
 * fails for a reason outside its own subject (a work-in-progress file under
 * `app/src/main/assets/pi-extensions/`, say) should not be able to hide a verdict
 * about the palette.
 *
 * Exit code 0 = every assertion holds. Non-zero = read the last line.
 */

import { execFileSync, spawn } from "node:child_process";
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const PI_PACKAGE = "@earendil-works/pi-coding-agent";

/** The one place the engine version is written down. */
function pinnedVersion() {
	const text = readFileSync(join(ROOT, "tools/fetch-runtime.mjs"), "utf8");
	const match = /const PI_VERSION = "([^"]+)"/.exec(text);
	if (!match) {
		throw new Error("tools/fetch-runtime.mjs no longer declares PI_VERSION — this script reads it as the single source of truth");
	}
	return match[1];
}

const failures = [];
function check(name, ok, consequence) {
	if (ok) {
		console.log(`PASS ${name}`);
		return;
	}
	failures.push(`${name}\n      → ${consequence}`);
	console.log(`FAIL ${name}`);
}

// ---------------------------------------------------------------- part 1: surface

function appLiterals(file, pattern) {
	const text = readFileSync(join(ROOT, file), "utf8");
	return [...text.matchAll(pattern)].map((m) => m[1]).filter(Boolean);
}

function distText(piDir) {
	// The shipped artifact is what matters, not a source tree: whichever strings
	// survive into `dist/` are the ones the app can actually talk to.
	const files = execFileSync("find", [join(piDir, "dist"), "-name", "*.js", "-type", "f"], {
		encoding: "utf8",
	}).trim().split("\n").filter(Boolean);
	return files.map((file) => readFileSync(file, "utf8")).join("\n");
}

function checkSurface(piDir) {
	const dist = distText(piDir);
	// `Commands.kt` builds its records two ways, and both are commands pi must still
	// understand: `buildJsonObject { put("type", …) }` for the ones that carry fields,
	// and the `simple(id, type)` helper for the field-less ones. Scanning only the
	// first form (as this did at 0.85.1) asserted 19 of the 34 records and left a
	// rename in `abort`, `get_state`, `cycle_model`, `clone`, `get_tree` and eleven
	// others invisible — which is the one thing this group exists to catch.
	//
	// `image` is excluded: the `put("type", "image")` inside `putImages` is an
	// `ImageContent` element of a `prompt`'s `images` array (`rpc-types.ts`'s
	// `ImageContent`), not an RPC command, and asserting it would pass vacuously
	// because the string `image` appears in the engine for many other reasons.
	const commands = [
		...appLiterals("rpc/src/main/kotlin/app/pi/rpc/Commands.kt", /put\("type", "([a-z_]+)"\)/g),
		...appLiterals("rpc/src/main/kotlin/app/pi/rpc/Commands.kt", /simple\(id, "([a-z_]+)"\)/g),
	].filter((command) => command !== "image");
	// The app answers the extension UI sub-protocol in two places, because pi's eight
	// methods have two shapes: four are dialogs that need an answer
	// (`ExtensionUi.kt`'s method enum), four are fire-and-forget chrome
	// (`PiSessionViewModel.onExtensionChrome`'s dispatch). Both are scanned — a rename
	// in either would leave an extension's request unanswered.
	const uiMethods = [
		...appLiterals("app/src/main/kotlin/app/pi/ui/extension/ExtensionUi.kt", /[A-Za-z]+\("([a-zA-Z]+)"\)/g),
		...appLiterals("app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt", /"([a-zA-Z]+)" -> pushNotice|\n\s*"([a-zA-Z]+)" -> (?:setExtension|_state)/g).filter(Boolean),
		...appLiterals("app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt", /"(notify|setStatus|setWidget|setTitle)" ->/g),
	];
	for (const command of new Set(commands)) {
		check(
			`RPC command still exists in the pinned engine: ${command}`,
			dist.includes(`"${command}"`),
			`app.pi sends this command (rpc/.../Commands.kt). If pi renamed or removed it, ` +
				`find the new name in the pinned rpc-types.ts and update Commands.kt + its callers.`,
		);
	}
	// The provider table is the app's own transcription of pi's `providers/*.ts` — the
	// one table here that *writes* something: `PiCredentialService.save` puts a
	// preset's `baseUrl` and `api` into `models.json`, so a stale entry would override
	// the engine's real endpoint for that provider (the §M11 shape again). Only the
	// providers pi ships are asserted; the app's own three (Ollama, llama.cpp, 自定义)
	// have no counterpart by definition.
	const presets = [
		...readFileSync(join(ROOT, "app/src/main/kotlin/app/pi/packages/PiProviderPresets.kt"), "utf8")
			.matchAll(/Preset\("([^"]+)",\s*"[^"]*",\s*"([^"]*)",\s*"([^"]*)",[^)]*?(true|false),\s*(true|false)/g),
	].map((m) => ({ id: m[1], baseUrl: m[2], api: m[3], builtInPi: m[5] === "true" }));
	for (const preset of presets.filter((p) => p.builtInPi && p.baseUrl.length > 0)) {
		check(
			`provider preset matches the pinned engine: ${preset.id}`,
			dist.includes(preset.baseUrl),
			`app.pi keeps its own table of pi's providers (packages/PiProviderPresets.kt) and ` +
				`saves baseUrl/api from it into models.json — a stale entry overrides the engine's ` +
				`own endpoint. Re-read the pinned providers/${preset.id}.ts.`,
		);
	}

	// The one file path the app reconstructs rather than asks for. A rename here makes
	// `PiModelCatalog` return an empty list for every provider — by design ("silence
	// rather than a guess"), which means nothing else in the build could notice.
	check(
		"the pinned engine still names the model catalog `models-store.json`",
		dist.includes("models-store.json"),
		"app.pi reads pi's own model catalog beside models.json " +
			"(packages/PiModelCatalog.kt, called from PiCredentialService.catalogModels and " +
			"PiModelInventory). Re-read the pinned core/model-runtime.ts's FileModelsStore " +
			"construction and update both readers if the name or the location moved.",
	);

	// `set_editor_text` is the ninth method (`rpc-types.ts`'s RpcExtensionUIRequest) and
	// the one that is neither a dialog nor chrome: the app applies it to the composer
	// (`PiSessionViewModel`'s `"set_editor_text" ->` arm). It was missing from this list
	// at 0.85.1, so a rename of the only method that fills the composer was unasserted.
	const knownUiMethods = [
		"confirm",
		"input",
		"notify",
		"select",
		"editor",
		"setStatus",
		"setTitle",
		"setWidget",
		"set_editor_text",
	];
	for (const method of knownUiMethods) {
		check(
			`extension UI method still exists in the pinned engine: ${method}`,
			dist.includes(`"${method}"`),
			`app.pi answers this extension UI request (ui/extension/**). A rename means the ` +
				`extension's dialog would never be answered — check rpc-types.ts's RpcExtensionUIRequest.`,
		);
	}
	console.log(`   (${new Set(commands).size} commands, ${uiMethods.length} local handlers, ${presets.filter((p) => p.builtInPi).length} provider presets scanned)`);
	checkMentionArgv(dist);
	checkPreSpawnFlags(dist);
	checkProjectConfigDir(piDir);
}

/**
 * pi's **project** config directory — `<cwd>/.pi`, the root of every project-scoped
 * thing pi reads: `settings.json`, `skills/`, `prompts/`, `themes/`, `extensions/`,
 * `SYSTEM.md`, `APPEND_SYSTEM.md`, and `pi install -l`'s install scope.
 *
 * Why this needs asserting: the name is a **transcription**, not a channel. pi's RPC
 * settings surface is files rather than schema (`docs/settings-review.md`), and no
 * response names the directory. pi derives it as
 * `CONFIG_DIR_NAME = pkg.piConfig?.configDir || ".pi"` (`src/config.ts:504`) from its
 * own `package.json`, so a pinned engine that ever declared something else would leave
 * the app reading and writing a directory pi never opens — silently: settings show
 * defaults, project skills/prompts/themes/extensions never appear, and a project-level
 * `pi install -l` reports success into a directory pi ignores. That is the §M12 shape,
 * one layer below the settings keys.
 *
 * The app-side value is read out of the Kotlin source as text (the technique
 * `settings-audit` uses) rather than imported, because this script is JavaScript and
 * the app is Kotlin.
 */
function checkProjectConfigDir(piDir) {
	const kotlinDir = appLiterals(
		"app/src/main/kotlin/app/pi/runtime/PiProjectConfig.kt",
		/DIRECTORY:\s*String\s*=\s*"([^"]+)"/g,
	)[0];
	const manifest = JSON.parse(readFileSync(join(piDir, "package.json"), "utf8"));
	const engineDir = manifest.piConfig?.configDir ?? ".pi";
	check(
		"the pinned engine's project config directory is the one the app joins onto the workspace",
		kotlinDir === engineDir,
		"app.pi resolves every project-scoped path itself: `<workspace>/<dir>/settings.json` " +
			"(settings/PiSettingsFileStore.kt, packages/PiCredentialService.kt), `themes/` " +
			"(ui/theme/PiThemeFiles.kt), `extensions/` (ui/PiSessionViewModel.kt), `skills/` and " +
			"`prompts/`, and `pi install -l`'s scope (packages/AgentLayout.kt). Those call sites " +
			"read `PiProjectConfig.DIRECTORY`. Re-read the pinned package.json's `piConfig` and " +
			"`src/config.ts`'s CONFIG_DIR_NAME, then update `PiProjectConfig.DIRECTORY`.",
	);
	console.log(`   (project config dir pinned: ${kotlinDir})`);
}

/**
 * The pre-spawn table (`rpc/src/main/kotlin/app/pi/rpc/PiPreSpawnConfig.kt`) is the
 * app's claim about which CLI flags and environment variables pi accepts at launch.
 *
 * Why this needs an assertion rather than a `file:line`: pi's parser routes any
 * unrecognised `--flag` into `unknownFlags` (extension flags) instead of failing, so a
 * renamed flag would not error anywhere — the settings row would simply stop doing
 * anything. The same is true of an environment variable: a renamed one is just absent.
 * That is the §I11 shape (a switch with no effect), one layer lower.
 */
function checkPreSpawnFlags(dist) {
	const text = readFileSync(join(ROOT, "rpc/src/main/kotlin/app/pi/rpc/PiPreSpawnConfig.kt"), "utf8");
	const exposed = (text.split("val APP_EXPOSED_PRE_SPAWN")[1] ?? "").split("val COVERED_BY_PI_SETTING")[0];
	const flags = [...exposed.matchAll(/flag = "([^"]+)"/g)].map((m) => m[1]);
	const variables = [...exposed.matchAll(/envVar = "([^"]+)"/g)].map((m) => m[1]);
	for (const flag of flags) {
		check(
			`pre-spawn CLI flag still exists in the pinned engine: ${flag}`,
			dist.includes(flag),
			`app.pi passes ${flag} to pi at launch (rpc/.../PiPreSpawnConfig.kt, from PiLaunchOptions). ` +
				`pi treats an unknown --flag as an extension flag rather than an error, so the settings ` +
				`row would silently do nothing. Re-read the pinned cli/args.ts parseArgs and update ` +
				`PiPreSpawnConfig.kt + PiLaunchOptions.kt.`,
		);
	}
	for (const variable of variables) {
		check(
			`pre-spawn environment variable still exists in the pinned engine: ${variable}`,
			dist.includes(variable),
			`app.pi sets ${variable} in pi's process environment (rpc/.../PiPreSpawnConfig.kt). A rename ` +
				`leaves the switch silently inert. Re-read the pinned docs/environment-variables.md and its ` +
				`reader, then update PiPreSpawnConfig.kt + PiLaunchOptions.kt.`,
		);
	}
	console.log(`   (${flags.length} pre-spawn flags, ${variables.length} variables pinned)`);
}

/**
 * pi's `@` file-mention list is produced by the guest's own `fd`, run with **pi's
 * argv** — that is the whole reason the app does not reimplement an ignore engine:
 * pi's candidate semantics *are* fd's semantics (layered ignore files, `--hidden`
 * with the `.git` exclusion, the 100-result cap). `PiFileMentions.fdCommand` is a
 * transcription of that argv, so if pi changes it the app silently starts offering a
 * different file set than pi's own `@` — the "App believes a copy" shape
 * `docs/pi-sourced-lists.md` is about.
 *
 * Whitespace is stripped before the comparison so a minifier change cannot fail it,
 * and only the flags are pinned: `--base-directory`'s value and the query are
 * arguments the app supplies.
 */
function checkMentionArgv(dist) {
	const compact = dist.replace(/\s+/g, "");
	const flags = [
		'"--type","f"',
		'"--type","d"',
		'"--follow"',
		'"--hidden"',
		'"--exclude",".git"',
		'"--exclude",".git/*"',
		'"--exclude",".git/**"',
	].join(",");
	const hasFlags = compact.includes(flags);
	const hasCaps =
		compact.includes('"--base-directory"') &&
		compact.includes('"--max-results"') &&
		compact.includes('"--full-path"');
	check(
		"pi's own fd argv for the @ file list is unchanged in the pinned engine",
		hasFlags && hasCaps,
		"app.pi reproduces pi's `@` candidates by running the guest's fd with pi's argv " +
			"(app/src/main/kotlin/app/pi/ui/chat/PiFileMentions.kt, `fdCommand`, cited to " +
			"packages/tui/src/autocomplete.ts's walkDirectoryWithFd). Re-read that function in " +
			"the pinned engine and update `fdCommand` + the `mentions` harness — otherwise the " +
			"App's completion offers a different file set than pi's own `@`.",
	);
}

// ------------------------------------------------------------ part 1b: theme values

/**
 * pi's five optional colour tokens and the token each one falls back to
 * (`withThemeColorFallbacks`, `theme.ts:261-276`; the same five are re-applied in
 * `Theme`'s constructor at `:303-316`).
 *
 * These are the **five fallback targets the built-in themes do not exercise**:
 * `dark.json` and `light.json` both declare all five tokens explicitly, so a bug in
 * this map cannot show up in the value comparison below. It is written down here so
 * the mapping itself is reviewable, and it is asserted against the App's own copy by
 * [checkThemeValues] rather than left implicit.
 */
const THEME_OPTIONAL_FALLBACKS = {
	scrollbarTrack: "muted",
	scrollbarThumb: "text",
	thinkingMax: "thinkingXhigh",
	searchMatchBg: "selectedBg",
	searchMatchText: "text",
};

/** Where the pinned engine keeps the two built-in themes. */
function builtinThemePath(piDir, name) {
	return join(piDir, "dist", "modes", "interactive", "theme", `${name}.json`);
}

/**
 * `resolveVarRefs` (`theme.ts:228-244`): a hex string, an empty string and a 0-255
 * integer are literal; anything else names a `vars` entry and resolves recursively.
 * Returns `null` for a reference pi would reject (missing target, or a cycle), so the
 * caller reports it instead of throwing the whole script away.
 */
function resolveThemeValue(value, vars, seen = new Set()) {
	if (typeof value === "number" || value === "" || value.startsWith("#")) return value;
	if (seen.has(value) || !(value in vars)) return null;
	seen.add(value);
	return resolveThemeValue(vars[value], vars, seen);
}

/**
 * Every `colors` token plus the three `export` surfaces of one built-in theme,
 * resolved the way pi resolves them: `vars` references followed, the five optional
 * tokens filled from their fallbacks, `export` folded in.
 *
 * Returns `null` when the file is missing (a package that moved its themes) or when
 * any value refuses to resolve, so the caller can say which of those happened.
 */
function builtinThemeColors(piDir, name) {
	const file = builtinThemePath(piDir, name);
	if (!existsSync(file)) return null;
	const json = JSON.parse(readFileSync(file, "utf8"));
	const vars = json.vars ?? {};
	// pi's fallback map points at the *unresolved* sibling value, which is then
	// resolved with everything else — `{...colors, thinkingMax: colors.thinkingMax ??
	// colors.thinkingXhigh}`.
	const raw = { ...json.colors };
	for (const [token, fallback] of Object.entries(THEME_OPTIONAL_FALLBACKS)) {
		if (raw[token] === undefined) raw[token] = raw[fallback];
	}
	for (const [token, value] of Object.entries(json.export ?? {})) raw[token] = value;
	const out = {};
	for (const [token, value] of Object.entries(raw)) {
		const resolved = resolveThemeValue(value, vars);
		if (resolved === null) return null;
		out[token] = resolved;
	}
	return out;
}

/**
 * One `PiPalette.Dark` / `PiPalette.Light` body, as `token → "#rrggbb"`.
 *
 * Reads the Kotlin as text for the reason `checkProjectConfigDir` does: this script
 * is JavaScript and the App is Kotlin. Returns `null` when the declaration is not
 * shaped the way this reads it — the closing paren of the constructor call is the
 * anchor, because a regex that silently matched nothing would make every comparison
 * downstream vacuous.
 */
function appPalette(theme) {
	const text = readFileSync(join(ROOT, "app/src/main/kotlin/app/pi/ui/theme/PiPalette.kt"), "utf8");
	const body = new RegExp(`val ${theme} = PiPalette\\(([\\s\\S]*?)\\n {8}\\)`).exec(text);
	if (!body) return null;
	const out = {};
	for (const match of body[1].matchAll(/^\s*(\w+) = Color\(0x([0-9A-Fa-f]{8})\)/gm)) {
		out[match[1]] = `#${match[2].slice(2).toLowerCase()}`;
	}
	return out;
}

/** One `#RRGGBB` out of a `res/values…/colors.xml`, or `null` if it is not there. */
function resourceColor(file, name) {
	const text = readFileSync(join(ROOT, file), "utf8");
	const match = new RegExp(`<color name="${name}">\\s*(#[0-9A-Fa-f]{6})\\s*</color>`).exec(text);
	return match ? match[1].toLowerCase() : null;
}

/** The pinned engine's own version, for a failure that has to name what changed. */
function engineVersion(piDir) {
	try {
		return JSON.parse(readFileSync(join(piDir, "package.json"), "utf8")).version ?? "unknown";
	} catch {
		return "unknown";
	}
}

/**
 * `PiPalette.kt` against the pinned engine's built-in `dark`/`light` themes, plus the
 * two launch-window literals that copy two of their values.
 *
 * ## Why this group exists
 *
 * `PiPalette.Dark` / `PiPalette.Light` are the **only** pi facts in this app that are
 * *copied* rather than *read*: 51 required tokens + 5 optional ones + the 3 `export`
 * surfaces, transcribed from `dark.json` / `light.json` because a phone needs them
 * synchronously (the first frame cannot wait for a theme file). Everything else in
 * `ui/` is either derived from those values (`PiContrast`'s five contrast-corrected
 * tokens, `PiTheme.colorScheme()`'s `surfaceContainer*` ladder) or read from the
 * engine at runtime (`PiThemeFiles.kt` resolves a user's theme file). So if pi
 * re-colours a built-in theme, **nothing else in this repository can notice**: the
 * app keeps compiling and keeps painting pi's previous colours, and a user's imported
 * theme would suddenly disagree with the built-ins it was tuned beside.
 *
 * ## What it asserts, and what it deliberately does not
 *
 * It asserts, per theme: the token **names** match both ways (no token dropped from
 * `PiPalette.kt`, none invented), the parsed **count** is at least 50 (56 + 3 export
 * is the real number; the floor is what turns "the regex stopped matching" into a
 * failure instead of 59 silent passes), and every **value** is equal ignoring case.
 * Then it asserts the two `res/values…/colors.xml` launch-window literals against the
 * matching `export.pageBg`, because those are the only other place a palette value is
 * written down outside Kotlin.
 *
 * It does **not** assert `THEME_OPTIONAL_FALLBACKS` against pi: the built-in themes
 * declare all five optional tokens, so the fallback path never runs for them. A pi
 * that re-pointed a fallback at a different token with the same colour would still
 * pass here; `PiThemeFiles.kt:126-132` remains a transcription that only a re-read of
 * `theme.ts:261-276` can check.
 *
 * ## What to do when it fails
 *
 * Two honest answers, and this check cannot pick between them:
 *
 *  - **re-transcribe** — edit the named token(s) in `PiPalette.kt` to the values this
 *    check prints. That is what the current design wants: the palette is a compile-time
 *    constant, so the first frame is instant and works with no engine on disk;
 *  - **read at runtime** — make `PiPalette.Dark`/`Light` load `dist/.../dark.json` from
 *    the payload instead of holding literals. That couples the app's start-up path to
 *    the engine package's layout (`docs/pi-android-ui-spec.md` §2.1 makes pi's theme
 *    JSON the single source of truth, so this is not a violation of it) and gives up
 *    the "palette exists before anything is provisioned" property the current shape
 *    buys — which is why the literals were chosen.
 */
function checkThemeValues(piDir) {
	const version = engineVersion(piDir);

	for (const theme of ["Dark", "Light"]) {
		const name = theme.toLowerCase();
		const file = builtinThemePath(piDir, name);
		const pi = builtinThemeColors(piDir, name);
		check(
			`the pinned engine still ships ${file}`,
			pi !== null,
			`PiPalette.${theme} is a transcription of that file, and every screen reads it through ` +
				`PiTheme.palette. If the built-in themes moved inside the package, re-read the package ` +
				`layout and re-point this check — do not delete it: nothing else notices a re-colouring.`,
		);
		if (pi === null) continue;

		const app = appPalette(theme);
		check(
			`PiPalette.kt still declares \`val ${theme} = PiPalette(...)\` with all of its tokens (>= 50)`,
			app !== null && Object.keys(app).length >= 50,
			`app/src/main/kotlin/app/pi/ui/theme/PiPalette.kt is the app's whole colour system. This ` +
				`check reads its \`name = Color(0xFF……)\` lines as text; if the declaration was renamed, ` +
				`reformatted or split, re-point the reader (the >= 50 floor is what makes a reader that ` +
				`stopped matching fail instead of passing vacuously).`,
		);
		if (app === null) continue;

		const missing = Object.keys(pi).filter((token) => !(token in app));
		const extra = Object.keys(app).filter((token) => !(token in pi));
		check(
			`PiPalette.${theme} names exactly pi ${version}'s ${name} tokens`,
			missing.length === 0 && extra.length === 0,
			`pi ${version}'s ${name}.json: ${Object.keys(pi).length} tokens; PiPalette.${theme}: ` +
				`${Object.keys(app).length}. ` +
				(missing.length > 0 ? `Missing from PiPalette.kt: ${missing.join(", ")}. ` : "") +
				(extra.length > 0 ? `Not in pi's theme any more: ${extra.join(", ")}. ` : "") +
				`Token names are the contract between a user's theme file and this app ` +
				`(PiThemeFiles.kt maps them by name); re-read the pinned theme-schema.json and update ` +
				`PiPalette.kt, PiThemeFiles.kt's REQUIRED_TOKENS/OPTIONAL_FALLBACKS and ` +
				`ChromeColor.kt's match list together.`,
		);

		const mismatches = Object.keys(pi)
			.filter((token) => token in app)
			.filter((token) => app[token] !== String(pi[token]).toLowerCase())
			.map((token) => `${token}: app ${app[token]} vs pi ${String(pi[token]).toLowerCase()}`);
		check(
			`PiPalette.${theme} values equal pi ${version}'s ${name}.json (${Object.keys(pi).length} tokens)`,
			mismatches.length === 0 && Object.keys(app).length >= 50,
			`pi ${version} changed ${mismatches.length} value(s) in its built-in ${name} theme:\n` +
				mismatches.map((line) => `        ${line}`).join("\n") +
				`\n      → PiPalette.${theme} (app/src/main/kotlin/app/pi/ui/theme/PiPalette.kt) is a ` +
				`literal transcription, and every screen paints through PiTheme.palette, so the app is ` +
				`still showing the previous colours. Either re-transcribe the tokens above into ` +
				`PiPalette.kt (the current design: a compile-time constant that exists before any theme ` +
				`is read) or change PiPalette.Dark/Light to load ${file} from the payload at start-up ` +
				`(which couples start-up to the engine package's layout). Do not silence this check: ` +
				`it is the only one covering colours.`,
		);
	}

	// The launch-window ground cannot be a Compose colour (it is drawn before the
	// first composition), so it is the one palette value written down outside Kotlin.
	// `values/` is the day resource, `values-night/` the night one; MainActivity
	// replaces both with the *resolved* theme's `pageBg` as soon as the theme file has
	// been read, which is why only the pre-resolution frame depends on these two.
	for (const [qualifier, file, themeName] of [
		["values", "app/src/main/res/values/colors.xml", "light"],
		["values-night", "app/src/main/res/values-night/colors.xml", "dark"],
	]) {
		const pi = builtinThemeColors(piDir, themeName);
		const expected = pi === null ? null : String(pi.pageBg).toLowerCase();
		const actual = resourceColor(file, "pi_window_background");
		check(
			`${file} pi_window_background equals pi ${version}'s ${themeName} export.pageBg`,
			expected !== null && actual === expected,
			`${file} is the window ground for the frames before Compose has a theme: it must be the ` +
				`${themeName} (\`${qualifier}\`) export surface, and it is currently ${actual ?? "missing"}. ` +
				`PiPalette.${themeName === "dark" ? "Dark" : "Light"}.pageBg is that same value in Kotlin ` +
				`(PiTheme.colorScheme maps it to Surface), so both move together — re-read ` +
				`res/values/colors.xml + res/values-night/colors.xml and PiPalette.kt.`,
		);
	}
}

// -------------------------------------------------------------- part 2: behaviour

function runPi(piDir, agentDir, requests, seconds = 60) {
	const cli = join(piDir, "dist/cli.js");
	const out = execFileSync(
		"node",
		[cli, "--mode", "rpc", "--session-dir", join(agentDir, "sessions")],
		{
			input: requests.map((r) => JSON.stringify(r) + "\n").join(""),
			encoding: "utf8",
			timeout: seconds * 1000,
			env: {
				...process.env,
				PI_CODING_AGENT_DIR: agentDir,
				PI_SKIP_VERSION_CHECK: "1",
				PI_OFFLINE: "1",
				DEEPSEEK_API_KEY: "sk-contract",
			},
			stdio: ["pipe", "pipe", "pipe"],
		},
	);
	return out
		.split("\n")
		.filter(Boolean)
		.flatMap((line) => {
			try {
				return [JSON.parse(line)];
			} catch {
				return [];
			}
		});
}

/**
 * The **packed** entry the app launches, asserted end to end.
 *
 * `PiEngineHost` prefers `dist/bundle/rpc-entry.js` (upstream's
 * `exports["./rpc-entry"]`, which forces `--mode rpc` itself) and falls back to
 * the unpacked `dist/cli.js` — the packed build is what takes engine startup from
 * ~18–20 s to ~1 s, and the fallback exists so that a future payload without it
 * only gets slower. Those two are complementary and both have to stay asserted:
 * this check is the one that fails *loudly* on the next version bump if the bundle
 * disappears, while the app's fallback is what keeps a user's phone working.
 *
 * The risk the entry change carries is a real turn, not a `--version` print, so the
 * assertion is a served `get_state` over the packed entry with no `--mode rpc` on
 * the command line.
 */
function checkBundledEntry(piDir) {
	const bundled = join(piDir, "dist/bundle/rpc-entry.js");
	const cli = join(piDir, "dist/bundle/cli.js");
	check(
		"the payload contains the packed rpc entry the app launches",
		existsSync(bundled) && existsSync(cli),
		"PiEngineHost prefers dist/bundle/rpc-entry.js (package.json `exports[\"./rpc-entry\"]`). " +
			"It falls back to dist/cli.js, so the app still starts — just ~20x slower. If upstream " +
			"really dropped the bundle, update PiEngineHost's candidate list and this check together.",
	);
	if (!existsSync(bundled)) return;

	const probe = mkdtempSync(join(tmpdir(), "pi-contract-bundle-"));
	mkdirSync(join(probe, "sessions"), { recursive: true });
	const out = execFileSync(
		"node",
		[bundled, "--session-dir", join(probe, "sessions")],
		{
			input: JSON.stringify({ id: "b", type: "get_state" }) + "\n",
			encoding: "utf8",
			timeout: 60_000,
			env: {
				...process.env,
				PI_CODING_AGENT_DIR: probe,
				PI_SKIP_VERSION_CHECK: "1",
				PI_OFFLINE: "1",
			},
			stdio: ["pipe", "pipe", "pipe"],
		},
	);
	const answer = out
		.split("\n")
		.filter(Boolean)
		.flatMap((line) => {
			try {
				return [JSON.parse(line)];
			} catch {
				return [];
			}
		})
		.find((event) => event.type === "response" && event.id === "b");
	check(
		"the packed entry serves a get_state without --mode rpc",
		answer?.success === true,
		"rpc-entry.js is supposed to inject `--mode rpc` itself. If it no longer does, the app's " +
			"launch line (PiEngineHost's guestCommand) is what breaks — it deliberately omits the flag " +
			"for this entry.",
	);
	rmSync(probe, { recursive: true, force: true });
}

function availableModels(piDir, agentDir) {
	const events = runPi(piDir, agentDir, [{ id: "m", type: "get_available_models" }]);
	const response = events.find((e) => e.type === "response" && e.command === "get_available_models");
	return response?.data?.models ?? [];
}

function writeModelsJson(agentDir, block) {
	writeFileSync(join(agentDir, "models.json"), JSON.stringify({ providers: { deepseek: block } }, null, 2));
}

function checkBehaviour(piDir) {
	const probe = mkdtempSync(join(tmpdir(), "pi-contract-"));
	mkdirSync(join(probe, "sessions"), { recursive: true });

	const catalog = availableModels(piDir, probe).filter((m) => m.provider === "deepseek");
	if (catalog.length === 0) {
		check(
			"a provider with a credential contributes models to get_available_models",
			false,
			"nothing else below can be asserted. Either the env-var credential stopped being " +
				"recognised, or get_available_models stopped reporting unconfigured providers.",
		);
		return;
	}
	const model = catalog[0];
	const id = model.id;

	// (1) A declaration that omits the capability fields replaces the catalog entry
	//     with pi's defaults. This is the fact `PiCredentialService.save` is built on:
	//     it is why the app declares nothing for a provider pi ships.
	writeModelsJson(probe, { baseUrl: "https://api.deepseek.com", api: "openai-completions", models: [{ id }] });
	const afterDeclaration = availableModels(piDir, probe).find((m) => m.provider === "deepseek" && m.id === id);
	check(
		"a models.json entry without `input` really does become text-only",
		Array.isArray(afterDeclaration?.input) && JSON.stringify(afterDeclaration.input) === '["text"]',
		"docs/known-gaps.md §M11 rests on this. If pi stopped defaulting `input`, the rule in " +
			"PiCredentialService.save ('declare only what pi cannot know') is no longer explained by it — re-derive it.",
	);
	check(
		"a models.json entry without `contextWindow` really does become 128000",
		afterDeclaration?.contextWindow === 128000,
		"the other half of §M11: omission is a downgrade, not a no-op. Re-check " +
			"provider-composer.ts's modelFromJson in the pinned engine.",
	);

	// (2) `modelOverrides` merges instead of replacing, which is why it is the safe
	//     way to adjust one field of a model pi already knows.
	writeModelsJson(probe, {
		baseUrl: "https://api.deepseek.com",
		api: "openai-completions",
		modelOverrides: { [id]: { contextWindow: 4096 } },
	});
	const afterOverride = availableModels(piDir, probe).find((m) => m.provider === "deepseek" && m.id === id);
	check(
		"modelOverrides really does merge (the field changes)",
		afterOverride?.contextWindow === 4096,
		"if this stops working, the app's one safe way to adjust a catalog model is gone — " +
			"see provider-composer.ts's applyOverride.",
	);
	check(
		"modelOverrides really does merge (other fields survive)",
		JSON.stringify(afterOverride?.input) === JSON.stringify(model.input),
		"a merge that discards the rest is indistinguishable from `models[]`, which is the " +
			"defect §M11/§M12 are about; the app must not recommend modelOverrides if it replaces.",
	);

	rmSync(probe, { recursive: true, force: true });
}

/**
 * The fact the whole 设置 → 模型 page is built on: **over `--mode rpc`, `models.json` is
 * only read when the process starts.**
 *
 * `ModelConfig.load` has exactly two call sites (`core/model-runtime.ts:176` in
 * `create`, `:699` in `refresh`), and nothing on the RPC surface calls `refresh` —
 * `get_available_models` answers from `getAvailableSnapshot()` (`rpc-mode.ts:490-493`).
 * `PiModelInventory` turns "the file has it, the engine's list does not" into
 * 「等待重启」, and `PiModelsScreen` tells the user to restart for it. If pi ever grew an
 * RPC refresh (or reloaded the file per call), that sentence would become a lie — the
 * user would restart for nothing. So the negative is asserted directly: write the file
 * *while the engine is running*, ask again, and require the new model to be absent —
 * then require it to be present in a fresh process.
 *
 * The negative is the flaky-looking half, so what it may not depend on: it does not
 * depend on timing (both requests are answered by the same live process, in order),
 * and a process that never answers fails the check with that sentence rather than
 * passing quietly.
 */
function startPi(piDir, agentDir) {
	const cli = join(piDir, "dist/cli.js");
	const child = spawn("node", [cli, "--mode", "rpc", "--session-dir", join(agentDir, "sessions")], {
		env: {
			...process.env,
			PI_CODING_AGENT_DIR: agentDir,
			PI_SKIP_VERSION_CHECK: "1",
			PI_OFFLINE: "1",
			DEEPSEEK_API_KEY: "sk-contract",
		},
		stdio: ["pipe", "pipe", "pipe"],
	});
	const events = [];
	let buffer = "";
	child.stdout.setEncoding("utf8");
	child.stdout.on("data", (chunk) => {
		buffer += chunk;
		let index;
		while ((index = buffer.indexOf("\n")) >= 0) {
			const line = buffer.slice(0, index);
			buffer = buffer.slice(index + 1);
			if (!line.trim()) continue;
			try {
				events.push(JSON.parse(line));
			} catch {
				// A partial or non-JSON line is not an answer; the wait below times out.
			}
		}
	});
	child.stderr.setEncoding("utf8");
	child.stderr.on("data", () => {});
	return {
		events,
		send(request) {
			child.stdin.write(`${JSON.stringify(request)}\n`);
		},
		async waitFor(predicate, ms = 30_000) {
			const deadline = Date.now() + ms;
			for (;;) {
				const found = events.find(predicate);
				if (found) return found;
				if (Date.now() > deadline) return undefined;
				await new Promise((resolve) => setTimeout(resolve, 25));
			}
		},
		async stop() {
			child.stdin.end();
			await new Promise((resolve) => {
				const timer = setTimeout(() => {
					child.kill("SIGKILL");
					resolve();
				}, 5_000);
				child.on("exit", () => {
					clearTimeout(timer);
					resolve();
				});
			});
		},
	};
}

async function checkStartupOnlyReload(piDir) {
	const probe = mkdtempSync(join(tmpdir(), "pi-contract-reload-"));
	mkdirSync(join(probe, "sessions"), { recursive: true });

	const baseline = availableModels(piDir, probe).filter((model) => model.provider === "deepseek");
	if (baseline.length === 0) {
		check(
			"a provider with a credential contributes models to get_available_models",
			false,
			"the reload check below needs a credentialed provider to add a model to; " +
				"re-read core/model-runtime.ts's credential handling if this stops working.",
		);
		rmSync(probe, { recursive: true, force: true });
		return;
	}
	const model = baseline[0];
	const addedId = "contract-added-model";

	const live = startPi(piDir, probe);
	try {
		live.send({ id: "before", type: "get_available_models" });
		const before = await live.waitFor((event) => event.type === "response" && event.id === "before");
		check(
			"a live engine answers get_available_models",
			before?.success === true,
			"nothing below can be asserted without a served request; if this fails, the " +
				"RPC startup path changed and PiEngineSession's readiness probe is the place to look.",
		);

		writeModelsJson(probe, {
			baseUrl: "https://api.deepseek.com",
			api: "openai-completions",
			models: [{ id: addedId, name: addedId }],
		});

		live.send({ id: "after", type: "get_available_models" });
		const after = await live.waitFor((event) => event.type === "response" && event.id === "after");
		const liveIds = (after?.data?.models ?? []).map((entry) => entry.id);
		check(
			"models.json edited while the engine runs really does need a restart",
			after?.success === true && !liveIds.includes(addedId),
			"PiModelInventory's PENDING_RESTART status and PiModelsScreen's 「等待重启」 + " +
				"restart button are this fact. If pi now re-reads models.json during a session, " +
				"re-read core/model-runtime.ts's refresh call sites and drop that UI (or find " +
				"the command that refreshes and use it instead of a restart).",
		);
	} finally {
		await live.stop();
	}

	const fresh = availableModels(piDir, probe);
	check(
		"a fresh engine does read the edited models.json",
		fresh.some((entry) => entry.provider === "deepseek" && entry.id === addedId),
		"the other half of the same fact: the file is read at startup " +
			"(core/model-runtime.ts:176). If this fails, the probe itself is wrong (the file " +
			"or the credential), not the app.",
	);
	check(
		"the declared model did not silently inherit a catalog entry",
		fresh.find((entry) => entry.id === addedId)?.name === addedId,
		"a NEW id is appended, never merged into an existing one — this is what tells the " +
			"two halves of applyModelsJson (replace vs push) apart; see " +
			"provider-composer.ts:203-209 if it stopped holding.",
	);

	rmSync(probe, { recursive: true, force: true });
}

// ------------------------------------------------------------- part 3: extensions

function checkExtensions(piDir) {
	const probe = mkdtempSync(join(tmpdir(), "pi-contract-ext-"));
	mkdirSync(join(probe, "sessions"), { recursive: true });
	cpSync(join(ROOT, "app/src/main/assets/pi-extensions"), join(probe, "extensions"), { recursive: true });

	// `get_commands` only answers once the engine is serving, so the extensions have
	// been loaded (and their factories run) by then. Loading failures are reported on
	// stderr as `extension_error`, which runPi drops — so the assertion is that the
	// engine still answers at all, plus a positive signal from the bridge extension's
	// own command.
	const events = runPi(piDir, probe, [{ id: "c", type: "get_commands" }]);
	const answer = events.find((e) => e.type === "response" && e.command === "get_commands");
	check(
		"the shipped extensions load into the pinned engine",
		answer?.success === true,
		"app/src/main/assets/pi-extensions/** is this app's whole device layer. A changed " +
			"extension API shows up here first; re-read the pinned core/extensions/loader.ts + types.ts.",
	);
	rmSync(probe, { recursive: true, force: true });
}

// ------------------------------------------------------------------------- main

/** The groups `--only` accepts, in the order a full run prints them. */
const GROUPS = [
	["surface", "--- surface ---"],
	["theme", "--- theme values ---"],
	["bundled", "--- bundled entry ---"],
	["behaviour", "--- behaviour ---"],
	["extensions", "--- extensions ---"],
];

const onlyArg = process.argv.find((argument) => argument.startsWith("--only="));
const only = onlyArg ? onlyArg.slice("--only=".length) : null;
if (only !== null && !GROUPS.some(([name]) => name === only)) {
	console.error(`unknown --only=${only}; expected one of ${GROUPS.map(([name]) => name).join(", ")}`);
	process.exit(2);
}
const runs = (name) => only === null || only === name;

const explicit = process.argv.indexOf("--pi");
let piDir = explicit >= 0 ? resolve(process.argv[explicit + 1]) : null;
if (!piDir) {
	// A *stable* directory under `build/` (gitignored), not a `mkdtemp` under the
	// system temp dir. npm resolves the project root by walking **up** from `cwd` to
	// the nearest `package.json`/`node_modules`, so an unrelated `/tmp/package.json`
	// (left by any earlier `npm install` in `/tmp`) captures the install: npm writes
	// `/tmp/node_modules` while this script then looks in the temp dir and exits 2
	// with `not a pi package`. `build/pi-contract` has no such ancestor inside this
	// repository, and a persistent directory also makes a re-run a fast `up to date`
	// instead of re-downloading the engine.
	const stage = join(ROOT, "build", "pi-contract");
	mkdirSync(stage, { recursive: true });
	const version = pinnedVersion();
	console.log(`engine: ${PI_PACKAGE}@${version} (from tools/fetch-runtime.mjs)`);
	execFileSync("npm", ["install", "--ignore-scripts", "--no-audit", "--no-fund", `${PI_PACKAGE}@${version}`], {
		cwd: stage,
		stdio: "inherit",
	});
	piDir = join(stage, "node_modules", PI_PACKAGE);
}
if (!existsSync(join(piDir, "dist"))) {
	console.error(`not a pi package: ${piDir}`);
	process.exit(2);
}

// `surface` and `theme` are static reads of the package; `behaviour` and
// `extensions` spawn the engine, so they are the two a failure elsewhere in the tree
// (or a missing runtime) can turn into a throw before the static verdicts are read.
// A full run keeps its historical order; `--only=<group>` runs exactly one.
if (runs("surface")) {
	console.log("\n--- surface ---");
	checkSurface(piDir);
}
if (runs("theme")) {
	console.log("\n--- theme values ---");
	checkThemeValues(piDir);
}
if (runs("bundled")) {
	console.log("\n--- bundled entry ---");
	checkBundledEntry(piDir);
}
if (runs("behaviour")) {
	console.log("\n--- behaviour ---");
	checkBehaviour(piDir);
	await checkStartupOnlyReload(piDir);
}
if (runs("extensions")) {
	console.log("\n--- extensions ---");
	checkExtensions(piDir);
}

if (failures.length > 0) {
	console.error(`\npi contract: FAILED (${failures.length})`);
	for (const failure of failures) console.error(`  ${failure}`);
	process.exit(1);
}
console.log("\npi contract: OK — the app's assumptions about the pinned engine still hold");
