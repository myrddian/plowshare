import { contextBridge, ipcRenderer } from 'electron';
import type {
  DesktopApi,
  DesktopState,
  Reply,
  Request,
  WorkspaceMessage,
} from './shared.ts';

/**
 * Optional timing, measured here because this is the boundary a command actually crosses: the
 * renderer's call is the moment it was issued, and the frame after its reply is the moment the UI
 * had finished answering it. Measuring in the main process alone cannot see either end.
 *
 * <p>The renderer is told nothing about whether telemetry is on. It always reports; the main
 * process decides whether a row is written, so the switch lives in one place.
 *
 * <p><b>What renderMs does and does not mean.</b> It is the first frame painted after the reply
 * resolved, from a double {@code requestAnimationFrame}: one callback runs before the coming
 * frame, the second after it has been composited. That is the final UI update when the caller
 * renders synchronously on resolve, which is how these screens are written. A caller that does
 * further asynchronous work after the reply — another fetch, a deferred layout — will finish after
 * this mark, and the row will understate it. It is reported as null rather than zero when no frame
 * arrives, which is what a hidden or backgrounded window does, since such a window is never asked
 * to paint.
 */
let inFlight = 0;
let sequence = 0;

function afterNextPaint(): Promise<number | null> {
  if (typeof requestAnimationFrame !== 'function') return Promise.resolve(null);
  return new Promise((resolve) => {
    // A hidden window is not asked to paint, so the frame may never come. Resolving null beats
    // leaving the row unwritten or inventing a number.
    const abandon = setTimeout(() => resolve(null), 2000);
    requestAnimationFrame(() =>
      requestAnimationFrame(() => {
        clearTimeout(abandon);
        resolve(performance.now());
      }),
    );
  });
}

const api: DesktopApi = {
  request: async (request: Request) => {
    const issued = performance.now();
    const depth = inFlight;
    const correlation = (sequence += 1);
    inFlight += 1;
    let ok = true;
    try {
      return (await ipcRenderer.invoke(
        'plowshare:request',
        request,
        correlation,
      )) as Reply;
    } catch (failure) {
      ok = false;
      throw failure;
    } finally {
      inFlight -= 1;
      const replyMs = performance.now() - issued;
      // Fire and forget, and after the reply has been handed back, so observing a command cannot
      // delay it. The main process ignores the row when telemetry is off.
      // Reported rather than swallowed: afterNextPaint resolves rather than rejects, so a
      // rejection here would be a defect in this file and should be visible.
      void afterNextPaint()
        .then((painted) =>
          ipcRenderer.send('plowshare:telemetry', {
            correlation,
            action:
              request && typeof request === 'object' && 'action' in request
                ? String((request as { action: unknown }).action)
                : 'unknown',
            depth,
            replyMs,
            renderMs: painted === null ? null : painted - issued,
            ok,
          }),
        )
        .catch((failure: unknown) =>
          console.warn('Client telemetry row dropped', failure),
        );
    }
  },
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
