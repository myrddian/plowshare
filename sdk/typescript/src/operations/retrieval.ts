import { isList } from '../binding/values.ts';
/** Retrieval wire types mirror the server response records. Dates and ids stay
 * strings; enum names stay open to server additions. No rendering or I/O. */
export interface MemoryHome {
  readonly project: string | null;
}
export interface MemoryProvenance {
  readonly at: string;
  readonly by: string;
  readonly where: string;
}
export interface MemoryInvalidation {
  readonly at: string;
  readonly by: string;
  readonly reason: string;
}
export interface MemoryRecord {
  readonly id: string;
  readonly summary: string;
  readonly scope: string;
  readonly formed: MemoryProvenance;
  readonly state: string;
  readonly pinned: boolean;
  readonly uses: number;
  readonly lastUsed: string | null;
  readonly body: string;
  readonly supersedes: string | null;
  readonly supersededBy: string | null;
  readonly invalidation: MemoryInvalidation | null;
  readonly home: MemoryHome;
}
export interface MemoryIndexEntry {
  readonly id: string;
  readonly summary: string;
  readonly scope: string;
  readonly unsearchable: boolean;
}
export interface RecallResponse {
  readonly question: string;
  readonly limit: number;
  readonly memories: readonly MemoryRecord[];
  readonly unsearchable: number;
}
export interface MemoryWriteResponse {
  readonly kind: string;
  readonly memoryId: string;
  readonly reason: string;
  readonly targetId: string | null;
  readonly demoted: readonly string[];
}
export interface PassageCoverage {
  readonly eligible: number;
  readonly indexed: number;
  readonly passages: number;
  readonly pending: number;
  readonly failed: number;
  readonly stale: number;
}
export interface NavigationRetrieval {
  readonly tier: string;
  readonly globalFallback: boolean;
  readonly seedMode: string;
  readonly coverage: PassageCoverage | null;
  readonly fallback: string | null;
  readonly queryEmbeddingCalls: number;
  readonly generation: string | null;
}
export interface MemoryNavigation {
  readonly retrieval?: NavigationRetrieval;
  readonly level: string;
  readonly ids: readonly string[];
  readonly text: string;
  readonly complete: boolean;
  readonly modelCalls: number;
}
export interface MemoryRepair {
  readonly repaired: readonly string[];
  readonly failed: readonly string[];
}
export interface Reconsidered {
  readonly reopened: readonly string[];
  readonly refused: readonly string[];
}
export interface ProposalView {
  readonly id: string;
  readonly memoryId: string;
  readonly project: string | null;
  readonly action: string;
  readonly reason: string;
  readonly state: string;
  readonly createdAt: string;
  readonly proposedBy: string;
  readonly resolvedAt: string | null;
  readonly resolvedBy: string | null;
  readonly resolution: string | null;
}
export interface ResolvedProposal {
  readonly proposal: ProposalView;
  readonly promotedId: string | null;
  readonly demoted: readonly string[];
}
export interface UnitView {
  readonly id: string;
  readonly title: string | null;
  readonly synthetic: boolean;
  readonly summary: string | null;
}
export interface DocumentListEntry {
  readonly documentId: string;
  readonly sourceName: string;
  readonly title: string;
  readonly summary: string | null;
  readonly vocabulary: string | null;
  readonly ingestedAt: string;
  readonly ingestedBy: string;
  readonly byteSize: number;
  readonly chapters: number;
  readonly sections: number;
  readonly paragraphs: number;
  readonly chunks: number;
}
export interface DocumentListResponse {
  readonly documents: readonly DocumentListEntry[];
  readonly total: number;
  readonly limit: number;
  readonly offset: number;
  readonly naming: string | null;
}
export interface DocumentChapter {
  readonly id: string;
  readonly title: string | null;
  readonly synthetic: boolean;
  readonly summary: string | null;
  readonly sections: readonly UnitView[];
}
export interface DocumentDetailResponse {
  readonly documentId: string;
  readonly sourceName: string;
  readonly title: string;
  readonly summary: string | null;
  readonly vocabulary: string | null;
  readonly ingestedAt: string;
  readonly ingestedBy: string;
  readonly byteSize: number;
  readonly chapters: readonly DocumentChapter[];
}
export interface ChunkDetailResponse {
  readonly chunkId: string;
  readonly text: string;
  readonly paragraphId: string;
  readonly paragraphOrdinal: number;
  readonly paragraphSummary: string | null;
  readonly section: UnitView | null;
  readonly chapter: UnitView | null;
  readonly documentId: string;
  readonly sourceName: string;
  readonly title: string;
  readonly documentSummary: string | null;
}
export interface RetrieveHit {
  readonly score: number;
  readonly chunk: ChunkDetailResponse;
}
export interface RetrieveResponse {
  readonly query: string;
  readonly document: string | null;
  readonly limit: number;
  readonly hits: readonly RetrieveHit[];
}
export interface RankedDocument {
  readonly documentId: string;
  readonly sourceName: string;
  readonly title: string;
  readonly summary: string | null;
  readonly ingestedAt: string;
  readonly score: number;
}
export interface DocumentRankingResponse {
  readonly query: string;
  readonly limit: number;
  readonly documents: readonly RankedDocument[];
  readonly rankable: number;
  readonly unranked: number;
}
export interface DocumentStanceResponse {
  readonly documentId: string;
  readonly claim: string;
  readonly topical: number;
  readonly stance: number;
  readonly basis: string;
}
export interface DocumentSearchHit {
  readonly chunkId: string;
  readonly text: string;
  readonly similarity: number;
  readonly paragraphId: string;
  readonly paragraphText: string;
  readonly paragraphOrdinal: number;
  readonly documentId: string;
  readonly sourceName: string;
  readonly title: string;
}
export interface DocumentSearchResponse {
  readonly query: string;
  readonly limit: number;
  readonly mode: string;
  readonly hits: readonly DocumentSearchHit[];
  readonly searchable: number;
  readonly unsearchable: number;
}
export interface Citation {
  readonly id: string;
  readonly standing: string;
  readonly paragraphId: string | null;
  readonly documentId: string | null;
  readonly sourceName: string;
  readonly paragraphOrdinal: number;
  readonly title: string | null;
  readonly paragraphText: string | null;
  readonly conversationId: string | null;
  readonly turnOrdinal: number | null;
  readonly agent: string;
  readonly citedAt: string;
}
export interface CitationsResponse {
  readonly scope: string;
  readonly limit: number;
  readonly citations: readonly Citation[];
}
export interface LogSearchHit {
  readonly conversationId: string;
  readonly ordinal: number;
  readonly turnOrdinal: number;
  readonly kind: string;
  readonly rank: number;
  readonly evidence?: SearchEvidence;
  readonly snippet: string;
  readonly length: number;
  readonly supersededBy: number | null;
  readonly handle: string | null;
  readonly recordedAt: string | null;
}
export interface SearchEvidence {
  readonly retrievedBy: string;
  readonly passagePosition: number | null;
  readonly sourceRevision: string | null;
}
export interface SearchRetrieval {
  readonly requestedMode: string;
  readonly effectiveMode: string;
  readonly totalMeaning: string;
  readonly snapshot: string | null;
  readonly truncated: boolean;
  readonly complete: boolean;
  readonly fallback: string | null;
  readonly coverage: PassageCoverage | null;
  readonly generation: string | null;
  readonly queryEmbeddingCalls: number;
  readonly provenance: string;
}
export interface LogSearchReach {
  readonly searched: number;
  readonly ejected: number;
  readonly recordedOnly: number;
}
export interface LogSearchResponse {
  readonly retrieval?: SearchRetrieval;
  readonly hits: readonly LogSearchHit[];
  readonly total: number;
  readonly offset: number;
  readonly limit: number;
  readonly reach: LogSearchReach;
}
export interface WebSearchHit {
  readonly url: string;
  readonly title: string;
  readonly snippet: string;
}
export interface WebSearchResponse {
  readonly hits: readonly WebSearchHit[];
  readonly page: number;
  readonly pageSize: number;
  readonly total: number;
  readonly hasMore: boolean;
  readonly refusal: string | null;
}
export interface WebFetchResponse {
  readonly url: string;
  readonly title: string | null;
  readonly text: string | null;
  readonly offset: number;
  readonly nextOffset: number;
  readonly total: number;
  readonly hasMore: boolean;
  readonly refusal: string | null;
}

