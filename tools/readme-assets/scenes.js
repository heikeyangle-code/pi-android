/* Pi for Android — Reproducible Scene Builders (zh & en, light & dark)
 * Renders live MathJax 4.1.3 SVG and Mermaid 12.2.0 SVG inside headless Chromium.
 */

const SVG = {
  menu: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="4" y1="7" x2="20" y2="7"/><line x1="4" y1="12" x2="20" y2="12"/><line x1="4" y1="17" x2="15" y2="17"/></svg>`,
  chevDown: `<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>`,
  plus: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>`,
  kebab: `<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="5.5" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="12" cy="18.5" r="1.6"/></svg>`,
  back: `<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>`,
  arrowUp: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="19" x2="12" y2="5"/><polyline points="6 11 12 5 18 11"/></svg>`,
  check: `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>`,
  folder: `<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>`,
  file: `<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>`,
  shield: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l7 3v5c0 4.5-3 8.2-7 10-4-1.8-7-5.5-7-10V6l7-3z"/></svg>`,
  spark: `<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l2.2 6.8L21 12l-6.8 2.2L12 21l-2.2-6.8L3 12l6.8-2.2z"/></svg>`,
  copy: `<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>`,
  wifi: `<svg width="12" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M2 9.5a15 15 0 0 1 20 0"/><path d="M5.5 13a10 10 0 0 1 13 0"/><path d="M9 16.5a5 5 0 0 1 6 0"/><circle cx="12" cy="19.5" r="1.2" fill="currentColor"/></svg>`,
  battery: `<svg width="18" height="10" viewBox="0 0 26 14" fill="none"><rect x="1" y="2" width="21" height="10" rx="2.5" stroke="currentColor" stroke-width="1.7"/><rect x="3.5" y="4.5" width="14" height="5" rx="1" fill="currentColor"/><path d="M23.5 5v4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>`,
};

function ctxRingSvg(pct) {
  const r = 5.5;
  const c = 2 * Math.PI * r;
  const offset = c * (1 - pct / 100);
  return `
    <span class="ctx-ring">
      <svg width="14" height="14" viewBox="0 0 16 16">
        <circle cx="8" cy="8" r="${r}" fill="none" stroke="var(--border-strong)" stroke-width="1.8"/>
        <circle cx="8" cy="8" r="${r}" fill="none" stroke="var(--muted)" stroke-width="1.8"
          stroke-dasharray="${c.toFixed(1)}" stroke-dashoffset="${offset.toFixed(1)}"
          stroke-linecap="round" transform="rotate(-90 8 8)"/>
      </svg>
      <span>${pct}%</span>
    </span>`;
}

function statusBarHtml(time = "09:41") {
  return `
    <div class="status-bar">
      <span>${time}</span>
      <span class="status-cam"></span>
      <span class="status-icons">${SVG.wifi}${SVG.battery}</span>
    </div>`;
}

function floatingTopBar(title) {
  return `
    <div class="floating-top-wrap">
      <div class="capsule capsule-session">
        ${SVG.menu}
        <span class="capsule-session-title">${title}</span>
        ${SVG.chevDown}
      </div>
      <div class="capsule capsule-actions">
        <span class="cap-icon-btn">${SVG.plus}</span>
        <span class="cap-divider"></span>
        <span class="cap-icon-btn">${SVG.kebab}</span>
      </div>
    </div>`;
}

function subTopBar(title, rightPill = "") {
  return `
    <div class="sub-top-bar">
      <div class="sub-top-left">${SVG.back}<span>${title}</span></div>
      ${rightPill ? `<span class="sub-top-pill">${rightPill}</span>` : ""}
    </div>`;
}

function composerHtml({ placeholder, model = "Sonnet 4", think = "High", pct = 18, extChip = "", queueHtml = "", widgetHtml = "" }) {
  return `
    <div class="composer-zone">
      ${widgetHtml}
      ${queueHtml}
      <div class="composer-card">
        <div class="composer-input"><span class="composer-placeholder">${placeholder}</span></div>
        <div class="composer-bar">
          <div class="composer-left">
            <span class="composer-plus">${SVG.plus}</span>
            <span class="model-think-capsule">
              <span>${model}</span>
              ${SVG.chevDown}
              <span class="mt-sep"></span>
              <span class="think-dot"></span>
              <span>${think}</span>
            </span>
            ${extChip ? `<span class="ext-chip">${SVG.spark}<span>${extChip}</span></span>` : ""}
          </div>
          <div class="composer-right">
            ${ctxRingSvg(pct)}
            <span class="send-btn send-btn-idle">${SVG.arrowUp}</span>
          </div>
        </div>
      </div>
    </div>`;
}

function phoneShell({ theme, large = false, bodyHtml, extraClass = "", time = "09:41" }) {
  return `
    <div class="phone ${large ? "phone-lg" : ""} ${extraClass}">
      <div class="phone-screen ${theme}">
        ${statusBarHtml(time)}
        ${bodyHtml}
        <div class="gesture-bar"><span class="gesture-pill"></span></div>
      </div>
    </div>`;
}

function phoneSlot({ caption, dotColor, phoneHtml }) {
  return `
    <div class="phone-slot">
      ${phoneHtml}
      <div class="phone-caption">
        <span class="caption-dot" style="background:${dotColor}"></span>
        <span>${caption}</span>
      </div>
    </div>`;
}

/* ============================================================================
 * Localized Copy Dictionary (Strictly Factual, Calm, Zero Hype)
 * ============================================================================ */

