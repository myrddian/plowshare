import { _electron as electron, expect } from 'playwright/test';
import executablePath from 'electron';
import { mkdtemp, readFile, readdir, rm, mkdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import assert from 'node:assert/strict';
import { protocolFixture } from './protocol-fixture.mjs';

const directory = await mkdtemp(join(tmpdir(), 'plowshare-provider-ui-'));
const id = '11111111-1111-1111-1111-111111111111';
const connection = { project: 'Research', connection: id, revision: id, enabled: true, deleted: false,
  definition: { name: 'Fixture endpoint', endpoint: 'https://provider.example/mcp', authentication: 'API_KEY', apiKeyHeader: 'X-API-Key', oauth: null } };
let credential = { project: 'Research', connection: id, subject: 'fixture', generation: id, connectionRevision: id, state: 'READY', expiresAt: null, keyId: 'fixture' };
const fixture = await protocolFixture({ serverAdmin: false, ownerProvider(type, payload) {
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
  const sockets = fixture.eventSessions();
  await dialog.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(dialog.getByRole('status')).toContainText('Snapshot read at');
  await dialog.locator('select').nth(0).selectOption(id);
  await dialog.getByRole('button', { name: 'Inspect authorization', exact: true }).click();
  await expect(dialog).toContainText('Authorization for fixture: READY');
  const secret = dialog.locator('input[type=password]');
  await secret.click(); await secret.pressSequentially('fixture-private-material', { delay: 10 });
  await dialog.getByRole('button', { name: 'Set credential', exact: true }).click();
  await expect(secret).toHaveValue('');
  await expect(dialog.getByRole('status')).toContainText('Original receipt confirmed');
  assert.equal(fixture.frames.filter(frame => frame.type === 'provider.credential.put').length, 1);
  assert.equal(fixture.eventSessions(), sockets, 'Management opened another event socket');
  assert.equal(fixture.frames.some(frame => frame.type.startsWith('tool.scope.worker.')), false);
  assert.equal(fixture.httpPaths.some(path => /provider-connection|provider-credential|tool-scope/.test(path)), false);
  const state = await page.evaluate(() => window.plowshare.request({ action: 'bootstrap' }));
  assert.equal(JSON.stringify(state).includes('fixture-private-material'), false);
  for (const file of await readdir(join(directory, 'provider-management'))) {
    if (file.endsWith('.json')) assert.equal((await readFile(join(directory, 'provider-management', file), 'utf8')).includes('fixture-private-material'), false);
  }
  await mkdir('build/smoke', { recursive: true });
  await page.screenshot({ path: 'build/smoke/tool-connections.png' });
  await dialog.getByRole('button', { name: 'Close', exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByRole('button', { name: 'Tool connections', exact: true })).toBeFocused();
  console.log('Native tool connection smoke passed: regular owner, masked entry, one mutation, redacted state/bookmarks, same socket and close focus.');
} finally {
  if (app) await app.close();
  await fixture.close();
  await rm(directory, { recursive: true, force: true });
}
