import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

// Exercise the distributable itself with no development runtime on PATH.
// This does not claim a GUI/session test or contact a deployed server.
const root = fileURLToPath(new URL('../', import.meta.url));
const output = join(root, 'build/distributions');
const version = JSON.parse(
  await readFile(join(root, 'plowshare-cli/package.json'), 'utf8'),
).version;
const target = `${process.platform}-${process.arch}`;
const executable = join(
  output,
  `plowshare-${version}-${target}${process.platform === 'win32' ? '.exe' : ''}`,
);
const isolated = await mkdtemp(join(tmpdir(), 'plowshare-distribution-'));
try {
  const env = { ...process.env, PATH: '', PLOWSHARE_CONFIG_DIR: isolated };
  function launch(args) {
    return execFileSync(executable, args, {
      cwd: isolated,
      env,
      encoding: 'utf8',
      timeout: 30_000,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
  }
  assert.match(
    launch(['--version']),
    new RegExp(`Plowshare .*${target}.*bundled Node`),
  );
  assert.match(launch(['--help']), /plowshare/);
  assert.match(launch(['talk', '--help']), /plowshare talk/);
  for (const file of await readdir(output)) {
    if (!file.endsWith('.sha256')) continue;
    const artifact = file.slice(0, -7);
    const hash = createHash('sha256')
      .update(await readFile(join(output, artifact)))
      .digest('hex');
    assert.equal(
      (await readFile(join(output, file), 'utf8')).trim(),
      `${hash}  ${artifact}`,
    );
  }
  console.log(
    JSON.stringify({
      target,
      standaloneVersionHelpAndTuiHelp: true,
      checksums: true,
      guiSessionTested: false,
    }),
  );
} finally {
  await rm(isolated, { recursive: true, force: true });
}
