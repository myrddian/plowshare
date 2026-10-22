import {
  actingOptions,
  type RequestOptions,
  type RequestSignal,
} from './acting.ts';
import { utf8Length } from './files.ts';
import { CURRENT_VERSION, asking, isAnswer, outcomeIn } from './envelope.ts';
import type { Outcome } from './envelope.ts';
import { PacketTransportFault } from './packets.ts';

/**
 * One socket, many questions, and the correlation that keeps their answers
 * apart.
 *
 * <h2>The socket is injected, and its type is declared here rather than
 * imported</h2>
 *
 * <p>The plan says the WebSocket is injected rather than reached for, which is
 * what makes this testable with a fake and is the same reasoning the client
 * design applies to `fetch`. The consequence, settled as a ruling before this
 * task ran: <b>an injected dependency does not need the platform's global type
 * at all — it needs a minimal structural interface describing only what is
 * actually called.</b> See {@link Socket}, which is four members.
 *
 * <p>That is stronger than naming `WebSocket` in three ways. It keeps
 * `src/binding/tsconfig.json`'s `types: []` intact, so the compiler stays the
 * first line of defence and a `node:` import in this directory remains a
 * TS2307 rather than something `@types/node` would quietly bless. It makes the
 * fake in `connection.test.ts` trivial, because a fake only has to implement
 * what the interface names. And it writes down the surface actually depended
 * on, which a platform type does not.
 *
 * <h2>What this class does not do</h2>
 *
 * <p>No reconnection, no backoff, no queueing of asks made before the socket is
 * open: `connect` is handed a socket and speaks on it. Composition — sign in,
 * refresh, ticket, open, hand the socket here — is task 5's `openSocket`, and
 * putting any of it here would make this module know about auth, which spec §3
 * is explicit that the protocol does not.
 */

/**
 * A message arriving from the socket.
 *
 * One field, because one field is what is read. A browser's `MessageEvent` and
 * Node's global `WebSocket` both carry a great deal more; none of it is used
 * here, and naming it would be a claim this module cannot keep.
 */
export interface Arrival {
  readonly data: unknown;
}

/**
 * <b>The whole of the socket surface this module depends on.</b>
 *
 * <p>Four members: {@link send}, {@link close}, and the two subscriptions. A
 * real `WebSocket` — the browser's, or Node's global since v22 — satisfies this
 * structurally, and so does the seventy-line fake in the test. What is
 * deliberately <b>omitted</b> is everything this module never calls:
 * `readyState`, `bufferedAmount`, `binaryType`, `extensions`, `protocol`,
 * `url`, the `on*` handler properties, `dispatchEvent`, `removeEventListener`,
 * and the `error` and `open` events.
 *
 * <p>Two of those omissions are worth stating rather than merely noting.
 * <b>`readyState` is absent because this module never asks a socket what it is
 * doing</b> — it learns that the connection is gone by being told (`close`),
 * which is the only account that is true at the moment it is read; a
 * `readyState` checked before a `send` is stale by the time the frame leaves.
 * <b>`error` is absent because a WebSocket error is always followed by a
 * close</b>, and it is the close that has to fail the outstanding asks; a
 * client that reacted to both would fail them twice.
 *
 * <p>`removeEventListener` is absent because a `Connection` lives exactly as
 * long as its socket: there is no path here that unsubscribes and keeps using
 * it. Cost if that changes, or if one of the other omissions turns out to be
 * needed: one member added to this interface and to the fake, found the first
 * time the real socket is driven.
 */
export interface Socket {
  /** One frame, as text. This client sends no binary. */
  send(frame: string): void;
  /** Optional queued writer. Settlement retains byte ownership even after caller detachment. */
  submit?(frame: string, submitted: () => void): RequestSend;

  /** Hangs up. Called by {@link Connection.close}. */
  close(): void;

  addEventListener(type: 'message', listener: (event: Arrival) => void): void;
  addEventListener(type: 'close', listener: (event: unknown) => void): void;
}
export interface RequestSend {
  /** True only when cancellation proves that no packet write can occur. */
  cancel(): boolean;
  readonly settled: Promise<void>;
}

