import { mountUsagePanel, type UsagePanel } from '../../../plowshare-console/src/screens/usage-panel.ts';
import type { DesktopState } from '../shared.ts';
let panel: UsagePanel | undefined;
let state: DesktopState;
const root = document.querySelector<HTMLElement>('#usage-panel')!;
function update(next: DesktopState) {
  state = next;
  document.querySelector('#usage-connection')!.textContent = state.mode === 'demo' ? 'Connect a server to read recorded usage.' : `${state.base} · ${state.handle} · ${state.connected ? 'Connected' : 'Disconnected · last snapshot'}`;
  if (state.mode === 'demo') { panel?.destroy(); panel = undefined; root.textContent = 'Usage is recorded by your server. The demo has no usage ledger.'; return; }
  if (!panel) panel = mountUsagePanel(root, {
    overview:true, context:false, injectStyle:false, storage:localStorage,
    select:async(type, filter) => { update((await window.plowshare.request({action:'usage-open',type,filter})).state); },
    read:async(type, payload) => (await window.plowshare.request({action:'usage-read',type,payload})).usage,
  });
  if (state.usage) panel.update(state.usage);
}
window.plowshare.subscribe(update);
window.addEventListener('workspace-refresh', () => { void panel?.refresh(); });
window.addEventListener('pagehide', () => { panel?.destroy(); void window.plowshare.request({action:'usage-close'}); });
void window.plowshare.request({action:'bootstrap'}).then(reply=>update(reply.state));
