/** Narrow untrusted arrays without Array.isArray's implicit any[] element type. */
export function isList(value: unknown): value is unknown[] {
  return Array.isArray(value);
}

/** Boundary object guard. Callers must still validate every field they expose. */
export function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !isList(value);
}

/** Validate printable identifiers without coercing arbitrary objects to text. */
export function isText(value: unknown): value is string {
  return typeof value === 'string';
}

/** Presentation accepts scalar values only; objects require an explicit DTO formatter. */
export function displayText(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'string' || typeof value === 'boolean')
    return String(value);
  if (typeof value === 'number' && Number.isFinite(value)) return String(value);
  throw new Error('Expected a displayable scalar.');
}

/** Rejection diagnostics never stringify arbitrary objects or private payloads. */
export function errorMessage(value: unknown): string {
  if (value instanceof Error) return value.message;
  if (typeof value === 'string') return value;
  return 'Unexpected failure.';
}

export function isOneOf<const T extends readonly string[]>(
  value: unknown,
  choices: T,
): value is T[number] {
  return (
    typeof value === 'string' && choices.some((choice) => choice === value)
  );
}

/** C0 controls and DEL are refused in identifiers; source text has its own contract. */
export function hasControlCharacters(value: string): boolean {
  return Array.from(value).some((character) => {
    const code = character.charCodeAt(0);
    return code < 32 || code === 127;
  });
}

/** Platform errors remain unknown until their optional code has been checked. */
export function errorCode(value: unknown): string | undefined {
  return value !== null &&
    typeof value === 'object' &&
    'code' in value &&
    typeof value.code === 'string'
    ? value.code
    : undefined;
}
