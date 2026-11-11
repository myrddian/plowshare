import { background } from './events.ts';
import { icon } from './icons.ts';
import { displayText } from 'plowshare-client-ts/binding/values';
import type {
  DesktopState,
  Reply,
  Request,
  ProjectAccessOperation,
} from '../shared.ts';
import type {
  ProjectAccess,
  ProjectRole,
} from 'plowshare-client-ts/operations/conversation-replies';

/** A project's membership view, mountable in any host element. */
export interface ProjectAccessView {
  /** Load and show access for this project in the view's host. */
  open(project: string): void;
  /** Stop showing; invalidates in-flight work and clears rows. */
  close(): void;
  /** Feed every state update (identity/connection changes invalidate). */
  update(state: DesktopState): void;
}

const ROLES = ['VIEWER', 'CONTRIBUTOR', 'MANAGER'] as const;

const ACTION_LABELS: Readonly<Record<string, string>> = {
  'member.add': 'added',
  'member.role': 'changed role',
  'member.remove': 'removed',
};

function roleOptions(selected: ProjectRole | null): string {
  return ROLES.map(
    (role) =>
      `<option value="${role}"${role === selected ? ' selected' : ''}>${role.toLowerCase()}</option>`,
  ).join('');
}

/**
 * Mounts the project access component into `host` and returns its controller.
 *
 * Ownership: the component owns `host`'s children. It does not assume a
 * dialog; the root fills the host and scrolls internally. Every server reply
 * is checked against a generation counter so work that was in flight when the
 * view was closed, reopened for another project, or the identity/connection
 * changed can never repaint or mutate the current view. Only one mutation or
 * refresh runs at a time (`busy`). Editing is offered only to managers of
 * shared projects; the server remains the authority for every change.
 */
