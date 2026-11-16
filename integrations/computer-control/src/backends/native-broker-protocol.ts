import type {
  NativeAcquisition,
  NativeActionResult,
  NativeControlCode,
  NativeControlPolicy,
  NativeFocusRequest,
  NativeLease,
  NativeObservation,
  NativeObserveRequest,
  NativePerformRequest,
  NativePermissions,
  NativeState,
  NativeWindows,
} from '../native-control.ts';
import {
  NativeControlError,
  decodeNativeAcquisition,
  decodeNativeActionResult,
  decodeNativeControlPolicy,
  decodeNativeFocusRequest,
  decodeNativeLease,
  decodeNativeObservation,
  decodeNativeObserveRequest,
  decodeNativePerformRequest,
  decodeNativePermissions,
  decodeNativeWindows,
} from '../native-control.ts';
import { computerId } from '../contracts.ts';
import { isAbsolute } from 'node:path';

export const NATIVE_BROKER_VERSION = 'computer-control-native/2';
export const NATIVE_BROKER_MAX_REQUEST_BYTES = 65_536;
export const NATIVE_BROKER_MAX_REPLY_BYTES = 2_097_152;
export const NATIVE_BROKER_MAX_PROCESS_VISITS = 256;

/**
 * Configuration is validated by the Node composition boundary and again by the
 * helper codec. The host-wide kernel resource name is never configurable here.
 * No Plowshare bearer, original text/result journal or model-supplied PID crosses
 * this channel. The directory supplements, rather than defines, OS exclusion.
 */
export interface NativeBrokerConfiguration {
  readonly ownershipDirectory: string;
  readonly target: {
    readonly targetId: string;
    readonly label: string;
    readonly surfaceId: string;
  };
  readonly control: NativeControlPolicy;
  readonly maxWindows: number;
  readonly maxNodes: number;
  readonly maxVisitedNodes: number;
  readonly cleanupBudgetMs: number;
}

/** Command body only; transport owns version, UUID requestId and remaining timeoutMs. */
export type NativeBrokerRequest =
  | { readonly operation: 'preflight' }
  | {
      readonly operation: 'acquire';
      readonly lease: NativeLease;
      readonly configuration: NativeBrokerConfiguration;
    }
  | { readonly operation: 'windows'; readonly lease: NativeLease }
  | {
      readonly operation: 'observe';
      readonly lease: NativeLease;
      readonly request: NativeObserveRequest;
    }
  | {
      readonly operation: 'focus';
      readonly lease: NativeLease;
      readonly request: NativeFocusRequest;
    }
  | {
      readonly operation: 'perform';
      readonly lease: NativeLease;
      readonly request: NativePerformRequest;
    }
  | { readonly operation: 'stop' };

export type NativeBrokerRequestEnvelope = NativeBrokerRequest & {
  readonly version: typeof NATIVE_BROKER_VERSION;
  readonly requestId: string;
  readonly timeoutMs: number;
};

/** One-way cancellation has no reply and never starts/repeats the original command. */
export interface NativeBrokerCancel {
  readonly version: typeof NATIVE_BROKER_VERSION;
  readonly requestId: string;
  readonly operation: 'cancel';
  readonly targetRequestId: string;
}

export interface NativeBrokerPreflight {
  readonly permissions: NativePermissions;
  readonly availability: NativeState['availability'];
  readonly secureInput: NativeState['secureInput'];
  readonly globalFence: 'clear' | 'held' | 'unknown';
}

/**
 * Proof about this broker's owned work only. The helper cannot assert that it
 * has exited while writing a reply. Node must combine this validated proof with
 * actual process close before returning NativeCleanupResult.helperStopped=true.
 */
export interface NativeBrokerCleanupProof {
  readonly physicalSettlement: 'settled' | 'uncertain';
  readonly heldInputsReleased: boolean;
}

/** A protocol error code is not evidence that input was never dispatched. */
export interface NativeBrokerFailure {
  readonly code: NativeControlCode;
  readonly physicalSettlement: 'settled' | 'uncertain';
}

interface ReplyHeader {
  readonly version: typeof NATIVE_BROKER_VERSION;
  readonly requestId: string;
}

/**
 * Validated replies returned from the owning codec. On the private JSON wire an
 * observation's image uses bytesBase64 instead of bytes. The codec strictly
 * bounds/decodes it, checks image geometry/signature, then returns Uint8Array.
 * Base64 never reaches Relay, the invocation journal or a model-facing result.
 */
export type NativeBrokerReply = ReplyHeader &
  (
    | {
        readonly operation: 'preflight';
        readonly result: NativeBrokerPreflight;
      }
    | { readonly operation: 'acquire'; readonly result: NativeAcquisition }
    | { readonly operation: 'windows'; readonly result: NativeWindows }
    | { readonly operation: 'observe'; readonly result: NativeObservation }
    | {
        readonly operation: 'focus' | 'perform';
        readonly result: NativeActionResult;
      }
    | { readonly operation: 'stop'; readonly result: NativeBrokerCleanupProof }
    | { readonly operation: 'failure'; readonly result: NativeBrokerFailure }
  );

