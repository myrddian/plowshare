import { EventEmitter } from 'node:events';
import { WebContentsView, type BrowserWindow, type Rectangle } from 'electron';
import type { WorkspaceRoute } from './shared.ts';

/** A sandboxed inspection page presented inside the existing workspace window. */
export class WorkspacePage extends EventEmitter {
  readonly view: WebContentsView;
  readonly webContents;
  private bounds: Rectangle | undefined;
  private visible = false;
  constructor(
    owner: BrowserWindow,
    preload: string,
    readonly route: () => WorkspaceRoute,
    private readonly activate: (page: WorkspacePage) => void,
    private readonly closed: (page: WorkspacePage) => void,
  ) {
    super();
    this.view = new WebContentsView({
      webPreferences: {
        preload,
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true,
        webSecurity: true,
      },
    });
    this.webContents = this.view.webContents;
    this.view.setVisible(false);
    this.view.setBackgroundColor('#171717');
    owner.contentView.addChildView(this.view);
    this.webContents.once('destroyed', () => {
      if (!owner.isDestroyed()) owner.contentView.removeChildView(this.view);
      this.closed(this);
      this.emit('closed');
    });
    this.webContents.on('before-input-event', (event, input) => {
      if (
        input.type !== 'keyDown' ||
        !(input.meta || input.control) ||
        input.alt ||
        !['k', 'b', 'n'].includes(input.key.toLowerCase())
      )
        return;
      event.preventDefault();
      owner.webContents.focus();
      owner.webContents.send('plowshare:workspace', {
        shortcut: input.key.toLowerCase(),
      });
    });
  }
  isDestroyed() {
    return this.webContents.isDestroyed();
  }
  show() {
    if (!this.isDestroyed()) this.activate(this);
  }
  focus() {
    if (!this.isDestroyed()) this.webContents.focus();
  }
  close() {
    if (!this.isDestroyed()) this.webContents.close();
  }
  loadURL(url: string) {
    return this.webContents.loadURL(url);
  }
  layout(bounds: Rectangle, visible: boolean) {
    if (this.isDestroyed()) return;
    if (
      !this.bounds ||
      (['x', 'y', 'width', 'height'] as const).some(
        (key) => this.bounds![key] !== bounds[key],
      )
    ) {
      this.view.setBounds(bounds);
      this.bounds = { ...bounds };
    }
    if (this.visible !== visible) {
      this.view.setVisible(visible);
      this.visible = visible;
    }
  }
}
