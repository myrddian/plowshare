import { _electron as electron, expect } from 'playwright/test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { protocolFixture } from './protocol-fixture.mjs';

const text = ['# Research findings', '', '## Evidence', '',
  'The study found **a unique result 🔬** with *careful qualifications* and `source_read`.', '',
  '- Read the original evidence', '- Compare the report', '',
  '| Finding | Evidence |', '| --- | --- |', '| Supported | Retained source |', '',
  '> Keep the source and its qualifications together.', '',
  '[Source reference](https://example.test/report)', '',
  '```text', ('A repeated code line with its original indentation.\n').repeat(1400), '```', '',
  '## Final conclusion', '', 'A retained conclusion after the complete code block.', '',
  '<img src=x onerror="window.fixtureInjection=true">', '', '[Unsafe](javascript:alert)',
].join('\n');
const fixture = await protocolFixture({ informationText:text, informationTitle:'Research findings' });
const profile = await mkdtemp(join(tmpdir(),'plowshare-report-reader-'));
const env = { ...process.env, PLOWSHARE_DESKTOP_PROFILE:profile, PLOWSHARE_DESKTOP_CONFIG:join(profile,'config'), PLOWSHARE_CONFIG_DIR:join(profile,'credentials') }; delete env.ELECTRON_RUN_AS_NODE;
let app;
try {
  const packaged = process.env.PLOWSHARE_PACKAGED_EXECUTABLE;
  app = await electron.launch({ executablePath:packaged ?? (await import('electron')).default, args:packaged ? [] : [resolve('.')], env });
  const main = await app.firstWindow(), errors=[];
  main.on('pageerror', error => errors.push(error.message));
  await main.evaluate(base => window.plowshare.request({action:'connect',base,handle:'fixture',password:'fixture-password'}),fixture.base);
  const opening = app.waitForEvent('window'); await main.locator('#library-open').click(); const library = await opening;
  library.on('pageerror', error => errors.push(error.message));
  const reports = library.locator('#library-source-panel');
  await reports.locator('.information-row').click();
  const rendered=reports.locator('[data-rendered-passage]');
  await expect(rendered.locator('h1')).toHaveText('Research findings');
  await expect(rendered.locator('h2')).toHaveText(['Evidence','Final conclusion']);
  await expect(rendered.locator('strong')).toHaveText('a unique result 🔬');
  await expect(rendered.locator('em')).toHaveText('careful qualifications');
  await expect(rendered.locator('ul li')).toHaveCount(2);
  await expect(rendered.locator('table')).toHaveCount(1);
  await expect(rendered.locator('blockquote')).toContainText('qualifications');
  await expect(rendered.locator('pre code')).toContainText('A repeated code line');
  await expect(rendered.locator('a')).toHaveAttribute('data-web-link','https://example.test/report');
  await expect(rendered.locator('img,script')).toHaveCount(0);
  await expect(reports.locator('[data-passage]')).toBeHidden();
  const reads=fixture.frames.filter(frame=>frame.type==='information.read');
  assert.deepEqual(reads.map(frame=>frame.payload.offset),[0,32768,65536]);
  assert.ok(reads.every(frame=>frame.payload.limit===32768));
  await expect(reports.locator('[data-detail] [data-ask]')).toHaveCount(0);
  await expect(library.locator('#report-filter [data-scope]')).toBeVisible();
  await expect(reports.locator('.information-intro')).toBeHidden();
  await expect(reports.locator('[data-scope-note]')).toBeHidden();
  await mkdir('build/smoke',{recursive:true});
  for (const [width,height,name] of [[1600,1000,'wide'],[1280,800,'normal'],[980,700,'compact']]) {
    await app.evaluate(({BrowserWindow},size) => BrowserWindow.getAllWindows()[0].setSize(...size),[width,height]);
    await expect.poll(async () => library.evaluate(() => {
      const text=document.querySelector('[data-rendered-passage]').getBoundingClientRect(), question=document.querySelector('[data-ask]').getBoundingClientRect();
      return text.height/innerHeight > .48 && text.top < innerHeight*.35 && question.bottom < text.top && document.documentElement.scrollWidth <= innerWidth;
    })).toBe(true);
    if (name==='wide') {
      await reports.locator('.pane-resizer[data-pane=sources]').press('ArrowRight');
      await expect.poll(async () => library.evaluate(() => Math.abs(document.querySelector('#library-source-panel [data-question]').getBoundingClientRect().left-document.querySelector('#library-source-panel [data-rendered-passage]').getBoundingClientRect().left))).toBeLessThan(1);
    }
    await expect(reports.locator('[data-record-evidence]')).toBeInViewport();
    await expect(library.locator('#report-filter')).toBeInViewport();
    await library.screenshot({path:`build/smoke/report-reader-${name}.png`});
  }
  await reports.locator('[data-ask] input').fill('A draft question stays during refresh');
  await library.locator('#report-filter [data-refresh]').click();
  await expect(reports.locator('[data-ask] input')).toHaveValue('A draft question stays during refresh');
  await rendered.locator('strong').evaluate(node => {
    const range=document.createRange();range.selectNodeContents(node);
    const selection=document.getSelection();selection.removeAllRanges();selection.addRange(range);
  });
  await reports.locator('[data-record-evidence]').click();
  await expect(reports.locator('[data-new-evidence]')).toContainText('Quotation saved');
  const formatted=fixture.frames.find(frame=>frame.type==='information.evidence.record').payload;
  assert.equal(formatted.quote,'a unique result 🔬');assert.equal(formatted.start,text.indexOf(formatted.quote));
  assert.equal(formatted.end,formatted.start+formatted.quote.length);
  await reports.locator('[data-source-format]').click();
  await expect(rendered).toBeHidden();
  await expect(reports.locator('[data-passage]')).toHaveValue(text);
  await reports.locator('[data-passage]').evaluate(node => {node.focus();node.setSelectionRange(8192,8208);});
  await reports.locator('[data-record-evidence]').click();
  await expect.poll(()=>fixture.frames.filter(frame=>frame.type==='information.evidence.record').length).toBe(2);
  const saved=fixture.frames.filter(frame=>frame.type==='information.evidence.record').at(-1).payload;
  assert.equal(saved.start,8192);assert.equal(saved.end,8208);assert.equal(saved.quote,text.slice(8192,8208));
  await reports.locator('[data-source-format]').click();
  await expect(rendered).toBeVisible();
  await expect(reports.locator('[data-source-prev],[data-source-next]')).toHaveCount(0);
  assert.equal(await library.evaluate(()=>window.fixtureInjection),undefined);
  await expect(reports.locator('[data-ask] input')).toHaveValue('A draft question stays during refresh');
  await reports.locator('[data-ask] button').click();
  await reports.locator('[data-ask]').dispatchEvent('submit');
  await expect(reports.locator('[data-answer]')).toContainText('Grounded answer from retained evidence.',{timeout:12000});
  assert.equal(fixture.frames.filter(frame=>frame.type==='information.ask').length,1);
  await library.locator('#report-filter [data-scope]').selectOption('shared');
  await expect(reports.locator('[data-ask]')).toHaveCount(0);
  await expect(reports.locator('[data-detail]')).toContainText('Choose a report');
  await expect(reports.locator('[data-list]')).toContainText('No reports yet');
  await library.locator('#library-documents').click();
  await expect(library.locator('#report-filter')).toBeHidden();
  assert.deepEqual(errors,[]);
  assert.deepEqual([...new Set(fixture.httpPaths)].sort(),['/v1/auth/login','/v1/auth/refresh','/v1/auth/ticket']);
  console.log('PASS: compact report filters, question toolbar, dominant reading area at three sizes, complete Markdown across WS read boundaries, safe links and HTML, rendered and raw quotation offsets, retained drafts, duplicate question guard and collection reset over WS.');
} finally { await app?.close(); await fixture.close(); await rm(profile,{recursive:true,force:true}); }
