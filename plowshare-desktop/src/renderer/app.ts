import { installApplicationDeployment } from './application-deployment.ts';
import { fileStorePlacement } from './server-filestores.ts';
import { installFileStores } from './filestores.ts';
import { isObject } from 'plowshare-client-ts/binding/values';
import { readPreferences, type Preferences } from './preferences.ts';
import { errorMessage } from 'plowshare-client-ts/binding/values';
import { ownedEvent, background } from './events.ts';
import { displayText } from 'plowshare-client-ts/binding/values';
import { botDisplayName, botDetails } from './bot-details.ts';
import { installPaneResize } from './pane-resize.ts';
import { installApplication } from './application.ts';
import { installProjectAccess } from './project-access.ts';
import { installServerAdmin } from './server-admin.ts';
import { installOperator } from './operator.ts';
import { activeJob, stoppedJob, homeKey, contextKey } from '../shared.ts';
import type { DesktopState, Job, Request, WorkspaceRoute } from '../shared.ts';
import { markdownHtml } from './markdown.ts';
import { icon, mountIcons } from './icons.ts';
import { installNavigation } from './navigation.ts';
import type { NavigationItem } from './navigation.ts';
import { copyButton, installCopyControls } from './copy.ts';
import { describePace } from 'plowshare-client-ts/operations/pace';
import { installQuestionPopup } from './question-popup.ts';
import {
  commandDraft,
  composerCommand,
  desktopCommands,
  installCommandPicker,
  installCommands,
} from './commands.ts';
import { installSync } from './sync.ts';
import { installApprovalControls, noticeApproval } from './approvals.ts';

mountIcons();
installCopyControls();

const $ = <T extends HTMLElement = HTMLElement>(selector: string): T =>
  document.querySelector(selector)!;
const content = new WeakMap<HTMLElement, string>();
function setHTML(element: HTMLElement, html: string) {
  if (content.get(element) === html) return;
  element.innerHTML = html;
  content.set(element, html);
}
const esc = (value: unknown) =>
  displayText(value ?? '').replace(
    /[&<>"']/g,
    (character) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[
        character
      ]!,
  );
const dialog = $<HTMLDialogElement>('#connection-dialog');
const draft = $<HTMLTextAreaElement>('#draft');
const transcript = $('#transcript');
const stage = $('#stage');
const chat = $('#chat');
const agentSelect = $<HTMLSelectElement>('#agent');
let state: DesktopState;
const commandPicker = installCommandPicker(
  $('#slash-commands'),
  draft,
  (name) => {
    const command = state.agents[scope]
      ?.find((row) => row.name === agentSelect.value)
      ?.commands?.find((row) => row.command === name);
    draft.value = command ? commandDraft(command, '', '') : `${name} `;
    drafts[selected] = draft.value;
    persist();
    render();
  },
);
const commandControls = installCommands(
  $('#agent-commands'),
  (command) => {
    draft.value = command;
    drafts[selected] = command;
    persist();
    render();
    draft.focus();
  },
  () => draft.value,
  async (command, text) => {
    const identity = JSON.stringify([
      state.base,
      state.handle,
      selected,
      agentSelect.value,
      command.name,
      text,
    ]);
    const key = `plowshare-workflow-request:${identity}`;
    const requestId = localStorage.getItem(key) ?? crypto.randomUUID();
    localStorage.setItem(key, requestId);
    await request({
      action: 'workflow-start',
      conversation: selected,
      agent: agentSelect.value,
      definition: command.name,
      text,
      requestId,
    });
    localStorage.removeItem(key);
  },
);
const application = installApplication(
  $('#application-panel'),
  () => state,
  request,
  chooseScope,
);
const approvalControls = installApprovalControls(
  document,
  () => state,
  request,
  render,
);
const questionPopup = installQuestionPopup(
  $<HTMLDialogElement>('#question-dialog'),
  $('#question-waiting'),
  request,
);
let selected = '';
let selectionStamp = '';
let scope = 'Research';
let drafts: Record<string, string> = {};
let chosenAgents: Record<string, string> = {};
let preferences: Preferences = {};
let personalBotExpansion: Record<string, boolean> = {};
let personalBotsLoading = false;
let personalBotReadKey = '';
let projectExpansion: Record<string, boolean> = {};
let filterExpansion: Record<string, boolean> = {};
let identity = '';
let signature = '';
let docked = false;
let expanded = false;
let inspectorHidden = false;
let offset = { x: 0, y: 0 };
let busy = false;
let connectionError = '';
let sidebarHidden = false;
let projectsCollapsed = false;
let applicationsCollapsed = false;
let personalCollapsed = false;
let personalConversationsCollapsed = false;
let personalBotsCollapsed = true;
let personalVisible = false;
let workspaceRoute: WorkspaceRoute = { kind: 'chat', label: 'Conversation' };
const historyBusy = new Set<string>();
const measurements = new Map<string, { at: number; pending: boolean }>();
try {
  preferences = readPreferences(
    JSON.parse(
      localStorage.getItem('plowshare.desktop.ui.v1') ?? '{}',
    ) as unknown,
  );
} catch {
  /* Start with a clean view if a local draft is unreadable. */
}

function persist(save = true) {
  if (!identity) return;
  preferences[identity] = {
    selected,
    scope,
    drafts,
    projectExpansion,
    chosenAgents,
    personalBotExpansion,
  };
  if (!save) return;
  if (state?.mode === 'live' && state.handle) {
    const owner = identity;
    const preference = preferences[identity];
    if (preference)
      void window.plowshare
        .request({
          action: 'connection-preferences',
          server: state.base,
          account: state.handle,
          preference,
        })
        .catch((reason: unknown) => {
          if (owner === identity)
            error(
              reason instanceof Error ? reason.message : errorMessage(reason),
            );
        });
  } else {
    try {
      localStorage.setItem(
        'plowshare.desktop.ui.v1',
        JSON.stringify(preferences),
      );
    } catch {
      /* Demo drafts remain available in this window. */
    }
  }
}
function key(next: DesktopState) {
  return next.mode === 'demo' ? 'demo' : `${next.base}|${next.handle}`;
}
function title(id: string) {
  const conversation = state.conversations.find((row) => row.id === id);
  return (
    conversation?.title ||
    state.history[id]?.entries
      .find((entry) => entry.kind === 'utterance')
      ?.text?.slice(0, 56) ||
    'New conversation'
  );
}
function selectedJob(): Job | undefined {
  return state?.jobs.filter((job) => job.conversation === selected).at(-1);
}
function update(next: DesktopState) {
  if (!state?.connected && next.connected) {
    measurements.clear();
    snapshotReads.clear();
  }
  const nextIdentity = key(next);
  if (nextIdentity !== identity) {
    persist(false);
    identity = nextIdentity;
    delete draft.dataset.conversation;
    const saved = next.localPreferences ?? preferences[identity];
    drafts = saved?.drafts ?? {};
    selected = saved?.selected ?? '';
    scope = saved?.scope ?? (next.mode === 'demo' ? 'Research' : '');
    projectExpansion = saved?.projectExpansion ?? {};
    filterExpansion = {};
    chosenAgents = saved?.chosenAgents ?? {};
    personalBotExpansion = saved?.personalBotExpansion ?? {};
    personalBotReadKey = '';
    signature = '';
    measurements.clear();
  }
  state = next;
  const botReadKey =
    state.personal && state.connected
      ? JSON.stringify([
          identity,
          state.personal.project,
          state.agents[state.personal.project]
            ?.filter((row) => row.bot)
            .map((row) => row.name),
        ])
      : '';
  if (
    !personalBotsCollapsed &&
    botReadKey &&
    botReadKey !== personalBotReadKey &&
    state.agents[state.personal!.project]
  ) {
    personalBotReadKey = botReadKey;
    background(refreshPersonalBots());
  }
  const opening =
    state.mode === 'live' &&
    /^(Connecting|Opening projects)/.test(state.connection);
  if (next.backgroundError) error(next.backgroundError);
  if (next.jobRecoveryError) error(next.jobRecoveryError);
  if (next.connectionPersistenceError) error(next.connectionPersistenceError);
  else if (connectionError && $('#error span').textContent === connectionError)
    $('#error').hidden = true;
  connectionError = next.connectionPersistenceError ?? '';
  if (!opening && !state.projects.some((row) => row.name === scope))
    scope = state.personal?.project ?? '';
  if (!scope && state.personal) scope = state.personal.project;
  const rows = state.conversations.filter(
    (row) => homeKey(row.project) === scope,
  );
  if (!opening && !rows.some((row) => row.id === selected))
    selected = rows[0]?.id ?? '';
  if (draft.dataset.conversation !== selected) {
    draft.value = drafts[selected] ?? '';
    draft.dataset.conversation = selected;
    signature = '';
  }
  render();
  persist(state.mode === 'demo');
  followSelection();
}
function followSelection() {
  if (state.mode !== 'live' || !state.connected) {
    selectionStamp = '';
    return;
  }
  const stamp = JSON.stringify([identity, selected]);
  if (stamp === selectionStamp) return;
  selectionStamp = stamp;
  action({ action: 'select', ...(selected ? { conversation: selected } : {}) });
}
function error(message: string) {
  $('#error span').textContent = message.replace(
    /^Error invoking remote method '[^']+': Error: /,
    '',
  );
  $('#error').hidden = false;
}
async function request(command: Request) {
  const reply = await window.plowshare.request(command);
  update(reply.state);
  if (reply.notice) error(reply.notice);
  return reply;
}
function action(command: Request) {
  const owner = identity;
  void request(command).catch((reason: unknown) => {
    if (owner === identity)
      error(reason instanceof Error ? reason.message : errorMessage(reason));
  });
}
async function loadHistory(id = selected, before?: number) {
  if (!id || historyBusy.has(id)) return;
  historyBusy.add(id);
  try {
    await request({
      action: 'history',
      conversation: id,
      ...(before === undefined ? {} : { before }),
    });
  } catch (reason) {
    error(reason instanceof Error ? reason.message : errorMessage(reason));
  } finally {
    historyBusy.delete(id);
  }
}
async function choose(id: string) {
  application.close();
  personalVisible = false;
  const row = state.conversations.find((row) => row.id === id);
  if (!row) return;
  await request({ action: 'workspace-chat' });
  const previousScope = scope;
  if (selected) drafts[selected] = draft.value;
  selected = id;
  scope = homeKey(row.project);
  projectExpansion[scope] = true;
  filterExpansion[scope] = true;
  draft.dataset.conversation = selected;
  draft.value = drafts[selected] ?? '';
  signature = '';
  render();
  persist();
  followSelection();
  if (
    state.mode === 'live' &&
    state.connected &&
    (scope !== previousScope || !state.agents[scope])
  ) {
    try {
      await request({ action: 'scope', ...(scope ? { project: scope } : {}) });
    } catch (reason) {
      error(reason instanceof Error ? reason.message : errorMessage(reason));
    }
  }
  await loadHistory(id);
}
async function chooseScope(nextScope: string) {
  application.close();
  personalVisible = false;
  await request({ action: 'workspace-chat' });
  if (selected) drafts[selected] = draft.value;
  scope = nextScope;
  projectExpansion[scope] = true;
  filterExpansion[scope] = true;
  selected = '';
  signature = '';
  update(state);
  try {
    await request({ action: 'scope', ...(scope ? { project: scope } : {}) });
    await loadHistory();
  } catch (reason) {
    error(reason instanceof Error ? reason.message : errorMessage(reason));
  }
}
async function newConversation() {
  if (
    busy ||
    (state.mode === 'live' && !state.connected) ||
    state.projects.find((row) => row.name === scope)?.role === 'VIEWER'
  )
    return;
  busy = true;
  try {
    const reply = await request({
      action: 'create',
      ...(scope ? { project: scope } : {}),
    });
    if (reply.conversation) await choose(reply.conversation);
    draft.focus();
  } catch (reason) {
    error(reason instanceof Error ? reason.message : errorMessage(reason));
  } finally {
    busy = false;
    render();
  }
}
function openConnect() {
  $<HTMLInputElement>('#connection-name').value =
    state.selectedConnection ?? '';
  $<HTMLInputElement>('#server-url').value = state.base;
  $<HTMLInputElement>('#handle').value = state.handle;
  $<HTMLInputElement>('#password').value = '';
  $('#connect-error').hidden = true;
  $('#disconnect').hidden = state.mode !== 'live' || !state.connected;
  if (!dialog.open) dialog.showModal();
}

const deploymentControls = installApplicationDeployment(() => state, request);
const serverAdministration = installServerAdmin(request);
const projectAccess = installProjectAccess(request);

