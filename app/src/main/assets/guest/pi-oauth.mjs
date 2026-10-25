/**
 * Subscription login for the App, driven by **pi's own** OAuth implementation.
 *
 * ## Why this exists at all
 *
 * pi supports a set of subscription providers (`anthropic`, `openai`,
 * `openai-codex`, `github-copilot`, `openrouter`, `kimi-coding`, `meta`, `xai`,
 * `radius` on 1.3.0) and each one has its own OAuth dance: PKCE + a loopback
 * callback server, RFC 8628 device codes, a GitHub token exchanged for a Copilot
 * token, a Meta identity token exchanged for a minted API key, a ChatGPT device id,
 * provider-specific headers and scopes. Re-implementing that in Kotlin would mean
 * copying nine client ids, nine endpoints and two extra token exchanges — and every
 * one of them is an internal detail of the pinned engine, not an API. A phone app
 * that drifts from it fails with `invalid_client` at the worst possible moment.
 *
 * So the app runs pi's flow **inside the guest, next to the pinned pi**, and only
 * does the two things a phone must do: draw the prompts (this protocol) and write
 * the resulting credential into `auth.json` (pi's own format).
 *
 * The flow list is not hard-coded either: it is `builtinProviders()` filtered by
 * `auth.oauth`, so a pi upgrade that adds a subscription provider makes it appear
 * here with no app change. That is the whole upgrade story.
 *
 * ## Protocol
 *
 * One JSON object per line, both directions. The app writes commands on stdin:
 *
 *   {"cmd":"list"}
 *   {"cmd":"login","provider":"anthropic"}
 *   {"cmd":"answer","promptId":3,"value":"http://localhost:53692/callback?code=..."}
 *   {"cmd":"cancel"}
 *
 * and reads events on stdout:
 *
 *   {"ev":"providers","providers":[{"id","name","oauthName","loginLabel","subscription"}]}
 *   {"ev":"notify", ...pi's AuthEvent verbatim (auth_url / device_code / progress / info)}
 *   {"ev":"prompt","promptId","type","message","placeholder?","options?"}
 *   {"ev":"credential","provider","credential":{...pi's OAuthCredential...}}
 *   {"ev":"cancelled"}
 *   {"ev":"error","message"}
 *
 * `notify` is passed through unchanged on purpose: pi's `AuthEvent` union already
 * says exactly what the UI needs (a URL to open, a code to show with its polling
 * interval, a progress line), and translating it here would be a second place to
 * keep in step with pi.
 *
 * ## The OpenAI device id
 *
 * `openai` (ChatGPT, 1.3.0) refuses to start without `options.getDeviceId()`. pi's
 * TUI passes `settings.deviceId` from its own settings file; this app has no reason
 * to write that file, so the caller supplies a UUID of its own through
 * `PI_OAUTH_DEVICE_ID`. It only has to be stable for this installation — it becomes
 * `urn:uuid:` in the authorization request.
 *
 * ## Failure shape
 *
 * Everything thrown by a flow becomes `{"ev":"error"}` with pi's own message, and
 * the process exits 0 so a failed login is never mistaken for a crashed bridge. A
 * missing pi-ai tree is reported the same way, before any prompt.
 */

import { createInterface } from "node:readline";
import { pathToFileURL } from "node:url";

const allJsPath = process.argv[2];
const deviceId = process.env.PI_OAUTH_DEVICE_ID ?? "";

/** @type {import("node:readline").Interface} */
const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });

function emit(event) {
	process.stdout.write(`${JSON.stringify(event)}\n`);
}

function fail(message) {
	emit({ ev: "error", message: String(message ?? "unknown error") });
}

let providersPromise;
function loadProviders() {
	providersPromise ??= import(pathToFileURL(allJsPath).href).then((module) => {
		if (typeof module.builtinProviders !== "function") {
			throw new Error(`${allJsPath} does not export builtinProviders()`);
		}
		return module.builtinProviders().filter((provider) => provider.auth?.oauth);
	});
	return providersPromise;
}

