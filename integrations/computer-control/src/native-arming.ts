import type { OperationDeadline } from './deadline.ts';
import { computerId } from './contracts.ts';
import { isoTimestamp } from './deadline.ts';
import type { NativeControlBackend, NativeLease } from './native-control.ts';
import { NativeControlError, decodeNativeLease } from './native-control.ts';

/** Host-local consent, never a Relay tool, model argument or replacement for tool/application grants. */
export interface NativeArmRequest {
  /** Stable host intent identity. An uncertain request is reconciled by status, never replayed. */
  readonly requestId: string;
  /** Granted lifetime starts at the local gesture, not at request submission; never renewed implicitly. */
  readonly durationMs: number;
}
export interface NativeArmBinding {
  readonly lease: NativeLease;
  /** Actual helper process epoch, distinct from the provider/session generation. */
  readonly helperGeneration: string;
}
export type NativeArmRevocationReason =
  | 'HOST_REVOKED'
  | 'LEASE_CLOSED'
  | 'SESSION_CHANGED'
  | 'PERMISSION_CHANGED'
  | 'DISPLAY_CHANGED'
  | 'APPLICATION_CHANGED'
  | 'POLICY_CHANGED'
  | 'INTERFERENCE'
  | 'SECURE_INPUT'
  | 'WITNESS_FAILED'
  | 'EXPIRED'
  | 'HELPER_STOPPED';

/**
 * Describes a scoped GUI-routing/compositor witness, not universal screen-unlocked
 * state or human/hardware attestation. The nonce is not a credential or an arming
 * answer: knowing its pixels never grants authority; local gesture and exact
 * owned-view/tag evidence remain mandatory.
 *
 * Structured nonces, event tags and raw private witness captures are never
 * exported. Normal model captures mask exact owned-window regions and refuse
 * observed geometry/display/compositor/Spaces uncertainty. This is a qualified
 * normal-window envelope, not universal pixel absence during OS animations.
 */
export interface NativeArmProof {
  readonly kind: 'local-ui-live-route/1';
  /** Refreshing changes this epoch, never the private grant's stable arm generation. */
  readonly proofEpoch: string;
  readonly state: 'fresh' | 'refresh-required';
  readonly verifiedAt: string;
  readonly displayGeneration: string;
  /** Opaque binding to the bounded exact allowed process/launch incarnations. */
  readonly applicationGeneration: string;
  readonly policyFingerprint: string;
}
export type NativeArmStatus =
  | { readonly state: 'UNARMED'; readonly binding: NativeArmBinding }
  | {
      readonly state: 'PENDING';
      readonly binding: NativeArmBinding;
      readonly requestId: string;
      readonly challengeId: string;
      readonly requestedAt: string;
      /** Separate <=30s local-decision expiry; request itself completes promptly. */
      readonly expiresAt: string;
    }
  | {
      readonly state: 'ARMED';
      readonly binding: NativeArmBinding;
      readonly requestId: string;
      readonly challengeId: string;
      /** Stable throughout this private grant; the model receives a separate interaction epoch. */
      readonly armGeneration: string;
      readonly armedAt: string;
      readonly expiresAt: string;
      readonly proof: NativeArmProof;
    }
  | {
      readonly state: 'REVOKED';
      readonly binding: NativeArmBinding;
      readonly requestId: string | null;
      readonly armGeneration: string | null;
      readonly revokedAt: string;
      readonly reason: NativeArmRevocationReason;
    };

