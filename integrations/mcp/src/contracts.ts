import type {
  SchemaDefinition,
  ToolObject,
  ToolResult,
  ToolValue,
} from 'plowshare-client-ts';

export type McpProtocol = '2026-07-28' | '2025-11-25';
/** Immutable, validated discovery snapshot. Metadata participates in offered identity. */
export interface McpTool {
  readonly name: string;
  readonly description: string;
  readonly input: SchemaDefinition;
  readonly output: SchemaDefinition | null;
  readonly metadata: ToolValue;
}
export interface McpPage {
  readonly tools: readonly McpTool[];
  readonly cursor: string | null;
}
export interface McpReply {
  readonly content: readonly string[];
  readonly structured: ToolValue | undefined;
  readonly isError: boolean;
}
/** A session belongs to exactly one released credential context; implementations never retry effects. */
export interface McpSession {
  page(cursor: string | null, signal: AbortSignal): Promise<McpPage>;
  call(
    tool: McpTool,
    arguments_: ToolObject,
    signal: AbortSignal,
  ): Promise<McpReply>;
  close(): Promise<void>;
}
export interface HttpPolicy {
  readonly endpoint: string;
  readonly allowHttp: boolean;
  readonly allowPrivateNetwork: boolean;
  readonly timeoutMs: number;
}
export interface McpSessionOptions {
  readonly protocol: McpProtocol;
  readonly policy: HttpPolicy;
  readonly header: 'Authorization' | 'X-API-Key' | 'Api-Key';
  readonly value: string;
}
export interface McpSessions {
  open(options: McpSessionOptions, signal: AbortSignal): Promise<McpSession>;
}
export const MAX_TOOLS = 128;
export const MAX_PAGES = 32;
export const MAX_WIRE_BYTES = 1_048_576;
export function rejected(text: string): ToolResult {
  return { state: 'REJECTED', text };
}
export function unknownResult(): ToolResult {
  return {
    state: 'UNKNOWN',
    text: 'MCP response was not verifiable; external effects may have occurred. Do not replay this invocation.',
  };
}

/** Stop waiting at the adapter deadline without replaying an already submitted SDK credential request.
 * Promise.race owns both settlements; the SDK retains its request timeout and correlation.
 */
export async function withinDeadline<T>(
  work: () => Promise<T>,
  signal: AbortSignal,
): Promise<T> {
  signal.throwIfAborted();
  let abort: (() => void) | undefined;
  const expired = new Promise<never>((_resolve, reject) => {
    abort = () => reject(new Error('MCP operation deadline expired'));
    signal.addEventListener('abort', abort, { once: true });
  });
  try {
    return await Promise.race([work(), expired]);
  } finally {
    if (abort !== undefined) signal.removeEventListener('abort', abort);
  }
}
