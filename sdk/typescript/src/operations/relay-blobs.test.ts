import { describe, expect, it } from 'vitest';
import {
  decodeRelayBlobDescriptor,
  decodeRelayBlobPublish,
  decodeRelayBlobRead,
} from './relay-blobs.js';

const request = {
  requestId: '11111111-1111-1111-1111-111111111111',
  project: 'atlas',
  topic: 'media.capture',
  occurredAt: '2026-10-22T00:00:00Z',
  mediaType: 'image/png',
};

describe('Relay binary boundaries', () => {
  it('accepts canonical metadata without locations or inline content', () => {
    expect(decodeRelayBlobPublish(request)).toEqual(request);
    expect(
      decodeRelayBlobDescriptor({
        sha256: 'a'.repeat(64),
        byteLength: 7,
        mediaType: 'image/png',
      }).byteLength,
    ).toBe(7);
  });
  it('rejects invalid MIME, identity, unknown request fields and calendar timestamps', () => {
    for (const mediaType of [
      'IMAGE/PNG',
      'image/*',
      'image/png; charset=utf8',
      'image/png\r\nx: y',
    ])
      expect(() => decodeRelayBlobPublish({ ...request, mediaType })).toThrow();
    for (const occurredAt of [
      '0000-01-01T00:00:00Z',
      '2026-02-30T00:00:00Z',
      '2026-10-22T24:00:00Z',
      '2026-10-22T00:00:00+18:01',
    ])
      expect(() =>
        decodeRelayBlobPublish({ ...request, occurredAt }),
      ).toThrow();
    expect(() =>
      decodeRelayBlobPublish({ ...request, file: '/private/file' }),
    ).toThrow();
    expect(() =>
      decodeRelayBlobPublish({ ...request, parentTopic: 'source.topic' }),
    ).toThrow();
    expect(() =>
      decodeRelayBlobRead({
        project: 'atlas',
        topic: 'media.capture',
        group: 'workers',
        eventId: '../file',
      }),
    ).toThrow();
  });
  it('rejects truncated or noncanonical identity and unbounded lengths', () => {
    for (const byteLength of [0, -1, 1.5, 52428801])
      expect(() =>
        decodeRelayBlobDescriptor({
          sha256: 'a'.repeat(64),
          byteLength,
          mediaType: 'image/png',
        }),
      ).toThrow();
    expect(() =>
      decodeRelayBlobDescriptor({
        sha256: 'a'.repeat(32),
        byteLength: 1,
        mediaType: 'image/png',
      }),
    ).toThrow();
  });
});
