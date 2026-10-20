import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  connect,
  type Arrival,
  type RequestSend,
  type Socket,
} from './connection.ts';

class Wire implements Socket {
  readonly sent: string[] = [];
  closed = false;
  listener?: (event: Arrival) => void;
  send(frame: string): void {
    this.sent.push(frame);
  }
  close(): void {
    this.closed = true;
  }
  addEventListener(type: string, listener: (event: Arrival) => void): void {
    if (type === 'message') this.listener = listener;
  }
  answer(index: number, payload: unknown = { code: 'OK', payload: [] }): void {
    const frame: unknown = JSON.parse(this.sent[index]!);
    if (frame === null || typeof frame !== 'object')
      throw new Error('Missing request');
    this.listener?.({ data: JSON.stringify({ ...frame, payload }) });
  }
}
const flush = async (): Promise<void> => {
  for (let n = 0; n < 12; n++) await Promise.resolve();
};
function opening(wire: Wire, maximum = 64) {
  const connection = connect({
    socket: wire,
    dispatchMode: 'concurrent-v1',
    deadline: {
      milliseconds: 30000,
      schedule: (callback, milliseconds) => {
        const timer = setTimeout(callback, milliseconds);
        return () => clearTimeout(timer);
      },
    },
  });
  const ready = connection.ready();
  wire.answer(0, {
    code: 'OK',
    payload: { mode: 'concurrent-v1', maxPending: maximum, maxIdBytes: 128 },
  });
  return { connection, ready };
}
afterEach(() => vi.useRealTimers());
describe('concurrent dispatch lifecycle', () => {
  it('selects once before application traffic and resolves B while A is held', async () => {
    const wire = new Wire();
    const { connection, ready } = opening(wire);
    await ready;
    const first = connection.ask('project.list', {});
    const second = connection.ask('project.list', {});
    let finished = false;
    const observed = first.then(() => {
      finished = true;
    });
    await flush();
    expect(wire.sent).toHaveLength(3);
    wire.answer(2);
    await expect(second).resolves.toEqual({ code: 'OK', payload: [] });
    expect(finished).toBe(false);
    wire.answer(1);
    await observed;
    await connection.ready();
    expect(wire.sent).toHaveLength(3);
    connection.close();
  });
  it('fails negotiation without submitting or replaying application requests', async () => {
    const wire = new Wire();
    const connection = connect({
      socket: wire,
      dispatchMode: 'concurrent-v1',
      deadline: { milliseconds: 100, schedule: () => () => undefined },
    });
    const asked = expect(
      connection.ask('project.list', {}),
    ).rejects.toMatchObject({ code: 'NOT_SUBMITTED' });
    wire.answer(0, { code: 'NOT_FOUND' });
    await asked;
    expect(wire.sent).toHaveLength(1);
    expect(wire.closed).toBe(true);
  });
  it('uses explicit per-call deadlines, and ignores detached late replies', async () => {
    vi.useFakeTimers();
    const wire = new Wire();
    const { connection, ready } = opening(wire);
    await ready;
    const first = expect(
      connection.ask('project.list', {}, { timeoutMs: 60000 }),
    ).rejects.toMatchObject({ code: 'RESPONSE_LOST' });
    await flush();
    await vi.advanceTimersByTimeAsync(30000);
    const second = expect(
      connection.ask('project.list', {}),
    ).rejects.toMatchObject({ code: 'RESPONSE_LOST' });
    await flush();
    await vi.advanceTimersByTimeAsync(30000);
    await first;
    // The second has its own 30s deadline and detached independently.
    await second;
    wire.answer(1);
    wire.answer(2);
    expect(wire.sent).toHaveLength(3);
    connection.close();
  });
  it('honors a smaller negotiated capacity', async () => {
    const wire = new Wire();
    const { connection, ready } = opening(wire, 1);
    await ready;
    const first = connection.ask('project.list', {});
    await expect(connection.ask('project.list', {})).rejects.toMatchObject({
      code: 'NOT_SUBMITTED',
    });
    await flush();
    expect(wire.sent).toHaveLength(2);
    wire.answer(1);
    await first;
    connection.close();
  });
  it('retains slots after cancellation until physical writer ownership settles', async () => {
    class QueuedWire extends Wire {
      readonly transfers: { release: () => void; submitted: () => void }[] = [];
      submit(frame: string, submitted: () => void): RequestSend {
        this.sent.push(frame);
        let release: () => void = () => undefined;
        const settled = new Promise<void>((resolve) => {
          release = resolve;
        });
        this.transfers.push({ release, submitted });
        return { cancel: () => true, settled };
      }
    }
    const wire = new QueuedWire();
    const connection = connect({
      socket: wire,
      deadline: { milliseconds: 30000, schedule: () => () => undefined },
    });
    const controllers = Array.from({ length: 64 }, () => new AbortController());
    const calls = controllers.map((control) =>
      expect(
        connection.ask('project.list', {}, { signal: control.signal }),
      ).rejects.toMatchObject({ code: 'NOT_SUBMITTED' }),
    );
    controllers.forEach((control) => control.abort());
    await Promise.all(calls);
    await expect(connection.ask('project.list', {})).rejects.toMatchObject({
      code: 'NOT_SUBMITTED',
    });
    expect(wire.sent).toHaveLength(64);
    wire.transfers.forEach((transfer) => transfer.release());
    await flush();
    const last = connection.ask('project.list', {});
    wire.answer(64);
    wire.transfers[64]!.release();
    await last;
    connection.close();
  });
  it('cancels a waiter during selection without sending application bytes', async () => {
    const wire = new Wire();
    const connection = connect({
      socket: wire,
      dispatchMode: 'concurrent-v1',
      deadline: { milliseconds: 30000, schedule: () => () => undefined },
    });
    const controller = new AbortController();
    const stopped = expect(
      connection.ask('project.list', {}, { signal: controller.signal }),
    ).rejects.toMatchObject({ code: 'NOT_SUBMITTED' });
    controller.abort();
    await stopped;
    wire.answer(0, {
      code: 'OK',
      payload: { mode: 'concurrent-v1', maxPending: 64, maxIdBytes: 128 },
    });
    await connection.ready();
    await flush();
    expect(wire.sent).toHaveLength(1);
    connection.close();
  });
});