/**
 * Host-only port on a controlled native backend. A provider's host adapter first
 * resolves its current owner-bound SessionReference to this private exact lease.
 * Wrong owner, account/project/run/provider generation, target or lease causes
 * zero helper effects. Do not disclose these DTOs to model content or tool journals.
 *
 * request() returns PENDING within its finite interactive deadline and presents
 * at most one helper-owned local confirmation surface. No remote confirm exists.
 * A second request during PENDING/ARMED refuses rather than replacing/extending
 * that intent; explicit revoke followed by a new local request is required.
 * PENDING expires independently within30s; an explicit local gesture starts a
 * grant lasting<=30s. status() never rearms or extends either lifetime. The helper
 * uses monotonic time; ISO fields are UI diagnostics, never client authority.
 *
 * ARMED denotes a scoped grant, not unconditional input readiness. A stale idle
 * witness may be refreshed on demand within the same grant without moving an
 * active drag/cursor. Active input requires a compositor witness refreshed at
 * most every100ms, step<=200ms, proof age<=250ms and fresh annotated target-PID
 * evidence. At most one compositor refresh is physically in flight; timeout
 * revokes and retains ownership until it settles, including during stop.
 * Helper-owned panels/processes are never discoverable/actionable model targets,
 * even if accidentally allowlisted. Only windows wholly within the selected
 * display are eligible. Negative witness, tap/session/sleep/permission/secure-input change or
 * interference irreversibly revokes the grant and cancels its active work.
 *
 * revoke() uses the helper's prompt cancellation/control lane, never waits behind
 * normal input. Revocation is not proof of held-input release: existing native
 * cleanup/UNKNOWN/physical-fence contracts remain authoritative. Close, stop and
 * host owner/context switches revoke; helper restart cannot inherit a grant.
 */
export interface NativeArming {
  request(
    lease: NativeLease,
    request: NativeArmRequest,
    deadline: OperationDeadline,
  ): Promise<NativeArmStatus>;
  status(
    lease: NativeLease,
    deadline: OperationDeadline,
  ): Promise<NativeArmStatus>;
  revoke(
    lease: NativeLease,
    deadline: OperationDeadline,
  ): Promise<NativeArmStatus>;
}

/** Additive host capability; observation/browser/older native backends remain unchanged. */
export interface NativeArmingBackend extends NativeControlBackend {
  readonly nativeArming: NativeArming;
}

