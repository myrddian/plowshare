import { retrievalReply } from './retrieval.ts';
import { validateInformationTagVersions } from './information-validation.ts';
import { validateDiscoveryReply } from './information-discovery.ts';
import { interchangeProblem } from './interchange-validation.ts';
import { validateNavigationMetadata } from './navigation-validation.ts';
import { validateToolInvocationResult } from './tool-results.ts';
import { validateImageAdmissionReply } from './image-admissions.ts';
import { validateProcReply } from './proc.ts';
import { validateHostedReply } from './hosted-tool-scopes.ts';
import { validateProviderCredentialReply } from './provider-credentials.ts';
import {
  identityStatusCheck,
  tokenReceiptCheck,
  applicationCredentialCheck,
} from './application-identities.ts';
import { fileStoreCatalogCheck } from './server-filestores.ts';
import { validateDeploymentReply } from './application-deployments.ts';
import { validateSwarmReply } from './swarm-types.ts';
import { validateRelayReply } from './relay.ts';
import {
  conversationReply,
  validateEntryMetadata,
  validateFileStoreReplies,
} from './conversation-replies.ts';
import { validateMessagingOrigins } from './messaging.ts';
import { validateReportDetails } from './report-details.ts';

import { problem, commandProblem, SHAPES } from './payload-validation.ts';
import type { ExtendedPayloads } from './catalog.ts';
import { isList } from '../binding/values.ts';
import { OPERATION_SCHEMAS } from './operation-schemas.ts';
import type { JsonValue } from './administration.ts';
import type { Operation, Payloads, Request } from './direct.ts';
import type { GoalPage, GoalStatus, Replies } from './replies.ts';

/** Generated contract vocabulary. No vendor parser or raw schema crosses the SDK. */
export interface Schema {
  readonly title?: string;
  readonly $ref?: string;
  readonly anyOf?: readonly Schema[];
  readonly const?: JsonValue;
  readonly type?: 'string' | 'number' | 'boolean' | 'null' | 'array' | 'object';
  readonly items?: Schema;
  readonly prefixItems?: readonly Schema[];
  readonly minItems?: number;
  readonly maxItems?: number;
  readonly properties?: Readonly<Record<string, Schema>>;
  readonly required?: readonly string[];
  readonly additionalProperties?: boolean | Schema;
  readonly forbidden?: boolean;
  readonly optional?: boolean;
}

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !isList(value);
}

/**
 * Validate and copy at the boundary. Input keys are allowlisted; output keys
 * unknown to this version are omitted. Extensible dictionaries retain only
 * checked values. Bounds prevent cyclic or excessively deep in-process inputs
 * as well as large JSON frames. Errors name the field, never its private value.
 */
