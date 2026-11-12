import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ApplicationActivityClient } from './application-activity.ts';
import { demoState } from './demo.ts';
import { emptyActivity } from './shared.ts';
import { checkedSender } from './fixture.test-support.ts';
import type { Ask } from 'plowshare-client-ts/operations/client-views';
import type { Outcome } from 'plowshare-client-ts/binding/envelope';
import type { DesktopState } from './shared.ts';

function fixture(serverAdmin = true) {
  const state: DesktopState = {
    ...demoState(),
    mode: 'live',
    connected: true,
    handle: 'enzo',
    serverAdmin,
    projects: [
      {
        name: 'network-privacy-watch',
        kind: 'application',
        role: 'MANAGER',
      },
    ],
    activity: emptyActivity(),
  };
  const calls: Ask[] = [];
  let respond: (ask: Ask) => Outcome | Promise<Outcome> = () => ({
    code: 'OK',
    payload: {
      snapshotAt: '2026-11-10T00:00:00Z',
      selection: {
        visibility: 'server',
        project: 'network-privacy-watch',
        conversation: null,
        root: null,
        causation: null,
        active: null,
        after: null,
        limit: 100,
      },
      conversations: [],
      total: 0,
      cursor: null,
      coverage: [],
    },
  });
  const client = new ApplicationActivityClient(
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

await test('ApplicationActivityClient lists with project and server visibility for admins', async () => {
  const f = fixture(true);
  f.respond((ask) => {
    assert.equal(ask.type, 'proc.list');
    assert.deepEqual(ask.payload, {
      project: 'network-privacy-watch',
      visibility: 'server',
      limit: 100,
      order: 'newest',
    });
    return {
      code: 'OK',
      payload: {
        snapshotAt: '2026-11-10T00:00:00Z',
        selection: {
          visibility: 'server',
          project: 'network-privacy-watch',
          conversation: null,
          root: null,
          causation: null,
          active: null,
          after: null,
          limit: 100,
          order: 'newest',
        },
        conversations: [
          {
            id: 'cnv_32618B9065BBF8AC',
            root: 'cnv_32618B9065BBF8AC',
            parent: null,
            origin: 'delegation',
            lifecycle: 'ACTIVE',
            project: 'network-privacy-watch',
            owner: '@service/215757d6-460d-471f-8a74-67e9eaf100c2',
            application: 'network-privacy-watch',
            actor: null,
            actingSubject: null,
            agent: 'privacy_analyst',
            principal: '@service/215757d6-460d-471f-8a74-67e9eaf100c2',
            cause: null,
            resource: null,
            revision: null,
            causationRoot: null,
            causationParent: null,
            causationDepth: null,
            createdAt: '2026-11-09T10:55:01Z',
            lastTurnAt: '2026-11-09T10:55:01Z',
            state: 'IDLE',
            evidence: 'retained database snapshot',
            latestJob: null,
            ending: null,
            children: 0,
            calls: 1,
            attempts: 1,
            activeCalls: 0,
            repeatedCalls: 0,
          },
        ],
        total: 1,
        cursor: null,
        coverage: ['All retained non-archived conversation origins'],
      },
    };
  });
  await f.client.list('network-privacy-watch');
  assert.equal(f.state.applicationActivity?.project, 'network-privacy-watch');
  assert.equal(f.state.applicationActivity?.visibility, 'server');
  assert.equal(
    f.state.applicationActivity?.items[0]?.id,
    'cnv_32618B9065BBF8AC',
  );
  assert.equal(f.state.applicationActivity?.items[0]?.agent, 'privacy_analyst');
});

await test('ApplicationActivityClient uses mine visibility without server admin', async () => {
  const f = fixture(false);
  f.respond((ask) => {
    assert.equal((ask.payload as { visibility?: string }).visibility, 'mine');
    return {
      code: 'OK',
      payload: {
        snapshotAt: '2026-11-10T00:00:00Z',
        selection: {
          visibility: 'mine',
          project: 'network-privacy-watch',
          conversation: null,
          root: null,
          causation: null,
          active: null,
          after: null,
          limit: 100,
        },
        conversations: [],
        total: 0,
        cursor: null,
        coverage: [],
      },
    };
  });
  await f.client.list('network-privacy-watch');
  assert.equal(f.state.applicationActivity?.visibility, 'mine');
});

await test('ApplicationActivityClient refuses a missing Application', async () => {
  const f = fixture();
  await assert.rejects(f.client.list('Other'), /available server Application/);
});

await test('ApplicationActivityClient select keeps only listed rows', async () => {
  const f = fixture(true);
  f.respond(() => ({
    code: 'OK',
    payload: {
      snapshotAt: '2026-11-10T00:00:00Z',
      selection: {
        visibility: 'server',
        project: 'network-privacy-watch',
        conversation: null,
        root: null,
        causation: null,
        active: null,
        after: null,
        limit: 100,
      },
      conversations: [
        {
          id: 'cnv_mine',
          root: 'cnv_mine',
          parent: null,
          origin: 'turn',
          lifecycle: 'ACTIVE',
          project: 'network-privacy-watch',
          owner: 'enzo',
          application: 'network-privacy-watch',
          actor: null,
          actingSubject: null,
          agent: null,
          principal: 'enzo',
          cause: null,
          resource: null,
          revision: null,
          causationRoot: null,
          causationParent: null,
          causationDepth: null,
          createdAt: '2026-11-09T10:55:01Z',
          lastTurnAt: '2026-11-09T10:55:01Z',
          state: 'IDLE',
          evidence: 'retained database snapshot',
          latestJob: null,
          ending: null,
          children: 0,
          calls: 0,
          attempts: 0,
          activeCalls: 0,
          repeatedCalls: 0,
        },
      ],
      total: 1,
      cursor: null,
      coverage: [],
    },
  }));
  await f.client.list('network-privacy-watch');
  f.client.select('cnv_mine');
  assert.equal(f.state.applicationActivity?.selected, 'cnv_mine');
  assert.throws(() => f.client.select('cnv_missing'), /available activity row/);
});
