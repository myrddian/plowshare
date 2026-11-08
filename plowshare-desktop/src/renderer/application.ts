import type { FileStoreReference } from 'plowshare-client-ts/binding/filestores';
import type { DesktopState, Reply, Request } from '../shared.ts';
import { ownedEvent } from './events.ts';
import {
  installFileBrowser,
  type FileBrowserHandle,
  type FileBrowserPort,
} from './file-browser.ts';

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
 * Conversations returns to the chat stage. Files mounts the reusable
 * file-browser over application.files / read / save.
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
    <section data-source hidden><label data-area-label hidden>File location<select data-area aria-label="Application file location"></select></label>
    <div data-file-browser></div></section>`;
  const get = <T extends HTMLElement = HTMLElement>(selector: string) =>
    host.querySelector<T>(selector)!;
  let project = '',
    identity = '',
    shown = false,
    files = false;
  let view: HubView = 'overview';
  let location: FileStoreReference | undefined;
  const selectedLocation = () => (location ? { location } : {});

  const port: FileBrowserPort = {
    async list(path) {
      await request({
        action: 'application-files',
        project,
        path,
        ...selectedLocation(),
      });
      const value = state().applicationFiles;
      if (value?.error) throw new Error(value.error);
      if (!value?.listing) throw new Error('The directory could not be read.');
      return {
        path: value.listing.path,
        more: value.listing.more,
        entries: value.listing.entries.map((entry) => ({
          name: entry.name,
          path: entry.path,
          directory: entry.directory,
        })),
      };
    },
    async read(path) {
      await request({
        action: 'application-file-read',
        project,
        path,
        ...selectedLocation(),
      });
      const value = state().applicationFiles;
      if (value?.error) throw new Error(value.error);
      if (!value?.document) throw new Error('The file could not be read.');
      return {
        path: value.document.path,
        text: value.document.text,
        revision: value.document.revision,
        writable: value.document.writable,
      };
    },
    async save(path, text, revision) {
      await request({
        action: 'application-file-save',
        project,
        path,
        text,
        revision,
        ...selectedLocation(),
      });
      const value = state().applicationFiles;
      if (value?.error) throw new Error(value.error);
      if (!value?.document) throw new Error('The save did not return a file.');
      return {
        path: value.document.path,
        text: value.document.text,
        revision: value.document.revision,
        writable: value.document.writable,
      };
    },
  };

  const browser: FileBrowserHandle = installFileBrowser(
    get('[data-file-browser]'),
    port,
  );

  const run = (work: () => Promise<unknown> | void) =>
    ownedEvent(async () => {
      try {
        await work();
      } catch (reason) {
        browser.session.error =
          reason instanceof Error
            ? reason.message
            : 'The Application view could not be opened.';
        browser.render();
      }
    });
  const scopeKey = () =>
    `${project}\0${JSON.stringify(location ?? null)}`;
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
      browser.session.setScope(scopeKey());
      if (files) await browser.openRoot();
      else browser.render();
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
      browser.reset();
    }
    project = name;
    shown = true;
    browser.session.setScope(scopeKey());
  };
  // Same path as the Files control. Do not click that button: render disables
  // it while this Application's file load or save is in flight.
  const openFileBrowser = async () => {
    show('files');
    browser.session.setScope(scopeKey());
    await browser.openRoot();
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
  function render() {
    const current = state(),
      key = `${current.base}\0${current.handle}`;
    if (key !== identity) {
      identity = key;
      browser.reset();
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
      browser.session.setScope(scopeKey());
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
    const loading =
      browser.session.busy ||
      !!current.applicationFiles?.loading ||
      !!current.applicationFiles?.saving;
    areaSelect.disabled = loading;
    get('[data-source]').hidden = !files;
    for (const button of host.querySelectorAll<HTMLButtonElement>(
      '[data-files]',
    ))
      button.disabled = loading;
    if (files) browser.render();
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
