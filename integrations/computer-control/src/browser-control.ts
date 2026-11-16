import type {
  CapturedImage,
  ComputerBackend,
  ComputerOwner,
  SessionReference,
} from './contracts.ts';
import { computerId, decodeCapturedImage, decodeOwner } from './contracts.ts';
import { isoTimestamp, positiveBudget } from './deadline.ts';
import type { OperationDeadline } from './deadline.ts';

export type BrowserControlCode =
  | 'INVALID_ARGUMENT'
  | 'CONTROL_DISABLED'
  | 'ORIGIN_DENIED'
  | 'NO_LEASE'
  | 'BUSY'
  | 'FENCED'
  | 'TARGET_GONE'
  | 'TAB_NOT_READY'
  | 'STALE_OBSERVATION'
  | 'FOCUS_MISMATCH'
  | 'NOT_EDITABLE'
  | 'SENSITIVE_TARGET'
  | 'UNSUPPORTED_FRAME'
  | 'INPUT_FAILED'
  | 'OBSERVATION_FAILED'
  | 'DEADLINE_EXPIRED'
  | 'UNEXPECTED_DIALOG'
  | 'DIALOG_MISMATCH'
  | 'CLEANUP_UNCERTAIN';

/** Fixed diagnostics only: browser text, URLs and credentials are not error messages. */
export class BrowserControlError extends Error {
  constructor(readonly code: BrowserControlCode) {
    super(code);
    this.name = 'BrowserControlError';
  }
}
export type BrowserToolName =
  'windows' | 'focus' | 'navigate' | 'pointer' | 'type' | 'key' | 'scroll';

/**
 * Required opt-in for the separate controlled factory, never a capture-only default.
 * Origins constrain document navigation, including every redirect BEFORE dispatch.
 * They are not a network sandbox: subresource transport has a different boundary.
 * Initial popup requests and all nested-frame navigations are refused in P2.
 */
export interface BrowserControlPolicy {
  readonly enabled: true;
  readonly tools: readonly BrowserToolName[];
  readonly allowedOrigins: readonly string[];
}
export interface BrowserAcquireRequest {
  readonly targetId: string;
  readonly owner: ComputerOwner;
  readonly session: SessionReference;
}
/** Host-only capability. Never put this token in model-visible arguments or metadata. */
export interface BrowserLease extends SessionReference {
  readonly leaseId: string;
  readonly targetId: string;
}
export interface BrowserStateGuard {
  readonly surfaceId: string;
  readonly documentId: string;
  readonly layoutGeneration: string;
  readonly focusGeneration: string;
}
export type BrowserTabReadiness = 'ready' | 'quarantined' | 'closed';
export interface BrowserState extends BrowserStateGuard {
  readonly readiness: BrowserTabReadiness;
  readonly guardInstalled: boolean;
  readonly url: string | null;
  readonly origin: string | null;
  /** An aborted popup destination, not proof that any request reached that destination. */
  readonly requestedUrl: string | null;
  readonly focusedElementId: string | null;
}
export interface BrowserBounds {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}
export interface BrowserElement {
  readonly elementId: string;
  readonly parentElementId: string | null;
  readonly role: string;
  readonly name: string;
  readonly editable: boolean;
  readonly focused: boolean;
  readonly disabled: boolean;
  readonly bounds: BrowserBounds | null;
}
/** Bounded semantic DOM approximation; this does not claim complete accessibility-tree fidelity. */
export interface BrowserSnapshot {
  readonly snapshotId: string;
  readonly rootElementId: string | null;
  readonly guard: BrowserStateGuard;
  readonly capturedAt: string;
  readonly kind: 'dom-semantic';
  readonly approximate: true;
  readonly nodes: readonly BrowserElement[];
  readonly truncated: boolean;
}
export interface BrowserObservation {
  readonly image: CapturedImage;
  readonly state: BrowserState;
  /** null only when structured=false was requested. No implicit success for unavailable structure. */
  readonly snapshot: BrowserSnapshot | null;
}
export interface BrowserTab {
  readonly state: BrowserState;
  readonly title: string;
}
export interface BrowserWindows {
  readonly windowsId: string;
  readonly capturedAt: string;
  readonly tabs: readonly BrowserTab[];
  readonly truncated: boolean;
}
export type BrowserActionTarget =
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
export type PreparedBrowserTarget =
  | { readonly kind: 'point'; readonly x: number; readonly y: number }
  | {
      readonly kind: 'element';
      readonly snapshotId: string;
      readonly elementId: string;
    };
export type BrowserElementTarget = Extract<
  BrowserActionTarget,
  { readonly kind: 'element' }
>;
export type BrowserButton = 'left' | 'middle' | 'right';
export type BrowserModifier = 'Alt' | 'Control' | 'Meta' | 'Shift';
/** Text insertion is a separate operation; only these keys/chords are exposed. */
export type BrowserKey =
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
export interface BrowserDragPoint {
  readonly x: number;
  readonly y: number;
  readonly atMs: number;
}
export type PreparedBrowserAction =
  | { readonly kind: 'move'; readonly target: PreparedBrowserTarget }
  | {
      readonly kind: 'click' | 'double_click';
      readonly target: PreparedBrowserTarget;
      readonly button: BrowserButton;
      readonly modifiers: readonly BrowserModifier[];
    }
  | {
      readonly kind: 'drag';
      readonly points: readonly BrowserDragPoint[];
      readonly button: BrowserButton;
      readonly modifiers: readonly BrowserModifier[];
    }
  | {
      readonly kind: 'type';
      readonly target: BrowserElementTarget;
      readonly mode: 'insert' | 'replace';
      readonly text: string;
    }
  | {
      readonly kind: 'key';
      readonly expectedFocus: BrowserElementTarget | null;
      readonly key: BrowserKey;
      readonly modifiers: readonly BrowserModifier[];
    }
  | {
      readonly kind: 'scroll';
      readonly target: PreparedBrowserTarget;
      readonly dx: number;
      readonly dy: number;
      readonly units: 'pixels' | 'lines';
    };

