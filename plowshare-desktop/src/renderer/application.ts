import type { FileStoreReference } from 'plowshare-client-ts/binding/filestores';
import type { DesktopState, Reply, Request } from '../shared.ts';
import { ownedEvent } from './events.ts';

const hubViews = [
  'overview',
  'conversations',
  'schedules',
  'relay',
  'files',
  'access',
] as const;
type HubView = (typeof hubViews)[number];
const isHubView = (value: string): value is HubView =>
  (hubViews as readonly string[]).includes(value);

/**
 * Application workflow hub and server source editor.
 * `open` lands on Overview with the file browser closed. Drafts stay in this
 * account and window only. Runtime, Memory and Boards stay on Overview;
 * Conversations returns to the chat stage.
 */
export function installApplication(
  host: HTMLElement,
  state: () => DesktopState,
  request: (value: Request) => Promise<Reply>,
  conversations: (project: string) => Promise<void>,
  openSchedules?: (project: string) => void,
  openRelay?: (project: string) => void,
) {
  host.innerHTML = `<header><div><div class="eyebrow">APPLICATION</div><h2 data-title></h2></div><button type="button" data-close>Conversations</button></header>
    <p data-summary></p><nav aria-label="Application views" data-hub-nav><button type="button" data-hub="overview">Overview</button><button type="button" data-hub="conversations">Conversations</button><button type="button" data-hub="schedules">Schedules</button><button type="button" data-hub="relay">Relay</button><button type="button" data-hub="files" data-files>Files</button><button type="button" data-hub="access" data-settings>Access</button></nav>
    <section data-overview><div data-overview-tiles><button type="button" data-hub="conversations"><strong>Conversations</strong><small>Chat scoped to this Application</small></button><button type="button" data-hub="schedules"><strong>Schedules</strong><small>Scheduled work that names this Application</small></button><button type="button" data-hub="relay"><strong>Relay</strong><small>Relay log scoped to this Application</small></button><button type="button" data-hub="files"><strong>Files</strong><small>Server source for this Application</small></button><button type="button" data-hub="access"><strong>Access</strong><small>Membership for this Application</small></button></div>
    <div class="application-secondary" aria-label="More Application surfaces"><button type="button" data-runtime>Runtime definitions</button><button type="button" data-memory>Memory</button><button type="button" data-board>Boards</button></div></section>
    <section data-schedules hidden><p>Scheduled work opens for this Application. Rows that name it stay in that list; a page with none says so.</p></section>
    <section data-relay hidden><p>Relay opens with this Application selected as its scope.</p></section>
    <section data-access hidden><p>Membership for this Application opens in Access.</p></section>
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
  let view: HubView = 'overview';
  const drafts = new Map<
    string,
    { text: string; revision: string; original: string }
  >();
  let location: FileStoreReference | undefined;
  const selectedLocation = () => (location ? { location } : {});
  const documentKey = () =>
    `${project}\0${JSON.stringify(location ?? null)}\0${state().applicationFiles?.document?.path ?? ''}`;
  const run = (work: () => Promise<unknown> | void) =>
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
  const show = (next: HubView) => {
    view = next;
    files = next === 'files';
    render();
  };
  const returnToConversations = () => {
    close();
    return conversations(project);
  };
  const useProject = (name: string) => {
    if (project !== name) {
      location = undefined;
      get<HTMLSelectElement>('[data-area]').selectedIndex = 0;
    }
    project = name;
    shown = true;
    rendered = '';
  };
  // Same path as the Files control. Do not click that button: render disables
  // it while this Application's file load or save is in flight.
  const openFileBrowser = async () => {
    show('files');
    await request({
      action: 'application-files',
      project,
      ...selectedLocation(),
    });
  };
  get('[data-close]').addEventListener('click', run(returnToConversations));
  host.addEventListener('click', (event) => {
    const hub = (event.target as HTMLElement | null)?.closest<HTMLElement>(
      '[data-hub]',
    )?.dataset.hub;
    if (!hub || !isHubView(hub)) return;
    if (hub === 'conversations') {
      run(returnToConversations)();
      return;
    }
    if (hub === 'access') {
      show('access');
      document
        .querySelector<HTMLButtonElement>(
          `[data-project-access="${CSS.escape(project)}"]`,
        )
        ?.click();
      return;
    }
    if (hub === 'files') {
      run(openFileBrowser)();
      return;
    }
    if (hub === 'schedules') {
      run(() => {
        show('schedules');
        openSchedules?.(project);
      })();
      return;
    }
    if (hub === 'relay') {
      run(() => {
        show('relay');
        openRelay?.(project);
      })();
      return;
    }
    show('overview');
  });
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
    get('[data-overview]').hidden = view !== 'overview';
    get('[data-schedules]').hidden = view !== 'schedules';
    get('[data-relay]').hidden = view !== 'relay';
    get('[data-access]').hidden = view !== 'access';
    for (const button of host.querySelectorAll<HTMLButtonElement>(
      '[data-hub]',
    )) {
      if (button.dataset.hub === view)
        button.setAttribute('aria-current', 'page');
      else button.removeAttribute('aria-current');
    }
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
      useProject(name);
      view = 'overview';
      files = false;
      render();
    },
    openFiles(name: string) {
      useProject(name);
      return openFileBrowser();
    },
  };
}