function decode(
  schema: Schema,
  value: unknown,
  input: boolean,
  path: string,
  depth = 0,
  definitions: Readonly<Record<string, Schema>> = OPERATION_SCHEMAS.$defs,
): JsonValue | undefined {
  const fail = (): never => {
    throw new Error(`${path} does not match the operation contract`);
  };
  if (depth > 64 || schema.forbidden) return fail();
  if (schema.optional)
    return value === undefined || value === null ? undefined : fail();
  if (schema.$ref !== undefined) {
    const target = definitions[schema.$ref.replace('#/$defs/', '')];
    if (target === undefined) return fail();
    return decode(target, value, input, path, depth, definitions);
  }
  if (schema.anyOf !== undefined) {
    for (const variant of schema.anyOf) {
      try {
        return decode(variant, value, input, path, depth, definitions);
      } catch {
        /* A union accepts exactly one of its complete declared shapes. */
      }
    }
    return fail();
  }
  if ('const' in schema) return value === schema.const ? schema.const : fail();
  switch (schema.type) {
    case 'null':
      return value === null ? null : fail();
    case 'string':
      return typeof value === 'string' && value.length <= 50 * 1024 * 1024
        ? value
        : fail();
    case 'number':
      return typeof value === 'number' && Number.isFinite(value)
        ? value
        : fail();
    case 'boolean':
      return typeof value === 'boolean' ? value : fail();
    case 'array': {
      if (!isList(value)) return fail();
      const entries: readonly unknown[] = value;
      if (
        entries.length > (schema.maxItems ?? 10000) ||
        entries.length < (schema.minItems ?? 0)
      )
        return fail();
      return entries.map((entry, index) => {
        const shape = schema.prefixItems?.[index] ?? schema.items;
        if (shape === undefined) return fail();
        const decoded = decode(
          shape,
          entry,
          input,
          `${path}[${index}]`,
          depth + 1,
          definitions,
        );
        return decoded === undefined ? fail() : decoded;
      });
    }
    case 'object': {
      if (!object(value) || Object.keys(value).length > 10000) return fail();
      for (const key of schema.required ?? [])
        if (!Object.hasOwn(value, key)) return fail();
      const result: Record<string, JsonValue> = {};
      for (const [key, entry] of Object.entries(value)) {
        const known = schema.properties?.[key];
        const extra = schema.additionalProperties;
        if (known === undefined && typeof extra !== 'object') {
          if (input && extra !== true) return fail();
          if (extra !== true) continue;
        }
        if (key === '__proto__' || key === 'prototype' || key === 'constructor')
          return fail();
        // Optional undefined is accepted only when omitted. This matches JSON
        // and exactOptionalPropertyTypes rather than silently dropping a value.
        const decoded = decode(
          known ?? (typeof extra === 'object' ? extra : {}),
          entry,
          input,
          `${path}.${key}`,
          depth + 1,
          definitions,
        );
        if (decoded === undefined) return fail();
        result[key] = decoded;
      }
      return result;
    }
    default:
      // Explicit JSON extension data still rejects functions, symbols, NaN and
      // cyclic values. It is never an unchecked object cast.
      if (value === undefined) return undefined;
      if (value === null) return null;
      if (typeof value === 'string')
        return decode(
          { type: 'string' },
          value,
          input,
          path,
          depth + 1,
          definitions,
        );
      if (typeof value === 'number')
        return decode(
          { type: 'number' },
          value,
          input,
          path,
          depth + 1,
          definitions,
        );
      if (typeof value === 'boolean') return value;
      if (isList(value))
        return decode(
          { type: 'array', items: {} },
          value,
          input,
          path,
          depth + 1,
          definitions,
        );
      if (object(value))
        return decode(
          { type: 'object', additionalProperties: {} },
          value,
          input,
          path,
          depth + 1,
          definitions,
        );
      return fail();
  }
}

export function isOperation(value: string): value is Operation {
  return Object.hasOwn(OPERATION_SCHEMAS.inputs, value);
}

/** Complete conversion before transport or application code sees a request. */
export function decodePayload<K extends Operation>(
  type: K,
  value: unknown,
): Payloads[K] {
  const portableFailure = interchangeProblem(type, value);
  if (portableFailure) throw new Error(portableFailure);
  const schema = OPERATION_SCHEMAS.inputs[type];
  if (schema === undefined) throw new Error('unknown Plowshare operation');
  // This assertion is confined to the generated, recursively validated boundary.
  const decoded = decode(schema, value, true, type);
  if (!object(decoded)) throw new Error('operation payload must be an object');
  const failure =
    problem(type, decoded) ??
    // Relay's complete domain validation already ran in problem(). The schema
    // above enforced field names and types; repeating command validation would
    // synchronously rescan up to 50 MiB of text on the client's event loop.
    (Object.hasOwn(SHAPES, type) && !type.startsWith('relay.')
      ? commandProblem(type as keyof ExtendedPayloads, decoded)
      : undefined);
  if (failure !== undefined) throw new Error(failure);
  const request = { type, payload: decoded } as Request;
  if (request.type === 'information.record.report')
    validateReportDetails(request.payload);
  return decoded as Payloads[K];
}

