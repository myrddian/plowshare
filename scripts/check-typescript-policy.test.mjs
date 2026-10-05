import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, relative } from 'node:path';
import {
  verifyPolicy,
  REQUIRED,
  MODULES,
  MODULE_PATHS,
} from './check-typescript-policy.mjs';
function fixture(work) {
  const root = mkdtempSync(join(tmpdir(), 'plowshare-typescript-policy-'));
  const write = (path, value) =>
    writeFileSync(join(root, path), JSON.stringify(value));
  try {
    write('tsconfig.strict.json', {
      compilerOptions: Object.fromEntries(REQUIRED.map((key) => [key, true])),
    });
    for (const module of MODULES) {
      mkdirSync(join(root, MODULE_PATHS[module]), { recursive: true });
      writeFileSync(
        join(root, `${MODULE_PATHS[module]}/main.ts`),
        'export const answer=42;',
      );
      write(`${MODULE_PATHS[module]}/tsconfig.json`, {
        extends: relative(
          join(root, MODULE_PATHS[module]),
          join(root, 'tsconfig.strict.json'),
        ),
        include: ['main.ts'],
      });
    }
    work(root, write);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}
await test('all compiler projects inherit the standard', () =>
  fixture((root) => assert.doesNotThrow(() => verifyPolicy(root))));
await test('a module cannot override a safety flag', () =>
  fixture((root, write) => {
    write('plowshare-cli/tsconfig.json', {
      extends: '../tsconfig.strict.json',
      compilerOptions: { noUncheckedIndexedAccess: false },
      include: ['main.ts'],
    });
    assert.throws(() => verifyPolicy(root), /noUncheckedIndexedAccess/);
  }));
await test('an extra test project cannot weaken optional checks', () =>
  fixture((root, write) => {
    write('plowshare-tui/tsconfig.test.json', {
      extends: './tsconfig.json',
      compilerOptions: { exactOptionalPropertyTypes: false },
    });
    assert.throws(() => verifyPolicy(root), /exactOptionalPropertyTypes/);
  }));
await test('changing the shared file cannot weaken the standard', () =>
  fixture((root, write) => {
    write('tsconfig.strict.json', {
      compilerOptions: Object.fromEntries(
        REQUIRED.map((key) => [key, key !== 'strict']),
      ),
    });
    assert.throws(() => verifyPolicy(root), /strict=true/);
  }));
await test('excluding every source cannot evade the gate', () =>
  fixture((root, write) => {
    write('plowshare-mcp/tsconfig.json', { include: ['absent.ts'] });
    assert.throws(() => verifyPolicy(root), /no checked/);
  }));
