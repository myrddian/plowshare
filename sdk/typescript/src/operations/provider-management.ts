import type { Code } from '../binding/codes.ts';
import { isObject } from '../binding/values.ts';
import { ConnectionFault } from '../binding/connection.ts';
import type { Payloads, Request } from './direct.ts';
import type { Replies } from './replies.ts';
import type { OperationTransport } from './response.ts';
import { decodePayload, decodeReply, decodeRequest } from './schema.ts';
import { checkedTransport } from './transport.ts';
import { providerUuid } from './provider-credentials.ts';
import type { ProviderMutationReceipt } from './provider-credentials.ts';
import type { HostedToolReceipt } from './hosted-tool-scopes.ts';

/** Owner management only. Worker credential release and native host launch are excluded. */
export const MANAGEMENT_OPERATIONS = [
  'provider.connection.list',
  'provider.connection.status',
  'provider.connection.receipt',
  'provider.connection.save',
  'provider.connection.change',
  'provider.credential.status',
  'provider.credential.put',
  'provider.credential.revoke',
  'provider.credential.rekey',
  'provider.credential.authorize',
  'provider.credential.refresh',
  'tool.scope.host.list',
  'tool.scope.host.status',
  'tool.scope.host.receipt',
  'tool.scope.host.save',
  'tool.scope.host.change',
  'tool.scope.list',
] as const;
export type ManagementOperation = (typeof MANAGEMENT_OPERATIONS)[number];
export type ManagementRequest = Extract<Request, { type: ManagementOperation }>;
export type ManagementValue = Replies[ManagementOperation];
export function isManagementOperation(
  value: string,
): value is ManagementOperation {
  return MANAGEMENT_OPERATIONS.some((operation) => operation === value);
}
/** JSON/IPC boundary: validate completely before returning the paired request union. */
export function managementRequest(
  operation: string,
  value: unknown,
): ManagementRequest {
  if (!isManagementOperation(operation))
    throw new Error('Unsupported owner management operation.');
  // decodeRequest validates every field; this assertion narrows its mapped operation union.
  return decodeRequest(operation, value) as ManagementRequest;
}

type ConnectionMutation =
  'provider.connection.save' | 'provider.connection.change';
type CredentialMutation =
  | 'provider.credential.put'
  | 'provider.credential.revoke'
  | 'provider.credential.rekey'
  | 'provider.credential.authorize'
  | 'provider.credential.refresh';
type BindingMutation = 'tool.scope.host.save' | 'tool.scope.host.change';
/** A recovery bookmark, never a replayable request. No definition, secret, hash or OAuth URL. */
export type ManagementIntent = {
  readonly project: string;
  readonly requestId: string;
} & (
  | {
      readonly operation: ConnectionMutation;
      readonly connection: string;
      readonly expectedRevision: string | null;
    }
  | {
      readonly operation: CredentialMutation;
      readonly connection: string;
      readonly subject: string | null;
      readonly expectedGeneration: string | null;
    }
  | {
      readonly operation: BindingMutation;
      readonly binding: string;
      readonly expectedGeneration: number | null;
    }
);
/** Implementations partition by authenticated origin/actor, validate storage and reserve atomically.
 * reserve refuses a second unresolved action on the same resource; remove matches the exact UUID.
 * Persistence completes before submission. A failed write prevents network effects. */
export interface ManagementIntentStore {
  list(): Promise<readonly ManagementIntent[]>;
  reserve(intent: ManagementIntent): Promise<void>;
  remove(requestId: string): Promise<void>;
}
export interface ManagementBookmarkStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}
/** Reports the original unresolved action, never the newly attempted UUID. */
export class ProviderManagementPending extends Error {
  readonly intent: ManagementIntent;
  constructor(intent: ManagementIntent) {
    super(
      `Outcome unknown for ${intent.operation}, ${intent.project}, request ${intent.requestId}. Read its original receipt; no mutation was replayed.`,
    );
    this.intent = decodeManagementIntent(intent);
  }
}
/** Browser composition uses session storage, partitioned by origin/actor. Only redacted
 * coordinates survive a reload; tabs never share a secret or an executable request. */
