import type { DesktopApi } from '../shared.ts';
import type {
  InformationScope,
  InformationOperation,
  InformationInput,
} from 'plowshare-client-ts/operations/information';
import { decodeInformationCall } from 'plowshare-client-ts/operations/information';
import { decodeReply } from 'plowshare-client-ts/operations/schema';
import type { Replies } from 'plowshare-client-ts/operations/replies';
import type { InformationFilter } from 'plowshare-client-ts/operations/information-payloads';
import type {
  CurrentDocument,
  DocumentConnection,
  NavigationCapabilities,
  NavigationStep,
  ReferencePolicySettings,
  ReferencePolicyTarget,
  ReferencePolicyView,
  SavedNavigationView,
} from 'plowshare-client-ts/operations/information-navigation';
import { escapeHtml as escape } from './markdown.ts';

/** FormData can contain files; text controls must never silently stringify one. */
function formText(data: FormData, name: string): string {
  const value = data.get(name);
  if (typeof value !== 'string') throw new Error(`Invalid ${name} field`);
  return value;
}
/** Server-owned navigation. Local state is a selection/draft, never a permission or result cache. */
export function installDocumentNavigation(
  api: DesktopApi,
  host: HTMLElement,
  selection: () => InformationScope,
  identity: () => string,
  actor: () => string,
  administrator: () => boolean,
  types: () => readonly { id: string; label: string }[],
) {
  const panel = document.createElement('details');
  panel.hidden = true;
  panel.className = 'information-navigation';
  panel.innerHTML = `<summary>Document navigation</summary><p>Browse current documents, follow explicit relationships, or narrow by tags and type.</p><div data-nav-toolbar><button data-nav-root>Browse selected collection</button><button data-nav-refresh>Refresh navigation</button><label>Saved views<select data-nav-views><option value="">Choose a saved view</option></select></label><form data-nav-save-form><label>Saved path name<input name="label" required maxlength="120"></label><button data-nav-save>Save this path</button></form><button data-nav-platform hidden>Platform policy</button><button data-nav-delete hidden>Delete saved view</button></div><nav data-nav-crumbs aria-label="Document path"></nav><p data-nav-error role="alert" hidden></p><p data-nav-notice role="status"></p><form data-nav-filter><label>Search documents<input name="search" type="search" maxlength="512"></label><label>Tag<select name="tag"><option value="">All tags</option></select></label><label>Type<select name="type"><option value="">All types</option></select></label><button>Narrow selection</button></form><div data-nav-results></div><div><button data-nav-prev>Previous documents</button><button data-nav-next>More documents</button></div><section data-nav-document></section><section data-nav-policy></section>`;
  const anchor = host.querySelector('[data-faceting]');
  if (anchor) anchor.before(panel);
  else host.append(panel);
  const workspace = document.createElement('div');
  workspace.className = 'navigation-workspace';
  const listPane = document.createElement('section');
  listPane.className = 'navigation-documents';
  const results = panel.querySelector<HTMLElement>('[data-nav-results]');
  const docPane = panel.querySelector<HTMLElement>('[data-nav-document]');
  const policyPane = panel.querySelector<HTMLElement>('[data-nav-policy]');
  const pager = results?.nextElementSibling;
  if (!results || !docPane || !policyPane || !pager)
    throw new Error('Navigation reader unavailable');
  listPane.append(results, pager);
  const reader = document.createElement('section');
  reader.className = 'navigation-reader';
  reader.append(docPane, policyPane);
  workspace.append(listPane, reader);
  panel.append(workspace);
  let capabilities: NavigationCapabilities | undefined;
  let currentIdentity = '',
    steps: readonly NavigationStep[] = [],
    docs: readonly CurrentDocument[] = [],
    connections: readonly DocumentConnection[] = [],
    saved: readonly SavedNavigationView[] = [];
  let activeScope: InformationScope = selection();
  let selected: CurrentDocument | undefined,
    policy: ReferencePolicyView | undefined,
    savedView: SavedNavigationView | undefined;
  let page = 0,
    sequence = 0,
    busy = false,
    openOffset = 0;
  const element = <T extends HTMLElement>(selector: string) => {
    const value = panel.querySelector<T>(selector);
    if (!value) throw new Error('Navigation control unavailable');
    return value;
  };
  const run = (action: () => Promise<void>) => {
    if (busy) return;
    busy = true;
    element('[data-nav-error]').hidden = true;
    action()
      .catch((error: unknown) => {
        element('[data-nav-error]').hidden = false;
        element('[data-nav-error]').textContent =
          error instanceof Error ? error.message : 'Document navigation failed';
      })
      .finally(() => {
        busy = false;
      });
  };
  async function call<K extends InformationOperation>(
    scope: InformationScope,
    operation: K,
    payload: InformationInput<K>,
  ): Promise<Replies[`information.${K}`]> {
    const checked = decodeInformationCall(
      operation,
      { ...payload, view: 'content-types-v1' },
      scope,
    );
    const result = await api.request({
      action: 'information',
      ...checked,
      scope,
    });
    return decodeReply(`information.${operation}`, result.information);
  }
  function scope(): InformationScope {
    const first = steps[0];
    return first?.kind === 'scope' ? first.scope : selection();
  }
  /** Retain the UUID across uncertain delivery/restarts. Only an explicit repeat submits again. */
  async function mutation<
    K extends
      | 'relationships.create'
      | 'relationships.remove'
      | 'policy.set'
      | 'views.save'
      | 'views.delete',
  >(
    operation: K,
    payload: Omit<InformationInput<K>, 'requestId'>,
  ): Promise<Replies[`information.${K}`]> {
    const actionScope = selected?.scope ?? scope();
    const key =
      'plowshare.navigation.request:' +
      JSON.stringify([identity(), actionScope, operation, payload]);
    let requestId = localStorage.getItem(key);
    if (!requestId) {
      requestId = crypto.randomUUID();
      localStorage.setItem(key, requestId);
    }
    // The generated boundary decoder pairs the complete operation and input before dispatch.
    const checked = decodeInformationCall(
      operation,
      { ...payload, requestId, view: 'content-types-v1' },
      actionScope,
    );
    const answer = await api.request({
      action: 'information',
      ...checked,
      scope: actionScope,
    });
    const result = decodeReply(`information.${operation}`, answer.information);
    localStorage.removeItem(key);
    return result;
  }
  function clearView() {
    selected = undefined;
    policy = undefined;
    docs = [];
    connections = [];
    saved = [];
    for (const name of [
      '[data-nav-document]',
      '[data-nav-results]',
      '[data-nav-policy]',
      '[data-nav-crumbs]',
      '[data-nav-notice]',
    ])
      element(name).textContent = '';
    element<HTMLSelectElement>('[data-nav-views]').innerHTML =
      '<option value="">Choose a saved view</option>';
    for (const field of panel.querySelectorAll<
      HTMLInputElement | HTMLTextAreaElement
    >('input,textarea'))
      field.value = '';
    for (const name of ['tag', 'type'])
      element<HTMLSelectElement>(`[name="${name}"]`).innerHTML =
        '<option value="">All</option>';
    panel.hidden = true;
  }
  function reset() {
    steps = [{ kind: 'scope', scope: selection() }];
    page = 0;
    selected = undefined;
    savedView = undefined;
    openOffset = 0;
  }
  function criteria(): InformationFilter {
    let filters: InformationFilter = {};
    for (const step of steps)
      if (step.kind === 'filter') filters = { ...filters, ...step.filter };
    return filters;
  }
  function traversal():
    | {
        relatedTo: string;
        relation: string;
        direction: 'outgoing' | 'backlinks';
      }
    | undefined {
    let resource: string | undefined;
    for (let index = 0; index < steps.length; index++) {
      const step = steps[index];
      if (step?.kind === 'document') resource = step.target.resource;
      if (
        step?.kind === 'traverse' &&
        resource &&
        !steps.slice(index + 1).some((s) => s.kind === 'document')
      )
        return {
          relatedTo: resource,
          relation: step.relation,
          direction: step.direction,
        };
    }
    return undefined;
  }
  async function refresh() {
    const token = ++sequence;
    if (steps.length > 32)
      throw new Error(
        'This path exceeds 32 steps. Return to an earlier breadcrumb.',
      );
    const current = identity();
    if (currentIdentity !== current) {
      currentIdentity = current;
      capabilities = undefined;
      reset();
      clearView();
    }
    capabilities = await call(scope(), 'navigation.capabilities', {});
    if (token !== sequence) return;
    panel.hidden = false;
    element('[data-nav-platform]').hidden = !administrator();
    if (!steps.length) reset();
    // Resolve every document selection afresh before rendering a breadcrumb or traversing it.
    const captions = new Map<number, string>();
    let candidate: CurrentDocument | undefined;
    let traversed: Extract<NavigationStep, { kind: 'traverse' }> | undefined;
    activeScope = scope();
    for (const [stepIndex, step] of steps.entries()) {
      if (step.kind === 'traverse') {
        traversed = step;
        continue;
      }
      if (step.kind !== 'document') continue;
      let resolved: CurrentDocument | undefined;
      if (traversed && candidate) {
        const rows = await call(activeScope, 'relationships.list', {
          resource: candidate.resource,
          direction: traversed.direction,
          relation: traversed.relation,
          otherResource: step.target.resource,
          limit: 100,
        });
        const found = rows.connections.find(
          (row) =>
            row.selection.kind === 'readable' &&
            row.selection.document.resource === step.target.resource,
        );
        if (found?.selection.kind === 'readable') {
          resolved = found.selection.document;
          if (step.target.revision) {
            const pinned = await call(resolved.scope, 'documents.get', {
              target: step.target,
            });
            resolved = pinned.kind === 'readable' ? pinned.document : undefined;
          }
        }
      } else {
        const result = await call(activeScope, 'documents.get', {
          target: step.target,
        });
        if (result.kind === 'readable') resolved = result.document;
      }
      if (token !== sequence) return;
      if (!resolved) {
        selected = undefined;
        docs = [];
        connections = [];
        element('[data-nav-results]').textContent =
          'This path is unavailable under current access.';
        element('[data-nav-document]').textContent = '';
        element('[data-nav-policy]').textContent = '';
        element('[data-nav-crumbs]').textContent = '';
        return;
      }
      captions.set(stepIndex, resolved.title);
      candidate = resolved;
      activeScope = resolved.scope;
      traversed = undefined;
    }
    const related = traversal();
    const [found, facets, views] = await Promise.all([
      call(activeScope, 'documents.list', {
        filter: criteria(),
        limit: 20,
        offset: page * 20,
        ...related,
      }),
      call(activeScope, 'documents.facets', { filter: criteria(), ...related }),
      call(scope(), 'views.list', {}),
    ]);
    if (token !== sequence) return;
    docs = found.documents;
    saved = views.views;
    selected = candidate;
    element('[data-nav-crumbs]').innerHTML = steps
      .map(
        (step, index) =>
          `<button data-nav-crumb="${index}">${escape(step.kind === 'scope' ? (step.scope.kind === 'project' ? step.scope.project : step.scope.kind) : step.kind === 'document' ? (captions.get(index) ?? 'Document') : step.kind === 'traverse' ? `${step.direction}: ${step.relation}` : 'Filtered selection')}</button>`,
      )
      .join(' → ');
    element('[data-nav-results]').innerHTML =
      docs
        .map(
          (doc, index) =>
            `<button data-nav-open="${index}"><strong>${escape(doc.title)}</strong><small>${doc.tags.map(escape).join(', ')}</small></button>`,
        )
        .join('') || '<p>No matching current documents.</p>';
    element<HTMLButtonElement>('[data-nav-prev]').disabled = page === 0;
    element<HTMLButtonElement>('[data-nav-next]').disabled = docs.length < 20;
    element('[data-nav-notice]').textContent = facets
      ? `${facets.total} current documents`
      : 'Related current documents';
    element<HTMLSelectElement>('[name="tag"]').innerHTML =
      '<option value="">All tags</option>' +
      (facets?.facets.tags ?? [])
        .map(
          (t) =>
            `<option value="${escape(t.value)}">${escape(t.value)} (${t.count})</option>`,
        )
        .join('');
    element<HTMLSelectElement>('[name="type"]').innerHTML =
      '<option value="">All types</option>' +
      types()
        .map(
          (t) => `<option value="${escape(t.id)}">${escape(t.label)}</option>`,
        )
        .join('');
    element<HTMLSelectElement>('[data-nav-views]').innerHTML =
      '<option value="">Choose a saved view</option>' +
      saved
        .map(
          (v, index) =>
            `<option value="${index}" ${savedView?.id === v.id ? 'selected' : ''}>${escape(v.label)}</option>`,
        )
        .join('');
    element('[data-nav-delete]').hidden =
      !savedView || !capabilities.writesEnabled;
    element<HTMLButtonElement>('[data-nav-save]').disabled =
      !capabilities.writesEnabled;
    if (selected && !related && steps.at(-1)?.kind === 'document')
      await showDocument(selected, token);
    else {
      element('[data-nav-document]').textContent = '';
      await showPolicy(
        scope().kind === 'project'
          ? {
              kind: 'project',
              project: scope().kind === 'project' ? scopeProject() : '',
            }
          : { kind: 'platform' },
        token,
      );
    }
  }
  function scopeProject(): string {
    const current = scope();
    return current.kind === 'project' ? current.project : '';
  }
  async function showDocument(doc: CurrentDocument, token: number) {
    element('[data-nav-document]').textContent = 'Opening document…';
    element('[data-nav-policy]').textContent = '';
    const last = steps.at(-1);
    const pinned =
      last?.kind === 'document' && last.target.revision !== undefined;
    const [outgoing, incoming, text] = await Promise.all([
      pinned
        ? Promise.resolve({ connections: [], limit: 100, offset: 0 })
        : call(doc.scope, 'relationships.list', {
            resource: doc.resource,
            direction: 'outgoing',
            limit: 100,
          }),
      pinned
        ? Promise.resolve({ connections: [], limit: 100, offset: 0 })
        : call(doc.scope, 'relationships.list', {
            resource: doc.resource,
            direction: 'backlinks',
            limit: 100,
          }),
      call(doc.scope, 'read', {
        revision: doc.revision,
        offset: openOffset,
        limit: 8192,
      }),
    ]);
    if (token !== sequence) return;
    connections = [...outgoing.connections, ...incoming.connections];
    const sections = [
      ['Relationships', outgoing.connections, 0],
      ['Backlinks', incoming.connections, outgoing.connections.length],
    ] as const;
    element('[data-nav-document]').innerHTML =
      `<h3>${escape(doc.title)}</h3><p>Current selection: ${escape(doc.sourceName)}</p><pre data-nav-text>${escape(text.text)}</pre><button data-nav-text-prev ${openOffset === 0 ? 'disabled' : ''}>Previous text</button><button data-nav-text-next ${text.text.length < 8192 ? 'disabled' : ''}>More text</button><button data-nav-pin>Pin this version in the path</button>${sections.map(([label, rows, start]) => `<h4>${label}</h4>${rows.map((row, index) => `<div>${row.selection.kind === 'locked' ? `<span>${escape(row.selection.label)} · content locked</span>` : row.selection.kind === 'readable' ? `<button data-nav-follow="${start + index}">${escape(row.relationship.relation)} → ${escape(row.selection.document.title)}</button>` : ''}${label === 'Relationships' && capabilities?.writesEnabled && doc.owner === actor() ? `<button data-nav-remove="${start + index}">Remove relationship</button>` : ''}</div>`).join('') || '<p>None visible.</p>'}`).join('')}
      <form data-nav-traverse><label>Relationship name<input name="relation" required pattern="[a-z][a-z0-9_-]{0,63}" maxlength="64"></label><label>Direction<select name="direction"><option value="outgoing">Outgoing</option><option value="backlinks">Backlinks</option></select></label><button>Browse related documents</button></form>
      ${capabilities?.writesEnabled && doc.owner === actor() ? `<details><summary>Add an explicit relationship</summary><form data-nav-link><label>Target document<select name="target" required><option value="">Choose from the current collection</option>${docs.map((d, index) => `<option value="${index}">${escape(d.title)}</option>`).join('')}</select></label><label>Relationship name<input name="relation" required pattern="[a-z][a-z0-9_-]{0,63}" maxlength="64"></label><label><input type="checkbox" name="pin">Pin the target’s displayed revision</label><button>Add relationship</button></form></details>` : ''}`;
    await showPolicy({ kind: 'document', resource: doc.resource }, token);
  }
  async function showPolicy(target: ReferencePolicyTarget, token: number) {
    policy = undefined;
    element('[data-nav-policy]').textContent = '';
    if (target.kind === 'platform' && !administrator()) return;
    const value = await call(activeScope, 'policy.get', { target });
    if (token !== sequence) return;
    policy = value;
    element('[data-nav-policy]').innerHTML =
      `<details><summary>Reference policy</summary><p>Effective: ${value.effective.ownerOnly ? 'owner access' : 'existing scope access'}; ${value.effective.referenceHidden ? 'references hidden' : 'approved locked references visible'}; ${value.effective.propagate ? 'restrictions propagate' : 'ordinary links do not propagate'}.</p><p>${value.retainedObligations} retained obligations. Removing links does not release them.</p>${value.canManage && capabilities?.writesEnabled ? `<form data-nav-policy-form><fieldset><legend>Defaults</legend>${checks('default', value.settings.defaults)}</fieldset><fieldset><legend>Mandatory constraints</legend>${checks('mandatory', value.settings.mandatory)}</fieldset><label><input name="overrides" type="checkbox" ${value.settings.allowOverrides ? 'checked' : ''}>Allow document overrides</label><label>Approved locked-reference label<input name="label" required maxlength="120" value="${escape(value.settings.referenceLabel)}"></label><label>Reason<textarea name="reason" required maxlength="4096"></textarea></label><button>Apply policy</button><p>Widening an established policy requires a release workflow unavailable in this version.</p></form>` : ''}</details>`;
  }
  function checks(
    prefix: string,
    value: ReferencePolicySettings['defaults'],
  ): string {
    return (['ownerOnly', 'referenceHidden', 'propagate'] as const)
      .map(
        (key) =>
          `<label><input type="checkbox" name="${prefix}-${key}" ${value[key] ? 'checked' : ''}>${key === 'ownerOnly' ? 'Owner only' : key === 'referenceHidden' ? 'Hide references' : 'Propagate restrictions'}</label>`,
      )
      .join('');
  }
  panel.addEventListener('toggle', () => {
    if (panel.open) run(refresh);
  });
  panel.addEventListener('click', (event) => {
    const target =
      event.target instanceof Element
        ? event.target.closest<HTMLElement>('button')
        : null;
    if (!target || busy) return;
    if (target.hasAttribute('data-nav-root'))
      run(async () => {
        reset();
        await refresh();
      });
    else if (target.hasAttribute('data-nav-refresh')) run(refresh);
    else if (
      target.hasAttribute('data-nav-prev') ||
      target.hasAttribute('data-nav-next')
    )
      run(async () => {
        page = Math.max(
          0,
          page + (target.hasAttribute('data-nav-next') ? 1 : -1),
        );
        await refresh();
      });
    else if (target.dataset.navCrumb !== undefined)
      run(async () => {
        steps = steps.slice(0, Number(target.dataset.navCrumb) + 1);
        page = 0;
        await refresh();
      });
    else if (target.dataset.navOpen !== undefined) {
      const doc = docs[Number(target.dataset.navOpen)];
      if (doc)
        run(async () => {
          steps = [
            ...steps,
            { kind: 'document', target: { resource: doc.resource } },
          ];
          openOffset = 0;
          await refresh();
        });
    } else if (target.dataset.navFollow !== undefined) {
      const edge = connections[Number(target.dataset.navFollow)];
      if (edge && edge.selection.kind === 'readable') {
        const doc = edge.selection.document;
        run(async () => {
          steps = [
            ...steps,
            {
              kind: 'traverse',
              relation: edge.relationship.relation,
              direction:
                edge.relationship.source === selected?.resource
                  ? 'outgoing'
                  : 'backlinks',
            },
            {
              kind: 'document',
              target: {
                resource: doc.resource,
                ...(edge.relationship.pinnedRevision &&
                edge.relationship.source === selected?.resource
                  ? { revision: edge.relationship.pinnedRevision }
                  : {}),
              },
            },
          ];
          page = 0;
          openOffset = 0;
          await refresh();
        });
      }
    } else if (target.hasAttribute('data-nav-pin') && selected) {
      const doc = selected;
      run(async () => {
        steps = steps.map((s, index) =>
          index === steps.length - 1 && s.kind === 'document'
            ? {
                kind: 'document',
                target: { resource: doc.resource, revision: doc.revision },
              }
            : s,
        );
        await refresh();
      });
    } else if (
      target.hasAttribute('data-nav-text-prev') ||
      target.hasAttribute('data-nav-text-next')
    )
      run(async () => {
        openOffset = Math.max(
          0,
          openOffset +
            (target.hasAttribute('data-nav-text-next') ? 8192 : -8192),
        );
        if (selected) await showDocument(selected, sequence);
      });
    else if (target.dataset.navRemove !== undefined) {
      const edge = connections[Number(target.dataset.navRemove)];
      if (edge)
        run(async () => {
          const result = await mutation('relationships.remove', {
            relationship: edge.relationship.id,
            expectedVersion: edge.relationship.version,
          });
          await refresh();
          element('[data-nav-notice]').textContent = result.retainedObligations
            ? 'Relationship removed; access obligations remain.'
            : 'Relationship removed.';
        });
    } else if (target.hasAttribute('data-nav-platform'))
      run(async () => {
        await showPolicy({ kind: 'platform' }, sequence);
      });
    else if (target.hasAttribute('data-nav-delete') && savedView) {
      const saved = savedView;
      run(async () => {
        await mutation('views.delete', {
          id: saved.id,
          expectedVersion: saved.version,
        });
        savedView = undefined;
        await refresh();
      });
    }
  });
  panel.addEventListener('change', (event) => {
    const target = event.target;
    if (
      target instanceof HTMLSelectElement &&
      target.hasAttribute('data-nav-views')
    ) {
      const chosen = saved[Number(target.value)];
      if (target.value !== '' && chosen)
        run(async () => {
          const fresh = await call(scope(), 'views.get', { id: chosen.id });
          steps = fresh.path.steps;
          savedView = fresh;
          page = 0;
          openOffset = 0;
          await refresh();
        });
    }
  });
  panel.addEventListener('submit', (event) => {
    const form = event.target;
    if (!(form instanceof HTMLFormElement)) return;
    event.preventDefault();
    const data = new FormData(form);
    if (form.hasAttribute('data-nav-save-form'))
      run(async () => {
        const label = formText(data, 'label');
        const draftKey =
          'plowshare.navigation.viewDraft:' +
          JSON.stringify([identity(), label, steps]);
        const id =
          savedView?.id ??
          localStorage.getItem(draftKey) ??
          crypto.randomUUID();
        localStorage.setItem(draftKey, id);
        savedView = await mutation('views.save', {
          id,
          expectedVersion: savedView?.version ?? 0,
          label,
          path: { steps },
        });
        localStorage.removeItem(draftKey);
        await refresh();
      });
    else if (form.hasAttribute('data-nav-filter'))
      run(async () => {
        const search = formText(data, 'search'),
          tag = formText(data, 'tag'),
          type = formText(data, 'type');
        const filter: InformationFilter = {
          ...criteria(),
          ...(search ? { search } : {}),
          ...(tag
            ? { tags: [...new Set([...(criteria().tags ?? []), tag])] }
            : {}),
          ...(type ? { contentType: type } : {}),
        };
        steps = [...steps, { kind: 'filter', filter }];
        page = 0;
        await refresh();
      });
    else if (form.hasAttribute('data-nav-traverse') && selected) {
      run(async () => {
        const direction = formText(data, 'direction');
        if (direction !== 'backlinks' && direction !== 'outgoing')
          throw new Error('Invalid relationship direction');
        steps = [
          ...steps,
          {
            kind: 'traverse',
            relation: formText(data, 'relation'),
            direction,
          },
        ];
        page = 0;
        await refresh();
      });
    } else if (form.hasAttribute('data-nav-link') && selected) {
      const source = selected;
      const index = formText(data, 'target');
      if (!/^[0-9]{1,6}$/.test(index))
        throw new Error('Choose a target document');
      const target = docs[Number(index)];
      if (target)
        run(async () => {
          await mutation('relationships.create', {
            source: source.resource,
            expectedSourceRevision: source.revision,
            relation: formText(data, 'relation'),
            target: {
              resource: target.resource,
              ...(data.has('pin') ? { revision: target.revision } : {}),
            },
          });
          await refresh();
        });
    } else if (form.hasAttribute('data-nav-policy-form') && policy) {
      const previous = policy;
      const read = (prefix: string) => ({
        ownerOnly: data.has(prefix + '-ownerOnly'),
        referenceHidden: data.has(prefix + '-referenceHidden'),
        propagate: data.has(prefix + '-propagate'),
      });
      run(async () => {
        await mutation('policy.set', {
          target: previous.target,
          expectedVersion: previous.version,
          settings: {
            defaults: read('default'),
            mandatory: read('mandatory'),
            allowOverrides: data.has('overrides'),
            referenceLabel: formText(data, 'label'),
          },
          reason: formText(data, 'reason'),
        });
        await refresh();
      });
    }
  });
  return {
    async probe() {
      reset();
      await refresh();
    },
    reset() {
      sequence++;
      reset();
      panel.open = false;
      panel.hidden = true;
      clearView();
      currentIdentity = '';
    },
  };
}
