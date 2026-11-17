import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { isAbsolute } from 'node:path';
import type { NativeCleanupResult, NativeLease } from '../native-control.ts';
import type { OperationDeadline } from '../deadline.ts';
import { positiveBudget } from '../deadline.ts';
import type { NativeChild, NativeChildLauncher } from './macos.ts';
import {
  NATIVE_BROKER_MAX_REPLY_BYTES,
  NATIVE_BROKER_MAX_REQUEST_BYTES,
  NATIVE_BROKER_VERSION,
} from './native-broker-protocol.ts';
import type {
  NativeBrokerCancel,
  NativeBrokerCleanupProof,
  NativeBrokerReply,
  NativeBrokerReplyDecoder,
  NativeBrokerRequest,
} from './native-broker-protocol.ts';

/** Fixed diagnostics only; native text, paths and operation payloads stay private. */
export class NativeProcessError extends Error {
  constructor(
    readonly code:
      | 'BUSY'
      | 'CLOSED'
      | 'INVALID_PROTOCOL'
      | 'INVALID_REQUEST'
      | 'DEADLINE_EXPIRED'
      | 'UNCERTAIN',
    readonly physicalSettlement: 'settled' | 'uncertain',
  ) {
    super(code);
    this.name = 'NativeProcessError';
  }
}

/** One normal command, one acknowledged revoke control and one-way cancellation; never retries input. */
export interface NativeControlProcess {
  request(
    command: NativeBrokerRequest,
    deadline: OperationDeadline,
  ): Promise<NativeBrokerReply>;
  /** Exact-lease revocation bypasses an outstanding ordinary input command. */
  revoke(
    lease: NativeLease,
    deadline: OperationDeadline,
  ): Promise<NativeBrokerReply>;
  /** Combines typed owned-input cleanup proof with actual process close. */
  close(deadline: OperationDeadline): Promise<NativeCleanupResult>;
}

/** Owning helper protocol codec validates every field before IO in either direction. */
export interface NativeBrokerCodec {
  decodeRequest(value: unknown): NativeBrokerRequest;
  decodeReply: NativeBrokerReplyDecoder;
}

interface Pending {
  readonly requestId: string;
  readonly operation: NativeBrokerRequest['operation'];
  readonly deadline: OperationDeadline;
  readonly resolve: (reply: NativeBrokerReply) => void;
  readonly reject: (error: NativeProcessError) => void;
  readonly detach: () => void;
  failure?: NativeProcessError;
}

/** JSON.parse alone loses duplicate fields, including escaped key aliases. */
export function parseNativeReplyJson(bytes: Uint8Array): unknown {
  const source = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  const value: unknown = JSON.parse(source);
  const tokens =
    source.match(/"(?:\\.|[^"\\])*"|[{}[\]:,]|[^\s{}[\]:,]+/gu) ?? [];
  const stack: (Set<string> | null)[] = [];
  for (let index = 0; index < tokens.length; index++) {
    const token = tokens[index];
    if (token === '{') stack.push(new Set());
    else if (token === '[') stack.push(null);
    else if (token === '}' || token === ']') stack.pop();
    else if (token?.startsWith('"') && tokens[index + 1] === ':') {
      const names = stack.at(-1);
      const name: unknown = JSON.parse(token);
      if (names == null || typeof name !== 'string' || names.has(name))
        throw new NativeProcessError('INVALID_PROTOCOL', 'uncertain');
      names.add(name);
    }
  }
  return value;
}

const launch: NativeChildLauncher = (executable) =>
  spawn(executable, ['--control'], {
    env: {},
    stdio: ['pipe', 'pipe', 'pipe'],
  });

/**
 * Persistent private stdio owner. It never frees a broker marker or kernel fence.
 * An expired/malformed/lost reply fences this instance even if the helper later
 * exits. A dead child cannot prove posted input was released. The backend owns
 * that recovery obligation and may not restart this process to retry an action.
 */
