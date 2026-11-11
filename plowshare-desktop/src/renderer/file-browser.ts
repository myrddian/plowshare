/**
 * Reusable Desktop file browser: expandable tree beside a plain-text code
 * editor (textarea with a synchronized line-number gutter).
 * Hosts supply a {@link FileBrowserPort}; Application/Project chrome stays outside.
 */

import { icon } from './icons.ts';
import { ownedEvent } from './events.ts';

export interface FileBrowserEntry {
  readonly name: string;
  readonly path: string;
  readonly directory: boolean;
}

export interface FileBrowserListing {
  readonly path: string;
  readonly entries: readonly FileBrowserEntry[];
  readonly more: boolean;
}

export interface FileBrowserDocument {
  readonly path: string;
  readonly text: string;
  readonly revision: string;
  readonly writable: boolean;
}

/** Narrow filesystem adapter. Hosts own auth, roots, and wire actions. */
export interface FileBrowserPort {
  list(path: string): Promise<FileBrowserListing>;
  read(path: string): Promise<FileBrowserDocument>;
  save?(
    path: string,
    text: string,
    revision: string,
  ): Promise<FileBrowserDocument>;
}

export type FileBrowserOptions = {
  /** Extra dataset key on row buttons (e.g. personalPath for smokes). */
  pathDataset?: string;
};

type Draft = { text: string; revision: string; original: string };

/** Per-instance browser state. Two sessions never share listings or drafts. */
export class FileBrowserSession {
  readonly expanded = new Set<string>();
  private listings = new Map<string, FileBrowserListing>();
  private drafts = new Map<string, Draft>();
  private current: FileBrowserDocument | undefined;
  private draft: Draft | undefined;
  private scope = '';
  busy = false;
  error = '';
  status = '';
  private port: FileBrowserPort;

  constructor(port: FileBrowserPort) {
    this.port = port;
  }

  /** Opaque root/location key so drafts survive area switches without colliding. */
  setScope(scope: string) {
    if (this.scope === scope) return;
    this.scope = scope;
    this.expanded.clear();
    this.listings.clear();
    this.current = undefined;
    this.draft = undefined;
    this.busy = false;
    this.error = '';
    this.status = '';
  }

  private draftKey(path: string) {
    return `${this.scope}\0${path}`;
  }

  get document() {
    return this.current;
  }

  get draftText() {
    return this.draft?.text;
  }

  get dirty() {
    return (
      !!this.current && !!this.draft && this.draft.text !== this.current.text
    );
  }

  get revisionConflict() {
    return (
      !!this.current &&
      !!this.draft &&
      this.draft.revision !== this.current.revision
    );
  }

  /**
   * True when a save would be attempted and accepted locally: the port can
   * write, the file is writable, the draft differs and is based on the current
   * revision, and no other operation is in flight. The Save button and the
   * keyboard shortcut share this gate; {@link save} still enforces it.
   */
  get savable() {
    return (
      !!this.port.save &&
      !!this.current?.writable &&
      !this.busy &&
      !this.revisionConflict &&
      this.dirty
    );
  }

  listing(path: string) {
    return this.listings.get(path);
  }

  reset() {
    this.scope = '';
    this.expanded.clear();
    this.listings.clear();
    this.drafts.clear();
    this.current = undefined;
    this.draft = undefined;
    this.busy = false;
    this.error = '';
    this.status = '';
  }

  /** Drop tree cache and open document; drafts for the current scope stay. */
  clearTree() {
    this.expanded.clear();
    this.listings.clear();
    this.current = undefined;
    this.draft = undefined;
    this.busy = false;
    this.error = '';
    this.status = '';
  }

  collapse(path: string) {
    this.expanded.delete(path);
    this.listings.delete(path);
    for (const key of [...this.expanded]) {
      if (key === path || key.startsWith(path ? `${path}/` : '')) {
        this.expanded.delete(key);
        this.listings.delete(key);
      }
    }
  }

  async expand(path: string) {
    this.busy = true;
    this.error = '';
    this.status = 'Reading…';
    try {
      const value = await this.port.list(path);
      this.listings.set(path, value);
      this.expanded.add(path);
      this.status = statusFor(value);
    } catch (reason) {
      this.error = reason instanceof Error ? reason.message : String(reason);
      this.status = '';
      throw reason;
    } finally {
      this.busy = false;
    }
  }

