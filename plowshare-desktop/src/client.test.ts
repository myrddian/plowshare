import type { NativeSessions, McpTool } from 'plowshare-mcp-integration';
import { decodeDesktopRequest } from './request-boundary.ts';
import { displayText } from 'plowshare-client-ts/binding/values';
import { present } from './fixture.test-support.ts';
import { isList } from 'plowshare-client-ts/binding/values';
import {
  projectWire,
  agentWire,
  conversationWire,
  entryWire,
  entryPageWire,
  contextWire,
  approvalWire,
} from './wire-fixtures.ts';
import { test } from 'node:test';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import assert from 'node:assert/strict';
import { DesktopClient, validatedBase } from './client.ts';
import type { Connector } from './client.ts';
import type { Outcome } from 'plowshare-client-ts/binding/envelope';
import { contextKey, replyState, stoppedJob } from './shared.ts';

function fixture(nativeSessions?: NativeSessions) {
  let push: (value: unknown) => void = () => {};
  let closed: () => void = () => {};
  const calls: { type: string; payload: Record<string, unknown> }[] = [];
  let onWorkflow: (
    payload: Record<string, unknown>,
  ) => Promise<Outcome> = async (payload) => ({
    code: 'ACCEPTED',
    payload: {
      id: 'orc_fixture',
      state: 'running',
      requestId: payload.requestId,
    },
  });
  let onRun: (
    payload: Record<string, unknown>,
  ) => Promise<Outcome> = async () => ({
    code: 'ACCEPTED',
    payload: { id: 'job-one' },
  });
  let onStatus: (
    payload: Record<string, unknown>,
  ) => Outcome | Promise<Outcome> = (payload) => ({
    code: 'OK',
    payload: { id: payload.job, state: 'RUNNING' },
  });
  type HistoryReply = () => Outcome;
  let approvalRows: unknown[] = [];
  const conversationApprovalRows = new Map<string, unknown[]>();
  let onApproval: () => Outcome | Promise<Outcome> = () => ({ code: 'OK' });
  let onInformation: (
    type: string,
    payload: Record<string, unknown>,
  ) => Outcome | Promise<Outcome> = () => ({ code: 'OK', payload: [] });
  let projects: unknown = [];
  let serverAdmin = false;
  let onAdmin: (
    type: string,
    payload: Record<string, unknown>,
  ) => Outcome | Promise<Outcome> = () => ({ code: 'OK', payload: [] });
  let onAgents: () => Outcome | Promise<Outcome> = () => ({
    code: 'OK',
    payload: [agentWire()],
  });
  let onApprovals:
    | ((payload: Record<string, unknown>) => Outcome | Promise<Outcome>)
    | undefined;
  let onContext: (
    payload: Record<string, unknown>,
  ) => Outcome | Promise<Outcome> = () => ({
    code: 'OK',
    payload: contextWire(),
  });
  let onTrajectory: (
    payload: Record<string, unknown>,
  ) => Outcome | Promise<Outcome> = () => ({
    code: 'OK',
    payload: entryPageWire(),
  });
  let onFollow: (
    payload: Record<string, unknown>,
  ) => Outcome | Promise<Outcome> = () => ({ code: 'OK' });
  let onUsage: (
    type: string,
    payload: Record<string, unknown>,
  ) => Promise<Outcome> | Outcome = () => ({ code: 'OK' });
  let onRelay: typeof onUsage = () => ({ code: 'OK' });
  let onProvider: typeof onUsage = () => ({ code: 'OK' });
  let onDeployment: typeof onUsage = () => ({ code: 'OK' });
  let onConversations: typeof onUsage = () => ({
    code: 'OK',
    payload: [conversationWire('first'), conversationWire('second')],
  });
  let onLifecycle: typeof onUsage = (_type, payload) => ({
    code: 'OK',
    payload: { id: payload.conversation, lifecycle: payload.lifecycle },
  });
  const connector: Connector = async (
    _base,
    _handle,
    _password,
    incoming,
    gone,
  ) => {
    push = incoming;
    closed = gone;
    return {
      session: 'desktop-session',
      serverAdmin,
      connection: {
        close() {},
        async ask(type, unknownPayload) {
          const payload = (unknownPayload ?? {}) as Record<string, unknown>;
          calls.push({ type, payload });
          if (type === 'admin.status')
            return { code: 'OK', payload: { handle: 'fixture', serverAdmin } };
          if (
            type.startsWith('admin.') ||
            [
              'project.access',
              'project.member.role',
              'project.member.add',
              'project.member.remove',
            ].includes(type)
          )
            return onAdmin(type, payload);
          if (
            type.startsWith('usage.') ||
            type === 'conversation.context.snapshot'
          )
            return onUsage(type, payload);
          if (
            [
              'application.deploy',
              'application.activate',
              'application.deployment.status',
              'application.deployment.receipt',
              'application.identity.status',
              'application.token.issue',
              'application.token.receipt',
            ].includes(type)
          )
            return onDeployment(type, payload);
          if (type.startsWith('relay.')) return onRelay(type, payload);
          if (type.startsWith('provider.') || type.startsWith('tool.scope.'))
            return onProvider(type, payload);
          if (type.startsWith('information.'))
            return onInformation(type, payload);
          if (type === 'orchestration.start') return onWorkflow(payload);
          if (type === 'agent.run') return onRun(payload);
          if (type === 'job.cancel')
            return {
              code: 'OK',
              payload: { id: payload.job, state: 'RUNNING' },
            };
          if (type === 'job.status') return onStatus(payload);
          if (type === 'conversation.context') return onContext(payload);
          if (type === 'agent.list') return onAgents();
          if (type === 'project.create')
            return {
              code: 'OK',
              payload: projectWire(String(payload.name), {
                workspace: String(payload.workspace),
              }),
            };
          if (type === 'project.list') return { code: 'OK', payload: projects };
          if (type === 'conversation.list')
            return onConversations(type, payload);
          if (type === 'conversation.lifecycle')
            return onLifecycle(type, payload);
          if (type === 'conversation.trajectory') return onTrajectory(payload);
          if (type === 'conversation.follow') return onFollow(payload);
          if (type === 'approval.list')
            return onApprovals
              ? onApprovals(payload)
              : {
                  code: 'OK',
                  payload: {
                    approvals:
                      typeof payload.conversation === 'string'
                        ? (conversationApprovalRows.get(payload.conversation) ??
                          [])
                        : approvalRows,
                  },
                };
          if (type === 'approval.answer') return onApproval();
          if (type === 'inbox.list')
            return { code: 'OK', payload: { items: [], unread: 0 } };
          if (type === 'orchestration.list')
            return { code: 'OK', payload: { orchestrations: [] } };
          return { code: 'OK' };
        },
      },
    };
  };
  const client = new DesktopClient(
    () => {},
    connector,
    nativeSessions === undefined ? {} : { nativeSessions },
  );
  return {
    client,
    calls,
    conversations: (value: typeof onConversations) => {
      onConversations = value;
    },
    lifecycle: (value: typeof onLifecycle) => {
      onLifecycle = value;
    },
    provider: (value: typeof onProvider) => {
      onProvider = value;
    },
    administration: (value: typeof onAdmin) => {
      onAdmin = value;
    },
    admin: (value: boolean) => {
      serverAdmin = value;
    },
    agents: (value: typeof onAgents) => {
      onAgents = value;
    },
    projects: (value: unknown) => {
      projects = value;
    },
    rawApprovals: (
      value: (payload: Record<string, unknown>) => Outcome | Promise<Outcome>,
    ) => {
      onApprovals = value;
    },
    push: (value: unknown) => push(value),
    closed: () => closed(),
    workflow: (value: typeof onWorkflow) => {
      onWorkflow = value;
    },
    run: (value: typeof onRun) => {
      onRun = value;
    },
    status: (value: typeof onStatus) => {
      onStatus = value;
    },
    usage: (value: typeof onUsage) => {
      onUsage = value;
    },
    deployment: (value: typeof onDeployment) => {
      onDeployment = value;
    },
    relay: (value: typeof onRelay) => {
      onRelay = value;
    },
    information: (value: typeof onInformation) => {
      onInformation = value;
    },
    history: (value: HistoryReply) => {
      onTrajectory = value;
    },
    context: (value: typeof onContext) => {
      onContext = value;
    },
    trajectory: (value: typeof onTrajectory) => {
      onTrajectory = value;
    },
    follow: (value: typeof onFollow) => {
      onFollow = value;
    },
    approvals: (value: typeof approvalRows) => {
      approvalRows = value;
    },
    conversationApprovals: (conversation: string, rows: unknown[]) => {
      conversationApprovalRows.set(conversation, rows);
    },
    answer: (value: typeof onApproval) => {
      onApproval = value;
    },
  };
}
const connect = (client: DesktopClient) =>
  client.dispatch({
    action: 'connect',
    base: 'http://127.0.0.1:8080',
    handle: 'tester',
    password: 'fixture-password',
  });

await test('confirmed archive retires retained active-chat metadata while preserving history and restore', async () => {
  const f = fixture();
  let archived = false;
  f.conversations((_type, payload) => ({
    code: 'OK',
    payload:
      (payload.lifecycle === 'archived') === archived
        ? [conversationWire('retained')]
        : [],
  }));
  f.lifecycle((_type, payload) => {
    archived = payload.lifecycle === 'archived';
    return {
      code: 'OK',
      payload: { id: payload.conversation, lifecycle: payload.lifecycle },
    };
  });
  try {
    await connect(f.client);
    f.client.retainConversation({ id: 'retained' });
    const history = { entries: [], more: false };
    f.client.state.history['retained'] = history;
    for (const lifecycle of ['archived', 'active'] as const) {
      await f.client.operator.prepare('conversation-lifecycle');
      f.client.operator.preview(f.client.state.operator!.identity, {
        id: 'retained',
        lifecycle,
      });
      await f.client.operator.apply(f.client.state.operator!.preview!.identity);
      await f.client.dispatch({ action: 'refresh' });
      assert.equal(
        f.client.state.conversations.some((row) => row.id === 'retained'),
        lifecycle === 'active',
      );
      assert.equal(f.client.state.history['retained'], history);
    }
  } finally {
    f.client.dispose();
  }
});

for (const acknowledgement of [
  { code: 'CONFLICT' },
  { code: 'OK', payload: { id: 'retained' } },
  { code: 'OK', payload: { id: 'foreign', lifecycle: 'archived' } },
  { code: 'OK', payload: { id: 'retained', lifecycle: 'active' } },
] satisfies readonly Outcome[]) {
  await test(`unconfirmed archive retains inspector metadata: ${JSON.stringify(acknowledgement)}`, async () => {
    const f = fixture();
    let listed = true;
    f.conversations((_type, payload) => ({
      code: 'OK',
      payload:
        listed && payload.lifecycle !== 'archived'
          ? [conversationWire('retained')]
          : [],
    }));
    f.lifecycle(() => acknowledgement);
    try {
      await connect(f.client);
      f.client.retainConversation({ id: 'retained' });
      await f.client.operator.prepare('conversation-lifecycle');
      f.client.operator.preview(f.client.state.operator!.identity, {
        id: 'retained',
        lifecycle: 'archived',
      });
      await assert.rejects(
        f.client.operator.apply(f.client.state.operator!.preview!.identity),
      );
      listed = false;
      await f.client.dispatch({ action: 'refresh' });
      assert.equal(
        f.client.state.conversations.some((row) => row.id === 'retained'),
        true,
      );
      assert.equal(
        f.calls.filter((row) => row.type === 'conversation.lifecycle').length,
        1,
      );
    } finally {
      f.client.dispose();
    }
  });
}

