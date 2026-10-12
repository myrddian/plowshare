import { ApplicationIdentityClient } from '../../../sdk/typescript/src/operations/application-identity-client.ts';
import { checkedTransport } from '../../../sdk/typescript/src/operations/transport.ts';
import { decodeReply } from '../../../sdk/typescript/src/operations/schema.ts';
import type { EventStream } from '../events.ts';
import { background } from '../background.ts';
import { button, el, input, labelled } from './dom.ts';

/** Uses the authenticated screen socket. Storage retains request metadata only, never secrets. */
export function applicationIdentityControls(
  stream: () => EventStream | null,
  project: string,
) {
  const root = document.createElement('details');
  root.className = 'application-identity';
  root.append(el('summary', '', 'Execution identity and credential'));
  const inspect = button('', 'Inspect identity'),
    issue = button('', 'Issue or rotate credential'),
    recover = button('', 'Read pending receipt'),
    dismiss = button('', 'Dismiss credential');
  const days = input('Credential lifetime in days', '1–365');
  days.type = 'number';
  days.min = '1';
  days.max = '365';
  days.value = '30';
  const rotate = document.createElement('input');
  rotate.type = 'checkbox';
  const secret = input('New service credential', '');
  secret.readOnly = true;
  secret.autocomplete = 'off';
  secret.hidden = true;
  const status = el('p', '', ''),
    error = el('p', 'error', '');
  error.setAttribute('role', 'alert');
  root.append(
    inspect,
    status,
    labelled('Credential lifetime in days', days),
    labelled('Rotate and invalidate the previous secret', rotate),
    el(
      'p',
      '',
      'Credentials appear once. After confirmed lost delivery, explicitly rotate the same retained token.',
    ),
    recover,
    issue,
    labelled('New service credential', secret),
    dismiss,
    error,
  );
  let key = '',
    closed = false,
    busy = false;
  const clear = () => {
    secret.value = '';
    secret.hidden = true;
  };
  const wire = checkedTransport({
    async ask(type, payload) {
      const socket = stream();
      if (closed || socket === null || socket.status().state !== 'open')
        throw new Error('Connect to inspect the retained identity.');
      return socket.ask(type, payload);
    },
  });
  const client = new ApplicationIdentityClient(wire, {
    load() {
      const value = localStorage.getItem(key);
      return value === null ? undefined : (JSON.parse(value) as unknown);
    },
    save(value) {
      if (!key) throw new Error('Administrator identity is not confirmed.');
      if (value) localStorage.setItem(key, JSON.stringify(value));
      else localStorage.removeItem(key);
    },
  });
  async function run(work: () => Promise<void>) {
    if (closed || busy) return;
    busy = true;
    clear();
    error.textContent = '';
    for (const control of [inspect, issue, recover, days, rotate])
      control.disabled = true;
    try {
      const authority = await wire.ask('admin.status', {});
      if (authority.code !== 'OK')
        throw new Error(authority.said ?? 'Administrator status refused.');
      const admin = decodeReply('admin.status', authority.payload);
      if (!admin.serverAdmin)
        throw new Error(
          'Connect as a server administrator to manage Application credentials.',
        );
      key = `plowshare.application-credential.${location.origin}.${admin.handle}`;
      if (closed) return;
      await work();
      if (!closed && client.pending())
        status.textContent = `Pending credential request ${client.pending()?.requestId}. Read its receipt before another issuance.`;
    } catch (failure) {
      clear();
      if (!closed)
        error.textContent =
          failure instanceof Error
            ? failure.message
            : 'Identity command failed. Inspect its receipt.';
    } finally {
      busy = false;
      for (const control of [inspect, issue, recover, days, rotate])
        control.disabled = false;
    }
  }
  inspect.addEventListener('click', () =>
    background(
      run(async () => {
        const identity = (await client.status(project)).identity;
        if (closed) return;
        status.textContent = identity
          ? `${identity.service.handle} · ${identity.principal} · ${identity.state} · ${identity.credentialIssued ? 'credential issued' : 'credential reserved'}`
          : 'Manual execution identity; use its existing service-account controls.';
      }),
    ),
  );
  recover.addEventListener('click', () =>
    background(
      run(async () => {
        const receipt = await client.recover();
        if (closed) return;
        status.textContent = `Request confirmed for ${receipt.project}, ${receipt.principal}. Explicitly rotate if the secret was lost.`;
      }),
    ),
  );
  issue.addEventListener('click', () =>
    background(
      run(async () => {
        const value = await client.issue({
          project,
          requestId: crypto.randomUUID(),
          expiresInDays: Number(days.value),
          rotate: rotate.checked,
        });
        if (closed || !root.open) return;
        secret.value = value.credential;
        secret.hidden = false;
        status.textContent = `Save this credential now. It expires ${value.receipt.expiresAt}.`;
      }),
    ),
  );
  dismiss.addEventListener('click', clear);
  root.addEventListener('toggle', () => {
    if (!root.open) clear();
  });
  return {
    element: root,
    destroy() {
      closed = true;
      clear();
    },
  };
}
