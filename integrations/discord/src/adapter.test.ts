import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { ConnectionFault } from 'plowshare-client-node';
import { DiscordAdapter } from './adapter.ts';
import { decodeState } from './state.ts';
import {
  configuration,
  input,
  FakeGateway,
  FakeDiscord,
  MemoryState,
  OTHER,
  BOT,
  PRINCIPAL,
  CHANNEL,
  GUILD,
} from './adapter.test-support.ts';

function fixture(config = configuration(), repository = new MemoryState()) {
  const gateway = new FakeGateway();
  const discord = new FakeDiscord();
  return {
    config,
    repository,
    gateway,
    discord,
    adapter: new DiscordAdapter(config, repository, gateway, discord),
  };
}
await test('only allowed native human messages in mapped guild channels reach the SDK', async () => {
  const f = fixture();
  for (const message of [
    input({ bot: true }),
    input({ webhook: true }),
    input({ userId: '900000000000000009' }),
    input({ guildId: '900000000000000009' }),
    input({ channelId: '900000000000000009' }),
  ])
    await f.adapter.accept(message);
  assert.equal(f.gateway.submissions.length, 0);
  const mentioned = fixture({
    ...f.config,
    routes: f.config.routes.map((r) => ({ ...r, requireMention: true })),
  });
  await mentioned.adapter.accept(input());
  assert.equal(mentioned.gateway.submissions.length, 0);
  await mentioned.adapter.accept(input({ content: `<@${BOT}> investigate` }));
  assert.equal(mentioned.gateway.submissions[0]?.body, 'investigate');
});
await test('journal admission precedes SDK dispatch and duplicate Gateway events never resubmit', async () => {
  const f = fixture();
  await f.adapter.accept(input());
  await f.adapter.accept(input());
  assert.equal(f.gateway.submissions.length, 1);
  assert.equal(f.gateway.submissions[0]?.project, 'research');
  assert.equal(f.gateway.submissions[0]?.agent, 'researcher');
  assert.ok(f.repository.saves >= 2);
  const failed = fixture();
  failed.repository.fail = true;
  await assert.rejects(failed.adapter.accept(input()));
  assert.equal(failed.gateway.submissions.length, 0);
});
await test('users and Discord threads get independent contexts; same-user follow-ups retain context', async () => {
  const f = fixture();
  await f.adapter.accept(input());
  const first = f.gateway.submissions[0];
  assert.equal(first?.context, null);
  const context = f.repository.state.records[0]?.contextId;
  await f.adapter.accept(input({ messageId: '200000000000000002' }));
  assert.equal(f.gateway.submissions[1]?.context, context);
  await f.adapter.accept(
    input({ messageId: '200000000000000003', userId: OTHER }),
  );
  assert.equal(f.gateway.submissions[2]?.context, null);
  assert.notEqual(f.gateway.submissions[2]?.client, first?.client);
  await f.adapter.accept(
    input({
      messageId: '200000000000000004',
      channelId: '200000000000000005',
      parentId: CHANNEL,
    }),
  );
  assert.equal(f.gateway.submissions[3]?.context, null);
});
await test('lost admission remains uncertain across restart and blocks new effects in that context', async () => {
  const f = fixture();
  f.gateway.failure = new ConnectionFault(
    'RESPONSE_LOST',
    'fixture connection lost',
  );
  await f.adapter.accept(input());
  const raw: unknown = JSON.parse(f.repository.persisted);
  const restarted = fixture(
    f.config,
    new MemoryState(decodeState(raw, f.config, PRINCIPAL, BOT)),
  );
  await restarted.adapter.accept(input({ messageId: '200000000000000002' }));
  await restarted.adapter.poll();
  assert.equal(restarted.gateway.submissions.length, 0);
  assert.equal(restarted.gateway.statuses.length, 0);
  assert.equal(restarted.repository.state.records[0]?.admission, 'UNKNOWN');
});
await test('known unsubmitted work is not retried, but a distinct user request may proceed', async () => {
  const f = fixture();
  f.gateway.failure = new ConnectionFault(
    'NOT_SUBMITTED',
    'fixture closed socket',
  );
  await f.adapter.accept(input());
  assert.equal(f.repository.state.records[0]?.admission, 'NOT_SUBMITTED');
  f.gateway.failure = null;
  await f.adapter.accept(input({ messageId: '200000000000000002' }));
  assert.equal(f.gateway.submissions.length, 2);
});
await test('polling delivers progress/final results once and restart uses retained task IDs', async () => {
  const f = fixture();
  await f.adapter.accept(input());
  f.gateway.result = {
    ...f.gateway.result,
    state: 'COMPLETED',
    ending: 'ANSWERED',
    replies: [
      {
        id: 'reply_1',
        body: 'The evidence is ready.',
        finalReply: true,
        generated: false,
        ending: 'ANSWERED',
        postedAt: '2026-01-01T00:00:00Z',
      },
    ],
  };
  await f.adapter.poll();
  await f.adapter.poll();
  assert.equal(
    f.discord.sends.filter((s) => s.text === 'The evidence is ready.').length,
    1,
  );
  const raw: unknown = JSON.parse(f.repository.persisted);
  const restarted = fixture(
    f.config,
    new MemoryState(decodeState(raw, f.config, PRINCIPAL, BOT)),
  );
  await restarted.adapter.poll();
  assert.equal(restarted.discord.sends.length, 0);
  assert.equal(restarted.gateway.submissions.length, 0);
});
await test('an uncertain Discord send is retained and never sent again', async () => {
  const f = fixture();
  await f.adapter.accept(input());
  f.discord.failure = new Error('Discord accepted but response was lost');
  await f.adapter.poll();
  await f.adapter.poll();
  assert.equal(f.discord.sends.length, 1);
  assert.equal(f.repository.state.records[0]?.outputs[0]?.state, 'UNKNOWN');
});
await test('revoked users and changed routes cannot receive retained results', async () => {
  const f = fixture();
  await f.adapter.accept(input());
  for (const config of [
    { ...f.config, allowedUserIds: [OTHER] },
    {
      ...f.config,
      routes: f.config.routes.map((r) => ({ ...r, bot: 'privileged' })),
    },
    {
      ...f.config,
      routes: f.config.routes.map((r) => ({ ...r, project: 'private' })),
    },
  ]) {
    const guarded = new DiscordAdapter(
      config,
      f.repository,
      f.gateway,
      f.discord,
    );
    await guarded.poll();
  }
  assert.equal(f.discord.sends.length, 0);
  assert.equal(f.gateway.statuses.length, 0);
});
await test('status/cancel refuse foreign user tasks without even looking them up', async () => {
  const f = fixture();
  await f.adapter.accept(input());
  const taskId = f.repository.state.records[0]?.taskId;
  assert.ok(taskId);
  await f.adapter.accept(
    input({
      messageId: '200000000000000002',
      userId: OTHER,
      content: `!plowshare cancel ${taskId}`,
    }),
  );
  assert.equal(f.gateway.cancellations.length, 0);
  await f.adapter.accept(
    input({ messageId: '200000000000000003', content: '!plowshare status' }),
  );
  assert.equal(f.gateway.statuses.length, 1);
  await f.adapter.accept(
    input({ messageId: '200000000000000004', content: '!plowshare cancel' }),
  );
  assert.equal(f.gateway.cancellations.length, 1);
});
await test('receipt capacity refuses new intake before any SDK submission', async () => {
  const f = fixture({ ...configuration(), maxRecords: 1 });
  await f.adapter.accept(input());
  await assert.rejects(
    f.adapter.accept(input({ messageId: '200000000000000002' })),
    /capacity/,
  );
  assert.equal(f.gateway.submissions.length, 1);
});
function notificationFixture() {
  const config = {
    ...configuration(),
    notifications: [
      {
        channelId: CHANNEL,
        project: 'research',
        topic: 'research.completed',
        group: 'discord.results',
        start: 'LATEST' as const,
      },
    ],
  };
  const f = fixture(config);
  f.gateway.batch = {
    project: 'research',
    topic: 'research.completed',
    group: 'discord.results',
    consumerId: randomUUID(),
    status: 'DATA',
    batchId: randomUUID(),
    fence: '1',
    through: '1',
    expiresAt: new Date(Date.now() + 30000).toISOString(),
    expiredThrough: null,
    events: [
      {
        position: '1',
        eventId: randomUUID(),
        publisher: 'fixture',
        occurredAt: '2026-01-01T00:00:00Z',
        publishedAt: '2026-01-01T00:00:00Z',
        correlationId: null,
        causationId: null,
        payload: {
          kind: 'TEXT',
          text: 'Scheduled report is ready.',
          schedule: null,
          emits: null,
          fireAt: null,
        },
      },
    ],
  };
  return f;
}
await test('Relay redelivery reuses Discord receipts; acknowledgments follow confirmed delivery', async () => {
  const f = notificationFixture();
  await f.adapter.poll();
  await f.adapter.poll();
  assert.equal(f.discord.sends.length, 1);
  assert.equal(f.gateway.acknowledgements.length, 2);
  assert.equal(f.gateway.submissions.length, 0);
});
await test('Relay gaps and uncertain delivery/acknowledgment stop the route without replay', async () => {
  const gap = notificationFixture();
  assert.ok(gap.gateway.batch);
  gap.gateway.batch = { ...gap.gateway.batch, status: 'GAP', events: [] };
  await gap.adapter.poll();
  assert.equal(gap.gateway.acknowledgements.length, 0);
  assert.equal(gap.repository.state.heldNotifications[0]?.reason, 'GAP');
  const lost = notificationFixture();
  lost.discord.failure = new Error('Uncertain delivery');
  await lost.adapter.poll();
  await lost.adapter.poll();
  assert.equal(lost.discord.sends.length, 1);
  assert.equal(lost.gateway.acknowledgements.length, 0);
  const ack = notificationFixture();
  ack.gateway.ackFailure = new ConnectionFault(
    'RESPONSE_LOST',
    'Lost acknowledgment',
  );
  await assert.rejects(ack.adapter.poll());
  await ack.adapter.poll();
  assert.equal(ack.gateway.acknowledgements.length, 1);
  assert.equal(
    ack.repository.state.heldNotifications[0]?.reason,
    'ACK_UNKNOWN',
  );
});

