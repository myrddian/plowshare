import type {
  CapturedImage,
  ComputerBackend,
  ComputerOwner,
  SessionReference,
} from './contracts.ts';
import { computerId, decodeCapturedImage, decodeOwner } from './contracts.ts';
import type { OperationDeadline } from './deadline.ts';
import { isoTimestamp, positiveBudget } from './deadline.ts';
import { framePoint } from './geometry.ts';

/** Native desktop tools share model-facing names with browser tools, never browser state. */
export type NativeToolName =
  'windows' | 'focus' | 'pointer' | 'type' | 'key' | 'scroll';
export type NativeControlCode =
  | 'INVALID_ARGUMENT'
  | 'CONTROL_DISABLED'
  | 'TARGET_NOT_ALLOWED'
  | 'NO_LEASE'
  | 'BUSY'
  | 'FENCED'
  | 'TARGET_GONE'
  | 'PERMISSION_DENIED'
  | 'SESSION_LOCKED'
  | 'SESSION_INACTIVE'
  | 'SESSION_UNKNOWN'
  | 'SECURE_INPUT'
  | 'INTERFERENCE'
  | 'STALE_OBSERVATION'
  | 'FOCUS_MISMATCH'
  | 'NOT_EDITABLE'
  | 'SENSITIVE_TARGET'
  | 'AX_UNAVAILABLE'
  | 'UNSUPPORTED_ACTION'
  | 'AMBIGUOUS_WINDOW'
  | 'INPUT_FAILED'
  | 'OBSERVATION_FAILED'
  | 'DEADLINE_EXPIRED'
  | 'CLEANUP_UNCERTAIN';

/** A diagnostic code alone never proves that an action was not dispatched. */
export class NativeControlError extends Error {
  constructor(readonly code: NativeControlCode) {
    super(code);
    this.name = 'NativeControlError';
  }
}
export interface NativePermissions {
  readonly screenCapture: boolean;
  readonly accessibility: boolean;
  readonly eventPosting: boolean;
  readonly eventListening: boolean;
}
export interface NativeControlPolicy {
  readonly enabled: true;
  readonly tools: readonly NativeToolName[];
  /** Exact configured applications, not model-selected PIDs or arbitrary application launch. */
  readonly allowedBundleIds: readonly string[];
}
export interface NativeAcquireRequest {
  readonly owner: ComputerOwner;
  readonly session: SessionReference;
  readonly targetId: string;
}
/** Host-only token; helper generation and OS-session identity remain private broker bindings. */
export interface NativeLease extends SessionReference {
  readonly leaseId: string;
  readonly targetId: string;
}
export type NativeAcquisition =
  | { readonly outcome: 'ACQUIRED'; readonly lease: NativeLease }
  | { readonly outcome: 'NOT_ACQUIRED'; readonly code: NativeControlCode }
  | {
      readonly outcome: 'UNCERTAIN';
      readonly lease: NativeLease | null;
      readonly code: NativeControlCode;
    };

/**
 * All identities are opaque references, not CGWindowID/PID/AX handles. applicationId
 * binds a process incarnation; windowId binds the exact AX window. Capture-surface
 * identity is separate: never guess an AX-to-CGWindowID association by title/role.
 */
export interface NativeStateGuard {
  readonly surfaceId: string;
  readonly applicationId: string | null;
  readonly windowId: string | null;
  readonly layoutGeneration: string;
  readonly focusGeneration: string;
  readonly displayGeneration: string;
  readonly interactionGeneration: string;
}
export interface NativeBounds {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}
export interface NativeDisplayGeometry {
  readonly displayId: string;
  readonly bounds: NativeBounds;
  readonly scaleX: number;
  readonly scaleY: number;
  readonly rotation: 0 | 90 | 180 | 270;
}
export interface NativeState extends NativeStateGuard {
  readonly availability:
    'ready' | 'locked' | 'inactive' | 'unknown' | 'unavailable';
  readonly permissions: NativePermissions;
  readonly secureInput: 'clear' | 'active' | 'unknown';
  readonly interference: 'clear' | 'detected' | 'unknown';
  /** false cannot be interpreted as body/no-element focus. */
  readonly focusKnown: boolean;
  readonly focusedElementId: string | null;
  readonly display: NativeDisplayGeometry;
  readonly applicationLabel: string | null;
  readonly bundleId: string | null;
}
export interface NativeElement {
  readonly elementId: string;
  readonly parentElementId: string | null;
  readonly role: string;
  readonly subrole: string | null;
  readonly name: string;
  readonly editable: boolean;
  readonly focused: boolean;
  readonly enabled: boolean;
  readonly secure: boolean;
  readonly bounds: NativeBounds | null;
}
/** Bounded AX projection; no password/value contents and no complete-tree claim. */
export interface NativeSnapshot {
  readonly snapshotId: string;
  readonly rootElementId: string | null;
  readonly guard: NativeStateGuard;
  readonly capturedAt: string;
  readonly kind: 'ax';
  readonly approximate: true;
  readonly nodes: readonly NativeElement[];
  readonly truncated: boolean;
}
export interface NativeObservation {
  readonly image: CapturedImage;
  readonly state: NativeState;
  /** Explicit unsupported AX observation fails; null only when structured=false. */
  readonly snapshot: NativeSnapshot | null;
}
export interface NativeWindow {
  /** Returned focus reference; distinct from a display capture surface. */
  readonly surfaceId: string;
  readonly applicationId: string;
  readonly windowId: string;
  readonly captureSurfaceId: string;
  readonly applicationLabel: string;
  readonly bundleId: string;
  readonly title: string;
  readonly bounds: NativeBounds;
  readonly focused: boolean;
  readonly state: 'available' | 'unavailable' | 'closed';
}
export interface NativeWindows {
  readonly windowsId: string;
  readonly capturedAt: string;
  readonly windows: readonly NativeWindow[];
  readonly truncated: boolean;
}
export type NativeActionTarget =
  | {
      readonly kind: 'point';
      readonly frameId: string;
      readonly x: number;
      readonly y: number;
    }
  | {
      readonly kind: 'element';
      readonly snapshotId: string;
      readonly elementId: string;
    };
