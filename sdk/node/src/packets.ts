import { createHash, randomUUID } from 'node:crypto';
import {
  packetSocket,
  PACKET_PROTOCOL,
} from 'plowshare-client-ts/binding/packets';
import type { Socket } from 'plowshare-client-ts/binding/connection';
export { PACKET_PROTOCOL };
/** Node's concrete byte/crypto boundary; packet state and limits stay in the neutral SDK. */
export function nodePacketSocket(socket: Socket): Socket {
  return packetSocket({
    socket,
    codec: {
      encode: (text) => new TextEncoder().encode(text),
      decode: (bytes) =>
        new TextDecoder('utf-8', { fatal: true }).decode(bytes),
      base64: (bytes) => Buffer.from(bytes).toString('base64'),
      unbase64: (text) => new Uint8Array(Buffer.from(text, 'base64')),
      hash: (bytes) =>
        Promise.resolve(createHash('sha256').update(bytes).digest('hex')),
      identity: randomUUID,
    },
    schedule: (expired, milliseconds) => {
      const timer = setTimeout(expired, milliseconds);
      return () => clearTimeout(timer);
    },
  });
}
