import {
  ChannelType,
  Client,
  Events,
  GatewayIntentBits,
  Partials,
  Routes,
} from 'discord.js';
import type { Message } from 'discord.js';
import type {
  Destination,
  DiscordDelivery,
  DiscordInput,
} from './contracts.ts';
import { snowflake } from './config.ts';

/** Fresh REST metadata is checked before disclosure; cache entries do not prove today's route. */
function channelDestination(value: unknown): Destination {
  if (
    value === null ||
    typeof value !== 'object' ||
    !('id' in value) ||
    !('type' in value)
  )
    throw new Error('Discord returned no supported channel');
  const channelId = snowflake(value.id);
  if (value.type === ChannelType.DM) {
    if (
      ('guild_id' in value && value.guild_id !== null) ||
      !('recipients' in value) ||
      !Array.isArray(value.recipients) ||
      value.recipients.length !== 1
    )
      throw new Error('Discord returned no exact DM recipient');
    const recipient: unknown = value.recipients[0];
    if (
      recipient === null ||
      typeof recipient !== 'object' ||
      !('id' in recipient)
    )
      throw new Error('Discord returned no exact DM recipient');
    return { kind: 'dm', channelId, recipientId: snowflake(recipient.id) };
  }
  if (!('guild_id' in value)) throw new Error('Discord returned no guild');
  const guildId = snowflake(value.guild_id);
  if (
    value.type === ChannelType.GuildText ||
    value.type === ChannelType.GuildAnnouncement
  )
    return { kind: 'guild', guildId, channelId, parentId: null };
  if (
    (value.type === ChannelType.PublicThread ||
      value.type === ChannelType.PrivateThread ||
      value.type === ChannelType.AnnouncementThread) &&
    'parent_id' in value
  )
    return {
      kind: 'guild',
      guildId,
      channelId,
      parentId: snowflake(value.parent_id),
    };
  throw new Error('Discord destination must be a text channel or thread');
}

/** Discord Gateway input and Discord HTTP output are explicit external protocol boundaries. */
export class DiscordNetwork implements DiscordDelivery {
  readonly client: Client;
  constructor(private readonly timeoutMs: number) {
    this.client = new Client({
      intents: [
        GatewayIntentBits.Guilds,
        GatewayIntentBits.GuildMessages,
        GatewayIntentBits.DirectMessages,
        GatewayIntentBits.MessageContent,
      ],
      partials: [Partials.Channel],
      allowedMentions: { parse: [], repliedUser: false },
      rest: { retries: 0, timeout: timeoutMs },
    });
  }
  onMessage(listener: (message: DiscordInput) => void): void {
    this.client.on(Events.MessageCreate, (message: Message) => {
      // Only one-to-one DMs enter direct routing; the adapter separately enforces its policy.
      if (message.author.bot || message.webhookId !== null) return;
      if (message.guildId === null && message.channel.type !== ChannelType.DM)
        return;
      const destination: Destination =
        message.guildId === null
          ? {
              kind: 'dm',
              channelId: snowflake(message.channelId),
              recipientId: snowflake(message.author.id),
            }
          : {
              kind: 'guild',
              guildId: snowflake(message.guildId),
              channelId: snowflake(message.channelId),
              parentId: message.channel.isThread()
                ? message.channel.parentId
                : null,
            };
      listener({
        ...destination,
        messageId: snowflake(message.id),
        userId: snowflake(message.author.id),
        content: message.content,
        bot: message.author.bot,
        webhook: message.webhookId !== null,
      });
    });
  }
  async login(token: string): Promise<string> {
    await this.client.login(token);
    if (this.client.user === null)
      throw new Error('Discord bot identity is unavailable');
    return snowflake(this.client.user.id);
  }
  async send(
    destination: Destination,
    text: string,
    replyTo: string | null,
    nonce: string,
  ): Promise<string> {
    const signal = AbortSignal.timeout(this.timeoutMs);
    const channel = channelDestination(
      await this.client.rest.get(Routes.channel(destination.channelId), {
        signal,
      }),
    );
    if (
      channel.channelId !== destination.channelId ||
      (channel.kind === 'dm'
        ? destination.kind !== 'dm' ||
          channel.recipientId !== destination.recipientId
        : destination.kind !== 'guild' ||
          channel.guildId !== destination.guildId ||
          channel.parentId !== destination.parentId)
    )
      throw new Error(
        'Discord destination no longer matches its admitted audience',
      );
    // Discord handles safe 429 pacing; ambiguous HTTP/network sends are not retried.
    // Nonce deduplication is supplementary; the journal remains authoritative for uncertainty.
    const result: unknown = await this.client.rest.post(
      Routes.channelMessages(destination.channelId),
      {
        signal,
        body: {
          content: text,
          allowed_mentions: { parse: [], replied_user: false },
          nonce,
          enforce_nonce: true,
          ...(replyTo === null
            ? {}
            : {
                message_reference: {
                  message_id: replyTo,
                  fail_if_not_exists: false,
                },
              }),
        },
      },
    );
    if (result === null || typeof result !== 'object' || !('id' in result))
      throw new Error('Discord did not return a message receipt');
    return snowflake(result.id);
  }
  async close(): Promise<void> {
    await this.client.destroy();
  }
}
