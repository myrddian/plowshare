import { installInformationInterchange } from './information-interchange.ts';
import { installDocumentNavigation } from './document-navigation.ts';
import {
  contentHtml,
  metadataObject,
  typeOptions,
} from './authored-information.ts';
import { parseSchemaDefinition } from 'plowshare-client-ts/operations/schema-instances';
import type {
  InformationContentCapabilities,
  InformationContentView,
  InformationTypeVersion,
  InformationTyping,
  InformationProcessingStage,
} from 'plowshare-client-ts/operations/information-content';
import type { InformationFilter } from 'plowshare-client-ts/operations/information-payloads';
import { errorMessage } from 'plowshare-client-ts/binding/values';
import { background } from './events.ts';
import { InformationCollectionReads } from './information-collection.ts';
import { InformationRefresh } from './information-refresh.ts';
import { decodeInformationCall } from 'plowshare-client-ts/operations/information';
import { decodeReply } from 'plowshare-client-ts/operations/schema';
import type { Replies } from 'plowshare-client-ts/operations/replies';
import { displayText } from 'plowshare-client-ts/binding/values';
import { isList, isOneOf } from 'plowshare-client-ts/binding/values';
import { icon } from './icons.ts';
import { markdownHtml } from './markdown.ts';
import { installPaneResize } from './pane-resize.ts';
import type { DesktopApi, DesktopState } from '../shared.ts';
import type {
  InformationOperation,
  InformationScope,
  InformationInput,
} from 'plowshare-client-ts/operations/information';
import {
  MANUAL_INDEX,
  MANUAL_TAG,
  manualChapterTag,
  manualRevision,
} from '../manual.ts';

