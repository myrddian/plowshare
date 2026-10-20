import type { SchemaDefinition } from './schema-definition.ts';
import type { SchemaObject } from './schema-instances.ts';
import { fieldsOf } from './response.ts';
import { isList } from '../binding/values.ts';
import { utf8Length } from '../binding/files.ts';

export const INFORMATION_CONTENT_VIEW = 'content-types-v1' as const;
export const DOCUMENT_METADATA_PROFILE =
  'plowshare-document-metadata/1' as const;
export interface InformationContentCapabilities {
  readonly capability: 'information.contentTypes.v1';
  readonly view: 'content-types-v1';
  readonly profile: 'plowshare-document-metadata/1';
  readonly typedWrites: boolean;
}
export type InformationTyping =
  | {
      readonly mode: 'set';
      readonly version: string;
      readonly metadata: SchemaObject;
    }
  | {
      readonly mode: 'inherit';
      readonly metadata?: SchemaObject;
      readonly version?: never;
    }
  | {
      readonly mode: 'clear';
      readonly version?: never;
      readonly metadata?: never;
    };

/** Immutable schema/version identity. Reading a contract confers no authority to assign it. */
export interface InformationTypeIdentity {
  readonly type: string;
  readonly version: string;
  readonly ordinal: number;
  readonly slug: string;
  readonly label: string;
  readonly definitionHash: string;
}
export interface InformationTypeContract {
  readonly type: string;
  readonly version: string;
  readonly ordinal: number;
  readonly slug: string;
  readonly label: string;
  readonly description: string;
  readonly profile: 'plowshare-document-metadata/1';
  readonly definitionHash: string;
  readonly schema: SchemaDefinition;
  readonly presentation: SchemaObject;
}
export interface InformationTypeVersion {
  readonly contract: InformationTypeContract;
  readonly state: 'draft' | 'active' | 'deprecated';
  readonly controlRevision: number;
  readonly createdAt: string;
}
export interface InformationTypeVersions {
  readonly results: readonly InformationTypeVersion[];
  readonly hasMore: boolean;
}
export interface InformationContentBinding {
  readonly contract: InformationTypeContract;
  readonly metadataHash: string;
  readonly metadata: SchemaObject;
}
/** Revision instance fields without repeating its schema or source body. */
export interface InformationContentMetadata {
  readonly type: InformationTypeIdentity;
  readonly metadataHash: string;
  readonly metadata: SchemaObject;
}
export type InformationProcessingStage =
  | 'extract'
  | 'derive'
  | 'embed'
  | 'summarise'
  | 'summary_embed'
  | 'autoTag'
  | 'tagGroups';
export interface InformationProjectionReuse {
  readonly stage: InformationProcessingStage;
  readonly revision: string;
  readonly generation: number;
  readonly fingerprint: string;
}
export interface InformationContentView {
  readonly revision: string;
  readonly contentType?: InformationContentBinding;
  readonly startingRevision?: string;
  readonly revisedBy?: string;
  readonly processingPolicy: 'automatic' | 'explicit_only';
  readonly reused: readonly InformationProjectionReuse[];
  readonly authorisedStages: readonly InformationProcessingStage[];
}
export interface MetadataValidationIssue {
  readonly instancePointer: string;
  readonly schemaPointer: string;
  readonly keyword: string;
  readonly message: string;
}
export interface MetadataValidationResult {
  readonly status: 'VALID' | 'INVALID' | 'BUDGET_EXHAUSTED';
  readonly issues: readonly MetadataValidationIssue[];
  readonly truncated: boolean;
}

/** The refusal boundary projects only bounded, named diagnostics, never arbitrary error payloads. */
export function decodeMetadataValidation(
  value: unknown,
): MetadataValidationResult {
  const fields = fieldsOf(value),
    status = fields['status'],
    truncated = fields['truncated'],
    rawIssues = fields['issues'];
  if (
    !['VALID', 'INVALID', 'BUDGET_EXHAUSTED'].includes(String(status)) ||
    typeof truncated !== 'boolean' ||
    !isList(rawIssues) ||
    rawIssues.length > 32
  )
    throw new Error('Invalid metadata validation result');
  if (
    status !== 'VALID' &&
    status !== 'INVALID' &&
    status !== 'BUDGET_EXHAUSTED'
  )
    throw new Error('Invalid metadata validation status');
  const issues = rawIssues.map((value) => {
    const issue = fieldsOf(value),
      instancePointer = issue['instancePointer'],
      schemaPointer = issue['schemaPointer'],
      keyword = issue['keyword'],
      message = issue['message'];
    if (
      typeof instancePointer !== 'string' ||
      typeof schemaPointer !== 'string' ||
      typeof keyword !== 'string' ||
      typeof message !== 'string' ||
      instancePointer.length > 3500 ||
      schemaPointer.length > 3500 ||
      keyword.length > 128 ||
      message.length > 512 ||
      (instancePointer !== '' && !instancePointer.startsWith('/')) ||
      (schemaPointer !== '' && !schemaPointer.startsWith('/'))
    )
      throw new Error('Invalid bounded metadata issue');
    return { instancePointer, schemaPointer, keyword, message };
  });
  const result: MetadataValidationResult = { status, issues, truncated };
  if (
    (status === 'VALID' && (issues.length > 0 || truncated)) ||
    (status !== 'VALID' && issues.length === 0) ||
    utf8Length(JSON.stringify(result)) > 8192
  )
    throw new Error('Invalid bounded metadata diagnostics');
  return result;
}
