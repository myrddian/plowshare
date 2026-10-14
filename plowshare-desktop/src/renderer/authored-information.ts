import type {
  InformationContentView,
  InformationTypeContract,
  InformationTypeVersion,
} from 'plowshare-client-ts/operations/information-content';
import {
  parseSchemaInstance,
  type SchemaObject,
  type SchemaInstance,
} from 'plowshare-client-ts/operations/schema-instances';
import { escapeHtml as escape } from './markdown.ts';

/** Complete metadata stays in a bounded JSON editor; annotation defaults are never inserted. */
function isSchemaObject(value: SchemaInstance): value is SchemaObject {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
export function metadataObject(source: string): SchemaObject {
  const value = parseSchemaInstance(source);
  if (!isSchemaObject(value))
    throw new Error('Metadata must be a complete JSON object.');
  return value;
}

export function typeOptions(
  types: readonly InformationTypeVersion[],
  selected = '',
): string {
  return types
    .filter((type) => type.state === 'active')
    .map(
      (type) =>
        `<option value="${escape(type.contract.version)}" ${selected === type.contract.version ? 'selected' : ''}>${escape(type.contract.label)} · version ${escape(type.contract.ordinal)}</option>`,
    )
    .join('');
}

export function contractHtml(contract: InformationTypeContract): string {
  return `<p>${escape(contract.label)} · ${escape(contract.slug)} · version ${escape(contract.ordinal)}</p><p>Schema conformance checks metadata structure. It does not verify the document’s claims or completeness.</p><details><summary>Bound contract and presentation hints</summary><pre>${escape(JSON.stringify({ profile: contract.profile, definitionHash: contract.definitionHash, schema: contract.schema, presentation: contract.presentation }, null, 2))}</pre></details>`;
}

export function contentHtml(
  view: InformationContentView,
  types: readonly InformationTypeVersion[],
  manage: boolean,
  writes: boolean,
  unsaved = false,
): string {
  return `<details data-content><summary>Document metadata and processing</summary>${view.contentType ? contractHtml(view.contentType.contract) + `<pre>${escape(JSON.stringify(view.contentType.metadata, null, 2))}</pre>` : '<p>Untyped document.</p>'}
    <p>${view.processingPolicy === 'explicit_only' ? 'Metadata-only revision. Saving metadata did not schedule document processing. Set a budget and select stages below if more processing is needed.' : 'Ordinary document processing.'}</p>
    ${view.revisedBy ? `<p>Revised by ${escape(view.revisedBy)}${view.startingRevision ? ` from ${escape(view.startingRevision)}` : ''}.</p>` : ''}
    <ul>${view.reused.map((reuse) => `<li>${escape(reuse.stage)} reused from ${escape(reuse.revision)} (generation ${escape(reuse.generation)}).</li>`).join('')}</ul><p>Authorised stages: ${escape(view.authorisedStages.join(', ') || 'none')}.</p>
    ${manage && writes ? `<form data-metadata><label>Typing change<select name="mode"><option value="inherit">Keep the current type</option><option value="set">Assign an active type</option><option value="clear">Clear typing</option></select></label><label>Active type<select name="version"><option value="">Choose a type for assignment</option>${typeOptions(types)}</select></label><label>Complete metadata object<textarea name="metadata" rows="7" maxlength="16384">${escape(JSON.stringify(view.contentType?.metadata ?? {}, null, 2))}</textarea></label><button type="button" data-metadata-validate>Validate metadata</button><button>Save metadata-only revision</button><p data-metadata-validation role="status"></p>${unsaved ? '<p>An unsaved edit is retained. Choose the newest revision, inspect its bound contract, then <button type="button" data-reapply-metadata>Reapply the saved edit here</button> and validate it before saving.</p>' : ''}<p>A stale version conflicts. Refresh the source and reapply your edit; no automatic merge is performed.</p></form>` : ''}
    ${manage && view.processingPolicy === 'explicit_only' ? '<form data-process><label>Select processing stages<select name="stages" multiple required><option value="extract">Extraction</option><option value="derive">Derivation</option><option value="embed">Passage embeddings</option><option value="summarise">Summaries</option><option value="summary_embed">Summary embedding</option><option value="autoTag">Automatic tags</option><option value="tagGroups">Tag groups</option></select></label><button>Request selected processing</button><p>Missing prerequisites are included. A budget alone does not start processing.</p></form>' : ''}</details>`;
}