/** Legacy replies omit these metadata fields. Essential findings/coverage are
 * always required; absent metadata remains absent rather than being invented. */
type ObservedRecall = Omit<RecallResponse, 'question' | 'limit'> &
  Partial<Pick<RecallResponse, 'question' | 'limit'>>;
type ObservedSearch = Omit<DocumentSearchResponse, 'mode'> &
  Partial<Pick<DocumentSearchResponse, 'mode'>>;
type ObservedIndex = Omit<MemoryIndexEntry, 'unsearchable'> &
  Partial<Pick<MemoryIndexEntry, 'unsearchable'>>;
export interface RetrievalReplies {
  'memory.index': readonly ObservedIndex[];
  'memory.read': MemoryRecord;
  'memory.invalidate': MemoryRecord;
  'memory.recall': ObservedRecall;
  'memory.write': MemoryWriteResponse;
  'memory.navigate': MemoryNavigation;
  'memory.reembed': MemoryRepair;
  'proposal.list': readonly ProposalView[];
  'proposal.resolve': ResolvedProposal;
  'proposal.reconsider': Reconsidered;
  'conversation.search': LogSearchResponse;
  'document.retrieve': RetrieveResponse;
  'document.list': DocumentListResponse;
  'document.detail': DocumentDetailResponse;
  'document.chunk': ChunkDetailResponse;
  'document.rank': DocumentRankingResponse;
  'document.stance': DocumentStanceResponse;
  'document.citations': CitationsResponse;
  'document.search': ObservedSearch;
  'web.search': WebSearchResponse;
  'web.fetch': WebFetchResponse;
}
export type RetrievalOperation = keyof RetrievalReplies;
export type CheckedReply<T> = T;

