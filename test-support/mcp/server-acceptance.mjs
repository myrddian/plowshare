// Explicit disposable-instance acceptance through public SDK/auth APIs only.
// Provision and exercise are separate: a lost mutation never automatically repeats.
import assert from 'node:assert/strict';
import { randomUUID, randomBytes } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { setTimeout as pause } from 'node:timers/promises';
import {
  signIn,
  changePassword,
} from '../../sdk/typescript/build/binding/auth.js';
import {
  connectPlowshare,
  requirePayload,
  ConnectionFault,
  Refusal,
} from '../../sdk/node/build/sdk.js';
import {
  decodeConfig,
  runIntegration,
  HttpMcpSessions,
} from '../../integrations/mcp/build/mcp/index.js';
const [action, state] = process.argv.slice(2);
assert.ok(
  ['provision', 'exercise', 'inspect'].includes(action) &&
    state?.startsWith('/'),
  'Supply provision|exercise|inspect and the private instance path',
);
const settings = JSON.parse(await readFile(state + '/instance.json', 'utf8'));
const origin = (await readFile(state + '/origin.txt', 'utf8')).trim();
assert.match(origin, /^http:\/\/127\.0\.0\.1:[0-9]+$/);
const fixture = JSON.parse(await readFile(state + '/fixture.json', 'utf8'));
const secretFile = state + '/acceptance-private.json';
const reportFile = state + '/acceptance-report.json';
const persist = (path, value, flag = 'w') =>
  writeFile(path, JSON.stringify(value, null, 2), { mode: 0o600, flag });
const door = {
  base: origin,
  fetch: (url, init) =>
    fetch(url, {
      ...init,
      redirect: 'error',
      signal: AbortSignal.timeout(10000),
    }),
};
const open = (token) => connectPlowshare({ origin, token, timeoutMs: 10000 });
const clients = [];
const connect = async (token) => {
  const client = await open(token);
  clients.push(client);
  return client;
};
const request = async (client, operation, payload) =>
  requirePayload(await client.request(operation, payload));
const agent =
  '---\nname: mcp_probe\ndescription: Isolated MCP acceptance\nmodel: mcp-test-chat\nmax-turns: 4\nmax-model-calls: 8\ndynamic: true\nexported: true\ntools: []\n---\nCall the offered MCP echo once, then finish.\n';
