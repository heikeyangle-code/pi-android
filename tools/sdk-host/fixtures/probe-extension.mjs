/**
 * 探针扩展：把 `ExtensionUIContext` 的每一面都碰一遍，验证宿主真的实现了它。
 * 被 tools/sdk-host/test.mjs 加载；它发出的每个效果都能在 App 侧一一对上。
 */
export default function probe(pi) {
  pi.registerMessageRenderer?.("probe", (message, _options, theme) => ({
    render: (width) => [theme.fg("accent", `renderer<${JSON.stringify(message?.content ?? null).slice(0, 40)}> width=${width}`)],
    dispose: () => {},
  }));

  pi.registerCommand?.("probe-ping", {
    description: "probe command",
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
    }));
    ctx.ui.notify(`custom=${done}`);
  });
}
