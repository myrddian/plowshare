import { createHash } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import {
  imageAdmissionRequest,
  decodeImageAdmissionReceipt,
} from './image-admissions.ts';
import { decodePayload, decodeReply } from './schema.ts';

describe('invocation image admission', () => {
  it('derives one stable slot, validates bounded descriptors and preserves absent versus unavailable', async () => {
    const host = {
      sha256: (text: string) =>
        Promise.resolve(createHash('sha256').update(text).digest('hex')),
    };
    const invocation = '11111111-1111-1111-1111-111111111111';
    const request = await imageAdmissionRequest(host, invocation, 0);
    expect(request).toEqual(await imageAdmissionRequest(host, invocation, 0));
    expect(request.requestId).not.toBe(
      (await imageAdmissionRequest(host, invocation, 1)).requestId,
    );
    expect(decodePayload('artifact.admission.status', request)).toEqual(
      request,
    );
    for (const ordinal of [-1, 16, 0.5, '0'])
      expect(() =>
        decodePayload('artifact.admission.status', { ...request, ordinal }),
      ).toThrow();
    expect(() =>
      decodePayload('artifact.admission.status', {
        ...request,
        project: 'foreign',
      }),
    ).toThrow();
    const receipt = {
      request,
      project: 'atlas',
      owner: 'owner',
      producer: 'producer',
      imageId: `img_${'a'.repeat(32)}`,
      mimeType: 'image/png',
      width: 2,
      height: 3,
      byteLength: 100,
      sha256: 'a'.repeat(64),
      admittedAt: '2026-10-22T00:00:00Z',
    };
    expect(decodeImageAdmissionReceipt(receipt)).toEqual(receipt);
    for (const mimeType of ['image/jpeg', 'image/webp']) {
      const supported = { ...receipt, mimeType };
      expect(decodeImageAdmissionReceipt(supported)).toEqual(supported);
      expect(
        decodeReply('artifact.admission.status', {
          state: 'COMMITTED',
          receipt: supported,
        }).receipt,
      ).toEqual(supported);
    }
    expect(
      decodeReply('artifact.admission.status', {
        state: 'ABSENT',
        receipt: null,
      }).state,
    ).toBe('ABSENT');
    expect(
      decodeReply('artifact.admission.status', {
        state: 'UNAVAILABLE',
        receipt,
      }).state,
    ).toBe('UNAVAILABLE');
    for (const changed of [
      { width: 0 },
      { byteLength: -1 },
      { sha256: 'bad' },
      { imageId: `img_${'b'.repeat(32)}` },
      { mimeType: 'image/gif' },
    ])
      expect(() =>
        decodeImageAdmissionReceipt({ ...receipt, ...changed }),
      ).toThrow();
    expect(() =>
      decodeReply('artifact.admission.status', {
        state: 'COMMITTED',
        receipt: null,
      }),
    ).toThrow();
  });
});