export class SessionManagementIntents implements ManagementIntentStore {
  private readonly storage: ManagementBookmarkStorage;
  private readonly key: string;
  constructor(storage: ManagementBookmarkStorage, key: string) {
    if (!key)
      throw new Error('Authenticated management storage scope is required.');
    this.storage = storage;
    this.key = key;
  }
  private read(): readonly ManagementIntent[] {
    const text = this.storage.getItem(this.key);
    if (text === null) return [];
    if (text.length > 131_072)
      throw new Error('Management bookmarks exceed the storage limit.');
    const value: unknown = JSON.parse(text);
    if (!Array.isArray(value) || value.length > 100)
      throw new Error('Invalid management bookmarks.');
    const intents = value.map((entry: unknown) =>
      decodeManagementIntent(entry),
    );
    if (
      new Set(intents.map((intent) => intent.requestId)).size !== intents.length
    )
      throw new Error('Duplicate management bookmarks.');
    return intents;
  }
  list(): Promise<readonly ManagementIntent[]> {
    return Promise.resolve(this.read());
  }
  reserve(input: ManagementIntent): Promise<void> {
    const intent = decodeManagementIntent(input),
      saved = this.read();
    const pending = saved.find(
      (entry) =>
        entry.requestId === intent.requestId ||
        sameManagementResource(entry, intent),
    );
    if (pending) throw new ProviderManagementPending(pending);
    if (saved.length >= 100)
      throw new Error('Management bookmark limit reached.');
    this.storage.setItem(this.key, JSON.stringify([...saved, intent]));
    return Promise.resolve();
  }
  remove(requestId: string): Promise<void> {
    this.storage.setItem(
      this.key,
      JSON.stringify(
        this.read().filter((intent) => intent.requestId !== requestId),
      ),
    );
    return Promise.resolve();
  }
}
export interface ProviderManagement {
  request<K extends ManagementOperation>(
    operation: K,
    input: Payloads[K],
  ): Promise<Replies[K]>;
  execute(request: ManagementRequest): Promise<ManagementValue>;
  pending(): Promise<readonly ManagementIntent[]>;
  recover(
    intent: ManagementIntent,
  ): Promise<ProviderMutationReceipt | HostedToolReceipt>;
}
export function managementIntent(
  request: ManagementRequest,
): ManagementIntent | undefined {
  const { type, payload } = request;
  switch (type) {
    case 'provider.connection.save':
    case 'provider.connection.change':
      return {
        operation: type,
        project: payload.project,
        requestId: payload.requestId,
        connection: payload.connection,
        expectedRevision: payload.expectedRevision,
      };
    case 'provider.credential.put':
    case 'provider.credential.revoke':
    case 'provider.credential.rekey':
    case 'provider.credential.authorize':
    case 'provider.credential.refresh':
      return {
        operation: type,
        project: payload.project,
        requestId: payload.requestId,
        connection: payload.connection,
        subject: payload.subject,
        expectedGeneration: payload.expectedGeneration,
      };
    case 'tool.scope.host.save':
    case 'tool.scope.host.change':
      return {
        operation: type,
        project: payload.project,
        requestId: payload.requestId,
        binding: payload.binding,
        expectedGeneration: payload.expectedGeneration,
      };
    default:
      return undefined;
  }
}
/** Reject injected stored bodies rather than preserving unknown fields in a recovery bookmark. */
export function decodeManagementIntent(value: unknown): ManagementIntent {
  if (!isObject(value) || typeof value.operation !== 'string')
    throw new Error('Invalid management bookmark.');
  const operation = value.operation;
  let keys: readonly string[], intent: ManagementIntent;
  if (
    operation === 'provider.connection.save' ||
    operation === 'provider.connection.change'
  ) {
    keys = [
      'operation',
      'project',
      'requestId',
      'connection',
      'expectedRevision',
    ];
    const coordinates = decodePayload('provider.connection.status', {
      project: value.project,
      connection: value.connection,
    });
    if (
      value.expectedRevision !== null &&
      !providerUuid(value.expectedRevision)
    )
      throw new Error('Invalid bookmark revision.');
    if (
      operation === 'provider.connection.change' &&
      value.expectedRevision === null
    )
      throw new Error('Missing bookmark revision.');
    intent = {
      operation,
      ...coordinates,
      requestId: intentId(value.requestId),
      expectedRevision: value.expectedRevision as string | null,
    };
  } else if (
    [
      'provider.credential.put',
      'provider.credential.revoke',
      'provider.credential.rekey',
      'provider.credential.authorize',
      'provider.credential.refresh',
    ].includes(operation)
  ) {
    keys = [
      'operation',
      'project',
      'requestId',
      'connection',
      'subject',
      'expectedGeneration',
    ];
    const coordinates = decodePayload('provider.credential.revoke', {
      project: value.project,
      connection: value.connection,
      subject: value.subject,
      requestId: value.requestId,
      expectedGeneration: value.expectedGeneration,
    });
    // The validated finite operation set above selects this closed variant.
    intent = { operation: operation as CredentialMutation, ...coordinates };
  } else if (
    operation === 'tool.scope.host.save' ||
    operation === 'tool.scope.host.change'
  ) {
    keys = [
      'operation',
      'project',
      'requestId',
      'binding',
      'expectedGeneration',
    ];
    const coordinates = decodePayload('tool.scope.host.status', {
      project: value.project,
      binding: value.binding,
    });
    if (
      !(
        value.expectedGeneration === null ||
        (typeof value.expectedGeneration === 'number' &&
          Number.isSafeInteger(value.expectedGeneration) &&
          value.expectedGeneration >= 1)
      ) ||
      (operation === 'tool.scope.host.change' &&
        value.expectedGeneration === null)
    )
      throw new Error('Invalid bookmark generation.');
    intent = {
      operation,
      ...coordinates,
      requestId: intentId(value.requestId),
      expectedGeneration: value.expectedGeneration,
    };
  } else throw new Error('Unsupported management bookmark.');
  if (
    Object.keys(value).length !== keys.length ||
    Object.keys(value).some((key) => !keys.includes(key))
  )
    throw new Error('Unexpected management bookmark fields.');
  return intent;
}
function intentId(value: unknown): string {
  if (typeof value !== 'string' || !providerUuid(value))
    throw new Error('Invalid bookmark request UUID.');
  return value;
}
export function sameManagementResource(
  a: ManagementIntent,
  b: ManagementIntent,
): boolean {
  if (a.project !== b.project) return false;
  if ('binding' in a || 'binding' in b)
    return 'binding' in a && 'binding' in b && a.binding === b.binding;
  // A definition change affects every subject's grants, so fence the entire connection.
  return a.connection === b.connection;
}