const localFileStores = installFileStores(request);
function render() {
  localFileStores.update(state);
  serverAdministration.update(state);
  projectAccess.update(state);
  if (!state) return;
  const recoveryWarning = $('#personal-recovery-warning');
  recoveryWarning.textContent = state.personal?.warning ?? '';
  recoveryWarning.hidden = state.mode !== 'live' || !state.personal?.warning;
  renderFiles();
  $('#server-project-add').hidden = !state.connected || !state.serverAdmin;
  deploymentControls.update();
  questionPopup.update(state, scope, selected);
  const filter = $<HTMLInputElement>(
    '#conversation-filter',
  ).value.toLowerCase();
  const rows = state.conversations.filter(
    (row) => homeKey(row.project) === scope,
  );
  $('#conversation-count').textContent = String(rows.length);
  const conversationRows = (
    project: string,
    includes: (id: string) => boolean = () => true,
  ) =>
    state.conversations
      .filter(
        (row) =>
          homeKey(row.project) === project &&
          includes(row.id) &&
          title(row.id).toLowerCase().includes(filter),
      )
      .map((row) => {
        const job = state.jobs.find(
          (job) => job.conversation === row.id && activeJob(job),
        );
        return `<button class="conversation-row" data-conversation="${esc(row.id)}" ${row.id === selected ? 'aria-current="page"' : ''}><span class="conversation-icon ${job ? 'is-active' : ''}">${icon(job ? 'activity' : 'message')}</span><div><span class="row-title">${esc(title(row.id))}</span>${job ? `<small>${esc(job.status)}</small>` : ''}</div></button>`;
      })
      .join('');
  const personal = state.personal;
  $('#personal-navigation').hidden = !personal;
  setHTML(
    $('#personal-conversations'),
    personal
      ? conversationRows(personal.project) ||
          '<p class="no-running">No conversations yet.</p>'
      : '',
  );
  setHTML(
    $('#personal-sections'),
    personal
      ? ['In', 'Out', 'Resources', 'Archive', 'Planning']
          .map(
            (section) =>
              `<button data-personal-section="${section}" ${personalVisible && personal.section === section ? 'aria-current="page"' : ''}>${icon(section === 'In' ? 'inbox' : 'folder')}<span>${personalSectionName(section)}</span></button>`,
          )
          .join('')
      : '',
  );
  setHTML(
    $('#personal-bots'),
    personal
      ? (personalBotsLoading
          ? '<p class="no-running" role="status">Loading bot conversations…</p>'
          : '') +
          (personal.botsError
            ? `<p class="no-running" role="alert">${esc(personal.botsError)}</p>`
            : '') +
          (state.agents[personal.project] ?? [])
            .filter((row) => row.bot)
            .map((bot, index) => {
              const expanded = personalBotExpansion[bot.name] === true;
              const chats = conversationRows(personal.project, (id) =>
                personalBotConversations(bot.name).includes(id),
              );
              return `<section class="personal-bot-group" data-personal-bot-group="${esc(bot.name)}"><div class="personal-bot-heading"><button class="project-toggle icon-button" data-personal-bot-toggle="${esc(bot.name)}" aria-expanded="${expanded}" aria-controls="personal-bot-conversations-${index}" aria-label="${expanded ? 'Collapse' : 'Expand'} ${esc(botDisplayName(bot))}">${icon('chevron')}</button><button class="personal-bot-name" data-personal-bot-open="${esc(bot.name)}" ${!bot.served && !personalBotConversations(bot.name).length ? 'disabled' : ''}>${esc(botDisplayName(bot))}${bot.preferred ? '<small>Default</small>' : ''}</button><button class="icon-button" data-personal-bot-new="${esc(bot.name)}" aria-label="New conversation with ${esc(botDisplayName(bot))}" ${!bot.served || busy ? 'disabled' : ''}>${icon('plus')}</button></div><nav id="personal-bot-conversations-${index}" class="personal-bot-conversations" ${expanded ? '' : 'hidden'} aria-label="Conversations with ${esc(botDisplayName(bot))}">${chats || '<p class="no-running">No conversations yet.</p>'}</nav></section>`;
            })
            .join('')
      : '',
  );
  renderPersonal();
  const projectRows = (application: boolean) =>
    state.projects
      .filter(
        (row) =>
          row.kind !== 'personal' &&
          (row.kind === 'application') === application,
      )
      .map((row) => row.name)
      .map((project, index) => {
        const folder = state.projectFolders?.find(
          (row) => row.name === project,
        );
        const recorded = state.projects.find((row) => row.name === project);
        const path = recorded?.workspace ?? folder?.path;
        const machine = recorded?.workspace
          ? (recorded.machine ?? 'Server')
          : folder?.machine;
        const location = [
          recorded?.type === 'DISJOINT' ? 'DISJOINT · no sync' : undefined,
          path ? `${machine ?? 'Server'} · ${path}` : undefined,
        ]
          .filter(Boolean)
          .join(' · ');
        const expanded = filter
          ? (filterExpansion[project] ?? true)
          : (projectExpansion[project] ?? scope === project);
        const items = expanded ? conversationRows(project) : '';
        return `<section class="project-group"><div class="project-heading" ${scope === project ? 'data-current="true"' : ''}><button class="project-toggle icon-button" data-project-toggle="${esc(project)}" aria-expanded="${expanded}" aria-controls="project-conversations-${application ? 'application' : 'external'}-${index}" aria-label="${expanded ? 'Collapse' : 'Expand'} ${esc(scopeName(project))}">${icon('chevron')}</button><button class="project-row" data-project="${esc(project)}" ${scope === project ? 'aria-current="true"' : ''} title="${esc([location, folder?.error].filter(Boolean).join(' · ') || 'Open project')}"><span class="project-name">${esc(scopeName(project))}${location ? `<small class="project-location">${esc(location)}</small>` : ''}</span>${folder ? `<small class="project-presence ${folder.files.status}" aria-label="${esc(folder.error ? 'Files unavailable' : folder.files.status)}">${icon(folder.files.status === 'ready' ? 'check' : folder.error || folder.files.status === 'lost' ? 'alert' : 'folder')}</small>` : ''}</button><button type="button" data-project-access="${esc(project)}" aria-label="Access for ${esc(scopeName(project))}" ${!state.connected ? 'disabled' : ''}>Access</button></div><div id="project-conversations-${application ? 'application' : 'external'}-${index}" class="project-conversations" ${expanded ? '' : 'hidden'}>${items || '<p class="no-running">No conversations loaded.</p>'}</div></section>`;
      })
      .join('');
  const projectListError = state.projectListError
    ? `<p class="no-running" role="alert">${esc(state.projectListError)}</p>`
    : '';
  const emptyProjects = (application: boolean) =>
    `<p class="no-running" role="status">${state.connected ? (application ? 'No Applications available to this account.' : 'No Projects available.') : state.mode === 'live' ? 'Connect to load workspaces.' : application ? 'No sample Applications.' : 'No sample Projects.'}</p>`;
  setHTML(
    $('#application-conversations'),
    projectListError + (projectRows(true) || emptyProjects(true)),
  );
  setHTML(
    $('#conversations'),
    projectListError + (projectRows(false) || emptyProjects(false)),
  );
  const jobs = state.jobs.filter(activeJob);
  $('#running-count').textContent = String(jobs.length);
  $('#inbox-count').textContent =
    state.activity.inbox.unread === undefined
      ? '—'
      : String(state.activity.inbox.unread);
  $('#inbox-open').title = state.activity.inbox.error || 'Open account mailbox';
  $('#runs-count').textContent = state.activity.runs.loaded
    ? String(
        state.activity.runs.items.filter(
          (run) =>
            !run.parent && ['running', 'asking', 'waiting'].includes(run.state),
        ).length,
      )
    : '—';
  $('#runs-open').title = state.activity.runs.error || 'Open account runs';
  setHTML(
    $('#running-jobs'),
    jobs
      .map(
        (job) =>
          `<button class="running-row" ${job.source === 'information' ? 'data-information-job' : `data-conversation="${esc(job.conversation)}"`}>${icon('activity')}<span>${esc(job.source === 'information' ? job.task : title(job.conversation))}</span></button>`,
      )
      .join('') || '<div class="no-running">No active runs.</div>',
  );
  $('#scope-caption').textContent = scopeName(scope);
  $('#title-caption').textContent = selected
    ? title(selected)
    : 'New conversation';
  $('#chat-title').textContent = selected ? title(selected) : 'An open field';
  $('#connection-label').textContent = [
    state.selectedConnection,
    state.connection,
  ]
    .filter(Boolean)
    .join(' · ');
  $('#chat-footer-mode').textContent =
    state.mode === 'demo'
      ? 'Local demo · no model calls'
      : state.connected
        ? 'Server connected'
        : 'Server unavailable';
  $('#notice').hidden = state.mode === 'live' && state.connected;
  if (state.mode === 'live') {
    $('#notice .demo-mark').textContent = 'OFFLINE';
    $('#notice>span:nth-child(2)').textContent =
      'Connection unavailable. Server jobs may continue; reconnect before sending.';
  } else {
    $('#notice .demo-mark').textContent = 'DEMO';
    $('#notice>span:nth-child(2)').textContent =
      'Sample workspace · local replies';
  }
  $('#connect-sidebar .button-label').textContent =
    state.mode === 'live' ? 'Connection settings' : 'Connect server';
  const roster = state.agents[scope] ?? [];
  const bots = roster.filter((row) => row.served && row.bot);
  const preferred = roster.find((row) => row.preferred);
  const previousAgent =
    chosenAgents[selected] ??
    (preferred
      ? preferred.served && preferred.bot
        ? preferred.name
        : ''
      : (bots[0]?.name ?? ''));
  const unmet =
    preferred &&
    (!preferred.served || !preferred.bot) &&
    !chosenAgents[selected];
  setHTML(
    agentSelect,
    (unmet
      ? `<option value="">Default ${esc(preferred.name)} unavailable · choose an agent</option>`
      : '') +
      bots
        .map(
          (row) =>
            `<option value="${esc(row.name)}">${esc(botDisplayName(row))}${row.model ? ` · ${esc(row.model)}` : ''}</option>`,
        )
        .join('') || '<option value="">No conversational agent</option>',
  );
  const desiredAgent = bots.some((row) => row.name === previousAgent)
    ? previousAgent
    : '';
  if (agentSelect.value !== desiredAgent) agentSelect.value = desiredAgent;
  const commandAgent = bots.find((row) => row.name === agentSelect.value);
  let identityPanel = document.querySelector<HTMLElement>('#agent-identity');
  if (!identityPanel) {
    identityPanel = document.createElement('details');
    identityPanel.id = 'agent-identity';
    identityPanel.className = 'agent-identity';
    agentSelect.closest('.composer-footer')?.after(identityPanel);
  }
  const snapshot = state.connected
    ? state.contextSnapshots?.[contextKey(selected, agentSelect.value)]?.value
    : undefined;
  const access = commandAgent;
  identityPanel.hidden = !access;
  if (access) setHTML(identityPanel, botDetails(access, state.files, snapshot));
  commandControls.update(
    commandAgent?.commands ?? [],
    state.mode === 'live' &&
      state.connected &&
      state.projects.find((row) => row.name === scope)?.role !== 'VIEWER',
    commandAgent?.withheld ?? [],
    commandAgent?.commands !== undefined,
  );
  commandPicker.update(commandAgent?.commands ?? []);
  draft.placeholder = agentSelect.value
    ? scope === state.personal?.project
      ? 'Capture an idea, explore your knowledge, or make a plan…'
      : 'Ask anything…'
    : 'Choose an agent to continue…';
  const job = selectedJob();
  const active = job !== undefined && activeJob(job);
  const available = state.mode === 'demo' || state.connected;
  const preparing = state.projectPreparing?.includes(scope) === true;
  const viewer =
    state.projects.find((row) => row.name === scope)?.role === 'VIEWER';
  if (viewer) draft.placeholder = 'Viewer access · read project conversations';
  const typedCommand = composerCommand(draft.value);
  const localCommand =
    typedCommand && !/^\/(skill|orchestration):/.test(typedCommand.name);
  $<HTMLButtonElement>('#send').disabled =
    (viewer && !localCommand) ||
    !available ||
    preparing ||
    busy ||
    !draft.value.trim() ||
    (!localCommand && (active || !agentSelect.value));
  $<HTMLButtonElement>('#new-conversation').disabled =
    viewer || !available || preparing || busy;
  $('#cancel').hidden =
    !active ||
    job?.status === 'starting' ||
    job?.id.startsWith('pending-') === true ||
    !available;
  $<HTMLButtonElement>('#cancel').disabled =
    viewer || job?.status === 'cancelling';
  const statusText = active
    ? job.status === 'unknown'
      ? 'Outcome uncertain'
      : job.status === 'cancelling'
        ? 'Stopping…'
        : job.phase === 'thinking'
          ? 'Reasoning…'
          : job.phase === 'answer'
            ? 'Responding…'
            : job.phase === 'tool'
              ? `Using ${job.tool || 'a tool'}…`
              : 'Working…'
    : !available
      ? 'Reconnect to send'
      : preparing
        ? 'Connecting project files…'
        : 'Ready';
  if ($('#composer-status-text').textContent !== statusText)
    $('#composer-status-text').textContent = statusText;
  // Keep the indicator mounted: streamed deltas must not restart its CSS animation.
  const indicator = $('#run-indicator');
  indicator.hidden =
    !available || !job || !['starting', 'running'].includes(job.status);
  indicator.dataset.phase = job?.phase ?? 'working';
  $<HTMLButtonElement>('#trajectory-open').disabled = !selected;
  if (workspaceRoute.kind === 'chat') {
    renderTranscript();
    renderInspector();
  }
  renderApprovals();
  renderStatus();
  measureContext();
  refreshCurrentProjection();
  renderBreadcrumb();
  if (workspaceRoute.kind === 'chat' && !personalVisible) fitDraft();
  navigation?.refresh();
}
let draftSize = '';
function fitDraft() {
  // Bound growth so long drafts cannot push the composer out of a compact window.
  const maximum = Math.min(160, Math.floor(window.innerHeight * 0.2));
  if (!draft.clientWidth) return;
  const nextSize = JSON.stringify([draft.value, draft.clientWidth, maximum]);
  if (nextSize === draftSize) return;
  draftSize = nextSize;
  draft.style.height = '0px';
  const height = Math.max(44, Math.min(maximum, draft.scrollHeight));
  draft.style.height = `${height}px`;
  draft.style.overflowY = draft.scrollHeight > height ? 'auto' : 'hidden';
}
function reflectLatest() {
  $('#jump-latest').hidden =
    transcript.scrollHeight - transcript.clientHeight - transcript.scrollTop <
    160;
}
transcript.addEventListener('scroll', reflectLatest, { passive: true });
$('#jump-latest').addEventListener('click', () => {
  transcript.scrollTop = transcript.scrollHeight;
  reflectLatest();
});
function measureContext(force = false) {
  const agent = agentSelect.value;
  if (!selected || !agent || state.mode !== 'live' || !state.connected) return;
  const key = `${identity}:${contextKey(selected, agent)}`;
  const previous = measurements.get(key);
  if (
    previous?.pending ||
    (!force &&
      previous &&
      Date.now() - previous.at <
        (selectedJob() && activeJob(selectedJob()!) ? 15000 : 60000))
  )
    return;
  const reading = { at: Date.now(), pending: true };
  measurements.set(key, reading);
  background(
    request({ action: 'context', conversation: selected, agent })
      .catch(() => {})
      .finally(() => {
        if (measurements.get(key) === reading) reading.pending = false;
      }),
  );
}
const snapshotReads = new Map<string, string>();
function refreshCurrentProjection() {
  if (
    !selected ||
    !agentSelect.value ||
    inspectorHidden ||
    workspaceRoute.kind !== 'chat' ||
    state.mode !== 'live' ||
    !state.connected
  )
    return;
  const id = `${identity}:${contextKey(selected, agentSelect.value)}`,
    job = selectedJob();
  const stamp = JSON.stringify([
    state.history[selected]?.through,
    job?.id,
    job?.status,
    job?.ending,
  ]);
  if (snapshotReads.get(id) === stamp) return;
  snapshotReads.set(id, stamp);
  refreshProjection(false);
}
const compact = (n: number) =>
  new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 })
    .format(n)
    .toLowerCase();
