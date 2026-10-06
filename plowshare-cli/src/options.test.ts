import { json, text, field, list } from './json.test-support.js';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import assert from 'node:assert/strict';
import test from 'node:test';
import { options, Usage } from './options.js';
import { exitFor, run } from './run.js';

await test('explicit scope overrides the environment and leaves payload scope to shared parsing', () => {
  const env = { PLOWSHARE_PROJECT: 'inherited' };
  assert.equal(
    options(['--project', 'chosen', 'memory', 'index'], env).project,
    'chosen',
  );
  assert.equal(
    options(['--global', 'memory', 'index'], env).project,
    undefined,
  );
  assert.equal(
    options(['memory', 'recall', 'a question'], env).command,
    'memory recall a question',
  );
  assert.throws(
    () => options(['memory', 'index'], { PLOWSHARE_PROJECT: ' ' }),
    Usage,
  );
  assert.throws(
    () => options(['--global', '--project', 'chosen', 'memory', 'index'], env),
    Usage,
  );
});

await test('server and url select the origin before or after commands and override the environment', () => {
  const env = { PLOWSHARE_URL: 'https://configured.example.test' };
  assert.equal(options(['project', 'list'], env).base, env.PLOWSHARE_URL);
  for (const flag of ['--server', '--url']) {
    assert.equal(
      options([flag, 'https://chosen.example.test/', 'project', 'list'], env)
        .base,
      'https://chosen.example.test',
    );
    assert.equal(
      options(['project', 'list', flag, 'https://chosen.example.test'], env)
        .base,
      'https://chosen.example.test',
    );
    assert.throws(() => options(['project', 'list', flag], env), Usage);
    for (const url of [
      ' ',
      'ws://example.test',
      'https://user:secret@example.test',
      'https://example.test/v1',
      'https://example.test?x=1',
      'https://example.test#fragment',
    ]) {
      assert.throws(
        () => options([flag, url, 'project', 'list'], env),
        (error) => error instanceof Usage && !error.message.includes('secret'),
      );
    }
  }
  assert.equal(
    options(
      [
        '--server',
        'https://first.example.test',
        '--url',
        'https://last.example.test',
        'project',
        'list',
      ],
      env,
    ).base,
    'https://last.example.test',
  );
  assert.equal(
    options(
      [
        '--url',
        'https://first.example.test',
        '--server',
        'https://last.example.test',
        'project',
        'list',
      ],
      env,
    ).base,
    'https://last.example.test',
  );
  assert.equal(options(['project', 'list'], {}).base, undefined);
  assert.throws(
    () => options(['project', 'list'], { PLOWSHARE_URL: '' }),
    Usage,
  );
});

await test('online commands require a server before credentials, prompts or stdin are used', async () => {
  let output = '';
  const forbidden = async (): Promise<never> => {
    throw new Error('must not prompt or read');
  };
  const io = {
    env: {},
    stdout: (text: string) => {
      output += text;
    },
    stderr: () => {},
    stdin: forbidden,
    login: forbidden,
    setup: forbidden,
  };
  for (const command of [
    ['login'],
    ['setup'],
    ['logout'],
    ['project', 'list'],
    ['conversation', 'follow', 'cnv_one'],
  ]) {
    output = '';
    assert.equal(await run([...command, '--json'], io), 2);
    const result = json(output);
    assert.match(text(result, ['said']), /--server ORIGIN.*PLOWSHARE_URL/);
    assert.equal(field(result, ['submission']), 'not-submitted');
  }
  output = '';
  assert.equal(await run(['project', 'list', '--validate', '--json'], io), 0);
  assert.equal(field(json(output), ['executed']), false);
  output = '';
  assert.equal(await run(['--help'], io), 0);
  assert.match(output, /--server ORIGIN/);
  output = '';
  assert.equal(await run(['--help', '--json'], io), 0);
  assert.ok(list(json(output), ['options']).includes('--server'));
});

