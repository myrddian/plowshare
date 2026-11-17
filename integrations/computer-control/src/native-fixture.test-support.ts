import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { OperationDeadline } from './deadline.ts';
import { awaitBrowserWork } from './backends/browser-control-rpc.ts';
import { parseNativeReplyJson } from './backends/native-control-process.ts';
import { record } from './provider.test-support.ts';

/** Explicitly launched owned AppKit fixture, never an arbitrary application. */
export class NativeFixture {
  private readonly child;
  private readonly exited: Promise<void>;
  private exitCode: number | null | undefined;
  private buffer = Buffer.alloc(0);
  private pending:
    | {
        readonly id: string;
        readonly resolve: (value: Readonly<Record<string, unknown>>) => void;
        readonly reject: (error: Error) => void;
      }
    | undefined;
  private failed = false;
  constructor(executable: string, deadline: OperationDeadline) {
    deadline.requireActive();
    this.child = spawn(executable, [], {
      stdio: ['pipe', 'pipe', 'pipe'],
      env: {},
    });
    this.exited = new Promise((resolve) => {
      this.child.once('close', (code) => {
        this.exitCode = code;
        this.fail();
        resolve();
      });
    });
    this.child.on('error', () => this.fail());
    this.child.stdin.on('error', () => this.fail());
    this.child.stdout.on('error', () => this.fail());
    this.child.stderr.on('error', () => this.fail());
    this.child.stderr.on('data', () => this.fail());
    this.child.stdout.on('data', (bytes: Buffer) => {
      if (this.failed) return;
      if (this.buffer.length + bytes.length > 65_536) {
        this.fail();
        return;
      }
      this.buffer = Buffer.concat([this.buffer, bytes]);
      const end = this.buffer.indexOf(10);
      if (end < 0) return;
      try {
        assert.equal(end, this.buffer.length - 1);
        const value = record(
            parseNativeReplyJson(this.buffer.subarray(0, end)),
          ),
          pending = this.pending;
        assert.ok(pending);
        assert.equal(value['version'], 'computer-control-native/fixture/1');
        assert.equal(value['requestId'], pending.id);
        assert.equal(value['ok'], true);
        const status = record(value['status']);
        assert.equal(status['processId'], this.processId);
        this.pending = undefined;
        this.buffer = Buffer.alloc(0);
        pending.resolve(status);
      } catch {
        this.fail();
      }
    });
  }
  get processId(): number {
    const pid = this.child.pid;
    if (pid === undefined || !Number.isSafeInteger(pid) || pid <= 0)
      throw new Error('Owned native fixture has no child process identity');
    return pid;
  }
  private fail(): void {
    this.failed = true;
    const pending = this.pending;
    this.pending = undefined;
    pending?.reject(
      new Error('Owned native fixture failed; no input was replayed'),
    );
  }
  async request(
    operation: 'configure' | 'status' | 'set_marker' | 'stop',
    fields: Readonly<Record<string, string | number>>,
    deadline: OperationDeadline,
  ): Promise<Readonly<Record<string, unknown>>> {
    deadline.requireActive();
    if (this.failed || this.pending !== undefined)
      throw new Error('Native fixture unavailable');
    const requestId = randomUUID(),
      timeoutMs = Math.max(
        1,
        Math.floor(Math.min(2000, deadline.remainingMs())),
      );
    const reply = new Promise<Readonly<Record<string, unknown>>>(
      (resolve, reject) => {
        this.pending = { id: requestId, resolve, reject };
        this.child.stdin.write(
          JSON.stringify({
            ...fields,
            version: 'computer-control-native/fixture/1',
            requestId,
            timeoutMs,
            operation,
          }) + '\n',
        );
      },
    );
    try {
      return await awaitBrowserWork(reply, deadline);
    } catch (error) {
      this.fail();
      throw error;
    }
  }
  async close(deadline: OperationDeadline): Promise<void> {
    try {
      if (!this.failed && this.exitCode === undefined)
        await this.request('stop', {}, deadline);
      this.child.stdin.end();
      await awaitBrowserWork(this.exited, deadline);
      assert.equal(this.exitCode, 0);
    } catch (error) {
      this.child.kill('SIGKILL');
      // Separate finite kill/reap cleanup; expiry of the original RPC budget is
      // never physical settlement. The application has no native input authority.
      const reaping = new OperationDeadline(2000);
      try {
        await awaitBrowserWork(this.exited, reaping);
      } catch (cleanupError) {
        throw new AggregateError(
          [error, cleanupError],
          'Owned fixture did not reap',
          { cause: cleanupError },
        );
      } finally {
        reaping.dispose();
      }
      throw error;
    }
  }
}
