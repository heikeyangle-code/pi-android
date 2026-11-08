// pi-resources.mjs —— 让 pi **自己的包管理器**回答「现在加载了哪些资源、来自哪、开没开」，
// 并用 `pi config` 同一套逻辑开关某一条。
//
// 为什么不在 Kotlin 里扫目录：pi 的发现规则（包清单 `pi` 键、git 包的检出目录、`+/-/!` 过滤、
// 项目对全局的继承……）一直在变，App 自己推一遍就会漏（装好的扩展看不见、git 包整个不列）。
// 这里直接用 pi 导出的 `DefaultPackageManager.resolve()`，开关则复用 `pi config` 的
// `ConfigSelectorComponent`（它在 dist 里，导出，可无头实例化）—— 写进 settings.json 的格式因此
// 与终端里的 `pi config` 逐字节相同，App 不转录任何一条 `+/-` 规则。
//
// 用法（由 App 通过 guest 的 node 调用）：
//   node pi-resources.mjs <引擎目录> <cwd> <agentDir> <trusted:0|1> list
//   node pi-resources.mjs <引擎目录> <cwd> <agentDir> <trusted:0|1> toggle <global|project> <类型> <路径>
// 输出：最后一行是一个 JSON 对象（前面的噪音行忽略）。出错：{"error": "..."}，退出码 1。
import { pathToFileURL } from "node:url";
import { join } from "node:path";

const [engineDir, cwd, agentDir, trustedFlag, command, ...rest] = process.argv.slice(2);
const trusted = trustedFlag === "1";
const href = (rel) => pathToFileURL(join(engineDir, rel)).href;

function out(value, code = 0) {
	process.stdout.write(`\n${JSON.stringify(value)}\n`);
	process.exit(code);
}

try {
	const { SettingsManager, DefaultPackageManager } = await import(href("dist/index.js"));
	const { ConfigSelectorComponent } = await import(href("dist/modes/interactive/components/config-selector.js"));
	let builtinNames = ["llama.cpp", "codemode", "tool-search", "mcp"];
	try {
		const { builtInExtensions } = await import(href("dist/extensions/index.js"));
		builtinNames = builtInExtensions.filter((e) => e.builtin === true).map((e) => e.name);
	} catch {
		// 内置扩展名单取不到就用写死的这四个（pi 1.5.0）；取到的始终以引擎为准。
	}

	const globalSettings = SettingsManager.create(cwd, agentDir, { projectTrusted: false });
	const projectSettings = trusted ? SettingsManager.create(cwd, agentDir, { projectTrusted: true }) : globalSettings;
	const make = (settings) => new DefaultPackageManager({ cwd, agentDir, settingsManager: settings, builtinExtensions: builtinNames });
	const globalResolved = await make(globalSettings).resolve();
	const projectResolved = trusted ? await make(projectSettings).resolve() : globalResolved;

	const noop = () => {};
	const selector = new ConfigSelectorComponent(
		{ global: globalResolved, project: projectResolved },
		projectSettings,
		cwd,
		agentDir,
		noop,
		noop,
		noop,
		24,
		"global",
		trusted,
	);
	const list = selector.getResourceList();

	const snapshot = (scope) => {
		list.setWriteScope(scope);
		return list.groups.map((group) => ({
			key: group.key,
			label: group.label,
			origin: group.origin,
			scope: group.scope,
			source: group.source,
			subgroups: group.subgroups.map((sub) => ({
				type: sub.type,
				items: sub.items.map((item) => ({
					path: item.path,
					name: item.displayName,
					enabled: item.enabled,
					baseDir: item.metadata.baseDir ?? null,
					// 项目视图独有：对全局的三态与继承关系（`pi config` 里的 [+] / [-] / 灰色）。
					override: scope === "project" ? list.getProjectOverrideState(item) : null,
					inherited: scope === "project" ? list.isInheritedGlobalItem(item) : false,
					inheritedEnabled: scope === "project" ? list.getInheritedEnabled(item) : null,
				})),
			})),
		}));
	};

	const failures = [...new Set([...(globalResolved.installFailures ?? []), ...(projectResolved.installFailures ?? [])].map((f) => String(f?.message ?? f?.source ?? f)))];

	if (command === "list") {
		out({ trusted, global: snapshot("global"), project: trusted ? snapshot("project") : [], installFailures: failures });
	} else if (command === "toggle") {
		const [scope, type, path] = rest;
		if (scope !== "global" && scope !== "project") out({ error: `未知层级：${scope}` }, 1);
		if (scope === "project" && !trusted) out({ error: "项目未被信任，不能改项目层的资源开关。" }, 1);
		list.setWriteScope(scope);
		const item = list.groups
			.flatMap((g) => g.subgroups)
			.flatMap((s) => s.items)
			.find((i) => i.resourceType === type && i.path === path);
		if (!item) out({ error: "没找到这条资源（可能刚被改过，刷新后重试）。" }, 1);
		// 与 `pi config` 一致：全局视图只能改用户级的；项目视图什么都能改（三态循环）。
		if (scope === "global" && list.getItemScope(item) !== "user") {
			out({ error: "这条资源来自本项目，请在「本项目」视图里改。" }, 1);
		}
		const enabled = list.toggleResource(item);
		await projectSettings.flush();
		if (projectSettings !== globalSettings) await globalSettings.flush();
		out({ ok: true, enabled: enabled ?? null, override: scope === "project" ? list.getProjectOverrideState(item) : null });
	} else {
		out({ error: `未知命令：${command}` }, 1);
	}
} catch (error) {
	out({ error: String(error?.stack ?? error?.message ?? error).split("\n").slice(0, 4).join(" | ") }, 1);
}
