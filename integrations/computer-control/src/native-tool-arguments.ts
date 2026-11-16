import type { SessionReference } from './contracts.ts';
import type { NativeToolName } from './native-control.ts';
import {
  NativeControlError,
  decodePreparedNativeAction,
} from './native-control.ts';
import {
  checkedBrowserTools,
  decodeBrowserFocusArguments,
  decodeBrowserKeyArguments,
  decodeBrowserObserveArguments,
  decodeBrowserPointerArguments,
  decodeBrowserScrollArguments,
  decodeBrowserSessionArguments,
  decodeBrowserTypeArguments,
} from './browser-tool-arguments.ts';
import type {
  BrowserFocusArguments,
  BrowserKeyArguments,
  BrowserObserveArguments,
  BrowserPointerArguments,
  BrowserScrollArguments,
  BrowserTypeArguments,
} from './browser-tool-arguments.ts';

// Native and browser tools deliberately share model argument vocabulary. Reuse
// its strict bounded decoder, not browser state or authority. Native dialogs are
// ordinary observed UI; a browser's automatic one-shot dialog policy is refused.
export type NativeObserveArguments = BrowserObserveArguments;
export type NativeFocusArguments = BrowserFocusArguments;
export type NativePointerArguments = Omit<
  BrowserPointerArguments,
  'dialogDecision'
>;
export type NativeTypeArguments = Omit<BrowserTypeArguments, 'dialogDecision'>;
export type NativeKeyArguments = Omit<BrowserKeyArguments, 'dialogDecision'>;
export type NativeScrollArguments = Omit<
  BrowserScrollArguments,
  'dialogDecision'
>;

function decode<T>(value: unknown, decoder: (value: unknown) => T): T {
  try {
    if (
      value !== null &&
      typeof value === 'object' &&
      Object.hasOwn(value, 'dialogDecision')
    )
      throw new NativeControlError('INVALID_ARGUMENT');
    return decoder(value);
  } catch {
    throw new NativeControlError('INVALID_ARGUMENT');
  }
}

export function checkedNativeTools(value: unknown): readonly NativeToolName[] {
  const names = decode(value, checkedBrowserTools);
  const selected: NativeToolName[] = [];
  for (const name of names) {
    if (name === 'navigate') throw new NativeControlError('INVALID_ARGUMENT');
    selected.push(name);
  }
  return Object.freeze(selected);
}
export function decodeNativeSessionArguments(value: unknown): SessionReference {
  return decode(value, decodeBrowserSessionArguments);
}
export function decodeNativeObserveArguments(
  value: unknown,
): NativeObserveArguments {
  return decode(value, decodeBrowserObserveArguments);
}
export function decodeNativeFocusArguments(
  value: unknown,
): NativeFocusArguments {
  return decode(value, decodeBrowserFocusArguments);
}
export function decodeNativePointerArguments(
  value: unknown,
): NativePointerArguments {
  const result = decode(value, decodeBrowserPointerArguments);
  return Object.freeze({
    sessionId: result.sessionId,
    generation: result.generation,
    action: result.action,
  });
}
export function decodeNativeTypeArguments(value: unknown): NativeTypeArguments {
  const result = decode(value, decodeBrowserTypeArguments);
  return Object.freeze({
    sessionId: result.sessionId,
    generation: result.generation,
    action: result.action,
  });
}
export function decodeNativeKeyArguments(value: unknown): NativeKeyArguments {
  const result = decode(value, decodeBrowserKeyArguments);
  const action = decodePreparedNativeAction(result.action);
  if (action.kind !== 'key') throw new NativeControlError('INVALID_ARGUMENT');
  return Object.freeze({
    sessionId: result.sessionId,
    generation: result.generation,
    frameId: result.frameId,
    action,
  });
}
export function decodeNativeScrollArguments(
  value: unknown,
): NativeScrollArguments {
  const result = decode(value, decodeBrowserScrollArguments);
  // Quartz wheel deltas are signed integers. Validation happens before frame
  // transformation or any native call; no implicit rounding of browser deltas.
  decodePreparedNativeAction({
    kind: 'scroll',
    target: { kind: 'point', x: 0, y: 0 },
    dx: result.dx,
    dy: result.dy,
    units: result.units,
  });
  return Object.freeze({
    sessionId: result.sessionId,
    generation: result.generation,
    target: result.target,
    dx: result.dx,
    dy: result.dy,
    units: result.units,
  });
}