await test('an archive acknowledgement from a previous connection cannot retire new retained metadata or refresh it', async () => {
  const f = fixture();
  f.conversations((_type, payload) => ({
    code: 'OK',
    payload:
      payload.lifecycle === 'archived' ? [] : [conversationWire('retained')],
  }));
  let deliver: ((value: Outcome) => void) | undefined;
  let entered: (() => void) | undefined;
  const submitted = new Promise<void>((resolve) => {
    entered = resolve;
  });
  f.lifecycle(
    () =>
      new Promise<Outcome>((resolve) => {
        deliver = resolve;
        entered!();
      }),
  );
  try {
    await connect(f.client);
    f.client.retainConversation({ id: 'retained' });
    await f.client.operator.prepare('conversation-lifecycle');
    f.client.operator.preview(f.client.state.operator!.identity, {
      id: 'retained',
      lifecycle: 'archived',
    });
    const pending = assert.rejects(
      f.client.operator.apply(f.client.state.operator!.preview!.identity),
    );
    await submitted;
    await f.client.dispatch({ action: 'disconnect' });
    await connect(f.client);
    f.client.retainConversation({ id: 'retained' });
    const reads = f.calls.filter(
      (row) => row.type === 'conversation.list',
    ).length;
    deliver!({
      code: 'OK',
      payload: { id: 'retained', lifecycle: 'archived' },
    });
    await pending;
    assert.equal(
      f.calls.filter((row) => row.type === 'conversation.list').length,
      reads,
      'Old acknowledgement cannot trigger new-connection refresh',
    );
    f.conversations(() => ({ code: 'OK', payload: [] }));
    await f.client.dispatch({ action: 'refresh' });
    assert.equal(
      f.client.state.conversations.some((row) => row.id === 'retained'),
      true,
    );
  } finally {
    f.client.dispose();
  }
});

await test('Relay receipts register unlisted owned logs with project metadata before following', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  const branch = {
    id: '26e48bfd-0664-47ab-b3a1-88984c02e8fa',
    position: '1',
    subscriber: 'relay.notices.due',
    name: 'review',
    receiver: 'agent.run',
    state: 'ACCEPTED',
    fence: '1',
    updatedAt: '2026-10-05T01:00:00Z',
    failure: null,
    receiptNamespace: 'job',
    receiptId: 'job_fixture',
    conversation: 'unlisted-relay-handler',
    conversationProject: 'Writing',
    routingHash: 'a'.repeat(64),
    handlerHash: null,
  };
  const page = {
    scope: { project: 'Research', system: false },
    topic: {
      name: 'release.observed',
      kind: 'TEXT',
      retentionSeconds: '345600',
      maxRecords: null,
      through: '1',
      expiredThrough: '0',
    },
    after: '1',
    next: '1',
    gapThrough: null,
    events: [],
    subscribers: [],
    branches: [branch],
  };
  const request = {
    action: 'relay-read',
    type: 'relay.log',
    payload: { project: 'Research', topic: 'release.observed', after: '1' },
  } as const;
  f.relay(() => ({
    code: 'OK',
    payload: { ...page, branches: [{ ...branch, conversationProject: null }] },
  }));
  await assert.rejects(f.client.dispatch(request), /unreadable/);
  assert.equal(
    f.client.state.conversations.some((row) => row.id === branch.conversation),
    false,
  );
  f.relay(() => ({
    code: 'OK',
    payload: { ...page, scope: { project: 'foreign', system: false } },
  }));
  await assert.rejects(f.client.dispatch(request), /unreadable/);
  assert.equal(
    f.client.state.conversations.some((row) => row.id === branch.conversation),
    false,
  );
  f.relay(() => ({ code: 'OK', payload: page }));
  await f.client.dispatch(request);
  assert.equal(
    f.client.state.conversations.find((row) => row.id === branch.conversation)
      ?.project,
    'Writing',
  );
  await f.client.followView('relay-owned-log', branch.conversation);
  await f.client.dispatch({ action: 'scope', project: 'Writing' });
  await f.client.dispatch({
    action: 'history',
    conversation: branch.conversation,
  });
  await f.client.followView('relay-owned-log', branch.conversation);
  assert.ok(
    f.calls.some(
      (call) =>
        call.type === 'conversation.follow' &&
        isList(call.payload.conversations) &&
        call.payload.conversations.includes(branch.conversation),
    ),
  );
  await assert.rejects(
    f.client.followView('relay-invented-log', 'invented'),
    /available conversation/,
  );
});

await test('information hints expose only a validated invalidation counter and socket recovery fences', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  const generation = f.client.state.informationConnection;
  const queries = f.calls.length;
  f.push({
    kind: 'information.invalidated',
    revision: 'untrusted-extra-field',
  });
  assert.equal(f.client.state.informationRevision, 1);
  assert.equal(f.client.state.informationConnection, generation);
  assert.equal(
    f.calls.length,
    queries,
    'The renderer owns selected refresh; pushes do not query blindly',
  );
  f.push({
    kind: 'information.changed',
    sequence: 1,
    revision: '00000000-0000-0000-0000-000000000001',
    generation: 1,
  });
  assert.equal(f.client.state.informationRevision, 2);
  f.push({ kind: 'information.changed', sequence: -1 });
  assert.equal(
    f.client.state.informationRevision,
    3,
    'Malformed hints require safe authoritative reconciliation too',
  );
  assert.match(
    f.client.state.backgroundError ?? '',
    /Unreadable server notification/,
  );
  f.closed();
  assert.notEqual(f.client.state.informationConnection, generation);
  assert.equal(f.client.state.connected, false);
});

await test('an Inbox change discovers approvals from another client without an active local job', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  f.approvals([approvalWire({ id: 'background-approval' })]);
  f.push({ kind: 'inbox.changed', unread: 1 });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(f.client.state.approvals[0]?.id, 'background-approval');
  assert.equal(
    f.calls.some((call) => call.type === 'agent.run'),
    false,
  );
});

await test('opening a conversation discovers foreground approvals absent from the account listing', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  f.approvals([approvalWire({ id: 'background', conversation: 'second' })]);
  f.conversationApprovals('first', [approvalWire({ id: 'foreground' })]);
  await connect(f.client);
  assert.deepEqual(
    f.client.state.approvals.map((row) => row.id),
    ['background'],
  );
  await f.client.followView('chat', 'first');
  assert.deepEqual(f.client.state.approvals.map((row) => row.id).sort(), [
    'background',
    'foreground',
  ]);
  assert.ok(
    f.calls.some(
      (call) =>
        call.type === 'approval.list' && call.payload.conversation === 'first',
    ),
  );
  assert.equal(
    f.calls.some((call) =>
      ['approval.answer', 'agent.run'].includes(call.type),
    ),
    false,
  );
});

await test('approval refresh combines distinct open conversations and removes closed-view questions', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  f.conversationApprovals('first', [approvalWire({ id: 'chat' })]);
  f.conversationApprovals('second', [
    approvalWire({ id: 'trajectory', conversation: 'second' }),
  ]);
  await connect(f.client);
  await f.client.followView('chat', 'first');
  await f.client.followView('trajectory', 'second');
  assert.deepEqual(f.client.state.approvals.map((row) => row.id).sort(), [
    'chat',
    'trajectory',
  ]);
  await f.client.followView('trajectory');
  assert.deepEqual(
    f.client.state.approvals.map((row) => row.id),
    ['chat'],
  );
});

await test('a run ending AWAITING discovers a command without a reason before polling stops', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  await f.client.dispatch({
    action: 'run',
    conversation: 'first',
    agent: 'bot',
    text: 'Task',
  });
  f.conversationApprovals('first', [approvalWire({ reason: null })]);
  f.status(() => ({
    code: 'OK',
    payload: {
      id: 'job-one',
      state: 'DONE',
      outcome: {
        ending: 'AWAITING',
        answered: false,
        text: 'Approve running echo fixture in /fixture on the server side? [approval-one]',
      },
    },
  }));
  // No Inbox push: the durable job outcome must also discover the pending decision.
  f.push({ job: 'job-one', kind: 'ended', ending: 'AWAITING' });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(present(f.client.state.jobs[0]).ending, 'AWAITING');
  assert.equal(f.client.state.approvals[0]?.id, 'approval-one');
  assert.equal(f.client.state.approvals[0]?.reason, undefined);
  assert.equal(f.calls.filter((call) => call.type === 'agent.run').length, 1);
  assert.equal(
    f.calls.some((call) => call.type === 'approval.answer'),
    false,
  );
});

await test('an unreadable approval push reports its refresh failure and preserves pending requests', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  f.approvals([approvalWire()]);
  await connect(f.client);
  f.rawApprovals(() => ({ code: 'OK', payload: {} }));
  f.push({ kind: 'inbox.changed', unread: 1 });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(f.client.state.approvals[0]?.id, 'approval-one');
  assert.match(
    f.client.state.backgroundError ?? '',
    /unreadable approval.list/,
  );
});

await test('a log push discovers approvals for work started outside this window without an Inbox push', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  await f.client.followView('chat', 'first');
  f.conversationApprovals('first', [approvalWire()]);
  f.history(() => ({
    code: 'OK',
    payload: entryPageWire([entryWire(1, 'answer', 'Waiting for approval')], 1),
  }));
  f.push({ kind: 'conversation.appended', conversation: 'first', through: 1 });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(f.client.state.approvals[0]?.id, 'approval-one');
  assert.equal(
    f.calls.some((row) => ['agent.run', 'approval.answer'].includes(row.type)),
    false,
  );
});

await test('a failed account listing cannot swallow an independently readable conversation approval', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  await f.client.followView('chat', 'first');
  f.rawApprovals((payload) =>
    payload.mine
      ? {
          code: 'INTERNAL_ERROR',
          said: 'Account listing temporarily unavailable',
        }
      : { code: 'OK', payload: { approvals: [approvalWire()] } },
  );
  await f.client.dispatch({ action: 'approvals-refresh' });
  assert.equal(f.client.state.approvals[0]?.id, 'approval-one');
  assert.match(
    f.client.state.approvalError ?? '',
    /Could not refresh command approvals/,
  );
  f.rawApprovals(() => ({ code: 'OK', payload: { approvals: [] } }));
  await f.client.dispatch({ action: 'approvals-refresh' });
  assert.equal(f.client.state.approvalError, undefined);
  assert.deepEqual(f.client.state.approvals, []);
});

