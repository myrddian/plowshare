import { isObject } from '../binding/values.ts';

export const IMAGE_ADMISSION_PROFILE = 'invocation-image/1';
export interface ImageAdmissionRequest {
  readonly invocation: string;
  readonly ordinal: number;
  readonly requestId: string;
}
export interface ImageAdmissionReceipt {
  readonly request: ImageAdmissionRequest;
  readonly project: string;
  readonly owner: string;
  readonly producer: string;
  readonly imageId: string;
  readonly mimeType: 'image/png' | 'image/jpeg' | 'image/webp';
  readonly width: number;
  readonly height: number;
  readonly byteLength: number;
  readonly sha256: string;
  readonly admittedAt: string;
}
export type ImageAdmissionStatus =
  | { readonly state: 'ABSENT'; readonly receipt: null }
  | {
      readonly state: 'COMMITTED' | 'UNAVAILABLE';
      readonly receipt: ImageAdmissionReceipt;
    };

const uuid = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/;
const subject =
  /^(?:[A-Za-z0-9][A-Za-z0-9_.-]{0,63}|@service\/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12})$/;
function identifier(value: unknown, max: number): value is string {
  return (
    typeof value === 'string' &&
    value.length > 0 &&
    value.length <= max &&
    value === value.trim() &&
    !Array.from(value).some((c) => {
      const n = c.charCodeAt(0);
      return n < 32 || (n >= 127 && n <= 159) || n === 0x2028 || n === 0x2029;
    })
  );
}
function integer(value: unknown, min: number, max: number): value is number {
  return (
    typeof value === 'number' &&
    Number.isSafeInteger(value) &&
    value >= min &&
    value <= max
  );
}
export function decodeImageAdmissionRequest(
  value: unknown,
): ImageAdmissionRequest {
  if (
    !isObject(value) ||
    typeof value['invocation'] !== 'string' ||
    !uuid.test(value['invocation']) ||
    typeof value['requestId'] !== 'string' ||
    !uuid.test(value['requestId']) ||
    !integer(value['ordinal'], 0, 15)
  )
    throw new Error('Invalid image admission identity');
  return {
    invocation: value['invocation'],
    ordinal: value['ordinal'],
    requestId: value['requestId'],
  };
}
/** Same SHA-256-derived slot as the Java protocol. Host supplies hashing, never randomness. */
export async function imageAdmissionRequest(
  host: { sha256(text: string): Promise<string> },
  invocation: string,
  ordinal: number,
): Promise<ImageAdmissionRequest> {
  if (!uuid.test(invocation) || !integer(ordinal, 0, 15))
    throw new Error('Invalid image admission slot');
  const hash = await host.sha256(
    `${IMAGE_ADMISSION_PROFILE}\n${invocation}\n${ordinal}`,
  );
  if (!/^[0-9a-f]{64}$/.test(hash))
    throw new Error('Invalid SHA-256 from image admission host');
  const h = hash.slice(0, 32);
  return {
    invocation,
    ordinal,
    requestId: `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`,
  };
}
export function decodeImageAdmissionReceipt(
  value: unknown,
): ImageAdmissionReceipt {
  if (!isObject(value)) throw new Error('Invalid image admission receipt');
  const request = decodeImageAdmissionRequest(value['request']);
  if (
    !identifier(value['project'], 1024) ||
    typeof value['owner'] !== 'string' ||
    !subject.test(value['owner']) ||
    typeof value['producer'] !== 'string' ||
    !subject.test(value['producer']) ||
    typeof value['sha256'] !== 'string' ||
    !/^[0-9a-f]{64}$/.test(value['sha256']) ||
    value['imageId'] !== `img_${value['sha256'].slice(0, 32)}` ||
    (value['mimeType'] !== 'image/png' &&
      value['mimeType'] !== 'image/jpeg' &&
      value['mimeType'] !== 'image/webp') ||
    !integer(value['width'], 1, 32768) ||
    !integer(value['height'], 1, 32768) ||
    value['width'] * value['height'] > 64000000 ||
    !integer(value['byteLength'], 1, 2147483647) ||
    typeof value['admittedAt'] !== 'string' ||
    value['admittedAt'].length > 40 ||
    !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(
      value['admittedAt'],
    ) ||
    !Number.isFinite(Date.parse(value['admittedAt']))
  )
    throw new Error('Invalid image admission descriptor');
  return {
    request,
    project: value['project'],
    owner: value['owner'],
    producer: value['producer'],
    imageId: `img_${value['sha256'].slice(0, 32)}`,
    mimeType: value['mimeType'],
    width: value['width'],
    height: value['height'],
    byteLength: value['byteLength'],
    sha256: value['sha256'],
    admittedAt: value['admittedAt'],
  };
}
export function validateImageAdmissionReply(
  type: string,
  value: unknown,
): void {
  if (type !== 'artifact.admission.status') return;
  if (!isObject(value)) throw new Error('Invalid image admission status');
  if (value['state'] === 'ABSENT' && value['receipt'] === null) return;
  if (value['state'] !== 'COMMITTED' && value['state'] !== 'UNAVAILABLE')
    throw new Error('Invalid image admission state');
  decodeImageAdmissionReceipt(value['receipt']);
}
