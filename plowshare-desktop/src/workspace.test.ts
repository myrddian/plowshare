import {
  decodePayload,
  decodeRequest,
} from 'plowshare-client-ts/operations/schema';
import type { Request as WsRequest } from 'plowshare-client-ts/operations/direct';
import type { Arrival } from 'plowshare-client-ts/binding/connection';
import type { SavedProject } from './project-config.ts';
import { present } from './fixture.test-support.ts';
import { mkdtemp, realpath, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir, hostname } from 'node:os';
import { thisMachine } from 'plowshare-client-node/marker';
import { join } from 'node:path';
import {
  agentWire,
  conversationWire,
  projectWire,
  entryPageWire,
  entryWire,
} from './wire-fixtures.ts';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DesktopWorkspace } from './workspace.ts';
import type { Connector, Connected } from './client.ts';
import type { JobStore, SavedJob } from './job-store.ts';
import type { ProjectStore } from './project-config.ts';
import type { ConnectionStore, SavedConnection } from './connection-config.ts';

function fixture(
  connectionStore?: ConnectionStore,
  jobStore?: JobStore,
  files = false,
) {
  const catalogs = new Map<
    string,
    NonNullable<ReturnType<typeof agentWire>['commands']>
  >();
  let serverProjects = [projectWire('Research'), projectWire('Writing')];
  const sessions: {
    id: string;
    closed: boolean;
    calls: WsRequest[];
    drop: () => void;
  }[] = [];
  const fileClaims: string[] = [];
  let fileRefusal: string | undefined;
  const storedProjects: SavedProject[] = [];
  let peerWait = Promise.resolve();
  const create = async (
    _push: (value: unknown) => void,
    lost: () => void,
  ): Promise<Connected> => {
    await peerWait;
    const row = {
      id: `session-${sessions.length}`,
      closed: false,
      calls: [] as WsRequest[],
      drop: lost,
    };
    sessions.push(row);
    const local: ReturnType<typeof projectWire>[] = [];
    return {
      session: row.id,
      spawn: create,
      ...(files
        ? {
            openFiles: async (
              claim: Parameters<NonNullable<Connected['openFiles']>>[0],
            ) => {
              fileClaims.push(claim.project);
              const listeners = new Map<string, (event: Arrival) => void>();
              setTimeout(() => {
                if (fileRefusal) {
                  const closing = {
                    data: undefined,
                    code: 1003,
                    reason: fileRefusal,
                  };
                  listeners.get('close')?.(closing);
                } else
                  listeners.get('message')?.({
                    data: JSON.stringify({
                      ready: true,
                      project: claim.project,
                    }),
                  });
              }, 1);
              return {
                addEventListener(type: string, fn: (event: Arrival) => void) {
                  listeners.set(type, fn);
                },
                send() {},
                close() {
                  queueMicrotask(() =>
                    listeners.get('close')?.({ data: { code: 1000 } }),
                  );
                },
              };
            },
          }
        : {}),
      connection: {
        close() {
          row.closed = true;
        },
        async ask(type, payload) {
          row.calls.push(decodeRequest(type, payload));
          if (type === 'project.list')
            return {
              code: 'OK',
              payload: [...serverProjects, ...local],
            };
          if (type === 'project.attach') {
            const value = decodePayload('project.attach', payload);
            const key =
              'client:' +
              row.id +
              ':' +
              Buffer.from(value.name).toString('base64url');
            const project = {
              ...projectWire(key),
              workspace: value.workspace,
              machine: value.machine,
              type: 'DISJOINT',
              displayName: value.name,
            };
            local.push(project);
            return { code: 'OK', payload: project };
          }
          if (type === 'conversation.list') {
            const project = decodePayload('conversation.list', payload).project;
            return {
              code: 'OK',
              payload: [
                conversationWire(project ? `${project}-chat` : 'global-chat', {
                  project: project ?? null,
                }),
              ],
            };
          }
          if (type === 'agent.list')
            return {
              code: 'OK',
              payload: [
                agentWire({
                  preferred: false,
                  commands:
                    catalogs.get(
                      decodePayload(
                        type === 'agent.list'
                          ? 'agent.list'
                          : 'conversation.list',
                        payload,
                      ).project ?? '',
                    ) ?? [],
                }),
              ],
            };
          if (type === 'approval.list')
            return { code: 'OK', payload: { approvals: [] } };
          if (type === 'conversation.trajectory') {
            const id = decodePayload(
              'conversation.trajectory',
              payload,
            ).conversation;
            return {
              code: 'OK',
              payload: entryPageWire(
                id === 'Research-chat'
                  ? [
                      entryWire(1, 'answer', 'Delegating', {
                        toolCalls: [
                          {
                            id: 'delegate',
                            name: 'agent_run',
                            arguments: '{}',
                            length: 2,
                            cut: false,
                            salient: null,
                            opened: {
                              agent: 'analyst',
                              conversation: 'Research-delegate',
                            },
                          },
                        ],
                      }),
                    ]
                  : [],
                id === 'Research-chat' ? 1 : 0,
              ),
            };
          }
          if (type === 'inbox.list')
            return { code: 'OK', payload: { items: [], unread: 0 } };
          if (type === 'orchestration.list')
            return { code: 'OK', payload: { orchestrations: [] } };
          return { code: 'OK' };
        },
      },
    };
  };
  const connector: Connector = async (
    _base,
    handle,
    _password,
    push,
    closed,
  ) => ({ ...(await create(push, closed)), handle: handle || 'alice' });
  const store: ProjectStore = {
    async list() {
      return [...storedProjects];
    },
    async put(value) {
      savedWrites.push(value);
    },
    async remove() {},
  };
  const savedWrites: SavedProject[] = [];
  const workspace = new DesktopWorkspace(
    () => {},
    store,
    connector,
    connectionStore,
    jobStore,
  );
  return {
    workspace,
    savedWrites,
    store,
    sessions,
    catalogs,
    setServerProjects: (rows: ReturnType<typeof projectWire>[]) => {
      serverProjects = rows;
    },
    storedProjects,
    fileClaims,
    refuseFiles: (reason?: string) => {
      fileRefusal = reason;
    },
    delayPeer: () => {
      let release!: () => void;
      peerWait = new Promise((resolve) => {
        release = resolve;
      });
      return () => {
        release();
        peerWait = Promise.resolve();
      };
    },
  };
}
const connect = (workspace: DesktopWorkspace, handle = 'alice') =>
  workspace.dispatch({
    action: 'connect',
    base: 'http://localhost:8080',
    handle,
    password: 'fixture-password',
  });

