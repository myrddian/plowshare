import { decodeReply } from './schema.ts';
import type {
  InformationRevision,
  InformationAcquisition,
  InformationEvent,
  MigrationDocument,
  MigrationPayload,
  MigrationLog,
  MigrationEntry,
  MigrationJob,
} from './information-views.ts';
import { isList } from '../binding/values.ts';
import type { Outcome } from '../binding/envelope.ts';
import { retrievalReply, type DocumentRankingResponse } from './retrieval.ts';

export type InformationRow = InformationRevision;
/** Source-owned titles remain distinct from parser-invented structural units. */
export type InformationStructuralTitle =
  | { readonly synthetic: true; readonly title?: never }
  | { readonly synthetic: false; readonly title: string };
export interface InformationSearchPlacement {
  readonly sectionId: string;
  readonly sectionTitle: InformationStructuralTitle;
  readonly sectionSummary: string | null;
  readonly chapterId: string;
  readonly chapterTitle: InformationStructuralTitle;
  readonly chapterSummary: string | null;
}
export type InformationUnplaced = {
  readonly sectionId?: never;
  readonly sectionTitle?: never;
  readonly sectionSummary?: never;
  readonly chapterId?: never;
  readonly chapterTitle?: never;
  readonly chapterSummary?: never;
};
export interface InformationSearchChunk {
  readonly chunkId: string;
  readonly chunkText: string;
  readonly paragraphId: string;
  readonly paragraphOrdinal: number;
  readonly paragraphSummary: string | null;
  readonly placement: InformationSearchPlacement | InformationUnplaced;
  readonly documentId: string;
  readonly sourceName: string;
  readonly documentTitle: string | null;
  readonly documentSummary: string | null;
}
/** Scoped search returns ancestry-bearing passages ordered by cosine distance. */
export interface InformationSearchHit {
  readonly chunk: InformationSearchChunk;
  readonly distance: number;
}
export type InformationFacetName =
  | 'kind'
  | 'tags'
  | 'autoTag'
  | 'tagGroup'
  | 'author'
  | 'documentAuthor'
  | 'when'
  | 'subtype';
