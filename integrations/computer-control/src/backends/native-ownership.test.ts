import assert from 'node:assert/strict';
import {
  mkdtemp,
  readFile,
  readdir,
  rm,
  symlink,
  unlink,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { OperationDeadline } from '../deadline.ts';
import { NativeDirectoryOwnership } from './native-ownership.ts';

const lease = {
  leaseId: 'owned-lease',
  targetId: 'target',
  sessionId: 'session',
  generation: 'generation',
};
await test('native durable marker excludes another owner and ordinary contention remains reusable', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'native-owned-'));
  const deadline = new OperationDeadline(2000);
  const first = new NativeDirectoryOwnership(directory);
  const second = new NativeDirectoryOwnership(directory);
  try {
    assert.equal(await first.acquire(lease, deadline), 'acquired');
    const original = await readFile(
      join(directory, 'capture.lock', 'lease.json'),
      'utf8',
    );
    assert.equal(
      await second.acquire({ ...lease, leaseId: 'second' }, deadline),
      'busy',
    );
    assert.equal(
      await readFile(join(directory, 'capture.lock', 'lease.json'), 'utf8'),
      original,
    );
    await assert.rejects(second.releaseAfterQuiescence(deadline));
    await first.releaseAfterQuiescence(deadline);
    assert.equal(
      await second.acquire({ ...lease, leaseId: 'second' }, deadline),
      'acquired',
    );
    await second.releaseAfterQuiescence(deadline);
    assert.deepEqual(await readdir(directory), []);
  } finally {
    deadline.dispose();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('native ownership validates lease before phase or filesystem effects', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'native-invalid-lease-'));
  const deadline = new OperationDeadline(2000);
  try {
    const owner = new NativeDirectoryOwnership(directory);
    await assert.rejects(owner.acquire({ ...lease, sessionId: '' }, deadline));
    assert.deepEqual(await readdir(directory), []);
    assert.equal(await owner.acquire(lease, deadline), 'acquired');
    await owner.releaseAfterQuiescence(deadline);
    assert.throws(() => new NativeDirectoryOwnership('relative'));
  } finally {
    deadline.dispose();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('native release rejects tampered complete lease identity and retains its crash marker', async () => {
  for (const field of [
    'leaseId',
    'targetId',
    'sessionId',
    'generation',
    'version',
    'extra',
  ]) {
    const directory = await mkdtemp(join(tmpdir(), 'native-tampered-'));
    const deadline = new OperationDeadline(2000);
    try {
      const owner = new NativeDirectoryOwnership(directory);
      await owner.acquire(lease, deadline);
      await writeFile(
        join(directory, 'capture.lock', 'lease.json'),
        JSON.stringify({
          version: 'native-local-lease/1',
          ...lease,
          [field]: 'foreign',
        }),
      );
      await assert.rejects(owner.releaseAfterQuiescence(deadline));
      assert.equal(
        await new NativeDirectoryOwnership(directory).acquire(lease, deadline),
        'busy',
      );
      assert.deepEqual(await readdir(directory), ['capture.lock']);
    } finally {
      deadline.dispose();
      await rm(directory, { recursive: true, force: true });
    }
  }
});

await test('native metadata reads reject duplicate fields, oversized bytes and symlinks', async () => {
  for (const kind of ['duplicate', 'oversize', 'symlink']) {
    const directory = await mkdtemp(join(tmpdir(), 'native-metadata-'));
    const deadline = new OperationDeadline(2000);
    try {
      const owner = new NativeDirectoryOwnership(directory);
      await owner.acquire(lease, deadline);
      const file = join(directory, 'capture.lock', 'lease.json');
      if (kind === 'duplicate')
        await writeFile(
          file,
          '{"version":"native-local-lease/1","leaseId":"owned-lease","leaseId":"owned-lease","targetId":"target","sessionId":"session","generation":"generation"}',
        );
      else if (kind === 'oversize') await writeFile(file, 'x'.repeat(4097));
      else {
        const other = join(directory, 'other.json');
        await writeFile(
          other,
          JSON.stringify({ version: 'native-local-lease/1', ...lease }),
        );
        await unlink(file);
        await symlink(other, file);
      }
      await assert.rejects(owner.releaseAfterQuiescence(deadline));
      assert.equal(
        await new NativeDirectoryOwnership(directory).acquire(lease, deadline),
        'busy',
      );
    } finally {
      deadline.dispose();
      await rm(directory, { recursive: true, force: true });
    }
  }
});

await test('native cleanup never recursively deletes unexpected outstanding helper evidence', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'native-evidence-'));
  const deadline = new OperationDeadline(2000);
  try {
    const owner = new NativeDirectoryOwnership(directory);
    await owner.acquire(lease, deadline);
    const evidence = join(directory, 'capture.lock', 'outstanding.json');
    await writeFile(evidence, '{"ownedInput":"unknown"}');
    await assert.rejects(owner.releaseAfterQuiescence(deadline));
    assert.equal(await readFile(evidence, 'utf8'), '{"ownedInput":"unknown"}');
    assert.equal(
      await new NativeDirectoryOwnership(directory).acquire(lease, deadline),
      'busy',
    );
  } finally {
    deadline.dispose();
    await rm(directory, { recursive: true, force: true });
  }
});
