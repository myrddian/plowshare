import { randomUUID } from 'node:crypto';
import type {
  Configuration,
  Destination,
  DiscordDelivery,
  DiscordInput,
  DirectDestination,
  GuildDestination,
  Gateway,
  MessageTask,
  State,
  StateRepository,
  Submission,
  TaskReference,
} from './contracts.ts';
import type {
  RelayAckRequest,
  RelayBatch,
  RelayConsumeRequest,
} from 'plowshare-client-node';
import { decodeConfig } from './config.ts';
import { messagingIdentity } from './state.ts';
import { MessagingRefusal } from './messaging-codec.ts';

export const GUILD = '100000000000000001';
export const CHANNEL = '100000000000000002';
export const USER = '100000000000000003';
export const OTHER = '100000000000000004';
export const BOT = '100000000000000005';
export const PRINCIPAL = '@service/00000000-0000-0000-0000-000000000001';
export function configuration(): Configuration {
  return decodeConfig({
    origin: 'https://plowshare.example.invalid',
    serviceTokenEnvironment: 'PLOWSHARE_DISCORD_SERVICE_TOKEN',
    discordTokenEnvironment: 'DISCORD_BOT_TOKEN',
    stateDirectory: '/tmp/discord-test-fixture',
    allowedUserIds: [USER, OTHER],
    messaging: [
      {
        project: 'research',
        bot: 'researcher',
        requests: 'discord.requests',
        replies: 'discord.replies',
      },
    ],
    routes: [
      {
        guildId: GUILD,
        channelId: CHANNEL,
        project: 'research',
        bot: 'researcher',
        requireMention: false,
      },
    ],
    pollMs: 2000,
    requestTimeoutMs: 30000,
  });
}
export function emptyState(config = configuration()): State {
  return {
    version: 3,
    messaging: messagingIdentity(config),
    relayGenerations: [],
    messageTraversals: [],
    origin: config.origin,
    principal: PRINCIPAL,
    discordBotId: BOT,
    consumerId: randomUUID(),
    contexts: [],
    records: [],
    heldNotifications: [],
  };
}
export class MemoryState implements StateRepository {
  saves = 0;
  persisted: string;
  fail = false;
  constructor(readonly state = emptyState()) {
    this.persisted = JSON.stringify(state);
  }
  async save(): Promise<void> {
    if (this.fail) throw new Error('Fixture persistence failure');
    this.saves++;
    this.persisted = JSON.stringify(this.state);
  }
}
export function input(
  overrides: Partial<
    GuildDestination & {
      messageId: string;
      userId: string;
      content: string;
      bot: boolean;
      webhook: boolean;
    }
  > = {},
): DiscordInput {
  return {
    kind: 'guild',
    messageId: '200000000000000001',
    guildId: GUILD,
    channelId: CHANNEL,
    parentId: null,
    userId: USER,
    content: 'Investigate the evidence.',
    bot: false,
    webhook: false,
    ...overrides,
  };
}
export function directInput(
  overrides: Partial<
    DirectDestination & {
      messageId: string;
      userId: string;
      content: string;
      bot: boolean;
      webhook: boolean;
    }
  > = {},
): DiscordInput {
  return {
    kind: 'dm',
    channelId: '100000000000000006',
    recipientId: USER,
    messageId: '200000000000000001',
    userId: USER,
    content: 'Investigate the evidence.',
    bot: false,
    webhook: false,
    ...overrides,
  };
}
export function task(agent = 'researcher'): MessageTask {
  return {
    id: randomUUID(),
    requestId: randomUUID(),
    cursor: null,
    context: randomUUID(),
    agent,
    message: 'bdm_fixture',
    state: 'WORKING',
    ending: null,
    revision: '1',
    replies: [],
    createdAt: '2026-01-01T00:00:00Z',
  };
}
export class FakeGateway implements Gateway {
  readonly submissions: Submission[] = [];
  readonly statuses: TaskReference[] = [];
  readonly cancellations: TaskReference[] = [];
  readonly acknowledgements: RelayAckRequest[] = [];
  failure: Error | null = null;
  recoveryAvailable = false;
  ackFailure: Error | null = null;
  result = task();
  batch: RelayBatch | null = null;
  async identity() {
    return PRINCIPAL;
  }
  async catalog(): Promise<void> {}
  async receive(request: Submission) {
    this.submissions.push(request);
    if (this.failure !== null) throw this.failure;
    // Every first receive starts an independently bound context; follow-ups preserve it.
    this.result = {
      ...this.result,
      id: randomUUID(),
      context: request.context ?? randomUUID(),
      agent: request.agent,
      requestId: request.requestId,
    };
    return this.result;
  }
  async status(reference: TaskReference) {
    this.statuses.push(reference);
    if (reference.id === null && !this.recoveryAvailable)
      throw new MessagingRefusal('NOT_ACCESSIBLE');
    return {
      ...this.result,
      id: reference.id ?? this.result.id,
      requestId:
        reference.id === null
          ? (reference.requestId ?? this.result.requestId)
          : this.result.requestId,
    };
  }
  async cancel(reference: TaskReference): Promise<MessageTask> {
    this.cancellations.push(reference);
    if (this.failure !== null) throw this.failure;
    return {
      ...this.result,
      id: reference.id ?? this.result.id,
      state: 'CANCELED',
      ending: 'CANCELED',
    };
  }
  async consume(request: RelayConsumeRequest) {
    if (this.batch !== null)
      return {
        ...this.batch,
        project: request.project,
        topic: request.topic,
        group: request.group,
        consumerId: request.consumerId,
      };
    return {
      ...request,
      status: 'EMPTY' as const,
      batchId: null,
      fence: null,
      through: '0',
      expiresAt: null,
      expiredThrough: null,
      events: [],
    };
  }
  async acknowledge(request: RelayAckRequest): Promise<void> {
    this.acknowledgements.push(request);
    if (this.ackFailure !== null) throw this.ackFailure;
  }
}
export class FakeDiscord implements DiscordDelivery {
  readonly sends: {
    destination: Destination;
    text: string;
    replyTo: string | null;
    nonce: string;
  }[] = [];
  failure: Error | null = null;
  async send(
    destination: Destination,
    text: string,
    replyTo: string | null,
    nonce: string,
  ) {
    this.sends.push({ destination, text, replyTo, nonce });
    if (this.failure !== null) throw this.failure;
    return (300000000000000000n + BigInt(this.sends.length)).toString();
  }
}
