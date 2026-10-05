import type { RawData } from 'ws';
import type {
  IncomingMessage,
  ServerResponse,
  RequestListener,
} from 'node:http';
/** Node HTTP dispatch is synchronous. A failed fixture handler closes its response
 * so the exercising client observes a transport failure instead of an unhandled rejection. */
export function httpHandler(
  handler: (
    request: IncomingMessage,
    response: ServerResponse,
  ) => Promise<unknown>,
): RequestListener {
  return (request, response) => {
    void handler(request, response).catch((cause: unknown) => {
      response.destroy(
        cause instanceof Error
          ? cause
          : new Error('Fixture handler failed.', { cause }),
      );
    });
  };
}

/** ws can deliver fragmented buffers or an ArrayBuffer as well as a Buffer. */
export function wireText(bytes: RawData): string {
  return Buffer.isBuffer(bytes)
    ? bytes.toString('utf8')
    : bytes instanceof ArrayBuffer
      ? Buffer.from(bytes).toString('utf8')
      : Buffer.concat(bytes).toString('utf8');
}