await test('a bare Yes cannot approve a newly discovered command', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  assert.equal(f.client.state.approvals.length, 0);
  f.conversationApprovals('first', [approvalWire()]);
  await assert.rejects(
    f.client.dispatch({
      action: 'run',
      conversation: 'first',
      agent: 'bot',
      text: 'Yes',
    }),
    /Use Approve or Deny/,
  );
  assert.equal(f.client.state.approvals[0]?.id, 'approval-one');
  assert.equal(
    f.calls.some((row) => ['agent.run', 'approval.answer'].includes(row.type)),
    false,
  );
});

await test('ambiguous approval replies retain the request and explain how to recover without calling the model', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  f.conversationApprovals('first', [approvalWire()]);
  await assert.rejects(
    f.client.dispatch({
      action: 'run',
      conversation: 'first',
      agent: 'bot',
      text: 'Just run the command',
    }),
    /Use Approve or Deny/,
  );
  f.conversationApprovals('first', [
    approvalWire(),
    approvalWire({ id: 'second-approval' }),
  ]);
  await assert.rejects(
    f.client.dispatch({
      action: 'run',
      conversation: 'first',
      agent: 'bot',
      text: 'Yes',
    }),
    /waiting for 2 command approvals/,
  );
  assert.equal(
    f.calls.some((row) => ['agent.run', 'approval.answer'].includes(row.type)),
    false,
  );
});

await test('an explicit approval answer reports a busy continuation instead of silently losing its note', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  f.approvals([approvalWire()]);
  await f.client.dispatch({ action: 'approvals-refresh' });
  f.answer(() => ({
    code: 'OK',
    payload: {
      id: 'approval-one',
      state: 'allowed',
      job: null,
      busy: true,
      note: 'The requesting turn is still closing.',
    },
  }));
  const reply = await f.client.dispatch({
    action: 'answer',
    id: 'approval-one',
    decision: 'once',
  });
  assert.match(reply.notice ?? '', /Approval recorded.*still closing/);
  assert.equal(
    f.calls.some((row) => row.type === 'agent.run'),
    false,
  );
});

await test('a failed approval read prevents chat from creating an unapproved competing turn', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  f.rawApprovals(() => ({
    code: 'INTERNAL_ERROR',
    said: 'Approval read unavailable',
  }));
  await assert.rejects(
    f.client.dispatch({
      action: 'run',
      conversation: 'first',
      agent: 'bot',
      text: 'Yes',
    }),
    /Approval read unavailable/,
  );
  assert.equal(
    f.calls.some((row) => ['agent.run', 'approval.answer'].includes(row.type)),
    false,
  );
});

await test('an acknowledged decision stays settled when the following discovery fails', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  f.approvals([approvalWire()]);
  await connect(f.client);
  f.answer(() => {
    f.rawApprovals(() => ({
      code: 'INTERNAL_ERROR',
      said: 'Listing unavailable',
    }));
    return {
      code: 'OK',
      payload: {
        id: 'approval-one',
        state: 'denied',
        job: null,
        busy: true,
        note: null,
      },
    };
  });
  await f.client.dispatch({
    action: 'answer',
    id: 'approval-one',
    decision: 'deny',
  });
  assert.deepEqual(f.client.state.approvals, []);
  assert.match(f.client.state.approvalError ?? '', /Listing unavailable/);
  assert.equal(
    f.calls.filter((row) => row.type === 'approval.answer').length,
    1,
  );
});

await test('an approval read from a previous connection cannot become a decision in the new view', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  let finish: (value: Outcome) => void = () => {
    throw new Error('Approval read did not start');
  };
  f.rawApprovals(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const sending = f.client.dispatch({
    action: 'run',
    conversation: 'first',
    agent: 'bot',
    text: 'Yes',
  });
  await new Promise((resolve) => setImmediate(resolve));
  await f.client.dispatch({ action: 'demo' });
  finish({
    code: 'OK',
    payload: { approvals: [approvalWire({ id: 'old-connection-approval' })] },
  });
  await assert.rejects(sending, /connection changed/i);
  assert.equal(
    f.client.state.approvals.some(
      (row) => row.id === 'old-connection-approval',
    ),
    false,
  );
  assert.equal(
    f.calls.some((row) => ['agent.run', 'approval.answer'].includes(row.type)),
    false,
  );
});

await test('a discovery response overtaken by an answer cannot resurrect the settled request', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  f.approvals([approvalWire()]);
  await connect(f.client);
  let finish: (value: Outcome) => void = () => {
    throw new Error('Listing did not start');
  };
  f.rawApprovals(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const reading = f.client.dispatch({ action: 'approvals-refresh' });
  await new Promise((resolve) => setImmediate(resolve));
  f.answer(() => {
    f.rawApprovals(() => ({ code: 'OK', payload: { approvals: [] } }));
    return {
      code: 'OK',
      payload: {
        id: 'approval-one',
        state: 'denied',
        job: null,
        busy: true,
        note: null,
      },
    };
  });
  const answering = f.client.dispatch({
    action: 'answer',
    id: 'approval-one',
    decision: 'deny',
  });
  await new Promise((resolve) => setImmediate(resolve));
  finish({ code: 'OK', payload: { approvals: [approvalWire()] } });
  await Promise.all([reading, answering]);
  assert.deepEqual(f.client.state.approvals, []);
});

await test('the same approval cannot be submitted twice while another window is answering it', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  f.approvals([approvalWire()]);
  await f.client.dispatch({ action: 'approvals-refresh' });
  let finish!: (value: Outcome) => void;
  f.answer(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const answer = f.client.dispatch({
    action: 'answer',
    id: 'approval-one',
    decision: 'once',
  });
  await assert.rejects(
    f.client.dispatch({
      action: 'answer',
      id: 'approval-one',
      decision: 'deny',
    }),
    /already being answered/,
  );
  assert.deepEqual(f.client.state.answeringApprovals, ['approval-one']);
  assert.equal(
    f.calls.filter((row) => row.type === 'approval.answer').length,
    1,
  );
  f.approvals([]);
  finish({
    code: 'OK',
    payload: {
      id: 'approval-one',
      state: 'allowed',
      job: null,
      busy: false,
      note: null,
    },
  });
  await answer;
  assert.deepEqual(f.client.state.answeringApprovals, []);
});

await test('manual Refresh reloads bots and their default, preserving the previous roster on refusal', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  f.agents(() => ({ code: 'OK', payload: [agentWire({ name: 'cathy' })] }));
  await f.client.dispatch({ action: 'refresh' });
  assert.equal(present(present(f.client.state.agents[''])[0]).name, 'cathy');
  assert.equal(present(present(f.client.state.agents[''])[0]).preferred, true);
  f.agents(() => ({
    code: 'BAD_REQUEST',
    said: 'Local bot discovery unavailable',
  }));
  await assert.rejects(
    f.client.dispatch({ action: 'refresh' }),
    /Local bot discovery unavailable/,
  );
  assert.equal(present(present(f.client.state.agents[''])[0]).name, 'cathy');
});

await test('an older bot refresh cannot displace a newer scope roster', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  let release!: (value: Outcome) => void;
  let asked!: () => void;
  const pending = new Promise<void>((resolve) => {
    asked = resolve;
  });
  f.agents(() => {
    asked();
    return new Promise((resolve) => {
      release = resolve;
    });
  });
  const refreshing = f.client.dispatch({ action: 'refresh' });
  await pending;
  f.agents(() => ({ code: 'OK', payload: [agentWire({ name: 'cathy' })] }));
  await f.client.dispatch({ action: 'scope' });
  release({
    code: 'OK',
    payload: [agentWire({ name: 'stale', preferred: false })],
  });
  await refreshing;
  assert.equal(present(present(f.client.state.agents[''])[0]).name, 'cathy');
});

await test('server configuration accepts origins and rejects credentials, paths and other protocols', () => {
  assert.equal(
    validatedBase('http://localhost:8080/'),
    'http://localhost:8080',
  );
  for (const input of [
    'file:///tmp',
    'https://user:password@example.com',
    'https://example.com/path',
    'https://example.com?token=a',
  ])
    assert.throws(() => validatedBase(input));
});

await test('a disconnected submission without a returned handle is not polled or replayed after reconnect', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  let release!: (answer: Outcome) => void;
  f.run(
    () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  );
  const submitted = f.client.dispatch({
    action: 'run',
    conversation: 'first',
    agent: 'bot',
    text: 'Task',
  });
  const failed = assert.rejects(submitted, /connection changed/);
  while (!release) await new Promise((resolve) => setTimeout(resolve, 0));
  f.closed();
  await connect(f.client);
  release({ code: 'ACCEPTED', payload: { id: 'late-handle' } });
  await failed;
  assert.equal(present(f.client.state.jobs[0]).status, 'unknown');
  assert.equal(f.calls.filter((call) => call.type === 'agent.run').length, 1);
  assert.equal(f.calls.filter((call) => call.type === 'job.status').length, 0);
});

await test('reconnect preserves a pending cancellation until a durable outcome arrives', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  await f.client.dispatch({
    action: 'run',
    conversation: 'first',
    agent: 'bot',
    text: 'Task',
  });
  await f.client.dispatch({ action: 'cancel', job: 'job-one' });
  f.closed();
  await connect(f.client);
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(present(f.client.state.jobs[0]).status, 'cancelling');
  assert.equal(f.calls.filter((call) => call.type === 'job.cancel').length, 1);
});

await test('a trajectory read refusal after submission does not turn an accepted job into a refused submission', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  f.history(() =>
    f.calls.some((call) => call.type === 'agent.run')
      ? { code: 'NOT_FOUND', said: 'Trajectory unavailable.' }
      : { code: 'OK', payload: entryPageWire() },
  );
  await assert.rejects(
    f.client.dispatch({
      action: 'run',
      conversation: 'first',
      agent: 'bot',
      text: 'Task',
    }),
    /Trajectory unavailable/,
  );
  assert.equal(present(f.client.state.jobs[0]).id, 'job-one');
  assert.equal(present(f.client.state.jobs[0]).status, 'running');
});

await test('a delayed status reply from the old connection cannot finish a reconnected job', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  let release!: (answer: Outcome) => void;
  f.status(
    () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  );
  await f.client.dispatch({
    action: 'run',
    conversation: 'first',
    agent: 'bot',
    text: 'Task',
  });
  f.closed();
  f.status(() => ({
    code: 'OK',
    payload: { id: 'job-one', state: 'RUNNING' },
  }));
  await connect(f.client);
  await new Promise((resolve) => setTimeout(resolve, 0));
  release({
    code: 'OK',
    payload: {
      id: 'job-one',
      state: 'DONE',
      outcome: { ending: 'ANSWERED', text: 'Obsolete result' },
    },
  });
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(present(f.client.state.jobs[0]).status, 'running');
  assert.notEqual(present(f.client.state.jobs[0]).text, 'Obsolete result');
});

const tick = () => new Promise((resolve) => setTimeout(resolve, 0));
const entry = entryWire;
const page = (
  entries: ReturnType<typeof entry>[],
  through: number,
  more = false,
  total = entries.length,
): Outcome => ({
  code: 'OK',
  payload: entryPageWire(entries, through, { more, total }),
});
const followSets = (f: ReturnType<typeof fixture>) =>
  f.calls
    .filter((call) => call.type === 'conversation.follow')
    .map((call) => call.payload.conversations);