export type BrowserDialogType = 'alert' | 'confirm' | 'prompt' | 'beforeunload';
/**
 * One anticipated dialog, scoped to ONE initiating invocation. Matching does not
 * authorize a second dialog. promptText additionally requires the type capability.
 * A mismatch/unexpected/second dialog stops intake and enters bounded cleanup;
 * this contract offers no later interactive modal-recovery workflow.
 */
export interface AnticipatedDialog {
  readonly expectedType: BrowserDialogType;
  readonly decision: 'accept' | 'dismiss';
  readonly exactMessage?: string;
  readonly promptText?: string;
}
export interface BrowserDialogEvent {
  readonly type: BrowserDialogType;
  readonly message: string;
  readonly messageTruncated: boolean;
  readonly decision: 'accept' | 'dismiss';
}
export type BrowserInputMechanism =
  'mouse' | 'keyboard' | 'insertText' | 'fill' | 'wheel' | 'focus' | 'goto';
/**
 * PARTIAL records proven adapter/protocol delivery, not DOM insertion/business
 * success and not a guess from elapsed time. A page can reject/revert delivered
 * events. UNCERTAIN remains immutable UNKNOWN even after separate cleanup proves
 * physical settlement; neither outcome authorizes replay.
 */
export type BrowserDeliveredExtent =
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
      readonly insertedUtf16Units: number;
      readonly cleared: boolean;
    }
  | { readonly kind: 'key'; readonly keyDowns: number; readonly keyUps: number }
  | (BrowserScrollConversion & { readonly kind: 'scroll' });

/** Bounded actual wheel conversion; delivery does not establish resulting DOM scroll. */
export interface BrowserScrollConversion {
  readonly dx: number;
  readonly dy: number;
  readonly units: 'css-pixels';
  readonly requestedUnits: 'pixels' | 'lines';
  readonly pixelsPerUnit: number;
}
export type BrowserDelivery =
  | {
      readonly outcome: 'NOT_DISPATCHED';
      readonly physicalSettlement: 'settled';
      readonly code: BrowserControlCode;
    }
  | {
      readonly outcome: 'DISPATCHED';
      readonly physicalSettlement: 'settled';
      readonly mechanism: Exclude<BrowserInputMechanism, 'wheel'>;
    }
  | {
      readonly outcome: 'DISPATCHED';
      readonly physicalSettlement: 'settled';
      readonly mechanism: 'wheel';
      readonly scroll: BrowserScrollConversion;
    }
  | {
      readonly outcome: 'PARTIAL';
      readonly physicalSettlement: 'settled';
      readonly delivered: BrowserDeliveredExtent;
      readonly code: BrowserControlCode;
    }
  | {
      readonly outcome: 'UNCERTAIN';
      readonly physicalSettlement: 'settled' | 'uncertain';
      readonly code: BrowserControlCode;
    };
export type BrowserPostObservation =
  | { readonly state: 'CAPTURED'; readonly value: BrowserObservation }
  | { readonly state: 'FAILED'; readonly code: BrowserControlCode };
export interface BrowserActionResult {
  readonly delivery: BrowserDelivery;
  readonly observation: BrowserPostObservation;
  readonly dialog: BrowserDialogEvent | null;
}
export interface BrowserCleanupResult {
  readonly physicalSettlement: 'settled' | 'uncertain';
  readonly heldInputsReleased: boolean;
}
export interface BrowserObserveRequest {
  readonly root?: BrowserElementTarget;
  readonly surfaceId: string;
  readonly structured: boolean;
}
export interface BrowserFocusRequest {
  readonly windowsId: string;
  readonly surfaceId: string;
  readonly observationReserveMs: number;
}
export interface BrowserNavigateRequest {
  readonly expected: BrowserStateGuard;
  readonly url: string;
  readonly dialogDecision?: AnticipatedDialog;
  readonly observationReserveMs: number;
}
export interface BrowserPerformRequest {
  readonly expected: BrowserStateGuard;
  readonly action: PreparedBrowserAction;
  readonly dialogDecision?: AnticipatedDialog;
  readonly observationReserveMs: number;
}