await test('job wait/poll/result share the status request with explicit wait policy', () => {
  assert.equal(options(['job', 'wait', 'j'], {}).command, 'job status j');
  assert.equal(options(['job', 'wait', 'j'], {}).wait, true);
  assert.equal(options(['job', 'poll', 'j'], {}).wait, false);
  assert.equal(options(['job', 'result', 'j'], {}).command, 'job status j');
  assert.equal(options(['--wait', 'memory', 'digest'], {}).wait, true);
  assert.equal(options(['job', 'watch', 'j'], {}).command, 'job status j');
  assert.equal(options(['job', 'watch', 'j'], {}).watch, true);
  assert.equal(options(['--watch', 'agent', 'run', '{}'], {}).wait, true);
});

await test('rejects credential-bearing URLs, arguments and invalid pacing without echoing them', () => {
  const secret = 'never-print-this';
  for (const args of [
    ['--url', `https://user:${secret}@example.com`, 'memory', 'index'],
    ['--password', secret, 'memory', 'index'],
    ['--url', 'https://example.com/v1', 'memory', 'index'],
    ['--poll-ms', '0', 'job', 'wait', 'j'],
    ['--timeout-ms', 'Infinity', 'memory', 'index'],
    ['--timeout-ms', '2147483648', 'memory', 'index'],
    ['--payload', 'file.json', 'memory', 'index'],
    ['--payload', '-', 'memory', 'read', 'm'],
  ]) {
    assert.throws(
      () => options(args, {}),
      (error) => error instanceof Usage && !error.message.includes(secret),
    );
  }
});

await test('help and input errors need no credentials, terminal or network', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'plowshare-empty-login-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  let output = '',
    errors = '',
    reads = 0;
  const io = {
    env: { PLOWSHARE_CONFIG_DIR: directory },
    stdout: (text: string) => {
      output += text;
    },
    stderr: (text: string) => {
      errors += text;
    },
    stdin: async () => {
      reads++;
      return '{}';
    },
  };
  assert.equal(await run(['--json', '--help'], io), 0);
  assert.equal(field(json(output), ['status']), 'help');
  assert.equal(reads, 0);
  output = '';
  assert.equal(await run(['--json', 'memory', 'curate'], io), 2);
  assert.match(text(json(output), ['said']), /needs project/);
  output = '';
  assert.equal(await run(['--json', 'memory', 'index'], io), 2);
  assert.match(text(json(output), ['said']), /Specify a server/);
  assert.equal(errors, '');
  output = '';
  assert.equal(await run(['--json', 'conversation', 'follow'], io), 2);
  assert.match(text(json(output), ['said']), /conversation id/);
  output = '';
  assert.equal(
    await run(['--json', '--watch', 'conversation', 'follow', 'c'], io),
    2,
  );
  assert.match(text(json(output), ['said']), /do not use/);
  output = '';
  assert.equal(
    await run(['--json', '--payload', '-', 'memory', 'index'], {
      ...io,
      stdin: async () => '[]',
    }),
    2,
  );
  assert.match(text(json(output), ['said']), /JSON object/);
});

await test('terminal job exits preserve truncation, awaiting and cancellation rather than claiming success', () => {
  const ended = (ending: string) =>
    exitFor({
      kind: 'completed',
      job: 'j',
      outcome: { code: 'OK', payload: { outcome: { ending } } },
    });
  assert.equal(ended('ANSWERED'), 0);
  for (const ending of [
    'CALL_BUDGET',
    'TURN_CAP',
    'CANCELLED',
    'AWAITING',
    'STUCK',
    'future-ending',
  ])
    assert.equal(ended(ending), 4);
  for (const ending of [
    'UNAVAILABLE',
    'SUB_AGENT_FAILED',
    'SESSION_GONE',
    'CALL_FAILURES',
  ])
    assert.equal(ended(ending), 1);
  assert.equal(
    exitFor({ kind: 'accepted', job: 'j', outcome: { code: 'ACCEPTED' } }),
    3,
  );
  assert.equal(exitFor({ kind: 'incomplete', outcome: { code: 'OK' } }), 4);
});

