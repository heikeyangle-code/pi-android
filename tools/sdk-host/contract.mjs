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
  "createToolSearchExtension", "VERSION",
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
for (const m of ["newSession", "switchSession", "fork", "setRebindSession", "dispose"]) check(`AgentSessionRuntime.${m}`, has(rt, m));
for (const m of ["session", "services", "diagnostics"]) check(`AgentSessionRuntime.${m}`, has(rt, m));

const sm = sdk.SessionManager;
for (const m of ["create", "open", "continueRecent", "findById", "list", "listAll"]) check(`SessionManager.${m}`, typeof sm?.[m] === "function");
const pm = sdk.DefaultPackageManager?.prototype ?? {};
for (const m of ["listConfiguredPackages", "checkForAvailableUpdates", "installAndPersist", "removeAndPersist", "update", "setProgressCallback", "resolve"]) check(`PackageManager.${m}`, has(pm, m));

// 认证（auth.mjs）：login 的第 4 个参数 / logout / 凭据列表。
const mr = sdk.ModelRuntime?.prototype ?? {};
for (const m of ["getProviders", "getProvider", "login", "logout", "listCredentials", "hasConfiguredAuth", "isUsingOAuth", "getProviderAuthStatus", "refresh"]) check(`ModelRuntime.${m}`, has(mr, m));
check("SettingsManager.getOrCreateDeviceId（OAuth 登录要设备 ID）", has(sdk.SettingsManager?.prototype ?? {}, "getOrCreateDeviceId"));

// 宿主伸进 pi **非公开路径**的全部地方（resources.mjs / mcp.mjs）。这里红了 = pi 升级挪了位置：
// 先修宿主，别在 App 里绕。每一项都写明谁在用。
const internals = [
  ["modes/interactive/components/config-selector.js", ["ConfigSelectorComponent"], "resources.mjs"],
  ["extensions/index.js", ["builtInExtensions"], "resources.mjs（可选，取不到有后备名单）"],
  ["extensions/mcp/cli.js", ["runMcpCommand"], "mcp.mjs"],
  ["extensions/mcp/config.js", ["loadMcpConfig", "updateMcpServerConfig"], "mcp.mjs"],
  ["extensions/mcp/runtime.js", ["McpServerConnection", "createDefaultTransport", "McpOAuthCredentialStore", "McpServerLog", "McpSignInCancelledError", "signInMcpServer"], "mcp.mjs"],
];
for (const [rel, names, who] of internals) {
  const mod = await import(pathToFileURL(join(pi, "dist", rel)).href).catch((e) => ({ __error: String(e?.message ?? e) }));
  for (const name of names) check(`内部路径 dist/${rel} 导出 ${name}（${who}）`, name in mod, mod.__error);
}

console.log(`pi ${sdk.VERSION}`);
if (failures.length) {
  console.log(`CONTRACT BROKEN (${failures.length}):\n  ${failures.join("\n  ")}`);
  process.exit(1);
}
console.log("sdk-host contract: OK");
