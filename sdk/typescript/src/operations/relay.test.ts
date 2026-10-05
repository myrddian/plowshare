import { describe, it, expect } from 'vitest';
import { decodePayload, decodeReply } from './schema.ts';
import { resultOf } from './direct.ts';
const topic = {
  name: 'release.observed',
  kind: 'TEXT',
  retentionSeconds: '345600',
  maxRecords: null,
  through: '9007199254740993',
  expiredThrough: '9007199254740992',
};
const page = {
  scope: { project: 'project', system: false },
  topic,
  after: '0',
  next: '9007199254740993',
  gapThrough: '9007199254740992',
  events: [
    {
      position: '9007199254740993',
      eventId: 'event',
      publisher: 'publisher',
      occurredAt: '2026-10-05T00:00:00Z',
      publishedAt: '2026-10-05T00:00:00Z',
      correlationId: null,
      causationId: null,
      payload: {
        kind: 'TEXT',
        text: 'original',
        schedule: null,
        emits: null,
        fireAt: null,
      },
    },
  ],
  subscribers: [],
  branches: [],
};
describe('Relay public contracts', () => {
  it('decodes lifecycle and private wake references while rejecting mixed or malformed families', () => {
    for (const kind of ['LIFECYCLE', 'WAKE_REQUESTED']) {
      const payload = {
        kind,
        text: null,
        schedule: null,
        emits: null,
        fireAt: null,
        lifecycle:
          kind === 'LIFECYCLE'
            ? {
                source: 'job.ended',
                subject: 'job_fixture',
                state: 'CANCELLED',
                context: null,
                related: null,
              }
            : null,
        wake:
          kind === 'WAKE_REQUESTED'
            ? {
                firing: 'fir_fixture',
                target: 'conversation:cnv_fixture',
                type: 'MESSAGE',
              }
            : null,
      };
      const updated = {
        ...page,
        topic: { ...topic, kind },
        events: [{ ...page.events[0], payload }],
      };
      expect(decodeReply('relay.log', updated).events[0]?.payload.kind).toBe(
        kind,
      );
      expect(() =>
        decodeReply('relay.log', {
          ...updated,
          events: [
            { ...updated.events[0], payload: { ...payload, text: 'extra' } },
          ],
        }),
      ).toThrow();
      expect(() =>
        decodeReply('relay.log', {
          ...updated,
          events: [
            {
              ...updated.events[0],
              payload: { ...payload, lifecycle: null, wake: null },
            },
          ],
        }),
      ).toThrow();
    }
  });
  it('preserves decimal positions beyond the safe-number range', () => {
    expect(
      decodePayload('relay.topics', { system: true, project: null }).system,
    ).toBe(true);
    expect(decodeReply('relay.log', page).events[0]?.position).toBe(
      '9007199254740993',
    );
    expect(
      decodePayload('relay.log', {
        project: 'project',
        topic: 'release.observed',
        after: '9223372036854775807',
      }).after,
    ).toBe('9223372036854775807');
  });
  it('refuses invalid scopes, unsupported fields, lossy positions and excessive bounds before sending', () => {
    for (const value of [
      { topic: 'event' },
      { project: 'project', system: true, topic: 'event' },
      { project: 'project', topic: 'event', after: 1 },
      { project: 'project', topic: 'event', after: '01' },
      { project: 'project', topic: 'event', after: '9223372036854775808' },
      { project: 'project', topic: 'event', limit: 101 },
      { project: 'project', topic: 'event', grant: 'admin' },
    ])
      expect(() => decodePayload('relay.log', value)).toThrow();
    expect(() =>
      decodePayload('relay.process', { project: 'project', limit: 33 }),
    ).toThrow();
    expect(() => decodePayload('relay.process', { system: true })).toThrow();
  });
  it('refuses corrupted offsets, expiry boundaries, event families and receipt projections', () => {
    for (const value of [
      { ...page, next: '1' },
      { ...page, gapThrough: null },
      { ...page, topic: { ...topic, expiredThrough: '9223372036854775808' } },
      {
        ...page,
        events: [{ ...page.events[0], position: Number('9007199254740993') }],
      },
      {
        ...page,
        events: [
          {
            ...page.events[0],
            payload: {
              kind: 'EMPTY',
              text: null,
              schedule: null,
              emits: null,
              fireAt: null,
            },
          },
        ],
      },
    ])
      expect(() => decodeReply('relay.log', value)).toThrow();
  });
  it('correlates scope, topic and requested page before legacy dispatch accepts a reply', () => {
    const request = {
      type: 'relay.log' as const,
      payload: { project: 'project', topic: 'release.observed' },
    };
    expect(resultOf(request, { code: 'OK', payload: page }).kind).toBe(
      'completed',
    );
    expect(
      resultOf(request, {
        code: 'OK',
        payload: { ...page, scope: { project: 'another', system: false } },
      }).kind,
    ).toBe('invalid-response');
    expect(
      resultOf(request, {
        code: 'OK',
        payload: { ...page, topic: { ...topic, name: 'another' } },
      }).kind,
    ).toBe('invalid-response');
  });
});

