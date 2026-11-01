import { createHash } from 'node:crypto';
import {
  IMAGE_ADMISSION_PROFILE,
  decodeImageAdmissionReceipt,
  decodeImageAdmissionRequest,
  imageAdmissionRequest,
  type ImageAdmissionRequest,
  type ImageAdmissionReceipt,
} from 'plowshare-client-ts/operations/image-admissions';

export function invocationImageRequest(
  invocation: string,
  ordinal: number,
): Promise<ImageAdmissionRequest> {
  return imageAdmissionRequest(
    {
      sha256: (text) =>
        Promise.resolve(createHash('sha256').update(text).digest('hex')),
    },
    invocation,
    ordinal,
  );
}

/** Explicit binary HTTP boundary. No redirects, automatic retry, server path or recapture. */
export async function admitInvocationImage(
  origin: string,
  bearer: string,
  request: ImageAdmissionRequest,
  bytes: Uint8Array,
  filename = 'image.png',
  http: typeof fetch = fetch,
): Promise<ImageAdmissionReceipt> {
  const target = new URL(origin);
  if (
    !['http:', 'https:'].includes(target.protocol) ||
    target.username ||
    target.password ||
    target.search ||
    target.hash ||
    (target.pathname !== '/' && target.pathname !== '')
  )
    throw new Error('Image admission requires an explicit server origin');
  if (!bearer.trim() || /[\r\n]/.test(bearer))
    throw new Error('Image admission requires a valid bearer');
  const checked = decodeImageAdmissionRequest(request);
  if (
    bytes.length === 0 ||
    !filename.trim() ||
    filename.length > 256 ||
    Array.from(filename).some((c) => {
      const n = c.charCodeAt(0);
      return n < 32 || (n >= 127 && n <= 159) || n === 0x2028 || n === 0x2029;
    })
  )
    throw new Error('Invalid invocation image upload');
  const body = new FormData();
  body.append('profile', IMAGE_ADMISSION_PROFILE);
  body.append('invocation', checked.invocation);
  body.append('ordinal', String(checked.ordinal));
  body.append('requestId', checked.requestId);
  body.append('name', filename);
  body.append('file', new Blob([new Uint8Array(bytes)]), 'image.png');
  const response = await http(new URL('/v1/images', target), {
    method: 'POST',
    headers: { Authorization: `Bearer ${bearer}` },
    body,
    redirect: 'error',
    signal: AbortSignal.timeout(60000),
  });
  if (!response.ok)
    throw new Error(
      `Image admission refused (${response.status}); reconcile the original slot`,
    );
  // Bound streaming receipt bytes even when Content-Length is absent or false.
  const reader = response.body?.getReader();
  if (reader === undefined)
    throw new Error('Image admission returned no receipt');
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      const data: unknown = chunk.value;
      if (!(data instanceof Uint8Array))
        throw new Error('Invalid image admission response bytes');
      total += data.byteLength;
      if (total > 16384)
        throw new Error('Image admission receipt exceeds bound');
      chunks.push(data);
    }
  } finally {
    await reader.cancel();
    reader.releaseLock();
  }
  const json: unknown = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  const receipt = decodeImageAdmissionReceipt(json);
  if (
    receipt.request.invocation !== checked.invocation ||
    receipt.request.ordinal !== checked.ordinal ||
    receipt.request.requestId !== checked.requestId
  )
    throw new Error('Image admission returned a different slot');
  return receipt;
}
