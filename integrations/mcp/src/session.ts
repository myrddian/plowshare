import {
  Client,
  StreamableHTTPClientTransport,
} from '@modelcontextprotocol/client';
import type {
  McpSession,
  McpSessions,
  McpSessionOptions,
  McpTool,
} from './contracts.ts';
import type { ToolObject } from 'plowshare-client-ts';
import { boundedHttp } from './http.ts';
import { decodeMcpPage, decodeMcpReply } from './codec.ts';

/** No global client, cache, auth refresh, redirect, era fallback or effect retry. */
export class HttpMcpSessions implements McpSessions {
  async open(
    options: McpSessionOptions,
    signal: AbortSignal,
  ): Promise<McpSession> {
    const controller = new AbortController();
    const opening = AbortSignal.any([signal, controller.signal]);
    const network = await boundedHttp(
      options.policy,
      controller.signal,
      opening,
    );
    network.setSignal(opening);
    const client = new Client(
      { name: 'plowshare-mcp-integration', version: '0.1.0' },
      {
        capabilities: {},
        enforceStrictCapabilities: true,
        versionNegotiation: {
          mode:
            options.protocol === '2026-07-28'
              ? { pin: options.protocol }
              : 'legacy',
          probe: { timeoutMs: options.policy.timeoutMs, maxRetries: 0 },
        },
        inputRequired: { autoFulfill: false },
        listMaxPages: 32,
      },
    );
    const transport = new StreamableHTTPClientTransport(
      new URL(options.policy.endpoint),
      {
        fetch: network.fetch,
        requestInit: {
          redirect: 'error',
          headers: { [options.header]: options.value },
        },
        reconnectionOptions: {
          initialReconnectionDelay: 1,
          maxReconnectionDelay: 1,
          reconnectionDelayGrowFactor: 1,
          maxRetries: 0,
        },
        onInsufficientScope: 'throw',
      },
    );
    try {
      await client.connect(transport, {
        timeout: options.policy.timeoutMs,
        signal: opening,
      });
      if (
        client.getNegotiatedProtocolVersion() !== options.protocol ||
        client.getServerCapabilities()?.tools === undefined
      )
        throw new Error(
          'MCP server does not support the configured tools profile',
        );
    } catch {
      controller.abort();
      await client.close();
      await network.close();
      throw new Error('MCP session negotiation failed');
    }
    return {
      async page(cursor, pageSignal) {
        // Raw typed requests avoid SDK auto-pagination, header filtering and shared cache writes.
        network.setSignal(pageSignal);
        const reply = await client.request(
          { method: 'tools/list', params: cursor === null ? {} : { cursor } },
          { signal: pageSignal, timeout: options.policy.timeoutMs },
        );
        return decodeMcpPage(reply);
      },
      async call(tool: McpTool, arguments_: ToolObject, callSignal) {
        network.setSignal(callSignal);
        const reply = await client.request(
          {
            method: 'tools/call',
            params: { name: tool.name, arguments: arguments_ },
          },
          { signal: callSignal, timeout: options.policy.timeoutMs },
        );
        return decodeMcpReply(reply);
      },
      async close() {
        controller.abort();
        try {
          await client.close();
        } finally {
          await network.close();
        }
      },
    };
  }
}