function renderStatus() {
  const reading = state.contexts[contextKey(selected, agentSelect.value)];
  const agent = (state.agents[scope] ?? []).find(
    (row) => row.name === agentSelect.value,
  );
  const job = selectedJob();
  const sent = reading?.sent;
  const limit = reading?.limit;
  const measured = sent !== undefined && Number.isFinite(sent) && sent >= 0;
  const sized = limit !== undefined && Number.isFinite(limit) && limit > 0;
  const percent =
    measured && sized ? Math.floor((sent / limit) * 100) : undefined;
  const stale =
    reading?.status === 'unavailable' ||
    (state.mode === 'live' && !state.connected);
  const sample = reading?.sample ? 'Sample · ' : '';
  const peak =
    reading?.sentAtTurn === undefined
      ? 'Last turn peak'
      : `Turn ${reading.sentAtTurn} peak`;
  $('#context-model').textContent = reading?.model || agent?.model || '';
  $('#context-model').title = reading?.model || agent?.model || '';
  $('#context-model').parentElement!.hidden = !$('#context-model').textContent;
  $('#context-label').textContent =
    `${sample}${stale && measured ? 'Last measured · ' : ''}${measured ? `${peak} · ${compact(sent)}${sized ? ` / ${compact(limit)}` : ' tokens'}${percent === undefined ? '' : ` · ${percent}%`}` : `Context not measured${sized ? ` · max ${compact(limit)}` : ''}`}`;
  $('#context-status').title =
    reading?.detail ||
    'Peak model-reported prompt tokens in the latest measured turn. A fold can reduce subsequent requests without changing this historical peak; the next turn supplies a new measurement. Your unsent draft is not included.';
  const meter = $<HTMLProgressElement>('#context-meter');
  meter.hidden = percent === undefined;
  meter.value = Math.min(percent ?? 0, 100);
  meter.setAttribute(
    'aria-label',
    `${sample}${stale ? 'Last measured turn peak' : peak} ${percent ?? 'unknown'} percent`,
  );
  $('#context-status').dataset.level =
    percent !== undefined && percent >= 85 ? 'high' : 'normal';
  $('#context-status').dataset.stale = String(stale);
  const allowance = job?.allowance;
  $('#call-budget').hidden = !allowance;
  $('#call-budget').textContent = allowance
    ? `Calls ${allowance.modelCallsSpent}${allowance.maxModelCalls === undefined ? (allowance.noBudget ? ' · no ceiling' : '') : ` / ${allowance.maxModelCalls}`}`
    : '';
  $('#call-budget').title =
    'Model calls spent across the delegation tree; separate from context tokens.';
  const measuredRun = state.jobs
    .filter((row) => row.conversation === selected && row.pace !== undefined)
    .at(-1);
  const pace = $('#run-pace');
  pace.hidden = !measuredRun;
  pace.title = measuredRun
    ? `Latest measured run by ${measuredRun.agent}. Measurements become available when the run finishes.`
    : '';
  const metrics = measuredRun?.pace;
  if (metrics) {
    const titles = {
      tools: 'Tool calls requested by this run’s model.',
      thinking:
        metrics.reasoningTokens === undefined
          ? 'Thinking tokens were not measured.'
          : metrics.reasoningEstimated
            ? 'Thinking tokens include a server estimate (~).'
            : 'Thinking tokens reported by the model.',
      responding:
        'Output tokens generated across this run’s model calls. A dash means not measured.',
      waiting:
        'Time to the first thinking or answer delta, including queueing and prompt processing. A dash means not measured.',
      speed:
        'Generated tokens per second, as measured by the server. A dash means not measured.',
    };
    pace.innerHTML = `<span class="pace-label">Last measured run</span> ${describePace(
      metrics,
    )
      .map(
        (part) =>
          `<span class="pace-stat" data-pace="${part.kind}" title="${esc(titles[part.kind])}" ${part.kind === 'tools' ? `aria-label="${metrics.toolCalls} tool calls"` : ''}>${part.kind === 'tools' ? icon('gear') : ''}${esc(part.text)}</span>`,
      )
      .join(' ')}`;
  } else pace.innerHTML = '';
  const alerts: { label: string; text: string; kind: string }[] = [];
  if (state.connected && state.liveHistory?.status === 'unavailable')
    alerts.push({
      label: 'Live updates unavailable',
      text:
        state.liveHistory.detail ||
        'Refresh manually to read later conversation turns.',
      kind: 'warning',
    });
  else if (state.connected && state.history[selected]?.error)
    alerts.push({
      label: 'History catch-up unavailable',
      text: state.history[selected]?.error ?? 'History catch-up unavailable.',
      kind: 'warning',
    });
  if (percent !== undefined && percent >= 85 && !stale)
    alerts.push({
      label: 'Last turn approached limit',
      text: 'The latest measured turn had a large prompt. A fold may have reduced subsequent requests.',
      kind: 'warning',
    });
  if (!agentSelect.value)
    alerts.push({
      label: 'Agent unavailable',
      text: 'Choose a served conversational agent before sending.',
      kind: 'warning',
    });
  if (job?.status === 'unknown')
    alerts.push({
      label: 'Outcome uncertain',
      text:
        job.detail ||
        'The job may still be running on the server. Reconnect to check its state.',
      kind: 'warning',
    });
  else if (job?.status === 'interrupted')
    alerts.push({
      label: 'Request interrupted',
      text: job.detail || 'Inspect the trajectory before retrying.',
      kind: 'warning',
    });
  else if (job && stoppedJob(job))
    alerts.push({
      label: `Run stopped · ${job.ending?.toLowerCase().replaceAll('_', ' ') ?? 'no answer'}`,
      text:
        [job.text, job.detail].filter(Boolean).join(' — ') ||
        'The run ended without a completed answer.',
      kind: 'warning',
    });
  else if (job?.status === 'finished' && job.detail)
    alerts.push({ label: 'Answer note', text: job.detail, kind: 'note' });
  const panel = $('#conversation-alerts');
  panel.hidden = !alerts.length;
  panel.innerHTML = alerts
    .map(
      (row) =>
        `<div class="conversation-alert ${row.kind}">${icon(row.kind === 'warning' ? 'alert' : 'file')}<strong>${esc(row.label)}</strong><span>${esc(row.text)}</span></div>`,
    )
    .join('');
}
function renderTranscript() {
  const history = state.history[selected];
  const job = selectedJob();
  // Durable arrivals after this job's watermark wait until its stream is settled.
  // Ordinals identify deliveries; equal text in a later harness turn is still a new turn.
  const entries = (history?.entries ?? []).filter(
    (entry) =>
      !job ||
      !activeJob(job) ||
      job.logAfter === undefined ||
      entry.ordinal <= job.logAfter,
  );
  const nextSignature = JSON.stringify([
    selected,
    entries,
    history?.more,
    job?.text,
    job?.status,
    job?.phase,
    job?.tool,
    job?.events.length,
    job?.detail,
    state.approvals,
    state.answeringApprovals,
    approvalControls.revision,
  ]);
  if (signature === nextSignature) return;
  signature = nextSignature;
  const nearBottom =
    transcript.scrollHeight - transcript.clientHeight - transcript.scrollTop <
    100;
  const oldTop = transcript.scrollTop;
  const wasEmpty = !transcript.childElementCount;
  let content = history?.more
    ? `<button class="load-earlier" id="load-earlier">${icon('history')}Load earlier entries</button>`
    : '';
  content += entries
    .map((entry) => {
      if (
        !entry.text ||
        (entry.kind === 'answer' && (entry.asked ?? 0) > 0) ||
        !['utterance', 'answer', 'summary'].includes(entry.kind)
      )
        return '';
      const kind =
        entry.kind === 'utterance'
          ? 'person'
          : entry.kind === 'summary'
            ? 'summary'
            : 'answer';
      const label =
        kind === 'person'
          ? entry.speaker === 'harness'
            ? `Harness · ${entry.speakerName ?? 'external'}`
            : 'You'
          : kind === 'summary'
            ? 'Conversation summary'
            : 'Assistant';
      const approval = noticeApproval(state, {
        kind: 'approval',
        answer: entry.text,
      });
      const ownApproval =
        approval &&
        (approval.conversation === selected || approval.askedIn === selected);
      return (
        message(
          kind,
          label,
          entry.text,
          entry.cut
            ? `Excerpt shown · ${entry.length ?? ''} characters in source`
            : '',
        ) + (ownApproval ? approvalControls.prompt(approval) : '')
      );
    })
    .join('');
  if (job && activeJob(job)) {
    if (job.source !== 'approval')
      content += message('person', 'You', job.task);
    const waiting =
      job.status === 'unknown'
        ? 'The connection was lost. This job may still be running on the server.'
        : job.status === 'cancelling'
          ? 'Stopping…'
          : job.phase === 'thinking'
            ? 'Reasoning…'
            : job.phase === 'tool'
              ? `Using ${job.tool || 'a tool'}…`
              : 'Working on your request…';
    content += `<article class="message answer live" data-copy-source="${esc(job.text)}"><div class="message-label"><span class="answer-mark">${icon('sparkles')}</span><span>${esc(job.agent)} · ${esc(job.status)}</span>${job.text ? copyButton('Copy answer as Markdown', 'answer') : ''}</div><div class="message-body markdown-body">${markdownHtml(job.text || waiting)}${job.status === 'running' && job.phase === 'answer' ? '<span class="stream-caret"></span>' : ''}</div>${job.detail ? `<p class="history-note">${esc(job.detail)}</p>` : ''}</article>`;
  }
  if (!content)
    content = `<div class="empty-chat"><div class="empty-mark">${icon('sparkles')}</div><h2>What are we thinking about?</h2><p>Ask a question, explore your sources, or work through something together.</p><div class="suggestions"><button data-suggestion="Help me explore an idea and find useful sources.">${icon('search')}Explore an idea</button><button data-suggestion="Help me turn these notes into a clear plan.">${icon('file')}Make a plan</button><button data-suggestion="Help me understand a problem step by step.">${icon('route')}Work through a problem</button></div></div>`;
  transcript.innerHTML = content;
  transcript.classList.toggle(
    'is-empty',
    !!transcript.querySelector('.empty-chat'),
  );
  if (nearBottom || wasEmpty) transcript.scrollTop = transcript.scrollHeight;
  else transcript.scrollTop = oldTop;
  reflectLatest();
}
function message(kind: string, label: string, body: string, note = '') {
  return `<article class="message ${kind}" ${kind === 'answer' ? `data-copy-source="${esc(body)}"` : ''}><div class="message-label">${kind === 'answer' ? `<span class="answer-mark">${icon('sparkles')}</span>` : ''}<span>${esc(label)}</span>${kind === 'answer' ? copyButton('Copy answer as Markdown', 'answer') : ''}</div><div class="message-body ${kind === 'answer' ? 'markdown-body' : ''}">${kind === 'answer' ? markdownHtml(body) : esc(body)}</div>${note ? `<p class="history-note">${esc(note)}</p>` : ''}</article>`;
}
function scopeName(project: string) {
  return project === state.personal?.project
    ? 'Personal'
    : state.projects.find((row) => row.name === project)?.displayName ||
        project ||
        'Global resources';
}
function personalSectionName(section: string) {
  return section === 'In' ? 'Inbox' : section;
}
function personalBotConversations(bot: string) {
  const personal = state.personal;
  return personal
    ? state.conversations
        .filter(
          (row) =>
            row.project === personal.project &&
            (chosenAgents[row.id] === bot ||
              personal.botLatest?.[bot] === row.id ||
              state.jobs.some(
                (job) => job.conversation === row.id && job.agent === bot,
              )),
        )
        .map((row) => row.id)
    : [];
}
async function refreshPersonalBots() {
  if (personalBotsLoading || !state.personal || !state.connected) return;
  personalBotsLoading = true;
  render();
  try {
    await request({ action: 'personal-bots' });
  } catch (reason) {
    error(reason instanceof Error ? reason.message : errorMessage(reason));
  } finally {
    personalBotsLoading = false;
    render();
  }
}
async function openPersonalBot(bot: string, create = false) {
  const personal = state.personal;
  if (!personal || busy) return;
  const existing =
    personal.botLatest?.[bot] ?? personalBotConversations(bot).at(-1);
  if (!create && existing) {
    chosenAgents[existing] = bot;
    await choose(existing);
    persist();
    return;
  }
  if (
    !state.agents[personal.project]?.some(
      (row) => row.name === bot && row.bot && row.served,
    )
  )
    return;
  busy = true;
  render();
  try {
    const reply = await request({
      action: 'create',
      project: personal.project,
    });
    if (reply.conversation) {
      chosenAgents[reply.conversation] = bot;
      personalBotExpansion[bot] = true;
      await choose(reply.conversation);
      persist();
      draft.focus();
    }
  } catch (reason) {
    error(reason instanceof Error ? reason.message : errorMessage(reason));
  } finally {
    busy = false;
    render();
  }
}
function renderPersonal() {
  const personal = state.personal;
  const showPersonal =
    personalVisible && !!personal && workspaceRoute.kind === 'chat';
  if ($('#personal-panel').hidden === showPersonal)
    $('#personal-panel').hidden = !showPersonal;
  // Background state updates must not reveal chat underneath an embedded page.
  if (workspaceRoute.kind !== 'chat') application.close();
  application.render();
  const hideStage =
    workspaceRoute.kind !== 'chat' || showPersonal || application.visible;
  if (stage.hidden !== hideStage) stage.hidden = hideStage;
  if (!showPersonal || !personal) return;
  $('#personal-title').textContent = personalSectionName(
    personal.section ?? 'Personal',
  );
  $('#personal-path').textContent = personal.path ?? personal.root ?? '';
  const section = personal.section ?? 'Resources';
  const path = personal.path ?? section;
  const parent = path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : '';
  const bots =
    section === 'Bots'
      ? (state.agents[personal.project] ?? [])
          .filter((row) => row.bot)
          .map(
            (row) =>
              `<button class="personal-bot" data-personal-bot="${esc(row.name)}" ${!row.served ? 'disabled' : ''}>${icon('cpu')}<span>${esc(row.name)}</span>${row.preferred ? '<small>Default</small>' : ''}</button>`,
          )
          .join('')
      : '';
  setHTML(
    $('#personal-content'),
    (personal.error ? `<p role="alert">${esc(personal.error)}</p>` : '') +
      (parent
        ? `<button class="secondary-button" data-personal-path="${esc(parent)}">Back</button>`
        : '') +
      bots +
      (personal.text !== undefined
        ? `<pre class="personal-preview">${esc(personal.text)}</pre>`
        : personal.note
          ? `<p>${esc(personal.note)}</p>`
          : (personal.entries ?? [])
              .map(
                (row) =>
                  `<button class="personal-file" data-personal-path="${esc(row.path)}">${icon(row.directory ? 'folder' : 'file')}<span>${esc(row.name)}</span></button>`,
              )
              .join('') ||
            '<p>This section is empty. Add files in your personal folder.</p>'),
  );
}
$('#personal-sections').addEventListener('click', (event) => {
  const section = (event.target as HTMLElement).closest<HTMLElement>(
    '[data-personal-section]',
  )?.dataset.personalSection as NonNullable<
    DesktopState['personal']
  >['section'];
  if (!section) return;
  personalVisible = true;
  if (state.personal) state.personal.section = section;
  renderPersonal();
  action({ action: 'personal-section', section });
});
$('#personal-close').addEventListener('click', () => {
  personalVisible = false;
  render();
});
$('#personal-conversations').addEventListener('click', (event) => {
  const id = (event.target as HTMLElement).closest<HTMLElement>(
    '[data-conversation]',
  )?.dataset.conversation;
  if (id) background(choose(id));
});
$('#personal-content').addEventListener('click', (event) => {
  const target = event.target as HTMLElement;
  const path = target.closest<HTMLElement>('[data-personal-path]')?.dataset
    .personalPath;
  if (path && state.personal?.section)
    action({
      action: 'personal-section',
      section: state.personal.section,
      path,
    });
  const bot = target.closest<HTMLElement>('[data-personal-bot]')?.dataset
    .personalBot;
  if (bot) background(openPersonalBot(bot, true));
});
$('#personal-bots').addEventListener('click', (event) => {
  const target = event.target as HTMLElement;
  const toggle = target.closest<HTMLElement>('[data-personal-bot-toggle]');
  if (toggle) {
    personalBotExpansion[toggle.dataset.personalBotToggle!] =
      toggle.getAttribute('aria-expanded') !== 'true';
    render();
    persist();
    return;
  }
  const create = target.closest<HTMLElement>('[data-personal-bot-new]')?.dataset
    .personalBotNew;
  if (create) {
    background(openPersonalBot(create, true));
    return;
  }
  const bot = target.closest<HTMLElement>('[data-personal-bot-open]')?.dataset
    .personalBotOpen;
  if (bot) {
    background(openPersonalBot(bot));
    return;
  }
  const id = target.closest<HTMLElement>('[data-conversation]')?.dataset
    .conversation;
  const group = target.closest<HTMLElement>('[data-personal-bot-group]')
    ?.dataset.personalBotGroup;
  if (id && group) {
    chosenAgents[id] = group;
    background(choose(id));
  }
});
$('#personal-conversation-new').addEventListener('click', () => {
  if (state.personal)
    background(chooseScope(state.personal.project).then(newConversation));
});
$('#personal-bots-files').addEventListener('click', () => {
  personalVisible = true;
  action({ action: 'personal-section', section: 'Bots' });
});