/**
 * Reusable browser capability, independent of clients and the Plowshare socket.
 * Acquire binds the validated call owner/session to a fresh isolated context;
 * another binding cannot acquire the target until release proves quiescence.
 * Before context creation/navigation, acquire durably fsyncs a stable target
 * ownership marker independent of provider binding/catalogue/session/generation.
 * It remains for the whole lease. Never steal by PID/age; after a crash retain
 * the marker until explicit physical recovery. Release removes it only after
 * original RPCs, owned inputs and the exact context are quiescent. Uncertain
 * filesystem cleanup retains the lease/fence. No extra per-action replay log is
 * implied: this lease predates all input and never authorizes action replay.
 * No storage/cookies or element references survive release into the next lease.
 *
 * Every action rechecks actual document/layout/focus and exact connected handles
 * before dispatch. expectedFocus:null means observed body/no-element focus, NEVER
 * a wildcard. Quarantined tabs permit listing/focus/new explicit navigation only;
 * input needs a ready HTTP(S) document and installed redirect guard. Child-frame
 * navigation and targeting are refused; do not claim OOPIF coverage.
 *
 * Strict argument/reference validation happens before dispatch. These action
 * methods return typed delivery for EVERY known post-admission outcome. An
 * unexpected thrown action error (even BrowserControlError) is not proof of a
 * pre-input refusal: the provider must treat it as UNKNOWN and fence the lease.
 * Only an explicit NOT_DISPATCHED result establishes a known backend refusal.
 *
 * Each input reserves post-observation time before dispatch. A known delivery
 * plus failed observation is COMPLETED text-only; unresolved delivery is UNKNOWN
 * text-only and fences the session. Never retry the original input to recover.
 * Internal drag/chord holds are <=1s and cleanup releases ONLY owned inputs.
 * Drag atMs starts at0 and is strictly increasing, with final atMs<=1000.
 * A settled delivery includes physical RPC settlement and owned-input release;
 * UI/business verification remains a separate observation.
 * Subtree observation resolves the exact old root handle before replacing refs;
 * its fresh snapshot is bounded and explicitly names its returned subtree root.
 */
export interface BrowserControl {
  readonly allowedTools: readonly BrowserToolName[];
  acquire(
    request: BrowserAcquireRequest,
    deadline: OperationDeadline,
  ): Promise<BrowserLease>;
  release(
    lease: BrowserLease,
    deadline: OperationDeadline,
  ): Promise<BrowserCleanupResult>;
  windows(
    lease: BrowserLease,
    deadline: OperationDeadline,
  ): Promise<BrowserWindows>;
  observe(
    lease: BrowserLease,
    request: BrowserObserveRequest,
    deadline: OperationDeadline,
  ): Promise<BrowserObservation>;
  focus(
    lease: BrowserLease,
    request: BrowserFocusRequest,
    deadline: OperationDeadline,
  ): Promise<BrowserActionResult>;
  navigate(
    lease: BrowserLease,
    request: BrowserNavigateRequest,
    deadline: OperationDeadline,
  ): Promise<BrowserActionResult>;
  perform(
    lease: BrowserLease,
    request: BrowserPerformRequest,
    deadline: OperationDeadline,
  ): Promise<BrowserActionResult>;
}
/**
 * Separate controlled factory only. Its inherited bare capture ALWAYS refuses,
 * even without a lease. Authorized observations go through browserControl.observe.
 * The original capture-only factory/ComputerBackend API remains unchanged.
 *
 * Provider/session state must bind frame/snapshot/windows refs to owner+generation.
 * Controlled idle expiry/close queues bounded cleanup and retains occupancy until
 * release proves quiescence; the P1 idle-remove path must not free controlled work.
 */
export interface BrowserControlBackend extends ComputerBackend {
  readonly browserControl: BrowserControl;
}

