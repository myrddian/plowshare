import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, readFile, rename, rm } from 'node:fs/promises';
import { join } from 'node:path';

/** Use the official binary: package-manager Node builds may depend on Homebrew libraries. */
export async function distributionRuntime(root) {
  const pin = JSON.parse(await readFile(join(root, 'scripts/distribution-runtime.json'), 'utf8'));
  if (process.platform !== pin.platform || process.arch !== pin.arch) throw new Error('The pinned native runtime requires a macOS Apple Silicon build host.');
  const directory = join(root, 'build/runtime');
  const name = `node-v${pin.version}-${pin.platform}-${pin.arch}`;
  const archive = join(directory, name + '.tar.gz');
  await mkdir(directory, { recursive: true });
  let bytes;
  try { bytes = await readFile(archive); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  if (!bytes) {
    const temporary = archive + '.download';
    try {
      execFileSync('curl', ['--fail', '--location', '--silent', '--show-error', `https://nodejs.org/dist/v${pin.version}/${name}.tar.gz`, '--output', temporary]);
      bytes = await readFile(temporary);
      if (createHash('sha256').update(bytes).digest('hex') !== pin.sha256) throw new Error('Downloaded Node runtime checksum does not match the pin.');
      await rename(temporary, archive);
    } finally { await rm(temporary, { force: true }); }
  }
  if (createHash('sha256').update(bytes).digest('hex') !== pin.sha256) throw new Error('Cached Node runtime checksum does not match the pin. Remove the cached archive and rebuild.');
  execFileSync('tar', ['-xzf', archive, '-C', directory]);
  const executable = join(directory, name, 'bin/node');
  if (execFileSync(executable, ['--version'], { encoding: 'utf8' }).trim() !== `v${pin.version}`) throw new Error('Pinned runtime version mismatch.');
  return { ...pin, executable, license: join(directory, name, 'LICENSE') };
}