/** Retains the discriminator/payload relation after complete boundary validation. */
export function decodeRequest(type: string, value: unknown): Request {
  if (!isOperation(type)) throw new Error('unknown Plowshare operation');
  return { type, payload: decodePayload(type, value) } as Request;
}

function acceptsNull(schema: Schema): boolean {
  // Count JSON nesting, not trusted generated graph indirections.
  if (schema.$ref) {
    const target = OPERATION_SCHEMAS.$defs[schema.$ref.replace('#/$defs/', '')];
    if (!target) throw new Error('Missing generated reply definition.');
    return acceptsNull(target);
  }
  return schema.type === 'null' || (schema.anyOf?.some(acceptsNull) ?? false);
}

export function decodeReply<K extends keyof Replies>(
  type: K,
  value: unknown,
): Replies[K] {
  const schema = OPERATION_SCHEMAS.results[type];
  if (schema === undefined)
    throw new Error('operation has no one-shot reply contract');
  const decoded = decode(
    schema,
    value === undefined && acceptsNull(schema) ? null : value,
    false,
    type,
  ) as unknown as Replies[K];
  if (
    type === 'agent.run.ex' &&
    (typeof decoded !== 'object' ||
      decoded === null ||
      !('id' in decoded) ||
      typeof decoded.id !== 'string' ||
      decoded.id.trim() === '' ||
      !('conversation' in decoded) ||
      typeof decoded.conversation !== 'string' ||
      !decoded.conversation.startsWith('cnv_'))
  )
    throw new Error(
      'External run reply needs a job identity and resolved internal conversation',
    );
  if (
    type === 'conversation.open.ex' &&
    conversationReply('conversation.open.ex', {
      code: 'OK',
      payload: decoded,
    }) === undefined
  )
    throw new Error('Invalid external conversation view');
  if (type === 'filestore.list' && !fileStoreCatalogCheck(decoded))
    throw new Error('Invalid FileStore catalogue');
  if (
    type === 'conversation.search' &&
    retrievalReply('conversation.search', decoded) === undefined
  )
    throw new Error('Invalid grouped conversation search response');
  validateActingReply(type, decoded);
  validateNavigationMetadata(type, decoded);
  validateInformationTagVersions(type, decoded);
  validateDiscoveryReply(type, decoded);
  validateProviderCredentialReply(type, decoded);
  const portableFailure = interchangeProblem(type, decoded, true);
  if (portableFailure) throw new Error(portableFailure);
  validateHostedReply(type, decoded);
  validateImageAdmissionReply(type, decoded);
  if (type === 'tool.invocation.read') validateToolInvocationResult(decoded);
  validateRelayReply(type, decoded);
  validateSwarmReply(type, decoded);
  validateDeploymentReply(type, decoded);
  if (
    (type === 'application.identity.status' && !identityStatusCheck(decoded)) ||
    (type === 'application.token.receipt' && !tokenReceiptCheck(decoded)) ||
    (type === 'application.token.issue' && !applicationCredentialCheck(decoded))
  )
    throw new Error('Invalid Application identity reply');
  validateEntryMetadata(type, decoded);
  validateProcReply(type, decoded);
  validateFileStoreReplies(type, decoded);
  validateMessagingOrigins(type, decoded);
  if (type === 'goal.list') validateGoalPage(decoded as GoalPage);
  if (type === 'goal.status') validateGoalStatus(decoded as GoalStatus);
  if (
    type === 'goal.receipt' ||
    type === 'goal.answer' ||
    type === 'goal.start' ||
    type === 'goal.sources' ||
    type === 'message.listen' ||
    type === 'message.unlisten'
  )
    validateGoalReply(type, decoded);
  return decoded;
}

/** Shared bounded boundary engine; the owning adapter supplies its generated contract. */
export function decodeContract(
  schema: Schema,
  definitions: Readonly<Record<string, Schema>>,
  value: unknown,
  name: string,
  input = true,
): JsonValue | undefined {
  return decode(schema, value, input, name, 0, definitions);
}

