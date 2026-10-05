import type { Outcome } from '../binding/envelope.ts';
import {
  bool,
  count,
  list,
  named,
  nullable,
  record,
  text,
  type Check,
} from './wire-checks.ts';

/** Stable instance addresses; transport topics and seats are not part of this contract. */
export interface MessageInstance {
  readonly id: string;
  readonly project: string;
  readonly agent: string;
  readonly conversation: string;
  readonly lifetime: 'persistent' | 'task' | 'caller';
  readonly defaultInstance: boolean;
  readonly active: boolean;
  readonly archived: boolean;
  readonly state: string;
  readonly pending: number;
  readonly job: string | null;
  readonly createdAt: string;
}
export interface MessageDelivery {
  readonly message: string;
  readonly sender: string;
  readonly recipient: string;
  readonly replyTo: string | null;
  readonly replyExpected: boolean;
  readonly finalReply: boolean;
  readonly generated: boolean;
  readonly state: string;
  readonly ending: string | null;
  readonly reply: string | null;
  readonly job: string | null;
  readonly deadlineAt: string | null;
  readonly postedAt: string;
  readonly body: string;
}
export interface MessagingPayloads {
  'message.instances': {
    readonly project: string;
    readonly archived?: boolean;
    readonly offset?: number;
    readonly limit?: number;
  };
  'message.instance': { readonly instance: string };
  'message.instance.open': {
    readonly project: string;
    readonly agent: string;
    readonly makeDefault?: boolean;
    readonly requestId: string;
  };
  'message.instance.default': { readonly instance: string };
  'message.instance.stop': { readonly instance: string };
  'message.instance.archive': { readonly instance: string };
  'message.deliveries': {
    readonly instance: string;
    readonly offset?: number;
    readonly limit?: number;
  };
  'message.delivery': { readonly message: string };
  'message.cancel': { readonly message: string };
}
export interface MessageInstances {
  readonly instances: readonly MessageInstance[];
  readonly more: boolean;
  readonly offset: number;
}
export interface MessageDeliveries {
  readonly deliveries: readonly MessageDelivery[];
  readonly more: boolean;
  readonly offset: number;
}
export interface MessagingReplies {
  'message.instances': MessageInstances;
  'message.instance': MessageInstance;
  'message.instance.open': MessageInstance;
  'message.instance.default': MessageInstance;
  'message.instance.stop': MessageInstance;
  'message.instance.archive': MessageInstance;
  'message.deliveries': MessageDeliveries;
  'message.delivery': MessageDelivery;
  'message.cancel': MessageDelivery;
}
const instance = record(
  {
    id: named,
    project: named,
    agent: named,
    conversation: named,
    lifetime: (v) => ['persistent', 'task', 'caller'].includes(String(v)),
    defaultInstance: bool,
    active: bool,
    archived: bool,
    state: named,
    pending: count,
    job: nullable(named),
    createdAt: named,
  },
  (row) =>
    (!row['defaultInstance'] ||
      (row['lifetime'] === 'persistent' && row['active'] === true)) &&
    (!row['archived'] || row['active'] === false),
);
const delivery = record(
  {
    message: named,
    sender: named,
    recipient: named,
    replyTo: nullable(named),
    replyExpected: bool,
    finalReply: bool,
    generated: bool,
    state: named,
    ending: nullable(named),
    reply: nullable(named),
    job: nullable(named),
    deadlineAt: nullable(named),
    postedAt: named,
    body: text,
  },
  (row) =>
    (!row['finalReply'] || row['replyTo'] !== null) &&
    (!row['generated'] ||
      (row['finalReply'] === true && row['ending'] !== null)),
);
const readers = {
  'message.instances': record({
    instances: list(instance),
    more: bool,
    offset: count,
  }),
  'message.instance': instance,
  'message.instance.open': instance,
  'message.instance.default': instance,
  'message.instance.stop': instance,
  'message.instance.archive': instance,
  'message.deliveries': record({
    deliveries: list(delivery),
    more: bool,
    offset: count,
  }),
  'message.delivery': delivery,
  'message.cancel': delivery,
} satisfies Record<keyof MessagingReplies, Check>;
export const MESSAGING_OPERATIONS = Object.keys(
  readers,
) as readonly (keyof MessagingReplies)[];
export const isMessagingOperation = (
  type: string,
): type is keyof MessagingReplies => Object.hasOwn(readers, type);
export function messagingReply<T extends keyof MessagingReplies>(
  type: T,
  outcome: Outcome,
): (Outcome & { readonly payload?: MessagingReplies[T] }) | undefined {
  return outcome.code === 'OK' && readers[type](outcome.payload)
    ? (outcome as Outcome & { readonly payload?: MessagingReplies[T] })
    : undefined;
}