export function mountProjectAccess(
  host: HTMLElement,
  request: (request: Request) => Promise<Reply>,
): ProjectAccessView {
  const root = document.createElement('div');
  root.className = 'project-access';
  root.innerHTML = `<header class="pa-head">
      <div class="pa-title"><strong data-project></strong><span class="pa-chip" data-role></span></div>
      <button type="button" class="icon-button" data-refresh aria-label="Refresh access" title="Refresh">${icon('refresh')}</button>
    </header>
    <p class="pa-error" data-access-error role="alert" hidden></p>
    <dl class="pa-legend">
      <div><dt>Viewer</dt><dd>reads project work</dd></div>
      <div><dt>Contributor</dt><dd>also runs agents and changes work</dd></div>
      <div><dt>Manager</dt><dd>also manages agents and members</dd></div>
    </dl>
    <p class="pa-note">Workspace write-path restrictions still apply.</p>
    <section class="pa-section">
      <h3>Members</h3>
      <div class="pa-members" data-members></div>
    </section>
    <form class="pa-add" data-add hidden>
      <h3>Add member</h3>
      <div class="pa-add-row">
        <input name="handle" required autocomplete="off" placeholder="Account name" aria-label="Account name">
        <select name="role" aria-label="Role">${roleOptions('CONTRIBUTOR')}</select>
        <button class="pa-primary">Add member</button>
      </div>
    </form>
    <section class="pa-section" data-history hidden>
      <h3>Access history</h3>
      <ul class="pa-changes" data-changes></ul>
    </section>`;
  host.replaceChildren(root);
  const get = <T extends HTMLElement>(selector: string) =>
    root.querySelector<T>(selector)!;
  let project = '',
    identity = '',
    connected = false,
    generation = 0,
    busy = false;

  const error = (reason?: unknown) => {
    get('[data-access-error]').hidden = !reason;
    get('[data-access-error]').textContent =
      reason instanceof Error ? reason.message : displayText(reason ?? '');
  };
  const setBusy = (value: boolean) => {
    busy = value;
    root
      .querySelectorAll<HTMLButtonElement>('[data-add] button')
      .forEach((button) => (button.disabled = value));
    get<HTMLButtonElement>('[data-refresh]').disabled = value;
  };
  const clear = () => {
    get('[data-members]').replaceChildren();
    get('[data-changes]').replaceChildren();
    get('[data-add]').hidden = true;
    get('[data-history]').hidden = true;
  };
  const call = async (
    operation: ProjectAccessOperation,
    handle?: string,
    role?: ProjectRole,
  ) => {
    const current = generation;
    const reply = await request({
      action: 'project-access',
      operation,
      project,
      ...(handle ? { handle } : {}),
      ...(role ? { role } : {}),
    });
    if (current !== generation)
      throw new Error('The connection changed. Reopen project access.');
    return reply.administration as ProjectAccess;
  };

  function memberRow(
    grant: ProjectAccess['members'][number],
    editable: boolean,
  ) {
    const row = document.createElement('div');
    row.className = 'pa-row';
    const label = document.createElement('span');
    label.className = 'pa-handle';
    label.textContent = grant.handle;
    row.append(label);
    if (!editable) {
      const role = document.createElement('span');
      role.className = 'pa-role';
      role.textContent = grant.role.toLowerCase();
      row.append(role);
      return row;
    }
    const select = document.createElement('select');
    select.setAttribute('aria-label', `Role for ${grant.handle}`);
    select.innerHTML = roleOptions(grant.role);
    const save = document.createElement('button');
    save.type = 'button';
    save.textContent = 'Save role';
    save.dataset.handle = grant.handle;
    save.disabled = true;
    // Saving is only meaningful when the choice differs from the stored role.
    select.addEventListener('change', () => {
      save.disabled = busy || select.value === grant.role;
    });
    save.addEventListener('click', () =>
      background(
        run(async () => {
          await call(
            'project.member.role',
            grant.handle,
            select.value as ProjectRole,
          );
          await refresh();
        }),
      ),
    );
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'pa-danger';
    remove.textContent = 'Remove';
    remove.dataset.remove = grant.handle;
    remove.addEventListener('click', () =>
      background(
        run(async () => {
          await call('project.member.remove', grant.handle);
          await refresh();
        }),
      ),
    );
    const actions = document.createElement('span');
    actions.className = 'pa-actions';
    actions.append(save, remove);
    row.append(select, actions);
    return row;
  }

  function changeRow(change: ProjectAccess['history'][number]) {
    const item = document.createElement('li');
    const when = document.createElement('time');
    const date = new Date(change.occurredAt);
    when.dateTime = change.occurredAt;
    when.title = change.occurredAt;
    when.textContent = Number.isNaN(date.getTime())
      ? change.occurredAt
      : date.toLocaleString();
    const what = document.createElement('span');
    what.className = 'pa-what';
    const action = ACTION_LABELS[change.action] ?? change.action;
    what.textContent = `${change.actor} ${action} ${change.target}`;
    item.append(when, what);
    if (change.role) {
      const role = document.createElement('span');
      role.className = 'pa-chip';
      role.textContent = change.role.toLowerCase();
      item.append(role);
    }
    return item;
  }

  async function refresh() {
    const access = await call('project.access');
    const editable =
      access.role === 'MANAGER' &&
      !project.startsWith('personal:') &&
      !project.startsWith('client:');
    get('[data-project]').textContent = access.project;
    get('[data-role]').textContent = access.role.toLowerCase();
    get('[data-add]').hidden = !editable;
    get('[data-members]').replaceChildren(
      ...access.members.map((grant) => memberRow(grant, editable)),
    );
    get('[data-history]').hidden = access.role !== 'MANAGER';
    get('[data-changes]').replaceChildren(...access.history.map(changeRow));
  }
  async function run(work: () => Promise<void>) {
    if (busy) return;
    const current = generation;
    setBusy(true);
    error();
    try {
      await work();
    } catch (reason) {
      if (current === generation) error(reason);
    } finally {
      if (current === generation) setBusy(false);
    }
  }
  get('[data-add]').addEventListener('submit', (event) => {
    event.preventDefault();
    const form = event.currentTarget as HTMLFormElement;
    const data = new FormData(form);
    background(
      run(async () => {
        await call(
          'project.member.add',
          displayText(data.get('handle') ?? '').trim(),
          displayText(data.get('role')) as ProjectRole,
        );
        form.reset();
        await refresh();
      }),
    );
  });
  get('[data-refresh]').addEventListener('click', () =>
    background(run(refresh)),
  );

  const view: ProjectAccessView = {
    open(name: string) {
      if (!connected) return;
      generation++;
      setBusy(false);
      project = name;
      error();
      clear();
      get('[data-project]').textContent = name;
      get('[data-role]').textContent = 'loading';
      background(run(refresh));
    },
    close() {
      generation++;
      setBusy(false);
      project = '';
      error();
      clear();
      get('[data-project]').textContent = '';
      get('[data-role]').textContent = '';
    },
    update(state: DesktopState) {
      const next = `${state.base}:${state.handle}`;
      if (identity !== next || connected !== state.connected) {
        generation++;
        if (project) view.close();
      }
      identity = next;
      connected = state.connected;
    },
  };
  return view;
}

/** A project manager can grant access without receiving server administration privileges. */
export function installProjectAccess(
  request: (request: Request) => Promise<Reply>,
) {
  const dialog = document.createElement('dialog');
  dialog.id = 'project-access-dialog';
  dialog.className = 'server-admin-dialog project-access-dialog';
  dialog.setAttribute('aria-labelledby', 'project-access-title');
  dialog.innerHTML = `<header class="dialog-header"><h2 id="project-access-title">Project access</h2><button type="button" class="icon-button" data-close aria-label="Close" title="Close">${icon('close')}</button></header><div data-access-host></div>`;
  document.body.append(dialog);
  const view = mountProjectAccess(
    dialog.querySelector<HTMLElement>('[data-access-host]')!,
    request,
  );
  dialog
    .querySelector('[data-close]')!
    .addEventListener('click', () => dialog.close());
  dialog.addEventListener('close', () => view.close());
  // The dialog mirrors the view's identity rule: a changed account or
  // connection dismisses it rather than leaving an empty shell open.
  let identity = '',
    connected = false;
  return {
    open(name: string) {
      if (!connected) return;
      view.open(name);
      if (!dialog.open) dialog.showModal();
    },
    update(state: DesktopState) {
      const next = `${state.base}:${state.handle}`;
      if (identity !== next || connected !== state.connected) {
        if (dialog.open) dialog.close();
      }
      identity = next;
      connected = state.connected;
      view.update(state);
    },
  };
}
