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
  it('holds a request past the window until a slot frees', async () => {
    const wire = new Wire();
    const { connection, ready } = opening(wire, 2);
    await ready;
    const first = connection.ask('project.list', {});
    const second = connection.ask('project.list', {});
    const third = connection.ask('project.list', {});
    let settled = false;
    const observed = third.then(() => {
      settled = true;
    });
    await flush();
    expect(wire.sent).toHaveLength(3);
    expect(settled).toBe(false);
    wire.answer(1);
    await first;
    await flush();
    expect(wire.sent).toHaveLength(4);
    wire.answer(3);
    await observed;
    wire.answer(2);
    await second;
    connection.close();
  });
  // Guards strand() settling parked callers; not red-before (the old code rejected at once).
  it('settles a parked caller when the connection closes', async () => {
    const wire = new Wire();
    let close: (() => void) | undefined;
    wire.addEventListener = (type, listener) => {
      if (type === 'message') wire.listener = listener;
      else if (type === 'close') close = () => listener({ data: '' });
    };
    const { connection, ready } = opening(wire, 1);
    await ready;
    const first = expect(
      connection.ask('project.list', {}),
    ).rejects.toMatchObject({ code: 'RESPONSE_LOST' });
    const second = expect(
      connection.ask('project.list', {}),
    ).rejects.toMatchObject({ code: 'NOT_SUBMITTED' });
    await flush();
    expect(wire.sent).toHaveLength(2);
    close!();
    await first;
    // The parked caller is settled by the closure rather than left hanging.
    await second;
    expect(wire.sent).toHaveLength(2);
  });
  it('wakes a waiter when a deadline frees the slot', async () => {
    vi.useFakeTimers();
    const wire = new Wire();
    const { connection, ready } = opening(wire, 1);
    await ready;
    const first = expect(
      connection.ask('project.list', {}, { timeoutMs: 1000 }),
    ).rejects.toMatchObject({ code: 'RESPONSE_LOST' });
    const second = connection.ask('project.list', {}, { timeoutMs: 60000 });
    await flush();
    expect(wire.sent).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(1000);
    await first;
    await flush();
    expect(wire.sent).toHaveLength(3);
    wire.answer(2);
    await second;
    connection.close();
  });
  it('wakes a waiter when an in-flight request is aborted', async () => {
    const wire = new Wire();
    const { connection, ready } = opening(wire, 1);
    await ready;
    const controller = new AbortController();
    const first = expect(
      connection.ask('project.list', {}, { signal: controller.signal }),
    ).rejects.toMatchObject({ code: 'RESPONSE_LOST' });
    const second = connection.ask('project.list', {});
    await flush();
    expect(wire.sent).toHaveLength(2);
    controller.abort();
    await first;
    await flush();
    expect(wire.sent).toHaveLength(3);
    wire.answer(2);
    await second;
    connection.close();
  });
  it('sends nothing and fails NOT_SUBMITTED when closed while launch-parked', async () => {
    const wire = new Wire();
    const connection = connect({
      socket: wire,
      dispatchMode: 'concurrent-v1',
      deadline: { milliseconds: 30000, schedule: () => () => undefined },
    });
    const calls = [1, 2, 3, 4].map(() =>
      connection.ask('project.list', {}).then(
        () => 'ok',
        (cause: { code: string }) => cause.code,
      ),
    );
    wire.answer(0, {
      code: 'OK',
      payload: { mode: 'concurrent-v1', maxPending: 2, maxIdBytes: 128 },
    });
    await flush();
    // Two dispatched, two parked behind the depth.
    expect(wire.sent).toHaveLength(3);
    connection.close();
    await flush();
    // The frame count catches a launch pumped while strand() still sees an open socket.
    expect(wire.sent).toHaveLength(3);
    expect(await Promise.all(calls)).toEqual([
      'RESPONSE_LOST',
      'RESPONSE_LOST',
      'NOT_SUBMITTED',
      'NOT_SUBMITTED',
    ]);
  });
  // Red before: the deadline was armed at submission, so a launch-parked request
  // (admitted, waiting behind the negotiated depth) expired at 30s with no frame sent.
  it('does not spend the deadline while launch-parked behind the depth', async () => {
    vi.useFakeTimers();
    const wire = new Wire();
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
    const outcomes: string[] = [];
    const record = (name: string) => (settled: unknown) => {
      outcomes.push(`${name}:${(settled as { code?: string })?.code ?? 'ok'}`);
    };
    const first = connection
      .ask('project.list', {})
      .then(record('first'), record('first'));
    const second = connection
      .ask('project.list', {})
      .then(record('second'), record('second'));
    wire.answer(0, {
      code: 'OK',
      payload: { mode: 'concurrent-v1', maxPending: 1, maxIdBytes: 128 },
    });
    await flush();
    // One in flight; the second is admitted but launch-parked.
    expect(wire.sent).toHaveLength(2);
    // The in-flight request expires; the parked one has not started its clock.
    await vi.advanceTimersByTimeAsync(29000);
    await vi.advanceTimersByTimeAsync(29000);
    expect(outcomes).toEqual(['first:RESPONSE_LOST']);
    await first;
    await flush();
    // The slot freed at 30s, so the parked request launched then and is timed
    // from that moment: 58s elapsed is only 28s of its own budget.
    expect(wire.sent).toHaveLength(3);
    await vi.advanceTimersByTimeAsync(1000);
    expect(outcomes).toEqual(['first:RESPONSE_LOST']);
    await vi.advanceTimersByTimeAsync(2000);
    await second;
    expect(outcomes).toEqual(['first:RESPONSE_LOST', 'second:RESPONSE_LOST']);
    connection.close();
  });
  it('does not spend the deadline while parked for admission', async () => {
    vi.useFakeTimers();
    const wire = new Wire();
    const { connection, ready } = opening(wire, 1);
    await ready;
    const first = connection.ask('project.list', {}, { timeoutMs: 600000 });
    let settled = false;
    const second = connection
      .ask('project.list', {}, { timeoutMs: 1000 })
      .then(() => {
        settled = true;
      });
    await flush();
    await vi.advanceTimersByTimeAsync(5000);
    expect(settled).toBe(false);
    expect(wire.sent).toHaveLength(2);
    wire.answer(1);
    await first;
    await flush();
    expect(wire.sent).toHaveLength(3);
    wire.answer(2);
    await second;
    expect(settled).toBe(true);
    connection.close();
  });
  it('settles launch-parked and admission-parked requests on close, with no timer firing', async () => {
    vi.useFakeTimers();
    const wire = new Wire();
    let close: (() => void) | undefined;
    wire.addEventListener = (type, listener) => {
      if (type === 'message') wire.listener = listener;
      else if (type === 'close') close = () => listener({ data: '' });
    };
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
    // Two before negotiation: at depth 1 the second is launch-parked. Two more
    // after negotiation park for admission.
    const calls = [1, 2].map(() =>
      connection.ask('project.list', {}).then(
        () => 'ok',
        (cause: { code: string }) => cause.code,
      ),
    );
    wire.answer(0, {
      code: 'OK',
      payload: { mode: 'concurrent-v1', maxPending: 1, maxIdBytes: 128 },
    });
    await flush();
    const more = [3, 4].map(() =>
      connection.ask('project.list', {}).then(
        () => 'ok',
        (cause: { code: string }) => cause.code,
      ),
    );
    await flush();
    // Negotiation frame plus one transmitted request.
    expect(wire.sent).toHaveLength(2);
    close!();
    await flush();
    expect(await Promise.all([...calls, ...more])).toEqual([
      'RESPONSE_LOST',
      'NOT_SUBMITTED',
      'NOT_SUBMITTED',
      'NOT_SUBMITTED',
    ]);
    // Nothing may fire or transmit after the settle: no deadline is running.
    await vi.advanceTimersByTimeAsync(60000);
    expect(vi.getTimerCount()).toBe(0);
    expect(wire.sent).toHaveLength(2);
  });
  it('admits parked callers in arrival order', async () => {
    const wire = new Wire();
    const { connection, ready } = opening(wire, 1);
    await ready;
    const calls = [1, 2, 3, 4].map((n) =>
      connection.ask('project.list', { n }),
    );
    await flush();
    for (let n = 1; n <= 3; n++) {
      wire.answer(n);
      await flush();
      expect(wire.sent).toHaveLength(n + 2);
      expect(JSON.parse(wire.sent[n + 1]!).payload).toEqual({ n: n + 1 });
    }
    wire.answer(4);
    await Promise.all(calls);
    connection.close();
  });
  it('wakes a waiter when the physical writer fails', async () => {
    class FailingWire extends Wire {
      fail: (cause: unknown) => void = () => undefined;
      submit(frame: string, submitted: () => void): RequestSend {
        this.sent.push(frame);
        let first = this.sent.length === 2;
        const settled = new Promise<void>((resolve, reject) => {
          if (first) this.fail = reject;
          else resolve();
        });
        first = false;
        if (this.sent.length > 1) submitted();
        return { cancel: () => true, settled };
      }
    }
    const wire = new FailingWire();
    const { connection, ready } = opening(wire, 1);
    await ready;
    const first = expect(
      connection.ask('project.list', {}),
    ).rejects.toMatchObject({ code: 'RESPONSE_LOST' });
    const second = connection.ask('project.list', {});
    await flush();
    expect(wire.sent).toHaveLength(2);
    wire.fail(new Error('writer down'));
    await first;
    await flush();
    expect(wire.sent).toHaveLength(3);
    wire.answer(2);
    await second;
    connection.close();
  });
  // Guards the new parked-abort listener; the old code also rejected, so this is not red-before.
  it('fails a parked request fast when its signal aborts', async () => {
    const wire = new Wire();
    const { connection, ready } = opening(wire, 1);
    await ready;
    const holder = expect(
      connection.ask('project.list', {}),
    ).rejects.toMatchObject({ code: 'RESPONSE_LOST' });
    const controller = new AbortController();
    const parked = expect(
      connection.ask('project.list', {}, { signal: controller.signal }),
    ).rejects.toMatchObject({ code: 'NOT_SUBMITTED' });
    await flush();
    controller.abort();
    await parked;
    expect(wire.sent).toHaveLength(2);
    connection.close();
    await holder;
  });
  it('stops admitting when a renegotiated depth is below what is in flight', async () => {
    const wire = new Wire();
    const connection = connect({
      socket: wire,
      dispatchMode: 'concurrent-v1',
      deadline: { milliseconds: 30000, schedule: () => () => undefined },
    });
    // Four requests are admitted under the pre-negotiation default window.
    const early = [1, 2, 3, 4].map(() => connection.ask('project.list', {}));
    wire.answer(0, {
      code: 'OK',
      payload: { mode: 'concurrent-v1', maxPending: 2, maxIdBytes: 128 },
    });
    await flush();
    // Only the advertised depth is dispatched; the rest wait.
    expect(wire.sent).toHaveLength(3);
    const late = connection.ask('project.list', {});
    await flush();
    expect(wire.sent).toHaveLength(3);
    wire.answer(1);
    await early[0];
    await flush();
    // Three admitted, depth two: still no new admission or dispatch of `late`.
    expect(wire.sent).toHaveLength(4);
    wire.answer(2);
    wire.answer(3);
    await flush();
    wire.answer(4);
    await flush();
    expect(wire.sent).toHaveLength(6);
    wire.answer(5);
    await Promise.all(early);
    await late;
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
    // Cancelled requests still hold their slots, so the next caller is held.
    const last = connection.ask('project.list', {});
    await flush();
    expect(wire.sent).toHaveLength(64);
    wire.transfers.forEach((transfer) => transfer.release());
    await flush();
    expect(wire.sent).toHaveLength(65);
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
