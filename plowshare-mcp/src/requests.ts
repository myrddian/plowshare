import type { Backend } from './adapter.js';
import { prepare } from './protocol.js';
import type { Response } from './protocol.js';

export interface McpRequests {
  /** Admission is synchronous; replies are owned by the serialized writer. */
  submit(line: string): void;
  /** Stop admission, detach requests and drain physical work/output to the deadline. */
  close(): Promise<void>;
  /** EOF permits admitted calls to finish before closing the shared session. */
  drain(): Promise<void>;
}
export interface McpRequestOptions {
  readonly timeoutMs: number;
  readonly backend: (signal: AbortSignal, submitted: () => void) => Backend;
  readonly write: (record: string) => Promise<void>;
  readonly fatal: (failure: Error) => void;
  readonly maxPending?: number;
  readonly maxInputBytes?: number;
  readonly maxResponseBytes?: number;
}
interface Task {
  readonly control: AbortController;
  readonly cancel: () => void;
}

/** Event-loop confined state; no async callback mutates a shared foreign session.
 * Capacity remains reserved until both physical execution and output settle. In
 * particular, detaching a timed-out root cannot let a later run cross that root.
 */
export function mcpRequests(options: McpRequestOptions): McpRequests {
  const capacity = options.maxPending ?? 16;
  const inputLimit = options.maxInputBytes ?? 16 * 1024 * 1024;
  const responseLimit = options.maxResponseBytes ?? 8 * 1024 * 1024;
  for (const value of [capacity, inputLimit, responseLimit, options.timeoutMs])
    if (!Number.isSafeInteger(value) || value < 1)
      throw new Error('Invalid MCP request limits');
  if (
    capacity > 64 ||
    inputLimit > 64 * 1024 * 1024 ||
    responseLimit > 320 * 1024 * 1024 ||
    capacity * responseLimit > 1024 * 1024 * 1024 ||
    options.timeoutMs > 2147483647
  )
    throw new Error('MCP request limits exceed the process budget');
  const active = new Map<string | number, Task>();
  const work = new Set<Promise<void>>();
  let closing = false,
    retained = 0;
  let writer = Promise.resolve();
  let presence = Promise.resolve();
  let initialized = false,
    initializing = false,
    initializationComplete = false,
    initializedNotice = false,
    outputFailed = false;
  let initializeOutput: Promise<void> | undefined;
  const error = (id: unknown, code: number, message: string): Response => ({
    jsonrpc: '2.0',
    id,
    error: { code, message },
  });

  function write(response: Response): Promise<void> {
    let encoded = JSON.stringify(response) + '\n';
    if (Buffer.byteLength(encoded) > responseLimit)
      encoded =
        JSON.stringify(
          error(
            response.id,
            -32603,
            'MCP response exceeded the byte limit; operation completion may be unknown; no request was repeated',
          ),
        ) + '\n';
    const next = writer.then(async () => {
      if (outputFailed) throw new Error('MCP output is closed');
      let timer: ReturnType<typeof setTimeout> | undefined;
      try {
        await Promise.race([
          options.write(encoded),
          new Promise<never>((_, reject) => {
            timer = setTimeout(
              () => reject(new Error('MCP output deadline exceeded')),
              options.timeoutMs,
            );
          }),
        ]);
      } finally {
        if (timer) clearTimeout(timer);
      }
    });
    // A broken writer is terminal. Never silently release a slot and accumulate
    // subsequent owed replies behind an output stream that cannot make progress.
    writer = next.catch((failure: unknown) => {
      if (outputFailed) return;
      closing = true;
      outputFailed = true;
      for (const task of active.values()) task.cancel();
      options.fatal(
        failure instanceof Error ? failure : new Error('MCP output failed'),
      );
    });
    return next;
  }

  function admit(line: string): void {
    if (closing) throw new Error('MCP is draining; nothing was submitted');
    const bytes = Buffer.byteLength(line);
    if (bytes > inputLimit - retained)
      throw new Error('MCP input capacity exceeded; nothing was submitted');
    const call = prepare(line);
    if (call.kind === 'silent') return;
    if (call.kind === 'initialized') {
      if (initializing) {
        initializedNotice = true;
        if (initializationComplete) initialized = true;
      }
      return;
    }
    if (call.kind === 'cancel') {
      active.get(call.id)?.cancel();
      return;
    }
    // Duplicate IDs cannot receive an overload reply: that could settle the
    // original caller with another request's refusal. Close the stream instead.
    if (call.kind === 'request' && active.has(call.id))
      throw new Error(
        'Duplicate active MCP request ID; no duplicate was submitted',
      );
    // Reserve one output record even for protocol errors/overload responses. A
    // peer flooding control errors cannot create an unbounded writer queue.
    if (work.size >= capacity) {
      // A small separately bounded control allowance reports ordinary overload.
      // A peer that also saturates this writer allowance is a terminal stream failure.
      if (work.size >= capacity + 4)
        throw new Error(
          'MCP response capacity exceeded; nothing was submitted',
        );
      const id = call.kind === 'request' ? call.id : null;
      const rejected = write(
        error(
          id,
          -32000,
          'MCP request capacity exceeded; nothing was submitted',
        ),
      )
        .catch(() => {})
        .finally(() => {
          work.delete(rejected);
        });
      work.add(rejected);
      return;
    }
    retained += bytes;
    if (call.kind === 'reply') {
      const task = write(call.response)
        .catch(() => {})
        .finally(() => {
          retained -= bytes;
          work.delete(task);
        });
      work.add(task);
      return;
    }
    const control = new AbortController();
    let submitted = false,
      settled = false;
    let respond!: (reply: Response) => void;
    const result = new Promise<Response>((resolve) => {
      respond = resolve;
    });
    const settle = (reply: Response) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      respond(reply);
    };
    const cancel = () => {
      control.abort();
      settle(
        error(
          call.id,
          -32800,
          submitted
            ? 'Call detached; completion is unknown; no mutation was replayed or cancelled'
            : 'Call cancelled before submission; nothing was submitted',
        ),
      );
    };
    const timer = setTimeout(cancel, options.timeoutMs);
    active.set(call.id, { control, cancel });
    let execute: Promise<void>;
    const run = async () => {
      if (control.signal.aborted) return;
      try {
        if (call.method === 'initialize') {
          if (initializing) {
            settle(error(call.id, -32600, 'MCP is already initialized'));
            return;
          }
          initializing = true;
        } else if (!['ping', 'tools/list'].includes(call.method)) {
          // Pipelined calls may wait behind initialize, but no tool can run
          // before the initialization reply has crossed the output writer.
          if (initializing && !initializationComplete) await initializeOutput;
          if (control.signal.aborted) return;
          if (!initialized) {
            settle(
              error(
                call.id,
                -32600,
                'MCP initialization must complete before tool calls',
              ),
            );
            return;
          }
        }
        const reply = await call.execute(
          options.backend(control.signal, () => {
            submitted = true;
          }),
        );
        if (reply) settle(reply);
        else settle(error(call.id, -32603, 'Request produced no response'));
      } catch (failure) {
        settle(
          error(
            call.id,
            -32603,
            failure instanceof Error ? failure.message : 'MCP request failed',
          ),
        );
      }
    };
    if (call.presence) {
      // Reserve order on receipt, before either task can await filesystem/network
      // work. The physical tail outlives caller cancellation and protects roots.
      execute = presence.then(run);
      presence = execute;
    } else execute = run();
    const output = result.then(write).catch(() => {});
    if (call.method === 'initialize' && initializeOutput === undefined) {
      initializeOutput = output.then(() => {
        initializationComplete = true;
        initialized = initializedNotice && !outputFailed;
      });
    }
    const owned = Promise.all([execute, output])
      .then(() => {})
      .finally(() => {
        clearTimeout(timer);
        active.delete(call.id);
        retained -= bytes;
        work.delete(owned);
      });
    work.add(owned);
  }
  let draining: Promise<void> | undefined;
  let drained = false,
    closeStarted = false;
  let deadline: ReturnType<typeof setTimeout> | undefined;
  let rejectDrain: ((failure: Error) => void) | undefined;
  const expired = () => {
    const failure = new Error(
      'MCP drain deadline exceeded; remaining operation completion is unknown; no request was replayed',
    );
    for (const task of active.values()) task.cancel();
    rejectDrain?.(failure);
  };
  function drain(): Promise<void> {
    if (draining) return draining;
    closing = true;
    draining = (async () => {
      try {
        await Promise.race([
          Promise.all([...work]).then(() => writer),
          new Promise<never>((_, reject) => {
            rejectDrain = reject;
            deadline = setTimeout(expired, options.timeoutMs);
          }),
        ]);
      } catch (failure) {
        options.fatal(
          failure instanceof Error ? failure : new Error('MCP drain failed'),
        );
        throw failure;
      } finally {
        drained = true;
        if (deadline) clearTimeout(deadline);
      }
    })();
    return draining;
  }
  return {
    submit: admit,
    drain,
    close: async () => {
      closing = true;
      for (const task of active.values()) task.cancel();
      const pending = drain();
      // Signal-driven shutdown shares the EOF drain, but shortens its remaining
      // wait. A non-cooperative root cannot keep stdio shutdown open forever.
      if (!drained && !closeStarted) {
        closeStarted = true;
        if (deadline) clearTimeout(deadline);
        deadline = setTimeout(expired, Math.min(options.timeoutMs, 5000));
      }
      await pending.catch(() => {});
    },
  };
}