await test('chat and native inspection owners follow their union, including shared views and complete removal', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  await f.client.dispatch({ action: 'select', conversation: 'first' });
  await f.client.followView('trajectory:second', 'second');
  await f.client.dispatch({ action: 'select', conversation: 'second' });
  await f.client.followView('trajectory:second');
  assert.deepEqual(followSets(f), [
    [],
    ['first'],
    ['first', 'second'],
    ['second'],
  ]);
  await f.client.dispatch({ action: 'select' });
  assert.deepEqual(followSets(f).at(-1), []);
  assert.equal(f.client.state.liveHistory?.status, 'ready');
  await assert.rejects(
    f.client.dispatch({ action: 'select', conversation: 'not-loaded' }),
    /available conversation/,
  );
});

await test('subscription changes serialize and an old acknowledgement cannot displace the latest views', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  let release: (outcome: Outcome) => void = () => {};
  f.follow(
    () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  );
  const first = f.client.dispatch({ action: 'select', conversation: 'first' });
  const second = f.client.dispatch({
    action: 'select',
    conversation: 'second',
  });
  const inspection = f.client.followView('trajectory:second', 'second');
  const close = f.client.followView('trajectory:second');
  assert.deepEqual(followSets(f).at(-1), ['first']);
  f.follow(() => ({ code: 'OK' }));
  release({ code: 'OK' });
  await Promise.all([first, second, inspection, close]);
  assert.deepEqual(followSets(f).slice(-2), [['first'], ['second']]);
});

await test('closing inspection removes its follow without waiting for a pending history read or cancelling a job', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  let release: (outcome: Outcome) => void = () => {};
  f.trajectory(
    () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  );
  const opening = f.client.followView('trajectory:first', 'first');
  await tick();
  await f.client.followView('trajectory:first');
  assert.deepEqual(followSets(f).at(-1), []);
  assert.equal(
    f.calls.some((call) => call.type === 'job.cancel'),
    false,
  );
  release(page([], 0));
  await opening;
});

await test('catch-up pages every entry kind by ordinal and retains loaded earlier scrollback', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  f.trajectory(() =>
    page(
      Array.from({ length: 21 }, (_, i) => entry(i + 80)),
      100,
      true,
    ),
  );
  await f.client.dispatch({ action: 'select', conversation: 'first' });
  f.trajectory(() =>
    page(
      Array.from({ length: 79 }, (_, i) => entry(i + 1)),
      100,
    ),
  );
  await f.client.dispatch({
    action: 'history',
    conversation: 'first',
    before: 80,
  });
  const later = Array.from({ length: 205 }, (_, i) =>
    entry(i + 101, i % 2 ? 'tool_result' : 'answer'),
  );
  f.trajectory((payload) => {
    assert.equal(payload.after, 100);
    assert.equal(payload.kinds, undefined);
    const offset = Number(payload.offset);
    return page(later.slice(offset, offset + 64), 305, false, later.length);
  });
  f.push({
    kind: 'conversation.appended',
    conversation: 'first',
    through: 305,
  });
  await tick();
  const history = f.client.state.history.first;
  assert.equal(present(history).entries.length, 305);
  assert.equal(present(history).through, 305);
  assert.equal(present(history).oldest, 1);
  assert.equal(present(history).more, false);
  assert.deepEqual(
    f.calls
      .filter(
        (call) =>
          call.type === 'conversation.trajectory' && call.payload.after === 100,
      )
      .map((call) => call.payload.offset),
    [0, 64, 128, 192],
  );
  const count = f.calls.length;
  f.push({
    kind: 'conversation.appended',
    conversation: 'first',
    through: 305,
  });
  f.push({
    kind: 'conversation.appended',
    conversation: 'second',
    through: 50,
  });
  await tick();
  assert.equal(f.calls.length, count);
});

await test('pushes during catch-up are coalesced and read after the pending snapshot', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  f.trajectory(() => page([entry(1), entry(2)], 2));
  await f.client.dispatch({ action: 'select', conversation: 'first' });
  let release: (outcome: Outcome) => void = () => {};
  f.trajectory(
    () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  );
  f.push({ kind: 'conversation.appended', conversation: 'first', through: 3 });
  await tick();
  f.push({ kind: 'conversation.appended', conversation: 'first', through: 5 });
  f.push({ kind: 'conversation.appended', conversation: 'first', through: 5 });
  f.trajectory((payload) => {
    assert.equal(payload.after, 3);
    return page([entry(4), entry(5)], 5);
  });
  release(page([entry(3)], 3));
  await tick();
  assert.equal(present(f.client.state.history.first).through, 5);
  assert.deepEqual(
    present(f.client.state.history.first).entries.map((row) => row.ordinal),
    [1, 2, 3, 4, 5],
  );
});

await test('growth during paged reads cannot advance the cursor past a newer unobserved turn', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  f.trajectory(() => page([entry(1)], 1));
  await f.client.dispatch({ action: 'select', conversation: 'first' });
  f.trajectory((payload) => {
    if (payload.after === 4) return page([entry(5)], 5);
    assert.equal(payload.after, 1);
    return payload.offset === 0
      ? page([entry(2), entry(3)], 4, false, 3)
      : page([entry(4), entry(5)], 5, false, 4);
  });
  f.push({ kind: 'conversation.appended', conversation: 'first', through: 4 });
  await tick();
  assert.equal(present(f.client.state.history.first).through, 5);
  assert.deepEqual(
    present(f.client.state.history.first).entries.map((row) => row.ordinal),
    [1, 2, 3, 4, 5],
  );
  assert.equal(
    f.calls.some(
      (call) =>
        call.type === 'conversation.trajectory' && call.payload.after === 4,
    ),
    true,
  );
});

await test('history refusal preserves data and cursor and a manual refresh retries without agent mutations', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  f.trajectory(() => page([entry(1), entry(2)], 2));
  await f.client.dispatch({ action: 'select', conversation: 'first' });
  f.trajectory(() => ({
    code: 'INTERNAL_ERROR',
    said: 'History temporarily unavailable',
  }));
  f.push({ kind: 'conversation.appended', conversation: 'first', through: 3 });
  await tick();
  assert.equal(present(f.client.state.history.first).through, 2);
  assert.equal(present(f.client.state.history.first).entries.length, 2);
  assert.match(
    present(f.client.state.history.first).error!,
    /temporarily unavailable/,
  );
  f.trajectory(() => page([entry(3)], 3));
  await f.client.dispatch({ action: 'refresh' });
  assert.equal(present(f.client.state.history.first).through, 3);
  assert.equal(present(f.client.state.history.first).error, undefined);
  assert.equal(
    f.calls.some((call) =>
      ['agent.run', 'job.cancel', 'approval.answer'].includes(call.type),
    ),
    false,
  );
});

await test('an old server cannot silently downgrade multi-view following or disable foreground operations', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  f.follow(() => ({ code: 'BAD_REQUEST', said: 'conversation is required' }));
  await connect(f.client);
  await f.client.dispatch({ action: 'select', conversation: 'first' });
  assert.equal(f.client.state.connected, true);
  assert.equal(f.client.state.liveHistory?.status, 'unavailable');
  assert.match(f.client.state.liveHistory?.detail ?? '', /Update the server/);
  assert.equal(
    f.calls
      .filter((call) => call.type === 'conversation.follow')
      .every(
        (call) =>
          isList(call.payload.conversations) &&
          !('conversation' in call.payload),
      ),
    true,
  );
  await f.client.dispatch({
    action: 'run',
    conversation: 'first',
    agent: 'bot',
    text: 'Task',
  });
  assert.equal(present(f.client.state.jobs[0]).status, 'running');
});

await test('reconnect restores the complete view set and catches up missed history without resubmitting work', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  f.trajectory(() => page([entry(1), entry(2)], 2));
  await f.client.dispatch({ action: 'select', conversation: 'first' });
  await f.client.followView('trajectory:second', 'second');
  f.closed();
  f.push({ kind: 'conversation.appended', conversation: 'first', through: 4 });
  assert.equal(present(f.client.state.history.first).through, 2);
  f.trajectory((payload) => {
    assert.equal(payload.after, 2);
    return page([entry(3), entry(4)], 4);
  });
  await connect(f.client);
  await tick();
  assert.deepEqual(followSets(f).at(-1), ['first', 'second']);
  assert.equal(present(f.client.state.history.first).through, 4);
  assert.equal(present(f.client.state.history.second).through, 4);
  assert.equal(
    f.calls.some((call) => call.type === 'agent.run'),
    false,
  );
  f.trajectory(() => page([], 0));
  await f.client.dispatch({
    action: 'connect',
    base: 'http://127.0.0.1:8080',
    handle: 'another',
    password: 'fixture-password',
  });
  assert.deepEqual(followSets(f).at(-1), []);
  assert.equal(f.client.state.history.first, undefined);
});

await test('late subscription acknowledgements and history reads cannot cross a mode change', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  let release: (outcome: Outcome) => void = () => {};
  f.trajectory(
    () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  );
  const selecting = f.client.dispatch({
    action: 'select',
    conversation: 'first',
  });
  await tick();
  await f.client.dispatch({ action: 'demo' });
  release(page([entry(500)], 500));
  await selecting;
  assert.equal(f.client.state.mode, 'demo');
  assert.equal(f.client.state.history.first, undefined);
  f.trajectory(() => page([], 0));
  await connect(f.client);
  f.follow(
    () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  );
  const subscribing = f.client.dispatch({
    action: 'select',
    conversation: 'second',
  });
  await f.client.dispatch({ action: 'demo' });
  release({ code: 'OK' });
  await subscribing;
  assert.equal(f.client.state.mode, 'demo');
  assert.equal(f.client.state.liveHistory, undefined);
});

await test('early events and simultaneous runs are attributed by job handle, independent of view selection', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  const releases = new Map<string, () => void>();
  f.run(
    (payload) =>
      new Promise((resolve) => {
        const id = `job-${displayText(payload.conversation)}`;
        f.push({ job: id, kind: 'started', agent: 'bot' });
        f.push({
          job: id,
          part: 'THINKING',
          text: `thinking-${displayText(payload.conversation)}`,
        });
        f.push({
          job: id,
          part: 'ANSWER',
          text: `answer-${displayText(payload.conversation)}`,
        });
        releases.set(String(payload.conversation), () =>
          resolve({ code: 'ACCEPTED', payload: { id } }),
        );
      }),
  );
  const first = f.client.dispatch({
    action: 'run',
    conversation: 'first',
    agent: 'bot',
    text: 'First task',
  });
  const second = f.client.dispatch({
    action: 'run',
    conversation: 'second',
    agent: 'bot',
    text: 'Second task',
  });
  await tick();
  releases.get('second')!();
  await second;
  releases.get('first')!();
  await first;
  assert.equal(
    f.client.state.jobs.find((row) => row.conversation === 'first')?.text,
    'answer-first',
  );
  assert.equal(
    f.client.state.jobs.find((row) => row.conversation === 'second')?.text,
    'answer-second',
  );
  assert.equal(
    f.client.state.jobs.find((row) => row.conversation === 'first')?.thinking,
    'thinking-first',
  );
  assert.equal(
    f.client.state.jobs.find((row) => row.conversation === 'second')?.thinking,
    'thinking-second',
  );
  f.push({ job: 'job-first', part: 'THINKING', text: ' continued' });
  assert.equal(
    f.client.state.jobs.find((row) => row.conversation === 'first')?.thinking,
    'thinking-first continued',
  );
  assert.equal(
    f.client.state.jobs.find((row) => row.conversation === 'first')?.text,
    'answer-first',
  );
  assert.equal(
    f.calls.find((call) => call.type === 'agent.run')?.payload.session,
    'desktop-session',
  );
});