export type NativeElementTarget = Extract<
  NativeActionTarget,
  { readonly kind: 'element' }
>;
/** Points are finite Quartz desktop coordinates; negative origins are valid. */
export type PreparedNativeTarget =
  | { readonly kind: 'point'; readonly x: number; readonly y: number }
  | NativeElementTarget;
export type NativeButton = 'left' | 'middle' | 'right';
export type NativeModifier = 'Alt' | 'Control' | 'Meta' | 'Shift';
/** Finite safe-chord subset: no clipboard, launch, file, print or privilege shortcuts. */
export type NativeKey =
  | 'Enter'
  | 'Tab'
  | 'Escape'
  | 'Backspace'
  | 'Delete'
  | 'Home'
  | 'End'
  | 'PageUp'
  | 'PageDown'
  | 'ArrowUp'
  | 'ArrowDown'
  | 'ArrowLeft'
  | 'ArrowRight'
  | 'Space'
  | 'a'
  | 'z'
  | 'y';
export interface NativeDragPoint {
  readonly x: number;
  readonly y: number;
  readonly atMs: number;
}
export type PreparedNativeAction =
  | { readonly kind: 'move'; readonly target: PreparedNativeTarget }
  | {
      readonly kind: 'click' | 'double_click';
      readonly target: PreparedNativeTarget;
      readonly button: NativeButton;
      readonly modifiers: readonly NativeModifier[];
    }
  | {
      readonly kind: 'drag';
      readonly points: readonly NativeDragPoint[];
      readonly button: NativeButton;
      readonly modifiers: readonly NativeModifier[];
    }
  | {
      readonly kind: 'type';
      readonly target: NativeElementTarget;
      readonly mode: 'insert' | 'replace';
      readonly text: string;
    }
  | {
      readonly kind: 'key';
      readonly expectedFocus: NativeElementTarget | null;
      readonly key: NativeKey;
      readonly modifiers: readonly NativeModifier[];
    }
  | {
      readonly kind: 'scroll';
      readonly target: PreparedNativeTarget;
      readonly dx: number;
      readonly dy: number;
      readonly units: 'pixels' | 'lines';
    };

export type NativeInputMechanism =
  | 'cg-event'
  | 'ax-press'
  | 'ax-selected-text'
  | 'ax-set-value'
  | 'unicode-events'
  | 'focus';
export interface NativeScrollDelivery {
  readonly dx: number;
  readonly dy: number;
  readonly units: 'pixels' | 'lines';
}
/** Proven adapter dispatch extent only, never characters accepted or application success. */
export type NativeDeliveredExtent =
  | {
      readonly kind: 'pointer';
      readonly moves: number;
      readonly clicks: number;
    }
  | {
      readonly kind: 'drag';
      readonly points: number;
      readonly buttonReleased: true;
    }
  | {
      readonly kind: 'type';
      readonly dispatchedUtf16Units: number;
      readonly cleared: boolean;
    }
  | { readonly kind: 'key'; readonly keyDowns: number; readonly keyUps: number }
  | (NativeScrollDelivery & { readonly kind: 'scroll' });
export type NativeDelivery =
  | {
      readonly outcome: 'NOT_DISPATCHED';
      readonly physicalSettlement: 'settled';
      readonly code: NativeControlCode;
    }
  | {
      readonly outcome: 'DISPATCHED';
      readonly physicalSettlement: 'settled';
      readonly mechanism: NativeInputMechanism;
    }
  | {
      readonly outcome: 'DISPATCHED';
      readonly physicalSettlement: 'settled';
      readonly mechanism: 'cg-scroll';
      readonly scroll: NativeScrollDelivery;
    }
  | {
      readonly outcome: 'PARTIAL';
      readonly physicalSettlement: 'settled';
      readonly delivered: NativeDeliveredExtent;
      readonly code: NativeControlCode;
    }
  | {
      readonly outcome: 'UNCERTAIN';
      readonly physicalSettlement: 'settled' | 'uncertain';
      readonly code: NativeControlCode;
    };
export type NativePostObservation =
  | { readonly state: 'CAPTURED'; readonly value: NativeObservation }
  | { readonly state: 'FAILED'; readonly code: NativeControlCode };
export interface NativeActionResult {
  readonly delivery: NativeDelivery;
  readonly observation: NativePostObservation;
}
export interface NativeCleanupResult {
  readonly physicalSettlement: 'settled' | 'uncertain';
  readonly heldInputsReleased: boolean;
  readonly helperStopped: boolean;
}
export interface NativeObserveRequest {
  readonly surfaceId: string;
  readonly structured: boolean;
  readonly root?: NativeElementTarget;
}
export interface NativeFocusRequest {
  readonly invocationId: string;
  readonly windowsId: string;
  readonly surfaceId: string;
  readonly observationReserveMs: number;
}
export interface NativePerformRequest {
  /** Validated ToolCall identity, never a model argument or replay permission. */
  readonly invocationId: string;
  readonly expected: NativeStateGuard;
  readonly action: PreparedNativeAction;
  readonly observationReserveMs: number;
}

