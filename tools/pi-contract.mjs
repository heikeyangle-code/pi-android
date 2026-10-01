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
 *  3. **App transcription tables** — the five lists the app keeps by hand *because pi
 *     has no channel for them*: settings keys, built-in slash commands, resource
 *     types, tool names and the theme token set. These are the copies that go stale
 *     silently (§M11/§M12's shape), and one of them already had: `/bug` was added to
 *     pi in 0.86.1 and the app answered it with "no such command" until this group
 *     existed. See [checkAppTables].
 *  4. **Tool-result text** — the fragments pi's *tools* write into a result, which
 *     `ui/blocks/ToolOutputParse.kt` parses as if they were a format (the `read`
 *     footer, the `grep`/`find` empty answers, the `limit reached` notice, the shell
 *     exit line, `details.truncation`'s keys), plus the duration ladder the app copies
 *     out of the TUI renderer. Nothing else in this file looks at
 *     `dist/core/tools/**`, and the app degrades to a generic card instead of failing,
 *     so a rename here is otherwise invisible. See [checkToolText].
 *  5. **Session surface** — the session-file facts the app transcribes rather than
 *     asks for: the entry types it models, that `CURRENT_SESSION_VERSION` is still 3,
 *     that `appendCompaction` still accepts a null `firstKeptEntryId` (retain-none
 *     compaction), and that the app still keeps entry types it does not model (the
 *     `context_edit` pair). pi exposes no RPC channel for any of them. See
 *     [checkSessionSurface].
 *  6. **Official model catalog** — the format of `@earendil-works/pi-ai`'s bundled
 *     `dist/providers/data/**`, which `packages/PiOfficialCatalog.kt` parses. Its
 *     sha256 check makes a *matching* file silent, so 0.99.0's rename of the inner keys
 *     from `<id>` to `<type>:<id>` — and its new image and classifier entries — reached
 *     the import sheet as extra chat models with nothing failing. See
 *     [checkCatalogData].
 *  7. **Behaviour** — `models.json` semantics, asserted by running the pinned
 *     engine: a declaration that omits `input`/`contextWindow` really does replace
 *     the catalog entry with pi's defaults (that is why the app must not declare
 *     models pi knows), `modelOverrides` really does merge (that is why it is the
 *     safe way to adjust one), and a catalog model's default `inputLimits` really is
 *     the 2000×2000 / 4.5 MiB(base64) / q80 profile `AttachmentBudget.kt` pre-resizes to.
 *  8. **Extensions** — the extensions this app ships still load into the pinned
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
 *                                              # tables | tooltext | session |
 *                                              # catalog | bundled | behaviour |
 *                                              # extensions
 *
 * `--only` exists because the groups cost very different things: `surface`, `theme`,
 * `tables`, `tooltext`, `session` and `catalog` are static reads of `dist/`, while
 * `behaviour` and the startup-reload check spawn the engine and `extensions` loads this
 * app's own extension tree. A group that fails for a reason outside its own subject (a
 * work-in-progress file under `app/src/main/assets/pi-extensions/`, say) should not
 * be able to hide a verdict about the palette.
 *
 * Exit code 0 = every assertion holds. Non-zero = read the last line.
 */

