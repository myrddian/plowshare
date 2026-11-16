import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, readFile, rename, rm } from 'node:fs/promises';
import { join } from 'node:path';

/** Use the official binary: package-manager Node builds may depend on Homebrew libraries. */
export async function distributionRuntime(root) {
  const manifest =
    process.platform === 'win32'
      ? 'distribution-runtime.windows.json'
      : 'distribution-runtime.json';
  const pin = JSON.parse(
    await readFile(join(root, 'scripts', manifest), 'utf8'),
  );
  if (process.platform !== pin.platform || process.arch !== pin.arch)
    throw new Error('Native clients require macOS/arm64 or Windows/x64.');
  const directory = join(root, 'build/runtime');
  // Node's archive naming uses "win", while process.platform uses "win32".
  const archivePlatform = pin.platform === 'win32' ? 'win' : pin.platform;
  const name = `node-v${pin.version}-${archivePlatform}-${pin.arch}`;
  const extension = process.platform === 'win32' ? '.zip' : '.tar.gz';
  const archive = join(directory, name + extension);
  await mkdir(directory, { recursive: true });
  let bytes;
  try {
    bytes = await readFile(archive);
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  if (!bytes) {
    const temporary = archive + '.download';
    try {
      execFileSync(
        'curl',
        [
          '--fail',
          '--location',
          '--silent',
          '--show-error',
          '--connect-timeout',
          '15',
          '--max-time',
          '300',
          `https://nodejs.org/dist/v${pin.version}/${name}${extension}`,
          '--output',
          temporary,
        ],
        { timeout: 310_000 },
      );
      bytes = await readFile(temporary);
      if (createHash('sha256').update(bytes).digest('hex') !== pin.sha256)
        throw new Error(
          'Downloaded Node runtime checksum does not match the pin.',
        );
      await rename(temporary, archive);
    } finally {
      await rm(temporary, { force: true });
    }
  }
  if (createHash('sha256').update(bytes).digest('hex') !== pin.sha256)
    throw new Error(
      'Cached Node runtime checksum does not match the pin. Remove the cached archive and rebuild.',
    );
  if (process.platform === 'win32') {
    execFileSync(
      process.env.PYTHON ?? 'python',
      [
        '-c',
        'import sys,zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])',
        archive,
        directory,
      ],
      { timeout: 120_000 },
    );
  } else {
    execFileSync('tar', ['-xzf', archive, '-C', directory], {
      timeout: 120_000,
    });
  }
  const executable = join(
    directory,
    name,
    process.platform === 'win32' ? 'node.exe' : 'bin/node',
  );
  if (
    execFileSync(executable, ['--version'], { encoding: 'utf8' }).trim() !==
    `v${pin.version}`
  )
    throw new Error('Pinned runtime version mismatch.');
  return { ...pin, executable, license: join(directory, name, 'LICENSE') };
}