/** What {@link connect} needs, and what it will accept on top of that. */
export interface ConnectOptions {
  /** The socket, already open. See {@link Socket}. */
  readonly socket: Socket;
  /** Low-level injected transports default to ordered; public SDK opening selects concurrent-v1. */
  readonly dispatchMode?: 'concurrent-v1' | 'ordered';
  readonly maxPendingBytes?: number;
  /** Platform-supplied deadline; cancellation removes the pending request, never replays it. */
  readonly deadline?: {
    readonly milliseconds: number;
    readonly schedule: (
      expired: () => void,
      milliseconds: number,
    ) => () => void;
  };

  /**
   * Every frame that is not an answer to something this client asked.
   *
   * A bare `JobEvent` today — `{ job, kind, agent?, tool?, ending?, … }` —
   * handed on as `unknown` rather than typed here, because giving it a shape
   * would put a piece of the job model in the wire module, and interpreting
   * an event is `logic/`'s work (task 6's `followed`).
   */
  readonly onPush?: (push: unknown) => void;

  /**
   * The socket went away, and every ask outstanding has already been failed.
   *
   * <h2>Why an outstanding ask is not enough, which is what a whole-plan
   * review corrected</h2>
   *
   * <p>The `close` listener below has always stranded the waiting map, so a
   * caller sitting in `await ask(…)` learns about a close by that promise
   * rejecting. The assumption underneath is that a caller who cares is always
   * inside an ask — and for this protocol that is exactly false. `agent.run`
   * answers `ACCEPTED` in milliseconds and the run itself is <b>minutes</b>,
   * every one of them spent with no frame outstanding at all, waiting on a
   * push. A close in that window fails nothing, because nothing is waiting,
   * and the caller waits for an event that can no longer arrive.
   *
   * <p>So this is the signal for the other half of a caller's life. A proxy's
   * idle cutoff, a laptop sleeping, a deploy, a restart: all of them land
   * here, and none of them land on an ask.
   *
   * <h2>After the strand, and once</h2>
   *
   * <p>It is called <b>after</b> `strand`, which is a claim about state
   * rather than about scheduling, and the difference is worth spelling out
   * because a reader will assume the other one. By the time this runs every
   * outstanding ask has been rejected and the connection is already closed,
   * so a listener that tries to ask something is refused by `ask`'s own guard
   * instead of left waiting on a dead socket. What it is <b>not</b> is
   * ordered ahead of those callers resuming: a rejection resumes its awaiter
   * in a microtask and this runs synchronously inside the close listener, so
   * a listener here runs <i>before</i> any `await ask(…)` continuation. That
   * order is pinned in `connection.test.ts` rather than described.
   *
   * <p>And once, because it is a `close` subscription and a socket closes
   * once — there is no path here that re-opens one.
   *
   * <p><b>{@link Connection.close} does not call it.</b> A caller that hung up
   * knows it hung up; telling it so would turn every ordinary end of a
   * session into a report that the connection was lost.
   */
  readonly onClose?: () => void;
}

/** One socket's worth of question-and-answer. */
export interface Connection {
  /**
   * Sends a frame and resolves with the server's answer to <b>that</b> frame.
   *
   * @param type a dotted frame type, from the server's `FrameTypes`
   * @param payload the frame's own data; `{}` when omitted
   * @returns the {@link Outcome}, whatever its code — a `CONFLICT` resolves
   *     like an `OK` does, because a refusal is an answer. The promise is
   *     rejected only when there is <b>no</b> answer to give: the socket
   *     closed, or a frame arrived that this build cannot read as one
   */
  ask(
    type: string,
    payload?: unknown,
    options?: RequestOptions,
  ): Promise<Outcome>;

  /** Hangs up, failing every ask still outstanding. */
  close(): void;
}

/** One outstanding question. */
interface Waiting {
  readonly type: string;
  readonly answered: (reply: Reply) => void;
  readonly failed: (trouble: Error) => void;
  cancelDeadline?: () => void;
  cancelAbort?: () => void;
  cancelSend?: () => boolean;
  submitted: boolean;
  terminal: boolean;
  transferDone: boolean;
  readonly release: () => void;
}