await test('lazily loaded project sessions retain independent human command catalogs during refresh', async () => {
  const { workspace, sessions, catalogs } = fixture();
  const skill = (name: string) => ({
    command: `/skill:${name}`,
    aliases: [],
    kind: 'skill' as const,
    name,
    description: name,
    argumentHint: 'Work',
    executor: 'interlocutor',
    mode: 'NEW' as const,
    tier: 'SESSION',
    hash: name,
    agentVisible: false,
  });
  catalogs.set('Research', [skill('research')]);
  catalogs.set('Writing', [skill('write')]);
  try {
    await connect(workspace);
    await Promise.all(
      ['Research', 'Writing'].map((project) =>
        workspace.dispatch({ action: 'scope', project }),
      ),
    );
    assert.deepEqual(
      present(present(workspace.state.agents.Research)[0]).commands?.map(
        (row) => row.command,
      ),
      ['/skill:research'],
    );
    assert.deepEqual(
      present(present(workspace.state.agents.Writing)[0]).commands?.map(
        (row) => row.command,
      ),
      ['/skill:write'],
    );
    const owners = ['Research', 'Writing'].map((project) =>
      sessions.find((row) =>
        row.calls.some(
          (call) =>
            call.type === 'agent.list' && call.payload.project === project,
        ),
      )!,
    );
    assert.notEqual(present(owners[0]).id, present(owners[1]).id);
    assert.ok(owners.every((row) => !row.closed));
    catalogs.set('Writing', [skill('revise')]);
    await workspace.dispatch({ action: 'refresh' });
    assert.equal(
      present(
        present(present(workspace.state.agents.Research)[0]).commands?.[0],
      ).command,
      '/skill:research',
    );
    assert.equal(
      present(present(present(workspace.state.agents.Writing)[0]).commands?.[0])
        .command,
      '/skill:revise',
    );
  } finally {
    await workspace.shutdown();
  }
});

