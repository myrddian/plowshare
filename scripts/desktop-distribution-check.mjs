import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { execFileSync, spawn } from 'node:child_process';
const temporary = await mkdtemp(join(tmpdir(), 'plowshare desktop install '));
try {
  execFileSync('tar', ['-xzf', resolve('build/distributions/plowshare-desktop-0.1.0-darwin-arm64.tar.gz'), '-C', temporary]);
  execFileSync('codesign', ['--verify', '--deep', '--strict', join(temporary, 'plowshare-desktop-0.1.0-darwin-arm64/Plowshare.app')]);
  const child = spawn(process.execPath, ['--experimental-strip-types', 'scripts/transport-smoke.mjs'], {
    cwd: resolve('plowshare-desktop'), stdio: 'inherit',
    env: { ...process.env, PLOWSHARE_PACKAGED_EXECUTABLE: join(temporary, 'plowshare-desktop-0.1.0-darwin-arm64/Plowshare.app/Contents/MacOS/Plowshare') },
  });
  const code = await new Promise((resolve, reject) => { child.once('error', reject); child.once('exit', resolve); });
  if (code !== 0) throw new Error(`Packaged desktop smoke failed (${code}).`);
} finally { await rm(temporary, { recursive: true, force: true }); }
