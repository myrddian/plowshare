import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DiscordNetwork } from './discord.ts';
import { CHANNEL, GUILD } from './adapter.test-support.ts';

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
        { guildId: GUILD, channelId: CHANNEL, parentId: null },
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
        { guildId: GUILD, channelId: CHANNEL, parentId: null },
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
        { guildId: GUILD, channelId: CHANNEL, parentId: '100000000000000002' },
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
        { guildId: GUILD, channelId: CHANNEL, parentId: null },
        'bounded',
        null,
        'nonce',
      ),
    );
  } finally {
    await network.close();
  }
});
