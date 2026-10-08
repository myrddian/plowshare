import type { FileStoreReference } from 'plowshare-client-ts/binding/filestores';
import { fileStoreReference } from 'plowshare-client-ts/binding/filestores';
import type { FileStoreCatalog } from 'plowshare-client-ts/operations/server-filestores';

/** Server choices come from authenticated discovery; relative paths remain operator input. */
export function fileStorePlacement(
  host: HTMLElement,
  load: () => Promise<FileStoreCatalog>,
  requireSourcePath = true,
) {
  host.classList.add('filestore-placement');
  host.innerHTML = `<label>Server FileStore<select data-store required disabled><option value="">Load server FileStores</option></select></label>
    <label>Relative source path<input data-path required placeholder="my-app"></label>
    <button type="button" data-reload-stores>Reload FileStores</button><p data-stores-status role="status"></p><div data-area-list></div>
    <button type="button" data-add-area>Add writable area</button>`;
  const source = host.querySelector<HTMLSelectElement>('[data-store]')!;
  const path = host.querySelector<HTMLInputElement>('[data-path]')!;
  // Deployment needs a child path for retained releases; registration can name a store root.
  path.required = requireSourcePath;
  const areas = host.querySelector<HTMLElement>('[data-area-list]')!;
  const status = host.querySelector<HTMLElement>('[data-stores-status]')!;
  const add = host.querySelector<HTMLButtonElement>('[data-add-area]')!;
  const reload = host.querySelector<HTMLButtonElement>('[data-reload-stores]')!;
  let catalog: FileStoreCatalog | undefined;
  let generation = 0;
  const options = (
    select: HTMLSelectElement,
    manager: boolean,
    chosen = '',
  ) => {
    select.replaceChildren(new Option('Choose a FileStore', ''));
    for (const store of catalog?.stores ?? []) {
      if (manager && store.role !== 'MANAGER') continue;
      select.append(new Option(store.alias, store.alias));
    }
    const choices = Array.from(select.options).filter((option) => option.value);
    select.value = choices.some((option) => option.value === chosen)
      ? chosen
      : !chosen && choices.length === 1
        ? choices[0]!.value
        : '';
    select.disabled = choices.length === 0;
  };
  const area = (value?: FileStoreReference) => {
    if (areas.children.length >= 100)
      throw new Error('Use at most 100 writable areas.');
    const row = document.createElement('div');
    row.className = 'filestore-area';
    row.dataset.area = '';
    row.innerHTML = `<label>Writable FileStore<select required data-area-store></select></label><label>Relative writable path<input data-area-path placeholder="reports/my-app"></label><button type="button">Remove area</button>`;
    const select = row.querySelector<HTMLSelectElement>('select')!;
    options(select, false, value?.store);
    row.querySelector<HTMLInputElement>('input')!.value = value?.path ?? '';
    row.querySelector('button')!.addEventListener('click', () => row.remove());
    areas.append(row);
  };
  add.addEventListener('click', () => {
    if (catalog?.stores.length && areas.children.length < 100) area();
  });
  const read = (
    select: HTMLSelectElement,
    input: HTMLInputElement,
    manager: boolean,
  ): FileStoreReference => {
    const store = catalog?.stores.find((entry) => entry.alias === select.value);
    if (!store || (manager && store.role !== 'MANAGER'))
      throw new Error('Choose a granted server FileStore.');
    const value = { store: store.alias, path: input.value };
    if (!fileStoreReference(value))
      throw new Error('Use a canonical relative path without traversal.');
    return value;
  };
  return {
    reload,
    reset() {
      generation++;
      catalog = undefined;
      source.replaceChildren(new Option('Load server FileStores', ''));
      source.disabled = true;
      path.value = '';
      areas.replaceChildren();
      status.textContent = '';
      add.disabled = true;
    },
    async load() {
      const current = ++generation;
      catalog = undefined;
      source.disabled = true;
      add.disabled = true;
      reload.disabled = true;
      for (const select of areas.querySelectorAll<HTMLSelectElement>('select'))
        select.disabled = true;
      status.textContent = 'Loading server FileStores…';
      try {
        const result = await load();
        if (current !== generation) return;
        catalog = result;
        options(source, true, source.value);
        for (const select of areas.querySelectorAll<HTMLSelectElement>(
          'select',
        ))
          options(select, false, select.value);
        add.disabled = result.stores.length === 0;
        status.textContent = result.stores.some(
          (store) => store.role === 'MANAGER',
        )
          ? ''
          : 'No FileStores grant you MANAGER access. Ask the server operator to configure a grant.';
      } catch (error) {
        if (current !== generation) return;
        source.replaceChildren(new Option('FileStores unavailable', ''));
        status.textContent =
          'Server FileStores could not be loaded. Reload before submitting.';
        throw error;
      } finally {
        if (current === generation) reload.disabled = false;
      }
    },
    set(
      destination: FileStoreReference,
      writableAreas: readonly FileStoreReference[],
    ) {
      options(source, true, destination.store);
      path.value = destination.path;
      areas.replaceChildren();
      for (const value of writableAreas) area(value);
    },
    read() {
      if (!catalog)
        throw new Error('Load server FileStores before submitting.');
      const destination = read(source, path, true);
      if (requireSourcePath && !destination.path)
        throw new Error('Specify a relative Application source path.');
      return {
        destination,
        writableAreas: Array.from(
          areas.querySelectorAll<HTMLElement>('[data-area]'),
        ).map((row) =>
          read(
            row.querySelector<HTMLSelectElement>('select')!,
            row.querySelector<HTMLInputElement>('input')!,
            false,
          ),
        ),
      };
    },
  };
}
