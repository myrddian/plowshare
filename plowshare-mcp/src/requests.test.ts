import assert from 'node:assert/strict';
import test from 'node:test';
import { decodeReply } from 'plowshare-client-ts/operations/schema';
import type { Backend } from './adapter.js';
import { mcpRequests } from './requests.js';
import { record } from './values.js';

const tick = () => new Promise<void>((resolve) => setImmediate(resolve));
const input = (id: string | number, method: string, params?: unknown) =>
  JSON.stringify({ jsonrpc: '2.0', id, method, params });
const cancel = (id: string | number) =>
  JSON.stringify({
    jsonrpc: '2.0',
    method: 'notifications/cancelled',
    params: { requestId: id },
  });
function deferred() {
  let finish!: () => void;
  const promise = new Promise<void>((resolve) => {
    finish = resolve;
  });
  return { promise, finish };
}
function fixture(backend: Backend, maxPending = 16, writeGate?: Promise<void>) {
  const replies: Record<string, unknown>[] = [];
  const failures: Error[] = [];
  let submitted = 0;
  const owner = mcpRequests({
    timeoutMs: 1000,
    maxPending,
    backend: (_signal, sent) => ({
      ...backend,
      invoke: (type, payload, options) => {
        sent();
        submitted++;
        return backend.invoke(type, payload, options);
      },
      root: (project, path) => {
        sent();
        submitted++;
        return backend.root(project, path);
      },
    }),
    write: async (line) => {
      if (writeGate) await writeGate;
      const value: unknown = JSON.parse(line);
      replies.push(record(value));
    },
    fatal: (failure) => {
      failures.push(failure);
    },
  });
  const initialize = async () => {
    owner.submit(input('init', 'initialize', {}));
    owner.submit(
      JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }),
    );
    await tick();
    replies.length = 0;
  };
  return { owner, replies, failures, initialize, submitted: () => submitted };
}
const unused: Backend = {
  invoke: async () => {
    throw new Error('Unexpected backend invocation');
  },
  root: async () => {
    throw new Error('Unexpected root');
  },
  runSession: () => null,
};

await test('held tool does not block a later reply and cancellation has one terminal winner', async () => {
  const hold = deferred();
  const f = fixture({
    ...unused,
    invoke: async (type, payload) => {
      if (!('project' in payload) || payload.project !== 'other')
        await hold.promise;
      return decodeReply(type, { id: 'digest-job' });
    },
  });
  await f.initialize();
  f.owner.submit(
    input(1, 'tools/call', { name: 'memory_digest', arguments: {} }),
  );
  f.owner.submit(
    input(2, 'tools/call', {
      name: 'memory_digest',
      arguments: { project: 'other' },
    }),
  );
  await tick();
  assert.deepEqual(
    f.replies.map((reply) => reply['id']),
    [2],
  );
  f.owner.submit(cancel(1));
  await tick();
  assert.deepEqual(
    f.replies.map((reply) => reply['id']),
    [2, 1],
  );
  assert.match(JSON.stringify(f.replies[1]), /completion is unknown/);
  hold.finish();
  await f.owner.drain();
  assert.equal(f.submitted(), 2);
  assert.equal(f.replies.length, 2);
  assert.deepEqual(f.failures, []);
});

await test('presence lane retains physical root ordering after observer cancellation', async () => {
  const hold = deferred();
  const calls: string[] = [];
  const entered = deferred();
  const f = fixture({
    ...unused,
    root: async (project) => {
      calls.push(`root:${project}`);
      entered.finish();
      if (project === 'A') await hold.promise;
      return {
        current: { project, machine: 'fixture', root: `/fixture/${project}` },
      };
    },
    invoke: async (type, payload) => {
      calls.push(type);
      if (type === 'agent.run') {
        assert.ok('project' in payload && payload.project === 'Personal');
        return decodeReply(type, { id: 'run-job' });
      }
      throw new Error('Unexpected operation');
    },
  });
  await f.initialize();
  f.owner.submit(
    input(1, 'tools/call', {
      name: 'client_root_project_here',
      arguments: { project: 'A', path: '/fixture/A' },
    }),
  );
  f.owner.submit(
    input(2, 'tools/call', {
      name: 'agent_run',
      arguments: { project: 'Personal', agent: 'fixture', task: 'Read' },
    }),
  );
  f.owner.submit(
    input(3, 'tools/call', {
      name: 'client_root_project_here',
      arguments: { project: 'B', path: '/fixture/B' },
    }),
  );
  await entered.promise;
  f.owner.submit(cancel(1));
  f.owner.submit(input(4, 'ping'));
  await tick();
  assert.deepEqual(calls, ['root:A']);
  assert.ok(f.replies.some((reply) => reply['id'] === 4));
  hold.finish();
  await f.owner.drain();
  assert.deepEqual(calls, ['root:A', 'agent.run', 'root:B']);
  assert.equal(f.replies.filter((reply) => reply['id'] === 1).length, 1);
});

