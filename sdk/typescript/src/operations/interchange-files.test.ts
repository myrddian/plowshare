import { createHash } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { decodePayload, decodeReply } from './schema.ts';
import type {
  PortableBundle,
  PortableImportPlan,
} from './information-interchange.ts';
import {
  packEditedPortableBundle,
  readablePortableFiles,
  unpackPortableBundle,
  type PortableCodec,
} from './interchange-files.ts';
const id = '11111111-1111-4111-8111-111111111111';
const revision = '22222222-2222-4222-8222-222222222222';
const text = '\uFEFF# Refund safety 🔎\r\nDisable automatic refunds.\r\n';
const original = Buffer.from(text, 'utf8');
const sum = (bytes: Uint8Array) =>
  createHash('sha256').update(bytes).digest('hex');
const codec: PortableCodec = {
  encode: (text) => Buffer.from(text, 'utf8'),
  base64: (bytes) => Buffer.from(bytes).toString('base64'),
  unbase64: (text) => new Uint8Array(Buffer.from(text, 'base64')),
  hash: (bytes) => Promise.resolve(sum(bytes)),
};
function bundle(): PortableBundle {
  const hash = sum(original);
  return {
    format: 'plowshare-information-bundle/1',
    id,
    relationshipCoverage: 'included-endpoints-only',
    relationships: [],
    documents: [
      {
        descriptor: {
          resource: id,
          revision,
          ordinal: 1,
          sourceName: 'refund.md',
          title: 'Refund safety',
          path: 'documents/refund.md',
          mediaType: 'text/markdown; charset=utf-8',
          contentHash: hash,
          tags: ['billing'],
          provenance: {
            owner: 'foreign-owner',
            kind: 'source',
            createdAt: '2026-10-16T00:00:00Z',
            sourceHash: hash,
            dependencies: [],
            evidence: [],
          },
        },
        bytesBase64: original.toString('base64'),
      },
    ],
  };
}
function plan(): PortableImportPlan {
  return {
    bundle: bundle(),
    destinations: [
      { resource: id, name: 'imported/refund.md', mode: 'create' },
    ],
    types: [],
    reuse: [],
    restoreRelationships: false,
  };
}
describe('portable external authoring', () => {
  it('preserves original Markdown BOM, CRLF, Unicode and exact byte identity', async () => {
    const source = bundle(),
      unpacked = await unpackPortableBundle(source, codec);
    expect(Buffer.from(unpacked.files[0]?.bytes ?? [])).toEqual(original);
    expect(
      await packEditedPortableBundle(unpacked.manifest, unpacked.files, codec),
    ).toEqual(source);
  });
  it('repackages an explicit edit while retaining the original source claim', async () => {
    const source = bundle(),
      unpacked = await unpackPortableBundle(source, codec);
    const changed = Buffer.from(text + 'External edit.\r\n');
    const packed = await packEditedPortableBundle(
      unpacked.manifest,
      [{ path: 'documents/refund.md', bytes: changed }],
      codec,
    );
    const d = packed.documents[0]?.descriptor;
    expect(d?.contentHash).toBe(sum(changed));
    expect(d?.revision).toBe(revision);
    expect(d?.provenance.sourceHash).toBe(sum(original));
    expect(source.documents[0]?.bytesBase64).toBe(original.toString('base64'));
  });
  it('refuses corrupt content, unsafe paths and missing or extra files', async () => {
    const source = bundle(),
      doc = source.documents[0];
    if (!doc) throw new Error('Missing fixture');
    expect(() =>
      decodeReply('information.interchange.export', {
        ...source,
        documents: [
          { ...doc, bytesBase64: Buffer.from('tampered').toString('base64') },
        ],
      }),
    ).toThrow();
    for (const path of [
      '../outside.md',
      '/absolute.md',
      'safe/CON.md',
      'safe//file.md',
    ])
      expect(() =>
        decodeReply('information.interchange.export', {
          ...source,
          documents: [{ ...doc, descriptor: { ...doc.descriptor, path } }],
        }),
      ).toThrow();
    const unpacked = await unpackPortableBundle(source, codec);
    await expect(
      packEditedPortableBundle(unpacked.manifest, [], codec),
    ).rejects.toThrow('missing');
    await expect(
      packEditedPortableBundle(
        unpacked.manifest,
        [...unpacked.files, { path: 'extra.md', bytes: original }],
        codec,
      ),
    ).rejects.toThrow('not present');
  });
  it('requires complete identity maps, exact revision choices and no imported permissions', () => {
    const source = plan();
    expect(
      decodePayload('information.interchange.import', {
        scope: { kind: 'personal' },
        requestId: id,
        plan: source,
      }).plan,
    ).toEqual(source);
    for (const changed of [
      { destinations: [] },
      { destinations: [{ resource: id, name: 'refund.md', mode: 'revise' }] },
      { permissions: ['admin'] },
      {
        reuse: [{ foreignRevision: revision, localRevision: id }],
        destinations: [],
      },
    ])
      expect(() =>
        decodePayload('information.interchange.import', {
          scope: { kind: 'personal' },
          requestId: id,
          plan: { ...source, ...changed },
        }),
      ).toThrow();
    expect(() =>
      decodePayload('information.interchange.export', {
        scope: { kind: 'personal' },
        revisions: [revision, revision],
      }),
    ).toThrow();
  });
  it('labels extracted Markdown separately from original bytes', async () => {
    const d = bundle().documents[0]?.descriptor;
    if (!d) throw new Error('Missing fixture');
    const readable = {
      format: 'plowshare-information-bundle/1' as const,
      path: 'documents/extracted.md',
      representation: 'extraction' as const,
      markdown: 'Extracted text',
      textHash: sum(Buffer.from('Extracted text')),
      sidecar: { ...d, mediaType: 'application/pdf' },
    };
    const files = await readablePortableFiles(readable, codec);
    expect(Buffer.from(files[0]?.bytes ?? []).toString('utf8')).toBe(
      'Extracted text',
    );
    expect(Buffer.from(files[1]?.bytes ?? []).toString('utf8')).toContain(
      '"representation": "extraction"',
    );
    await expect(
      readablePortableFiles({ ...readable, textHash: '0'.repeat(64) }, codec),
    ).rejects.toThrow('Invalid portable data');
  });
});