await test('cancellation remains pending until job.status confirms an outcome', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  await f.client.dispatch({
    action: 'run',
    conversation: 'first',
    agent: 'bot',
    text: 'Task',
  });
  await f.client.dispatch({ action: 'cancel', job: 'job-one' });
  assert.equal(present(f.client.state.jobs[0]).status, 'cancelling');
  f.status(() => ({
    code: 'OK',
    payload: {
      id: 'job-one',
      state: 'DONE',
      outcome: { ending: 'CANCELLED', answered: false, text: 'Stopped' },
    },
  }));
  f.push({ job: 'job-one', kind: 'ended', ending: 'CANCELLED' });
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(present(f.client.state.jobs[0]).status, 'finished');
  assert.equal(present(f.client.state.jobs[0]).ending, 'CANCELLED');
  assert.equal(stoppedJob(present(f.client.state.jobs[0])), true);
});

await test('successful wire outcomes are answers, retain genuine notes and clear stale polling errors', async (t) => {
  for (const detail of [undefined, 'The response reached the output limit.']) {
    const f = fixture();
    t.after(() => f.client.dispose());
    await connect(f.client);
    await f.client.dispatch({
      action: 'run',
      conversation: 'first',
      agent: 'bot',
      text: 'Research',
    });
    present(f.client.state.jobs[0]).detail =
      'A previous status request failed.';
    const answer =
      '`orc_123` (`deep_research`) is working; its result will be delivered when it finishes.';
    f.status(() => ({
      code: 'OK',
      payload: {
        id: 'job-one',
        state: 'DONE',
        outcome: {
          ending: 'ANSWERED',
          answered: true,
          text: answer,
          ...(detail === undefined ? {} : { detail }),
        },
      },
    }));
    f.push({ job: 'job-one', kind: 'ended', ending: 'ANSWERED' });
    await new Promise((resolve) => setTimeout(resolve, 0));
    const job = f.client.state.jobs[0];
    assert.equal(present(job).status, 'finished');
    assert.equal(present(job).ending, 'ANSWERED');
    assert.equal(present(job).answered, true);
    assert.equal(present(job).text, answer);
    assert.equal(present(job).detail, detail);
    assert.equal(
      stoppedJob(present(job)),
      false,
      'An answered run must not be classified as stopped.',
    );
  }
});

await test('terminal pace uses the TUI reader and preserves estimates, missing measurements and measured zeros', async (t) => {
  const cases = [
    {
      wire: {
        toolCalls: 0,
        reasoningTokens: 20,
        reasoningEstimated: true,
        completionTokens: 72,
        firstTokenMillis: 431,
        tokensPerSecond: 34.4,
      },
      expected: {
        toolCalls: 0,
        reasoningTokens: 20,
        reasoningEstimated: true,
        completionTokens: 72,
        firstTokenMillis: 431,
        tokensPerSecond: 34.4,
      },
    },
    {
      wire: {
        toolCalls: 2,
        reasoningTokens: null,
        completionTokens: 0,
        firstTokenMillis: null,
        tokensPerSecond: null,
      },
      expected: { toolCalls: 2, completionTokens: 0 },
    },
    { wire: null, expected: undefined },
  ];
  for (const { wire, expected } of cases) {
    const f = fixture();
    t.after(() => f.client.dispose());
    await connect(f.client);
    await f.client.dispatch({
      action: 'run',
      conversation: 'first',
      agent: 'bot',
      text: 'Task',
    });
    f.status(() => ({
      code: 'OK',
      payload: {
        id: 'job-one',
        state: 'DONE',
        outcome: {
          ending: 'ANSWERED',
          answered: true,
          text: 'Answer',
          pace: wire,
        },
      },
    }));
    f.push({ job: 'job-one', kind: 'ended', ending: 'ANSWERED' });
    await new Promise((resolve) => setTimeout(resolve, 0));
    assert.deepEqual(present(f.client.state.jobs[0]).pace, expected);
    assert.equal(
      f.client.state.jobs.some(
        (job) => job.conversation === 'second' && job.pace,
      ),
      false,
    );
  }
});

await test('refusal preserves the server reason and does not leave a running job', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  f.run(async () => ({
    code: 'CONFLICT',
    said: 'This conversation is already running.',
  }));
  await assert.rejects(
    f.client.dispatch({
      action: 'run',
      conversation: 'first',
      agent: 'bot',
      text: 'Task',
    }),
    /already running/,
  );
  assert.equal(present(f.client.state.jobs[0]).status, 'interrupted');
});

await test('disconnect leaves an uncertain outcome and never resubmits a run', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  await f.client.dispatch({
    action: 'run',
    conversation: 'first',
    agent: 'bot',
    text: 'Task',
  });
  f.closed();
  assert.equal(f.client.state.connected, false);
  assert.equal(present(f.client.state.jobs[0]).status, 'unknown');
  await connect(f.client);
  assert.equal(f.calls.filter((call) => call.type === 'agent.run').length, 1);
  assert.equal(f.client.state.mode, 'live');
});

await test('returning to demo drops live data and ignores old socket callbacks', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  await f.client.dispatch({ action: 'demo' });
  f.closed();
  f.push({ kind: 'ended', job: 'job-one' });
  assert.equal(f.client.state.mode, 'demo');
  assert.equal(f.client.state.connection, 'Offline demo');
  assert.equal(
    f.client.state.conversations.some((row) => row.id === 'first'),
    false,
  );
});

await test('unknown desktop operations and broad approval decisions are refused', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  assert.throws(
    () => decodeDesktopRequest({ action: 'ask', type: 'project.forget' }),
    /contract/,
  );
  assert.throws(
    () =>
      decodeDesktopRequest({
        action: 'answer',
        id: 'approval',
        decision: 'project',
      }),
    /contract/,
  );
});

await test('an approval continuation tracks the returned job and its early events without submitting an agent turn', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  f.approvals([approvalWire()]);
  await connect(f.client);
  f.answer(() => {
    f.push({
      job: 'continued-job',
      part: 'ANSWER',
      text: 'Continuation result',
    });
    return {
      code: 'OK',
      payload: {
        id: 'approval-one',
        state: 'allowed',
        job: 'continued-job',
        busy: false,
        note: null,
      },
    };
  });
  await f.client.dispatch({
    action: 'answer',
    id: 'approval-one',
    decision: 'once',
  });
  assert.equal(present(f.client.state.jobs[0]).id, 'continued-job');
  assert.equal(present(f.client.state.jobs[0]).conversation, 'first');
  assert.equal(present(f.client.state.jobs[0]).source, 'approval');
  assert.equal(present(f.client.state.jobs[0]).text, 'Continuation result');
  assert.equal(f.calls.filter((call) => call.type === 'agent.run').length, 0);
});

await test('malformed lists and history preserve existing state instead of displaying false emptiness', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  f.projects({});
  await connect(f.client);
  assert.match(f.client.state.projectListError!, /unreadable project.list/);
  f.projects([]);
  await connect(f.client);
  f.approvals([
    approvalWire({
      id: 'a',
      command: ['true'],
      cwd: '/tmp',
      defaultPrefix: [],
    }),
  ]);
  await f.client.dispatch({ action: 'refresh' });
  f.rawApprovals(() => ({ code: 'OK', payload: {} }));
  await f.client.dispatch({ action: 'refresh' });
  assert.match(f.client.state.approvalError ?? '', /unreadable approval.list/);
  assert.equal(present(f.client.state.approvals[0]).id, 'a');
  f.history(() => ({
    code: 'OK',
    payload: entryPageWire([entryWire(1, 'utterance', 'kept')], 1),
  }));
  await f.client.dispatch({ action: 'history', conversation: 'first' });
  f.history(() => ({ code: 'OK', payload: {} }));
  await assert.rejects(
    f.client.dispatch({ action: 'history', conversation: 'first' }),
    /unreadable conversation.trajectory/,
  );
  assert.equal(
    present(present(f.client.state.history['first']).entries[0]).text,
    'kept',
  );
});

await test('a terminal reply missing its identity or answered flag cannot finish a desktop job', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  await f.client.dispatch({
    action: 'run',
    conversation: 'first',
    agent: 'bot',
    text: 'Task',
  });
  for (const payload of [
    {
      state: 'DONE',
      outcome: { ending: 'ANSWERED', answered: true, text: 'invalid' },
    },
    {
      id: 'job-one',
      state: 'DONE',
      outcome: { ending: 'ANSWERED', text: 'invalid' },
    },
  ]) {
    f.status(() => ({ code: 'OK', payload }));
    f.push({ job: 'job-one', kind: 'ended' });
    await new Promise((resolve) => setTimeout(resolve, 0));
    assert.notEqual(present(f.client.state.jobs[0]).status, 'finished');
    assert.notEqual(present(f.client.state.jobs[0]).text, 'invalid');
  }
  f.status(() => ({
    code: 'OK',
    payload: {
      id: 'job-one',
      state: 'DONE',
      outcome: { ending: 'ANSWERED', answered: true, text: 'valid' },
    },
  }));
  f.push({ job: 'job-one', kind: 'ended' });
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(present(f.client.state.jobs[0]).status, 'finished');
  assert.equal(present(f.client.state.jobs[0]).text, 'valid');
});

await test('context measurements use model counts and remain scoped to conversation and agent', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  f.context((payload) => ({
    code: 'OK',
    payload: contextWire(
      payload.agent === 'other'
        ? null
        : payload.conversation === 'first'
          ? 15240
          : 400,
      { sentAtTurn: 48 },
      { model: String(payload.agent), contextLength: 131072 },
    ),
  }));
  await f.client.dispatch({
    action: 'context',
    conversation: 'first',
    agent: 'bot',
  });
  await f.client.dispatch({
    action: 'context',
    conversation: 'second',
    agent: 'bot',
  });
  await f.client.dispatch({
    action: 'context',
    conversation: 'first',
    agent: 'other',
  });
  assert.equal(
    present(f.client.state.contexts[contextKey('first', 'bot')]).sent,
    15240,
  );
  assert.equal(
    present(f.client.state.contexts[contextKey('first', 'bot')]).sentAtTurn,
    48,
  );
  assert.equal(
    present(f.client.state.contexts[contextKey('second', 'bot')]).sent,
    400,
  );
  assert.equal(
    present(f.client.state.contexts[contextKey('first', 'other')]).sent,
    undefined,
  );
  assert.equal(
    present(f.client.state.contexts[contextKey('first', 'other')]).limit,
    131072,
  );
  assert.equal(
    present(f.client.state.contexts[contextKey('first', 'other')]).model,
    'other',
  );
  assert.deepEqual(
    f.calls.find((call) => call.type === 'conversation.context')?.payload,
    { conversation: 'first', agent: 'bot' },
  );
  f.context(() => ({ code: 'NOT_FOUND', said: 'Context unavailable' }));
  await f.client.dispatch({
    action: 'context',
    conversation: 'first',
    agent: 'bot',
  });
  assert.equal(
    present(f.client.state.contexts[contextKey('first', 'bot')]).status,
    'unavailable',
  );
  assert.equal(
    present(f.client.state.contexts[contextKey('first', 'bot')]).sent,
    15240,
  );
});

