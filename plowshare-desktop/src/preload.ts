import { contextBridge, ipcRenderer } from 'electron';
import type {
  DesktopApi,
  DesktopState,
  Request,
  WorkspaceMessage,
} from './shared.ts';
const api: DesktopApi = {
  request: (request: Request) =>
    ipcRenderer.invoke('plowshare:request', request),
  subscribe: (listener: (state: DesktopState) => void) => {
    const receive = (_event: unknown, state: DesktopState) => listener(state);
    ipcRenderer.on('plowshare:state', receive);
    return () => ipcRenderer.removeListener('plowshare:state', receive);
  },
  subscribeWorkspace: (listener) => {
    const receive = (_event: unknown, message: WorkspaceMessage) =>
      listener(message);
    ipcRenderer.on('plowshare:workspace', receive);
    return () => ipcRenderer.removeListener('plowshare:workspace', receive);
  },
};
ipcRenderer.on('plowshare:workspace-refresh', () =>
  window.dispatchEvent(new Event('workspace-refresh')),
);
contextBridge.exposeInMainWorld('plowshare', api);
