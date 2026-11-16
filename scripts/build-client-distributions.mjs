import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

// Native client packaging has no Java/server dependency. Keep its explicit
// package graph ordered: linked SDK consumers need declarations before tsc.
const root = fileURLToPath(new URL('../', import.meta.url));
const deadline = Date.now() + 60 * 60 * 1000;
const pnpm = process.platform === 'win32' ? 'pnpm.cmd' : 'pnpm';
const expected = JSON.parse(
  readFileSync(join(root, 'plowshare-cli/package.json')),
).packageManager.split('@')[1];

function run(program, args, directory = root) {
  const remaining = deadline - Date.now();
  if (remaining <= 0)
    throw new Error('Native client build exceeded its one-hour budget.');
  execFileSync(program, args, {
    cwd: directory,
    stdio: 'inherit',
    timeout: Math.min(remaining, 15 * 60 * 1000),
  });
}

function install(directory) {
  const args = [
    'install',
    '--frozen-lockfile',
    '--config.confirmModulesPurge=false',
  ];
  if (process.platform === 'win32')
    run('cmd.exe', ['/d', '/s', '/c', pnpm, ...args], directory);
  else run(pnpm, args, directory);
}

const pnpmVersion = execFileSync(
  process.platform === 'win32' ? 'cmd.exe' : pnpm,
  process.platform === 'win32'
    ? ['/d', '/s', '/c', pnpm, '--version']
    : ['--version'],
  { encoding: 'utf8', timeout: 30_000 },
).trim();
if (pnpmVersion !== expected)
  throw new Error(`Expected pnpm ${expected}; got ${pnpmVersion}.`);

for (const name of [
  'sdk/typescript',
  'sdk/node',
  'integrations/mcp',
  'plowshare-tui',
  'plowshare-cli',
  'plowshare-mcp',
  'plowshare-desktop',
]) {
  const directory = join(root, name);
  install(directory);
  if (name === 'sdk/typescript' || name === 'sdk/node') {
    run(
      process.execPath,
      [
        join(root, 'scripts/build-javascript-sdk.mjs'),
        name === 'sdk/node' ? 'node' : 'typescript',
      ],
      directory,
    );
  } else if (name === 'plowshare-desktop') {
    run(
      process.execPath,
      ['node_modules/typescript/bin/tsc', '--noEmit'],
      directory,
    );
    run(process.execPath, ['scripts/build.mjs'], directory);
  } else if (name === 'plowshare-tui') {
    run(
      process.execPath,
      ['node_modules/typescript/bin/tsc', '-b', '--force'],
      directory,
    );
  } else {
    run(
      process.execPath,
      ['node_modules/typescript/bin/tsc', '-p', 'tsconfig.json'],
      directory,
    );
  }
}
run(process.execPath, [join(root, 'scripts/distributions.mjs'), 'clients']);
run(process.execPath, [join(root, 'scripts/client-distribution-smoke.mjs')]);
