import type {
  RelayBatch,
  RelayAckRequest,
  RelayConsumeRequest,
} from 'plowshare-client-node';

/** Adapter-owned projection of the generic messaging task contract. */
export interface MessageTask {
  readonly id: string;
  readonly requestId: string;
  readonly cursor: string | null;
  readonly context: string;
  readonly agent: string;
  readonly message: string;
  readonly state:
    | 'SUBMITTED'
    | 'WORKING'
    | 'INPUT_REQUIRED'
    | 'COMPLETED'
    | 'FAILED'
    | 'CANCELED';
  readonly ending: string | null;
  readonly replies: readonly {
    readonly id: string;
    readonly body: string;
    readonly finalReply: boolean;
    readonly generated: boolean;
    readonly ending: string | null;
    readonly postedAt: string;
  }[];
  readonly createdAt: string;
  readonly revision: string;
}
export type MessageHeader = Omit<MessageTask, 'replies' | 'cursor'>;
/** Fixed-head read progress; retained until outputs and final FOLLOW cursor commit together. */
export interface MessageTraversal {
  readonly project: string;
  readonly agent: string;
  readonly requests: string;
  readonly replies: string;
  readonly client: string;
  readonly header: MessageHeader;
  readonly parts: readonly {
    readonly reply: MessageTask['replies'][number];
    readonly complete: boolean;
  }[];
  readonly next: string | null;
  readonly resume: string | null;
  readonly terminalFollowed: boolean;
}
export interface Submission {
  readonly project: string;
  readonly agent: string;
  readonly client: string;
  readonly requestId: string;
  readonly context: string | null;
  readonly body: string;
}
export interface TaskReference {
  readonly project: string;
  readonly client: string;
  readonly agent: string;
  readonly id: string | null;
  readonly requestId?: string;
  readonly cursor?: string | null;
}
/** Topics are operator pins. No Discord message may select these destinations. */
export interface MessagingBinding {
  readonly project: string;
  readonly bot: string;
  readonly requests: string;
  readonly replies: string;
}

export interface Route {
  readonly guildId: string;
  readonly channelId: string;
  readonly project: string;
  readonly bot: string;
  readonly requireMention: boolean;
}
export interface NotificationRoute {
  readonly channelId: string;
  readonly project: string;
  readonly topic: string;
  readonly group: string;
  readonly start: 'LATEST' | 'OLDEST_RETAINED';
}
export interface BotTarget {
  readonly project: string;
  readonly bot: string;
}
/** Disabled unless explicitly mapped. DMs use service authority, never human delegation. */
export type DirectMessagePolicy =
  { readonly mode: 'disabled' } | ({ readonly mode: 'service' } & BotTarget);
export interface Configuration {
  readonly origin: string;
  readonly serviceTokenEnvironment: string;
  readonly discordTokenEnvironment: string;
  readonly stateDirectory: string;
  readonly allowedUserIds: readonly string[];
  readonly routes: readonly Route[];
  readonly notifications: readonly NotificationRoute[];
  readonly pollMs: number;
  readonly requestTimeoutMs: number;
  readonly maxRecords: number;
  readonly dm: DirectMessagePolicy;
  readonly messaging: readonly MessagingBinding[];
  readonly legacyStateMigration: boolean;
}
/** Only native Discord events, decoded by the platform boundary, enter routing. */
export type DiscordInput = Destination & {
  readonly messageId: string;
  readonly userId: string;
  readonly content: string;
  readonly bot: boolean;
  readonly webhook: boolean;
};
/** The adapter receives validated public SDK DTOs, never internal services. */
export interface Gateway {
  identity(): Promise<string>;
  catalog(route: BotTarget): Promise<void>;
  receive(request: Submission): Promise<MessageTask>;
  status(reference: TaskReference): Promise<MessageTask>;
  cancel(reference: TaskReference): Promise<MessageTask>;
  consume(request: RelayConsumeRequest): Promise<RelayBatch>;
  acknowledge(request: RelayAckRequest): Promise<void>;
}
export interface GuildDestination {
  readonly kind: 'guild';
  readonly guildId: string;
  readonly channelId: string;
  readonly parentId: string | null;
}
export interface DirectDestination {
  readonly kind: 'dm';
  readonly channelId: string;
  readonly recipientId: string;
}
export type Destination = GuildDestination | DirectDestination;
/** A missing send receipt is uncertain. Implementations must disable effect retries. */
export interface DiscordDelivery {
  send(
    destination: Destination,
    text: string,
    replyTo: string | null,
    nonce: string,
  ): Promise<string>;
}
export type Admission =
  'ADMITTING' | 'ACCEPTED' | 'UNKNOWN' | 'REFUSED' | 'NOT_SUBMITTED';
export interface Output {
  readonly key: string;
  readonly text: string;
  state: 'READY' | 'SENDING' | 'SENT' | 'UNKNOWN';
  messageId: string | null;
}
export interface RecordEntry {
  readonly key: string;
  readonly kind: 'ASK' | 'STATUS' | 'CANCEL' | 'NOTICE';
  readonly userId: string | null;
  readonly destination: Destination;
  readonly project: string;
  readonly bot: string;
  readonly client: string;
  readonly contextKey: string;
  readonly requestId: string;
  nativeHeader: MessageHeader | null;
  lastRevision: string;
  replyCursor: string | null;
  readonly replyTo: string | null;
  readonly notification: NotificationRoute | null;
  admission: Admission;
  taskId: string | null;
  contextId: string | null;
  finished: boolean;
  readonly seenReplies: string[];
  readonly outputs: Output[];
}
export interface ContextBinding {
  readonly key: string;
  readonly contextId: string;
}
/** Private delivery coordinates and receipts; credentials and inbound prompts are never retained. */
export interface State {
  readonly version: 3;
  readonly messaging: string;
  readonly messageTraversals: MessageTraversal[];
  readonly relayGenerations: {
    readonly project: string;
    readonly topic: string;
    readonly generation: string;
  }[];
  readonly origin: string;
  readonly principal: string;
  readonly discordBotId: string;
  readonly consumerId: string;
  readonly contexts: ContextBinding[];
  readonly records: RecordEntry[];
  readonly heldNotifications: {
    readonly key: string;
    readonly reason: 'GAP' | 'ACK_UNKNOWN' | 'DELIVERY_UNKNOWN';
  }[];
}
/** Exclusive process ownership serializes updates; save resolves after durable replacement. */
export interface StateRepository {
  readonly state: State;
  save(): Promise<void>;
}