await test('options may follow commands and offline validation resolves scope without authentication', async () => {
  let output = '';
  const io = {
    env: { PLOWSHARE_HANDLE: 'should-not-authenticate' },
    stdout: (text: string) => {
      output += text;
    },
    stderr: () => {},
    stdin: async () => '{}',
  };
  const key = '00000000-0000-0000-0000-000000000001';
  assert.equal(
    await run(
      [
        'orchestration',
        'start',
        JSON.stringify({
          agent: 'caller',
          definition: 'custom',
          request: 'task',
          requestId: key,
        }),
        '--project',
        'repo',
        '--json',
        '--validate',
      ],
      io,
    ),
    0,
  );
  assert.deepEqual(json(output), {
    status: 'validated',
    operation: 'orchestration.start',
    scope: { kind: 'project', project: 'repo' },
    mutation: true,
    payload: {
      agent: 'caller',
      definition: 'custom',
      request: 'task',
      requestId: key,
      project: 'repo',
    },
    executed: false,
  });
  output = '';
  assert.equal(
    await run(
      [
        'information',
        'acquire',
        JSON.stringify({
          scope: { kind: 'personal' },
          requestId: key,
          url: 'https://example.test/source',
        }),
        '--validate',
        '--json',
      ],
      io,
    ),
    0,
  );
  assert.equal(field(json(output), ['operation']), 'information.acquire');
  output = '';
  assert.equal(await run(['--json', '--help'], io), 0);
  const help = json(output);
  assert.ok(
    list(help, ['commands']).some(
      (row) => text(row, ['command']) === 'information acquire',
    ),
  );
  assert.ok(
    list(help, ['commands']).some(
      (row) => text(row, ['command']) === 'orchestration start',
    ),
  );
  assert.match(text(help, ['exits', '3']), /accepted/);
  assert.equal(
    options(['orchestration', 'wait', 'orc_one', '--json'], {}).wait,
    true,
  );
});

await test('agent runs default to a new scoped conversation and explicit modes validate offline', async () => {
  let output = '';
  const io = {
    env: { PLOWSHARE_PROJECT: '' },
    stdout: (text: string) => {
      output += text;
    },
    stderr: () => {},
    stdin: async () => '{}',
  };
  const validate = async (
    payload: Record<string, unknown>,
    flags: string[] = [],
  ) => {
    output = '';
    const code = await run(
      [
        'agent',
        'run',
        JSON.stringify(payload),
        '--json',
        '--validate',
        ...flags,
      ],
      io,
    );
    return { code, value: json(output) };
  };
  const basic = { agent: 'a', task: 'work' };
  let result = await validate(basic);
  assert.equal(field(result, ['code']), 0);
  assert.deepEqual(field(result, ['value', 'scope']), { kind: 'global' });
  assert.equal(field(result, ['value', 'payload', 'newConversation']), true);
  result = await validate(basic, ['--project', 'repo', '--new-conversation']);
  assert.equal(field(result, ['value', 'payload', 'project']), 'repo');
  assert.equal(field(result, ['value', 'payload', 'newConversation']), true);
  result = await validate({ ...basic, conversation: 'cnv_existing' });
  assert.equal(
    field(result, ['value', 'payload', 'newConversation']),
    undefined,
  );
  assert.deepEqual(field(result, ['value', 'scope']), {
    kind: 'conversation',
    id: 'cnv_existing',
  });
  result = await validate(basic, ['--standalone']);
  assert.equal(field(result, ['value', 'payload', 'newConversation']), false);
  assert.equal((await validate({ ...basic, images: ['img_1'] })).code, 2);
  assert.equal(
    (await validate({ ...basic, images: ['img_1'] }, ['--standalone'])).code,
    0,
  );
  assert.equal(
    (
      await validate({ ...basic, conversation: 'cnv_existing' }, [
        '--new-conversation',
      ])
    ).code,
    2,
  );
  assert.equal(
    (await validate(basic, ['--new-conversation', '--standalone'])).code,
    2,
  );
  output = '';
  assert.equal(
    await run(
      ['job', 'status', 'job_1', '--new-conversation', '--validate', '--json'],
      io,
    ),
    2,
  );
});

