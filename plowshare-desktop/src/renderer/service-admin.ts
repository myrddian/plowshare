import type { DesktopState, Reply, Request, ServerAdminOperation } from '../shared.ts';
import type { ServiceAccount, ServiceToken, ServiceCredential, ServiceScope, ProjectAccess } from 'plowshare-client-ts/operations/conversation-replies';

/** Machine credentials are revealed once in this dialog and never placed in application state. */
export function installServiceAdmin(request: (value: Request) => Promise<Reply>) {
  const dialog = document.createElement('dialog');
  dialog.id = 'service-admin-dialog'; dialog.className = 'server-admin-dialog';
  dialog.setAttribute('aria-labelledby', 'service-admin-title');
  dialog.innerHTML = `<header><h2 id="service-admin-title">Service accounts</h2><button type="button" data-close>Close</button></header>
    <p>Plowshare identities for integrations and pipelines. They have no password login or Personal space.</p><p data-error role="alert" hidden></p>
    <button type="button" data-refresh>Refresh</button>
    <form data-create><label>Account name <input name="handle" required maxlength="64" pattern="[A-Za-z0-9][A-Za-z0-9_.-]{0,63}" autocomplete="off"></label><button>Create service account</button></form>
    <div data-accounts></div><section data-account hidden><h3 data-name></h3><p data-status></p><button type="button" data-enabled></button>
    <h4>Project scopes</h4><p>Grant the account access, then choose the token's permission ceiling. Existing account grants are changed only by Grant access.</p>
    <label>Project <select data-project aria-label="Service project"></select></label><label>Role <select data-role aria-label="Service role"><option>VIEWER</option><option selected>CONTRIBUTOR</option><option>MANAGER</option></select></label>
    <button type="button" data-grant>Grant access</button><button type="button" data-scope-add>Add token scope</button><div data-scopes></div>
    <form data-issue><label>Token name <input name="name" required maxlength="64" autocomplete="off"></label><label>Expires in days <input name="days" type="number" min="1" max="365" value="30" required></label><button>Issue token</button></form>
    <section data-credential hidden><h4>New credential</h4><p>Save this credential now. It is not available from token listings. Rotation invalidates its previous value.</p><input data-secret readonly aria-label="Service credential" autocomplete="off"><button type="button" data-dismiss>Dismiss credential</button></section>
    <h4>Tokens</h4><div data-tokens></div></section>`;
  document.body.append(dialog);
  const get = <T extends HTMLElement>(selector: string) => dialog.querySelector<T>(selector)!;
  let state: DesktopState | undefined, selected = '', generation = 0, busy = false;
  let accounts: readonly ServiceAccount[] = [], scopes: ServiceScope[] = [];
  const clear = () => { get<HTMLInputElement>('[data-secret]').value = ''; get('[data-credential]').hidden = true; };
  const error = (reason?: unknown) => { get('[data-error]').hidden = !reason; get('[data-error]').textContent = reason instanceof Error ? reason.message : String(reason ?? ''); };
  const call = async <T>(operation: ServerAdminOperation, payload: Record<string, unknown> = {}) => {
    const turn = generation; const reply = await request({action:'server-admin',operation,payload});
    if (turn !== generation || !dialog.open || !state?.connected || !state.serverAdmin) throw new Error('Administration connection changed.');
    return reply.administration as T;
  };
  function controls() {
    dialog.querySelectorAll<HTMLButtonElement|HTMLInputElement|HTMLSelectElement>('button,input,select').forEach(node => { node.disabled = busy && !node.matches('[data-close]'); });
    get<HTMLButtonElement>('[data-issue] button').disabled = busy || !scopes.length || !accounts.find(row => row.handle === selected)?.enabled;
    for (const key of ['[data-grant]','[data-scope-add]']) get<HTMLButtonElement>(key).disabled = busy || !get<HTMLSelectElement>('[data-project]').value;
  }
  const run = async (work: () => Promise<void>) => {
    if (busy) return; busy = true; error(); controls();
    try { await work(); } catch (reason) { if (dialog.open) error(reason); }
    finally { busy = false; controls(); }
  };
  function reveal(value: ServiceCredential) { clear(); get<HTMLInputElement>('[data-secret]').value = value.credential; get('[data-credential]').hidden = false; }
  function renderScopes() {
    const parent = get('[data-scopes]'); parent.replaceChildren();
    scopes.forEach(scope => {
      const row = document.createElement('p'); row.textContent = `${scope.project} · ${scope.role.toLowerCase()} `;
      const remove = document.createElement('button'); remove.type = 'button'; remove.textContent = 'Remove scope';
      remove.addEventListener('click',() => { scopes = scopes.filter(value => value !== scope); renderScopes(); controls(); }); row.append(remove); parent.append(row);
    });
  }
  async function tokens() {
    const parent = get('[data-tokens]'); parent.replaceChildren(); if (!selected) return;
    const rows = await call<readonly ServiceToken[]>('admin.service.tokens',{handle:selected});
    if (!rows.length) parent.textContent = 'No tokens issued.';
    for (const token of rows) {
      const section = document.createElement('section'); section.dataset.token = token.id;
      const summary = document.createElement('p'); summary.textContent = `${token.name} · ${token.revokedAt ? 'Revoked' : new Date(token.expiresAt).getTime() <= Date.now() ? 'Expired' : 'Active'} · Expires ${new Date(token.expiresAt).toLocaleString()} · ${token.id}`;
      const limits = document.createElement('p'); limits.textContent = token.scopes.map(scope => `${scope.project}: ${scope.role.toLowerCase()}`).join(', ');
      section.append(summary,limits);
      for (const action of ['rotate','revoke'] as const) {
        const button = document.createElement('button'); button.type = 'button'; button.textContent = action === 'rotate' ? 'Rotate token' : 'Revoke token'; button.dataset[action] = token.id;
        button.addEventListener('click',() => { clear(); void run(async () => {
          if (action === 'rotate') { const result = await call<ServiceCredential>('admin.service.token.rotate',{handle:selected,id:token.id,expiresInDays:Number(get<HTMLInputElement>('[name="days"]').value)}); reveal(result); await tokens(); }
          else { await call('admin.service.token.revoke',{handle:selected,id:token.id}); await tokens(); }
        }); }); section.append(button);
      }
      parent.append(section);
    }
  }
  async function refresh() {
    accounts = await call<readonly ServiceAccount[]>('admin.service.accounts');
    if (!accounts.some(row => row.handle === selected)) selected = accounts[0]?.handle ?? '';
    const parent = get('[data-accounts]'); parent.replaceChildren();
    for (const account of accounts) {
      const button = document.createElement('button'); button.type = 'button'; button.dataset.serviceHandle = account.handle; button.textContent = account.handle;
      button.addEventListener('click',() => { clear(); void run(async () => { selected = account.handle; scopes=[]; renderScopes(); await refresh(); }); }); parent.append(button);
    }
    const account = accounts.find(row => row.handle === selected); get('[data-account]').hidden = !account;
    if (account) { get('[data-name]').textContent = account.handle; get('[data-status]').textContent = account.enabled ? 'Enabled' : 'Disabled'; get('[data-enabled]').textContent = account.enabled ? 'Disable service account' : 'Enable service account'; }
    await tokens();
  }
  get('[data-close]').addEventListener('click',() => dialog.close());
  dialog.addEventListener('close',() => { generation++; clear(); accounts=[]; selected=''; scopes=[]; renderScopes(); get('[data-accounts]').replaceChildren(); get('[data-tokens]').replaceChildren(); get('[data-account]').hidden=true; get<HTMLFormElement>('[data-issue]').reset(); get<HTMLFormElement>('[data-create]').reset(); error(); });
  get('[data-refresh]').addEventListener('click',() => { clear(); void run(refresh); });
  get('[data-dismiss]').addEventListener('click',clear);
  get<HTMLFormElement>('[data-create]').addEventListener('submit',event => { event.preventDefault(); clear(); const form = get<HTMLFormElement>('[data-create]'); const handle = String(new FormData(form).get('handle') ?? '').trim(); void run(async () => { await call('admin.service.account.create',{handle}); selected=handle; scopes=[]; renderScopes(); form.reset(); await refresh(); }); });
  get('[data-enabled]').addEventListener('click',() => { clear(); void run(async () => { await call('admin.service.account.update',{handle:selected,enabled:!accounts.find(row => row.handle === selected)!.enabled}); await refresh(); }); });
  const scope = (): ServiceScope => ({project:get<HTMLSelectElement>('[data-project]').value,role:get<HTMLSelectElement>('[data-role]').value as ServiceScope['role']});
  get('[data-scope-add]').addEventListener('click',() => { const value = scope(); scopes = [...scopes.filter(row => row.project !== value.project),value]; renderScopes(); controls(); });
  get('[data-grant]').addEventListener('click',() => { clear(); void run(async () => {
    const value = scope(), turn = generation, handle = selected;
    const reply = await request({action:'project-access',operation:'project.access',project:value.project});
    if (turn !== generation || !dialog.open) return;
    const access = reply.administration as ProjectAccess;
    await request({action:'project-access',operation:access.members.some(row => row.handle === handle) ? 'project.member.role' : 'project.member.add',project:value.project,handle,role:value.role});
  }); });
  get<HTMLFormElement>('[data-issue]').addEventListener('submit',event => { event.preventDefault(); clear(); const data = new FormData(get<HTMLFormElement>('[data-issue]')); void run(async () => {
    const result = await call<ServiceCredential>('admin.service.token.create',{handle:selected,name:String(data.get('name') ?? '').trim(),expiresInDays:Number(data.get('days')),scopes:[...scopes]}); reveal(result); await tokens();
  }); });
  return {
    open() {
      if (!state?.connected || !state.serverAdmin) return;
      const parent = get<HTMLSelectElement>('[data-project]'); parent.replaceChildren();
      for (const project of state.projects.filter(row => row.kind !== 'personal' && !/^(?:personal:|client:)/.test(row.name))) {
        const option = document.createElement('option'); option.value = project.name; option.textContent = project.displayName ?? project.name; parent.append(option);
      }
      clear(); dialog.showModal(); void run(refresh);
    },
    close() { if (dialog.open) dialog.close(); clear(); },
    update(next: DesktopState) {
      if (state && (state.base !== next.base || state.handle !== next.handle || !next.connected || !next.serverAdmin)) { generation++; if (dialog.open) dialog.close(); clear(); }
      state = next;
    },
  };
}