function renderInspector() {
  const reading =
      state.contextSnapshots?.[contextKey(selected, agentSelect.value)],
    snapshot = reading?.value;
  const disconnected = state.mode === 'live' && !state.connected;
  const enabled =
    !!selected &&
    !!agentSelect.value &&
    state.mode === 'live' &&
    state.connected &&
    !reading?.loading;
  $<HTMLButtonElement>('#context-refresh').disabled = !enabled;
  $<HTMLButtonElement>('#context-count').disabled = !enabled;
  let content = `<section class="context-section"><h3>${esc(selected ? title(selected) : 'New conversation')}</h3><p class="context-note">Current agent projection, before your next prompt. Your draft is excluded.</p>`;
  if (reading?.loading) content += '<p role="status">Constructing context…</p>';
  if (reading?.error)
    content += `<p role="alert">${esc(reading.error)}${snapshot ? ' Showing the last snapshot.' : ''}</p>`;
  if (disconnected && snapshot)
    content += '<p role="status">Disconnected · last snapshot</p>';
  if (!snapshot)
    content += `<p>${state.mode === 'demo' ? 'Connect to see the server’s constructed projection.' : selected ? 'No context snapshot loaded.' : 'Select a conversation to inspect its context.'}</p>`;
  else {
    content += `<div class="context-key"><span>Agent</span><strong>${esc(snapshot.agent)}</strong></div><div class="context-key"><span>Model</span><strong>${esc(snapshot.model)}</strong></div><p class="context-note">Captured ${esc(new Date(snapshot.captured_at).toLocaleTimeString())}${selectedJob() && activeJob(selectedJob()!) ? ' · work in progress; refresh to update' : ''}</p>`;
    const count = snapshot.count;
    content += `<div class="context-key"><span>Projection tokens</span><strong>${count?.tokens == null ? (count?.basis === 'UNKNOWN' ? 'Unknown' : 'Not counted') : esc(BigInt(count.tokens).toLocaleString())}</strong></div>${count ? `<p class="context-note">${esc(count.basis.toLowerCase())}${count.gaps.length ? ' · ' + esc(count.gaps.join(', ')) : ''}</p>` : ''}`;
    content +=
      '<p class="context-note">Includes the system block, projected conversation and offered tool schemas. Temporary prompts added during execution can change the next request.</p>';
  }
  if (selected)
    content += `<details class="context-details" data-context-details="identity"><summary>Conversation details</summary><div class="context-key"><span>ID</span><code>${esc(selected)}</code></div></details>`;
  content += '</section>';
  if (snapshot) {
    content += `<section class="context-section"><h3>Constructed projection</h3>${snapshot.messages.map((message, index) => `<details class="context-details projection-message" data-context-details="message-${index}"><summary>${index + 1} · ${esc(message.role)}${message.tool_call_id ? ' · ' + esc(message.tool_call_id) : ''}</summary>${message.parts.map((part) => (part.type === 'text' ? `<pre>${esc(part.text)}</pre>` : `<p>Image ${esc(part.uid)} · image bytes omitted from this preview.</p>`)).join('')}${message.tool_calls.length ? `<pre>${esc(JSON.stringify(message.tool_calls, null, 2))}</pre>` : ''}</details>`).join('')}</section>`;
    content += `<section class="context-section"><details class="context-details" data-context-details="schemas"><summary>Offered tools · ${snapshot.tools.length}</summary>${snapshot.tools.map((tool) => `<h4>${esc(tool.name)}</h4><p>${esc(tool.description)}</p><pre>${esc(JSON.stringify(tool.parameters, null, 2))}</pre>`).join('') || '<p>No tools offered.</p>'}</details><details class="context-details" data-context-details="sampling"><summary>Request settings</summary><pre>${esc(JSON.stringify(snapshot.sampling, null, 2))}</pre></details></section>`;
  }
  const element = $('#inspector-content'),
    view = `${identity}:${selected}:${agentSelect.value}`;
  if (element.dataset.stamp !== content || element.dataset.view !== view) {
    const sameView = element.dataset.view === view,
      scroll = sameView ? element.scrollTop : 0;
    const open = sameView
      ? new Set(
          [
            ...element.querySelectorAll<HTMLDetailsElement>('details[open]'),
          ].map((row) => row.dataset.contextDetails),
        )
      : new Set();
    element.innerHTML = content;
    element.dataset.stamp = content;
    element.dataset.view = view;
    element.querySelectorAll<HTMLDetailsElement>('details').forEach((row) => {
      row.open = open.has(row.dataset.contextDetails);
    });
    element.scrollTop = scroll;
  }
}
$('#context-refresh').addEventListener('click', () => refreshProjection(false));
$('#context-count').addEventListener('click', () => refreshProjection(true));
function refreshProjection(measure: boolean) {
  const conversation = selected,
    agent = agentSelect.value,
    account = identity;
  if (conversation && agent)
    void request({
      action: 'context-snapshot',
      conversation,
      agent,
      measure,
    }).catch((reason: unknown) => {
      if (
        conversation === selected &&
        agent === agentSelect.value &&
        account === identity
      )
        error(reason instanceof Error ? reason.message : errorMessage(reason));
    });
}
function renderApprovals() {
  const approvals = state.approvals.filter((row) => row.state === 'asked');
  const bar = $('#approval-bar');
  bar.hidden = !approvals.length;
  bar.innerHTML = approvals
    .map((row) =>
      approvalControls.prompt(
        row,
        row.conversation === selected ? '' : title(row.conversation),
      ),
    )
    .join('');
}