const id = '26e48bfd-0664-47ab-b3a1-88984c02e8fa';
const control = {
  requestId: id,
  project: 'project',
  topic: 'release.observed',
  topicGeneration: id,
  action: 'ACKNOWLEDGE_GAP' as const,
  subscriber: 'relay.notices.release',
  subscriptionGeneration: id,
  expiredThrough: '9007199254740993',
  reason: 'Acknowledge loss',
};
const outcome = {
  requestId: id,
  project: 'project',
  topic: 'release.observed',
  action: 'ACKNOWLEDGE_GAP',
  subscriber: 'relay.notices.release',
  deliveryId: null,
  status: 'GAP_ACKNOWLEDGED',
  seenThrough: '9007199254740993',
  completedAt: '2026-10-05T00:00:00Z',
};
describe('Relay operator controls', () => {
  it('validates generations, action fields and decimal positions before sending', () => {
    expect(decodePayload('relay.operate', control).expiredThrough).toBe(
      '9007199254740993',
    );
    for (const value of [
      { ...control, topicGeneration: 'bad' },
      { ...control, subscriber: 'builtin.schedule' },
      { ...control, expiredThrough: Number('9007199254740993') },
      { ...control, deliveryId: id },
      { ...control, action: 'ABANDON' },
      { ...control, action: 'REMOVE_TOPIC' },
      { ...control, grant: 'admin' },
      { ...control, reason: 'bad\nreason' },
    ])
      expect(() => decodePayload('relay.operate', value)).toThrow();
    expect(() =>
      decodePayload('relay.operate', {
        ...control,
        action: 'ABANDON',
        expiredThrough: null,
        deliveryId: id,
        fence: '1',
        expectedState: 'DISPATCHING',
      }),
    ).toThrow();
  });
  it('rejects foreign outcomes and preserves unresolved abandonment', () => {
    const asked = { type: 'relay.operate' as const, payload: control };
    expect(resultOf(asked, { code: 'OK', payload: outcome }).kind).toBe(
      'completed',
    );
    expect(
      resultOf(asked, {
        code: 'OK',
        payload: {
          ...outcome,
          requestId: '11111111-1111-1111-1111-111111111111',
        },
      }).kind,
    ).toBe('invalid-response');
    expect(() =>
      decodeReply('relay.operate', {
        ...outcome,
        action: 'ABANDON',
        deliveryId: id,
        seenThrough: null,
        status: 'ABANDONED_UNCERTAIN',
      }),
    ).not.toThrow();
    expect(() =>
      decodeReply('relay.operate', {
        ...outcome,
        action: 'ABANDON',
        deliveryId: id,
        status: 'FAILED',
      }),
    ).toThrow();
  });
});