function validateActingReply(type: string, value: unknown): void {
  if (type === 'acting.turn.start' || type === 'acting.turn.receipt') {
    if (
      !object(value) ||
      typeof value['requestId'] !== 'string' ||
      !/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(
        value['requestId'],
      ) ||
      typeof value['conversation'] !== 'string' ||
      value['conversation'].length > 128 ||
      !/^cnv_[A-Za-z0-9]+$/.test(value['conversation']) ||
      typeof value['agent'] !== 'string' ||
      !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/.test(value['agent']) ||
      typeof value['state'] !== 'string' ||
      !['ADMITTED', 'STARTED', 'FINISHED', 'UNCERTAIN'].includes(
        value['state'],
      ) ||
      (value['job'] !== null &&
        (typeof value['job'] !== 'string' ||
          value['job'].length > 128 ||
          !/^job_[A-Za-z0-9]+$/.test(value['job']))) ||
      ((value['state'] === 'STARTED' || value['state'] === 'FINISHED') &&
        value['job'] === null)
    )
      throw new Error('Invalid acting turn receipt');
  }
  if (type === 'acting.context.open') {
    if (
      !object(value) ||
      typeof value['context'] !== 'string' ||
      !/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(value['context']) ||
      typeof value['subject'] !== 'string' ||
      !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/.test(value['subject']) ||
      typeof value['expiresAt'] !== 'string' ||
      !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(
        value['expiresAt'],
      ) ||
      !Number.isFinite(Date.parse(value['expiresAt']))
    )
      throw new Error('Invalid acting context reply');
  }
}

const goalUuid = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/u;
function goalIdentity(value: string): boolean {
  return (
    value.length === 36 &&
    goalUuid.test(value) &&
    value !== '00000000-0000-0000-0000-000000000000'
  );
}
function goalPositive(value: number, maximum = 2147483647): boolean {
  return Number.isInteger(value) && value >= 1 && value <= maximum;
}
function goalText(value: string, maximum: number): boolean {
  return value.length > 0 && value.length <= maximum && !value.includes('\0');
}
function goalInstant(value: string): boolean {
  return (
    /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/u.test(value) &&
    Number.isFinite(Date.parse(value))
  );
}
function validateGoalPage(page: GoalPage): void {
  if (
    page.goals.length > 50 ||
    new Set(page.goals.map((row) => row.goal)).size !== page.goals.length ||
    (page.next !== null && page.next !== page.goals.at(-1)?.goal)
  )
    throw new Error('Invalid Goal Agent page');
  for (const row of page.goals) validateGoalStatus(row);
}
function validateGoalStatus(status: GoalStatus): void {
  if (
    !goalIdentity(status.goal) ||
    !goalText(status.project, 1024) ||
    !/^cnv_[A-Za-z0-9]{1,124}$/u.test(status.conversation) ||
    !/^ins_[A-Za-z0-9]{1,124}$/u.test(status.instance) ||
    !goalPositive(status.version) ||
    (status.agent !== null && !goalText(status.agent, 1024)) ||
    (status.objective !== null && !goalText(status.objective, 16000)) ||
    (status.nextScheduleAt !== null && !goalInstant(status.nextScheduleAt)) ||
    status.schedules.length > 16 ||
    status.relayListeners.length > 16 ||
    (!status.readable &&
      (status.agent !== null ||
        status.objective !== null ||
        status.detail !== null ||
        status.latestResult !== null ||
        status.nextScheduleAt !== null ||
        status.schedules.length > 0 ||
        status.relayListeners.length > 0 ||
        status.moreSchedules ||
        status.moreRelayListeners))
  )
    throw new Error('Invalid Goal Agent status');
  const result = status.latestResult;
  if (
    result !== null &&
    (!/^job_[A-Za-z0-9]{1,124}$/u.test(result.job) ||
      !goalPositive(result.turn) ||
      !goalText(result.summary, 16000) ||
      result.evidence.length < 1 ||
      result.evidence.length > 12 ||
      result.evidence.some((item) => !goalText(item, 500)))
  )
    throw new Error('Invalid Goal Agent result');
  for (const wake of status.schedules) {
    if (
      !goalText(wake.schedule, 512) ||
      !goalIdentity(wake.generation) ||
      (wake.nextFireAt !== null && !goalInstant(wake.nextFireAt))
    )
      throw new Error('Invalid Goal Agent schedule wake');
  }
  for (const wake of status.relayListeners) {
    if (
      !goalIdentity(wake.binding) ||
      !goalIdentity(wake.generation) ||
      (wake.topic !== null &&
        (wake.topic.length > 1024 ||
          !/^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$/u.test(wake.topic))) ||
      (wake.pendingBatch !== null &&
        (!goalIdentity(wake.pendingBatch.id) ||
          !goalPositive(wake.pendingBatch.memberCount, 16) ||
          !goalInstant(wake.pendingBatch.deadline)))
    )
      throw new Error('Invalid Goal Agent Relay listener');
  }
}

