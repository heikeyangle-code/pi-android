import { Text, SelectList, Container, VStack } from "@earendil-works/pi-tui";
export default function (pi: any) {
  pi.registerCommand("render", { description: "r", handler: async (_a: string, c: any) => {
    const th = c.ui.theme;
    const list = new SelectList([{ value: "a", label: "Alpha", description: "first" }, { value: "b", label: "Beta" }], 5, {
      selectedPrefix: (t: string) => th.fg("accent", t), selectedText: (t: string) => th.fg("accent", t),
      description: (t: string) => th.fg("muted", t), scrollInfo: (t: string) => th.fg("dim", t), noMatch: (t: string) => t,
    });
    const root = new Container();
    root.addChild(new Text(th.fg("accent", th.bold("Title")) + " plain", 1, 0));
    root.addChild(list);
    const lines = root.render(40);
    list.handleInput("\x1b[B");          // arrow down
    const after = root.render(40);
    c.ui.setStatus("paui/test", JSON.stringify({ lines, after }));
  }});
}