/**
 * Native controller, independent of any client or server socket. Acquisition
 * owns ONE OS-derived interactive-session resource across all displays/windows,
 * provider bindings and processes. The persistent private helper/broker and P1
 * capture path must share that kernel namespace and durable crash fence; neither
 * configuration-directory aliases nor target IDs create independent desktops.
 *
 * NOT_ACQUIRED proves no owned helper/lease/resources remain and permits release
 * of only the caller's empty local reservation. UNCERTAIN retains its own fence;
 * never clean another binding's lease, steal by PID/age or retry acquisition to
 * improve the answer. Every acquired lease predates capture/input. Unclassified
 * thrown acquisition/action failures remain uncertain, not proven refusals.
 *
 * Each action revalidates exact process incarnation/window/AX handles, display
 * geometry, focus and detectable local interference before dispatch. Locked,
 * inactive, secure-input or unknown readiness refuses input. Console-active
 * does not prove unlocked. Permissions are checked separately without prompts.
 * An AX operation has a positive finite remaining messaging timeout. Physical
 * GUI input requires allowed-application/window hit-testing; no implicit focus,
 * app launch, clipboard, credential injection or privilege escalation.
 *
 * Text is <=4096 UTF-16 units with well-formed Unicode preserved verbatim. Insert
 * and replace require the exact focused editable nonsecure element; insertion
 * mechanism is explicit. A null key focus expectation means proven no-element
 * focus, never unknown/wildcard. Drag is2..128 points with strictly increasing
 * atMs from0 through<=1000; no standalone held-key/button tools. Input/cleanup
 * releases only owned holds. A killed helper does NOT prove release of holds.
 *
 * Reserve result/image capacity and post-observation time BEFORE input. Methods
 * return typed delivery for all known post-admission outcomes. CGEvent posting
 * establishes local posting only, not app acknowledgement. Keep established
 * DISPATCHED/PARTIAL evidence if observation fails/expires; do not relabel it as
 * uncertain input. SDK invocation deadlines may still settle immutable UNKNOWN;
 * local evidence never rewrites that receipt or authorizes action replay.
 */
export interface NativeControl {
  readonly allowedTools: readonly NativeToolName[];
  acquire(
    request: NativeAcquireRequest,
    deadline: OperationDeadline,
  ): Promise<NativeAcquisition>;
  release(
    lease: NativeLease,
    deadline: OperationDeadline,
  ): Promise<NativeCleanupResult>;
  windows(
    lease: NativeLease,
    deadline: OperationDeadline,
  ): Promise<NativeWindows>;
  observe(
    lease: NativeLease,
    request: NativeObserveRequest,
    deadline: OperationDeadline,
  ): Promise<NativeObservation>;
  focus(
    lease: NativeLease,
    request: NativeFocusRequest,
    deadline: OperationDeadline,
  ): Promise<NativeActionResult>;
  perform(
    lease: NativeLease,
    request: NativePerformRequest,
    deadline: OperationDeadline,
  ): Promise<NativeActionResult>;
}
/** Separate controlled factory; inherited bare capture always refuses, even when idle. */
export interface NativeControlBackend extends ComputerBackend {
  readonly nativeControl: NativeControl;
}