export class SpawnNativeControlProcess implements NativeControlProcess {
  private readonly child: NativeChild;
  private pending: Pending | undefined;
  private controlPending: Pending | undefined;
  private buffer = Buffer.alloc(0);
  private fenced = false;
  private closing = false;
  private didClose = false;
  private exitCode: number | null = null;
  private diagnostics = 0;
  private cleanupProof: NativeBrokerCleanupProof | undefined;
  private readonly exited: Promise<void>;
  private finishExit = () => {};
  private killTimer: ReturnType<typeof setTimeout> | undefined;
  private drainTimer: ReturnType<typeof setTimeout> | undefined;
  private shutdown: Promise<NativeCleanupResult> | undefined;

  constructor(
    executable: string,
    private readonly codec: NativeBrokerCodec,
    startupDeadline: OperationDeadline,
    private readonly cleanupBudgetMs = 2000,
    launcher: NativeChildLauncher = launch,
  ) {
    if (
      !isAbsolute(executable) ||
      Array.from(executable).some(
        (character) => (character.codePointAt(0) ?? 0) < 32,
      )
    )
      throw new NativeProcessError('INVALID_REQUEST', 'settled');
    positiveBudget(cleanupBudgetMs);
    if (
      typeof codec.decodeRequest !== 'function' ||
      typeof codec.decodeReply !== 'function'
    )
      throw new NativeProcessError('INVALID_REQUEST', 'settled');
    this.exited = new Promise((resolve) => {
      this.finishExit = resolve;
    });
    startupDeadline.requireActive();
    this.child = launcher(executable);
    this.child.stdout.on('data', (bytes: Buffer) => this.receive(bytes));
    this.child.stdout.on('error', () => this.fail('UNCERTAIN', false));
    this.child.stderr.on('error', () => this.fail('UNCERTAIN', false));
    this.child.stderr.on('data', (bytes: Buffer) => {
      this.diagnostics += bytes.byteLength;
      if (this.diagnostics > 2048) this.fail('INVALID_PROTOCOL', false);
    });
    this.child.stdin.on('error', () => this.fail('UNCERTAIN', false));
    this.child.on('error', () => this.fail('UNCERTAIN', false));
    this.child.on('close', (code) => {
      this.didClose = true;
      this.exitCode = code;
      this.clearTimers();
      if (
        !this.closing ||
        this.cleanupProof === undefined ||
        this.buffer.length > 0 ||
        code !== 0 ||
        this.diagnostics !== 0
      )
        this.fenced = true;
      this.rejectPending();
      this.finishExit();
    });
  }

  request(
    command: NativeBrokerRequest,
    deadline: OperationDeadline,
  ): Promise<NativeBrokerReply> {
    if (command.operation === 'stop' || command.operation === 'arm_revoke')
      return Promise.reject(
        new NativeProcessError('INVALID_REQUEST', 'settled'),
      );
    return this.send(command, deadline);
  }

  revoke(
    lease: NativeLease,
    deadline: OperationDeadline,
  ): Promise<NativeBrokerReply> {
    return this.send({ operation: 'arm_revoke', lease }, deadline, true);
  }

