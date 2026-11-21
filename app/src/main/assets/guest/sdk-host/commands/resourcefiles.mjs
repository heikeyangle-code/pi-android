/**
 * 资源文件：技能、提示模板、主题、扩展、上下文文件（AGENTS.md / SYSTEM.md / APPEND_SYSTEM.md）的读、改、建、删。
 *
 * pi 的资源**就是文件**，位置是 SDK 定死的（`DefaultResourceLoader`）：
 *   - `<agentDir>/{skills,prompts,themes,extensions}` 与 `<cwd>/.pi/…` 同名四个目录；
 *   - `.agents/skills`（项目）与 `~/.agents/skills`（Agent Skills 约定）；
 *   - 上下文：`AGENTS.override.md|AGENTS.md|AGENTS.MD|CLAUDE.md|CLAUDE.MD`（agentDir 与 cwd 往上各级）；
 *   - 系统提示：`<agentDir>/SYSTEM.md`、`<cwd>/.pi/SYSTEM.md`，追加是 `APPEND_SYSTEM.md`。
 * 所以管理资源 = 管这些文件，不需要通用文件浏览器。这里按**同一份位置表**判定一个路径能不能动：
 * 白名单之外的一律拒绝（`auth.json`、`settings.json`、`models.json`、会话、`npm/` 都不在里面——
 * 它们有各自的专页，或者 pi 会整份重写）。读放宽一档：包里的资源（pi 加载了、但不归用户管）可以看，不能改。
 *
 * 写是「同目录临时文件 + rename」，并带 (size, mtime) 指纹：打开编辑器后文件被别处（终端、AI 的工具）
 * 改过，保存就拒绝，不把别人的改动盖掉。
 */

