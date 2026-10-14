import { nativeMcpControls } from './native-mcp.ts';
import { toolConnectionControls } from '../../../clients/tool-connections.ts';
import type { ToolConnectionControls } from '../../../clients/tool-connections.ts';
import {
  managementRequest,
  decodeManagementIntent,
  ProviderManagementRefused,
} from 'plowshare-client-ts/operations/provider-management';
import type {
  ProviderManagement,
  ManagementRequest,
} from 'plowshare-client-ts/operations/provider-management';
import { decodeReply } from 'plowshare-client-ts/operations/schema';
import type {
  DesktopState,
  Reply,
  Request,
  ProviderManagementCall,
} from '../shared.ts';

/** Main owns the socket and private bookmarks. Renderer only holds display DTOs and drafts. */
export function installToolConnections(
  state: () => DesktopState,
  request: (value: Request) => Promise<Reply>,
) {
  const dialog = document.createElement('dialog');
  dialog.setAttribute('aria-label', 'Tool connections');
  dialog.className = 'tool-connections-dialog';
  document.body.append(dialog);
  let controls: ToolConnectionControls | undefined,
    native: ReturnType<typeof nativeMcpControls> | undefined,
    identity = '',
    restore: HTMLElement | null = null;
  const key = () => JSON.stringify([state().base, state().handle]);
  async function call(value: Request): Promise<Reply> {
    if (identity !== key() || !state().connected)
      throw new Error(
        'Connection changed; inspect the original server/account.',
      );
    const reply = await request(value);
    if (identity !== key() || !state().connected)
      throw new Error('Connection changed; previous reply discarded.');
    if (reply.providerManagementRefusal) {
      const refusal = reply.providerManagementRefusal;
      throw new ProviderManagementRefused(
        refusal.operation,
        refusal.code,
        refusal.unsupported,
      );
    }
    return reply;
  }
  const ipcCall = (value: ManagementRequest): ProviderManagementCall => {
    // Complete SDK boundary validation precedes this mapped-union compiler assertion.
    const checked = managementRequest(value.type, value.payload);
    return {
      action: 'provider-management',
      operation: checked.type,
      payload: checked.payload,
    } as ProviderManagementCall;
  };
  const management: ProviderManagement = {
    async request(operation, payload) {
      const reply = await call(ipcCall(managementRequest(operation, payload)));
      return decodeReply(operation, reply.providerManagement);
    },
    async execute(value) {
      return management.request(value.type, value.payload);
    },
    async pending() {
      const reply = await call({ action: 'provider-management-pending' });
      if (!reply.providerManagementPending)
        throw new Error('Unreadable management bookmarks.');
      return reply.providerManagementPending.map(decodeManagementIntent);
    },
    async recover(input) {
      const intent = decodeManagementIntent(input),
        reply = await call({ action: 'provider-management-recover', intent });
      return 'binding' in intent
        ? decodeReply('tool.scope.host.receipt', reply.providerManagement)
        : decodeReply('provider.connection.receipt', reply.providerManagement);
    },
  };
  dialog.addEventListener('close', () => {
    controls?.destroy();
    native?.destroy();
    native = undefined;
    controls = undefined;
    dialog.replaceChildren();
    restore?.focus();
  });
  return {
    open(project: string) {
      controls?.destroy();
      native?.destroy();
      identity = key();
      restore =
        document.activeElement instanceof HTMLElement
          ? document.activeElement
          : null;
      controls = toolConnectionControls({
        project,
        management,
        context: () => ({
          origin: state().base,
          actor: state().handle,
          connected: state().connected,
          serverAdmin: state().serverAdmin === true,
        }),
        authorize: async (url) => {
          await call({ action: 'open-link', url });
        },
      });
      native = nativeMcpControls({
        project,
        context: () => ({
          origin: state().base,
          actor: state().handle,
          connected: state().connected,
          serverAdmin: state().serverAdmin === true,
        }),
        execute: async (command) => {
          const reply = await call({
            action: 'native-mcp',
            command: JSON.stringify(command),
          });
          if (reply.nativeMcp === undefined)
            throw new Error('Unreadable native MCP reply');
          return reply.nativeMcp;
        },
      });
      native.element.open = true;
      controls.element.open = true;
      const close = document.createElement('button');
      close.textContent = 'Close';
      close.type = 'button';
      close.addEventListener('click', () => dialog.close());
      const header = document.createElement('div');
      header.className = 'dialog-header';
      const title = document.createElement('h2');
      title.textContent = 'Tool connections';
      header.append(title, close);
      const tabs = document.createElement('div');
      tabs.className = 'tool-connection-tabs';
      tabs.setAttribute('role', 'tablist');
      tabs.setAttribute('aria-label', 'Connection location');
      const localTab = document.createElement('button'),
        serverTab = document.createElement('button');
      localTab.type = serverTab.type = 'button';
      localTab.textContent = 'This client';
      serverTab.textContent = 'Server';
      for (const tab of [localTab, serverTab]) tab.setAttribute('role', 'tab');
      const choose = (local: boolean) => {
        native?.active(local);
        if (controls) {
          controls.element.hidden = local;
          controls.element.open = !local;
        }
        localTab.setAttribute('aria-selected', String(local));
        serverTab.setAttribute('aria-selected', String(!local));
        localTab.tabIndex = local ? 0 : -1;
        serverTab.tabIndex = local ? -1 : 0;
        dialog.scrollTop = 0;
      };
      localTab.addEventListener('click', () => choose(true));
      serverTab.addEventListener('click', () => choose(false));
      tabs.addEventListener('keydown', (event) => {
        if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key))
          return;
        event.preventDefault();
        const local =
          event.key === 'Home' ||
          (event.key !== 'End' &&
            localTab.getAttribute('aria-selected') !== 'true');
        choose(local);
        (local ? localTab : serverTab).focus();
      });
      native.element.id = 'native-tool-connections';
      controls.element.id = 'server-tool-connections';
      localTab.setAttribute('aria-controls', native.element.id);
      serverTab.setAttribute('aria-controls', controls.element.id);
      localTab.id = 'native-tool-tab';
      serverTab.id = 'server-tool-tab';
      native.element.setAttribute('role', 'tabpanel');
      native.element.setAttribute('aria-labelledby', localTab.id);
      controls.element.setAttribute('role', 'tabpanel');
      controls.element.setAttribute('aria-labelledby', serverTab.id);
      tabs.append(localTab, serverTab);
      dialog.replaceChildren(header, tabs, native.element, controls.element);
      choose(true);
      dialog.showModal();
      close.focus();
    },
    update() {
      controls?.update();
      native?.update();
      if (dialog.open && (identity !== key() || !state().connected))
        dialog.close();
    },
  };
}