type Check = (value: unknown) => boolean;
type Shape = Readonly<Record<string, Check>>;
const object = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !isList(value);
const text: Check = (value) => typeof value === 'string';
const named: Check = (value) =>
  typeof value === 'string' && value.trim() !== '';
const bool: Check = (value) => typeof value === 'boolean';
const count: Check = (value) =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
const positive: Check = (value) => count(value) && (value as number) > 0;
const finite: Check = (value) =>
  typeof value === 'number' && Number.isFinite(value);
const nullable =
  (check: Check): Check =>
  (value) =>
    value === null || check(value);
const optional =
  (check: Check): Check =>
  (value) =>
    value === undefined || check(value);
const list =
  (check: Check): Check =>
  (value) =>
    isList(value) && value.every(check);
const record =
  (
    shape: Shape,
    invariant?: (row: Record<string, unknown>) => boolean,
  ): Check =>
  (value) =>
    object(value) &&
    Object.entries(shape).every(([name, check]) => check(value[name])) &&
    (invariant?.(value) ?? true);
const strings = list(named);
const provenance = record({ at: named, by: named, where: text });
const invalidation = record({ at: named, by: named, reason: text });
const home = record(
  { project: nullable(named), global: optional(bool) },
  (row) =>
    row['global'] === undefined || row['global'] === (row['project'] === null),
);
const memory = record({
  id: named,
  summary: text,
  scope: text,
  formed: provenance,
  state: named,
  pinned: bool,
  uses: count,
  lastUsed: nullable(named),
  body: text,
  supersedes: nullable(named),
  supersededBy: nullable(named),
  invalidation: nullable(invalidation),
  home,
});
const index = record({
  id: named,
  summary: text,
  scope: text,
  unsearchable: optional(bool),
});
const proposal = record({
  id: named,
  memoryId: named,
  project: nullable(named),
  action: named,
  reason: text,
  state: named,
  createdAt: named,
  proposedBy: named,
  resolvedAt: nullable(named),
  resolvedBy: nullable(named),
  resolution: nullable(text),
});
const unit = record(
  {
    id: named,
    title: nullable(text),
    synthetic: bool,
    summary: nullable(text),
  },
  (row) => row['synthetic'] !== true || row['title'] === null,
);
const documentBase = {
  documentId: named,
  sourceName: text,
  title: text,
  summary: nullable(text),
  vocabulary: nullable(text),
  ingestedAt: named,
  ingestedBy: named,
  byteSize: count,
};
const chapter = record(
  {
    id: named,
    title: nullable(text),
    synthetic: bool,
    summary: nullable(text),
    sections: list(unit),
  },
  (row) => row['synthetic'] !== true || row['title'] === null,
);
const chunk = record({
  chunkId: named,
  text,
  paragraphId: named,
  paragraphOrdinal: positive,
  paragraphSummary: nullable(text),
  section: nullable(unit),
  chapter: nullable(unit),
  documentId: named,
  sourceName: text,
  title: text,
  documentSummary: nullable(text),
});
const searchHit = record({
  chunkId: named,
  text,
  similarity: finite,
  paragraphId: named,
  paragraphText: text,
  paragraphOrdinal: positive,
  documentId: named,
  sourceName: text,
  title: text,
});
const citation = record(
  {
    id: named,
    standing: named,
    paragraphId: nullable(named),
    documentId: nullable(named),
    sourceName: text,
    paragraphOrdinal: positive,
    title: nullable(text),
    paragraphText: nullable(text),
    conversationId: nullable(named),
    turnOrdinal: nullable(positive),
    agent: named,
    citedAt: named,
  },
  (row) =>
    row['standing'] === 'resolves'
      ? named(row['paragraphId']) &&
        named(row['documentId']) &&
        text(row['paragraphText'])
      : row['standing'] === 'paragraph_gone'
        ? row['paragraphId'] === null &&
          named(row['documentId']) &&
          row['paragraphText'] === null
        : row['standing'] === 'document_gone'
          ? row['paragraphId'] === null &&
            row['documentId'] === null &&
            row['paragraphText'] === null
          : true,
);
const coverage = record({
  eligible: count,
  indexed: count,
  passages: count,
  pending: count,
  failed: count,
  stale: count,
});
const navigationRetrieval = record({
  tier: named,
  globalFallback: bool,
  seedMode: named,
  coverage: nullable(coverage),
  fallback: nullable(text),
  queryEmbeddingCalls: count,
  generation: nullable(named),
});
const searchRetrieval = record({
  requestedMode: named,
  effectiveMode: named,
  totalMeaning: named,
  snapshot: nullable(named),
  truncated: bool,
  complete: bool,
  fallback: nullable(text),
  coverage: nullable(coverage),
  generation: nullable(named),
  queryEmbeddingCalls: count,
  provenance: named,
});
const evidence = record({
  retrievedBy: named,
  passagePosition: nullable(count),
  sourceRevision: nullable(named),
});
const logHit = record({
  evidence: optional(evidence),
  conversationId: named,
  ordinal: positive,
  turnOrdinal: positive,
  kind: named,
  rank: finite,
  snippet: text,
  length: count,
  supersededBy: nullable(positive),
  handle: nullable(named),
  recordedAt: nullable(named),
});