const COPY = {
  zh: {
    brandSub: "基于官方 Pi SDK 的第三方 Android 客户端",
    heroTitle: "手机上的 AI Agent：\n聊天好看，动手也行",
    heroDesc: "离线排版 Markdown、公式与图表，用你自己的模型把对话留在手机本机；需要写代码时，在同一段对话里直接读写文件与运行命令。",
    heroPills: ["自带模型与 OAuth", "离线公式与图表", "对话保存在本机"],
    placeholder: "输入消息，或 / 唤起命令...",
    heroChatTitle: "归并排序与快排分析",
    heroUser: "对比一下归并排序和快速排序的时间复杂度，给一个表格和递推公式。",
    heroThought: "已思考 1.4s",
    heroLead: "两者平均时间复杂度均为 <span class=\"math-inline\" data-tex=\"O(n \\log n)\"></span>，主要差异在于最坏情况与空间开销：",
    heroTh: ["算法", "平均", "最坏", "稳定性"],
    heroRows: [
      ["归并排序", "O(n log n)", "O(n log n)", "稳定"],
      ["快速排序", "O(n log n)", "O(n²)", "不稳定"],
      ["堆排序", "O(n log n)", "O(n log n)", "不稳定"],
    ],
    heroFormulaIntro: "归并排序的分治递推式可由主定理求解：",
    heroNote: "提示：数据基本有序时，快排建议采用三数取中或随机枢轴以避免退化。",
    heroSecTitle: "缓存架构草图",
    heroSecUser: "画一下请求经过本地缓存和模型网关的流转图，再给一个 Kotlin 缓存封装。",
    mermaidHdr: "Mermaid 流程图",
    f1Cap1: "Warm Paper · Markdown、表格与 MathJax 公式",
    f1Cap2: "Gallery Paper · 离线 Mermaid 图表与代码高亮",
    f1Cap3: "Everforest Light · 全屏划选与按段复制",
    f1MathTitle: "交叉熵损失推导",
    f1MathUser: "写出多分类交叉熵损失和 Softmax 梯度的简洁形式。",
    f1MathP1: "对于样本 <span class=\"math-inline\" data-tex=\"(x_i, y_i)\"></span>，模型输出 logits 经 Softmax 归一化为概率分布 <span class=\"math-inline\" data-tex=\"\\hat{y}_i\"></span>：",
    f1MathP2: "对其求偏导可直接消去指数项，得到简洁的梯度表达：",
    f1MathTh: ["函数", "表达式", "数值特性"],
    f1CopyTitle: "划选复制工作台",
    f1CopyPill: "已选 2 段",
    f1CopyMode1: "自由划选",
    f1CopyMode2: "按段点选",
    f1CopyB1: "在 Android 端进行长回答流式渲染时，短文本与缓存命中的定稿内容走同步帧渲染，长回答在后台线程合并解析，避免高度跳动。",
    f1CopyB2: "离线公式与图表仅在首次出现时按需初始化渲染组件，不影响日常启动速度。",
    f1CopyB3: "已复制所选段落与代码片段，保留原始 Markdown 结构。",
    f1CopyBtn: "复制已选内容",
    f2Cap1: "Warm Paper · OAuth 登录、API Key 与自建端点",
    f2Cap2: "Gallery Paper · 思考深度与上下文用量控制",
    f2Cap3: "Warm Ink · /tree 对话分支树与导出",
    f2ModelBar: "模型与供应商",
    f2ModelPill: "本机保存",
    f2OAuthSec: "已连接账号（OAuth）",
    f2KeySec: "API Key 与自定义端点",
    f2ThinkBar: "思考深度与上下文",
    f2ThinkLabel: "思考深度档位",
    f2ThinkHint: "根据问题复杂度按需切换，低档位更快省额度。",
    f2CtxLabel: "当前会话上下文",
    f2CtxMeta: "已用 48.2k / 200k tokens（24%）",
    f2CompactBtn: "立即压缩上下文（/compact）",
    f2TreeBar: "会话分支树（/tree）",
    f2TreePill: "导出 HTML",
    f2TreeNodes: [
      { title: "初始方案：用 SQLite 存储缓存索引", sub: "主干 · 10:12", active: false },
      { title: "分支 A：改用内存 LRU + 磁盘 WAL", sub: "已验证 · 10:18", active: false },
      { title: "分支 B：对比冷启动耗时与内存峰值", sub: "当前所在分支 · 10:24", active: true },
      { title: "分支 B-1：补写边界情况单元测试", sub: "待合并 · 10:29", active: false },
    ],
    f3Cap1: "Warm Paper · 工具调用卡与行内字级 Diff",
    f3Cap2: "Gallery Paper · 真实项目目录浏览",
    f3Cap3: "Midnight · 60fps 原生多标签 PTY 终端",
    f3ChatTitle: "修复会话索引加载",
    f3User: "把 session-index 的读取改成带上限的并发控制，并跑一下单测。",
    f3Done: "已完成",
    f3Pass: "42 项通过",
    f3WsTitle: "工作区文件",
    f3WsPill: "main · 2 修改",
    f3TermBar: "本地终端",
    f3TermPill: "Ubuntu 24.04",
    f4Cap1: "Gallery Paper · 官方扩展、技能与 ~/.pi 目录",
    f4Cap2: "Dawn · MCP 工具服务器与 Code Mode 编排",
    f4ExtTitle: "扩展与资源",
    f4ExtPill: "热重载",
    f4ExtSec1: "已启用扩展（~/.pi/agent/extensions）",
    f4ExtSec2: "项目技能与提示模板（.pi/）",
    f4McpTitle: "MCP 服务器与 Code Mode",
    f4McpPill: "已连接 2 个",
    f4McpSec: "外部工具服务器",
    f4CodeModeSec: "Code Mode 多工具编排",
    f5Cap1: "Warm Paper · 三组设备能力默认全关（0 Token）",
    f5Cap2: "Warm Paper · 不可逆操作原生三选一确认",
    f5DevTitle: "设备能力",
    f5DevPill: "默认全关",
    f5ZeroNotice: "未开启的能力组不会注入模型上下文，占用 0 Token。",
    f5Groups: [
      { title: "系统交互", sub: "应用启动、剪贴板、通知收发与回复、分享、打开链接、定位、手电筒与传感器" },
      { title: "屏幕自动化", sub: "读屏、锚点点击、输入、滑动、按键与编号标注截图（需手动开启无障碍）" },
      { title: "设备 Shell", sub: "本机命令行（接入 Shizuku 可提权至 ADB 级）" },
    ],
    f5DlgTag: "高风险操作确认",
    f5DlgTitle: "允许发送通知回复？",
    f5DlgDesc: "模型请求向会话「研发群」发送文本回复。该操作发出后不可撤回，请确认参数后再放行。",
    f5DlgParam: "目标：研发群\n内容：测试报告已整理好，晚点发群里。",
    f5BtnOnce: "仅本次允许",
    f5BtnSess: "本次会话允许",
    f5BtnDeny: "拒绝",
    f6Cap1: "Warm Ink · 虚拟 TUI 面板、OSC 8 链接与快捷芯片",
    f6Cap2: "Warm Paper · 悬浮扩展组件与输入栏快捷入口",
    f6TuiTitle: "扩展界面 · deploy-watch",
    f6TuiPill: "虚拟 TUI",
    f6WidgetTitle: "构建监控 · ci-watcher",
    f6WidgetSub: "6 项任务运行中 · 已完成 4/6",
    f6ChatTitle: "发布前检查",
    f6User: "盯一下这次提交的 CI 进度，全部绿了提醒我。",
    f6Reply: "已接入 `ci-watcher` 扩展，当前构建状态会实时显示在输入框上方组件里，你可以随时点击快捷芯片查看详情。",
  },
  en: {
    brandSub: "Third-party Android client built on the official Pi SDK",
    heroTitle: "AI Agent on Android:\nClean Chat, Ready to Build",
    heroDesc: "Offline Markdown, math, and diagrams with your own models and local history. When a task calls for code, edit files and run commands in the same thread.",
    heroPills: ["Bring Your Models", "Offline Math & Diagrams", "Local Chat History"],
    placeholder: "Message or type / for commands...",
    heroChatTitle: "Sorting Complexity Notes",
    heroUser: "Compare Merge Sort and Quick Sort with a summary table and recurrence relation.",
    heroThought: "Thought for 1.4s",
    heroLead: "Both average <span class=\"math-inline\" data-tex=\"O(n \\log n)\"></span> time, but differ in worst-case bounds and stability:",
    heroTh: ["Algorithm", "Average", "Worst", "Stable"],
    heroRows: [
      ["Merge Sort", "O(n log n)", "O(n log n)", "Yes"],
      ["Quick Sort", "O(n log n)", "O(n²)", "No"],
      ["Heap Sort", "O(n log n)", "O(n log n)", "No"],
    ],
    heroFormulaIntro: "Merge Sort follows the standard divide-and-conquer recurrence:",
    heroNote: "Note: For nearly sorted inputs, use median-of-three or a random pivot to avoid quadratic degradation.",
    heroSecTitle: "Cache Flow Sketch",
    heroSecUser: "Sketch the request flow through local cache to the gateway, plus a small Kotlin helper.",
    mermaidHdr: "Mermaid Diagram",
    f1Cap1: "Warm Paper · Markdown, Tables & MathJax",
    f1Cap2: "Gallery Paper · Offline Mermaid & Code Highlight",
    f1Cap3: "Everforest Light · Free-Select & Block Copy",
    f1MathTitle: "Cross-Entropy Gradient",
    f1MathUser: "Write the multiclass cross-entropy loss and its Softmax gradient.",
    f1MathP1: "For sample <span class=\"math-inline\" data-tex=\"(x_i, y_i)\"></span>, Softmax maps logits to class probabilities <span class=\"math-inline\" data-tex=\"\\hat{y}_i\"></span>:",
    f1MathP2: "Differentiating with respect to logits cancels the exponential terms cleanly:",
    f1MathTh: ["Function", "Formula", "Property"],
    f1CopyTitle: "Copy Studio",
    f1CopyPill: "2 blocks",
    f1CopyMode1: "Free Select",
    f1CopyMode2: "Pick Blocks",
    f1CopyB1: "Short replies and cached finalized messages render synchronously on frame zero, while long streaming replies parse off the UI thread without height jumps.",
    f1CopyB2: "Offline math and diagram engines initialize lazily on first use, keeping cold start fast.",
    f1CopyB3: "Selected paragraphs and code snippets copy cleanly with original Markdown preserved.",
    f1CopyBtn: "Copy Selected",
    f2Cap1: "Warm Paper · OAuth, API Keys & Custom Endpoints",
    f2Cap2: "Gallery Paper · Thinking Depth & Context Ring",
    f2Cap3: "Warm Ink · /tree Session Branching & Export",
    f2ModelBar: "Models & Providers",
    f2ModelPill: "Stored locally",
    f2OAuthSec: "Connected Accounts (OAuth)",
    f2KeySec: "API Keys & Custom Endpoints",
    f2ThinkBar: "Thinking & Context",
    f2ThinkLabel: "Thinking Level",
    f2ThinkHint: "Adjust depth per turn; lower levels answer faster and use fewer tokens.",
    f2CtxLabel: "Session Context Window",
    f2CtxMeta: "48.2k / 200k tokens used (24%)",
    f2CompactBtn: "Compact Context Now (/compact)",
    f2TreeBar: "Session Tree (/tree)",
    f2TreePill: "Export HTML",
    f2TreeNodes: [
      { title: "Initial draft: SQLite cache index", sub: "Root · 10:12", active: false },
      { title: "Branch A: Memory LRU + disk WAL", sub: "Tested · 10:18", active: false },
      { title: "Branch B: Compare cold-start & peak heap", sub: "Active branch · 10:24", active: true },
      { title: "Branch B-1: Add edge-case unit tests", sub: "Draft · 10:29", active: false },
    ],
    f3Cap1: "Warm Paper · Tool Cards & Word-Level Diff",
    f3Cap2: "Gallery Paper · Real Workspace Explorer",
    f3Cap3: "Midnight · 60fps Multi-Tab PTY Terminal",
    f3ChatTitle: "Cap Session Index Loader",
    f3User: "Add bounded concurrency to session-index reads and run the unit tests.",
    f3Done: "Done",
    f3Pass: "42 passed",
    f3WsTitle: "Workspace Files",
    f3WsPill: "main · 2 modified",
    f3TermBar: "Local Terminal",
    f3TermPill: "Ubuntu 24.04",
    f4Cap1: "Gallery Paper · Extensions, Skills & ~/.pi Parity",
    f4Cap2: "Dawn · MCP Servers & Code Mode",
    f4ExtTitle: "Extensions & Resources",
    f4ExtPill: "Hot reload",
    f4ExtSec1: "Enabled Extensions (~/.pi/agent/extensions)",
    f4ExtSec2: "Project Skills & Templates (.pi/)",
    f4McpTitle: "MCP & Code Mode",
    f4McpPill: "2 connected",
    f4McpSec: "External Tool Servers",
    f4CodeModeSec: "Code Mode Orchestration",
    f5Cap1: "Warm Paper · All 3 Groups Off by Default (0 Tokens)",
    f5Cap2: "Warm Paper · Native 3-Choice Confirmation",
    f5DevTitle: "Device Capabilities",
    f5DevPill: "All off",
    f5ZeroNotice: "Disabled groups are never injected into model prompts (0 context tokens).",
    f5Groups: [
      { title: "System Interaction", sub: "App launch, clipboard, notifications, share, URLs, location, torch & sensors" },
      { title: "Screen Automation", sub: "Read screen, tap, type, swipe, keys & annotated capture (requires Accessibility)" },
      { title: "Device Shell", sub: "Local shell commands (optional ADB-level elevation via Shizuku)" },
    ],
    f5DlgTag: "Confirmation Required",
    f5DlgTitle: "Send notification reply?",
    f5DlgDesc: "The agent requested to reply to 'Eng Team'. This action cannot be undone. Review the parameters before allowing.",
    f5DlgParam: "Target: Eng Team\nText: Test report is ready, sharing shortly.",
    f5BtnOnce: "Allow once",
    f5BtnSess: "Allow for session",
    f5BtnDeny: "Deny",
    f6Cap1: "Warm Ink · Virtual TUI Grid, OSC 8 Links & Chips",
    f6Cap2: "Warm Paper · Floating Widget & Composer Chip",
    f6TuiTitle: "Extension UI · deploy-watch",
    f6TuiPill: "Virtual TUI",
    f6WidgetTitle: "Build Watch · ci-watcher",
    f6WidgetSub: "6 jobs running · 4/6 passed",
    f6ChatTitle: "Pre-release Check",
    f6User: "Watch CI for this commit and notify me once all checks pass.",
    f6Reply: "Attached `ci-watcher`. Live progress stays pinned in the widget above the composer; tap the shortcut chip anytime to open details.",
  },
};

