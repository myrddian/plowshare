import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';
import { chmod, cp, mkdir, readdir, readFile, rm, stat, symlink, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { distributionRuntime } from './distribution-runtime.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const requireDesktop = createRequire(join(root, 'plowshare-desktop/package.json'));
const output = join(root, 'build/distributions');
const version = JSON.parse(await readFile(join(root, 'plowshare-cli/package.json'), 'utf8')).version;
const kind = process.argv[2];
if (!['clients', 'server'].includes(kind)) throw new Error('Choose clients or server. Use the Gradle distribution tasks.');
const git = (...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim();
const info = { version, commit: git('rev-parse', 'HEAD'), trackedChanges: Boolean(git('status', '--porcelain', '--untracked-files=no')), target: kind === 'clients' ? 'darwin-arm64 standalone clients and desktop' : 'Java 21 server' };
await mkdir(output, { recursive: true });
const stage = join(root, 'build/package', kind);
await rm(stage, { recursive: true, force: true });
await mkdir(stage, { recursive: true });
async function json(path, value) { await writeFile(path, JSON.stringify(value, null, 2) + '\n'); }
async function common(directory, metadata) {
  await mkdir(directory, { recursive: true });
  await cp(join(root, 'docs/distributions.md'), join(directory, 'INSTALL.md'));
  await json(join(directory, 'build-info.json'), { ...info, ...metadata });
}
async function archive(directory) {
  const destination = join(output, `${directory.split('/').at(-1)}.tar.gz`);
  execFileSync('python3', [join(root, 'scripts/distribution-archive.py'), directory, destination]);
  await checksum(destination);
}
async function checksum(destination) {
  const digest = createHash('sha256').update(await readFile(destination)).digest('hex');
  await writeFile(destination + '.sha256', `${digest}  ${destination.split('/').at(-1)}\n`);
  console.log(destination);
}
if (kind === 'clients') {
  if (process.platform !== 'darwin' || process.arch !== 'arm64') throw new Error('The first desktop target requires a macOS Apple Silicon build host.');
  const { build } = requireDesktop('esbuild');
  const runtime = await distributionRuntime(root);
  const headless = join(stage, `plowshare-clients-${version}-darwin-arm64`);
  await common(headless, { runtime: `bundled Node ${runtime.version}`, runtimeArchiveSha256: runtime.sha256, transport: 'WS operations; HTTP authentication bootstrap' });
  const embedded = join(stage, 'embedded');
  await mkdir(embedded, { recursive: true });
  const dependencies = new Set();
  const main = join(embedded, 'client.mjs');
  const result = await build({ absWorkingDir: root, entryPoints: ['scripts/distribution-native.mjs'], outfile: main, bundle: true, platform: 'node', format: 'esm', target: 'node26', metafile: true, define: { __PLOWSHARE_VERSION__: JSON.stringify(version), 'process.env.NODE_ENV': '"production"', 'process.env.DEV': '"false"' },
    // Ink's optional developer-only module is not installed or shipped.
    plugins: [{ name: 'production-ink', setup(builder) {
      builder.onResolve({ filter: /^\.\/devtools\.js$/ }, args => args.importer.includes('/ink/build/') ? { path: 'disabled', namespace: 'ink-devtools' } : undefined);
      builder.onLoad({ filter: /.*/, namespace: 'ink-devtools' }, () => ({ contents: 'export {};', loader: 'js' }));
    } }],
    banner: { js: 'import { createRequire as __plowshareRequire } from "node:module"; const require = __plowshareRequire(import.meta.url);' } });
  for (const input of Object.keys(result.metafile.inputs)) if (input.includes('/node_modules/')) dependencies.add(resolve(root, input));
  await mkdir(join(headless, 'bin'), { recursive: true });
  const executable = join(headless, 'bin/plowshare');
  await cp(runtime.license, join(headless, 'NODE-LICENSE.txt'));
  // Retain dependency attribution and license text for the actual bundled inputs.
  const packages = new Map();
  for (const input of dependencies) {
    let directory = dirname(input);
    while (directory !== dirname(directory)) {
      try {
        const manifest = JSON.parse(await readFile(join(directory, 'package.json'), 'utf8'));
        if (manifest.name && manifest.version) { packages.set(`${manifest.name}@${manifest.version}`, { manifest, directory }); break; }
      } catch { /* Continue to the package root. */ }
      directory = dirname(directory);
    }
  }
  let notices = 'Third-party code bundled with the Plowshare clients and desktop main process.\n';
  for (const [name, { manifest, directory }] of [...packages].sort(([a], [b]) => a.localeCompare(b))) {
    notices += `\n=== ${name} (${JSON.stringify(manifest.license ?? 'see package')}) ===\n`;
    for (const file of (await readdir(directory)).filter(name => /^(licen[cs]e|copying|notice)(\.|$)/i.test(name)).sort()) if ((await stat(join(directory, file))).isFile()) notices += await readFile(join(directory, file), 'utf8') + '\n';
  }
  await writeFile(join(headless, 'THIRD-PARTY-NOTICES.txt'), notices);
  const licenses = join(embedded, 'licenses.txt');
  await writeFile(licenses, await readFile(runtime.license, 'utf8') + '\n' + notices);
  const configuration = join(embedded, 'sea.json');
  await json(configuration, { main, mainFormat: 'module', executable: runtime.executable, output: executable, assets: { licenses, 'build-info': join(headless, 'build-info.json') },
    disableExperimentalSEAWarning: true, useSnapshot: false, useCodeCache: false, execArgvExtension: 'none' });
  execFileSync(runtime.executable, ['--build-sea', configuration], { stdio: 'inherit' });
  await chmod(executable, 0o755);
  execFileSync('codesign', ['--force', '--sign', '-', '--timestamp=none', executable]);
  execFileSync('codesign', ['--verify', '--strict', executable]);
  for (const name of ['plowshare-cli', 'plowshare-mcp', 'plowshare-talk']) await symlink('plowshare', join(headless, 'bin', name));
  await archive(headless);
  const single = join(output, `plowshare-${version}-darwin-arm64`);
  await cp(executable, single);
  await chmod(single, 0o755);
  await checksum(single);
  // Superseded developer-runtime archives are generated outputs, not release candidates.
  for (const suffix of ['.tar.gz', '.tar.gz.sha256']) await rm(join(output, `plowshare-clients-${version}${suffix}`), { force: true });

  const application = join(stage, 'desktop-app');
  await mkdir(join(application, 'build'), { recursive: true });
  // Explicit allowlist: never copy profiles, config, credentials or dependency caches.
  for (const path of ['main.cjs', 'preload.cjs', 'renderer']) await cp(join(root, 'plowshare-desktop/build', path), join(application, 'build', path), { recursive: true });
  await json(join(application, 'package.json'), { name: 'plowshare-desktop', productName: 'Plowshare', version, main: 'build/main.cjs', description: 'Plowshare remote agent workspace' });
  await writeFile(join(application, 'THIRD-PARTY-NOTICES.txt'), notices);
  const electronVersion = JSON.parse(await readFile(join(root, 'plowshare-desktop/package.json'), 'utf8')).devDependencies.electron;
  const { packager } = requireDesktop('@electron/packager');
  const [packaged] = await packager({ dir: application, out: join(stage, 'native'), name: 'Plowshare', appBundleId: 'io.aeyer.plowshare', platform: 'darwin', arch: 'arm64', electronVersion, asar: true, prune: false, overwrite: true,
    osxSign: { identity: '-', identityValidation: false, timestamp: 'none', preAutoEntitlements: false, preEmbedProvisioningProfile: false,
      optionsForFile: () => ({ entitlements: join(root, 'scripts/distribution-entitlements.plist'), hardenedRuntime: true }) } });
  const desktop = join(stage, `plowshare-desktop-${version}-darwin-arm64`);
  await common(desktop, { electronVersion, platform: 'darwin', arch: 'arm64', signing: 'ad-hoc', developerSigned: false, notarized: false });
  await cp(join(packaged, 'Plowshare.app'), join(desktop, 'Plowshare.app'), { recursive: true, dereference: false, verbatimSymlinks: true });
  for (const file of (await readdir(packaged)).filter(name => /license/i.test(name))) await cp(join(packaged, file), join(desktop, file));
  await archive(desktop);
} else {
  const server = join(stage, `plowshare-server-${version}`);
  await common(server, { runtime: 'Java 21', database: 'PostgreSQL', configurationIncluded: false });
  await mkdir(join(server, 'lib'), { recursive: true });
  await mkdir(join(server, 'bin'), { recursive: true });
  const libs = join(root, 'plowshare-server/build/libs');
  const jars = (await readdir(libs)).filter(name => name.endsWith('.jar') && !name.endsWith('-plain.jar'));
  if (jars.length !== 1) throw new Error(`Expected one executable server jar; found ${jars.length}. Run a clean bootJar build.`);
  await cp(join(libs, jars[0]), join(server, 'lib/plowshare-server.jar'));
  await cp(join(root, 'scripts/distribution-server'), join(server, 'bin/plowshare-server'));
  await chmod(join(server, 'bin/plowshare-server'), 0o755);
  await archive(server);
}
