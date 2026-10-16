import { createServer } from 'node:http';
import { createServer as createTlsServer } from 'node:https';
import { readFileSync } from 'node:fs';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { randomUUID } from 'node:crypto';
import { object } from './codec.ts';
import type { McpProtocol } from './contracts.ts';

export const inputSchema = {
  type: 'object',
  properties: {
    values: {
      type: 'array',
      items: {
        type: 'object',
        properties: { name: { type: 'string', minLength: 1 } },
        required: ['name'],
        additionalProperties: false,
      },
    },
  },
  required: ['values'],
  additionalProperties: false,
};
export const rawTool = {
  name: 'echo/values',
  description: 'Echo nested values',
  inputSchema,
};
export type WireMode =
  | 'json'
  | 'sse'
  | 'loss'
  | 'unauthorized'
  | 'input'
  | 'invalid'
  | 'oversize'
  | 'redirect'
  | 'hang'
  | 'schema'
  | 'malformed'
  | 'error';
export interface WirePage {
  readonly tools: readonly unknown[];
  readonly cursor?: string;
}
export class McpWire {
  readonly seen: {
    method: string;
    authorization: string;
    session: string;
    arguments: unknown;
    cursor: string | null;
    clientInfo: unknown;
    headers: IncomingMessage['headers'];
  }[] = [];
  readonly faults: unknown[] = [];
  readonly sessions = new Map<string, string>();
  mode: WireMode = 'json';
  tools: readonly unknown[] = [rawTool];
  cursor: string | undefined;
  advertisedVersion: string;
  toolsSupported = true;
  acceptedAuthorization: ReadonlySet<string> | undefined;
  onList:
    ((authorization: string, cursor: string | null) => WirePage) | undefined;
  onCall: (() => void) | undefined;
  abortedCalls = 0;
  private readonly server;
  endpoint = '';
  constructor(
    readonly protocol: McpProtocol,
    readonly tls = false,
  ) {
    this.advertisedVersion = protocol;
    const respond = (request: IncomingMessage, response: ServerResponse) => {
      this.respond(request, response).catch((error: unknown) => {
        this.faults.push(error);
        response.destroy();
      });
    };
    // A public fixture key/certificate, trusted only by the disposable test process.
    // Production certificate/hostname verification stays enabled, including in these tests.
    this.server = tls
      ? createTlsServer(
          {
            key: readFileSync(
              new URL('../../src/fixtures/mcp-test-key.pem', import.meta.url),
            ),
            cert: readFileSync(
              new URL('../../src/fixtures/mcp-test-cert.pem', import.meta.url),
            ),
          },
          respond,
        )
      : createServer(respond);
  }
  async start(port = 0): Promise<void> {
    await new Promise<void>((resolve) =>
      this.server.listen(port, '127.0.0.1', resolve),
    );
    const address = this.server.address();
    if (!address || typeof address === 'string')
      throw new Error('Missing fixture address');
    this.endpoint = `${this.tls ? 'https' : 'http'}://127.0.0.1:${address.port}/mcp`;
  }
  async close(): Promise<void> {
    if (!this.server.listening) return;
    this.server.closeAllConnections();
    await new Promise<void>((resolve, reject) =>
      this.server.close((error) => (error ? reject(error) : resolve())),
    );
  }
  private async respond(
    request: IncomingMessage,
    response: ServerResponse,
  ): Promise<void> {
    const chunks: Buffer[] = [];
    for await (const chunk of request) {
      const value: unknown = chunk;
      if (!Buffer.isBuffer(value)) throw new Error('Invalid fixture bytes');
      chunks.push(value);
    }
    if (!chunks.length) {
      response.writeHead(405).end();
      return;
    }
    const source: unknown = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    const message = object(source);
    const method = message.method;
    if (typeof method !== 'string') throw new Error('Missing fixture method');
    const params = message.params === undefined ? {} : object(message.params);
    const authorization =
      request.headers.authorization ?? request.headers['x-api-key'];
    const token = typeof authorization === 'string' ? authorization : '';
    const session = request.headers['mcp-session-id'];
    const cursor = params.cursor === undefined ? null : params.cursor;
    if (cursor !== null && typeof cursor !== 'string')
      throw new Error('Invalid fixture cursor');
    this.seen.push({
      method,
      authorization: token,
      session: typeof session === 'string' ? session : '',
      arguments: params.arguments,
      cursor,
      clientInfo: params.clientInfo,
      headers: request.headers,
    });
    if (this.acceptedAuthorization && !this.acceptedAuthorization.has(token)) {
      response.writeHead(401, { 'www-authenticate': 'Bearer' }).end();
      return;
    }
    if (method === 'notifications/initialized' || message.id === undefined) {
      response.writeHead(202).end();
      return;
    }
    let result: unknown;
    const headers: Record<string, string> = {
      'content-type': 'application/json',
    };
    if (method === 'server/discover')
      result = {
        resultType: 'complete',
        supportedVersions: [this.advertisedVersion],
        capabilities: this.toolsSupported ? { tools: {} } : {},
        ttlMs: 0,
        cacheScope: 'public',
        _meta: {
          'io.modelcontextprotocol/serverInfo': {
            name: 'fixture',
            version: '1',
          },
        },
      };
    else if (method === 'initialize') {
      const id = randomUUID();
      this.sessions.set(token, id);
      headers['mcp-session-id'] = id;
      result = {
        protocolVersion: this.advertisedVersion,
        capabilities: this.toolsSupported ? { tools: {} } : {},
        serverInfo: { name: 'fixture', version: '1' },
      };
    } else if (method === 'tools/list') {
      const page = this.onList?.(token, cursor) ?? {
        tools: this.tools,
        ...(this.cursor === undefined ? {} : { cursor: this.cursor }),
      };
      result = {
        ...(this.protocol === '2026-07-28'
          ? { resultType: 'complete', ttlMs: 0, cacheScope: 'public' }
          : {}),
        tools: page.tools,
        ...(page.cursor === undefined ? {} : { nextCursor: page.cursor }),
      };
    } else if (method === 'tools/call') {
      if (
        this.protocol === '2025-11-25' &&
        this.sessions.get(token) !== session
      )
        throw new Error('Fixture call used another credential session');
      response.once('close', () => {
        if (!response.writableFinished) this.abortedCalls++;
      });
      this.onCall?.();
      if (this.mode === 'loss') {
        response.destroy();
        return;
      }
      if (this.mode === 'hang') return;
      if (this.mode === 'malformed') {
        response.writeHead(200, headers).end('{');
        return;
      }
      if (this.mode === 'unauthorized') {
        response
          .writeHead(401, {
            'www-authenticate':
              'Bearer resource_metadata="https://example.invalid/auth"',
          })
          .end();
        return;
      }
      if (this.mode === 'redirect') {
        response.writeHead(307, { location: '/elsewhere' }).end();
        return;
      }
      if (this.mode === 'input')
        result = {
          resultType: 'input_required',
          inputRequests: {},
          requestState: 'fixture',
        };
      else if (this.mode === 'invalid')
        result = {
          content: [{ type: 'image', data: 'ZmFrZQ==', mimeType: 'image/png' }],
        };
      else
        result = {
          ...(this.protocol === '2026-07-28' ? { resultType: 'complete' } : {}),
          content: [
            {
              type: 'text',
              text:
                this.mode === 'oversize' ? 'x'.repeat(1_048_577) : 'received',
            },
          ],
          isError: this.mode === 'error',
          structuredContent:
            this.mode === 'schema'
              ? { values: [{ name: 42 }] }
              : params.arguments,
        };
    } else throw new Error('Unexpected fixture method');
    const encoded = JSON.stringify({ jsonrpc: '2.0', id: message.id, result });
    if (this.mode === 'sse' && method === 'tools/call') {
      response.writeHead(200, {
        ...headers,
        'content-type': 'text/event-stream',
      });
      response.end('event: message\ndata: ' + encoded + '\n\n');
    } else {
      response.writeHead(200, headers).end(encoded);
    }
  }
}
