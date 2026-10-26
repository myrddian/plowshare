import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  mkdtemp,
  mkdir,
  readFile,
  rm,
  stat,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  executableUuidOffset,
  prepareMacAppIdentity,
  withMacAppIdentity,
} from './macos-identity.mjs';

function executable(cpu = 0x100000c, original = 1) {
  const bytes = Buffer.alloc(64, 0xa5);
  bytes.writeUInt32LE(0xfeedfacf, 0);
  bytes.writeUInt32LE(cpu, 4);
  bytes.writeUInt32LE(0, 8);
  bytes.writeUInt32LE(2, 12);
  bytes.writeUInt32LE(1, 16);
  bytes.writeUInt32LE(24, 20);
  bytes.writeUInt32LE(0x1b, 32);
  bytes.writeUInt32LE(24, 36);
  bytes.fill(original, 40, 56);
  return bytes;
}
const uuid = (bytes) =>
  bytes.subarray(executableUuidOffset(bytes), executableUuidOffset(bytes) + 16);

test('fresh builds keep one app identity across copies and separate apps, CPUs and runtimes', () => {
  const stock = executable();
  const before = Buffer.from(stock);
  const result = withMacAppIdentity(stock, 'io.aeyer.plowshare');
  assert.deepEqual(
    stock,
    before,
    'shared Electron input must remain untouched',
  );
  assert.notDeepEqual(uuid(result), uuid(stock));
  assert.deepEqual(
    result,
    withMacAppIdentity(Buffer.from(stock), 'io.aeyer.plowshare'),
  );
  assert.notDeepEqual(
    uuid(result),
    uuid(withMacAppIdentity(stock, 'example.other-app')),
  );
  assert.notDeepEqual(
    uuid(result),
    uuid(withMacAppIdentity(executable(0x1000007), 'io.aeyer.plowshare')),
  );
  assert.notDeepEqual(
    uuid(result),
    uuid(withMacAppIdentity(executable(0x100000c, 2), 'io.aeyer.plowshare')),
  );
  assert.equal(uuid(result)[6] >>> 4, 8);
  assert.equal(uuid(result)[8] >>> 6, 2);
  // The launcher code/load layout stays byte-identical outside the UUID slot.
  assert.deepEqual(result.subarray(0, 40), stock.subarray(0, 40));
  assert.deepEqual(result.subarray(56), stock.subarray(56));
});

test('invalid identifiers and malformed or unsupported images fail before mutation', () => {
  const cases = [
    ['short header', (bytes) => bytes.subarray(0, 20)],
    [
      'universal image',
      (bytes) => {
        bytes.writeUInt32BE(0xcafebabe, 0);
        return bytes;
      },
    ],
    [
      'framework image',
      (bytes) => {
        bytes.writeUInt32LE(6, 12);
        return bytes;
      },
    ],
    [
      'out of bounds table',
      (bytes) => {
        bytes.writeUInt32LE(1000, 20);
        return bytes;
      },
    ],
    [
      'excessive command count',
      (bytes) => {
        bytes.writeUInt32LE(1000, 16);
        return bytes;
      },
    ],
    [
      'zero command length',
      (bytes) => {
        bytes.writeUInt32LE(0, 36);
        return bytes;
      },
    ],
    [
      'misaligned command',
      (bytes) => {
        bytes.writeUInt32LE(23, 36);
        return bytes;
      },
    ],
    [
      'oversized command',
      (bytes) => {
        bytes.writeUInt32LE(32, 36);
        return bytes;
      },
    ],
    [
      'missing UUID',
      (bytes) => {
        bytes.writeUInt32LE(0x19, 32);
        return bytes;
      },
    ],
    [
      'invalid UUID size',
      (bytes) => {
        bytes.writeUInt32LE(16, 36);
        return bytes;
      },
    ],
    [
      'incomplete table',
      (bytes) => {
        bytes.writeUInt32LE(0, 16);
        return bytes;
      },
    ],
    [
      'duplicate UUID',
      (bytes) => {
        const duplicate = Buffer.concat([
          bytes.subarray(0, 56),
          bytes.subarray(32, 56),
        ]);
        duplicate.writeUInt32LE(2, 16);
        duplicate.writeUInt32LE(48, 20);
        return duplicate;
      },
    ],
  ];
  for (const [name, change] of cases) {
    const bytes = change(executable());
    const before = Buffer.from(bytes);
    assert.throws(() => withMacAppIdentity(bytes, 'io.aeyer.plowshare'), name);
    assert.deepEqual(bytes, before, name);
  }
  for (const identifier of [
    undefined,
    '',
    'with spaces',
    'no-domain',
    'example/escape',
  ]) {
    assert.throws(() => withMacAppIdentity(executable(), identifier));
  }
});

test('packager hook touches only the extracted launcher and preserves its executable mode', async () => {
  const buildPath = await mkdtemp(join(tmpdir(), 'plowshare identity '));
  try {
    const folder = join(buildPath, 'Electron.app/Contents/MacOS');
    await mkdir(folder, { recursive: true });
    const path = join(folder, 'Electron');
    const stock = executable();
    await writeFile(path, stock, { mode: 0o755 });
    const framework = join(folder, 'framework-fixture');
    await writeFile(framework, stock);
    await assert.rejects(
      prepareMacAppIdentity({ buildPath, platform: 'linux' }),
    );
    assert.deepEqual(await readFile(path), stock);
    await prepareMacAppIdentity({ buildPath, platform: 'darwin' });
    assert.notDeepEqual(uuid(await readFile(path)), uuid(stock));
    assert.equal((await stat(path)).mode & 0o777, 0o755);
    assert.deepEqual(await readFile(framework), stock);
    await writeFile(path, Buffer.from('unrecognized executable'));
    await assert.rejects(
      prepareMacAppIdentity({ buildPath, platform: 'darwin' }),
    );
    assert.equal(await readFile(path, 'utf8'), 'unrecognized executable');
  } finally {
    await rm(buildPath, { recursive: true, force: true });
  }
});
