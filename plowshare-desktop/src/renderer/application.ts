import type { FileStoreReference } from 'plowshare-client-ts/binding/filestores';
import type {
  DesktopState,
  Reply,
  Request,
  WorkspaceRoute,
} from '../shared.ts';
import { ownedEvent } from './events.ts';
import {
  conversationsForProject,
  mountChat,
  restoreChat,
} from './chat-nest.ts';
import { applicationActivityRows } from './application-activity.ts';
import {
  installFileBrowser,
  type FileBrowserHandle,
  type FileBrowserPort,
} from './file-browser.ts';
import { icon } from './icons.ts';
import {
  renderWorkspaceSlotError,
  workspaceSlotMarkup,
  type WorkspaceSlots,
} from './workspace-slots.ts';

const hubViews = [
  'overview',
  'activity',
  'schedules',
  'relay',
  'files',
  'access',
] as const;
type HubView = (typeof hubViews)[number];
const isHubView = (value: string): value is HubView =>
  (hubViews as readonly string[]).includes(value);

export type ApplicationChatNest = {
  chat: HTMLElement;
  home: HTMLElement;
  selected: () => string;
  title: (id: string) => string;
  choose: (id: string) => Promise<void>;
  create: () => Promise<void>;
};

/**
 * Host hooks for workspace pages shown inside the hub. The hub registers its
 * slots with the shared registry; `release` returns the workspace to chat
 * when the hub leaves a view that hosts a page.
 */
export type ApplicationEmbedding = {
  slots: WorkspaceSlots;
  release: () => Promise<unknown>;
};

/**
 * Application workflow hub and server source editor.
 * `open` lands on Overview. Activity lists every LLM-bearing conversation for
 * this Application via proc.list (server visibility for admins). Own person
 * chats nest the existing chat; other inventory rows open their trajectory
 * inside the Activity detail pane. Schedules and Relay likewise host their
 * workspace pages inside the hub, so the header and view tabs stay in place.
 * Trajectory reads use the server's current scoped permissions for each log.
 * Files mounts the reusable file-browser over application.files / read / save.
 */
