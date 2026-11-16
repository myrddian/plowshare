import type { SessionReference } from './contracts.ts';
import { computerId } from './contracts.ts';
import {
  BrowserControlError,
  browserHttpUrl,
  decodeAnticipatedDialog,
  decodeBrowserActionTarget,
  decodePreparedBrowserAction,
} from './browser-control.ts';
import type {
  AnticipatedDialog,
  BrowserActionTarget,
  BrowserButton,
  BrowserDragPoint,
  BrowserElementTarget,
  BrowserModifier,
  BrowserToolName,
  PreparedBrowserAction,
} from './browser-control.ts';

/** Public model arguments contain references, never backend leases or supplied state guards. */
export interface BrowserObserveArguments extends SessionReference {
  readonly surfaceId: string;
  readonly structured: boolean;
  readonly root?: BrowserElementTarget;
}
export interface BrowserFocusArguments extends SessionReference {
  readonly windowsId: string;
  readonly surfaceId: string;
}
export type BrowserNavigationReference =
  | { readonly kind: 'frame'; readonly frameId: string }
  | {
      readonly kind: 'window';
      readonly windowsId: string;
      readonly surfaceId: string;
    };
export interface BrowserNavigateArguments extends SessionReference {
  readonly observation: BrowserNavigationReference;
  readonly url: string;
  readonly dialogDecision?: AnticipatedDialog;
}
export type BrowserPointerAction =
  | { readonly kind: 'move'; readonly target: BrowserActionTarget }
  | {
      readonly kind: 'click' | 'double_click';
      readonly target: BrowserActionTarget;
      readonly button: BrowserButton;
      readonly modifiers: readonly BrowserModifier[];
    }
  | {
      readonly kind: 'drag';
      readonly frameId: string;
      readonly points: readonly BrowserDragPoint[];
      readonly button: BrowserButton;
      readonly modifiers: readonly BrowserModifier[];
    };
export interface BrowserPointerArguments extends SessionReference {
  readonly action: BrowserPointerAction;
  readonly dialogDecision?: AnticipatedDialog;
}
export interface BrowserTypeArguments extends SessionReference {
  readonly action: Extract<PreparedBrowserAction, { readonly kind: 'type' }>;
  readonly dialogDecision?: AnticipatedDialog;
}
export interface BrowserKeyArguments extends SessionReference {
  readonly frameId: string;
  readonly action: Extract<PreparedBrowserAction, { readonly kind: 'key' }>;
  readonly dialogDecision?: AnticipatedDialog;
}
export interface BrowserScrollArguments extends SessionReference {
  readonly target: BrowserActionTarget;
  readonly dx: number;
  readonly dy: number;
  readonly units: 'pixels' | 'lines';
  readonly dialogDecision?: AnticipatedDialog;
}

