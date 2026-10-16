import {
  RECOVERY_FORMAT,
  scopeKey,
  recoveryContext,
  decodeImportRecovery,
} from '../import-recovery-contract.ts';
import type { PortableCodec } from 'plowshare-client-ts/operations/interchange-files';
import { sha256 } from 'plowshare-client-ts/binding/sha256';
import type { DesktopApi } from '../shared.ts';
import type { InformationScope } from 'plowshare-client-ts/operations/information';
import { decodeInformationCall } from 'plowshare-client-ts/operations/information';
import { decodeReply } from 'plowshare-client-ts/operations/schema';
import type { PortableImportPlan } from 'plowshare-client-ts/operations/information-interchange';
import { readablePortableFiles } from 'plowshare-client-ts/operations/interchange-files';
import { errorMessage, isObject } from 'plowshare-client-ts/binding/values';

const codec: PortableCodec = {
  encode: (text) => new TextEncoder().encode(text),
  base64: (bytes) => {
    let text = '';
    for (let i = 0; i < bytes.length; i += 8192)
      text += String.fromCharCode(...bytes.subarray(i, i + 8192));
    return btoa(text);
  },
  unbase64: (text) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0)),
  hash: (bytes) => Promise.resolve(sha256(bytes)),
};
interface ImportDraft {
  readonly plan: PortableImportPlan;
  readonly requestId: string;
  readonly context: string;
  recoverySaved: boolean;
}
/** Portable exchange owns only a reviewed draft; the server owns admission and receipt recovery. */
export function installInformationInterchange(
  api: DesktopApi,
  host: HTMLElement,
  scope: () => InformationScope,
  identity: () => string,
  revision: () => string,
  changed: () => Promise<void>,
) {
  const panel = document.createElement('details');
  panel.hidden = true;
  panel.className = 'information-interchange';
  panel.innerHTML = `<summary>Portable exchange</summary><p>Export a selected version for external authoring. Bundles include original bytes, metadata and permitted dependencies. Markdown exports label extracted text.</p><button type="button" data-portable-bundle>Download selected document bundle</button><button type="button" data-portable-readable>Download Markdown and sidecar</button><p>Import a prepared plan or recovery file with explicit destinations, schema mappings and relationship choices. A recovery file must be saved before submission; keep it until the result is known. Imported documents remain unprocessed until you request processing.</p><form data-portable-review><label>Import plan or recovery file<input name="plan" type="file" accept="application/json,.json" required></label><button>Review import plan</button></form><section data-portable-preview></section><button type="button" data-portable-import hidden>Import reviewed plan</button><p data-portable-status role="status"></p>`;
  host.append(panel);
  const statusNode = panel.querySelector<HTMLElement>('[data-portable-status]'),
    previewNode = panel.querySelector<HTMLElement>('[data-portable-preview]'),
    confirmNode = panel.querySelector<HTMLButtonElement>(
      '[data-portable-import]',
    );
  if (!statusNode || !previewNode || !confirmNode)
    throw new Error('Portable exchange controls unavailable');
  const status = statusNode,
    preview = previewNode,
    confirm = confirmNode;
  let prepared: ImportDraft | undefined;
  let busy = false,
    generation = 0;
  const context = () => recoveryContext(identity(), scope());

  function showReview(draft: ImportDraft) {
    preview.replaceChildren();
    const summary = document.createElement('p');
    summary.textContent = `Admit ${draft.plan.bundle.documents.length - draft.plan.reuse.length} new versions; reuse ${draft.plan.reuse.length} existing versions. ${draft.plan.restoreRelationships ? 'Restore mapped relationships.' : 'Omit relationship restoration.'} Foreign authorship remains an unverified origin claim.`;
    preview.append(summary);
    const list = document.createElement('ul');
    for (const destination of draft.plan.destinations) {
      const row = document.createElement('li');
      row.textContent = `${destination.name}: ${destination.mode === 'create' ? 'create only; refuse an existing name' : 'revise only against the selected starting version'}`;
      list.append(row);
    }
    preview.append(list);
    confirm.hidden = false;
    status.textContent =
      'Review the destinations, then submit or recover this import. Recovery always uses the original request ID.';
  }
  function download(name: string, bytes: Uint8Array) {
    const url = URL.createObjectURL(new Blob([new Uint8Array(bytes).buffer])),
      anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = name.split('/').at(-1) ?? 'document';
    anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  function run(action: () => Promise<void>) {
    if (busy) return;
    busy = true;
    status.textContent = 'Working…';
    const key = context();
    action()
      .finally(() => {
        busy = false;
      })
      .catch((reason: unknown) => {
        if (key === context()) status.textContent = errorMessage(reason);
      });
  }
  panel.querySelector('[data-portable-bundle]')?.addEventListener('click', () =>
    run(async () => {
      if (!revision())
        throw new Error(
          'Choose a source version in the list before exporting.',
        );
      const key = context(),
        checked = decodeInformationCall(
          'interchange.export',
          { revisions: [revision()] },
          scope(),
        );
      const answer = await api.request({
        action: 'information',
        ...checked,
        scope: scope(),
      });
      const bundle = decodeReply(
        'information.interchange.export',
        answer.information,
      );
      if (context() !== key) return;
      download(
        `knowledge-${bundle.id}.json`,
        new TextEncoder().encode(JSON.stringify(bundle, null, 2) + '\n'),
      );
      status.textContent = `Exported ${bundle.documents.length} versions. Optional relationships outside this bundle are omitted.`;
    }),
  );
  panel
    .querySelector('[data-portable-readable]')
    ?.addEventListener('click', () =>
      run(async () => {
        const key = context(),
          checked = decodeInformationCall(
            'interchange.readable',
            { revision: revision() },
            scope(),
          );
        const answer = await api.request({
          action: 'information',
          ...checked,
          scope: scope(),
        });
        const readable = decodeReply(
            'information.interchange.readable',
            answer.information,
          ),
          files = await readablePortableFiles(readable, codec);
        if (context() !== key) return;
        for (const file of files) download(file.path, file.bytes);
        status.textContent =
          readable.representation === 'extraction'
            ? 'Downloaded a labelled extraction and sidecar. The bundle export contains original bytes.'
            : 'Downloaded original Markdown and its sidecar.';
      }),
    );
  panel
    .querySelector('[data-portable-review]')
    ?.addEventListener('submit', (event) => {
      event.preventDefault();
      run(async () => {
        prepared = undefined;
        confirm.hidden = true;
        preview.replaceChildren();
        const input =
            panel.querySelector<HTMLInputElement>('input[name="plan"]'),
          file = input?.files?.[0];
        if (!file || file.size > 8388608)
          throw new Error('Choose an import plan JSON file up to 8 MiB.');
        const key = context(),
          stamp = generation,
          raw: unknown = JSON.parse(await file.text());
        let requestId: string = crypto.randomUUID(),
          plan: unknown = raw;
        if (isObject(raw) && raw['format'] === RECOVERY_FORMAT) {
          const recovered = decodeImportRecovery(raw);
          if (
            recovered.context !== key ||
            scopeKey(recovered.scope) !== scopeKey(scope())
          )
            throw new Error(
              'Select the original connection, account and scope before reviewing this recovery file.',
            );
          requestId = recovered.requestId;
          plan = recovered.plan;
        }
        const checked = decodeInformationCall(
          'interchange.import',
          { requestId, plan },
          scope(),
        );
        if (checked.operation !== 'interchange.import')
          throw new Error('Invalid import plan');
        if (context() !== key || generation !== stamp) return;
        prepared = {
          plan: checked.payload.plan,
          requestId: checked.payload.requestId,
          context: key,
          recoverySaved: false,
        };
        showReview(prepared);
      });
    });
  confirm.addEventListener('click', () =>
    run(async () => {
      const draft = prepared;
      if (!draft || draft.context !== context())
        throw new Error(
          'The connection or scope changed. Review this import plan again.',
        );
      const checked = decodeInformationCall(
        'interchange.import',
        { plan: draft.plan, requestId: draft.requestId },
        scope(),
      );
      // Await actual private-file persistence before any uncertain network effect. Canceling
      // the native picker or a write failure leaves the original draft/key available to retry.
      if (!draft.recoverySaved) {
        await api.request({
          action: 'information-import-recovery-save',
          recovery: {
            format: RECOVERY_FORMAT,
            requestId: draft.requestId,
            context: draft.context,
            scope: scope(),
            plan: draft.plan,
          },
        });
        if (draft.context !== context())
          throw new Error(
            'The connection or scope changed. Review this import again.',
          );
        draft.recoverySaved = true;
      }
      const answer = await api.request({
        action: 'information',
        ...checked,
        scope: scope(),
      });
      const result = decodeReply(
        'information.interchange.import',
        answer.information,
      );
      if (draft.context !== context()) return;
      if (result.bundle !== draft.plan.bundle.id)
        throw new Error(
          'Import receipt does not match the reviewed bundle. Retain this plan for recovery.',
        );
      prepared = undefined;
      confirm.hidden = true;
      status.textContent =
        'Import retained. Processing remains an explicit choice.';
      await changed();
    }),
  );
  return {
    async probe() {
      const stamp = ++generation,
        key = context();
      panel.hidden = true;
      const checked = decodeInformationCall(
        'interchange.capabilities',
        {},
        scope(),
      );
      try {
        const answer = await api.request({
          action: 'information',
          ...checked,
          scope: scope(),
        });
        decodeReply('information.interchange.capabilities', answer.information);
        if (stamp === generation && key === context()) {
          panel.hidden = false;
          if (prepared?.context === key) showReview(prepared);
        }
      } catch {
        /* Older servers do not offer portable exchange. Existing Library operations remain available. */
      }
    },
    reset() {
      generation++;
      // Reset the view, not an uncertain operation's recovery identity. Probe requires the same
      // connection/account/scope and another explicit confirmation before submitting anything.
      confirm.hidden = true;
      panel.hidden = true;
      panel.open = false;
      preview.replaceChildren();
      status.textContent = '';
    },
  };
}
