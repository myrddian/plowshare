import { describe, expect, it } from 'vitest';
import { decodePayload, decodeReply } from './schema.ts';
import { InformationClient, InformationMetadataError } from './information.ts';
import { parseSchemaInstance } from './schema-instances.ts';

const revision = '00000000-0000-4000-8000-000000000001';
const requestId = '00000000-0000-4000-8000-000000000002';
const scope = { kind: 'personal' } as const;
describe('authored information public boundary', () => {
  it('preserves explicit null and missing members and rejects duplicate decoded keys and lossy decimals', () => {
    expect(parseSchemaInstance('{"severity":null}')).toEqual({
      severity: null,
    });
    expect(parseSchemaInstance('{}')).toEqual({});
    expect(() => parseSchemaInstance('{"x":1,"\\u0078":2}')).toThrow(
      /Duplicate/,
    );
    expect(() => parseSchemaInstance('{"x":0.10000000000000001}')).toThrow(
      /round-trip/,
    );
    expect(() =>
      parseSchemaInstance('{"x":"' + 'é'.repeat(9000) + '"}'),
    ).toThrow(/byte/);
  });
  it('requires a supporting view and a closed typing alternative before any write', () => {
    expect(() =>
      decodePayload('information.content.revise', {
        scope,
        requestId,
        expectedRevision: revision,
        typing: { mode: 'clear' },
      }),
    ).toThrow();
    for (const typing of [
      { mode: 'clear', metadata: {} },
      { mode: 'inherit', version: revision },
      { mode: 'set', version: revision },
      { mode: 'set', version: revision, metadata: [], grant: '*' },
    ])
      expect(() =>
        decodePayload('information.content.revise', {
          scope,
          view: 'content-types-v1',
          requestId,
          expectedRevision: revision,
          typing,
        }),
      ).toThrow();
    expect(
      decodePayload('information.content.revise', {
        scope,
        view: 'content-types-v1',
        requestId,
        expectedRevision: revision,
        typing: { mode: 'inherit', metadata: { severity: null } },
      }),
    ).toMatchObject({ typing: { metadata: { severity: null } } });
  });
  it('exposes bounded field diagnostics and never replays a refused mutation', async () => {
    let calls = 0;
    const client = new InformationClient(
      {
        async ask() {
          calls++;
          return {
            code: 'VALIDATION_FAILED',
            said: 'INFORMATION_METADATA_INVALID',
            metadataValidation: {
              status: 'INVALID',
              issues: [
                {
                  instancePointer: '/severity',
                  schemaPointer: '/required',
                  keyword: 'required',
                  message: 'Required metadata field missing',
                },
              ],
              truncated: false,
            },
          };
        },
      },
      scope,
      'documents',
      'content-types-v1',
    );
    const error = await client
      .call('content.revise', {
        view: 'content-types-v1',
        requestId,
        expectedRevision: revision,
        typing: { mode: 'clear' },
      })
      .catch((error: unknown) => error);
    expect(error).toBeInstanceOf(InformationMetadataError);
    expect(String(error)).toContain('/severity');
    expect(calls).toBe(1);
  });
  it('does not interpret unsupported profiles or falsely accept partial capability responses', () => {
    expect(() =>
      decodeReply('information.types.capabilities', {
        capability: 'information.contentTypes.v1',
        view: 'content-types-v1',
        profile: 'foreign-profile',
        typedWrites: true,
      }),
    ).toThrow();
    expect(() =>
      decodeReply('information.content.get', {
        revision,
        contentType: {},
        processingPolicy: 'explicit_only',
        reused: [],
        authorisedStages: [],
      }),
    ).toThrow();
  });
});
