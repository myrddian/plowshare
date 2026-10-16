import type { InformationScope } from './information.ts';
import type { InformationFilter } from './information-payloads.ts';
import type { InformationFacetCounts } from './information-replies.ts';

export interface ReferencePolicy {
  readonly ownerOnly: boolean;
  readonly referenceHidden: boolean;
  readonly propagate: boolean;
}
export interface ReferencePolicySettings {
  readonly defaults: ReferencePolicy;
  readonly mandatory: ReferencePolicy;
  readonly allowOverrides: boolean;
  /** Explicitly approved label; never automatically copied from a private title. */
  readonly referenceLabel: string;
}
export type ReferencePolicyTarget =
  | {
      readonly kind: 'platform';
      readonly project?: never;
      readonly resource?: never;
    }
  | {
      readonly kind: 'project';
      readonly project: string;
      readonly resource?: never;
    }
  | {
      readonly kind: 'document';
      readonly resource: string;
      readonly project?: never;
    };
export interface ReferencePolicyView {
  readonly target: ReferencePolicyTarget;
  readonly version: number;
  readonly settings: ReferencePolicySettings;
  readonly effective: ReferencePolicy;
  readonly canManage: boolean;
  readonly retainedObligations: number;
}
export interface DocumentTarget {
  readonly resource: string;
  readonly revision?: string;
}
export interface CurrentDocument {
  readonly resource: string;
  readonly revision: string;
  readonly title: string;
  readonly sourceName: string;
  readonly owner: string;
  readonly tags: readonly string[];
  readonly scope: InformationScope;
}
export type DocumentSelection =
  | { readonly kind: 'readable'; readonly document: CurrentDocument }
  | {
      readonly kind: 'locked';
      readonly resource: string;
      readonly label: string;
    }
  | { readonly kind: 'unavailable' };
export interface DocumentRelationship {
  readonly id: string;
  readonly source: string;
  readonly target: string;
  readonly pinnedRevision?: string;
  readonly relation: string;
  readonly version: number;
  readonly removed: boolean;
}
export interface DocumentConnection {
  readonly relationship: DocumentRelationship;
  readonly selection: DocumentSelection;
}
export type NavigationStep =
  | { readonly kind: 'scope'; readonly scope: InformationScope }
  | { readonly kind: 'document'; readonly target: DocumentTarget }
  | {
      readonly kind: 'traverse';
      readonly relation: string;
      readonly direction: 'outgoing' | 'backlinks';
    }
  | { readonly kind: 'filter'; readonly filter: InformationFilter };
export interface NavigationPath {
  readonly steps: readonly NavigationStep[];
}
export interface SavedNavigationView {
  readonly id: string;
  readonly label: string;
  readonly path: NavigationPath;
  readonly version: number;
  readonly deleted: boolean;
}
export interface NavigationChanged {
  readonly id: string;
  readonly version: number;
  readonly retainedObligations: boolean;
}
export interface NavigationCapabilities {
  readonly capability: 'document-navigation-v1';
  readonly writesEnabled: boolean;
  readonly maxSteps: number;
  readonly maxClosure: number;
}
type Common = {
  readonly scope: InformationScope;
  readonly corpus?: 'documents';
  readonly view?: 'content-types-v1';
};
type Page = { readonly limit?: number; readonly offset?: number };
type Mutation = { readonly requestId: string };
export interface NavigationPayloads {
  'information.navigation.capabilities': Common;
  'information.documents.list': Common &
    Page & {
      readonly filter?: InformationFilter;
      readonly relatedTo?: string;
      readonly relation?: string;
      readonly direction?: 'outgoing' | 'backlinks';
    };
  'information.documents.facets': Common & {
    readonly filter?: InformationFilter;
    readonly relatedTo?: string;
    readonly relation?: string;
    readonly direction?: 'outgoing' | 'backlinks';
  };
  'information.documents.get': Common & { readonly target: DocumentTarget };
  'information.relationships.list': Common &
    Page & {
      readonly resource: string;
      readonly otherResource?: string;
      readonly direction: 'outgoing' | 'backlinks';
      readonly relation?: string;
    };
  'information.relationships.create': Common &
    Mutation & {
      readonly source: string;
      readonly target: DocumentTarget;
      readonly relation: string;
      readonly expectedSourceRevision: string;
    };
  'information.relationships.remove': Common &
    Mutation & {
      readonly relationship: string;
      readonly expectedVersion: number;
    };
  'information.policy.get': Common & { readonly target: ReferencePolicyTarget };
  'information.policy.set': Common &
    Mutation & {
      readonly target: ReferencePolicyTarget;
      readonly expectedVersion: number;
      readonly settings: ReferencePolicySettings;
      readonly reason: string;
    };
  'information.views.list': Common;
  'information.views.get': Common & { readonly id: string };
  'information.views.save': Common &
    Mutation & {
      readonly id: string;
      readonly expectedVersion: number;
      readonly label: string;
      readonly path: NavigationPath;
    };
  'information.views.delete': Common &
    Mutation & { readonly id: string; readonly expectedVersion: number };
}
export interface NavigationReplies {
  'information.navigation.capabilities': NavigationCapabilities;
  'information.documents.list': {
    readonly documents: readonly CurrentDocument[];
    readonly limit: number;
    readonly offset: number;
  };
  'information.documents.facets': InformationFacetCounts;
  'information.documents.get': DocumentSelection;
  'information.relationships.list': {
    readonly connections: readonly DocumentConnection[];
    readonly limit: number;
    readonly offset: number;
  };
  'information.relationships.create': DocumentRelationship;
  'information.relationships.remove': NavigationChanged;
  'information.policy.get': ReferencePolicyView;
  'information.policy.set': ReferencePolicyView;
  'information.views.list': { readonly views: readonly SavedNavigationView[] };
  'information.views.get': SavedNavigationView;
  'information.views.save': SavedNavigationView;
  'information.views.delete': NavigationChanged;
}

export const NAVIGATION_OPERATIONS = [
  'navigation.capabilities',
  'documents.list',
  'documents.facets',
  'documents.get',
  'relationships.list',
  'relationships.create',
  'relationships.remove',
  'policy.get',
  'policy.set',
  'views.list',
  'views.get',
  'views.save',
  'views.delete',
] as const;
export function isNavigationOperation(operation: string): boolean {
  return (NAVIGATION_OPERATIONS as readonly string[]).includes(
    operation.replace(/^information\./, ''),
  );
}
