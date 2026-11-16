import type { OperationDeadline } from './deadline.ts';
import { isoTimestamp } from './deadline.ts';
import type { AffineTransform } from './geometry.ts';
import { validateTransform } from './geometry.ts';

export type BackendErrorCode =
  'PERMISSION_DENIED' | 'TARGET_GONE' | 'CAPTURE_FAILED' | 'DEADLINE_EXPIRED';

/** Fixed, actionable codes cross the helper boundary; native payloads never become diagnostics. */
export class BackendError extends Error {
  constructor(
    readonly code: BackendErrorCode,
    readonly physicalSettlement: 'settled' | 'uncertain' = 'settled',
  ) {
    super(code);
    this.name = 'BackendError';
  }
}

export interface ComputerOwner {
  readonly account: string;
  readonly project: string;
  readonly run: string;
  readonly provider: string;
}

export type ComputerCapability =
  'capture' | 'structured' | 'pointer' | 'keyboard';
export interface ComputerSurface {
  readonly id: string;
  readonly label: string;
}
export interface ComputerTarget {
  readonly targetId: string;
  readonly backend: 'browser' | 'macos';
  readonly label: string;
  readonly surfaces: readonly ComputerSurface[];
  readonly capabilities: readonly ComputerCapability[];
}

export interface CaptureRequest {
  readonly targetId: string;
  readonly surfaceId: string;
}
export interface CapturedImage {
  readonly bytes: Uint8Array;
  readonly mimeType: 'image/png' | 'image/jpeg' | 'image/webp';
  readonly width: number;
  readonly height: number;
  readonly transform: AffineTransform;
  readonly capturedAt: string;
  readonly layoutGeneration: string;
}
export interface CaptureLimits {
  readonly maxBytes: number;
  readonly maxDimension: number;
}
export const DEFAULT_CAPTURE_LIMITS: CaptureLimits = Object.freeze({
  maxBytes: 1_048_576,
  maxDimension: 1600,
});

/**
 * Backends receive an already-selected configured target and one shared remaining deadline.
 * They own cancellation until physical capture settles. No credentials or vendor objects cross here.
 */
export interface ComputerBackend {
  targets(deadline: OperationDeadline): Promise<readonly ComputerTarget[]>;
  capture(
    request: CaptureRequest,
    deadline: OperationDeadline,
  ): Promise<CapturedImage>;
}

export interface SessionReference {
  readonly sessionId: string;
  readonly generation: string;
}
export interface OpenArguments {
  readonly targetId: string;
}
export interface ObserveArguments extends SessionReference {
  readonly surfaceId: string;
}

function object(
  value: unknown,
  keys: readonly string[],
): Readonly<Record<string, unknown>> {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    throw new Error('Invalid computer-control object');
  const row: Readonly<Record<string, unknown>> = value as Readonly<
    Record<string, unknown>
  >;
  if (Object.keys(row).some((key) => !keys.includes(key)))
    throw new Error('Unknown computer-control field');
  return row;
}

/** Identifiers and visible labels are bounded independently of transport-envelope bounds. */
export function computerId(value: unknown): string {
  if (
    typeof value !== 'string' ||
    value.length < 1 ||
    value.length > 256 ||
    value.trim() !== value ||
    Array.from(value).some((character) => {
      const code = character.codePointAt(0) ?? 0;
      return (
        code < 32 ||
        (code >= 127 && code <= 159) ||
        code === 0x2028 ||
        code === 0x2029
      );
    })
  )
    throw new Error('Invalid computer-control identifier');
  return value;
}

export function decodeOwner(value: unknown): ComputerOwner {
  const row = object(value, ['account', 'project', 'run', 'provider']);
  return Object.freeze({
    account: computerId(row['account']),
    project: computerId(row['project']),
    run: computerId(row['run']),
    provider: computerId(row['provider']),
  });
}

export function decodeTargetsArguments(
  value: unknown,
): Readonly<Record<string, never>> {
  object(value, []);
  return Object.freeze({});
}

export function decodeOpenArguments(value: unknown): OpenArguments {
  const row = object(value, ['targetId']);
  return Object.freeze({ targetId: computerId(row['targetId']) });
}

export function decodeCloseArguments(value: unknown): SessionReference {
  const row = object(value, ['sessionId', 'generation']);
  return Object.freeze({
    sessionId: computerId(row['sessionId']),
    generation: computerId(row['generation']),
  });
}

export function decodeObserveArguments(value: unknown): ObserveArguments {
  const row = object(value, ['sessionId', 'generation', 'surfaceId']);
  return Object.freeze({
    sessionId: computerId(row['sessionId']),
    generation: computerId(row['generation']),
    surfaceId: computerId(row['surfaceId']),
  });
}

export function decodeCaptureRequest(value: unknown): CaptureRequest {
  const row = object(value, ['targetId', 'surfaceId']);
  return Object.freeze({
    targetId: computerId(row['targetId']),
    surfaceId: computerId(row['surfaceId']),
  });
}

