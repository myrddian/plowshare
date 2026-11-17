import { lstat, mkdir, open, rmdir, unlink } from 'node:fs/promises';
import { constants } from 'node:fs';
import { isAbsolute, join } from 'node:path';
import {
  atomicPrivateWrite,
  privateDirectory,
} from 'plowshare-client-node/connections';
import type { NativeLease } from '../native-control.ts';
import { NativeControlError, decodeNativeLease } from '../native-control.ts';
import type { OperationDeadline } from '../deadline.ts';
import { awaitBrowserWork } from './browser-control-rpc.ts';
import { parseNativeReplyJson } from './native-control-process.ts';

async function force(directory: string): Promise<void> {
  const handle = await open(directory, 'r');
  try {
    await handle.sync();
  } finally {
    await handle.close();
  }
}

/**
 * The exact P1 capture.lock is held for the whole controller lease. This private
 * durable evidence supplements the helper's HOST-WIDE kernel semaphore; changing
 * directories never creates a second physical desktop. No stale/PID recovery.
 */
export class NativeDirectoryOwnership {
  private phase: 'idle' | 'acquiring' | 'held' | 'releasing' | 'fenced' =
    'idle';
  private lease: NativeLease | undefined;
  private inode: bigint | undefined;
  constructor(private readonly directory: string) {
    if (
      typeof directory !== 'string' ||
      directory.length > 4096 ||
      !isAbsolute(directory) ||
      Array.from(directory).some(
        (character) => (character.codePointAt(0) ?? 0) < 32,
      )
    )
      throw new NativeControlError('INVALID_ARGUMENT');
  }
  fence(): void {
    this.phase = 'fenced';
  }

  async acquire(
    lease: NativeLease,
    deadline: OperationDeadline,
  ): Promise<'acquired' | 'busy'> {
    deadline.requireActive();
    if (this.phase !== 'idle') throw new NativeControlError('FENCED');
    const checked = decodeNativeLease(lease);
    this.phase = 'acquiring';
    this.lease = checked;
    const lock = join(this.directory, 'capture.lock');
    const work = (async () => {
      await privateDirectory(this.directory);
      deadline.requireActive();
      if (this.phase !== 'acquiring') throw new NativeControlError('FENCED');
      try {
        await mkdir(lock, { mode: 0o700 });
      } catch (error) {
        // EEXIST before this instance owns any resource is ordinary contention.
        // Never inspect, remove or otherwise clean up the foreign marker.
        if (
          error instanceof Error &&
          'code' in error &&
          error.code === 'EEXIST'
        )
          return 'busy' as const;
        throw error;
      }
      this.inode = (await lstat(lock, { bigint: true })).ino;
      deadline.requireActive();
      if (this.phase !== 'acquiring') throw new NativeControlError('FENCED');
      await atomicPrivateWrite(join(lock, 'lease.json'), {
        version: 'native-local-lease/1',
        ...checked,
      });
      await force(lock);
      await force(this.directory);
      deadline.requireActive();
      if (this.phase !== 'acquiring') throw new NativeControlError('FENCED');
      return 'acquired' as const;
    })();
    try {
      const outcome = await awaitBrowserWork(work, deadline);
      deadline.requireActive();
      if (this.phase !== 'acquiring') throw new NativeControlError('FENCED');
      if (outcome === 'busy') {
        this.phase = 'idle';
        this.lease = undefined;
        return outcome;
      }
      this.phase = 'held';
      return outcome;
    } catch (error) {
      this.phase = 'fenced';
      throw error;
    }
  }

  /** Only invoke after validated owned-input proof AND parent-observed helper exit. */
  async releaseAfterQuiescence(deadline: OperationDeadline): Promise<void> {
    deadline.requireActive();
    if (this.phase !== 'held' || this.lease === undefined)
      throw new NativeControlError('FENCED');
    this.phase = 'releasing';
    const lock = join(this.directory, 'capture.lock'),
      lease = this.lease;
    const work = (async () => {
      const info = await lstat(lock, { bigint: true });
      if (
        !info.isDirectory() ||
        info.isSymbolicLink() ||
        info.ino !== this.inode
      )
        throw new NativeControlError('FENCED');
      const file = join(lock, 'lease.json');
      const handle = await open(
        file,
        constants.O_RDONLY | constants.O_NOFOLLOW,
      );
      let value: unknown;
      try {
        const info = await handle.stat();
        if (!info.isFile() || info.size > 4096)
          throw new NativeControlError('FENCED');
        const bytes = Buffer.alloc(4097);
        const { bytesRead } = await handle.read(bytes, 0, bytes.byteLength, 0);
        if (
          bytesRead < 1 ||
          bytesRead > 4096 ||
          (await handle.stat()).size > 4096
        )
          throw new NativeControlError('FENCED');
        value = parseNativeReplyJson(bytes.subarray(0, bytesRead));
      } finally {
        await handle.close();
      }
      if (value === null || typeof value !== 'object' || Array.isArray(value))
        throw new NativeControlError('FENCED');
      const record = value as Readonly<Record<string, unknown>>;
      if (
        Object.keys(record).some(
          (key) =>
            ![
              'version',
              'leaseId',
              'targetId',
              'sessionId',
              'generation',
            ].includes(key),
        ) ||
        record['version'] !== 'native-local-lease/1'
      )
        throw new NativeControlError('FENCED');
      const stored = decodeNativeLease({
        leaseId: record['leaseId'],
        targetId: record['targetId'],
        sessionId: record['sessionId'],
        generation: record['generation'],
      });
      if (
        stored.leaseId !== lease.leaseId ||
        stored.targetId !== lease.targetId ||
        stored.sessionId !== lease.sessionId ||
        stored.generation !== lease.generation
      )
        throw new NativeControlError('FENCED');
      deadline.requireActive();
      if (this.phase !== 'releasing') throw new NativeControlError('FENCED');
      await unlink(file);
      await force(lock);
      deadline.requireActive();
      if (this.phase !== 'releasing') throw new NativeControlError('FENCED');
      // Refuse unexpected helper evidence rather than recursively deleting it.
      await rmdir(lock);
      await force(this.directory);
      deadline.requireActive();
    })();
    try {
      await awaitBrowserWork(work, deadline);
      this.phase = 'idle';
      this.lease = undefined;
      this.inode = undefined;
    } catch (error) {
      this.phase = 'fenced';
      throw error;
    }
  }
}