await test('context inspection sends boolean flags and exposes the system block, tools and failures', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  const snapshot = {
    conversation: 'first',
    agent: 'bot',
    projection: 'next',
    captured_at: '2026-10-19T00:00:00Z',
    model: 'fixture-model',
    sampling: {},
    messages: [
      {
        role: 'system',
        parts: [{ type: 'text', text: 'The constructed system prompt' }],
        tool_calls: [],
        tool_call_id: null,
      },
    ],
    tools: [
      {
        name: 'run',
        description: 'Run a command',
        parameters: { type: 'object' },
      },
    ],
    count: null,
  };
  f.usage(() => ({ code: 'OK', payload: snapshot }));
  for (const measure of [false, true]) {
    await f.client.dispatch({
      action: 'context-snapshot',
      conversation: 'first',
      agent: 'bot',
      measure,
    });
    assert.deepEqual(f.calls.at(-1), {
      type: 'conversation.context.snapshot',
      payload: { conversation: 'first', agent: 'bot', measure },
    });
    assert.deepEqual(
      f.client.state.contextSnapshots?.[contextKey('first', 'bot')],
      { value: snapshot },
    );
  }
  f.usage(() => ({ code: 'NOT_FOUND', said: 'Agent definition unavailable' }));
  await f.client.dispatch({
    action: 'context-snapshot',
    conversation: 'first',
    agent: 'bot',
    measure: false,
  });
  const failed = f.client.state.contextSnapshots?.[contextKey('first', 'bot')];
  assert.deepEqual(failed?.value, snapshot);
  assert.match(failed?.error ?? '', /Agent definition unavailable/);
  assert.equal(failed?.loading, undefined);
  assert.equal(
    f.calls.some((call) => call.type === 'agent.run'),
    false,
  );
});

await test('late context responses cannot replace a newer measurement or cross a mode change', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  let release: (value: Outcome) => void = () => {};
  f.context(
    () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  );
  const older = f.client.dispatch({
    action: 'context',
    conversation: 'first',
    agent: 'bot',
  });
  f.context(() => ({
    code: 'OK',
    payload: contextWire(200, {}, { contextLength: 1000 }),
  }));
  await f.client.dispatch({
    action: 'context',
    conversation: 'first',
    agent: 'bot',
  });
  release({
    code: 'OK',
    payload: contextWire(100, {}, { contextLength: 1000 }),
  });
  await older;
  assert.equal(
    present(f.client.state.contexts[contextKey('first', 'bot')]).sent,
    200,
  );
  f.context(
    () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  );
  const late = f.client.dispatch({
    action: 'context',
    conversation: 'first',
    agent: 'bot',
  });
  await f.client.dispatch({ action: 'demo' });
  release({
    code: 'OK',
    payload: contextWire(999, {}, { contextLength: 1000 }),
  });
  await late;
  assert.equal(f.client.state.contexts[contextKey('first', 'bot')], undefined);
});

await test('information uses the existing authenticated socket, pins its scope and preserves refusals', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  const id = '00000000-0000-4000-8000-000000000009';
  f.information(() => ({
    code: 'BAD_REQUEST',
    said: 'An input became unavailable.',
  }));
  await assert.rejects(
    f.client.dispatch({
      action: 'information',
      operation: 'record.report',
      scope: { kind: 'project', project: 'research' },
      payload: {
        requestId: id,
        name: 'Checked report',
        text: 'Retained report text',
      },
    }),
    /An input became unavailable/,
  );
  const sent = f.calls.find(
    (call) => call.type === 'information.record.report',
  )!;
  assert.deepEqual(sent.payload.scope, {
    kind: 'project',
    project: 'research',
  });
  assert.equal(sent.payload.requestId, id);
  assert.equal(
    f.calls.filter((call) => call.type === 'information.record.report').length,
    1,
  );
});

await test('a document question is tracked and polled without attributing it to the current conversation', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  f.information(() => ({
    code: 'ACCEPTED',
    payload: { job: 'document-answer', revision: 'source' },
  }));
  f.status(() => ({
    code: 'OK',
    payload: {
      id: 'document-answer',
      state: 'FINISHED',
      outcome: { ending: 'ANSWERED', answered: true, text: 'Grounded answer' },
    },
  }));
  await f.client.dispatch({
    action: 'information',
    operation: 'ask',
    scope: { kind: 'personal' },
    payload: { revision: 'source', question: 'What does it establish?' },
  });
  await new Promise((resolve) => setTimeout(resolve, 0));
  const job = f.client.state.jobs.find((job) => job.id === 'document-answer')!;
  assert.equal(job.source, 'information');
  assert.equal(job.revision, 'source');
  assert.equal(job.conversation, '');
  assert.equal(job.text, 'Grounded answer');
  assert.equal(job.status, 'finished');
  assert.equal(
    f.calls.some(
      (call) =>
        call.type === 'conversation.trajectory' &&
        call.payload.conversation === '',
    ),
    false,
  );
});
await test('full wire validation preserves the desktop roster and context when required metadata is missing', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  f.agents(() => ({
    code: 'OK',
    payload: [{ name: 'partial', bot: true, served: true }],
  }));
  await assert.rejects(
    f.client.dispatch({ action: 'refresh' }),
    /unreadable agent.list/,
  );
  assert.equal(present(present(f.client.state.agents[''])[0]).name, 'bot');
  f.context(() => ({
    code: 'OK',
    payload: contextWire(100, {}, { contextLength: 1000 }),
  }));
  await f.client.dispatch({
    action: 'context',
    conversation: 'first',
    agent: 'bot',
  });
  f.context(() => ({
    code: 'OK',
    payload: { sent: 200, prefix: { contextLength: 1000 } },
  }));
  await f.client.dispatch({
    action: 'context',
    conversation: 'first',
    agent: 'bot',
  });
  assert.equal(
    present(f.client.state.contexts[contextKey('first', 'bot')]).sent,
    100,
  );
  assert.equal(
    present(f.client.state.contexts[contextKey('first', 'bot')]).status,
    'unavailable',
  );
});
await test('a damaged approval acknowledgement leaves its decision uncertain and is never replayed', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  f.approvals([approvalWire()]);
  await f.client.dispatch({ action: 'refresh' });
  f.answer(() => ({
    code: 'OK',
    payload: {
      id: 'approval-one',
      state: 'allowed',
      job: 'continued-job',
      busy: false,
    },
  }));
  await assert.rejects(
    f.client.dispatch({
      action: 'answer',
      id: 'approval-one',
      decision: 'once',
    }),
    /unreadable approval.answer/,
  );
  assert.equal(
    f.calls.filter((call) => call.type === 'approval.answer').length,
    1,
  );
  assert.equal(
    f.calls.some((call) => call.type === 'agent.run'),
    false,
  );
  assert.equal(f.client.state.jobs.at(-1)?.status, 'unknown');
});

await test('authoring admits a served role with a builder grant while ordinary chat keeps its bot selection', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  f.agents(() => ({
    code: 'OK',
    payload: [
      agentWire({
        name: 'interlocutor',
        bot: false,
        orchestrations: ['design_orchestration'],
      }),
    ],
  }));
  await connect(f.client);
  await assert.rejects(
    f.client.dispatch({
      action: 'run',
      conversation: 'first',
      agent: 'interlocutor',
      text: 'Ordinary chat',
    }),
    /conversational/,
  );
  await f.client.authoringTurn(
    'first',
    'interlocutor',
    'Start the granted design_orchestration',
  );
  assert.equal(f.calls.filter((row) => row.type === 'agent.run').length, 1);
});

await test('authoring role refuses missing grants and retains lost replies without resubmitting', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  await assert.rejects(
    f.client.authoringTurn('first', 'bot', 'Design'),
    /granted/,
  );
  assert.equal(f.calls.filter((row) => row.type === 'agent.run').length, 0);
  f.agents(() => ({
    code: 'OK',
    payload: [
      agentWire({
        name: 'interlocutor',
        bot: false,
        orchestrations: ['design_orchestration'],
      }),
    ],
  }));
  await f.client.dispatch({ action: 'refresh' });
  f.run(() => {
    throw new Error('Lost after accepting');
  });
  await assert.rejects(
    f.client.authoringTurn('first', 'interlocutor', 'Design'),
    /Lost/,
  );
  assert.equal(f.client.state.jobs.at(-1)?.status, 'unknown');
  await assert.rejects(
    f.client.authoringTurn('first', 'interlocutor', 'Design'),
    /uncertain/,
  );
  assert.equal(f.calls.filter((row) => row.type === 'agent.run').length, 1);
});

await test('a document answer keeps early progress and uncertain submission is never replayed', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  f.information(async () => {
    f.push({
      job: 'document-answer',
      part: 'ANSWER',
      text: 'Early grounded text',
    });
    return {
      code: 'ACCEPTED',
      payload: { job: 'document-answer', revision: 'source' },
    };
  });
  await f.client.dispatch({
    action: 'information',
    operation: 'ask',
    scope: { kind: 'personal' },
    payload: { revision: 'source', question: 'Question' },
  });
  const job = f.client.state.jobs.find((row) => row.id === 'document-answer')!;
  assert.equal(job.text, 'Early grounded text');
  assert.equal(job.conversation, '');
  f.information(async () => {
    throw new Error('socket lost after submission');
  });
  await assert.rejects(
    f.client.dispatch({
      action: 'information',
      operation: 'ask',
      scope: { kind: 'personal' },
      payload: { revision: 'source', question: 'Second question' },
    }),
    /socket lost/,
  );
  assert.equal(f.client.state.jobs.at(-1)?.status, 'unknown');
  const count = f.calls.filter(
    (call) => call.type === 'information.ask',
  ).length;
  f.closed();
  await connect(f.client);
  assert.equal(
    f.calls.filter((call) => call.type === 'information.ask').length,
    count,
  );
});

