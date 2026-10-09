import { createHash, randomUUID } from 'node:crypto';
import { describe, expect, it, vi } from 'vitest';
import type { Arrival, Socket } from './connection.ts';
import {
  packetSocket,
  legacySocket,
  type PacketCodec,
  PACKET_BYTES,
} from './packets.ts';
import { sha256 } from './sha256.ts';

class Wire implements Socket {
  readonly sent: string[] = [];
  closed = false;
  private listener: ((event: Arrival) => void) | undefined;
  send(frame: string): void {
    this.sent.push(frame);
  }
  close(): void {
    this.closed = true;
  }
  addEventListener(type: 'message', listener: (event: Arrival) => void): void;
  addEventListener(type: 'close', listener: (event: unknown) => void): void;
  addEventListener(type: string, listener: (event: Arrival) => void): void {
    if (type === 'message') this.listener = listener;
  }
  deliver(frame: string): void {
    this.listener?.({ data: frame });
  }
}
const codec: PacketCodec = {
  encode: (text) => new TextEncoder().encode(text),
  decode: (bytes) => new TextDecoder('utf-8', { fatal: true }).decode(bytes),
  base64: (bytes) => Buffer.from(bytes).toString('base64'),
  unbase64: (text) => Uint8Array.from(Buffer.from(text, 'base64')),
  hash: (bytes) =>
    Promise.resolve(createHash('sha256').update(bytes).digest('hex')),
  identity: randomUUID,
};
function connected(wire: Wire): Socket {
  return packetSocket({
    socket: wire,
    codec,
    schedule: (callback, ms) => {
      const timer = setTimeout(callback, ms);
      return () => clearTimeout(timer);
    },
  });
}
function parts(bytes: Uint8Array): string[] {
  const id = randomUUID();
  const hash = createHash('sha256').update(bytes).digest('hex');
  const count = Math.ceil(bytes.length / 65536);
  return Array.from({ length: count }, (_, index) =>
    JSON.stringify({
      kind: 'transport.segment',
      version: 1,
      transferId: id,
      segmentNumber: index + 1,
      segmentCount: count,
      byteOffset: index * 65536,
      totalBytes: bytes.length,
      sha256: hash,
      data: codec.base64(bytes.subarray(index * 65536, (index + 1) * 65536)),
    }),
  );
}
const flush = async (): Promise<void> => {
  for (let i = 0; i < 20; i++) await Promise.resolve();
};

describe('bounded packet transport', () => {
  it('rejects an oversized explicit legacy send before touching the physical socket', () => {
    const wire = new Wire();
    const socket = legacySocket(wire);
    expect(() => socket.send('é'.repeat(524289))).toThrow('legacy allowance');
    expect(wire.sent).toHaveLength(0);
    socket.send('small');
    expect(wire.sent).toEqual(['small']);
    socket.close();
  });
  it('preserves split Unicode, accepts reordered identical ranges, and rejects completed reuse', async () => {
    const wire = new Wire();
    const socket = connected(wire);
    const messages: unknown[] = [];
    socket.addEventListener('message', (event) => messages.push(event.data));
    const text = 'x'.repeat(65535) + '😀' + 'y';
    const packets = parts(codec.encode(text));
    wire.deliver(packets[1]!);
    wire.deliver(packets[1]!);
    await flush();
    expect(messages).toEqual([]);
    wire.deliver(packets[0]!);
    await flush();
    expect(messages).toEqual([text]);
    expect(wire.sent).toHaveLength(3);
    wire.deliver(packets[0]!);
    await flush();
    expect(wire.closed).toBe(true);
    expect(messages).toEqual([text]);
    socket.close();
  });
  it.each([
    [
      'hash',
      (frame: string) =>
        frame.replace(
          /"sha256":"[a-f0-9]+"/,
          '"sha256":"' + '0'.repeat(64) + '"',
        ),
    ],
    [
      'duplicate field',
      (frame: string) =>
        frame.replace('"version":1', '"version":1,"version":1'),
    ],
    [
      'numeric string',
      (frame: string) => frame.replace('"version":1', '"version":"1"'),
    ],
    ['unknown field', (frame: string) => frame.replace('{', '{"unknown":1,')],
  ])('rejects invalid %s before application dispatch', async (_, invalid) => {
    const wire = new Wire();
    const socket = connected(wire);
    const received = vi.fn();
    socket.addEventListener('message', received);
    wire.deliver(invalid(parts(codec.encode('hello'))[0]!));
    await flush();
    expect(wire.closed).toBe(true);
    expect(received).not.toHaveBeenCalled();
    socket.close();
  });
  it('refuses invalid UTF-8 and conflicting duplicates', async () => {
    for (const invalid of [true, false]) {
      const wire = new Wire();
      const socket = connected(wire);
      const received = vi.fn();
      socket.addEventListener('message', received);
      if (invalid) wire.deliver(parts(new Uint8Array([255]))[0]!);
      else {
        const packet = parts(new Uint8Array(70000))[0]!;
        wire.deliver(packet);
        await flush();
        wire.deliver(packet.replace('AAAA', 'AQAA'));
      }
      await flush();
      expect(wire.closed).toBe(true);
      expect(received).not.toHaveBeenCalled();
      socket.close();
    }
  });
  it('sends one bounded segment at a time and emits no replay after close', async () => {
    const wire = new Wire();
    const socket = connected(wire);
    socket.send('x'.repeat(70000));
    await flush();
    expect(wire.sent).toHaveLength(1);
    const first: unknown = JSON.parse(wire.sent[0]!);
    if (
      first === null ||
      typeof first !== 'object' ||
      !('transferId' in first) ||
      typeof first.transferId !== 'string'
    )
      throw new Error('Missing transfer identity');
    wire.deliver(
      JSON.stringify({
        kind: 'transport.credit',
        version: 1,
        transferId: first.transferId,
        segmentNumber: 1,
      }),
    );
    await flush();
    expect(wire.sent).toHaveLength(2);
    expect(
      wire.sent.every((frame) => codec.encode(frame).length <= PACKET_BYTES),
    ).toBe(true);
    socket.close();
    await flush();
    expect(wire.sent).toHaveLength(2);
  });
  it('reclaims timed-out reservations for another connection', async () => {
    vi.useFakeTimers();
    const wire = new Wire();
    const socket = connected(wire);
    wire.deliver(parts(new Uint8Array(32 * 1024 * 1024))[0]!);
    await flush();
    await vi.advanceTimersByTimeAsync(15000);
    expect(wire.closed).toBe(true);
    const next = new Wire();
    const live = connected(next);
    live.send('x'.repeat(32 * 1024 * 1024));
    await flush();
    expect(next.sent).toHaveLength(1);
    live.close();
    socket.close();
    await flush();
    vi.useRealTimers();
  });
});

describe('portable SHA-256', () => {
  it('agrees with native hashing at padding, block, and packet boundaries', () => {
    for (const length of [0, 1, 55, 56, 63, 64, 65, 65535, 65536, 65537]) {
      const bytes = Uint8Array.from({ length }, (_, i) => i % 251);
      expect(sha256(bytes)).toBe(
        createHash('sha256').update(bytes).digest('hex'),
      );
    }
  });
});
