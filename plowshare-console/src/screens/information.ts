import { InformationRefresh } from '../../../sdk/typescript/src/operations/information-refresh.ts';
import { decodeServerPush } from '../../../sdk/typescript/src/operations/push.ts';
import { isObject } from '../../../sdk/typescript/src/binding/values.ts';
import { checkedTransport } from '../../../sdk/typescript/src/operations/transport.ts';
import { errorMessage } from '../../../sdk/typescript/src/binding/values.ts';
import { background } from '../background.ts';
import { isOneOf } from '../../../sdk/typescript/src/binding/values.ts';
import {
  InformationClient,
  decodeInformationCall,
  INFORMATION_OPERATIONS,
} from '../../../plowshare-tui/src/logic/information';
import type {
  InformationOperation,
  InformationScope,
  InformationInput,
} from '../../../plowshare-tui/src/logic/information';
import type { EventStream, EventStreamOptions } from '../events';
import { el, button, input, labelled } from './dom';
import type { Screen } from './screen';

/** Retained information uses the tab's existing socket and authoritative scoped reads. */
export function createInformation(options: {
  root: HTMLElement;
  session: string;
  openStream: (options: EventStreamOptions) => EventStream;
  project: string | null;
}): Screen {
  const root = options.root;
  const scope = document.createElement('select');
  for (const kind of ['personal', 'project', 'global_read']) {
    const option = document.createElement('option');
    option.value = kind;
    option.textContent =
      kind === 'global_read' ? 'Available to everyone' : kind;
    scope.append(option);
  }
  const project = input('Project', 'Project name');
  project.value = options.project ?? '';
  scope.value = project.value ? 'project' : 'personal';
  const catalogue = el('div', ''),
    detail = el('div', ''),
    error = el('p', '');
  error.setAttribute('role', 'alert');
  const view = document.createElement('select');
  for (const name of ['list', 'inventory', 'acquisitions']) {
    const option = document.createElement('option');
    option.value = name;
    option.textContent = name;
    view.append(option);
  }
  const previous = button('Previous', 'Previous catalogue page'),
    next = button('Next', 'Next catalogue page');
  const reload = button('refresh', 'Refresh catalogue');
  const operation = document.createElement('select');
  for (const name of INFORMATION_OPERATIONS) {
    const option = document.createElement('option');
    option.value = name;
    option.textContent = name;
    operation.append(option);
  }
  const payload = document.createElement('textarea');
  payload.rows = 8;
  payload.value = '{}';
  payload.setAttribute('aria-label', 'Information request payload');
  const send = button('run operation', 'Run selected information operation');
  const controls = document.createElement('details');
  controls.append(
    el('summary', '', 'Information controls'),
    labelled('Operation', operation),
    labelled('Payload', payload),
    el(
      'p',
      '',
      'Mutations need a stable UUID requestId. Keep the payload unchanged after uncertain delivery. Sharing and finalisation act on the selected concrete revision.',
    ),
    send,
  );
  root.append(
    el('h2', 'screen-title', 'Information'),
    labelled('Scope', scope),
    labelled('Project', project),
    labelled('View', view),
    reload,
    error,
    catalogue,
    previous,
    next,
    detail,
    controls,
  );
  let epoch = 0,
    closed = false,
    offset = 0,
    loading = false,
    inspecting = 0,
    requested = false,
    revision = 0,
    progressRevision = 0,
    refreshRevision = 0,
    connection = 0;
  let selected: string | undefined;
  const selection = (): InformationScope =>
    scope.value === 'project'
      ? { kind: 'project', project: project.value }
      : scope.value === 'personal' || scope.value === 'global_read'
        ? { kind: scope.value }
        : (() => {
            throw new Error('Unknown information scope.');
          })();
  const refreshes = new InformationRefresh(
    {
      schedule(delay, action) {
        const timer = setTimeout(action, delay);
        return () => clearTimeout(timer);
      },
    },
    (contextChanged) => {
      epoch++;
      catalogue.style.visibility = detail.style.visibility = 'hidden';
      if (contextChanged) {
        selected = undefined;
        catalogue.replaceChildren();
        detail.replaceChildren();
      }
    },
    () => background(load()),
  );
  const observe = () =>
    refreshes.observe(
      closed || root.hidden
        ? undefined
        : JSON.stringify([
            options.session,
            connection,
            selection(),
            view.value,
            offset,
          ]),
      revision,
      progressRevision,
      refreshRevision,
    );
  // The shell retains built screens while hiding their host. Hidden readers must not poll, and
  // becoming visible must reconcile before exposing retained protected content again.
  const visibility = new MutationObserver(observe);
  visibility.observe(root, { attributes: true, attributeFilter: ['hidden'] });
  const stream: EventStream = options.openStream({
    session: options.session,
    onStatus: (status) => {
      if (status.state === 'open') {
        connection++;
        observe();
      } else if (status.state === 'closed' || status.state === 'reconnecting') {
        epoch++;
        catalogue.style.visibility = detail.style.visibility = 'hidden';
      }
    },
    onEvent: (value) => {
      if (
        !isObject(value) ||
        (value.kind !== 'information.changed' &&
          value.kind !== 'information.invalidated')
      )
        return;
      try {
        const hint = decodeServerPush(value);
        if (
          'kind' in hint &&
          hint.kind === 'information.invalidated' &&
          'refreshOnly' in hint &&
          hint.refreshOnly === true
        )
          refreshRevision++;
        else if (
          'kind' in hint &&
          hint.kind === 'information.invalidated' &&
          'progressOnly' in hint &&
          hint.progressOnly === true
        )
          progressRevision++;
        else revision++;
      } catch (reason) {
        // A malformed information hint cannot leave protected content visible.
        revision++;
        error.textContent = errorMessage(reason);
      }
      observe();
    },
  });
  const client = () =>
    new InformationClient(checkedTransport(stream), selection());
  const call = <K extends InformationOperation>(
    name: K,
    ...args: Record<string, never> extends InformationInput<K>
      ? [body?: InformationInput<K>]
      : [body: InformationInput<K>]
  ) => client().call<K>(name, ...args);
  const fail = (reason: unknown) => {
    error.textContent =
      reason instanceof Error ? reason.message : errorMessage(reason);
    catalogue.replaceChildren();
    detail.replaceChildren();
  };
  async function inspect(id: string, refresh = false) {
    const stamp = refresh ? epoch : ++epoch;
    selected = id;
    if (!refresh) detail.replaceChildren();
    inspecting++;
    try {
      const status = await call(
        'status',
        view.value === 'acquisitions' ? { acquisition: id } : { revision: id },
      );
      if (closed || stamp !== epoch) return;
      const nodes = [el('pre', '', JSON.stringify(status, null, 2))];
      if (view.value !== 'acquisitions') {
        const window = await call('read', {
          revision: id,
          offset: 0,
          limit: 8192,
        });
        if (closed || stamp !== epoch) return;
        nodes.push(el('pre', '', JSON.stringify(window, null, 2)));
      }
      detail.replaceChildren(...nodes);
      detail.style.visibility = 'visible';
      // Background refresh must not overwrite a retained operation/requestId draft.
      if (!refresh)
        payload.value = JSON.stringify(
          view.value === 'acquisitions'
            ? { acquisition: id, requestId: crypto.randomUUID() }
            : { revision: id, requestId: crypto.randomUUID() },
          null,
          2,
        );
    } catch (reason) {
      if (!closed && stamp === epoch) fail(reason);
    } finally {
      inspecting--;
      if (!loading && !inspecting && requested && !closed) background(load());
    }
  }
  async function load() {
    if (closed || root.hidden) return;
    observe();
    if (loading || inspecting) {
      requested = true;
      return;
    }
    requested = false;
    refreshes.readStarted();
    loading = true;
    const stamp = epoch;
    error.textContent = '';
    try {
      const data =
        view.value === 'list'
          ? await call('list', { offset, limit: 20 })
          : view.value === 'inventory'
            ? await call('inventory', { offset, limit: 20 })
            : view.value === 'acquisitions'
              ? await call('acquisitions', { offset, limit: 20 })
              : (() => {
                  throw new Error('Unknown catalogue view.');
                })();
      if (closed || stamp !== epoch) return;
      catalogue.replaceChildren();
      catalogue.style.visibility = 'visible';
      previous.disabled = offset === 0;
      next.disabled = data.length < 20;
      for (const row of data) {
        const open = button(
          'information-document',
          ('title' in row ? row.title : undefined) ?? row.source_name ?? row.id,
        );
        open.addEventListener('click', () => {
          background(inspect(row.id));
        });
        catalogue.append(open);
      }
      if (!data.length)
        catalogue.append(el('p', '', 'No information in this selection.'));
      if (selected && data.some((row) => row.id === selected))
        await inspect(selected, true);
      else {
        selected = undefined;
        detail.replaceChildren();
        detail.style.visibility = 'visible';
      }
    } catch (reason) {
      if (!closed && stamp === epoch) fail(reason);
    } finally {
      loading = false;
      if (requested && !closed) background(load());
    }
  }
  send.addEventListener('click', () => {
    background(
      (async () => {
        const stamp = ++epoch;
        try {
          const body: unknown = JSON.parse(payload.value);
          if (!isOneOf(operation.value, INFORMATION_OPERATIONS))
            throw new Error('Unknown information operation.');
          const result = await client().invoke(
            decodeInformationCall(operation.value, body, selection()),
          );
          if (closed || stamp !== epoch) return;
          detail.replaceChildren(
            el('pre', '', JSON.stringify(result, null, 2)),
          );
          error.textContent = '';
        } catch (reason) {
          if (stamp === epoch) fail(reason);
        }
      })(),
    );
  });
  reload.addEventListener('click', () => {
    background(load());
  });
  previous.addEventListener('click', () => {
    offset = Math.max(0, offset - 20);
    background(load());
  });
  next.addEventListener('click', () => {
    offset += 20;
    background(load());
  });
  for (const selector of [scope, project, view])
    selector.addEventListener('change', () => {
      offset = 0;
      detail.replaceChildren();
      background(load());
    });
  return {
    element: () => root,
    load,
    destroy() {
      closed = true;
      visibility.disconnect();
      refreshes.stop();
      epoch++;
      stream.close();
      root.replaceChildren();
    },
  };
}
