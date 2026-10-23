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
  /**
   * Shed load instead of waiting for dispatch capacity. Local only; never
   * serialized.
   *
   * <p>The waiting form is the default: a request meeting a full window parks
   * until a slot frees, and a server `CAPACITY_EXHAUSTED` is flow control the
   * caller never sees. With this set the same request fails at once with
   * `NOT_SUBMITTED` — nothing was written — and a refusal is handed back as the
   * request's own outcome, with no backoff and no resubmission.
   *
   * <p>It is per call rather than per connection because the choice belongs to
   * the caller's role, not to the SDK: an interactive caller has nothing useful
   * to do with a refusal but wait, while a batch job under its own deadline may
   * prefer to drop the work.
   */
  readonly nonBlocking?: boolean;
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
      (key) =>
        !['actingFor', 'signal', 'timeoutMs', 'nonBlocking'].includes(key),
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
  // Rejected rather than coerced: a truthy string or 1 here would silently buy
  // load shedding, and the two forms differ in whether a caller is told the
  // server refused it.
  if ('nonBlocking' in options && typeof options.nonBlocking !== 'boolean')
    throw new Error('Choose whether to wait for dispatch capacity');
  const local = {
    ...(options.signal === undefined ? {} : { signal: options.signal }),
    ...(options.timeoutMs === undefined
      ? {}
      : { timeoutMs: options.timeoutMs }),
    ...(options.nonBlocking === undefined
      ? {}
      : { nonBlocking: options.nonBlocking }),
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