  async open(path: string) {
    this.busy = true;
    this.error = '';
    this.status = 'Reading file…';
    try {
      const value = await this.port.read(path);
      this.applyDocument(value);
      this.status = value.path;
    } catch (reason) {
      this.error = reason instanceof Error ? reason.message : String(reason);
      this.status = '';
      throw reason;
    } finally {
      this.busy = false;
    }
  }

  applyDocument(value: FileBrowserDocument) {
    this.current = value;
    const key = this.draftKey(value.path);
    const prior = this.drafts.get(key);
    if (!prior || prior.text === prior.original || prior.text === value.text) {
      this.draft = {
        text: value.text,
        original: value.text,
        revision: value.revision,
      };
      this.drafts.set(key, this.draft);
    } else {
      this.draft = prior;
    }
  }

  setDraft(text: string) {
    if (!this.current || !this.draft) return;
    this.draft.text = text;
    this.drafts.set(this.draftKey(this.current.path), this.draft);
  }

  discardDraft() {
    if (!this.current) return;
    this.draft = {
      text: this.current.text,
      original: this.current.text,
      revision: this.current.revision,
    };
    this.drafts.set(this.draftKey(this.current.path), this.draft);
  }

  async save() {
    if (!this.port.save) throw new Error('This browser cannot save files.');
    if (!this.current || !this.draft) return;
    if (!this.current.writable)
      throw new Error('Write access is unavailable for this file.');
    if (this.revisionConflict)
      throw new Error(
        'The server file changed. Read and review the current file before saving.',
      );
    this.busy = true;
    this.error = '';
    this.status = 'Saving…';
    try {
      const value = await this.port.save(
        this.current.path,
        this.draft.text,
        this.draft.revision,
      );
      this.drafts.delete(this.draftKey(this.current.path));
      this.applyDocument(value);
      this.status = value.path;
    } catch (reason) {
      this.error = reason instanceof Error ? reason.message : String(reason);
      throw reason;
    } finally {
      this.busy = false;
    }
  }

  async jump(path: string) {
    const trimmed = path.trim().replace(/^\/+/, '').replace(/\/+$/, '');
    if (!trimmed) {
      await this.expand('');
      return;
    }
    try {
      await this.open(trimmed);
    } catch {
      await this.expand(trimmed);
    }
  }
}

function statusFor(value: FileBrowserListing) {
  const where = value.path || '/';
  if (value.more)
    return `${where} · First ${value.entries.length} entries; use a relative path for other files.`;
  if (!value.entries.length) return `${where} · This directory is empty.`;
  return where;
}

export interface FileBrowserHandle {
  readonly session: FileBrowserSession;
  readonly root: HTMLElement;
  render(): void;
  reset(): void;
  openRoot(): Promise<void>;
  focusTree(): void;
}

/**
 * Mount tree + editor into `host`. Call {@link FileBrowserHandle.render} after
 * session mutations that the host performs itself.
 */