export function installApplication(
  host: HTMLElement,
  state: () => DesktopState,
  request: (value: Request) => Promise<Reply>,
  conversations: (project: string) => Promise<void>,
  openSchedules?: (project: string) => void,
  openRelay?: (project: string) => void,
  nest?: ApplicationChatNest,
  embedding?: ApplicationEmbedding,
) {
  host.innerHTML = `<header class="hub-header"><div class="hub-heading"><div class="eyebrow">APPLICATION</div><h2 data-title></h2><div class="hub-meta" data-summary></div></div><button type="button" class="hub-close" data-close aria-label="Close Application">${icon('close')}<span>Close</span></button></header>
    <nav aria-label="Application views" data-hub-nav><button type="button" data-hub="overview">Overview</button><button type="button" data-hub="activity">Activity</button><button type="button" data-hub="schedules">Schedules</button><button type="button" data-hub="relay">Relay</button><button type="button" data-hub="files" data-files>Files</button><button type="button" data-hub="access" data-settings>Access</button></nav>
    <section data-overview><div class="hub-group"><h3>Workspace</h3><div data-overview-tiles><button type="button" data-hub="activity"><strong>Activity</strong><small>Conversations and LLM logs, with full trajectories</small></button><button type="button" data-hub="schedules"><strong>Schedules</strong><small>Schedules and triggers that run this Application</small></button><button type="button" data-hub="relay"><strong>Relay</strong><small>Messages relayed to and from this Application</small></button><button type="button" data-hub="files"><strong>Files</strong><small>Browse and edit the Application source</small></button><button type="button" data-hub="access"><strong>Access</strong><small>Who can use and manage this Application</small></button></div></div>
    <div class="hub-group application-secondary" role="group" aria-label="More Application surfaces"><h3>Related</h3><div class="hub-links"><button type="button" data-runtime>Runtime definitions</button><button type="button" data-memory>Memory</button><button type="button" data-board>Boards</button></div></div></section>
    <section data-activity hidden>
      <div class="application-activity-split">
        <div class="application-activity-list">
          <div class="application-conversation-toolbar">
            <strong>Activity</strong>
            <div class="application-activity-actions">
              <button type="button" class="icon-button" data-activity-refresh aria-label="Refresh activity">${icon('refresh')}</button>
              <button type="button" class="icon-button" data-conversation-new aria-label="New conversation">${icon('plus')}</button>
            </div>
          </div>
          <nav data-activity-tree aria-label="Application activity"></nav>
          <p class="application-activity-note" data-activity-note hidden></p>
        </div>
        <div class="application-activity-embed">
          <div class="application-conversation-embed" data-conversation-embed hidden></div>
          ${workspaceSlotMarkup('activity', 'Opening trajectory…')}
          <div class="activity-empty" data-activity-empty hidden><p>Select a conversation or log.</p></div>
        </div>
      </div>
    </section>
    <section data-schedules hidden>${workspaceSlotMarkup('schedules', 'Opening scheduled work for this Application…')}</section>
    <section data-relay hidden>${workspaceSlotMarkup('relay', 'Opening the Relay log for this Application…')}</section>
    <section data-access hidden><div class="hub-panel"><h3>Access</h3><p>Membership for this Application is managed in the Access dialog.</p><button type="button" data-access-open>Open Access</button></div></section>
    <section data-source hidden><label data-area-label hidden>File location<select data-area aria-label="Application file location"></select></label>
    <div data-file-browser></div></section>`;
  const get = <T extends HTMLElement = HTMLElement>(selector: string) =>
    host.querySelector<T>(selector)!;
  let project = '',
    identity = '',
    shown = false,
    files = false;
  let view: HubView = 'overview';
  let selected = '';
  // Failure to open the current view's embedded page; cleared on view change.
  let embedError = '';
  let location: FileStoreReference | undefined;
  const selectedLocation = () => (location ? { location } : {});
  const escape = (value: string) =>
    value.replace(
      /[&<>"']/g,
      (character) =>
        ({
          '&': '&amp;',
          '<': '&lt;',
          '>': '&gt;',
          '"': '&quot;',
          "'": '&#39;',
        })[character]!,
    );

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

  // Failures belong to the view that raised them: Files reports in the
  // browser; every other view reports in its embed placeholder.
  const run = (work: () => Promise<unknown> | void) =>
    ownedEvent(async () => {
      try {
        await work();
      } catch (reason) {
        const message =
          reason instanceof Error
            ? reason.message
            : 'The Application view could not be opened.';
        if (files) {
          browser.session.error = message;
          browser.render();
        } else {
          // The failed view no longer claims any page; hand a page that was
          // showing (e.g. the previous row's trajectory) back to chat so it
          // never covers the error.
          embedError = message;
          render();
          embedding?.slots.changed();
          if (embedding) await embedding.release();
        }
      }
    });
  const scopeKey = () => `${project}\0${JSON.stringify(location ?? null)}`;
  const releaseChat = () => {
    if (nest) restoreChat(nest.chat, nest.home);
  };
  const nestChat = () => {
    if (nest) mountChat(nest.chat, get('[data-conversation-embed]'));
  };
  const date = (value: string) => {
    const time = Date.parse(value);
    return Number.isFinite(time)
      ? new Date(time).toLocaleString(undefined, {
          month: 'short',
          day: 'numeric',
          hour: 'numeric',
          minute: '2-digit',
        })
      : value;
  };
  const activityRows = () => {
    const listed = state().applicationActivity;
    const titles: Record<string, string> = {};
    if (nest)
      for (const row of conversationsForProject(
        state().conversations,
        project,
        nest.title,
      ))
        titles[row.id] = row.title;
    return applicationActivityRows(listed?.items ?? [], {
      titles,
      selected,
      handle: state().handle,
    });
  };
  const showActivityPanes = (nestable: boolean) => {
    const chatEmbed = get('[data-conversation-embed]');
    const slot = get('[data-workspace-slot="activity"]');
    const empty = get('[data-activity-empty]');
    const showingChat = !!selected && nestable;
    const showingLog = !!selected && !nestable;
    chatEmbed.hidden = !showingChat;
    slot.hidden = !showingLog;
    empty.hidden = showingChat || showingLog;
    if (showingChat) nestChat();
    else releaseChat();
  };
  const renderActivity = () => {
    const listed = state().applicationActivity;
    const tree = get('[data-activity-tree]');
    const note = get('[data-activity-note]');
    const rows = activityRows();
    if (listed?.error) {
      note.hidden = false;
      note.textContent = listed.error;
    } else if (listed?.visibility === 'mine') {
      note.hidden = false;
      note.textContent =
        'Showing work owned by this account. Server admin visibility is required to list service-owned Application logs.';
    } else {
      note.hidden = true;
      note.textContent = '';
    }
    if (!rows.length) {
      tree.innerHTML = `<p class="application-activity-note">${listed?.loading ? 'Loading activity…' : listed?.loaded ? 'No LLM activity for this Application yet.' : 'Activity unavailable. Try Refresh.'}</p>`;
    } else {
      tree.innerHTML = rows
        .map((row) => {
          const glyph =
            row.origin === 'turn'
              ? 'message'
              : row.origin === 'delegation' || row.origin === 'orchestration'
                ? 'route'
                : 'activity';
          return `<button type="button" class="activity-row" data-activity-id="${escape(row.id)}" aria-current="${row.current}"><span class="conversation-icon">${icon(glyph)}</span><span class="activity-text"><strong>${escape(row.title)}</strong><small>${escape(row.subtitle)}${row.live ? ' · live' : ''}</small></span><time datetime="${escape(row.at)}">${escape(date(row.at))}</time></button>`;
        })
        .join('');
    }
    const current = rows.find((row) => row.id === selected);
    showActivityPanes(!!current?.nestChat);
  };
  const openTrajectory = (id: string) =>
    request({ action: 'application-activity-open', conversation: id });
  // A chat row nests the live chat; a log row opens its trajectory page,
  // which the host places over the Activity embed slot.
  const chooseRow = async (id: string) => {
    selected = id;
    show('activity');
    const row = activityRows().find((item) => item.id === id);
    if (row?.nestChat && nest) await nest.choose(id);
    else {
      releaseChat();
      renderActivity();
      if (row) await openTrajectory(id);
    }
    renderActivity();
    embedding?.slots.changed();
  };
  const openActivity = async () => {
    show('activity');
    await request({ action: 'application-activity', project });
    const rows = activityRows();
    if (selected && rows.some((row) => row.id === selected))
      await chooseRow(selected);
    else if (rows[0]) await chooseRow(rows[0].id);
    else {
      selected = '';
      renderActivity();
    }
  };
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
    releaseChat();
    shown = false;
    host.hidden = true;
    embedding?.slots.changed();
  };
  // Every view change releases a page the new view does not claim (the host
  // only releases unclaimed pages), so one left open by Activity, Schedules
  // or Relay never lingers in the breadcrumb or over another view.
  const show = (next: HubView) => {
    if (next !== view) embedError = '';
    view = next;
    files = next === 'files';
    if (next !== 'activity') releaseChat();
    render();
    embedding?.slots.changed();
    if (embedding) run(embedding.release)();
  };
  const returnToStage = () => {
    close();
    return conversations(project);
  };
  const useProject = (name: string) => {
    if (project !== name) {
      location = undefined;
      get<HTMLSelectElement>('[data-area]').selectedIndex = 0;
      browser.reset();
      releaseChat();
      selected = '';
    }
    project = name;
    shown = true;
    browser.session.setScope(scopeKey());
  };
  const openFileBrowser = async () => {
    show('files');
    browser.session.setScope(scopeKey());
    await browser.openRoot();
  };
  get('[data-close]').addEventListener('click', run(returnToStage));
  get('[data-conversation-new]').addEventListener(
    'click',
    run(async () => {
      if (!nest) return;
      show('activity');
      await nest.create();
      selected = nest.selected();
      await request({ action: 'application-activity', project });
      renderActivity();
    }),
  );
  get('[data-activity-refresh]').addEventListener(
    'click',
    run(async () => {
      await openActivity();
    }),
  );
  get('[data-activity-tree]').addEventListener('click', (event) => {
    const id = (event.target as HTMLElement | null)?.closest<HTMLElement>(
      '[data-activity-id]',
    )?.dataset.activityId;
    if (!id) return;
    run(() => chooseRow(id))();
  });
  host.addEventListener('click', (event) => {
    const retry = (event.target as HTMLElement | null)?.closest(
      '[data-slot-retry]',
    );
    if (!retry) return;
    embedError = '';
    if (view === 'activity' && selected) run(() => chooseRow(selected))();
    else if (view === 'schedules') run(() => openSchedules?.(project))();
    else if (view === 'relay') run(() => openRelay?.(project))();
  });
  const openAccess = () =>
    document
      .querySelector<HTMLButtonElement>(
        `[data-project-access="${CSS.escape(project)}"]`,
      )
      ?.click();
  get('[data-access-open]').addEventListener('click', openAccess);
  host.addEventListener('click', (event) => {
    const hub = (event.target as HTMLElement | null)?.closest<HTMLElement>(
      '[data-hub]',
    )?.dataset.hub;
    if (!hub || !isHubView(hub)) return;
    if (hub === 'activity') {
      run(openActivity)();
      return;
    }
    if (hub === 'access') {
      show('access');
      openAccess();
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
    const chips = [
      ...(row.applicationRoot
        ? [`${row.applicationRoot.store}/${row.applicationRoot.path}`]
        : []),
      row.type === 'DISJOINT'
        ? 'Source managed externally'
        : 'Source managed by Plowshare',
      row.role?.toLowerCase() ?? 'access granted',
    ];
    get('[data-summary]').innerHTML = chips
      .map((chip) => `<span>${escape(chip)}</span>`)
      .join('');
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
    get('[data-activity]').hidden = view !== 'activity';
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
      !!current.applicationFiles?.saving ||
      !!current.applicationActivity?.loading;
    areaSelect.disabled = loading;
    get('[data-source]').hidden = !files;
    for (const button of host.querySelectorAll<HTMLButtonElement>(
      '[data-files]',
    ))
      button.disabled = loading;
    get<HTMLButtonElement>('[data-activity-refresh]').disabled = loading;
    for (const slot of host.querySelectorAll<HTMLElement>(
      '[data-workspace-slot]',
    ))
      renderWorkspaceSlotError(
        slot,
        slot.dataset.workspaceSlot === view ? embedError : '',
      );
    if (files) browser.render();
    if (view === 'activity') renderActivity();
    else releaseChat();
  }
  /**
   * The slot a workspace page of this route should cover, or undefined when
   * the hub does not want it shown. Activity claims this Application's
   * trajectories while a log row is selected, including children opened
   * from inside that trajectory; trajectories opened elsewhere (Runs,
   * Mailbox, another project) keep their full-size placement.
   */
  const embedSlot = (route: WorkspaceRoute): HTMLElement | undefined => {
    if (!shown || host.hidden || embedError) return undefined;
    if (view === 'activity' && route.kind === 'trajectory') {
      const row = activityRows().find((item) => item.id === selected);
      return row &&
        !row.nestChat &&
        (!route.project || route.project === project)
        ? get('[data-workspace-slot="activity"]')
        : undefined;
    }
    if (view === 'schedules' && route.kind === 'activity')
      return get('[data-workspace-slot="schedules"]');
    if (view === 'relay' && route.kind === 'relay')
      return get('[data-workspace-slot="relay"]');
    return undefined;
  };
  if (embedding) {
    embedding.slots.register(embedSlot);
    for (const slot of host.querySelectorAll('[data-workspace-slot]'))
      embedding.slots.observe(slot);
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
      releaseChat();
      render();
    },
    openFiles(name: string) {
      useProject(name);
      return openFileBrowser();
    },
  };
}