export interface InformationFacetValue {
  readonly value: string;
  readonly count: number;
}
export interface InformationFacetValues {
  readonly kind: readonly InformationFacetValue[];
  readonly tags: readonly InformationFacetValue[];
  readonly autoTag: readonly InformationFacetValue[];
  readonly tagGroup: readonly InformationFacetValue[];
  readonly author: readonly InformationFacetValue[];
  readonly documentAuthor: readonly InformationFacetValue[];
  readonly when: readonly InformationFacetValue[];
  readonly subtype: readonly InformationFacetValue[];
}
export interface InformationFacetMore {
  readonly kind: boolean;
  readonly tags: boolean;
  readonly autoTag: boolean;
  readonly tagGroup: boolean;
  readonly author: boolean;
  readonly documentAuthor: boolean;
  readonly when: boolean;
  readonly subtype: boolean;
}
export interface InformationTagEdge {
  readonly group: string;
  readonly tag: string;
  readonly count: number;
}
export interface InformationTagGraph {
  readonly edges: readonly InformationTagEdge[];
  readonly hasMore: boolean;
}
export interface InformationFacetCounts {
  readonly total: number;
  readonly facets: InformationFacetValues;
  readonly hasMore: InformationFacetMore;
  readonly tagGraph: InformationTagGraph;
}
export interface InformationAdmission {
  readonly revision: string;
  readonly resource: string;
  readonly created: boolean;
}
export type InformationTicket = InformationAcquisition;
export interface InformationQuestion {
  readonly job: string;
  readonly revision: string;
}
export interface InformationWindow {
  readonly revision: string;
  readonly start: number;
  readonly end: number;
  readonly total: number;
  readonly text: string;
  readonly document_type?: 'document' | 'code';
  readonly document_subtype?: string;
}
export interface CodeSymbol {
  readonly ordinal: number;
  readonly name: string;
  readonly kind: string;
  readonly qualified_name: string;
  readonly parent_ordinal: number | null;
  readonly signature: string;
  readonly start_offset: number;
  readonly end_offset: number;
  readonly start_line: number;
  readonly end_line: number;
}
export interface CodeOutline {
  readonly revision: string;
  readonly language: string;
  readonly status:
    | 'pending'
    | 'unavailable'
    | 'ready'
    | 'partial'
    | 'unsupported'
    | 'limited'
    | 'failed'
    | 'stale';
  readonly reason: string | null;
  readonly parser_version: string;
  readonly source_hash: string | null;
  readonly symbol_count: number;
  readonly offset: number;
  readonly has_more: boolean;
  readonly symbols: readonly CodeSymbol[];
  readonly locator: 'extracted-text:utf16';
  readonly source_kind: 'retained_revision';
  readonly role: string;
}
export interface CodeSymbolMatch extends CodeSymbol {
  readonly revision: string;
  readonly source_name: string;
  readonly language: string;
  readonly outline_status: 'ready' | 'partial' | 'limited';
  readonly source_hash: string;
  readonly parser_version: string;
}
export interface CodeSymbolMatches {
  readonly query: string;
  readonly offset: number;
  readonly has_more: boolean;
  readonly symbols: readonly CodeSymbolMatch[];
  readonly locator: 'extracted-text:utf16';
  readonly source_kind: 'retained_revision';
  readonly role: string;
}
export interface InformationEvidence {
  readonly id: string;
  readonly revision_id: string;
  readonly start_offset: number;
  readonly end_offset: number;
  readonly quote: string;
  readonly locator: string;
  readonly paragraph_id?: string;
  readonly created_at?: string;
}
export type RecordedEvidence = { readonly evidence: string };
export type FinalisedInformation = { readonly finalised: true };
export type QueuedInformation = { readonly queued: true };
export type ReleasedInformation = { readonly released: true };
export type AdoptedInformation = { readonly adopted: true };
export type ChangedInformation = { readonly changed: true };
export type InformationAvailability = { readonly availability: string };
export interface InformationEvents {
  readonly events: readonly InformationEvent[];
  readonly cursor: number;
}
export interface InformationMigrationInventory {
  readonly documents: readonly MigrationDocument[];
  readonly payloads: readonly MigrationPayload[];
}
export interface InformationMigrationInspection {
  readonly log: readonly MigrationLog[];
  readonly entries: readonly MigrationEntry[];
  readonly job: readonly MigrationJob[];
}
export interface InformationAwaitOutcome {
  readonly state: string;
  readonly acquisition?: string;
  readonly revision?: string;
}
export interface InformationFence {
  readonly expected: number;
  readonly settled: number;
  readonly ready: number;
  readonly pending: number;
  readonly complete: boolean;
  readonly outcomes: readonly InformationAwaitOutcome[];
}
export interface InformationReplies {
  'information.await': InformationFence;
  'information.upload': InformationAdmission;
  'information.acquire': InformationTicket;
  'information.facets': InformationFacetCounts;
  'information.tags.groups': ChangedInformation;
  'information.tags': ChangedInformation;
  'information.list': readonly InformationRow[];
  'information.status': InformationRevision | InformationAcquisition;
  'information.read': InformationWindow;
  'information.outline': CodeOutline;
  'information.symbols': CodeSymbolMatches;
  'information.search': readonly InformationSearchHit[];
  'information.rank': DocumentRankingResponse;
  'information.ask': InformationQuestion;
  'information.evidence.record': RecordedEvidence;
  'information.evidence.read': InformationEvidence;
  'information.record.report': InformationAdmission;
  'information.finalise': FinalisedInformation;
  'information.link': ChangedInformation;
  'information.unlink': ChangedInformation;
  'information.share': ChangedInformation;
  'information.unshare': ChangedInformation;
  'information.withdraw': InformationAvailability;
  'information.exclude': InformationAvailability;
  'information.unexclude': InformationAvailability;
  'information.restore': InformationAvailability;
  'information.delete': InformationAvailability;
  'information.retry': QueuedInformation;
  'information.revise': InformationAdmission;
  'information.replace': InformationAdmission;
  'information.refresh': InformationTicket;
  'information.rebuild': QueuedInformation;
  'information.allowance': ChangedInformation;
  'information.events': InformationEvents;
  'information.migration.list': InformationMigrationInventory;
  'information.migration.adopt': AdoptedInformation;
  'information.migration.inspect': InformationMigrationInspection;
  'information.migration.release': ReleasedInformation;
  'information.inventory': readonly InformationRow[];
  'information.acquisitions': readonly InformationAcquisition[];
}
export type InformationFrame = keyof InformationReplies;
export const INFORMATION_FRAMES = [
  'information.upload',
  'information.acquire',
  'information.list',
  'information.facets',
  'information.tags',
  'information.tags.groups',
  'information.status',
  'information.await',
  'information.read',
  'information.outline',
  'information.symbols',
  'information.search',
  'information.rank',
  'information.ask',
  'information.evidence.record',
  'information.evidence.read',
  'information.record.report',
  'information.finalise',
  'information.link',
  'information.unlink',
  'information.share',
  'information.unshare',
  'information.withdraw',
  'information.exclude',
  'information.unexclude',
  'information.restore',
  'information.delete',
  'information.retry',
  'information.revise',
  'information.replace',
  'information.refresh',
  'information.rebuild',
  'information.allowance',
  'information.events',
  'information.migration.list',
  'information.migration.adopt',
  'information.migration.inspect',
  'information.migration.release',
  'information.inventory',
  'information.acquisitions',
] as const;
export const isInformationFrame = (type: string): type is InformationFrame =>
  (INFORMATION_FRAMES as readonly string[]).includes(type);