  private send(
    command: NativeBrokerRequest,
    deadline: OperationDeadline,
    control = false,
  ): Promise<NativeBrokerReply> {
    let checked: NativeBrokerRequest;
    try {
      checked = this.codec.decodeRequest(command);
    } catch {
      return Promise.reject(
        new NativeProcessError('INVALID_REQUEST', 'settled'),
      );
    }
    try {
      deadline.requireActive();
    } catch {
      return Promise.reject(
        new NativeProcessError('DEADLINE_EXPIRED', 'settled'),
      );
    }
    if (
      this.fenced ||
      this.didClose ||
      (this.closing && checked.operation !== 'stop')
    )
      return Promise.reject(
        new NativeProcessError('CLOSED', this.fenced ? 'uncertain' : 'settled'),
      );
    if (
      control
        ? this.controlPending !== undefined
        : this.pending !== undefined || this.controlPending !== undefined
    )
      return Promise.reject(new NativeProcessError('BUSY', 'settled'));
    if (control && checked.operation !== 'arm_revoke')
      return Promise.reject(
        new NativeProcessError('INVALID_REQUEST', 'settled'),
      );
    const requestId = randomUUID();
    const wire = Buffer.from(
      JSON.stringify({
        ...checked,
        version: NATIVE_BROKER_VERSION,
        requestId,
        timeoutMs: Math.max(1, Math.floor(deadline.remainingMs())),
      }) + '\n',
    );
    if (wire.byteLength > NATIVE_BROKER_MAX_REQUEST_BYTES)
      return Promise.reject(
        new NativeProcessError('INVALID_REQUEST', 'settled'),
      );
    return new Promise((resolve, reject) => {
      const abort = () => this.fail('DEADLINE_EXPIRED', true);
      const pending: Pending = {
        requestId,
        operation: checked.operation,
        deadline,
        resolve,
        reject,
        detach: () => deadline.signal.removeEventListener('abort', abort),
      };
      if (control) this.controlPending = pending;
      else this.pending = pending;
      deadline.signal.addEventListener('abort', abort, { once: true });
      if (deadline.signal.aborted || deadline.remainingMs() <= 0) {
        // Nothing entered stdin. Keep the existing lease alive; only this call
        // is a proven pre-dispatch refusal.
        if (control) this.controlPending = undefined;
        else this.pending = undefined;
        deadline.signal.removeEventListener('abort', abort);
        reject(new NativeProcessError('DEADLINE_EXPIRED', 'settled'));
        return;
      }
      try {
        this.child.stdin.write(wire);
      } catch {
        this.fail('UNCERTAIN', false);
      }
    });
  }

  private receive(bytes: Buffer): void {
    if (this.fenced) return;
    let offset = 0;
    let frames = 0;
    // Two correlated replies may share a pipe read. Bound each frame rather
    // than the concatenated read, retaining at most one incomplete frame.
    while (offset < bytes.length) {
      if (this.pending === undefined && this.controlPending === undefined) {
        this.fail('INVALID_PROTOCOL', false);
        return;
      }
      const newline = bytes.indexOf(10, offset);
      const end = newline < 0 ? bytes.length : newline;
      if (this.buffer.length + end - offset > NATIVE_BROKER_MAX_REPLY_BYTES) {
        this.fail('INVALID_PROTOCOL', false);
        return;
      }
      this.buffer = Buffer.concat([this.buffer, bytes.subarray(offset, end)]);
      if (newline < 0) return;
      if (++frames > 2) {
        this.fail('INVALID_PROTOCOL', false);
        return;
      }
      const frame = this.buffer;
      this.buffer = Buffer.alloc(0);
      this.receiveFrame(frame);
      if (this.fenced) return;
      offset = end + 1;
    }
  }

  private receiveFrame(frame: Buffer): void {
    try {
      const reply = this.codec.decodeReply(parseNativeReplyJson(frame));
      const control = reply.requestId === this.controlPending?.requestId;
      const pending = control ? this.controlPending : this.pending;
      if (
        pending === undefined ||
        reply.version !== NATIVE_BROKER_VERSION ||
        reply.requestId !== pending.requestId ||
        (reply.operation !== pending.operation && reply.operation !== 'failure')
      )
        throw new NativeProcessError('INVALID_PROTOCOL', 'uncertain');
      if (
        pending.deadline.remainingMs() <= 0 ||
        pending.deadline.signal.aborted
      ) {
        this.fail('DEADLINE_EXPIRED', true);
        return;
      }
      if (reply.operation === 'stop') this.cleanupProof = reply.result;
      if (control) this.controlPending = undefined;
      else this.pending = undefined;
      pending.detach();
      pending.resolve(reply);
    } catch {
      this.fail('INVALID_PROTOCOL', false);
    }
  }

  private clearTimers(): void {
    if (this.killTimer !== undefined) clearTimeout(this.killTimer);
    if (this.drainTimer !== undefined) clearTimeout(this.drainTimer);
    this.killTimer = undefined;
    this.drainTimer = undefined;
  }

