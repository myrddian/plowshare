import { errorMessage } from 'plowshare-client-ts/binding/values';
import { background } from './events.ts';
import { displayText } from 'plowshare-client-ts/binding/values';
import type { DesktopState, Request, Reply } from '../shared.ts';
import { previewIdentity, syncIdentity } from '../sync-shared.ts';
const esc = (value: unknown) =>
  displayText(value ?? '').replace(
    /[&<>"']/g,
    (c) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[
        c
      ]!,
  );

/** Project folder dialog's sync view. Drafts survive status pushes, keyed to reviewed bytes. */
export function installSync(send: (request: Request) => Promise<Reply>) {
  const host = document.querySelector<HTMLElement>('#project-sync')!;
  let state: DesktopState,
    project = '',
    adding = false,
    key = '',
    confirmed = false;
  const drafts = new Map<string, string>();
  const value = () =>
    state.projectFolders?.find((row) => row.name === project)?.sync;
  const draftKey = () =>
    JSON.stringify([
      state.base,
      state.handle,
      project,
      value()?.preview && previewIdentity(value()!.preview!),
    ]);
  async function action(request: Request) {
    try {
      await send(request);
    } catch (error) {
      const notice = host.querySelector<HTMLElement>('[data-sync-error]');
      if (notice)
        notice.textContent =
          error instanceof Error ? error.message : errorMessage(error);
    }
  }
  host.addEventListener('change', (event) => {
    if ((event.target as HTMLElement).matches('[data-sync-confirm]')) {
      confirmed = (event.target as HTMLInputElement).checked;
      render(state, project, adding);
    }
  });
  host.addEventListener('input', (event) => {
    if ((event.target as HTMLElement).matches('[data-sync-merge]')) {
      drafts.set(draftKey(), (event.target as HTMLTextAreaElement).value);
      const button = host.querySelector<HTMLButtonElement>(
        '[data-sync-how="done"]',
      );
      if (button)
        button.disabled =
          !!value()?.busy ||
          /^(<{7}|={7}|>{7})(?: |$)/m.test(
            (event.target as HTMLTextAreaElement).value,
          );
    }
  });
  host.addEventListener('click', (event) => {
    const button = (event.target as HTMLElement).closest<HTMLButtonElement>(
      'button',
    );
    const current = value();
    if (!button || button.disabled || !current) return;
    if (button.dataset.syncRefresh)
      background(action({ action: 'sync-refresh', project }));
    const kind = button.dataset.syncKind as 'on' | 'off' | 'now' | undefined;
    if (kind)
      background(
        action({
          action: 'sync-change',
          project,
          kind,
          identity: syncIdentity(current),
        }),
      );
    const path = button.dataset.syncPath;
    if (path) background(action({ action: 'sync-inspect', project, path }));
    const how = button.dataset.syncHow as
      'mine' | 'theirs' | 'done' | undefined;
    if (how && current.preview)
      background(
        action({
          action: 'sync-resolve',
          project,
          how,
          identity: previewIdentity(current.preview),
          ...(how === 'done'
            ? { text: drafts.get(draftKey()) ?? current.preview.merged ?? '' }
            : {}),
        }),
      );
  });
  function render(next: DesktopState, scope: string, addingProject: boolean) {
    state = next;
    project = scope;
    adding = addingProject;
    host.hidden = adding || !project || state.mode !== 'live';
    if (host.hidden) return;
    const current = value();
    const newKey = JSON.stringify([
      state.base,
      state.handle,
      project,
      current && syncIdentity(current),
    ]);
    if (newKey !== key) {
      confirmed = false;
      key = newKey;
    }
    if (!current) {
      host.innerHTML =
        '<h3>Project sync</h3><p>Connect this project’s local folder to inspect synchronization. Disconnecting files pauses this computer; it keeps the server copy.</p>';
      return;
    }
    const status = current.status,
      busy = current.busy || !state.connected;
    const disabled = busy || current.loading || !status ? 'disabled' : '';
    const preview = current.preview;
    const textView = (
      title: string,
      part: { text: string | null; missing: boolean; bytes: number },
    ) =>
      `<section><h4>${title}</h4><pre>${esc(part.missing ? 'File deleted' : (part.text ?? `Binary or large file · ${part.bytes.toLocaleString()} bytes. Text preview unavailable.`))}</pre></section>`;
    const merge =
      preview?.merged === undefined
        ? undefined
        : (drafts.get(draftKey()) ?? preview.merged);
    const focused = document.activeElement as HTMLTextAreaElement | null;
    const selection = focused?.matches('[data-sync-merge]')
      ? [focused.selectionStart, focused.selectionEnd]
      : undefined;
    host.innerHTML = `<h3>Project sync</h3><p role="status">${esc(!status ? 'Reading sync status…' : !status.enabled ? 'Files are stored only on this computer.' : `Server copy enabled · ${(status.state ?? 'offline').toLowerCase()} · ${status.openConflicts} open conflicts`)}${current.loading ? ' · Updating…' : ''}</p>
      <p class="dialog-note">A server copy lets agents work while this computer is offline. Hidden files are excluded unless already allowed; reserved Plowshare and Git files remain excluded.${status ? ` File limit: ${status.maxFileBytes.toLocaleString()} bytes. Hidden paths allowed: ${esc(status.syncHidden.join(', ') || 'none')}.` : ''}</p>
      <div class="sync-actions"><button data-sync-refresh="true" class="secondary-button" ${busy ? 'disabled' : ''}>Refresh sync status</button>${status?.enabled ? `<button data-sync-kind="now" class="secondary-button" ${disabled}>Sync now</button>` : `<button data-sync-kind="on" class="primary-button" ${disabled || !status?.eligible ? 'disabled' : ''}>Enable server copy</button>`}</div>
      <p data-sync-error role="alert">${esc(current.error)}</p><p role="status">${esc([current.standing, current.notice].filter(Boolean).join('\n'))}</p>
      ${current.conflicts.length ? `<h4>Open conflicts</h4><ul class="sync-conflicts">${current.conflicts.map((row) => `<li><button class="secondary-button" data-sync-path="${esc(row.path)}" ${busy ? 'disabled' : ''}>Inspect ${esc(row.path)}</button><span>Server version: ${esc(row.theirsAuthor)}${row.runId ? ` · run ${esc(row.runId)}` : ''}</span></li>`).join('')}</ul>` : ''}
      ${
        preview
          ? `<section class="sync-review"><h4>${esc(preview.row.path)}</h4><p>Your working file is preserved until you choose a version.</p><div class="sync-versions">${textView('My version', preview.mine)}${textView('Server version', preview.server)}${textView('Common base', preview.base)}</div>
        <div class="sync-actions"><button class="secondary-button" data-sync-how="mine" ${busy ? 'disabled' : ''}>Keep my version</button><button class="secondary-button" data-sync-how="theirs" ${busy ? 'disabled' : ''}>${preview.server.missing ? 'Use server deletion' : 'Use server version'}</button></div>
        ${merge === undefined ? '<p>Text merging is unavailable for binary or large files. Choose a version after reviewing it in your normal file tools.</p>' : `<label>Merged version<textarea data-sync-merge rows="10" spellcheck="false">${esc(merge)}</textarea></label><p>Remove merge markers and review the complete text. This replaces the local file and closes the conflict.</p><button class="primary-button" data-sync-how="done" ${busy || /^(<{7}|={7}|>{7})(?: |$)/m.test(merge) ? 'disabled' : ''}>Use merged version</button>`}</section>`
          : ''
      }
      ${status?.enabled ? `<section class="sync-disable"><label><input type="checkbox" data-sync-confirm ${confirmed ? 'checked' : ''}>Remove the server copy and sync history. Keep this computer’s files.</label><p>Synchronize and resolve all conflicts first. Disconnect files to pause this computer without removing the server copy.</p><button class="secondary-button" data-sync-kind="off" ${disabled || !confirmed || current.conflicts.length || status.state !== 'LIVE' ? 'disabled' : ''}>Disable sync and remove server copy</button></section>` : ''}`;
    if (selection) {
      const editor =
        host.querySelector<HTMLTextAreaElement>('[data-sync-merge]');
      editor?.focus();
      editor?.setSelectionRange(selection[0]!, selection[1]!);
    }
  }
  return render;
}
