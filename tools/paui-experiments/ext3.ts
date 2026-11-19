import { ExtensionRunner } from "@earendil-works/pi-coding-agent";
import { Text } from "@earendil-works/pi-tui";
let runner: any;
const orig = (ExtensionRunner as any).prototype.setUIContext;
(ExtensionRunner as any).prototype.setUIContext = function (ui: any, mode: any) {
  runner = this;
  // replace the host ui BEFORE pi wraps it: add a real custom() and getAllThemes
  const richer = { ...ui, async custom() { return "from-richer-host"; }, getAllThemes: () => [{ name: "x" }] };
  return orig.call(this, richer, mode);
};
export default function (pi: any) {
  pi.registerMessageRenderer("demo", (msg: any, opts: any, th: any) => new Text(th.fg("accent", "RENDERED:" + msg.content), 0, 0));
  pi.registerCommand("rr", { description: "r", handler: async (_a: string, c: any) => {
    const r = runner?.getMessageRenderer("demo");
    const comp = r ? r({ customType: "demo", content: "hi" }, { expanded: false }, c.ui.theme) : null;
    const got = await c.ui.custom(() => ({ render: () => [] }));
    c.ui.setStatus("paui/rr", JSON.stringify({ haveRunner: !!runner, lines: comp?.render(30), custom: got, themes: c.ui.getAllThemes() }));
  }});
}
