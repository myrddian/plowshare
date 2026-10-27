import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { nativeApp } from '../plowshare-desktop/scripts/native-app.mjs';
import {
  executableUuidOffset,
  withMacAppIdentity,
} from '../plowshare-desktop/scripts/macos-identity.mjs';

let controlDirectory;
let stockExecutable;
const uuid = (bytes) =>
  bytes.subarray(executableUuidOffset(bytes), executableUuidOffset(bytes) + 16);

// Use the packager's pinned runtime as the control. The npm Electron binary may
// be absent on a build worker; packaging downloads/verifies its own runtime.
before(async () => {
  controlDirectory = await mkdtemp(
    join(tmpdir(), 'plowshare identity control '),
  );
  const source = join(controlDirectory, 'source');
  await mkdir(source);
  await writeFile(
    join(source, 'package.json'),
    JSON.stringify({
      name: 'identity-control',
      version: '0.1.0',
      main: 'main.js',
    }),
  );
  await writeFile(
    join(source, 'main.js'),
    '// Packaging fixture; never launched.\n',
  );
  const requireDesktop = createRequire(
    resolve('plowshare-desktop/package.json'),
  );
  const { packager } = requireDesktop('@electron/packager');
  const manifest = JSON.parse(
    await readFile('plowshare-desktop/package.json', 'utf8'),
  );
  const [control] = await packager({
    dir: source,
    out: join(controlDirectory, 'output'),
    name: 'IdentityControl',
    appBundleId: 'example.identity-control',
    platform: 'darwin',
    arch: process.arch,
    electronVersion: manifest.devDependencies.electron,
    prune: false,
  });
  stockExecutable = join(
    control,
    'IdentityControl.app/Contents/MacOS/IdentityControl',
  );
});
after(async () => {
  if (controlDirectory)
    await rm(controlDirectory, { recursive: true, force: true });
});

async function verifyBundle(bundle) {
  const info = JSON.parse(
    execFileSync(
      'plutil',
      ['-convert', 'json', '-o', '-', join(bundle, 'Contents/Info.plist')],
      { encoding: 'utf8' },
    ),
  );
  assert.equal(info.CFBundleIdentifier, 'io.aeyer.plowshare');
  for (const key of [
    'CFBundleName',
    'CFBundleDisplayName',
    'CFBundleExecutable',
  ]) {
    assert.equal(info[key], 'Plowshare');
  }
  assert.equal(
    typeof info.NSLocalNetworkUsageDescription,
    'string',
    'The signed app must explain its LAN access when launched outside Terminal',
  );
  assert.match(info.NSLocalNetworkUsageDescription, /server/i);
  assert.ok(info.NSLocalNetworkUsageDescription.trim().length > 20);
  const launcher = await readFile(join(bundle, 'Contents/MacOS/Plowshare'));
  const stock = await readFile(stockExecutable);
  assert.notDeepEqual(
    uuid(launcher),
    uuid(stock),
    'The app must not share the stock Electron executable identity with other apps',
  );
  assert.deepEqual(
    uuid(launcher),
    uuid(withMacAppIdentity(stock, info.CFBundleIdentifier)),
    'Fresh builds in either path must derive the same application/runtime identity',
  );
  for (const suffix of ['', ' (GPU)', ' (Plugin)', ' (Renderer)']) {
    const helper = JSON.parse(
      execFileSync(
        'plutil',
        [
          '-convert',
          'json',
          '-o',
          '-',
          join(
            bundle,
            `Contents/Frameworks/Plowshare Helper${suffix}.app/Contents/Info.plist`,
          ),
        ],
        { encoding: 'utf8' },
      ),
    );
    assert.equal(helper.CFBundleIdentifier, 'io.aeyer.plowshare.helper');
    assert.equal(helper.CFBundleDisplayName, `Plowshare Helper${suffix}`);
    assert.equal(helper.CFBundleExecutable, `Plowshare Helper${suffix}`);
  }
  // Metadata must be present before signing, rather than patched into a signed bundle.
  execFileSync('codesign', ['--verify', '--deep', '--strict', bundle]);
}

test('developer native bundle declares LAN access before signing', async () => {
  const stockBefore = await readFile(stockExecutable);
  const executable = await nativeApp();
  await verifyBundle(dirname(dirname(dirname(executable))));
  assert.deepEqual(
    await readFile(stockExecutable),
    stockBefore,
    'Shared runtime stays untouched',
  );
});

test('distributed desktop archive retains LAN usage metadata and a valid signature', async () => {
  const temporary = await mkdtemp(
    join(tmpdir(), 'plowshare desktop packaging '),
  );
  try {
    const name = 'plowshare-desktop-0.1.0-darwin-arm64';
    const archive = resolve('build/distributions', name + '.tar.gz');
    const digest = createHash('sha256')
      .update(await readFile(archive))
      .digest('hex');
    assert.equal(
      await readFile(archive + '.sha256', 'utf8'),
      `${digest}  ${name}.tar.gz\n`,
    );
    execFileSync('tar', ['-xzf', archive, '-C', temporary]);
    await verifyBundle(join(temporary, name, 'Plowshare.app'));
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});