import { execFileSync, spawn } from "node:child_process";
import { cpSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
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
 * `resolveVarRefs` (`theme.ts:24`): a hex string, an empty string, a 0-255 integer
 * **and an `oklch()`/`okhsl()` colour** are literal; anything else names a `vars`
 * entry and resolves recursively. Returns `null` for a reference pi would reject
 * (missing target, or a cycle), so the caller reports it instead of throwing the
 * whole script away.
 *
 * The `ok(lch|hsl)` clause is 0.99.0's addition, and it is why this group failed
 * against 0.99.2 with a message about a missing file: without it, `okhsl(232 54% 67%)`
 * is looked up as a `vars` key, not found, and the whole read is abandoned — while the
 * file is right there. `theme.ts` spells the clause `/^ok(lch|hsl)\(/i`; it is repeated
 * here verbatim.
 */
function resolveThemeValue(value, vars, seen = new Set()) {
	if (
		typeof value === "number" ||
		value === "" ||
		value.startsWith("#") ||
		/^ok(lch|hsl)\(/i.test(value)
	) {
		return value;
	}
	if (seen.has(value) || !(value in vars)) return null;
	seen.add(value);
	return resolveThemeValue(vars[value], vars, seen);
}

// ------------------------------------------------------------- pi's colour grammar
//
// 0.99.0 rewrote the built-in themes in OKHSL and left `vars` references pointing at
// those functions, so comparing a theme against `PiPalette.kt` — which is Compose
// `Color(0xFF……)`, i.e. sRGB — now needs the conversion pi does in
// `@earendil-works/pi-tui`'s `colors.ts` and `oklab.ts`.
//
// This is a **port**, not an approximation, and here the difference matters: OKHSL's
// saturation is relative to the sRGB gamut *at that hue and lightness*, so the usual
// HSL formula would be wrong for most tokens — and wrong in a way that still looks
// plausible in a diff. The maths is Björn Ottosson's reference implementation (MIT),
// the same one pi ports. It is duplicated here rather than imported because this
// script asserts against a *directory*, and that directory is not guaranteed to have
// `node_modules` (CI hands `--pi` a freshly installed package, a developer hands it a
// checkout). Keep it in step with `packages/tui/src/oklab.ts`: a drift shows up as a
// value mismatch on the offending token, because the `theme` group compares every
// token's rendered hex — never as silence.

const OKLAB_LAB_TO_LMS = [
	[1, 0.3963377773761749, 0.2158037573099136],
	[1, -0.1055613458156586, -0.0638541728258133],
	[1, -0.0894841775298119, -1.2914855480194092],
];
const OKLAB_LMS_TO_LINEAR_SRGB = [
	[4.0767416360759583, -3.3077115392580629, 0.2309699031821043],
	[-1.2684379732850315, 2.6097573492876882, -0.341319376002657],
	[-0.0041960761386756, -0.7034186179359362, 1.7076146940746117],
];
/** Per sRGB channel: the (a, b) half-plane where it clips first, and the saturation fit. */
const OKLAB_SATURATION_FIT = [
	[
		[-1.8817031, -0.80936501],
		[1.19086277, 1.76576728, 0.59662641, 0.75515197, 0.56771245],
	],
	[
		[1.8144408, -1.19445267],
		[0.73956515, -0.45954404, 0.08285427, 0.12541073, -0.14503204],
	],
	[
		[0.13110758, 1.81333971],
		[1.35733652, -0.00915799, -1.1513021, -0.50559606, 0.00692167],
	],
];
const OKLAB_K1 = 0.206;
const OKLAB_K2 = 0.03;
const OKLAB_K3 = (1 + OKLAB_K1) / (1 + OKLAB_K2);

const oklabMatrix = (m, [x, y, z]) => m.map((row) => row[0] * x + row[1] * y + row[2] * z);
const okhslToOklabLightness = (x) => (x * x + OKLAB_K1 * x) / (OKLAB_K3 * (x + OKLAB_K2));
const oklabLinearToSrgb = (value) =>
	value > 0.0031308 ? 1.055 * value ** (1 / 2.4) - 0.055 : 12.92 * value;

/** Oklab `[L, a, b]` to linear sRGB `[r, g, b]` (0-1, may leave the gamut). */
function oklabToLinearSrgb(lab) {
	return oklabMatrix(
		OKLAB_LMS_TO_LINEAR_SRGB,
		oklabMatrix(OKLAB_LAB_TO_LMS, lab).map((value) => value ** 3),
	);
}

/** Linear sRGB (0-1) to sRGB channels (0-255, rounded), clipping out-of-gamut channels. */
function linearSrgbToRgb(linear) {
	const [r, g, b] = linear.map((value) =>
		Math.round(Math.min(1, Math.max(0, oklabLinearToSrgb(value))) * 255),
	);
	return { r, g, b };
}

const oklabLmsSlopes = (a, b) => OKLAB_LAB_TO_LMS.map((row) => row[1] * a + row[2] * b);

/** Largest saturation (C/L) inside sRGB for hue `(a, b)`: polynomial fit plus one Halley step. */
function oklabMaxSaturation(a, b) {
	const channel = OKLAB_SATURATION_FIT.findIndex(
		([[x, y]], index) => index === 2 || x * a + y * b > 1,
	);
	const [k0, k1, k2, k3, k4] = OKLAB_SATURATION_FIT[channel][1];
	const weights = OKLAB_LMS_TO_LINEAR_SRGB[channel];
	const saturation = k0 + k1 * a + k2 * b + k3 * a * a + k4 * a * b;
	const slopes = oklabLmsSlopes(a, b);
	const base = slopes.map((k) => 1 + saturation * k);
	const dot = (values) => values.reduce((sum, value, index) => sum + weights[index] * value, 0);
	const f = dot(base.map((value) => value ** 3));
	const f1 = dot(base.map((value, index) => 3 * slopes[index] * value ** 2));
	const f2 = dot(base.map((value, index) => 6 * slopes[index] ** 2 * value));
	return saturation - (f * f1) / (f1 * f1 - 0.5 * f * f2);
}

/** Oklab lightness and chroma of the most saturated sRGB colour of hue `(a, b)`. */
function oklabCusp(a, b) {
	const saturation = oklabMaxSaturation(a, b);
	const lightness = Math.cbrt(
		1 / Math.max(...oklabToLinearSrgb([1, saturation * a, saturation * b])),
	);
	return [lightness, lightness * saturation];
}

/** Chroma where the constant-lightness line at `lightness` leaves the sRGB gamut. */
function oklabMaxChroma(a, b, lightness, [cuspL, cuspC]) {
	if (lightness <= cuspL) return (cuspC * lightness) / cuspL;
	const t = (cuspC * (lightness - 1)) / (cuspL - 1);
	const slopes = oklabLmsSlopes(a, b);
	const lms = slopes.map((k) => lightness + t * k);
	const cubes = lms.map((value) => value ** 3);
	const first = lms.map((value, index) => 3 * slopes[index] * value ** 2);
	const second = lms.map((value, index) => 6 * slopes[index] ** 2 * value);
	const dot = (row, values) => row[0] * values[0] + row[1] * values[1] + row[2] * values[2];
	const steps = OKLAB_LMS_TO_LINEAR_SRGB.map((row) => {
		const f = dot(row, cubes) - 1;
		const f1 = dot(row, first);
		const f2 = dot(row, second);
		const u = f1 / (f1 * f1 - 0.5 * f * f2);
		return u >= 0 ? -f * u : Number.MAX_VALUE;
	});
	return t + Math.min(...steps);
}

/** OKHSL's chroma reference points at lightness `L` and hue `(a, b)`: `[c0, cMid, cMax]`. */
function oklabChromaStops(L, a, b) {
	const peak = oklabCusp(a, b);
	const cMax = oklabMaxChroma(a, b, L, peak);
	const k = cMax / Math.min(L * (peak[1] / peak[0]), (1 - L) * (peak[1] / (1 - peak[0])));
	const midS =
		0.11516993 +
		1 /
			(7.4477897 +
				4.1590124 * b +
				a *
					(-2.19557347 +
						1.75198401 * b +
						a *
							(-2.13704948 -
								10.02301043 * b +
								a * (-4.24894561 + 5.38770819 * b + 4.69891013 * a))));
	const midT =
		0.11239642 +
		1 /
			(1.6132032 -
				0.68124379 * b +
				a *
					(0.40370612 +
						0.90148123 * b +
						a * (-0.27087943 + 0.6122399 * b + a * (0.00299215 - 0.45399568 * b - 0.14661872 * a))));
	const cMid =
		0.9 * k * Math.sqrt(Math.sqrt(1 / (1 / (L * midS) ** 4 + 1 / ((1 - L) * midT) ** 4)));
	const c0 = Math.sqrt(1 / (1 / (L * 0.4) ** 2 + 1 / ((1 - L) * 0.8) ** 2));
	return [c0, cMid, cMax];
}

/** OKHSL to sRGB channels (0-255, rounded). Hue in degrees, saturation and lightness 0-1. */
function okhslToRgb(hue, saturation, lightness) {
	const L = okhslToOklabLightness(lightness);
	let lab = [L, 0, 0];
	if (L > 0 && L < 1 && saturation > 0) {
		const angle = (2 * Math.PI * (((hue % 360) + 360) % 360)) / 360;
		const a = Math.cos(angle);
		const b = Math.sin(angle);
		const [c0, cMid, cMax] = oklabChromaStops(L, a, b);
		let chroma;
		if (saturation < 0.8) {
			const t = 1.25 * saturation;
			const k1 = 0.8 * c0;
			chroma = (t * k1) / (1 - (1 - k1 / cMid) * t);
		} else {
			const t = 5 * (saturation - 0.8);
			const k1 = (0.2 * cMid ** 2 * 1.25 ** 2) / c0;
			chroma = cMid + (t * k1) / (1 - (1 - k1 / (cMax - cMid)) * t);
		}
		lab = [L, chroma * a, chroma * b];
	}
	return linearSrgbToRgb(oklabToLinearSrgb(lab));
}

const oklabInSrgbGamut = (linear) => linear.every((c) => c >= -1e-7 && c <= 1 + 1e-7);

/** OKLCH to sRGB channels, gamut-mapped by reducing chroma at a fixed hue. */
function oklchToRgb(l, c, h) {
	const radians = (h * Math.PI) / 180;
	const cos = Math.cos(radians);
	const sin = Math.sin(radians);
	const atChroma = (chroma) => oklabToLinearSrgb([l, chroma * cos, chroma * sin]);
	const direct = atChroma(c);
	if (oklabInSrgbGamut(direct)) return linearSrgbToRgb(direct);
	// The achromatic colour is always in gamut, so it is the fallback when no
	// bisection step fits (`oklch(100% 0.3 150)` must map to white).
	let linear = atChroma(0);
	let low = 0;
	let high = c;
	for (let index = 0; index < 20; index++) {
		const chroma = (low + high) / 2;
		const candidate = atChroma(chroma);
		if (oklabInSrgbGamut(candidate)) {
			low = chroma;
			linear = candidate;
		} else {
			high = chroma;
		}
	}
	return linearSrgbToRgb(linear);
}

const XTERM_BASIC = [
	[0, 0, 0],
	[128, 0, 0],
	[0, 128, 0],
	[128, 128, 0],
	[0, 0, 128],
	[128, 0, 128],
	[0, 128, 128],
	[192, 192, 192],
	[128, 128, 128],
	[255, 0, 0],
	[0, 255, 0],
	[255, 255, 0],
	[0, 0, 255],
	[255, 0, 255],
	[0, 255, 255],
	[255, 255, 255],
];
const XTERM_CUBE = [0, 95, 135, 175, 215, 255];

/** A 0-255 ANSI index to sRGB channels (`colors.ts`'s `indexedToRgb`). */
function indexedToRgb(index) {
	if (index < 16) {
		return { r: XTERM_BASIC[index][0], g: XTERM_BASIC[index][1], b: XTERM_BASIC[index][2] };
	}
	if (index < 232) {
		const cube = index - 16;
		return {
			r: XTERM_CUBE[Math.floor(cube / 36)],
			g: XTERM_CUBE[Math.floor((cube % 36) / 6)],
			b: XTERM_CUBE[cube % 6],
		};
	}
	const gray = 8 + (index - 232) * 10;
	return { r: gray, g: gray, b: gray };
}

const OKLAB_NUMBER = String.raw`[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?`;
// `colors.ts`'s `OKLCH_PATTERN` / `OKHSL_PATTERN`, verbatim. Note the different
// argument orders: OKLCH is `L C H` (L may carry `%`), OKHSL is `H S% L%`.
const OKLCH_PATTERN = new RegExp(
	`^oklch\\(\\s*(${OKLAB_NUMBER})(%)?\\s+(${OKLAB_NUMBER})\\s+(${OKLAB_NUMBER})(?:deg)?\\s*\\)$`,
	"i",
);
const OKHSL_PATTERN = new RegExp(
	`^okhsl\\(\\s*(${OKLAB_NUMBER})(?:deg)?\\s+(${OKLAB_NUMBER})(%)?\\s+(${OKLAB_NUMBER})(%)?\\s*\\)$`,
	"i",
);

/**
 * One theme value to sRGB channels, or `null` when it is nothing pi would accept.
 *
 * The grammar is `colors.ts`'s `parseColor`: a 3- or 6-digit hex string, an `oklch()`,
 * an `okhsl()`, or a 0-255 integer (an ANSI index).
 */
function themeColorToRgb(value) {
	if (typeof value === "number") {
		return Number.isInteger(value) && value >= 0 && value <= 255 ? indexedToRgb(value) : null;
	}
	if (typeof value !== "string") return null;
	const hex = /^#([\da-f]{3}|[\da-f]{6})$/i.exec(value);
	if (hex) {
		const digits = hex[1].length === 3 ? [...hex[1]].map((digit) => digit + digit).join("") : hex[1];
		return {
			r: Number.parseInt(digits.slice(0, 2), 16),
			g: Number.parseInt(digits.slice(2, 4), 16),
			b: Number.parseInt(digits.slice(4, 6), 16),
		};
	}
	const oklch = OKLCH_PATTERN.exec(value);
	if (oklch) {
		const lightness = Number.parseFloat(oklch[1]) / (oklch[2] ? 100 : 1);
		return oklchToRgb(lightness, Number.parseFloat(oklch[3]), Number.parseFloat(oklch[4]));
	}
	const okhsl = OKHSL_PATTERN.exec(value);
	if (okhsl) {
		const saturation = Number.parseFloat(okhsl[2]) / (okhsl[3] ? 100 : 1);
		const lightness = Number.parseFloat(okhsl[4]) / (okhsl[5] ? 100 : 1);
		return okhslToRgb(Number.parseFloat(okhsl[1]), saturation, lightness);
	}
	return null;
}

/** One theme value to lowercase `#rrggbb`, or `null` when pi would reject it. */
function themeColorToHex(value) {
	const rgb = themeColorToRgb(value);
	if (rgb === null) return null;
	const channel = (n) => n.toString(16).padStart(2, "0");
	return `#${channel(rgb.r)}${channel(rgb.g)}${channel(rgb.b)}`;
}

/**
 * Every `colors` token plus the three `export` surfaces of one built-in theme,
 * resolved the way pi resolves them: `vars` references followed, the five optional
 * tokens filled from their fallbacks, `export` folded in, and each value rendered to
 * lowercase `#rrggbb` — because `PiPalette.kt` is Compose `Color(0xFF……)`, i.e. sRGB,
 * and 0.99.0's themes are written in `okhsl()`.
 *
 * Returns `{colors, why}`: `colors` is `null` when the file is missing (a package that
 * moved its themes) **or** when a value refuses to resolve, and `why` says which. The
 * two used to share one message, which is how a reader bug read as "the engine no
 * longer ships dark.json" while the file sat on disk — see `resolveThemeValue`.
 */
function builtinThemeColors(piDir, name) {
	const file = builtinThemePath(piDir, name);
	if (!existsSync(file)) {
		return { colors: null, why: `the file is missing (looked for ${file})` };
	}
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
		if (resolved === null) {
			return {
				colors: null,
				why:
					`${file} has a \`${token}\` value this reader cannot resolve ` +
					`(${JSON.stringify(value)}: neither a hex colour, an okhsl()/oklch() colour, ` +
					`a 0-255 index, nor a \`vars\` entry that resolves). The file is there — the ` +
					`value grammar is what changed. Re-read \`resolveVarRefs\` in the pinned ` +
					`\`modes/interactive/theme/theme.ts\``,
			};
		}
		const hex = themeColorToHex(resolved);
		if (hex === null) {
			return {
				colors: null,
				why:
					`${file}'s \`${token}\` resolves to ${JSON.stringify(resolved)}, which this reader ` +
					`cannot turn into sRGB. The colour grammar comes from ` +
					`\`@earendil-works/pi-tui\`'s \`colors.ts\` (\`parseColor\`); re-read it and port ` +
					`the change into \`themeColorToRgb\` here`,
			};
		}
		out[token] = hex;
	}
	return { colors: out, why: null };
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
		const read = builtinThemeColors(piDir, name);
		const pi = read.colors;
		check(
			`the pinned engine's ${name} theme still reads as this checker reads it (${file})`,
			pi !== null,
			`PiPalette.${theme} is a transcription of that file, and every screen reads it through ` +
				`PiTheme.palette. ${read.why}. Do not delete this check: nothing else notices a ` +
				`re-colouring. Re-read the pinned theme file and the code named above, then re-point ` +
				`this reader.`,
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
		const pi = builtinThemeColors(piDir, themeName).colors;
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

	// (3) `inputLimits` (new in 0.87.1) — the one fact `AttachmentBudget.kt` transcribes
	//     *as a default*. A catalog model the engine reports carries the resolved profile,
	//     so the three numbers the App's arithmetic is built on are assertable directly
	//     instead of re-read from source. `maxBytes` counts base64 characters, which is
	//     the unit `AttachmentBudget.PI_MAX_BASE64_CHARS` speaks.
	const limits = model.inputLimits?.images?.resize;
	check(
		"a catalog model's default image resize profile is still 2000x2000 / 4.5 MiB(base64) / q80",
		limits?.maxWidth === 2000 &&
			limits?.maxHeight === 2000 &&
			limits?.maxBytes === 4718592 &&
			limits?.jpegQuality === 80,
		`app.pi pre-resizes an attachment to pi's own ceiling before sending it ` +
			`(ui/screens/AttachmentBudget.kt: PI_MAX_DIMENSION, PI_MAX_BASE64_CHARS, PI_JPEG_QUALITIES, ` +
			`cited to utils/image-resize-core.ts). ${engineVersion(piDir)} reports ` +
			`${JSON.stringify(limits)}. If the default moved, the App's "this image fits" arithmetic no ` +
			`longer matches what pi will send — re-read utils/image-resize-core.ts and re-derive ` +
			`AttachmentBudget's constants + its harness, or stop pre-resizing.`,
	);
	//     The App's editors reach a catalog model only through `modelOverrides` (never
	//     `models[]`, which replaces the entry — §M11), so the new nested field has to be
	//     adjustable through that path.
	writeModelsJson(probe, {
		baseUrl: "https://api.deepseek.com",
		api: "openai-completions",
		modelOverrides: { [id]: { inputLimits: { images: { resize: { maxBytes: 999 } } } } },
	});
	const afterLimits = availableModels(piDir, probe).find((m) => m.provider === "deepseek" && m.id === id);
	check(
		"modelOverrides can set inputLimits (and keeps the rest of the model intact)",
		afterLimits?.inputLimits?.images?.resize?.maxBytes === 999 &&
			afterLimits?.inputLimits?.images?.resize?.maxWidth === 2000 &&
			JSON.stringify(afterLimits?.input) === JSON.stringify(model.input),
		`the App's model editor adjusts a catalog model only through modelOverrides ` +
			`(packages/PiCredentialService.kt's "declare only what pi cannot know" rule, §M11). If a nested ` +
			`object like inputLimits did not merge, that rule would have to be re-derived — re-read the ` +
			`pinned core/provider-composer.ts's applyOverride.`,
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
 * 「等待重启」, and `ModelProviderScreen` tells the user to restart for it. If pi ever grew an
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
			"PiModelInventory's PENDING_RESTART status and ModelProviderScreen's 「等待重启」 + " +
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

// ------------------------------------------------------- part 2b: tool-result text

/**
 * The **text pi's tools write into a result**, which this app parses as if it were a
 * format. `app/src/main/kotlin/app/pi/ui/blocks/ToolOutputParse.kt` turns a tool
 * result into a block by matching pi's own strings — the `read` footer, the `grep` and
 * `find` empty answers, the `limit reached` notice, the shell exit line — and
 * `ToolBlockChrome.kt`/`ShellBlock.kt` render pi's `Elapsed`/`Took` figure from a
 * duration ladder copied out of `core/tools/renderers/bash.ts`.
 *
 * ## Why this needs its own group
 *
 * Nothing in this script looked at `dist/core/tools/**` or `renderers/**` before: the
 * `surface` group reads RPC command *names*, and the design of `ToolOutputParse` is
 * "never throw" — an unrecognised body degrades to the generic card. So a renamed
 * footer is **invisible**: no build failure, no crash, just a worse transcript on the
 * phone. That is the §M11/§M12 shape one layer lower (the app believes a copy of pi's
 * text and the copy went stale).
 *
 * ## Why the producer file is part of the assertion
 *
 * The same sentence can survive in the engine for unrelated reasons (a docs string, a
 * system prompt, a fixture), which is how a `surface` check can pass vacuously. Each
 * row below therefore reads **the file that produces the text**, and the failure names
 * the App code that depends on it.
 *
 * ## What this deliberately does not do
 *
 * It does not pin whole sentences with their numbers (`` "[Showing lines 3-40 of 900.
 * Use offset=41 to continue.]" `` is built from templates), and it does not assert the
 * layout the app invents *around* pi's text — only pi's own fragments. A rewrite that
 * keeps the distinctive prefix still passes; that is the intended sensitivity, because
 * the app matches on those prefixes.
 */
const TOOL_TEXT = [
	{
		what: "read's truncation footer (both branches)",
		file: "core/tools/read.js",
		needles: ["Showing lines ", "more lines in file. Use offset="],
		app: "SourceBody.footer (ToolOutputParse.kt, `readBody`)",
	},
	{
		what: "grep's empty answer",
		file: "core/tools/grep.js",
		needles: ["No matches found"],
		app: "GrepBody.empty (ToolOutputParse.kt, `grepBody`)",
	},
	{
		what: "find's empty answer",
		file: "core/tools/find.js",
		needles: ["No files found matching pattern"],
		app: "the `find` body (ToolOutputParse.kt, `patternListBody`)",
	},
	{
		what: "the shell tools' non-zero exit line",
		file: "core/tools/bash.js",
		needles: ["Command exited with code "],
		app: "ShellBody's exit line (app/src/main/kotlin/app/pi/ui/blocks/ShellBlock.kt)",
	},
];

/**
 * The `limit reached` notice. `ToolOutputParse.NOTICE_SUBJECT` is the regex
 * `limit|truncated`, applied to a result's trailing line to decide whether that line
 * is pi's own footnote rather than tool output — so the *phrase*, not a full sentence,
 * is the contract. `ls` shares `find`'s notice text.
 */
const TOOL_NOTICE_FILES = ["core/tools/grep.js", "core/tools/find.js", "core/tools/ls.js"];

/** The `details.truncation` keys the app reads off a tool result. */
const TRUNCATION_KEYS = ["truncated", "truncatedBy", "outputLines", "totalLines"];

function checkToolText(piDir) {
	for (const row of TOOL_TEXT) {
		const file = join(piDir, "dist", row.file);
		const text = existsSync(file) ? readFileSync(file, "utf8") : "";
		for (const needle of row.needles) {
			check(
				`${row.file} still produces ${JSON.stringify(needle)}`,
				text.includes(needle),
				`app.pi parses pi's tool-result text: this fragment is what ` +
					`${row.app} keys on. A rename here does not fail the build and does not throw — ` +
					`ToolOutputParse answers null by design and the block degrades to the generic card. ` +
					`Re-read the pinned ${row.file}, update the Kotlin matcher, and update this row.`,
			);
		}
	}

	for (const file of TOOL_NOTICE_FILES) {
		const full = join(piDir, "dist", file);
		const text = existsSync(full) ? readFileSync(full, "utf8") : "";
		check(
			`${file} still says "limit reached"`,
			text.includes("limit reached"),
			`app.pi tells pi's own limit footnote apart from tool output by the words ` +
				`limit/truncated (ToolOutputParse.kt's NOTICE_SUBJECT, used by the grep and ` +
				`find/ls bodies). Without the phrase that line is rendered as if the tool had ` +
				`printed it. Re-read the pinned ${file} and the App's notice test.`,
		);
	}

	// `TruncationResult` (`core/tools/truncate.ts`) is handed to the app inside a tool
	// result's `details`, and `ToolOutputParse` reads it as an object — a renamed key
	// silently becomes "not truncated" on the phone while pi really did truncate.
	const truncateFile = join(piDir, "dist", "core/tools/truncate.js");
	const truncate = existsSync(truncateFile) ? readFileSync(truncateFile, "utf8") : "";
	for (const key of TRUNCATION_KEYS) {
		check(
			`truncate.js still reports details.truncation.${key}`,
			truncate.includes(key),
			`app.pi reads this key off a tool result (ToolOutputParse.kt, and the "已截断" footer in ` +
				`ShellBlock.kt / PathListBlock.kt). A renamed key reports "not truncated" for output pi ` +
				`did truncate. Re-read the pinned core/tools/truncate.ts + the tool that sets it.`,
		);
	}

	// The one pi fact this app **copies** rather than parses: pi's duration units, taken
	// from the TUI renderer. The renderer's output never reaches RPC — the App
	// re-implements the ladder in Kotlin so a phone reads a duration the way pi's
	// terminal does. A change here is therefore a *silent divergence*: both surfaces keep
	// working and stop agreeing.
	//
	// Note what is *not* asserted: the words. The App's own label is Chinese
	// (`已运行`/`耗时`), so "Elapsed" exists only on pi's side — asserting the App spells
	// "Elapsed" would fail on correct code. What the two sides share is the unit ladder:
	// `%.1fs` under a minute, `m` + `s` under an hour, `h` + `m` + `s` above it.
	const bashRenderer = join(piDir, "dist", "core/tools/renderers", "bash.js");
	const renderer = existsSync(bashRenderer) ? readFileSync(bashRenderer, "utf8") : "";
	check(
		"renderers/bash.js still carries pi's Elapsed/Took label and a duration ladder",
		renderer.includes("Elapsed") && renderer.includes("Took") && renderer.includes("m "),
		`app.pi reproduces pi's elapsed figure and its unit ladder ` +
			`(ToolOutputParse.formatDuration + elapsedText, used by ShellBlock.kt and ` +
			`ToolBlockChrome.kt) from renderers/bash.ts's formatDuration. If this file moved or the ` +
			`ladder changed, re-read the pinned renderers/bash.ts and the App's copy together — the two ` +
			`surfaces silently disagree otherwise.`,
	);
	const appFormat = readFileSync(join(ROOT, "app/src/main/kotlin/app/pi/ui/blocks/ToolOutputParse.kt"), "utf8");
	check(
		"the App still carries that copied unit ladder (formatDuration)",
		["fun formatDuration(", '"%.1fs"', "m ${"].every((needle) => appFormat.includes(needle)),
		`ToolOutputParse.formatDuration is the App's copy of pi's duration ladder ` +
			`(renderers/bash.ts:32-42) and ToolBlockChrome.toolHeaderReading prints it. If the App ` +
			`dropped or reshaped it, this group no longer describes the same fact — either restore it ` +
			`or delete this row together with the copy.`,
	);
	console.log(
		`   (${TOOL_TEXT.reduce((n, r) => n + r.needles.length, 0)} tool-text fragments, ` +
			`${TOOL_NOTICE_FILES.length} limit notices, ${TRUNCATION_KEYS.length} truncation keys)`,
	);
}

// -------------------------------------------------------- part 2c: session surface

/**
 * The **session-file** facts the app hard-codes instead of asking for.
 *
 * `rpc/src/main/kotlin/app/pi/rpc/SessionEntries.kt` maps pi's persisted `SessionEntry`
 * union to Kotlin, `app/src/main/kotlin/app/pi/session/SessionFileReader.kt` reads the
 * JSONL directly, and `Transcript.kt` resolves compactions by `firstKeptEntryId`. None
 * of these can be asked over RPC — `get_entries` hands back entries but never the
 * union, and the file format's own version integer is not reported anywhere. So the
 * facts below are transcriptions, each with a distinct failure mode:
 *
 *  - a **renamed** entry type the app models would come back `SessionEntry.Unknown`
 *    (kept, but shown as raw JSON — the tree screen loses its row);
 *  - a **bumped** `CURRENT_SESSION_VERSION` means pi may rewrite the file in a shape
 *    the app's reader was not written for, and nothing else in the build looks at it;
 *  - a **narrowed** `firstKeptEntryId` (back to non-null) would make retain-none
 *    compactions unrepresentable in Kotlin, where the field is deliberately `String?`.
 *
 * ## The unmodelled entry type is asserted, deliberately
 *
 * `context_edit` arrived in 0.87.1 and is **not** in the app's modelled set. That is the
 * design, not a gap: `parseSessionEntry`'s `else -> SessionEntry.Unknown` is what makes
 * an entry written by a newer pi *kept* rather than dropped. So this group asserts the
 * pair — pi still writes `context_edit` (the reason the Unknown arm is exercised), and
 * the App still has the Unknown arm that keeps it. Asserting only the first, or
 * "helpfully" adding `context_edit` to the modelled list, would each break the property
 * the pair is there to protect.
 */
const APP_ENTRY_TYPES = [
	["message", "SessionMessageEntry"],
	["model_change", "ModelChangeEntry"],
	["thinking_level_change", "ThinkingLevelChangeEntry"],
	["compaction", "CompactionEntry"],
	["branch_summary", "BranchSummaryEntry"],
	["custom", "CustomEntry"],
	["custom_message", "CustomMessageEntry"],
	["label", "LabelEntry"],
	["session_info", "SessionInfoEntry"],
];

function checkSessionSurface(piDir) {
	const sessionManager = readFileSync(join(piDir, "dist", "core", "session-manager.js"), "utf8");
	const declared = readFileSync(join(piDir, "dist", "core", "session-manager.d.ts"), "utf8");

	const version = /CURRENT_SESSION_VERSION\s*=\s*(\d+)/.exec(sessionManager)?.[1] ?? null;
	check(
		"the pinned engine still writes session format version 3",
		version === "3",
		`app.pi reads the session JSONL itself (session/SessionFileReader.kt, ` +
			`rpc/.../SessionEntries.kt) and has no channel for this integer — pi reports it nowhere ` +
			`over RPC. A bump means pi may migrate or write a shape the reader was not written for; ` +
			`re-read the pinned core/session-manager.ts's migrateSessionEntries and the App's reader ` +
			`together before touching this check.`,
	);

	const union = /export type SessionEntry =([^;]+);/.exec(declared)?.[1] ?? "";
	for (const [type, kotlin] of APP_ENTRY_TYPES) {
		check(
			`the pinned engine's SessionEntry union still contains the ${type} entry the app models`,
			union.includes(kotlin),
			`rpc/.../SessionEntries.kt parses "${type}". If it left the union, pi stopped writing it and ` +
				`the App's data class is dead code (or the entry was renamed, in which case the App must ` +
				`follow or it will render it as SessionEntry.Unknown's raw JSON). Re-read the pinned ` +
				`core/session-manager.d.ts and update SessionEntries.kt + its consumers.`,
		);
	}

	// The pair described above: a newer pi's entry type exists, and the App has the arm
	// that keeps it. If this fails on the App side, unknown entries are being *dropped*,
	// which is the one outcome the design forbids.
	const appEntries = readFileSync(join(ROOT, "rpc/src/main/kotlin/app/pi/rpc/SessionEntries.kt"), "utf8");
	check(
		"pi still has an entry type the app does not model (context_edit), and the app still keeps unknown types",
		union.includes("ContextEditEntry") && /else\s*->\s*SessionEntry\.Unknown\(/.test(appEntries),
		`SessionEntries.kt's \`else -> SessionEntry.Unknown(type, meta, raw)\` is what keeps an entry ` +
			`written by a newer pi instead of dropping it (the ${union.includes("ContextEditEntry") ? "still-present" : "now-absent"} ` +
			`context_edit is the current example). If pi no longer writes any type the App does not model, ` +
			`this row is vacuous and should be replaced by whatever the next new type is; if the App lost ` +
			`its Unknown arm, newer sessions silently lose rows — re-read SessionEntries.kt's parser.`,
	);

	// The retain-none compaction: `appendCompaction(summary, null, tokensBefore)` keeps no
	// preceding entries. The App's `Compaction.firstKeptEntryId` is `String?` for this
	// reason (`SessionEntries.kt`, `Transcript.kt`'s `onCompactionEntry`).
	check(
		"firstKeptEntryId is still nullable in appendCompaction",
		/firstKeptEntryId:\s*string\s*\|\s*null/.test(declared),
		`app.pi models Compaction.firstKeptEntryId as String? and Transcript.onCompactionEntry ` +
			`carries it forward when a later compaction omits it. If pi narrowed the type back to ` +
			`string, a retain-none compaction becomes unrepresentable in Kotlin — re-read the pinned ` +
			`core/session-manager.d.ts's appendCompaction and the App's parse path.`,
	);
	console.log(`   (${APP_ENTRY_TYPES.length} modelled entry types, session version ${version})`);
}

// -------------------------------------- part 2d: the app's own transcription tables

/**
 * The tables the app maintains **because pi offers no channel for them**, asserted
 * against the engine that actually ships.
 *
 * `docs/pi-sourced-lists.md` permits an app-owned table only when pi cannot be asked:
 * `get_commands` excludes built-ins by design, settings have no RPC schema, the theme
 * token set has no endpoint, and the built-in tool names appear only inside the system
 * prompt. The rule has a price — these copies go stale **silently**, which is the
 * §M11/§M12 shape this repository has already paid for twice. This group is that
 * price's insurance:
 *
 *  - **settings keys** (`ui/settings/PiSettingsRegistry.kt`) against the pinned
 *    `Settings` interface. A key pi deleted or renamed is a row the user can still
 *    toggle and pi will ignore — the "switch with no effect" bug
 *    (`docs/settings-review.md`). The *reverse* direction is printed, not asserted:
 *    pi having a key the app does not expose is a decision, not a defect.
 *  - **built-in slash commands** (`ui/chat/PiSlashCommands.kt`): pi's
 *    `BUILTIN_SLASH_COMMANDS` is excluded from `get_commands`, so the app keeps both
 *    halves of the list by hand (the rows it shows, and the sentence it gives for a
 *    name the user types from memory). Both directions are asserted, because a *new*
 *    pi built-in is exactly what goes unnoticed: `/bug` arrived in 0.86.1 and this
 *    table answered it with "no such command" until 0.87.1's audit.
 *  - **resource types** (`packages/PiPackageFilters.kt`) against pi's own four keys.
 *  - **tool names** (`PiQuickAdd.builtinToolDefaults` + the registry's `optionalTools`
 *    presets) against pi's `ToolName` union: a chip naming a tool pi no longer has
 *    writes a whitelist that silently enables nothing.
 *  - **theme tokens** (`ui/theme/PiThemeFiles.kt`): the required set and the five
 *    optional fallbacks, against `theme-schema.json` and `theme.js`'s fallback map.
 *    The `theme` group asserts the *values* of the built-in themes; this asserts the
 *    app's own schema transcription, which is what decides whether a user's theme file
 *    is accepted at all.
 *
 * Every reader below has a **floor** on what it extracted. A regex that stopped
 * matching would otherwise turn each assertion into a silent pass — the technique
 * `checkThemeValues` uses for `PiPalette.kt`.
 */

/** `val <name> = listOf("a", "b")` — the quoted entries, in order. */
function kotlinListOf(text, name) {
	const match = new RegExp(`val\\s+${name}\\s*(?::[^=]+)?=\\s*listOf\\(([\\s\\S]*?)\\)`).exec(text);
	if (!match) return null;
	return [...match[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]);
}

/** `val <name> ... = mapOf("k" to "v", …)` — the keys, in order. */
function kotlinMapOf(text, name) {
	const match = new RegExp(`val\\s+${name}\\s*(?::[^=]+)?=\\s*mapOf\\(([\\s\\S]*?)\\n\\s*\\)`).exec(text);
	if (!match) return null;
	return [...match[1].matchAll(/"([^"]+)"\s+to\s+"/g)].map((m) => m[1]);
}

/** A `val <name> = listOf(...)` block's raw text, for readers with their own shape. */
function kotlinBlock(text, name) {
	const match = new RegExp(`val\\s+${name}\\s*(?::[^=]+)?=\\s*([\\s\\S]*?)\\n(?:val|fun|private val|/\\*\\*|\\}\n)`).exec(text);
	return match ? match[1] : null;
}

/** `export interface Name { … }` blocks of a `.d.ts`, name → body. */
function tsInterfaces(text) {
	const out = new Map();
	for (const match of text.matchAll(/export interface (\w+)(?:\s+extends\s+[^{]+)?\s*\{([\s\S]*?)\n\}/g)) {
		out.set(match[1], match[2]);
	}
	return out;
}

/**
 * Whether a dotted settings path resolves from `Settings` by walking the declared
 * interfaces. pi's schema is TypeBox-generated, so the `.d.ts` mirrors it one
 * interface per nested object; an inline object type is a leaf and resolves here.
 */
function settingsPathResolves(interfaces, path) {
	let body = interfaces.get("Settings");
	if (!body) return false;
	const segments = path.split(".");
	for (let index = 0; index < segments.length; index++) {
		const match = new RegExp(`^\\s*${segments[index]}\\??\\s*:\\s*([^;]+);`, "m").exec(body);
		if (!match) return false;
		if (index === segments.length - 1) return true;
		const named = /^(\w+)$/.exec(match[1].trim())?.[1];
		if (!named || !interfaces.has(named)) return false;
		body = interfaces.get(named);
	}
	return true;
}

function checkAppTables(piDir) {
	const read = (file) => readFileSync(join(ROOT, file), "utf8");
	const engine = (file) => readFileSync(join(piDir, "dist", file), "utf8");

	// ---------------------------------------------------------------- settings keys
	const settingsDeclared = engine("core/settings-manager.d.ts");
	const interfaces = tsInterfaces(settingsDeclared);
	const registryText = read("app/src/main/kotlin/app/pi/ui/settings/PiSettingsRegistry.kt");
	const appKeys = [...registryText.matchAll(/key = "([^"]+)"/g)]
		.map((m) => m[1])
		.filter((key) => !key.startsWith("app."));
	check(
		"the settings registry still exposes the keys this group reads (>= 35 pi-owned keys)",
		appKeys.length >= 35 && interfaces.has("Settings"),
		`ui/settings/PiSettingsRegistry.kt declares its rows as \`key = "…"\` and this reader takes ` +
			`every key that is not \`app.\`-prefixed. It found ${appKeys.length} and ` +
			`${interfaces.has("Settings") ? "the" : "NO"} \`Settings\` interface in the pinned ` +
			`settings-manager.d.ts. If a row was renamed or reformatted, re-point this reader (the floor ` +
			`is what keeps a stopped reader from passing vacuously).`,
	);
	for (const key of appKeys) {
		check(
			`the pinned engine's Settings schema still has ${key}`,
			settingsPathResolves(interfaces, key),
			`ui/settings/PiSettingsRegistry.kt writes ${key} into settings.json. If pi deleted or renamed ` +
				`it, the row still renders and still saves and pi ignores it — the "switch with no effect" ` +
				`shape of docs/settings-review.md. Re-read the pinned core/settings-manager.ts's Settings ` +
				`schema and either follow the rename or delete the row.`,
		);
	}
	// Informational: the other direction is a decision (TUI-only keys), not a defect.
	const piTopLevel = new Set([...interfaces.get("Settings").matchAll(/^ {4}(\w+)\??:/gm)].map((m) => m[1]));
	const appTopLevel = new Set(appKeys.map((key) => key.split(".")[0]));
	const notExposed = [...piTopLevel].filter((key) => !appTopLevel.has(key)).sort();
	console.log(
		`   (${appKeys.length} pi-owned settings keys pinned, ${piTopLevel.size} in pi; ` +
			`${notExposed.length} pi keys not exposed by the app: ${notExposed.join(", ")})`,
	);

	// ------------------------------------------------- the reverse direction, as decisions
	//
	// "Not exposed" is a decision per key, so it is written down per key rather than
	// merely printed. The printer above was the only witness when 0.99.2 added
	// `deviceId`, `codemode` and `fullscreenWheelScrollLines`: a new key means a switch
	// the user cannot reach, or — worse, and this is the §M12 shape this repository has
	// already paid for twice — a value the app's editor never knew it had to preserve.
	// A key that is neither exposed nor declared now fails, and a declaration that has
	// gone stale (pi removed the key, or the app started exposing it) fails too, so the
	// list cannot rot into a description of a state that cannot happen.
	const UNEXPOSED_SETTINGS_DECISIONS = {
		// TUI-only: the original terminal interface's own presentation and input
		// details, which this app re-implements in Compose. Changing them changes
		// nothing a phone shows.
		autocompleteMaxVisible: "TUI 输入框的自动补全可见行数",
		collapseChangelog: "TUI 启动后折叠 changelog",
		doubleEscapeAction: "TUI 双击 Esc 的行为",
		editorPaddingX: "TUI 编辑器左右留白",
		externalEditor: "TUI 的 Ctrl+G 外部编辑器",
		fullscreenCopyOnSelect: "TUI 全屏下选择即复制",
		fullscreenExitOutput: "TUI 全屏退出后是否回显输出",
		fullscreenScrollbar: "TUI 全屏滚动条",
		fullscreenWheelScrollLines: "TUI 全屏鼠标滚轮行数（0.99.0 新键）",
		markdown: "TUI 的 markdown 渲染档位",
		outputPad: "TUI 输出左右留白（0|1）",
		quietStartup: "TUI 启动横幅",
		showHardwareCursor: "TUI 是否显示硬件光标",
		tuiMode: "TUI 渲染模式",
		// pi's own bookkeeping rather than a value the user decides.
		deviceId:
			"pi 为 ChatGPT 登录生成的安装标识（0.99.0 新键）：只写全局设置、从 bug report 排除。" +
			"App 不展示也不删（写入路径是锁内重读整份文档再改目标键）",
		lastChangelogVersion: "pi 自己记录的已读 changelog 版本",
		trackingId: "随 enableAnalytics 生成的匿名标识",
		enableAnalytics: "匿名分析开关：本应用不发遥测，所以没有这个入口",
		// Resource lists: the app has a screen for each of them, and editing the raw
		// arrays would fight those screens and `PiPackageFilters`' own `+`/`-`/`!` syntax.
		extensions: "资源列表：由「资源包」屏与 PiPackageFilters 的过滤语法管理",
		prompts: "资源列表：由「已发现的资源」屏展示",
		skills: "资源列表：由「已发现的资源」屏展示",
		themes: "资源列表：主题由「主题」屏选择（PiThemeFiles 自己扫描目录）",
		terminal: "终端能力覆盖（hyperlinks/trueColor/images）：App 不经过终端渲染",
		warnings: "pi 的警告开关（anthropicExtraUsage）",
		sessionDir: "会话目录：App 在命令行上固定它（--session-dir），见 G_SESSIONS 的分组摘要",
		treeFilterMode: "/tree 的默认过滤器：App 的会话树有自己的筛选",
	};
	const undecided = notExposed.filter((key) => !(key in UNEXPOSED_SETTINGS_DECISIONS));
	check(
		`every pi settings key the app does not expose is a written-down decision (${Object.keys(UNEXPOSED_SETTINGS_DECISIONS).length} decided)`,
		undecided.length === 0,
		`ui/settings/PiSettingsRegistry.kt neither exposes nor declares ${undecided.length} of pi's ` +
			`settings keys: ${undecided.join(", ")}. Each is a decision — expose it as a row, or add it ` +
			`to UNEXPOSED_SETTINGS_DECISIONS in this file with the reason (TUI-only, pi-internal, ` +
			`superseded …). The failure mode is silence in both directions: a key the user cannot ` +
			`reach, or a value the app's single-key editor never knew it had to preserve.`,
	);
	const staleDecisions = Object.keys(UNEXPOSED_SETTINGS_DECISIONS).filter(
		(key) => !notExposed.includes(key),
	);
	check(
		`the unexposed-settings decisions still describe reality (${staleDecisions.length} stale)`,
		staleDecisions.length === 0,
		`These keys are declared unexposed but are no longer in \`notExposed\`: ` +
			`${staleDecisions.join(", ")}. Either pi renamed or removed them, or the app now exposes ` +
			`them — delete the line rather than leave one that describes a state which cannot happen.`,
	);

	// ------------------------------------------------------- built-in slash commands
	const slashText = read("app/src/main/kotlin/app/pi/ui/chat/PiSlashCommands.kt");
	const listedBlock = kotlinBlock(slashText, "PI_BUILTIN_SLASH_COMMANDS") ?? "";
	const unlistedBlock = kotlinBlock(slashText, "PI_UNLISTED_BUILTIN_COMMANDS") ?? "";
	const listed = [...listedBlock.matchAll(/PiSlashCommand\(\s*"([a-z-]+)"/g)].map((m) => m[1]);
	const unlisted = [...unlistedBlock.matchAll(/^\s*"([a-z-]+)"\s+to\s+"/gm)].map((m) => m[1]);
	const piBuiltins = [...engine("core/slash-commands.js").matchAll(/name: "([a-z-]+)"/g)].map((m) => m[1]);
	check(
		"the built-in slash-command tables are still readable (>= 7 rows + >= 10 hints, and pi has >= 20)",
		listed.length >= 7 && unlisted.length >= 10 && piBuiltins.length >= 20,
		`ui/chat/PiSlashCommands.kt keeps both halves of pi's built-in list ` +
			`(${listed.length} rows + ${unlisted.length} hints read; pi's BUILTIN_SLASH_COMMANDS has ` +
			`${piBuiltins.length}). The reader matches \`PiSlashCommand("name"\` inside the rows block ` +
			`and \`"name" to "…"\` inside the hints map; re-point it if either was reformatted.`,
	);
	for (const name of [...listed, ...unlisted]) {
		check(
			`pi still has the built-in slash command /${name}`,
			piBuiltins.includes(name),
			`ui/chat/PiSlashCommands.kt names /${name}. If pi removed or renamed it, the app either ` +
				`offers a command that does nothing (a row) or tells the user pi has a command it no ` +
				`longer has (a hint). Re-read the pinned core/slash-commands.ts's BUILTIN_SLASH_COMMANDS ` +
				`and update the row/hint.`,
		);
	}
	for (const name of piBuiltins) {
		check(
			`the app covers pi's built-in slash command /${name}`,
			listed.includes(name) || unlisted.includes(name),
			`pi's BUILTIN_SLASH_COMMANDS has /${name} and neither table in ui/chat/PiSlashCommands.kt ` +
				`names it, so a user who types it is told there is no such command — which is false. This ` +
				`is how /bug (new in 0.86.1) stayed uncovered. Add it to PI_BUILTIN_SLASH_COMMANDS as a ` +
				`row or to PI_UNLISTED_BUILTIN_COMMANDS with an honest sentence.`,
		);
	}

	// -------------------------------------------------------------- resource types
	const appResourceTypes = kotlinListOf(read("app/src/main/kotlin/app/pi/packages/PiPackageFilters.kt"), "RESOURCE_TYPES");
	const piResourceTypes = [.../const RESOURCE_TYPES = \[([^\]]*)\]/.exec(engine("modes/interactive/components/config-selector.js"))?.[1].matchAll(/"([^"]+)"/g) ?? []].map((m) => m[1]);
	check(
		"the resource-type list is still readable on both sides (4 keys)",
		appResourceTypes?.length === 4 && piResourceTypes.length === 4,
		`app/src/main/kotlin/app/pi/packages/PiPackageFilters.kt's RESOURCE_TYPES ` +
			`(${appResourceTypes?.length ?? "unreadable"}) and pi's ` +
			`modes/interactive/components/config-selector.ts's (${piResourceTypes.length}) are the same ` +
			`four keys. Re-point this reader if either list moved.`,
	);
	for (const type of appResourceTypes ?? []) {
		check(
			`pi still knows the resource type "${type}"`,
			piResourceTypes.includes(type),
			`PiPackageFilters.kt reads and writes the "${type}" key of settings.json's package filters. ` +
				`A rename upstream means the app's filter row edits a key pi ignores — re-read the pinned ` +
				`config-selector.ts and follow it.`,
		);
	}

	// ------------------------------------------------------------------ tool names
	const quickAdd = read("app/src/main/kotlin/app/pi/ui/settings/PiQuickAdd.kt");
	const baseline = kotlinListOf(quickAdd, "builtinToolDefaults");
	const optional = kotlinListOf(registryText, "optionalTools");
	const piTools = [...engine("core/tools/index.js").matchAll(/^\s+"([a-z]+)",$/gm)].map((m) => m[1]);
	const toolNames = new Set(piTools);
	check(
		"the tool-name lists are still readable on both sides (4 defaults, 3 optional, 8 built-ins)",
		baseline?.length === 4 && optional?.length === 3 && toolNames.size === 8,
		`PiQuickAdd.builtinToolDefaults (${baseline?.length ?? "unreadable"}), the registry's ` +
			`optionalTools (${optional?.length ?? "unreadable"}) and pi's ToolName union ` +
			`(${toolNames.size}) are what the 内建工具 row's chips write into settings.json. Re-point ` +
			`this reader if a list moved.`,
	);
	for (const tool of [...(baseline ?? []), ...(optional ?? [])]) {
		check(
			`pi still has a built-in tool named ${tool}`,
			toolNames.has(tool),
			`the 内建工具 row offers ${tool} as a chip and PiQuickAdd folds it into a *complete* ` +
				`whitelist (pi's defaultTools does not merge). A tool pi no longer has means the saved ` +
				`list enables nothing by that name — re-read the pinned core/tools/index.ts's ToolName.`,
		);
	}

	// ---------------------------------------------------------------- theme schema
	const themeFiles = read("app/src/main/kotlin/app/pi/ui/theme/PiThemeFiles.kt");
	const required = kotlinListOf(themeFiles, "REQUIRED_TOKENS");
	const fallbackKeys = kotlinMapOf(themeFiles, "OPTIONAL_FALLBACKS");
	const piSchema = JSON.parse(engine("modes/interactive/theme/theme-schema.json"));
	const piTokens = new Set(Object.keys(piSchema.properties.colors.properties));
	const appTokens = new Set([...(required ?? []), ...(fallbackKeys ?? [])]);
	check(
		"the theme token sets are still readable (51 required + 5 optional, 56 in the schema)",
		required?.length === 51 && fallbackKeys?.length === 5 && piTokens.size === 56,
		`ui/theme/PiThemeFiles.kt transcribes pi's theme schema: REQUIRED_TOKENS ` +
			`(${required?.length ?? "unreadable"}), OPTIONAL_FALLBACKS (${fallbackKeys?.length ?? "unreadable"}), ` +
			`against theme-schema.json's ${piTokens.size} colour tokens. This is the set that decides ` +
			`whether a user's own theme file is accepted with a warning or silently half-applied — ` +
			`re-point this reader if either declaration moved.`,
	);
	const missingTokens = [...piTokens].filter((token) => !appTokens.has(token));
	const extraTokens = [...appTokens].filter((token) => !piTokens.has(token));
	check(
		"PiThemeFiles names exactly pi's theme tokens",
		missingTokens.length === 0 && extraTokens.length === 0,
		`theme-schema.json: ${piTokens.size} tokens; PiThemeFiles: ${appTokens.size}. ` +
			(missingTokens.length ? `Not read by the app: ${missingTokens.join(", ")}. ` : "") +
			(extraTokens.length ? `Not in pi any more: ${extraTokens.join(", ")}. ` : "") +
			`A missing token means a user's theme file is reported as incomplete for a token pi accepts; ` +
			`re-read the pinned theme-schema.json and update REQUIRED_TOKENS/OPTIONAL_FALLBACKS.`,
	);

	// The five fallbacks: `theme.ts`'s `withThemeColorFallbacks` points at the sibling
	// token a theme may omit. The app re-derives each colour from the same target, so a
	// re-pointed fallback upstream silently changes what the app paints.
	const fallbackBody = /function withThemeColorFallbacks\(colors\)\s*\{([\s\S]*?)\n\}/.exec(engine("modes/interactive/theme/theme.js"))?.[1] ?? "";
	const piFallbacks = new Map(
		[...fallbackBody.matchAll(/(\w+):\s*colors\.\w+\s*\?\?\s*colors\.(\w+)/g)].map((m) => [m[1], m[2]]),
	);
	const appFallbackMap = new Map(
		[...(kotlinBlock(themeFiles, "OPTIONAL_FALLBACKS") ?? "").matchAll(/"(\w+)"\s+to\s+"(\w+)"/g)].map((m) => [m[1], m[2]]),
	);
	check(
		"the five optional-token fallbacks point at the same tokens as pi",
		piFallbacks.size === 5 &&
			appFallbackMap.size === 5 &&
			[...piFallbacks].every(([token, target]) => appFallbackMap.get(token) === target),
		`pi's withThemeColorFallbacks (pinned theme.ts) maps ` +
			`${JSON.stringify(Object.fromEntries(piFallbacks))}; the app maps ` +
			`${JSON.stringify(Object.fromEntries(appFallbackMap))}. A theme that omits one of these five ` +
			`tokens gets pi's fallback colour and the app's different one — re-read the pinned theme.ts ` +
			`and update PiThemeFiles.OPTIONAL_FALLBACKS.`,
	);
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
	["tables", "--- app transcription tables ---"],
	["tooltext", "--- tool-result text ---"],
	["session", "--- session surface ---"],
	["bundled", "--- bundled entry ---"],
	["catalog", "--- official model catalog ---"],
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
/**
 * The **official model catalog's data format**: `@earendil-works/pi-ai`'s bundled
 * `dist/providers/data/**`, which `packages/PiOfficialCatalog.kt` parses.
 *
 * ## Why this group exists
 *
 * This is the one pi file whose format change is *invisible* to the app. The reader
 * verifies each provider file's sha256 against a manifest, and a matching hash with
 * parseable JSON produces **no problem and no notice** — so when 0.99.0 renamed the
 * per-provider inner keys from `<id>` to `<type>:<id>` and started emitting image and
 * classifier entries next to chat ones, the app kept "succeeding": it ignored the key
 * names and read `element["id"]`, so 57 image models and 15 classifier models appeared
 * as chat models, were default-checked in the import sheet, and were written into
 * `enabledModels` (or, for non-built-in providers, into `models.json`, where pi's own
 * schema has no `type` and builds a chat model out of a classifier). That is the
 * §M11 shape — a capability silently wrong rather than loudly broken.
 *
 * ## What it asserts
 *
 *  1. the shipped `schemaVersion` is the one the app was written against
 *     (`PiOfficialCatalog.MODEL_DATA_SCHEMA_VERSION`, transcribed from pi's
 *     `scripts/model-data.ts`'s `MODEL_DATA_SCHEMA_VERSION`);
 *  2. every inner-key **prefix** the data actually uses is one of the model types the
 *     app's reader knows about — a fourth type must not arrive unnoticed;
 *  3. the data still contains a non-`chat` entry, so (2) is not vacuous: if pi ever
 *     emitted chat only, the filter this group protects would stop being exercised and
 *     that change should be seen, not assumed.
 *
 * The data lives in a **sibling** package (`@earendil-works/pi-ai`), reached through
 * the same `node_modules` the coding-agent package sits in — which is how both CI
 * (`build/pi-contract`) and a `--pi <installed tree>` invocation have it.
 */
function checkCatalogData(piDir) {
	const registryText = readFileSync(
		join(ROOT, "app/src/main/kotlin/app/pi/packages/PiOfficialCatalog.kt"),
		"utf8",
	);
	const declared = /MODEL_DATA_SCHEMA_VERSION\s*=\s*(\d+)/.exec(registryText);
	// The types the app's reader knows how to *filter* (`PiOfficialCatalog`'s `providerOf`
	// drops everything that is not chat; the rest are named so that a new one is a
	// failure here rather than a new row in the import sheet).
	const knownTypes = ["chat", "image", "classifier"];

	// Two shapes, because the tool is pointed at both: CI hands `--pi` the package
	// directory npm created (`<stage>/node_modules/@earendil-works/pi-coding-agent`,
	// where pi-ai is the **sibling** package), while a hand-run may hand it an unpacked
	// package root that carries its own `node_modules`. First existing wins.
	const dataCandidates = [
		join(piDir, "..", "pi-ai", "dist", "providers", "data"),
		join(piDir, "node_modules", "@earendil-works", "pi-ai", "dist", "providers", "data"),
	];
	const dataDir = dataCandidates.find((candidate) => existsSync(candidate)) ?? dataCandidates[0];
	check(
		"the pinned engine's pi-ai package still ships its model data directory",
		existsSync(dataDir),
		`looked for ${dataCandidates.join(" and ")}. PiOfficialCatalog.kt parses these files, and this ` +
			`group is the only thing that would notice their format moving. If pi-ai moved its data, ` +
			`re-point this check (packages/ai/scripts/generate-models.ts is what writes it) — do not ` +
			`delete it.`,
	);
	if (!existsSync(dataDir)) return;

	const manifestPath = join(dataDir, ".manifest.json");
	check(
		"the model data directory still has its manifest",
		existsSync(manifestPath),
		`${manifestPath} is missing. PiOfficialCatalog.kt reads \`schemaVersion\` from it; without the ` +
			`file the app cannot check it either, so this is the earliest place to say so.`,
	);
	if (!existsSync(manifestPath)) return;

	const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
	check(
		`the model data manifest declares schemaVersion ${declared ? declared[1] : "<unreadable>"}, the version the app reads`,
		declared !== null && Number(manifest.schemaVersion) === Number(declared[1]),
		`${manifestPath} says schemaVersion ${JSON.stringify(manifest.schemaVersion)} and ` +
			`PiOfficialCatalog.MODEL_DATA_SCHEMA_VERSION is ${declared ? declared[1] : "unreadable"}. ` +
			`0.99.0 moved this from 3 to 6 and renamed the inner keys to \`<type>:<id>\` in the same ` +
			`release; a mismatch means the reader's assumptions moved with it. Re-read ` +
			`packages/ai/scripts/model-data.ts and generate-models.ts, then update ` +
			`PiOfficialCatalog.kt (its \`type\` filter, and this constant).`,
	);

	const prefixes = new Map();
	let providerFiles = 0;
	let nonChat = 0;
	for (const name of readdirSync(dataDir)) {
		if (!name.endsWith(".json") || name.startsWith(".")) continue;
		providerFiles++;
		const groups = JSON.parse(readFileSync(join(dataDir, name), "utf8"));
		for (const models of Object.values(groups)) {
			if (models === null || typeof models !== "object") continue;
			for (const key of Object.keys(models)) {
				const prefix = key.includes(":") ? key.slice(0, key.indexOf(":")) : "<none>";
				prefixes.set(prefix, (prefixes.get(prefix) ?? 0) + 1);
				if (prefix !== "chat") nonChat++;
			}
		}
	}
	check(
		"the model data is still readable as api → inner key (>= 30 provider files)",
		providerFiles >= 30 && prefixes.size > 0,
		`read ${providerFiles} provider files under ${dataDir} and found ${prefixes.size} distinct ` +
			`inner-key prefixes. A collapse here means the layout changed, not that the data is empty — ` +
			`re-read generate-models.ts and re-point this reader.`,
	);
	const unknown = [...prefixes.keys()].filter((prefix) => !knownTypes.includes(prefix));
	check(
		`every inner-key prefix is a model type the app knows (${[...prefixes.keys()].sort().join(", ")})`,
		unknown.length === 0,
		`the data uses inner-key prefix(es) ${unknown.join(", ")}, which PiOfficialCatalog.kt does not ` +
			`know about. \`providerOf\` filters on \`type\`, and \`modelOf\` defaults a missing \`type\` to ` +
			`"chat"\` — so an unrecognised type is read as a chat model and lands in the import sheet's ` +
			`default selection. Add it to \`knownTypes\` here *and* decide what the app does with it ` +
			`(today: filter it out, like image and classifier).`,
	);
	check(
		"the data still contains a non-chat entry, so the type filter is exercised",
		nonChat > 0,
		`every inner key in ${providerFiles} provider files is now \`chat:\`. The type filter in ` +
			`PiOfficialCatalog.kt exists because 0.99.0 started emitting image and classifier entries ` +
			`beside chat ones; with none left, that filter's justification (and this group's ` +
			`non-vacuity) should be re-derived rather than assumed.`,
	);
	console.log(`   (${providerFiles} provider files, ${nonChat} non-chat entries, schemaVersion ${manifest.schemaVersion})`);
}

if (runs("surface")) {
	console.log("\n--- surface ---");
	checkSurface(piDir);
}
if (runs("theme")) {
	console.log("\n--- theme values ---");
	checkThemeValues(piDir);
}
if (runs("tables")) {
	console.log("\n--- app transcription tables ---");
	checkAppTables(piDir);
}
if (runs("tooltext")) {
	console.log("\n--- tool-result text ---");
	checkToolText(piDir);
}
if (runs("session")) {
	console.log("\n--- session surface ---");
	checkSessionSurface(piDir);
}
if (runs("catalog")) {
	console.log("\n--- official model catalog ---");
	checkCatalogData(piDir);
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