const escape = (value: unknown) =>
  displayText(value ?? '').replace(
    /[&<>"']/g,
    (c) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[
        c
      ]!,
  );
import type {
  InformationWindow,
  InformationTagEdge,
  InformationFacetCounts,
} from 'plowshare-client-ts/operations/information-replies';

/** The library follows durable revision state; reconnecting never resubmits work. */
export function installInformation(
  api: DesktopApi,
  state: () => DesktopState,
  activeProject: () => string,
  host: HTMLElement,
  options: {
    kind?: 'source' | 'report';
    filterHost?: HTMLElement;
    manual?: boolean;
    onManualChapter?: (chapter: string) => void;
  } = {},
) {
  const dialog = host;
  dialog.classList.add('information-panel');
  let active = false,
    inspectRevision = 0,
    detailSignature = '',
    sourceCanAsk = false;
  let cachedText: { key: string; html: string } | undefined;
  dialog.innerHTML = `<p class="information-intro">Keep the sources behind your work here. Choose a source to read it, ask a question, or save a quotation as evidence.</p>
    <div class="information-reader-toolbar"><div class="information-toolbar" data-collection-controls><label>Show sources from<select data-scope aria-label="Source collection"></select></label><label>Browse<select data-view aria-label="Source view"><option value="list">Sources &amp; reports</option><option value="inventory">My saved versions</option><option value="acquisitions">URL imports</option></select></label><button data-refresh>Refresh sources</button><button data-add-open class="primary-button">Add source</button></div><div data-question hidden></div></div>
    <p data-scope-note class="information-help"></p><p data-error role="alert" hidden></p><p data-notice role="status" hidden></p>
    <details data-faceting class="information-faceting"><summary>Filter sources <span data-facet-total></span></summary><form data-facet-search class="information-toolbar"><label>Search saved text<input name="search" type="search" maxlength="512" placeholder="Words or phrases"></label><button>Search</button><button type="button" data-facet-clear>Clear filters</button></form><div data-facet-selection class="information-toolbar" aria-label="Selected filters"></div><p data-facet-status role="status" hidden></p><div data-facet-choices class="information-facet-choices"></div><details class="information-tag-relations"><summary>Related tag groups</summary><div data-tag-graph></div></details></details>
    <div class="information-workspace"><section class="information-list-pane" aria-label="Sources"><div data-list></div><div class="information-toolbar information-pagination"><button data-previous>Previous sources</button><button data-next>More sources</button></div></section><section data-detail aria-label="Selected source"><div class="information-empty"><h2>Read, ask, and collect evidence</h2><p>Choose a source from the list to read its text or ask a question grounded in that source.</p><p>Use Add source to save text or import a public web page.</p></div></section></div>
    <dialog data-add-dialog aria-labelledby="source-add-title"><header class="dialog-header"><h2 id="source-add-title">Add a source</h2><button type="button" data-add-close>Close</button></header><p>Save text or import a public web page. Processing uses your server’s configured models.</p><p data-add-error role="alert" hidden></p><form data-add><label>Source name<input name="name" autofocus required maxlength="512" placeholder="A name you will recognise"></label><label>Text<textarea name="text" rows="5" placeholder="Paste the source text"></textarea></label><label>Or a public web page URL<input name="url" type="url" placeholder="https://…"></label><p>When a URL is supplied, the web page is imported instead of the pasted text.</p><button class="primary-button">Save source</button></form></dialog>
    <details class="information-report"><summary>Advanced: save a report with evidence references</summary><p>Use this when you already have source version and evidence IDs. Read and ask about sources above for everyday work.</p><form data-report><label>Report name<input name="name" required></label><label>Report text<textarea name="text" rows="6" required></textarea></label><label>Source version IDs, comma separated<input name="inputs" required></label><label>Evidence IDs, comma separated<input name="evidence"></label><label>Previous report version ID (for feedback)<input name="feedback"></label><button>Save draft report</button></form></details>`;
  let authored: InformationContentCapabilities | undefined;
  let navigationIdentity = '';
  let authoredIdentity = '',
    typeIdentity = '';
  let availableTypes: readonly InformationTypeVersion[] = [];
  let selectedContent: InformationContentView | undefined;
  let pendingMetadataEdit: InformationTyping | undefined;
  let pendingMetadataScope = '';
  const authoredControls = document.createElement('details');
  authoredControls.hidden = true;
  authoredControls.className = 'information-types';
  authoredControls.innerHTML = `<summary>Document types</summary><p>Types validate metadata structure. They do not verify factual claims.</p><label>Filter by active type<select data-type-filter><option value="">All document types</option><option value="untyped">Untyped</option></select></label><div data-type-manager hidden><form data-type-create><label>Slug<input name="slug" required maxlength="64" pattern="[a-z][a-z0-9-]*"></label><label>Label<input name="label" required maxlength="120"></label><label>Description<textarea name="description" maxlength="4096"></textarea></label><label>Object schema<textarea name="schema" rows="7" required maxlength="65536">{ "type": "object", "properties": {}, "additionalProperties": false }</textarea></label><button>Create immutable draft</button></form><div data-type-versions></div></div></details>`;
  dialog.querySelector('[data-faceting]')!.before(authoredControls);
  const capabilityNote = document.createElement('details');
  capabilityNote.className = 'information-capability-note';
  capabilityNote.hidden = true;
  capabilityNote.innerHTML =
    '<summary>Server document capabilities</summary><p role="status"></p>';
  dialog.querySelector('.information-workspace')!.after(capabilityNote);
  const authorFields = `<fieldset data-typed-author hidden><label>Document type<select name="contentVersion"><option value="">Untyped</option></select></label><label>Complete metadata object<textarea name="metadata" rows="5" maxlength="16384">{}</textarea></label><p>Defaults in a schema are annotations. Enter every required field explicitly.</p></fieldset>`;
  for (const form of dialog.querySelectorAll<HTMLFormElement>(
    '[data-add],[data-report]',
  ))
    form
      .querySelector('button')!
      .insertAdjacentHTML('beforebegin', authorFields);
  const select = dialog.querySelector<HTMLSelectElement>('[data-scope]')!;
  const view = dialog.querySelector<HTMLSelectElement>('[data-view]')!;
  const list = dialog.querySelector<HTMLElement>('[data-list]')!;
  const detail = dialog.querySelector<HTMLElement>('[data-detail]')!;
  const question = dialog.querySelector<HTMLElement>('[data-question]')!;
  const notice = dialog.querySelector<HTMLElement>('[data-notice]')!;
  const addDialog =
    dialog.querySelector<HTMLDialogElement>('[data-add-dialog]')!;
  const error = dialog.querySelector<HTMLElement>('[data-error]')!;
  let offset = 0,
    selected = '',
    acquisition = false,
    busy = false,
    epoch = 0;
  let reloading = false,
    reloadRequested = false,
    resetGroupDraft = false;
  let filters: InformationFilter = {};
  const collectionReads = new InformationCollectionReads();
  const facetStatus = dialog.querySelector<HTMLElement>('[data-facet-status]')!;
  let displayedFacetContext = '';
  const connectionContext = () =>
    JSON.stringify([
      state().base,
      state().handle,
      state().mode,
      state().selectedConnection,
      state().informationConnection,
    ]);
  const facetContext = () =>
    JSON.stringify([epoch, connectionContext(), scope(), view.value, filters]);
  const collectionContext = () => JSON.stringify([facetContext(), offset]);
  const facetNames = [
    'kind',
    'tags',
    'autoTag',
    'tagGroup',
    'author',
    'documentAuthor',
    'when',
    'subtype',
    'contentType',
    'contentTypeVersion',
  ] as const;
  const facetLabels: Record<string, string> = {
    kind: 'Resource kind',
    tags: 'Your tags',
    autoTag: 'Automatic tags',
    tagGroup: 'Tag group',
    author: 'Owner',
    documentAuthor: 'Document author',
    when: 'Saved month (UTC)',
    subtype: 'Format or language',
    contentType: 'Document type ID',
    contentTypeVersion: 'Type version ID',
  };
  function renderFacets(value: InformationFacetCounts) {
    revealFacets();
    const choices = dialog.querySelector<HTMLElement>('[data-facet-choices]')!;
    dialog.querySelector<HTMLElement>('[data-facet-total]')!.textContent =
      `· ${String(value.total)} ${value.total === 1 ? 'version' : 'versions'}`;
    const facets = value.facets;
    const html = facetNames
      .filter(
        (name) =>
          (name !== 'kind' || !options.kind) &&
          (authored || !['contentType', 'contentTypeVersion'].includes(name)),
      )
      .map((name) => {
        const selected = filters[name];
        const available = (facets[name] ?? []).filter((row) =>
          isList(selected)
            ? !selected.includes(String(row.value))
            : selected !== row.value,
        );
        return `<label>${escape(facetLabels[name])}<select data-facet="${name}" aria-label="${escape(facetLabels[name])}" ${available.length ? '' : 'disabled'}><option value="">Narrow by ${escape((facetLabels[name] ?? name).toLowerCase())}</option>${available.map((row) => `<option value="${escape(row.value)}">${escape(row.value)} (${escape(row.count)})</option>`).join('')}</select>${value.hasMore[name] ? '<small>Showing the 100 most frequent values.</small>' : ''}</label>`;
      })
      .join('');
    if (choices.innerHTML !== html) choices.innerHTML = html;
    const graph = dialog.querySelector<HTMLElement>('[data-tag-graph]')!;
    const grouped = new Map<string, InformationTagEdge[]>();
    for (const edge of value.tagGraph.edges) {
      const name = String(edge.group);
      grouped.set(name, [...(grouped.get(name) ?? []), edge]);
    }
    const graphHtml =
      [...grouped.entries()]
        .slice(0, 20)
        .map(
          ([group, edges]) =>
            `<section><button type="button" data-group-browse="${escape(group)}">${escape(group)}</button><p>${edges
              .slice(0, 12)
              .map((edge) => `${escape(edge.tag)} (${escape(edge.count)})`)
              .join(', ')}${edges.length > 12 ? ' …' : ''}</p></section>`,
        )
        .join('') || '<p>No related tag groups in these results yet.</p>';
    const graphContent =
      graphHtml +
      (grouped.size > 20 || value.tagGraph.hasMore
        ? '<small>Showing the most frequent relationships. Narrow the filters to see more.</small>'
        : '');
    if (graph.innerHTML !== graphContent) graph.innerHTML = graphContent;
    renderSelectedFilters();
  }
  function renderSelectedFilters() {
    const selected = dialog.querySelector<HTMLElement>(
      '[data-facet-selection]',
    )!;
    const selection = Object.entries(filters)
      .flatMap(([name, value]) =>
        (isList(value) ? value : [value]).map(
          (item) =>
            `<button type="button" data-facet-remove="${escape(name)}" data-value="${escape(item)}" aria-label="Remove ${escape(facetLabels[name] ?? name)} filter ${escape(item)}">${escape(facetLabels[name] ?? 'Search')}: ${escape(item)} ×</button>`,
        ),
      )
      .join('');
    if (selected.innerHTML !== selection) selected.innerHTML = selection;
  }
  const sourceResize = installPaneResize({
    container: dialog,
    pane: dialog.querySelector<HTMLElement>('.information-list-pane')!,
    other: detail,
    key: 'sources',
    label: 'Resize source list',
    property: '--sources-width',
    minimum: 220,
  });
  if (options.kind === 'report') {
    dialog.classList.add('information-reports');
    // Collection filters belong with the catalogue rather than consuming the report's
    // reading height. They remain keyboard-accessible when the catalogue scrolls.
    dialog
      .querySelector('.information-list-pane')!
      .prepend(authoredControls, dialog.querySelector('[data-faceting]')!);
    select.parentElement!.firstChild!.textContent = '';
    select.title = 'Filter report collection';
    dialog.querySelector<HTMLElement>('[data-scope-note]')!.hidden = true;
    select.setAttribute('aria-label', 'Report collection');
    detail.setAttribute('aria-label', 'Selected report');
    dialog.querySelector<HTMLElement>('.information-intro')!.hidden = true;
    view.parentElement!.hidden = true;
    dialog.querySelector<HTMLElement>('[data-add-open]')!.hidden = true;
    dialog.querySelector<HTMLElement>('.information-report')!.hidden = true;
    const refresh = dialog.querySelector<HTMLButtonElement>('[data-refresh]')!;
    refresh.innerHTML = icon('refresh');
    refresh.title = 'Refresh reports';
    refresh.setAttribute('aria-label', 'Refresh reports');
    if (options.filterHost) {
      options.filterHost.append(select.parentElement!, refresh);
      dialog.querySelector<HTMLElement>('[data-collection-controls]')!.hidden =
        true;
      refresh.addEventListener('click', () => {
        detailSignature = '';
        cachedText = undefined;
        background(reload());
      });
    }
    dialog.querySelector<HTMLElement>('[data-previous]')!.textContent =
      'Previous reports';
    dialog.querySelector<HTMLElement>('[data-next]')!.textContent =
      'More reports';
    detail.innerHTML =
      '<div class="information-empty"><h2>Generated reports</h2><p>Choose a report to read its findings and supporting evidence.</p></div>';
    dialog
      .querySelector<HTMLElement>('.information-list-pane')!
      .setAttribute('aria-label', 'Reports');
  } else if (options.kind === 'source') {
    const firstView = view.options[0];
    if (firstView) firstView.text = 'Document sources';
    dialog.querySelector<HTMLElement>('.information-report')!.hidden = true;
  }
  const addTitle = addDialog.querySelector<HTMLElement>('h2')!;
  addTitle.id = `source-add-title-${host.id}`;
  addDialog.setAttribute('aria-labelledby', addTitle.id);
  const emptyDetail = detail.innerHTML;
  const asking = new Set<string>();
  let sourceOffset = 0,
    sourceFormat: 'rendered' | 'source' = 'rendered';
  let sourceWindow: InformationWindow | undefined;
  const requests = new Map<string, string>();
  if (options.manual) {
    dialog.classList.add('information-reports', 'information-manual');
    dialog.querySelector<HTMLElement>('.information-intro')!.textContent =
      'Read the Plowshare manual. Choose a chapter or follow a chapter link.';
    select.disabled = true;
    select.parentElement!.hidden = true;
    detail.setAttribute('aria-label', 'Selected manual chapter');
    dialog.querySelector<HTMLElement>('[data-refresh]')!.textContent =
      'Refresh chapters';
    dialog.querySelector<HTMLElement>('[data-previous]')!.textContent =
      'Previous chapters';
    dialog.querySelector<HTMLElement>('[data-next]')!.textContent =
      'More chapters';
    view.parentElement!.hidden = true;
    dialog.querySelector<HTMLElement>('[data-add-open]')!.hidden = true;
    dialog.querySelector<HTMLElement>('.information-report')!.hidden = true;
  }
  const scope = (): InformationScope =>
    select.value === 'global_read'
      ? { kind: 'global_read' }
      : select.value === 'personal'
        ? { kind: 'personal', includeGlobal: true }
        : {
            kind: 'project',
            project: select.value.slice(8),
            includeGlobal: true,
          };
  const showError = (reason: unknown) => {
    error.hidden = false;
    error.textContent =
      reason instanceof Error ? reason.message : errorMessage(reason);
    if (addDialog.open) {
      const message = addDialog.querySelector<HTMLElement>('[data-add-error]')!;
      message.hidden = false;
      message.textContent = error.textContent;
    }
  };
  async function call<K extends InformationOperation>(
    operation: K,
    ...args: Record<string, never> extends InformationInput<K>
      ? [payload?: InformationInput<K>]
      : [payload: InformationInput<K>]
  ): Promise<Replies[`information.${K}`]> {
    const selection = scope();
    const checked = decodeInformationCall(
      operation,
      { ...(args[0] ?? {}), ...(authored ? { view: authored.view } : {}) },
      selection,
    );
    const answer = await api.request({
      action: 'information',
      ...checked,
      scope: selection,
    });
    return decodeReply(`information.${operation}`, answer.information);
  }
  const navigation =
    options.manual || options.kind === 'report'
      ? undefined
      : installDocumentNavigation(
          api,
          dialog,
          scope,
          () => JSON.stringify([state().base, state().handle, state().mode]),
          () => state().handle,
          () => state().serverAdmin === true,
          () =>
            availableTypes.map((t) => ({
              id: t.contract.type,
              label: t.contract.label,
            })),
        );
  const portable = options.manual
    ? undefined
    : installInformationInterchange(
        api,
        dialog,
        scope,
        () => JSON.stringify([state().base, state().handle, state().mode]),
        () => selected,
        reload,
      );
  let portableIdentity = '';
  function requestId(operation: string, payload: object) {
    const key = JSON.stringify([scope(), operation, payload]);
    let id = requests.get(key);
    if (!id) {
      id = crypto.randomUUID();
      requests.set(key, id);
    }
    return id;
  }
  async function reload() {
    if (!active || !state().connected || state().mode !== 'live') return;
    if (busy || reloading) {
      reloadRequested = true;
      return;
    }
    reloadRequested = false;
    refreshes.readStarted();
    reloading = true;
    const stamp = epoch;
    const context = collectionContext();
    const current = () =>
      active &&
      state().connected &&
      state().mode === 'live' &&
      context === collectionContext();
    try {
      const connectionIdentity = connectionContext();
      if (authoredIdentity !== connectionIdentity) {
        authoredIdentity = connectionIdentity;
        authored = undefined;
        capabilityNote.hidden = true;
        typeIdentity = '';
        availableTypes = [];
        try {
          const capabilities = await call('types.capabilities', {});
          if (!current()) return;
          authored = capabilities;
        } catch {
          if (!current()) return;
          const unavailable =
            'This server has not advertised authored document types. Existing untyped sources remain available.';
          if (options.kind === 'report') {
            capabilityNote.hidden = false;
            capabilityNote.querySelector('p')!.textContent = unavailable;
          } else {
            notice.hidden = false;
            notice.textContent = unavailable;
          }
        }
      }
      const portableContext = JSON.stringify([connectionIdentity, scope()]);
      if (portable && portableIdentity !== portableContext) {
        portableIdentity = portableContext;
        await portable.probe();
        if (!current()) return;
      }
      if (navigation && navigationIdentity !== connectionIdentity) {
        navigationIdentity = connectionIdentity;
        try {
          await navigation.probe();
          if (!current()) return;
        } catch (reason) {
          if (!current()) return;
          notice.hidden = false;
          notice.textContent = `Document navigation is unavailable: ${errorMessage(reason)}`;
        }
      }
      const registryIdentity = JSON.stringify([connectionIdentity, scope()]);
      if (pendingMetadataScope !== registryIdentity) {
        pendingMetadataScope = registryIdentity;
        pendingMetadataEdit = undefined;
      }
      if (authored && typeIdentity !== registryIdentity) {
        typeIdentity = registryIdentity;
        availableTypes =
          scope().kind === 'global_read'
            ? []
            : (await call('types.list', { view: authored.view, limit: 100 }))
                .results;
        if (!current()) return;
        const registryScope = scope();
        const manager =
          registryScope.kind === 'personal' ||
          (registryScope.kind === 'project' &&
            state().projects.some(
              (project) =>
                project.name === registryScope.project &&
                project.role === 'MANAGER',
            ));
        authoredControls.hidden =
          !!options.manual || scope().kind === 'global_read';
        authoredControls.querySelector<HTMLElement>(
          '[data-type-manager]',
        )!.hidden = !manager || !authored.typedWrites;
        const versions =
          manager && authored.typedWrites
            ? (
                await call('types.list', {
                  view: authored.view,
                  drafts: true,
                  limit: 100,
                })
              ).results
            : availableTypes;
        if (!current()) return;
        const versionsHtml = versions
          .map((type) => {
            const controls =
              manager && authored?.typedWrites
                ? `<details><summary>Prepare a new immutable version</summary><form data-type-version><input type="hidden" name="type" value="${escape(type.contract.type)}"><input type="hidden" name="controlRevision" value="${escape(type.controlRevision)}"><label>Object schema<textarea name="schema" rows="6" maxlength="65536" required>${escape(JSON.stringify(type.contract.schema, null, 2))}</textarea></label><label>Presentation hints<textarea name="presentation" rows="3" maxlength="8192">${escape(JSON.stringify(type.contract.presentation, null, 2))}</textarea></label><button>Create next draft</button></form></details>${type.state === 'draft' && manager ? `<button data-type-transition="${escape(type.contract.version)}" data-control-revision="${escape(type.controlRevision)}" data-type-action="activate">Activate</button>` : type.state === 'active' && manager ? `<button data-type-transition="${escape(type.contract.version)}" data-control-revision="${escape(type.controlRevision)}" data-type-action="deprecate">Deprecate</button>` : ''}`
                : '';
            return `<section class="information-type-version"><p>${escape(type.contract.label)} · ${escape(type.contract.ordinal)} · ${escape(type.state)} <code>${escape(type.contract.version)}</code></p>${controls}</section>`;
          })
          .join('');
        const versionList = authoredControls.querySelector<HTMLElement>(
          '[data-type-versions]',
        )!;
        if (versionList.innerHTML !== versionsHtml)
          versionList.innerHTML = versionsHtml;
        const typeFilter =
          authoredControls.querySelector<HTMLSelectElement>(
            '[data-type-filter]',
          )!;
        const filterOptions =
          '<option value="">All document types</option><option value="untyped">Untyped</option>' +
          typeOptions(availableTypes);
        if (typeFilter.innerHTML !== filterOptions)
          typeFilter.innerHTML = filterOptions;
        for (const fieldset of dialog.querySelectorAll<HTMLElement>(
          '[data-typed-author]',
        )) {
          fieldset.hidden =
            !authored.typedWrites ||
            scope().kind === 'global_read' ||
            !!options.manual;
          const typeSelect =
            fieldset.querySelector<HTMLSelectElement>('select')!;
          const authorOptions =
            '<option value="">Untyped</option>' + typeOptions(availableTypes);
          if (typeSelect.innerHTML !== authorOptions)
            typeSelect.innerHTML = authorOptions;
        }
      }
      const faceting = dialog.querySelector<HTMLElement>('[data-faceting]')!;
      faceting.hidden = view.value !== 'list';
      // Manual navigation stays in its shared collection even after clearing search filters.
      const filter = options.manual
        ? {
            ...filters,
            tags: [
              ...new Set([
                ...(isList(filters.tags) ? filters.tags : []),
                MANUAL_TAG,
              ]),
            ],
          }
        : filters;
      const payload = {
        offset,
        limit: 20,
        ...(view.value === 'list'
          ? { filter, ...(options.kind ? { kind: options.kind } : {}) }
          : {}),
      };
      if (!current()) return;
      const facetKey = facetContext();
      renderSelectedFilters();
      if (displayedFacetContext !== facetKey) {
        displayedFacetContext = facetKey;
        dialog
          .querySelector<HTMLElement>('[data-facet-choices]')!
          .replaceChildren();
        dialog
          .querySelector<HTMLElement>('[data-tag-graph]')!
          .replaceChildren();
        dialog.querySelector<HTMLElement>('[data-facet-total]')!.textContent =
          '';
      }
      await collectionReads.load({
        facetKey,
        current,
        facetsCurrent: () =>
          active &&
          state().connected &&
          state().mode === 'live' &&
          facetKey === facetContext(),
        list: () =>
          view.value === 'list'
            ? call('list', payload)
            : view.value === 'inventory'
              ? call('inventory', { offset, limit: 20 })
              : view.value === 'acquisitions'
                ? call('acquisitions', { offset, limit: 20 })
                : Promise.reject(new Error('Unknown collection view.')),
        ...(view.value === 'list'
          ? {
              facets: () =>
                call('facets', {
                  filter,
                  ...(options.kind ? { kind: options.kind } : {}),
                }),
            }
          : {}),
        showFacets: renderFacets,
        facetProgress: (progress) => {
          facetStatus.hidden = progress.state === 'ready';
          facetStatus.setAttribute(
            'role',
            progress.state === 'failed' ? 'alert' : 'status',
          );
          facetStatus.textContent =
            progress.state === 'loading'
              ? 'Loading filters…'
              : progress.state === 'failed'
                ? `Filters are unavailable: ${errorMessage(progress.reason)}. Refresh sources to retry.`
                : '';
        },
        showList: async (result) => {
          const data = result.map((row) => ({
            id: row.id,
            title: 'title' in row ? row.title : undefined,
            source_name: row.source_name,
            status:
              'state' in row
                ? row.state
                : (row.report_status ?? row.availability),
            ordinal: 'ordinal' in row ? row.ordinal : undefined,
            kind: 'kind' in row ? row.kind : undefined,
            tags: 'tags' in row ? row.tags : [],
            autoTag: 'autoTag' in row ? row.autoTag : [],
          }));
          if (stamp !== epoch || !active) return;
          if (
            view.value === 'list' &&
            options.kind &&
            data.some((row) => row.kind !== options.kind)
          )
            throw new Error(
              'The server returned a different resource type. Update the server before using this collection.',
            );
          if (stamp !== epoch || !active) return;
          const listHtml =
            data
              .map(
                (row) =>
                  `<button class="information-row ${selected === String(row.id) ? 'selected' : ''}" aria-pressed="${selected === String(row.id)}" data-id="${escape(row.id)}" data-acquisition="${view.value === 'acquisitions'}"><strong>${escape(row.title ?? row.source_name)}</strong><small>${escape(row.status)}${row.ordinal ? ` · revision ${escape(row.ordinal)}` : ''}</small>${!options.manual && isList(row.tags) && row.tags.length ? `<small>Your tags: ${row.tags.map(escape).join(', ')}</small>` : ''}${!options.manual && isList(row.autoTag) && row.autoTag.length ? `<small>Automatic: ${row.autoTag.map(escape).join(', ')}</small>` : ''}</button>`,
              )
              .join('') ||
            (Object.keys(filters).length
              ? '<div class="information-empty"><h3>No matching versions</h3><p>Remove a filter or clear the filters to browse again.</p></div>'
              : options.manual
                ? '<div class="information-empty"><h3>No manual chapters</h3><p>Ask your administrator to install the shared manual.</p></div>'
                : options.kind === 'report'
                  ? '<div class="information-empty"><h3>No reports yet</h3><p>Generated reports will appear here when research work finishes.</p></div>'
                  : '<div class="information-empty"><h3>No sources here yet</h3><p>Add a source or choose another collection.</p></div>');
          if (list.innerHTML !== listHtml) list.innerHTML = listHtml;
          dialog.querySelector<HTMLButtonElement>('[data-previous]')!.disabled =
            offset === 0;
          dialog.querySelector<HTMLButtonElement>('[data-next]')!.disabled =
            data.length < 20;
          if (selected) await inspect(selected, acquisition, stamp);
          if (current()) revealReadSurfaces();
        },
      });
    } catch (reason) {
      if (current()) {
        collectionReads.invalidate();
        displayedFacetContext = '';
        clearProtected();
        revealReadSurfaces();
        list.textContent = 'This collection is unavailable.';
        detail.textContent = '';
        question.hidden = true;
        question.innerHTML = '';
        showError(reason);
      }
    } finally {
      reloading = false;
      if ((reloadRequested || context !== collectionContext()) && active)
        background(reload());
    }
  }
  async function inspect(id: string, isAcquisition: boolean, stamp = epoch) {
    if (selected !== id) {
      question.hidden = true;
      question.innerHTML = '';
      detail.dataset.sourceId = '';
      sourceOffset = 0;
      sourceFormat = 'rendered';
      detailSignature = '';
      detail.innerHTML = '<p role="status">Opening source…</p>';
    }
    const inspection = ++inspectRevision;
    selected = id;
    acquisition = isAcquisition;
    sourceCanAsk = false;
    const value = await call(
      'status',
      isAcquisition ? { acquisition: id } : { revision: id },
    );
    if (
      stamp !== epoch ||
      inspection !== inspectRevision ||
      selected !== id ||
      acquisition !== isAcquisition ||
      !active
    )
      return;
    for (const row of list.querySelectorAll<HTMLElement>('[data-id]')) {
      row.classList.toggle('selected', row.dataset.id === id);
      row.setAttribute('aria-pressed', String(row.dataset.id === id));
    }
    if (isAcquisition) {
      if (!('state' in value))
        throw new Error('Expected an acquisition status.');
      question.hidden = true;
      question.innerHTML = '';
      detail.innerHTML = `<h3>${escape(value.source_name)}</h3><p>${escape(value.state)} · ${escape(value.attempt)} attempts</p><p>${escape(value.url)}</p>${value.error ? `<p role="alert">${escape(value.error)}</p>` : ''}<p>Import budget: ${escape(value.allowance_total)} calls.</p><div class="information-toolbar">${['failed', 'blocked'].includes(String(value.state)) ? '<button data-control="retry">Retry URL import</button>' : ''}${value.revision_id ? `<button data-open-revision="${escape(value.revision_id)}">Read imported source</button>` : ''}</div>`;
      return;
    }
    if ('state' in value)
      throw new Error('Expected a retained revision status.');
    const steps = (value.steps ?? []).filter(
      (row) => row.generation === value.generation,
    );
    const extraction = steps.find((row) => row.stage === 'extract');
    const derived = steps.find((row) => row.stage === 'derive');
    const extracted =
      !!extraction && ['ready', 'skipped'].includes(String(extraction.state));
    sourceCanAsk =
      value.availability === 'active' &&
      extracted &&
      !!derived &&
      ['ready', 'skipped'].includes(String(derived.state));
    const extractionFailed =
      !!extraction &&
      ['failed', 'blocked', 'cancelled'].includes(String(extraction.state));
    const blank = displayText(extraction?.error ?? '').includes(
      'extracted text is blank',
    );
    const textKey = JSON.stringify([
      scope(),
      id,
      value.generation,
      extraction,
      sourceOffset,
    ]);
    const content =
      authored && value.availability === 'active'
        ? await call('content.get', { view: authored.view, revision: id })
        : undefined;
    if (
      stamp !== epoch ||
      inspection !== inspectRevision ||
      selected !== id ||
      !active
    )
      return;
    selectedContent = content;
    const report = value.report;
    const answerJob = state()
      .jobs.filter((job) => job.source === 'information' && job.revision === id)
      .at(-1);
    const manage = !options.manual && value.can_manage === true;
    const control = (operation: string, label: string) =>
      `<button data-control="${operation}">${label}</button>`;
    const management = manage
      ? [
          ...(value.availability === 'active'
            ? [
                control('retry', 'Retry incomplete work'),
                control(
                  value.excluded ? 'unexclude' : 'exclude',
                  value.excluded
                    ? 'Include in discovery'
                    : 'Exclude from discovery',
                ),
                control('withdraw', 'Withdraw'),
                control('share', 'Share'),
                control('unshare', 'Unshare'),
              ]
            : value.availability !== 'deleted'
              ? [control('restore', 'Restore')]
              : []),
          ...(report?.status === 'draft'
            ? [control('finalise', 'Finalise report')]
            : []),
          ...(value.availability !== 'deleted'
            ? [control('delete', 'Delete retained content')]
            : []),
        ].join('')
      : '';
    const nextSignature = JSON.stringify([
      id,
      value,
      content,
      answerJob?.status,
      answerJob?.text,
      answerJob?.detail,
      sourceOffset,
    ]);
    if (detailSignature === nextSignature) {
      renderAnswer();
      return;
    }
    const sameSource = detail.dataset.sourceId === id;
    const expanded = sameSource
      ? Array.from(detail.querySelectorAll<HTMLDetailsElement>('details')).map(
          (node) => node.open,
        )
      : [];
    const drafts = sameSource
      ? Array.from(
          dialog.querySelectorAll<
            HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement
          >(
            '[data-detail] input[name],[data-detail] textarea[name],[data-detail] select[name],[data-question] input[name]',
          ),
        ).map((node) => [node.name, node.value])
      : [];
    const scroll = detail.scrollTop;
    const renderedScroll = sameSource
      ? (detail.querySelector<HTMLElement>('[data-rendered-passage]')
          ?.scrollTop ?? 0)
      : 0;
    detail.dataset.sourceId = id;
    detailSignature = nextSignature;
    const attention = steps.some(
      (row) =>
        row.state === 'failed' ||
        row.state === 'blocked' ||
        (row.state === 'ready' && row.compatible === false),
    );
    const preparing = steps.some(
      (row) => !['ready', 'skipped'].includes(String(row.state)),
    );
    const legacy = steps.some((row) => row.state === 'skipped');
    const questionHelp = sourceCanAsk
      ? 'The answer uses this saved source and may take a moment. It uses the configured model.'
      : value.availability !== 'active'
        ? 'Questions are unavailable for this version.'
        : extractionFailed
          ? 'Questions are unavailable because text extraction did not finish.'
          : !extracted
            ? 'Questions will be available after text extraction and passage preparation finish.'
            : 'The saved text is readable. Questions will be available after passage preparation finishes.';
    const textUnavailable =
      value.availability !== 'active'
        ? '<p>Source text is unavailable for this version.</p>'
        : !extracted
          ? `<h3>${blank ? 'No readable text was extracted' : extractionFailed ? 'Source text is unavailable' : 'Preparing source text'}</h3><p>${blank ? 'The server tried to extract this source but found no readable text.' : extractionFailed ? 'Text extraction did not finish. See Processing details for the server’s reason.' : 'Text extraction has not finished yet. This view will update when it is ready.'}</p>${extractionFailed && manage ? `<p>${blank ? 'Open Manage source and paste readable text into Replacement text to save a new version. Retrying the same saved content may fail again.' : 'Open Manage source to retry incomplete work or supply replacement text as a new version.'}</p><button data-repair-source>Open Manage source</button>` : ''}`
          : '';
    const questionBusy =
      asking.has(id) ||
      (!!answerJob &&
        ['starting', 'running', 'cancelling', 'unknown'].includes(
          answerJob.status,
        ));
    const status =
      value.availability !== 'active'
        ? String(value.availability)
        : attention
          ? 'Needs attention'
          : !steps.length
            ? 'Processing status unavailable'
            : content?.processingPolicy === 'explicit_only' &&
                steps.some(
                  (step) =>
                    step.state === 'pending' &&
                    !content.authorisedStages.includes(step.stage),
                )
              ? 'Saved; processing awaits an explicit request'
              : preparing
                ? 'Preparing source'
                : legacy
                  ? 'Saved legacy text'
                  : 'Processing complete';
    question.hidden = false;
    question.innerHTML = `<form data-ask data-ready="${sourceCanAsk}" class="information-ask"><label>Ask about this source<input name="question" aria-describedby="source-question-help-${host.id}" placeholder="What does this source say about…?" required></label><button class="primary-button" title="${escape(questionHelp)}" ${questionBusy || !sourceCanAsk ? 'disabled' : ''}>${questionBusy ? 'Reading…' : 'Ask source'}</button><p class="information-help" id="source-question-help-${host.id}">${escape(questionHelp)}</p></form>`;
    const metadata = content
      ? contentHtml(
          content,
          availableTypes,
          manage,
          !!authored?.typedWrites,
          !!pendingMetadataEdit,
        )
      : '';
    detail.innerHTML = `<header class="information-source-heading"><h2>${escape(value.title ?? value.source_name)}</h2>${value.documentAuthor ? `<p class="information-document-author">Document author: ${escape(value.documentAuthor)}${value.documentAuthorSource === 'organisation' ? ' · issuing organisation' : value.documentAuthorSource === 'account' ? ' · owner account (fallback)' : ''}</p>` : ''}<p>Version ${escape(value.ordinal)} · ${escape(status)}${report?.status ? ` · ${escape(report?.status)} report` : ''}</p>${attention ? '<p role="status">Some processing needs attention. Open Processing details below for the reason and recovery options.</p>' : ''}</header>
      ${options.kind === 'report' ? '' : metadata}
      <div data-answer role="status">${answerJob ? `<p>${escape(answerJob.status === 'finished' ? 'Answer' : answerJob.status === 'running' ? 'Finding an answer…' : answerJob.status)}</p><div class="markdown-body">${markdownHtml(answerJob.text || answerJob.detail || 'Waiting for the source answer.')}</div>` : ''}</div>
      <div data-source>${textUnavailable || (cachedText?.key === textKey ? cachedText.html : '<p>Loading source text…</p>')}</div>
      <div class="information-source-tools">${options.kind === 'report' ? metadata : ''}<details><summary>${options.kind === 'report' ? 'Evidence' : 'Evidence and source references'}</summary><p>Saved quotations connect findings to the exact text they came from.</p>${(isList(value.inputs) ? value.inputs : []).map((id) => `<button data-open-revision="${escape(id)}">Read source version ${escape(id)}</button>`).join('')}${(isList(value.citations) ? value.citations : []).map((id) => `<button data-evidence="${escape(id)}">Read saved quotation ${escape(id)}</button>`).join('')}<div data-evidence-detail></div></details>
      <details data-manage-source ${manage ? '' : 'hidden'}><summary>Manage source</summary><div class="information-toolbar">${management}</div>
      ${
        manage
          ? `<form data-allowance><label>Processing budget (maximum model calls)<input name="total" type="number" min="${escape(value.allowance_spent)}" value="${escape(value.allowance_total)}" required></label><button>Update budget</button></form>
      <form data-rebuild><label>Rebuild from <select name="stage"><option value="embed">Passage embeddings</option><option value="summarise">Summaries</option><option value="summary_embed">Summary embedding</option><option value="autoTag">Automatic tags and document author</option><option value="tagGroups">Tag groups</option></select></label><button>Rebuild</button></form>
      <form data-link><label>Use this source in a project<select name="project">${state()
        .projects.map((row) => `<option>${escape(row.name)}</option>`)
        .join(
          '',
        )}</select></label><button name="direction" value="link">Link</button><button name="direction" value="unlink">Unlink</button></form>
      ${value.source_uri ? '<button data-control="refresh">Refresh original URL into a new revision</button>' : ''}
      ${value.kind === 'report' ? '<p>To edit report prose, use Save a report with the prior report UUID and its inputs and citations.</p>' : '<form data-revise><label>Replacement text<textarea name="text" rows="4" required></textarea></label><button>Save a new version</button></form>'}`
          : ''
      }
      </details>
      <details class="information-processing"><summary>${options.kind === 'report' ? 'Processing' : 'Processing details'}</summary><p>These steps prepare the saved text for search and questions. “Ready” means that step has finished.</p><p>${escape(value.allowance_spent ?? 'Unknown')} / ${escape(value.allowance_total ?? 'unknown')} model calls used.</p><table><thead><tr><th>Step</th><th>Status</th><th>Attempts</th></tr></thead><tbody>${steps.map((row) => `<tr><td>${escape(({ extract: 'Extract text', derive: 'Organise passages', embed: 'Prepare passage search', summarise: 'Write summaries', summary_embed: 'Prepare summary search', autoTag: 'Generate tags and document author', tagGroups: 'Group related tags' } as Record<string, string>)[String(row.stage)] ?? row.stage)}</td><td>${escape(row.state === 'ready' && row.compatible === false ? `${row.state} · settings changed; rebuild needed` : row.state)}</td><td>${escape(row.attempt)}</td></tr>${row.error ? `<tr><td colspan="3" role="alert">${escape(row.error)}</td></tr>` : ''}`).join('')}</tbody></table><p class="information-id">Source version ID: ${escape(id)}</p><details><summary>Processing history</summary><pre>${escape(JSON.stringify(value.events ?? [], null, 2))}</pre></details>${report?.details ? `<details><summary>Report review data</summary><pre>${escape(JSON.stringify(report?.details, null, 2))}</pre></details>` : ''}</details></div>
      <details class="information-tag-metadata"><summary>Tags</summary>
      <section class="information-tags"><h3>Tags</h3><p>Your tags: ${isList(value.tags) && value.tags.length ? value.tags.map(escape).join(', ') : 'None yet'}</p><p>Automatic tags: ${isList(value.autoTag) && value.autoTag.length ? value.autoTag.map(escape).join(', ') : value.auto_tag_generated && steps.some((row) => row.stage === 'autoTag' && row.state === 'ready') ? 'No topical tags found' : `Not ready${steps.find((row) => row.stage === 'autoTag') ? ` · ${escape(steps.find((row) => row.stage === 'autoTag')!.state)}` : ''}`}</p>${manage ? `<form data-tags><label>Your tags, separated by commas<input name="tags" value="${escape(isList(value.tags) ? value.tags.join(', ') : '')}" maxlength="2080"></label><button>Save tags</button></form>` : ''}</section>
      <details class="information-tag-groups"><summary>Tag groups · ${value.tagGroupsSource === 'manual' ? 'your override' : 'automatic'}</summary>${
        Object.entries(value.tagGroups ?? {})
          .map(
            ([group, tags]) =>
              `<p><strong>${escape(group)}</strong>: ${isList(tags) ? tags.map(escape).join(', ') : ''}</p>`,
          )
          .join('') || '<p>No groups assigned yet.</p>'
      }${
        manage
          ? `<form data-tag-groups><label>One group per line: category: tag, tag<textarea name="groups" rows="4" maxlength="35000" placeholder="databases: postgresql, sqlite">${escape(
              Object.entries(value.tagGroups ?? {})
                .map(
                  ([group, tags]) =>
                    `${group}: ${isList(tags) ? tags.join(', ') : ''}`,
                )
                .join('\n'),
            )}</textarea></label><button>Save groups</button><button type="button" data-use-auto-groups>Use automatic groups</button><small>Use existing tags. Saving replaces automatic group choices; a blank list removes all groups.</small></form>`
          : ''
      }</details>
      </details>`;
    Array.from(detail.querySelectorAll<HTMLDetailsElement>('details')).forEach(
      (node, i) => {
        node.open = expanded[i] ?? false;
      },
    );
    for (const [name, value] of drafts) {
      if (name === 'groups' && resetGroupDraft) continue;
      const field = Array.from(
        dialog.querySelectorAll<
          HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement
        >('[data-detail] [name],[data-question] [name]'),
      ).find((node) => node.name === name);
      if (field && value !== undefined) field.value = value;
    }
    resetGroupDraft = false;
    detail.scrollTop = scroll;
    applySourceFormat();
    const retainedReport = detail.querySelector<HTMLElement>(
      '[data-rendered-passage]',
    );
    if (retainedReport) retainedReport.scrollTop = renderedScroll;
    if (
      value.availability === 'active' &&
      extracted &&
      cachedText?.key !== textKey
    ) {
      try {
        const wholeReport =
          options.kind === 'report' || options.manual === true;
        let window = await call('read', {
          revision: id,
          offset: wholeReport ? 0 : sourceOffset,
          limit: wholeReport ? 32768 : 8192,
        });
        if (wholeReport) {
          const total = Number(window.total),
            parts = [displayText(window.text ?? '')];
          if (options.manual && total > 262144)
            throw new Error(
              'This manual chapter exceeds the supported size. Ask your server administrator to split it.',
            );
          if (
            window.revision !== id ||
            Number(window.start) !== 0 ||
            Number(window.end) !== (parts[0]?.length ?? 0) ||
            !Number.isSafeInteger(total) ||
            total < (parts[0]?.length ?? 0)
          )
            throw new Error(
              'The report read returned inconsistent text offsets.',
            );
          while (Number(window.end) < total) {
            if (
              stamp !== epoch ||
              inspection !== inspectRevision ||
              selected !== id ||
              !active
            )
              return;
            const offset = Number(window.end),
              next = await call('read', { revision: id, offset, limit: 32768 });
            const text = displayText(next.text ?? '');
            if (
              next.revision !== window.revision ||
              Number(next.start) !== offset ||
              Number(next.end) !== offset + text.length ||
              Number(next.total) !== total ||
              !text.length ||
              Number(next.end) > total
            )
              throw new Error(
                'The report read returned inconsistent text offsets.',
              );
            parts.push(text);
            window = next;
          }
          window = { ...window, start: 0, text: parts.join('') };
        }
        if (
          stamp !== epoch ||
          inspection !== inspectRevision ||
          selected !== id ||
          !active
        )
          return;
        const source = detail.querySelector<HTMLElement>('[data-source]');
        if (!source) return;
        sourceWindow = window;
        source.innerHTML = `<div class="information-text-heading"><h3>Source text</h3><span class="information-help">${wholeReport ? `${escape(window.total)} characters` : `Characters ${escape(Number(window.start) + 1)}–${escape(window.end)} of ${escape(window.total)}`}</span>${wholeReport ? '<button data-source-format type="button" aria-pressed="false">Show source</button>' : ''}</div>${wholeReport ? `<div data-rendered-passage class="markdown-body information-rendered-report" tabindex="0" role="region" aria-label="${options.manual ? 'Rendered manual chapter' : 'Rendered report'}">${markdownHtml(String(window.text), options.manual)}</div>` : ''}<textarea data-passage readonly rows="12" aria-label="Saved source text" ${wholeReport ? 'hidden' : ''}>${escape(window.text)}</textarea><div class="information-toolbar information-text-actions">${wholeReport ? '' : `<button data-source-prev ${sourceOffset === 0 ? 'disabled' : ''}>Previous text</button><button data-source-next ${Number(window.end) >= Number(window.total) ? 'disabled' : ''}>Next text</button>`}<button data-record-evidence title="Select a quotation in the source text, then save it as evidence">Save selected quotation</button></div><div data-new-evidence></div>`;
        cachedText = { key: textKey, html: source.innerHTML };
        applySourceFormat();
        const renderedReport = detail.querySelector<HTMLElement>(
          '[data-rendered-passage]',
        );
        if (renderedReport) renderedReport.scrollTop = renderedScroll;
      } catch (reason) {
        if (
          stamp === epoch &&
          inspection === inspectRevision &&
          selected === id &&
          active
        ) {
          const source = detail.querySelector<HTMLElement>('[data-source]');
          const message = (
            reason instanceof Error ? reason.message : errorMessage(reason)
          ).replace(/^Error invoking remote method '[^']+': (?:Error: )?/, '');
          if (source) {
            source.innerHTML = `<h3>Source text is unavailable</h3><p>${message === 'this revision has not been extracted' ? 'The server has no saved extraction for this version. Open Processing details to check its extraction status.' : escape(message)}</p><p>Use Refresh sources to try reading again.</p>`;
            cachedText = { key: textKey, html: source.innerHTML };
          }
        }
      }
    }
  }
  function applySourceFormat() {
    const rendered = detail.querySelector<HTMLElement>(
      '[data-rendered-passage]',
    );
    const passage = detail.querySelector<HTMLTextAreaElement>('[data-passage]');
    const toggle = detail.querySelector<HTMLButtonElement>(
      '[data-source-format]',
    );
    if (!rendered || !passage || !toggle) return;
    rendered.hidden = sourceFormat === 'source';
    passage.hidden = sourceFormat === 'rendered';
    toggle.textContent =
      sourceFormat === 'source' ? 'Rendered view' : 'Show source';
    toggle.setAttribute('aria-pressed', String(sourceFormat === 'source'));
  }
  async function mutate<K extends InformationOperation>(
    operation: K,
    body: Omit<InformationInput<K>, 'requestId'>,
  ) {
    if (busy) return;
    busy = true;
    select.disabled = view.disabled = true;
    error.hidden = true;
    const stamp = epoch,
      key = JSON.stringify([scope(), operation, body]);
    try {
      // The mapped decoder owns dynamic operation pairing and validates before IPC.
      const checked = decodeInformationCall(
        operation,
        {
          ...body,
          ...(authored ? { view: authored.view } : {}),
          requestId: requestId(operation, body),
        },
        scope(),
      );
      await api.request({ action: 'information', ...checked, scope: scope() });
      requests.delete(key);
      if (operation.startsWith('types.')) typeIdentity = '';
      if (operation === 'types.activate' || operation === 'types.deprecate')
        authoredControls.open = false;
      if (operation === 'content.revise') {
        pendingMetadataEdit = undefined;
        selected = '';
        selectedContent = undefined;
        detail.innerHTML =
          '<p>Metadata-only revision saved. Choose the new version to read it; processing awaits explicit selection.</p>';
      }
      if (operation === 'tags.groups') resetGroupDraft = true;
      detailSignature = '';
      cachedText = undefined;
      notice.hidden = false;
      notice.textContent =
        operation === 'acquire'
          ? 'URL import accepted. Open URL imports to follow its progress.'
          : operation === 'upload'
            ? 'Source saved. Choose it from the list to start reading.'
            : 'Source change confirmed.';
      if (operation === 'acquire' || operation === 'upload') {
        addDialog.close();
        (addDialog.querySelector('form') as HTMLFormElement).reset();
        if (operation === 'acquire') view.value = 'acquisitions';
      }
    } catch (reason) {
      if (stamp === epoch) {
        const message =
          reason instanceof Error ? reason.message : errorMessage(reason);
        showError(
          message.includes('INFORMATION_REVISION_CONFLICT')
            ? new Error(
                'A newer revision exists. Refresh and select the newest version, then reapply and validate your saved metadata edit.',
              )
            : message.includes('INFORMATION_TYPE_CONFLICT')
              ? new Error(
                  'The type changed. Refresh its current control revision before reapplying this change.',
                )
              : reason,
        );
        detailSignature = '';
      }
    } finally {
      if (operation.startsWith('types.')) typeIdentity = '';
      busy = false;
      select.disabled = view.disabled = false;
      await reload();
    }
  }
  dialog.addEventListener('click', (event) => {
    const stamp = epoch;
    const reportError = (reason: unknown) => {
      if (stamp === epoch && active) showError(reason);
    };
    const target = (event.target as HTMLElement).closest<HTMLButtonElement>(
      'button',
    );
    if (!target) return;
    if (options.manual && target.dataset.manualChapter) {
      options.onManualChapter?.(target.dataset.manualChapter);
      return;
    }
    if (target.hasAttribute('data-add-open')) {
      addDialog.querySelector<HTMLElement>('[data-add-error]')!.hidden = true;
      addDialog.showModal();
    }
    if (target.hasAttribute('data-add-close')) addDialog.close();
    if (target.hasAttribute('data-source-format')) {
      sourceFormat = sourceFormat === 'rendered' ? 'source' : 'rendered';
      applySourceFormat();
    }
    if (target.hasAttribute('data-source-prev')) {
      sourceOffset = Math.max(0, sourceOffset - 8192);
      void inspect(selected, false).catch(reportError);
    }
    if (target.hasAttribute('data-source-next')) {
      if (!sourceWindow) return;
      sourceOffset = sourceWindow.end;
      void inspect(selected, false).catch(reportError);
    }
    if (target.hasAttribute('data-record-evidence')) {
      const passage =
        detail.querySelector<HTMLTextAreaElement>('[data-passage]');
      if (!passage) return;
      let start = passage.selectionStart,
        end = passage.selectionEnd;
      if (passage.hidden) {
        const rendered = detail.querySelector<HTMLElement>(
            '[data-rendered-passage]',
          ),
          selection = document.getSelection();
        if (
          !selection?.rangeCount ||
          !rendered?.contains(selection.getRangeAt(0).commonAncestorContainer)
        ) {
          showError('Select a quotation in the report.');
          return;
        }
        const quote = selection.toString();
        start = passage.value.indexOf(quote);
        end = start + quote.length;
        if (
          !quote ||
          start < 0 ||
          passage.value.indexOf(quote, start + 1) !== -1
        ) {
          showError(
            'Use Show source to select the exact quotation when it includes formatting or occurs more than once.',
          );
          return;
        }
      }
      if (start === end) {
        showError('Select a quotation in the retained text.');
        return;
      }
      if (!sourceWindow) return;
      const body = {
        revision: selected,
        start: sourceWindow.start + start,
        end: sourceWindow.start + end,
        quote: passage.value.slice(start, end),
        locator: 'extracted-text:utf16',
      };
      const stamp = epoch,
        id = selected;
      void call('evidence.record', {
        ...body,
        requestId: requestId('evidence.record', body),
      })
        .then((answer) => {
          if (stamp !== epoch || id !== selected) return;
          const place = detail.querySelector<HTMLElement>(
            '[data-new-evidence]',
          );
          if (place)
            place.textContent = `Quotation saved. Evidence ID: ${answer.evidence}`;
        })
        .catch(reportError);
    }
    if (target.dataset.evidence) {
      const stamp = epoch,
        id = selected;
      void call('evidence.read', { evidence: target.dataset.evidence })
        .then((answer) => {
          if (stamp !== epoch || id !== selected) return;
          const value = answer,
            place = detail.querySelector<HTMLElement>('[data-evidence-detail]');
          if (place)
            place.innerHTML = `<blockquote>${escape(value.quote)}</blockquote><p>${escape(value.locator)} ${escape(value.start_offset)}–${escape(value.end_offset)}</p><button data-open-revision="${escape(value.revision_id)}">Open original retained revision</button>`;
        })
        .catch(reportError);
    }
    if (target.hasAttribute('data-repair-source')) {
      const manage = detail.querySelector<HTMLDetailsElement>(
        '[data-manage-source]',
      );
      if (manage) {
        manage.open = true;
        manage.scrollIntoView({ block: 'nearest' });
        manage
          .querySelector<HTMLTextAreaElement>('[data-revise] textarea')
          ?.focus();
      }
    }
    if (target.hasAttribute('data-use-auto-groups'))
      background(mutate('tags.groups', { revision: selected, groups: null }));
    if (target.hasAttribute('data-refresh')) {
      typeIdentity = '';
      detailSignature = '';
      cachedText = undefined;
      background(reload());
    }
    if (target.hasAttribute('data-previous')) {
      offset = Math.max(0, offset - 20);
      background(reload());
    }
    if (target.hasAttribute('data-next')) {
      offset += 20;
      background(reload());
    }
    if (target.dataset.id)
      void inspect(
        target.dataset.id,
        target.dataset.acquisition === 'true',
      ).catch((reason: unknown) => {
        if (stamp === epoch) {
          detail.textContent = '';
          question.hidden = true;
          question.innerHTML = '';
          showError(reason);
        }
      });
    if (target.dataset.openRevision)
      void inspect(target.dataset.openRevision, false).catch(reportError);
    if (target.dataset.typeTransition)
      background(
        mutate(
          target.dataset.typeAction === 'activate'
            ? 'types.activate'
            : 'types.deprecate',
          {
            view: 'content-types-v1',
            version: target.dataset.typeTransition,
            expectedControlRevision: Number(target.dataset.controlRevision),
          },
        ),
      );
    if (target.hasAttribute('data-reapply-metadata') && pendingMetadataEdit) {
      const form = target.closest('form')!;
      form.querySelector<HTMLSelectElement>('[name="mode"]')!.value =
        pendingMetadataEdit.mode;
      form.querySelector<HTMLSelectElement>('[name="version"]')!.value =
        pendingMetadataEdit.version ?? '';
      form.querySelector<HTMLTextAreaElement>('[name="metadata"]')!.value =
        JSON.stringify(pendingMetadataEdit.metadata ?? {}, null, 2);
    }
    if (target.hasAttribute('data-metadata-validate')) {
      const form = target.closest('form')!;
      const data = new FormData(form);
      const version =
        data.get('mode') === 'set'
          ? displayText(data.get('version'))
          : selectedContent?.contentType?.contract.version;
      if (!version) {
        showError('Select a type before validation.');
        return;
      }
      background(
        (async () => {
          const result = await call('types.validate', {
            view: 'content-types-v1',
            version,
            ...(data.get('mode') !== 'set'
              ? { attachedRevision: selected }
              : {}),
            metadata: metadataObject(displayText(data.get('metadata'))),
          });
          form.querySelector<HTMLElement>(
            '[data-metadata-validation]',
          )!.textContent =
            result.status === 'VALID'
              ? 'Metadata conforms to the bound schema. Claims have not been verified.'
              : result.issues
                  .map(
                    (issue) =>
                      `${issue.instancePointer || '/'}: ${issue.message}`,
                  )
                  .join('\n');
        })().catch(reportError),
      );
    }
    if (target.dataset.control) {
      if (
        target.dataset.control === 'delete' &&
        !confirm(
          'Delete this revision’s retained content? This cannot be restored.',
        )
      )
        return;
      const operation = target.dataset.control;
      if (acquisition) {
        if (operation !== 'retry') {
          showError('Unknown acquisition action.');
          return;
        }
        background(mutate('retry', { acquisition: selected }));
      } else if (
        isOneOf(operation, [
          'retry',
          'exclude',
          'unexclude',
          'withdraw',
          'restore',
          'share',
          'unshare',
          'finalise',
          'delete',
          'refresh',
        ] as const)
      ) {
        if (operation === 'refresh' && selectedContent?.contentType)
          background(
            mutate(operation, {
              revision: selected,
              expectedRevision: selected,
              typing: { mode: 'inherit' },
            }),
          );
        else if (
          operation === 'retry' &&
          selectedContent?.processingPolicy === 'explicit_only'
        )
          showError(
            'Select stages in Document metadata and processing before retrying.',
          );
        else background(mutate(operation, { revision: selected }));
      } else showError('Unknown source action.');
    }
  });
  authoredControls
    .querySelector<HTMLSelectElement>('[data-type-filter]')!
    .addEventListener('change', (event) => {
      const value = (event.target as HTMLSelectElement).value;
      const { contentTypeVersion: _type, untyped: _untyped, ...rest } = filters;
      filters = {
        ...rest,
        ...(value === 'untyped'
          ? { untyped: true }
          : value
            ? { contentTypeVersion: value }
            : {}),
      };
      offset = 0;
      background(reload());
    });
  function authorTyping(data: FormData): InformationTyping | undefined {
    const version = displayText(data.get('contentVersion')).trim();
    return version
      ? {
          mode: 'set',
          version,
          metadata: metadataObject(displayText(data.get('metadata'))),
        }
      : undefined;
  }

  const collectionNote = () => {
    dialog.querySelector<HTMLElement>('[data-scope-note]')!.textContent =
      select.value === 'personal'
        ? 'Your personal sources, plus knowledge available to every signed-in account.'
        : select.value === 'global_read'
          ? 'Knowledge available to every signed-in account.'
          : `Sources available in ${select.selectedOptions[0]?.textContent}.`;
  };
  const resetSelection = () => {
    epoch++;
    offset = 0;
    selected = '';
    detailSignature = '';
    cachedText = undefined;
    sourceCanAsk = false;
    error.hidden = true;
    detail.innerHTML = emptyDetail;
    question.hidden = true;
    question.innerHTML = '';
    collectionNote();
    observeRefresh();
    refreshes.refreshNow();
  };
  function removeFilter(name: keyof InformationFilter) {
    const next = { ...filters };
    delete next[name];
    filters = next;
  }
  const resetFilters = () => {
    filters = {};
    const search = dialog.querySelector<HTMLInputElement>(
      '[data-facet-search] input',
    )!;
    search.value = '';
    resetSelection();
  };
  select.addEventListener('change', resetFilters);
  dialog.addEventListener('change', (event) => {
    const target = event.target as HTMLSelectElement;
    const name = target.dataset.facet;
    if (!isOneOf(name, facetNames) || !target.value) return;
    if (name === 'tags' || name === 'autoTag')
      filters = {
        ...filters,
        [name]: [
          ...new Set([
            ...(isList(filters[name]) ? filters[name] : []),
            target.value,
          ]),
        ],
      };
    else if (name === 'kind') {
      if (!isOneOf(target.value, ['source', 'report'] as const)) return;
      filters = { ...filters, kind: target.value };
    } else filters = { ...filters, [name]: target.value };
    resetSelection();
  });
  dialog.addEventListener('click', (event) => {
    const target = (event.target as HTMLElement).closest<HTMLButtonElement>(
      'button',
    );
    if (!target) return;
    if (target.dataset.groupBrowse) {
      filters = { ...filters, tagGroup: target.dataset.groupBrowse };
      resetSelection();
    }
    if (target.hasAttribute('data-facet-clear')) resetFilters();
    const name = target.dataset.facetRemove;
    if (!isOneOf(name, [...facetNames, 'search'] as const)) return;
    const value = filters[name];
    if (isList(value)) {
      const remaining = value.filter((item) => item !== target.dataset.value);
      if (remaining.length) filters = { ...filters, [name]: remaining };
      else removeFilter(name);
    } else {
      removeFilter(name);
      if (name === 'search')
        dialog.querySelector<HTMLInputElement>(
          '[data-facet-search] input',
        )!.value = '';
    }
    resetSelection();
  });
  view.addEventListener('change', resetSelection);
  dialog.addEventListener('submit', (event) => {
    const stamp = epoch;
    const reportError = (reason: unknown) => {
      if (stamp === epoch && active) showError(reason);
    };
    event.preventDefault();
    const form = event.target as HTMLFormElement;
    const data = new FormData(form);
    if (form.hasAttribute('data-facet-search')) {
      const text = displayText(data.get('search') ?? '').trim();
      if (text) filters = { ...filters, search: text };
      else removeFilter('search');
      resetSelection();
    }
    if (form.hasAttribute('data-tags'))
      background(
        mutate('tags', {
          revision: selected,
          tags: displayText(data.get('tags') ?? '')
            .split(',')
            .map((tag) => tag.trim())
            .filter(Boolean),
        }),
      );
    if (form.hasAttribute('data-tag-groups')) {
      const groups: Record<string, string[]> = {};
      for (const line of displayText(data.get('groups') ?? '')
        .split('\n')
        .map((line) => line.trim())
        .filter(Boolean)) {
        const colon = line.indexOf(':');
        if (colon < 1) {
          showError('Use category: tag, tag on each line.');
          return;
        }
        const group = line.slice(0, colon).trim(),
          tags = line
            .slice(colon + 1)
            .split(',')
            .map((tag) => tag.trim())
            .filter(Boolean);
        if (!tags.length) {
          showError('Each group needs at least one existing tag.');
          return;
        }
        groups[group] = [...new Set([...(groups[group] ?? []), ...tags])];
      }
      background(mutate('tags.groups', { revision: selected, groups }));
    }
    if (form.hasAttribute('data-type-version')) {
      try {
        background(
          mutate('types.version.create', {
            view: 'content-types-v1',
            type: displayText(data.get('type')),
            expectedControlRevision: Number(data.get('controlRevision')),
            schema: parseSchemaDefinition(displayText(data.get('schema'))),
            presentation: metadataObject(displayText(data.get('presentation'))),
          }),
        );
      } catch (reason) {
        showError(reason);
      }
    }
    if (form.hasAttribute('data-type-create')) {
      try {
        background(
          mutate('types.version.create', {
            view: 'content-types-v1',
            slug: displayText(data.get('slug')),
            label: displayText(data.get('label')),
            description: displayText(data.get('description')),
            schema: parseSchemaDefinition(displayText(data.get('schema'))),
          }),
        );
      } catch (reason) {
        showError(reason);
      }
    }
    if (form.hasAttribute('data-metadata')) {
      try {
        const mode = displayText(data.get('mode'));
        const typing: InformationTyping =
          mode === 'clear'
            ? { mode: 'clear' }
            : mode === 'set'
              ? {
                  mode: 'set',
                  version: displayText(data.get('version')),
                  metadata: metadataObject(displayText(data.get('metadata'))),
                }
              : {
                  mode: 'inherit',
                  ...(selectedContent?.contentType
                    ? {
                        metadata: metadataObject(
                          displayText(data.get('metadata')),
                        ),
                      }
                    : {}),
                };
        pendingMetadataEdit = typing;
        background(
          mutate('content.revise', {
            view: 'content-types-v1',
            expectedRevision: selected,
            typing,
          }),
        );
      } catch (reason) {
        showError(reason);
      }
    }
    if (form.hasAttribute('data-process')) {
      const stages = data
        .getAll('stages')
        .filter(
          (stage): stage is InformationProcessingStage =>
            typeof stage === 'string' &&
            [
              'extract',
              'derive',
              'embed',
              'summarise',
              'summary_embed',
              'autoTag',
              'tagGroups',
            ].includes(stage),
        );
      background(mutate('retry', { revision: selected, stages }));
    }
    if (form.hasAttribute('data-report')) {
      const ids = (name: string) =>
        displayText(data.get(name) ?? '')
          .split(',')
          .map((id) => id.trim())
          .filter(Boolean);
      const feedback = displayText(data.get('feedback') ?? '').trim();
      let typing: InformationTyping | undefined;
      try {
        typing = authorTyping(data);
      } catch (reason) {
        showError(reason);
        return;
      }
      background(
        mutate('record.report', {
          name: displayText(data.get('name')),
          text: displayText(data.get('text')),
          inputs: ids('inputs'),
          evidence: ids('evidence'),
          ...(feedback ? { feedback } : {}),
          ...(typing
            ? { typing, ...(feedback ? { expectedRevision: feedback } : {}) }
            : {}),
        }),
      );
    }
    if (form.hasAttribute('data-add')) {
      const name = displayText(data.get('name') ?? ''),
        url = displayText(data.get('url') ?? '').trim(),
        text = displayText(data.get('text') ?? '');
      if (!url && !text.trim()) {
        showError('Enter source text or a URL.');
        return;
      }
      let typing: InformationTyping | undefined;
      try {
        typing = authorTyping(data);
      } catch (reason) {
        showError(reason);
        return;
      }
      if (url && typing) {
        showError(
          'Import the URL first, then assign metadata on its retained version.',
        );
        return;
      }
      background(
        url
          ? mutate('acquire', { name, url })
          : mutate('upload', { name, text, ...(typing ? { typing } : {}) }),
      );
    }
    if (form.hasAttribute('data-link'))
      background(
        mutate(
          event.submitter?.getAttribute('value') === 'unlink'
            ? 'unlink'
            : 'link',
          {
            revision: selected,
            collectionProject: displayText(data.get('project')),
          },
        ),
      );
    if (form.hasAttribute('data-revise'))
      background(
        mutate('revise', {
          revision: selected,
          text: displayText(data.get('text')),
          ...(selectedContent?.contentType
            ? {
                expectedRevision: selected,
                typing: { mode: 'inherit' } as const,
              }
            : {}),
        }),
      );
    if (form.hasAttribute('data-ask')) {
      if (!sourceCanAsk) return;
      const stamp = epoch,
        id = selected;
      const question = displayText(data.get('question'));
      if (
        asking.has(id) ||
        state().jobs.some(
          (job) =>
            job.source === 'information' &&
            job.revision === id &&
            ['starting', 'running', 'cancelling', 'unknown'].includes(
              job.status,
            ),
        )
      )
        return;
      if (
        state().jobs.some(
          (job) =>
            job.source === 'information' &&
            job.revision === id &&
            job.task === question &&
            job.status !== 'interrupted',
        )
      ) {
        notice.hidden = false;
        notice.textContent =
          'This question has already been submitted. Its answer or progress is shown below. Edit the question to ask something new.';
        return;
      }
      asking.add(id);
      const button = form.querySelector<HTMLButtonElement>('button');
      if (button) button.disabled = true;
      background(
        call('ask', { revision: id, question })
          .then(() => {
            if (stamp !== epoch || selected !== id) return;
            const place = detail.querySelector<HTMLElement>('[data-answer]');
            if (place)
              place.textContent = `Your question was accepted. The answer will appear here.`;
          })
          .catch(reportError)
          .finally(() => {
            asking.delete(id);
            if (stamp === epoch) renderAnswer();
          }),
      );
    }
    if (form.hasAttribute('data-allowance'))
      background(
        mutate('allowance', {
          revision: selected,
          maxModelCalls: Number(data.get('total')),
        }),
      );
    if (form.hasAttribute('data-rebuild'))
      background(
        mutate('rebuild', {
          revision: selected,
          stage: displayText(data.get('stage')),
          ...(selectedContent?.processingPolicy === 'explicit_only'
            ? {
                stages: [
                  displayText(data.get('stage')) as InformationProcessingStage,
                ],
              }
            : {}),
        }),
      );
  });
  function renderAnswer(snapshot = state()) {
    if (!active || !selected || acquisition) return;
    const job = snapshot.jobs
      .filter(
        (row) => row.source === 'information' && row.revision === selected,
      )
      .at(-1);
    const place = detail.querySelector<HTMLElement>('[data-answer]');
    if (place && job) {
      const html = `<p>${escape(job.status === 'finished' ? 'Answer' : job.status === 'running' ? 'Finding an answer…' : job.status)}</p><div class="markdown-body">${markdownHtml(job.text || job.detail || 'Waiting for the source answer.')}</div>`;
      if (place.innerHTML !== html) place.innerHTML = html;
    }
    const button =
      question.querySelector<HTMLButtonElement>('[data-ask] button');
    if (button)
      button.disabled =
        !sourceCanAsk ||
        asking.has(selected) ||
        (!!job &&
          ['starting', 'running', 'cancelling', 'unknown'].includes(
            job.status,
          ));
  }
  const facetSurfaces = () => [
    dialog.querySelector<HTMLElement>('[data-facet-choices]')!,
    dialog.querySelector<HTMLElement>('[data-tag-graph]')!,
    dialog.querySelector<HTMLElement>('[data-facet-total]')!,
  ];
  function revealFacets() {
    for (const surface of facetSurfaces()) surface.style.visibility = '';
  }
  function revealReadSurfaces() {
    for (const surface of [list, detail, question, authoredControls])
      surface.style.visibility = '';
  }
  function clearProtected() {
    list.replaceChildren();
    detail.replaceChildren();
    question.replaceChildren();
    for (const surface of facetSurfaces()) surface.replaceChildren();
    selectedContent = undefined;
    pendingMetadataEdit = undefined;
    sourceWindow = undefined;
    cachedText = undefined;
    detailSignature = '';
    sourceCanAsk = false;
    capabilityNote.hidden = true;
    authoredControls.hidden = true;
    authoredControls
      .querySelector<HTMLElement>('[data-type-versions]')!
      .replaceChildren();
    authoredControls.querySelector<HTMLSelectElement>(
      '[data-type-filter]',
    )!.innerHTML =
      '<option value="">All document types</option><option value="untyped">Untyped</option>';
    for (const fieldset of dialog.querySelectorAll<HTMLElement>(
      '[data-typed-author]',
    )) {
      fieldset.hidden = true;
      fieldset.querySelector<HTMLSelectElement>('select')!.innerHTML =
        '<option value="">Untyped</option>';
    }
    availableTypes = [];
    authored = undefined;
    authoredIdentity = typeIdentity = '';
  }
  const refreshes = new InformationRefresh(
    {
      schedule(delay, action) {
        const timer = setTimeout(action, delay);
        return () => clearTimeout(timer);
      },
    },
    (contextChanged) => {
      epoch++;
      inspectRevision++;
      collectionReads.invalidate();
      typeIdentity = '';
      navigation?.reset();
      portable?.reset();
      navigationIdentity = portableIdentity = '';
      // A hint carries no authority or content. Preserve current drafts in hidden
      // DOM only until fresh scoped list/status reads authorize their disclosure.
      if (contextChanged) {
        clearProtected();
        selected = '';
        authoredIdentity = navigationIdentity = portableIdentity = '';
      }
      for (const surface of [
        list,
        detail,
        question,
        authoredControls,
        ...facetSurfaces(),
      ])
        surface.style.visibility = 'hidden';
    },
    () => background(reload()),
  );
  function observeRefresh(snapshot = state()) {
    refreshes.observe(
      active && snapshot.connected && snapshot.mode === 'live'
        ? JSON.stringify([connectionContext(), scope()])
        : undefined,
      snapshot.informationRevision ?? 0,
    );
  }
  const unsubscribe = api.subscribe((snapshot) => {
    observeRefresh(snapshot);
    renderAnswer(snapshot);
  });
  const close = () => {
    active = false;
    refreshes.stop();
    collectionReads.invalidate();
    facetStatus.hidden = true;
    epoch++;
    inspectRevision++;
    selected = '';
    detailSignature = '';
    cachedText = undefined;
    sourceCanAsk = false;
    sourceWindow = undefined;
    dialog.hidden = true;
    if (options.filterHost) options.filterHost.hidden = true;
    addDialog.close();
    detail.innerHTML = emptyDetail;
    question.hidden = true;
    question.innerHTML = '';
  };
  return {
    open(revision?: string, chapter = MANUAL_INDEX) {
      active = true;
      dialog.hidden = false;
      if (options.filterHost) options.filterHost.hidden = false;
      const project = activeProject();
      select.innerHTML =
        `<option value="personal">${options.kind === 'report' ? 'My + generally available' : 'My collection + generally available'}</option><option value="global_read">Available to everyone</option>` +
        state()
          .projects.map(
            (row) =>
              `<option value="project:${escape(row.name)}">${escape(row.name)}</option>`,
          )
          .join('');
      select.value = options.manual
        ? 'global_read'
        : project
          ? `project:${project}`
          : 'personal';
      question.hidden = true;
      question.innerHTML = '';
      navigation?.reset();
      portable?.reset();
      portableIdentity = '';
      navigationIdentity = '';
      epoch++;
      selected = '';
      offset = 0;
      detailSignature = '';
      cachedText = undefined;
      sourceCanAsk = false;
      error.hidden = true;
      collectionNote();
      detail.innerHTML = emptyDetail;
      observeRefresh();
      if (!state().connected || state().mode !== 'live') {
        detail.innerHTML = '';
        list.textContent = `Connect to your server in the main window to read ${options.manual ? 'the manual' : options.kind === 'report' ? 'reports' : 'sources'}.`;
        revealReadSurfaces();
        return;
      }
      if (options.manual) {
        const stamp = epoch;
        filters = {};
        view.value = 'list';
        detail.innerHTML = '<p role="status">Opening the manual…</p>';
        // Resolve the stable supplied name/tag afresh; updates may have a new revision UUID.
        void (async () => {
          const result = await call('list', {
            kind: 'source',
            filter: { tags: [MANUAL_TAG, manualChapterTag(chapter)] },
            offset: 0,
            limit: 2,
          });
          if (stamp !== epoch || !active) return;
          if (!isList(result))
            throw new Error('The manual catalogue is unavailable.');
          const id = manualRevision(result, chapter);
          if (id) await inspect(id, false, stamp);
          else
            detail.innerHTML =
              '<div class="information-empty"><h2>Manual unavailable</h2><p>Ask your server administrator to install or update the Plowshare manual.</p></div>';
        })().catch((reason: unknown) => {
          if (stamp === epoch && active) {
            detail.innerHTML = '';
            showError(reason);
          }
        });
      } else if (revision) {
        const stamp = epoch;
        void inspect(revision, false, stamp).catch((reason: unknown) => {
          if (stamp === epoch && active) showError(reason);
        });
      }
      refreshes.refreshNow();
    },
    refresh() {
      if (active) refreshes.refreshNow();
    },
    close,
    destroy() {
      close();
      sourceResize.destroy();
      unsubscribe();
      refreshes.stop();
      dialog.replaceChildren();
    },
  };
}