/** This explicit menu is also the response-validation inventory. No fallback
 * manufactures an empty array, coverage count or successful mutation result. */
const readers = {
  'memory.index': list(index),
  'memory.read': memory,
  'memory.invalidate': memory,
  'memory.recall': record({
    question: optional(text),
    limit: optional(positive),
    memories: list(memory),
    unsearchable: count,
  }),
  'memory.write': record(
    {
      kind: named,
      memoryId: named,
      reason: text,
      targetId: nullable(named),
      demoted: strings,
    },
    (row) =>
      !['supersedes', 'merged_into'].includes(row['kind'] as string) ||
      named(row['targetId']),
  ),
  'memory.navigate': record({
    retrieval: optional(navigationRetrieval),
    level: named,
    ids: strings,
    text,
    complete: bool,
    modelCalls: count,
  }),
  'memory.reembed': record({ repaired: strings, failed: strings }),
  'proposal.list': list(proposal),
  'proposal.resolve': record({
    proposal,
    promotedId: nullable(named),
    demoted: strings,
  }),
  'proposal.reconsider': record({ reopened: strings, refused: strings }),
  'conversation.search': record({
    retrieval: optional(searchRetrieval),
    hits: list(logHit),
    total: count,
    offset: count,
    limit: positive,
    reach: record({ searched: count, ejected: count, recordedOnly: count }),
  }),
  'document.retrieve': record({
    query: text,
    document: nullable(named),
    limit: positive,
    hits: list(record({ score: finite, chunk })),
  }),
  'document.list': record({
    documents: list(
      record({
        ...documentBase,
        chapters: count,
        sections: count,
        paragraphs: count,
        chunks: count,
      }),
    ),
    total: count,
    limit: positive,
    offset: count,
    naming: nullable(text),
  }),
  'document.detail': record({ ...documentBase, chapters: list(chapter) }),
  'document.chunk': chunk,
  'document.rank': record({
    query: text,
    limit: positive,
    documents: list(
      record({
        documentId: named,
        sourceName: text,
        title: text,
        summary: nullable(text),
        ingestedAt: named,
        score: finite,
      }),
    ),
    rankable: count,
    unranked: count,
  }),
  'document.stance': record({
    documentId: named,
    claim: text,
    topical: finite,
    stance: finite,
    basis: named,
  }),
  'document.citations': record({
    scope: named,
    limit: positive,
    citations: list(citation),
  }),
  'document.search': record({
    query: text,
    limit: positive,
    mode: optional(named),
    hits: list(searchHit),
    searchable: count,
    unsearchable: count,
  }),
  'web.search': record({
    hits: list(record({ url: named, title: text, snippet: text })),
    page: positive,
    pageSize: positive,
    total: count,
    hasMore: bool,
    refusal: nullable(text),
  }),
  'web.fetch': record(
    {
      url: named,
      title: nullable(text),
      text: nullable(text),
      offset: count,
      nextOffset: count,
      total: count,
      hasMore: bool,
      refusal: nullable(text),
    },
    (row) =>
      (row['nextOffset'] as number) >= (row['offset'] as number) &&
      (named(row['refusal']) || text(row['text'])) &&
      (row['hasMore'] !== true ||
        ((row['nextOffset'] as number) > (row['offset'] as number) &&
          (row['nextOffset'] as number) < (row['total'] as number))),
  ),
} satisfies Record<RetrievalOperation, Check>;
export const RETRIEVAL_OPERATIONS = Object.keys(
  readers,
) as readonly RetrievalOperation[];
export function isRetrievalOperation(type: string): type is RetrievalOperation {
  return Object.hasOwn(readers, type);
}
/** Return the original object/array after validation, preserving future fields
 * and every server value. Never coerce, trim, re-order or fill missing metadata. */
export function retrievalReply<T extends RetrievalOperation>(
  type: T,
  payload: unknown,
): CheckedReply<RetrievalReplies[T]> | undefined {
  return readers[type](payload)
    ? (payload as CheckedReply<RetrievalReplies[T]>)
    : undefined;
}
