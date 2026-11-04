import { errorMessage } from 'plowshare-client-ts/binding/values';
import type { RuntimeSetting } from '../runtime-configuration.ts';
import type { DesktopState, Reply, Request } from '../shared.ts';
import type { AdministrationPanel } from './admin-panel.ts';
import { background } from './events.ts';

/** Only declared live keys are editable. Startup settings remain owned by deployment configuration. */
export function installConfigurationAdmin(
  request: (value: Request) => Promise<Reply>,
  host: HTMLElement,
): AdministrationPanel {
  const element = document.createElement('section');
  element.id = 'configuration-admin-panel';
  element.className = 'administration-panel';
  element.hidden = true;
  element.innerHTML = `<header><div><h2>Configuration</h2><p>Live settings declared by this server.</p></div><button type="button" data-refresh>Refresh</button></header>
    <p class="admin-note">Models, providers, pools, storage and other startup settings are managed in the server deployment and require a restart. This page lists live keys only.</p>
    <p data-error role="alert" hidden></p><p data-saved role="status"></p>
    <div class="admin-columns"><div data-settings aria-label="Live settings"></div><form data-edit hidden><h3 data-setting-key></h3><p data-source></p><p data-pinned class="admin-note" hidden>This value is pinned by deployment configuration. An edit applies now, but the deployment value will be restored on restart.</p><label>Value<textarea name="value" rows="4" maxlength="65536" spellcheck="false"></textarea></label><p class="admin-note">The server stores this text as supplied. A saved value does not guarantee that a consumer accepts it; verify the affected operation after changing it.</p><p data-updated></p><button type="submit">Save live value</button></form></div><p data-empty hidden>No live configuration keys are declared. Startup settings are still managed in the server deployment.</p>`;
  host.append(element);
  const get = <T extends HTMLElement>(selector: string) =>
    element.querySelector<T>(selector)!;
  let state: DesktopState | undefined,
    generation = 0,
    busy = false;
  let settings: readonly RuntimeSetting[] = [],
    selected: RuntimeSetting | undefined;
  const message = (reason?: unknown) => {
    get('[data-error]').hidden = reason === undefined;
    get('[data-error]').textContent =
      reason === undefined ? '' : errorMessage(reason);
  };
  function choose(setting: RuntimeSetting) {
    selected = setting;
    get('[data-edit]').hidden = false;
    get('[data-setting-key]').textContent = setting.key;
    get('[data-source]').textContent = setting.pinned
      ? 'Deployment pin · editable for this runtime'
      : setting.updatedBy && setting.updatedBy !== 'boot'
        ? 'Runtime override'
        : 'Startup value';
    get('[data-pinned]').hidden = !setting.pinned;
    get<HTMLTextAreaElement>('[name=value]').value = setting.value ?? '';
    get('[data-updated]').textContent = setting.updatedBy
      ? `Last changed by ${setting.updatedBy}${setting.updatedAt ? ` · ${new Date(setting.updatedAt).toLocaleString()}` : ''}`
      : 'No runtime edit recorded.';
    for (const button of get(
      '[data-settings]',
    ).querySelectorAll<HTMLButtonElement>('button'))
      button.setAttribute(
        'aria-pressed',
        String(button.dataset.key === setting.key),
      );
  }
  function render() {
    get('[data-settings]').replaceChildren();
    get('[data-empty]').hidden = settings.length !== 0;
    for (const setting of settings) {
      const button = document.createElement('button');
      button.type = 'button';
      button.dataset.key = setting.key;
      button.textContent = setting.key;
      button.addEventListener('click', () => {
        message();
        get('[data-saved]').textContent = '';
        choose(setting);
      });
      get('[data-settings]').append(button);
    }
    const current =
      settings.find((row) => row.key === selected?.key) ?? settings[0];
    get('[data-edit]').hidden = !current;
    if (current) choose(current);
    else selected = undefined;
  }
  async function call(
    value: Extract<Request, { action: 'server-configuration' }>,
  ) {
    const turn = generation;
    if (element.hidden || !state?.connected || !state.serverAdmin)
      throw new Error('Connect as a server administrator first.');
    const reply = await request(value);
    if (
      turn !== generation ||
      element.hidden ||
      !state.connected ||
      !state.serverAdmin
    )
      throw new Error('Administration connection changed.');
    return reply;
  }
  async function run(work: () => Promise<void>) {
    if (busy) return;
    busy = true;
    const turn = generation;
    message();
    const controls = [
      ...element.querySelectorAll<HTMLButtonElement | HTMLTextAreaElement>(
        'button,textarea',
      ),
    ];
    controls.forEach((node) => (node.disabled = true));
    try {
      await work();
    } catch (reason) {
      if (turn === generation && !element.hidden) message(reason);
    } finally {
      if (turn === generation) {
        busy = false;
        controls.forEach((node) => (node.disabled = false));
      }
    }
  }
  async function refresh() {
    const reply = await call({
      action: 'server-configuration',
      operation: 'list',
    });
    if (!reply.runtimeSettings)
      throw new Error('Unreadable live configuration listing.');
    settings = reply.runtimeSettings;
    render();
  }
  get('[data-refresh]').addEventListener('click', () => {
    get('[data-saved]').textContent = '';
    background(run(refresh));
  });
  get<HTMLFormElement>('[data-edit]').addEventListener('submit', (event) => {
    event.preventDefault();
    const setting = selected;
    if (!setting) return;
    get('[data-saved]').textContent = '';
    background(
      run(async () => {
        const reply = await call({
          action: 'server-configuration',
          operation: 'set',
          key: setting.key,
          value: get<HTMLTextAreaElement>('[name=value]').value,
        });
        if (!reply.runtimeSetting)
          throw new Error(
            'Unreadable configuration write reply. Reload to inspect its outcome.',
          );
        const updated = reply.runtimeSetting;
        settings = settings.map((row) =>
          row.key === updated.key ? updated : row,
        );
        render();
        get('[data-saved]').textContent =
          'Live value saved. Verify the affected operation before making further changes.';
      }),
    );
  });
  function close() {
    generation++;
    busy = false;
    element.hidden = true;
    settings = [];
    selected = undefined;
    get('[data-settings]').replaceChildren();
    get<HTMLFormElement>('[data-edit]').reset();
    get('[data-edit]').hidden = true;
    get('[data-saved]').textContent = '';
    message();
  }
  return {
    element,
    open() {
      element.hidden = false;
      background(run(refresh));
    },
    close,
    update(next) {
      if (
        state &&
        (state.base !== next.base ||
          state.handle !== next.handle ||
          !next.connected ||
          !next.serverAdmin)
      )
        close();
      state = next;
    },
  };
}