const object = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !isList(value);
const text = (value: unknown): value is string =>
  typeof value === 'string' && value.trim() !== '';
const integer = (value: unknown): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
const symbol = (value: unknown): boolean =>
  object(value) &&
  integer(value['ordinal']) &&
  value['ordinal'] > 0 &&
  text(value['name']) &&
  text(value['kind']) &&
  text(value['qualified_name']) &&
  (value['parent_ordinal'] === null ||
    (integer(value['parent_ordinal']) &&
      value['parent_ordinal'] > 0 &&
      value['parent_ordinal'] < value['ordinal'])) &&
  typeof value['signature'] === 'string' &&
  integer(value['start_offset']) &&
  integer(value['end_offset']) &&
  value['end_offset'] > value['start_offset'] &&
  integer(value['start_line']) &&
  value['start_line'] > 0 &&
  integer(value['end_line']) &&
  value['end_line'] >= value['start_line'];
const accepted: readonly string[] = [
  'information.upload',
  'information.revise',
  'information.replace',
  'information.record.report',
  'information.acquire',
  'information.refresh',
  'information.ask',
];
/** Validate codes and identity-bearing receipts. A queued admission is never completed work. */
export function informationReply<T extends InformationFrame>(
  type: T,
  outcome: Outcome,
): InformationReplies[T] | undefined {
  const value = legacyInformationReply(type, outcome);
  if (value === undefined) return undefined;
  try {
    return decodeReply(type, value);
  } catch {
    return undefined;
  }
}
// Legacy identity and ordering checks complement complete structural decoding.
// Raw data remains inside this boundary; it is never exposed as a DTO here.
function legacyInformationReply(
  type: InformationFrame,
  outcome: Outcome,
): unknown {
  if (outcome.code !== (accepted.includes(type) ? 'ACCEPTED' : 'OK'))
    return undefined;
  const value = outcome.payload,
    verb = type.slice('information.'.length);
  if (verb === 'rank') return retrievalReply('document.rank', value);
  if (['list', 'inventory', 'acquisitions'].includes(verb)) {
    return isList(value) &&
      value.every(
        (row) =>
          object(row) &&
          text(row['id']) &&
          (verb !== 'acquisitions' || text(row['state'])),
      )
      ? value
      : undefined;
  }
  // Scoped search uses the server's ancestry-bearing passage list, rather
  // than the legacy HTTP document.retrieve wrapper. Full decoding follows.
  if (verb === 'search') return value;
  if (!object(value)) return undefined;
  const rows = (value: unknown): boolean =>
    isList(value) && value.every(object);
  if (verb === 'outline' || verb === 'symbols') {
    if (
      value['locator'] !== 'extracted-text:utf16' ||
      value['source_kind'] !== 'retained_revision' ||
      !text(value['role']) ||
      !integer(value['offset']) ||
      typeof value['has_more'] !== 'boolean' ||
      !isList(value['symbols']) ||
      !value['symbols'].every(symbol)
    )
      return undefined;
    if (verb === 'outline') {
      const valid =
        text(value['revision']) &&
        text(value['language']) &&
        text(value['parser_version']) &&
        (value['source_hash'] === null || text(value['source_hash'])) &&
        (value['reason'] === null || text(value['reason'])) &&
        integer(value['symbol_count']) &&
        value['symbol_count'] >= value['symbols'].length &&
        [
          'pending',
          'unavailable',
          'ready',
          'partial',
          'unsupported',
          'limited',
          'failed',
          'stale',
        ].includes(String(value['status']));
      return valid ? value : undefined;
    }
    return text(value['query']) &&
      value['symbols'].every(
        (row) =>
          object(row) &&
          text(row['revision']) &&
          text(row['source_name']) &&
          text(row['language']) &&
          text(row['source_hash']) &&
          text(row['parser_version']) &&
          ['ready', 'partial', 'limited'].includes(
            String(row['outline_status']),
          ),
      )
      ? value
      : undefined;
  }
  if (verb === 'await') {
    const outcomes = value['outcomes'];
    if (
      !isList(outcomes) ||
      !outcomes.every(
        (row): row is InformationAwaitOutcome =>
          object(row) &&
          [
            'pending',
            'ready',
            'unavailable',
            'failed',
            'blocked',
            'cancelled',
            'skipped',
          ].includes(String(row['state'])) &&
          (text(row['acquisition']) || text(row['revision'])),
      )
    )
      return undefined;
    const identities = outcomes.map((row) =>
      text(row['acquisition'])
        ? 'acquisition:' + row['acquisition']
        : 'revision:' + row['revision'],
    );
    if (
      new Set(identities).size !== outcomes.length ||
      outcomes.some((row) => row['state'] === 'ready' && !text(row['revision']))
    )
      return undefined;
    const settled = outcomes.filter((row) => row['state'] !== 'pending').length,
      ready = outcomes.filter((row) => row['state'] === 'ready').length;
    return value['expected'] === outcomes.length &&
      value['settled'] === settled &&
      value['ready'] === ready &&
      value['pending'] === outcomes.length - settled &&
      value['complete'] === (settled === outcomes.length)
      ? value
      : undefined;
  }
  if (verb === 'facets') {
    const names = [
      'kind',
      'tags',
      'autoTag',
      'tagGroup',
      'author',
      'documentAuthor',
      'when',
      'subtype',
    ];
    const facets = value['facets'],
      more = value['hasMore'],
      graph = value['tagGraph'];
    if (
      !object(graph) ||
      typeof graph['hasMore'] !== 'boolean' ||
      !isList(graph['edges']) ||
      graph['edges'].length > 1000 ||
      !graph['edges'].every(
        (edge): edge is InformationTagEdge =>
          object(edge) &&
          text(edge['group']) &&
          text(edge['tag']) &&
          integer(edge['count']) &&
          edge['count'] > 0 &&
          edge['count'] <= Number(value['total']),
      )
    )
      return undefined;
    if (
      new Set(
        graph['edges'].map((edge) => JSON.stringify([edge.group, edge.tag])),
      ).size !== graph['edges'].length
    )
      return undefined;
    return integer(value['total']) &&
      object(facets) &&
      object(more) &&
      names.every(
        (name) =>
          typeof more[name] === 'boolean' &&
          isList(facets[name]) &&
          facets[name].length <= 100 &&
          facets[name].every(
            (row: unknown) =>
              object(row) &&
              text(row['value']) &&
              integer(row['count']) &&
              row['count'] > 0 &&
              row['count'] <= Number(value['total']),
          ),
      )
      ? value
      : undefined;
  }
  if (verb === 'events')
    return rows(value['events']) && integer(value['cursor'])
      ? value
      : undefined;
  if (verb === 'migration.list')
    return rows(value['documents']) && rows(value['payloads'])
      ? value
      : undefined;
  if (verb === 'migration.inspect')
    return rows(value['log']) && rows(value['entries']) && rows(value['job'])
      ? value
      : undefined;
  let valid: boolean;
  if (['upload', 'revise', 'replace', 'record.report'].includes(verb))
    valid =
      text(value['revision']) &&
      text(value['resource']) &&
      typeof value['created'] === 'boolean';
  else if (['acquire', 'refresh'].includes(verb))
    valid = text(value['id']) && text(value['state']);
  else if (verb === 'ask')
    valid = text(value['job']) && text(value['revision']);
  else if (verb === 'status')
    valid =
      text(value['id']) &&
      (text(value['state']) ||
        (isList(value['steps']) &&
          isList(value['inputs']) &&
          typeof value['can_manage'] === 'boolean'));
  else if (verb === 'read')
    valid =
      text(value['revision']) &&
      integer(value['start']) &&
      integer(value['end']) &&
      integer(value['total']) &&
      value['start'] <= value['end'] &&
      value['end'] <= value['total'] &&
      typeof value['text'] === 'string' &&
      value['text'].length === value['end'] - value['start'];
  else if (verb === 'evidence.record') valid = text(value['evidence']);
  else if (verb === 'evidence.read')
    valid =
      text(value['id']) &&
      text(value['revision_id']) &&
      integer(value['start_offset']) &&
      integer(value['end_offset']) &&
      value['start_offset'] < value['end_offset'] &&
      text(value['quote']) &&
      value['quote'].length === value['end_offset'] - value['start_offset'] &&
      text(value['locator']);
  else if (
    [
      'link',
      'unlink',
      'share',
      'unshare',
      'allowance',
      'tags',
      'tags.groups',
    ].includes(verb)
  )
    valid = value['changed'] === true;
  else if (
    ['withdraw', 'exclude', 'unexclude', 'restore', 'delete'].includes(verb)
  )
    valid =
      value['availability'] ===
      (
        {
          withdraw: 'withdrawn',
          exclude: 'excluded',
          unexclude: 'included',
          restore: 'active',
          delete: 'deleted',
        } as Record<string, string>
      )[verb];
  else
    valid =
      value[
        (
          {
            finalise: 'finalised',
            retry: 'queued',
            rebuild: 'queued',
            'migration.release': 'released',
            'migration.adopt': 'adopted',
          } as Record<string, string>
        )[verb] ?? ''
      ] === true;
  return valid ? value : undefined;
}
