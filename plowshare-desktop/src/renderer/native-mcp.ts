import type {
  NativeCommand,
  NativeCommandResult,
  NativeManagedStatus,
  NativeProfile,
  NativeMcpConfig,
} from 'plowshare-mcp-integration';
import {
  NATIVE_FIELDS,
  nativeDraft,
  nativeFormConfig,
  DEFAULT_LIMITS,
} from 'plowshare-mcp-integration/native-editor';
import type {
  NativeField,
  NativeEnvironmentReference,
} from 'plowshare-mcp-integration/native-editor';
import { decodePayload } from 'plowshare-client-ts/operations/schema';
import type { ToolConnectionContext } from '../../../clients/tool-connections.ts';

/** Renderer owns transient drafts only. Main validates private files, login ownership,
 * launch consent and the original sharing socket. Closing this view never stops hosts.
 */
export function nativeMcpControls(options: {
  readonly project: string;
  readonly context: () => ToolConnectionContext;
  readonly execute: (command: NativeCommand) => Promise<NativeCommandResult>;
}) {
  const node = <K extends keyof HTMLElementTagNameMap>(tag: K, text = '') => {
    const value = document.createElement(tag);
    value.textContent = text;
    return value;
  };
  const element = node('details');
  element.dataset['connectionLocation'] = 'local';
  element.className = 'tool-connections';
  element.append(node('summary', 'This client'));
  const location = node('p'),
    status = node('p'),
    error = node('p');
  status.setAttribute('role', 'status');
  error.setAttribute('role', 'alert');
  element.append(
    location,
    node(
      'p',
      'HTTP and STDIO connections run in this desktop instance. Start is explicit. Hosts stop on disconnect, account change or app exit. Credentials are selected by environment reference; their values are never displayed.',
    ),
  );
  function field(parent: HTMLElement, label: string, value = '') {
    const row = node('label', label),
      control = node('input');
    control.value = value;
    row.style.display = 'grid';
    row.style.marginBlock = '0.5rem';
    row.append(control);
    parent.append(row);
    return control;
  }
  function select(
    parent: HTMLElement,
    label: string,
    values: readonly string[],
  ) {
    const row = node('label', label),
      control = node('select');
    control.setAttribute('aria-label', label);
    for (const value of values) {
      const option = node(
        'option',
        value === 'http' || value === 'stdio' ? value.toUpperCase() : value,
      );
      option.value = value;
      control.append(option);
    }
    row.style.display = 'grid';
    row.append(control);
    parent.append(row);
    return control;
  }
  function check(parent: HTMLElement, label: string) {
    const row = node('label', label),
      control = node('input');
    control.type = 'checkbox';
    row.prepend(control);
    parent.append(row);
    return control;
  }
  const editor = node('fieldset');
  editor.append(node('legend', 'Private native profile'));
  element.append(editor);
  const file = field(
    editor,
    'Profile file (absolute path in a private directory)',
  );
  file.dataset['nativeField'] = 'file';
  const kind = select(editor, 'Upstream transport', ['http', 'stdio']);
  const groups = {
    common: node('div'),
    http: node('fieldset'),
    stdio: node('fieldset'),
    advanced: node('details'),
  };
  groups.common.className = 'native-profile-fields';
  groups.http.className = 'native-profile-fields';
  groups.http.append(node('legend', 'HTTP endpoint'));
  groups.stdio.append(node('legend', 'Owned STDIO process'));
  groups.advanced.append(node('summary', 'Timeouts and admission budgets'));
  editor.append(groups.common, groups.http, groups.stdio, groups.advanced);
  const inputs = new Map<NativeField, HTMLInputElement | HTMLSelectElement>();
  for (const [key, label, group] of NATIVE_FIELDS) {
    const input =
      key === 'protocol'
        ? select(groups[group], label, ['2026-07-28', '2025-11-25'])
        : key === 'header'
          ? select(groups[group], label, [
              'Authorization',
              'X-API-Key',
              'Api-Key',
            ])
          : field(groups[group], label);
    input.dataset['nativeField'] = key;
    if (
      input instanceof HTMLInputElement &&
      (key.endsWith('Ms') ||
        key === 'timeoutSeconds' ||
        key === 'maxRecoveryCatalogues')
    ) {
      input.type = 'number';
      input.min = '1';
    }
    inputs.set(key, input);
  }
  const allowHttp = check(groups.http, 'Allow unencrypted HTTP'),
    allowPrivateNetwork = check(groups.http, 'Allow private network endpoints');
  const argumentRows = node('div'),
    environmentRows = node('div');
  groups.stdio.append(
    node(
      'p',
      'Arguments are passed individually, without a shell. Only the selected environment references are inherited.',
    ),
    argumentRows,
    environmentRows,
  );
  function addArgument(value = '') {
    const row = node('div'),
      input = field(row, 'Process argument', value),
      remove = node('button', 'Remove argument');
    remove.type = 'button';
    remove.addEventListener('click', () => row.remove());
    row.append(remove);
    argumentRows.append(row);
    return input;
  }
  function addEnvironment(name = '', reference = '') {
    const row = node('div');
    field(row, 'Child environment name', name);
    field(row, 'Parent environment reference', reference);
    const remove = node('button', 'Remove environment reference');
    remove.type = 'button';
    remove.addEventListener('click', () => row.remove());
    row.append(remove);
    environmentRows.append(row);
  }
  const addArg = node('button', 'Add argument'),
    addEnv = node('button', 'Add environment reference');
  addArg.type = addEnv.type = 'button';
  addArg.addEventListener('click', () => addArgument());
  addEnv.addEventListener('click', () => addEnvironment());
  groups.stdio.append(addArg, addEnv);
  const limitLabels = {
    maxToolNameLength: 'Upstream tool name length (characters)',
    maxDescriptionLength: 'Tool description length (characters)',
    maxCursorLength: 'Discovery cursor length (characters)',
    maxOfferedNameLength: 'Plowshare tool name length (characters)',
    maxPages: 'Discovery page budget',
    maxWireBytes: 'Message budget (bytes)',
    maxCatalogueBytes: 'Complete catalogue budget (bytes)',
    maxResultBytes: 'Tool result budget (bytes)',
    maxContentBlocks: 'Tool result content block budget',
  };
  const limits = new Map<keyof NativeMcpConfig['limits'], HTMLInputElement>();
  for (const key of Object.keys(
    DEFAULT_LIMITS,
  ) as (keyof typeof DEFAULT_LIMITS)[]) {
    const input = field(
      groups.advanced,
      limitLabels[key],
      String(DEFAULT_LIMITS[key]),
    );
    input.type = 'number';
    input.min = '1';
    input.dataset['nativeLimit'] = key;
    limits.set(key, input);
  }
  let saved = false;
  let loaded: NativeProfile | undefined,
    hosts: readonly NativeManagedStatus[] = [],
    closed = false,
    busy = false,
    epoch = 0,
    identity = '';
  const key = () =>
    JSON.stringify([options.context().origin, options.context().actor]);
  const visibility = () => {
    groups.http.hidden = kind.value !== 'http';
    groups.stdio.hidden = kind.value !== 'stdio';
  };
  kind.addEventListener('change', visibility);
  const draftActions = node('div');
  editor.append(draftActions);
  const launchReview = node('pre');
  launchReview.style.whiteSpace = 'pre-wrap';
  editor.append(launchReview);
  const consent = check(
    editor,
    'I reviewed this profile and permit this instance to connect or launch its configured process',
  );
  const hostSection = node('fieldset');
  hostSection.append(node('legend', 'Hosts in this client instance'));
  element.append(hostSection);
  const hostSelect = select(hostSection, 'Running or unavailable host', []),
    inventory = node('div'),
    hostActions = node('div');
  inventory.dataset['nativeInventory'] = '';
  hostSection.append(hostActions, inventory);
  const share = node('details');
  share.append(node('summary', 'Share this profile with server-owned work'));
  share.append(
    node(
      'p',
      'Explicit sharing advertises tools through this login’s provider scope. Server agents require their existing grants. Local calls remain direct. Stop withdraws sharing and stops the local host.',
    ),
  );
  const shareScope = field(share, 'Sharing scope'),
    provider = field(share, 'Provider name'),
    agents = field(share, 'Dynamic agents (comma separated)'),
    grants = field(share, 'Tool grants (comma separated)'),
    lease = field(share, 'Lease seconds', '120'),
    poll = field(share, 'Poll interval milliseconds', '1000'),
    renewal = field(share, 'Lease renewal fraction', '0.5');
  const shareConsent = check(
    share,
    'I authorize sharing these tools with the selected server project and agents',
  );
  editor.append(share);
  location.after(status, error);
  element.insertBefore(hostSection, editor);
  function fill(config?: NativeMcpConfig) {
    saved = config !== undefined;
    const draft = nativeDraft(options.project, config);
    for (const [key, input] of inputs) input.value = draft[key];
    kind.value = config?.transport.kind ?? 'http';
    allowHttp.checked =
      config?.transport.kind === 'http' && config.transport.allowHttp;
    allowPrivateNetwork.checked =
      config?.transport.kind === 'http' && config.transport.allowPrivateNetwork;
    argumentRows.replaceChildren();
    environmentRows.replaceChildren();
    if (config?.transport.kind === 'stdio') {
      for (const arg of config.transport.args) addArgument(arg);
      for (const [name, reference] of Object.entries(
        config.transport.environment,
      ))
        addEnvironment(name, reference);
    }
    for (const [key, input] of limits)
      input.value = String(config?.limits[key] ?? DEFAULT_LIMITS[key]);
    consent.checked = shareConsent.checked = false;
    launchReview.textContent = '';
    visibility();
  }
  function readConfig(): NativeMcpConfig {
    const draft = nativeDraft(options.project),
      budgets = { ...DEFAULT_LIMITS };
    for (const [key, input] of inputs) draft[key] = input.value;
    for (const [key, input] of limits) budgets[key] = Number(input.value);
    const references: NativeEnvironmentReference[] = [
      ...environmentRows.children,
    ].map((row) => {
      const values = row.querySelectorAll('input');
      const name = values.item(0),
        reference = values.item(1);
      if (!name || !reference)
        throw new Error('Incomplete environment reference');
      return { name: name.value, reference: reference.value };
    });
    if (kind.value !== 'http' && kind.value !== 'stdio')
      throw new Error('Select native transport');
    return nativeFormConfig({
      owner: {
        origin: options.context().origin,
        account: options.context().actor,
      },
      draft,
      kind: kind.value,
      args: [...argumentRows.querySelectorAll('input')].map(
        (input) => input.value,
      ),
      environment: references,
      allowHttp: allowHttp.checked,
      allowPrivateNetwork: allowPrivateNetwork.checked,
      limits: budgets,
    });
  }
  const selected = () =>
    hosts.find(
      (host) => host.instance + '/' + host.profile === hostSelect.value,
    );
  function drawInventory() {
    const host = selected();
    inventory.replaceChildren();
    if (!host) return;
    inventory.append(
      node(
        'p',
        `${host.instance} / ${host.profile} · ${host.transport.toUpperCase()} · ${host.active ? 'Available' : 'Unavailable'} · ${host.sharing === 'server' ? 'Shared with server' : host.sharing === 'uncertain' ? 'Sharing unconfirmed; inspect original receipts' : 'Local only'} · project ${host.project}`,
      ),
    );
    for (const tool of host.tools) {
      const details = node('details');
      details.append(
        node('summary', tool.name),
        node('p', tool.description),
        node(
          'pre',
          JSON.stringify(tool.inputSchema ?? tool.parameters, null, 2),
        ),
      );
      inventory.append(details);
    }
  }
  hostSelect.addEventListener('change', drawInventory);
  async function refresh() {
    const result = await execute({ action: 'list' });
    if (!Array.isArray(result))
      throw new Error('Expected native host snapshot');
    // Main's command owner returns a validated native status list for this exact action.
    hosts = result as readonly NativeManagedStatus[];
    const selection = hostSelect.value;
    hostSelect.replaceChildren();
    for (const host of hosts) {
      const option = node(
        'option',
        `${host.instance} / ${host.profile} · ${host.active ? 'available' : 'unavailable'}`,
      );
      option.value = host.instance + '/' + host.profile;
      hostSelect.append(option);
    }
    if (hosts.some((host) => host.instance + '/' + host.profile === selection))
      hostSelect.value = selection;
    drawInventory();
  }
  function active(value: boolean) {
    epoch++;
    element.hidden = !value;
    consent.checked = shareConsent.checked = false;
  }
  function update() {
    const context = options.context();
    location.textContent = `This client · ${context.origin} · ${context.actor}${context.connected ? '' : ' · disconnected'}`;
    if (identity !== key() || !context.connected) {
      epoch++;
      identity = key();
      loaded = undefined;
      hosts = [];
      file.value = '';
      fill();
      hostSelect.replaceChildren();
      inventory.replaceChildren();
      status.textContent = 'Refresh after connecting to the selected account.';
    }
    for (const control of element.querySelectorAll('input,select,button'))
      if (
        control instanceof HTMLInputElement ||
        control instanceof HTMLSelectElement ||
        control instanceof HTMLButtonElement
      )
        control.disabled = busy || !context.connected;
  }
  function action(
    parent: HTMLElement,
    text: string,
    work: () => Promise<string>,
  ) {
    const button = node('button', text);
    button.type = 'button';
    parent.append(button);
    button.addEventListener('click', () => {
      if (busy || closed || element.hidden || !options.context().connected)
        return;
      const generation = epoch,
        actor = key();
      busy = true;
      update();
      error.textContent = '';
      status.textContent = 'Working…';
      work()
        .then((message) => {
          if (!closed && generation === epoch && actor === key())
            status.textContent = message;
        })
        .catch(() => {
          if (!closed && generation === epoch && actor === key()) {
            error.textContent =
              'Action refused or unconfirmed. Check profile ownership, configuration and original private receipts before another action.';
            status.textContent = 'No action was replayed.';
          }
        })
        .finally(() => {
          busy = false;
          if (!closed) update();
        });
    });
  }
  // Every async operation is fenced independently of its final message: a late read
  // must not replace a draft after disconnect/account switch/dialog close.
  async function execute(command: NativeCommand) {
    const generation = epoch,
      actor = key();
    const result = await options.execute(command);
    if (
      closed ||
      element.hidden ||
      generation !== epoch ||
      actor !== key() ||
      !options.context().connected
    )
      throw new Error('Native view changed');
    return result;
  }
  action(draftActions, 'Load profile', async () => {
    const result = await execute({ action: 'profile-read', file: file.value });
    if (!('config' in result)) throw new Error('Expected profile');
    loaded = result;
    fill(result.config);
    return 'Private profile loaded. No host was started.';
  });
  action(draftActions, 'Save profile', async () => {
    const result = await execute({
      action: 'profile-save',
      file: file.value,
      config: readConfig(),
      expectedRevision: loaded?.file === file.value ? loaded.revision : null,
    });
    if (!('config' in result)) throw new Error('Expected profile');
    loaded = result;
    fill(result.config);
    return 'Private profile saved. No host was started.';
  });
  const newProfile = node('button', 'New profile');
  newProfile.type = 'button';
  newProfile.addEventListener('click', () => {
    loaded = undefined;
    file.value = '';
    fill();
  });
  draftActions.prepend(newProfile);
  async function reviewed(): Promise<NativeManagedStatus> {
    if (!loaded || loaded.file !== file.value || !saved)
      throw new Error('Save or reload changed profile before launch');
    const result = await execute({
      action: 'inspect',
      file: loaded.file,
      expectedRevision: loaded.revision,
    });
    if (!('tools' in result)) throw new Error('Expected launch review');
    launchReview.textContent = `${result.instance} / ${result.profile} · ${result.transport.toUpperCase()} · project ${result.project}\n${result.review.endpoint ?? result.review.executable ?? ''}\n${result.review.argumentCount} arguments · environment names: ${result.review.environmentNames.join(', ') || 'none'}\nAuthorization: ${result.review.authorizationConfigured ? 'environment reference' : 'none'}`;
    return result;
  }
  action(draftActions, 'Review launch', async () => {
    await reviewed();
    consent.checked = false;
    return 'Review the saved profile, then explicitly authorize Start.';
  });
  action(draftActions, 'Start local host', async () => {
    if (!consent.checked) throw new Error('Consent required');
    await reviewed();
    if (loaded === undefined) throw new Error('Load profile');
    await execute({
      action: 'start',
      file: file.value,
      consent: true,
      expectedRevision: loaded.revision,
    });
    await refresh();
    return 'Local host started. No server scope was published.';
  });
  action(hostActions, 'Refresh local hosts', async () => {
    await refresh();
    return 'This client’s host snapshot refreshed.';
  });
  action(hostActions, 'Stop selected host', async () => {
    const host = selected();
    if (!host) throw new Error('Select a host');
    await execute({
      action: 'stop',
      instance: host.instance,
      profile: host.profile,
    });
    await refresh();
    return 'Host stopped; any sharing was withdrawn.';
  });
  action(share, 'Start and share with server', async () => {
    if (!consent.checked || !shareConsent.checked)
      throw new Error('Local launch and sharing consent required');
    const config = readConfig();
    await reviewed();
    const split = (value: string) =>
      value
        .split(',')
        .map((text) => text.trim())
        .filter(Boolean);
    const scope = decodePayload('tool.scope.connect', {
      project: config.project,
      scope: shareScope.value,
      provider: provider.value,
      prefix: config.prefix,
      agents: split(agents.value),
      grants: split(grants.value),
      leaseSeconds: Number(lease.value),
    });
    await execute({
      action: 'share',
      file: file.value,
      ...(loaded === undefined ? {} : { expectedRevision: loaded.revision }),
      consent: true,
      scope,
      pollMs: Number(poll.value),
      renewalFraction: Number(renewal.value),
    });
    await refresh();
    return 'Explicit server sharing started on this login’s original socket.';
  });
  editor.addEventListener('input', (event) => {
    if (
      event.target !== consent &&
      event.target !== shareConsent &&
      !(event.target instanceof HTMLElement && share.contains(event.target))
    ) {
      saved = false;
      consent.checked = false;
      launchReview.textContent = '';
    }
  });
  groups.stdio.addEventListener('click', (event) => {
    if (event.target instanceof HTMLButtonElement) {
      saved = false;
      consent.checked = false;
      launchReview.textContent = '';
    }
  });
  fill();
  update();
  return {
    element,
    update,
    active,
    destroy() {
      closed = true;
      epoch++;
      loaded = undefined;
      hosts = [];
      file.value = '';
      fill();
      inventory.replaceChildren();
    },
  };
}