/**
 * One `interaction.prompt` waiting for the app's answer.
 *
 * pi's prompts carry their own `signal` when an out-of-band event can resolve the
 * step (`manual_code` raced against the loopback callback server). That signal must
 * reject the promise, otherwise cancelling — or the browser winning the race — leaves
 * the flow waiting forever on an input nobody will type.
 */
let nextPromptId = 1;
const pendingPrompts = new Map();

function ask(prompt) {
	const promptId = nextPromptId++;
	return new Promise((resolve, reject) => {
		const signal = prompt.signal;
		if (signal?.aborted) {
			reject(new Error("Login cancelled"));
			return;
		}
		const onAbort = () => {
			pendingPrompts.delete(promptId);
			reject(new Error("Login cancelled"));
		};
		signal?.addEventListener("abort", onAbort, { once: true });
		pendingPrompts.set(promptId, {
			resolve: (value) => {
				signal?.removeEventListener("abort", onAbort);
				resolve(value);
			},
			reject: (error) => {
				signal?.removeEventListener("abort", onAbort);
				reject(error);
			},
		});
		emit({
			ev: "prompt",
			promptId,
			type: prompt.type,
			message: prompt.message,
			placeholder: prompt.placeholder ?? null,
			options: prompt.type === "select" ? prompt.options : null,
		});
	});
}

/** @type {{ controller: AbortController } | null} */
let running = null;

async function listProviders() {
	try {
		const providers = await loadProviders();
		emit({
			ev: "providers",
			providers: providers.map((provider) => ({
				id: provider.id,
				name: provider.name,
				oauthName: provider.auth.oauth.name,
				loginLabel: provider.auth.oauth.loginLabel ?? null,
				subscription: provider.auth.oauth.isSubscription === true,
			})),
		});
	} catch (error) {
		fail(error instanceof Error ? error.message : error);
	}
}

async function login(providerId) {
	const controller = new AbortController();
	running = { controller };
	try {
		const providers = await loadProviders();
		const provider = providers.find((entry) => entry.id === providerId);
		if (!provider) throw new Error(`pi has no subscription login for "${providerId}"`);

		const interaction = {
			signal: controller.signal,
			notify: (event) => emit({ ev: "notify", ...event }),
			prompt: (prompt) => ask(prompt),
		};
		const credential = await provider.auth.oauth.login(interaction, {
			getDeviceId: () => deviceId,
			agentName: "pi-android",
		});
		if (!credential || credential.type !== "oauth") {
			throw new Error(`pi returned a ${credential?.type ?? "empty"} credential for ${providerId}`);
		}
		emit({ ev: "credential", provider: providerId, credential });
	} catch (error) {
		const message = error instanceof Error ? error.message : String(error);
		if (controller.signal.aborted) emit({ ev: "cancelled" });
		else fail(message);
	} finally {
		for (const pending of pendingPrompts.values()) pending.reject(new Error("Login cancelled"));
		pendingPrompts.clear();
		running = null;
		// A login is the whole reason this process exists: once it ends (credential,
		// cancel or error) there is nothing left to wait for, and the app's reader is
		// waiting for EOF. Yielding first lets the last line flush through the pipe.
		setTimeout(() => process.exit(0), 20);
	}
}

lines.on("line", (line) => {
	const text = line.trim();
	if (!text) return;
	let command;
	try {
		command = JSON.parse(text);
	} catch {
		fail("stdin was not JSON");
		return;
	}
	switch (command.cmd) {
		case "list":
			void listProviders();
			break;
		case "login":
			if (running) {
				fail("a login is already running");
				break;
			}
			void login(String(command.provider ?? ""));
			break;
		case "answer": {
			const pending = pendingPrompts.get(command.promptId);
			if (!pending) break;
			pendingPrompts.delete(command.promptId);
			pending.resolve(String(command.value ?? ""));
			break;
		}
		case "cancel":
			if (running) {
				running.controller.abort();
			} else {
				emit({ ev: "cancelled" });
			}
			break;
		default:
			fail(`unknown command "${command.cmd}"`);
	}
});

process.on("uncaughtException", (error) => {
	fail(error instanceof Error ? error.message : error);
});
process.on("unhandledRejection", (error) => {
	fail(error instanceof Error ? error.message : error);
});

emit({ ev: "ready" });
