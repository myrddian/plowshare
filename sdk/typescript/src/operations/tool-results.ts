import { decodeToolContent, toolContentText } from './tool-content.ts';
import type { ToolContentBody } from './tool-content.ts';
import { isObject } from '../binding/values.ts';

export interface ToolInvocationRead {
  readonly project: string;
  readonly invocation: string;
}
export interface ToolInvocationResult {
  readonly id: string;
  readonly state: 'COMPLETED' | 'REJECTED' | 'UNKNOWN';
  readonly text: string;
  readonly content?: ToolContentBody | null;
}
const uuid = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/;
export function validateToolInvocationResult(value: unknown): void {
  if (
    !isObject(value) ||
    typeof value['id'] !== 'string' ||
    !uuid.test(value['id']) ||
    !['COMPLETED', 'REJECTED', 'UNKNOWN'].includes(
      typeof value['state'] === 'string' ? value['state'] : '',
    ) ||
    typeof value['text'] !== 'string' ||
    value['text'].length > 16384 ||
    value['text'].includes('\0')
  )
    throw new Error('Invalid tool invocation result');
  if (value['content'] == null) {
    if (!value['text'].trim()) throw new Error('Empty tool result');
    return;
  }
  const body = decodeToolContent(value['content']);
  if (
    toolContentText(body) !== value['text'] ||
    (value['state'] !== 'COMPLETED' &&
      body.parts.some((p) => p.kind === 'IMAGE'))
  )
    throw new Error('Invalid tool invocation content');
}