const nativeTools: readonly NativeToolName[] = [
  'windows',
  'focus',
  'pointer',
  'type',
  'key',
  'scroll',
];
const nativeCodes: readonly NativeControlCode[] = [
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
const nativeKeys: readonly NativeKey[] = [
  'Enter',
  'Tab',
  'Escape',
  'Backspace',
  'Delete',
  'Home',
  'End',
  'PageUp',
  'PageDown',
  'ArrowUp',
  'ArrowDown',
  'ArrowLeft',
  'ArrowRight',
  'Space',
  'a',
  'z',
  'y',
];
function invalid(): never {
  throw new NativeControlError('INVALID_ARGUMENT');
}
function object(
  value: unknown,
  allowed: readonly string[],
): Readonly<Record<string, unknown>> {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    invalid();
  const row = value as Readonly<Record<string, unknown>>;
  if (Object.keys(row).some((key) => !allowed.includes(key))) invalid();
  return row;
}
function choice<T extends string>(value: unknown, allowed: readonly T[]): T {
  for (const item of allowed) if (value === item) return item;
  return invalid();
}
function text(value: unknown, max: number, empty = true): string {
  if (
    typeof value !== 'string' ||
    value.length > max ||
    (!empty && value.length === 0) ||
    Array.from(value).some((character) => {
      const code = character.codePointAt(0) ?? 0;
      return code === 0 || (code >= 0xd800 && code <= 0xdfff);
    })
  )
    invalid();
  return value;
}
function numeric(
  value: unknown,
  min: number,
  max: number,
  integer = false,
): number {
  if (
    typeof value !== 'number' ||
    !Number.isFinite(value) ||
    value < min ||
    value > max ||
    (integer && !Number.isSafeInteger(value))
  )
    invalid();
  return value;
}
function flag(value: unknown): boolean {
  if (typeof value !== 'boolean') invalid();
  return value;
}
function list(value: unknown, max: number): readonly unknown[] {
  if (!Array.isArray(value) || value.length > max) invalid();
  return value as readonly unknown[];
}
function id(value: unknown): string {
  try {
    return computerId(value);
  } catch {
    return invalid();
  }
}
function nullableId(value: unknown): string | null {
  return value === null ? null : id(value);
}
function bundle(value: unknown): string {
  const name = text(value, 255, false);
  if (!/^[A-Za-z0-9][A-Za-z0-9-]*(?:\.[A-Za-z0-9][A-Za-z0-9-]*)+$/u.test(name))
    invalid();
  return name;
}
function bounds(value: unknown, positive = false): NativeBounds {
  const row = object(value, ['x', 'y', 'width', 'height']);
  const width = numeric(row['width'], 0, 1e7),
    height = numeric(row['height'], 0, 1e7);
  if (positive && (width === 0 || height === 0)) invalid();
  return Object.freeze({
    x: numeric(row['x'], -1e7, 1e7),
    y: numeric(row['y'], -1e7, 1e7),
    width,
    height,
  });
}
function budget(value: unknown): number {
  if (typeof value !== 'number') invalid();
  return positiveBudget(value);
}
const guardFields = [
  'surfaceId',
  'applicationId',
  'windowId',
  'layoutGeneration',
  'focusGeneration',
  'displayGeneration',
  'interactionGeneration',
] as const;
function projectedGuard(
  row: Readonly<Record<string, unknown>>,
): NativeStateGuard {
  return Object.freeze({
    surfaceId: id(row['surfaceId']),
    applicationId: nullableId(row['applicationId']),
    windowId: nullableId(row['windowId']),
    layoutGeneration: id(row['layoutGeneration']),
    focusGeneration: id(row['focusGeneration']),
    displayGeneration: id(row['displayGeneration']),
    interactionGeneration: id(row['interactionGeneration']),
  });
}
export function decodeNativeControlPolicy(value: unknown): NativeControlPolicy {
  const row = object(value, ['enabled', 'tools', 'allowedBundleIds']);
  if (row['enabled'] !== true) invalid();
  const tools = list(row['tools'], 6).map((item) => choice(item, nativeTools));
  const allowedBundleIds = list(row['allowedBundleIds'], 32).map(bundle);
  if (
    tools.length === 0 ||
    allowedBundleIds.length === 0 ||
    new Set(tools).size !== tools.length ||
    new Set(allowedBundleIds).size !== allowedBundleIds.length
  )
    invalid();
  return Object.freeze({
    enabled: true,
    tools: Object.freeze(tools),
    allowedBundleIds: Object.freeze(allowedBundleIds),
  });
}
export function requireAllowedNativeBundle(
  policy: NativeControlPolicy,
  value: unknown,
): string {
  const checked = decodeNativeControlPolicy(policy),
    name = bundle(value);
  if (!checked.allowedBundleIds.includes(name))
    throw new NativeControlError('TARGET_NOT_ALLOWED');
  return name;
}
export function decodeNativePermissions(value: unknown): NativePermissions {
  const row = object(value, [
    'screenCapture',
    'accessibility',
    'eventPosting',
    'eventListening',
  ]);
  return Object.freeze({
    screenCapture: flag(row['screenCapture']),
    accessibility: flag(row['accessibility']),
    eventPosting: flag(row['eventPosting']),
    eventListening: flag(row['eventListening']),
  });
}
export function decodeNativeAcquireRequest(
  value: unknown,
): NativeAcquireRequest {
  const row = object(value, ['owner', 'session', 'targetId']),
    session = object(row['session'], ['sessionId', 'generation']);
  return Object.freeze({
    owner: decodeOwner(row['owner']),
    targetId: id(row['targetId']),
    session: Object.freeze({
      sessionId: id(session['sessionId']),
      generation: id(session['generation']),
    }),
  });
}
export function decodeNativeLease(value: unknown): NativeLease {
  const row = object(value, ['leaseId', 'targetId', 'sessionId', 'generation']);
  return Object.freeze({
    leaseId: id(row['leaseId']),
    targetId: id(row['targetId']),
    sessionId: id(row['sessionId']),
    generation: id(row['generation']),
  });
}
export function decodeNativeAcquisition(value: unknown): NativeAcquisition {
  const row = object(value, ['outcome', 'lease', 'code']);
  switch (row['outcome']) {
    case 'ACQUIRED':
      object(value, ['outcome', 'lease']);
      return Object.freeze({
        outcome: 'ACQUIRED',
        lease: decodeNativeLease(row['lease']),
      });
    case 'NOT_ACQUIRED':
      object(value, ['outcome', 'code']);
      return Object.freeze({
        outcome: 'NOT_ACQUIRED',
        code: choice(row['code'], nativeCodes),
      });
    case 'UNCERTAIN':
      object(value, ['outcome', 'lease', 'code']);
      return Object.freeze({
        outcome: 'UNCERTAIN',
        lease: row['lease'] === null ? null : decodeNativeLease(row['lease']),
        code: choice(row['code'], nativeCodes),
      });
    default:
      return invalid();
  }
}
export function decodeNativeStateGuard(value: unknown): NativeStateGuard {
  return projectedGuard(object(value, guardFields));
}
export function sameNativeGuard(
  a: NativeStateGuard,
  b: NativeStateGuard,
): boolean {
  return guardFields.every((key) => a[key] === b[key]);
}
export function decodeNativeState(value: unknown): NativeState {
  const row = object(value, [
    ...guardFields,
    'availability',
    'permissions',
    'secureInput',
    'interference',
    'focusKnown',
    'focusedElementId',
    'display',
    'applicationLabel',
    'bundleId',
  ]);
  const guard = projectedGuard(row),
    permissions = decodeNativePermissions(row['permissions']);
  const availability = choice(row['availability'], [
    'ready',
    'locked',
    'inactive',
    'unknown',
    'unavailable',
  ]);
  const secureInput = choice(row['secureInput'], [
      'clear',
      'active',
      'unknown',
    ]),
    interference = choice(row['interference'], [
      'clear',
      'detected',
      'unknown',
    ]);
  const focusKnown = flag(row['focusKnown']),
    focusedElementId = nullableId(row['focusedElementId']);
  const display = object(row['display'], [
    'displayId',
    'bounds',
    'scaleX',
    'scaleY',
    'rotation',
  ]);
  const rotation = numeric(display['rotation'], 0, 270, true);
  if (rotation !== 0 && rotation !== 90 && rotation !== 180 && rotation !== 270)
    invalid();
  const applicationLabel =
      row['applicationLabel'] === null
        ? null
        : text(row['applicationLabel'], 256),
    bundleId = row['bundleId'] === null ? null : bundle(row['bundleId']);
  if (
    (!focusKnown && focusedElementId !== null) ||
    (guard.applicationId === null &&
      (guard.windowId !== null ||
        applicationLabel !== null ||
        bundleId !== null ||
        focusedElementId !== null))
  )
    invalid();
  if (
    availability === 'ready' &&
    (!permissions.screenCapture ||
      !permissions.accessibility ||
      !permissions.eventPosting ||
      !permissions.eventListening ||
      !focusKnown ||
      guard.applicationId === null ||
      guard.windowId === null ||
      bundleId === null ||
      secureInput !== 'clear' ||
      interference !== 'clear')
  )
    invalid();
  return Object.freeze({
    ...guard,
    availability,
    permissions,
    secureInput,
    interference,
    focusKnown,
    focusedElementId,
    display: Object.freeze({
      displayId: id(display['displayId']),
      bounds: bounds(display['bounds'], true),
      scaleX: numeric(display['scaleX'], Number.EPSILON, 16),
      scaleY: numeric(display['scaleY'], Number.EPSILON, 16),
      rotation,
    }),
    applicationLabel,
    bundleId,
  });
}
/** Unknown state is refusal, never fabricated unlocked readiness from console-active or stale AX focus. */
export function requireReadyNativeState(value: NativeState): NativeState {
  const state = decodeNativeState(value);
  if (
    !state.permissions.screenCapture ||
    !state.permissions.accessibility ||
    !state.permissions.eventPosting ||
    !state.permissions.eventListening
  )
    throw new NativeControlError('PERMISSION_DENIED');
  if (state.availability === 'locked')
    throw new NativeControlError('SESSION_LOCKED');
  if (state.availability === 'inactive')
    throw new NativeControlError('SESSION_INACTIVE');
  if (state.availability !== 'ready')
    throw new NativeControlError('SESSION_UNKNOWN');
  if (state.secureInput !== 'clear')
    throw new NativeControlError('SECURE_INPUT');
  if (state.interference !== 'clear')
    throw new NativeControlError('INTERFERENCE');
  if (!state.focusKnown) throw new NativeControlError('FOCUS_MISMATCH');
  return state;
}
export function decodeNativeSnapshot(value: unknown): NativeSnapshot {
  const row = object(value, [
    'snapshotId',
    'rootElementId',
    'guard',
    'capturedAt',
    'kind',
    'approximate',
    'nodes',
    'truncated',
  ]);
  if (row['kind'] !== 'ax' || row['approximate'] !== true) invalid();
  const nodes = list(row['nodes'], 200).map((value): NativeElement => {
    const node = object(value, [
      'elementId',
      'parentElementId',
      'role',
      'subrole',
      'name',
      'editable',
      'focused',
      'enabled',
      'secure',
      'bounds',
    ]);
    return Object.freeze({
      elementId: id(node['elementId']),
      parentElementId: nullableId(node['parentElementId']),
      role: text(node['role'], 64, false),
      subrole:
        node['subrole'] === null ? null : text(node['subrole'], 64, false),
      name: text(node['name'], 256),
      editable: flag(node['editable']),
      focused: flag(node['focused']),
      enabled: flag(node['enabled']),
      secure: flag(node['secure']),
      bounds: node['bounds'] === null ? null : bounds(node['bounds']),
    });
  });
  const seen = new Set<string>();
  for (const node of nodes) {
    if (
      seen.has(node.elementId) ||
      (node.parentElementId !== null && !seen.has(node.parentElementId))
    )
      invalid();
    seen.add(node.elementId);
  }
  if (nodes.filter((node) => node.focused).length > 1) invalid();
  const rootElementId = nullableId(row['rootElementId']);
  if (
    rootElementId !== null &&
    (nodes[0]?.elementId !== rootElementId ||
      nodes[0].parentElementId !== null ||
      nodes.slice(1).some((node) => node.parentElementId === null))
  )
    invalid();
  return Object.freeze({
    snapshotId: id(row['snapshotId']),
    rootElementId,
    guard: decodeNativeStateGuard(row['guard']),
    capturedAt: isoTimestamp(row['capturedAt']),
    kind: 'ax',
    approximate: true,
    nodes: Object.freeze(nodes),
    truncated: flag(row['truncated']),
  });
}
export function decodeNativeObservation(value: unknown): NativeObservation {
  const row = object(value, ['image', 'state', 'snapshot']),
    state = decodeNativeState(row['state']),
    image = decodeCapturedImage(row['image']);
  const snapshot =
    row['snapshot'] === null ? null : decodeNativeSnapshot(row['snapshot']);
  if (
    !state.permissions.screenCapture ||
    image.layoutGeneration !== state.layoutGeneration
  )
    invalid();
  if (snapshot !== null) {
    if (
      !state.permissions.accessibility ||
      !sameNativeGuard(snapshot.guard, state)
    )
      invalid();
    const focused =
      snapshot.nodes.find((node) => node.focused)?.elementId ?? null;
    if (
      focused !== null &&
      (!state.focusKnown || focused !== state.focusedElementId)
    )
      invalid();
    const declaredFocus = snapshot.nodes.find(
      (node) => node.elementId === state.focusedElementId,
    );
    if (declaredFocus !== undefined && !declaredFocus.focused) invalid();
    if (
      state.focusKnown &&
      snapshot.rootElementId === null &&
      !snapshot.truncated &&
      focused !== state.focusedElementId
    )
      invalid();
  }
  // P3 captures one configured display. An invertible but malformed helper
  // transform cannot authorize points outside that observed display.
  const visible = state.display.bounds;
  for (const [x, y] of [
    [0, 0],
    [image.width - 1, 0],
    [0, image.height - 1],
    [image.width - 1, image.height - 1],
  ]) {
    if (x === undefined || y === undefined) invalid();
    const point = framePoint(image.transform, image.width, image.height, x, y);
    if (
      point.x < visible.x ||
      point.x > visible.x + visible.width ||
      point.y < visible.y ||
      point.y > visible.y + visible.height
    )
      invalid();
  }
  return Object.freeze({ image, state, snapshot });
}
export function decodeNativeWindows(value: unknown): NativeWindows {
  const row = object(value, [
    'windowsId',
    'capturedAt',
    'windows',
    'truncated',
  ]);
  const windows = list(row['windows'], 32).map((value): NativeWindow => {
    const window = object(value, [
      'surfaceId',
      'applicationId',
      'windowId',
      'captureSurfaceId',
      'applicationLabel',
      'bundleId',
      'title',
      'bounds',
      'focused',
      'state',
    ]);
    return Object.freeze({
      surfaceId: id(window['surfaceId']),
      applicationId: id(window['applicationId']),
      windowId: id(window['windowId']),
      captureSurfaceId: id(window['captureSurfaceId']),
      applicationLabel: text(window['applicationLabel'], 256),
      bundleId: bundle(window['bundleId']),
      title: text(window['title'], 256),
      bounds: bounds(window['bounds'], true),
      focused: flag(window['focused']),
      state: choice(window['state'], ['available', 'unavailable', 'closed']),
    });
  });
  if (
    new Set(windows.map((window) => window.surfaceId)).size !==
      windows.length ||
    new Set(windows.map((window) => window.windowId)).size !== windows.length ||
    windows.filter((window) => window.focused).length > 1
  )
    invalid();
  return Object.freeze({
    windowsId: id(row['windowsId']),
    capturedAt: isoTimestamp(row['capturedAt']),
    windows: Object.freeze(windows),
    truncated: flag(row['truncated']),
  });
}
export function decodeNativeActionTarget(value: unknown): NativeActionTarget {
  const row = object(value, [
    'kind',
    'frameId',
    'x',
    'y',
    'snapshotId',
    'elementId',
  ]);
  if (row['kind'] === 'point') {
    object(value, ['kind', 'frameId', 'x', 'y']);
    return Object.freeze({
      kind: 'point',
      frameId: id(row['frameId']),
      x: numeric(row['x'], 0, 32767, true),
      y: numeric(row['y'], 0, 32767, true),
    });
  }
  if (row['kind'] === 'element') {
    object(value, ['kind', 'snapshotId', 'elementId']);
    return Object.freeze({
      kind: 'element',
      snapshotId: id(row['snapshotId']),
      elementId: id(row['elementId']),
    });
  }
  return invalid();
}
function element(value: unknown): NativeElementTarget {
  const target = decodeNativeActionTarget(value);
  if (target.kind !== 'element') invalid();
  return target;
}
function preparedTarget(value: unknown): PreparedNativeTarget {
  const row = object(value, ['kind', 'x', 'y', 'snapshotId', 'elementId']);
  if (row['kind'] !== 'point') return element(value);
  object(value, ['kind', 'x', 'y']);
  return Object.freeze({
    kind: 'point',
    x: numeric(row['x'], -1e7, 1e7),
    y: numeric(row['y'], -1e7, 1e7),
  });
}
function modifiers(value: unknown): readonly NativeModifier[] {
  const result = list(value, 4).map((value) =>
    choice<NativeModifier>(value, ['Alt', 'Control', 'Meta', 'Shift']),
  );
  if (new Set(result).size !== result.length) invalid();
  return Object.freeze(result);
}
function safeChord(key: NativeKey, selected: readonly NativeModifier[]): void {
  const chord = [...selected].sort().join('+') + ':' + key;
  if (['Meta:a', 'Meta:z', 'Meta+Shift:z'].includes(chord)) return;
  if (['a', 'z', 'y'].includes(key)) invalid();
  if (selected.length === 0) return;
  if (
    selected.length === 1 &&
    selected[0] === 'Shift' &&
    [
      'Enter',
      'Tab',
      'Home',
      'End',
      'PageUp',
      'PageDown',
      'ArrowUp',
      'ArrowDown',
      'ArrowLeft',
      'ArrowRight',
      'Space',
    ].includes(key)
  )
    return;
  invalid();
}
export function decodePreparedNativeAction(
  value: unknown,
): PreparedNativeAction {
  const row = object(value, [
    'kind',
    'target',
    'button',
    'modifiers',
    'points',
    'mode',
    'text',
    'expectedFocus',
    'key',
    'dx',
    'dy',
    'units',
  ]);
  switch (row['kind']) {
    case 'move':
      object(value, ['kind', 'target']);
      return Object.freeze({
        kind: 'move',
        target: preparedTarget(row['target']),
      });
    case 'click':
    case 'double_click':
      object(value, ['kind', 'target', 'button', 'modifiers']);
      return Object.freeze({
        kind: row['kind'],
        target: preparedTarget(row['target']),
        button: choice(row['button'], ['left', 'middle', 'right']),
        modifiers: modifiers(row['modifiers']),
      });
    case 'drag': {
      object(value, ['kind', 'points', 'button', 'modifiers']);
      const points = list(row['points'], 128).map((value): NativeDragPoint => {
        const point = object(value, ['x', 'y', 'atMs']);
        return Object.freeze({
          x: numeric(point['x'], -1e7, 1e7),
          y: numeric(point['y'], -1e7, 1e7),
          atMs: numeric(point['atMs'], 0, 1000, true),
        });
      });
      if (
        points.length < 2 ||
        points[0]?.atMs !== 0 ||
        points.some(
          (point, index) =>
            index > 0 && point.atMs <= (points[index - 1]?.atMs ?? 0),
        )
      )
        invalid();
      return Object.freeze({
        kind: 'drag',
        points: Object.freeze(points),
        button: choice(row['button'], ['left', 'middle', 'right']),
        modifiers: modifiers(row['modifiers']),
      });
    }
    case 'type': {
      object(value, ['kind', 'target', 'mode', 'text']);
      const mode = choice(row['mode'], ['insert', 'replace']);
      return Object.freeze({
        kind: 'type',
        target: element(row['target']),
        mode,
        text: text(row['text'], 4096, mode === 'replace'),
      });
    }
    case 'key': {
      object(value, ['kind', 'expectedFocus', 'key', 'modifiers']);
      const key = choice(row['key'], nativeKeys),
        selected = modifiers(row['modifiers']);
      safeChord(key, selected);
      return Object.freeze({
        kind: 'key',
        expectedFocus:
          row['expectedFocus'] === null ? null : element(row['expectedFocus']),
        key,
        modifiers: selected,
      });
    }
    case 'scroll': {
      object(value, ['kind', 'target', 'dx', 'dy', 'units']);
      const units = choice(row['units'], ['pixels', 'lines']),
        max = units === 'pixels' ? 32768 : 128;
      const dx = numeric(row['dx'], -max, max, true),
        dy = numeric(row['dy'], -max, max, true);
      if (dx === 0 && dy === 0) invalid();
      return Object.freeze({
        kind: 'scroll',
        target: preparedTarget(row['target']),
        dx,
        dy,
        units,
      });
    }
    default:
      return invalid();
  }
}
/** Final live helper checks still own process/window/hit-test/AX connectedness; these cannot retarget. */
export function requireNativeActionState(
  actual: NativeState,
  expected: NativeStateGuard,
  action: PreparedNativeAction,
): void {
  const state = requireReadyNativeState(actual),
    guard = decodeNativeStateGuard(expected),
    checked = decodePreparedNativeAction(action);
  if (!sameNativeGuard(state, guard))
    throw new NativeControlError('STALE_OBSERVATION');
  if (
    checked.kind === 'type' &&
    state.focusedElementId !== checked.target.elementId
  )
    throw new NativeControlError('FOCUS_MISMATCH');
  if (
    checked.kind === 'key' &&
    (!state.focusKnown ||
      state.focusedElementId !== (checked.expectedFocus?.elementId ?? null))
  )
    throw new NativeControlError('FOCUS_MISMATCH');
}
export function decodeNativeObserveRequest(
  value: unknown,
): NativeObserveRequest {
  const row = object(value, ['surfaceId', 'structured', 'root']),
    structured = flag(row['structured']);
  if (!structured && row['root'] !== undefined) invalid();
  return Object.freeze({
    surfaceId: id(row['surfaceId']),
    structured,
    ...(row['root'] === undefined ? {} : { root: element(row['root']) }),
  });
}
function invocation(value: unknown): string {
  const name = id(value);
  if (!/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/u.test(name)) invalid();
  return name;
}
export function decodeNativeFocusRequest(value: unknown): NativeFocusRequest {
  const row = object(value, [
    'invocationId',
    'windowsId',
    'surfaceId',
    'observationReserveMs',
  ]);
  return Object.freeze({
    invocationId: invocation(row['invocationId']),
    windowsId: id(row['windowsId']),
    surfaceId: id(row['surfaceId']),
    observationReserveMs: budget(row['observationReserveMs']),
  });
}
export function decodeNativePerformRequest(
  value: unknown,
): NativePerformRequest {
  const row = object(value, [
    'invocationId',
    'expected',
    'action',
    'observationReserveMs',
  ]);
  return Object.freeze({
    invocationId: invocation(row['invocationId']),
    expected: decodeNativeStateGuard(row['expected']),
    action: decodePreparedNativeAction(row['action']),
    observationReserveMs: budget(row['observationReserveMs']),
  });
}
function scrollDelivery(value: unknown): NativeScrollDelivery {
  const row = object(value, ['dx', 'dy', 'units']),
    units = choice(row['units'], ['pixels', 'lines']),
    max = units === 'pixels' ? 32768 : 128;
  const dx = numeric(row['dx'], -max, max, true),
    dy = numeric(row['dy'], -max, max, true);
  if (dx === 0 && dy === 0) invalid();
  return Object.freeze({ dx, dy, units });
}
function extent(value: unknown): NativeDeliveredExtent {
  const row = object(value, [
    'kind',
    'moves',
    'clicks',
    'points',
    'buttonReleased',
    'dispatchedUtf16Units',
    'cleared',
    'keyDowns',
    'keyUps',
    'dx',
    'dy',
    'units',
  ]);
  switch (row['kind']) {
    case 'pointer': {
      object(value, ['kind', 'moves', 'clicks']);
      const moves = numeric(row['moves'], 0, 1, true),
        clicks = numeric(row['clicks'], 0, 2, true);
      if (moves + clicks === 0) invalid();
      return Object.freeze({ kind: 'pointer', moves, clicks });
    }
    case 'drag':
      object(value, ['kind', 'points', 'buttonReleased']);
      if (row['buttonReleased'] !== true) invalid();
      return Object.freeze({
        kind: 'drag',
        points: numeric(row['points'], 1, 128, true),
        buttonReleased: true,
      });
    case 'type': {
      object(value, ['kind', 'dispatchedUtf16Units', 'cleared']);
      const dispatchedUtf16Units = numeric(
          row['dispatchedUtf16Units'],
          0,
          4096,
          true,
        ),
        cleared = flag(row['cleared']);
      if (dispatchedUtf16Units === 0 && !cleared) invalid();
      return Object.freeze({ kind: 'type', dispatchedUtf16Units, cleared });
    }
    case 'key': {
      object(value, ['kind', 'keyDowns', 'keyUps']);
      const keyDowns = numeric(row['keyDowns'], 1, 5, true),
        keyUps = numeric(row['keyUps'], 1, 5, true);
      if (keyDowns !== keyUps) invalid();
      return Object.freeze({ kind: 'key', keyDowns, keyUps });
    }
    case 'scroll':
      object(value, ['kind', 'dx', 'dy', 'units']);
      return Object.freeze({
        kind: 'scroll',
        ...scrollDelivery({
          dx: row['dx'],
          dy: row['dy'],
          units: row['units'],
        }),
      });
    default:
      return invalid();
  }
}
export function decodeNativeDelivery(value: unknown): NativeDelivery {
  const row = object(value, [
    'outcome',
    'physicalSettlement',
    'code',
    'mechanism',
    'scroll',
    'delivered',
  ]);
  switch (row['outcome']) {
    case 'NOT_DISPATCHED':
      object(value, ['outcome', 'physicalSettlement', 'code']);
      if (row['physicalSettlement'] !== 'settled') invalid();
      return Object.freeze({
        outcome: 'NOT_DISPATCHED',
        physicalSettlement: 'settled',
        code: choice(row['code'], nativeCodes),
      });
    case 'DISPATCHED': {
      if (row['physicalSettlement'] !== 'settled') invalid();
      if (row['mechanism'] === 'cg-scroll') {
        object(value, ['outcome', 'physicalSettlement', 'mechanism', 'scroll']);
        return Object.freeze({
          outcome: 'DISPATCHED',
          physicalSettlement: 'settled',
          mechanism: 'cg-scroll',
          scroll: scrollDelivery(row['scroll']),
        });
      }
      object(value, ['outcome', 'physicalSettlement', 'mechanism']);
      return Object.freeze({
        outcome: 'DISPATCHED',
        physicalSettlement: 'settled',
        mechanism: choice(row['mechanism'], [
          'cg-event',
          'ax-press',
          'ax-selected-text',
          'ax-set-value',
          'unicode-events',
          'focus',
        ]),
      });
    }
    case 'PARTIAL':
      object(value, ['outcome', 'physicalSettlement', 'code', 'delivered']);
      if (row['physicalSettlement'] !== 'settled') invalid();
      return Object.freeze({
        outcome: 'PARTIAL',
        physicalSettlement: 'settled',
        code: choice(row['code'], nativeCodes),
        delivered: extent(row['delivered']),
      });
    case 'UNCERTAIN':
      object(value, ['outcome', 'physicalSettlement', 'code']);
      return Object.freeze({
        outcome: 'UNCERTAIN',
        physicalSettlement: choice(row['physicalSettlement'], [
          'settled',
          'uncertain',
        ]),
        code: choice(row['code'], nativeCodes),
      });
    default:
      return invalid();
  }
}
export function decodeNativeActionResult(value: unknown): NativeActionResult {
  const row = object(value, ['delivery', 'observation']),
    delivery = decodeNativeDelivery(row['delivery']),
    observed = object(row['observation'], ['state', 'value', 'code']);
  let observation: NativePostObservation;
  if (observed['state'] === 'CAPTURED') {
    object(row['observation'], ['state', 'value']);
    if (
      delivery.outcome === 'UNCERTAIN' ||
      delivery.outcome === 'NOT_DISPATCHED'
    )
      invalid();
    observation = Object.freeze({
      state: 'CAPTURED',
      value: decodeNativeObservation(observed['value']),
    });
  } else {
    object(row['observation'], ['state', 'code']);
    if (observed['state'] !== 'FAILED') invalid();
    observation = Object.freeze({
      state: 'FAILED',
      code: choice(observed['code'], nativeCodes),
    });
  }
  return Object.freeze({ delivery, observation });
}
export function decodeNativeCleanupResult(value: unknown): NativeCleanupResult {
  const row = object(value, [
      'physicalSettlement',
      'heldInputsReleased',
      'helperStopped',
    ]),
    physicalSettlement = choice(row['physicalSettlement'], [
      'settled',
      'uncertain',
    ]);
  const heldInputsReleased = flag(row['heldInputsReleased']),
    helperStopped = flag(row['helperStopped']);
  if (
    physicalSettlement === 'settled' &&
    (!heldInputsReleased || !helperStopped)
  )
    invalid();
  return Object.freeze({
    physicalSettlement,
    heldInputsReleased,
    helperStopped,
  });
}