  private fail(code: NativeProcessError['code'], cancel: boolean): void {
    if (this.didClose) return;
    this.fenced = true;
    const pending = this.pending ?? this.controlPending;
    const firstFailure = this.pendingWork().every(
      (work) => work.failure === undefined,
    );
    for (const pending of this.pendingWork()) {
      pending.failure ??= new NativeProcessError(code, 'uncertain');
      pending.detach();
    }
    if (cancel && firstFailure && pending !== undefined) {
      const message: NativeBrokerCancel = {
        version: NATIVE_BROKER_VERSION,
        requestId: randomUUID(),
        operation: 'cancel',
        targetRequestId: pending.requestId,
      };
      try {
        this.child.stdin.write(JSON.stringify(message) + '\n');
      } catch {
        this.child.kill('SIGKILL');
      }
    }
    if (this.didClose || this.drainTimer !== undefined) return;
    // Cancellation and kill/reap share ONE finite cleanup budget. A failed reap
    // settles the JS waiter as uncertain but never makes this process reusable.
    this.drainTimer = setTimeout(() => {
      this.clearTimers();
      this.rejectPending();
    }, this.cleanupBudgetMs);
    if (cancel)
      this.killTimer = setTimeout(
        () => this.child.kill('SIGKILL'),
        Math.max(1, Math.floor(this.cleanupBudgetMs / 2)),
      );
    else this.child.kill('SIGKILL');
  }

  private pendingWork(): readonly Pending[] {
    return [this.pending, this.controlPending].flatMap((pending) =>
      pending === undefined ? [] : [pending],
    );
  }

  private rejectPending(): void {
    const pending = this.pendingWork();
    this.pending = undefined;
    this.controlPending = undefined;
    for (const work of pending) {
      work.detach();
      work.reject(
        work.failure ?? new NativeProcessError('UNCERTAIN', 'uncertain'),
      );
    }
  }

  close(deadline: OperationDeadline): Promise<NativeCleanupResult> {
    if (this.shutdown === undefined) {
      const bounded = deadline.child(this.cleanupBudgetMs);
      this.shutdown = this.stop(bounded).finally(() => bounded.dispose());
    }
    return this.shutdown;
  }

  private async stop(
    deadline: OperationDeadline,
  ): Promise<NativeCleanupResult> {
    this.closing = true;
    if (this.pending !== undefined || this.controlPending !== undefined)
      this.fail('UNCERTAIN', true);
    try {
      if (!this.fenced && !this.didClose) {
        const reply = await this.send({ operation: 'stop' }, deadline);
        if (reply.operation !== 'stop') this.fail('UNCERTAIN', false);
        this.child.stdin.end();
      }
      let detach = () => {};
      const expired = new Promise<void>((resolve) => {
        const abort = () => {
          this.fail('DEADLINE_EXPIRED', false);
          resolve();
        };
        deadline.signal.addEventListener('abort', abort, { once: true });
        detach = () => deadline.signal.removeEventListener('abort', abort);
        if (deadline.remainingMs() <= 0 || deadline.signal.aborted) abort();
      });
      try {
        await Promise.race([this.exited, expired]);
      } finally {
        detach();
      }
    } catch {
      this.fail('UNCERTAIN', false);
    }
    // A timer callback can lag the monotonic cutoff. Physical exit is a fact,
    // but a late exit cannot become a successful bounded cleanup publication.
    if (deadline.remainingMs() <= 0 || deadline.signal.aborted)
      this.fenced = true;
    const proof = this.cleanupProof;
    return Object.freeze({
      physicalSettlement:
        !this.fenced &&
        this.didClose &&
        this.exitCode === 0 &&
        proof?.physicalSettlement === 'settled' &&
        proof.heldInputsReleased
          ? 'settled'
          : 'uncertain',
      heldInputsReleased: proof?.heldInputsReleased === true,
      helperStopped: this.didClose,
    });
  }
}
