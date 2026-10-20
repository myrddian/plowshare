/** Immutable selection on one request; it never changes a connection's authenticated actor. */
export interface ActingSelection {
  readonly context: string;
}
export interface RequestOptions {
  readonly actingFor?: ActingSelection;
  /** Local wait cancellation; never serialized and never cancels remote work. */
  readonly signal?: RequestSignal;
  /** Invocation-through-reply allowance; overrides the connection default locally. */
  readonly timeoutMs?: number;
}
/** Structural AbortSignal subset keeps the neutral SDK independent of DOM/Node types. */
export interface RequestSignal {
  readonly aborted: boolean;
  addEventListener(
    type: 'abort',
    listener: () => void,
    options?: { once?: boolean },
  ): void;
  removeEventListener(type: 'abort', listener: () => void): void;
}
export function actingOptions(options?: RequestOptions): RequestOptions {
  if (options === undefined) return {};
  if (
    typeof options !== 'object' ||
    options === null ||
    Array.isArray(options) ||
    Object.keys(options).some(
      (key) => !['actingFor', 'signal', 'timeoutMs'].includes(key),
    )
  )
    throw new Error('Invalid request options');
  if (
    'timeoutMs' in options &&
    (typeof options.timeoutMs !== 'number' ||
      !Number.isFinite(options.timeoutMs) ||
      options.timeoutMs <= 0 ||
      options.timeoutMs > 2147483647)
  )
    throw new Error('Choose a positive bounded request timeout');
  if (
    'signal' in options &&
    (typeof options.signal !== 'object' ||
      options.signal === null ||
      typeof options.signal.aborted !== 'boolean' ||
      typeof options.signal.addEventListener !== 'function' ||
      typeof options.signal.removeEventListener !== 'function')
  )
    throw new Error('Choose a request cancellation signal');
  const local = {
    ...(options.signal === undefined ? {} : { signal: options.signal }),
    ...(options.timeoutMs === undefined
      ? {}
      : { timeoutMs: options.timeoutMs }),
  };
  if (!('actingFor' in options)) return local;
  const selection = options.actingFor;
  if (
    typeof selection !== 'object' ||
    selection === null ||
    Object.keys(selection).length !== 1 ||
    typeof selection.context !== 'string' ||
    !/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(selection.context)
  )
    throw new Error('Choose a canonical acting context UUID');
  return { ...local, actingFor: { context: selection.context } };
}
