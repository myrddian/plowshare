// The native main/preload/renderer boundary, measured on real WS sockets.
import { _electron as electron } from 'playwright/test';
import executablePath from 'electron';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { protocolFixture } from './protocol-fixture.mjs';
const temporary = await mkdtemp(join(tmpdir(), 'plowshare-transport-'));
const fixture = await protocolFixture();
const env = { ...process.env, PLOWSHARE_CONFIG_DIR: join(temporary, 'credentials'), PLOWSHARE_DESKTOP_CONFIG: join(temporary, 'config'), PLOWSHARE_DESKTOP_PROFILE: join(temporary, 'profile') };
delete env.ELECTRON_RUN_AS_NODE;
let app;
try {
    const packaged = process.env.PLOWSHARE_PACKAGED_EXECUTABLE;
    app = await electron.launch({ executablePath: packaged ?? executablePath, args: packaged ? [] : [resolve('.')], env });
    if (packaged) assert.equal(await app.evaluate(({ app }) => app.isPackaged), true);
    const page = await app.firstWindow();
    const request = value => page.evaluate(value => window.plowshare.request(value), value);
    await request({ action: 'connect', base: fixture.base, handle: 'fixture', password: 'fixture-password' });
    const created = await request({ action: 'create' });
    await request({ action: 'run', conversation: created.conversation, agent: 'fixture-bot', text: 'Transport acceptance' });
    fixture.completeLatest('Transport acceptance finished.');
    await page.waitForFunction(async () => (await window.plowshare.request({ action: 'bootstrap' })).state.jobs.some(job => job.status === 'finished' && job.text.includes('Transport acceptance finished.')));
    const libraryOpening = app.waitForEvent('window');
    await page.locator('#memories-open').click();
    const library = await libraryOpening;
    await library.locator('[data-memory]').first().click();
    await library.waitForFunction(async () => !!(await window.plowshare.request({ action: 'bootstrap' })).state.library.memory.value);
    const activityOpening = app.waitForEvent('window');
    await request({ action: 'activity', view: 'definitions' });
    const activity = await activityOpening;
    await activity.evaluate(() => window.plowshare.request({ action: 'run-definitions' }));
    for (const type of ['conversation.open', 'agent.run', 'job.status', 'conversation.trajectory', 'memory.index', 'memory.read', 'orchestration.definitions'])
        assert.ok(fixture.frames.some(row => row.type === type), `${type} never reached WS`);
    assert.equal(fixture.frames.filter(row => row.type === 'agent.run').length, 1, 'mutation replay');
    assert.deepEqual([...new Set(fixture.httpPaths)].sort(), ['/v1/auth/login', '/v1/auth/refresh', '/v1/auth/ticket']);
    console.log(JSON.stringify({ frontend: 'native-desktop', status: 'passed', httpPaths: [...new Set(fixture.httpPaths)].sort(), wsFrames: [...new Set(fixture.frames.map(row => row.type))].sort(), agentSubmissions: 1 }));
}
finally {
    await app?.close();
    await fixture.close();
    await rm(temporary, { recursive: true, force: true });
}
