import type { NavigationPayloads } from './information-navigation.ts';
import type {
  InformationTyping,
  InformationProcessingStage,
} from './information-content.ts';
import type { SchemaDefinition } from './schema-definition.ts';
import type { SchemaObject } from './schema-instances.ts';
import type { InformationScope, InformationCorpus } from './information.ts';

export interface ReportFinding {
  readonly id: string;
  readonly objective: string;
  readonly claim: string;
  readonly support?: readonly string[];
  readonly counterEvidence?: readonly string[];
  readonly rationale: string;
  readonly verdict: 'holds' | 'weakened' | 'refuted' | 'not_checked';
}
export interface ReportReview {
  readonly stage: string;
  readonly outcome: string;
  readonly text: string;
}

export interface InformationFilter {
  readonly kind?: 'source' | 'report';
  readonly tags?: readonly string[];
  readonly autoTag?: readonly string[];
  /** Resource owner handle, not a bibliographic author. */
  readonly author?: string;
  /** Clear document author, issuing organisation, or the owner account fallback. */
  readonly tagGroup?: string;
  readonly documentAuthor?: string;
  readonly when?: string;
  readonly subtype?: string;
  readonly search?: string;
  readonly contentType?: string;
  readonly contentTypeVersion?: string;
  readonly untyped?: boolean;
}

/** Authenticated information frame payloads; publication and migration remain operator controls. */
export interface InformationPayloads extends NavigationPayloads {
  'information.types.capabilities': {
    readonly scope: InformationScope;
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
  };
  'information.types.list': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly view: 'content-types-v1';
    readonly drafts?: boolean;
    readonly limit?: number;
    readonly offset?: number;
  };
  'information.types.get': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly view: 'content-types-v1';
    readonly version: string;
    readonly attachedRevision?: string;
    readonly publishedOnly?: boolean;
  };
  'information.types.validate': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly view: 'content-types-v1';
    readonly version: string;
    readonly attachedRevision?: string;
    readonly metadata: SchemaObject;
    readonly publishedOnly?: boolean;
  };
  'information.types.version.create': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly view: 'content-types-v1';
    readonly requestId: string;
    readonly type?: string;
    readonly expectedControlRevision?: number;
    readonly slug?: string;
    readonly label?: string;
    readonly description?: string;
    readonly schema: SchemaDefinition;
    readonly presentation?: SchemaObject;
  };
  'information.types.activate': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly view: 'content-types-v1';
    readonly requestId: string;
    readonly version: string;
    readonly expectedControlRevision: number;
  };
  'information.types.deprecate': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly view: 'content-types-v1';
    readonly requestId: string;
    readonly version: string;
    readonly expectedControlRevision: number;
  };
  'information.content.get': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly view: 'content-types-v1';
    readonly revision: string;
  };
  'information.content.revise': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly view: 'content-types-v1';
    readonly requestId: string;
    readonly expectedRevision: string;
    readonly typing: InformationTyping;
  };
  'information.upload': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly requestId: string;
    readonly name: string;
    readonly text: string;
    readonly expectedRevision?: string;
    readonly typing?: InformationTyping;
  };
  'information.acquire': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly requestId: string;
    readonly url: string;
    readonly name?: string;
    readonly expectedRevision?: string;
    readonly typing?: InformationTyping;
  };
  'information.facets': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly kind?: 'source' | 'report';
    readonly filter?: InformationFilter;
  };
  'information.tags.groups': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
    readonly groups: Readonly<Record<string, readonly string[]>> | null;
  };
  'information.tags': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
    readonly tags: readonly string[];
  };
  'information.list': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly kind?: 'source' | 'report';
    readonly filter?: InformationFilter;
    readonly limit?: number;
    readonly offset?: number;
  };
  'information.await': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly sources: readonly (
      | { readonly revision: string; readonly acquisition?: never }
      | { readonly acquisition: string; readonly revision?: never }
    )[];
    readonly waitMs?: number;
  };
  'information.status': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision?: string;
    readonly acquisition?: string;
  };
  'information.read': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly offset?: number;
    readonly limit?: number;
  };
  'information.outline': {
    readonly corpus: 'code';
    readonly scope: InformationScope;
    readonly revision: string;
    readonly limit?: number;
    readonly offset?: number;
  };
  'information.symbols': {
    readonly corpus: 'code';
    readonly scope: InformationScope;
    readonly query: string;
    readonly revision?: string;
    readonly limit?: number;
    readonly offset?: number;
  };
  'information.search': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly query: string;
    readonly filter?: InformationFilter;
    readonly revision?: string;
    readonly limit?: number;
  };
  'information.rank': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly query: string;
    readonly filter?: InformationFilter;
    readonly limit?: number;
  };
  'information.ask': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly question: string;
    readonly maxModelCalls?: number;
  };
  'information.evidence.record': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
    readonly start: number;
    readonly end: number;
    readonly quote: string;
    readonly locator: string;
  };
  'information.evidence.read': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly evidence: string;
  };
  'information.record.report': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly requestId: string;
    readonly name: string;
    readonly text: string;
    readonly inputs?: readonly string[];
    readonly evidence?: readonly string[];
    readonly feedback?: string;
    readonly objectives?: readonly string[];
    readonly findings?: readonly ReportFinding[];
    readonly reviews?: readonly ReportReview[];
    readonly scopeChanges?: readonly string[];
    readonly expectedRevision?: string;
    readonly typing?: InformationTyping;
  };
  'information.finalise': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
  };
  'information.link': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
    readonly collectionProject: string;
  };
  'information.unlink': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
    readonly collectionProject: string;
  };
  'information.share': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
  };
  'information.unshare': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
  };
  'information.withdraw': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
  };
  'information.exclude': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
  };
  'information.unexclude': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
  };
  'information.restore': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
  };
  'information.delete': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
  };
  'information.retry': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly acquisition?: string;
    readonly revision?: string;
    readonly requestId?: string;
    readonly stages?: readonly InformationProcessingStage[];
  };
  'information.revise': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
    readonly text: string;
    readonly name?: string;
    readonly expectedRevision?: string;
    readonly typing?: InformationTyping;
  };
  'information.replace': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
    readonly text: string;
    readonly name?: string;
    readonly expectedRevision?: string;
    readonly typing?: InformationTyping;
  };
  'information.refresh': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
    readonly expectedRevision?: string;
    readonly typing?: InformationTyping;
  };
  'information.rebuild': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
    readonly stage: string;
    readonly stages?: readonly InformationProcessingStage[];
  };
  'information.allowance': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
    readonly maxModelCalls: number;
  };
  'information.events': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly after?: number;
    readonly limit?: number;
  };
  'information.migration.list': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope?: InformationScope;
    readonly limit?: number;
    readonly offset?: number;
  };
  'information.migration.adopt': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope?: InformationScope;
    readonly revision: string;
    readonly owner: string;
    readonly visibility: string;
    readonly reason: string;
    readonly requestId: string;
    readonly collectionProject?: string;
  };
  'information.migration.inspect': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope?: InformationScope;
    readonly payload: string;
    readonly reason: string;
    readonly limit?: number;
    readonly offset?: number;
  };
  'information.migration.release': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly payload: string;
    readonly owner: string;
    readonly reason: string;
    readonly requestId: string;
    readonly inputs?: readonly string[];
  };
  'information.inventory': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly limit?: number;
    readonly offset?: number;
  };
  'information.acquisitions': {
    readonly view?: 'content-types-v1';
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly limit?: number;
    readonly offset?: number;
  };
}