await test('initialization and duplicate active IDs defend backend admission', async () => {
  const hold = deferred();
  const f = fixture({
    ...unused,
    invoke: async (type) => {
      await hold.promise;
      return decodeReply(type, { id: 'job' });
    },
  });
  f.owner.submit(
    input('early', 'tools/call', { name: 'memory_digest', arguments: {} }),
  );
  await tick();
  assert.equal(f.submitted(), 0);
  assert.match(JSON.stringify(f.replies), /initialization/);
  await f.initialize();
  f.owner.submit(
    input(1, 'tools/call', { name: 'memory_digest', arguments: {} }),
  );
  assert.throws(() => f.owner.submit(input(1, 'ping')), /Duplicate active/);
  hold.finish();
  await f.owner.drain();
  assert.equal(f.submitted(), 1);
});

await test('writer backpressure reserves call capacity and reports overload without invoking tools', async () => {
  const hold = deferred();
  const f = fixture(unused, 1, hold.promise);
  f.owner.submit(input(1, 'ping'));
  f.owner.submit(
    input(2, 'tools/call', { name: 'memory_digest', arguments: {} }),
  );
  await tick();
  assert.equal(f.replies.length, 0);
  assert.equal(f.submitted(), 0);
  hold.finish();
  await f.owner.drain();
  assert.deepEqual(f.replies.map((reply) => reply['id']).sort(), [1, 2]);
  assert.match(
    JSON.stringify(f.replies.find((reply) => reply['id'] === 2)),
    /capacity exceeded/,
  );
});

await test('saturated admission cannot answer a duplicate ID with another request refusal', async () => {
  const hold = deferred();
  const f = fixture(unused, 1, hold.promise);
  f.owner.submit(input(1, 'ping'));
  assert.throws(() => f.owner.submit(input(1, 'ping')), /Duplicate active/);
  hold.finish();
  await f.owner.drain();
  assert.equal(f.replies.length, 1);
});

await test('pipelined tool admission waits for initialize output handoff', async () => {
  const hold = deferred();
  const f = fixture(
    { ...unused, invoke: async (type) => decodeReply(type, { id: 'job' }) },
    16,
    hold.promise,
  );
  f.owner.submit(input('init', 'initialize', {}));
  f.owner.submit(
    JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }),
  );
  f.owner.submit(
    input(1, 'tools/call', { name: 'memory_digest', arguments: {} }),
  );
  await tick();
  assert.equal(f.submitted(), 0);
  hold.finish();
  await f.owner.drain();
  assert.equal(f.submitted(), 1);
  assert.equal(f.replies[0]?.['id'], 'init');
});

await test('EOF drain has a deadline even when physical root never cooperates', async () => {
  const hold = deferred();
  const entered = deferred();
  const failures: Error[] = [];
  const owner = mcpRequests({
    timeoutMs: 20,
    backend: () => ({
      ...unused,
      root: async () => {
        entered.finish();
        await hold.promise;
        return {
          current: { project: 'A', machine: 'fixture', root: '/fixture/A' },
        };
      },
    }),
    write: async () => {},
    fatal: (failure) => {
      failures.push(failure);
    },
  });
  owner.submit(input('init', 'initialize', {}));
  owner.submit(
    JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }),
  );
  owner.submit(
    input(1, 'tools/call', {
      name: 'client_root_project_here',
      arguments: { project: 'A', path: '/fixture/A' },
    }),
  );
  await entered.promise;
  await assert.rejects(owner.drain(), /drain deadline/);
  assert.equal(failures.length, 1);
  await owner.close();
  hold.finish();
});
