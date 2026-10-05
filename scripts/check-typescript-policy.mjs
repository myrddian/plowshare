import ts from 'typescript';
import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync, execFileSync } from 'node:child_process';

export const MODULES = [
  'client-ts',
  'client-node',
  'cli',
  'mcp',
  'tui',
  'console',
  'desktop',
];
export const MODULE_PATHS = Object.fromEntries(
  MODULES.map((module) => [
    module,
    module === 'client-ts'
      ? 'sdk/typescript'
      : module === 'client-node'
        ? 'sdk/node'
        : `plowshare-${module}`,
  ]),
);
// The standard owns this gate. Weakening the inherited file itself must also fail.
export const REQUIRED = [
  'strict',
  'noEmitOnError',
  'noUncheckedIndexedAccess',
  'exactOptionalPropertyTypes',
  'noImplicitOverride',
  'noUnusedLocals',
  'noUnusedParameters',
  'noFallthroughCasesInSwitch',
  'isolatedModules',
  'verbatimModuleSyntax',
  'forceConsistentCasingInFileNames',
];
export function verifyPolicy(root) {
  const policy = JSON.parse(
    readFileSync(resolve(root, 'tsconfig.strict.json'), 'utf8'),
  ).compilerOptions;
  for (const key of REQUIRED)
    if (policy?.[key] !== true)
      throw new Error(`Shared policy requires ${key}=true`);
  function check(directory) {
    let checked = 0;
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      if (['node_modules', 'build', 'build-tests', 'dist'].includes(entry.name))
        continue;
      const path = resolve(directory, entry.name);
      if (entry.isDirectory()) checked += check(path);
      else if (/^tsconfig.*\.json$/.test(entry.name)) {
        const loaded = ts.readConfigFile(path, ts.sys.readFile);
        if (loaded.error)
          throw new Error(
            ts.flattenDiagnosticMessageText(loaded.error.messageText, '\n'),
          );
        const parsed = ts.parseJsonConfigFileContent(
          loaded.config,
          ts.sys,
          directory,
        );
        const malformed = parsed.errors.filter((error) => error.code !== 18003);
        if (malformed.length)
          throw new Error(
            ts.flattenDiagnosticMessageText(malformed[0].messageText, '\n'),
          );
        // Solution files have no sources; their referenced leaf projects own the policy.
        if (parsed.fileNames.length === 0) continue;
        checked++;
        for (const key of REQUIRED)
          if (parsed.options[key] !== true)
            throw new Error(`${path} must inherit ${key}=true`);
      }
    }
    return checked;
  }
  for (const module of MODULES)
    if (check(resolve(root, MODULE_PATHS[module])) === 0)
      throw new Error(`${module} has no checked TypeScript project`);
}
if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  verifyPolicy(process.cwd());
  if (process.argv.includes('--compile-additional')) {
    for (const module of ['client-node', 'tui']) {
      const result = spawnSync(
        process.execPath,
        [
          'node_modules/typescript/bin/tsc',
          '-p',
          `${MODULE_PATHS[module]}/tsconfig.lint.json`,
          '--pretty',
          'false',
        ],
        { stdio: 'inherit' },
      );
      if (result.error)
        throw new Error(`Could not start TypeScript compiler for ${module}`, {
          cause: result.error,
        });
      if (result.signal)
        throw new Error(
          `TypeScript compiler for ${module} terminated by ${result.signal}`,
        );
      if (result.status !== 0) process.exit(result.status ?? 1);
    }
  }
  // Generated IPC schemas must match the checked-in union after every client edit.
  execFileSync(
    process.execPath,
    [
      'sdk/typescript/scripts/generate-operation-schemas.mjs',
      '--desktop',
      '--check',
    ],
    { stdio: 'inherit' },
  );
  execFileSync(
    process.execPath,
    [
      'sdk/typescript/scripts/generate-operation-schemas.mjs',
      '--console',
      '--check',
    ],
    { stdio: 'inherit' },
  );
  console.log('All TypeScript compiler projects enforce the shared policy.');
}