$('#usage-open').addEventListener('click', () => action({ action: 'usage' }));
$('#connection-usage').addEventListener('click', () => {
  dialog.close();
  action({ action: 'usage' });
});
$('#board-open').addEventListener('click', () =>
  action({
    action: 'board-inspection',
    view: 'board',
    ...(scope ? { project: scope } : {}),
  }),
);
$('#swarm-open').addEventListener('click', () =>
  action({
    action: 'board-inspection',
    view: 'swarm',
    ...(scope ? { project: scope } : {}),
  }),
);
$('#manual-open').addEventListener('click', () =>
  action({ action: 'library', view: 'manual', project: null }),
);
$('#library-open').addEventListener('click', () =>
  action({ action: 'library', view: 'sources', project: scope || null }),
);
$('#inbox-open').addEventListener('click', () =>
  action({ action: 'activity', view: 'inbox' }),
);
$('#runs-open').addEventListener('click', () =>
  action({ action: 'activity', view: 'runs' }),
);
$('#studio-open').addEventListener('click', () =>
  action({ action: 'activity', view: 'builder' }),
);
$('#relay-open').addEventListener('click', () => action({ action: 'relay' }));
$('#schedules-open').addEventListener('click', () =>
  action({ action: 'activity', view: 'schedules' }),
);
$('#memories-open').addEventListener('click', () =>
  action({ action: 'library', view: 'memories', project: scope || null }),
);
$('#trajectory-open').addEventListener('click', () => {
  if (selected) action({ action: 'trajectory', conversation: selected });
});
document.addEventListener('click', (event) => {
  const link = (event.target as HTMLElement).closest<HTMLElement>(
    '[data-web-link]',
  );
  if (link?.dataset.webLink) {
    event.preventDefault();
    action({ action: 'open-link', url: link.dataset.webLink });
  }
});
const connectionMenu = $<HTMLElement>('#connection-menu');
$('#connection-button').addEventListener('click', () => {
  connectionMenu.innerHTML =
    (state.namedConnections ?? [])
      .map(
        (row) =>
          `<button type="button" data-connection="${esc(row.name)}" ${row.name === state.selectedConnection ? 'aria-current="true"' : ''}><strong>${esc(row.name)}</strong><small>${esc(row.account)} · ${esc(row.server)}</small></button>`,
      )
      .join('') +
    '<button type="button" id="connection-manage">Add / manage connection…</button>';
  connectionMenu.showPopover();
  $('#connection-button').setAttribute('aria-expanded', 'true');
  connectionMenu.querySelector<HTMLButtonElement>('button')?.focus();
});
connectionMenu.addEventListener('toggle', () =>
  $('#connection-button').setAttribute(
    'aria-expanded',
    String(connectionMenu.matches(':popover-open')),
  ),
);
connectionMenu.addEventListener('keydown', (event) => {
  const buttons = [
    ...connectionMenu.querySelectorAll<HTMLButtonElement>('button'),
  ];
  const current = buttons.indexOf(document.activeElement as HTMLButtonElement);
  if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
    event.preventDefault();
    const index =
      event.key === 'Home'
        ? 0
        : event.key === 'End'
          ? buttons.length - 1
          : (current + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) %
            buttons.length;
    buttons[index]?.focus();
  }
});
connectionMenu.addEventListener(
  'click',
  ownedEvent(async (event: MouseEvent) => {
    const button = (event.target as HTMLElement).closest<HTMLButtonElement>(
      'button',
    );
    if (!button) return;
    connectionMenu.hidePopover();
    if (button.id === 'connection-manage') {
      openConnect();
      return;
    }
    if (button.dataset.connection) {
      try {
        await request({
          action: 'connection-select',
          name: button.dataset.connection,
        });
      } catch (reason) {
        openConnect();
        $('#connect-error').textContent =
          reason instanceof Error ? reason.message : errorMessage(reason);
        $('#connect-error').hidden = false;
      }
    }
  }),
);
for (const action of ['connection-rename', 'connection-remove'] as const) {
  $('#' + action).addEventListener(
    'click',
    ownedEvent(async () => {
      if (!state.selectedConnection)
        throw new Error('Select a saved connection first.');
      await request(
        action === 'connection-rename'
          ? {
              action,
              name: state.selectedConnection,
              nextName: $<HTMLInputElement>('#connection-name').value,
            }
          : { action, name: state.selectedConnection },
      );
      dialog.close();
    }),
  );
}
$('#connect-sidebar').addEventListener('click', openConnect);
$('#notice-connect').addEventListener('click', openConnect);
$('#close-dialog').addEventListener('click', () => dialog.close());
$('#dismiss-error').addEventListener('click', () => {
  $('#error').hidden = true;
});
$('#new-conversation').addEventListener('click', () =>
  background(newConversation()),
);
for (const navigation of ['#conversations', '#application-conversations'])
  $(navigation).addEventListener('click', (event) => {
    const toggle = (event.target as HTMLElement).closest<HTMLElement>(
      '[data-project-toggle]',
    );
    if (toggle) {
      const project = toggle.dataset.projectToggle!;
      const expansion = $<HTMLInputElement>('#conversation-filter').value
        ? filterExpansion
        : projectExpansion;
      expansion[project] = toggle.getAttribute('aria-expanded') !== 'true';
      render();
      persist();
      return;
    }
    const project = (event.target as HTMLElement).closest<HTMLElement>(
      '[data-project]',
    )?.dataset.project;
    if (project !== undefined) {
      background(
        (async () => {
          await chooseScope(project);
          if (
            state.projects.find((row) => row.name === project)?.kind ===
            'application'
          ) {
            application.open(project);
            renderPersonal();
          }
        })(),
      );
      return;
    }
    const id = (event.target as HTMLElement).closest<HTMLElement>(
      '[data-conversation]',
    )?.dataset.conversation;
    if (id) background(choose(id));
  });
$('#running-jobs').addEventListener('click', (event) => {
  if ((event.target as HTMLElement).closest('[data-information-job]')) {
    action({ action: 'library', view: 'sources', project: scope || null });
    return;
  }
  const id = (event.target as HTMLElement).closest<HTMLElement>(
    '[data-conversation]',
  )?.dataset.conversation;
  if (id) background(choose(id));
});
$('#conversation-filter').addEventListener('input', () => {
  filterExpansion = {};
  render();
});
agentSelect.addEventListener('change', () => {
  chosenAgents[selected] = agentSelect.value;
  render();
  persist();
});
draft.addEventListener('input', () => {
  drafts[selected] = draft.value;
  persist();
  render();
});
draft.addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    $<HTMLFormElement>('#composer').requestSubmit();
  }
});
$('#composer').addEventListener(
  'submit',
  ownedEvent(async (event: SubmitEvent) => {
    event.preventDefault();
    if ($<HTMLButtonElement>('#send').disabled || busy) return;
    const message = draft.value;
    const agent = agentSelect.value;
    if (!message.trim()) return;
    const command = composerCommand(message);
    if (command && !/^\/(skill|orchestration):/.test(command.name)) {
      try {
        await runWorkspaceCommand(command.name, command.argumentsText);
        if (draft.value === message) {
          draft.value = '';
          drafts[selected] = '';
          persist();
          render();
        }
      } catch (reason) {
        error(reason instanceof Error ? reason.message : errorMessage(reason));
      }
      return;
    }
    if (!selected) await newConversation();
    if (!selected) return;
    const conversation = selected;
    busy = true;
    drafts[conversation] = '';
    draft.value = '';
    persist();
    render();
    try {
      await request({ action: 'run', conversation, agent, text: message });
    } catch (reason) {
      drafts[conversation] = message;
      if (selected === conversation) draft.value = message;
      error(reason instanceof Error ? reason.message : errorMessage(reason));
    } finally {
      busy = false;
      persist();
      render();
    }
  }),
);
$('#cancel').addEventListener('click', () => {
  const job = selectedJob();
  if (job) action({ action: 'cancel', job: job.id });
});
transcript.addEventListener('click', (event) => {
  const button = (event.target as HTMLElement).closest<HTMLElement>('button');
  if (!button) return;
  if (button.dataset.suggestion) {
    draft.value = button.dataset.suggestion;
    drafts[selected] = draft.value;
    persist();
    render();
    draft.focus();
  }
  if (button.id === 'load-earlier')
    background(loadHistory(selected, state.history[selected]?.oldest));
});
$('#refresh').addEventListener(
  'click',
  ownedEvent(async () => {
    if (workspaceRoute.kind === 'manage') {
      $('#workspace-manage [data-read]').click();
      return;
    }
    if (workspaceRoute.kind !== 'chat') {
      action({ action: 'workspace-refresh' });
      return;
    }
    try {
      await request({ action: 'scope', ...(scope ? { project: scope } : {}) });
      await request({ action: 'refresh' });
      await loadHistory();
    } catch (reason) {
      error(reason instanceof Error ? reason.message : errorMessage(reason));
    }
    measureContext(true);
  }),
);
function move() {
  chat.style.transform =
    docked || expanded ? 'none' : `translate(${offset.x}px, ${offset.y}px)`;
}
$('#dock').addEventListener('click', () => {
  docked = !docked;
  chat.classList.toggle('docked', docked);
  chat.classList.toggle('floating', !docked);
  $('#dock').setAttribute('aria-label', docked ? 'Float chat' : 'Dock chat');
  $('#dock').title = docked ? 'Float chat' : 'Dock chat';
  move();
  $('#dock').innerHTML = icon(docked ? 'float' : 'dock');
  $('#dock').setAttribute('aria-pressed', String(docked));
});
$('#expand').addEventListener('click', () => {
  expanded = !expanded;
  stage.classList.toggle('expanded', expanded);
  $('#expand').setAttribute(
    'aria-label',
    expanded ? 'Restore chat size' : 'Expand chat',
  );
  $('#expand').title = expanded ? 'Restore chat size' : 'Expand chat';
  $('#expand').innerHTML = icon(expanded ? 'minimize' : 'maximize');
  $('#expand').setAttribute('aria-pressed', String(expanded));
  reflectInspector();
  sidebarResize.refresh();
  contextResize.refresh();
  move();
});
$('#inspector-toggle').addEventListener('click', () => {
  showInspector(getComputedStyle($('#inspector')).display === 'none');
});
function showInspector(show: boolean) {
  if (show && expanded) $('#expand').click();
  inspectorHidden = !show;
  stage.classList.toggle('inspector-hidden', !show);
  stage.classList.toggle('show-inspector', show);
  reflectInspector();
  sidebarResize.refresh();
  contextResize.refresh();
  if (!show) $('#inspector-toggle').focus();
  else if (window.innerWidth <= 1010) $('#inspector-close').focus();
}
function reflectInspector() {
  const shown = getComputedStyle($('#inspector')).display !== 'none';
  $('#inspector-toggle').setAttribute('aria-expanded', String(shown));
  $('#context-scrim').hidden = !shown || window.innerWidth > 1010;
}
$('#inspector-close').addEventListener('click', () => showInspector(false));
type InfoTab = 'context' | 'commands' | 'files';
let infoTab: InfoTab = 'context';
function showInfoTab(tab: InfoTab) {
  infoTab = tab;
  for (const name of ['context', 'commands', 'files'] as const) {
    const active = name === tab;
    const button = $<HTMLButtonElement>(`#info-${name}-tab`);
    button.setAttribute('aria-selected', String(active));
    button.tabIndex = active ? 0 : -1;
    $(`#info-${name}`).hidden = !active;
  }
  showInspector(true);
  if (tab === 'context') refreshCurrentProjection();
  if (tab === 'commands' && state.mode === 'live' && state.connected)
    action({ action: 'scope', ...(scope ? { project: scope } : {}) });
}

