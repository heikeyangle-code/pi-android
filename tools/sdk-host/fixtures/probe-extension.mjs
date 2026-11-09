/**
 * 探针扩展：只做一件事——把 `ExtensionUIContext` 的每一面都碰一遍，验证宿主真的实现了它。
 *
 * 被 `tools/sdk-host/smoke.mjs` 加载。它打印的每条结果都应该能在 App 侧对上：
 *   - `setStatus` / `notify` / `setTitle` → 单向消息
 *   - `setWidget(字符串数组)` → 行数组
 *   - `setWidget(工厂)` → 一个可重渲染、可收按键的 surface
 *   - `select` → 阻塞对话框，等 App 回值
 *   - `custom()` → 整屏组件，`handleInput` 收到回车后 `done()`
 */
export default function probe(pi) {
  pi.on("session_start", async (_event, ctx) => {
    ctx.ui.setStatus("probe", "ready");
    ctx.ui.setTitle("probe session");
    ctx.ui.setWidget("probe-lines", ["alpha", "beta"]);
    ctx.ui.setWidget("probe-factory", (tui, theme) => ({
      render: (width) => [`factory width=${width}`, `theme=${theme.name}`],
      handleInput: (data) => {
        ctx.ui.notify(`factory key=${JSON.stringify(data)}`);
      },
      invalidate: () => {},
      dispose: () => {},
    }));

    const picked = await ctx.ui.select("pick one", ["a", "b"]);
    ctx.ui.notify(`picked=${picked}`);

    const done = await ctx.ui.custom((tui, theme, keybindings, resolve) => ({
      render: (width) => [`custom width=${width}`, `press enter`],
      handleInput: (data) => {
        if (data === "\r" || data === "\n") resolve("entered");
      },
      dispose: () => {},
    }));
    ctx.ui.notify(`custom=${done}`);
  });
}