export function installFileBrowser(
  host: HTMLElement,
  port: FileBrowserPort,
  options: FileBrowserOptions = {},
): FileBrowserHandle {
  const session = new FileBrowserSession(port);
  const pathDataset = options.pathDataset;
  const canSave = typeof port.save === 'function';
  host.classList.add('file-browser');
  // One frame: tree pane (jump field above it), editor pane, and a slim
  // footer that owns listing status, errors and the caret position.
  host.innerHTML = `<div class="file-browser-split">
      <div class="file-browser-side">
        <form class="file-browser-jump" data-jump>
          <input data-path autocomplete="off" spellcheck="false" placeholder="Go to path…" aria-label="Relative path">
          <button type="submit" data-jump-go>Go</button>
        </form>
        <div class="file-browser-tree" data-tree role="tree" aria-label="Files"></div>
      </div>
      <section class="file-browser-editor" data-editor>
        <div class="file-browser-empty-editor" data-editor-empty>
          <span data-icon-slot>${icon('file')}</span>
          <p>Select a file to open</p>
        </div>
        <div class="file-browser-document" data-editor-body hidden>
          <header class="file-browser-bar">
            <h3 class="file-browser-title" data-file-title></h3>
            <span class="file-browser-dirty" data-dirty role="img" aria-label="Unsaved changes" title="Unsaved changes" hidden></span>
            <span class="file-browser-badge" data-permission></span>
            <div class="file-browser-actions">
              <button type="button" data-read-current title="Read the current file from the server">Reload</button>
              <button type="button" class="is-primary" data-save aria-keyshortcuts="Meta+S Control+S" title="Save file (Cmd/Ctrl+S)" ${canSave ? '' : 'hidden'}>Save</button>
            </div>
          </header>
          <div class="file-browser-conflict" data-conflict hidden>
            <p>The server file changed. Your draft is retained; read and review the current file before saving.</p>
            <button type="button" data-use-current hidden>Use current file and discard draft</button>
          </div>
          <div class="file-browser-code">
            <div class="file-browser-gutter" data-gutter aria-hidden="true"></div>
            <textarea data-text wrap="off" spellcheck="false" autocapitalize="off" autocomplete="off" aria-label="File content"></textarea>
          </div>
        </div>
      </section>
    </div>
    <footer class="file-browser-foot">
      <p data-status role="status"></p>
      <p data-error role="alert" hidden></p>
      <span class="file-browser-position" data-position></span>
    </footer>`;

  const get = <T extends HTMLElement = HTMLElement>(selector: string) =>
    host.querySelector<T>(selector)!;
  const text = get<HTMLTextAreaElement>('[data-text]');
  const gutter = get('[data-gutter]');
  let painted = '';
  let paintedPath = '';
  let gutterLines = 0;

  const run = (work: () => Promise<unknown> | void) =>
    ownedEvent(async () => {
      try {
        await work();
      } finally {
        render();
      }
    });

  get<HTMLFormElement>('[data-jump]').addEventListener('submit', (event) => {
    event.preventDefault();
    run(() => session.jump(get<HTMLInputElement>('[data-path]').value))();
  });

  get('[data-read-current]').addEventListener(
    'click',
    run(async () => {
      if (!session.document) return;
      await session.open(session.document.path);
    }),
  );
  get('[data-use-current]').addEventListener('click', () => {
    session.discardDraft();
    render();
  });
  // Typing only changes the draft, so repaint the editor chrome and leave the
  // tree (and its focus) alone.
  text.addEventListener('input', () => {
    session.setDraft(text.value);
    renderEditor();
  });
  // The gutter never scrolls on its own; it follows the text vertically.
  text.addEventListener('scroll', () => {
    gutter.scrollTop = text.scrollTop;
  });
  for (const type of ['keyup', 'mouseup', 'focus', 'select'])
    text.addEventListener(type, renderPosition);
  get('[data-save]').addEventListener(
    'click',
    run(() => session.save()),
  );
  // Cmd/Ctrl+S anywhere inside the browser. The shortcut is always consumed so
  // the page never sees it, but it only saves what the Save button would.
  host.addEventListener('keydown', (event) => {
    if (!isSaveShortcut(event)) return;
    event.preventDefault();
    if (session.savable) run(() => session.save())();
  });

  function markPath(button: HTMLElement, path: string) {
    button.dataset.applicationPath = path;
    button.dataset.personalPath = path;
    if (pathDataset) button.dataset[pathDataset] = path;
  }

  /** Rebuilding the tree drops focus; remember which row control held it. */
  function focusedTreeControl(tree: HTMLElement) {
    const active = document.activeElement;
    if (!(active instanceof HTMLElement) || !tree.contains(active)) return;
    const path = active.closest<HTMLElement>('[data-path]')?.dataset.path;
    if (path === undefined) return;
    return {
      path,
      toggle: active.classList.contains('file-browser-toggle'),
    };
  }

  function renderTree() {
    const tree = get('[data-tree]');
    const focused = focusedTreeControl(tree);
    tree.replaceChildren();
    const toggleDirectory = (path: string) =>
      run(async () => {
        if (session.expanded.has(path)) session.collapse(path);
        else await session.expand(path);
      });
    const walk = (path: string, depth: number) => {
      const listing = session.listing(path);
      if (!listing) return;
      for (const entry of listing.entries) {
        const row = document.createElement('div');
        row.className = 'file-browser-row';
        row.style.setProperty('--depth', String(depth));
        row.setAttribute('role', 'treeitem');
        row.dataset.path = entry.path;
        const label = document.createElement('button');
        label.type = 'button';
        label.className = 'file-browser-name';
        label.title = entry.path;
        markPath(label, entry.path);
        if (entry.directory) {
          const open = session.expanded.has(entry.path);
          row.setAttribute('aria-expanded', open ? 'true' : 'false');
          const toggle = document.createElement('button');
          toggle.type = 'button';
          toggle.className = `file-browser-toggle${open ? ' is-open' : ''}`;
          toggle.innerHTML = icon('chevron');
          toggle.setAttribute(
            'aria-label',
            open ? `Collapse ${entry.name}` : `Expand ${entry.name}`,
          );
          toggle.addEventListener('click', toggleDirectory(entry.path));
          label.innerHTML = `${icon('folder')}<span>${escapeText(entry.name)}</span>`;
          label.addEventListener('click', toggleDirectory(entry.path));
          row.append(toggle, label);
          tree.append(row);
          if (open) walk(entry.path, depth + 1);
        } else {
          row.classList.add('is-file');
          label.innerHTML = `${icon('file')}<span>${escapeText(entry.name)}</span>`;
          if (session.document?.path === entry.path)
            label.setAttribute('aria-current', 'true');
          label.addEventListener(
            'click',
            run(() => session.open(entry.path)),
          );
          row.append(label);
          tree.append(row);
        }
      }
      if (listing.more) {
        const more = document.createElement('p');
        more.className = 'file-browser-more';
        more.style.setProperty('--depth', String(depth));
        more.textContent = 'More entries not shown';
        tree.append(more);
      }
      if (!listing.entries.length && path === '') {
        const empty = document.createElement('p');
        empty.className = 'file-browser-empty';
        empty.textContent = 'No files in this location.';
        tree.append(empty);
      }
    };
    if (session.expanded.has('') || session.listing('')) walk('', 0);
    else {
      const hint = document.createElement('p');
      hint.className = 'file-browser-empty';
      hint.textContent = session.busy
        ? 'Reading…'
        : 'Open this location to browse files.';
      tree.append(hint);
    }
    if (!focused) return;
    for (const row of tree.querySelectorAll<HTMLElement>('[data-path]')) {
      if (row.dataset.path !== focused.path) continue;
      const control =
        (focused.toggle
          ? row.querySelector<HTMLElement>('.file-browser-toggle')
          : null) ?? row.querySelector<HTMLElement>('.file-browser-name');
      control?.focus();
      return;
    }
  }

  /** Path with the directory part truncated first so the file name survives. */
  function renderTitle(path: string) {
    const title = get('[data-file-title]');
    if (title.title === path) return;
    const slash = path.lastIndexOf('/') + 1;
    const directory = document.createElement('span');
    directory.className = 'file-browser-title-directory';
    directory.textContent = path.slice(0, slash);
    const name = document.createElement('span');
    name.className = 'file-browser-title-name';
    name.textContent = path.slice(slash);
    title.title = path;
    title.replaceChildren(directory, name);
  }

  function renderGutter(value: string) {
    const lines = lineCount(value);
    if (lines === gutterLines) return;
    gutterLines = lines;
    gutter.textContent = lineNumbers(lines);
    gutter.style.setProperty(
      '--digits',
      String(Math.max(2, String(lines).length)),
    );
    gutter.scrollTop = text.scrollTop;
  }

  function renderPosition() {
    const position = get('[data-position]');
    if (!session.document || session.draftText === undefined) {
      position.textContent = '';
      return;
    }
    const caret = cursorPosition(text.value, text.selectionStart);
    position.textContent = `Ln ${caret.line}, Col ${caret.column} · ${gutterLines} ${gutterLines === 1 ? 'line' : 'lines'}`;
  }

  /** Status, errors and caret. The open file's path is already the editor
   * title, so a status that only repeats it is not shown a second time. */
  function renderFooter() {
    const error = get('[data-error]');
    error.hidden = !session.error;
    error.textContent = session.error;
    const status = get('[data-status]');
    status.textContent =
      session.status === session.document?.path ? '' : session.status;
    status.hidden = !!session.error;
    renderPosition();
  }

  /** Editor pane only: safe to call on every keystroke. */
  function renderEditor() {
    const source = session.document;
    get('[data-editor-empty]').hidden = !!source;
    get('[data-editor-body]').hidden = !source;
    if (!source || session.draftText === undefined) {
      painted = '';
      paintedPath = '';
      renderPosition();
      return;
    }
    const key = `${source.path}\0${source.revision}`;
    if (painted !== key || text.value !== session.draftText)
      text.value = session.draftText;
    painted = key;
    renderGutter(text.value);
    if (paintedPath !== source.path) {
      // A different file starts at its top-left corner.
      paintedPath = source.path;
      text.scrollTop = 0;
      text.scrollLeft = 0;
      gutter.scrollTop = 0;
    }
    text.readOnly = !source.writable || session.busy || !canSave;
    renderTitle(source.path);
    get('[data-dirty]').hidden = !session.dirty;
    const permission = get('[data-permission]');
    const conflict = session.revisionConflict;
    const writable = source.writable && canSave;
    permission.textContent = conflict
      ? 'Changed on server'
      : writable
        ? 'Writable'
        : 'Read only';
    permission.title = conflict
      ? 'The server file changed. Your draft is retained; read and review the current file before saving.'
      : writable
        ? 'Write allowed for this file.'
        : 'Read only for your account or this workspace path.';
    permission.dataset.state = conflict
      ? 'conflict'
      : writable
        ? 'writable'
        : 'read-only';
    get('[data-conflict]').hidden = !canSave || !conflict;
    get('[data-read-current]').hidden = !canSave;
    get('[data-use-current]').hidden = !canSave || !conflict;
    get('[data-save]').hidden = !canSave;
    get<HTMLButtonElement>('[data-save]').disabled = !session.savable;
    renderPosition();
  }

  function render() {
    for (const button of host.querySelectorAll('button'))
      button.disabled = session.busy && !button.matches('[data-use-current]');
    renderTree();
    renderEditor();
    renderFooter();
  }

  return {
    session,
    root: host,
    render,
    reset() {
      session.reset();
      get<HTMLInputElement>('[data-path]').value = '';
      painted = '';
      paintedPath = '';
      render();
    },
    async openRoot() {
      session.clearTree();
      await session.expand('');
      render();
    },
    focusTree() {
      get('[data-tree]')
        .querySelector<HTMLElement>('button.file-browser-name')
        ?.focus();
    },
  };
}