import { existsSync, mkdirSync, readFileSync, realpathSync, renameSync, rmSync, statSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { basename, dirname, join, relative, resolve, sep } from "node:path";
import { ERR, HostError, bad, optStr, str } from "../protocol.mjs";

const MAX_READ_BYTES = 1024 * 1024;
export const CONTEXT_NAMES = ["AGENTS.override.md", "AGENTS.md", "AGENTS.MD", "CLAUDE.md", "CLAUDE.MD"];
const SYSTEM_NAMES = ["SYSTEM.md", "APPEND_SYSTEM.md"];
const CONFIG_DIR = ".pi";

const real = (p) => {
  try {
    return realpathSync(p);
  } catch {
    return resolve(p);
  }
};

/** `p` 在 `dir` 里面（不含 dir 本身）。 */
const inside = (p, dir) => {
  const rel = relative(dir, p);
  return rel !== "" && !rel.startsWith("..") && !rel.startsWith(sep) && !/^[A-Za-z]:/.test(rel);
};

/**
 * 判一个路径属于哪一类资源、归谁管。返回 null = 不在白名单里。
 * `kind`：skill / prompt / theme / extension / context / system / append-system。
 * `scope`：user（agentDir 与 ~/.agents）或 project。
 */
export function classify(path, { agentDir, cwd, home = homedir() }) {
  const p = real(path);
  const agent = real(agentDir);
  const proj = real(cwd);
  const roots = [
    { dir: join(agent, "skills"), kind: "skill", scope: "user" },
    { dir: join(agent, "prompts"), kind: "prompt", scope: "user" },
    { dir: join(agent, "themes"), kind: "theme", scope: "user" },
    { dir: join(agent, "extensions"), kind: "extension", scope: "user" },
    { dir: join(home, ".agents", "skills"), kind: "skill", scope: "user" },
    { dir: join(proj, CONFIG_DIR, "skills"), kind: "skill", scope: "project" },
    { dir: join(proj, CONFIG_DIR, "prompts"), kind: "prompt", scope: "project" },
    { dir: join(proj, CONFIG_DIR, "themes"), kind: "theme", scope: "project" },
    { dir: join(proj, CONFIG_DIR, "extensions"), kind: "extension", scope: "project" },
    { dir: join(proj, ".agents", "skills"), kind: "skill", scope: "project" },
  ];
  for (const r of roots) {
    if (!inside(p, r.dir)) continue;
    const rel = relative(r.dir, p);
    // 目录型资源（技能 / 扩展）里什么文件都能改（SKILL.md、references/、index.ts）；单文件型只认对应后缀。
    if (r.kind === "prompt" && !(rel.endsWith(".md") && !rel.includes(sep))) return null;
    if (r.kind === "theme" && !(rel.endsWith(".json") && !rel.includes(sep))) return null;
    return { kind: r.kind, scope: r.scope, root: r.dir, rel };
  }
  const name = basename(p);
  const dir = dirname(p);
  if (SYSTEM_NAMES.includes(name)) {
    if (dir === agent) return { kind: name === "SYSTEM.md" ? "system" : "append-system", scope: "user", root: agent, rel: name };
    if (dir === join(proj, CONFIG_DIR)) return { kind: name === "SYSTEM.md" ? "system" : "append-system", scope: "project", root: dir, rel: name };
  }
  if (CONTEXT_NAMES.includes(name)) {
    // 全局上下文在 agentDir；项目的在 cwd 及其各级祖先——祖先那几个只有 pi 读到了才算（调用方传 extra）。
    if (dir === agent) return { kind: "context", scope: "user", root: agent, rel: name };
    if (dir === proj) return { kind: "context", scope: "project", root: proj, rel: name };
  }
  return null;
}

const stampOf = (st) => ({ size: st.size, mtimeMs: Math.trunc(st.mtimeMs) });

function writeAtomic(path, text) {
  mkdirSync(dirname(path), { recursive: true });
  const tmp = `${path}.${process.pid}.tmp`;
  writeFileSync(tmp, text, "utf8");
  renameSync(tmp, path);
}

// ------------------------------------------------------------------ 模板（按 SDK 的格式）

/** 技能名：SDK `validateName`——小写字母、数字、连字符，不以连字符开头结尾，不连续连字符，≤64。 */
export function skillNameError(name) {
  if (name.length > 64) return "技能名最长 64 个字符。";
  if (!/^[a-z0-9-]+$/.test(name)) return "技能名只能用小写字母、数字和连字符。";
  if (name.startsWith("-") || name.endsWith("-")) return "技能名不能以连字符开头或结尾。";
  if (name.includes("--")) return "技能名不能有连续的连字符。";
  return null;
}

/** 提示模板和扩展的文件名（也是 `/命令名`）：不含空格和路径分隔符。 */
export function plainNameError(name) {
  if (!/^[A-Za-z0-9][A-Za-z0-9_.-]*$/.test(name) || name.endsWith(".")) return "名字只能用字母、数字、下划线、点和连字符，且以字母或数字开头。";
  if (name.length > 64) return "名字最长 64 个字符。";
  return null;
}

const templates = {
  skill: (name) => `---\nname: ${name}\ndescription: 一句话说清它做什么、什么时候该用（模型靠这句话决定要不要读它，必填，≤1024 字）\n---\n\n# ${name}\n\n在这里写具体步骤。需要的脚本、参考文档放在这个目录里，用相对路径引用。\n`,
  prompt: () => `---\ndescription: 一句话说明这个模板做什么\nargument-hint: "[参数]"\n---\n\n在这里写提示正文。$1 是第一个参数，$@ 是全部参数。\n`,
  extension: (name) => `// ${name}：pi 扩展。默认导出一个函数，pi 启动（或重新加载）时用扩展 API 调它。\nexport default function (pi) {\n  pi.registerCommand("${name}", {\n    description: "示例命令",\n    handler: async (_args, ctx) => {\n      ctx.ui.notify("${name} 运行了", "info");\n    },\n  });\n}\n`,
  context: () => "",
  system: () => "",
  "append-system": () => "",
};

export function register(reg, { controller }) {
  const env = () => ({ agentDir: controller.cfg.agentDir, cwd: controller.cwd });

  /** pi 此刻读到的上下文文件（含祖先目录里的），只读放行，写不放行（它们归别的项目）。 */
  const loadedPaths = () => {
    const out = new Set();
    try {
      const rl = controller.requireSession().resourceLoader;
      for (const f of rl.getAgentsFiles().agentsFiles ?? []) out.add(real(f.path));
      for (const s of rl.getSkills().skills ?? []) out.add(real(s.filePath));
      for (const t of rl.getPrompts().prompts ?? []) out.add(real(t.filePath));
      for (const t of rl.getThemes().themes ?? []) if (t.path) out.add(real(t.path));
      for (const e of rl.getExtensions().extensions ?? []) out.add(real(e.resolvedPath ?? e.path));
    } catch {
      /* 会话没建好就只剩白名单 */
    }
    return out;
  };

  const needProject = (cls) => {
    if (cls?.scope === "project" && !controller.isProjectTrusted()) {
      throw new HostError(ERR.REJECTED, "项目还没被信任：pi 不读项目里的 .pi 资源，改它没有意义。先在「扩展与资源」里表态。");
    }
  };

  reg.add("resources.file.read", (args) => {
    const path = str(args, "path");
    const cls = classify(path, env());
    if (!cls && !loadedPaths().has(real(path))) throw new HostError(ERR.REJECTED, "这个文件不是 pi 的资源文件，不能在这里打开。");
    if (!existsSync(path)) throw new HostError(ERR.NOT_FOUND, "文件不存在。");
    const st = statSync(path);
    if (!st.isFile()) throw new HostError(ERR.REJECTED, "这不是文件。");
    if (st.size > MAX_READ_BYTES) throw new HostError(ERR.REJECTED, `文件太大（${st.size} 字节），超过 ${MAX_READ_BYTES} 字节的编辑上限。`);
    return {
      path,
      kind: cls?.kind ?? null,
      scope: cls?.scope ?? null,
      rel: cls?.rel ?? basename(path),
      text: readFileSync(path, "utf8"),
      ...stampOf(st),
      writable: cls !== null,
      reason: cls ? null : "它来自包或上层目录，不归你管：更新包时会被覆盖。想改就复制一份到自己的目录。",
    };
  });

  reg.add("resources.file.write", (args) => {
    const path = str(args, "path");
    const text = args?.text;
    if (typeof text !== "string") throw bad("缺少字符串参数 text");
    const cls = classify(path, env());
    if (!cls) throw new HostError(ERR.REJECTED, "这个文件不在可改的资源位置里。");
    needProject(cls);
    if (existsSync(path)) {
      const st = statSync(path);
      const size = args.expectSize;
      const mtime = args.expectMtimeMs;
      if (typeof size === "number" && typeof mtime === "number" && (st.size !== size || Math.trunc(st.mtimeMs) !== mtime)) {
        throw new HostError(ERR.BUSY, "文件在你编辑期间被改过（终端、AI 的工具或别的程序）。请重新打开，避免盖掉别人的改动。");
      }
    }
    writeAtomic(path, text);
    return { path, ...stampOf(statSync(path)) };
  });

  reg.add("resources.file.create", (args) => {
    const kind = str(args, "kind");
    const scope = str(args, "scope");
    if (scope !== "user" && scope !== "project") throw bad(`未知层级：${scope}`);
    const { agentDir, cwd } = env();
    const base = scope === "user" ? agentDir : join(cwd, CONFIG_DIR);
    const name = optStr(args, "name") ?? "";
    let path;
    let text;
    switch (kind) {
      case "skill": {
        const e = skillNameError(name);
        if (e) throw bad(e);
        path = join(base, "skills", name, "SKILL.md");
        text = templates.skill(name);
        break;
      }
      case "prompt": {
        const e = plainNameError(name);
        if (e) throw bad(e);
        path = join(base, "prompts", `${name}.md`);
        text = templates.prompt(name);
        break;
      }
      case "extension": {
        const e = plainNameError(name);
        if (e) throw bad(e);
        path = join(base, "extensions", `${name}.ts`);
        text = templates.extension(name);
        break;
      }
      case "context": {
        if (!CONTEXT_NAMES.includes(name) && !SYSTEM_NAMES.includes(name)) throw bad("上下文文件只能是 AGENTS.md、CLAUDE.md、SYSTEM.md 或 APPEND_SYSTEM.md。");
        // 项目的 AGENTS.md / CLAUDE.md 放在项目根（pi 从 cwd 往上找）；SYSTEM / APPEND_SYSTEM 在 `.pi` 里。
        path = SYSTEM_NAMES.includes(name) ? join(base, name) : join(scope === "user" ? agentDir : cwd, name);
        text = (SYSTEM_NAMES.includes(name) ? (name === "SYSTEM.md" ? templates.system : templates["append-system"]) : templates.context)(name);
        break;
      }
      default:
        throw bad(`不能新建这类资源：${kind}`);
    }
    needProject({ scope });
    if (existsSync(path)) throw new HostError(ERR.REJECTED, "已经有同名的了，请直接编辑它。");
    if (!classify(path, env())) throw new HostError(ERR.INTERNAL, "新建的位置不在资源白名单里（宿主 bug）。");
    writeAtomic(path, text);
    return { path, ...stampOf(statSync(path)) };
  });

  reg.add("resources.file.delete", (args) => {
    const path = str(args, "path");
    const cls = classify(path, env());
    if (!cls) throw new HostError(ERR.REJECTED, "这个文件不在可删的资源位置里。");
    needProject(cls);
    if (!existsSync(path)) throw new HostError(ERR.NOT_FOUND, "文件不存在。");
    // 技能是目录：删它的 SKILL.md 就是删整个技能目录（连带 references/、scripts/）——只在目录**直接**挂在 skills 根下时。
    if (cls.kind === "skill" && basename(path) === "SKILL.md" && dirname(dirname(real(path))) === cls.root) {
      rmSync(dirname(path), { recursive: true, force: true });
      return { path: dirname(path), removed: "directory" };
    }
    rmSync(path, { force: true });
    return { path, removed: "file" };
  });
}
