import { decodeImageAdmissionReceipt } from './image-admissions.ts';
import type { ImageAdmissionReceipt } from './image-admissions.ts';

export const TOOL_CONTENT_PROFILE = 'content/1';
export type ToolImageUse = 'OBSERVATION' | 'OUTPUT' | 'BOTH';
/** Null inactive fields keep the portable Java/native SDK contract explicit. */
export interface ToolContentPart {
  readonly kind: 'TEXT' | 'IMAGE';
  readonly text: string | null;
  readonly imageId: string | null;
  readonly ordinal: number | null;
  readonly use: ToolImageUse | null;
}
export interface ToolContentBody {
  readonly version: 'content/1';
  readonly parts: readonly ToolContentPart[];
}
function object(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    throw new Error('Invalid tool content object');
  return value as Record<string, unknown>;
}
function keys(
  value: Record<string, unknown>,
  allowed: readonly string[],
): void {
  if (Object.keys(value).some((k) => !allowed.includes(k)))
    throw new Error('Unknown tool content field');
}
export function toolTextPart(text: string): ToolContentPart {
  if (typeof text !== 'string' || text.length > 16384 || text.includes('\0'))
    throw new Error('Invalid tool content text');
  return Object.freeze({
    kind: 'TEXT',
    text,
    imageId: null,
    ordinal: null,
    use: null,
  });
}
export function decodeToolContent(value: unknown): ToolContentBody {
  const body = object(value);
  keys(body, ['version', 'parts']);
  if (
    body['version'] !== TOOL_CONTENT_PROFILE ||
    !Array.isArray(body['parts']) ||
    body['parts'].length < 1 ||
    body['parts'].length > 16
  )
    throw new Error('Invalid tool content body');
  const values: readonly unknown[] = body['parts'];
  const slots = new Set<number>();
  let textLength = 0;
  const parts = values.map((value) => {
    const part = object(value);
    keys(part, ['kind', 'text', 'imageId', 'ordinal', 'use']);
    if (part['kind'] === 'TEXT') {
      if (
        typeof part['text'] !== 'string' ||
        part['imageId'] != null ||
        part['ordinal'] != null ||
        part['use'] != null
      )
        throw new Error('Invalid text part');
      textLength += part['text'].length;
      return toolTextPart(part['text']);
    }
    const id = part['imageId'],
      ordinal = part['ordinal'],
      use = part['use'];
    if (
      part['kind'] !== 'IMAGE' ||
      part['text'] != null ||
      typeof id !== 'string' ||
      !/^img_[0-9a-f]{32}$/.test(id) ||
      typeof ordinal !== 'number' ||
      !Number.isInteger(ordinal) ||
      ordinal < 0 ||
      ordinal > 15 ||
      slots.has(ordinal) ||
      (use !== 'OBSERVATION' && use !== 'OUTPUT' && use !== 'BOTH')
    )
      throw new Error('Invalid image tool reference');
    slots.add(ordinal);
    return Object.freeze({
      kind: 'IMAGE' as const,
      text: null,
      imageId: id,
      ordinal,
      use,
    });
  });
  if (
    slots.size > 4 ||
    textLength > 16384 ||
    (slots.size === 0 &&
      !parts
        .map((p) => p.text ?? '')
        .join('')
        .trim())
  )
    throw new Error('Tool content exceeds its allowance');
  return Object.freeze({
    version: TOOL_CONTENT_PROFILE,
    parts: Object.freeze(parts),
  });
}
export function toolContentText(body: ToolContentBody): string {
  return body.parts.map((p) => p.text ?? '').join('');
}
/** Build from the original admission receipt; the server still verifies the owning invocation. */
export function toolImagePart(
  receipt: ImageAdmissionReceipt,
  use: ToolImageUse,
): ToolContentPart {
  const admitted = decodeImageAdmissionReceipt(receipt);
  return (
    decodeToolContent({
      version: TOOL_CONTENT_PROFILE,
      parts: [
        {
          kind: 'IMAGE',
          text: null,
          imageId: admitted.imageId,
          ordinal: admitted.request.ordinal,
          use,
        },
      ],
    }).parts[0] ??
    (() => {
      throw new Error('Missing image part');
    })()
  );
}

/** Compatibility helper with its original PNG-only contract. */
export function toolPngPart(
  receipt: ImageAdmissionReceipt,
  use: ToolImageUse,
): ToolContentPart {
  if (receipt.mimeType !== 'image/png') throw new Error('PNG receipt required');
  return toolImagePart(receipt, use);
}