function validateGoalReply(
  type:
    | 'goal.answer'
    | 'goal.receipt'
    | 'goal.start'
    | 'goal.sources'
    | 'message.listen'
    | 'message.unlisten',
  value: unknown,
): void {
  const uuid = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/u;
  const topic = /^[a-z][a-z0-9]*([._-][a-z0-9]+)*$/u;
  if (
    !object(value) ||
    typeof value['goal'] !== 'string' ||
    !uuid.test(value['goal'])
  )
    throw new Error('Invalid Goal Agent identity');
  const validTopic = (name: unknown) =>
    typeof name === 'string' && name.length <= 1024 && topic.test(name);
  const validUuid = (candidate: unknown) =>
    typeof candidate === 'string' && uuid.test(candidate);
  if (type === 'goal.answer') {
    const ids = [value['goal'], value['question'], value['requestId']];
    if (
      !ids.every(
        (id) => validUuid(id) && id !== '00000000-0000-0000-0000-000000000000',
      ) ||
      typeof value['conversation'] !== 'string' ||
      !/^cnv_[A-Za-z0-9]{1,124}$/u.test(value['conversation'])
    )
      throw new Error('Invalid Goal Agent answer receipt');
  } else if (type === 'goal.start' || type === 'goal.receipt') {
    const version = value['version'];
    if (
      !validUuid(value['requestId']) ||
      typeof value['instance'] !== 'string' ||
      !/^ins_[A-Za-z0-9]{1,123}$/u.test(value['instance']) ||
      typeof value['conversation'] !== 'string' ||
      !/^cnv_[A-Za-z0-9]{1,123}$/u.test(value['conversation']) ||
      typeof version !== 'number' ||
      !Number.isSafeInteger(version) ||
      version < 1
    )
      throw new Error('Invalid Goal Agent launch receipt');
  } else if (type === 'message.listen') {
    if (
      !validUuid(value['binding']) ||
      !validUuid(value['generation']) ||
      !validTopic(value['topic'])
    )
      throw new Error('Invalid Goal Agent listener receipt');
  } else if (type === 'message.unlisten') {
    const version = value['version'];
    const zero = '00000000-0000-0000-0000-000000000000';
    if (
      !validUuid(value['binding']) ||
      !validUuid(value['requestId']) ||
      [value['goal'], value['binding'], value['requestId']].includes(zero) ||
      typeof version !== 'number' ||
      !Number.isInteger(version) ||
      version < 1 ||
      version > 2147483647
    )
      throw new Error('Invalid Goal Agent listener removal receipt');
  } else {
    const items: unknown = value['sources'];
    if (
      !Array.isArray(items) ||
      items.length > 100 ||
      items.some(
        (source: unknown) =>
          !object(source) ||
          !validTopic(source['topic']) ||
          !validUuid(source['generation']),
      )
    )
      throw new Error('Invalid Goal Agent source page');
  }
}