await test('project clients own independent sessions and conversation views', async () => {
  const { workspace, sessions } = fixture();
  try {
    await connect(workspace);
    await Promise.all([
      workspace.dispatch({ action: 'scope', project: 'Research' }),
      workspace.dispatch({ action: 'scope', project: 'Writing' }),
    ]);
    await workspace.followView('research-window', 'Research-chat');
    await workspace.followView('writing-window', 'Writing-chat');
    assert.equal(sessions.length, 3);
    for (const name of ['Research', 'Writing']) {
      const session = sessions.find((row) =>
        row.calls.some(
          (call) =>
            call.type === 'conversation.list' && call.payload.project === name,
        ),
      )!;
      assert.ok(
        session.calls.some(
          (call) =>
            call.type === 'conversation.follow' &&
            call.payload.conversations?.includes(`${name}-chat`),
        ),
      );
      assert.ok(
        !session.calls.some(
          (call) =>
            call.type === 'conversation.follow' &&
            call.payload.conversations?.includes(
              `${name === 'Writing' ? 'Research' : 'Writing'}-chat`,
            ),
        ),
      );
    }
    await assert.rejects(
      workspace.dispatch({ action: 'scope', project: 'Unknown project' }),
      /available project/,
    );
    assert.equal(sessions.length, 3);
  } finally {
    await workspace.shutdown();
  }
});
await test('a delegated project trajectory stays on its parent session without requiring a top-level chat listing', async () => {
  const { workspace, sessions } = fixture();
  try {
    await connect(workspace);
    await workspace.dispatch({ action: 'scope', project: 'Research' });
    await workspace.followView('parent', 'Research-chat');
    await workspace.followView('delegate', 'Research-delegate');
    const owner = sessions.find((session) =>
      session.calls.some(
        (call) =>
          call.type === 'conversation.list' &&
          call.payload.project === 'Research',
      ),
    )!;
    assert.ok(
      owner.calls.some(
        (call) =>
          call.type === 'conversation.trajectory' &&
          call.payload.conversation === 'Research-delegate',
      ),
    );
    assert.equal(
      present(sessions[0]).calls.some(
        (call) =>
          call.type === 'conversation.trajectory' &&
          call.payload.conversation === 'Research-delegate',
      ),
      false,
    );
    assert.equal(
      workspace.state.conversations.some(
        (row) => row.id === 'Research-delegate',
      ),
      false,
    );
  } finally {
    await workspace.shutdown();
  }
});

await test('a late project reconnect cannot replace a newer main chat selection', async () => {
  const { workspace, sessions, delayPeer: _delayPeer } = fixture();
  const delayPeer = _delayPeer.bind(undefined);
  try {
    await connect(workspace);
    await workspace.dispatch({ action: 'scope', project: 'Research' });
    present(sessions[1]).drop();
    const release = delayPeer();
    const old = workspace.followView('chat', 'Research-chat');
    await workspace.followView('chat', 'global-chat');
    release();
    await old;
    assert.deepEqual(
      present(sessions[0])
        .calls.filter((row) => row.type === 'conversation.follow')
        .at(-1)?.payload.conversations,
      ['global-chat'],
    );
    assert.deepEqual(
      present(sessions[2])
        .calls.filter((row) => row.type === 'conversation.follow')
        .at(-1)?.payload.conversations,
      [],
    );
  } finally {
    await workspace.shutdown();
  }
});