export function decodeTargets(value: unknown): readonly ComputerTarget[] {
  if (!Array.isArray(value) || value.length > 32)
    throw new Error('Invalid computer target collection');
  const targets = value.map((item: unknown): ComputerTarget => {
    const row = object(item, [
      'targetId',
      'backend',
      'label',
      'surfaces',
      'capabilities',
    ]);
    const backend = row['backend'];
    if (backend !== 'browser' && backend !== 'macos')
      throw new Error('Unsupported backend');
    const rawSurfaces = row['surfaces'];
    if (
      !Array.isArray(rawSurfaces) ||
      rawSurfaces.length < 1 ||
      rawSurfaces.length > 32
    )
      throw new Error('Invalid computer surface collection');
    const surfaces = rawSurfaces.map((item: unknown): ComputerSurface => {
      const surface = object(item, ['id', 'label']);
      return Object.freeze({
        id: computerId(surface['id']),
        label: computerId(surface['label']),
      });
    });
    if (new Set(surfaces.map((surface) => surface.id)).size !== surfaces.length)
      throw new Error('Duplicate computer surface');
    const rawCapabilities = row['capabilities'];
    if (!Array.isArray(rawCapabilities) || rawCapabilities.length > 4)
      throw new Error('Invalid computer capability collection');
    const capabilities = rawCapabilities.map(
      (item: unknown): ComputerCapability => {
        if (
          item !== 'capture' &&
          item !== 'structured' &&
          item !== 'pointer' &&
          item !== 'keyboard'
        )
          throw new Error('Unknown computer capability');
        return item;
      },
    );
    if (new Set(capabilities).size !== capabilities.length)
      throw new Error('Duplicate computer capability');
    return Object.freeze({
      targetId: computerId(row['targetId']),
      backend,
      label: computerId(row['label']),
      surfaces: Object.freeze(surfaces),
      capabilities: Object.freeze(capabilities),
    });
  });
  if (new Set(targets.map((target) => target.targetId)).size !== targets.length)
    throw new Error('Duplicate computer target');
  return Object.freeze(targets);
}

function imageMime(bytes: Uint8Array): CapturedImage['mimeType'] {
  if ([137, 80, 78, 71, 13, 10, 26, 10].every((byte, i) => bytes[i] === byte))
    return 'image/png';
  if (bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255)
    return 'image/jpeg';
  if (
    bytes.length >= 12 &&
    bytes[0] === 82 &&
    bytes[1] === 73 &&
    bytes[2] === 70 &&
    bytes[3] === 70 &&
    bytes[8] === 87 &&
    bytes[9] === 69 &&
    bytes[10] === 66 &&
    bytes[11] === 80
  )
    return 'image/webp';
  throw new Error('Unsupported captured image signature');
}

export function validateCaptureLimits(value: unknown): CaptureLimits {
  const row = object(value, ['maxBytes', 'maxDimension']);
  const maxBytes = row['maxBytes'],
    maxDimension = row['maxDimension'];
  if (
    typeof maxBytes !== 'number' ||
    !Number.isSafeInteger(maxBytes) ||
    maxBytes < 1 ||
    maxBytes > 5_242_880 ||
    typeof maxDimension !== 'number' ||
    !Number.isSafeInteger(maxDimension) ||
    maxDimension < 1 ||
    maxDimension > 32768
  )
    throw new Error('Invalid capture limits');
  return Object.freeze({ maxBytes, maxDimension });
}

/** Signature/cap checks precede upload; complete format validation remains image admission's job. */
export function decodeCapturedImage(
  value: unknown,
  limits: CaptureLimits = DEFAULT_CAPTURE_LIMITS,
): CapturedImage {
  limits = validateCaptureLimits(limits);
  const row = object(value, [
    'bytes',
    'mimeType',
    'width',
    'height',
    'transform',
    'capturedAt',
    'layoutGeneration',
  ]);
  const bytes = row['bytes'];
  if (
    !(bytes instanceof Uint8Array) ||
    bytes.byteLength < 1 ||
    bytes.byteLength > limits.maxBytes
  )
    throw new Error('Capture exceeds its byte limit');
  const mimeType = imageMime(bytes);
  if (row['mimeType'] !== mimeType)
    throw new Error('Captured MIME does not match image signature');
  const width = row['width'],
    height = row['height'];
  if (
    typeof width !== 'number' ||
    !Number.isSafeInteger(width) ||
    width < 1 ||
    width > limits.maxDimension ||
    typeof height !== 'number' ||
    !Number.isSafeInteger(height) ||
    height < 1 ||
    height > limits.maxDimension
  )
    throw new Error('Capture exceeds its dimension limit');
  return Object.freeze({
    bytes: new Uint8Array(bytes),
    mimeType,
    width,
    height,
    transform: validateTransform(row['transform']),
    capturedAt: isoTimestamp(row['capturedAt']),
    layoutGeneration: computerId(row['layoutGeneration']),
  });
}