/* ============================================================================
 * Individual Phone Builders
 * ============================================================================ */

function buildHeroPrimaryPhone(c, theme) {
  const rows = c.heroRows
    .map((r) => `<tr><td><strong>${r[0]}</strong></td><td style="font-family:var(--font-mono);font-size:10px">${r[1]}</td><td style="font-family:var(--font-mono);font-size:10px">${r[2]}</td><td>${r[3]}</td></tr>`)
    .join("");
  const bodyHtml = `
    ${floatingTopBar(c.heroChatTitle)}
    <div class="chat-body">
      <div class="user-bubble-row"><div class="user-bubble">${c.heroUser}</div></div>
      <div class="asst-block">
        <div class="thinking-strip"><span class="thinking-dot"></span><span>${c.heroThought}</span>${SVG.chevDown}</div>
        <div class="asst-p">${c.heroLead}</div>
        <div class="md-table-wrap">
          <table class="md-table">
            <thead><tr>${c.heroTh.map((h) => `<th>${h}</th>`).join("")}</tr></thead>
            <tbody>${rows}</tbody>
          </table>
        </div>
        <div class="asst-p">${c.heroFormulaIntro}</div>
        <div class="math-block" data-tex="T(n) = 2T\\!\\left(\\frac{n}{2}\\right) + \\Theta(n) \\implies T(n) = \\Theta(n \\log n)"></div>
        <div class="callout">${c.heroNote}</div>
      </div>
    </div>
    ${composerHtml({ placeholder: c.placeholder, model: "Claude Sonnet 4", think: "High", pct: 18 })}`;
  return phoneShell({ theme, bodyHtml });
}

