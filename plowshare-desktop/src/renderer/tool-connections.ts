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

/** Settings owns client configuration; server credentials have a separate project view.
 * Main owns files, processes and sockets. Closing a view discards drafts, never a host.
 */
export function installToolConnections(
  state: () => DesktopState,
  request: (value: Request) => Promise<Reply>,
  openConnection: () => void,
) {
  const dialog = document.createElement('dialog');
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
  const context = () => ({
    origin: state().base,
    actor: state().handle,
    connected: state().connected,
    serverAdmin: state().serverAdmin === true,
  });
  const clear = () => {
    controls?.destroy();
    native?.destroy();
    native = undefined;
    controls = undefined;
    dialog.replaceChildren();
  };
  dialog.addEventListener('close', () => {
    clear();
    restore?.focus();
  });
  function header(title: string) {
    clear();
    identity = key();
    restore =
      document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null;
    dialog.setAttribute('aria-label', title);
    const header = document.createElement('header');
    header.className = 'dialog-header';
    const heading = document.createElement('h2');
    heading.textContent = title;
    const close = document.createElement('button');
    close.type = 'button';
    close.textContent = 'Close';
    close.addEventListener('click', () => dialog.close());
    header.append(heading, close);
    dialog.append(header);
    return close;
  }
  return {
    open(project: string, initial: 'connection' | 'mcp' = 'connection') {
      const close = header('Settings');
      const tabs = document.createElement('div');
      tabs.className = 'tool-connection-tabs';
      tabs.setAttribute('role', 'tablist');
      tabs.setAttribute('aria-label', 'Settings sections');
      const connectionTab = document.createElement('button'),
        mcpTab = document.createElement('button');
      const connection = document.createElement('section');
      connection.id = 'connection-settings';
      connection.setAttribute('role', 'tabpanel');
      const description = document.createElement('p');
      description.textContent = state().connected
        ? `${state().base} · ${state().handle}`
        : 'Choose a server and sign in.';
      const edit = document.createElement('button');
      edit.type = 'button';
      edit.textContent = 'Manage server connections';
      edit.addEventListener('click', () => {
        dialog.close();
        openConnection();
      });
      connection.append(description, edit);
      const root = state().projectFolders?.find(
        (folder) => folder.name === project,
      )?.path;
      native = nativeMcpControls({
        project,
        ...(root === undefined ? {} : { root }),
        context,
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
      native.element.id = 'native-tool-connections';
      native.element.setAttribute('role', 'tabpanel');
      for (const [tab, label, panel, id] of [
        [connectionTab, 'Connection', connection, 'connection-settings-tab'],
        [mcpTab, 'Client MCP', native.element, 'native-tool-tab'],
      ] as const) {
        tab.type = 'button';
        tab.textContent = label;
        tab.id = id;
        tab.setAttribute('role', 'tab');
        tab.setAttribute('aria-controls', panel.id);
        panel.setAttribute('aria-labelledby', id);
        tabs.append(tab);
      }
      let loaded = false;
      const choose = (mcp: boolean) => {
        connection.hidden = mcp;
        native?.active(mcp);
        connectionTab.setAttribute('aria-selected', String(!mcp));
        mcpTab.setAttribute('aria-selected', String(mcp));
        connectionTab.tabIndex = mcp ? -1 : 0;
        mcpTab.tabIndex = mcp ? 0 : -1;
        dialog.scrollTop = 0;
        if (mcp && !loaded && state().connected) {
          loaded = true;
          native?.loadSettings();
        }
      };
      connectionTab.addEventListener('click', () => choose(false));
      mcpTab.addEventListener('click', () => choose(true));
      tabs.addEventListener('keydown', (event) => {
        if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key))
          return;
        event.preventDefault();
        const mcp =
          event.key === 'End' ||
          (event.key !== 'Home' &&
            mcpTab.getAttribute('aria-selected') !== 'true');
        choose(mcp);
        (mcp ? mcpTab : connectionTab).focus();
      });
      dialog.append(tabs, connection, native.element);
      choose(initial === 'mcp');
      dialog.showModal();
      close.focus();
    },
    openOwner(project: string) {
      const close = header('My server tools');
      controls = toolConnectionControls({
        project,
        management: providerManagement(call),
        context,
        authorize: async (url) => {
          await call({ action: 'open-link', url });
        },
      });
      controls.element.open = true;
      dialog.append(controls.element);
      dialog.showModal();
      close.focus();
    },
    update() {
      controls?.update();
      native?.update();
      if (dialog.open && identity !== key()) dialog.close();
    },
  };
}

/** Shared typed management adapter; each mounted view supplies its own identity fence. */
function providerManagement(
  call: (value: Request) => Promise<Reply>,
): ProviderManagement {
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
  return management;
}

/** Server-owned endpoints and assignments mount inside administration, with unchanged SDK authorization. */
export function serverToolConnections(
  state: () => DesktopState,
  request: (value: Request) => Promise<Reply>,
  project: string,
): ToolConnectionControls {
  const identity = JSON.stringify([state().base, state().handle]);
  const current = () =>
    state().connected &&
    state().serverAdmin === true &&
    identity === JSON.stringify([state().base, state().handle]);
  const call = async (value: Request): Promise<Reply> => {
    if (!current()) throw new Error('Administration connection changed.');
    const reply = await request(value);
    if (!current())
      throw new Error(
        'Administration connection changed; previous reply discarded.',
      );
    if (reply.providerManagementRefusal) {
      const refusal = reply.providerManagementRefusal;
      throw new ProviderManagementRefused(
        refusal.operation,
        refusal.code,
        refusal.unsupported,
      );
    }
    return reply;
  };
  return toolConnectionControls({
    project,
    management: providerManagement(call),
    context: () => ({
      origin: state().base,
      actor: state().handle,
      connected: current(),
      serverAdmin: state().serverAdmin === true,
    }),
    authorize: async (url) => {
      await call({ action: 'open-link', url });
    },
  });
}