function invalid(): never {
  throw new NativeControlError('INVALID_ARGUMENT');
}
function object(
  value: unknown,
  fields: readonly string[],
): Readonly<Record<string, unknown>> {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    invalid();
  const result = value as Readonly<Record<string, unknown>>;
  if (Object.keys(result).some((key) => !fields.includes(key))) invalid();
  return result;
}
function identifier(value: unknown): string {
  if (
    typeof value !== 'string' ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u.test(
      value,
    )
  )
    invalid();
  return value;
}
function timestamp(value: unknown): string {
  try {
    return isoTimestamp(value);
  } catch {
    return invalid();
  }
}
function binding(value: unknown): NativeArmBinding {
  const row = object(value, ['lease', 'helperGeneration']);
  const lease = Object.freeze(decodeNativeLease(row['lease'])),
    helperGeneration = identifier(row['helperGeneration']);
  if (lease.generation === helperGeneration) invalid();
  return Object.freeze({ lease, helperGeneration });
}
function instant(value: string): bigint {
  const fraction = /\.(\d+)Z$/u.exec(value)?.[1] ?? '';
  // Date truncates to milliseconds; retain public ISO instant nanosecond precision.
  return (
    BigInt(Date.parse(value)) * 1_000_000n +
    BigInt(fraction.padEnd(9, '0').slice(3))
  );
}
function interval(start: string, end: string): void {
  const duration = instant(end) - instant(start);
  if (duration <= 0n || duration > 30_000_000_000n) invalid();
}
export function decodeNativeArmRequest(value: unknown): NativeArmRequest {
  const row = object(value, ['requestId', 'durationMs']);
  const durationMs = row['durationMs'];
  if (
    typeof durationMs !== 'number' ||
    !Number.isSafeInteger(durationMs) ||
    durationMs < 1 ||
    durationMs > 30_000
  )
    invalid();
  return Object.freeze({ requestId: identifier(row['requestId']), durationMs });
}
export function decodeNativeArmProof(value: unknown): NativeArmProof {
  const row = object(value, [
    'kind',
    'proofEpoch',
    'state',
    'verifiedAt',
    'displayGeneration',
    'applicationGeneration',
    'policyFingerprint',
  ]);
  if (
    row['kind'] !== 'local-ui-live-route/1' ||
    (row['state'] !== 'fresh' && row['state'] !== 'refresh-required')
  )
    invalid();
  const fingerprint = row['policyFingerprint'];
  if (typeof fingerprint !== 'string' || !/^[0-9a-f]{64}$/u.test(fingerprint))
    invalid();
  return Object.freeze({
    kind: row['kind'],
    proofEpoch: identifier(row['proofEpoch']),
    state: row['state'],
    verifiedAt: timestamp(row['verifiedAt']),
    displayGeneration: computerId(row['displayGeneration']),
    applicationGeneration: computerId(row['applicationGeneration']),
    policyFingerprint: fingerprint,
  });
}
export function decodeNativeArmStatus(value: unknown): NativeArmStatus {
  const initial = object(value, [
    'state',
    'binding',
    'requestId',
    'challengeId',
    'requestedAt',
    'expiresAt',
    'armGeneration',
    'armedAt',
    'proof',
    'revokedAt',
    'reason',
  ]);
  const common = binding(initial['binding']);
  switch (initial['state']) {
    case 'UNARMED': {
      object(value, ['state', 'binding']);
      return Object.freeze({ state: 'UNARMED', binding: common });
    }
    case 'PENDING': {
      const row = object(value, [
        'state',
        'binding',
        'requestId',
        'challengeId',
        'requestedAt',
        'expiresAt',
      ]);
      const requestedAt = timestamp(row['requestedAt']),
        expiresAt = timestamp(row['expiresAt']);
      interval(requestedAt, expiresAt);
      return Object.freeze({
        state: 'PENDING',
        binding: common,
        requestId: identifier(row['requestId']),
        challengeId: identifier(row['challengeId']),
        requestedAt,
        expiresAt,
      });
    }
    case 'ARMED': {
      const row = object(value, [
        'state',
        'binding',
        'requestId',
        'challengeId',
        'armGeneration',
        'armedAt',
        'expiresAt',
        'proof',
      ]);
      const armedAt = timestamp(row['armedAt']),
        expiresAt = timestamp(row['expiresAt']);
      interval(armedAt, expiresAt);
      const proof = decodeNativeArmProof(row['proof']);
      if (
        instant(proof.verifiedAt) < instant(armedAt) ||
        instant(proof.verifiedAt) >= instant(expiresAt)
      )
        invalid();
      return Object.freeze({
        state: 'ARMED',
        binding: common,
        requestId: identifier(row['requestId']),
        challengeId: identifier(row['challengeId']),
        armGeneration: identifier(row['armGeneration']),
        armedAt,
        expiresAt,
        proof,
      });
    }
    case 'REVOKED': {
      const row = object(value, [
        'state',
        'binding',
        'requestId',
        'armGeneration',
        'revokedAt',
        'reason',
      ]);
      const reason = row['reason'];
      if (
        reason !== 'HOST_REVOKED' &&
        reason !== 'LEASE_CLOSED' &&
        reason !== 'SESSION_CHANGED' &&
        reason !== 'PERMISSION_CHANGED' &&
        reason !== 'DISPLAY_CHANGED' &&
        reason !== 'APPLICATION_CHANGED' &&
        reason !== 'POLICY_CHANGED' &&
        reason !== 'INTERFERENCE' &&
        reason !== 'SECURE_INPUT' &&
        reason !== 'WITNESS_FAILED' &&
        reason !== 'EXPIRED' &&
        reason !== 'HELPER_STOPPED'
      )
        invalid();
      if (row['armGeneration'] !== null && row['requestId'] === null) invalid();
      return Object.freeze({
        state: 'REVOKED',
        binding: common,
        requestId:
          row['requestId'] === null ? null : identifier(row['requestId']),
        armGeneration:
          row['armGeneration'] === null
            ? null
            : identifier(row['armGeneration']),
        revokedAt: timestamp(row['revokedAt']),
        reason,
      });
    }
    default:
      return invalid();
  }
}
