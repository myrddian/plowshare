import type { Arrival, Socket } from './connection.ts';
import { utf8Length } from './files.ts';

export const PACKET_PROTOCOL = 'plowshare-segments-v1';
export const PACKET_BYTES = 128 * 1024;
export const MESSAGE_BYTES = 320 * 1024 * 1024;
const CHUNK = 64 * 1024;
const CONNECTION_BYTES = 640 * 1024 * 1024;
const PROCESS_BYTES = 2 * 1024 * 1024 * 1024;
let processHeld = 0;

/** Platform codecs are injected so the common packet owner has no Node or DOM dependency. */
export interface PacketCodec {
  encode(text: string): Uint8Array;
  decode(bytes: Uint8Array): string;
  base64(bytes: Uint8Array): string;
  unbase64(text: string): Uint8Array;
  hash(bytes: Uint8Array): Promise<string>;
  identity(): string;
}
export interface PacketOptions {
  readonly socket: Socket;
  readonly codec: PacketCodec;
  readonly schedule: (expired: () => void, milliseconds: number) => () => void;
}
interface Segment {
  readonly kind: 'transport.segment';
  readonly version: 1;
  readonly transferId: string;
  readonly segmentNumber: number;
  readonly segmentCount: number;
  readonly byteOffset: number;
  readonly totalBytes: number;
  readonly sha256: string;
  readonly data: string;
}
interface Credit {
  readonly kind: 'transport.credit';
  readonly version: 1;
  readonly transferId: string;
  readonly segmentNumber: number;
}
interface Assembly {
  readonly first: Segment;
  readonly bytes: Uint8Array;
  readonly ranges: Set<number>;
  readonly started: number;
  cancelIdle: () => void;
  readonly cancelTotal: () => void;
}
interface Outgoing {
  readonly bytes: Uint8Array;
}
interface AwaitingCredit {
  readonly id: string;
  readonly number: number;
  readonly resolve: () => void;
  readonly reject: (reason: Error) => void;
}
function object(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    throw new Error('invalid packet object');
  return value as Record<string, unknown>;
}
function identity(value: unknown): string {
  if (
    typeof value !== 'string' ||
    !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(
      value,
    )
  )
    throw new Error('invalid transfer identity');
  return value;
}
function integer(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value))
    throw new Error('invalid packet integer');
  return value;
}
function credit(value: Record<string, unknown>): Credit {
  if (
    Object.keys(value).sort().join(',') !==
      'kind,segmentNumber,transferId,version' ||
    value['kind'] !== 'transport.credit' ||
    value['version'] !== 1
  )
    throw new Error('invalid credit fields');
  const number = integer(value['segmentNumber']);
  if (number < 1 || number > MESSAGE_BYTES / CHUNK)
    throw new Error('invalid credit number');
  return {
    kind: 'transport.credit',
    version: 1,
    transferId: identity(value['transferId']),
    segmentNumber: number,
  };
}
function segment(value: Record<string, unknown>): Segment {
  if (
    Object.keys(value).sort().join(',') !==
      'byteOffset,data,kind,segmentCount,segmentNumber,sha256,totalBytes,transferId,version' ||
    value['kind'] !== 'transport.segment' ||
    value['version'] !== 1
  )
    throw new Error('invalid segment fields');
  const total = integer(value['totalBytes']);
  const count = integer(value['segmentCount']);
  const number = integer(value['segmentNumber']);
  const offset = integer(value['byteOffset']);
  const hash = value['sha256'];
  const data = value['data'];
  if (
    total <= 0 ||
    total > MESSAGE_BYTES ||
    count !== Math.ceil(total / CHUNK) ||
    number < 1 ||
    number > count ||
    offset !== (number - 1) * CHUNK ||
    typeof hash !== 'string' ||
    !/^[a-f0-9]{64}$/.test(hash) ||
    typeof data !== 'string' ||
    data.length > 87384
  )
    throw new Error('invalid segment metadata');
  return {
    kind: 'transport.segment',
    version: 1,
    transferId: identity(value['transferId']),
    segmentNumber: number,
    segmentCount: count,
    byteOffset: offset,
    totalBytes: total,
    sha256: hash,
    data,
  };
}