await test('shutdown waits for pending project authentication and closes its late socket', async () => {
  const { workspace, sessions, delayPeer: _delayPeer } = fixture();
  const delayPeer = _delayPeer.bind(undefined);
  await connect(workspace);
  const release = delayPeer();
  const opening = workspace.dispatch({ action: 'scope', project: 'Research' });
  const refused = assert.rejects(opening, /superseded|changed/);
  await new Promise<void>((resolve) => setImmediate(resolve));
  let stopped = false;
  const shutdown = workspace.shutdown().then(() => {
    stopped = true;
  });
  await new Promise<void>((resolve) => setImmediate(resolve));
  assert.equal(stopped, false);
  release();
  await refused;
  await shutdown;
  assert.ok(sessions.every((row) => row.closed));
});

await test('account replacement closes all prior project sessions and drops their data', async () => {
  const { workspace, sessions } = fixture();
  try {
    await connect(workspace);
    await workspace.dispatch({ action: 'scope', project: 'Research' });
    await connect(workspace, 'bob');
    assert.equal(present(sessions[0]).closed, true);
    assert.equal(present(sessions[1]).closed, true);
    assert.equal(workspace.state.handle, 'bob');
    assert.ok(
      !workspace.state.conversations.some((row) => row.id === 'Research-chat'),
    );
  } finally {
    await workspace.shutdown();
  }
});

await test('closing a project trajectory while offline removes its follow before account reconnect', async () => {
  const { workspace, sessions } = fixture();
  try {
    await connect(workspace);
    await workspace.dispatch({ action: 'scope', project: 'Research' });
    await workspace.followView('trajectory', 'Research-chat');
    await workspace.dispatch({ action: 'disconnect' });
    await workspace.followView('trajectory');
    await connect(workspace);
    await workspace.dispatch({ action: 'scope', project: 'Research' });
    const latest = sessions.at(-1)!;
    assert.deepEqual(
      latest.calls.filter((row) => row.type === 'conversation.follow').at(-1)
        ?.payload.conversations,
      [],
    );
  } finally {
    await workspace.shutdown();
  }
});

await test('a conversation validated by Board retains its metadata when routed to a project client', async () => {
  const { workspace, sessions } = fixture();
  try {
    await connect(workspace);
    // Account inspectors can expose a log absent from the normal chat listing.
    workspace.control.retainConversation({
      id: 'board-member-log',
      project: 'Archived project',
      title: 'Board member',
    });
    await workspace.followView('board-trajectory', 'board-member-log');
    await workspace.dispatch({
      action: 'history',
      conversation: 'board-member-log',
    });
    assert.ok(
      present(sessions[1]).calls.some(
        (row) =>
          row.type === 'conversation.trajectory' &&
          row.payload.conversation === 'board-member-log',
      ),
    );
    assert.ok(
      workspace.state.conversations.some(
        (row) =>
          row.id === 'board-member-log' && row.project === 'Archived project',
      ),
    );
    await assert.rejects(
      workspace.dispatch({
        action: 'history',
        conversation: 'unvalidated-log',
      }),
      /available conversation/,
    );
  } finally {
    await workspace.shutdown();
  }
});

await test('invalid login fields cannot replace the namespace or close existing project clients', async () => {
  const { workspace, sessions } = fixture();
  try {
    await connect(workspace);
    await workspace.dispatch({ action: 'scope', project: 'Research' });
    await assert.rejects(
      workspace.dispatch({
        action: 'connect',
        base: 'http://localhost:8080',
        handle: ' ',
        password: 'fixture-password',
      }),
      /Invalid handle/,
    );
    assert.equal(workspace.state.handle, 'alice');
    assert.equal(workspace.state.connected, true);
    assert.equal(present(sessions[0]).closed, false);
    assert.equal(present(sessions[1]).closed, false);
  } finally {
    await workspace.shutdown();
  }
});

