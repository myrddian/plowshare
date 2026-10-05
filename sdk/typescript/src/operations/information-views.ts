import type { ReportFinding, ReportReview } from './information-payloads.ts';

/** Public Information.Revision projection. Optional metadata is omitted for
 * unreadable revisions and older peers; present fields are always checked. */
export interface InformationRevision {
  readonly id: string;
  readonly resource_id?: string;
  readonly ordinal?: number;
  readonly title?: string;
  readonly media_type?: string;
  readonly document_type?: 'document' | 'code';
  readonly document_subtype?: string;
  readonly source_uri?: string;
  readonly content_hash?: string;
  readonly text_hash?: string;
  readonly byte_size?: number;
  readonly created_at?: string;
  readonly availability?: 'active' | 'excluded' | 'withdrawn' | 'deleted';
  readonly excluded?: boolean;
  readonly generation?: number;
  readonly converter?: string;
  readonly allowance_total?: number;
  readonly allowance_spent?: number;
  readonly source_name?: string;
  readonly kind?: 'source' | 'report';
  readonly author?: string;
  readonly tags?: readonly string[];
  readonly autoTag?: readonly string[];
  readonly auto_tag_generated?: boolean;
  readonly documentAuthor?: string;
  readonly documentAuthorSource?: 'person' | 'organisation' | 'account';
  readonly tagGroups?: Readonly<Record<string, readonly string[]>>;
  readonly tagGroupsSource?: 'manual' | 'automatic';
  readonly report_status?: 'draft' | 'final' | 'superseded';
  readonly can_manage?: boolean;
  readonly steps?: readonly InformationStep[];
  readonly progress?: InformationProgress;
  readonly events?: readonly InformationEvent[];
  readonly inputs?: readonly string[];
  readonly report?: InformationReport;
  readonly citations?: readonly string[];
}
export interface InformationStep {
  readonly stage:
    | 'extract'
    | 'derive'
    | 'embed'
    | 'summarise'
    | 'summary_embed'
    | 'autoTag'
    | 'tagGroups';
  readonly state:
    | 'pending'
    | 'running'
    | 'ready'
    | 'skipped'
    | 'failed'
    | 'blocked'
    | 'cancelled';
  readonly attempt: number;
  readonly generation: number;
  readonly error?: string;
  readonly fingerprint?: string;
  readonly started_at?: string;
  readonly finished_at?: string;
  readonly compatible?: boolean;
}
export interface InformationProgress {
  readonly passage_vectors: {
    readonly total: number;
    readonly completed: number;
  };
  readonly stored_summary_entities: {
    readonly total: number;
    readonly completed: number;
  };
}
export interface InformationEvent {
  readonly sequence: number;
  readonly revision_id?: string;
  readonly generation: number;
  readonly stage?: string;
  readonly action?: string;
  readonly detail?: string;
  readonly recorded_at: string;
  readonly document_type?: 'document' | 'code';
  readonly document_subtype?: string;
}
export interface InformationReport {
  readonly status: 'draft' | 'final' | 'superseded';
  readonly feedback_revision?: string;
  readonly finalised_at?: string;
  readonly produced_by?: string;
  readonly definition_hash?: string;
  readonly details: {
    readonly objectives: readonly string[];
    readonly findings: readonly ReportFinding[];
    readonly reviews: readonly ReportReview[];
    readonly scopeChanges: readonly string[];
  };
}
export interface InformationGate {
  readonly denied: string | null;
  readonly notes: readonly string[];
  readonly records: readonly {
    readonly hook: string;
    readonly file: string | null;
    readonly tier: string;
    readonly stage: string;
    readonly tool: string | null;
    readonly decision: string;
    readonly reason: string | null;
    readonly added: string | null;
    readonly original: string | null;
    readonly tookMs: number;
  }[];
}
/** Acquisition receipts may be observed before their revision exists. */
export interface InformationAcquisition {
  readonly id: string;
  readonly state: 'queued' | 'running' | 'failed' | 'blocked' | 'succeeded';
  readonly url?: string;
  readonly source_name?: string;
  readonly corpus?: 'documents' | 'code';
  readonly revision_id?: string | null;
  readonly attempt?: number;
  readonly error?: string | null;
  readonly allowance_total?: number;
  readonly created_at?: string;
  readonly pre_gate?: InformationGate | null;
  readonly post_gate?: InformationGate | null;
}
export interface MigrationDocument {
  readonly id: string;
  readonly source_name: string;
  readonly title: string | null;
  readonly visibility: string | null;
}
export interface MigrationPayload {
  readonly payload_id: string;
  readonly reason: string | null;
}
export interface MigrationLog {
  readonly id: string;
  readonly owner_handle: string | null;
  readonly agent: string | null;
}
export interface MigrationEntry {
  readonly ordinal: number;
  readonly kind: string;
  readonly content: string | null;
  readonly tool_calls:
    | readonly {
        readonly id: string;
        readonly name: string;
        readonly arguments: string;
      }[]
    | null;
}
export interface MigrationJob {
  readonly id: string;
  readonly agent: string | null;
  readonly ending: string | null;
}
