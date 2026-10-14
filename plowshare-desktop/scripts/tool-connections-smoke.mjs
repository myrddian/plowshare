import { _electron as electron, expect } from 'playwright/test';
import executablePath from 'electron';
import { mkdtemp, readFile, readdir, rm, mkdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import assert from 'node:assert/strict';
import { protocolFixture } from './protocol-fixture.mjs';
import { nativeFixture } from '../../integrations/mcp/build/mcp/native.test-support.js';
import { McpWire, rawTool } from '../../integrations/mcp/build/mcp/wire.test-support.js';

const upstream = new McpWire('2026-07-28');
upstream.tools = [{ ...rawTool, description: 'Echo nested values <img src=x onerror=globalThis.mcpFixtureInjected=true>' }];
await upstream.start();
const stdio = await nativeFixture();
const directory = await mkdtemp(join(tmpdir(), 'plowshare-provider-ui-'));
const id = '11111111-1111-1111-1111-111111111111';
const connection = { project: 'Research', connection: id, revision: id, enabled: true, deleted: false,
  definition: { name: 'Fixture endpoint', endpoint: 'https://provider.example/mcp', authentication: 'API_KEY', apiKeyHeader: 'X-API-Key', oauth: null } };
let credential = { project: 'Research', connection: id, subject: 'fixture', generation: id, connectionRevision: id, state: 'READY', expiresAt: null, keyId: 'fixture' };
const fixture = await protocolFixture({ serverAdmin: false,
  relay(type, payload) {
    if (type === 'relay.publish') return {code:'OK',payload:{requestId:payload.requestId,project:payload.project,topic:payload.topic,position:'1',publishedAt:new Date().toISOString()}};
    if (type === 'relay.consume') return {code:'OK',payload:{project:payload.project,topic:payload.topic,group:payload.group,consumerId:payload.consumerId,status:'EMPTY',batchId:null,fence:null,through:'0',expiresAt:null,expiredThrough:null,events:[]}};
    throw new Error('Unexpected shared native operation '+type);
  }, ownerProvider(type, payload) {
  if (type === 'tool.scope.connect') return {code:'OK',payload:{...payload,sourceProvider:payload.provider,provider:'scanner',account:'fixture'}};
  if (type === 'tool.scope.disconnect') return {code:'OK',payload:{project:payload.project,scope:payload.scope,disconnected:true}};
  if (type === 'provider.connection.list') return { code: 'OK', payload: [connection] };
  if (type === 'tool.scope.host.list') return { code: 'OK', payload: [] };
  if (type === 'tool.scope.list') return { code: 'OK', payload: { connections: [] } };
  if (type === 'provider.credential.status') return { code: 'OK', payload: credential };
  if (type === 'provider.credential.put') {
    assert.equal(payload.secret, 'fixture-private-material');
    assert.equal(payload.subject, null);
    credential = { ...credential, generation: payload.requestId };
    return { code: 'OK', payload: { requestId: payload.requestId, operation: type, connection, credential } };
  }
  throw new Error('Unexpected owner operation ' + type);
} });
fixture.addServerProject({ name: 'Research' });
const env = { ...process.env, PLOWSHARE_CONFIG_DIR: directory, PLOWSHARE_DESKTOP_PROFILE: join(directory, 'profile') };
delete env.ELECTRON_RUN_AS_NODE; delete env.PLOWSHARE_DESKTOP_CONFIG;
let app;
try {
  app = await electron.launch({ executablePath, args: [resolve('.')], env });
  const page = await app.firstWindow();
  await page.locator('#connect-sidebar').click();
  await page.locator('#server-url').fill(fixture.base);
  await page.locator('#handle').fill('fixture');
  await page.locator('#password').click();
  await page.locator('#password').pressSequentially('fixture-password', { delay: 20 });
  await page.locator('#connection-form button[type=submit]').click();
  await expect(page.locator('#connection-dialog')).toBeHidden();
  await page.locator('[data-project="Research"]').click();
  await page.getByRole('button', { name: 'Tool connections', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Tool connections', exact: true });
  await expect(dialog).toBeVisible();
  await dialog.getByRole('tab', { name: 'This client', exact: true }).focus();
  await page.keyboard.press('ArrowRight');
  await expect(dialog.getByRole('tab', { name: 'Server', exact: true })).toBeFocused();
  await expect(dialog.getByRole('tab', { name: 'Server', exact: true })).toHaveAttribute('aria-selected', 'true');
  const sockets = fixture.eventSessions();
  const server = dialog.locator('[data-connection-location=server]');
  const local = dialog.locator('[data-connection-location=local]');
  await expect(local).toContainText('This client');
  await expect(server).toContainText('Server connections');
  await server.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(server.getByRole('status')).toContainText('Snapshot read at');
  await server.locator('select').nth(0).selectOption(id);
  await server.getByRole('button', { name: 'Inspect authorization', exact: true }).click();
  await expect(dialog).toContainText('Authorization for fixture: READY');
  const secret = server.locator('input[type=password]');
  await secret.click(); await secret.pressSequentially('fixture-private-material', { delay: 10 });
  await server.getByRole('button', { name: 'Set credential', exact: true }).click();
  await expect(secret).toHaveValue('');
  await expect(server.getByRole('status')).toContainText('Original receipt confirmed');
  assert.equal(fixture.frames.filter(frame => frame.type === 'provider.credential.put').length, 1);
  assert.equal(fixture.eventSessions(), sockets, 'Management opened another event socket');
  assert.equal(fixture.frames.some(frame => frame.type.startsWith('tool.scope.worker.')), false);
  assert.equal(fixture.httpPaths.some(path => /provider-connection|provider-credential|tool-scope/.test(path)), false);
  const state = await page.evaluate(() => window.plowshare.request({ action: 'bootstrap' }));
  assert.equal(JSON.stringify(state).includes('fixture-private-material'), false);
  for (const file of await readdir(join(directory, 'provider-management'))) {
    if (file.endsWith('.json')) assert.equal((await readFile(join(directory, 'provider-management', file), 'utf8')).includes('fixture-private-material'), false);
  }
  await dialog.getByRole('tab', { name: 'This client', exact: true }).click();
  const frameCount = fixture.frames.length;
  await local.getByLabel('Profile file (absolute path in a private directory)', { exact: true }).fill(join(directory, 'native', 'http.json'));
  for (const [label, value] of [
    ['Client instance ID', 'desktop-ui'], ['Connection name', 'http'], ['Execution project', 'Research'],
    ['Tool prefix', 'ui_'], ['Private receipt directory (absolute path)', join(directory, 'native-receipts')],
    ['MCP HTTP endpoint', upstream.endpoint],
  ]) await local.getByLabel(label, { exact: true }).fill(value);
  await local.getByLabel('Allow unencrypted HTTP', { exact: true }).check();
  await local.getByLabel('Allow private network endpoints', { exact: true }).check();
  await local.getByRole('button', { name: 'Save profile', exact: true }).click();
  await expect(local.getByRole('status')).toContainText('Private profile saved');
  assert.equal(upstream.seen.length, 0, 'Save launched an upstream connection');
  await local.getByRole('button', { name: 'Start local host', exact: true }).click();
  await expect(local.getByRole('alert')).toContainText('Action refused');
  assert.equal(upstream.seen.length, 0, 'Start without consent opened upstream');
  await local.getByRole('button', { name: 'Review launch', exact: true }).click();
  await expect(local.getByRole('status')).toContainText('Review the saved profile');
  await local.getByLabel('I reviewed this profile and permit this instance to connect or launch its configured process', { exact: true }).check();
  await local.getByRole('button', { name: 'Start local host', exact: true }).click();
  await expect(local.getByRole('status')).toContainText('Local host started');
  await expect(local).toContainText('Local only');
  await expect(local).toContainText('Echo nested values');
  assert.equal(await local.locator('img').count(), 0);
  assert.equal(await page.evaluate(() => globalThis.mcpFixtureInjected), undefined);
  await expect(local.locator('[data-native-inventory] pre')).toContainText('items');
  assert.equal(fixture.frames.length, frameCount, 'Native local controls used the Plowshare socket');
  assert.ok(upstream.seen.some(row => row.method === 'tools/list'));
  assert.equal(upstream.seen.some(row => row.method === 'tools/call'), false, 'Viewing inventory invoked a tool');
  await local.getByRole('button', { name: 'Stop selected host', exact: true }).click();
  await expect(local.getByRole('status')).toContainText('Host stopped');
  assert.equal(fixture.frames.length, frameCount, 'Stopping local host used Relay');
  const savedProfile = JSON.parse(await readFile(join(directory, 'native', 'http.json'), 'utf8'));
  assert.equal(savedProfile.owner.account, 'fixture');
  assert.equal(savedProfile.owner.origin, fixture.base);
  await local.getByRole('button', { name: 'New profile', exact: true }).click();
  await local.getByLabel('Profile file (absolute path in a private directory)', { exact: true }).fill(join(directory, 'native', 'stdio.json'));
  await local.getByLabel('Upstream transport', { exact: true }).selectOption('stdio');
  for (const [label, value] of [
    ['Client instance ID', 'desktop-ui'], ['Connection name', 'stdio'], ['Execution project', 'Research'],
    ['Tool prefix', 'ui_'], ['Private receipt directory (absolute path)', join(directory, 'native-receipts')],
    ['Executable (absolute path)', process.execPath], ['Working directory (absolute path)', stdio.directory],
  ]) await local.getByLabel(label, { exact: true }).fill(value);
  await local.getByRole('button', { name: 'Add argument', exact: true }).click();
  await local.getByLabel('Process argument', { exact: true }).fill(stdio.config.transport.args[0]);
  await local.getByRole('button', { name: 'Save profile', exact: true }).click();
  await expect(local.getByRole('status')).toContainText('Private profile saved');
  assert.equal((await stdio.seen()).length, 0);
  await local.getByRole('button', { name: 'Review launch', exact: true }).click();
  await expect(local.getByRole('status')).toContainText('Review the saved profile');
  await local.getByLabel('I reviewed this profile and permit this instance to connect or launch its configured process', { exact: true }).check();
  await local.getByRole('button', { name: 'Start local host', exact: true }).click();
  await expect(local.getByRole('status')).toContainText('Local host started');
  assert.ok((await stdio.seen()).some(row => row.method === 'tools/list'));
  assert.equal((await stdio.seen()).some(row => row.home), false);
  await expect(local).not.toContainText('fixture-secret-stderr');
  await dialog.getByRole('button', { name: 'Close', exact: true }).click();
  await page.getByRole('button', { name: 'Tool connections', exact: true }).click();
  await dialog.locator('[data-connection-location=local]').getByRole('button', { name: 'Refresh local hosts', exact: true }).click();
  await expect(dialog.locator('[data-connection-location=local]')).toContainText('Echo nested values');
  assert.equal(fixture.frames.length, frameCount, 'Local controls used the server after reopen');
  await mkdir('build/smoke', { recursive: true });
  await dialog.evaluate(node => { node.scrollTop = 0; });
  await page.screenshot({ path: 'build/smoke/tool-connections.png' });
  await dialog.locator('[data-connection-location=local]').getByRole('button', { name: 'Stop selected host', exact: true }).click();
  await expect(dialog.locator('[data-connection-location=local]').getByRole('status')).toContainText('Host stopped');
  const nativePanel = dialog.locator('[data-connection-location=local]');
  await nativePanel.getByLabel('Profile file (absolute path in a private directory)', { exact: true }).fill(join(directory, 'native', 'stdio.json'));
  await nativePanel.getByRole('button', { name: 'Load profile', exact: true }).click();
  await expect(nativePanel.getByRole('status')).toContainText('Private profile loaded');
  await nativePanel.getByText('Share this profile with server-owned work', { exact: true }).click();
  for (const [label, value] of [['Sharing scope','desktop'], ['Provider name','mcpnative'], ['Dynamic agents (comma separated)','agent'], ['Tool grants (comma separated)','*']]) await nativePanel.getByLabel(label, {exact:true}).fill(value);
  await nativePanel.getByRole('button', { name: 'Start and share with server', exact: true }).click();
  await expect(nativePanel.getByRole('alert')).toContainText('Action refused');
  assert.equal(fixture.frames.filter(frame => frame.type === 'tool.scope.connect').length, 0, 'Sharing without both consents published a scope');
  await nativePanel.getByLabel('I reviewed this profile and permit this instance to connect or launch its configured process', {exact:true}).check();
  await nativePanel.getByLabel('I authorize sharing these tools with the selected server project and agents', {exact:true}).check();
  await nativePanel.getByRole('button', { name: 'Start and share with server', exact: true }).click();
  await expect(nativePanel.getByRole('status')).toContainText('Explicit server sharing started');
  await expect(nativePanel).toContainText('Shared with server');
  assert.equal(fixture.frames.filter(frame => frame.type === 'tool.scope.connect').length, 1);
  assert.ok(fixture.frames.some(frame => frame.type === 'relay.publish'));
  assert.equal(fixture.eventSessions(), sockets);
  await nativePanel.getByRole('button', { name: 'Stop selected host', exact: true }).click();
  await expect(nativePanel.getByRole('status')).toContainText('Host stopped');
  assert.equal(fixture.frames.filter(frame => frame.type === 'tool.scope.disconnect').length, 1);
  await dialog.getByRole('button', { name: 'Close', exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByRole('button', { name: 'Tool connections', exact: true })).toBeFocused();
  console.log('Native tool connection smoke passed: HTTP/STDIO profile forms, explicit consent, safe inventory, local lifetime, consented server sharing/withdrawal, regular owner, masked entry, one mutation, redacted state/bookmarks, same socket and close focus.');
} finally {
  if (app) await app.close();
  await fixture.close();
  await upstream.close();
  await stdio.close();
  await rm(directory, { recursive: true, force: true });
}