await test('startup restores once with saved login and explicit disconnect prevents restart reconnect', async () => {
  let saved: SavedConnection = {
    server: 'http://localhost:8080',
    account: 'alice',
    reconnect: true,
  };
  const store: ConnectionStore = {
    async load() {
      return saved;
    },
    async save(value) {
      saved = value;
    },
  };
  const { workspace, sessions } = fixture(store);
  try {
    const replies = await Promise.all([
      workspace.dispatch({ action: 'bootstrap' }),
      workspace.dispatch({ action: 'bootstrap' }),
    ]);
    assert.ok(replies.every((reply) => reply.state.connected));
    assert.equal(sessions.length, 1);
    assert.equal(saved.account, 'alice');
    await workspace.dispatch({ action: 'disconnect' });
    assert.equal(saved.reconnect, false);
    await workspace.dispatch({ action: 'bootstrap' });
    assert.equal(sessions.length, 1);
    const restarted = fixture(store);
    try {
      const reply = await restarted.workspace.dispatch({ action: 'bootstrap' });
      assert.equal(reply.state.connected, false);
      assert.equal(reply.state.base, saved.server);
      assert.equal(reply.state.handle, saved.account);
      assert.equal(restarted.sessions.length, 0);
    } finally {
      await restarted.workspace.shutdown();
    }
  } finally {
    await workspace.shutdown();
  }
});

await test('connection preference write failures are visible without losing the live session', async () => {
  const { workspace } = fixture({
    async load() {
      return undefined;
    },
    async save() {
      throw new Error('disk full');
    },
  });
  try {
    const reply = await connect(workspace);
    assert.equal(reply.state.connected, true);
    assert.match(reply.state.connectionPersistenceError!, /could not be saved/);
  } finally {
    await workspace.shutdown();
  }
});

await test('disconnect during preference loading prevents a late automatic connection', async () => {
  let release!: (value: SavedConnection) => void;
  const { workspace, sessions } = fixture({
    load: () =>
      new Promise((resolve) => {
        release = resolve;
      }),
    async save() {},
  });
  try {
    const restoring = workspace.dispatch({ action: 'bootstrap' });
    await workspace.dispatch({ action: 'disconnect' });
    release({
      server: 'http://localhost:8080',
      account: 'alice',
      reconnect: true,
    });
    await restoring;
    assert.equal(sessions.length, 0);
    assert.equal(workspace.state.connected, false);
  } finally {
    await workspace.shutdown();
  }
});

await test('job recovery keeps receipts for unavailable projects instead of deleting them from the journal', async (t) => {
  const retained: SavedJob = {
    id: 'unavailable-job',
    handle: 'unavailable-job',
    conversation: 'missing-chat',
    agent: 'worker',
    project: 'missing',
  };
  let saved: SavedJob[] = [];
  const store: JobStore = {
    async load() {
      return [retained];
    },
    async save(_base, _account, rows) {
      saved = rows;
    },
    async flush() {},
  };
  const f = fixture(undefined, store);
  t.after(() => f.workspace.shutdown());
  await connect(f.workspace);
  assert.match(f.workspace.state.jobRecoveryError!, /retained/);
  assert.deepEqual(saved, [retained]);
  assert.equal(
    f.sessions.some((session) =>
      session.calls.some((call) => call.type === 'agent.run'),
    ),
    false,
  );
});

await test('manifest folders use their own project connection and are not persisted for other clients', async () => {
  const root = await realpath(
      await mkdtemp(join(tmpdir(), 'workspace-disjoint-')),
    ),
    { workspace, sessions, savedWrites } = fixture(undefined, undefined, true);
  try {
    const manifest = JSON.stringify({
      version: 1,
      name: 'Integration',
      routing: {
        sendTo: ['notifications'],
        routeFiles: ['routes/internal.json'],
      },
      integration: { enabled: true },
    });
    await writeFile(join(root, 'plowshare'), manifest);
    await connect(workspace);
    const reply = await workspace.rootDirectory(root);
    assert.ok(reply.rootProject?.startsWith('client:'));
    assert.equal(
      workspace.state.projects.find((row) => row.name === reply.rootProject)
        ?.displayName,
      'Integration',
    );
    const owner = sessions.find((row) =>
      row.calls.some((call) => call.type === 'project.attach'),
    )!;
    assert.notEqual(owner.id, sessions[0]!.id);
    assert.ok(
      owner.calls.some(
        (call) =>
          call.type === 'conversation.list' &&
          call.payload.project === reply.rootProject,
      ),
    );
    assert.equal(savedWrites.length, 0);
    const again = await workspace.rootDirectory(root);
    assert.equal(again.rootProject, reply.rootProject);
    assert.equal(sessions.length, 2);
    assert.equal(await readFile(join(root, 'plowshare'), 'utf8'), manifest);
    await assert.rejects(readFile(join(root, '.plowshare/project')), {
      code: 'ENOENT',
    });
  } finally {
    await workspace.shutdown();
    await rm(root, { recursive: true, force: true });
  }
});