export class ProviderManagementRefused extends Error {
  readonly operation: ManagementOperation;
  readonly code: Code;
  readonly unsupported: boolean;
  constructor(
    operation: ManagementOperation,
    code: Code,
    unsupported: boolean,
  ) {
    // Do not echo server diagnostics: even a refusal may contain submitted credential material.
    super(
      unsupported
        ? `${operation}: unsupported by this server. Upgrade the server; no HTTP fallback was attempted.`
        : `${operation}: ${code}. Inspect current permissions and revision/generation before a new action.`,
    );
    this.operation = operation;
    this.code = code;
    this.unsupported = unsupported;
  }
}
/** Reuses one checked socket. Writes occur once; receipt reads never resubmit a mutation.
 * INTERNAL_ERROR and transport/unreadable failures retain the bookmark. Boundary/domain
 * refusals establish non-admission. A missing receipt leaves the original UUID fenced. */
export class ProviderManagementClient implements ProviderManagement {
  private readonly transport: OperationTransport;
  private readonly intents: ManagementIntentStore;
  constructor(transport: OperationTransport, intents: ManagementIntentStore) {
    this.transport = checkedTransport(transport);
    this.intents = intents;
  }
  pending(): Promise<readonly ManagementIntent[]> {
    return this.intents.list();
  }
  async request<K extends ManagementOperation>(
    operation: K,
    input: Payloads[K],
  ): Promise<Replies[K]> {
    const request = managementRequest(operation, input);
    const intent = managementIntent(request);
    if (intent) await this.intents.reserve(intent);
    const answer = await this.transport
      .ask(operation, decodePayload(operation, input))
      .catch(async (failure: unknown) => {
        // The checked connection can prove non-submission. Every other delivery failure
        // remains uncertain; neither branch retries the request.
        if (
          intent &&
          failure instanceof ConnectionFault &&
          failure.code === 'NOT_SUBMITTED'
        )
          await this.intents.remove(intent.requestId);
        throw failure;
      });
    if (answer.code !== 'OK') {
      if (
        intent &&
        ['BAD_REQUEST', 'VALIDATION_FAILED', 'CONFLICT', 'NOT_FOUND'].includes(
          answer.code,
        )
      )
        await this.intents.remove(intent.requestId);
      const unsupported =
        answer.code === 'NOT_FOUND' &&
        answer.said === `no handler is registered for type "${operation}"`;
      throw new ProviderManagementRefused(operation, answer.code, unsupported);
    }
    const value = decodeReply(operation, answer.payload);
    if (intent) await this.intents.remove(intent.requestId);
    return value;
  }
  async execute(request: ManagementRequest): Promise<ManagementValue> {
    if (
      request.type === 'provider.connection.receipt' ||
      request.type === 'tool.scope.host.receipt'
    ) {
      const pending = (await this.pending()).find(
        (intent) =>
          intent.project === request.payload.project &&
          intent.requestId === request.payload.requestId &&
          'binding' in intent === (request.type === 'tool.scope.host.receipt'),
      );
      if (pending) return this.recover(pending);
    }
    return this.request(request.type, request.payload);
  }
  async recover(
    input: ManagementIntent,
  ): Promise<ProviderMutationReceipt | HostedToolReceipt> {
    const intent = decodeManagementIntent(input);
    if ('binding' in intent) {
      const receipt = await this.request('tool.scope.host.receipt', {
        project: intent.project,
        requestId: intent.requestId,
      });
      if (
        receipt.operation !== intent.operation ||
        receipt.binding.binding !== intent.binding
      )
        throw new Error(
          'Receipt does not match the original management action; no mutation was replayed.',
        );
      await this.intents.remove(intent.requestId);
      return receipt;
    }
    const receipt = await this.request('provider.connection.receipt', {
      project: intent.project,
      requestId: intent.requestId,
    });
    if (
      receipt.operation !== intent.operation ||
      receipt.connection.connection !== intent.connection ||
      ('subject' in intent &&
        intent.subject !== null &&
        receipt.credential?.subject !== intent.subject)
    )
      throw new Error(
        'Receipt does not match the original management action; no mutation was replayed.',
      );
    await this.intents.remove(intent.requestId);
    return receipt;
  }
}

/** CLI validation/output projection. Real material must enter through stdin or a masked field. */
export function redactedManagementRequest(
  request: ManagementRequest,
): ManagementRequest {
  return request.type === 'provider.credential.put'
    ? {
        type: request.type,
        payload: { ...request.payload, secret: '[redacted]' },
      }
    : request;
}
/** OAuth URL is a one-time user action, never terminal JSON, state, storage or diagnostics. */
export function redactedManagementValue(
  operation: ManagementOperation,
  value: ManagementValue,
): ManagementValue {
  if (operation !== 'provider.credential.authorize') return value;
  const authorization = decodeReply('provider.credential.authorize', value);
  return { ...authorization, authorizationUrl: '[redacted]' };
}