function invalid(): never {
  throw new BrowserControlError('INVALID_ARGUMENT');
}
function object(
  value: unknown,
  fields: readonly string[],
): Readonly<Record<string, unknown>> {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    invalid();
  const row = value as Readonly<Record<string, unknown>>;
  if (Object.keys(row).some((key) => !fields.includes(key))) invalid();
  return row;
}
function id(value: unknown): string {
  try {
    return computerId(value);
  } catch {
    return invalid();
  }
}
function session(row: Readonly<Record<string, unknown>>): SessionReference {
  return { sessionId: id(row['sessionId']), generation: id(row['generation']) };
}
function number(
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
function choice<T extends string>(value: unknown, allowed: readonly T[]): T {
  for (const item of allowed) if (value === item) return item;
  return invalid();
}
function modifiers(value: unknown): readonly BrowserModifier[] {
  if (value === undefined) return Object.freeze([]);
  if (!Array.isArray(value) || value.length > 4) invalid();
  const items = value.map((item: unknown) =>
    choice<BrowserModifier>(item, ['Alt', 'Control', 'Meta', 'Shift']),
  );
  if (new Set(items).size !== items.length) invalid();
  return Object.freeze(items);
}
function dialog(row: Readonly<Record<string, unknown>>): {
  readonly dialogDecision?: AnticipatedDialog;
} {
  return row['dialogDecision'] === undefined
    ? {}
    : { dialogDecision: decodeAnticipatedDialog(row['dialogDecision']) };
}
function element(value: unknown): BrowserElementTarget {
  const target = decodeBrowserActionTarget(value);
  if (target.kind !== 'element') invalid();
  return target;
}

export function checkedBrowserTools(
  value: unknown,
): readonly BrowserToolName[] {
  if (!Array.isArray(value) || value.length > 7) invalid();
  const items = value.map((item: unknown) =>
    choice<BrowserToolName>(item, [
      'windows',
      'focus',
      'navigate',
      'pointer',
      'type',
      'key',
      'scroll',
    ]),
  );
  if (new Set(items).size !== items.length) invalid();
  // Catalogue and journal identities do not depend on configuration array order.
  return Object.freeze(items.sort());
}

export function decodeBrowserSessionArguments(
  value: unknown,
): SessionReference {
  return Object.freeze(session(object(value, ['sessionId', 'generation'])));
}
export function decodeBrowserObserveArguments(
  value: unknown,
): BrowserObserveArguments {
  const row = object(value, [
    'sessionId',
    'generation',
    'surfaceId',
    'structured',
    'root',
  ]);
  const structured = row['structured'] === undefined ? true : row['structured'];
  if (
    typeof structured !== 'boolean' ||
    (!structured && row['root'] !== undefined)
  )
    invalid();
  return Object.freeze({
    ...session(row),
    surfaceId: id(row['surfaceId']),
    structured,
    ...(row['root'] === undefined ? {} : { root: element(row['root']) }),
  });
}
export function decodeBrowserFocusArguments(
  value: unknown,
): BrowserFocusArguments {
  const row = object(value, [
    'sessionId',
    'generation',
    'windowsId',
    'surfaceId',
  ]);
  return Object.freeze({
    ...session(row),
    windowsId: id(row['windowsId']),
    surfaceId: id(row['surfaceId']),
  });
}
export function decodeBrowserNavigateArguments(
  value: unknown,
): BrowserNavigateArguments {
  const row = object(value, [
    'sessionId',
    'generation',
    'observation',
    'url',
    'dialogDecision',
  ]);
  const reference = object(row['observation'], [
    'kind',
    'frameId',
    'windowsId',
    'surfaceId',
  ]);
  let observation: BrowserNavigationReference;
  if (reference['kind'] === 'frame') {
    object(reference, ['kind', 'frameId']);
    observation = Object.freeze({
      kind: 'frame',
      frameId: id(reference['frameId']),
    });
  } else if (reference['kind'] === 'window') {
    object(reference, ['kind', 'windowsId', 'surfaceId']);
    observation = Object.freeze({
      kind: 'window',
      windowsId: id(reference['windowsId']),
      surfaceId: id(reference['surfaceId']),
    });
  } else return invalid();
  return Object.freeze({
    ...session(row),
    observation,
    url: browserHttpUrl(row['url']),
    ...dialog(row),
  });
}

export function decodeBrowserPointerArguments(
  value: unknown,
): BrowserPointerArguments {
  const row = object(value, [
    'sessionId',
    'generation',
    'action',
    'target',
    'frameId',
    'points',
    'button',
    'modifiers',
    'dialogDecision',
  ]);
  const kind = choice(row['action'], ['move', 'click', 'double_click', 'drag']);
  let action: BrowserPointerAction;
  if (kind === 'move') {
    object(value, [
      'sessionId',
      'generation',
      'action',
      'target',
      'dialogDecision',
    ]);
    action = Object.freeze({
      kind,
      target: decodeBrowserActionTarget(row['target']),
    });
  } else if (kind === 'drag') {
    object(value, [
      'sessionId',
      'generation',
      'action',
      'frameId',
      'points',
      'button',
      'modifiers',
      'dialogDecision',
    ]);
    const values = row['points'];
    if (!Array.isArray(values) || values.length < 2 || values.length > 128)
      invalid();
    const points = values.map((item: unknown): BrowserDragPoint => {
      const point = object(item, ['x', 'y', 'atMs']);
      return Object.freeze({
        x: number(point['x'], 0, 32767, true),
        y: number(point['y'], 0, 32767, true),
        atMs: number(point['atMs'], 0, 1000, true),
      });
    });
    if (
      points[0]?.atMs !== 0 ||
      points.some(
        (point, index) =>
          index > 0 && point.atMs <= (points[index - 1]?.atMs ?? 0),
      )
    )
      invalid();
    action = Object.freeze({
      kind,
      frameId: id(row['frameId']),
      points: Object.freeze(points),
      button: choice(row['button'] === undefined ? 'left' : row['button'], [
        'left',
        'middle',
        'right',
      ]),
      modifiers: modifiers(row['modifiers']),
    });
  } else {
    object(value, [
      'sessionId',
      'generation',
      'action',
      'target',
      'button',
      'modifiers',
      'dialogDecision',
    ]);
    action = Object.freeze({
      kind,
      target: decodeBrowserActionTarget(row['target']),
      button: choice(row['button'] === undefined ? 'left' : row['button'], [
        'left',
        'middle',
        'right',
      ]),
      modifiers: modifiers(row['modifiers']),
    });
  }
  return Object.freeze({ ...session(row), action, ...dialog(row) });
}
export function decodeBrowserTypeArguments(
  value: unknown,
): BrowserTypeArguments {
  const row = object(value, [
    'sessionId',
    'generation',
    'target',
    'mode',
    'text',
    'dialogDecision',
  ]);
  const action = decodePreparedBrowserAction({
    kind: 'type',
    target: row['target'],
    mode: row['mode'],
    text: row['text'],
  });
  if (action.kind !== 'type') return invalid();
  return Object.freeze({ ...session(row), action, ...dialog(row) });
}
export function decodeBrowserKeyArguments(value: unknown): BrowserKeyArguments {
  const row = object(value, [
    'sessionId',
    'generation',
    'frameId',
    'expectedFocus',
    'key',
    'modifiers',
    'dialogDecision',
  ]);
  const action = decodePreparedBrowserAction({
    kind: 'key',
    expectedFocus: row['expectedFocus'],
    key: row['key'],
    modifiers: modifiers(row['modifiers']),
  });
  if (action.kind !== 'key') return invalid();
  return Object.freeze({
    ...session(row),
    frameId: id(row['frameId']),
    action,
    ...dialog(row),
  });
}
export function decodeBrowserScrollArguments(
  value: unknown,
): BrowserScrollArguments {
  const row = object(value, [
    'sessionId',
    'generation',
    'target',
    'dx',
    'dy',
    'units',
    'dialogDecision',
  ]);
  const target = decodeBrowserActionTarget(row['target']);
  // Validate shared wheel semantics before preparing coordinates or dispatching.
  // A point remains an image reference here; conversion happens under its session lock.
  const action = decodePreparedBrowserAction({
    kind: 'scroll',
    target:
      target.kind === 'point'
        ? { kind: 'point', x: target.x, y: target.y }
        : target,
    dx: row['dx'],
    dy: row['dy'],
    units: row['units'],
  });
  if (action.kind !== 'scroll') return invalid();
  return Object.freeze({
    ...session(row),
    target,
    dx: action.dx,
    dy: action.dy,
    units: action.units,
    ...dialog(row),
  });
}
