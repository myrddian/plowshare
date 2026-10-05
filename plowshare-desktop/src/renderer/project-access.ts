import { background } from './events.ts';
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

/** A project manager can grant access without receiving server administration privileges. */
export function installProjectAccess(
  request: (request: Request) => Promise<Reply>,
) {
  const dialog = document.createElement('dialog');
  dialog.id = 'project-access-dialog';
  dialog.className = 'server-admin-dialog';
  dialog.setAttribute('aria-labelledby', 'project-access-title');
  dialog.innerHTML = `<header><h2 id="project-access-title">Project access</h2><button type="button" data-close>Close</button></header>
    <p data-project></p><p data-role></p><p>Viewer reads project work. Contributor also runs agents and changes work. Manager also manages agents and project membership. Workspace write-path restrictions still apply.</p>
    <p data-error role="alert" hidden></p><button type="button" data-refresh>Refresh</button><div data-members></div>
    <form data-add hidden><h3>Add account</h3><label>Account name<input name="handle" required autocomplete="off"></label><label>Role<select name="role"><option value="VIEWER">Viewer</option><option value="CONTRIBUTOR" selected>Contributor</option><option value="MANAGER">Manager</option></select></label><button>Add member</button></form>
    <section data-history hidden><h3>Access history</h3><div data-changes></div></section>`;
  document.body.append(dialog);
  const get = <T extends HTMLElement>(selector: string) =>
    dialog.querySelector<T>(selector)!;
  let project = '',
    identity = '',
    connected = false,
    generation = 0,
    busy = false;
  const error = (reason?: unknown) => {
    get('[data-error]').hidden = !reason;
    get('[data-error]').textContent =
      reason instanceof Error ? reason.message : displayText(reason ?? '');
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
  async function refresh() {
    const access = await call('project.access');
    const editable =
      access.role === 'MANAGER' &&
      !project.startsWith('personal:') &&
      !project.startsWith('client:');
    get('[data-project]').textContent = access.project;
    get('[data-role]').textContent = `Your role: ${access.role.toLowerCase()}`;
    get('[data-add]').hidden = !editable;
    const parent = get('[data-members]');
    parent.replaceChildren();
    for (const grant of access.members) {
      const row = document.createElement('div');
      row.className = 'admin-actions';
      const label = document.createElement('span');
      label.textContent = grant.handle;
      row.append(label);
      if (editable) {
        const select = document.createElement('select');
        select.setAttribute('aria-label', `Role for ${grant.handle}`);
        for (const value of ['VIEWER', 'CONTRIBUTOR', 'MANAGER'] as const) {
          const option = document.createElement('option');
          option.value = value;
          option.textContent = value.toLowerCase();
          select.append(option);
        }
        select.value = grant.role;
        const save = document.createElement('button');
        save.type = 'button';
        save.textContent = 'Save role';
        save.dataset.handle = grant.handle;
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
        row.append(select, save, remove);
      } else {
        const role = document.createElement('span');
        role.textContent = grant.role.toLowerCase();
        row.append(role);
      }
      parent.append(row);
    }
    get('[data-history]').hidden = access.role !== 'MANAGER';
    const history = get('[data-changes]');
    history.replaceChildren();
    for (const change of access.history) {
      const row = document.createElement('p');
      row.textContent = `${change.occurredAt} · ${change.actor} · ${change.action} · ${change.target}${change.role ? ' · ' + change.role.toLowerCase() : ''}`;
      history.append(row);
    }
  }
  async function run(work: () => Promise<void>) {
    if (busy) return;
    const current = generation;
    busy = true;
    error();
    get('[data-add]')
      .querySelectorAll<HTMLButtonElement>('button')
      .forEach((button) => (button.disabled = true));
    try {
      await work();
    } catch (reason) {
      if (current === generation) error(reason);
    } finally {
      if (current === generation) {
        busy = false;
        get('[data-add]')
          .querySelectorAll<HTMLButtonElement>('button')
          .forEach((button) => (button.disabled = false));
      }
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
  get('[data-close]').addEventListener('click', () => dialog.close());
  dialog.addEventListener('close', () => {
    generation++;
    busy = false;
    project = '';
    get('[data-members]').replaceChildren();
    get('[data-changes]').replaceChildren();
  });
  return {
    open(name: string) {
      if (!connected) return;
      generation++;
      busy = false;
      project = name;
      error();
      get('[data-project]').textContent = name;
      get('[data-role]').textContent = 'Loading access…';
      get('[data-add]').hidden = true;
      get('[data-history]').hidden = true;
      get('[data-members]').replaceChildren();
      get('[data-changes]').replaceChildren();
      if (!dialog.open) dialog.showModal();
      background(run(refresh));
    },
    update(state: DesktopState) {
      const next = `${state.base}:${state.handle}`;
      if (identity !== next || connected !== state.connected) {
        generation++;
        if (dialog.open) dialog.close();
      }
      identity = next;
      connected = state.connected;
    },
  };
}