await test('usage subscriptions reconcile across disconnect and close without issuing agent runs', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  let revision = 0;
  const totals = {
    calls: '1',
    attempts: '1',
    active_calls: '0',
    incomplete_attempts: '0',
    unknown_cost_attempts: '0',
    input_tokens: '10',
    output_tokens: '2',
    input_tokens_known: '1',
    output_tokens_known: '1',
    costs: { USD: '0.00001' },
    usage_complete: true,
    cost_complete: true,
    complete: true,
  };
  const report = (conversation: string) => ({
    filters: { type: 'usage.conversation', filter: { conversation } },
    totals,
    groups: [],
    cursor: null,
    health: {
      watermark: '1',
      as_of: '2026-10-02T00:00:00Z',
      capture_enabled: true,
      historical_usage: 'not_imported',
    },
  });
  f.usage((type, payload) =>
    type === 'usage.unsubscribe'
      ? { code: 'OK', payload: {} }
      : {
          code: 'OK',
          payload: {
            subscription: 's' + ++revision,
            revision: 0,
            filters: report(String(payload.conversation)).filters,
            report: report(String(payload.conversation)),
          },
        },
  );
  await f.client.dispatch({
    action: 'usage-open',
    type: 'usage.conversation',
    filter: { conversation: 'first' },
  });
  assert.equal(f.client.state.usage?.report?.totals.input_tokens, '10');
  f.push({
    id: null,
    type: 'usage.updated',
    protocol_version: 'plowshare-v1',
    payload: {
      subscription: 's1',
      revision: 2,
      report: { ...report('first'), totals: { ...totals, input_tokens: '20' } },
    },
  });
  f.push({
    id: null,
    type: 'usage.updated',
    protocol_version: 'plowshare-v1',
    payload: { subscription: 's1', revision: 1, report: report('first') },
  });
  assert.equal(f.client.state.usage?.report?.totals.input_tokens, '20');
  f.closed();
  assert.equal(f.client.state.usage?.stale, true);
  await connect(f.client);
  assert.equal(revision, 2);
  assert.equal(f.client.state.usage?.report?.totals.input_tokens, '10');
  await f.client.dispatch({
    action: 'usage-open',
    type: 'usage.conversation',
    filter: { conversation: 'second' },
  });
  assert.equal(
    f.client.state.usage?.report?.filters.filter.conversation,
    'second',
  );
  await f.client.dispatch({ action: 'usage-close' });
  assert.equal(f.client.state.usage?.report, undefined);
  assert.equal(f.calls.filter((c) => c.type === 'usage.unsubscribe').length, 2);
  assert.equal(f.calls.filter((c) => c.type === 'agent.run').length, 0);
});

await test('restored job status cannot attach another conversation outcome to the saved receipt', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  f.status(() => ({
    code: 'OK',
    payload: {
      id: 'saved-job',
      conversation: 'second',
      state: 'DONE',
      outcome: {
        ending: 'ANSWERED',
        answered: true,
        text: 'Other conversation result',
      },
    },
  }));
  await f.client.restoreJobs([
    {
      id: 'saved-job',
      handle: 'saved-job',
      conversation: 'first',
      agent: 'alice',
    },
  ]);
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.equal(present(f.client.state.jobs[0]).status, 'unknown');
  assert.equal(present(f.client.state.jobs[0]).text, '');
  assert.equal(
    f.calls.some((call) => call.type === 'agent.run'),
    false,
  );
});

await test('direct workflow starts check the agent grant, preserve the request key and never dispatch a caller turn', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  f.agents(() => ({
    code: 'OK',
    payload: [
      agentWire({
        commands: [
          {
            command: '/orchestration:research',
            aliases: [],
            kind: 'orchestration',
            name: 'research',
            description: 'Research',
            argumentHint: 'Work',
            executor: 'researcher',
            mode: null,
            tier: 'GLOBAL',
            hash: 'fixture',
          },
        ],
      }),
    ],
  }));
  await connect(f.client);
  const input = {
    action: 'workflow-start' as const,
    conversation: 'first',
    agent: 'bot',
    definition: 'research',
    text: 'Original\nquestion',
    requestId: '00000000-0000-0000-0000-000000000001',
  };
  await assert.rejects(
    f.client.dispatch({ ...input, definition: 'ungranted' }),
    /granted/,
  );
  assert.equal(
    f.calls.some((call) => call.type === 'orchestration.start'),
    false,
  );
  await assert.rejects(f.client.dispatch(input), /explicit project/);
  f.client.state.conversations = f.client.state.conversations.map((row) => ({
    ...row,
    project: 'repo',
  }));
  f.client.state.agents['repo'] = f.client.state.agents[''] ?? [];
  let finish!: (answer: Outcome) => void;
  f.workflow(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const launching = f.client.dispatch(input);
  await assert.rejects(f.client.dispatch(input), /already in progress/);
  finish({
    code: 'ACCEPTED',
    payload: {
      id: 'orc_fixture',
      state: 'running',
      requestId: input.requestId,
    },
  });
  assert.match((await launching).notice!, /Follow.*Runs/);
  assert.deepEqual(
    f.calls.find((call) => call.type === 'orchestration.start')!.payload,
    {
      agent: 'bot',
      definition: 'research',
      project: 'repo',
      request: 'Original\nquestion',
      requestId: input.requestId,
    },
  );
  assert.equal(
    f.calls.some((call) => call.type === 'agent.run'),
    false,
  );
});

await test('server project creation requires the persisted role and uses one WS mutation', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  await assert.rejects(
    f.client.dispatch({
      action: 'server-project-create',
      name: 'example',
      workspace: '/srv/example',
    }),
    /administrator/,
  );
  assert.equal(
    f.calls.filter((row) => row.type === 'project.create').length,
    0,
  );
  f.admin(true);
  await connect(f.client);
  f.projects([projectWire('example', { workspace: '/srv/example' })]);
  await f.client.dispatch({
    action: 'server-project-create',
    name: 'example',
    workspace: '/srv/example',
  });
  assert.deepEqual(
    f.calls.filter((row) => row.type === 'project.create'),
    [
      {
        type: 'project.create',
        payload: {
          name: 'example',
          workspace: '/srv/example',
          type: 'MANAGED',
        },
      },
    ],
  );
  assert.equal(
    f.client.state.projects.find((row) => row.name === 'example')?.workspace,
    '/srv/example',
  );
});

await test('server account operations recheck the role and keep credentials out of state', async () => {
  const f = fixture();
  f.admin(true);
  await f.client.dispatch({
    action: 'connect',
    base: 'http://127.0.0.1:8091',
    handle: 'fixture',
    password: 'fixture',
  });
  const credential = {
    account: {
      handle: 'worker',
      enabled: true,
      serverAdmin: false,
      mustChangePassword: true,
      createdAt: '2026-10-04T00:00:00Z',
    },
    temporaryPassword: 'one-time-test-password',
  };
  f.administration(() => ({ code: 'OK', payload: credential }));
  const reply = await f.client.dispatch({
    action: 'server-admin',
    operation: 'admin.account.create',
    payload: { handle: 'worker' },
  });
  assert.deepEqual(reply.administration, credential);
  assert.ok(
    !JSON.stringify(reply.state).includes(credential.temporaryPassword),
  );
  assert.equal(
    f.calls.filter((row) => row.type === 'admin.account.create').length,
    1,
  );
  f.admin(false);
  await assert.rejects(
    f.client.dispatch({
      action: 'server-admin',
      operation: 'admin.account.reset',
      payload: { handle: 'worker' },
    }),
    /administrator/,
  );
  assert.equal(
    f.calls.filter((row) => row.type === 'admin.account.reset').length,
    0,
  );
  f.client.dispose();
});

await test('regular project managers use project access commands without server administrator access', async () => {
  const f = fixture();
  await f.client.dispatch({
    action: 'connect',
    base: 'http://fixture.invalid',
    handle: 'fixture',
    password: 'fixture-password',
  });
  const access = {
    project: 'integration',
    role: 'MANAGER',
    permissions: ['read', 'work', 'manage'],
    members: [{ handle: 'worker', role: 'VIEWER' }],
    history: [],
  };
  f.administration(() => ({ code: 'OK', payload: access }));
  const reply = await f.client.dispatch({
    action: 'project-access',
    operation: 'project.member.role',
    project: 'integration',
    handle: 'worker',
    role: 'VIEWER',
  });
  assert.deepEqual(reply.administration, access);
  assert.equal(replyState(reply).serverAdmin, false);
  assert.deepEqual(
    f.calls.filter((row) => row.type === 'project.member.role').at(-1)?.payload,
    { project: 'integration', handle: 'worker', role: 'VIEWER' },
  );
  f.administration(() => ({
    code: 'BAD_REQUEST',
    said: 'MANAGER access required',
  }));
  await assert.rejects(
    f.client.dispatch({
      action: 'project-access',
      operation: 'project.member.remove',
      project: 'integration',
      handle: 'worker',
    }),
    /MANAGER access/,
  );
});

await test('offline demo does not preselect a deployment or connect without an explicit origin', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  assert.equal(f.client.state.base, '');
  await assert.rejects(
    f.client.dispatch({
      action: 'connect',
      base: '',
      handle: 'tester',
      password: 'fixture-password',
    }),
  );
  assert.equal(f.calls.length, 0);
  assert.equal(f.client.state.mode, 'demo');
});

await test('Application deployment requires administration, correlates receipts and preserves uncertain request identity', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  const requestId = '11111111-1111-1111-1111-111111111111',
    revision = '22222222-2222-2222-2222-222222222222';
  const payload = {
    project: 'app',
    requestId,
    expectedRevision: null,
    destination: { store: 'applications', path: 'app' },
    writableAreas: [],
    files: [{ path: 'plowshare.json', text: '{"version":1,"name":"app"}' }],
  };
  await connect(f.client);
  await assert.rejects(
    f.client.dispatch({
      action: 'application-deployment',
      operation: 'application.deploy',
      payload,
    }),
    /administrator/,
  );
  assert.equal(
    f.calls.filter((call) => call.type === 'application.deploy').length,
    0,
  );
  f.admin(true);
  await connect(f.client);
  const receipt = {
    project: 'app',
    requestId,
    release: { revision, digest: 'a'.repeat(64), fileCount: 1 },
  };
  f.deployment(() => ({
    code: 'OK',
    payload: { ...receipt, project: 'foreign' },
  }));
  await assert.rejects(
    f.client.dispatch({
      action: 'application-deployment',
      operation: 'application.deploy',
      payload,
    }),
    /unreadable/,
  );
  f.deployment(async () => {
    throw new Error('lost reply');
  });
  await assert.rejects(
    f.client.dispatch({
      action: 'application-deployment',
      operation: 'application.deploy',
      payload,
    }),
    /lost reply/,
  );
  assert.equal(
    f.calls.filter((call) => call.type === 'application.deploy').length,
    2,
  );
  f.deployment((type, p) => {
    assert.equal(type, 'application.deployment.receipt');
    assert.deepEqual(p, { project: 'app', requestId });
    return { code: 'OK', payload: receipt };
  });
  const recovered = await f.client.dispatch({
    action: 'application-deployment',
    operation: 'application.deployment.receipt',
    payload: { project: 'app', requestId },
  });
  assert.deepEqual(recovered.deployment, receipt);
});

