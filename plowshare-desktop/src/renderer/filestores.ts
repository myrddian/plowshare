import type { DesktopState, Reply, Request } from '../shared.ts';
import { ownedEvent } from './events.ts';
import { errorMessage } from 'plowshare-client-ts/binding/values';

/** FileStore registration belongs to this computer, independently of the selected server/account. */
export function installFileStores(request: (value: Request) => Promise<Reply>) {
  const get = <T extends HTMLElement = HTMLElement>(id: string) =>
    document.querySelector<T>(id)!;
  const dialog = get<HTMLDialogElement>('#filestore-dialog');
  const banner = get('#filestore-notice');
  const status = get('#filestore-status');
  const form = get<HTMLFormElement>('#filestore-form');
  const message = get('#filestore-error');
  let busy = false;
  const open = () => {
    message.hidden = true;
    dialog.showModal();
  };
  get('#filestore-open').addEventListener('click', open);
  get('#filestore-manage').addEventListener('click', open);
  get('#filestore-close').addEventListener('click', () => dialog.close());
  const run = (work: () => Promise<void>) =>
    ownedEvent(async () => {
      if (busy) return;
      busy = true;
      status.textContent = 'Updating local FileStores…';
      message.hidden = true;
      get<HTMLButtonElement>('#filestore-create').disabled = true;
      get<HTMLButtonElement>('#filestore-reload').disabled = true;
      try {
        await work();
      } catch (error) {
        message.textContent = errorMessage(error);
        message.hidden = false;
      } finally {
        busy = false;
        get<HTMLButtonElement>('#filestore-create').disabled = false;
        get<HTMLButtonElement>('#filestore-reload').disabled = false;
      }
    });
  get('#filestore-reload').addEventListener(
    'click',
    run(async () => {
      await request({ action: 'filestore-load' });
    }),
  );
  form.addEventListener(
    'submit',
    run(async () => {
      const reply = await request({
        action: 'filestore-setup',
        alias: get<HTMLInputElement>('#filestore-alias').value,
        root: get<HTMLInputElement>('#filestore-root').value,
      });
      if (reply.state.localFileStores?.status === 'loaded') dialog.close();
    }),
  );
  // Prevent normal form navigation even when validation or an asynchronous save fails.
  form.addEventListener('submit', (event) => event.preventDefault());
  get('#filestore-browse').addEventListener(
    'click',
    run(async () => {
      const reply = await request({ action: 'filestore-choose' });
      if (reply.fileStoreRoot)
        get<HTMLInputElement>('#filestore-root').value = reply.fileStoreRoot;
    }),
  );
  return {
    update(state: DesktopState) {
      const stores = state.localFileStores;
      banner.hidden = stores?.status === 'loaded';
      get('#filestore-notice-text').textContent =
        stores?.message ?? 'Checking local FileStores…';
      status.textContent = stores
        ? `${stores.message} Definition: ${stores.registryPath}`
        : 'Checking local FileStores…';
      form.hidden = stores?.status !== 'needs-setup';
      const list = get('#filestore-list');
      list.replaceChildren(
        ...(stores?.stores ?? []).map((row) => {
          const item = document.createElement('li');
          item.textContent = `${row.alias}${row.alias === stores?.defaultStore ? ' (default)' : ''}: ${row.root}`;
          return item;
        }),
      );
    },
  };
}
