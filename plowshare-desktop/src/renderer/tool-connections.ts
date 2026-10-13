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
  document.body.append(dialog);
  let controls: ToolConnectionControls | undefined,
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
    controls = undefined;
    dialog.replaceChildren();
    restore?.focus();
  });
  return {
    open(project: string) {
      controls?.destroy();
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
      controls.element.open = true;
      const close = document.createElement('button');
      close.textContent = 'Close';
      close.type = 'button';
      close.addEventListener('click', () => dialog.close());
      dialog.replaceChildren(controls.element, close);
      dialog.showModal();
      close.focus();
    },
    update() {
      controls?.update();
      if (dialog.open && (identity !== key() || !state().connected))
        dialog.close();
    },
  };
}
