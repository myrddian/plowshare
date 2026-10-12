import { toolConnectionControls } from '../../../clients/tool-connections.ts';
import {
  ProviderManagementClient,
  SessionManagementIntents,
} from '../../../sdk/typescript/src/operations/provider-management.ts';
import { checkedTransport } from '../../../sdk/typescript/src/operations/transport.ts';
import { decodeReply } from '../../../sdk/typescript/src/operations/schema.ts';
import type { EventStream } from '../events.ts';

/** Project-scoped owner controls on the tab's shared socket. Authentication is confirmed
 * on that socket before selecting a bookmark namespace. Account/reconnect changes fence
 * old replies; a refresh never borrows worker authority or releases server-held secrets. */
export function consoleToolConnections(
  stream: () => EventStream | null,
  project: string,
) {
  let actor = '',
    serverAdmin = false,
    generation = 0,
    destroyed = false;
  const wire = checkedTransport({
    async ask(type, payload) {
      const socket = stream(),
        epoch = generation;
      if (destroyed || !socket || socket.status().state !== 'open')
        throw new Error('Connect to manage tool connections.');
      const authority = await socket.ask('admin.status', {});
      if (
        epoch !== generation ||
        stream() !== socket ||
        authority.code !== 'OK'
      )
        throw new Error('Authentication snapshot changed.');
      const confirmed = decodeReply('admin.status', authority.payload);
      if (actor && actor !== confirmed.handle)
        throw new Error('Account changed. Reload Projects before management.');
      actor = confirmed.handle;
      serverAdmin = confirmed.serverAdmin;
      const answer = await socket.ask(type, payload);
      if (
        epoch !== generation ||
        stream() !== socket ||
        socket.status().state !== 'open'
      )
        throw new Error('Connection changed; reply discarded.');
      return answer;
    },
  });
  const management = new ProviderManagementClient(wire, {
    // Resolve identity BEFORE the shared client persists a mutation. The private adapter
    // stores only validated redacted coordinates. No credential/URL enters browser storage.
    async list() {
      return (await intents()).list();
    },
    async reserve(value) {
      await (await intents()).reserve(value);
    },
    async remove(requestId) {
      await (await intents()).remove(requestId);
    },
  });
  async function intents() {
    const socket = stream(),
      epoch = generation;
    if (destroyed || !socket || socket.status().state !== 'open')
      throw new Error('Connect before inspecting management bookmarks.');
    const answer = await socket.ask('admin.status', {});
    if (epoch !== generation || stream() !== socket || answer.code !== 'OK')
      throw new Error('Authentication changed.');
    const confirmed = decodeReply('admin.status', answer.payload);
    if (actor && actor !== confirmed.handle)
      throw new Error('Account changed. Reload Projects before management.');
    actor = confirmed.handle;
    serverAdmin = confirmed.serverAdmin;
    return new SessionManagementIntents(
      sessionStorage,
      `plowshare.provider-management.${location.origin}.${actor}`,
    );
  }
  const controls = toolConnectionControls({
    project,
    management,
    context: () => ({
      origin: location.origin,
      actor,
      serverAdmin,
      connected: actor !== '' && stream()?.status().state === 'open',
    }),
  });
  // Confirm the socket identity on opening, so the first intentional Refresh is not
  // confused with an account switch. Opening never sends a mutation.
  const summary = controls.element.querySelector('summary');
  const authenticationError = document.createElement('p');
  authenticationError.setAttribute('role', 'alert');
  controls.element.append(authenticationError);
  summary?.addEventListener('click', () => {
    intents()
      .then(() => {
        authenticationError.textContent = '';
        controls.update();
      })
      .catch(() => {
        controls.update();
        authenticationError.textContent =
          'Authenticated identity could not be confirmed. Connect and reopen before managing tools.';
      });
  });
  return {
    ...controls,
    update() {
      generation++;
      if (stream()?.status().state !== 'open') {
        actor = '';
        serverAdmin = false;
      }
      controls.update();
      if (
        controls.element.open &&
        !actor &&
        stream()?.status().state === 'open'
      )
        intents()
          .then(() => controls.update())
          .catch(() => {
            authenticationError.textContent =
              'Reconnect identity could not be confirmed. Reopen Projects before managing tools.';
          });
    },
    destroy() {
      destroyed = true;
      generation++;
      controls.destroy();
    },
  };
}
