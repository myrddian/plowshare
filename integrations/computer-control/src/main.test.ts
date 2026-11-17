import assert from 'node:assert/strict';
import test from 'node:test';
import { main } from './main.ts';
import type { ComputerHostLogin, ComputerMainPorts } from './main.ts';
import { createHostDiagnostics } from './host-diagnostics.ts';
import { hostConfig } from './private-config.test-support.ts';
import type { ComputerHostOptions } from './runner.ts';

function fixture() {
  const lines: string[] = [];
  let closes = 0,
    auth = 0,
    runs = 0;
  let stopped: (() => void) | undefined;
  const login: ComputerHostLogin = {
    account: hostConfig.binding.account,
    connection: { request: () => Promise.reject(new Error('unused')) },
    currentBearer: async () => 'private-token',
    close: () => {
      closes++;
    },
  };
  const ports: ComputerMainPorts = {
    configReader: { read: async () => hostConfig, drain: async () => {} },
    diagnostics: createHostDiagnostics((line) => lines.push(line)),
    authenticate: async () => {
      auth++;
      return login;
    },
    runHost: async (options) => {
      runs++;
      options.onReady?.();
    },
    subscribeStop: (stop) => {
      stopped = stop;
      return () => {
        stopped = undefined;
      };
    },
  };
  return {
    ports,
    login,
    lines,
    stop: () => stopped?.(),
    counts: () => ({ closes, auth, runs }),
  };
}
await test('main validates args/config before auth and never logs private errors', async () => {
  const f = fixture();
  await assert.rejects(main([], {}, f.ports));
  assert.equal(f.counts().auth, 0);
  f.ports.configReader.read = async () => {
    throw new Error('private-path/token');
  };
  await assert.rejects(main(['/fixture'], {}, f.ports));
  assert.equal(f.counts().auth, 0);
  assert.ok(!f.lines.join('').includes('private'));
});
await test('main uses exact original login, inherited startup and once budget, closes on success', async () => {
  const f = fixture();
  let received: ComputerHostOptions | undefined;
  f.ports.runHost = async (options) => {
    received = options;
    options.startupDeadline?.requireActive();
    options.onReady?.();
  };
  await main(['/fixture', '--once'], {}, f.ports);
  assert.ok(received);
  assert.equal(received.connection, f.login.connection);
  assert.equal(received.diagnostics, f.ports.diagnostics);
  assert.equal(received.maxPasses, 1);
  assert.deepEqual(f.counts(), { auth: 1, runs: 0, closes: 1 });
});
await test('stop during noncooperative authentication closes eventual late session and never starts host', async () => {
  const f = fixture();
  let resolve: ((login: ComputerHostLogin) => void) | undefined;
  f.ports.authenticate = () =>
    new Promise((done) => {
      resolve = done;
    });
  const pending = main(['/fixture'], {}, f.ports);
  for (let i = 0; i < 10 && resolve === undefined; i++) await Promise.resolve();
  assert.ok(resolve);
  f.stop();
  await assert.rejects(pending);
  resolve(f.login);
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(f.counts().closes, 1);
  assert.equal(f.counts().runs, 0);
});
await test('mismatched account closes original session without runner effects', async () => {
  const f = fixture();
  f.ports.authenticate = async () => ({ ...f.login, account: 'other' });
  await assert.rejects(main(['/fixture'], {}, f.ports));
  assert.equal(f.counts().closes, 1);
  assert.equal(f.counts().runs, 0);
});

await test('diagnostic writer failure cannot skip original-session cleanup', async () => {
  const f = fixture();
  const ports = {
    ...f.ports,
    diagnostics: createHostDiagnostics(() => {
      throw new Error('sink failure');
    }),
  };
  await main(['/fixture'], {}, ports);
  assert.equal(ports.diagnostics.unavailable, true);
  assert.equal(f.counts().closes, 1);
});

await test('failed session close still aborts the original authenticated lifetime', async () => {
  const f = fixture();
  let lifetime: AbortSignal | undefined;
  f.ports.authenticate = async (_config, _environment, signal) => {
    lifetime = signal;
    return {
      ...f.login,
      close() {
        f.login.close();
        throw new Error('private close failure');
      },
    };
  };
  await assert.rejects(main(['/fixture'], {}, f.ports));
  assert.equal(f.counts().closes, 1);
  assert.ok(lifetime?.aborted);
});

await test('failed stop unsubscription cannot skip original login close or lifetime abort', async () => {
  const f = fixture();
  let signal: AbortSignal | undefined;
  f.ports.subscribeStop = () => () => {
    throw new Error('unsubscribe failed');
  };
  f.ports.authenticate = async (_config, _env, lifetime) => {
    signal = lifetime;
    return f.login;
  };
  await assert.rejects(main(['/fixture'], {}, f.ports));
  assert.equal(f.counts().closes, 1);
  assert.ok(signal?.aborted);
});

await test('failed stop subscription rejects before authentication and releases startup timer', async () => {
  const f = fixture();
  const before = process
    .getActiveResourcesInfo()
    .filter((name) => name === 'Timeout').length;
  f.ports.subscribeStop = () => {
    throw new Error('subscribe failed');
  };
  await assert.rejects(main(['/fixture'], {}, f.ports));
  assert.equal(f.counts().auth, 0);
  assert.equal(
    process.getActiveResourcesInfo().filter((name) => name === 'Timeout')
      .length,
    before,
  );
});
