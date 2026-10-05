import { isList } from '../binding/values.ts';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';
import { dispatch, request, type Request } from './direct.ts';
import {
  RETRIEVAL_OPERATIONS,
  retrievalReply,
  type RetrievalOperation,
} from './retrieval.ts';

const fixtures = JSON.parse(
  readFileSync(
    new URL(
      '../../../../test-support/contracts/ws-retrieval-fixtures.json',
      import.meta.url,
    ),
    'utf8',
  ),
) as { replies: Record<RetrievalOperation, unknown> };
const clone = (type: RetrievalOperation): Record<string, unknown> =>
  structuredClone(fixtures.replies[type]) as Record<string, unknown>;
const read = (type: RetrievalOperation, payload: unknown) =>
  dispatch(
    {
      async ask() {
        return { code: 'OK', payload };
      },
    },
    { type, payload: {} } as Request,
  );

describe('shared retrieval response boundary', () => {
  it('covers every reviewed response fixture and retains raw arrays, metadata and future fields', async () => {
    expect([...RETRIEVAL_OPERATIONS].sort()).toEqual(
      Object.keys(fixtures.replies).sort(),
    );
    for (const type of RETRIEVAL_OPERATIONS) {
      const payload = structuredClone(fixtures.replies[type]);
      if (!isList(payload))
        (payload as Record<string, unknown>)['future'] = { kept: true };
      expect(retrievalReply(type, payload), type).toBe(payload);
      expect((await read(type, payload)).kind, type).toBe(
        type === 'memory.navigate' ? 'incomplete' : 'completed',
      );
    }
  });
  it.each(RETRIEVAL_OPERATIONS)(
    'never invents an empty finding for missing/primitive %s payloads',
    async (type) => {
      for (const payload of [undefined, null, 'broken', 0]) {
        expect(retrievalReply(type, payload)).toBeUndefined();
        const result = await read(type, payload);
        expect(result.kind).toBe('invalid-response');
        expect(result.outcome.payload).toBe(payload);
      }
    },
  );
  it('accepts genuine empty findings while preserving nonzero incomplete coverage', () => {
    for (const [type, field] of [
      ['memory.recall', 'memories'],
      ['document.search', 'hits'],
      ['document.retrieve', 'hits'],
      ['document.list', 'documents'],
      ['document.rank', 'documents'],
      ['document.citations', 'citations'],
      ['conversation.search', 'hits'],
      ['web.search', 'hits'],
    ] as const) {
      const payload = clone(type);
      payload[field] = [];
      expect(retrievalReply(type, payload), type).toBe(payload);
      delete payload[field];
      expect(retrievalReply(type, payload), type).toBeUndefined();
    }
    expect(retrievalReply('memory.index', [])).toEqual([]);
    expect(retrievalReply('proposal.list', [])).toEqual([]);
  });
  it('validates coverage, provenance, nested evidence and synthetic structure without coercion', () => {
    const cases: [RetrievalOperation, string[], unknown][] = [
      ['memory.recall', ['unsearchable'], -1],
      ['memory.recall', ['memories', '0', 'home', 'global'], false],
      ['memory.read', ['formed', 'by'], 3],
      ['memory.read', ['body'], null],
      ['memory.index', ['0', 'unsearchable'], 'false'],
      ['document.search', ['searchable'], 0.5],
      ['document.search', ['hits', '0', 'paragraphId'], ' '],
      ['document.search', ['hits', '0', 'similarity'], Infinity],
      ['document.retrieve', ['hits', '0', 'chunk', 'text'], null],
      ['document.detail', ['chapters', '0', 'title'], 'invented'],
      ['document.rank', ['unranked'], '0'],
      ['document.citations', ['citations', '0', 'paragraphText'], null],
      ['document.citations', ['citations', '1', 'paragraphText'], 'obsolete'],
      ['document.citations', ['citations', '2', 'documentId'], 'foreign'],
      ['conversation.search', ['reach', 'ejected'], null],
      ['web.fetch', ['nextOffset'], 0],
      ['web.fetch', ['nextOffset'], 40],
      ['web.search', ['hits', '0', 'snippet'], false],
      ['proposal.resolve', ['demoted'], null],
      ['memory.reembed', ['failed'], undefined],
    ];
    for (const [type, path, value] of cases) {
      const payload = clone(type);
      let row = payload;
      for (const key of path.slice(0, -1))
        row = row[key] as Record<string, unknown>;
      row[path.at(-1)!] = value;
      expect(
        retrievalReply(type, payload),
        `${type}: ${path.join('.')}`,
      ).toBeUndefined();
    }
  });
  it('retains legacy metadata omissions and future enum names without fabricating evidence', () => {
    const recall = clone('memory.recall');
    delete recall['question'];
    delete recall['limit'];
    expect(retrievalReply('memory.recall', recall)).toBe(recall);
    const search = clone('document.search');
    delete search['mode'];
    expect(retrievalReply('document.search', search)).toBe(search);
    const citations = clone('document.citations');
    (citations['citations'] as Record<string, unknown>[])[0]!['standing'] =
      'future_standing';
    expect(retrievalReply('document.citations', citations)).toBe(citations);
    const write = clone('memory.write');
    write['kind'] = 'future_write_kind';
    expect(retrievalReply('memory.write', write)).toBe(write);
  });
  it('retains refusal bodies and permits EOF windows beyond the original total', async () => {
    const payload = { refusal: 'provider unavailable', future: true };
    expect(await read('web.search', payload)).toEqual({
      kind: 'refused',
      outcome: { code: 'OK', payload },
    });
    const beyond = {
      ...clone('web.fetch'),
      offset: 99,
      nextOffset: 99,
      hasMore: false,
      text: '',
    };
    expect(retrievalReply('web.fetch', beyond)).toBe(beyond);
  });
  it('requires OK for findings and sends malformed mutations exactly once', async () => {
    for (const code of ['CREATED', 'NO_CONTENT'] as const) {
      const outcome = { code, payload: fixtures.replies['document.search'] };
      expect(
        await dispatch(
          {
            async ask() {
              return outcome;
            },
          },
          request('document.search', { query: 'x' }),
        ),
      ).toEqual({ kind: 'invalid-response', outcome });
    }
    let calls = 0;
    const result = await dispatch(
      {
        async ask() {
          calls++;
          return { code: 'OK', payload: { kind: 'new' } };
        },
      },
      request('memory.write', {
        proposal: {
          summary: 'x',
          scope: 'x',
          body: 'x',
          formedBy: 'person',
          formedWhere: '',
        },
      }),
    );
    expect(result.kind).toBe('invalid-response');
    expect(calls).toBe(1);
  });
  it('holds every wire DTO field and type to its actual Java record', () => {
    const source = ts.createSourceFile(
      'retrieval.ts',
      readFileSync(new URL('./retrieval.ts', import.meta.url), 'utf8'),
      ts.ScriptTarget.Latest,
      true,
    );
    const authorities: [
      string,
      string,
      string,
      string[],
      Record<string, string>?,
    ][] = [
      ['MemoryHome', 'protocol/Home', 'Home', ['project']],
      ['MemoryProvenance', 'protocol/Provenance', 'Provenance', []],
      ['MemoryInvalidation', 'protocol/Invalidation', 'Invalidation', []],
      [
        'MemoryRecord',
        'protocol/Memory',
        'Memory',
        ['lastUsed', 'supersedes', 'supersededBy', 'invalidation'],
        {
          Home: 'MemoryHome',
          Provenance: 'MemoryProvenance',
          Invalidation: 'MemoryInvalidation',
        },
      ],
      ['MemoryIndexEntry', 'server/archive/TocEntry', 'TocEntry', []],
      [
        'RecallResponse',
        'server/api/RecallResponse',
        'RecallResponse',
        [],
        { Memory: 'MemoryRecord' },
      ],
      [
        'MemoryWriteResponse',
        'protocol/WriteResult',
        'WriteResult',
        ['targetId'],
      ],
      [
        'MemoryNavigation',
        'server/agents/digests/Navigator',
        'Result',
        [],
        { Retrieval: 'NavigationRetrieval' },
      ],
      [
        'NavigationRetrieval',
        'server/agents/digests/Navigator',
        'Retrieval',
        ['coverage', 'fallback', 'generation'],
        { 'PassageIndex.Coverage': 'PassageCoverage' },
      ],
      ['PassageCoverage', 'server/archive/PassageIndex', 'Coverage', []],
      ['MemoryRepair', 'server/archive/Archive', 'Repair', []],
      ['Reconsidered', 'server/archive/PromotionQueue', 'Reconsidered', []],
      [
        'ProposalView',
        'server/api/ProposalView',
        'ProposalView',
        ['project', 'resolvedAt', 'resolvedBy', 'resolution'],
      ],
      [
        'ResolvedProposal',
        'server/api/ResolvedProposal',
        'ResolvedProposal',
        ['promotedId'],
      ],
      ['UnitView', 'server/api/UnitView', 'UnitView', ['title', 'summary']],
      [
        'DocumentListEntry',
        'server/api/DocumentListResponse',
        'Listed',
        ['summary', 'vocabulary'],
      ],
      [
        'DocumentListResponse',
        'server/api/DocumentListResponse',
        'DocumentListResponse',
        ['naming'],
        { Listed: 'DocumentListEntry' },
      ],
      [
        'DocumentChapter',
        'server/api/DocumentDetailResponse',
        'Chapter',
        ['title', 'summary'],
      ],
      [
        'DocumentDetailResponse',
        'server/api/DocumentDetailResponse',
        'DocumentDetailResponse',
        ['summary', 'vocabulary'],
        { Chapter: 'DocumentChapter' },
      ],
      [
        'ChunkDetailResponse',
        'server/api/ChunkDetailResponse',
        'ChunkDetailResponse',
        ['paragraphSummary', 'section', 'chapter', 'documentSummary'],
      ],
      ['RetrieveHit', 'server/api/RetrieveResponse', 'Hit', []],
      [
        'RetrieveResponse',
        'server/api/RetrieveResponse',
        'RetrieveResponse',
        ['document'],
        { Hit: 'RetrieveHit' },
      ],
      [
        'RankedDocument',
        'server/api/DocumentRankingResponse',
        'Ranked',
        ['summary'],
      ],
      [
        'DocumentRankingResponse',
        'server/api/DocumentRankingResponse',
        'DocumentRankingResponse',
        [],
        { Ranked: 'RankedDocument' },
      ],
      [
        'DocumentStanceResponse',
        'server/api/DocumentStanceResponse',
        'DocumentStanceResponse',
        [],
      ],
      ['DocumentSearchHit', 'server/api/DocumentSearchResponse', 'Hit', []],
      [
        'DocumentSearchResponse',
        'server/api/DocumentSearchResponse',
        'DocumentSearchResponse',
        [],
        { Hit: 'DocumentSearchHit' },
      ],
      [
        'Citation',
        'server/api/CitationsResponse',
        'Cited',
        [
          'paragraphId',
          'documentId',
          'title',
          'paragraphText',
          'conversationId',
          'turnOrdinal',
        ],
      ],
      [
        'CitationsResponse',
        'server/api/CitationsResponse',
        'CitationsResponse',
        [],
        { Cited: 'Citation' },
      ],
      [
        'LogSearchHit',
        'server/api/LogSearchView',
        'Hit',
        ['supersededBy', 'handle', 'recordedAt'],
        { Evidence: 'SearchEvidence' },
      ],
      [
        'SearchEvidence',
        'server/api/LogSearchView',
        'Evidence',
        ['passagePosition', 'sourceRevision'],
      ],
      [
        'SearchRetrieval',
        'server/api/LogSearchView',
        'Retrieval',
        ['snapshot', 'fallback', 'coverage', 'generation'],
        {
          'io.aeyer.plowshare.server.archive.PassageIndex.Coverage':
            'PassageCoverage',
        },
      ],
      ['LogSearchReach', 'server/api/LogSearchView', 'Reach', []],
      [
        'LogSearchResponse',
        'server/api/LogSearchView',
        'LogSearchView',
        [],
        {
          Hit: 'LogSearchHit',
          Reach: 'LogSearchReach',
          Retrieval: 'SearchRetrieval',
        },
      ],
      ['WebSearchHit', 'protocol/search/Hit', 'Hit', []],
      [
        'WebSearchResponse',
        'protocol/search/SearchPage',
        'SearchPage',
        ['refusal'],
        { Hit: 'WebSearchHit' },
      ],
      [
        'WebFetchResponse',
        'protocol/fetch/FetchWindow',
        'FetchWindow',
        ['title', 'text', 'refusal'],
      ],
    ];
    for (const [type, path, record, nullable, renamed = {}] of authorities) {
      const [module, ...rest] = path.split('/');
      const java = readFileSync(
        new URL(
          `../../../../plowshare-${module}/src/main/java/io/aeyer/plowshare/${module}/${rest.join('/')}.java`,
          import.meta.url,
        ),
        'utf8',
      ).replace(/@[\w.]+(?:\([^)]*\))?/g, '');
      const fields = new RegExp(`public record ${record}\\(([^)]*)\\)`, 's')
        .exec(java)![1]!
        .split(',')
        .map((part) => {
          const [wireType, name] = part.trim().split(/\s+/);
          function mapped(raw: string): string {
            const list = /^List<(.+)>$/.exec(raw);
            if (list) return `readonly ${mapped(list[1]!)}[]`;
            return (
              (
                {
                  String: 'string',
                  UUID: 'string',
                  Instant: 'string',
                  int: 'number',
                  long: 'number',
                  double: 'number',
                  Integer: 'number',
                  boolean: 'boolean',
                  MemoryState: 'string',
                  VerdictKind: 'string',
                  ...renamed,
                } as Record<string, string>
              )[raw] ?? raw
            );
          }
          return [
            name!,
            mapped(wireType!) + (nullable.includes(name!) ? ' | null' : ''),
          ];
        })
        .sort(([a], [b]) => a!.localeCompare(b!));
      const dto = source.statements.find(
        (node) => ts.isInterfaceDeclaration(node) && node.name.text === type,
      ) as ts.InterfaceDeclaration;
      expect(dto, type).toBeDefined();
      expect(
        dto.members
          .map((member) => {
            const field = member as ts.PropertySignature;
            const optional =
              ((type === 'MemoryNavigation' || type === 'LogSearchResponse') &&
                field.name.getText(source) === 'retrieval') ||
              (type === 'LogSearchHit' &&
                field.name.getText(source) === 'evidence');
            expect(!!field.questionToken, type).toBe(optional);
            return [field.name.getText(source), field.type!.getText(source)];
          })
          .sort(([a], [b]) => a!.localeCompare(b!)),
        type,
      ).toEqual(fields);
    }
    const dtos = source.statements.filter(
      (node) =>
        ts.isInterfaceDeclaration(node) &&
        node.name.text !== 'RetrievalReplies',
    );
    expect(dtos).toHaveLength(authorities.length);
  });
});
