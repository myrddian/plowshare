import { spawn } from 'node:child_process';
import { mkdir, open, rm } from 'node:fs/promises';
import { isAbsolute, join } from 'node:path';
import type { Readable, Writable } from 'node:stream';
import { privateDirectory } from 'plowshare-client-node/connections';
import {
  BackendError,
  computerId,
  decodeCapturedImage,
  decodeCaptureRequest,
  decodeTargets,
} from '../contracts.ts';
import type {
  CapturedImage,
  CaptureRequest,
  ComputerBackend,
  ComputerTarget,
} from '../contracts.ts';
import type { OperationDeadline } from '../deadline.ts';
import { positiveBudget } from '../deadline.ts';
import type { BackendHandle } from './types.ts';

const VERSION = 'computer-control-native/1';
const MAX_OUTPUT_BYTES = 1_048_576 + 8192;

export interface MacOSTargetConfig {
  readonly targetId: string;
  readonly label: string;
  readonly surfaceId: string;
}
export interface MacOSBackendConfig {
  readonly helperExecutable: string;
  readonly ownershipDirectory: string;
  readonly cleanupBudgetMs?: number;
  readonly targets: readonly MacOSTargetConfig[];
}
export interface NativeRequest {
  readonly version: 'computer-control-native/1';
  readonly operation: 'status' | 'capture';
  readonly timeoutMs: number;
  readonly surfaceId?: string;
  readonly maxEdge?: number;
  readonly maxBytes?: number;
}
/** One process per operation; resolve/reject only after exit, or fence permanently on failed drain. */
export interface NativeCaptureProcess {
  run(
    executable: string,
    request: NativeRequest,
    deadline: OperationDeadline,
  ): Promise<Uint8Array>;
  close(deadline: OperationDeadline): Promise<void>;
}

/** Node process boundary exposed only to lifecycle tests; the native helper gets private stdio. */
export interface NativeChild {
  readonly stdin: Writable;
  readonly stdout: Readable;
  readonly stderr: Readable;
  on(event: 'error', listener: (error: Error) => void): this;
  on(event: 'close', listener: (code: number | null) => void): this;
  kill(signal: 'SIGKILL'): boolean;
}
export type NativeChildLauncher = (executable: string) => NativeChild;
/** Durability boundary for the fixed native resource marker, injectable for failed-force tests. */
export interface NativeOwnershipSync {
  force(directory: string): Promise<void>;
}
const diskOwnershipSync: NativeOwnershipSync = {
  async force(path) {
    const directory = await open(path, 'r');
    try {
      await directory.sync();
    } finally {
      await directory.close();
    }
  },
};
const launchChild: NativeChildLauncher = (executable) =>
  spawn(executable, [], {
    stdio: ['pipe', 'pipe', 'pipe'],
    env: {},
  });

function object(
  value: unknown,
  allowed: readonly string[],
): Readonly<Record<string, unknown>> {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    throw new BackendError('CAPTURE_FAILED');
  const row = value as Readonly<Record<string, unknown>>;
  if (Object.keys(row).some((key) => !allowed.includes(key)))
    throw new BackendError('CAPTURE_FAILED');
  return row;
}

export function decodeMacOSBackendConfig(value: unknown): MacOSBackendConfig {
  const row = object(value, [
    'helperExecutable',
    'ownershipDirectory',
    'cleanupBudgetMs',
    'targets',
  ]);
  const helperExecutable = row['helperExecutable'];
  if (
    typeof helperExecutable !== 'string' ||
    !isAbsolute(helperExecutable) ||
    Array.from(helperExecutable).some(
      (character) => (character.codePointAt(0) ?? 0) < 32,
    )
  )
    throw new Error(
      'Native helperExecutable must be an explicit absolute path',
    );
  const ownershipDirectory = row['ownershipDirectory'];
  if (
    typeof ownershipDirectory !== 'string' ||
    !isAbsolute(ownershipDirectory) ||
    Array.from(ownershipDirectory).some(
      (character) => (character.codePointAt(0) ?? 0) < 32,
    )
  )
    throw new Error(
      'Native ownershipDirectory must be an explicit absolute path',
    );
  const cleanupBudgetMs = row['cleanupBudgetMs'] ?? 2000;
  if (typeof cleanupBudgetMs !== 'number')
    throw new Error('Invalid native cleanup budget');
  positiveBudget(cleanupBudgetMs);
  const rawTargets = row['targets'];
  if (
    !Array.isArray(rawTargets) ||
    rawTargets.length < 1 ||
    rawTargets.length > 32
  )
    throw new Error('Native backend requires 1..32 configured targets');
  const targets = rawTargets.map((value: unknown): MacOSTargetConfig => {
    const target = object(value, ['targetId', 'label', 'surfaceId']);
    const surfaceId = target['surfaceId'];
    if (
      typeof surfaceId !== 'string' ||
      !/^(?:display|window):[1-9][0-9]{0,9}$/u.test(surfaceId) ||
      Number(surfaceId.split(':')[1]) > 4_294_967_295
    )
      throw new Error(
        'Native surfaceId must name an explicit display/window identifier',
      );
    return Object.freeze({
      targetId: computerId(target['targetId']),
      label: computerId(target['label']),
      surfaceId,
    });
  });
  if (new Set(targets.map((target) => target.targetId)).size !== targets.length)
    throw new Error('Duplicate native target');
  return Object.freeze({
    helperExecutable,
    ownershipDirectory,
    cleanupBudgetMs,
    targets: Object.freeze(targets),
  });
}