await test('targeted help, group help, aliases and version work offline without stdin', async () => {
  let output = '',
    reads = 0;
  const io = {
    env: { PLOWSHARE_URL: 'invalid-url' },
    stdout: (text: string) => {
      output += text;
    },
    stderr: () => {},
    stdin: async () => {
      reads++;
      throw new Error('must stay offline');
    },
  };
  assert.equal(await run(['web', 'search', '--help', '--json'], io), 0);
  let help = json(output);
  assert.deepEqual(
    list(help, ['commands']).map((row) => text(row, ['command'])),
    ['web search'],
  );
  assert.deepEqual(field(help, ['commands', 0, 'required']), [
    'query',
    'pageSize',
    'max',
    'page',
  ]);
  output = '';
  assert.equal(await run(['web', '--help', '--json'], io), 0);
  assert.deepEqual(
    list(json(output), ['commands'])
      .map((row) => text(row, ['command']))
      .sort(),
    ['web fetch', 'web search'],
  );
  output = '';
  assert.equal(await run(['job', 'wait', '--help', '--json'], io), 0);
  help = json(output);
  assert.equal(field(help, ['commands', 0, 'operation']), 'job.status');
  assert.equal(field(help, ['commands', 0, 'command']), 'job wait');
  assert.equal(field(help, ['aliases', 'job wait', 'wait']), true);
  output = '';
  assert.equal(await run(['unknown', '--help', '--json'], io), 2);
  assert.equal(field(json(output), ['code']), 'INVALID_INPUT');
  output = '';
  assert.equal(await run(['toString', '--help', '--json'], io), 2);
  output = '';
  assert.equal(await run(['--version'], io), 0);
  assert.match(output, /^plowshare-cli \d+\.\d+\.\d+\n$/);
  output = '';
  assert.equal(await run(['--version', '--json'], io), 0);
  assert.equal(field(json(output), ['status']), 'version');
  assert.equal(reads, 0);
});

await test('CLI manages shared named connections including names with spaces and refuses a conflicting selection offline', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'plowshare-cli-connections-'));
  try {
    let output = '',
      failure = '';
    const invoke = (args: string[]) =>
      run(args, {
        env: { PLOWSHARE_CONFIG_DIR: directory },
        stdout: (text) => {
          output = text;
        },
        stderr: (text) => {
          failure = text;
        },
        stdin: async () => {
          throw new Error(
            'No stdin is needed for local connection management.',
          );
        },
      });
    assert.equal(
      await invoke([
        'connection',
        'add',
        'A name with spaces',
        'https://server.example',
        'alice',
      ]),
      0,
    );
    assert.ok(output.includes('A name with spaces'));
    assert.equal(
      await invoke([
        'connection',
        'rename',
        'A name with spaces',
        'Renamed account',
      ]),
      0,
    );
    assert.equal(await invoke(['connection', 'select', 'Renamed account']), 0);
    assert.equal(
      await invoke([
        '--connection',
        'Renamed account',
        '--server',
        'https://foreign.example',
        'memory',
        'index',
      ]),
      2,
    );
    assert.match(failure, /conflicts/);
    assert.equal(await invoke(['connection', 'remove', 'Renamed account']), 0);
    assert.equal(await invoke(['connection', 'list']), 0);
    assert.deepEqual(list(json(output), ['connections']), []);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
