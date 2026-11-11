import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  applicationActivityRows,
  activityVisibility,
  isLiveProcState,
} from './application-activity.ts';
import type { ProcConversation } from 'plowshare-client-ts/operations/proc';

const row = (
  id: string,
  extra: Partial<ProcConversation> = {},
): ProcConversation => ({
  id,
  root: id,
  parent: null,
  origin: 'turn',
  lifecycle: 'ACTIVE',
  project: 'network-privacy-watch',
  owner: 'enzo',
  application: null,
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
  lastTurnAt: null,
  state: 'IDLE',
  evidence: 'retained database snapshot',
  latestJob: null,
  ending: null,
  children: 0,
  calls: 0,
  attempts: 0,
  activeCalls: 0,
  repeatedCalls: 0,
  ...extra,
});

await test('activityVisibility uses server for admins and mine otherwise', () => {
  assert.equal(activityVisibility(true), 'server');
  assert.equal(activityVisibility(false), 'mine');
  assert.equal(activityVisibility(undefined), 'mine');
});

await test('isLiveProcState treats running and processing as live', () => {
  assert.equal(isLiveProcState('RUNNING'), true);
  assert.equal(isLiveProcState('PROCESSING'), true);
  assert.equal(isLiveProcState('IDLE'), false);
  assert.equal(isLiveProcState('COMPLETED'), false);
});

await test('applicationActivityRows prefers live work then newest logs', () => {
  const rows = applicationActivityRows(
    [
      row('cnv_idle', {
        agent: 'privacy_analyst',
        origin: 'delegation',
        state: 'IDLE',
        owner: '@service/abc',
        principal: '@service/abc',
        createdAt: '2026-11-09T10:55:01Z',
        lastTurnAt: '2026-11-09T10:55:01Z',
      }),
      row('cnv_live', {
        agent: 'privacy_analyst',
        origin: 'delegation',
        state: 'RUNNING',
        owner: '@service/abc',
        createdAt: '2026-11-08T00:00:00Z',
        lastTurnAt: '2026-11-10T00:00:00Z',
      }),
      row('cnv_turn', {
        origin: 'turn',
        state: 'COMPLETED',
        owner: 'enzo',
        createdAt: '2026-11-11T00:00:00Z',
        lastTurnAt: '2026-11-11T00:00:00Z',
      }),
    ],
    { titles: { cnv_turn: 'Person chat' }, selected: 'cnv_live' },
  );
  assert.deepEqual(
    rows.map((item) => [item.id, item.live, item.current, item.title]),
    [
      ['cnv_live', true, true, 'privacy_analyst'],
      ['cnv_turn', false, false, 'Person chat'],
      ['cnv_idle', false, false, 'privacy_analyst'],
    ],
  );
  assert.match(rows[2]!.subtitle, /delegation/i);
  assert.match(rows[2]!.subtitle, /@service/);
});

await test('applicationActivityRows nests person chats without treating service ownership as READ authority', () => {
  const rows = applicationActivityRows(
    [
      row('cnv_mine', { owner: 'enzo', origin: 'turn' }),
      row('cnv_service', {
        owner: '@service/abc',
        origin: 'delegation',
        agent: 'privacy_analyst',
      }),
    ],
    { handle: 'enzo', titles: { cnv_mine: 'Mine' } },
  );
  assert.equal(rows.find((row) => row.id === 'cnv_mine')?.nestChat, true);
  assert.equal(rows.find((row) => row.id === 'cnv_service')?.nestChat, false);
});
