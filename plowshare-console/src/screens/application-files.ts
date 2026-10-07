import { outcomeIn } from '../../../sdk/typescript/src/binding/envelope.ts';
import type { EventStream } from '../events';
import {
  request,
  resultOf,
  type Request,
} from '../../../sdk/typescript/src/operations/direct.ts';
import { decodeReply } from '../../../sdk/typescript/src/operations/schema.ts';
import type { ApplicationFileDocument } from '../../../sdk/typescript/src/operations/conversation-replies.ts';
import { background } from '../background.ts';
import { button, el, input, labelled, problemText } from './dom';

/** Browses deployed source over the authenticated socket, without a local checkout or HTTP replay. */
export function applicationFiles(
  project: string,
  stream: () => EventStream | null,
): HTMLElement {
  const section = el('section', 'application-files');
  const toggle = button('files', 'Files');
  const content = el('div', 'application-source');
  content.hidden = true;
  const path = input('path', 'relative folder or file');
  const browse = button('browse', 'Browse folder'),
    open = button('read', 'Read file');
  const status = el('p', 'application-status'),
    error = el('p', 'trouble');
  error.setAttribute('role', 'alert');
  const entries = el('div', 'application-entries'),
    editor = el('section', 'application-editor');
  editor.hidden = true;
  const title = el('h4', 'application-file-title'),
    permission = el('p', 'application-permission');
  const text = document.createElement('textarea');
  text.setAttribute('aria-label', 'Application file content');
  text.spellcheck = false;
  const save = button('save', 'Save file'),
    readCurrent = button('refresh-file', 'Read current file'),
    discard = button('discard', 'Use current file and discard draft');
  discard.hidden = true;
  editor.append(title, permission, text, readCurrent, discard, save);
  content.append(
    labelled('Relative path', path),
    browse,
    open,
    status,
    error,
    entries,
    editor,
  );
  section.append(toggle, content);
  let source: ApplicationFileDocument | undefined,
    busy = false,
    uncertain = false;
  const drafts = new Map<
    string,
    { text: string; revision: string; original: string }
  >();
  function render() {
    for (const control of [browse, open, readCurrent, toggle, discard])
      control.disabled = busy;
    text.readOnly = busy || !source?.writable;
    const draft = source ? drafts.get(source.path) : undefined;
    save.disabled =
      busy ||
      uncertain ||
      !source?.writable ||
      !draft ||
      draft.revision !== source.revision ||
      draft.text === source.text;
    discard.hidden = !source || !draft || draft.revision === source.revision;
    permission.textContent =
      draft && source && draft.revision !== source.revision
        ? 'The server file changed. Your draft is retained; review the current file before saving.'
        : source?.writable
          ? 'Write allowed for this file.'
          : 'Read only for your account or this workspace path.';
  }
  async function ask(value: Request) {
    const active = stream();
    if (!active || active.status().state !== 'open')
      throw new Error(
        'Connect to the server before opening Application files.',
      );
    const answer = await active.ask(value.type, value.payload);
    const result = resultOf(value, outcomeIn(answer));
    if (result.kind !== 'completed')
      throw new Error(
        answer.said ?? 'The Application file reply could not be verified.',
      );
    return answer.payload;
  }
  async function run(work: () => Promise<void>) {
    if (busy) return;
    busy = true;
    error.textContent = '';
    status.textContent = 'Waiting for server…';
    render();
    try {
      await work();
    } catch (reason) {
      error.textContent = problemText(
        reason,
        'Application files are unavailable.',
      );
    } finally {
      busy = false;
      status.textContent = '';
      render();
    }
  }
  async function list(relative: string) {
    const value = decodeReply(
      'application.files',
      await ask(request('application.files', { project, path: relative })),
    );
    if (!section.isConnected) return;
    source = undefined;
    editor.hidden = true;
    path.value = value.path;
    entries.replaceChildren();
    if (value.path) {
      const up = button('parent', 'Parent folder');
      up.addEventListener('click', () =>
        background(
          run(() =>
            list(
              value.path.includes('/')
                ? value.path.slice(0, value.path.lastIndexOf('/'))
                : '',
            ),
          ),
        ),
      );
      entries.append(up);
    }
    for (const entry of value.entries) {
      const choice = button(
        'entry',
        `${entry.directory ? 'Folder · ' : ''}${entry.name}`,
      );
      choice.dataset['applicationPath'] = entry.path;
      choice.addEventListener('click', () =>
        background(
          run(() => (entry.directory ? list(entry.path) : read(entry.path))),
        ),
      );
      entries.append(choice);
    }
    if (value.more)
      entries.append(
        el(
          'p',
          'nothing',
          'First 200 entries. Use a relative path to open other files.',
        ),
      );
    else if (!value.entries.length)
      entries.append(el('p', 'nothing', 'This directory is empty.'));
  }
  async function read(relative: string) {
    const value = decodeReply(
      'application.file.read',
      await ask(request('application.file.read', { project, path: relative })),
    );
    if (!section.isConnected) return;
    source = value;
    uncertain = false;
    path.value = value.path;
    editor.hidden = false;
    title.textContent = value.path;
    let draft = drafts.get(value.path);
    if (!draft || draft.text === draft.original || draft.text === value.text) {
      draft = {
        text: value.text,
        original: value.text,
        revision: value.revision,
      };
      drafts.set(value.path, draft);
    }
    text.value = draft.text;
  }
  toggle.addEventListener('click', () => {
    content.hidden = !content.hidden;
    if (!content.hidden) background(run(() => list('')));
  });
  browse.addEventListener('click', () =>
    background(run(() => list(path.value))),
  );
  open.addEventListener('click', () => background(run(() => read(path.value))));
  readCurrent.addEventListener('click', () => {
    if (source) background(run(() => read(source!.path)));
  });
  discard.addEventListener('click', () => {
    if (source) {
      drafts.set(source.path, {
        text: source.text,
        original: source.text,
        revision: source.revision,
      });
      text.value = source.text;
      render();
    }
  });
  text.addEventListener('input', () => {
    if (source) {
      const draft = drafts.get(source.path);
      if (draft) draft.text = text.value;
      render();
    }
  });
  save.addEventListener('click', () =>
    background(
      run(async () => {
        if (!source || !source.writable || uncertain) return;
        const draft = drafts.get(source.path);
        if (!draft || draft.revision !== source.revision) return;
        uncertain = true;
        try {
          const saved = decodeReply(
            'application.file.save',
            await ask(
              request('application.file.save', {
                project,
                path: source.path,
                text: draft.text,
                revision: draft.revision,
              }),
            ),
          );
          source = saved;
          uncertain = false;
          drafts.set(saved.path, {
            text: saved.text,
            original: saved.text,
            revision: saved.revision,
          });
        } catch (reason) {
          throw new Error(
            `${problemText(reason, 'The save failed.')} Read the file before saving again; the edit will not be replayed.`,
            { cause: reason },
          );
        }
      }),
    ),
  );
  render();
  return section;
}
