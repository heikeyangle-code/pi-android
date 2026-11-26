#!/usr/bin/env node
/**
 * 宿主对 pi SDK 的依赖清单，逐项断言。**升级 pi 时先跑它**：红了，说明上游改了宿主用到的东西，
 * 输出会精确告诉你是哪一项，改宿主对应的一处即可（docs/sdk-host/upgrading.md）。
 *
 *   node tools/sdk-host/contract.mjs [pi 包目录]
 */
import { existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const pi = resolve(process.argv[2] ?? join(here, "../../build/pi-contract/node_modules/@earendil-works/pi-coding-agent"));
const bundle = join(pi, "dist/bundle/index.js");
const failures = [];
const check = (name, ok) => {
  if (!ok) failures.push(name);
};

check("dist/bundle/index.js 存在（宿主的快速入口，不是上游承诺的入口）", existsSync(bundle));
const sdk = await import(pathToFileURL(existsSync(bundle) ? bundle : join(pi, "dist/index.js")).href);

for (const name of [
  "createAgentSessionRuntime", "createAgentSessionServices", "createAgentSessionFromServices", "createEventBus",
  "ModelRuntime", "SettingsManager", "SessionManager", "ProjectTrustStore", "hasTrustRequiringProjectResources",
  "DefaultPackageManager", "DefaultResourceLoader", "createMcpExtension", "createCodemodeExtension",
  "createToolSearchExtension", "Theme", "initTheme", "getSelectListTheme", "getPackageDir", "VERSION",
]) check(`导出 ${name}`, name in sdk);

const proto = sdk.AgentSession?.prototype ?? {};
const has = (obj, name) => {
  const d = Object.getOwnPropertyDescriptor(obj, name);
  return !!d && (typeof d.value === "function" || typeof d.get === "function");
};
for (const m of [
  "prompt", "steer", "followUp", "abort", "clearQueue", "setModel", "cycleModel", "setThinkingLevel",
  "cycleThinkingLevel", "getAvailableThinkingLevels", "setSteeringMode", "setFollowUpMode", "compact",
  "setAutoCompactionEnabled", "setAutoRetryEnabled", "abortRetry", "executeBash", "abortBash", "recordBashResult",
  "exportToHtml", "getSessionStats", "getLastAssistantText", "getUserMessagesForForking", "setSessionName",
  "bindExtensions", "reload", "subscribe", "navigateTree", "waitForIdle", "getContextUsage", "getActiveToolNames",
  "getAllTools", "setActiveToolsByName", "dispose",
  // getters（`sessionManager` 与 `agent` 是实例字段，不在原型上，由 test.mjs 的运行时覆盖）
  "sessionId", "sessionFile", "sessionName", "messages", "model", "thinkingLevel", "isStreaming", "isCompacting",
  "steeringMode", "followUpMode", "autoCompactionEnabled", "pendingMessageCount", "extensionRunner",
  "promptTemplates", "resourceLoader", "modelRuntime",
]) check(`AgentSession.${m}`, has(proto, m));

const rt = sdk.AgentSessionRuntime?.prototype ?? {};
for (const m of ["newSession", "switchSession", "fork", "setRebindSession", "takeUnreportedDiagnostics", "dispose"]) check(`AgentSessionRuntime.${m}`, has(rt, m));
for (const m of ["session", "services", "diagnostics"]) check(`AgentSessionRuntime.${m}`, has(rt, m));

const sm = sdk.SessionManager;
for (const m of ["create", "open", "continueRecent", "findById", "list", "listAll", "forkFrom"]) check(`SessionManager.${m}`, typeof sm?.[m] === "function");
const pm = sdk.DefaultPackageManager?.prototype ?? {};
for (const m of ["listConfiguredPackages", "checkForAvailableUpdates", "installAndPersist", "removeAndPersist", "update", "setProgressCallback", "resolve", "parseSource"]) check(`PackageManager.${m}`, has(pm, m));

// 项目信任与已加载资源（controller.mjs / resources.mjs）：全部是公开出口。
const ts = sdk.ProjectTrustStore?.prototype ?? {};
for (const m of ["get", "getEntry", "set", "setMany"]) check(`ProjectTrustStore.${m}`, has(ts, m));
check("hasTrustRequiringProjectResources", typeof sdk.hasTrustRequiringProjectResources === "function");
const stm = sdk.SettingsManager?.prototype ?? {};
for (const m of ["setProjectTrusted", "isProjectTrusted", "getDefaultProjectTrust"]) check(`SettingsManager.${m}`, has(stm, m));
const drl = sdk.DefaultResourceLoader?.prototype ?? {};
for (const m of ["getExtensions", "getSkills", "getPrompts", "getThemes", "getAgentsFiles", "getSystemPromptSource", "getAppendSystemPromptSources"]) check(`ResourceLoader.${m}`, has(drl, m));

// 认证（auth.mjs）：login 的第 4 个参数 / logout / 凭据列表。
const mr = sdk.ModelRuntime?.prototype ?? {};
for (const m of ["getProviders", "getProvider", "login", "logout", "listCredentials", "hasConfiguredAuth", "isUsingOAuth", "getProviderAuthStatus", "refresh"]) check(`ModelRuntime.${m}`, has(mr, m));
check("SettingsManager.getOrCreateDeviceId（OAuth 登录要设备 ID）", has(sdk.SettingsManager?.prototype ?? {}, "getOrCreateDeviceId"));

// 宿主伸进 pi **非公开路径**的全部地方（controller.mjs / resources.mjs / mcp.mjs / catalog.mjs / model.mjs / sessionlist.mjs）。
// 这里红了 = pi 升级挪了位置：先修宿主，别在 App 里绕。每一项都写明谁在用。
const internals = [
  ["core/keybindings.js", ["KeybindingsManager"], "controller.mjs"],
  ["core/footer-data-provider.js", ["FooterDataProvider"], "controller.mjs"],
  ["core/http-dispatcher.js", ["applyHttpProxySettings", "configureHttpDispatcher"], "controller.mjs"],
  ["core/slash-commands.js", ["BUILTIN_SLASH_COMMANDS"], "catalog.mjs"],
  ["modes/interactive/components/custom-entry.js", ["CustomEntryComponent"], "catalog.mjs"],
  ["core/model-resolver.js", ["findExactModelReferenceMatch"], "model.mjs"],
  ["utils/paths.js", ["shortenHomePath", "pathsEqual", "resolvePath"], "sessionlist.mjs"],
  ["modes/interactive/components/session-selector-search.js", ["filterAndSortSessions", "hasSessionName", "parseSearchQuery"], "sessionlist.mjs"],
  ["modes/interactive/components/config-selector.js", ["ConfigSelectorComponent"], "resources.mjs"],
  ["extensions/index.js", ["builtInExtensions"], "resources.mjs（可选，取不到有后备名单）"],
  ["extensions/mcp/cli.js", ["runMcpCommand"], "mcp.mjs"],
  ["extensions/mcp/config.js", ["loadMcpConfig", "updateMcpServerConfig", "addMcpServerConfig", "removeMcpServerConfig"], "mcp.mjs"],
  ["core/mcp-servers.js", ["validateMcpServerConfig"], "mcp.mjs"],
  ["extensions/mcp/runtime.js", ["McpServerConnection", "createDefaultTransport", "McpOAuthCredentialStore", "McpServerLog", "McpSignInCancelledError", "signInMcpServer"], "mcp.mjs"],
];
for (const [rel, names, who] of internals) {
  const mod = await import(pathToFileURL(join(pi, "dist", rel)).href).catch((e) => ({ __error: String(e?.message ?? e) }));
  for (const name of names) check(`内部路径 dist/${rel} 导出 ${name}（${who}）`, name in mod, mod.__error);
}

// 兄弟包 @earendil-works/pi-ai 非公开路径（model.mjs 读取每模型真实支持的思考档位）。
const piAiCandidates = [
  join(pi, "..", "pi-ai", "dist", "models.js"),
  join(pi, "node_modules", "@earendil-works", "pi-ai", "dist", "models.js"),
];
const piAiModelsPath = piAiCandidates.find((p) => existsSync(p));
const piAiMod = piAiModelsPath
  ? await import(pathToFileURL(piAiModelsPath).href).catch((e) => ({ __error: String(e?.message ?? e) }))
  : { __error: "missing pi-ai/dist/models.js" };
check("兄弟包 @earendil-works/pi-ai dist/models.js 导出 getSupportedThinkingLevels（model.mjs）", typeof piAiMod.getSupportedThinkingLevels === "function");

console.log(`pi ${sdk.VERSION}`);
if (failures.length) {
  console.log(`CONTRACT BROKEN (${failures.length}):\n  ${failures.join("\n  ")}`);
  process.exit(1);
}
console.log("sdk-host contract: OK");
