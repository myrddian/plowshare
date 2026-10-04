import { copyFile, lstat, mkdir, readFile, realpath } from 'node:fs/promises';
import { constants } from 'node:fs';
import { dirname, isAbsolute, join, normalize, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const bundle = resolve(dirname(fileURLToPath(import.meta.url)), '../plowshare-server/src/main/resources/personal-starter');
async function info(path) {
  try { return await lstat(path); }
  catch (error) { if (error.code === 'ENOENT') return null; throw error; }
}
async function safe(root, target) {
  const local = relative(root, target);
  if (isAbsolute(local) || local === '..' || local.startsWith('..' + sep)) throw new Error('Starter path leaves Personal');
  let current = root;
  for (const part of local.split(sep).filter(Boolean)) {
    current = join(current, part);
    if ((await info(current))?.isSymbolicLink()) throw new Error('Starter paths must not contain symlinks');
  }
}

/** Install into an already claimed Personal union; never replace a user's file or touch its Git metadata. */
export async function installPersonalStarter(directory) {
  const root = resolve(directory);
  if ((await info(root))?.isSymbolicLink() || !(await info(root))?.isDirectory()) throw new Error('Choose a real Personal directory');
  const canonical = await realpath(root);
  const claimPath = join(canonical, '.plowshare/personal.json');
  await safe(canonical, claimPath);
  const claim = JSON.parse(await readFile(claimPath, 'utf8'));
  if (typeof claim.account !== 'string' || !claim.account || typeof claim.server !== 'string'
      || claim.project !== 'personal:' + Buffer.from(claim.account, 'utf8').toString('hex')) {
    throw new Error('The directory is not claimed by a Personal account');
  }
  const git = join(canonical, '.plowshare/sync.git');
  await safe(canonical, git);
  if (!(await info(git))?.isDirectory()) throw new Error('Connect Personal files before installing the starter');
  const files = (await readFile(join(bundle, 'manifest.txt'), 'utf8')).split(/\r?\n/).filter(Boolean);
  if (new Set(files).size !== files.length) throw new Error('Duplicate starter path');
  for (const file of files) {
    if (isAbsolute(file) || normalize(file) !== file || !/^(Resources|Planning)\//.test(file)) throw new Error('Invalid starter path');
    await safe(canonical, join(canonical, file));
  }
  const created = [], preserved = [];
  for (const file of files) {
    const target = join(canonical, file);
    await safe(canonical, target);
    const existing = await info(target);
    if (existing) {
      if (!existing.isFile()) throw new Error('Starter target must be a file: ' + file);
      preserved.push(file); continue;
    }
    await mkdir(dirname(target), { recursive: true });
    try { await copyFile(join(bundle, file), target, constants.COPYFILE_EXCL); created.push(file); }
    catch (error) { if (error.code !== 'EEXIST') throw error; preserved.push(file); }
  }
  return { created, preserved };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.argv.length !== 3) throw new Error('Usage: node scripts/install-personal-starter.mjs <connected-personal-directory>');
  console.log(JSON.stringify(await installPersonalStarter(process.argv[2]), null, 2));
}
