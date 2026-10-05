import { checkedTransport } from '../../../sdk/typescript/src/operations/transport.ts';
import { errorMessage } from '../../../sdk/typescript/src/binding/values.ts';
import { background } from '../background.ts';
import { isOneOf } from '../../../sdk/typescript/src/binding/values.ts';
import {
  InformationClient,
  decodeInformationCall,
  INFORMATION_OPERATIONS,
  informationChanged,
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
  for (const kind of ['personal', 'project', 'shared']) {
    const option = document.createElement('option');
    option.value = kind;
    option.textContent = kind;
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
    offset = 0;
  const selection = (): InformationScope =>
    scope.value === 'project'
      ? { kind: 'project', project: project.value }
      : scope.value === 'personal' || scope.value === 'shared'
        ? { kind: scope.value }
        : (() => {
            throw new Error('Unknown information scope.');
          })();
  const stream: EventStream = options.openStream({
    session: options.session,
    onEvent: (value) => {
      if (informationChanged(value)) background(load());
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
  async function inspect(id: string) {
    const stamp = ++epoch;
    detail.replaceChildren();
    try {
      const status = await call(
        'status',
        view.value === 'acquisitions' ? { acquisition: id } : { revision: id },
      );
      if (closed || stamp !== epoch) return;
      detail.append(el('pre', '', JSON.stringify(status, null, 2)));
      payload.value = JSON.stringify(
        { revision: id, requestId: crypto.randomUUID() },
        null,
        2,
      );
      if (view.value === 'acquisitions') {
        payload.value = JSON.stringify(
          { acquisition: id, requestId: crypto.randomUUID() },
          null,
          2,
        );
        return;
      }
      const window = await call('read', {
        revision: id,
        offset: 0,
        limit: 8192,
      });
      if (closed || stamp !== epoch) return;
      detail.append(el('pre', '', JSON.stringify(window, null, 2)));
    } catch (reason) {
      if (stamp === epoch) fail(reason);
    }
  }
  async function load() {
    const stamp = ++epoch;
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
      detail.replaceChildren();
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
    } catch (reason) {
      if (stamp === epoch) fail(reason);
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
      epoch++;
      stream.close();
      root.replaceChildren();
    },
  };
}
