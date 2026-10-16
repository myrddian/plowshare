/** Immutable selection on one request; it never changes a connection's authenticated actor. */
export interface ActingSelection {
  readonly context: string;
}
export interface RequestOptions {
  readonly actingFor?: ActingSelection;
}
export function actingOptions(options?: RequestOptions): RequestOptions {
  if (options === undefined) return {};
  if (
    typeof options !== 'object' ||
    options === null ||
    Array.isArray(options) ||
    Object.keys(options).some((key) => key !== 'actingFor')
  )
    throw new Error('Invalid request options');
  if (!('actingFor' in options)) return {};
  const selection = options.actingFor;
  if (
    typeof selection !== 'object' ||
    selection === null ||
    Object.keys(selection).length !== 1 ||
    typeof selection.context !== 'string' ||
    !/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(selection.context)
  )
    throw new Error('Choose a canonical acting context UUID');
  return { actingFor: { context: selection.context } };
}
