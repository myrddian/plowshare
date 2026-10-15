import type {
  Payloads,
  Replies,
  RelayBatch,
  RelayAckRequest,
  RelayConsumeRequest,
} from 'plowshare-client-node';

export type IncomingTask = Replies['incoming.status'];
export type Submission = Payloads['incoming.receive'];
export type TaskReference = Payloads['incoming.status'];

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
}
/** Only native Discord events, decoded by the platform boundary, enter routing. */
export interface DiscordInput {
  readonly messageId: string;
  readonly guildId: string;
  readonly channelId: string;
  readonly parentId: string | null;
  readonly userId: string;
  readonly content: string;
  readonly bot: boolean;
  readonly webhook: boolean;
}
/** The adapter receives validated public SDK DTOs, never internal services. */
export interface Gateway {
  identity(): Promise<string>;
  catalog(route: Route): Promise<void>;
  receive(request: Submission): Promise<IncomingTask>;
  status(reference: TaskReference): Promise<IncomingTask>;
  cancel(reference: TaskReference): Promise<IncomingTask>;
  consume(request: RelayConsumeRequest): Promise<RelayBatch>;
  acknowledge(request: RelayAckRequest): Promise<void>;
}
export interface Destination {
  readonly guildId: string;
  readonly channelId: string;
  readonly parentId: string | null;
}
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
  readonly version: 1;
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