async function runWorkspaceCommand(name: string, argument: string) {
  if (name === '/') {
    showInfoTab('commands');
    return;
  }
  if (name === '/project') {
    if (!argument.trim()) throw new Error('Use /project <name>.');
    if (!state.projects.some((row) => row.name === argument.trim()))
      throw new Error('Choose a project listed in the sidebar.');
    await chooseScope(argument.trim());
    return;
  }
  if (!desktopCommands.some(([command]) => command === name))
    throw new Error(
      `Unknown command: ${name}. Use /help to see available commands.`,
    );
  if (argument.trim())
    throw new Error(
      `${name} does not take arguments. Use /help to see available commands.`,
    );
  if (['/help', '/commands', '/skills', '/orchestrations'].includes(name)) {
    showInfoTab('commands');
    return;
  }
  if (name === '/new') {
    await newConversation();
    return;
  }
  if (name === '/bots') {
    $('#agent-identity').setAttribute('open', '');
    agentSelect.focus();
    return;
  }
  if (name === '/projects') {
    setSidebar(false);
    setProjectsCollapsed(false);
    $('#conversation-filter').focus();
    return;
  }
  if (name === '/conversations') {
    $('#navigation-open').click();
    return;
  }
  if (name === '/context') {
    showInfoTab('context');
    return;
  }
  if (name === '/earlier') {
    await loadHistory(selected, state.history[selected]?.oldest);
    return;
  }
  if (name === '/refresh') {
    await request({ action: 'scope', ...(scope ? { project: scope } : {}) });
    await request({ action: 'refresh' });
    await loadHistory();
    return;
  }
  if (name === '/cancel') {
    const job = selectedJob();
    if (!job || !activeJob(job))
      throw new Error('This conversation has no active chat job.');
    await request({ action: 'cancel', job: job.id });
    return;
  }
  const button = (
    {
      '/log': '#trajectory-open',
      '/trajectory': '#trajectory-open',
      '/inbox': '#inbox-open',
      '/runs': '#runs-open',
      '/schedule': '#schedules-open',
      '/memory': '#memories-open',
      '/board': '#board-open',
      '/swarm': '#swarm-open',
      '/usage': '#usage-open',
      '/approvals': '#controls-button',
    } as Record<string, string>
  )[name];
  if (button) $(button).click();
}
for (const name of ['context', 'commands', 'files'] as const) {
  const button = $<HTMLButtonElement>(`#info-${name}-tab`);
  button.addEventListener('click', () => showInfoTab(name));
  button.addEventListener('keydown', (event) => {
    const tabs: InfoTab[] = ['context', 'commands', 'files'];
    const next =
      event.key === 'Home'
        ? 0
        : event.key === 'End'
          ? 2
          : event.key === 'ArrowRight'
            ? (tabs.indexOf(infoTab) + 1) % 3
            : event.key === 'ArrowLeft'
              ? (tabs.indexOf(infoTab) + 2) % 3
              : -1;
    if (next < 0) return;
    event.preventDefault();
    showInfoTab(tabs[next]!);
    $(`#info-${tabs[next]}-tab`).focus();
  });
}

$('#context-scrim').addEventListener('click', () => showInspector(false));
document.addEventListener('keydown', (event) => {
  if (
    event.key === 'Escape' &&
    !dialog.open &&
    !navigation?.isOpen() &&
    window.innerWidth <= 1010 &&
    $('#inspector-toggle').getAttribute('aria-expanded') === 'true'
  ) {
    event.preventDefault();
    showInspector(false);
  }
});
window.addEventListener('resize', () => {
  reflectInspector();
  fitDraft();
  reflectLatest();
});
new ResizeObserver(reflectLatest).observe(transcript);
reflectInspector();
const grip = $('#chat-grip');
let drag: { x: number; y: number; left: number; top: number } | undefined;
const clamp = (value: number) => Math.max(-12, Math.min(12, value));
grip.addEventListener('pointerdown', (event) => {
  if (docked || expanded) return;
  drag = { x: event.clientX, y: event.clientY, left: offset.x, top: offset.y };
  grip.setPointerCapture(event.pointerId);
});
grip.addEventListener('pointermove', (event) => {
  if (!drag) return;
  offset = {
    x: clamp(drag.left + event.clientX - drag.x),
    y: clamp(drag.top + event.clientY - drag.y),
  };
  move();
});
grip.addEventListener('pointerup', () => {
  drag = undefined;
});
grip.addEventListener('pointercancel', () => {
  drag = undefined;
});
grip.addEventListener('keydown', (event) => {
  if (docked || expanded || !event.key.startsWith('Arrow')) return;
  event.preventDefault();
  offset.x = clamp(
    offset.x +
      (event.key === 'ArrowRight' ? 4 : event.key === 'ArrowLeft' ? -4 : 0),
  );
  offset.y = clamp(
    offset.y +
      (event.key === 'ArrowDown' ? 4 : event.key === 'ArrowUp' ? -4 : 0),
  );
  move();
});
function persistLayout() {
  try {
    localStorage.setItem(
      'plowshare.desktop.layout.v1',
      JSON.stringify({
        sidebarHidden,
        projectsCollapsed,
        applicationsCollapsed,
        personalCollapsed,
        personalConversationsCollapsed,
        personalBotsCollapsed,
      }),
    );
  } catch {
    /* Keep the visible choice. */
  }
}
function setSidebar(hidden: boolean) {
  sidebarHidden = hidden;
  $('.workspace').classList.toggle('sidebar-hidden', hidden);
  $('#sidebar').hidden = hidden;
  $('#sidebar-toggle').setAttribute('aria-expanded', String(!hidden));
  $('#sidebar-toggle').setAttribute(
    'aria-label',
    hidden ? 'Show sidebar' : 'Hide sidebar',
  );
  $('#sidebar-toggle').title = `${hidden ? 'Show' : 'Hide'} sidebar · ⌘/Ctrl B`;
  persistLayout();
  reflectInspector();
  fitDraft();
  reflectLatest();
}
try {
  const layout: unknown = JSON.parse(
    localStorage.getItem('plowshare.desktop.layout.v1') ?? '{}',
  );
  if (!isObject(layout)) throw new Error('Unreadable saved layout.');
  sidebarHidden = layout.sidebarHidden === true;
  projectsCollapsed = layout.projectsCollapsed === true;
  applicationsCollapsed = layout.applicationsCollapsed === true;
  personalCollapsed = layout.personalCollapsed === true;
  personalConversationsCollapsed =
    layout.personalConversationsCollapsed === true;
  personalBotsCollapsed = layout.personalBotsCollapsed !== false;
} catch {
  /* Use the workspace layout. */
}
setSidebar(sidebarHidden);
function setProjectsCollapsed(collapsed: boolean) {
  projectsCollapsed = collapsed;
  $('#sidebar').classList.toggle('projects-collapsed', collapsed);
  $('#sidebar-projects').hidden = collapsed;
  $('#projects-toggle').setAttribute('aria-expanded', String(!collapsed));
  $('#project-add').hidden = collapsed;
  persistLayout();
}
function setApplicationsCollapsed(collapsed: boolean) {
  applicationsCollapsed = collapsed;
  $('#sidebar-applications').hidden = collapsed;
  $('#applications-toggle').setAttribute('aria-expanded', String(!collapsed));
  persistLayout();
}
setApplicationsCollapsed(applicationsCollapsed);
$('#applications-toggle').addEventListener('click', () =>
  setApplicationsCollapsed(!applicationsCollapsed),
);
setProjectsCollapsed(projectsCollapsed);
$('#projects-toggle').addEventListener('click', () =>
  setProjectsCollapsed(!projectsCollapsed),
);
function setPersonalCollapsed(collapsed: boolean) {
  personalCollapsed = collapsed;
  $('#personal-contents').hidden = collapsed;
  $('#personal-toggle').setAttribute('aria-expanded', String(!collapsed));
  persistLayout();
}
setPersonalCollapsed(personalCollapsed);
$('#personal-toggle').addEventListener('click', () =>
  setPersonalCollapsed(!personalCollapsed),
);
function reflectPersonalSections() {
  $('#personal-conversations').hidden = personalConversationsCollapsed;
  $('#personal-conversations-toggle').setAttribute(
    'aria-expanded',
    String(!personalConversationsCollapsed),
  );
  $('#personal-bots').hidden = personalBotsCollapsed;
  $('#personal-bots-toggle').setAttribute(
    'aria-expanded',
    String(!personalBotsCollapsed),
  );
}
reflectPersonalSections();
$('#personal-conversations-toggle').addEventListener('click', () => {
  personalConversationsCollapsed = !personalConversationsCollapsed;
  reflectPersonalSections();
  persistLayout();
});
$('#personal-bots-toggle').addEventListener('click', () => {
  personalBotsCollapsed = !personalBotsCollapsed;
  reflectPersonalSections();
  persistLayout();
  if (!personalBotsCollapsed) background(refreshPersonalBots());
});
const sidebarResize = installPaneResize({
  container: $('.workspace'),
  pane: $('#sidebar'),
  other: $('.main-area'),
  key: 'sidebar',
  label: 'Resize navigation sidebar',
  property: '--sidebar-width',
  minimum: 200,
  maximum: () =>
    Math.min(
      480,
      $('.workspace').clientWidth -
        (window.innerWidth > 1010 &&
        getComputedStyle($('#inspector')).display !== 'none'
          ? 700
          : 520),
    ),
});
const contextResize = installPaneResize({
  container: stage,
  pane: $('#inspector'),
  other: chat,
  side: 'right',
  key: 'context',
  label: 'Resize Info panel',
  property: '--context-width',
  minimum: 220,
  maximum: () => {
    const style = getComputedStyle(stage);
    const space =
      window.innerWidth <= 1010
        ? 40
        : parseFloat(style.paddingLeft) +
          parseFloat(style.paddingRight) +
          parseFloat(style.columnGap) +
          350;
    return Math.min(520, stage.clientWidth - space);
  },
});

