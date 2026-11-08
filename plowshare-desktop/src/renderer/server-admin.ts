import { installConfigurationAdmin } from './configuration-admin.ts';
import { serverToolConnections } from './tool-connections.ts';
import type { ToolConnectionControls } from '../../../clients/tool-connections.ts';
import { background } from './events.ts';
import { decodeAdminCall } from '../admin-input.ts';
import { decodeReply } from 'plowshare-client-ts/operations/schema';
import { displayText } from 'plowshare-client-ts/binding/values';
import { installPricingAdmin } from './pricing-admin.ts';
import { installServiceAdmin } from './service-admin.ts';
import type {
  DesktopState,
  Reply,
  Request,
  ServerAdminOperation,
} from '../shared.ts';
import type {
  AccountCredential,
  ServerAccount,
} from 'plowshare-client-ts/operations/conversation-replies';

/** Workspace-owned administration. Section changes discard revealed secrets and fence late replies. */
export function installServerAdmin(
  request: (request: Request) => Promise<Reply>,
  host: HTMLElement,
  work: { open(host: HTMLElement): void; close(): void },
) {
  type Section =
    | 'configuration'
    | 'accounts'
    | 'services'
    | 'tools'
    | 'pricing'
    | 'audit'
    | 'work';
  // Deploy and Add Application stay on these ids so the existing dialog openers
  // can bind them. Visibility remains connected && serverAdmin.
  host.innerHTML = `<div class="administration-shell"><header class="administration-heading"><div><h1>Server administration</h1><p data-server-identity></p></div><div class="administration-heading-actions"><button type="button" id="application-deploy-open" hidden>Deploy</button><button type="button" id="server-project-add" hidden title="Create a managed server Application">Add Application</button><button type="button" data-return>Return to conversation</button></div></header>
    <div class="administration-layout"><nav class="administration-navigation" aria-label="Administration sections"><button type="button" data-section="configuration">Configuration</button><div class="admin-nav-label">Identity &amp; access</div><button type="button" data-section="accounts">Accounts</button><button type="button" data-section="services" data-services>Service accounts</button><div class="admin-nav-label">Work &amp; tools</div><button type="button" data-section="work">Manage work</button><button type="button" data-section="tools">Server MCP</button><button type="button" data-section="pricing" data-pricing>Pricing</button><button type="button" data-section="audit">Audit history</button></nav><div class="administration-content"><p data-error role="alert" hidden></p><div data-panels></div></div></div></div>`;
  const panels = host.querySelector<HTMLElement>('[data-panels]')!;
  const accountsPanel = document.createElement('section');
  accountsPanel.id = 'accounts-admin-panel';
  accountsPanel.className = 'administration-panel';
  accountsPanel.hidden = true;
  accountsPanel.innerHTML = `<header><div><h2>Accounts</h2><p>Account roles, access and login sessions.</p></div><button type="button" data-refresh>Refresh accounts</button></header>
    <details class="admin-create"><summary>Create account</summary><form data-create><label>Account name <input name="handle" required maxlength="64" pattern="[A-Za-z0-9][A-Za-z0-9_.-]{0,63}" autocomplete="off"></label>
    <label><input name="serverAdmin" type="checkbox">Server administrator</label><button>Create account</button></form></details>
    <section data-credential hidden><h3>Temporary password</h3><p data-credential-name></p><p>Give this password to the account owner. They must change it before using the server.</p><input data-password readonly aria-label="Temporary password" autocomplete="off"><button type="button" data-hide-password>Dismiss password</button></section>
    <div class="admin-columns"><div data-accounts aria-label="Accounts"></div><section data-account hidden><h3 data-account-name></h3><p data-account-status></p>
    <div class="admin-actions"><button type="button" data-enabled></button><button type="button" data-role></button><button type="button" data-reset>Reset password</button><button type="button" data-revoke>Sign out all sessions</button></div>
    <h4>Active login sessions</h4><div data-sessions></div></section></div>`;
  panels.append(accountsPanel);
  const auditPanel = document.createElement('section');
  auditPanel.id = 'audit-admin-panel';
  auditPanel.className = 'administration-panel';
  auditPanel.hidden = true;
  auditPanel.innerHTML = `<header><div><h2>Audit history</h2><p>Account changes and session revocations, newest first.</p></div><button type="button" data-audit-refresh>Refresh history</button></header><div data-audit></div><button type="button" data-older hidden>Load older entries</button>`;
  panels.append(auditPanel);
  const toolsPanel = document.createElement('section');
  toolsPanel.id = 'server-tools-admin-panel';
  toolsPanel.className = 'administration-panel';
  toolsPanel.hidden = true;
  toolsPanel.innerHTML = `<header><div><h2>Server MCP</h2><p>Shared endpoints and project assignments. Credentials remain scoped to their owner.</p></div></header><label class="admin-project-picker">Execution project<select data-tools-project aria-label="Server tools execution project"></select></label><p data-tools-empty hidden>No execution projects are available for server tool management.</p><div data-tools-host></div>`;
  panels.append(toolsPanel);
  const workPanel = document.createElement('section');
  workPanel.className = 'administration-panel';
  workPanel.id = 'work-admin-panel';
  workPanel.hidden = true;
  panels.append(workPanel);
  const get = <T extends HTMLElement>(selector: string) =>
    host.querySelector<T>(selector)!;
  let active: Section = 'configuration';
  let snapshot: DesktopState | undefined;
  let tools: ToolConnectionControls | undefined;
  const configuration = installConfigurationAdmin(request, panels);
  const services = installServiceAdmin(request, panels, () =>
    select('accounts'),
  );
  const pricing = installPricingAdmin(request, panels, () =>
    select('accounts'),
  );
  let connected = false,
    handle = '',
    base = '',
    generation = 0,
    busy = false;
  let accounts: readonly ServerAccount[] = [],
    selected = '',
    before = 0;
  const error = (reason?: unknown) => {
    const node = get('[data-error]');
    node.hidden = !reason;
    node.textContent =
      reason instanceof Error ? reason.message : displayText(reason ?? '');
  };
  const clearPassword = () => {
    get<HTMLInputElement>('[data-password]').value = '';
    get('[data-credential-name]').textContent = '';
    get('[data-credential]').hidden = true;
  };
  const call = async <K extends ServerAdminOperation>(
    operation: K,
    payload?: Record<string, unknown>,
  ) => {
    const turn = generation;
    const response = await request({
      action: 'server-admin',
      ...decodeAdminCall(operation, payload),
    });
    if (turn !== generation || host.hidden || !connected)
      throw new Error('Administration connection changed.');
    return decodeReply(operation, response.administration);
  };
  const run = async (work: () => Promise<void>) => {
    if (busy) return;
    busy = true;
    const turn = generation;
    error();
    const controls = [
      ...accountsPanel.querySelectorAll<HTMLButtonElement | HTMLInputElement>(
        'button,input',
      ),
    ];
    controls.forEach((node) => {
      if (!node.matches('[data-close]')) node.disabled = true;
    });
    try {
      await work();
    } catch (reason) {
      if (turn === generation && !host.hidden) error(reason);
    } finally {
      if (turn === generation) {
        busy = false;
        controls.forEach((node) => {
          node.disabled = false;
        });
        renderAccount();
      }
    }
  };
  function showCredential(value: AccountCredential) {
    clearPassword();
    get('[data-credential]').hidden = false;
    get('[data-credential-name]').textContent = value.account.handle;
    get<HTMLInputElement>('[data-password]').value = value.temporaryPassword;
  }
  function renderAccount() {
    const account = accounts.find((row) => row.handle === selected);
    get('[data-account]').hidden = !account;
    if (!account) return;
    get('[data-account-name]').textContent = account.handle;
    get('[data-account-status]').textContent =
      `${account.enabled ? 'Enabled' : 'Disabled'} · ${account.serverAdmin ? 'Server administrator' : 'Regular user'}${account.mustChangePassword ? ' · Password change required' : ''}`;
    get('[data-enabled]').textContent = account.enabled
      ? 'Disable account'
      : 'Enable account';
    get('[data-role]').textContent = account.serverAdmin
      ? 'Remove administrator role'
      : 'Make administrator';
    get<HTMLButtonElement>('[data-reset]').disabled =
      busy || account.handle === handle;
    accountsPanel
      .querySelectorAll<HTMLButtonElement>('[data-account-handle]')
      .forEach((button) =>
        button.setAttribute(
          'aria-pressed',
          String(button.dataset.accountHandle === selected),
        ),
      );
  }
  async function sessions() {
    get('[data-sessions]').replaceChildren();
    if (!selected) return;
    const rows = await call('admin.sessions', {
      handle: selected,
    });
    const parent = get('[data-sessions]');
    if (!rows.length) parent.textContent = 'No active login sessions.';
    for (const session of rows) {
      const row = document.createElement('p');
      row.textContent = `${session.id} · ${session.restricted ? 'Password change required' : 'Active'} · Expires ${new Date(session.expiresAt).toLocaleString()}`;
      parent.append(row);
    }
  }
  async function audit(older = false) {
    const page = await call('admin.audit', {
      limit: 25,
      ...(older ? { before } : {}),
    });
    const parent = get('[data-audit]');
    if (!older) parent.replaceChildren();
    for (const entry of page.entries) {
      const row = document.createElement('p');
      row.textContent = `${new Date(entry.occurredAt).toLocaleString()} · ${entry.actor} · ${entry.action} · ${entry.target}${entry.enabled === null ? '' : ` · ${entry.enabled ? 'Enabled' : 'Disabled'}`}${entry.serverAdmin === null ? '' : ` · ${entry.serverAdmin ? 'Administrator' : 'Regular user'}`}`;
      parent.append(row);
    }
    if (!parent.childNodes.length)
      parent.textContent = 'No administrator changes recorded yet.';
    before = page.before;
    get('[data-older]').hidden = before === 0;
  }
  async function refresh() {
    accounts = await call('admin.accounts');
    if (!accounts.some((row) => row.handle === selected))
      selected = accounts[0]?.handle ?? '';
    const parent = get('[data-accounts]');
    parent.replaceChildren();
    for (const account of accounts) {
      const button = document.createElement('button');
      button.type = 'button';
      button.dataset.accountHandle = account.handle;
      button.textContent = `${account.handle} · ${account.serverAdmin ? 'Administrator' : 'Regular user'}${account.enabled ? '' : ' · Disabled'}`;
      button.addEventListener('click', () => {
        background(
          run(async () => {
            clearPassword();
            selected = account.handle;
            renderAccount();
            await sessions();
          }),
        );
      });
      parent.append(button);
    }
    renderAccount();
    await sessions();
  }
  function mountTools() {
    tools?.destroy();
    tools = undefined;
    get('[data-tools-host]').replaceChildren();
    const project = get<HTMLSelectElement>('[data-tools-project]').value;
    get('[data-tools-empty]').hidden = !!project;
    if (!project || !snapshot) return;
    tools = serverToolConnections(() => snapshot!, request, project);
    tools.element.open = true;
    get('[data-tools-host]').append(tools.element);
  }
  function deactivate() {
    generation++;
    busy = false;
    clearPassword();
    work.close();
    workPanel.hidden = true;
    configuration.close();
    services.close();
    pricing.close();
    tools?.destroy();
    tools = undefined;
    get('[data-tools-host]').replaceChildren();
    accountsPanel.hidden = auditPanel.hidden = toolsPanel.hidden = true;
    error();
  }
  function select(section: Section) {
    if (host.hidden || !connected) return;
    deactivate();
    active = section;
    for (const button of host.querySelectorAll<HTMLButtonElement>(
      '[data-section]',
    )) {
      if (button.dataset.section === section)
        button.setAttribute('aria-current', 'page');
      else button.removeAttribute('aria-current');
    }
    if (section === 'work') {
      workPanel.hidden = false;
      work.open(workPanel);
    } else if (section === 'configuration') configuration.open();
    else if (section === 'services') services.open();
    else if (section === 'pricing') pricing.open();
    else if (section === 'tools') {
      toolsPanel.hidden = false;
      const picker = get<HTMLSelectElement>('[data-tools-project]');
      const selectedProject = picker.value;
      picker.replaceChildren();
      for (const project of snapshot?.projects ?? []) {
        const option = document.createElement('option');
        option.value = project.name;
        option.textContent = project.displayName ?? project.name;
        picker.append(option);
      }
      if (
        [...picker.options].some((option) => option.value === selectedProject)
      )
        picker.value = selectedProject;
      mountTools();
    } else if (section === 'accounts') {
      accountsPanel.hidden = false;
      background(run(refresh));
    } else {
      auditPanel.hidden = false;
      background(run(() => audit()));
    }
  }
  for (const button of host.querySelectorAll<HTMLButtonElement>(
    '[data-section]',
  ))
    button.addEventListener('click', () =>
      select(button.dataset.section as Section),
    );
  get('[data-tools-project]').addEventListener('change', mountTools);
  get('[data-return]').addEventListener('click', () => {
    background(request({ action: 'workspace-chat' }));
  });
  get('[data-refresh]').addEventListener('click', () => {
    clearPassword();
    background(run(refresh));
  });
  get('[data-audit-refresh]').addEventListener('click', () => {
    background(run(() => audit()));
  });
  get('[data-older]').addEventListener('click', () => {
    background(run(() => audit(true)));
  });
  get('[data-hide-password]').addEventListener('click', clearPassword);
  get<HTMLFormElement>('[data-create]').addEventListener('submit', (event) => {
    event.preventDefault();
    clearPassword();
    const form = get<HTMLFormElement>('[data-create]');
    const data = new FormData(form);
    background(
      run(async () => {
        const credential = await call('admin.account.create', {
          handle: displayText(data.get('handle') ?? '').trim(),
          serverAdmin: data.get('serverAdmin') === 'on',
        });
        selected = credential.account.handle;
        form.reset();
        showCredential(credential);
        await refresh();
      }),
    );
  });
  for (const [selector, field] of [
    ['[data-enabled]', 'enabled'],
    ['[data-role]', 'serverAdmin'],
  ] as const)
    get(selector).addEventListener('click', () => {
      clearPassword();
      background(
        run(async () => {
          const account = accounts.find((row) => row.handle === selected)!;
          await call('admin.account.update', {
            handle: selected,
            [field]: !account[field],
          });
          await refresh();
        }),
      );
    });
  get('[data-reset]').addEventListener('click', () => {
    clearPassword();
    background(
      run(async () => {
        const credential = await call('admin.account.reset', {
          handle: selected,
        });
        showCredential(credential);
        await refresh();
      }),
    );
  });
  get('[data-revoke]').addEventListener('click', () => {
    clearPassword();
    background(
      run(async () => {
        await call('admin.session.revoke', { handle: selected });
        await refresh();
      }),
    );
  });
  return {
    open() {
      select(active);
    },
    select,
    close() {
      deactivate();
      accounts = [];
      selected = '';
      before = 0;
      get('[data-accounts]').replaceChildren();
      get('[data-sessions]').replaceChildren();
      get('[data-audit]').replaceChildren();
      get<HTMLFormElement>('[data-create]').reset();
    },
    update(state: DesktopState) {
      configuration.update(state);
      services.update(state);
      pricing.update(state);
      const available = state.connected && state.serverAdmin === true;
      if (
        base !== state.base ||
        handle !== state.handle ||
        (connected && !available)
      ) {
        this.close();
        active = 'configuration';
        if (!host.hidden) background(request({ action: 'workspace-chat' }));
      }
      snapshot = state;
      connected = available;
      handle = state.handle;
      base = state.base;
      get('[data-server-identity]').textContent = state.connected
        ? `${state.base} · ${state.handle}`
        : 'Disconnected';
      document.querySelector<HTMLButtonElement>('#server-admin-open')!.hidden =
        !available;
      tools?.update();
    },
  };
}
