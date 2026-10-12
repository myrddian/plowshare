import { ApplicationIdentityClient } from 'plowshare-client-ts/operations/application-identity-client';
import { decodeRequest } from 'plowshare-client-ts/operations/schema';
import type { DesktopState, Reply, Request } from '../shared.ts';
import { ownedEvent } from './events.ts';

/** Explicit human control. Storage contains only pending request metadata; close clears the secret. */
export function applicationIdentityDialog(
  state: () => DesktopState,
  request: (value: Request) => Promise<Reply>,
) {
  const dialog = document.createElement('dialog');
  dialog.setAttribute('aria-label', 'Application execution identity');
  dialog.innerHTML = `<form><h2>Application execution identity</h2><label>Application project<input data-project required></label>
    <button type="button" data-status>Inspect identity</button><p data-identity role="status"></p>
    <label>Credential lifetime in days<input data-days type="number" min="1" max="365" value="30" required></label>
    <label><input data-rotate type="checkbox"> Rotate the existing credential, invalidating its previous secret</label>
    <p data-pending></p><button type="button" data-recover>Read pending receipt</button>
    <p>Issuance reveals a secret once. After confirmed lost delivery, explicitly rotate the same retained token.</p>
    <section data-secret-area hidden><label>New service credential<input data-secret readonly autocomplete="off"></label><button type="button" data-dismiss>Dismiss credential</button></section>
    <p data-result role="status"></p><p data-error role="alert"></p>
    <button type="submit">Issue or rotate credential</button><button type="button" data-close>Close</button></form>`;
  document.body.append(dialog);
  const get = <T extends HTMLElement = HTMLElement>(selector: string) =>
    dialog.querySelector<T>(selector)!;
  const clearSecret = () => {
    get<HTMLInputElement>('[data-secret]').value = '';
    get('[data-secret-area]').hidden = true;
  };
  let key = '',
    busy = false;
  const currentKey = () =>
    `plowshare.application-credential.${state().base}.${state().handle}`;
  const client = new ApplicationIdentityClient(
    {
      async ask(type, payload) {
        if (key !== currentKey())
          throw new Error(
            'Administrator connection changed; reconcile on the original server.',
          );
        const decoded = decodeRequest(type, payload);
        let call: Request;
        if (decoded.type === 'application.identity.status')
          call = {
            action: 'application-identity',
            operation: decoded.type,
            payload: decoded.payload,
          };
        else if (decoded.type === 'application.token.receipt')
          call = {
            action: 'application-identity',
            operation: decoded.type,
            payload: decoded.payload,
          };
        else if (decoded.type === 'application.token.issue')
          call = {
            action: 'application-identity',
            operation: decoded.type,
            payload: decoded.payload,
          };
        else throw new Error('Unsupported identity command.');
        const reply = await request(call);
        if (key !== currentKey()) {
          clearSecret();
          throw new Error(
            'Administrator connection changed; inspect the original receipt.',
          );
        }
        return (
          reply.applicationIdentityRefusal ?? {
            code: 'OK',
            payload: reply.applicationIdentity,
          }
        );
      },
    },
    {
      load() {
        const value = localStorage.getItem(key);
        return value === null ? undefined : (JSON.parse(value) as unknown);
      },
      save(value) {
        if (value) localStorage.setItem(key, JSON.stringify(value));
        else localStorage.removeItem(key);
      },
    },
  );
  const project = () => get<HTMLInputElement>('[data-project]').value;
  const showPending = () => {
    const pending = client.pending();
    get('[data-pending]').textContent = pending
      ? `Pending request: ${pending.requestId}`
      : 'No pending credential request.';
  };
  const run = (work: () => Promise<void>) =>
    ownedEvent(async () => {
      if (busy) return;
      busy = true;
      get('[data-error]').textContent = '';
      for (const button of dialog.querySelectorAll<HTMLButtonElement>('button'))
        button.disabled = true;
      try {
        await work();
        showPending();
      } catch (error) {
        clearSecret();
        get('[data-error]').textContent =
          error instanceof Error
            ? error.message
            : 'Identity command failed; inspect its receipt.';
      } finally {
        busy = false;
        for (const button of dialog.querySelectorAll<HTMLButtonElement>(
          'button',
        ))
          button.disabled = false;
      }
    });
  get('[data-status]').addEventListener(
    'click',
    run(async () => {
      clearSecret();
      const status = await client.status(project());
      const identity = status.identity;
      get('[data-identity]').textContent = identity
        ? `${identity.service.handle} · ${identity.service.role} · ${identity.principal} · ${identity.state} · ${identity.credentialIssued ? 'credential issued' : 'credential reserved'}`
        : 'Manual execution identity. Continue using its existing service-account controls.';
    }),
  );
  get('[data-recover]').addEventListener(
    'click',
    run(async () => {
      clearSecret();
      const receipt = await client.recover();
      get<HTMLInputElement>('[data-project]').value = receipt.project;
      get('[data-result]').textContent =
        `Credential request confirmed for ${receipt.principal}. If its secret was lost, select rotation and issue a new credential.`;
    }),
  );
  get('form').addEventListener('submit', (event) => {
    event.preventDefault();
    run(async () => {
      clearSecret();
      const value = await client.issue({
        project: project(),
        requestId: crypto.randomUUID(),
        expiresInDays: Number(get<HTMLInputElement>('[data-days]').value),
        rotate: get<HTMLInputElement>('[data-rotate]').checked,
      });
      if (!dialog.open) return;
      get<HTMLInputElement>('[data-secret]').value = value.credential;
      get('[data-secret-area]').hidden = false;
      get('[data-result]').textContent =
        `Credential issued for ${value.receipt.principal}, expiring ${value.receipt.expiresAt}. Save it now.`;
    })();
  });
  get('[data-dismiss]').addEventListener('click', clearSecret);
  get('[data-close]').addEventListener('click', () => dialog.close());
  dialog.addEventListener('close', clearSecret);
  return {
    open(selected: string) {
      key = currentKey();
      clearSecret();
      get<HTMLInputElement>('[data-project]').value = selected;
      get('[data-error]').textContent = '';
      showPending();
      dialog.showModal();
    },
    update() {
      if (key && key !== currentKey()) {
        clearSecret();
        dialog.close();
      }
    },
  };
}