/** The stdout frame is one bounded JSON line followed by exactly the declared PNG byte count. */
function decodeReply(bytes: Uint8Array): {
  readonly metadata: Readonly<Record<string, unknown>>;
  readonly image: Uint8Array;
} {
  if (bytes.byteLength > MAX_OUTPUT_BYTES)
    throw new BackendError('CAPTURE_FAILED');
  const newline = bytes.indexOf(10);
  if (newline < 1 || newline >= 8192) throw new BackendError('CAPTURE_FAILED');
  const text = new TextDecoder('utf-8', { fatal: true }).decode(
    bytes.subarray(0, newline),
  );
  const value: unknown = JSON.parse(text);
  const tokens =
    text.match(/"(?:\\.|[^"\\])*"|[{}[\]:,]|[^\s{}[\]:,]+/gu) ?? [];
  const stack: (Set<string> | null)[] = [];
  for (let i = 0; i < tokens.length; i++) {
    const token = tokens[i];
    if (token === '{') stack.push(new Set());
    else if (token === '[') stack.push(null);
    else if (token === '}' || token === ']') stack.pop();
    else if (token?.startsWith('"') && tokens[i + 1] === ':') {
      const key: unknown = JSON.parse(token);
      const names = stack.at(-1);
      if (typeof key !== 'string' || names == null || names.has(key))
        throw new BackendError('CAPTURE_FAILED');
      names.add(key);
    }
  }
  const metadata = object(value, [
    'version',
    'ok',
    'operation',
    'code',
    'screenCaptureAllowed',
    'accessibilityAllowed',
    'byteLength',
    'width',
    'height',
    'transform',
    'capturedAt',
    'layoutGeneration',
  ]);
  if (metadata['version'] !== VERSION || typeof metadata['ok'] !== 'boolean')
    throw new BackendError('CAPTURE_FAILED');
  const image = bytes.subarray(newline + 1);
  if (metadata['ok'] === false) {
    if (image.byteLength !== 0) throw new BackendError('CAPTURE_FAILED');
    switch (metadata['code']) {
      case 'PERMISSION_DENIED':
        throw new BackendError('PERMISSION_DENIED');
      case 'TARGET_GONE':
        throw new BackendError('TARGET_GONE');
      default:
        throw new BackendError('CAPTURE_FAILED');
    }
  }
  return { metadata, image };
}

/** Private stdio only, empty environment, no shell or Plowshare credentials inherited by helper. */
export class SpawnNativeCaptureProcess implements NativeCaptureProcess {
  private active = false;
  private fenced = false;
  private closing = false;
  private lockHeld = false;
  private stopCurrent: (() => void) | undefined;
  private settled: Promise<void> = Promise.resolve();

  constructor(
    private readonly ownershipDirectory: string,
    private readonly launcher: NativeChildLauncher = launchChild,
    private readonly cleanupBudgetMs = 2000,
    private readonly ownershipSync: NativeOwnershipSync = diskOwnershipSync,
  ) {
    if (
      !isAbsolute(ownershipDirectory) ||
      Array.from(ownershipDirectory).some(
        (character) => (character.codePointAt(0) ?? 0) < 32,
      )
    )
      throw new Error(
        'Native ownershipDirectory must be explicit and absolute',
      );
    positiveBudget(cleanupBudgetMs);
  }

  private async synchronizeDirectory(): Promise<void> {
    await this.ownershipSync.force(this.ownershipDirectory);
  }

  private async acquire(deadline: OperationDeadline): Promise<void> {
    await privateDirectory(this.ownershipDirectory);
    deadline.requireActive();
    if (this.closing) throw new BackendError('CAPTURE_FAILED');
    await mkdir(join(this.ownershipDirectory, 'capture.lock'), { mode: 0o700 });
    this.lockHeld = true;
    // An interruption after mkdir never authorizes deleting or stealing this lock.
    try {
      await this.synchronizeDirectory();
      deadline.requireActive();
    } catch (error) {
      this.fenced = true;
      throw error;
    }
  }

