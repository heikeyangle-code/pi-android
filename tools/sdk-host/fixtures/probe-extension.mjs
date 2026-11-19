/**
 * 探针扩展：把 `ExtensionUIContext` 的每一面都碰一遍，验证宿主真的实现了它。
 * 被 tools/sdk-host/test.mjs 加载；它发出的每个效果都能在 App 侧一一对上。
 */
export default function probe(pi) {
  pi.registerMessageRenderer?.("probe", (message, _options, theme) => ({
    render: (width) => [theme.fg("accent", `renderer<${JSON.stringify(message?.content ?? null).slice(0, 40)}> width=${width}`)],
    dispose: () => {},
  }));

  // 自定义会话条目的渲染器（`appendEntry` 写的 `custom` 条目）与 markdown transformer。
  pi.registerEntryRenderer?.("probe-entry", (entry, options, theme) => ({
    render: () => [theme.fg("accent", `entry<${JSON.stringify(entry.data)}> expanded=${options.expanded} key=${typeof options.keyHint}`)],
    invalidate: () => {},
  }));
  pi.registerMarkdownTransformer?.(
    (markdown, context) => markdown.replaceAll("PROBE", `[${context.messageType}:${context.isStreaming}]`),
    { messageTypes: ["user", "assistant", "assistant-thinking", "custom", "summary", "changelog"] },
  );

  // 带自定义渲染器的工具：测 render.tool 把 renderCall / renderResult 渲成行，且同一次调用共享 state。
  pi.registerTool?.({
    name: "probe_tool",
    label: "Probe tool",
    description: "probe",
    parameters: { type: "object", properties: { q: { type: "string" } } },
    async execute() {
      return { content: [{ type: "text", text: "ok" }], details: {} };
    },
    renderCall: (args, theme, ctx) => {
      ctx.state.calls = (ctx.state.calls ?? 0) + 1;
      return { render: (w) => [theme.fg("accent", `call q=${args?.q} w=${w} n=${ctx.state.calls}`)] };
    },
    renderResult: (result, options, theme, ctx) => ({
      render: () => [`result expanded=${options.expanded} err=${ctx.isError} n=${ctx.state.calls}`, String(result?.content?.[0]?.text), `details=${JSON.stringify(result?.details)}`],
    }),
  });

  // 假 OAuth 供应商：测 auth.login 把 SDK 的登录回调译成「事件 + 向 App 提问」。
  pi.registerProvider?.("probe-sso", {
    name: "Probe SSO",
    baseUrl: "https://probe.invalid",
    api: "openai-completions",
    models: [{ id: "probe-model", name: "Probe", input: ["text"], cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, reasoning: false, contextWindow: 8000, maxTokens: 1000 }],
    oauth: {
      name: "Probe SSO",
      async login(cb) {
        cb.onAuth({ url: "https://probe.invalid/authorize", instructions: "open it" });
        cb.onProgress?.("waiting");
        const code = await cb.onPrompt({ message: "paste the code" });
        if (code === "never") await new Promise((_r, rej) => cb.signal?.addEventListener("abort", () => rej(new Error("aborted"))));
        return { refresh: "r-" + code, access: "a-" + code, expires: Date.now() + 3600_000 };
      },
      async refreshToken(c) { return c; },
      getApiKey: (c) => c.access,
    },
  });

  pi.registerFlag?.("probe-mode", { description: "probe flag", type: "string", default: "fast" });
  pi.registerFlag?.("probe-loud", { type: "boolean", default: false });

  pi.registerCommand?.("probe-ping", {
    description: "probe command",
    getArgumentCompletions: (prefix) =>
      ["alpha", "beta", "gamma"].filter((v) => v.startsWith(prefix)).map((v) => ({ value: v, label: v, description: `arg ${v}` })),
    handler: async (_args, ctx) => ctx.ui.notify("pong"),
  });

  pi.on("session_start", async (event, ctx) => {
    // 噪声：扩展里随手一行 console.log，不应该污染协议流。
    console.log("NOISE-from-extension {not json");
    process.stdout.write("NOISE-raw-write\n");

    ctx.ui.setStatus("probe", `ready:${event.reason ?? "start"}`);
    ctx.ui.setTitle("probe session");
    ctx.ui.setWidget("probe-lines", ["alpha", "beta"]);

    // 会自己动的组件：靠 tui.requestRender() 触发重绘。
    ctx.ui.setWidget("probe-factory", (tui, theme) => {
      let ticks = 0;
      const timer = setInterval(() => {
        ticks += 1;
        tui.requestRender();
      }, 30);
      return {
        render: (width) => [`factory width=${width} ticks=${ticks}`, theme.fg("accent", "colored"), `theme=${theme.name}`],
        handleInput: (data) => ctx.ui.notify(`factory key=${JSON.stringify(data)}`),
        invalidate: () => {},
        dispose: () => {
          clearInterval(timer);
          console.error("PROBE-FACTORY-DISPOSED"); // 旧会话的 ctx 已失效，不能再 notify，走 stderr 留证
        },
      };
    });

    // 热重载（`session.reload` / 项目信任改动）会等 session_start 的处理器跑完：下面这些故意不返回的
    // 交互只属于「首次启动」，否则每次重载测试都会卡在没人回答的 custom 组件上。
    if (event.reason === "reload") return;

    const picked = await ctx.ui.select("pick one", ["a", "b"]);
    ctx.ui.notify(`picked=${picked}`);

    // 故意没人回答：宿主应在超时后收回并通知 App。
    const confirmed = await ctx.ui.confirm("slow", "no one answers", { timeout: 150 });
    ctx.ui.notify(`confirm=${confirmed}`);

    const done = await ctx.ui.custom((_tui, _theme, _keys, resolve) => ({
      render: (width) => [`custom width=${width}`, "press enter"],
      handleInput: (data) => {
        if (data === "\r" || data === "\n") resolve("entered");
      },
      dispose: () => {},
    }), { blocked: "probe-blocked" });
    ctx.ui.notify(`custom=${done}`);
  });
}
