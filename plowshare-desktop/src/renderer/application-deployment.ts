import type { Payloads } from 'plowshare-client-ts/operations/direct';
import { decodePayload } from 'plowshare-client-ts/operations/schema';
import { fileStorePlacement } from './server-filestores.ts';
import type {
  ApplicationSourceFile,
  ApplicationDeploymentStatus,
} from 'plowshare-client-ts/operations/application-deployments';
import type { DesktopState, Reply, Request } from '../shared.ts';
import { ownedEvent } from './events.ts';

/** Human deployment UI. The exact pending DTO survives reload for read-only receipt recovery. */
export function installApplicationDeployment(
  state: () => DesktopState,
  request: (value: Request) => Promise<Reply>,
) {
  const dialog = document.createElement('dialog');
  dialog.id = 'application-deployment-dialog';
  dialog.setAttribute('aria-label', 'Deploy an Application');
  dialog.innerHTML = `<form><h2>Deploy an Application</h2><p>Choose a folder with plowshare.json. Deploy its source to this server and retain the project’s work across updates.</p>
    <button type="button" data-choose>Choose source folder</button><p data-package>No source selected.</p>
    <label>Application project<input data-project required></label><div data-placement></div><p>Leave writable areas blank for read-only runtime files.</p>
    <button type="button" data-status>Read deployment status</button><p data-active role="status">Read status before updating an existing deployment.</p>
    <label>Retained revision<select data-revision><option value="">No revisions loaded</option></select></label><button type="button" data-activate>Activate selected revision</button>
    <p data-request></p><p data-result role="status"></p><p data-error role="alert" hidden></p>
    <div class="dialog-actions"><button type="button" data-close>Close</button><button type="button" data-recover>Read pending receipt</button><button type="button" data-new>New submission</button><button type="submit">Deploy source</button></div></form>`;
  document.body.append(dialog);
  const get = <T extends HTMLElement = HTMLElement>(selector: string) =>
    dialog.querySelector<T>(selector)!;
  let files: readonly ApplicationSourceFile[] = [],
    folder = '',
    status: ApplicationDeploymentStatus | undefined;
  let pending:
    | Payloads['application.deploy']
    | Payloads['application.activate']
    | undefined;
  let busy = false,
    unreadablePending = false;
  const key = () =>
    `plowshare.application-deployment.${state().base}.${state().handle}`;
  let draftKey = '';
  const call = async (value: Request): Promise<Reply> => {
    if (key() !== draftKey)
      throw new Error('The deployment connection changed.');
    const reply = await request(value);
    if (key() !== draftKey)
      throw new Error(
        'The deployment connection changed; reconcile the receipt on its original server.',
      );
    return reply;
  };
  const placement = fileStorePlacement(get('[data-placement]'), async () => {
    const reply = await call({ action: 'server-filestore-list' });
    if (!reply.serverFileStores)
      throw new Error('Server FileStore catalogue was unreadable.');
    return reply.serverFileStores;
  });
  const show = (selector: string, text: string) => {
    get(selector).textContent = text;
  };
  const save = () => {
    if (pending) localStorage.setItem(draftKey, JSON.stringify(pending));
    else localStorage.removeItem(draftKey);
  };
  const run = (work: () => void | Promise<void>) =>
    ownedEvent(async () => {
      if (busy) return;
      busy = true;
      get('[data-error]').hidden = true;
      show('[data-result]', 'Working…');
      for (const button of dialog.querySelectorAll<HTMLButtonElement>('button'))
        button.disabled = true;
      try {
        await work();
        if (get('[data-result]').textContent === 'Working…')
          show('[data-result]', 'Ready.');
      } catch (error) {
        show(
          '[data-error]',
          error instanceof Error
            ? error.message
            : 'Deployment could not be read or submitted.',
        );
        get('[data-error]').hidden = false;
      } finally {
        busy = false;
        for (const button of dialog.querySelectorAll<HTMLButtonElement>(
          'button',
        ))
          button.disabled = false;
      }
    });
  const project = () => get<HTMLInputElement>('[data-project]').value;
  const readStatus = async () => {
    const reply = await call({
      action: 'application-deployment',
      operation: 'application.deployment.status',
      payload: { project: project() },
    });
    if (!reply.deployment || !('activeRevision' in reply.deployment))
      throw new Error('Deployment status was unreadable.');
    status = reply.deployment;
    show(
      '[data-active]',
      status.activeRevision
        ? `Active revision: ${status.activeRevision}`
        : 'No active deployment. First deployment is available.',
    );
    const select = get<HTMLSelectElement>('[data-revision]');
    select.replaceChildren();
    for (const release of status.releases) {
      const option = document.createElement('option');
      option.value = release.revision;
      option.textContent = `${release.revision} · ${release.fileCount} files`;
      select.append(option);
    }
  };
  document.querySelector('#application-deploy-open')!.addEventListener(
    'click',
    run(async () => {
      draftKey = key();
      placement.reset();
      files = [];
      folder = '';
      status = undefined;
      pending = undefined;
      unreadablePending = false;
      show('[data-package]', 'No source selected.');
      show('[data-request]', '');
      show('[data-active]', 'Read deployment status before submitting.');
      get<HTMLSelectElement>('[data-revision]').replaceChildren();
      dialog.showModal();
      const stored = localStorage.getItem(key());
      if (stored) {
        try {
          const value: unknown = JSON.parse(stored);
          try {
            pending = decodePayload('application.deploy', value);
          } catch {
            pending = decodePayload('application.activate', value);
          }
        } catch {
          unreadablePending = true;
          throw new Error(
            'The saved submission is unreadable. Inspect its receipt using the CLI before deliberately choosing New submission.',
          );
        }
        get<HTMLInputElement>('[data-project]').value = pending.project;
        show(
          '[data-request]',
          `Pending request: ${pending.requestId}. Read its receipt before another submission.`,
        );
      }
      await placement.load();
      if (pending && 'destination' in pending)
        placement.set(pending.destination, pending.writableAreas);
    }),
  );
  placement.reload.addEventListener(
    'click',
    run(() => placement.load()),
  );
  get('[data-close]').addEventListener('click', () => dialog.close());
  get('[data-choose]').addEventListener(
    'click',
    run(async () => {
      if (pending || unreadablePending)
        throw new Error(
          'Read the pending receipt or deliberately start a new submission first.',
        );
      const reply = await call({ action: 'application-package' });
      if (!reply.applicationPackage) return;
      files = reply.applicationPackage.files;
      folder = reply.applicationPackage.folder;
      show(
        '[data-package]',
        `${folder} · ${files.length} source files ready to review.`,
      );
    }),
  );
  get('[data-status]').addEventListener('click', run(readStatus));
  get('[data-recover]').addEventListener(
    'click',
    run(async () => {
      if (!pending) throw new Error('No pending deployment request.');
      const reply = await call({
        action: 'application-deployment',
        operation: 'application.deployment.receipt',
        payload: { project: pending.project, requestId: pending.requestId },
      });
      if (!reply.deployment || !('requestId' in reply.deployment))
        throw new Error('Deployment receipt was unreadable.');
      show(
        '[data-result]',
        `Committed revision: ${reply.deployment.release.revision}`,
      );
      pending = undefined;
      save();
      await readStatus();
    }),
  );
  get('[data-new]').addEventListener(
    'click',
    run(async () => {
      pending = undefined;
      unreadablePending = false;
      save();
      show('[data-request]', 'New submission selected.');
      await readStatus();
    }),
  );
  get('form').addEventListener('submit', (event) => {
    event.preventDefault();
    run(async () => {
      if (pending || unreadablePending)
        throw new Error(
          'A pending request must be reconciled before submitting again.',
        );
      if (!files.length || !folder)
        throw new Error('Choose an Application source folder.');
      if (!status || status.project !== project())
        throw new Error('Read this project’s deployment status first.');
      const payload = decodePayload('application.deploy', {
        project: project(),
        requestId: crypto.randomUUID(),
        expectedRevision: status.activeRevision,
        ...placement.read(),
        files,
      });
      pending = payload;
      save();
      show('[data-request]', `Request: ${payload.requestId}`);
      const reply = await call({
        action: 'application-deployment',
        operation: 'application.deploy',
        payload,
      });
      if (!reply.deployment || !('requestId' in reply.deployment))
        throw new Error(
          'Submission outcome is unknown. Read the retained receipt.',
        );
      show(
        '[data-result]',
        `Deployed revision: ${reply.deployment.release.revision}`,
      );
      pending = undefined;
      save();
      await readStatus();
    })();
  });
  get('[data-activate]').addEventListener(
    'click',
    run(async () => {
      if (pending || unreadablePending)
        throw new Error(
          'Read the pending receipt before activating another revision.',
        );
      if (!status?.activeRevision || status.project !== project())
        throw new Error('Read this deployment’s active revision first.');
      const payload = decodePayload('application.activate', {
        project: project(),
        requestId: crypto.randomUUID(),
        expectedRevision: status.activeRevision,
        revision: get<HTMLSelectElement>('[data-revision]').value,
      });
      pending = payload;
      save();
      show('[data-request]', `Request: ${payload.requestId}`);
      const reply = await call({
        action: 'application-deployment',
        operation: 'application.activate',
        payload,
      });
      if (!reply.deployment || !('requestId' in reply.deployment))
        throw new Error(
          'Activation outcome is unknown. Read the retained receipt.',
        );
      show(
        '[data-result]',
        `Activated revision: ${reply.deployment.release.revision}`,
      );
      pending = undefined;
      save();
      await readStatus();
    }),
  );
  return {
    update: () => {
      if (draftKey && draftKey !== key()) {
        dialog.close();
        placement.reset();
        files = [];
        pending = undefined;
        status = undefined;
      }
      document.querySelector<HTMLElement>('#application-deploy-open')!.hidden =
        !state().connected || !state().serverAdmin;
    },
  };
}