await test('an exact thread mapping overrides its parent regardless of declaration order', async () => {
  const config = configuration();
  const parent = config.routes[0];
  assert.ok(parent);
  const f = fixture({
    ...config,
    routes: [
      parent,
      {
        ...parent,
        channelId: '200000000000000005',
        project: 'support',
        bot: 'supporter',
      },
    ],
  });
  await f.adapter.accept(
    input({ channelId: '200000000000000005', parentId: CHANNEL }),
  );
  assert.equal(f.gateway.submissions[0]?.project, 'support');
  assert.equal(f.gateway.submissions[0]?.agent, 'supporter');
  await f.adapter.poll();
  assert.equal(f.discord.sends.length, 1);
});

await test('foreign result coordinates fail closed before Discord disclosure', async () => {
  const f = fixture();
  await f.adapter.accept(input());
  f.gateway.result = { ...f.gateway.result, context: randomUUID() };
  await assert.rejects(f.adapter.poll(), /Foreign incoming/);
  assert.equal(f.discord.sends.length, 0);
});

await test('lost cancellation is observed without resubmitting the cancel mutation', async () => {
  const f = fixture();
  await f.adapter.accept(input());
  f.gateway.failure = new ConnectionFault(
    'RESPONSE_LOST',
    'Cancellation response lost',
  );
  await f.adapter.accept(
    input({ messageId: '200000000000000002', content: '!plowshare cancel' }),
  );
  f.gateway.result = {
    ...f.gateway.result,
    state: 'CANCELED',
    ending: 'CANCELED',
  };
  await f.adapter.poll();
  await f.adapter.poll();
  assert.equal(f.gateway.cancellations.length, 1);
  assert.ok(
    f.repository.state.records.find((r) => r.kind === 'CANCEL')?.finished,
  );
});

