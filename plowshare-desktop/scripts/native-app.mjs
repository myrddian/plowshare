import { packager } from '@electron/packager';
import { cp, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const desktopRoot = fileURLToPath(new URL('../', import.meta.url));

/** Give macOS its own named bundle without modifying the shared Electron runtime. */
export async function nativeApp() {
  const build = join(desktopRoot, 'build');
  await mkdir(build, { recursive: true });
  const source = await mkdtemp(join(build, 'native-source-'));
  try {
    await mkdir(join(source, 'build'));
    // Profiles, credentials and test outputs must never enter the bundle.
    for (const file of ['main.cjs', 'preload.cjs', 'renderer', 'icons']) {
      await cp(join(build, file), join(source, 'build', file), { recursive: true });
    }
    const manifest = JSON.parse(await readFile(join(desktopRoot, 'package.json'), 'utf8'));
    await writeFile(join(source, 'package.json'), JSON.stringify({
      name: manifest.name, productName: 'Plowshare', version: manifest.version, main: 'build/main.cjs',
    }));
    const [directory] = await packager({
      dir: source, out: join(build, 'native'), name: 'Plowshare', appBundleId: 'io.aeyer.plowshare',
      platform: 'darwin', arch: process.arch, electronVersion: manifest.devDependencies.electron,
      asar: true, prune: false, overwrite: true, icon: join(desktopRoot, 'assets/icons/plowshare.icns'),
      osxSign: {
        identity: '-', identityValidation: false, timestamp: 'none',
        preAutoEntitlements: false, preEmbedProvisioningProfile: false,
        optionsForFile: () => ({ entitlements: join(desktopRoot, '../scripts/distribution-entitlements.plist'), hardenedRuntime: true }),
      },
    });
    return join(directory, 'Plowshare.app/Contents/MacOS/Plowshare');
  } finally {
    await rm(source, { recursive: true, force: true });
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  console.log(await nativeApp());
}
