import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ApplicationRunsClient } from './application-runs.ts';
import { demoState } from './demo.ts';
import { emptyActivity } from './shared.ts';
import { checkedSender } from './fixture.test-support.ts';
import { runWire as run, statusWire } from './run-fixtures.ts';
import type { Ask } from 'plowshare-client-ts/operations/client-views';
import type { Outcome } from 'plowshare-client-ts/binding/envelope';
import type { DesktopState } from './shared.ts';
import { mergeApplicationRuns } from './application-runs.ts';
import type { Run } from 'plowshare-client-ts/operations/inspection';

const listed = (
  id: string,
  state: string,
  createdAt: string,
  project = 'Managed app',
): Run =>
  ({
    id,
    definition: 'research',
    tier: 'project',
    project,
    state,
    depth: 0,
    createdAt,
  }) as Run;

function fixture() {
  const state: DesktopState = {
    ...demoState(),
    mode: 'live',
    connected: true,
    projects: [{ name: 'Managed app', kind: 'application', role: 'CONTRIBUTOR' }],
    activity: emptyActivity(),
  };
  const calls: Ask[] = [];
  let respond: (ask: Ask) => Outcome | Promise<Outcome> = () => ({
    code: 'OK',
    payload: { orchestrations: [] },
  });
  const client = new ApplicationRunsClient(
    () => state,
    checkedSender(async (ask) => {
      calls.push(ask);
      return respond(ask);
    }),
    () => {},
  );
  return {
    state,
    calls,
    client,
    respond(value: typeof respond) {
      respond = value;
    },
  };
}

await test('mergeApplicationRuns prefers live roots then newest history', () => {
  assert.deepEqual(
    mergeApplicationRuns(
      [
        listed('old', 'finished', '2020-01-01T00:00:00Z'),
        listed('mid', 'finished', '2020-02-01T00:00:00Z'),
      ],
      [
        listed('live', 'running', '2020-01-15T00:00:00Z'),
        listed('old', 'running', '2020-01-01T00:00:00Z'),
      ],
    ).map((row) => row.id),
    ['live', 'old', 'mid'],
  );
});

await test('ApplicationRunsClient lists with project on every orchestration.list ask', async () => {
  const f = fixture();
  f.respond((ask) => {
    const payload = ask.payload as { project?: string; state?: string };
    assert.equal(payload.project, 'Managed app');
    if (payload.state === 'running')
      return {
        code: 'OK',
        payload: {
          orchestrations: [
            run('live', 'running', {
              project: 'Managed app',
              createdAt: '2020-02-01T00:00:00Z',
            }),
          ],
        },
      };
    if (payload.state) return { code: 'OK', payload: { orchestrations: [] } };
    return {
      code: 'OK',
      payload: {
        orchestrations: [
          run('done', 'finished', {
            project: 'Managed app',
            createdAt: '2020-01-01T00:00:00Z',
          }),
        ],
      },
    };
  });
  await f.client.list('Managed app');
  assert.equal(f.state.applicationRuns?.project, 'Managed app');
  assert.deepEqual(
    f.state.applicationRuns?.items.map((row) => row.id),
    ['live', 'done'],
  );
  assert.equal(
    f.calls.filter((ask) => ask.type === 'orchestration.list').length,
    4,
  );
  assert.ok(
    f.calls.every(
      (ask) =>
        ask.type !== 'orchestration.list' ||
        (ask.payload as { project?: string }).project === 'Managed app',
    ),
  );
});

await test('ApplicationRunsClient inspect loads detail without an Activity window', async () => {
  const f = fixture();
  f.respond((ask) =>
    ask.type === 'orchestration.status'
      ? { code: 'OK', payload: statusWire('live', 'running') }
      : {
          code: 'OK',
          payload: {
            orchestrations: [
              run('live', 'running', { project: 'Managed app' }),
            ],
          },
        },
  );
  await f.client.list('Managed app');
  await f.client.inspect('live');
  assert.equal(f.state.activity.details.live?.value?.run.id, 'live');
  assert.equal(f.state.applicationRuns?.selected, 'live');
});

await test('ApplicationRunsClient refuses a project that is not an available Application', async () => {
  const f = fixture();
  await assert.rejects(f.client.list('Research'), /available server Application/);
  f.state.connected = false;
  await assert.rejects(f.client.list('Managed app'), /available server Application/);
});
