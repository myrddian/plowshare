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
}

/** Authenticated information frame payloads; publication and migration remain operator controls. */
export interface InformationPayloads {
  'information.upload': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly requestId: string;
    readonly name: string;
    readonly text: string;
  };
  'information.acquire': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly requestId: string;
    readonly url: string;
    readonly name?: string;
  };
  'information.facets': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly kind?: 'source' | 'report';
    readonly filter?: InformationFilter;
  };
  'information.tags.groups': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
    readonly groups: Readonly<Record<string, readonly string[]>> | null;
  };
  'information.tags': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
    readonly tags: readonly string[];
  };
  'information.list': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly kind?: 'source' | 'report';
    readonly filter?: InformationFilter;
    readonly limit?: number;
    readonly offset?: number;
  };
  'information.await': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly sources: readonly (
      | { readonly revision: string; readonly acquisition?: never }
      | { readonly acquisition: string; readonly revision?: never }
    )[];
    readonly waitMs?: number;
  };
  'information.status': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision?: string;
    readonly acquisition?: string;
  };
  'information.read': {
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
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly query: string;
    readonly filter?: InformationFilter;
    readonly revision?: string;
    readonly limit?: number;
  };
  'information.rank': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly query: string;
    readonly filter?: InformationFilter;
    readonly limit?: number;
  };
  'information.ask': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly question: string;
    readonly maxModelCalls?: number;
  };
  'information.evidence.record': {
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
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly evidence: string;
  };
  'information.record.report': {
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
  };
  'information.finalise': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
  };
  'information.link': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
    readonly collectionProject: string;
  };
  'information.unlink': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
    readonly collectionProject: string;
  };
  'information.share': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
  };
  'information.unshare': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
  };
  'information.withdraw': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
  };
  'information.exclude': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
  };
  'information.unexclude': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
  };
  'information.restore': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
  };
  'information.delete': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
  };
  'information.retry': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly acquisition?: string;
    readonly revision?: string;
    readonly requestId?: string;
  };
  'information.revise': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
    readonly text: string;
    readonly name?: string;
  };
  'information.replace': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
    readonly text: string;
    readonly name?: string;
  };
  'information.refresh': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
  };
  'information.rebuild': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
    readonly stage: string;
  };
  'information.allowance': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly revision: string;
    readonly requestId: string;
    readonly maxModelCalls: number;
  };
  'information.events': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly after?: number;
    readonly limit?: number;
  };
  'information.migration.list': {
    readonly corpus?: InformationCorpus;
    readonly scope?: InformationScope;
    readonly limit?: number;
    readonly offset?: number;
  };
  'information.migration.adopt': {
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
    readonly corpus?: InformationCorpus;
    readonly scope?: InformationScope;
    readonly payload: string;
    readonly reason: string;
    readonly limit?: number;
    readonly offset?: number;
  };
  'information.migration.release': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly payload: string;
    readonly owner: string;
    readonly reason: string;
    readonly requestId: string;
    readonly inputs?: readonly string[];
  };
  'information.inventory': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly limit?: number;
    readonly offset?: number;
  };
  'information.acquisitions': {
    readonly corpus?: InformationCorpus;
    readonly scope: InformationScope;
    readonly limit?: number;
    readonly offset?: number;
  };
}