await test('an offline project session cannot resurrect a revoked Application in account navigation', async () => {
  const { workspace, sessions, setServerProjects } = fixture();
  setServerProjects([
    projectWire('Research', { kind: 'application' }),
    projectWire('Writing'),
  ]);
  try {
    await connect(workspace);
    await workspace.dispatch({ action: 'scope', project: 'Research' });
    const child = sessions.find((row) =>
      row.calls.some(
        (call) =>
          call.type === 'agent.list' && call.payload.project === 'Research',
      ),
    );
    assert.ok(child);
    child.drop();
    setServerProjects([projectWire('Writing')]);
    await workspace.dispatch({ action: 'refresh' });
    assert.ok(!workspace.state.projects.some((row) => row.name === 'Research'));
    assert.ok(
      !workspace.state.conversations.some((row) => row.project === 'Research'),
    );
    await assert.rejects(
      workspace.dispatch({ action: 'scope', project: 'Research' }),
      /available project/,
    );
  } finally {
    await workspace.shutdown();
  }
});

await test('saved folders do not expose a registered project absent from a successful account listing', async () => {
  const { workspace, store, setServerProjects } = fixture();
  setServerProjects([projectWire('Writing')]);
  store.list = async () => [
    {
      server: 'http://localhost:8080',
      account: 'alice',
      name: 'Research',
      path: '/fixture/research',
      machine: 'fixture',
      enabled: false,
    },
  ];
  try {
    await connect(workspace);
    assert.ok(!workspace.state.projects.some((row) => row.name === 'Research'));
    assert.ok(
      !workspace.state.projectFolders?.some((row) => row.name === 'Research'),
    );
  } finally {
    await workspace.shutdown();
  }
});

await test('a refused saved folder is not retried by navigation or run preparation; explicit reconnect recovers', async (t) => {
  const root = await realpath(
    await mkdtemp(join(tmpdir(), 'plowshare-refused-')),
  );
  t.after(() => rm(root, { recursive: true, force: true }));
  const { workspace, storedProjects, fileClaims, refuseFiles } = fixture(
    undefined,
    undefined,
    true,
  );
  storedProjects.push({
    server: 'http://localhost:8080',
    account: 'alice',
    name: 'Research',
    path: root,
    machine: thisMachine(process.env, hostname()),
    enabled: true,
  });
  refuseFiles('Project is rooted elsewhere. Use the original directory.');
  try {
    await connect(workspace);
    assert.deepEqual(fileClaims, ['Research']);
    for (let i = 0; i < 3; i++) {
      const reply = await workspace.dispatch({
        action: 'scope',
        project: 'Research',
      });
      assert.match(reply.notice ?? '', /rooted elsewhere/);
      await assert.rejects(
        workspace.dispatch({
          action: 'run',
          conversation: 'Research-chat',
          text: 'Do work',
          agent: 'a',
        }),
        /rooted elsewhere/,
      );
    }
    assert.deepEqual(fileClaims, ['Research']);
    assert.equal(workspace.state.connected, true);
    await assert.rejects(readFile(join(root, '.plowshare/project')), {
      code: 'ENOENT',
    });
    await assert.rejects(
      workspace.dispatch({ action: 'project-open', project: 'Research' }),
      /rooted elsewhere/,
    );
    assert.equal(fileClaims.length, 2);
    refuseFiles();
    await workspace.dispatch({ action: 'project-open', project: 'Research' });
    await workspace.dispatch({ action: 'scope', project: 'Research' });
    assert.equal(fileClaims.length, 3);
    assert.equal(workspace.state.files.status, 'ready');
  } finally {
    await workspace.shutdown();
  }
});
