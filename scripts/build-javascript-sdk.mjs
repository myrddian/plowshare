import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { lstatSync, rmSync } from 'node:fs';
import path from 'node:path';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/** Portable lexical containment; different drives and parent components are rejected. */
export function within(parent, target, paths = path) {
  const child = paths.relative(parent, target);
  return (
    child === '' ||
    (!paths.isAbsolute(child) &&
      child !== '..' &&
      !child.startsWith(`..${paths.sep}`))
  );
}

/** Validate the entire cleanup set before removing any configured output. */
export function cleanOutputs(root, outputs) {
  const build = resolve(root, 'build');
  for (const output of outputs) {
    if (!within(build, output))
      throw new Error('Compiler output escapes the SDK build directory');
    let component = root;
    for (const part of ['', ...path.relative(root, output).split(path.sep)]) {
      component = resolve(component, part);
      let metadata;
      try {
        metadata = lstatSync(component);
      } catch (failure) {
        if (failure.code === 'ENOENT') break;
        throw failure;
      }
      // Do not follow a configured output or any existing ancestor out of the
      // owning SDK. A bad later output must leave all earlier outputs intact.
      if (metadata.isSymbolicLink())
        throw new Error('Symbolic link in SDK compiler output path');
    }
  }
  for (const output of outputs)
    rmSync(output, { recursive: true, force: true });
}

function buildSdk() {
  // Both Gradle and package builds use this owner. TypeScript leaves outputs of
  // deleted sources behind; wildcard package exports would otherwise publish them.
  const name = process.argv[2];
  if (!['typescript', 'node'].includes(name) || process.argv.length !== 3)
    throw new Error('Expected exactly one JavaScript SDK project name');
  const root = resolve(
    dirname(fileURLToPath(import.meta.url)),
    '..',
    'sdk',
    name,
  );
  const build = resolve(root, 'build');
  const require = createRequire(resolve(root, 'package.json'));
  const ts = require('typescript');
  const outputs = new Set();
  const visited = new Set();
  function collect(config) {
    config = resolve(config);
    if (!within(root, config))
      throw new Error('SDK project reference escapes its owner');
    if (visited.has(config)) return;
    visited.add(config);
    const parsed = ts.getParsedCommandLineOfConfigFile(
      config,
      {},
      {
        ...ts.sys,
        onUnRecoverableConfigFileDiagnostic(diagnostic) {
          throw new Error(
            ts.flattenDiagnosticMessageText(diagnostic.messageText, '\n'),
          );
        },
      },
    );
    if (!parsed || parsed.errors.length)
      throw new Error('Invalid SDK TypeScript build configuration');
    for (const output of [
      parsed.options.outDir,
      parsed.options.tsBuildInfoFile,
    ]) {
      if (!output) continue;
      if (!within(build, output))
        throw new Error('Compiler output escapes the SDK build directory');
      outputs.add(output);
    }
    for (const reference of parsed.projectReferences ?? [])
      collect(ts.resolveProjectReferencePath(reference));
  }
  collect(resolve(root, 'tsconfig.json'));
  // Validate every project before removing anything. Only configured compiler
  // outputs are removed, rather than a hard-coded list of retired module names.
  cleanOutputs(root, outputs);
  execFileSync(
    process.execPath,
    [
      require.resolve('typescript/bin/tsc'),
      ...(name === 'typescript' ? ['-b', '--force'] : ['-p', 'tsconfig.json']),
    ],
    { cwd: root, stdio: 'inherit' },
  );
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
)
  buildSdk();
