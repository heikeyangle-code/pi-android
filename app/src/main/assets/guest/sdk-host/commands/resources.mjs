/**
 * 资源清单与开关：现在加载了哪些扩展 / 技能 / 提示模板 / 主题、来自哪、开没开，以及开关某一条。
 *
 * 以前是 `guest/pi-resources.mjs`（另起 node 进程，每次点开页面冷启动一遍引擎）。现在在宿主进程里
 * 直接做，同一套逻辑：
 *  - 清单来自 `DefaultPackageManager.resolve()`，**必须**传 `onMissing → "skip"`：不传的话遇到没装好的
 *    包会当场 npm install / git clone，没网时每次列清单都卡到超时；
 *  - 开关复用 `pi config` 的 `ConfigSelectorComponent` 的资源列表（它在 dist 里、无头可实例化），
 *    所以写进 settings.json 的 `+/-/!` 规则与终端里 `pi config` 逐字节相同，App 不转录任何一条规则。
 *    **这是整个宿主里唯一一处伸进 pi 非公开路径的地方**（`dist/modes/interactive/...`）；
 *    contract 测试会断言它存在，pi 升级若挪了它，CI 会第一时间红。
 */

import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { ERR, HostError, bad, str } from "../protocol.mjs";

const FALLBACK_BUILTINS = ["llama.cpp", "codemode", "tool-search", "mcp"];

async function importDist(controller, rel) {
  return import(pathToFileURL(join(controller.cfg.piDir, "dist", rel)).href);
}

async function builtinNames(controller) {
  try {
    const { builtInExtensions } = await importDist(controller, "extensions/index.js");
    return builtInExtensions.filter((e) => e.builtin === true).map((e) => e.name);
  } catch {
    return FALLBACK_BUILTINS; // 取不到就用 1.5.0 的名单；取到的始终以引擎为准
  }
}

export function register(reg, { controller }) {
  /** 每次都是全新的设置管理器：开关写盘后再 load 一次，读到的才是写完的状态（实测内存副本不跟着更新）。 */
  async function load() {
    const sdk = controller.sdk;
    if (!sdk || !controller.opened) throw new HostError(ERR.NOT_OPEN, "先 session.open");
    const { cwd, agentDir } = controller.cfg;
    const trusted = controller.isProjectTrusted();
    const { ConfigSelectorComponent } = await importDist(controller, "modes/interactive/components/config-selector.js");
    const names = await builtinNames(controller);

    const missing = new Set();
    const skipMissing = async (source) => {
      missing.add(String(source));
      return "skip";
    };
    const make = (settings) => new sdk.DefaultPackageManager({ cwd, agentDir, settingsManager: settings, builtinExtensions: names });
    const globalSettings = sdk.SettingsManager.create(cwd, agentDir, { projectTrusted: false });
    const projectSettings = trusted ? sdk.SettingsManager.create(cwd, agentDir, { projectTrusted: true }) : globalSettings;
    const resolveWith = (settings) => make(settings).resolve(skipMissing);
    const [globalResolved, projectResolved] = trusted
      ? await Promise.all([resolveWith(globalSettings), resolveWith(projectSettings)])
      : await resolveWith(globalSettings).then((r) => [r, r]);
    const noop = () => {};
    const selector = new ConfigSelectorComponent({ global: globalResolved, project: projectResolved }, projectSettings, cwd, agentDir, noop, noop, noop, 24, "global", trusted);
    return { trusted, globalSettings, projectSettings, globalResolved, projectResolved, missing, list: selector.getResourceList() };
  }

  const snapshot = (list, scope) => {
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

  const failures = (ctx) => [
    ...new Set([
      ...[...ctx.missing].map((source) => `${source}（没装好，在「扩展包」里重装）`),
      ...[...(ctx.globalResolved.installFailures ?? []), ...(ctx.projectResolved.installFailures ?? [])].map((f) => String(f?.message ?? f?.source ?? f)),
    ]),
  ];

  const full = (ctx) => ({
    trusted: ctx.trusted,
    global: snapshot(ctx.list, "global"),
    project: ctx.trusted ? snapshot(ctx.list, "project") : [],
    installFailures: failures(ctx),
  });

  reg.add("resources.list", async () => full(await load()));

  reg.add("resources.toggle", async (args) => {
    const scope = str(args, "scope");
    const type = str(args, "type");
    const path = str(args, "path");
    if (scope !== "global" && scope !== "project") throw bad(`未知层级：${scope}`);
    let ctx = await load();
    if (scope === "project" && !ctx.trusted) throw new HostError(ERR.REJECTED, "项目未被信任，不能改项目层的资源开关。");
    ctx.list.setWriteScope(scope);
    const item = ctx.list.groups.flatMap((g) => g.subgroups).flatMap((s) => s.items).find((i) => i.resourceType === type && i.path === path);
    if (!item) throw new HostError(ERR.NOT_FOUND, "没找到这条资源（可能刚被改过，刷新后重试）。");
    // 与 `pi config` 一致：全局视图只能改用户级的；项目视图什么都能改（三态循环）。
    if (scope === "global" && ctx.list.getItemScope(item) !== "user") {
      throw new HostError(ERR.REJECTED, "这条资源来自本项目，请在「本项目」视图里改。");
    }
    const enabled = ctx.list.toggleResource(item);
    await ctx.projectSettings.flush();
    if (ctx.projectSettings !== ctx.globalSettings) await ctx.globalSettings.flush();
    const result = { enabled: enabled ?? null, override: scope === "project" ? ctx.list.getProjectOverrideState(item) : null };
    ctx = await load();
    return { ...result, ...full(ctx) };
  });
}
