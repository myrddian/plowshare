import { createHash } from 'node:crypto';
import {
  decodeToolSchema,
  decodeToolValue,
  validateToolSchema,
  validateSchemaArguments,
} from 'plowshare-client-ts/operations/tool-schemas';
import type {
  ToolDeclaration,
  ToolResult,
  ToolValue,
} from 'plowshare-client-ts';
import type { McpPage, McpProtocol, McpReply, McpTool } from './contracts.ts';
import { DEFAULT_LIMITS } from './settings.ts';
import type { McpLimits } from './settings.ts';
import { MAX_TOOL_NAME_LENGTH } from 'plowshare-client-ts/operations/relay-tools';

export function object(value: unknown): Record<string, unknown> {
  if (
    value === null ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    Object.getPrototypeOf(value) !== Object.prototype
  )
    throw new Error('Expected a plain object');
  return value as Record<string, unknown>;
}
export function text(value: unknown, max: number): string {
  if (
    typeof value !== 'string' ||
    !value.trim() ||
    value.length > max ||
    Array.from(value).some(
      (character) =>
        character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127,
    )
  )
    throw new Error('Invalid bounded text');
  return value;
}
/** Never discard unknown schema constraints. The shared SDK owns the executable profile. */
export function decodeMcpTool(
  value: unknown,
  limits: McpLimits = DEFAULT_LIMITS,
): McpTool {
  const raw = object(value);
  const name = text(raw.name, limits.maxToolNameLength);
  const description =
    raw.description === undefined
      ? text(name, limits.maxDescriptionLength)
      : text(raw.description, limits.maxDescriptionLength);
  const input = decodeToolSchema(raw.inputSchema);
  validateToolSchema(input);
  const output =
    raw.outputSchema === undefined ? null : decodeToolSchema(raw.outputSchema);
  if (output !== null) validateToolSchema(output);
  if (raw.execution !== undefined) {
    const execution = object(raw.execution);
    if (
      Object.keys(execution).some((key) => key !== 'taskSupport') ||
      (execution.taskSupport !== undefined &&
        execution.taskSupport !== 'forbidden' &&
        execution.taskSupport !== 'optional')
    )
      throw new Error('MCP tasks are outside the synchronous profile');
  }
  if (raw.annotations !== undefined) {
    const annotations = object(raw.annotations);
    for (const key of [
      'readOnlyHint',
      'destructiveHint',
      'idempotentHint',
      'openWorldHint',
    ])
      if (
        annotations[key] !== undefined &&
        typeof annotations[key] !== 'boolean'
      )
        throw new Error('Invalid MCP annotations');
  }
  // Copy all discovery metadata into the identity, including benign vendor fields.
  const metadata = decodeToolValue(raw);
  return Object.freeze({ name, description, input, output, metadata });
}
export function decodeMcpPage(
  value: unknown,
  limits: McpLimits = DEFAULT_LIMITS,
): McpPage {
  const raw = object(value);
  if (!Array.isArray(raw.tools))
    throw new Error('MCP catalogue exceeds tool bound');
  const source: readonly unknown[] = raw.tools;
  return Object.freeze({
    tools: Object.freeze(source.map((tool) => decodeMcpTool(tool, limits))),
    cursor:
      raw.nextCursor === undefined
        ? null
        : text(raw.nextCursor, limits.maxCursorLength),
  });
}
export function decodeMcpReply(
  value: unknown,
  limits: McpLimits = DEFAULT_LIMITS,
): McpReply {
  const raw = object(value);
  if (raw.resultType !== undefined && raw.resultType !== 'complete')
    throw new Error('Unsupported MCP continuation');
  if (raw.isError !== undefined && typeof raw.isError !== 'boolean')
    throw new Error('Invalid MCP error marker');
  if (
    !Array.isArray(raw.content) ||
    raw.content.length > limits.maxContentBlocks
  )
    throw new Error('Invalid MCP content');
  const source: readonly unknown[] = raw.content;
  const content = source.map((block) => {
    const item = object(block);
    if (
      item.type !== 'text' ||
      typeof item.text !== 'string' ||
      item.text.length > limits.maxResultBytes ||
      item.text.includes('\0')
    )
      throw new Error('Only bounded text MCP results are supported');
    return item.text;
  });
  return Object.freeze({
    content: Object.freeze(content),
    structured:
      raw.structuredContent === undefined
        ? undefined
        : decodeToolValue(raw.structuredContent),
    isError: raw.isError === true,
  });
}
function canonical(value: ToolValue): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  return (
    '{' +
    Object.entries(value)
      .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
      .map(([key, item]) => JSON.stringify(key) + ':' + canonical(item))
      .join(',') +
    '}'
  );
}
export function contractHash(value: ToolValue): string {
  return createHash('sha256').update(canonical(value)).digest('hex');
}
export function declaration(
  tool: McpTool,
  prefix: string,
  protocol: McpProtocol,
  context: string,
  timeoutSeconds: number,
  limits: McpLimits = DEFAULT_LIMITS,
): ToolDeclaration {
  if (!/^[a-z][a-z0-9]*(?:_[a-z0-9]+)*_$/.test(prefix) || prefix.length > 48)
    throw new Error('Invalid MCP tool prefix');
  const hash = contractHash({
    protocol,
    context,
    timeoutSeconds,
    limits: { ...limits },
    tool: tool.metadata,
  }).slice(0, 32);
  const available =
    Math.min(limits.maxOfferedNameLength, MAX_TOOL_NAME_LENGTH) -
    prefix.length -
    hash.length -
    1;
  if (available < 1)
    throw new Error(
      'Configured offered-name budget cannot fit the binding prefix and hash',
    );
  const slug =
    tool.name
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '_')
      .replace(/^_+|_+$/g, '')
      .slice(0, available)
      .replace(/_+$/g, '') || 't';
  return Object.freeze({
    name: prefix + slug + '_' + hash,
    description: tool.description,
    parameters: [],
    timeoutSeconds,
    inputSchema: tool.input,
  });
}
export function result(
  tool: McpTool,
  reply: McpReply,
  maxResultBytes: number = DEFAULT_LIMITS.maxResultBytes,
): ToolResult {
  if (
    tool.output !== null &&
    (!reply.isError || reply.structured !== undefined)
  ) {
    if (reply.structured === undefined)
      throw new Error('Missing MCP structured output');
    validateSchemaArguments(tool.output, reply.structured);
  }
  const encoded = JSON.stringify({
    isError: reply.isError,
    content: reply.content,
    ...(reply.structured === undefined
      ? {}
      : { structuredContent: reply.structured }),
  });
  if (
    encoded.length > maxResultBytes ||
    Buffer.byteLength(encoded) > maxResultBytes
  )
    throw new Error('MCP result exceeds provider limit');
  return { state: 'COMPLETED', text: encoded };
}
