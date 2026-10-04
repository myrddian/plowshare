import { installPricingAdmin } from './pricing-admin.ts';
import { installServiceAdmin } from './service-admin.ts';
import type { DesktopState, Reply, Request, ServerAdminOperation } from '../shared.ts';
import type { AccountCredential, ServerAccount, ServerSession, AdminAuditPage } from 'plowshare-client-ts/operations/conversation-replies';

/** Transient operator dialog. Passwords exist only in the current response and reveal field. */
export function installServerAdmin(request: (request: Request) => Promise<Reply>) {
  const services = installServiceAdmin(request);
  const pricing = installPricingAdmin(request);
  const opener = document.querySelector<HTMLButtonElement>('#server-admin-open')!;
  const dialog = document.createElement('dialog');
  dialog.id = 'server-admin-dialog'; dialog.className = 'server-admin-dialog';
  dialog.setAttribute('aria-labelledby', 'server-admin-title');
  dialog.innerHTML = `<header><h2 id="server-admin-title">Server administration</h2><button type="button" data-close aria-label="Close administration">Close</button></header>
    <p data-error role="alert" hidden></p>
    <div class="admin-actions"><button type="button" data-refresh>Refresh</button><button type="button" data-services>Service accounts</button><button type="button" data-pricing>Pricing</button></div>
    <h3>Accounts</h3><form data-create><label>Account name <input name="handle" required maxlength="64" pattern="[A-Za-z0-9][A-Za-z0-9_.-]{0,63}" autocomplete="off"></label>
    <label><input name="serverAdmin" type="checkbox">Server administrator</label><button>Create account</button></form>
    <section data-credential hidden><h3>Temporary password</h3><p data-credential-name></p><p>Give this password to the account owner. They must change it before using the server.</p><input data-password readonly aria-label="Temporary password" autocomplete="off"><button type="button" data-hide-password>Dismiss password</button></section>
    <div class="admin-columns"><div data-accounts></div><section data-account hidden><h3 data-account-name></h3><p data-account-status></p>
    <div class="admin-actions"><button type="button" data-enabled></button><button type="button" data-role></button><button type="button" data-reset>Reset password</button><button type="button" data-revoke>Sign out all sessions</button></div>
    <h4>Active login sessions</h4><div data-sessions></div></section></div>
    <h3>Audit history</h3><p>Newest changes first. Account changes and session revocation are retained on the server.</p><div data-audit></div><button type="button" data-older hidden>Load older entries</button>`;
  document.body.append(dialog);
  const get = <T extends HTMLElement>(selector: string) => dialog.querySelector<T>(selector)!;
  let connected = false, handle = '', base = '', generation = 0, busy = false;
  let accounts: readonly ServerAccount[] = [], selected = '', before = 0;
  const error = (reason?: unknown) => { const node = get('[data-error]'); node.hidden = !reason; node.textContent = reason instanceof Error ? reason.message : String(reason ?? ''); };
  const clearPassword = () => { get<HTMLInputElement>('[data-password]').value = ''; get('[data-credential-name]').textContent = ''; get('[data-credential]').hidden = true; };
  const call = async <T>(operation: ServerAdminOperation, payload?: Record<string, unknown>) => {
    const turn = generation;
    const response = await request({ action: 'server-admin', operation, payload });
    if (turn !== generation || !dialog.open || !connected) throw new Error('Administration connection changed.');
    return response.administration as T;
  };
  const run = async (work: () => Promise<void>) => {
    if (busy) return;
    busy = true; error();
    const controls = [...dialog.querySelectorAll<HTMLButtonElement|HTMLInputElement>('button,input')];
    controls.forEach(node => { if (!node.matches('[data-close]')) node.disabled = true; });
    try { await work(); } catch (reason) { if (dialog.open) error(reason); }
    finally { busy = false; controls.forEach(node => { node.disabled = false; }); renderAccount(); }
  };
  function showCredential(value: AccountCredential) {
    clearPassword(); get('[data-credential]').hidden = false;
    get('[data-credential-name]').textContent = value.account.handle;
    get<HTMLInputElement>('[data-password]').value = value.temporaryPassword;
  }
  function renderAccount() {
    const account = accounts.find(row => row.handle === selected);
    get('[data-account]').hidden = !account;
    if (!account) return;
    get('[data-account-name]').textContent = account.handle;
    get('[data-account-status]').textContent = `${account.enabled ? 'Enabled' : 'Disabled'} · ${account.serverAdmin ? 'Server administrator' : 'Regular user'}${account.mustChangePassword ? ' · Password change required' : ''}`;
    get('[data-enabled]').textContent = account.enabled ? 'Disable account' : 'Enable account';
    get('[data-role]').textContent = account.serverAdmin ? 'Remove administrator role' : 'Make administrator';
    get<HTMLButtonElement>('[data-reset]').disabled = busy || account.handle === handle;
    dialog.querySelectorAll<HTMLButtonElement>('[data-account-handle]').forEach(button => button.setAttribute('aria-pressed',String(button.dataset.accountHandle === selected)));
  }
  async function sessions() {
    get('[data-sessions]').replaceChildren();
    if (!selected) return;
    const rows = await call<readonly ServerSession[]>('admin.sessions',{ handle: selected });
    const parent = get('[data-sessions]');
    if (!rows.length) parent.textContent = 'No active login sessions.';
    for (const session of rows) {
      const row = document.createElement('p'); row.textContent = `${session.id} · ${session.restricted ? 'Password change required' : 'Active'} · Expires ${new Date(session.expiresAt).toLocaleString()}`; parent.append(row);
    }
  }
  async function audit(older = false) {
    const page = await call<AdminAuditPage>('admin.audit',{ limit: 25, ...(older ? {before} : {}) });
    const parent = get('[data-audit]'); if (!older) parent.replaceChildren();
    for (const entry of page.entries) {
      const row = document.createElement('p'); row.textContent = `${new Date(entry.occurredAt).toLocaleString()} · ${entry.actor} · ${entry.action} · ${entry.target}${entry.enabled === null ? '' : ` · ${entry.enabled ? 'Enabled' : 'Disabled'}`}${entry.serverAdmin === null ? '' : ` · ${entry.serverAdmin ? 'Administrator' : 'Regular user'}`}`; parent.append(row);
    }
    if (!parent.childNodes.length) parent.textContent = 'No administrator changes recorded yet.';
    before = page.before; get('[data-older]').hidden = before === 0;
  }
  async function refresh() {
    accounts = await call<readonly ServerAccount[]>('admin.accounts');
    if (!accounts.some(row => row.handle === selected)) selected = accounts[0]?.handle ?? '';
    const parent = get('[data-accounts]'); parent.replaceChildren();
    for (const account of accounts) {
      const button = document.createElement('button'); button.type = 'button'; button.dataset.accountHandle = account.handle;
      button.textContent = `${account.handle} · ${account.serverAdmin ? 'Administrator' : 'Regular user'}${account.enabled ? '' : ' · Disabled'}`;
      button.addEventListener('click',() => { void run(async () => { clearPassword(); selected = account.handle; renderAccount(); await sessions(); }); });
      parent.append(button);
    }
    renderAccount(); await sessions(); await audit();
  }
  get('[data-pricing]').addEventListener('click',() => { clearPassword(); pricing.open(); });
  get('[data-services]').addEventListener('click',() => { clearPassword(); services.open(); });
  opener.addEventListener('click',() => { clearPassword(); dialog.showModal(); void run(refresh); });
  get('[data-close]').addEventListener('click',() => dialog.close());
  dialog.addEventListener('close',() => { services.close(); pricing.close(); generation++; clearPassword(); accounts=[]; selected=''; get('[data-accounts]').replaceChildren(); get('[data-sessions]').replaceChildren(); get('[data-audit]').replaceChildren(); error(); });
  get('[data-hide-password]').addEventListener('click',clearPassword);
  get('[data-refresh]').addEventListener('click',() => { clearPassword(); void run(refresh); });
  get('[data-older]').addEventListener('click',() => { void run(() => audit(true)); });
  get<HTMLFormElement>('[data-create]').addEventListener('submit',event => {
    event.preventDefault(); clearPassword(); const form = get<HTMLFormElement>('[data-create]'); const data = new FormData(form);
    void run(async () => {
      const credential = await call<AccountCredential>('admin.account.create',{ handle: String(data.get('handle') ?? '').trim(), serverAdmin: data.get('serverAdmin') === 'on' });
      selected = credential.account.handle; form.reset(); showCredential(credential); await refresh();
    });
  });
  for (const [selector,field] of [['[data-enabled]','enabled'],['[data-role]','serverAdmin']] as const) get(selector).addEventListener('click',() => {
    clearPassword(); void run(async () => { const account = accounts.find(row => row.handle === selected)!; await call('admin.account.update',{ handle: selected, [field]: !account[field] }); await refresh(); });
  });
  get('[data-reset]').addEventListener('click',() => { clearPassword(); void run(async () => { const credential = await call<AccountCredential>('admin.account.reset',{handle: selected}); showCredential(credential); await refresh(); }); });
  get('[data-revoke]').addEventListener('click',() => { clearPassword(); void run(async () => { await call('admin.session.revoke',{handle: selected}); await refresh(); }); });
  return { update(state: DesktopState) {
    services.update(state); pricing.update(state);
    const available = state.connected && state.serverAdmin === true;
    if (base !== state.base || handle !== state.handle || connected && !available) { generation++; if (dialog.open) dialog.close(); clearPassword(); }
    connected = available; handle = state.handle; base = state.base; opener.hidden = !available;
  } };
}