/** Complete envelope and checked outcome, including opaque future fields in raw. */
export interface Reply {
  readonly outcome: Outcome;
  readonly raw: Readonly<Record<string, unknown>>;
}
export interface Connected extends Connection {
  /** Acknowledged dispatch negotiation, with no application request replay or fallback. */
  ready(): Promise<void>;
  /** Close and observe release of every transport-owned request/physical buffer. */
  closeAsync(): Promise<void>;
  request(
    type: string,
    payload?: unknown,
    options?: RequestOptions,
  ): Promise<Reply>;
}

/** Delivery state for recovery without replaying a mutation. */
export class ConnectionFault extends Error {
  readonly code: 'NOT_SUBMITTED' | 'RESPONSE_LOST' | 'INVALID_ENVELOPE';
  constructor(
    code: ConnectionFault['code'],
    message: string,
    options?: ErrorOptions,
  ) {
    super(message, options);
    this.code = code;
  }
  get delivery(): 'NOT_SUBMITTED' | 'UNKNOWN' | 'INVALID_RESPONSE' {
    return this.code === 'RESPONSE_LOST'
      ? 'UNKNOWN'
      : this.code === 'INVALID_ENVELOPE'
        ? 'INVALID_RESPONSE'
        : 'NOT_SUBMITTED';
  }
}

/**
 * Speaks frames on an already-open socket.
 *
 * <h2>Correlation is by `id`, and the id means nothing to anybody else</h2>
 *
 * <p>Spec §3.1: `id` is client-generated and correlates a response to its
 * request. IDs remain unique for the entire physical connection generation,
 * which a counter gives without reaching for a
 * `crypto` this project has no types for and no need of. Nothing reads it but
 * the map below.
 */
