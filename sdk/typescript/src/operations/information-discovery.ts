import type { InformationFilter } from './information-payloads.ts';
import type { InformationWindow } from './information-replies.ts';
import { isList } from '../binding/values.ts';

const scopeId = /^(?:global_read|[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12})$/;
const uuid = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/;
const record = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !isList(value);

/** Structural decoding runs first; these checks enforce continuation and evidence invariants. */
export function validateDiscoveryReply(type: string, value: unknown): void {
  if (!type.startsWith('information.discovery.')) return;
  const invalid = (): never => {
    throw new Error('Invalid information discovery reply');
  };
  if (!record(value)) return invalid();
  if (type.endsWith('.read')) {
    const { revision, start, end, total, text } = value;
    if (
      typeof revision !== 'string' ||
      !uuid.test(revision) ||
      typeof text !== 'string' ||
      typeof start !== 'number' ||
      typeof end !== 'number' ||
      typeof total !== 'number' ||
      !Number.isSafeInteger(start) ||
      !Number.isSafeInteger(end) ||
      !Number.isSafeInteger(total) ||
      start < 0 ||
      end < start ||
      total < end ||
      text.length !== end - start
    )
      return invalid();
    return;
  }
  const { items, nextCursor, hasMore } = value;
  if (
    !isList(items) ||
    items.length > 50 ||
    typeof hasMore !== 'boolean' ||
    hasMore !== (nextCursor !== null) ||
    (nextCursor !== null &&
      (typeof nextCursor !== 'string' ||
        !/^[A-Za-z0-9_-]{1,2048}\.[A-Za-z0-9_-]{43}$/.test(nextCursor)))
  )
    return invalid();
  if (!type.endsWith('.scopes')) {
    const scopes = value['scopes'];
    if (
      !isList(scopes) ||
      scopes.length < 1 ||
      scopes.length > 32 ||
      new Set(scopes).size !== scopes.length ||
      scopes.some((scope) => typeof scope !== 'string' || !scopeId.test(scope))
    )
      return invalid();
  }
  for (const item of items) {
    if (!record(item)) return invalid();
    if (type.endsWith('.scopes')) {
      if (typeof item['id'] !== 'string' || !scopeId.test(item['id']))
        return invalid();
    } else if (type.endsWith('.facets')) {
      const count = item['count'];
      if (
        typeof count !== 'number' ||
        !Number.isSafeInteger(count) ||
        count < 1
      )
        return invalid();
    } else {
      if (
        typeof item['resource'] !== 'string' ||
        !uuid.test(item['resource']) ||
        typeof item['revision'] !== 'string' ||
        !uuid.test(item['revision']) ||
        typeof item['scope'] !== 'string' ||
        !scopeId.test(item['scope']) ||
        typeof item['createdAt'] !== 'string' ||
        Number.isNaN(Date.parse(item['createdAt']))
      )
        return invalid();
    }
  }
}

/** Scope IDs are opaque selection locators, never grants or execution destinations. */
export interface DiscoveryScope {
  readonly id: string;
  readonly kind: string;
  readonly name: string;
  readonly project: string | null;
}
export interface DiscoveryScopes {
  readonly items: readonly DiscoveryScope[];
  readonly nextCursor: string | null;
  readonly hasMore: boolean;
}
export interface DiscoveryItem {
  readonly resource: string;
  readonly revision: string;
  readonly scope: string;
  readonly project?: string;
  readonly sourceName: string;
  readonly title?: string;
  readonly kind: 'source' | 'report';
  readonly reportStatus?: 'draft' | 'final' | 'superseded';
  readonly createdAt: string;
  readonly tags: readonly string[];
  readonly autoTag: readonly string[];
  readonly documentAuthor?: string;
  readonly subtype: string;
  readonly contentIndexed: boolean;
}
export interface DiscoveryPage {
  readonly scopes: readonly string[];
  readonly items: readonly DiscoveryItem[];
  readonly nextCursor: string | null;
  readonly hasMore: boolean;
}
export type DiscoveryFacet =
  | 'kind'
  | 'tags'
  | 'autoTag'
  | 'tagGroup'
  | 'documentAuthor'
  | 'subtype'
  | 'when';
export interface DiscoveryFacets {
  readonly scopes: readonly string[];
  readonly facet: DiscoveryFacet;
  readonly items: readonly { readonly value: string; readonly count: number }[];
  readonly nextCursor: string | null;
  readonly hasMore: boolean;
}
export interface DiscoverySelection {
  readonly scopes: readonly string[];
  readonly filter?: Omit<InformationFilter, 'search'>;
  readonly limit?: number;
  readonly cursor?: string;
}
export interface DiscoveryPayloads {
  'information.discovery.scopes': {
    readonly limit?: number;
    readonly cursor?: string;
  };
  'information.discovery.list': DiscoverySelection;
  'information.discovery.search': DiscoverySelection & {
    readonly query: string;
  };
  'information.discovery.facets': DiscoverySelection & {
    readonly facet: DiscoveryFacet;
  };
  'information.discovery.read': {
    readonly scopes: readonly string[];
    readonly revision: string;
    readonly offset?: number;
    readonly limit?: number;
  };
}
export interface DiscoveryReplies {
  'information.discovery.scopes': DiscoveryScopes;
  'information.discovery.list': DiscoveryPage;
  'information.discovery.search': DiscoveryPage;
  'information.discovery.facets': DiscoveryFacets;
  'information.discovery.read': InformationWindow;
}