function buildHeroSecondaryPhone(c, theme, lang) {
  const mermaidDef = lang === "zh"
    ? `flowchart TD\n  A[请求进入] --> B{本地缓存}\n  B -->|命中| C[直接返回]\n  B -->|未命中| D[模型网关]`
    : `flowchart TD\n  A[Request] --> B{Local Cache}\n  B -->|Hit| C[Fast Reply]\n  B -->|Miss| D[Model API]`;
  const bodyHtml = `
    ${floatingTopBar(c.heroSecTitle)}
    <div class="chat-body">
      <div class="user-bubble-row"><div class="user-bubble">${c.heroSecUser}</div></div>
      <div class="asst-block">
        <div class="mermaid-card">
          <div class="mermaid-hdr"><span>${c.mermaidHdr}</span><span>Mermaid 12</span></div>
          <div class="mermaid-stage" data-mermaid="${mermaidDef.replace(/"/g, "&quot;")}" data-theme="${theme === "theme-warm-ink" ? "dark" : "light"}"></div>
        </div>
        <div class="code-card">
          <div class="code-hdr"><span>kotlin</span><span style="display:inline-flex;align-items:center;gap:4px">${SVG.copy} Copy</span></div>
          <pre class="code-pre"><span class="kw">class</span> <span class="ty">MemoCache</span>&lt;<span class="ty">K</span>, <span class="ty">V</span>&gt;(<span class="kw">private val</span> max: <span class="ty">Int</span>) {
  <span class="kw">private val</span> map = <span class="fn">LinkedHashMap</span>&lt;<span class="ty">K</span>, <span class="ty">V</span>&gt;()
  <span class="kw">fun</span> <span class="fn">getOrPut</span>(k: <span class="ty">K</span>, load: () -&gt; <span class="ty">V</span>): <span class="ty">V</span> =
    map.<span class="fn">getOrPut</span>(k) { <span class="fn">load</span>().<span class="fn">also</span> { <span class="fn">trim</span>() } }
}</pre>
        </div>
      </div>
    </div>
    ${composerHtml({ placeholder: c.placeholder, model: "GPT-5.2", think: "Med", pct: 26 })}`;
  return phoneShell({ theme, bodyHtml });
}

/* --- F1: Reading, Math, Mermaid, Copy Studio --- */
function buildF1MathPhone(c) {
  const bodyHtml = `
    ${floatingTopBar(c.f1MathTitle)}
    <div class="chat-body">
      <div class="user-bubble-row"><div class="user-bubble">${c.f1MathUser}</div></div>
      <div class="asst-block">
        <div class="asst-p">${c.f1MathP1}</div>
        <div class="math-block" data-tex="\\mathcal{L}(\\theta) = -\\frac{1}{N} \\sum_{i=1}^{N} \\sum_{c=1}^{C} y_{i,c} \\log \\hat{y}_{i,c}"></div>
        <div class="asst-p">${c.f1MathP2}</div>
        <div class="math-block" data-tex="\\frac{\\partial \\mathcal{L}}{\\partial z_k} = \\hat{y}_k - y_k"></div>
        <div class="md-table-wrap">
          <table class="md-table">
            <thead><tr><th>${c.f1MathTh[0]}</th><th>${c.f1MathTh[1]}</th><th>${c.f1MathTh[2]}</th></tr></thead>
            <tbody>
              <tr><td>Softmax</td><td style="font-family:var(--font-mono);font-size:9.8px">exp(z)/Σexp</td><td>[0, 1]</td></tr>
              <tr><td>LogSumExp</td><td style="font-family:var(--font-mono);font-size:9.8px">z - m - log Σ</td><td>Stable</td></tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
    ${composerHtml({ placeholder: c.placeholder, model: "Sonnet 4", think: "High", pct: 15 })}`;
  return phoneShell({ theme: "theme-warm-paper", bodyHtml });
}

function buildF1MermaidPhone(c, lang) {
  const mermaidDef = lang === "zh"
    ? `flowchart TD\n  A[Markdown 流] --> B{命中缓存?}\n  B -->|是| C[帧 0 同步排版]\n  B -->|否| D[后台解析管线]\n  D --> E[矢量公式 / 图表]`
    : `flowchart TD\n  A[Markdown Stream] --> B{Cached?}\n  B -->|Yes| C[Frame-0 Layout]\n  B -->|No| D[Async AST Worker]\n  D --> E[Math & Diagram SVG]`;
  const bodyHtml = `
    ${floatingTopBar(lang === "zh" ? "渲染管线设计" : "Render Pipeline")}
    <div class="chat-body">
      <div class="asst-block">
        <div class="mermaid-card">
          <div class="mermaid-hdr"><span>${c.mermaidHdr}</span><span>Mermaid 12.2</span></div>
          <div class="mermaid-stage" data-mermaid="${mermaidDef.replace(/"/g, "&quot;")}" data-theme="light"></div>
        </div>
        <div class="code-card">
          <div class="code-hdr"><span>typescript</span><span style="display:inline-flex;align-items:center;gap:4px">${SVG.copy} Copy</span></div>
          <pre class="code-pre"><span class="kw">export function</span> <span class="fn">parseFrame</span>(src: <span class="ty">string</span>) {
  <span class="kw">const</span> hit = docCache.<span class="fn">get</span>(src);
  <span class="kw">if</span> (hit) <span class="kw">return</span> hit; <span class="cmt">// 0ms cached path</span>
  <span class="kw">return</span> <span class="fn">buildAst</span>(src, { gfm: <span class="kw">true</span> });
}</pre>
        </div>
      </div>
    </div>
    ${composerHtml({ placeholder: c.placeholder, model: "Sonnet 4", think: "Med", pct: 22 })}`;
  return phoneShell({ theme: "theme-gallery-paper", bodyHtml });
}

