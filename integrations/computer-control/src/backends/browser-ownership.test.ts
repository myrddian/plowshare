import assert from 'node:assert/strict';
import { mkdtemp, readdir, rm, stat, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { BackendError } from '../contracts.ts';
import { OperationDeadline } from '../deadline.ts';
import { FakeClock } from '../contracts.test-support.ts';
import { DirectoryBrowserOwnership } from './browser-ownership.ts';
import type { BrowserOwnershipDisk } from './browser-ownership.ts';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((accept, refuse) => {
    resolve = accept;
    reject = refuse;
  });
  return { promise, resolve, reject };
}

class MemoryDisk implements BrowserOwnershipDisk {
  readonly markers = new Set<string>();
  readonly events: string[] = [];
  prepareStep: (() => Promise<void>) | undefined;
  createStep: (() => Promise<void>) | undefined;
  forceStep: (() => Promise<void>) | undefined;
  removeStep: (() => Promise<void>) | undefined;
  async prepare(): Promise<void> {
    this.events.push('prepare');
    await this.prepareStep?.();
  }
  async create(marker: string): Promise<void> {
    this.events.push('create');
    await this.createStep?.();
    if (this.markers.has(marker)) throw new Error('Exists');
    this.markers.add(marker);
  }
  async force(): Promise<void> {
    this.events.push('force');
    await this.forceStep?.();
  }
  async remove(marker: string): Promise<void> {
    this.events.push('remove');
    await this.removeStep?.();
    if (!this.markers.delete(marker)) throw new Error('Absent');
  }
}

const uncertain = (error: unknown): boolean =>
  error instanceof BackendError && error.physicalSettlement === 'uncertain';

await test('ownership requires an explicit absolute bounded directory', () => {
  for (const path of ['', 'relative', '/bad\npath', `/${'x'.repeat(4096)}`])
    assert.throws(() => new DirectoryBrowserOwnership(path));
});

await test('durable target key excludes a second host and contains no page or binding metadata', async () => {
  const root = await mkdtemp(join(tmpdir(), 'computer-browser-owner-'));
  const directory = join(root, 'ownership');
  const deadline = new OperationDeadline(2000);
  try {
    const first = new DirectoryBrowserOwnership(directory);
    const lease = await first.acquire('fixture-target', deadline);
    const names = await readdir(directory);
    assert.equal(names.length, 1);
    const name = names[0];
    assert.ok(name !== undefined && /^[a-f0-9]{64}\.lease$/u.test(name));
    assert.deepEqual(await readdir(join(directory, name)), []);
    assert.equal((await stat(directory)).mode & 0o777, 0o700);
    assert.equal((await stat(join(directory, name))).mode & 0o777, 0o700);
    await assert.rejects(
      new DirectoryBrowserOwnership(directory).acquire(
        'fixture-target',
        deadline,
      ),
      uncertain,
    );
    await lease.releaseAfterQuiescence(deadline);
    assert.deepEqual(await readdir(directory), []);
    const next = await new DirectoryBrowserOwnership(directory).acquire(
      'fixture-target',
      deadline,
    );
    await next.releaseAfterQuiescence(deadline);
  } finally {
    deadline.dispose();
    await rm(root, { recursive: true, force: true });
  }
});

await test('ownership refuses symlink storage and leaves the destination untouched', async () => {
  const root = await mkdtemp(join(tmpdir(), 'computer-browser-owner-'));
  const destination = join(root, 'destination');
  const deadline = new OperationDeadline(2000);
  try {
    const owner = new DirectoryBrowserOwnership(destination);
    const lease = await owner.acquire('fixture', deadline);
    await lease.releaseAfterQuiescence(deadline);
    const alias = join(root, 'alias');
    await symlink(destination, alias);
    await assert.rejects(
      new DirectoryBrowserOwnership(alias).acquire('fixture', deadline),
      uncertain,
    );
    assert.deepEqual(await readdir(destination), []);
  } finally {
    deadline.dispose();
    await rm(root, { recursive: true, force: true });
  }
});

