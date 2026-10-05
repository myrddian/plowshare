import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  mkdtemp,
  mkdir,
  readFile,
  writeFile,
  copyFile,
  rm,
  readdir,
  realpath,
} from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { setTimeout as pause } from 'node:timers/promises';

test(
  'server launch snapshots survive replacing or cleaning the build jar and identical launches reuse the snapshot',
  { timeout: 10000 },
  async () => {
    const directory = await mkdtemp(join(tmpdir(), 'plowshare-server-launch-'));
    const bin = join(directory, 'bin'),
      libs = join(directory, 'plowshare-server', 'build', 'libs'),
      cache = join(directory, 'runtime');
    let child;
    try {
      await mkdir(bin, { recursive: true });
      await mkdir(libs, { recursive: true });
      await copyFile(resolve('bin/plowshare'), join(bin, 'plowshare'));
      await writeFile(join(directory, 'secrets.env'), '', { mode: 0o600 });
      await writeFile(join(libs, 'server.jar'), 'original server bytes');
      await writeFile(
        join(libs, 'server-plain.jar'),
        'plain jar must not launch',
      );
      const marker = join(directory, 'marker'),
        proceed = join(directory, 'proceed');
      await writeFile(
        join(bin, 'java'),
        `#!/usr/bin/env node\nimport fs from 'node:fs';\nconst jar=process.argv[3];\nfs.writeFileSync(process.env.FIXTURE_MARKER,jar);\nwhile(!fs.existsSync(process.env.FIXTURE_PROCEED)) await new Promise(resolve=>setTimeout(resolve,10));\nprocess.stdout.write(fs.readFileSync(jar));\n`,
        { mode: 0o755 },
      );
      // Use an .mjs extension via a tiny executable wrapper for Node's module mode.
      await copyFile(join(bin, 'java'), join(bin, 'java.mjs'));
      await writeFile(
        join(bin, 'java'),
        '#!/bin/sh\nexec node "$(dirname "$0")/java.mjs" "$@"\n',
        { mode: 0o755 },
      );
      const env = {
        ...process.env,
        PATH: bin + ':' + process.env.PATH,
        PLOWSHARE_RUNTIME_DIR: cache,
        PLOWSHARE_SECRETS: join(directory, 'secrets.env'),
        PLOWSHARE_CONSOLE_TOKEN: 'launch-fixture',
        FIXTURE_MARKER: marker,
        FIXTURE_PROCEED: proceed,
      };
      const launch = () => {
        child = spawn('bash', [join(bin, 'plowshare'), '--no-build'], { env });
        let output = '',
          error = '';
        child.stdout.on('data', (bytes) => {
          output += bytes;
        });
        child.stderr.on('data', (bytes) => {
          error += bytes;
        });
        return new Promise((resolve, reject) => {
          child.on('error', reject);
          child.on('close', (code) =>
            code === 0 ? resolve(output) : reject(new Error(error)),
          );
        });
      };
      let running = launch(),
        path;
      running.catch(() => {});
      for (let i = 0; i < 200; i++) {
        try {
          path = await readFile(marker, 'utf8');
          break;
        } catch {
          await pause(10);
        }
      }
      assert.ok(path, 'The launcher reached Java');
      assert.match(path, new RegExp(cache + '/[a-f0-9]{64}\\.jar$'));
      await writeFile(join(libs, 'server.jar'), 'replacement bytes');
      await rm(join(libs, 'server.jar'));
      await writeFile(proceed, 'go');
      assert.equal(await running, 'original server bytes');
      await writeFile(join(libs, 'server.jar'), 'original server bytes');
      assert.equal(await launch(), 'original server bytes');
      assert.deepEqual(
        (await readdir(cache)).filter((name) => name.endsWith('.jar')),
        [path.split('/').at(-1)],
      );
      await writeFile(join(libs, 'server.jar'), 'new server bytes');
      assert.equal(await launch(), 'new server bytes');
      assert.equal(
        (await readdir(cache)).filter((name) => name.endsWith('.jar')).length,
        2,
      );
      assert.equal(
        (await readdir(cache)).some((name) => name.startsWith('.launch.')),
        false,
      );
    } finally {
      child?.kill();
      await rm(directory, { recursive: true, force: true });
    }
  },
);