await test('a changed thread parent cannot adopt history or control tasks from its former audience', async () => {
  const parent = configuration().routes[0];
  assert.ok(parent);
  const newParent = '200000000000000006';
  const thread = '200000000000000005';
  const f = fixture({
    ...configuration(),
    routes: [parent, { ...parent, channelId: newParent }],
  });
  await f.adapter.accept(input({ channelId: thread, parentId: CHANNEL }));
  const taskId = f.repository.state.records[0]?.taskId;
  await f.adapter.accept(
    input({
      messageId: '200000000000000002',
      channelId: thread,
      parentId: newParent,
      content: `!plowshare cancel ${taskId}`,
    }),
  );
  assert.equal(f.gateway.cancellations.length, 0);
  await f.adapter.accept(
    input({
      messageId: '200000000000000003',
      channelId: thread,
      parentId: newParent,
    }),
  );
  assert.equal(f.gateway.submissions[1]?.context, null);
});

await test('malformed controls in any case cannot become agent input', async () => {
  const f = fixture();
  await f.adapter.accept(input({ content: '!PLOWSHARE cancel invalid' }));
  await f.adapter.accept(
    input({ content: '!plowshare status extra arguments' }),
  );
  assert.equal(f.gateway.submissions.length, 0);
});

await test('editing a notification destination cannot bypass uncertain sends or acknowledgments', async () => {
  for (const phase of ['send', 'ack', 'interrupted-send']) {
    const f = notificationFixture();
    if (phase === 'ack') {
      f.gateway.ackFailure = new ConnectionFault('RESPONSE_LOST', 'Lost ack');
      await assert.rejects(f.adapter.poll());
    } else {
      f.discord.failure = new Error('Lost send');
      await f.adapter.poll();
      if (phase === 'interrupted-send') {
        // Crash after the send intent was saved, before a subscription hold was saved.
        const output = f.repository.state.records[0]?.outputs[0];
        assert.ok(output);
        output.state = 'SENDING';
        f.repository.state.heldNotifications.splice(0);
      }
    }
    const newChannel = '200000000000000006';
    const config = {
      ...f.config,
      routes: [
        {
          guildId: GUILD,
          channelId: newChannel,
          project: 'research',
          bot: 'researcher',
          requireMention: false,
        },
      ],
      notifications: f.config.notifications.map((n) => ({
        ...n,
        channelId: newChannel,
      })),
    };
    const restarted = fixture(
      config,
      new MemoryState(decodeState(f.repository.state, config, PRINCIPAL, BOT)),
    );
    restarted.gateway.batch = f.gateway.batch;
    await restarted.adapter.poll();
    assert.equal(restarted.discord.sends.length, 0);
    assert.equal(restarted.gateway.acknowledgements.length, 0);
    assert.equal(restarted.repository.state.heldNotifications.length, 1);
  }
});