function invalid(): never {
  throw new BrowserControlError('INVALID_ARGUMENT');
}
function row(
  value: unknown,
  keys: readonly string[],
): Readonly<Record<string, unknown>> {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    invalid();
  const record = value as Readonly<Record<string, unknown>>;
  if (Object.keys(record).some((key) => !keys.includes(key))) invalid();
  return record;
}
function choice<T extends string>(value: unknown, options: readonly T[]): T {
  if (typeof value !== 'string' || !options.includes(value as T)) invalid();
  return value as T; // Complete finite allowlist above.
}
function text(value: unknown, maximum: number, empty = true): string {
  if (
    typeof value !== 'string' ||
    value.length > maximum ||
    (!empty && value.length === 0) ||
    Array.from(value).some((character) => {
      const n = character.codePointAt(0) ?? 0;
      return n === 0 || (n >= 0xd800 && n <= 0xdfff);
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
function boolean(value: unknown): boolean {
  if (typeof value !== 'boolean') invalid();
  return value;
}
function list(value: unknown, maximum: number): readonly unknown[] {
  if (!Array.isArray(value) || value.length > maximum) invalid();
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
const toolNames = [
  'windows',
  'focus',
  'navigate',
  'pointer',
  'type',
  'key',
  'scroll',
] as const;
const buttons = ['left', 'middle', 'right'] as const;
const modifierNames = ['Alt', 'Control', 'Meta', 'Shift'] as const;
const dialogTypes = ['alert', 'confirm', 'prompt', 'beforeunload'] as const;
const codes: readonly BrowserControlCode[] = [
  'INVALID_ARGUMENT',
  'CONTROL_DISABLED',
  'ORIGIN_DENIED',
  'NO_LEASE',
  'BUSY',
  'FENCED',
  'TARGET_GONE',
  'TAB_NOT_READY',
  'STALE_OBSERVATION',
  'FOCUS_MISMATCH',
  'NOT_EDITABLE',
  'SENSITIVE_TARGET',
  'UNSUPPORTED_FRAME',
  'INPUT_FAILED',
  'OBSERVATION_FAILED',
  'DEADLINE_EXPIRED',
  'UNEXPECTED_DIALOG',
  'DIALOG_MISMATCH',
  'CLEANUP_UNCERTAIN',
];
const keys: readonly BrowserKey[] = [
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

/** Exclude clipboard, file, print and browser-chrome shortcuts instead of hiding them in key. */
function safeChord(
  key: BrowserKey,
  selected: readonly BrowserModifier[],
): void {
  const chord = [...selected].sort().join('+') + ':' + key;
  const editing = [
    'Control:a',
    'Control:z',
    'Control:y',
    'Meta:a',
    'Meta:z',
    'Control+Shift:z',
    'Meta+Shift:z',
  ];
  if (editing.includes(chord)) return;
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

export function browserHttpUrl(value: unknown): string {
  const source = text(value, 4096, false);
  if (
    Array.from(source).some(
      (character) => (character.codePointAt(0) ?? 0) < 32,
    ) ||
    source.trim() !== source
  )
    invalid();
  let url: URL;
  try {
    url = new URL(source);
  } catch {
    return invalid();
  }
  if (
    !['http:', 'https:'].includes(url.protocol) ||
    url.hostname.includes('*') ||
    url.username ||
    url.password
  )
    invalid();
  if (url.href.length > 4096) invalid();
  return url.href;
}
export function decodeBrowserControlPolicy(
  value: unknown,
): BrowserControlPolicy {
  const record = row(value, ['enabled', 'tools', 'allowedOrigins']);
  if (record['enabled'] !== true) invalid();
  const tools = list(record['tools'], toolNames.length).map((item) =>
    choice(item, toolNames),
  );
  if (tools.length === 0 || new Set(tools).size !== tools.length) invalid();
  const allowedOrigins = list(record['allowedOrigins'], 32).map((item) => {
    const source = browserHttpUrl(item),
      url = new URL(source);
    if (url.pathname !== '/' || url.search || url.hash) invalid();
    return url.origin;
  });
  if (
    allowedOrigins.length === 0 ||
    new Set(allowedOrigins).size !== allowedOrigins.length
  )
    invalid();
  return Object.freeze({
    enabled: true,
    tools: Object.freeze(tools),
    allowedOrigins: Object.freeze(allowedOrigins),
  });
}
export function permittedBrowserUrl(
  policy: BrowserControlPolicy,
  value: unknown,
): string {
  const checked = decodeBrowserControlPolicy(policy),
    url = browserHttpUrl(value);
  if (!checked.allowedOrigins.includes(new URL(url).origin))
    throw new BrowserControlError('ORIGIN_DENIED');
  return url;
}
export function decodeBrowserLease(value: unknown): BrowserLease {
  const record = row(value, ['leaseId', 'targetId', 'sessionId', 'generation']);
  return Object.freeze({
    leaseId: id(record['leaseId']),
    targetId: id(record['targetId']),
    sessionId: id(record['sessionId']),
    generation: id(record['generation']),
  });
}
export function decodeBrowserAcquireRequest(
  value: unknown,
): BrowserAcquireRequest {
  const record = row(value, ['owner', 'session', 'targetId']),
    session = row(record['session'], ['sessionId', 'generation']);
  return Object.freeze({
    targetId: id(record['targetId']),
    owner: decodeOwner(record['owner']),
    session: Object.freeze({
      sessionId: id(session['sessionId']),
      generation: id(session['generation']),
    }),
  });
}
export function decodeBrowserStateGuard(value: unknown): BrowserStateGuard {
  const record = row(value, [
    'surfaceId',
    'documentId',
    'layoutGeneration',
    'focusGeneration',
  ]);
  return Object.freeze({
    surfaceId: id(record['surfaceId']),
    documentId: id(record['documentId']),
    layoutGeneration: id(record['layoutGeneration']),
    focusGeneration: id(record['focusGeneration']),
  });
}
export function decodeBrowserState(value: unknown): BrowserState {
  const record = row(value, [
    'surfaceId',
    'documentId',
    'layoutGeneration',
    'focusGeneration',
    'readiness',
    'guardInstalled',
    'url',
    'origin',
    'requestedUrl',
    'focusedElementId',
  ]);
  const guard = decodeBrowserStateGuard({
    surfaceId: record['surfaceId'],
    documentId: record['documentId'],
    layoutGeneration: record['layoutGeneration'],
    focusGeneration: record['focusGeneration'],
  });
  const readiness = choice(record['readiness'], [
      'ready',
      'quarantined',
      'closed',
    ]),
    guardInstalled = boolean(record['guardInstalled']);
  const url = record['url'] === null ? null : text(record['url'], 4096, false),
    origin =
      record['origin'] === null ? null : text(record['origin'], 4096, false);
  const requestedUrl =
      record['requestedUrl'] === null
        ? null
        : browserHttpUrl(record['requestedUrl']),
    focusedElementId = nullableId(record['focusedElementId']);
  if (readiness === 'ready') {
    if (
      !guardInstalled ||
      url === null ||
      browserHttpUrl(url) !== url ||
      new URL(url).origin !== origin ||
      requestedUrl !== null
    )
      invalid();
  } else {
    if (origin !== null || focusedElementId !== null) invalid();
    if (readiness === 'closed' && (url !== null || guardInstalled)) invalid();
    if (
      readiness === 'quarantined' &&
      url !== 'about:blank' &&
      url !== 'chrome-error://chromewebdata/'
    )
      invalid();
  }
  return Object.freeze({
    ...guard,
    readiness,
    guardInstalled,
    url,
    origin,
    requestedUrl,
    focusedElementId,
  });
}
export function sameBrowserGuard(
  a: BrowserStateGuard,
  b: BrowserStateGuard,
): boolean {
  return (
    a.surfaceId === b.surfaceId &&
    a.documentId === b.documentId &&
    a.layoutGeneration === b.layoutGeneration &&
    a.focusGeneration === b.focusGeneration
  );
}
export function requireReadyBrowserState(value: BrowserState): BrowserState {
  const state = decodeBrowserState(value);
  if (state.readiness !== 'ready' || !state.guardInstalled)
    throw new BrowserControlError('TAB_NOT_READY');
  return state;
}
/** Snapshot ownership is checked by sessions; this checks the live guard and exact focus expectation. */
export function requireBrowserActionState(
  actual: BrowserState,
  expected: BrowserStateGuard,
  action: PreparedBrowserAction,
): void {
  const state = requireReadyBrowserState(actual),
    guard = decodeBrowserStateGuard(expected),
    checked = decodePreparedBrowserAction(action);
  if (!sameBrowserGuard(state, guard))
    throw new BrowserControlError('STALE_OBSERVATION');
  if (
    checked.kind === 'type' &&
    state.focusedElementId !== checked.target.elementId
  )
    throw new BrowserControlError('FOCUS_MISMATCH');
  if (
    checked.kind === 'key' &&
    state.focusedElementId !== (checked.expectedFocus?.elementId ?? null)
  )
    throw new BrowserControlError('FOCUS_MISMATCH');
}
/** A quarantined owned tab may only enter ready state through a new explicit guarded navigation. */
export function requireBrowserNavigationState(
  actual: BrowserState,
  expected: BrowserStateGuard,
): void {
  const state = decodeBrowserState(actual),
    guard = decodeBrowserStateGuard(expected);
  if (state.readiness === 'closed' || !state.guardInstalled)
    throw new BrowserControlError('TAB_NOT_READY');
  if (!sameBrowserGuard(state, guard))
    throw new BrowserControlError('STALE_OBSERVATION');
}
function bounds(value: unknown): BrowserBounds {
  const record = row(value, ['x', 'y', 'width', 'height']);
  return Object.freeze({
    x: numeric(record['x'], -32768, 32768),
    y: numeric(record['y'], -32768, 32768),
    width: numeric(record['width'], 0, 32768),
    height: numeric(record['height'], 0, 32768),
  });
}
export function decodeBrowserSnapshot(value: unknown): BrowserSnapshot {
  const record = row(value, [
    'snapshotId',
    'rootElementId',
    'guard',
    'capturedAt',
    'kind',
    'approximate',
    'nodes',
    'truncated',
  ]);
  if (record['kind'] !== 'dom-semantic' || record['approximate'] !== true)
    invalid();
  const nodes = list(record['nodes'], 200).map((value): BrowserElement => {
    const element = row(value, [
      'elementId',
      'parentElementId',
      'role',
      'name',
      'editable',
      'focused',
      'disabled',
      'bounds',
    ]);
    return Object.freeze({
      elementId: id(element['elementId']),
      parentElementId: nullableId(element['parentElementId']),
      role: text(element['role'], 64, false),
      name: text(element['name'], 256),
      editable: boolean(element['editable']),
      focused: boolean(element['focused']),
      disabled: boolean(element['disabled']),
      bounds: element['bounds'] === null ? null : bounds(element['bounds']),
    });
  });
  const ids = new Set(nodes.map((node) => node.elementId));
  if (
    ids.size !== nodes.length ||
    nodes.filter((node) => node.focused).length > 1 ||
    nodes.some(
      (node) =>
        node.parentElementId !== null &&
        (!ids.has(node.parentElementId) ||
          node.parentElementId === node.elementId),
    )
  )
    invalid();
  // A parent must precede its child, forbidding cycles without unbounded traversal.
  const seen = new Set<string>();
  for (const node of nodes) {
    if (node.parentElementId !== null && !seen.has(node.parentElementId))
      invalid();
    seen.add(node.elementId);
  }
  const rootElementId = nullableId(record['rootElementId']);
  if (
    rootElementId !== null &&
    (nodes[0]?.elementId !== rootElementId ||
      nodes[0]?.parentElementId !== null ||
      nodes.slice(1).some((node) => node.parentElementId === null))
  )
    invalid();
  return Object.freeze({
    snapshotId: id(record['snapshotId']),
    rootElementId,
    guard: decodeBrowserStateGuard(record['guard']),
    capturedAt: isoTimestamp(record['capturedAt']),
    kind: 'dom-semantic',
    approximate: true,
    nodes: Object.freeze(nodes),
    truncated: boolean(record['truncated']),
  });
}
export function decodeBrowserObservation(value: unknown): BrowserObservation {
  const record = row(value, ['image', 'state', 'snapshot']),
    image = decodeCapturedImage(record['image']),
    state = requireReadyBrowserState(decodeBrowserState(record['state']));
  const snapshot =
    record['snapshot'] === null
      ? null
      : decodeBrowserSnapshot(record['snapshot']);
  if (
    image.layoutGeneration !== state.layoutGeneration ||
    (snapshot !== null && !sameBrowserGuard(snapshot.guard, state))
  )
    invalid();
  if (snapshot !== null) {
    const focused =
      snapshot.nodes.find((node) => node.focused)?.elementId ?? null;
    if (focused !== null && focused !== state.focusedElementId) invalid();
    const declaredFocus = snapshot.nodes.find(
      (node) => node.elementId === state.focusedElementId,
    );
    if (declaredFocus !== undefined && !declaredFocus.focused) invalid();
    if (
      snapshot.rootElementId === null &&
      !snapshot.truncated &&
      focused !== state.focusedElementId
    )
      invalid();
  }
  return Object.freeze({ image, state, snapshot });
}
export function decodeBrowserWindows(value: unknown): BrowserWindows {
  const record = row(value, ['windowsId', 'capturedAt', 'tabs', 'truncated']);
  const tabs = list(record['tabs'], 32).map((value): BrowserTab => {
    const tab = row(value, ['state', 'title']);
    return Object.freeze({
      state: decodeBrowserState(tab['state']),
      title: text(tab['title'], 256),
    });
  });
  if (new Set(tabs.map((tab) => tab.state.surfaceId)).size !== tabs.length)
    invalid();
  return Object.freeze({
    windowsId: id(record['windowsId']),
    capturedAt: isoTimestamp(record['capturedAt']),
    tabs: Object.freeze(tabs),
    truncated: boolean(record['truncated']),
  });
}
export function decodeBrowserActionTarget(value: unknown): BrowserActionTarget {
  const record = row(value, [
    'kind',
    'frameId',
    'x',
    'y',
    'snapshotId',
    'elementId',
  ]);
  if (record['kind'] === 'point') {
    row(value, ['kind', 'frameId', 'x', 'y']);
    return Object.freeze({
      kind: 'point',
      frameId: id(record['frameId']),
      x: numeric(record['x'], 0, 32767, true),
      y: numeric(record['y'], 0, 32767, true),
    });
  }
  if (record['kind'] === 'element') {
    row(value, ['kind', 'snapshotId', 'elementId']);
    return Object.freeze({
      kind: 'element',
      snapshotId: id(record['snapshotId']),
      elementId: id(record['elementId']),
    });
  }
  return invalid();
}
function preparedTarget(value: unknown): PreparedBrowserTarget {
  const record = row(value, ['kind', 'x', 'y', 'snapshotId', 'elementId']);
  if (record['kind'] === 'point') {
    row(value, ['kind', 'x', 'y']);
    return Object.freeze({
      kind: 'point',
      x: numeric(record['x'], 0, 32768),
      y: numeric(record['y'], 0, 32768),
    });
  }
  const target = decodeBrowserActionTarget(value);
  if (target.kind !== 'element') invalid();
  return target;
}
function elementTarget(value: unknown): BrowserElementTarget {
  const target = decodeBrowserActionTarget(value);
  if (target.kind !== 'element') invalid();
  return target;
}
function modifiers(value: unknown): readonly BrowserModifier[] {
  const result = list(value, 4).map((item) => choice(item, modifierNames));
  if (new Set(result).size !== result.length) invalid();
  return Object.freeze(result);
}
export function decodeAnticipatedDialog(value: unknown): AnticipatedDialog {
  const record = row(value, [
    'expectedType',
    'decision',
    'exactMessage',
    'promptText',
  ]);
  const expectedType = choice(record['expectedType'], dialogTypes),
    decision = choice(record['decision'], ['accept', 'dismiss']);
  if (
    record['promptText'] !== undefined &&
    (expectedType !== 'prompt' || decision !== 'accept')
  )
    invalid();
  return Object.freeze({
    expectedType,
    decision,
    ...(record['exactMessage'] === undefined
      ? {}
      : { exactMessage: text(record['exactMessage'], 1024) }),
    ...(record['promptText'] === undefined
      ? {}
      : { promptText: text(record['promptText'], 4096) }),
  });
}
export function decodePreparedBrowserAction(
  value: unknown,
): PreparedBrowserAction {
  const record = row(value, [
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
  switch (record['kind']) {
    case 'move':
      row(value, ['kind', 'target']);
      return Object.freeze({
        kind: 'move',
        target: preparedTarget(record['target']),
      });
    case 'click':
    case 'double_click':
      row(value, ['kind', 'target', 'button', 'modifiers']);
      return Object.freeze({
        kind: record['kind'],
        target: preparedTarget(record['target']),
        button: choice(record['button'], buttons),
        modifiers: modifiers(record['modifiers']),
      });
    case 'drag': {
      row(value, ['kind', 'points', 'button', 'modifiers']);
      const points = list(record['points'], 128).map(
        (value): BrowserDragPoint => {
          const point = row(value, ['x', 'y', 'atMs']);
          return Object.freeze({
            x: numeric(point['x'], 0, 32768),
            y: numeric(point['y'], 0, 32768),
            atMs: numeric(point['atMs'], 0, 1000, true),
          });
        },
      );
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
        button: choice(record['button'], buttons),
        modifiers: modifiers(record['modifiers']),
      });
    }
    case 'type': {
      row(value, ['kind', 'target', 'mode', 'text']);
      const mode = choice(record['mode'], ['insert', 'replace']);
      return Object.freeze({
        kind: 'type',
        target: elementTarget(record['target']),
        mode,
        text: text(record['text'], 4096, mode === 'replace'),
      });
    }
    case 'key': {
      row(value, ['kind', 'expectedFocus', 'key', 'modifiers']);
      const key = choice(record['key'], keys),
        selected = modifiers(record['modifiers']);
      safeChord(key, selected);
      return Object.freeze({
        kind: 'key',
        expectedFocus:
          record['expectedFocus'] === null
            ? null
            : elementTarget(record['expectedFocus']),
        key,
        modifiers: selected,
      });
    }
    case 'scroll': {
      row(value, ['kind', 'target', 'dx', 'dy', 'units']);
      const units = choice(record['units'], ['pixels', 'lines']),
        maximum = units === 'lines' ? 128 : 32768;
      const dx = numeric(record['dx'], -maximum, maximum),
        dy = numeric(record['dy'], -maximum, maximum);
      if (dx === 0 && dy === 0) invalid();
      return Object.freeze({
        kind: 'scroll',
        target: preparedTarget(record['target']),
        dx,
        dy,
        units,
      });
    }
    default:
      return invalid();
  }
}
function reserve(value: unknown): number {
  if (typeof value !== 'number') invalid();
  return positiveBudget(value);
}
export function decodeBrowserPerformRequest(
  value: unknown,
): BrowserPerformRequest {
  const record = row(value, [
    'expected',
    'action',
    'dialogDecision',
    'observationReserveMs',
  ]);
  return Object.freeze({
    expected: decodeBrowserStateGuard(record['expected']),
    action: decodePreparedBrowserAction(record['action']),
    observationReserveMs: reserve(record['observationReserveMs']),
    ...(record['dialogDecision'] === undefined
      ? {}
      : { dialogDecision: decodeAnticipatedDialog(record['dialogDecision']) }),
  });
}
export function decodeBrowserNavigateRequest(
  value: unknown,
): BrowserNavigateRequest {
  const record = row(value, [
    'expected',
    'url',
    'dialogDecision',
    'observationReserveMs',
  ]);
  return Object.freeze({
    expected: decodeBrowserStateGuard(record['expected']),
    url: browserHttpUrl(record['url']),
    observationReserveMs: reserve(record['observationReserveMs']),
    ...(record['dialogDecision'] === undefined
      ? {}
      : { dialogDecision: decodeAnticipatedDialog(record['dialogDecision']) }),
  });
}
export function decodeBrowserFocusRequest(value: unknown): BrowserFocusRequest {
  const record = row(value, ['windowsId', 'surfaceId', 'observationReserveMs']);
  return Object.freeze({
    windowsId: id(record['windowsId']),
    surfaceId: id(record['surfaceId']),
    observationReserveMs: reserve(record['observationReserveMs']),
  });
}
export function decodeBrowserObserveRequest(
  value: unknown,
): BrowserObserveRequest {
  const record = row(value, ['surfaceId', 'structured', 'root']);
  const structured = boolean(record['structured']);
  if (!structured && record['root'] !== undefined) invalid();
  return Object.freeze({
    surfaceId: id(record['surfaceId']),
    structured,
    ...(record['root'] === undefined
      ? {}
      : { root: elementTarget(record['root']) }),
  });
}

export function decodeBrowserScrollConversion(
  value: unknown,
): BrowserScrollConversion {
  const record = row(value, [
    'dx',
    'dy',
    'units',
    'requestedUnits',
    'pixelsPerUnit',
  ]);
  if (record['units'] !== 'css-pixels') invalid();
  const requestedUnits = choice(record['requestedUnits'], ['pixels', 'lines']);
  const pixelsPerUnit = numeric(record['pixelsPerUnit'], Number.EPSILON, 256);
  if (requestedUnits === 'pixels' && pixelsPerUnit !== 1) invalid();
  const dx = numeric(record['dx'], -32768, 32768),
    dy = numeric(record['dy'], -32768, 32768);
  if (dx === 0 && dy === 0) invalid();
  return Object.freeze({
    dx,
    dy,
    units: 'css-pixels',
    requestedUnits,
    pixelsPerUnit,
  });
}
/** Must run before wheel dispatch; the accepted line/pixel bounds guarantee bounded conversion. */
export function browserScrollConversion(
  action: Extract<PreparedBrowserAction, { readonly kind: 'scroll' }>,
  pixelsPerLine: number,
): BrowserScrollConversion {
  const checked = decodePreparedBrowserAction(action);
  if (checked.kind !== 'scroll') invalid();
  const ratio =
    checked.units === 'pixels'
      ? 1
      : numeric(pixelsPerLine, Number.EPSILON, 256);
  return decodeBrowserScrollConversion({
    dx: checked.dx * ratio,
    dy: checked.dy * ratio,
    units: 'css-pixels',
    requestedUnits: checked.units,
    pixelsPerUnit: ratio,
  });
}

function deliveredExtent(value: unknown): BrowserDeliveredExtent {
  const record = row(value, [
    'kind',
    'moves',
    'clicks',
    'points',
    'buttonReleased',
    'insertedUtf16Units',
    'cleared',
    'keyDowns',
    'keyUps',
    'dx',
    'dy',
    'units',
    'requestedUnits',
    'pixelsPerUnit',
  ]);
  switch (record['kind']) {
    case 'pointer': {
      row(value, ['kind', 'moves', 'clicks']);
      const moves = numeric(record['moves'], 0, 1, true),
        clicks = numeric(record['clicks'], 0, 2, true);
      if (moves + clicks === 0) invalid();
      return Object.freeze({ kind: 'pointer', moves, clicks });
    }
    case 'drag':
      row(value, ['kind', 'points', 'buttonReleased']);
      if (record['buttonReleased'] !== true) invalid();
      return Object.freeze({
        kind: 'drag',
        points: numeric(record['points'], 1, 128, true),
        buttonReleased: true,
      });
    case 'type': {
      row(value, ['kind', 'insertedUtf16Units', 'cleared']);
      const insertedUtf16Units = numeric(
          record['insertedUtf16Units'],
          0,
          4096,
          true,
        ),
        cleared = boolean(record['cleared']);
      if (insertedUtf16Units === 0 && !cleared) invalid();
      return Object.freeze({ kind: 'type', insertedUtf16Units, cleared });
    }
    case 'key': {
      row(value, ['kind', 'keyDowns', 'keyUps']);
      const keyDowns = numeric(record['keyDowns'], 1, 5, true),
        keyUps = numeric(record['keyUps'], 1, 5, true);
      if (keyDowns !== keyUps) invalid();
      return Object.freeze({ kind: 'key', keyDowns, keyUps });
    }
    case 'scroll': {
      row(value, [
        'kind',
        'dx',
        'dy',
        'units',
        'requestedUnits',
        'pixelsPerUnit',
      ]);
      return Object.freeze({
        kind: 'scroll',
        ...decodeBrowserScrollConversion({
          dx: record['dx'],
          dy: record['dy'],
          units: record['units'],
          requestedUnits: record['requestedUnits'],
          pixelsPerUnit: record['pixelsPerUnit'],
        }),
      });
    }
    default:
      return invalid();
  }
}
export function decodeBrowserDelivery(value: unknown): BrowserDelivery {
  const record = row(value, [
    'outcome',
    'physicalSettlement',
    'code',
    'mechanism',
    'delivered',
    'scroll',
  ]);
  switch (record['outcome']) {
    case 'NOT_DISPATCHED':
      row(value, ['outcome', 'physicalSettlement', 'code']);
      if (record['physicalSettlement'] !== 'settled') invalid();
      return Object.freeze({
        outcome: 'NOT_DISPATCHED',
        physicalSettlement: 'settled',
        code: choice(record['code'], codes),
      });
    case 'DISPATCHED': {
      if (record['physicalSettlement'] !== 'settled') invalid();
      if (record['mechanism'] === 'wheel') {
        row(value, ['outcome', 'physicalSettlement', 'mechanism', 'scroll']);
        return Object.freeze({
          outcome: 'DISPATCHED',
          physicalSettlement: 'settled',
          mechanism: 'wheel',
          scroll: decodeBrowserScrollConversion(record['scroll']),
        });
      }
      row(value, ['outcome', 'physicalSettlement', 'mechanism']);
      return Object.freeze({
        outcome: 'DISPATCHED',
        physicalSettlement: 'settled',
        mechanism: choice(record['mechanism'], [
          'mouse',
          'keyboard',
          'insertText',
          'fill',
          'focus',
          'goto',
        ]),
      });
    }
    case 'PARTIAL':
      row(value, ['outcome', 'physicalSettlement', 'code', 'delivered']);
      if (record['physicalSettlement'] !== 'settled') invalid();
      return Object.freeze({
        outcome: 'PARTIAL',
        physicalSettlement: 'settled',
        code: choice(record['code'], codes),
        delivered: deliveredExtent(record['delivered']),
      });
    case 'UNCERTAIN':
      row(value, ['outcome', 'physicalSettlement', 'code']);
      return Object.freeze({
        outcome: 'UNCERTAIN',
        physicalSettlement: choice(record['physicalSettlement'], [
          'settled',
          'uncertain',
        ]),
        code: choice(record['code'], codes),
      });
    default:
      return invalid();
  }
}
export function decodeBrowserActionResult(value: unknown): BrowserActionResult {
  const record = row(value, ['delivery', 'observation', 'dialog']),
    delivery = decodeBrowserDelivery(record['delivery']);
  const observed = row(record['observation'], ['state', 'value', 'code']);
  let observation: BrowserPostObservation;
  if (observed['state'] === 'CAPTURED') {
    row(record['observation'], ['state', 'value']);
    observation = Object.freeze({
      state: 'CAPTURED',
      value: decodeBrowserObservation(observed['value']),
    });
  } else {
    row(record['observation'], ['state', 'code']);
    if (observed['state'] !== 'FAILED') invalid();
    observation = Object.freeze({
      state: 'FAILED',
      code: choice(observed['code'], codes),
    });
  }
  if (
    (delivery.outcome === 'UNCERTAIN' ||
      delivery.outcome === 'NOT_DISPATCHED') &&
    observation.state === 'CAPTURED'
  )
    invalid();
  let dialog: BrowserDialogEvent | null = null;
  if (record['dialog'] !== null) {
    const event = row(record['dialog'], [
      'type',
      'message',
      'messageTruncated',
      'decision',
    ]);
    dialog = Object.freeze({
      type: choice(event['type'], dialogTypes),
      message: text(event['message'], 1024),
      messageTruncated: boolean(event['messageTruncated']),
      decision: choice(event['decision'], ['accept', 'dismiss']),
    });
  }
  return Object.freeze({ delivery, observation, dialog });
}
export function decodeBrowserCleanupResult(
  value: unknown,
): BrowserCleanupResult {
  const record = row(value, ['physicalSettlement', 'heldInputsReleased']),
    physicalSettlement = choice(record['physicalSettlement'], [
      'settled',
      'uncertain',
    ]),
    heldInputsReleased = boolean(record['heldInputsReleased']);
  if (physicalSettlement === 'settled' && !heldInputsReleased) invalid();
  return Object.freeze({ physicalSettlement, heldInputsReleased });
}
