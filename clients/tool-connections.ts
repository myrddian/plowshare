import { ProviderManagementRefused } from '../sdk/typescript/src/operations/provider-management.ts';
import type {
  ProviderManagement,
  ManagementIntent,
} from '../sdk/typescript/src/operations/provider-management.ts';
import type {
  ProviderConnection,
  ProviderCredentialStatus,
} from '../sdk/typescript/src/operations/provider-credentials.ts';
import type { HostedToolBinding } from '../sdk/typescript/src/operations/hosted-tool-scopes.ts';
import type { ToolScopeConnection } from '../sdk/typescript/src/operations/tool-scopes.ts';
import { decodePayload } from '../sdk/typescript/src/operations/schema.ts';

/** Composition supplies the current authenticated context and its existing socket capability.
 * The browser UI owns only drafts and display snapshots; server records own every lifecycle. */
export interface ToolConnectionContext {
  readonly origin: string;
  readonly actor: string;
  readonly connected: boolean;
  readonly serverAdmin: boolean;
}
export interface ToolConnectionControls {
  readonly element: HTMLDetailsElement;
  update(): void;
  destroy(): void;
}
class ToolConnectionInputRefused extends Error {}
export function toolConnectionControls(options: {
  readonly project: string;
  readonly context: () => ToolConnectionContext;
  readonly management: ProviderManagement;
  readonly authorize?: (url: string) => Promise<void>;
}): ToolConnectionControls {
  const root = document.createElement('details');
  root.className = 'tool-connections';
  root.style.maxWidth = '48rem';
  const node = <K extends keyof HTMLElementTagNameMap>(tag: K, text = '') => {
    const element = document.createElement(tag);
    element.textContent = text;
    return element;
  };
  root.append(node('summary', 'Tool connections'));
  const location = node('p'),
    status = node('p'),
    error = node('p'),
    evidence = node('pre');
  status.setAttribute('role', 'status');
  error.setAttribute('role', 'alert');
  evidence.style.whiteSpace = 'pre-wrap';
  const label = (text: string, control: HTMLElement) => {
    const row = node('label', text + ' ');
    row.style.display = 'grid';
    row.style.marginBlock = '0.75rem';
    row.append(control);
    root.append(row);
    return control;
  };
  const field = (name: string, value = '') => {
    const input = node('input');
    input.value = value;
    input.dataset['toolField'] = name;
    label(name, input);
    return input;
  };
  const credentialProject = field(
    'Credential project',
    options.project.startsWith('personal:') ? '' : options.project,
  );
  const subject = field(
    'Service credential subject (empty means my authorization)',
  );
  subject.placeholder = '@service/token UUID';
  const endpoints = node('select');
  label('Shared endpoint', endpoints);
  const grantText = node('p');
  const secret = field('API key or machine secret');
  secret.type = 'password';
  secret.autocomplete = 'off';
  const authorization = node('a', 'Open upstream authorization');
  authorization.hidden = true;
  authorization.target = '_blank';
  authorization.rel = 'noopener noreferrer';
  const generalActions = node('div'),
    authorizationActions = node('div'),
    assignmentActions = node('div'),
    endpointActions = node('div'),
    pendingActions = node('div');
  root.insertBefore(location, root.children.item(1));
  location.after(generalActions);
  root.append(grantText, authorization, authorizationActions);
  const assignments = node('select');
  label('Your server assignment', assignments);
  const scope = field('Scope'),
    provider = field('Provider name'),
    prefix = field('Tool prefix');
  const agents = field('Assigned dynamic agents (comma separated)'),
    grants = field('Tool grants (comma separated)');
  const executor = field('Operator-approved executor principal');
  executor.placeholder = '@service/token UUID';
  const lease = field('Catalogue lease in seconds', '120');
  lease.type = 'number';
  lease.min = '1';
  root.append(assignmentActions);
  const definition = node('textarea');
  definition.rows = 8;
  definition.dataset['toolField'] = 'Endpoint definition';
  label('Shared HTTP endpoint definition (JSON; no credentials)', definition);
  root.append(endpointActions);
  root.append(
    node(
      'p',
      'Shared endpoint changes require server administration and credential-project Manager access. Every revision revokes existing grants.',
    ),
    node(
      'p',
      'Binding owner: authenticated actor. Credential subject: selected above. Executor: explicit operator principal. Accounting owner: caller; service credentials do not select a spending pool.',
    ),
    node(
      'p',
      'Use * only to grant all provider tools, including future additions. Compatibility and worker health: not reported. Native hosts belong to their own session; this panel cannot start or adopt them.',
    ),
    evidence,
    status,
    error,
  );
  const pending = node('select');
  label('Unconfirmed management requests', pending);
  root.append(pendingActions);
  let connections: readonly ProviderConnection[] = [],
    bindings: readonly HostedToolBinding[] = [],
    routes: readonly ToolScopeConnection[] = [],
    intentList: readonly ManagementIntent[] = [],
    credential: ProviderCredentialStatus | undefined;
  let closed = false,
    busy = false,
    epoch = 0,
    identity = '';
  let savedEndpoint = '',
    savedAssignment = '';
  const key = () =>
    JSON.stringify([options.context().origin, options.context().actor]);
  const currentConnection = () =>
    connections.find((value) => value.connection === endpoints.value);
  const currentBinding = () =>
    bindings.find((value) => value.binding === assignments.value);
  const clearSecret = () => {
    secret.value = '';
    authorization.removeAttribute('href');
    authorization.hidden = true;
  };
  const clearSnapshots = () => {
    connections = [];
    bindings = [];
    routes = [];
    credential = undefined;
    endpoints.replaceChildren();
    assignments.replaceChildren();
    pending.replaceChildren();
    evidence.textContent = '';
    grantText.textContent = '';
  };
  const validView = (generation: number, actor: string) =>
    !closed &&
    root.open &&
    epoch === generation &&
    key() === actor &&
    options.context().connected;
  const controls = () =>
    root.querySelectorAll<
      | HTMLInputElement
      | HTMLTextAreaElement
      | HTMLSelectElement
      | HTMLButtonElement
    >('input, textarea, select, button:not([data-tool-review])');
  const drawLocation = () => {
    const context = options.context();
    location.textContent = `${context.origin} · ${context.actor} · execution project ${options.project}${context.connected ? '' : ' · disconnected; snapshots stale'}`;
    for (const control of controls())
      control.disabled = busy || !context.connected;
    subject.disabled = !context.serverAdmin || busy;
    definition.readOnly = !context.serverAdmin;
    for (const button of endpointActions.querySelectorAll('button'))
      button.disabled = busy || !context.serverAdmin || !context.connected;
  };
  function drawEvidence() {
    evidence.textContent =
      bindings
        .map((binding) => {
          const active = routes.some(
            (route) => JSON.stringify(route) === JSON.stringify(binding.route),
          );
          return `${binding.definition.scope}: saved ${binding.deleted ? 'deleted' : binding.enabled ? 'enabled' : 'disabled'} · generation ${binding.generation} · ${active ? 'available in latest snapshot' : 'unavailable in latest snapshot; reason not reported'}\nOwner: ${binding.owner} · credential subject: ${binding.credentialSubject} · executor: ${binding.definition.executor}`;
        })
        .join('\n\n') || 'No saved server assignments in this snapshot.';
  }
  function drawGrant() {
    const connection = currentConnection();
    if (!connection || !credential) {
      grantText.textContent = 'Select an endpoint and inspect authorization.';
      return;
    }
    const expired =
      credential.expiresAt !== null &&
      Date.parse(credential.expiresAt) <= Date.now();
    const description =
      credential.state === 'READY' &&
      connection.definition.authentication === 'CLIENT_CREDENTIALS' &&
      credential.expiresAt === null
        ? 'Machine secret stored; obtain access token'
        : credential.state === 'UNCERTAIN'
          ? 'Reauthorization required; do not replay the exchange'
          : credential.state === 'READY' && expired
            ? 'Access expired; explicitly refresh or authorize'
            : credential.state;
    grantText.textContent = `${connection.definition.name} · ${connection.definition.endpoint} · revision ${connection.revision}\nAuthorization for ${credential.subject}: ${description}${credential.expiresAt ? ` · expires ${credential.expiresAt}` : ''}`;
  }
  async function readGrant() {
    const generation = epoch,
      actor = key();
    const connection = currentConnection();
    credential = undefined;
    if (connection) {
      const value = await options.management.request(
        'provider.credential.status',
        {
          project: credentialProject.value,
          connection: connection.connection,
          subject: subject.value || null,
        },
      );
      if (validView(generation, actor)) credential = value;
    }
  }
  function fillAssignment() {
    const binding = currentBinding();
    scope.value = binding?.definition.scope ?? '';
    provider.value = binding?.definition.provider ?? '';
    prefix.value = binding?.definition.prefix ?? '';
    agents.value = binding?.definition.agents.join(', ') ?? '';
    grants.value = binding?.definition.grants.join(', ') ?? '';
    executor.value = binding?.definition.executor ?? '';
    lease.value = String(binding?.definition.leaseSeconds ?? 120);
    if (binding) {
      credentialProject.value = binding.definition.credentialProject;
      subject.value = binding.definition.subject ?? '';
      savedEndpoint = binding.definition.connection;
      // A binding can refer to another credential project. Do not reuse an endpoint
      // or grant from the previously selected project before the next fresh read.
      connections = [];
      endpoints.replaceChildren();
      definition.value = '';
      status.textContent =
        'Assignment selected; refresh its credential project and endpoint before editing authorization.';
    }
  }
  let cancelReview: (() => void) | undefined;
  async function review(message: string): Promise<void> {
    const generation = epoch,
      actor = key(),
      focus = document.activeElement;
    const dialog = node('dialog'),
      explanation = node('p', message),
      cancel = node('button', 'Cancel'),
      confirm = node('button', 'Confirm action');
    dialog.setAttribute('aria-label', 'Review tool connection action');
    cancel.type = 'button';
    confirm.type = 'button';
    // Background state updates keep editing controls fenced while a review is
    // open, but its Cancel/Confirm controls must remain keyboard-operable.
    cancel.dataset['toolReview'] = '';
    confirm.dataset['toolReview'] = '';
    dialog.append(explanation, cancel, confirm);
    root.append(dialog);
    const accepted = await new Promise<boolean>((resolve) => {
      const finish = (value: boolean) => {
        dialog.remove();
        cancelReview = undefined;
        resolve(value);
      };
      cancelReview = () => finish(false);
      cancel.addEventListener('click', () => finish(false), { once: true });
      confirm.addEventListener('click', () => finish(true), { once: true });
      dialog.addEventListener(
        'cancel',
        (event) => {
          event.preventDefault();
          finish(false);
        },
        { once: true },
      );
      dialog.showModal();
      cancel.focus();
    });
    // The action finally block reenables its trigger before focus is restored.
    setTimeout(() => {
      if (
        validView(generation, actor) &&
        focus instanceof HTMLElement &&
        focus.isConnected
      )
        focus.focus();
    }, 0);
    if (!accepted || !validView(generation, actor))
      throw new ToolConnectionInputRefused('Cancelled before submission.');
  }
  function selectValues(
    select: HTMLSelectElement,
    rows: readonly { id: string; name: string }[],
    selected: string,
  ) {
    select.replaceChildren(node('option', 'Select / new'));
    const first = select.options.item(0);
    if (first) first.value = '';
    for (const row of rows) {
      const option = node('option', row.name);
      option.value = row.id;
      select.append(option);
    }
    select.value = selected;
  }
  async function refresh() {
    const generation = epoch,
      actor = key();
    const selectedEndpoint = savedEndpoint || endpoints.value,
      selectedAssignment = savedAssignment || assignments.value;
    const endpointList = await options.management.request(
      'provider.connection.list',
      { project: credentialProject.value },
    );
    const bindingList = await options.management.request(
      'tool.scope.host.list',
      { project: options.project },
    );
    const active = await options.management.request('tool.scope.list', {
      project: options.project,
    });
    if (!validView(generation, actor)) return;
    connections = endpointList;
    bindings = bindingList;
    routes = active.connections;
    selectValues(
      endpoints,
      connections.map((value) => ({
        id: value.connection,
        name: `${value.definition.name}${value.deleted ? ' (deleted)' : !value.enabled ? ' (disabled)' : ''}`,
      })),
      selectedEndpoint,
    );
    selectValues(
      assignments,
      bindings.map((value) => ({
        id: value.binding,
        name: value.definition.scope,
      })),
      selectedAssignment,
    );
    savedEndpoint = '';
    savedAssignment = '';
    await readGrant();
    const saved = await options.management.pending();
    if (!validView(generation, actor)) return;
    intentList = saved;
    selectValues(
      pending,
      intentList.map((intent) => ({
        id: intent.requestId,
        name: `${intent.operation} · ${intent.project} · ${intent.requestId}`,
      })),
      pending.value,
    );
    drawEvidence();
    drawGrant();
  }
  /** Freeze drafts during one action, drop every late result on selection/identity/close.
   * Read-only refreshes never authorize, refresh tokens, start hosts or replay mutations. */
  function action(
    text: string,
    work: () => Promise<void>,
    mutation = false,
    group: HTMLElement = generalActions,
  ) {
    const button = node('button', text);
    button.type = 'button';
    group.append(button);
    button.style.margin = '0.25rem';
    button.addEventListener('click', () => {
      const execute = async () => {
        if (busy || closed || !root.open || !options.context().connected)
          return;
        const generation = epoch,
          actor = key();
        let mutationConfirmed = false;
        busy = true;
        error.textContent = '';
        status.textContent = mutation
          ? 'Submitting once; retain the original request UUID if the reply is lost.'
          : 'Reading server snapshot…';
        for (const control of controls()) control.disabled = true;
        try {
          await work();
          mutationConfirmed = mutation;
          if (!validView(generation, actor)) {
            clearSecret();
            clearSnapshots();
            return;
          }
          if (mutation) await refresh();
          if (!validView(generation, actor)) {
            clearSecret();
            clearSnapshots();
            return;
          }
          drawGrant();
          drawEvidence();
          if (!mutation && status.textContent === 'Reading server snapshot…')
            status.textContent = `Snapshot read at ${new Date().toISOString()}. A saved assignment does not guarantee admission of the next call.`;
        } catch (failure) {
          clearSecret();
          if (validView(generation, actor)) {
            if (!mutationConfirmed)
              status.textContent = mutation
                ? 'Action unconfirmed or refused; inspect original receipts before another mutation.'
                : 'Snapshot read failed; retained metadata may be stale.';
            error.textContent =
              failure instanceof ProviderManagementRefused
                ? failure.message
                : failure instanceof ToolConnectionInputRefused
                  ? failure.message
                  : mutationConfirmed
                    ? 'Mutation receipt confirmed; the current snapshot could not be read. Refresh metadata separately.'
                    : mutation
                      ? 'Outcome unknown or input refused. Inspect unconfirmed requests and original receipts before another action. No mutation was replayed.'
                      : 'Read failed or refused. Check the credential project, subject and current permissions; no HTTP fallback was attempted.';
            intentList = await options.management.pending();
            if (validView(generation, actor))
              selectValues(
                pending,
                intentList.map((intent) => ({
                  id: intent.requestId,
                  name: `${intent.operation} · ${intent.project} · ${intent.requestId}`,
                })),
                '',
              );
          }
        } finally {
          busy = false;
          secret.value = '';
          for (const control of controls()) control.disabled = false;
          drawLocation();
        }
      };
      // The UI owns failures including storage errors in the catch/recovery path.
      execute().catch(() => {
        clearSecret();
        if (!closed)
          error.textContent =
            'Recovery bookmarks could not be read. Inspect private local storage before another mutation.';
      });
    });
    return button;
  }
  const credentialInput = () => {
    const connection = currentConnection();
    if (!connection || !credential)
      throw new ToolConnectionInputRefused(
        'Inspect current authorization first.',
      );
    return {
      project: credentialProject.value,
      connection: connection.connection,
      subject: subject.value || null,
      requestId: crypto.randomUUID(),
      expectedGeneration: credential.generation,
    };
  };
  action('Refresh', refresh);
  action(
    'Inspect authorization',
    async () => {
      clearSecret();
      await readGrant();
    },
    false,
    authorizationActions,
  );
  action(
    'Set credential',
    async () => {
      const material = secret.value;
      clearSecret();
      if (currentConnection()?.definition.authentication === 'OAUTH_PKCE')
        throw new ToolConnectionInputRefused(
          'Use browser authorization for this endpoint.',
        );
      const receipt = await options.management.request(
        'provider.credential.put',
        { ...credentialInput(), secret: material },
      );
      status.textContent = `Original receipt confirmed: ${receipt.requestId}. Current status is read separately.`;
    },
    true,
    authorizationActions,
  );
  action(
    'Authorize in browser',
    async () => {
      const generation = epoch,
        actor = key();
      clearSecret();
      if (currentConnection()?.definition.authentication !== 'OAUTH_PKCE')
        throw new Error(
          'This endpoint does not use user browser authorization.',
        );
      const value = await options.management.request(
        'provider.credential.authorize',
        credentialInput(),
      );
      if (!validView(generation, actor)) return;
      // Desktop's explicit action opens in the OS browser. Console presents a deliberate link,
      // avoiding popup creation in the asynchronous reply/background refresh continuation.
      if (options.authorize) {
        try {
          await options.authorize(value.authorizationUrl);
        } catch {
          status.textContent = `Authorization request ${value.receipt.requestId} confirmed; browser could not be opened. Inspect current authorization before a new request.`;
          return;
        }
      } else if (root.open && !closed) {
        authorization.href = value.authorizationUrl;
        authorization.hidden = false;
      }
      status.textContent = `Authorization request ${value.receipt.requestId} confirmed; awaiting upstream authorization.`;
    },
    true,
    authorizationActions,
  );
  for (const [name, operation] of [
    ['Obtain / refresh access token', 'provider.credential.refresh'],
    ['Revoke authorization', 'provider.credential.revoke'],
    ['Rekey encryption', 'provider.credential.rekey'],
  ] as const)
    action(
      name,
      async () => {
        if (
          (operation === 'provider.credential.refresh' ||
            operation === 'provider.credential.rekey') &&
          credential?.state !== 'READY'
        )
          throw new Error(
            'Inspect a READY generation before obtaining/refreshing or rekeying. UNCERTAIN requires reauthorization.',
          );
        clearSecret();
        if (operation === 'provider.credential.revoke') {
          const input = credentialInput();
          await review(
            `Revoke authorization for ${input.subject ?? options.context().actor} on endpoint ${input.connection} in credential project ${input.project || 'Personal'}? Existing assignments may become unavailable.`,
          );
        }
        const receipt = await options.management.request(
          operation,
          credentialInput(),
        );
        status.textContent = `Original receipt confirmed: ${receipt.requestId}. Current status is read separately.`;
      },
      true,
      authorizationActions,
    );
  action(
    'Save server assignment',
    async () => {
      clearSecret();
      const connection = currentConnection();
      if (!connection)
        throw new ToolConnectionInputRefused('Select an endpoint.');
      const binding = currentBinding();
      const requestId = crypto.randomUUID();
      const payload = decodePayload('tool.scope.host.save', {
        project: options.project,
        binding: binding?.binding ?? crypto.randomUUID(),
        requestId,
        expectedGeneration: binding?.generation ?? null,
        definition: {
          scope: scope.value,
          provider: provider.value,
          prefix: prefix.value,
          agents: agents.value
            .split(',')
            .map((value) => value.trim())
            .filter(Boolean),
          grants: grants.value
            .split(',')
            .map((value) => value.trim())
            .filter(Boolean),
          leaseSeconds: Number(lease.value),
          credentialProject: credentialProject.value,
          connection: connection.connection,
          subject: subject.value || null,
          executor: executor.value,
        },
      });
      const receipt = await options.management.request(
        'tool.scope.host.save',
        payload,
      );
      savedAssignment = receipt.binding.binding;
      status.textContent = `Assignment save confirmed: ${requestId}. Worker registration is separate.`;
    },
    true,
    assignmentActions,
  );
  for (const actionName of ['ENABLE', 'DISABLE', 'DELETE'] as const)
    action(
      `${actionName.toLowerCase()} assignment`,
      async () => {
        clearSecret();
        const binding = currentBinding();
        if (!binding)
          throw new ToolConnectionInputRefused('Select an assignment.');
        if (actionName !== 'ENABLE')
          await review(
            `${actionName.toLowerCase()} assignment ${binding.definition.scope} in ${options.project}, using credential subject ${binding.credentialSubject}?`,
          );
        const receipt = await options.management.request(
          'tool.scope.host.change',
          {
            project: options.project,
            binding: binding.binding,
            requestId: crypto.randomUUID(),
            expectedGeneration: binding.generation,
            action: actionName,
          },
        );
        status.textContent = `Original assignment receipt confirmed: ${receipt.requestId}.`;
      },
      true,
      assignmentActions,
    );
  action(
    'Save shared endpoint',
    async () => {
      clearSecret();
      const connection = currentConnection();
      const requestId = crypto.randomUUID();
      const decoded: unknown = JSON.parse(definition.value);
      const payload = decodePayload('provider.connection.save', {
        project: credentialProject.value,
        connection: connection?.connection ?? crypto.randomUUID(),
        requestId,
        expectedRevision: connection?.revision ?? null,
        definition: decoded,
      });
      const receipt = await options.management.request(
        'provider.connection.save',
        payload,
      );
      savedEndpoint = receipt.connection.connection;
      status.textContent = `Original endpoint receipt confirmed: ${requestId}. Existing grants require new authorization.`;
    },
    true,
    endpointActions,
  );
  for (const actionName of ['ENABLE', 'DISABLE', 'DELETE'] as const)
    action(
      `${actionName.toLowerCase()} endpoint`,
      async () => {
        clearSecret();
        const connection = currentConnection();
        if (!connection)
          throw new ToolConnectionInputRefused('Select an endpoint.');
        if (actionName !== 'ENABLE')
          await review(
            `${actionName.toLowerCase()} shared endpoint ${connection.definition.name} in credential project ${credentialProject.value || 'Personal'}? All assignments using this endpoint can be affected.`,
          );
        const receipt = await options.management.request(
          'provider.connection.change',
          {
            project: credentialProject.value,
            connection: connection.connection,
            requestId: crypto.randomUUID(),
            expectedRevision: connection.revision,
            action: actionName,
          },
        );
        status.textContent = `Original endpoint receipt confirmed: ${receipt.requestId}.`;
      },
      true,
      endpointActions,
    );
  action(
    'Inspect unconfirmed requests',
    async () => {
      clearSecret();
      intentList = await options.management.pending();
      selectValues(
        pending,
        intentList.map((intent) => ({
          id: intent.requestId,
          name: `${intent.operation} · ${intent.project} · ${intent.requestId}`,
        })),
        '',
      );
    },
    false,
    pendingActions,
  );
  action(
    'Read original receipt',
    async () => {
      clearSecret();
      const intent = intentList.find(
        (value) => value.requestId === pending.value,
      );
      if (!intent)
        throw new ToolConnectionInputRefused('Select an original request.');
      const receipt = await options.management.recover(intent);
      status.textContent = `Original ${receipt.operation} receipt ${receipt.requestId} confirmed. This is historical evidence; refresh present status separately.`;
      await refresh();
    },
    false,
    pendingActions,
  );
  for (const control of [credentialProject, subject, endpoints, assignments])
    control.addEventListener('change', () => {
      cancelReview?.();
      epoch++;
      clearSecret();
      credential = undefined;
      grantText.textContent =
        'Selection changed; inspect current authorization.';
      if (control === endpoints)
        definition.value = currentConnection()
          ? JSON.stringify(currentConnection()?.definition, null, 2)
          : '';
      if (control === assignments) fillAssignment();
      if (control === credentialProject) {
        connections = [];
        endpoints.replaceChildren();
        savedEndpoint = '';
        definition.value = '';
      }
    });
  authorization.addEventListener('click', (event) => {
    if (busy || closed || identity !== key() || !root.open) {
      event.preventDefault();
      clearSecret();
      return;
    }
    // Clear after the anchor's default action has captured its URL; never store it.
    setTimeout(clearSecret, 0);
  });
  root.addEventListener('toggle', () => {
    if (!root.open) cancelReview?.();
    epoch++;
    clearSecret();
    if (!root.open)
      status.textContent = 'Snapshots stale; refresh after opening.';
    drawLocation();
  });
  function update() {
    drawLocation();
    if (identity !== key() || !options.context().connected) {
      cancelReview?.();
      epoch++;
      clearSecret();
      clearSnapshots();
      for (const input of [
        subject,
        scope,
        provider,
        prefix,
        agents,
        grants,
        executor,
      ])
        input.value = '';
      definition.value = '';
      status.textContent =
        'Snapshots stale; refresh on the selected server/account.';
      identity = key();
    }
  }
  update();
  return {
    element: root,
    update,
    destroy() {
      cancelReview?.();
      closed = true;
      epoch++;
      clearSecret();
      clearSnapshots();
    },
  };
}