async function launchConfiguration(args, overrides = {}) {
  const directory = await realpath(
    await mkdtemp(join(tmpdir(), 'plowshare-launch-config-')),
  );
  try {
    const bin = join(directory, 'bin');
    const libs = join(directory, 'plowshare-server', 'build', 'libs');
    await mkdir(bin, { recursive: true });
    await mkdir(libs, { recursive: true });
    await copyFile(resolve('bin/plowshare'), join(bin, 'plowshare'));
    await copyFile(
      resolve('bin/plowshare-deployment'),
      join(bin, 'plowshare-deployment'),
    );
    await writeFile(join(libs, 'server.jar'), 'fixture jar');
    const secrets = join(directory, 'secrets.env');
    await writeFile(secrets, 'LLM_CHAT_MODEL=file-chat\n', { mode: 0o600 });
    const overlay = join(directory, 'providers.yml');
    await writeFile(overlay, 'plowshare: {}\n');
    const deployment = join(directory, 'deployment.env');
    await writeFile(
      deployment,
      'LLM_BASE_URL=http://file.invalid/v1\nPLOWSHARE_CONFIG_OVERLAY=providers.yml\n',
    );
    await writeFile(
      join(bin, 'java'),
      '#!/bin/sh\nexec node "$(dirname "$0")/java.mjs"\n',
      { mode: 0o755 },
    );
    await writeFile(
      join(bin, 'java.mjs'),
      `const keys = ['LLM_BASE_URL', 'LLM_CHAT_MODEL', 'PLOWSHARE_DB_URL', 'PLOWSHARE_PORT', 'PLOWSHARE_DATA_DIR', 'SPRING_CONFIG_ADDITIONAL_LOCATION']; process.stdout.write(JSON.stringify(Object.fromEntries(keys.map(key => [key, process.env[key] ?? null]))));\n`,
    );
    const env = Object.fromEntries(
      Object.entries(process.env).filter(
        ([key]) => !/^(LLM_|SPARK_|MODEL_|PLOWSHARE_|SPRING_)/.test(key),
      ),
    );
    Object.assign(env, {
      PATH: `${bin}:${process.env.PATH}`,
      PLOWSHARE_SECRETS: join(directory, 'absent'),
      PLOWSHARE_RUNTIME_DIR: join(directory, 'runtime'),
      ...overrides,
    });
    const child = spawn(
      'bash',
      [
        join(
          bin,
          overrides.launchDeployment ? 'plowshare-deployment' : 'plowshare',
        ),
        ...args.map((arg) => arg.replaceAll('$FIXTURE', directory)),
      ],
      { env, cwd: directory },
    );
    let output = '',
      error = '';
    child.stdout.on('data', (bytes) => {
      output += bytes;
    });
    child.stderr.on('data', (bytes) => {
      error += bytes;
    });
    const code = await new Promise((resolve, reject) => {
      child.on('error', reject);
      child.on('close', resolve);
    });
    return { code, output, error, directory };
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

test('launcher does not synthesize inference, database or persistent-state settings', async () => {
  const result = await launchConfiguration(['--no-build']);
  assert.equal(result.code, 0, result.error);
  const configuration = JSON.parse(result.output);
  for (const key of [
    'LLM_BASE_URL',
    'LLM_CHAT_MODEL',
    'PLOWSHARE_DB_URL',
    'PLOWSHARE_PORT',
    'PLOWSHARE_DATA_DIR',
  ]) {
    assert.equal(configuration[key], null, key);
  }
});

test('explicit overlay reaches Java as an absolute required file and flags win over selected secrets', async () => {
  const result = await launchConfiguration([
    '--no-build',
    '--config',
    'providers.yml',
    '--secrets',
    '$FIXTURE/secrets.env',
    '--llm-model',
    'flag-chat',
  ]);
  assert.equal(result.code, 0, result.error);
  const configuration = JSON.parse(result.output);
  assert.equal(configuration.LLM_CHAT_MODEL, 'flag-chat');
  assert.equal(
    configuration.SPRING_CONFIG_ADDITIONAL_LOCATION,
    `file:${result.directory}/providers.yml`,
  );
});

test('missing overlays, removed hardware flags and incomplete options fail before Java', async () => {
  for (const args of [
    ['--no-build', '--config', 'missing.yml'],
    ['--spark-url', 'http://fixture.invalid'],
    ['--config'],
    ['--llm-model'],
  ]) {
    const result = await launchConfiguration(args);
    assert.notEqual(result.code, 0);
    assert.equal(result.output, '');
    assert.match(
      result.error,
      /does not exist|hardware-specific|requires a value/,
    );
  }
});

test('configuration inspection never prints endpoint credentials or API keys', async () => {
  const result = await launchConfiguration(['--print'], {
    LLM_BASE_URL: 'https://user:fixture-secret@fixture.invalid/v1',
    LLM_API_KEY: 'fixture-api-secret',
    PLOWSHARE_DB_URL:
      'jdbc:postgresql://fixture.invalid/db?password=fixture-db-secret',
  });
  assert.equal(result.code, 0, result.error);
  assert.doesNotMatch(
    result.output,
    /fixture-secret|fixture-api-secret|fixture-db-secret/,
  );
  assert.match(result.output, /inference-url set/);
});

test('deployment wrapper preserves exported values and validates its selected overlay', async () => {
  const result = await launchConfiguration(['--no-build'], {
    launchDeployment: 'true',
    PLOWSHARE_DEPLOYMENT: 'deployment.env',
    LLM_BASE_URL: 'http://exported.invalid/v1',
  });
  assert.equal(result.code, 0, result.error);
  const configuration = JSON.parse(result.output);
  assert.equal(configuration.LLM_BASE_URL, 'http://exported.invalid/v1');
  assert.equal(
    configuration.SPRING_CONFIG_ADDITIONAL_LOCATION,
    `file:${result.directory}/providers.yml`,
  );
  const missing = await launchConfiguration(['--no-build'], {
    launchDeployment: 'true',
    PLOWSHARE_DEPLOYMENT: 'deployment.env',
    PLOWSHARE_CONFIG_OVERLAY: 'missing.yml',
  });
  assert.notEqual(missing.code, 0);
  assert.match(missing.error, /configuration file does not exist/);
});

test('exported Spring locations win over deployment overlays and --config wins over both', async () => {
  const preserved = await launchConfiguration(['--no-build'], {
    PLOWSHARE_CONFIG_OVERLAY: 'missing.yml',
    SPRING_CONFIG_ADDITIONAL_LOCATION: 'file:/operator/providers.yml',
  });
  assert.equal(preserved.code, 0, preserved.error);
  assert.equal(
    JSON.parse(preserved.output).SPRING_CONFIG_ADDITIONAL_LOCATION,
    'file:/operator/providers.yml',
  );
  const selected = await launchConfiguration(
    ['--no-build', '--config', 'providers.yml'],
    { SPRING_CONFIG_ADDITIONAL_LOCATION: 'file:/operator/providers.yml' },
  );
  assert.equal(selected.code, 0, selected.error);
  assert.equal(
    JSON.parse(selected.output).SPRING_CONFIG_ADDITIONAL_LOCATION,
    `file:${selected.directory}/providers.yml`,
  );
});