function buildF1CopyPhone(c) {
  const bodyHtml = `
    ${subTopBar(c.f1CopyTitle, c.f1CopyPill)}
    <div class="sheet-body">
      <div class="seg-row" style="grid-template-columns:1fr 1fr">
        <div class="seg-item">${c.f1CopyMode1}</div>
        <div class="seg-item seg-item-active">${c.f1CopyMode2}</div>
      </div>
      <div class="copy-card copy-card-picked">
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:5px">
          <span class="pill-tag pill-tag-accent">#1</span>
          <span style="color:var(--accent)">${SVG.check}</span>
        </div>
        <div><span class="sel-mark">${c.f1CopyB1}</span></div>
      </div>
      <div class="copy-card copy-card-picked">
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:5px">
          <span class="pill-tag pill-tag-accent">#2</span>
          <span style="color:var(--accent)">${SVG.check}</span>
        </div>
        <div>${c.f1CopyB2}</div>
      </div>
      <div class="copy-card" style="opacity:0.72">
        <div style="margin-bottom:5px"><span class="pill-tag">#3</span></div>
        <div>${c.f1CopyB3}</div>
      </div>
      <div style="margin-top:auto">
        <div class="dialog-btn dialog-btn-primary">${c.f1CopyBtn}</div>
      </div>
    </div>`;
  return phoneShell({ theme: "theme-everforest-light", bodyHtml });
}

/* --- F2: Models, Thinking/Context, /tree --- */
function buildF2ModelPhone(c) {
  const bodyHtml = `
    ${subTopBar(c.f2ModelBar, c.f2ModelPill)}
    <div class="sheet-body">
      <div class="section-label">${c.f2OAuthSec}</div>
      <div class="card-group">
        <div class="list-row list-row-active">
          <div class="row-main">
            <div class="row-title">Claude Sonnet 4 <span class="pill-tag pill-tag-accent">OAuth</span></div>
            <div class="row-sub">anthropic · 200k context · vision</div>
          </div>
          <span style="color:var(--accent)">${SVG.check}</span>
        </div>
        <div class="list-row">
          <div class="row-main">
            <div class="row-title">GPT-5.2 <span class="pill-tag pill-tag-ok">OAuth</span></div>
            <div class="row-sub">openai-codex · 256k context</div>
          </div>
        </div>
        <div class="list-row">
          <div class="row-main">
            <div class="row-title">Gemini 2.5 Pro <span class="pill-tag">OAuth</span></div>
            <div class="row-sub">google-gemini-cli · 1M context</div>
          </div>
        </div>
      </div>
      <div class="section-label">${c.f2KeySec}</div>
      <div class="card-group">
        <div class="list-row">
          <div class="row-main">
            <div class="row-title">OpenRouter <span class="pill-tag">API Key</span></div>
            <div class="row-sub">api.openrouter.ai/api/v1</div>
          </div>
        </div>
        <div class="list-row">
          <div class="row-main">
            <div class="row-title">Ollama / Local LLM <span class="pill-tag">Custom</span></div>
            <div class="row-sub">http://192.168.1.20:11434/v1</div>
          </div>
        </div>
      </div>
    </div>`;
  return phoneShell({ theme: "theme-warm-paper", bodyHtml });
}

function buildF2ThinkPhone(c) {
  const bodyHtml = `
    ${subTopBar(c.f2ThinkBar, "Sonnet 4")}
    <div class="sheet-body">
      <div class="section-label">${c.f2ThinkLabel}</div>
      <div class="card-group" style="padding:12px;display:flex;flex-direction:column;gap:10px">
        <div class="seg-row">
          <div class="seg-item">off</div>
          <div class="seg-item">low</div>
          <div class="seg-item">med</div>
          <div class="seg-item seg-item-active">high</div>
          <div class="seg-item">max</div>
        </div>
        <div class="row-sub">${c.f2ThinkHint}</div>
      </div>
      <div class="section-label">${c.f2CtxLabel}</div>
      <div class="card-group" style="padding:13px;display:flex;flex-direction:column;gap:10px">
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="row-title">24%</span>
          <span class="pill-tag">48.2k / 200k</span>
        </div>
        <div class="progress-track"><div class="progress-fill" style="width:24%"></div></div>
        <div class="row-sub">${c.f2CtxMeta}</div>
        <div class="dialog-btn dialog-btn-secondary" style="margin-top:4px">${c.f2CompactBtn}</div>
      </div>
      <div class="card-group" style="margin-top:auto">
        <div class="list-row">
          <div class="row-main">
            <div class="row-title">~/.pi/agent/sessions/</div>
            <div class="row-sub">JSONL · Local device storage only</div>
          </div>
          <span class="pill-tag pill-tag-ok">Local</span>
        </div>
      </div>
    </div>`;
  return phoneShell({ theme: "theme-gallery-paper", bodyHtml });
}

function buildF2TreePhone(c) {
  const nodes = c.f2TreeNodes
    .map(
      (n, idx) => `
      <div class="tree-row">
        <div class="tree-rail">
          <span class="tree-node ${n.active ? "tree-node-active" : ""}"></span>
          ${idx < c.f2TreeNodes.length - 1 ? `<span class="tree-stem"></span>` : ""}
        </div>
        <div class="card-group" style="flex:1;padding:9px 11px;${n.active ? "border-color:var(--accent);background:var(--accent-soft)" : ""}">
          <div class="row-title" style="font-size:11.3px">${n.title}</div>
          <div class="row-sub" style="margin-top:3px">${n.sub}</div>
        </div>
      </div>`
    )
    .join("");
  const bodyHtml = `
    ${subTopBar(c.f2TreeBar, c.f2TreePill)}
    <div class="sheet-body" style="gap:4px">
      ${nodes}
      <div style="margin-top:auto;display:flex;gap:8px;padding:4px 0">
        <span class="pill-tag">/fork</span>
        <span class="pill-tag">/clone</span>
        <span class="pill-tag">/export</span>
        <span class="pill-tag">/import</span>
      </div>
    </div>`;
  return phoneShell({ theme: "theme-warm-ink", bodyHtml });
}

/* --- F3: Hands-On Coding (Diff, Workspace, PTY Terminal) --- */
function buildF3DiffPhone(c) {
  const bodyHtml = `
    ${floatingTopBar(c.f3ChatTitle)}
    <div class="chat-body">
      <div class="user-bubble-row"><div class="user-bubble">${c.f3User}</div></div>
      <div class="tool-card">
        <div class="tool-hdr">
          <span class="tool-title">edit · session-index.mjs</span>
          <span class="tool-badge">${c.f3Done}</span>
        </div>
        <div class="diff-lines">
          <div class="diff-row"><span class="diff-ln">41</span><span>async function loadAll(files) {</span></div>
          <div class="diff-row diff-del"><span class="diff-ln">42</span><span>- return <span class="w-del">Promise.all</span>(files.map(readMeta));</span></div>
          <div class="diff-row diff-add"><span class="diff-ln">42</span><span>+ return <span class="w-add">mapLimit</span>(files, <span class="w-add">8</span>, readMeta);</span></div>
          <div class="diff-row"><span class="diff-ln">43</span><span>}</span></div>
        </div>
      </div>
      <div class="tool-card">
        <div class="tool-hdr">
          <span class="tool-title">bash · npm test</span>
          <span class="tool-badge">${c.f3Pass}</span>
        </div>
        <pre class="code-pre" style="background:transparent;padding:7px 10px"><span class="cmt">$ node --test test/session-index.test.mjs</span>
<span style="color:var(--ok)">ok 1 - caps concurrent file reads at 8</span>
<span style="color:var(--ok)">ok 2 - preserves chronological order</span>
<span class="cmt"># tests 42 · pass 42 · duration 148ms</span></pre>
      </div>
    </div>
    ${composerHtml({ placeholder: c.placeholder, model: "Sonnet 4", think: "High", pct: 31 })}`;
  return phoneShell({ theme: "theme-warm-paper", bodyHtml });
}