it('shares opening when an injected send synchronously publishes a push', async () => {
  class PushWire extends Wire {
    override send(frame: string): void {
      super.send(frame);
      this.listener?.({
        data: JSON.stringify({ kind: 'inbox.changed', unread: 1 }),
      });
    }
  }
  const wire = new PushWire();
  let again: Promise<void> | undefined;
  const connection = connect({
    socket: wire,
    dispatchMode: 'concurrent-v1',
    deadline: { milliseconds: 1000, schedule: () => () => undefined },
    onPush: () => {
      again = connection.ready();
    },
  });
  const first = connection.ready();
  expect(again).toBe(first);
  expect(wire.sent).toHaveLength(1);
  wire.answer(0, {
    code: 'OK',
    payload: { mode: 'concurrent-v1', maxPending: 64, maxIdBytes: 128 },
  });
  await first;
  connection.close();
});

it('cancels a transfer handle created during synchronous encoder cancellation', async () => {
  const controller = new AbortController();
  let cancelled = false;
  class ReentrantWire extends Wire {
    submit(): RequestSend {
      controller.abort();
      return {
        cancel: () => {
          cancelled = true;
          return true;
        },
        settled: Promise.resolve(),
      };
    }
  }
  const connection = connect({ socket: new ReentrantWire() });
  await expect(
    connection.ask('project.list', {}, { signal: controller.signal }),
  ).rejects.toMatchObject({ delivery: 'NOT_SUBMITTED' });
  expect(cancelled).toBe(true);
  connection.close();
});

it('closeAsync shares drain ownership and waits for a detached physical buffer', async () => {
  let release!: () => void;
  const settled = new Promise<void>((resolve) => {
    release = resolve;
  });
  class PhysicalWire extends Wire {
    submit(_frame: string, submitted: () => void): RequestSend {
      submitted();
      return { cancel: () => false, settled };
    }
  }
  const wire = new PhysicalWire();
  const connection = connect({ socket: wire });
  const failed = expect(
    connection.ask('project.list', {}),
  ).rejects.toMatchObject({ delivery: 'UNKNOWN' });
  const first = connection.closeAsync();
  const second = connection.closeAsync();
  expect(first).toBe(second);
  let drained = false;
  const observer = first.then(() => {
    drained = true;
  });
  await failed;
  expect(drained).toBe(false);
  expect(wire.closed).toBe(true);
  release();
  await observer;
  await second;
  expect(drained).toBe(true);
});
