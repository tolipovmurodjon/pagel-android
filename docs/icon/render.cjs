// node render.cjs in.svg out.png size  (transparent background)
const { chromium } = require('@playwright/test');
const fs = require('fs');
(async () => {
  const [inp, out, size] = process.argv.slice(2); const s = +size;
  const b = await chromium.launch({ channel: 'chrome' }); const p = await b.newPage();
  await p.setViewportSize({ width: s, height: s });
  await p.setContent(`<html><body style="margin:0;background:${process.argv[5]||"transparent"}">${fs.readFileSync(inp, 'utf8').replace('<svg', `<svg width="${s}" height="${s}"`)}</body></html>`);
  await p.screenshot({ path: out, clip: { x: 0, y: 0, width: s, height: s } });
  await b.close();
})();