function buildF3WorkspacePhone(c) {
  const files = [
    { dir: true, name: "src/", meta: "14 files" },
    { dir: false, name: "src/session-index.mjs", meta: "M · 4.2 KB", mod: true },
    { dir: false, name: "src/cache-store.mjs", meta: "3.1 KB" },
    { dir: true, name: "test/", meta: "6 files" },
    { dir: false, name: "test/session-index.test.mjs", meta: "M · 2.8 KB", mod: true },
    { dir: false, name: "package.json", meta: "1.1 KB" },
    { dir: false, name: "README.md", meta: "5.4 KB" },
  ];
  const bodyHtml = `
    ${subTopBar(c.f3WsTitle, c.f3WsPill)}
    <div class="sheet-body">
      <div class="card-group">
        ${files
          .map(
            (f) => `
          <div class="list-row">
            <div class="row-title" style="font-family:var(--font-mono);font-size:11.2px">
              <span style="color:var(--muted)">${f.dir ? SVG.folder : SVG.file}</span>
              <span>${f.name}</span>
            </div>
            <span class="pill-tag ${f.mod ? "pill-tag-accent" : ""}">${f.meta}</span>
          </div>`
          )
          .join("")}
      </div>
    </div>`;
  return phoneShell({ theme: "theme-gallery-paper", bodyHtml });
}

function buildF3TerminalPhone(c) {
  const bodyHtml = `
    ${subTopBar(c.f3TermBar, c.f3TermPill)}
    <div class="term-tabs">
      <span class="term-tab term-tab-active">1 · bash</span>
      <span class="term-tab">2 · git</span>
      <span class="term-tab">+</span>
    </div>
    <pre class="term-canvas"><span style="color:var(--ok)">root@pi</span>:<span style="color:var(--accent)">~/app</span>$ rg "mapLimit" src/
<span style="color:var(--syn-type)">src/index.mjs</span>:42: return mapLimit(xs, 8, fn);

<span style="color:var(--ok)">root@pi</span>:<span style="color:var(--accent)">~/app</span>$ git diff --stat
 src/session-index.mjs | <span style="color:var(--ok)">12 ++++++</span><span style="color:#F09388">--</span>
 test/index.test.mjs   | <span style="color:var(--ok)">24 ++++++++++++</span>
 2 files changed, 34 insertions(+), 2 deletions(-)

<span style="color:var(--ok)">root@pi</span>:<span style="color:var(--accent)">~/app</span>$ node --version
v24.22.1
<span style="color:var(--ok)">root@pi</span>:<span style="color:var(--accent)">~/app</span>$ <span style="display:inline-block;width:7px;height:12px;background:var(--text);vertical-align:-2px"></span></pre>
    <div class="term-keys">
      <div class="term-key">ESC</div>
      <div class="term-key">TAB</div>
      <div class="term-key">CTRL</div>
      <div class="term-key">ALT</div>
      <div class="term-key">↑</div>
      <div class="term-key">↓</div>
    </div>`;
  return phoneShell({ theme: "theme-midnight", bodyHtml });
}

/* --- F4: Ecosystem (Extensions & MCP / Code Mode) --- */
function buildF4ExtensionsPhone(c, lang) {
  const exts = lang === "zh"
    ? [
        { name: "git-review", sub: "自动生成提交摘要与 /review 命令", on: true },
        { name: "ci-watcher", sub: "推送后盯守 CI 状态并投影组件", on: true },
        { name: "theme-sync", sub: "按时间切换 ~/.pi/agent/themes", on: false },
      ]
    : [
        { name: "git-review", sub: "Commit summaries & /review command", on: true },
        { name: "ci-watcher", sub: "Track CI runs via floating widget", on: true },
        { name: "theme-sync", sub: "Auto-switch ~/.pi/agent/themes", on: false },
      ];
  const skills = lang === "zh"
    ? [
        { name: "refactor-kotlin", sub: ".pi/skills/refactor-kotlin/SKILL.md" },
        { name: "release-notes", sub: "~/.pi/agent/skills/release-notes" },
      ]
    : [
        { name: "refactor-kotlin", sub: ".pi/skills/refactor-kotlin/SKILL.md" },
        { name: "release-notes", sub: "~/.pi/agent/skills/release-notes" },
      ];
  const bodyHtml = `
    ${subTopBar(c.f4ExtTitle, c.f4ExtPill)}
    <div class="sheet-body">
      <div class="section-label">${c.f4ExtSec1}</div>
      <div class="card-group">
        ${exts
          .map(
            (e) => `
          <div class="list-row">
            <div class="row-main">
              <div class="row-title" style="font-family:var(--font-mono);font-size:11.6px">${e.name}</div>
              <div class="row-sub">${e.sub}</div>
            </div>
            <div class="switch ${e.on ? "switch-on" : ""}"><span class="switch-knob"></span></div>
          </div>`
          )
          .join("")}
      </div>
      <div class="section-label">${c.f4ExtSec2}</div>
      <div class="card-group">
        ${skills
          .map(
            (s) => `
          <div class="list-row">
            <div class="row-main">
              <div class="row-title">${s.name}</div>
              <div class="row-sub" style="font-family:var(--font-mono);font-size:9.8px">${s.sub}</div>
            </div>
            <span class="pill-tag">SKILL</span>
          </div>`
          )
          .join("")}
      </div>
    </div>`;
  return phoneShell({ theme: "theme-gallery-paper", large: true, bodyHtml });
}

function buildF4McpPhone(c, lang) {
  const servers = lang === "zh"
    ? [
        { name: "github-mcp", sub: "HTTP · OAuth 已授权 · 14 个工具", tag: "在线" },
        { name: "postgres-local", sub: "stdio · 只读模式 · 5 个工具", tag: "在线" },
        { name: "docs-search", sub: "SSE · 自定义端点", tag: "按需" },
      ]
    : [
        { name: "github-mcp", sub: "HTTP · OAuth signed in · 14 tools", tag: "Online" },
        { name: "postgres-local", sub: "stdio · read-only · 5 tools", tag: "Online" },
        { name: "docs-search", sub: "SSE · custom endpoint", tag: "On demand" },
      ];
  const bodyHtml = `
    ${subTopBar(c.f4McpTitle, c.f4McpPill)}
    <div class="sheet-body">
      <div class="section-label">${c.f4McpSec}</div>
      <div class="card-group">
        ${servers
          .map(
            (s, i) => `
          <div class="list-row">
            <div class="row-main">
              <div class="row-title" style="font-family:var(--font-mono);font-size:11.6px">${s.name}</div>
              <div class="row-sub">${s.sub}</div>
            </div>
            <span class="pill-tag ${i < 2 ? "pill-tag-ok" : ""}">${s.tag}</span>
          </div>`
          )
          .join("")}
      </div>
      <div class="section-label">${c.f4CodeModeSec}</div>
      <div class="code-card">
        <div class="code-hdr"><span>code_mode · batch</span><span>3 calls → 1 turn</span></div>
        <pre class="code-pre"><span class="kw">const</span> prs = <span class="kw">await</span> github.<span class="fn">listPRs</span>({ state: <span class="str">"open"</span> });
<span class="kw">const</span> diffs = <span class="kw">await</span> Promise.<span class="fn">all</span>(
  prs.<span class="fn">slice</span>(<span class="num">0</span>, <span class="num">3</span>).<span class="fn">map</span>(p =&gt; github.<span class="fn">getDiff</span>(p.id))
);
<span class="kw">return</span> <span class="fn">summarizeChanges</span>(diffs);</pre>
      </div>
    </div>`;
  return phoneShell({ theme: "theme-dawn", large: true, bodyHtml });
}

