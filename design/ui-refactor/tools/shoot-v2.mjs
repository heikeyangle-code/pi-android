// v2 出图：62 台设备逐台截图 + 整页全貌 + 控制台错误检查
// 用法：node tools/shoot-v2.mjs
import { createRequire } from 'node:module';
import fs from 'node:fs';

const require = createRequire(import.meta.url);
const { chromium } = require('/usr/local/lib/node_modules/playwright');

const ROOT = '/root/pi-android/design/ui-refactor';
const FILE = `${ROOT}/design-demos/direction-b-v2.html`;
const OUT = `${ROOT}/design-demos/shots-v2`;
fs.mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 2400, height: 1200 } });
const errs = [];
page.on('pageerror', (e) => errs.push(`pageerror: ${e.message}`));
page.on('console', (m) => { if (m.type() === 'error') errs.push(`console: ${m.text()}`); });
page.on('requestfailed', (r) => errs.push(`requestfailed: ${r.url()} ${r.failure()?.errorText}`));

await page.goto('file://' + FILE, { waitUntil: 'load' });
await page.waitForTimeout(4000);

const phones = page.locator('div[style*="border-radius: 44px"]');
const n = await phones.count();
console.log('phones:', n);

for (let i = 0; i < n; i++) {
  const name = `phone${String(i + 1).padStart(2, '0')}.png`;
  await phones.nth(i).screenshot({ path: `${OUT}/${name}` }).catch((e) => errs.push(`shot ${name}: ${e.message}`));
}
console.log('shots written:', fs.readdirSync(OUT).length);

// 整页全貌（62 台，可能很大；失败不致命）
try {
  await page.screenshot({ path: `${OUT}/board.png`, fullPage: true });
  console.log('board ok');
} catch (e) {
  console.log('board failed:', e.message);
}

// 交互冒烟：切底栏 / 展开工具卡 / 弹覆盖层，只关心有没有异常
const smoke = [];
const clickText = async (i, text) => {
  try {
    await phones.nth(i).getByText(text, { exact: false }).first().click({ timeout: 2500 });
    await page.waitForTimeout(400);
    smoke.push(`phone${i + 1} 点「${text}」ok`);
  } catch (e) { smoke.push(`phone${i + 1} 点「${text}」失败: ${e.message.split('\n')[0]}`); }
};
await clickText(0, '工作区');
await clickText(0, '设置');
await clickText(0, '对话');
await clickText(0, '会话');
console.log(smoke.join('\n'));

console.log('errors:', errs.length ? '\n  ' + errs.slice(0, 12).join('\n  ') : '无');
fs.writeFileSync(`${OUT}/report.txt`, `phones=${n}\n${smoke.join('\n')}\nerrors=${errs.length}\n${errs.join('\n')}\n`);
await browser.close();