let integration;
let integrationFailure;
const stop = new AbortController();
try {
  if (action === 'provision') {
    // The marker is saved before the first mutation. A failed run needs inspection or a new
    // disposable state directory; restarting this provision pass refuses instead of replaying.
    const saved = {
      phase: 'provisioning',
      intents: [],
      users: [],
      connection: randomUUID(),
      workers: [],
    };
    await persist(secretFile, saved, 'wx');
    let signed = await signIn(door, 'test-admin', settings.password);
    if (signed.mustChangePassword) {
      const next = randomBytes(24).toString('hex');
      await writeFile(state + '/admin-password.txt', next, { mode: 0o600 });
      await changePassword(door, signed.tokens.access, settings.password, next);
      settings.password = next;
      await persist(state + '/instance.json', settings);
      signed = await signIn(door, 'test-admin', next);
    }
    saved.admin = signed.tokens.access;
    await persist(secretFile, saved);
    const admin = await connect(saved.admin);
    const mutate = async (client, operation, payload) => {
      saved.intents.push({ operation, requestId: payload.requestId ?? null });
      await persist(secretFile, saved);
      return request(client, operation, payload);
    };
    await mkdir(state + '/users-workspace', { mode: 0o700 });
    await mutate(admin, 'project.define', {
      name: 'mc06-users',
      workspace: state + '/users-workspace',
    });
    for (const account of ['alice', 'bob']) {
      const created = await mutate(admin, 'admin.account.create', {
        handle: account,
      });
      const user = {
        account,
        password: created.temporaryPassword,
        binding: randomUUID(),
        project: 'mc06-users',
      };
      saved.users.push(user);
      await persist(secretFile, saved);
      const initial = await signIn(door, account, user.password);
      const next = randomBytes(24).toString('hex');
      user.nextPassword = next;
      await persist(secretFile, saved);
      await changePassword(door, initial.tokens.access, user.password, next);
      user.password = next;
      delete user.nextPassword;
      user.token = (await signIn(door, account, next)).tokens.access;
      await persist(secretFile, saved);
      await mutate(admin, 'project.member.add', {
        project: 'mc06-users',
        handle: account,
        role: 'CONTRIBUTOR',
      });
    }
    await mutate(admin, 'agent.define', {
      project: 'mc06-users',
      name: 'mcp_probe',
      text: agent,
    });
    await mutate(admin, 'admin.service.account.create', {
      handle: 'mc06-worker',
    });
    await mutate(admin, 'project.member.add', {
      project: 'mc06-users',
      handle: 'mc06-worker',
      role: 'CONTRIBUTOR',
    });
    const worker = await mutate(admin, 'admin.service.token.create', {
      handle: 'mc06-worker',
      name: 'MCP acceptance worker',
      scopes: [{ project: 'mc06-users', role: 'CONTRIBUTOR' }],
    });
    saved.executor = worker;
    await persist(secretFile, saved);
    const manifest = {
      version: 1,
      name: 'mc06-service',
      service: { handle: 'mc06-application', role: 'CONTRIBUTOR' },
      access: { accounts: [{ handle: 'test-admin', role: 'MANAGER' }] },
      toolScopes: [{ scope: 'mcp', provider: 'mcp-fixture', grants: ['*'] }],
      toolGrants: [{ toolScope: 'mcp', agent: 'mcp_probe' }],
    };
    const deployment = await mutate(admin, 'application.deploy', {
      project: 'mc06-service',
      requestId: randomUUID(),
      expectedRevision: null,
      destination: { store: 'test', path: 'mc06-service' },
      writableAreas: [],
      files: [
        { path: 'plowshare.json', text: JSON.stringify(manifest) },
        { path: 'agents/mcp_probe.md', text: agent },
      ],
    });
    assert.ok(deployment.release.revision);
    const service = await mutate(admin, 'application.token.issue', {
      project: 'mc06-service',
      requestId: randomUUID(),
      expiresInDays: 1,
      rotate: false,
    });
    saved.service = {
      account: service.receipt.principal,
      token: service.credential,
      project: 'mc06-service',
      binding: randomUUID(),
      connection: randomUUID(),
    };
    await persist(secretFile, saved);
    const overlay = JSON.parse(
      await readFile(state + '/application.json', 'utf8'),
    );
    overlay.plowshare.providers.hosting.executors = [worker.token.principal];
    await persist(state + '/application.json', overlay);
    saved.phase = 'provisioned';
    await persist(secretFile, saved);
    const login = [
      'Local MCP test instance',
      'Console: ' + origin,
      '',
      'Administrator: test-admin',
      'Password: ' + settings.password,
      '',
      ...saved.users.flatMap((user) => [
        'User: ' + user.account,
        'Password: ' + user.password,
        '',
      ]),
      'Synthetic model/MCP services only. Keep this file private.',
    ].join('\n');
    await writeFile(state + '/LOGIN.txt', login, { mode: 0o600, flag: 'wx' });
    console.log(
      'Provisioned two users and an Application-owned service identity. Stop/start the instance, then run exercise.',
    );
  } else if (action === 'exercise') {
    const saved = JSON.parse(await readFile(secretFile, 'utf8'));
    assert.equal(
      saved.phase,
      'provisioned',
      'Exercise is once-only: inspect retained intents/results after failure',
    );
    saved.phase = 'exercising';
    await persist(secretFile, saved);
    // Login again after the server restart; service tokens retain their exact principal.
    const admin = await connect(
      (await signIn(door, 'test-admin', settings.password)).tokens.access,
    );
    const mutate = async (client, operation, payload) => {
      saved.intents.push({ operation, requestId: payload.requestId ?? null });
      await persist(secretFile, saved);
      return request(client, operation, payload);
    };
    await mutate(admin, 'provider.connection.save', {
      project: 'mc06-users',
      connection: saved.connection,
      requestId: randomUUID(),
      expectedRevision: null,
      definition: {
        name: 'Isolated MCP fixture',
        endpoint: fixture.endpoint,
        authentication: 'API_KEY',
        apiKeyHeader: 'Authorization',
        oauth: null,
      },
    });
    const actors = [...saved.users, saved.service];
    const actorClients = [];
    for (const actor of actors) {
      const label = actor === saved.service ? 'service' : actor.account;
      if (actor !== saved.service)
        actor.token = (
          await signIn(door, actor.account, actor.password)
        ).tokens.access;
      const client = await connect(actor.token);
      actorClients.push(client);
      const credentialProject =
        actor === saved.service ? actor.project : 'mc06-users';
      const connectionId =
        actor === saved.service ? actor.connection : saved.connection;
      if (actor === saved.service)
        await mutate(admin, 'provider.connection.save', {
          project: credentialProject,
          connection: connectionId,
          requestId: randomUUID(),
          expectedRevision: null,
          definition: {
            name: 'Isolated MCP fixture',
            endpoint: fixture.endpoint,
            authentication: 'API_KEY',
            apiKeyHeader: 'Authorization',
            oauth: null,
          },
        });
      await mutate(
        actor === saved.service ? admin : client,
        'provider.credential.put',
        {
          project: credentialProject,
          connection: connectionId,
          subject: actor === saved.service ? actor.account : null,
          requestId: randomUUID(),
          expectedGeneration: null,
          secret: 'Bearer ' + settings.upstream[label],
        },
      );
      const receipt = await mutate(client, 'tool.scope.host.save', {
        project: actor.project,
        binding: actor.binding,
        requestId: randomUUID(),
        expectedGeneration: null,
        definition: {
          scope: 'mcp',
          provider: 'mcp-fixture',
          prefix: 'mcp_',
          grants: ['*'],
          agents: ['mcp_probe'],
          leaseSeconds: 120,
          credentialProject,
          connection: connectionId,
          subject: null,
          executor: saved.executor.token.principal,
        },
      });
      assert.equal(receipt.binding.owner, actor.account);
      assert.equal(receipt.binding.credentialSubject, actor.account);
      assert.equal(receipt.binding.application, actor === saved.service);
      saved.workers.push({
        binding: actor.binding,
        generation: receipt.binding.generation,
      });
      await persist(secretFile, saved);
    }
    // One executor process and separate hosted contexts/journals over the actual server.
    const executor = await connect(saved.executor.credential);
    const config = decodeConfig({
      origin,
      tokenEnvironment: 'TEST_ONLY',
      journalDirectory: state + '/mcp-journals',
      requestTimeoutMs: 10000,
      defaults: {
        protocol: '2026-07-28',
        allowHttp: false,
        allowPrivateNetwork: true,
        timeoutMs: 5000,
        timeoutSeconds: 30,
        pollMs: 25,
      },
      workers: saved.workers,
    });
    integration = runIntegration(
      executor,
      config,
      new HttpMcpSessions(),
      stop.signal,
    ).catch((error) => {
      integrationFailure = error;
    });
    const wait = async (work) => {
      const deadline = Date.now() + 15000;
      while (Date.now() < deadline) {
        if (integrationFailure) throw integrationFailure;
        const result = await work();
        if (result) return result;
        await pause(25);
      }
      throw new Error(
        'Configured acceptance deadline exceeded; inspect original receipts',
      );
    };
    for (let index = 0; index < actors.length; index++) {
      const actor = actors[index];
      const client = actorClients[index];
      await wait(async () =>
        (await request(client, 'tool.scope.list', { project: actor.project }))
          .connections.length
          ? true
          : null,
      );
      // A second independent public SDK client sees the same user's server binding.
      const second = await connect(actor.token);
      const firstBindings = await request(client, 'tool.scope.host.list', {
        project: actor.project,
      });
      const secondBindings = await request(second, 'tool.scope.host.list', {
        project: actor.project,
      });
      assert.deepEqual(firstBindings, secondBindings);
      assert.ok(
        firstBindings.every((binding) => binding.owner === actor.account),
      );
      const ownCredential = await request(
        second,
        'provider.credential.status',
        {
          project: actor === saved.service ? actor.project : 'mc06-users',
          connection:
            actor === saved.service ? actor.connection : saved.connection,
          subject: null,
        },
      );
      assert.equal(ownCredential.subject, actor.account);
      assert.equal(ownCredential.state, 'READY');
      const before = await (await fetch(fixture.observations)).json();
      const job = await mutate(second, 'agent.run', {
        project: actor.project,
        agent: 'mcp_probe',
        task: 'Exercise the MCP fixture once.',
        newConversation: true,
        maxTurns: 4,
      });
      actor.job = job.id;
      actor.conversation = job.conversation;
      await persist(secretFile, saved);
      const final = await wait(async () => {
        const current = await request(second, 'job.status', { job: job.id });
        return current.outcome ? current : null;
      });
      assert.equal(final.outcome.answered, true);
      const after = await (await fetch(fixture.observations)).json();
      const label = actor === saved.service ? 'service' : actor.account;
      assert.equal(after.counts[label], before.counts[label] + 1);
      for (const other of ['alice', 'bob', 'service'].filter(
        (name) => name !== label,
      ))
        assert.equal(after.counts[other], before.counts[other]);
      try {
        actor.usage = await wait(async () => {
          const usage = await request(second, 'usage.calls', {
            project: actor.project,
            conversation: job.conversation,
            limit: 20,
          });
          return usage.calls?.length ? usage : null;
        });
        assert.ok(
          actor.usage.calls.every(
            (call) => call.conversation_id === job.conversation,
          ),
        );
      } catch (error) {
        // Preserve a known public-contract gap explicitly; this is a failed acceptance
        // dimension, never a substitute raw response, hidden success or request replay.
        if (
          error instanceof ConnectionFault &&
          error.code === 'INVALID_ENVELOPE'
        )
          actor.accountingGap = 'usage.calls INVALID_ENVELOPE';
        else if (error instanceof Refusal)
          actor.accountingGap =
            'usage.calls refused: ' + error.reply.outcome.code;
        else throw error;
      }
      await persist(secretFile, saved);
      console.log(
        label +
          ': own credential, two clients and one MCP effect passed; accounting ' +
          (actor.accountingGap
            ? 'BLOCKED by public accounting read'
            : 'passed'),
      );
    }
    // A different human cannot read another owner's credential or binding.
    const [alice, bob] = actorClients;
    assert.equal(
      (
        await bob.request('tool.scope.host.status', {
          project: 'mc06-users',
          binding: saved.users[0].binding,
        })
      ).kind,
      'refused',
    );
    await assert.rejects(
      alice.request('provider.credential.status', {
        project: 'mc06-users',
        connection: saved.connection,
        subject: 'bob',
      }),
      /Select only an exact service principal or null/,
    );
    const accountingPassed = actors.every((actor) => !actor.accountingGap);
    const report = {
      status: accountingPassed ? 'passed' : 'blocked-accounting',
      accounting: accountingPassed
        ? 'verified public usage reads'
        : 'public accounting read blocked; separate platform fix required',
      accountingGaps: actors.flatMap((actor) =>
        actor.accountingGap ? [actor.accountingGap] : [],
      ),
      configuredExecutionPassed: true,
      environment:
        'local configured server and synthetic TLS MCP/model services',
      users: 2,
      independentClientsPerIdentity: 2,
      applicationPrincipal: saved.service.account,
      executorPrincipal: saved.executor.token.principal,
      oneEffectPerIdentity: true,
      liveOrganizationOAuth: 'not tested',
      separateHosts: 'not tested',
      observations: (await (await fetch(fixture.observations)).json()).counts,
    };
    await persist(reportFile, report);
    saved.phase = report.status;
    await persist(secretFile, saved);
    console.log(
      'Configured public-SDK acceptance ' +
        report.status +
        '; redacted report: ' +
        reportFile,
    );
    if (!accountingPassed) process.exitCode = 2;
  } else {
    // Read-only inspection of original jobs after an interrupted pass. Never register,
    // publish, renew, run an agent or start an MCP session from this branch.
    const saved = JSON.parse(await readFile(secretFile, 'utf8'));
    assert.ok(
      ['exercising', 'blocked-accounting', 'passed'].includes(saved.phase),
    );
    const actors = [...saved.users, saved.service];
    const readers = [];
    const before = await (await fetch(fixture.observations)).json();
    for (const actor of actors) {
      assert.ok(
        actor.job && actor.conversation,
        'No retained job: inspect the original submission; no new job was sent',
      );
      const token =
        actor === saved.service
          ? actor.token
          : (await signIn(door, actor.account, actor.password)).tokens.access;
      const one = await connect(token);
      const two = await connect(token);
      readers.push(one);
      const bindings = await request(one, 'tool.scope.host.list', {
        project: actor.project,
      });
      assert.deepEqual(
        bindings,
        await request(two, 'tool.scope.host.list', { project: actor.project }),
      );
      assert.ok(
        bindings.some(
          (binding) =>
            binding.binding === actor.binding &&
            binding.owner === actor.account &&
            binding.credentialSubject === actor.account &&
            binding.application === (actor === saved.service),
        ),
      );
      const grant = await request(two, 'provider.credential.status', {
        project: actor === saved.service ? actor.project : 'mc06-users',
        connection:
          actor === saved.service ? actor.connection : saved.connection,
        subject: null,
      });
      assert.equal(grant.subject, actor.account);
      assert.equal(grant.state, 'READY');
      const job = await request(two, 'job.status', { job: actor.job });
      assert.equal(job.conversation, actor.conversation);
      assert.equal(job.outcome?.answered, true);
      const label = actor === saved.service ? 'service' : actor.account;
      assert.equal(
        before.counts[label],
        1,
        'Exactly one observed effect in the original fixture lifetime',
      );
      try {
        const usage = await request(two, 'usage.calls', {
          project: actor.project,
          conversation: actor.conversation,
          limit: 20,
        });
        assert.ok(usage.calls?.length);
        assert.ok(
          usage.calls.every(
            (call) => call.conversation_id === actor.conversation,
          ),
        );
        delete actor.accountingGap;
      } catch (error) {
        if (
          error instanceof ConnectionFault &&
          error.code === 'INVALID_ENVELOPE'
        )
          actor.accountingGap = 'usage.calls INVALID_ENVELOPE';
        else if (error instanceof Refusal)
          actor.accountingGap =
            'usage.calls refused: ' + error.reply.outcome.code;
        else throw error;
      }
    }
    assert.equal(
      (
        await readers[1].request('tool.scope.host.status', {
          project: 'mc06-users',
          binding: saved.users[0].binding,
        })
      ).kind,
      'refused',
    );
    await assert.rejects(
      readers[0].request('provider.credential.status', {
        project: 'mc06-users',
        connection: saved.connection,
        subject: 'bob',
      }),
      /Select only an exact service principal or null/,
    );
    const after = await (await fetch(fixture.observations)).json();
    assert.deepEqual(
      after,
      before,
      'Original-job inspection must send no MCP effects',
    );
    const gaps = actors.flatMap((actor) =>
      actor.accountingGap ? [actor.accountingGap] : [],
    );
    const report = {
      status: gaps.length ? 'blocked-accounting' : 'passed',
      environment:
        'local configured server and synthetic TLS MCP/model services',
      users: 2,
      independentClientsPerIdentity: 2,
      applicationIdentityVerified: true,
      separateExecutorVerified:
        saved.service.account !== saved.executor.token.principal,
      configuredExecutionPassed: true,
      crossUserRefusalPassed: true,
      readOnlyOriginalJobInspection: true,
      accountingGaps: gaps,
      observations: after.counts,
      liveOrganizationOAuth: 'not tested',
      separateHosts: 'not tested',
    };
    await persist(reportFile, report);
    saved.phase = report.status;
    await persist(secretFile, saved);
    console.log(
      'Original jobs/identities and no-replay inspection verified; acceptance ' +
        report.status +
        '. Report: ' +
        reportFile,
    );
    if (gaps.length) process.exitCode = 2;
  }
} finally {
  stop.abort();
  if (integration) await integration;
  for (const client of clients.reverse()) client.close();
  if (integrationFailure)
    throw new Error('Configured worker failed; inspect retained journals', {
      cause: integrationFailure,
    });
}
