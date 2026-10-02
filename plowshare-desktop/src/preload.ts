import { contextBridge, ipcRenderer } from 'electron';
import type { DesktopApi, DesktopState, Request } from './shared.ts';
const api: DesktopApi = {
  request: (request: Request) => ipcRenderer.invoke('plowshare:request', request),
  subscribe: (listener: (state: DesktopState) => void) => {
    const receive = (_event: unknown, state: DesktopState) => listener(state);
    ipcRenderer.on('plowshare:state', receive);
    return () => ipcRenderer.removeListener('plowshare:state', receive);
  },
};
contextBridge.exposeInMainWorld('plowshare', api);
