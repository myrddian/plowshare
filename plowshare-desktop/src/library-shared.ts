import type { InformationWindow } from 'plowshare-client-ts/operations/information-replies';
import type {
  RetrievalReplies,
  DocumentDetailResponse,
  ChunkDetailResponse,
  MemoryRecord,
  CitationsResponse,
  DocumentStanceResponse,
} from 'plowshare-client-ts/operations/retrieval';
export type LibraryView =
  'sources' | 'documents' | 'memories' | 'search' | 'manual';
export type SearchKind =
  'conversation' | 'documents' | 'retrieve' | 'rank' | 'recall' | 'navigate';
export interface Reading<T> {
  value?: T;
  loading?: boolean;
  stale?: boolean;
  error?: string;
}
export interface LibraryState {
  view: LibraryView;
  report?: { revision: string; sequence: number };
  manual?: { chapter: string; sequence: number };
  project: string | null;
  selected?: 'document' | 'memory' | 'chunk';
  documents: Reading<RetrievalReplies['document.list']> & { query: string };
  memories: Reading<RetrievalReplies['memory.index']>;
  proposals: Reading<RetrievalReplies['proposal.list']>;
  document: Reading<DocumentDetailResponse> & { id?: string };
  reader?: Reading<InformationWindow> & { id: string };
  chunk: Reading<ChunkDetailResponse> & { id?: string };
  memory: Reading<MemoryRecord> & { id?: string };
  citations: Reading<CitationsResponse>;
  stance: Reading<DocumentStanceResponse> & { claim?: string };
  search: Reading<SearchValue>;
  busy?: boolean;
  notice?: string;
  error?: string;
}
export type SearchValue = {
  kind: SearchKind;
  query: string;
  project: string | null;
  mode: 'lexical' | 'semantic' | 'hybrid';
  reply:
    | RetrievalReplies['conversation.search']
    | RetrievalReplies['document.search']
    | RetrievalReplies['document.retrieve']
    | RetrievalReplies['document.rank']
    | RetrievalReplies['memory.recall']
    | RetrievalReplies['memory.navigate'];
};
export const emptyLibrary = (): LibraryState => ({
  view: 'documents',
  project: null,
  documents: { query: '' },
  memories: {},
  proposals: {},
  document: {},
  chunk: {},
  memory: {},
  citations: {},
  stance: {},
  search: {},
});
export const libraryIdentity = (value: unknown) => JSON.stringify(value);

/** Reading a memory counts a use server-side; those two counters are not an edit. */
export function memoryIdentity(value: MemoryRecord): string {
  const { uses: _uses, lastUsed: _lastUsed, ...content } = value;
  return libraryIdentity(content);
}
