import { createHash } from 'node:crypto';
import {
  MAX_RELAY_BLOB_BYTES,
  decodeRelayBlobDescriptor,
  decodeRelayBlobPublish,
  decodeRelayBlobPublished,
  decodeRelayBlobRead,
  type RelayBlobDescriptor,
  type RelayBlobPublish,
  type RelayBlobPublished,
  type RelayBlobRead,
} from 'plowshare-client-ts/operations/relay-blobs';

function target(origin: string, bearer: string): URL {
  const base = new URL(origin);
  if (
    !['http:', 'https:'].includes(base.protocol) ||
    base.username ||
    base.password ||
    base.search ||
    base.hash ||
    (base.pathname !== '' && base.pathname !== '/')
  )
    throw new Error('Relay binary transfer requires an explicit server origin');
  if (!bearer.trim() || /[\r\n]/.test(bearer))
    throw new Error('Relay binary transfer requires a valid bearer');
  return new URL('/v1/relay/blobs', base);
}

async function bounded(response: Response, limit: number): Promise<Uint8Array> {
  const reader = response.body?.getReader();
  if (reader === undefined)
    throw new Error('Relay binary response has no body');
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      const bytes: unknown = chunk.value;
      if (!(bytes instanceof Uint8Array))
        throw new Error('Invalid Relay response bytes');
      total += bytes.byteLength;
      if (total > limit)
        throw new Error('Relay binary response exceeds its bound');
      chunks.push(bytes);
    }
  } finally {
    try {
      await reader.cancel();
    } finally {
      reader.releaseLock();
    }
  }
  return Buffer.concat(chunks, total);
}

/** Explicit single-attempt publication. Retain request ID/bytes before calling; never recapture. */
export async function publishRelayBlob(
  origin: string,
  bearer: string,
  request: RelayBlobPublish,
  bytes: Uint8Array,
  http: typeof fetch = fetch,
): Promise<RelayBlobPublished> {
  const url = target(origin, bearer),
    checked = decodeRelayBlobPublish(request);
  if (bytes.length < 1 || bytes.length > MAX_RELAY_BLOB_BYTES)
    throw new Error('Relay binary upload exceeds its bound');
  const snapshot = new Uint8Array(bytes),
    digest = createHash('sha256').update(snapshot).digest('hex');
  const body = new FormData();
  for (const [key, value] of Object.entries(checked)) {
    if (typeof value !== 'string')
      throw new Error('Invalid Relay binary metadata');
    body.append(key, value);
  }
  body.append('file', new Blob([snapshot]), 'blob.bin');
  const response = await http(url, {
    method: 'POST',
    headers: { Authorization: `Bearer ${bearer}` },
    body,
    redirect: 'error',
    signal: AbortSignal.timeout(60000),
  });
  if (!response.ok)
    throw new Error(
      `Relay binary publication refused (${response.status}); reconcile the original request`,
    );
  const raw: unknown = JSON.parse(
    new TextDecoder('utf-8', { fatal: true }).decode(
      await bounded(response, 16384),
    ),
  );
  const receipt = decodeRelayBlobPublished(raw),
    published = receipt.publication;
  if (
    published.requestId !== checked.requestId ||
    published.project !== checked.project ||
    published.topic !== checked.topic ||
    receipt.blob.sha256 !== digest ||
    receipt.blob.byteLength !== snapshot.length ||
    receipt.blob.mediaType !== checked.mediaType
  )
    throw new Error('Relay binary publication returned a different receipt');
  return receipt;
}

/** Read a consumed descriptor through current publication authority and verify original bytes. */
export async function readRelayBlob(
  origin: string,
  bearer: string,
  request: RelayBlobRead,
  expected: RelayBlobDescriptor,
  http: typeof fetch = fetch,
): Promise<Uint8Array> {
  const url = target(origin, bearer),
    checked = decodeRelayBlobRead(request),
    descriptor = decodeRelayBlobDescriptor(expected);
  for (const [key, value] of Object.entries(checked)) {
    if (typeof value !== 'string')
      throw new Error('Invalid Relay binary read metadata');
    url.searchParams.set(key, value);
  }
  const response = await http(url, {
    method: 'GET',
    headers: { Authorization: `Bearer ${bearer}` },
    redirect: 'error',
    signal: AbortSignal.timeout(60000),
  });
  if (!response.ok)
    throw new Error(`Relay binary download refused (${response.status})`);
  const length = response.headers.get('content-length'),
    sha = response.headers.get('x-plowshare-blob-sha256'),
    media = response.headers.get('x-plowshare-blob-media-type');
  if (
    response.headers.get('content-type') !== 'application/octet-stream' ||
    (length !== null && length !== String(descriptor.byteLength)) ||
    (sha !== null && sha !== descriptor.sha256) ||
    (media !== null && media !== descriptor.mediaType)
  )
    throw new Error('Relay binary download headers differ from its descriptor');
  const bytes = await bounded(response, descriptor.byteLength);
  if (
    bytes.length !== descriptor.byteLength ||
    createHash('sha256').update(bytes).digest('hex') !== descriptor.sha256
  )
    throw new Error('Relay binary download failed integrity verification');
  return bytes;
}

export type {
  RelayBlobDescriptor,
  RelayBlobPublish,
  RelayBlobPublished,
  RelayBlobRead,
};