/** Raw JSON stays at the protocol boundary; callers receive a complete typed DTO. */
export type NativeBrokerReplyDecoder = (value: unknown) => NativeBrokerReply;

function invalid(): never {
  throw new NativeControlError('INVALID_ARGUMENT');
}
function row(
  value: unknown,
  fields: readonly string[],
): Readonly<Record<string, unknown>> {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    invalid();
  const record = value as Readonly<Record<string, unknown>>;
  if (Object.keys(record).some((key) => !fields.includes(key))) invalid();
  return record;
}
function integer(value: unknown, maximum: number): number {
  if (
    typeof value !== 'number' ||
    !Number.isSafeInteger(value) ||
    value < 1 ||
    value > maximum
  )
    invalid();
  return value;
}
function choice<T extends string>(value: unknown, values: readonly T[]): T {
  if (typeof value !== 'string' || !values.includes(value as T)) invalid();
  return value as T; // The complete finite allowlist above establishes the variant.
}
const codes: readonly NativeControlCode[] = [
  'INVALID_ARGUMENT',
  'CONTROL_DISABLED',
  'TARGET_NOT_ALLOWED',
  'NO_LEASE',
  'BUSY',
  'FENCED',
  'TARGET_GONE',
  'PERMISSION_DENIED',
  'SESSION_LOCKED',
  'SESSION_INACTIVE',
  'SESSION_UNKNOWN',
  'SECURE_INPUT',
  'INTERFERENCE',
  'STALE_OBSERVATION',
  'FOCUS_MISMATCH',
  'NOT_EDITABLE',
  'SENSITIVE_TARGET',
  'AX_UNAVAILABLE',
  'UNSUPPORTED_ACTION',
  'AMBIGUOUS_WINDOW',
  'INPUT_FAILED',
  'OBSERVATION_FAILED',
  'DEADLINE_EXPIRED',
  'CLEANUP_UNCERTAIN',
];

export function decodeNativeBrokerConfiguration(
  value: unknown,
): NativeBrokerConfiguration {
  const config = row(value, [
    'ownershipDirectory',
    'target',
    'control',
    'maxWindows',
    'maxNodes',
    'maxVisitedNodes',
    'cleanupBudgetMs',
  ]);
  const directory = config['ownershipDirectory'];
  if (
    typeof directory !== 'string' ||
    directory.length > 4096 ||
    !isAbsolute(directory) ||
    Array.from(directory).some(
      (character) => (character.codePointAt(0) ?? 0) < 32,
    )
  )
    invalid();
  const target = row(config['target'], ['targetId', 'label', 'surfaceId']);
  const surfaceId = target['surfaceId'];
  if (
    typeof surfaceId !== 'string' ||
    !/^display:[1-9][0-9]{0,9}$/u.test(surfaceId) ||
    Number(surfaceId.slice(8)) > 4294967295
  )
    invalid();
  const maxNodes = integer(config['maxNodes'], 200),
    maxVisitedNodes = integer(config['maxVisitedNodes'], 10000);
  if (maxVisitedNodes < maxNodes) invalid();
  return Object.freeze({
    ownershipDirectory: directory,
    target: Object.freeze({
      targetId: computerId(target['targetId']),
      label: computerId(target['label']),
      surfaceId,
    }),
    control: decodeNativeControlPolicy(config['control']),
    maxWindows: integer(config['maxWindows'], 32),
    maxNodes,
    maxVisitedNodes,
    cleanupBudgetMs: integer(config['cleanupBudgetMs'], 300000),
  });
}

/** Strict outgoing command validation runs before transport writes any bytes. */
export function decodeNativeBrokerRequest(value: unknown): NativeBrokerRequest {
  const command = row(value, [
    'operation',
    'lease',
    'configuration',
    'request',
  ]);
  switch (command['operation']) {
    case 'preflight':
    case 'stop':
      row(value, ['operation']);
      return Object.freeze({ operation: command['operation'] });
    case 'acquire': {
      row(value, ['operation', 'lease', 'configuration']);
      const lease = decodeNativeLease(command['lease']),
        configuration = decodeNativeBrokerConfiguration(
          command['configuration'],
        );
      if (lease.targetId !== configuration.target.targetId) invalid();
      return Object.freeze({ operation: 'acquire', lease, configuration });
    }
    case 'windows':
      row(value, ['operation', 'lease']);
      return Object.freeze({
        operation: 'windows',
        lease: decodeNativeLease(command['lease']),
      });
    case 'observe':
      row(value, ['operation', 'lease', 'request']);
      return Object.freeze({
        operation: 'observe',
        lease: decodeNativeLease(command['lease']),
        request: decodeNativeObserveRequest(command['request']),
      });
    case 'focus':
      row(value, ['operation', 'lease', 'request']);
      return Object.freeze({
        operation: 'focus',
        lease: decodeNativeLease(command['lease']),
        request: decodeNativeFocusRequest(command['request']),
      });
    case 'perform':
      row(value, ['operation', 'lease', 'request']);
      return Object.freeze({
        operation: 'perform',
        lease: decodeNativeLease(command['lease']),
        request: decodeNativePerformRequest(command['request']),
      });
    default:
      return invalid();
  }
}

