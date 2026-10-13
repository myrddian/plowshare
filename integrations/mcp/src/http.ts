import { lookup } from 'node:dns/promises';
import { isIP } from 'node:net';
import ipaddr from 'ipaddr.js';
import { Agent, fetch as httpFetch } from 'undici';
import type { FetchLike } from '@modelcontextprotocol/client';
import type { HttpPolicy } from './contracts.ts';
import { MAX_WIRE_BYTES } from './contracts.ts';

export function endpoint(value: string, allowHttp: boolean): URL {
  const url = new URL(value);
  if (
    (!allowHttp && url.protocol !== 'https:') ||
    !['https:', 'http:'].includes(url.protocol) ||
    url.username ||
    url.password ||
    url.hash ||
    url.search ||
    value.length > 2048
  )
    throw new Error('MCP requires an explicit permitted HTTP(S) endpoint');
  return url;
}
export function allowedAddress(
  address: string,
  privateNetwork: boolean,
): boolean {
  if (!ipaddr.isValid(address)) return false;
  const parsed = ipaddr.process(address);
  return privateNetwork || parsed.range() === 'unicast';
}
/** Resolve once per session, validate every answer, and pin the selected address in the socket lookup.
 * TLS still validates the configured hostname. Redirects cannot widen the operator endpoint fence.
 */
export async function boundedHttp(
  policy: HttpPolicy,
  lifetime: AbortSignal,
  opening: AbortSignal = lifetime,
): Promise<{
  fetch: FetchLike;
  setSignal(signal: AbortSignal): void;
  close(): Promise<void>;
}> {
  const url = endpoint(policy.endpoint, policy.allowHttp);
  const hostname = url.hostname.replace(/^\[|\]$/g, '');
  const addresses = isIP(hostname)
    ? [{ address: hostname, family: isIP(hostname) }]
    : await new Promise<{ address: string; family: number }[]>(
        (resolve, reject) => {
          const aborted = () => reject(new Error('MCP DNS deadline expired'));
          opening.addEventListener('abort', aborted, { once: true });
          if (opening.aborted) {
            aborted();
            return;
          }
          lookup(hostname, { all: true }).then(
            (values) => {
              opening.removeEventListener('abort', aborted);
              resolve(values);
            },
            () => {
              opening.removeEventListener('abort', aborted);
              reject(new Error('MCP endpoint resolution failed'));
            },
          );
        },
      );
  opening.throwIfAborted();
  lifetime.throwIfAborted();
  if (
    !addresses.length ||
    addresses.some(
      (entry) => !allowedAddress(entry.address, policy.allowPrivateNetwork),
    )
  )
    throw new Error('MCP endpoint address is outside its network policy');
  const selected = addresses[0]!;
  const agent = new Agent({
    connect: {
      autoSelectFamily: false,
      lookup: (_host, _options, callback) =>
        callback(null, selected.address, selected.family),
    },
  });
  let operationSignal = lifetime;
  const fetch: FetchLike = async (input, init) => {
    const target = input instanceof Request ? input.url : String(input);
    if (new URL(target).href !== url.href)
      throw new Error('MCP request escaped configured endpoint');
    const method = init?.method ?? 'GET';
    // Standalone notification/subscription streams are outside this bounded request/response profile.
    if (method === 'GET') return new Response(null, { status: 405 });
    if (
      !['POST', 'DELETE'].includes(method) ||
      (init?.body !== undefined && typeof init.body !== 'string')
    )
      throw new Error('Unsupported MCP HTTP request');
    if (
      typeof init?.body === 'string' &&
      Buffer.byteLength(init.body) > MAX_WIRE_BYTES
    )
      throw new Error('MCP request exceeds bound');
    const signals = [
      lifetime,
      operationSignal,
      AbortSignal.timeout(policy.timeoutMs),
    ];
    if (init?.signal) signals.push(init.signal);
    const headers: Record<string, string> = {};
    new Headers(init?.headers).forEach((value, key) => {
      headers[key] = value;
    });
    const response = await httpFetch(url, {
      method,
      headers,
      ...(typeof init?.body === 'string' ? { body: init.body } : {}),
      signal: AbortSignal.any(signals),
      redirect: 'error',
      dispatcher: agent,
    });
    if (response.status >= 300 && response.status < 400) {
      await response.body?.cancel();
      throw new Error('MCP redirects are refused');
    }
    const responseHeaders = new Headers();
    response.headers.forEach((value, key) => {
      responseHeaders.set(key, value);
    });
    if (!response.body)
      return new Response(null, {
        status: response.status,
        headers: responseHeaders,
      });
    const reader = response.body.getReader();
    let bytes = 0;
    const body = new ReadableStream<Uint8Array>({
      async pull(controller) {
        try {
          const next = await reader.read();
          if (next.done) {
            controller.close();
            return;
          }
          const chunk: unknown = next.value;
          if (!(chunk instanceof Uint8Array))
            throw new Error('Invalid MCP response bytes');
          bytes += chunk.byteLength;
          if (bytes > MAX_WIRE_BYTES) {
            await reader.cancel();
            throw new Error('MCP response exceeds bound');
          }
          controller.enqueue(chunk);
        } catch {
          controller.error(
            new Error('MCP response could not be read within its bounds'),
          );
        }
      },
      async cancel() {
        await reader.cancel();
      },
    });
    return new Response(body, {
      status: response.status,
      headers: responseHeaders,
    });
  };
  return {
    fetch,
    setSignal(signal) {
      operationSignal = signal;
    },
    async close() {
      await agent.destroy();
    },
  };
}