$('#sidebar-toggle').addEventListener('click', () =>
  setSidebar(!sidebarHidden),
);
document.addEventListener('keydown', (event) => {
  if (
    !(event.metaKey || event.ctrlKey) ||
    event.altKey ||
    event.isComposing ||
    document.querySelector('dialog:modal') ||
    navigation?.isOpen()
  )
    return;
  if (event.key.toLowerCase() === 'n') {
    event.preventDefault();
    background(newConversation());
  }
  if (event.key.toLowerCase() === 'b') {
    event.preventDefault();
    if ($('#sidebar').contains(document.activeElement))
      $('#sidebar-toggle').focus();
    setSidebar(!sidebarHidden);
  }
});
const navigation = installNavigation((): NavigationItem[] => {
  if (!state) return [];
  const available = state.mode === 'demo' || state.connected;
  return [
    ...[...state.conversations]
      .sort((a, b) => Number(b.id === selected) - Number(a.id === selected))
      .map((row) => ({
        id: `conversation:${row.id}`,
        title: title(row.id),
        detail: `${scopeName(row.project ?? '')}${row.id === selected ? ' · Current' : ''}`,
        group: 'Conversations' as const,
        icon: 'message' as const,
        run: async () => {
          await choose(row.id);
          draft.focus();
        },
      })),
    ...state.projects
      .map((row) => row.name)
      .map((name) => ({
        id: `workspace:${name}`,
        title: scopeName(name),
        detail: name === scope ? 'Current workspace' : 'Switch workspace',
        group: 'Workspaces' as const,
        icon: 'folder' as const,
        disabled: !available,
        run: async () => {
          await chooseScope(name);
          draft.focus();
        },
      })),
    {
      id: 'new',
      title: 'New conversation',
      detail: scopeName(scope),
      group: 'Actions',
      icon: 'plus',
      shortcut: '⌘/Ctrl N',
      disabled: !available || busy,
      run: newConversation,
    },
    {
      id: 'compose',
      title: 'Focus message',
      detail: 'Return to your draft',
      group: 'Actions',
      icon: 'message',
      run: async () => {
        await request({ action: 'workspace-chat' });
        draft.focus();
      },
    },
    {
      id: 'board',
      title: 'Open board',
      detail: 'Inspect topics, messages and decisions',
      group: 'Actions',
      icon: 'layers',
      run: () =>
        action({
          action: 'board-inspection',
          view: 'board',
          ...(scope ? { project: scope } : {}),
        }),
    },
    {
      id: 'swarm',
      title: 'Open swarm',
      detail: 'Inspect active seats and the model queue',
      group: 'Actions',
      icon: 'swarm',
      run: () =>
        action({
          action: 'board-inspection',
          view: 'swarm',
          ...(scope ? { project: scope } : {}),
        }),
    },
    {
      id: 'trajectory',
      title: 'Open trajectory',
      detail: 'Inspect this conversation’s recorded steps',
      group: 'Actions',
      icon: 'route',
      disabled: !selected,
      run: () => action({ action: 'trajectory', conversation: selected }),
    },
    {
      id: 'usage',
      title: 'Usage',
      detail: 'Recorded calls, tokens and costs on this server',
      group: 'Actions',
      icon: 'activity',
      run: () => action({ action: 'usage' }),
    },
    {
      id: 'context',
      title: 'Toggle Info panel',
      detail: 'Current constructed projection and offered tools',
      group: 'Actions',
      icon: 'panel',
      run: async () => {
        await request({ action: 'workspace-chat' });
        $('#inspector-toggle').click();
      },
    },
    {
      id: 'sidebar',
      title: sidebarHidden ? 'Show sidebar' : 'Hide sidebar',
      detail: 'Conversation navigation',
      group: 'Actions',
      icon: 'sidebar',
      shortcut: '⌘/Ctrl B',
      run: () => setSidebar(!sidebarHidden),
    },
    {
      id: 'files',
      title: 'Project files',
      detail: 'Connect or disconnect a local folder',
      group: 'Actions',
      icon: 'folder',
      run: () => $('#files-open').click(),
    },
    {
      id: 'connection',
      title: 'Connection settings',
      detail: state.mode === 'demo' ? 'Connect your server' : state.base,
      group: 'Actions',
      icon: 'server',
      run: openConnect,
    },
  ];
}, error);
$('#connection-form').addEventListener(
  'submit',
  ownedEvent(async (event: SubmitEvent) => {
    event.preventDefault();
    const button = $<HTMLButtonElement>('#submit-connection');
    button.disabled = true;
    button.innerHTML = `${icon('server')}Connecting…`;
    $('#connect-error').hidden = true;
    const password = $<HTMLInputElement>('#password').value;
    $<HTMLInputElement>('#password').value = '';
    try {
      await request({
        action: 'connect',
        ...($<HTMLInputElement>('#connection-name').value.trim()
          ? { name: $<HTMLInputElement>('#connection-name').value.trim() }
          : {}),
        base: $<HTMLInputElement>('#server-url').value,
        handle: $<HTMLInputElement>('#handle').value,
        password,
      });
      dialog.close();
      await request({ action: 'scope', ...(scope ? { project: scope } : {}) });
      await loadHistory();
    } catch (reason) {
      $('#connect-error').textContent =
        reason instanceof Error ? reason.message : errorMessage(reason);
      $('#connect-error').hidden = false;
    } finally {
      button.disabled = false;
      button.innerHTML = `${icon('server')}Connect`;
    }
  }),
);
$('#use-demo').addEventListener(
  'click',
  ownedEvent(async () => {
    await request({ action: 'demo' });
    dialog.close();
  }),
);
$('#disconnect').addEventListener(
  'click',
  ownedEvent(async () => {
    await request({ action: 'disconnect' });
    dialog.close();
  }),
);
window.plowshare.subscribe(update);
void request({ action: 'bootstrap' })
  .then(async (reply) => {
    if (reply.route) showWorkspace(reply.route);
    if (state.mode === 'live' && state.connected) {
      await request({ action: 'scope', ...(scope ? { project: scope } : {}) });
      await loadHistory();
    }
  })
  .catch((reason: unknown) => error(errorMessage(reason)));

const renderSync = installSync(request);
let filePicking = false;
let addingProject = false;
let fileAccessError = '';
let fileAccessScope = '';
const deniedFileAccess = new Set<string>();
const fileAccessKey = () => `${identity}:${scope}`;
function renderFiles() {
  renderSync(state, scope, addingProject);
  const personal = scope === state.personal?.project && !addingProject;
  const folder = state.projectFolders?.find((row) => row.name === scope);
  const recorded = state.projects.find((row) => row.name === scope);
  const recordedLocation =
    !personal && recorded?.workspace
      ? `${recorded.machine ?? 'Server'} · ${recorded.workspace}`
      : '';
  const localLocation = folder ? `${folder.machine} · ${folder.path}` : '';
  const recoverable =
    !!recorded?.workspace &&
    !!state.localMachine &&
    recorded.machine === state.localMachine;
  const files =
    folder?.files ??
    (state.files.project === scope ? state.files : { status: 'off' as const });
  const ready = files.status === 'ready';
  if (fileAccessScope !== scope) {
    fileAccessScope = scope;
    fileAccessError = '';
  }
  const prompt = $('#file-access-prompt');
  const denied = deniedFileAccess.has(fileAccessKey());
  prompt.hidden = state.mode !== 'live' || !state.connected || ready || denied;
  const connecting =
    files.status === 'opening' || state.projectPreparing?.includes(scope);
  $('#file-access-title').textContent = connecting
    ? 'Connecting file access…'
    : personal
      ? 'Personal file access unavailable'
      : files.status === 'lost'
        ? 'Restore file access'
        : 'Allow file access';
  $('#file-access-description').textContent = personal
    ? 'Personal uses the selected connection’s default store. Retry to reconnect its files.'
    : folder || recoverable
      ? `Allow agents to read and change files in ${folder?.path ?? recorded!.workspace}. Access is remembered on this computer; disconnect it in Project files.`
      : `Choose a local folder${scope ? ` for ${scope}` : ' to add a project'}. Agents can read and change files inside it. Access is remembered on this computer.`;
  $('#file-access-error').textContent =
    fileAccessError ||
    folder?.error ||
    files.detail ||
    (personal ? state.personal?.error : '') ||
    '';
  $('#file-access-error').hidden = !$('#file-access-error').textContent;
  $('#file-access-allow').textContent = connecting
    ? 'Connecting…'
    : personal
      ? 'Retry'
      : 'Approve';
  $('#file-access-deny').hidden = personal;
  $<HTMLButtonElement>('#file-access-allow').disabled =
    filePicking || !!connecting;
  $<HTMLButtonElement>('#file-access-deny').disabled =
    filePicking || !!connecting;
  $('#files-label').textContent =
    files.status === 'opening'
      ? 'Connecting files…'
      : ready
        ? files.project === scope
          ? 'Files connected'
          : `Files: ${files.project}`
        : personal
          ? 'Personal files unavailable'
          : denied
            ? 'File access denied'
            : files.status === 'lost'
              ? 'Files disconnected'
              : 'Connect files';
  $('#files-open').title = files.root
    ? `${files.project} · ${files.root}${files.detail ? ` · ${files.detail}` : ''}`
    : personal
      ? 'Personal files in this connection’s default store'
      : 'Connect a local project folder';
  $('#files-open').dataset.status = files.status;
  $('#files-title').textContent = addingProject
    ? 'Add project'
    : personal
      ? 'Personal file access'
      : 'Project file access';
  $('#files-description').textContent = addingProject
    ? 'Choose a folder. Its Plowshare marker or folder name identifies the project on the server.'
    : personal
      ? 'Personal files use the selected connection’s default store. Recreating it preserves the current store in a dated recovery directory.'
      : ready
        ? `Serving ${files.project}${files.project !== scope ? ' · another workspace' : ''}`
        : files.status === 'opening'
          ? `Connecting ${files.project}…`
          : files.status === 'lost'
            ? `File access lost for ${files.project}. Choose the folder again.`
            : `Choose a folder for ${scope || 'a new project'}.`;
  $('#files-root').textContent = addingProject
    ? ''
    : [
        recordedLocation,
        localLocation && localLocation !== recordedLocation
          ? `Saved folder: ${localLocation}`
          : '',
      ]
        .filter(Boolean)
        .join('\n') ||
      files.root ||
      '';
  $('#files-detail').textContent =
    state.projectConfigError ??
    state.projectListError ??
    (addingProject
      ? ''
      : (folder?.error ??
        files.detail ??
        (personal ? state.personal?.error : undefined) ??
        ''));
  $('#files-withdraw').hidden = addingProject || personal || !ready;
  $('#files-reopen').hidden =
    addingProject || (!personal && !folder && !recoverable) || ready;
  $('#files-reopen').innerHTML =
    `${icon('refresh')}${personal ? 'Retry Personal files' : folder ? 'Reconnect files' : 'Connect recorded folder'}`;
  $('#files-forget').hidden = addingProject || personal || !folder;
  $('#files-choose').hidden = personal;
  $('#files-recreate').hidden = addingProject || !personal;
  $<HTMLButtonElement>('#files-recreate').disabled =
    filePicking || files.status === 'opening' || !state.connected;
  $<HTMLButtonElement>('#files-reopen').disabled =
    filePicking || !state.connected;
  $<HTMLButtonElement>('#files-forget').disabled =
    filePicking || !state.connected;
  $<HTMLButtonElement>('#files-withdraw').disabled = filePicking;
  $<HTMLButtonElement>('#files-choose').disabled =
    filePicking || files.status === 'opening' || !state.connected;
}
$('#project-add').addEventListener(
  'click',
  ownedEvent(async () => {
    await request({ action: 'workspace-chat' });
    addingProject = true;
    $('#files-error').hidden = true;
    renderFiles();
    showInfoTab('files');
  }),
);
$('#files-open').addEventListener(
  'click',
  ownedEvent(async () => {
    await request({ action: 'workspace-chat' });
    deniedFileAccess.delete(fileAccessKey());
    addingProject = false;
    $('#files-error').hidden = true;
    renderFiles();
    showInfoTab('files');
  }),
);
$('#file-access-allow').addEventListener(
  'click',
  ownedEvent(async () => {
    const project = scope;
    const saved = state.projectFolders?.some((row) => row.name === project);
    const recorded = state.projects.find((row) => row.name === project);
    const recoverable =
      !!recorded?.workspace &&
      !!state.localMachine &&
      recorded.machine === state.localMachine;
    filePicking = true;
    fileAccessError = '';
    renderFiles();
    try {
      const reply = await request(
        project === state.personal?.project || saved || recoverable
          ? { action: 'project-open', project }
          : { action: 'files-choose', ...(project ? { project } : {}) },
      );
      if (reply.rootProject) await chooseScope(reply.rootProject);
    } catch (reason) {
      if (scope === project)
        fileAccessError =
          reason instanceof Error ? reason.message : errorMessage(reason);
      else
        error(reason instanceof Error ? reason.message : errorMessage(reason));
    } finally {
      filePicking = false;
      renderFiles();
    }
  }),
);
$('#file-access-deny').addEventListener(
  'click',
  ownedEvent(async () => {
    const project = scope;
    const key = fileAccessKey();
    filePicking = true;
    fileAccessError = '';
    renderFiles();
    try {
      if (state.projectFolders?.some((row) => row.name === project))
        await request({ action: 'files-withdraw', project });
      deniedFileAccess.add(key);
    } catch (reason) {
      if (scope === project)
        fileAccessError =
          reason instanceof Error ? reason.message : errorMessage(reason);
      else
        error(reason instanceof Error ? reason.message : errorMessage(reason));
    } finally {
      filePicking = false;
      renderFiles();
    }
  }),
);
$('#files-choose').addEventListener(
  'click',
  ownedEvent(async () => {
    filePicking = true;
    $('#files-error').hidden = true;
    renderFiles();
    try {
      const reply = await request({
        action: 'files-choose',
        ...(!addingProject && scope ? { project: scope } : {}),
      });
      if (reply.rootProject) {
        await chooseScope(reply.rootProject);
        addingProject = false;
        renderFiles();
      }
    } catch (error) {
      $('#files-error').textContent = errorMessage(error);
      $('#files-error').hidden = false;
    } finally {
      filePicking = false;
      renderFiles();
    }
  }),
);
$('#files-withdraw').addEventListener(
  'click',
  ownedEvent(async () => {
    filePicking = true;
    $('#files-error').hidden = true;
    renderFiles();
    try {
      await request({
        action: 'files-withdraw',
        ...(scope ? { project: scope } : {}),
      });
    } catch (error) {
      $('#files-error').textContent = errorMessage(error);
      $('#files-error').hidden = false;
    } finally {
      filePicking = false;
      renderFiles();
    }
  }),
);