await test('marker and parent are forced before a lease is returned; fencing never removes it', async () => {
  const disk = new MemoryDisk();
  const deadline = new OperationDeadline(2000);
  try {
    const owner = new DirectoryBrowserOwnership('/fixture/ownership', disk);
    const lease = await owner.acquire('fixture', deadline);
    assert.deepEqual(disk.events, ['prepare', 'create', 'force', 'force']);
    lease.fence();
    await assert.rejects(lease.releaseAfterQuiescence(deadline), uncertain);
    assert.equal(disk.markers.size, 1);
    assert.ok(!disk.events.includes('remove'));
    await assert.rejects(owner.acquire('fixture', deadline), uncertain);
  } finally {
    deadline.dispose();
  }
});

await test('cancellation during directory preparation prevents late marker creation', async () => {
  const disk = new MemoryDisk();
  const pending = deferred<void>();
  disk.prepareStep = () => pending.promise;
  const deadline = new OperationDeadline(2000);
  const owner = new DirectoryBrowserOwnership('/fixture/ownership', disk);
  try {
    const acquisition = owner.acquire('fixture', deadline);
    deadline.cancel();
    await assert.rejects(acquisition, uncertain);
    pending.resolve();
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(disk.markers.size, 0);
    assert.deepEqual(disk.events, ['prepare']);
  } finally {
    deadline.dispose();
  }
});

await test('late mkdir success after expiry never yields a lease or deletes the marker', async () => {
  const disk = new MemoryDisk();
  const entered = deferred<void>();
  const pending = deferred<void>();
  disk.createStep = () => {
    entered.resolve();
    return pending.promise;
  };
  const clock = new FakeClock();
  const deadline = new OperationDeadline(20, clock);
  const owner = new DirectoryBrowserOwnership('/fixture/ownership', disk);
  try {
    const acquisition = owner.acquire('fixture', deadline);
    await entered.promise;
    clock.advance(20);
    await assert.rejects(acquisition, uncertain);
    pending.resolve();
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(disk.markers.size, 1);
    assert.deepEqual(disk.events, ['prepare', 'create']);
    const later = new OperationDeadline(1000);
    try {
      await assert.rejects(owner.acquire('fixture', later), uncertain);
    } finally {
      later.dispose();
    }
  } finally {
    deadline.dispose();
  }
});

await test('failed force retains the exclusive marker without a usable lease', async () => {
  const disk = new MemoryDisk();
  disk.forceStep = async () => {
    throw new Error('private diagnostic');
  };
  const deadline = new OperationDeadline(2000);
  try {
    const owner = new DirectoryBrowserOwnership('/fixture/ownership', disk);
    await assert.rejects(
      owner.acquire('fixture', deadline),
      (error: unknown) => {
        assert.ok(uncertain(error));
        assert.ok(
          error instanceof Error &&
            !error.message.includes('private diagnostic'),
        );
        return true;
      },
    );
    assert.equal(disk.markers.size, 1);
    assert.ok(!disk.events.includes('remove'));
  } finally {
    deadline.dispose();
  }
});

await test('release retains occupancy through directory force and never deletes a later replacement', async () => {
  const disk = new MemoryDisk();
  const deadline = new OperationDeadline(2000);
  const owner = new DirectoryBrowserOwnership('/fixture/ownership', disk);
  try {
    const lease = await owner.acquire('fixture', deadline);
    const pending = deferred<void>();
    const entered = deferred<void>();
    disk.forceStep = () => {
      entered.resolve();
      return pending.promise;
    };
    const cleanup = new OperationDeadline(1000);
    const releasing = lease.releaseAfterQuiescence(cleanup);
    await entered.promise;
    await assert.rejects(owner.acquire('fixture', deadline), uncertain);
    cleanup.cancel();
    await assert.rejects(releasing, uncertain);
    disk.forceStep = undefined;
    const other = new DirectoryBrowserOwnership('/fixture/ownership', disk);
    const replacement = await other.acquire('fixture', deadline);
    pending.resolve();
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(disk.markers.size, 1);
    assert.equal(disk.events.filter((event) => event === 'remove').length, 1);
    await assert.rejects(lease.releaseAfterQuiescence(deadline), uncertain);
    await replacement.releaseAfterQuiescence(deadline);
    cleanup.dispose();
  } finally {
    deadline.dispose();
  }
});