await test('Application issuance keeps its one-time secret outside desktop state and preserves explicit refusal', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  await assert.rejects(
    f.client.dispatch({
      action: 'application-identity',
      operation: 'application.identity.status',
      payload: { project: 'app' },
    }),
    /administrator/,
  );
  f.admin(true);
  await connect(f.client);
  const requestId = '11111111-1111-1111-1111-111111111111',
    tokenId = '22222222-2222-2222-2222-222222222222';
  const credential = 'pss_' + '1'.repeat(48);
  f.deployment(() => ({
    code: 'OK',
    payload: {
      credential,
      receipt: {
        project: 'app',
        requestId,
        tokenId,
        principal: '@service/' + tokenId,
        expiresAt: '2026-11-01T00:00:00Z',
      },
    },
  }));
  const reply = await f.client.dispatch({
    action: 'application-identity',
    operation: 'application.token.issue',
    payload: { project: 'app', requestId, expiresInDays: 30, rotate: false },
  });
  assert.equal(JSON.stringify(reply.state).includes(credential), false);
  assert.equal(
    JSON.stringify(reply.applicationIdentity).includes(credential),
    true,
  );
  f.deployment(() => ({
    code: 'BAD_REQUEST',
    said: 'Already issued; select rotation',
  }));
  const refused = await f.client.dispatch({
    action: 'application-identity',
    operation: 'application.token.issue',
    payload: { project: 'app', requestId, expiresInDays: 30, rotate: false },
  });
  assert.deepEqual(refused.applicationIdentityRefusal, {
    code: 'BAD_REQUEST',
    said: 'Already issued; select rotation',
  });
});

await test('provider owner IPC works without administrator status, redacts metadata and retains lost writes across dialogs', async () => {
  const root = await mkdtemp(join(tmpdir(), 'desktop-provider-'));
  const previous = process.env['PLOWSHARE_CONFIG_DIR'];
  process.env['PLOWSHARE_CONFIG_DIR'] = root;
  const id = '11111111-1111-1111-1111-111111111111';
  const next = '22222222-2222-2222-2222-222222222222';
  const f = fixture();
  try {
    await f.client.dispatch({
      action: 'connect',
      base: 'https://server.example',
      handle: 'fixture',
      password: 'fixture',
    });
    const status = {
      project: 'fixture',
      connection: id,
      subject: 'fixture',
      generation: id,
      connectionRevision: id,
      state: 'READY',
      expiresAt: null,
      keyId: 'fixture',
    };
    const connection = {
      project: 'fixture',
      connection: id,
      revision: id,
      enabled: true,
      deleted: false,
      definition: {
        name: 'Fixture',
        endpoint: 'https://provider.example/mcp',
        authentication: 'API_KEY',
        apiKeyHeader: 'X-API-Key',
        oauth: null,
      },
    };
    const receipt = {
      requestId: id,
      operation: 'provider.credential.put',
      connection,
      credential: status,
    };
    f.provider(() => ({
      code: 'OK',
      payload: { ...status, accessToken: 'private-access' },
    }));
    const reply = await f.client.dispatch({
      action: 'provider-management',
      operation: 'provider.credential.status',
      payload: { project: 'fixture', connection: id, subject: null },
    });
    assert.deepEqual(reply.providerManagement, status);
    assert.equal(JSON.stringify(reply).includes('private-access'), false);
    f.provider(() => ({
      code: 'NOT_FOUND',
      said: 'no handler is registered for type "provider.connection.list"',
    }));
    const older = await f.client.dispatch({
      action: 'provider-management',
      operation: 'provider.connection.list',
      payload: { project: 'fixture' },
    });
    assert.equal(older.providerManagementRefusal?.unsupported, true);
    f.provider(() => {
      throw new Error('lost reply');
    });
    const put = {
      project: 'fixture',
      connection: id,
      subject: null,
      requestId: id,
      expectedGeneration: id,
      secret: 'private-material',
    };
    await assert.rejects(
      f.client.dispatch({
        action: 'provider-management',
        operation: 'provider.credential.put',
        payload: put,
      }),
      /lost reply/,
    );
    const pending = await f.client.dispatch({
      action: 'provider-management-pending',
    });
    assert.equal(pending.providerManagementPending?.length, 1);
    assert.equal(JSON.stringify(pending).includes('private-material'), false);
    await assert.rejects(
      f.client.dispatch({
        action: 'provider-management',
        operation: 'provider.credential.put',
        payload: { ...put, requestId: next },
      }),
      /Outcome unknown/,
    );
    assert.equal(
      f.calls.filter((call) => call.type === 'provider.credential.put').length,
      1,
    );
    const intent = pending.providerManagementPending?.[0];
    assert.ok(intent);
    f.provider(() => ({ code: 'OK', payload: receipt }));
    const recovered = await f.client.dispatch({
      action: 'provider-management-recover',
      intent,
    });
    assert.deepEqual(recovered.providerManagement, receipt);
    assert.deepEqual(
      (await f.client.dispatch({ action: 'provider-management-pending' }))
        .providerManagementPending,
      [],
    );
    assert.throws(() =>
      decodeDesktopRequest({
        action: 'provider-management',
        operation: 'tool.scope.worker.credential',
        payload: {},
      }),
    );
    assert.throws(() =>
      decodeDesktopRequest({
        action: 'provider-management-recover',
        intent: { ...intent, secret: 'injected' },
      }),
    );
    assert.equal(
      JSON.stringify(f.client.state).includes('private-material'),
      false,
    );
  } finally {
    f.client.dispose();
    if (previous === undefined) delete process.env['PLOWSHARE_CONFIG_DIR'];
    else process.env['PLOWSHARE_CONFIG_DIR'] = previous;
    await rm(root, { recursive: true, force: true });
  }
});

await test('desktop native commands invoke directly and socket loss closes instance-owned sessions', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'plowshare-desktop-native-'));
  let opened = 0,
    calls = 0,
    closed = 0;
  const tool: McpTool = {
    name: 'echo',
    description: 'Echo fixture',
    input: {
      type: 'object',
      properties: { value: { type: 'integer' } },
      required: ['value'],
      additionalProperties: false,
    },
    output: null,
    metadata: {
      name: 'echo',
      inputSchema: {
        type: 'object',
        properties: { value: { type: 'integer' } },
        required: ['value'],
        additionalProperties: false,
      },
    },
  };
  const f = fixture({
    async open() {
      opened++;
      return {
        async page() {
          return { tools: [tool], cursor: null };
        },
        async call() {
          calls++;
          return {
            content: ['observed'],
            structured: undefined,
            isError: false,
          };
        },
        async close() {
          closed++;
        },
      };
    },
  });
  const file = join(directory, 'profile.json');
  try {
    await connect(f.client);
    const before = f.calls.length;
    await writeFile(
      file,
      JSON.stringify({
        owner: { origin: f.client.state.base, account: f.client.state.handle },
        instance: 'desktop-test',
        profile: 'echo',
        project: 'fixture',
        prefix: 'local_',
        protocol: '2026-07-28',
        journalDirectory: join(directory, 'journals'),
        timeoutMs: 1000,
        timeoutSeconds: 1,
        shutdownGraceMs: 30,
        transport: {
          kind: 'stdio',
          executable: process.execPath,
          args: [],
          directory,
          environment: {},
        },
      }),
      { mode: 0o600 },
    );
    const dispatch = (command: unknown) =>
      f.client.dispatch(
        decodeDesktopRequest({
          action: 'native-mcp',
          command: JSON.stringify(command),
        }),
      );
    await assert.rejects(dispatch({ action: 'start', file, consent: false }));
    assert.equal(opened, 0);
    const reply = await dispatch({ action: 'start', file, consent: true });
    const status = reply.nativeMcp;
    assert.ok(status !== undefined && 'tools' in status);
    const declaration = status.tools[0];
    assert.ok(declaration);
    const invocation = {
      invocationId: '11111111-1111-1111-1111-111111111111',
      tool: declaration.name,
      arguments: { value: 1 },
      deadline: new Date(Date.now() + 1000).toISOString(),
    };
    const command = {
      action: 'call',
      instance: 'desktop-test',
      profile: 'echo',
      invocation,
    };
    await dispatch(command);
    await dispatch(command);
    assert.equal(calls, 1);
    assert.equal(f.calls.length, before);
    f.closed();
    await f.client.shutdown();
    assert.equal(closed, 1);
    await assert.rejects(dispatch(command));
  } finally {
    await f.client.shutdown();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('chat preflight cannot resurrect a decision answered during its read', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  f.approvals([approvalWire()]);
  await connect(f.client);
  let finish: (value: Outcome) => void = () => {
    throw new Error('Read did not start');
  };
  f.rawApprovals(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const sending = f.client.dispatch({
    action: 'run',
    conversation: 'first',
    agent: 'bot',
    text: 'Continue investigating',
  });
  const rejected = assert.rejects(sending, /approvals changed/);
  await new Promise((resolve) => setImmediate(resolve));
  f.rawApprovals(() => ({ code: 'OK', payload: { approvals: [] } }));
  f.answer(() => ({
    code: 'OK',
    payload: {
      id: 'approval-one',
      state: 'denied',
      job: null,
      busy: true,
      note: null,
    },
  }));
  await f.client.dispatch({
    action: 'answer',
    id: 'approval-one',
    decision: 'deny',
  });
  assert.deepEqual(f.client.state.approvals, []);
  finish({ code: 'OK', payload: { approvals: [approvalWire()] } });
  await rejected;
  assert.deepEqual(
    f.client.state.approvals,
    [],
    'An overtaken preflight must not restore the settled request',
  );
});

await test('chat preflight cannot overwrite a newer snapshot after another client answers', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  f.approvals([approvalWire()]);
  await connect(f.client);
  let finish: (value: Outcome) => void = () => {
    throw new Error('Read did not start');
  };
  f.rawApprovals(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const sending = f.client.dispatch({
    action: 'run',
    conversation: 'first',
    agent: 'bot',
    text: 'Yes',
  });
  const rejected = assert.rejects(sending, /approvals changed/);
  await new Promise((resolve) => setImmediate(resolve));
  f.rawApprovals(() => ({ code: 'OK', payload: { approvals: [] } }));
  await f.client.dispatch({ action: 'approvals-refresh' });
  finish({ code: 'OK', payload: { approvals: [approvalWire()] } });
  await rejected;
  assert.deepEqual(f.client.state.approvals, []);
  assert.equal(
    f.calls.some((row) => ['agent.run', 'approval.answer'].includes(row.type)),
    false,
  );
});

await test('Mailbox opening and refresh do not wait for unrelated approval scopes', async (t) => {
  const f = fixture();
  t.after(() => f.client.dispose());
  await connect(f.client);
  const releases: ((value: Outcome) => void)[] = [];
  f.rawApprovals(() => new Promise((resolve) => releases.push(resolve)));
  let opened = false;
  const opening = f.client
    .dispatch({ action: 'activity-view', view: 'inbox' })
    .then(() => {
      opened = true;
    });
  try {
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(
      opened,
      true,
      'Mailbox must load while approval reads remain pending',
    );
    assert.equal(f.client.state.activity.inbox.loaded, true);
    assert.ok(
      releases.length > 0,
      'Approval discovery still runs independently',
    );
    await f.client.dispatch({ action: 'activity-refresh' });
  } finally {
    // A refresh coalesced while opening may request one follow-up approval pass.
    f.rawApprovals(() => ({ code: 'OK', payload: { approvals: [] } }));
    for (const resolve of releases)
      resolve({ code: 'OK', payload: { approvals: [] } });
    await opening;
  }
});