function observation(value: unknown): NativeObservation {
  const observed = row(value, ['image', 'state', 'snapshot']);
  const image = row(observed['image'], [
    'bytesBase64',
    'mimeType',
    'width',
    'height',
    'transform',
    'capturedAt',
    'layoutGeneration',
  ]);
  const encoded = image['bytesBase64'];
  if (
    image['mimeType'] !== 'image/png' ||
    typeof encoded !== 'string' ||
    encoded.length === 0 ||
    encoded.length > 1398104 ||
    !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/u.test(
      encoded,
    )
  )
    invalid();
  const bytes = new Uint8Array(Buffer.from(encoded, 'base64'));
  if (
    bytes.byteLength > 1048576 ||
    Buffer.from(bytes).toString('base64') !== encoded
  )
    invalid();
  return decodeNativeObservation({
    image: {
      bytes,
      mimeType: image['mimeType'],
      width: image['width'],
      height: image['height'],
      transform: image['transform'],
      capturedAt: image['capturedAt'],
      layoutGeneration: image['layoutGeneration'],
    },
    state: observed['state'],
    snapshot: observed['snapshot'],
  });
}
function action(value: unknown): NativeActionResult {
  const result = row(value, ['delivery', 'observation']);
  const observed = row(result['observation'], ['state', 'value', 'code']);
  if (observed['state'] === 'CAPTURED') row(observed, ['state', 'value']);
  return decodeNativeActionResult({
    delivery: result['delivery'],
    observation:
      observed['state'] === 'CAPTURED'
        ? { state: 'CAPTURED', value: observation(observed['value']) }
        : observed,
  });
}

/** Convert the complete private wire reply into a validated immutable domain DTO. */
export function decodeNativeBrokerReply(value: unknown): NativeBrokerReply {
  const reply = row(value, ['version', 'requestId', 'operation', 'result']);
  const requestId = reply['requestId'];
  if (
    reply['version'] !== NATIVE_BROKER_VERSION ||
    typeof requestId !== 'string' ||
    !/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/u.test(requestId)
  )
    invalid();
  const header = { version: NATIVE_BROKER_VERSION, requestId } as const;
  switch (reply['operation']) {
    case 'preflight': {
      const result = row(reply['result'], [
        'permissions',
        'availability',
        'secureInput',
        'globalFence',
      ]);
      const permissions = decodeNativePermissions(result['permissions']);
      const availability = choice(result['availability'], [
        'ready',
        'locked',
        'inactive',
        'unknown',
        'unavailable',
      ]);
      const secureInput = choice(result['secureInput'], [
        'clear',
        'active',
        'unknown',
      ]);
      if (
        availability === 'ready' &&
        (Object.values(permissions).some((allowed) => !allowed) ||
          secureInput !== 'clear')
      )
        invalid();
      return Object.freeze({
        ...header,
        operation: 'preflight',
        result: Object.freeze({
          permissions,
          availability,
          secureInput,
          globalFence: choice(result['globalFence'], [
            'clear',
            'held',
            'unknown',
          ]),
        }),
      });
    }
    case 'acquire':
      return Object.freeze({
        ...header,
        operation: 'acquire',
        result: decodeNativeAcquisition(reply['result']),
      });
    case 'windows':
      return Object.freeze({
        ...header,
        operation: 'windows',
        result: decodeNativeWindows(reply['result']),
      });
    case 'observe':
      return Object.freeze({
        ...header,
        operation: 'observe',
        result: observation(reply['result']),
      });
    case 'focus':
    case 'perform':
      return Object.freeze({
        ...header,
        operation: reply['operation'],
        result: action(reply['result']),
      });
    case 'stop': {
      const result = row(reply['result'], [
        'physicalSettlement',
        'heldInputsReleased',
      ]);
      const physicalSettlement = choice(result['physicalSettlement'], [
          'settled',
          'uncertain',
        ]),
        heldInputsReleased = result['heldInputsReleased'];
      if (
        typeof heldInputsReleased !== 'boolean' ||
        (physicalSettlement === 'settled' && !heldInputsReleased)
      )
        invalid();
      return Object.freeze({
        ...header,
        operation: 'stop',
        result: Object.freeze({ physicalSettlement, heldInputsReleased }),
      });
    }
    case 'failure': {
      const result = row(reply['result'], ['code', 'physicalSettlement']);
      return Object.freeze({
        ...header,
        operation: 'failure',
        result: Object.freeze({
          code: choice(result['code'], codes),
          physicalSettlement: choice(result['physicalSettlement'], [
            'settled',
            'uncertain',
          ]),
        }),
      });
    }
    default:
      return invalid();
  }
}

export const nativeBrokerCodec = Object.freeze({
  decodeRequest: decodeNativeBrokerRequest,
  decodeReply: decodeNativeBrokerReply,
});
