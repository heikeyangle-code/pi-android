// 出图 + 交互自检：three directions -> shots/*.png + 控制台错误报告
// 用法：node /root/pi-android/design/ui-refactor/tools/shoot.mjs [a b c]
import { createRequire } from 'node:module';
import fs from 'node:fs';

// playwright 装成全局包（本机只有全局 node_modules），ESM 的 NODE_PATH 不生效，
// 所以用 createRequire 走 CJS 解析。
const require = createRequire(import.meta.url);
const { chromium } = require('/usr/local/lib/node_modules/playwright');

const ROOT = '/root/pi-android/design/ui-refactor';
const DEMOS = `${ROOT}/design-demos`;
const SHOTS = `${DEMOS}/shots`;
fs.mkdirSync(SHOTS, { recursive: true });

const all = [['a', 'direction-a'], ['b', 'direction-b'], ['c', 'direction-c']];
const want = process.argv.slice(2);
const targets = want.length ? all.filter(([k]) => want.includes(k)) : all;

const browser = await chromium.launch();
const report = [];

for (const [key, name] of targets) {
  const file = `${DEMOS}/${name}.html`;
  if (!fs.existsSync(file)) { report.push(`${key}: 文件不存在 ${file}`); continue; }
  const page = await browser.newPage({ viewport: { width: 2400, height: 1200 } });
  const errs = [];
  page.on('pageerror', (e) => errs.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errs.push(`console: ${m.text()}`); });
  page.on('requestfailed', (r) => errs.push(`requestfailed: ${r.url()} ${r.failure()?.errorText}`));

  await page.goto('file://' + file, { waitUntil: 'load' });
  await page.waitForTimeout(3000);

  await page.screenshot({ path: `${SHOTS}/${key}-board.png`, fullPage: true });

  // 每台手机都单独出图：整板 2400px 宽在手机上没法读，竖着看 5 张特写才行。
  let phones = page.locator('div[style*="border-radius: 44px"]');
  const nPhones = await phones.count();
  if (nPhones > 0) {
    for (let i = 0; i < nPhones; i++) {
      await phones.nth(i).screenshot({ path: `${SHOTS}/${key}-phone${i + 1}.png` })
        .catch((e) => errs.push(`shot phone${i + 1}: ${e.message}`));
    }
  } else {
    errs.push('未找到 AndroidFrame 外层（选择器 div[style*="border-radius: 44px"] 落空）');
  }

  // 交互测试：在第一台手机里点各处，看是否报错、是否真的变了
  const before = nPhones ? await phones.nth(0).innerHTML().catch(() => '') : '';
  const clickInPhone = async (text) => {
    try {
      const el = phones.nth(0).getByText(text, { exact: false }).first();
      await el.click({ timeout: 2500 });
      await page.waitForTimeout(700);
      return `点击「${text}」ok`;
    } catch (e) { return `点击「${text}」失败: ${e.message.split('\n')[0]}`; }
  };
  const clicks = [];
  for (const t of ['设置', '会话', '工作区', '对话']) clicks.push(await clickInPhone(t));
  const after = nPhones ? await phones.nth(0).innerHTML().catch(() => '') : '';

  report.push(
    `${key} (${name}): 手机数=${nPhones}\n` +
    `  点击交互: ${clicks.join(' | ')}\n` +
    `  点击后 DOM 变化: ${after !== before ? '有' : '无（可能只是切了 tab 又切回来）'}\n` +
    `  错误: ${errs.length ? errs.join('\n        ') : '无'}`
  );
  await page.close();
}

await browser.close();
console.log(report.join('\n\n'));
fs.writeFileSync(`${SHOTS}/report.txt`, report.join('\n\n') + '\n');