/** Number of editor lines; a trailing newline starts a final empty line. */
export function lineCount(text: string) {
  let lines = 1;
  for (let at = text.indexOf('\n'); at >= 0; at = text.indexOf('\n', at + 1))
    lines += 1;
  return lines;
}

/** Gutter text: one right-aligned number per editor line. */
export function lineNumbers(lines: number) {
  let numbers = '';
  for (let line = 1; line <= lines; line += 1)
    numbers += line === 1 ? '1' : `\n${line}`;
  return numbers;
}

/** One-based line and column of a caret offset, clamped to the text. */
export function cursorPosition(text: string, offset: number) {
  const end = Math.max(0, Math.min(offset, text.length));
  let line = 1;
  let lineStart = 0;
  for (
    let at = text.indexOf('\n');
    at >= 0 && at < end;
    at = text.indexOf('\n', at + 1)
  ) {
    line += 1;
    lineStart = at + 1;
  }
  return { line, column: end - lineStart + 1 };
}

/** Cmd+S or Ctrl+S with no other modifier. */
export function isSaveShortcut(event: {
  readonly key: string;
  readonly metaKey: boolean;
  readonly ctrlKey: boolean;
  readonly altKey: boolean;
  readonly shiftKey: boolean;
}) {
  return (
    event.key.toLowerCase() === 's' &&
    event.metaKey !== event.ctrlKey &&
    !event.altKey &&
    !event.shiftKey
  );
}

function escapeText(value: string) {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
}