export function connect(options: ConnectOptions): Connected {
  const deadline = options.deadline;
  const capacity = options.maxPendingBytes ?? 640 * 1024 * 1024;
  if (
    deadline !== undefined &&
    (!Number.isFinite(deadline.milliseconds) || deadline.milliseconds <= 0)
  )
    throw new Error('a positive request deadline is required');
  if (!Number.isSafeInteger(capacity) || capacity < 1)
    throw new Error('a positive pending byte limit is required');
  if (
    options.dispatchMode !== undefined &&
    !['concurrent-v1', 'ordered'].includes(options.dispatchMode)
  )
    throw new Error('choose an explicit dispatch mode');
  const concurrent = options.dispatchMode === 'concurrent-v1';
  if (concurrent && deadline === undefined)
    throw new Error('concurrent dispatch requires a deadline scheduler');
  const socket = options.socket;
  const waiting = new Map<string, Waiting>();
  let issued = 0,
    admitted = 0,
    held = 0,
    maximum = 64,
    dispatched = 0;
  /**
   * Callers parked until a request slot frees (backpressure, not failure). FIFO.
   * An entry is granted its slot (`admitted` already incremented) when resolved,
   * so no later synchronous caller can steal it. Not yet in `waiting`, hence
   * `strand()` must settle these itself.
   */
  const slotWaiters: {
    granted: () => void;
    refused: (cause: ConnectionFault) => void;
    cancelAbort?: () => void;
  }[] = [];
  /**
   * Admitted requests whose launch is parked behind the negotiated dispatch
   * depth, FIFO. Terminal entries are skipped; they release through `waiting`.
   */
  const launchWaiters: { asker: Waiting; proceed: () => void }[] = [];
  /**
   * Grants freed capacity to parked work. Runs from every slot release and after
   * negotiation changes `maximum`. Conditions are strict `<`, so a depth below
   * what is already in flight admits nothing until in-flight work drains below it.
   */
  function pump(): void {
    // Never launch once closed: strand() runs release() (hence pump) for each
    // failed request while the socket is still open, and a frame sent then would
    // reach the server after its caller was told it was not submitted.
    while (open && launchWaiters.length > 0 && dispatched < maximum) {
      const next = launchWaiters.shift();
      if (next !== undefined && !next.asker.terminal) next.proceed();
    }
    while (slotWaiters.length > 0 && admitted < maximum) {
      const next = slotWaiters.shift();
      if (next === undefined) break;
      next.cancelAbort?.();
      admitted++;
      next.granted();
    }
  }
  let open = true,
    callerClosed = false;
  let opening: Promise<void> | undefined;
  let stranding = false;
  let drained!: () => void;
  const drain = new Promise<void>((resolve) => {
    drained = resolve;
  });
  const checkDrained = (): void => {
    if (!open && !stranding && admitted === 0) drained();
  };

  function releaseIfSettled(asker: Waiting): void {
    if (asker.terminal && asker.transferDone) asker.release();
  }
  function terminate(id: string, asker: Waiting): boolean {
    if (asker.terminal) return false;
    asker.terminal = true;
    if (waiting.get(id) === asker) waiting.delete(id);
    asker.cancelDeadline?.();
    asker.cancelAbort?.();
    releaseIfSettled(asker);
    return true;
  }
  function fail(id: string, asker: Waiting, trouble: Error): void {
    if (terminate(id, asker)) asker.failed(trouble);
  }
  function strand(why: string, cause?: PacketTransportFault): void {
    open = false;
    stranding = true;
    // Launch-parked entries are also in `waiting` and fail below; drop them so
    // nothing can transmit them.
    launchWaiters.length = 0;
    // Nothing from a parked caller was sent, so it is safely NOT_SUBMITTED.
    for (const parked of slotWaiters.splice(0)) {
      parked.cancelAbort?.();
      parked.refused(
        new ConnectionFault(
          'NOT_SUBMITTED',
          why,
          cause ? { cause } : undefined,
        ),
      );
    }
    for (const [id, asker] of [...waiting]) {
      asker.cancelSend?.();
      fail(
        id,
        asker,
        new ConnectionFault(
          asker.submitted ? 'RESPONSE_LOST' : 'NOT_SUBMITTED',
          why,
          cause ? { cause } : undefined,
        ),
      );
    }
    stranding = false;
    checkDrained();
  }
  socket.addEventListener('close', (event) => {
    const wasOpen = open;
    // Packet transport closes carry a fixed diagnostic, rather than an untrusted
    // codec/JSON exception. Preserve it for pending calls without replaying work.
    const failure = event instanceof PacketTransportFault ? event : undefined;
    strand(
      failure
        ? `the packet transport closed: ${failure.reason}`
        : 'the socket closed before this frame was answered',
      failure,
    );
    if (wasOpen && !callerClosed) options.onClose?.();
  });
  socket.addEventListener('message', (event) => {
    if (typeof event.data !== 'string') return;
    let frame: unknown;
    try {
      frame = JSON.parse(event.data);
    } catch {
      return;
    }
    if (!isAnswer(frame) || frame['id'] === null) {
      if (!isAnswer(frame) || frame['protocol_version'] === CURRENT_VERSION) {
        try {
          options.onPush?.(frame);
        } catch {
          /* Notification consumers cannot strand exchanges. */
        }
      }
      return;
    }
    const id = frame['id'];
    if (typeof id !== 'string') return;
    const asker = waiting.get(id);
    if (asker === undefined) return;
    try {
      if (
        frame['protocol_version'] !== CURRENT_VERSION ||
        frame['type'] !== asker.type
      )
        throw new Error(
          `response type or protocol version does not match ${CURRENT_VERSION}: received ${String(frame['protocol_version'])}`,
        );
      const reply = { outcome: outcomeIn(frame['payload']), raw: frame };
      if (terminate(id, asker)) asker.answered(reply);
    } catch (cause) {
      fail(
        id,
        asker,
        new ConnectionFault(
          'INVALID_ENVELOPE',
          cause instanceof Error
            ? cause.message
            : 'unreadable response envelope; outcome is unknown',
          { cause },
        ),
      );
    }
  });

  /**
   * Resolves once a request slot is reserved for the caller (the caller has
   * already tried the synchronous path in `exchange`). Waiting is flow
   * control: it ends by grant, or by abort/closure, never by a capacity error.
   */
  function reserveSlot(signal: RequestSignal | undefined): Promise<void> {
    return new Promise<void>((granted, refused) => {
      const parked: (typeof slotWaiters)[number] = { granted, refused };
      if (signal !== undefined) {
        const aborted = (): void => {
          const at = slotWaiters.indexOf(parked);
          if (at >= 0) slotWaiters.splice(at, 1);
          refused(
            new ConnectionFault(
              'NOT_SUBMITTED',
              'request cancelled before submission',
            ),
          );
        };
        signal.addEventListener('abort', aborted, { once: true });
        parked.cancelAbort = () => signal.removeEventListener('abort', aborted);
      }
      slotWaiters.push(parked);
    });
  }

  /** Admission includes queued writers and negotiated opening waiters, not just sent IDs. */
  function exchange(
    type: string,
    payload: unknown,
    input: RequestOptions | undefined,
    control: boolean,
  ): Promise<Reply> {
    let requestOptions: RequestOptions;
    try {
      requestOptions = actingOptions(input);
      if (!control && type === 'transport.dispatch')
        throw new ConnectionFault(
          'NOT_SUBMITTED',
          'dispatch selection is owned by connection opening',
        );
      if (!open)
        throw new ConnectionFault('NOT_SUBMITTED', 'this connection is closed');
      if (requestOptions.signal?.aborted)
        throw new ConnectionFault(
          'NOT_SUBMITTED',
          'request cancelled before submission',
        );
      if (requestOptions.timeoutMs !== undefined && deadline === undefined)
        throw new Error('request timeouts require a deadline scheduler');
    } catch (cause) {
      return Promise.reject(
        cause instanceof Error
          ? cause
          : new Error('Invalid request before submission', { cause }),
      );
    }
    const options = requestOptions;
    // Reserve before encoding: caller DTO hooks can synchronously reenter this transport.
    if (admitted < maximum && slotWaiters.length === 0) {
      admitted++;
      return submit(type, payload, options, control);
    }
    return reserveSlot(options.signal).then(() =>
      submit(type, payload, options, control),
    );
  }

  /** Sends a request whose admission slot is already reserved; frees it on any pre-launch failure. */
  function submit(
    type: string,
    payload: unknown,
    requestOptions: RequestOptions,
    control: boolean,
  ): Promise<Reply> {
    let frame: string, id: string, bytes: number;
    try {
      if (!Number.isSafeInteger(issued + 1))
        throw new Error('request identity space exhausted');
      id = String(++issued);
      frame = JSON.stringify(asking(id, type, payload, requestOptions));
      bytes = utf8Length(frame);
      if (bytes > capacity - held)
        throw new ConnectionFault(
          'NOT_SUBMITTED',
          'pending request byte capacity exceeded before submission',
        );
    } catch (cause) {
      admitted--;
      pump();
      checkDrained();
      return Promise.reject(
        cause instanceof Error
          ? cause
          : new Error('Invalid request before submission', { cause }),
      );
    }
    held += bytes;
    return new Promise<Reply>((answered, failed) => {
      let released = false,
        launched = false;
      const asker: Waiting = {
        type,
        answered,
        failed,
        submitted: false,
        terminal: false,
        transferDone: true,
        release: () => {
          if (released) return;
          released = true;
          admitted--;
          if (launched) dispatched--;
          held -= bytes;
          checkDrained();
          // Every ending funnels here, so a failure frees a slot like a reply.
          pump();
        },
      };
      waiting.set(id, asker);
      const detached = (why: string): void => {
        asker.cancelSend?.();
        fail(
          id,
          asker,
          new ConnectionFault(
            asker.submitted ? 'RESPONSE_LOST' : 'NOT_SUBMITTED',
            why,
          ),
        );
      };
      if (deadline !== undefined) {
        const milliseconds = requestOptions.timeoutMs ?? deadline.milliseconds;
        asker.cancelDeadline = deadline.schedule(
          () =>
            detached(
              'request deadline expired; submitted work is not replayed',
            ),
          milliseconds,
        );
      }
      if (requestOptions.signal !== undefined) {
        const signal = requestOptions.signal;
        const aborted = (): void =>
          detached('request cancelled; submitted work is not replayed');
        signal.addEventListener('abort', aborted, { once: true });
        asker.cancelAbort = () => signal.removeEventListener('abort', aborted);
        if (signal.aborted) aborted();
      }
      const launch = (): void => {
        if (asker.terminal) return;
        if (!open) {
          detached('connection closed before submission');
          return;
        }
        // Wait behind the negotiated depth rather than fail; pump() resumes FIFO.
        if (dispatched >= maximum || launchWaiters.length > 0) {
          launchWaiters.push({ asker, proceed: transmit });
          return;
        }
        transmit();
      };
      const transmit = (): void => {
        dispatched++;
        launched = true;
        try {
          if (socket.submit !== undefined) {
            asker.transferDone = false;
            const sent = socket.submit(frame, () => {
              asker.submitted = true;
            });
            asker.cancelSend = () => sent.cancel();
            // An injected encoder may synchronously cancel while submit constructs its handle.
            if (asker.terminal) sent.cancel();
            sent.settled.then(
              () => {
                asker.transferDone = true;
                releaseIfSettled(asker);
              },
              (cause: unknown) => {
                asker.transferDone = true;
                fail(
                  id,
                  asker,
                  new ConnectionFault(
                    asker.submitted ? 'RESPONSE_LOST' : 'NOT_SUBMITTED',
                    cause instanceof PacketTransportFault
                      ? `request writer failed: ${cause.reason}; no request was replayed`
                      : 'request writer failed; no request was replayed',
                    { cause },
                  ),
                );
                releaseIfSettled(asker);
              },
            );
          } else {
            // A raw socket's synchronous send either refuses locally or accepts the frame.
            asker.submitted = true;
            socket.send(frame);
          }
        } catch (cause) {
          asker.transferDone = true;
          fail(
            id,
            asker,
            new ConnectionFault(
              'NOT_SUBMITTED',
              'socket refused the frame before submission',
              { cause },
            ),
          );
          releaseIfSettled(asker);
        }
      };
      if (control || !concurrent) launch();
      else
        ready().then(launch, (cause: unknown) =>
          fail(
            id,
            asker,
            new ConnectionFault(
              'NOT_SUBMITTED',
              'dispatch negotiation failed before application submission',
              { cause },
            ),
          ),
        );
    });
  }
  function ready(): Promise<void> {
    if (!concurrent) return Promise.resolve();
    if (opening) return opening;
    let accepted!: () => void;
    let rejected!: (cause: unknown) => void;
    // Publish ownership before invoking an injected socket: it may synchronously
    // deliver a push whose consumer asks for this same opening again.
    opening = new Promise<void>((resolve, reject) => {
      accepted = resolve;
      rejected = reject;
    });
    void exchange(
      'transport.dispatch',
      { mode: 'concurrent-v1' },
      undefined,
      true,
    )
      .then((reply) => {
        const body = reply.outcome.payload;
        if (
          reply.outcome.code !== 'OK' ||
          typeof body !== 'object' ||
          body === null ||
          !('mode' in body) ||
          body.mode !== 'concurrent-v1' ||
          !('maxPending' in body) ||
          typeof body.maxPending !== 'number' ||
          !Number.isSafeInteger(body.maxPending) ||
          body.maxPending < 1 ||
          body.maxPending > 64 ||
          !('maxIdBytes' in body) ||
          body.maxIdBytes !== 128
        )
          throw new ConnectionFault(
            'NOT_SUBMITTED',
            'server did not acknowledge concurrent dispatch; select ordered mode explicitly for an older server',
          );
        maximum = body.maxPending;
        accepted();
        pump();
      })
      .catch((cause: unknown) => {
        strand(
          'dispatch negotiation failed; no application request was replayed',
        );
        socket.close();
        rejected(
          new ConnectionFault(
            'NOT_SUBMITTED',
            'dispatch negotiation failed before application submission',
            { cause },
          ),
        );
      });
    return opening;
  }
  const close = (): void => {
    if (callerClosed) return;
    callerClosed = true;
    strand('this connection was closed before its requests were answered');
    socket.close();
  };
  return {
    ready,
    closeAsync: () => {
      close();
      return drain;
    },
    request: (type, payload, requestOptions) =>
      exchange(type, payload, requestOptions, false),
    ask: async (type, payload, requestOptions) =>
      (await exchange(type, payload, requestOptions, false)).outcome,
    close,
  };
}
