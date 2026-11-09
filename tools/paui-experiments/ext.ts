export default function (pi: any) {
  pi.on("session_start", async (_e: any, ctx: any) => {
    const ui = ctx.ui;
    const sameObject = ctx.ui === ui;
    // 1) is ctx.ui one shared object? patch custom() on it
    const orig = ui.custom;
    ui.custom = async (factory: any, options: any) => {
      const r = await ui.input("paui:custom", "{}");
      return r === undefined ? undefined : "patched:" + r;
    };
    // 2) a later read from a fresh ctx sees the patch?
    pi.registerCommand("probe", { description: "probe", handler: async (_a: string, c: any) => {
      const got = await c.ui.custom(() => ({ render: () => [] }));
      c.ui.notify("custom returned " + String(got), "info");
    }});
    ctx.ui.notify("hasUI=" + ctx.hasUI + " mode=" + ctx.mode + " patched=" + (ui.custom !== orig), "info");
  });
}
