import assert from 'node:assert/strict';
import {
  mkdtempSync,
  mkdirSync,
  existsSync,
  rmSync,
  symlinkSync,
  writeFileSync,
  readFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join, posix, win32 } from 'node:path';
import { afterEach, test } from 'node:test';
import { within, cleanOutputs } from './build-javascript-sdk.mjs';

let fixture;
afterEach(() => {
  if (fixture) rmSync(fixture, { recursive: true, force: true });
  fixture = undefined;
});
function setup() {
  fixture = mkdtempSync(join(tmpdir(), 'sdk-output-boundary-'));
  const root = join(fixture, 'sdk');
  const output = join(root, 'build', 'operations');
  const external = join(fixture, 'external');
  mkdirSync(output, { recursive: true });
  mkdirSync(external);
  writeFileSync(join(output, 'orphan.js'), 'stale');
  writeFileSync(join(external, 'evidence'), 'preserved');
  return { root, output, external };
}

test('containment handles POSIX and Windows parents, siblings and different drives', () => {
  for (const [paths, root, child, outside, sibling] of [
    [
      posix,
      '/sdk/build',
      '/sdk/build/operations',
      '/sdk/other',
      '/sdk/build-old',
    ],
    [
      win32,
      'C:\\sdk\\build',
      'C:\\sdk\\build\\operations',
      'C:\\sdk\\other',
      'C:\\sdk\\build-old',
    ],
  ]) {
    assert.equal(within(root, root, paths), true);
    assert.equal(within(root, child, paths), true);
    assert.equal(within(root, outside, paths), false);
    assert.equal(within(root, sibling, paths), false);
  }
  assert.equal(within('C:\\sdk\\build', 'D:\\external', win32), false);
});

test('cleanup removes configured compiler outputs and preserves unrelated reports', () => {
  const { root, output, external } = setup();
  const report = join(root, 'build', 'report');
  writeFileSync(report, 'keep');
  cleanOutputs(root, [output]);
  assert.equal(existsSync(output), false);
  assert.equal(readFileSync(report, 'utf8'), 'keep');
  assert.equal(readFileSync(join(external, 'evidence'), 'utf8'), 'preserved');
});

test('an escaping later output prevents every deletion', () => {
  const { root, output, external } = setup();
  assert.throws(() => cleanOutputs(root, [output, external]), /escapes/);
  assert.equal(readFileSync(join(output, 'orphan.js'), 'utf8'), 'stale');
  assert.equal(readFileSync(join(external, 'evidence'), 'utf8'), 'preserved');
});

for (const position of ['build', 'ancestor', 'output']) {
  test(`a symlink at ${position} is refused before any cleanup`, () => {
    const { root, output, external } = setup();
    let redirected;
    if (position === 'build') {
      rmSync(join(root, 'build'), { recursive: true });
      symlinkSync(external, join(root, 'build'), 'junction');
      redirected = join(root, 'build', 'nested');
    } else {
      const link =
        position === 'ancestor'
          ? join(root, 'build', 'linked')
          : join(root, 'build', 'other');
      symlinkSync(external, link, 'junction');
      redirected = position === 'ancestor' ? join(link, 'nested') : link;
    }
    assert.throws(
      () => cleanOutputs(root, [output, redirected]),
      /Symbolic link/,
    );
    assert.equal(readFileSync(join(external, 'evidence'), 'utf8'), 'preserved');
    if (position !== 'build')
      assert.equal(readFileSync(join(output, 'orphan.js'), 'utf8'), 'stale');
  });
}