  private async release(): Promise<void> {
    if (!this.lockHeld) return;
    await rm(join(this.ownershipDirectory, 'capture.lock'), {
      recursive: true,
    });
    await this.synchronizeDirectory();
    this.lockHeld = false;
  }

  async run(
    executable: string,
    request: NativeRequest,
    deadline: OperationDeadline,
  ): Promise<Uint8Array> {
    deadline.requireActive();
    if (this.active || this.fenced || this.closing)
      throw new BackendError(
        'CAPTURE_FAILED',
        this.fenced ? 'uncertain' : 'settled',
      );
    this.active = true;
    // Publish completion ownership before the first async filesystem step, so close()
    // cannot mistake an in-progress acquisition for an idle runner.
    let settle = () => {};
    this.settled = new Promise((resolve) => {
      settle = resolve;
    });
    let child: NativeChild;
    try {
      await this.acquire(deadline);
      deadline.requireActive();
      if (this.closing || this.fenced) throw new BackendError('CAPTURE_FAILED');
      child = this.launcher(executable);
    } catch {
      if (!this.fenced) {
        try {
          await this.release();
        } catch {
          this.fenced = true;
        }
      }
      this.active = false;
      settle();
      throw new BackendError(
        deadline.signal.aborted ? 'DEADLINE_EXPIRED' : 'CAPTURE_FAILED',
        this.fenced ? 'uncertain' : 'settled',
      );
    }
    return new Promise((resolve, reject) => {
      const chunks: Buffer[] = [];
      let length = 0;
      let diagnostics = 0;
      let failed: BackendError | undefined;
      let drain: ReturnType<typeof setTimeout> | undefined;
      let completed = false;
      const fail = (error: BackendError) => {
        failed ??= error;
        child.kill('SIGKILL');
        drain ??= setTimeout(() => {
          // A failed drain cannot free physical capacity. The runner remains fenced
          // even if a late close eventually arrives; the host must recover explicitly.
          this.fenced = true;
          completed = true;
          reject(
            new BackendError(failed?.code ?? 'CAPTURE_FAILED', 'uncertain'),
          );
        }, this.cleanupBudgetMs);
      };
      const aborted = () => fail(new BackendError('DEADLINE_EXPIRED'));
      this.stopCurrent = () => fail(new BackendError('CAPTURE_FAILED'));
      deadline.signal.addEventListener('abort', aborted, { once: true });
      child.stdout.on('data', (chunk: Buffer) => {
        length += chunk.byteLength;
        if (length > MAX_OUTPUT_BYTES) fail(new BackendError('CAPTURE_FAILED'));
        else chunks.push(chunk);
      });
      child.stderr.on('data', (chunk: Buffer) => {
        diagnostics += chunk.byteLength;
        if (diagnostics > 2048) fail(new BackendError('CAPTURE_FAILED'));
      });
      child.stdin.on('error', () => fail(new BackendError('CAPTURE_FAILED')));
      child.on('error', () => fail(new BackendError('CAPTURE_FAILED')));
      const closed = async (code: number | null) => {
        deadline.signal.removeEventListener('abort', aborted);
        if (drain !== undefined) clearTimeout(drain);
        this.stopCurrent = undefined;
        if (this.fenced) {
          this.active = false;
          settle();
          return;
        }
        try {
          await this.release();
        } catch {
          this.fenced = true;
          this.active = false;
          settle();
          if (!completed) {
            completed = true;
            reject(new BackendError('CAPTURE_FAILED', 'uncertain'));
          }
          return;
        }
        this.active = false;
        settle();
        if (completed) return;
        completed = true;
        if (failed !== undefined) reject(failed);
        else if (code !== 0 || diagnostics !== 0)
          reject(new BackendError('CAPTURE_FAILED'));
        else if (deadline.signal.aborted || deadline.remainingMs() <= 0)
          reject(new BackendError('DEADLINE_EXPIRED'));
        else resolve(new Uint8Array(Buffer.concat(chunks, length)));
      };
      child.on('close', (code) => {
        // Own asynchronous directory forcing; no event callback has an unobserved promise.
        closed(code).catch(() => {
          this.fenced = true;
          this.active = false;
          settle();
          reject(new BackendError('CAPTURE_FAILED', 'uncertain'));
        });
      });
      if (deadline.signal.aborted) aborted();
      else child.stdin.end(JSON.stringify(request));
    });
  }

