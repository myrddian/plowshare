import { interchangeProblem } from './interchange-validation.ts';
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
  validateEntryMetadata,
  validateFileStoreReplies,
} from './conversation-replies.ts';
import { validateMessagingOrigins } from './messaging.ts';
import { validateReportDetails } from './report-details.ts';
import {
  validateExternalRequest,
  validateExternalResponse,
} from './external-validation.ts';
import { problem, commandProblem, SHAPES } from './payload-validation.ts';
import type { ExtendedPayloads } from './catalog.ts';
import { isList } from '../binding/values.ts';
import { OPERATION_SCHEMAS } from './operation-schemas.ts';
import type { JsonValue } from './administration.ts';
import type { Operation, Payloads, Request } from './direct.ts';
import type { Replies } from './replies.ts';

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
  validateExternalRequest(request);
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
  if (type === 'filestore.list' && !fileStoreCatalogCheck(decoded))
    throw new Error('Invalid FileStore catalogue');
  validateActingReply(type, decoded);
  validateProviderCredentialReply(type, decoded);
  const portableFailure = interchangeProblem(type, decoded, true);
  if (portableFailure) throw new Error(portableFailure);
  validateHostedReply(type, decoded);
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
  validateExternalResponse({ type, payload: decoded } as Parameters<
    typeof validateExternalResponse
  >[0]);
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
