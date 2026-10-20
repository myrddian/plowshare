import { call, names, tools } from './adapter.js';
import type { Backend } from './adapter.js';
import { record } from './values.js';

export type Response = {
  jsonrpc: '2.0';
  id: unknown;
  result?: unknown;
  error?: { code: number; message: string };
};
const error = (id: unknown, code: number, message: string): Response => ({
  jsonrpc: '2.0',
  id,
  error: { code, message },
});
const result = (id: unknown, result: unknown): Response => ({
  jsonrpc: '2.0',
  id,
  result,
});

/** Preserves the reviewed MCP menu and framing, including notification silence and
 * tool errors in content rather than errors the harness would hide from a model. */
export async function respond(
  input: string,
  backend: Backend,
  version = '0.1.0',
): Promise<Response | undefined> {
  if (!input.trim()) return undefined;
  let parsed: unknown;
  try {
    parsed = JSON.parse(input);
  } catch {
    return error(null, -32700, 'invalid JSON');
  }
  let message: Record<string, unknown>;
  try {
    message = record(parsed);
  } catch {
    return error(null, -32600, 'invalid request');
  }
  const id = message['id'] ?? null;
  if (
    message['jsonrpc'] !== '2.0' ||
    typeof message['method'] !== 'string' ||
    (id !== null &&
      typeof id !== 'string' &&
      (typeof id !== 'number' || !Number.isFinite(id)))
  )
    return error(id, -32600, 'invalid request');
  if (id === null) return undefined;
  switch (message['method']) {
    case 'initialize':
      return result(id, {
        protocolVersion: '2024-11-05',
        capabilities: { tools: {} },
        serverInfo: { name: 'plowshare', version },
      });
    case 'ping':
      return result(id, {});
    case 'tools/list':
      return result(id, { tools });
    case 'tools/call': {
      const params = message['params'];
      let values: Record<string, unknown>;
      try {
        values = record(params);
      } catch {
        return error(id, -32602, 'tools/call needs a tool name');
      }
      const name = values['name'];
      if (typeof name !== 'string' || !name.trim())
        return error(id, -32602, 'tools/call needs a tool name');
      if (!names.has(name)) return error(id, -32602, 'unknown tool: ' + name);
      let args: Record<string, unknown>;
      try {
        args = record(values['arguments']);
      } catch {
        args = {};
      }
      try {
        return result(id, {
          content: [{ type: 'text', text: await call(backend, name, args) }],
        });
      } catch (failure) {
        return result(id, {
          content: [
            {
              type: 'text',
              text: failure instanceof Error ? failure.message : 'tool failed',
            },
          ],
          isError: true,
        });
      }
    }
    default:
      return error(id, -32601, 'unknown method: ' + message['method']);
  }
}

/** Validated scheduling metadata stays separate from transport payloads. */
export type PreparedCall =
  | { readonly kind: 'silent' }
  | { readonly kind: 'initialized' }
  | { readonly kind: 'cancel'; readonly id: string | number }
  | { readonly kind: 'reply'; readonly response: Response }
  | {
      readonly kind: 'request';
      readonly id: string | number;
      readonly method: string;
      readonly presence: boolean;
      readonly execute: (backend: Backend) => Promise<Response | undefined>;
    };

/** Decode scheduling metadata at the same boundary that owns JSON-RPC framing. */
export function prepare(input: string): PreparedCall {
  if (!input.trim()) return { kind: 'silent' };
  let parsed: unknown;
  try {
    parsed = JSON.parse(input);
  } catch {
    return { kind: 'reply', response: error(null, -32700, 'invalid JSON') };
  }
  let message: Record<string, unknown>;
  try {
    message = record(parsed);
  } catch {
    return { kind: 'reply', response: error(null, -32600, 'invalid request') };
  }
  const id = message['id'],
    method = message['method'];
  if (message['jsonrpc'] !== '2.0' || typeof method !== 'string')
    return { kind: 'reply', response: error(null, -32600, 'invalid request') };
  if (id === undefined || id === null) {
    if (method === 'notifications/initialized') return { kind: 'initialized' };
    if (method === 'notifications/cancelled') {
      const params = message['params'];
      if (
        typeof params === 'object' &&
        params !== null &&
        'requestId' in params &&
        (typeof params.requestId === 'string' ||
          (typeof params.requestId === 'number' &&
            Number.isFinite(params.requestId)))
      )
        return { kind: 'cancel', id: params.requestId };
    }
    return { kind: 'silent' };
  }
  if (
    (typeof id !== 'string' && typeof id !== 'number') ||
    (typeof id === 'number' && !Number.isFinite(id)) ||
    (typeof id === 'string' && (Buffer.byteLength(id) > 128 || !id.trim()))
  )
    return { kind: 'reply', response: error(null, -32600, 'invalid request') };
  const params = message['params'];
  const name =
    typeof params === 'object' && params !== null && 'name' in params
      ? params.name
      : undefined;
  return {
    kind: 'request',
    id,
    method,
    presence:
      method === 'tools/call' &&
      (name === 'client_root_project_here' || name === 'agent_run'),
    execute: (backend) => respond(input, backend),
  };
}
