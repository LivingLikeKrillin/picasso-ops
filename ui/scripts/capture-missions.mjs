import { createRequire } from 'node:module';
import fs from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../../', import.meta.url));
const require = createRequire(new URL('../package.json', import.meta.url));
const { chromium } = require('@playwright/test');
const out = root + '/docs/screens/style-preview';
await fs.mkdir(out, { recursive: true });
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  await page.goto(process.env.OPS_UI_URL || 'http://127.0.0.1:4173');
  await page.getByRole('navigation', { name: '영역' }).getByRole('button', { name: '임무', exact: true }).click();
  await page.getByRole('heading', { name: '서열 랙 준비' }).waitFor();
  await page.evaluate(() => document.fonts.ready);
  await page.evaluate(() => { document.activeElement?.blur(); window.scrollTo(0, 0); });
  await page.screenshot({ path: out + '/missions-fullstack.png', fullPage: true });
  await page.getByRole('navigation', { name: '미션 관리' }).getByRole('button', { name: '호출 연결', exact: true }).click();
  await page.getByRole('button', { name: '입력 검증·변환 미리보기' }).click();
  await page.getByLabel('변환된 작업 지시 JSON').waitFor();
  await page.getByRole('button', { name: '입력 검증·변환 미리보기' }).waitFor();
  await page.evaluate(() => { document.activeElement?.blur(); window.scrollTo(0, 0); });
  await page.screenshot({ path: out + '/mission-contract-fullstack.png', fullPage: true });
  const saved = page.getByRole('button', { name: 'asset-check v1 보기', exact: true });
  if (await saved.isVisible()) {
    await saved.click();
    await page.evaluate(() => { document.activeElement?.blur(); window.scrollTo(0, 0); });
    await page.screenshot({ path: out + '/mission-interface-version-fullstack.png', fullPage: true });
  }
  await page.getByRole('button', { name: '호출 계약 만들기', exact: true }).click();
  await page.getByRole('button', { name: '매핑 검증·미리보기', exact: true }).click();
  await page.getByLabel('계약 매핑 미리보기').waitFor();
  await page.evaluate(() => { document.activeElement?.blur(); window.scrollTo(0, 0); });
  await page.screenshot({ path: out + '/mission-interface-editor-fullstack.png', fullPage: true });
  console.log(JSON.stringify({ capturedAt: new Date().toISOString(), source: 'actual Ops HTTP, no route interception', output: out }));
} finally { await browser.close(); }
