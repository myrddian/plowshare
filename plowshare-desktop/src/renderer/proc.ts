import {
  mountProcPanel,
  type ProcPanel,
  type ProcReader,
} from '../../../plowshare-console/src/screens/proc-panel.ts';
import {
  decodePayload,
  decodeReply,
} from 'plowshare-client-ts/operations/schema';
import { background } from './events.ts';
import { replyState } from '../shared.ts';
import type { DesktopState } from '../shared.ts';

let panel: ProcPanel | undefined;
let identity: string | undefined;
const root = document.querySelector<HTMLElement>('#proc-panel')!;
const reader: ProcReader = {
  async read(type, query) {
    const reply = await window.plowshare.request({
      action: 'proc-read',
      type,
      payload: decodePayload(type, query),
    });
    return decodeReply(type, reply.proc);
  },
};
function update(state: DesktopState) {
  const next = `${state.base}|${state.handle}|${state.mode}`;
  if (identity !== next) {
    panel?.destroy();
    panel = undefined;
    root.replaceChildren();
    identity = next;
  }
  document.querySelector('#proc-connection')!.textContent =
    state.mode === 'demo'
      ? 'Connect a server to audit its work.'
      : `${state.base} · ${state.handle} · ${state.connected ? 'Connected' : 'Disconnected · snapshots may be stale'}`;
  if (state.mode === 'demo') return;
  if (!panel) {
    panel = mountProcPanel(root, reader, {
      serverAdmin: state.serverAdmin === true,
      canOpenConversation: (row) => row.owner === state.handle,
      openConversation: (conversation) =>
        background(
          window.plowshare.request({ action: 'proc-trajectory', conversation }),
        ),
    });
    background(panel.refresh());
  }
}
window.plowshare.subscribe(update);
window.addEventListener('workspace-refresh', () =>
  background(panel?.refresh()),
);
window.addEventListener('pagehide', () => panel?.destroy());
background(
  window.plowshare
    .request({ action: 'bootstrap' })
    .then((reply) => update(replyState(reply))),
);
