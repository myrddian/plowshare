import { describe, it, expect } from 'vitest';
import { MAX_RELAY_TEXT_BYTES } from '../binding/relay-text.ts';
import { decodePayload, decodeReply } from './schema.ts';
import { request, resultOf } from './direct.ts';
const id = '11111111-1111-1111-1111-111111111111';
const input = {
  project: 'fixture',
  topic: 'checks.requests',
  group: 'detectors',
  consumerId: id,
  start: 'OLDEST_RETAINED' as const,
};
const empty = {
  project: 'fixture',
  topic: 'checks.requests',
  group: 'detectors',
  consumerId: id,
  status: 'EMPTY',
  batchId: null,
  fence: null,
  through: '0',
  expiresAt: null,
  expiredThrough: null,
  events: [],
};
describe('Relay topic port reply boundaries', () => {
  it('bounds raw UTF-8 bytes rather than UTF-16 units or escaped JSON', () => {
    const publish = {
      project: input.project,
      topic: input.topic,
      requestId: id,
      occurredAt: '2026-10-09T00:00:00Z',
    };
    for (const text of [
      'x'.repeat(MAX_RELAY_TEXT_BYTES),
      'é'.repeat(MAX_RELAY_TEXT_BYTES / 2),
      '😀'.repeat(MAX_RELAY_TEXT_BYTES / 4),
      '\u0001'.repeat(MAX_RELAY_TEXT_BYTES),
    ]) {
      expect(decodePayload('relay.publish', { ...publish, text }).text).toBe(
        text,
      );
      expect(() =>
        decodePayload('relay.publish', { ...publish, text: text + 'x' }),
      ).toThrow();
    }
    for (const text of ['\ud800', 'x\udfff', 'x\0'])
      expect(() =>
        decodePayload('relay.publish', { ...publish, text }),
      ).toThrow();
  });
  it('binds consumption and acknowledgement to the requested scope and identity', () => {
    const asked = request('relay.consume', input);
    expect(resultOf(asked, { code: 'OK', payload: empty }).kind).toBe(
      'completed',
    );
    for (const change of [
      { project: 'foreign' },
      { topic: 'foreign' },
      { group: 'foreign' },
      { consumerId: '22222222-2222-2222-2222-222222222222' },
    ])
      expect(
        resultOf(asked, { code: 'OK', payload: { ...empty, ...change } }).kind,
      ).toBe('invalid-response');
    const ack = request('relay.ack', {
      project: input.project,
      topic: input.topic,
      group: input.group,
      consumerId: id,
      batchId: id,
      fence: '1',
    });
    const reply = {
      project: input.project,
      topic: input.topic,
      group: input.group,
      batchId: id,
      through: '1',
      gap: false,
    };
    expect(resultOf(ack, { code: 'OK', payload: reply }).kind).toBe(
      'completed',
    );
    expect(
      resultOf(ack, { code: 'OK', payload: { ...reply, gap: true } }).kind,
    ).toBe('invalid-response');
  });
  it('validates nested events and batch offsets before returning a leased batch', () => {
    const event = {
      position: '1',
      eventId: id,
      publisher: 'sdk.publisher',
      occurredAt: '2026-10-07T00:00:00Z',
      publishedAt: '2026-10-07T00:00:00Z',
      correlationId: null,
      causationId: null,
      causation: { rootId: id, parentId: null, depth: 0 },
      payload: {
        kind: 'TEXT',
        text: 'é'.repeat(MAX_RELAY_TEXT_BYTES / 2),
        schedule: null,
        emits: null,
        fireAt: null,
      },
    };
    const batch = {
      ...empty,
      status: 'DATA',
      batchId: id,
      fence: '1',
      through: '1',
      expiresAt: '2026-10-07T00:00:30Z',
      events: [event],
    };
    expect(decodeReply('relay.consume', batch).events).toHaveLength(1);
    for (const change of [
      { position: '0' },
      { position: '2' },
      { causation: { rootId: id, parentId: null, depth: 1 } },
      { payload: { ...event.payload, text: ' ' } },
      { publisher: ' ' },
    ])
      expect(() =>
        decodeReply('relay.consume', {
          ...batch,
          events: [{ ...event, ...change }],
        }),
      ).toThrow();
  });
});