  async close(deadline: OperationDeadline): Promise<void> {
    deadline.requireActive();
    this.closing = true;
    this.stopCurrent?.();
    let detach = () => {};
    const expired = new Promise<never>((_resolve, reject) => {
      const abort = () => {
        this.fenced = true;
        reject(new BackendError('DEADLINE_EXPIRED', 'uncertain'));
      };
      deadline.signal.addEventListener('abort', abort, { once: true });
      detach = () => deadline.signal.removeEventListener('abort', abort);
      if (deadline.signal.aborted) abort();
    });
    try {
      await Promise.race([this.settled, expired]);
      deadline.requireActive();
      if (this.fenced) throw new BackendError('CAPTURE_FAILED', 'uncertain');
    } finally {
      detach();
    }
  }
}

class MacOSBackend implements ComputerBackend {
  private closed = false;
  private readonly targetList: readonly ComputerTarget[];

  constructor(
    private readonly config: MacOSBackendConfig,
    private readonly process: NativeCaptureProcess,
  ) {
    this.targetList = decodeTargets(
      config.targets.map((target) => ({
        targetId: target.targetId,
        backend: 'macos',
        label: target.label,
        surfaces: [{ id: target.surfaceId, label: target.label }],
        capabilities: ['capture'],
      })),
    );
  }

  private async status(deadline: OperationDeadline): Promise<void> {
    const output = await this.process.run(
      this.config.helperExecutable,
      {
        version: VERSION,
        operation: 'status',
        timeoutMs: Math.max(1, Math.floor(deadline.remainingMs())),
      },
      deadline,
    );
    deadline.requireActive();
    const { metadata, image } = decodeReply(output);
    object(metadata, [
      'version',
      'ok',
      'operation',
      'screenCaptureAllowed',
      'accessibilityAllowed',
    ]);
    if (
      metadata['operation'] !== 'status' ||
      image.byteLength !== 0 ||
      typeof metadata['screenCaptureAllowed'] !== 'boolean' ||
      typeof metadata['accessibilityAllowed'] !== 'boolean'
    )
      throw new BackendError('CAPTURE_FAILED');
    if (!metadata['screenCaptureAllowed'])
      throw new BackendError('PERMISSION_DENIED');
    // Accessibility is reported separately by the helper; pixel capture does not require it.
  }

  async targets(
    deadline: OperationDeadline,
  ): Promise<readonly ComputerTarget[]> {
    deadline.requireActive();
    if (this.closed) throw new BackendError('TARGET_GONE');
    await this.status(deadline);
    return this.targetList;
  }

  async capture(
    value: CaptureRequest,
    deadline: OperationDeadline,
  ): Promise<CapturedImage> {
    deadline.requireActive();
    const request = decodeCaptureRequest(value);
    const target = this.config.targets.find(
      (item) => item.targetId === request.targetId,
    );
    if (
      this.closed ||
      target === undefined ||
      request.surfaceId !== target.surfaceId
    )
      throw new BackendError('TARGET_GONE');
    try {
      const output = await this.process.run(
        this.config.helperExecutable,
        {
          version: VERSION,
          operation: 'capture',
          surfaceId: target.surfaceId,
          maxEdge: 1600,
          maxBytes: 1_048_576,
          timeoutMs: Math.max(1, Math.floor(deadline.remainingMs())),
        },
        deadline,
      );
      deadline.requireActive();
      const { metadata, image } = decodeReply(output);
      object(metadata, [
        'version',
        'ok',
        'operation',
        'byteLength',
        'width',
        'height',
        'transform',
        'capturedAt',
        'layoutGeneration',
      ]);
      if (
        metadata['operation'] !== 'capture' ||
        metadata['byteLength'] !== image.byteLength
      )
        throw new BackendError('CAPTURE_FAILED');
      return decodeCapturedImage({
        bytes: image,
        mimeType: 'image/png',
        width: metadata['width'],
        height: metadata['height'],
        transform: metadata['transform'],
        capturedAt: metadata['capturedAt'],
        layoutGeneration: metadata['layoutGeneration'],
      });
    } catch (error) {
      if (deadline.signal.aborted) this.closed = true;
      if (error instanceof BackendError) throw error;
      throw new BackendError(
        deadline.signal.aborted ? 'DEADLINE_EXPIRED' : 'CAPTURE_FAILED',
      );
    }
  }

  async close(deadline: OperationDeadline): Promise<void> {
    this.closed = true;
    await this.process.close(deadline);
  }
}

export function createMacOSBackend(
  value: MacOSBackendConfig,
  process?: NativeCaptureProcess,
): BackendHandle {
  const config = decodeMacOSBackendConfig(value);
  const backend = new MacOSBackend(
    config,
    process ??
      new SpawnNativeCaptureProcess(
        config.ownershipDirectory,
        undefined,
        config.cleanupBudgetMs,
      ),
  );
  return { backend, close: (deadline) => backend.close(deadline) };
}
