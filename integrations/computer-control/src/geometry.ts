/** Frame-pixel to backend-coordinate affine transform, including crop and display origin. */
export interface AffineTransform {
  readonly a: number;
  readonly b: number;
  readonly c: number;
  readonly d: number;
  readonly e: number;
  readonly f: number;
}

export interface Point {
  readonly x: number;
  readonly y: number;
}

/** Reject singular/non-finite transforms before any coordinate is used for input. */
export function validateTransform(value: unknown): AffineTransform {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    throw new Error('Invalid coordinate transform');
  const fields: Readonly<Record<string, unknown>> = value as Readonly<
    Record<string, unknown>
  >;
  const keys = ['a', 'b', 'c', 'd', 'e', 'f'];
  if (Object.keys(fields).some((key) => !keys.includes(key)))
    throw new Error('Unknown coordinate transform field');
  const values = keys.map((key) => fields[key]);
  if (
    values.some(
      (item) =>
        typeof item !== 'number' ||
        !Number.isFinite(item) ||
        Math.abs(item) > 1e9,
    )
  )
    throw new Error('Invalid coordinate transform coefficient');
  // Every coefficient has been validated; retain simple explicit fields rather than a vendor matrix.
  const a = fields['a'] as number;
  const b = fields['b'] as number;
  const c = fields['c'] as number;
  const d = fields['d'] as number;
  const e = fields['e'] as number;
  const f = fields['f'] as number;
  if (Math.abs(a * d - b * c) < 1e-12)
    throw new Error('Singular coordinate transform');
  return Object.freeze({ a, b, c, d, e, f });
}

export function transformPoint(
  transform: AffineTransform,
  point: Point,
): Point {
  const checked = validateTransform(transform);
  if (!Number.isFinite(point.x) || !Number.isFinite(point.y))
    throw new Error('Invalid coordinate point');
  const x = checked.a * point.x + checked.c * point.y + checked.e;
  const y = checked.b * point.x + checked.d * point.y + checked.f;
  if (!Number.isFinite(x) || !Number.isFinite(y))
    throw new Error('Coordinate overflow');
  return Object.freeze({ x, y });
}

export function inverseTransform(transform: AffineTransform): AffineTransform {
  const { a, b, c, d, e, f } = validateTransform(transform);
  const determinant = a * d - b * c;
  return validateTransform({
    a: d / determinant,
    b: -b / determinant,
    c: -c / determinant,
    d: a / determinant,
    e: (c * f - d * e) / determinant,
    f: (b * e - a * f) / determinant,
  });
}

/** Model point coordinates identify returned image pixels, never arbitrary desktop points. */
export function framePoint(
  transform: AffineTransform,
  width: number,
  height: number,
  x: number,
  y: number,
): Point {
  if (
    !Number.isSafeInteger(width) ||
    width < 1 ||
    !Number.isSafeInteger(height) ||
    height < 1 ||
    !Number.isSafeInteger(x) ||
    !Number.isSafeInteger(y) ||
    x < 0 ||
    x >= width ||
    y < 0 ||
    y >= height
  )
    throw new Error('Point is outside the captured frame');
  return transformPoint(transform, { x, y });
}
