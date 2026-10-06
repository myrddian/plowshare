import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  mkdtemp,
  mkdir,
  copyFile,
  writeFile,
  rm,
  realpath,
} from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

async function fixture(name, args, settings = {}) {
  const root = await realpath(
    await mkdtemp(join(tmpdir(), 'plowshare-basic-launch-')),
  );
  try {
    const bin = join(root, 'bin');
    await mkdir(bin);
    await copyFile(resolve(`bin/${name}`), join(bin, name));
    await mkdir(
      join(
        root,
        'plowshare-tui/node_modules/plowshare-client-ts/build/binding',
      ),
      { recursive: true },
    );
    await writeFile(
      join(
        root,
        'plowshare-tui/node_modules/plowshare-client-ts/build/binding/auth.js',
      ),
      '',
    );
    await mkdir(join(root, 'extensions/search-searxng/build/libs'), {
      recursive: true,
    });
    await writeFile(
      join(
        root,
        'extensions/search-searxng/build/libs/search-searxng-0.1.0-SNAPSHOT.jar',
      ),
      'fixture',
    );
    await writeFile(
      join(root, 'capture.mjs'),
      'process.stdout.write(JSON.stringify({args:process.argv.slice(2),url:process.env.PLOWSHARE_URL??null,here:process.env.PLOWSHARE_HERE??null}));',
    );
    const capture = `exec "${process.execPath}" "${join(root, 'capture.mjs')}" "$@"\n`;
    await writeFile(
      join(bin, 'node'),
      '#!/bin/sh\nif [ "$1" = -e ]; then exit 0; fi\n' + capture,
      { mode: 0o755 },
    );
    await writeFile(join(bin, 'java'), '#!/bin/sh\n' + capture, {
      mode: 0o755,
    });
    const env = Object.fromEntries(
      Object.entries(process.env).filter(
        ([key]) => !/^(PLOWSHARE_|SEARXNG_|SPRING_)/.test(key),
      ),
    );
    Object.assign(env, { PATH: `${bin}:${process.env.PATH}`, ...settings });
    const child = spawn('bash', [join(bin, name), ...args], { env, cwd: root });
    let output = '',
      error = '';
    child.stdout.on('data', (data) => {
      output += data;
    });
    child.stderr.on('data', (data) => {
      error += data;
    });
    const code = await new Promise((accept, reject) => {
      child.on('error', reject);
      child.on('close', accept);
    });
    return { code, output, error, root };
  } finally {
    await rm(root, { recursive: true, force: true });
  }
}

test('TUI refuses an unspecified origin before starting a client', async () => {
  const result = await fixture('plowshare-talk', []);
  assert.equal(result.code, 2);
  assert.equal(result.output, '');
  assert.match(result.error, /supply --url or PLOWSHARE_URL/);
});

test('TUI forwards explicit selection and retains the caller project root', async () => {
  const result = await fixture('plowshare-talk', [
    '--url',
    'https://server.example.test',
    '--agent',
    'helper',
    '--project',
    'research',
  ]);
  assert.equal(result.code, 0, result.error);
  const received = JSON.parse(result.output);
  assert.equal(received.url, 'https://server.example.test');
  assert.equal(received.here, result.root);
  assert.equal(received.args.at(-1), 'src/view/main.ts');
});

test('headless help remains offline and forwards literal command arguments', async () => {
  const result = await fixture('plowshare-talk', [
    'memory',
    'navigate',
    '--help',
  ]);
  assert.equal(result.code, 0, result.error);
  assert.deepEqual(JSON.parse(result.output).args.slice(2), [
    'memory',
    'navigate',
    '--help',
  ]);
});

test('search launcher requires the engine and listener before starting Java', async () => {
  for (const settings of [
    {},
    { SEARXNG_BASE_URL: 'https://search.example.test' },
  ]) {
    const result = await fixture('plowshare-searxng', [], settings);
    assert.notEqual(result.code, 0);
    assert.equal(result.output, '');
    assert.match(result.error, /Set the/);
  }
});

test('search launcher only starts the foreground adapter and forwards Spring arguments', async () => {
  const result = await fixture(
    'plowshare-searxng',
    ['--spring.main.banner-mode=off'],
    {
      SEARXNG_BASE_URL: 'https://search.example.test',
      SEARXNG_BIND: '127.0.0.1',
      SEARXNG_PORT: '18086',
    },
  );
  assert.equal(result.code, 0, result.error);
  assert.deepEqual(JSON.parse(result.output).args.slice(2), [
    '--spring.main.banner-mode=off',
  ]);
});

test('simple launcher help works without endpoint configuration', async () => {
  for (const name of ['plowshare-talk', 'plowshare-searxng']) {
    const result = await fixture(name, ['--help']);
    assert.equal(result.code, 0, result.error);
    assert.match(result.output, /Usage:/);
  }
});
