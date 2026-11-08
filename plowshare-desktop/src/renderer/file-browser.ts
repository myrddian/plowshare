/**
 * Reusable Desktop file browser: expandable tree beside a file editor.
 * Hosts supply a {@link FileBrowserPort}; Application/Project chrome stays outside.
 */

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
    return !!this.current && !!this.draft && this.draft.text !== this.current.text;
  }

  get revisionConflict() {
    return (
      !!this.current &&
      !!this.draft &&
      this.draft.revision !== this.current.revision
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
    if (
      !prior ||
      prior.text === prior.original ||
      prior.text === value.text
    ) {
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
): FileBrowserHandle {
  const session = new FileBrowserSession(port);
  host.classList.add('file-browser');
  host.innerHTML = `<div class="file-browser-toolbar"><form data-jump><label>Jump to relative path<input data-path autocomplete="off" placeholder="Folder or file"></label><button type="submit" data-jump-go>Go</button></form>
    <p data-status role="status"></p><p data-error role="alert" hidden></p></div>
    <div class="file-browser-split">
      <div class="file-browser-tree" data-tree role="tree" aria-label="Files"></div>
      <section class="file-browser-editor" data-editor hidden>
        <h3 data-file-title></h3>
        <p data-permission></p>
        <label>File content<textarea data-text spellcheck="false" aria-label="File content"></textarea></label>
        <div class="file-browser-actions">
          <button type="button" data-read-current>Read current file</button>
          <button type="button" data-use-current hidden>Use current file and discard draft</button>
          <button type="button" data-save>Save file</button>
        </div>
      </section>
    </div>`;

  const get = <T extends HTMLElement = HTMLElement>(selector: string) =>
    host.querySelector<T>(selector)!;
  let painted = '';

  const run = (work: () => Promise<unknown> | void) => async () => {
    try {
      await work();
    } catch {
      // session.error already set
    } finally {
      render();
    }
  };

  get<HTMLFormElement>('[data-jump]').addEventListener('submit', (event) => {
    event.preventDefault();
    void run(() => session.jump(get<HTMLInputElement>('[data-path]').value))();
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
  get<HTMLTextAreaElement>('[data-text]').addEventListener('input', () => {
    session.setDraft(get<HTMLTextAreaElement>('[data-text]').value);
    render();
  });
  get('[data-save]').addEventListener(
    'click',
    run(() => session.save()),
  );

  function renderTree() {
    const tree = get('[data-tree]');
    tree.replaceChildren();
    const walk = (path: string, depth: number) => {
      const listing = session.listing(path);
      if (!listing) return;
      for (const entry of listing.entries) {
        const row = document.createElement('div');
        row.className = 'file-browser-row';
        row.style.setProperty('--depth', String(depth));
        row.setAttribute('role', 'treeitem');
        row.dataset.path = entry.path;
        if (entry.directory) {
          const open = session.expanded.has(entry.path);
          row.setAttribute('aria-expanded', open ? 'true' : 'false');
          const toggle = document.createElement('button');
          toggle.type = 'button';
          toggle.className = 'file-browser-toggle';
          toggle.textContent = open ? '▾' : '▸';
          toggle.setAttribute(
            'aria-label',
            open ? `Collapse ${entry.name}` : `Expand ${entry.name}`,
          );
          toggle.addEventListener(
            'click',
            run(async () => {
              if (session.expanded.has(entry.path)) session.collapse(entry.path);
              else await session.expand(entry.path);
            }),
          );
          const label = document.createElement('button');
          label.type = 'button';
          label.className = 'file-browser-name';
          label.textContent = entry.name;
          label.dataset.applicationPath = entry.path;
          label.addEventListener(
            'click',
            run(async () => {
              if (session.expanded.has(entry.path)) session.collapse(entry.path);
              else await session.expand(entry.path);
            }),
          );
          row.append(toggle, label);
          if (listing.more && entry === listing.entries.at(-1)) {
            const more = document.createElement('span');
            more.className = 'file-browser-more';
            more.textContent = 'More entries not shown';
            row.append(more);
          }
          tree.append(row);
          if (open) walk(entry.path, depth + 1);
        } else {
          const label = document.createElement('button');
          label.type = 'button';
          label.className = 'file-browser-name';
          label.textContent = entry.name;
          label.dataset.applicationPath = entry.path;
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
  }

  function render() {
    get('[data-error]').hidden = !session.error;
    get('[data-error]').textContent = session.error;
    get('[data-status]').textContent = session.status;
    for (const button of host.querySelectorAll('button'))
      button.disabled = session.busy && !button.matches('[data-use-current]');
    renderTree();
    const source = session.document;
    const editor = get('[data-editor]');
    editor.hidden = !source;
    if (!source || session.draftText === undefined) {
      painted = '';
      return;
    }
    const text = get<HTMLTextAreaElement>('[data-text]');
    const key = `${source.path}\0${source.revision}`;
    if (painted !== key || text.value !== session.draftText)
      text.value = session.draftText;
    painted = key;
    text.readOnly = !source.writable || session.busy;
    get('[data-file-title]').textContent = source.path;
    get('[data-permission]').textContent = session.revisionConflict
      ? 'The server file changed. Your draft is retained; read and review the current file before saving.'
      : source.writable
        ? 'Write allowed for this file.'
        : 'Read only for your account or this workspace path.';
    get<HTMLButtonElement>('[data-save]').disabled =
      !port.save ||
      !source.writable ||
      session.busy ||
      session.revisionConflict ||
      !session.dirty;
    get('[data-use-current]').hidden = !session.revisionConflict;
  }

  return {
    session,
    root: host,
    render,
    reset() {
      session.reset();
      get<HTMLInputElement>('[data-path]').value = '';
      painted = '';
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
