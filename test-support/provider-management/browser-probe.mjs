/** Browser acceptance of the actual Projects screen on one segmented fixture socket. */
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { build } from '../../plowshare-desktop/node_modules/esbuild/lib/main.js';
import { chromium } from '../../plowshare-desktop/node_modules/playwright/index.mjs';
import { expect } from '../../plowshare-desktop/node_modules/playwright/test.mjs';
import { WebSocketServer } from '../../sdk/node/node_modules/ws/wrapper.mjs';
import { fixtureSocket } from '../../scripts/sdk-packet-fixture.mjs';
import { projectWire } from '../../plowshare-desktop/src/wire-fixtures.ts';

const id = '11111111-1111-1111-1111-111111111111';
const connection = { project: 'fixture', connection: id, revision: id, enabled: true, deleted: false,
  definition: { name: 'Fixture endpoint', endpoint: 'https://provider.example/mcp', authentication: 'API_KEY', apiKeyHeader: 'X-API-Key', oauth: null } };
const grant = { project: 'fixture', connection: id, subject: 'owner', generation: id, connectionRevision: id, state: 'READY', expiresAt: null, keyId: 'fixture' };
const bundled = await build({ stdin: { contents: `
  import { createProjects } from './plowshare-console/src/screens/projects.ts';
  import { socketTransport } from './plowshare-console/src/transport.ts';
  import { openEventStream } from './plowshare-console/src/events.ts';
  import { mountStyles } from './plowshare-console/src/repl/styles.ts';
  mountStyles();
  const listeners = [];
  const stream = openEventStream({session:'${id}', onEvent() {}, onStatus(status) { for(const listen of listeners) listen(status); }});
  const screen = createProjects({root:document.body, transport:socketTransport(()=>stream), session:'${id}',
    openStream(options) { listeners.push(options.onStatus); return {ask:stream.ask,status:stream.status,close() {}}; }});
  window.addEventListener('fixture-context-update',()=>{for(const listen of listeners) listen(stream.status());});
  screen.load().catch(error=>{document.body.textContent=error.message;});
  window.addEventListener('beforeunload',()=>{screen.destroy();stream.close();});
`, resolveDir: resolve('.'), loader: 'ts' }, bundle: true, platform: 'browser', write: false });
const bytes = bundled.outputFiles[0].contents;
const server = createServer((request, response) => {
  assert.ok(!request.url.startsWith('/v1/'), 'Management attempted an HTTP fallback');
  response.setHeader('Content-Type', request.url === '/app.js' ? 'application/javascript' : 'text/html');
  response.end(request.url === '/app.js' ? bytes : '<!doctype html><html><head><meta charset="utf-8"><title>Provider management fixture</title></head><body><script type="module" src="/app.js"></script></body></html>');
});
const wss = new WebSocketServer({ server });
const calls = []; let upgrades = 0;
wss.on('connection', physical => {
  upgrades++;
  const socket = fixtureSocket(physical);
  socket.on('message', bytes => {
    const frame = JSON.parse(bytes.toString()); calls.push(frame);
    let outcome;
    switch(frame.type) {
      case 'project.list': outcome = {code:'OK',payload:[projectWire('fixture')]}; break;
      case 'admin.status': outcome = {code:'OK',payload:{handle:'owner',serverAdmin:false}}; break;
      case 'approval.list': outcome = {code:'OK',payload:{approvals:[]}}; break;
      case 'provider.connection.list': outcome = {code:'OK',payload:[connection]}; break;
      case 'tool.scope.host.list': outcome = {code:'OK',payload:[]}; break;
      case 'tool.scope.list': outcome = {code:'OK',payload:{connections:[]}}; break;
      case 'provider.credential.status': outcome = {code:'OK',payload:grant}; break;
      case 'provider.credential.put':
        assert.equal(frame.payload.secret,'fixture-private-material');
        outcome = {code:'INTERNAL_ERROR',payload:{secret:'fixture-private-material'}}; break;
      case 'provider.connection.receipt': outcome = {code:'NOT_FOUND',said:'original receipt not found'}; break;
      default: throw new Error('Unexpected operation '+frame.type);
    }
    socket.send(JSON.stringify({id:frame.id,type:frame.type,protocol_version:'plowshare-v1',payload:outcome}));
  });
});
await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
let browser;
try {
  browser = await chromium.launch({headless:true,...(process.argv[2] ? {executablePath:process.argv[2]} : {})});
  const page = await browser.newPage({viewport:{width:900,height:900}});
  await page.goto(`http://127.0.0.1:${server.address().port}`);
  await page.getByText('Server connections',{exact:true}).click();
  const panel = page.locator('.tool-connections');
  await expect(panel).toContainText('owner · execution project fixture');
  await panel.getByRole('button',{name:'Refresh',exact:true}).click();
  await expect(panel.getByRole('status')).toContainText('Snapshot read at');
  await panel.locator('select').nth(0).selectOption(id);
  await panel.getByRole('button',{name:'Inspect authorization',exact:true}).click();
  await expect(panel).toContainText('Authorization for owner: READY');
  await panel.getByRole('button',{name:'Revoke authorization',exact:true}).click();
  await expect(page.getByRole('dialog')).toContainText('Revoke authorization for owner');
  await page.evaluate(()=>window.dispatchEvent(new Event('fixture-context-update')));
  await expect(page.getByRole('button',{name:'Cancel',exact:true})).toBeEnabled();
  await expect(page.getByRole('button',{name:'Confirm action',exact:true})).toBeEnabled();
  await page.getByRole('button',{name:'Cancel',exact:true}).click();
  await expect(panel).toContainText('Cancelled before submission.');
  assert.equal(calls.some(frame=>frame.type==='provider.credential.revoke'),false);
  const secret = panel.locator('input[type=password]');
  await secret.fill('fixture-private-material');
  await panel.getByRole('button',{name:'Set credential',exact:true}).click();
  await expect(secret).toHaveValue('');
  await expect(panel).toContainText('provider.credential.put: INTERNAL_ERROR');
  const pending = panel.locator('select').last();
  await expect(pending.locator('option')).toHaveCount(2); await pending.selectOption({index:1});
  await panel.getByRole('button',{name:'Read original receipt',exact:true}).click();
  await expect(panel).toContainText('provider.connection.receipt: NOT_FOUND');
  assert.equal(calls.filter(frame=>frame.type==='provider.credential.put').length,1);
  assert.equal(upgrades,1);
  assert.equal(calls.some(frame=>frame.type.startsWith('tool.scope.worker.')),false);
  assert.equal((await panel.innerText()).includes('fixture-private-material'),false);
  assert.equal(await page.evaluate(()=>JSON.stringify(sessionStorage).includes('fixture-private-material')),false);
  await mkdir('build/provider-management',{recursive:true});
  await page.screenshot({path:'build/provider-management/console.png',fullPage:true});
  await panel.locator('summary').click(); await expect(secret).toHaveValue('');
  console.log('Browser Projects acceptance passed: one segmented socket, masked input, redacted bookmark, missing receipt and no replay/fallback/worker authority.');
} finally {
  await browser?.close();
  for(const client of wss.clients) client.terminate();
  await new Promise(resolve=>wss.close(resolve));
  await new Promise(resolve=>server.close(resolve));
}