/**
 * One authenticated negotiated socket. One data packet is in flight per direction; byte credits
 * bypass the logical writer. No replay, resumable spool, or domain acknowledgement lives here.
 */
export function packetSocket(options: PacketOptions): Socket {
  const { socket, codec, schedule } = options;
  const messages: ((event: Arrival) => void)[] = [];
  const closes: ((event: unknown) => void)[] = [];
  const incoming = new Map<string, Assembly>();
  const completed = new Set<string>();
  const outgoing: Outgoing[] = [];
  let held = 0;
  let open = true;
  let writing = false;
  let waiting: AwaitingCredit | undefined;
  let reader = Promise.resolve();
  function reserve(bytes: number): void {
    if (
      bytes > CONNECTION_BYTES - held ||
      bytes * 3 > PROCESS_BYTES - processHeld
    )
      throw new Error('packet memory capacity exceeded');
    held += bytes;
    processHeld += bytes * 3;
  }
  function release(bytes: number): void {
    held -= bytes;
    processHeld -= bytes * 3;
  }
  function stop(event: unknown): void {
    if (!open) return;
    open = false;
    waiting?.reject(new Error('packet connection closed'));
    waiting = undefined;
    for (const item of incoming.values()) {
      item.cancelIdle();
      item.cancelTotal();
      release(item.bytes.length);
    }
    incoming.clear();
    release(completed.size * 256);
    completed.clear();
    for (const item of outgoing.splice(0)) release(item.bytes.length);
    for (const listener of closes) listener(event);
  }
  function fail(): void {
    stop(undefined);
    socket.close();
  }
  function write(value: Segment | Credit): void {
    socket.send(JSON.stringify(value));
  }
  async function drain(): Promise<void> {
    if (writing) return;
    writing = true;
    try {
      while (open && outgoing.length > 0) {
        const item = outgoing.shift();
        if (item === undefined) break;
        try {
          const id = identity(codec.identity());
          const hash = await codec.hash(item.bytes);
          const count = Math.ceil(item.bytes.length / CHUNK);
          const started = Date.now();
          for (let number = 1; number <= count; number++) {
            if (!open || Date.now() - started >= 60_000)
              throw new Error('packet transfer deadline expired');
            const offset = (number - 1) * CHUNK;
            await new Promise<void>((resolve, reject) => {
              const cancel = schedule(
                () => reject(new Error('packet credit deadline expired')),
                15_000,
              );
              waiting = {
                id,
                number,
                resolve: () => {
                  cancel();
                  resolve();
                },
                reject: (reason) => {
                  cancel();
                  reject(reason);
                },
              };
              try {
                write({
                  kind: 'transport.segment',
                  version: 1,
                  transferId: id,
                  segmentNumber: number,
                  segmentCount: count,
                  byteOffset: offset,
                  totalBytes: item.bytes.length,
                  sha256: hash,
                  data: codec.base64(
                    item.bytes.subarray(
                      offset,
                      Math.min(item.bytes.length, offset + CHUNK),
                    ),
                  ),
                });
              } catch {
                waiting.reject(new Error('packet write failed'));
              }
            });
            waiting = undefined;
          }
        } finally {
          release(item.bytes.length);
        }
      }
    } finally {
      writing = false;
    }
  }
  async function receive(data: unknown): Promise<void> {
    if (!open) return;
    if (
      typeof data !== 'string' ||
      data.length > PACKET_BYTES ||
      codec.encode(data).length > PACKET_BYTES
    )
      throw new Error('invalid packet message');
    // Packet values are flat ASCII scalars; decode field names too, so escaped duplicate keys fail.
    const keys = new Set<string>();
    for (const match of data.matchAll(/"(?:[^"\\]|\\.)*"\s*:/g)) {
      const key: unknown = JSON.parse(
        match[0].slice(0, match[0].lastIndexOf(':')).trim(),
      );
      if (typeof key !== 'string' || keys.has(key))
        throw new Error('duplicate packet field');
      keys.add(key);
      if (
        [
          'version',
          'segmentNumber',
          'segmentCount',
          'byteOffset',
          'totalBytes',
        ].includes(key)
      ) {
        // JSON.parse erases 1.0/1e0 spelling. Wire integer fields use integer tokens in every SDK.
        const tail = data.slice(match.index + match[0].length);
        if (!/^\s*(?:0|[1-9][0-9]*)(?=\s*[,}])/.test(tail))
          throw new Error('invalid packet integer token');
      }
    }
    const value = object(JSON.parse(data));
    if (value['kind'] === 'transport.credit') {
      const ack = credit(value);
      if (
        waiting === undefined ||
        ack.transferId !== waiting.id ||
        ack.segmentNumber !== waiting.number
      )
        throw new Error('unexpected packet credit');
      waiting.resolve();
      return;
    }
    const part = segment(value);
    const bytes = codec.unbase64(part.data);
    if (
      bytes.length !== Math.min(CHUNK, part.totalBytes - part.byteOffset) ||
      codec.base64(bytes) !== part.data
    )
      throw new Error('invalid segment bytes');
    if (completed.has(part.transferId))
      throw new Error('completed transfer identity reused');
    let assembly = incoming.get(part.transferId);
    if (assembly === undefined) {
      if (incoming.size >= 4 || incoming.size + completed.size >= 4096)
        throw new Error('transfer capacity exceeded');
      reserve(part.totalBytes);
      assembly = {
        first: part,
        bytes: new Uint8Array(part.totalBytes),
        ranges: new Set(),
        started: Date.now(),
        cancelIdle: () => {},
        cancelTotal: schedule(fail, 60_000),
      };
      incoming.set(part.transferId, assembly);
    }
    if (
      assembly.first.totalBytes !== part.totalBytes ||
      assembly.first.segmentCount !== part.segmentCount ||
      assembly.first.sha256 !== part.sha256
    )
      throw new Error('conflicting transfer metadata');
    if (assembly.ranges.has(part.segmentNumber)) {
      for (let i = 0; i < bytes.length; i++)
        if (assembly.bytes[part.byteOffset + i] !== bytes[i])
          throw new Error('conflicting duplicate range');
    } else {
      assembly.bytes.set(bytes, part.byteOffset);
      assembly.ranges.add(part.segmentNumber);
    }
    assembly.cancelIdle();
    assembly.cancelIdle = schedule(fail, 15_000);
    let complete: string | undefined;
    if (assembly.ranges.size === part.segmentCount) {
      if ((await codec.hash(assembly.bytes)) !== part.sha256)
        throw new Error('transfer hash mismatch');
      if (!open) return;
      complete = codec.decode(assembly.bytes);
      assembly.cancelIdle();
      assembly.cancelTotal();
      incoming.delete(part.transferId);
      release(part.totalBytes);
      reserve(256);
      completed.add(part.transferId);
    }
    write({
      kind: 'transport.credit',
      version: 1,
      transferId: part.transferId,
      segmentNumber: part.segmentNumber,
    });
    if (complete !== undefined)
      for (const listener of messages) listener({ data: complete });
  }
  socket.addEventListener('message', (event) => {
    reader = reader.then(() => receive(event.data)).catch(fail);
  });
  socket.addEventListener('close', stop);
  return {
    send(frame): void {
      if (!open || frame.length === 0 || frame.length > MESSAGE_BYTES)
        throw new Error('encoded message exceeds transport allowance');
      const bytes = codec.encode(frame);
      if (bytes.length > MESSAGE_BYTES)
        throw new Error('encoded message exceeds transport allowance');
      reserve(bytes.length);
      outgoing.push({ bytes });
      void drain().catch(fail);
    },
    close(): void {
      stop(undefined);
      socket.close();
    },
    addEventListener(
      type: 'message' | 'close',
      listener: ((event: Arrival) => void) | ((event: unknown) => void),
    ): void {
      // The Socket overload correlates the event name and listener; TypeScript loses that pair in its implementation union.
      if (type === 'message') messages.push(listener);
      else closes.push(listener as (event: unknown) => void);
    },
  };
}

/** Explicit raw-JSON compatibility. The physical socket's send stays side-effect free on refusal. */
export function legacySocket(socket: Socket): Socket {
  return {
    send(frame): void {
      if (frame.length > 1024 * 1024 || utf8Length(frame) > 1024 * 1024)
        throw new Error('encoded message exceeds legacy allowance');
      socket.send(frame);
    },
    close: () => socket.close(),
    addEventListener: socket.addEventListener.bind(socket),
  };
}
