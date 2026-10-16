import type { InformationScope } from './information.ts';
import type { InformationContentBinding } from './information-content.ts';

/** Foreign provenance is descriptive data; imports never restore its authority or processing. */
export interface PortableEvidence {
  readonly id: string;
  readonly revision: string;
  readonly start: number;
  readonly end: number;
  readonly quote: string;
  readonly locator?: string;
}
export interface PortableOriginReference {
  readonly bundle: string;
  readonly manifestHash: string;
  readonly resource: string;
  readonly revision: string;
  readonly owner: string;
  readonly sourceHash: string;
}
export interface PortableProvenance {
  readonly owner: string;
  readonly kind: 'source' | 'report';
  readonly createdAt: string;
  readonly sourceHash: string;
  readonly importedFrom?: PortableOriginReference;
  readonly producer?: string;
  readonly producerDefinitionHash?: string;
  readonly dependencies: readonly string[];
  readonly evidence: readonly PortableEvidence[];
}
export interface PortableDescriptor {
  readonly resource: string;
  readonly revision: string;
  readonly ordinal: number;
  readonly sourceName: string;
  readonly title: string;
  readonly path: string;
  readonly mediaType: string;
  readonly contentHash: string;
  readonly tags: readonly string[];
  readonly contentType?: InformationContentBinding;
  readonly provenance: PortableProvenance;
}
export interface PortableDocument {
  readonly descriptor: PortableDescriptor;
  readonly bytesBase64: string;
}
export interface PortableLink {
  readonly source: string;
  readonly target: string;
  readonly pinnedRevision?: string;
  readonly relation: string;
}
export interface PortableBundle {
  readonly format: 'plowshare-information-bundle/1';
  readonly id: string;
  readonly documents: readonly PortableDocument[];
  readonly relationships: readonly PortableLink[];
  readonly relationshipCoverage: 'included-endpoints-only';
}
export type PortableDestination = {
  readonly resource: string;
  readonly name: string;
} & (
  | { readonly mode: 'create'; readonly expectedRevision?: never }
  | { readonly mode: 'revise'; readonly expectedRevision: string }
);
export interface PortableTypeMapping {
  readonly foreignVersion: string;
  readonly localVersion: string;
}
export interface PortableReuse {
  readonly foreignRevision: string;
  readonly localRevision: string;
}
export interface PortableImportPlan {
  readonly bundle: PortableBundle;
  readonly destinations: readonly PortableDestination[];
  readonly types: readonly PortableTypeMapping[];
  readonly reuse: readonly PortableReuse[];
  readonly restoreRelationships: boolean;
}
export interface PortableMapping {
  readonly foreignResource: string;
  readonly foreignRevision: string;
  readonly resource: string;
  readonly revision: string;
}
export interface PortableImported {
  readonly bundle: string;
  readonly mappings: readonly PortableMapping[];
}
export interface PortableOrigin {
  readonly bundle: string;
  readonly manifestHash: string;
  readonly foreignDocument: PortableDescriptor;
}
export interface PortableReadable {
  readonly format: 'plowshare-information-bundle/1';
  readonly path: string;
  readonly representation: 'original_markdown' | 'extraction';
  readonly markdown: string;
  readonly textHash: string;
  readonly sidecar: PortableDescriptor;
}
export interface PortableCapabilities {
  readonly format: 'plowshare-information-bundle/1';
  readonly maxDocuments: 16;
  readonly maxBytes: 2097152;
  readonly maxJsonBytes: 8388608;
}
type Selection = {
  readonly scope: InformationScope;
  readonly corpus?: 'documents';
  readonly view?: 'content-types-v1';
};
export interface InterchangePayloads {
  'information.interchange.capabilities': Selection;
  'information.interchange.export': Selection & {
    readonly revisions: readonly string[];
  };
  'information.interchange.readable': Selection & { readonly revision: string };
  'information.interchange.origin': Selection & { readonly revision: string };
  'information.interchange.import': Selection & {
    readonly requestId: string;
    readonly plan: PortableImportPlan;
  };
}
export interface InterchangeReplies {
  'information.interchange.capabilities': PortableCapabilities;
  'information.interchange.export': PortableBundle;
  'information.interchange.readable': PortableReadable;
  'information.interchange.origin': PortableOrigin;
  'information.interchange.import': PortableImported;
}
export const INTERCHANGE_OPERATIONS = [
  'interchange.capabilities',
  'interchange.export',
  'interchange.readable',
  'interchange.import',
  'interchange.origin',
] as const;
