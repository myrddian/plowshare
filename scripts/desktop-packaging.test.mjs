import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { nativeApp } from '../plowshare-desktop/scripts/native-app.mjs';

function verifyBundle(bundle) {
  const info = JSON.parse(execFileSync('plutil', [
    '-convert', 'json', '-o', '-', join(bundle, 'Contents/Info.plist'),
  ], { encoding: 'utf8' }));
  assert.equal(info.CFBundleIdentifier, 'io.aeyer.plowshare');
  assert.equal(typeof info.NSLocalNetworkUsageDescription, 'string',
    'The signed app must explain its LAN access when launched outside Terminal');
  assert.match(info.NSLocalNetworkUsageDescription, /server/i);
  assert.ok(info.NSLocalNetworkUsageDescription.trim().length > 20);
  // Metadata must be present before signing, rather than patched into a signed bundle.
  execFileSync('codesign', ['--verify', '--deep', '--strict', bundle]);
}

test('developer native bundle declares LAN access before signing', async () => {
  const executable = await nativeApp();
  verifyBundle(dirname(dirname(dirname(executable))));
});

test('distributed desktop archive retains LAN usage metadata and a valid signature', async () => {
  const temporary = await mkdtemp(join(tmpdir(), 'plowshare desktop packaging '));
  try {
    const name = 'plowshare-desktop-0.1.0-darwin-arm64';
    const archive = resolve('build/distributions', name + '.tar.gz');
    const digest = createHash('sha256').update(await readFile(archive)).digest('hex');
    assert.equal(await readFile(archive + '.sha256', 'utf8'), `${digest}  ${name}.tar.gz\n`);
    execFileSync('tar', ['-xzf', archive, '-C', temporary]);
    verifyBundle(join(temporary, name, 'Plowshare.app'));
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});
