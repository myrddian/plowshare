import { describe, expect, it } from 'vitest';
import { decodePayload, decodeReply } from './schema.ts';

const id = '11111111-1111-4111-8111-111111111111';
const scope = {
  kind: 'project',
  project: 'fixture',
  includeGlobal: false,
} as const;
const document = {
  resource: id,
  revision: id,
  title: 'Discovery title',
  sourceName: 'fixture.md',
  owner: 'fixture',
  tags: ['manual'],
  scope,
  contentMetadata: {
    type: {
      type: id,
      version: id,
      ordinal: 1,
      slug: 'incident',
      label: 'Incident',
      definitionHash: 'a'.repeat(64),
    },
    metadataHash: 'b'.repeat(64),
    metadata: { title: 'Typed title', note: null },
  },
};
describe('paged document metadata projection', () => {
  it('requires an explicit supporting view and closed projection option', () => {
    expect(
      decodePayload('information.documents.list', {
        scope,
        view: 'content-types-v1',
        projection: 'metadata-v1',
        limit: 50,
      }),
    ).toMatchObject({ projection: 'metadata-v1' });
    for (const request of [
      { scope, projection: 'metadata-v1' },
      { scope, view: 'content-types-v1', projection: 'body' },
      { scope, view: 'content-types-v1', projection: null },
    ])
      expect(() =>
        decodePayload('information.documents.list', request),
      ).toThrow();
  });
  it('decodes compact immutable instance fields without requiring a schema', () => {
    const result = decodeReply('information.documents.list', {
      documents: [document],
      limit: 50,
      offset: 0,
      projection: 'metadata-v1',
      future: 'ignored',
    });
    expect(result.documents[0]?.contentMetadata?.metadata).toEqual({
      title: 'Typed title',
      note: null,
    });
    expect(result).not.toHaveProperty('future');
    expect(result.documents[0]?.contentMetadata).not.toHaveProperty('schema');
    const { contentMetadata, ...header } = document;
    expect(contentMetadata).toBeDefined();
    expect(
      decodeReply('information.documents.list', {
        documents: [header],
        limit: 50,
        offset: 0,
      }),
    ).not.toHaveProperty('projection');
  });
  it('rejects malformed identities, digests and oversized metadata', () => {
    const projected = (
      contentMetadata: unknown,
      projection: unknown = 'metadata-v1',
    ) => ({
      documents: [{ ...document, contentMetadata }],
      limit: 50,
      offset: 0,
      projection,
    });
    for (const contentMetadata of [
      { ...document.contentMetadata, metadataHash: 'invalid' },
      {
        ...document.contentMetadata,
        type: { ...document.contentMetadata.type, ordinal: 0 },
      },
      {
        ...document.contentMetadata,
        type: { ...document.contentMetadata.type, version: 'invalid' },
      },
      { ...document.contentMetadata, metadata: { title: 'x'.repeat(4097) } },
    ])
      expect(() =>
        decodeReply('information.documents.list', projected(contentMetadata)),
      ).toThrow();
  });
});