/* --- F5: Device Copilot (All Off by Default + 3-Choice Confirmation) --- */
function buildF5SettingsPhone(c) {
  const bodyHtml = `
    ${subTopBar(c.f5DevTitle, c.f5DevPill)}
    <div class="sheet-body">
      <div class="callout" style="display:flex;align-items:flex-start;gap:8px">
        <span style="flex-shrink:0;margin-top:2px;color:var(--accent)">${SVG.shield}</span>
        <span>${c.f5ZeroNotice}</span>
      </div>
      <div class="card-group">
        ${c.f5Groups
          .map(
            (g) => `
          <div class="list-row" style="align-items:flex-start;padding:12px">
            <div class="row-main">
              <div class="row-title">${g.title} <span class="pill-tag">OFF</span></div>
              <div class="row-sub" style="margin-top:3px">${g.sub}</div>
            </div>
            <div class="switch" style="margin-top:2px"><span class="switch-knob"></span></div>
          </div>`
          )
          .join("")}
      </div>
    </div>`;
  return phoneShell({ theme: "theme-warm-paper", large: true, bodyHtml });
}

function buildF5ConfirmPhone(c) {
  const bodyHtml = `
    ${floatingTopBar(c.f5DevTitle)}
    <div class="chat-body" style="filter:blur(1.5px);opacity:0.45">
      <div class="user-bubble-row"><div class="user-bubble">${c.heroUser}</div></div>
    </div>
    <div class="dialog-scrim">
      <div class="dialog-box">
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="pill-tag pill-tag-accent">${c.f5DlgTag}</span>
          <span style="color:var(--muted)">${SVG.shield}</span>
        </div>
        <div style="font-size:15px;font-weight:650;letter-spacing:-0.01em">${c.f5DlgTitle}</div>
        <div style="font-size:11.6px;line-height:1.55;color:var(--muted)">${c.f5DlgDesc}</div>
        <pre class="code-pre" style="border-radius:10px;border:1px solid var(--border);background:var(--code-bg)">${c.f5DlgParam}</pre>
        <div style="display:flex;flex-direction:column;gap:7px;margin-top:4px">
          <div class="dialog-btn dialog-btn-primary">${c.f5BtnOnce}</div>
          <div class="dialog-btn dialog-btn-secondary">${c.f5BtnSess}</div>
          <div class="dialog-btn dialog-btn-ghost">${c.f5BtnDeny}</div>
        </div>
      </div>
    </div>`;
  return phoneShell({ theme: "theme-warm-paper", large: true, bodyHtml });
}

/* --- F6: Virtual TUI & Floating Widget --- */
function buildF6TuiPhone(c, lang) {
  const chips = lang === "zh"
    ? ["[r] 刷新", "[l] 查看日志", "[f] 仅看失败", "[q] 关闭"]
    : ["[r] Refresh", "[l] Logs", "[f] Failed only", "[q] Close"];
  const bodyHtml = `
    ${subTopBar(c.f6TuiTitle, c.f6TuiPill)}
    <div class="sheet-body">
      <div class="tui-grid">┌─ pi-android · main@70dbc9d ──────────┐
│                                      │
│  tawcroot         <span style="color:#68B984">PASS</span>   00m 42s     │
│  SDK Host (339)   <span style="color:#68B984">PASS</span>   00m 36s     │
│  Static guards    <span style="color:#68B984">PASS</span>   00m 19s     │
│  JVM unit tests   <span style="color:#68B984">PASS</span>   01m 14s     │
│  APK modern36     <span style="color:#E0C585">RUN </span>   [######..]  │
│  APK sideload28   <span style="color:#E0C585">RUN </span>   [#####...]  │
│                                      │
│  <span class="tui-osc8">github.com/.../runs/45843298883</span>     │
└──────────────────────────────────────┘</div>
      <div class="section-label">${lang === "zh" ? "自动提取快捷操作" : "Extracted Touch Actions"}</div>
      <div class="tui-chips">
        ${chips.map((ch) => `<span class="tui-chip">${ch}</span>`).join("")}
      </div>
      <div class="card-group" style="margin-top:auto">
        <div class="list-row">
          <div class="row-main">
            <div class="row-title">ctx.ui.custom()</div>
            <div class="row-sub">${lang === "zh" ? "支持手指滑动滚动与 OSC 8 链接点击" : "Touch scroll & clickable OSC 8 links"}</div>
          </div>
          <span class="pill-tag pill-tag-accent">TUI</span>
        </div>
      </div>
    </div>`;
  return phoneShell({ theme: "theme-warm-ink", large: true, bodyHtml });
}

function buildF6WidgetPhone(c, lang) {
  const widgetHtml = `
    <div class="ext-widget">
      <div style="display:flex;align-items:center;justify-content:space-between">
        <span style="font-size:11.2px;font-weight:600">${c.f6WidgetTitle}</span>
        <span class="pill-tag pill-tag-accent">67%</span>
      </div>
      <div class="progress-track"><div class="progress-fill" style="width:67%"></div></div>
      <div style="font-size:10.2px;color:var(--muted)">${c.f6WidgetSub}</div>
    </div>`;
  const bodyHtml = `
    ${floatingTopBar(c.f6ChatTitle)}
    <div class="chat-body">
      <div class="user-bubble-row"><div class="user-bubble">${c.f6User}</div></div>
      <div class="asst-block">
        <div class="asst-p">${c.f6Reply.replace("`ci-watcher`", `<span class="inline-code">ci-watcher</span>`)}</div>
      </div>
    </div>
    ${composerHtml({
      placeholder: c.placeholder,
      model: "Sonnet 4",
      think: "Med",
      pct: 14,
      extChip: lang === "zh" ? "扩展" : "Ext",
      widgetHtml,
    })}`;
  return phoneShell({ theme: "theme-warm-paper", large: true, bodyHtml });
}

/* ============================================================================
 * Scene Registry & Render Pipeline (MathJax 4.1.3 + Mermaid 12.2.0)
 * ============================================================================ */

