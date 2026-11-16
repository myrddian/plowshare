import { createHash } from 'node:crypto';
import { lstat, mkdir, open, rmdir } from 'node:fs/promises';
import { dirname, isAbsolute, join } from 'node:path';
import { privateDirectory } from 'plowshare-client-node/connections';
import { BackendError, computerId } from '../contracts.ts';
import type { OperationDeadline } from '../deadline.ts';

/** A lease is retained across all input, including failures whose delivery is uncertain. */
export interface BrowserOwnershipLease {
  /** Retain an existing marker when physical quiescence cannot be established. */
  fence(): void;
  /** Call only after all owned input/RPCs and the isolated context have physically settled. */
  releaseAfterQuiescence(deadline: OperationDeadline): Promise<void>;
}

export interface BrowserOwnership {
  acquire(
    targetId: string,
    deadline: OperationDeadline,
  ): Promise<BrowserOwnershipLease>;
}

/** Filesystem operations remain owned after deadline expiry; no timeout claims OS cancellation. */
export interface BrowserOwnershipDisk {
  prepare(directory: string): Promise<void>;
  create(marker: string): Promise<void>;
  force(directory: string): Promise<void>;
  remove(marker: string): Promise<void>;
}

async function forceDirectory(path: string): Promise<void> {
  const directory = await open(path, 'r');
  try {
    await directory.sync();
  } finally {
    await directory.close();
  }
}

const disk: BrowserOwnershipDisk = {
  async prepare(directory) {
    // Require an existing parent rather than creating an unbounded chain whose
    // durability cannot be established by syncing only the immediate parent.
    const parent = dirname(directory);
    const info = await lstat(parent);
    if (!info.isDirectory() || info.isSymbolicLink())
      throw new Error('Browser ownership parent must be a real directory');
    await privateDirectory(directory);
    await forceDirectory(parent);
  },
  async create(marker) {
    await mkdir(marker, { mode: 0o700 });
  },
  force: forceDirectory,
  async remove(marker) {
    // An empty marker contains no identity, URL, page content or credentials.
    // Refuse unexpected contents instead of recursively deleting local evidence.
    await rmdir(marker);
  },
};

type Phase = 'acquiring' | 'held' | 'releasing' | 'released' | 'fenced';
interface OwnershipRecord {
  phase: Phase;
}

/**
 * Cross-process exclusion for a configured browser target, independent of SDK
 * binding or generation. Interrupted hosts leave a durable marker. Recovery is
 * an explicit operator decision; this class never uses age or PID guesses.
 * After caller-proven physical quiescence, rmdir may succeed before a parent
 * fsync fails/expires. That instance stays fenced, although the pathname may
 * already be absent and a different process may safely acquire the quiet target.
 * No late cleanup retries removal or touches a replacement marker.
 */
export class DirectoryBrowserOwnership implements BrowserOwnership {
  private readonly records = new Map<string, OwnershipRecord>();

  constructor(
    private readonly directory: string,
    private readonly filesystem: BrowserOwnershipDisk = disk,
  ) {
    if (
      typeof directory !== 'string' ||
      directory.length > 4096 ||
      !isAbsolute(directory) ||
      Array.from(directory).some(
        (character) => (character.codePointAt(0) ?? 0) < 32,
      )
    )
      throw new Error(
        'Browser ownershipDirectory must be explicit and absolute',
      );
  }

  private async bounded<T>(
    work: Promise<T>,
    record: OwnershipRecord,
    deadline: OperationDeadline,
  ): Promise<T> {
    let detach = () => {};
    const expired = new Promise<never>((_resolve, reject) => {
      const abort = () => {
        record.phase = 'fenced';
        reject(new BackendError('DEADLINE_EXPIRED', 'uncertain'));
      };
      deadline.signal.addEventListener('abort', abort, { once: true });
      detach = () => deadline.signal.removeEventListener('abort', abort);
      if (deadline.signal.aborted) abort();
    });
    try {
      const result = await Promise.race([work, expired]);
      deadline.requireActive();
      return result;
    } catch {
      record.phase = 'fenced';
      throw new BackendError(
        deadline.signal.aborted ? 'DEADLINE_EXPIRED' : 'CAPTURE_FAILED',
        'uncertain',
      );
    } finally {
      // Promise.race owns late rejection as well as late success. The worker
      // checks phase between every step and never removes a fenced marker.
      detach();
    }
  }

  async acquire(
    targetId: string,
    deadline: OperationDeadline,
  ): Promise<BrowserOwnershipLease> {
    computerId(targetId);
    deadline.requireActive();
    const key = createHash('sha256')
      .update(`computer-control-browser-target/1\0${targetId}`)
      .digest('hex');
    if (this.records.has(key))
      throw new BackendError('CAPTURE_FAILED', 'uncertain');
    const record: OwnershipRecord = { phase: 'acquiring' };
    // Reserve before the first await, including directory preparation.
    this.records.set(key, record);
    const marker = join(this.directory, `${key}.lease`);
    const requirePhase = (phase: Phase) => {
      deadline.requireActive();
      if (record.phase !== phase)
        throw new BackendError('CAPTURE_FAILED', 'uncertain');
    };
    const acquisition = (async () => {
      await this.filesystem.prepare(this.directory);
      requirePhase('acquiring');
      await this.filesystem.create(marker);
      requirePhase('acquiring');
      await this.filesystem.force(marker);
      requirePhase('acquiring');
      await this.filesystem.force(this.directory);
      requirePhase('acquiring');
      record.phase = 'held';
    })();
    await this.bounded(acquisition, record, deadline);
    return Object.freeze({
      fence: () => {
        if (record.phase !== 'released') record.phase = 'fenced';
      },
      releaseAfterQuiescence: async (cleanup: OperationDeadline) => {
        cleanup.requireActive();
        if (record.phase !== 'held')
          throw new BackendError('CAPTURE_FAILED', 'uncertain');
        record.phase = 'releasing';
        const release = (async () => {
          await this.filesystem.remove(marker);
          cleanup.requireActive();
          if (record.phase !== 'releasing')
            throw new BackendError('CAPTURE_FAILED', 'uncertain');
          await this.filesystem.force(this.directory);
          cleanup.requireActive();
          if (record.phase !== 'releasing')
            throw new BackendError('CAPTURE_FAILED', 'uncertain');
          record.phase = 'released';
          this.records.delete(key);
        })();
        await this.bounded(release, record, cleanup);
      },
    });
  }
}