$('#files-recreate').addEventListener(
  'click',
  ownedEvent(async () => {
    filePicking = true;
    $('#files-error').hidden = true;
    renderFiles();
    try {
      await request({ action: 'personal-recreate' });
    } catch (reason) {
      $('#files-error').textContent = errorMessage(reason);
      $('#files-error').hidden = false;
    } finally {
      filePicking = false;
      renderFiles();
    }
  }),
);

for (const [selector, actionName] of [
  ['#files-reopen', 'project-open'],
  ['#files-forget', 'project-remove'],
] as const) {
  $(selector).addEventListener(
    'click',
    ownedEvent(async () => {
      filePicking = true;
      $('#files-error').hidden = true;
      renderFiles();
      try {
        await request({ action: actionName, project: scope });
      } catch (error) {
        $('#files-error').textContent = errorMessage(error);
        $('#files-error').hidden = false;
      } finally {
        filePicking = false;
        renderFiles();
      }
    }),
  );
}

const operatorControls = installOperator(
  window.plowshare,
  () => state,
  () => scope,
  {
    host: $('#workspace-manage'),
    close: () => action({ action: 'workspace-chat' }),
  },
);
$('#workspace-manage').classList.add('workspace-manage');
$('#controls-button').addEventListener('click', () => {
  if (!state.connected) {
    error('Connect to manage work.');
    return;
  }
  action({ action: 'workspace-chat', manage: true });
});

function renderBreadcrumb() {
  const trajectory = workspaceRoute.kind === 'trajectory';
  $('#scope-caption').textContent = personalVisible
    ? 'Personal'
    : scopeName(
        workspaceRoute.kind === 'chat'
          ? scope
          : (workspaceRoute.project ?? scope),
      );
  const caption = $<HTMLButtonElement>('#title-caption');
  caption.textContent = personalVisible
    ? personalSectionName(state.personal?.section ?? 'Personal')
    : trajectory
      ? (workspaceRoute.title ?? 'Conversation')
      : workspaceRoute.kind === 'chat'
        ? selected
          ? title(selected)
          : 'New conversation'
        : workspaceRoute.label;
  caption.disabled =
    workspaceRoute.kind !== 'chat' &&
    (!trajectory ||
      !state?.conversations.some(
        (row) => row.id === workspaceRoute.conversation,
      ));
  caption.title = trajectory
    ? 'Return to conversation'
    : caption.disabled
      ? workspaceRoute.label
      : 'Conversation';
  $('#view-divider').hidden = !trajectory;
  $('#view-caption').hidden = !trajectory;
  $('#view-caption').textContent = workspaceRoute.label;
  for (const button of document.querySelectorAll<HTMLButtonElement>(
    '.account-navigation button',
  )) {
    const current =
      button.id ===
      (
        {
          Mailbox: 'inbox-open',
          Runs: 'runs-open',
          Definitions: 'studio-open',
          'Orchestration builder': 'studio-open',
          'Scheduled work': 'schedules-open',
          Memories: 'memories-open',
          Library: 'library-open',
          Usage: 'usage-open',
          Board: 'board-open',
          Swarm: 'swarm-open',
          'Manage work': 'controls-button',
        } as Record<string, string>
      )[workspaceRoute.label];
    if (current) button.setAttribute('aria-current', 'page');
    else button.removeAttribute('aria-current');
  }
}
let layoutFrame = 0,
  layoutStamp = '';
function reportWorkspaceLayout() {
  if (layoutFrame) return;
  layoutFrame = requestAnimationFrame(() => {
    layoutFrame = 0;
    const bounds = $('#workspace-view-host').getBoundingClientRect();
    const layout = {
      action: 'workspace-layout' as const,
      x: bounds.x,
      y: bounds.y,
      width: bounds.width,
      height: bounds.height,
      visible:
        !['chat', 'manage'].includes(workspaceRoute.kind) &&
        !document.querySelector('dialog:modal'),
    };
    const stamp = JSON.stringify(layout);
    if (stamp === layoutStamp) return;
    layoutStamp = stamp;
    void window.plowshare
      .request(layout)
      .catch((reason: unknown) => error(errorMessage(reason)));
  });
}
function showWorkspace(route: WorkspaceRoute) {
  const changed = JSON.stringify(route) !== JSON.stringify(workspaceRoute);
  if (!changed) return;
  workspaceRoute = route;
  if (route.kind !== 'chat') personalVisible = false;
  renderPersonal();
  $('#workspace-view-host').hidden = route.kind === 'chat';
  $('#workspace-manage').hidden = route.kind !== 'manage';
  $('#inspector-toggle').hidden = route.kind !== 'chat';
  if (changed) {
    if (route.kind === 'manage') {
      try {
        operatorControls.open();
      } catch (reason) {
        error(errorMessage(reason));
      }
    } else operatorControls.close();
    if (route.kind === 'chat') {
      signature = '';
      render();
    }
  }
  renderBreadcrumb();
  reportWorkspaceLayout();
}
$('#scope-caption').addEventListener('click', () =>
  background(chooseScope(workspaceRoute.project ?? scope)),
);
$('#title-caption').addEventListener('click', () => {
  if (workspaceRoute.kind === 'trajectory' && workspaceRoute.conversation)
    background(choose(workspaceRoute.conversation));
});
window.plowshare.subscribeWorkspace?.((message) => {
  if (message.route) showWorkspace(message.route);
  if (message.shortcut)
    document.dispatchEvent(
      new KeyboardEvent('keydown', {
        key: message.shortcut,
        ctrlKey: true,
        bubbles: true,
      }),
    );
});
new ResizeObserver(reportWorkspaceLayout).observe($('#workspace-view-host'));
const modalLayout = new MutationObserver(reportWorkspaceLayout);
modalLayout.observe(document.body, {
  subtree: true,
  attributes: true,
  attributeFilter: ['open', 'hidden'],
});
window.addEventListener('resize', reportWorkspaceLayout);

$('#server-setup').addEventListener(
  'click',
  ownedEvent(async () => {
    const button = $<HTMLButtonElement>('#server-setup');
    button.disabled = true;
    const temporaryPassword = $<HTMLInputElement>('#setup-temporary').value;
    const password = $<HTMLInputElement>('#setup-password').value,
      repeated = $<HTMLInputElement>('#setup-repeat').value;
    for (const id of ['setup-temporary', 'setup-password', 'setup-repeat'])
      $<HTMLInputElement>('#' + id).value = '';
    try {
      if (password !== repeated)
        throw new Error('The administrator passwords did not match.');
      await request({
        action: 'server-setup',
        base: $<HTMLInputElement>('#server-url').value,
        temporaryPassword,
        handle: $<HTMLInputElement>('#setup-handle').value,
        password,
      });
      dialog.close();
    } catch (reason) {
      $('#connect-error').textContent =
        reason instanceof Error ? reason.message : errorMessage(reason);
      $('#connect-error').hidden = false;
    } finally {
      button.disabled = false;
    }
  }),
);
const serverPlacement = fileStorePlacement(
  $('#server-project-placement'),
  async () => {
    const base = state.base,
      handle = state.handle;
    const reply = await request({ action: 'server-filestore-list' });
    if (state.base !== base || state.handle !== handle)
      throw new Error('The server connection changed.');
    if (!reply.serverFileStores)
      throw new Error('Server FileStore catalogue was unreadable.');
    return reply.serverFileStores;
  },
  false,
);
const loadServerPlacement = async () => {
  $('#server-project-error').hidden = true;
  try {
    await serverPlacement.load();
  } catch (reason) {
    // Catalogue failures stay visible in the dialog and submission remains blocked.
    $('#server-project-error').textContent = errorMessage(reason);
    $('#server-project-error').hidden = false;
  }
};
serverPlacement.reload.addEventListener(
  'click',
  ownedEvent(loadServerPlacement),
);
let serverProjectConnection = '';
$('#server-project-add').addEventListener('click', () => {
  serverProjectConnection = JSON.stringify([state.base, state.handle]);
  $<HTMLFormElement>('#server-project-form').reset();
  serverPlacement.reset();
  $('#server-project-placement').hidden = true;
  $<HTMLFieldSetElement>('#server-project-placement').disabled = true;
  $('#server-project-legacy').hidden = false;
  $('#server-project-error').hidden = true;
  $<HTMLSelectElement>('#server-project-type').value = 'MANAGED';
  $<HTMLInputElement>('#server-project-writes').value = '.';
  $<HTMLInputElement>('#server-project-workspace').required = false;
  $('#server-project-path-note').textContent =
    'Leave the path blank to provision a server workspace.';
  $<HTMLDialogElement>('#server-project-dialog').showModal();
});
$('#server-project-filestore').addEventListener(
  'change',
  ownedEvent(async () => {
    const aliases = $<HTMLInputElement>('#server-project-filestore').checked;
    $('#server-project-placement').hidden = !aliases;
    $<HTMLFieldSetElement>('#server-project-placement').disabled = !aliases;
    $('#server-project-legacy').hidden = aliases;
    $<HTMLInputElement>('#server-project-workspace').required =
      !aliases &&
      $<HTMLSelectElement>('#server-project-type').value === 'DISJOINT';
    $('#server-project-path-note').textContent = aliases
      ? 'Choose a server FileStore and enter relative paths. Leave writable areas empty for read-only runtime files.'
      : 'Use the existing absolute server source-directory format.';
    if (aliases) await loadServerPlacement();
  }),
);
$('#server-project-type').addEventListener('change', () => {
  if ($<HTMLInputElement>('#server-project-filestore').checked) return;
  const disjoint =
    $<HTMLSelectElement>('#server-project-type').value === 'DISJOINT';
  $<HTMLInputElement>('#server-project-workspace').required =
    disjoint || $<HTMLInputElement>('#server-project-filestore').checked;
  $<HTMLInputElement>('#server-project-writes').value = disjoint ? '' : '.';
  $('#server-project-path-note').textContent = disjoint
    ? 'Choose an existing server checkout managed by your pipeline. Plowshare will not sync it to clients.'
    : 'Leave the path blank to provision a server workspace.';
});
$('#server-project-close').addEventListener('click', () =>
  $<HTMLDialogElement>('#server-project-dialog').close(),
);
$('#server-project-form').addEventListener(
  'submit',
  ownedEvent(async (event: SubmitEvent) => {
    event.preventDefault();
    const form = $<HTMLFormElement>('#server-project-form'),
      button = form.querySelector<HTMLButtonElement>('button[type="submit"]')!;
    button.disabled = true;
    try {
      if (
        !state.connected ||
        serverProjectConnection !== JSON.stringify([state.base, state.handle])
      )
        throw new Error(
          'The server connection changed. Close and reopen this dialog.',
        );
      const root = $<HTMLInputElement>('#server-project-workspace').value;
      const writes = $<HTMLInputElement>('#server-project-writes')
        .value.split(',')
        .map((path) => path.trim())
        .filter(Boolean);
      const placement = $<HTMLInputElement>(
        '#server-project-filestore',
      ).checked;
      const selected = placement ? serverPlacement.read() : undefined;
      await request({
        action: 'server-project-create',
        name: $<HTMLInputElement>('#server-project-name').value,
        type: $<HTMLSelectElement>('#server-project-type').value as
          'MANAGED' | 'DISJOINT',
        ...(selected
          ? {
              applicationRoot: selected.destination,
              writableAreas: selected.writableAreas,
            }
          : { workspace: root, writePaths: writes }),
      });
      $<HTMLDialogElement>('#server-project-dialog').close();
      form.reset();
    } catch (reason) {
      $('#server-project-error').textContent =
        reason instanceof Error ? reason.message : errorMessage(reason);
      $('#server-project-error').hidden = false;
    } finally {
      button.disabled = false;
    }
  }),
);

document.querySelector('#conversations')!.addEventListener('click', (event) => {
  const button = (event.target as Element).closest<HTMLElement>(
    '[data-project-access]',
  );
  if (button?.dataset.projectAccess)
    projectAccess.open(button.dataset.projectAccess);
});
