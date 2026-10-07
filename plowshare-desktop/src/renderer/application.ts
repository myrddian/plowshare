import type { FileStoreReference } from 'plowshare-client-ts/binding/filestores';
import type { DesktopState, Reply, Request } from '../shared.ts';
import { ownedEvent } from './events.ts';

/** Application landing page and server source editor. Drafts stay in this account/window only. */
export function installApplication(
  host: HTMLElement,
  state: () => DesktopState,
  request: (value: Request) => Promise<Reply>,
  conversations: (project: string) => Promise<void>,
) {
  host.innerHTML = `<header><div><div class="eyebrow">APPLICATION</div><h2 data-title></h2></div><button data-close>Conversations</button></header>
    <p data-summary></p><nav aria-label="Application views"><button data-files>Files</button><button data-runtime>Runtime definitions</button><button data-memory>Memory</button><button data-board>Boards</button><button data-settings>Access</button></nav>
    <section data-source hidden><label data-area-label hidden>File location<select data-area aria-label="Application file location"></select></label><form data-location><label>Relative path<input data-path autocomplete="off" placeholder="Folder or file inside this Application"></label><button data-browse>Browse folder</button><button data-open>Read file</button></form>
    <p data-status role="status"></p><p data-error role="alert" hidden></p><div data-entries></div>
    <section data-editor hidden><h3 data-file-title></h3><p data-permission></p><label>File content<textarea data-text spellcheck="false" aria-label="Application file content"></textarea></label>
    <div class="application-actions"><button data-read-current>Read current file</button><button data-use-current hidden>Use current file and discard draft</button><button data-save>Save file</button></div></section></section>`;
  const get = <T extends HTMLElement = HTMLElement>(selector: string) =>
    host.querySelector<T>(selector)!;
  let project = '',
    identity = '',
    shown = false,
    files = false,
    rendered = '';
  const drafts = new Map<
    string,
    { text: string; revision: string; original: string }
  >();
  let location: FileStoreReference | undefined;
  const selectedLocation = () => (location ? { location } : {});
  const documentKey = () =>
    `${project}\0${JSON.stringify(location ?? null)}\0${state().applicationFiles?.document?.path ?? ''}`;
  const run = (work: () => Promise<unknown>) =>
    ownedEvent(async () => {
      try {
        await work();
      } catch (reason) {
        get('[data-error]').hidden = false;
        get('[data-error]').textContent =
          reason instanceof Error
            ? reason.message
            : 'The Application view could not be opened.';
      }
    });
  get<HTMLSelectElement>('[data-area]').addEventListener(
    'change',
    run(async () => {
      const row = state().projects.find((row) => row.name === project);
      const index = get<HTMLSelectElement>('[data-area]').selectedIndex;
      location =
        index > 0
          ? row?.writableAreas?.filter(
              (area) =>
                area.store !== row.applicationRoot?.store ||
                area.path !== row.applicationRoot?.path,
            )[index - 1]
          : undefined;
      rendered = '';
      await request({
        action: 'application-files',
        project,
        ...selectedLocation(),
      });
    }),
  );
  const close = () => {
    shown = false;
    host.hidden = true;
  };
  get('[data-close]').addEventListener(
    'click',
    run(async () => {
      close();
      await conversations(project);
    }),
  );
  get('[data-files]').addEventListener(
    'click',
    run(async () => {
      files = true;
      render();
      await request({
        action: 'application-files',
        project,
        ...selectedLocation(),
      });
    }),
  );
  get('[data-runtime]').addEventListener(
    'click',
    run(() => request({ action: 'application-runtime', project })),
  );
  get('[data-memory]').addEventListener(
    'click',
    run(() => request({ action: 'library', view: 'memories', project })),
  );
  get('[data-board]').addEventListener(
    'click',
    run(() => request({ action: 'board-inspection', view: 'board', project })),
  );
  get('[data-settings]').addEventListener('click', () =>
    document
      .querySelector<HTMLButtonElement>(
        `[data-project-access="${CSS.escape(project)}"]`,
      )
      ?.click(),
  );
  get<HTMLFormElement>('[data-location]').addEventListener(
    'submit',
    (event) => {
      event.preventDefault();
      run(async () => {
        await request({
          action: 'application-files',
          project,
          ...selectedLocation(),
          path: get<HTMLInputElement>('[data-path]').value,
        });
      })();
    },
  );
  get('[data-open]').setAttribute('type', 'button');
  get('[data-open]').addEventListener(
    'click',
    run(() =>
      request({
        action: 'application-file-read',
        project,
        ...selectedLocation(),
        path: get<HTMLInputElement>('[data-path]').value,
      }),
    ),
  );
  get('[data-read-current]').addEventListener(
    'click',
    run(() =>
      request({
        action: 'application-file-read',
        project,
        ...selectedLocation(),
        path: state().applicationFiles!.document!.path,
      }),
    ),
  );
  get('[data-use-current]').addEventListener('click', () => {
    drafts.delete(documentKey());
    rendered = '';
    render();
  });
  get<HTMLTextAreaElement>('[data-text]').addEventListener('input', () => {
    const draft = drafts.get(documentKey());
    if (draft) draft.text = get<HTMLTextAreaElement>('[data-text]').value;
    render();
  });
  get('[data-save]').addEventListener(
    'click',
    run(async () => {
      const document = state().applicationFiles?.document,
        draft = drafts.get(documentKey());
      if (!document || !draft) return;
      await request({
        action: 'application-file-save',
        project,
        ...selectedLocation(),
        path: document.path,
        text: draft.text,
        revision: draft.revision,
      });
    }),
  );
  function render() {
    const current = state(),
      key = `${current.base}\0${current.handle}`;
    if (key !== identity) {
      identity = key;
      drafts.clear();
      close();
    }
    const row = current.projects.find(
      (row) => row.name === project && row.kind === 'application',
    );
    if (!row || !current.connected) close();
    host.hidden = !shown;
    if (!shown || !row) return;
    get('[data-title]').textContent = row.displayName ?? row.name;
    get('[data-summary]').textContent =
      `${row.applicationRoot ? `${row.applicationRoot.store}/${row.applicationRoot.path} · ` : ''}${row.type === 'DISJOINT' ? 'Source lifecycle managed externally' : 'Source lifecycle managed by Plowshare'} · ${row.role?.toLowerCase() ?? 'access granted'}`;
    const areaSelect = get<HTMLSelectElement>('[data-area]');
    const areaStamp = JSON.stringify([row.applicationRoot, row.writableAreas]);
    if (areaSelect.dataset.stamp !== areaStamp) {
      areaSelect.dataset.stamp = areaStamp;
      areaSelect.replaceChildren();
      const sourceOption = document.createElement('option');
      sourceOption.textContent = 'Application source';
      areaSelect.append(sourceOption);
      for (const area of row.writableAreas ?? []) {
        if (
          area.store === row.applicationRoot?.store &&
          area.path === row.applicationRoot?.path
        )
          continue;
        const option = document.createElement('option');
        option.textContent = `${area.store}/${area.path || ''}`;
        areaSelect.append(option);
      }
      location = undefined;
      rendered = '';
    }
    get('[data-area-label]').hidden = areaSelect.options.length < 2;
    areaSelect.disabled =
      !!current.applicationFiles?.loading || !!current.applicationFiles?.saving;
    get('[data-source]').hidden = !files;
    const value =
      current.applicationFiles?.project === project &&
      current.applicationFiles.location?.store === location?.store &&
      current.applicationFiles.location?.path === location?.path
        ? current.applicationFiles
        : undefined;
    get('[data-error]').hidden = !value?.error;
    get('[data-error]').textContent = value?.error ?? '';
    get('[data-status]').textContent = value?.saving
      ? 'Saving…'
      : value?.loading
        ? 'Reading server files…'
        : value?.listing
          ? `${value.listing.path || '/'}${value.listing.more ? ' · First 200 entries; use a relative path for other files.' : value.listing.entries.length ? '' : ' · This directory is empty.'}`
          : '';
    const entries = get('[data-entries]'),
      listing = value?.listing;
    const stamp = JSON.stringify([project, listing]);
    if (entries.dataset.stamp !== stamp) {
      get<HTMLInputElement>('[data-path]').value = listing?.path ?? '';
      entries.dataset.stamp = stamp;
      entries.replaceChildren();
      if (listing?.path) {
        const up = document.createElement('button');
        up.textContent = 'Parent folder';
        up.addEventListener(
          'click',
          run(() =>
            request({
              action: 'application-files',
              project,
              ...selectedLocation(),
              path: listing.path.includes('/')
                ? listing.path.slice(0, listing.path.lastIndexOf('/'))
                : '',
            }),
          ),
        );
        entries.append(up);
      }
      for (const entry of listing?.entries ?? []) {
        const button = document.createElement('button');
        button.textContent = `${entry.directory ? 'Folder · ' : ''}${entry.name}`;
        button.dataset.applicationPath = entry.path;
        button.addEventListener(
          'click',
          run(() =>
            request({
              action: entry.directory
                ? 'application-files'
                : 'application-file-read',
              project,
              ...selectedLocation(),
              path: entry.path,
            }),
          ),
        );
        entries.append(button);
      }
    }
    for (const button of host.querySelectorAll<HTMLButtonElement>(
      '[data-entries] button, [data-files], [data-browse], [data-open], [data-read-current]',
    ))
      button.disabled = !!value?.loading || !!value?.saving;
    const source = value?.document;
    get('[data-editor]').hidden = !source;
    if (!source || !value) {
      rendered = '';
      return;
    }
    const draftKey = documentKey();
    let draft = drafts.get(draftKey);
    if (!draft || draft.text === draft.original || source.text === draft.text) {
      draft = {
        text: source.text,
        original: source.text,
        revision: source.revision,
      };
      drafts.set(draftKey, draft);
    }
    const changed = draft.revision !== source.revision;
    const editor = get<HTMLTextAreaElement>('[data-text]');
    if (rendered !== draftKey)
      get<HTMLInputElement>('[data-path]').value = source.path;
    if (rendered !== draftKey || editor.value !== draft.text)
      editor.value = draft.text;
    rendered = draftKey;
    editor.readOnly = !source.writable || value.saving;
    get('[data-file-title]').textContent = source.path;
    get('[data-permission]').textContent = changed
      ? 'The server file changed. Your draft is retained; read and review the current file before saving.'
      : source.writable
        ? 'Write allowed for this file.'
        : 'Read only for your account or this workspace path.';
    get<HTMLButtonElement>('[data-save]').disabled =
      !source.writable ||
      value.saving ||
      value.uncertain === true ||
      changed ||
      draft.text === source.text;
    get('[data-use-current]').hidden = !changed;
  }
  return {
    render,
    close,
    get visible() {
      return shown;
    },
    open(name: string) {
      if (project !== name) {
        location = undefined;
        get<HTMLSelectElement>('[data-area]').selectedIndex = 0;
      }
      project = name;
      shown = true;
      files = false;
      rendered = '';
      render();
    },
  };
}
