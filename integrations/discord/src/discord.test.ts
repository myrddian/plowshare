import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ChannelType, Events, GatewayIntentBits, Partials } from 'discord.js';
import type { Message, OmitPartialGroupDMChannel } from 'discord.js';
import { DiscordNetwork } from './discord.ts';
import type { DiscordInput } from './contracts.ts';
import { CHANNEL, GUILD, USER } from './adapter.test-support.ts';

await test('Discord send verifies fresh destination metadata and disables all mention pings', async () => {
  const network = new DiscordNetwork(1000);
  let sent: unknown;
  network.client.rest.get = async () => ({
    id: CHANNEL,
    guild_id: GUILD,
    type: 0,
  });
  network.client.rest.post = async (_route, options) => {
    assert.ok(options?.signal);
    sent = options.body;
    return { id: '300000000000000001' };
  };
  try {
    assert.equal(
      await network.send(
        { kind: 'guild', guildId: GUILD, channelId: CHANNEL, parentId: null },
        '@everyone <@100000000000000003>',
        '200000000000000001',
        'nonce',
      ),
      '300000000000000001',
    );
    assert.ok(
      sent !== null &&
        typeof sent === 'object' &&
        'allowed_mentions' in sent &&
        'enforce_nonce' in sent,
    );
    assert.deepEqual(sent.allowed_mentions, { parse: [], replied_user: false });
    assert.equal(sent.enforce_nonce, true);
    assert.equal(network.client.rest.options.retries, 0);
  } finally {
    await network.close();
  }
});

await test('wrong guild or changed thread parent prevents Discord send before HTTP mutation', async () => {
  const network = new DiscordNetwork(1000);
  let sends = 0;
  network.client.rest.post = async () => {
    sends++;
    return { id: '300000000000000001' };
  };
  try {
    network.client.rest.get = async () => ({
      id: CHANNEL,
      guild_id: '900000000000000009',
      type: 0,
    });
    await assert.rejects(
      network.send(
        { kind: 'guild', guildId: GUILD, channelId: CHANNEL, parentId: null },
        'private',
        null,
        'nonce',
      ),
    );
    network.client.rest.get = async () => ({
      id: CHANNEL,
      guild_id: GUILD,
      type: 11,
      parent_id: '900000000000000009',
    });
    await assert.rejects(
      network.send(
        {
          kind: 'guild',
          guildId: GUILD,
          channelId: CHANNEL,
          parentId: '100000000000000002',
        },
        'private',
        null,
        'nonce',
      ),
    );
    assert.equal(sends, 0);
  } finally {
    await network.close();
  }
});

await test('a Discord response without a message ID provides no delivery confirmation', async () => {
  const network = new DiscordNetwork(1000);
  network.client.rest.get = async () => ({
    id: CHANNEL,
    guild_id: GUILD,
    type: 0,
  });
  network.client.rest.post = async () => ({});
  try {
    await assert.rejects(
      network.send(
        { kind: 'guild', guildId: GUILD, channelId: CHANNEL, parentId: null },
        'bounded',
        null,
        'nonce',
      ),
    );
  } finally {
    await network.close();
  }
});

await test('Discord DM delivery requires a fresh one-to-one recipient match and enables uncached DM intake', async () => {
  const network = new DiscordNetwork(1000);
  let reads = 0;
  let sends = 0;
  network.client.rest.get = async () => {
    reads++;
    return { id: CHANNEL, type: 1, recipients: [{ id: '100000000000000003' }] };
  };
  network.client.rest.post = async () => {
    sends++;
    return { id: '300000000000000001' };
  };
  const destination = {
    kind: 'dm' as const,
    channelId: CHANNEL,
    recipientId: '100000000000000003',
  };
  try {
    assert.ok(
      network.client.options.intents.has(GatewayIntentBits.DirectMessages),
    );
    assert.ok(network.client.options.partials?.includes(Partials.Channel));
    await network.send(destination, 'private', null, 'nonce');
    await network.send(destination, 'private again', null, 'nonce2');
    assert.equal(reads, 2);
    assert.equal(sends, 2);
    for (const channel of [
      { id: CHANNEL, type: 1, recipients: [{ id: '100000000000000004' }] },
      { id: CHANNEL, type: 1, recipients: [] },
      {
        id: CHANNEL,
        type: 1,
        recipients: [
          { id: destination.recipientId },
          { id: '100000000000000004' },
        ],
      },
      {
        id: CHANNEL,
        type: 1,
        recipients: [{ id: destination.recipientId }],
        guild_id: GUILD,
      },
      { id: CHANNEL, type: 3, recipients: [{ id: destination.recipientId }] },
      { id: CHANNEL, type: 0, guild_id: GUILD },
    ]) {
      network.client.rest.get = async () => channel;
      await assert.rejects(
        network.send(destination, 'private', null, 'nonce3'),
      );
    }
    assert.equal(sends, 2);
  } finally {
    await network.close();
  }
});

await test('native partial DM messages decode into a direct recipient without a fabricated guild', async () => {
  const network = new DiscordNetwork(1000);
  const received: DiscordInput[] = [];
  network.onMessage((message) => received.push(message));
  const isDirect = (
    message: Message,
  ): message is OmitPartialGroupDMChannel<Message> =>
    message.channel.type === ChannelType.DM;
  const author = {
    id: USER,
    username: 'fixture',
    discriminator: '0',
    avatar: null,
    bot: false,
  };
  const messageId = '200000000000000001';
  network.client.rest.get = async (route) =>
    route === `/channels/${CHANNEL}`
      ? { id: CHANNEL, type: ChannelType.DM, recipients: [author] }
      : {
          id: messageId,
          channel_id: CHANNEL,
          author,
          content: 'Direct evidence.',
          type: 0,
        };
  try {
    // Public discord.js managers construct real library objects from the REST boundary fixture.
    const channel = await network.client.channels.fetch(CHANNEL);
    assert.ok(channel?.type === ChannelType.DM);
    assert.equal(channel.partial, true);
    const message = await channel.messages.fetch(messageId);
    assert.ok(isDirect(message));
    network.client.emit(Events.MessageCreate, message);
    assert.deepEqual(received, [
      {
        kind: 'dm',
        channelId: CHANNEL,
        recipientId: USER,
        userId: USER,
        messageId,
        content: 'Direct evidence.',
        bot: false,
        webhook: false,
      },
    ]);
    network.client.rest.get = async () => ({
      id: messageId,
      channel_id: CHANNEL,
      author: { ...author, bot: true },
      content: 'Ignore bots.',
      type: 0,
    });
    const bot = await channel.messages.fetch({
      message: messageId,
      force: true,
    });
    assert.ok(isDirect(bot));
    network.client.emit(Events.MessageCreate, bot);
    network.client.rest.get = async () => ({
      id: messageId,
      channel_id: CHANNEL,
      author,
      webhook_id: '100000000000000009',
      content: 'Ignore webhooks.',
      type: 0,
    });
    const webhook = await channel.messages.fetch({
      message: messageId,
      force: true,
    });
    assert.ok(isDirect(webhook));
    network.client.emit(Events.MessageCreate, webhook);
    assert.equal(received.length, 1);
  } finally {
    await network.close();
  }
});
