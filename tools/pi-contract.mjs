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
 * with a `file:line` that describes **0.85.1**. Change `PI_VERSION` and the app
 * keeps compiling while the facts underneath it move — the failure mode this
 * repository has already paid for twice (`docs/known-gaps.md` §M11: a capability
 * silently downgraded; §M12: config keys silently deleted).
 *
 * CI today asserts that the payload *exists* and that its licences match. Nothing
 * asserts that what the app does with it still means what it meant. This does.
 *
 * ## What it checks
 *
 *  1. **Surface** — every RPC command string the app sends, every event type it
 *     parses, and every extension-UI method it answers must still appear in the
 *     pinned engine. Renames and removals fail here, cheaply.
 *  2. **Behaviour** — `models.json` semantics, asserted by running the pinned
 *     engine: a declaration that omits `input`/`contextWindow` really does replace
 *     the catalog entry with pi's defaults (that is why the app must not declare
 *     models pi knows), and `modelOverrides` really does merge (that is why it is
 *     the safe way to adjust one).
 *  3. **Extensions** — the extensions this app ships still load into the pinned
 *     engine. Their API surface is the one thing here with no static substitute.
 *
 * Every failure prints **the App code that depends on the fact**, because the
 * point is not "pi changed" but "this is what to re-read".
 *
 * ## Usage
 *
 *   node tools/pi-contract.mjs                 # install the pinned version, run all
 *   node tools/pi-contract.mjs --pi <dir>      # use an already-installed package
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
	const commands = appLiterals("rpc/src/main/kotlin/app/pi/rpc/Commands.kt", /put\("type", "([a-z_]+)"\)/g);
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

	const knownUiMethods = ["confirm", "input", "notify", "select", "editor", "setStatus", "setTitle", "setWidget"];
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

const explicit = process.argv.indexOf("--pi");
let piDir = explicit >= 0 ? resolve(process.argv[explicit + 1]) : null;
if (!piDir) {
	const stage = mkdtempSync(join(tmpdir(), "pi-contract-install-"));
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

console.log("\n--- surface ---");
checkSurface(piDir);
console.log("\n--- behaviour ---");
checkBehaviour(piDir);
await checkStartupOnlyReload(piDir);
console.log("\n--- extensions ---");
checkExtensions(piDir);

if (failures.length > 0) {
	console.error(`\npi contract: FAILED (${failures.length})`);
	for (const failure of failures) console.error(`  ${failure}`);
	process.exit(1);
}
console.log("\npi contract: OK — the app's assumptions about the pinned engine still hold");
