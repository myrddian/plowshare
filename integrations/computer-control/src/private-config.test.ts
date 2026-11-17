import assert from 'node:assert/strict';
import { mkdtemp, writeFile, chmod, symlink, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { OperationDeadline } from './deadline.ts';
import { FakeClock } from './contracts.test-support.ts';
import {
  FileComputerConfigReader,
  parsePrivateConfigJson,
} from './private-config.ts';
import type { PrivateConfigFile } from './private-config.ts';
import { hostConfigSource } from './private-config.test-support.ts';

await test('private config reads one regular private POSIX file and rejects link/public/oversize/invalid encoding', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'computer-config-'));
  const path = join(directory, 'config.json'),
    link = join(directory, 'link');
  const reader = new FileComputerConfigReader(),
    deadline = new OperationDeadline(2000);
  try {
    await writeFile(path, JSON.stringify(hostConfigSource), { mode: 0o600 });
    assert.equal(
      (await reader.read(path, deadline)).origin,
      'https://fixture.invalid',
    );
    await symlink(path, link);
    await assert.rejects(reader.read(link, deadline));
    await chmod(path, 0o644);
    await assert.rejects(reader.read(path, deadline), /CONFIG_PERMISSIONS/);
    await chmod(path, 0o600);
    await writeFile(path, new Uint8Array(65537));
    await assert.rejects(reader.read(path, deadline), /CONFIG_LIMIT/);
    await writeFile(path, new Uint8Array([255]));
    await assert.rejects(reader.read(path, deadline), /CONFIG_ENCODING/);
  } finally {
    deadline.dispose();
    await rm(directory, { recursive: true });
  }
});

await test('strict JSON rejects escaped duplicate aliases, deep input, and malformed DTO without reflecting source', () => {
  for (const source of [
    '{"x":1,"\\u0078":2}',
    '['.repeat(65) + '0' + ']'.repeat(65),
    '{private-secret',
  ])
    assert.throws(() => parsePrivateConfigJson(source), {
      message: 'CONFIG_JSON',
    });
});

await test('expired read keeps its late FD owner busy until original close settles, no late read', async () => {
  const clock = new FakeClock(),
    deadline = new OperationDeadline(2000, clock);
  let opened: ((file: PrivateConfigFile) => void) | undefined,
    reads = 0,
    closes = 0;
  const reader = new FileComputerConfigReader({
    uid: 1,
    open: () =>
      new Promise((resolve) => {
        opened = resolve;
      }),
  });
  const pending = reader.read('/private/fixture', deadline);
  clock.advance(2001);
  await assert.rejects(pending, /DEADLINE_EXPIRED/);
  const cleanup = new OperationDeadline(2000, clock);
  await assert.rejects(reader.read('/private/fixture', cleanup), /CONFIG_BUSY/);
  assert.ok(opened);
  opened({
    metadata: async () => ({ regular: true, size: 1, uid: 1, mode: 0o600 }),
    read: async () => {
      reads++;
      return 0;
    },
    close: async () => {
      closes++;
    },
  });
  await reader.drain(cleanup);
  assert.equal(reads, 0);
  assert.equal(closes, 1);
  deadline.dispose();
  cleanup.dispose();
});

await test('one FD handles short reads, rejects changed size/malformed permissions and owns failed close', async () => {
  const bytes = new TextEncoder().encode(JSON.stringify(hostConfigSource));
  let closed = 0,
    position = 0;
  const reader = new FileComputerConfigReader({
    uid: 7,
    open: async () => ({
      metadata: async () => ({ regular: true, size: 1, uid: 7, mode: 0o600 }),
      read: async (target, offset) => {
        const n = Math.min(7, bytes.length - position);
        target.set(bytes.subarray(position, position + n), offset);
        position += n;
        return n;
      },
      close: async () => {
        closed++;
      },
    }),
  });
  const deadline = new OperationDeadline(2000);
  try {
    assert.equal(
      (await reader.read('/fixture', deadline)).binding.project,
      'fixture',
    );
    assert.equal(closed, 1);
  } finally {
    deadline.dispose();
  }
});

await test('failed FD close permanently fences reader and drain without replacement open or repeated close', async () => {
  let opens = 0,
    closes = 0;
  const reader = new FileComputerConfigReader({
    uid: 1,
    open: async () => {
      opens++;
      return {
        metadata: async () => ({
          regular: false,
          size: 0,
          uid: 1,
          mode: 0o600,
        }),
        read: async () => 0,
        close: async () => {
          closes++;
          throw new Error('private close error');
        },
      };
    },
  });
  const deadline = new OperationDeadline(2000);
  try {
    await assert.rejects(
      reader.read('/fixture', deadline),
      /CONFIG_CLEANUP_UNCERTAIN/,
    );
    await assert.rejects(
      reader.read('/fixture', deadline),
      /CONFIG_CLEANUP_UNCERTAIN/,
    );
    await assert.rejects(reader.drain(deadline), /CONFIG_CLEANUP_UNCERTAIN/);
    assert.equal(opens, 1);
    assert.equal(closes, 1);
  } finally {
    deadline.dispose();
  }
});