window.renderScene = async function renderScene(sceneName, lang, mode) {
  const c = COPY[lang] || COPY.zh;
  const isDark = mode === "dark";
  const stage = document.getElementById("stage");
  stage.className = `stage ${isDark ? "stage-dark" : "stage-light"}`;

  let html = `<div class="stage-grid"></div>`;

  if (sceneName === "hero") {
    const primaryTheme = isDark ? "theme-warm-ink" : "theme-warm-paper";
    const secondaryTheme = isDark ? "theme-warm-paper" : "theme-warm-ink";
    html += `
      <div class="hero-layout">
        <div class="hero-copy">
          <div class="hero-brand-row">
            <img class="hero-icon" src="/brand/pi-readme-icon.png" alt="Pi" />
            <div>
              <div class="hero-brand-name">Pi for Android</div>
              <div class="hero-brand-sub">${c.brandSub}</div>
            </div>
          </div>
          <div class="hero-title">${c.heroTitle.replace(/\n/g, "<br/>")}</div>
          <div class="hero-desc">${c.heroDesc}</div>
          <div class="hero-pills">
            ${c.heroPills.map((p) => `<span class="hero-pill">${p}</span>`).join("")}
          </div>
        </div>
        <div class="hero-phones">
          <div class="hero-phone-primary">${buildHeroPrimaryPhone(c, primaryTheme)}</div>
          <div class="hero-phone-secondary">${buildHeroSecondaryPhone(c, secondaryTheme, lang)}</div>
        </div>
      </div>`;
  } else if (sceneName === "f1-reading") {
    html += `
      <div class="gallery-layout">
        <div class="gallery-phones-3">
          ${phoneSlot({ caption: c.f1Cap1, dotColor: "#C96442", phoneHtml: buildF1MathPhone(c) })}
          ${phoneSlot({ caption: c.f1Cap2, dotColor: "#2D6CDF", phoneHtml: buildF1MermaidPhone(c, lang) })}
          ${phoneSlot({ caption: c.f1Cap3, dotColor: "#3A7D5C", phoneHtml: buildF1CopyPhone(c) })}
        </div>
      </div>`;
  } else if (sceneName === "f2-models") {
    html += `
      <div class="gallery-layout">
        <div class="gallery-phones-3">
          ${phoneSlot({ caption: c.f2Cap1, dotColor: "#C96442", phoneHtml: buildF2ModelPhone(c) })}
          ${phoneSlot({ caption: c.f2Cap2, dotColor: "#2D6CDF", phoneHtml: buildF2ThinkPhone(c) })}
          ${phoneSlot({ caption: c.f2Cap3, dotColor: "#D97757", phoneHtml: buildF2TreePhone(c) })}
        </div>
      </div>`;
  } else if (sceneName === "f3-coding") {
    html += `
      <div class="gallery-layout">
        <div class="gallery-phones-3">
          ${phoneSlot({ caption: c.f3Cap1, dotColor: "#C96442", phoneHtml: buildF3DiffPhone(c) })}
          ${phoneSlot({ caption: c.f3Cap2, dotColor: "#2D6CDF", phoneHtml: buildF3WorkspacePhone(c) })}
          ${phoneSlot({ caption: c.f3Cap3, dotColor: "#7AA2F7", phoneHtml: buildF3TerminalPhone(c) })}
        </div>
      </div>`;
  } else if (sceneName === "f4-ecosystem") {
    html += `
      <div class="gallery-layout">
        <div class="gallery-phones-2">
          ${phoneSlot({ caption: c.f4Cap1, dotColor: "#2D6CDF", phoneHtml: buildF4ExtensionsPhone(c, lang) })}
          ${phoneSlot({ caption: c.f4Cap2, dotColor: "#907AA9", phoneHtml: buildF4McpPhone(c, lang) })}
        </div>
      </div>`;
  } else if (sceneName === "f5-copilot") {
    html += `
      <div class="gallery-layout">
        <div class="gallery-phones-2">
          ${phoneSlot({ caption: c.f5Cap1, dotColor: "#C96442", phoneHtml: buildF5SettingsPhone(c) })}
          ${phoneSlot({ caption: c.f5Cap2, dotColor: "#C96442", phoneHtml: buildF5ConfirmPhone(c) })}
        </div>
      </div>`;
  } else if (sceneName === "f6-virtual-tui") {
    html += `
      <div class="gallery-layout">
        <div class="gallery-phones-2">
          ${phoneSlot({ caption: c.f6Cap1, dotColor: "#D97757", phoneHtml: buildF6TuiPhone(c, lang) })}
          ${phoneSlot({ caption: c.f6Cap2, dotColor: "#C96442", phoneHtml: buildF6WidgetPhone(c, lang) })}
        </div>
      </div>`;
  }

  stage.innerHTML = html;

  // 1. Render real MathJax 4.1.3 SVG (disable SRE speech worker on document options)
  if (window.MathJax && window.MathJax.startup) {
    await window.MathJax.startup.promise;
    const doc = window.MathJax.startup.document;
    if (doc && doc.options) {
      doc.options.enableEnrichment = false;
      doc.options.enableSpeech = false;
      doc.options.enableBraille = false;
      doc.options.enableComplexity = false;
      doc.options.enableExplorer = false;
    }
    if (doc && doc.outputJax && doc.outputJax.font && doc.outputJax.font.options) {
      doc.outputJax.font.options.dynamicPrefix = "/app/src/main/assets/rich/mathjax/svg/dynamic";
    }
    const mathNodes = stage.querySelectorAll("[data-tex]");
    for (const el of mathNodes) {
      const display = el.classList.contains("math-block");
      const tex = el.getAttribute("data-tex");
      const out = await window.MathJax.tex2svgPromise(tex, { display });
      el.innerHTML = "";
      el.appendChild(out);
    }
  }

  // 2. Render real Mermaid 12.2.0 SVG
  if (window.mermaid) {
    const mermaidNodes = stage.querySelectorAll("[data-mermaid]");
    let idx = 0;
    for (const el of mermaidNodes) {
      const def = el.getAttribute("data-mermaid");
      const mTheme = el.getAttribute("data-theme") === "dark";
      window.mermaid.initialize({
        startOnLoad: false,
        securityLevel: "loose",
        theme: "base",
        fontFamily: "PiSans, sans-serif",
        themeVariables: mTheme
          ? {
              background: "#1E1D1B",
              primaryColor: "#282623",
              primaryTextColor: "#EBE8E1",
              primaryBorderColor: "#54504A",
              lineColor: "#9F9B92",
              secondaryColor: "#242320",
              tertiaryColor: "#1E1D1B",
              edgeLabelBackground: "#1E1D1B",
              fontSize: "14px",
            }
          : {
              background: "#FFFFFF",
              primaryColor: "#F3F1EA",
              primaryTextColor: "#16181C",
              primaryBorderColor: "#C8C5BC",
              lineColor: "#6B6E75",
              secondaryColor: "#EFEFED",
              tertiaryColor: "#FFFFFF",
              edgeLabelBackground: "#FFFFFF",
              fontSize: "14px",
            },
      });
      const { svg } = await window.mermaid.render(`mmd_${sceneName}_${idx++}`, def);
      el.innerHTML = svg;
    }
  }

  await document.fonts.ready;
  return true;
};
