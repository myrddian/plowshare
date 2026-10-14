import { afterEach, expect, test, vi } from 'vitest';
import { nativeMcpControls } from '../../../plowshare-desktop/src/renderer/native-mcp.ts';
import type {
  NativeCommand,
  NativeCommandResult,
  NativeManagedStatus,
  NativeProfile,
} from '../../../integrations/mcp/src/index.ts';
import { decodeNativeConfig } from '../../../integrations/mcp/src/native-config.ts';
import { decodeToolSchema } from '../../../sdk/typescript/src/operations/tool-schemas.ts';

// DOM-only acceptance of the neutral renderer. Production console never imports native controls.
const profile: NativeProfile = {
  file: '/private-fixture/profile.json',
  revision: 'a'.repeat(64),
  config: decodeNativeConfig({
    owner: { origin: 'https://fixture.example', account: 'owner' },
    instance: 'desktop',
    profile: 'echo',
    project: 'Research',
    protocol: '2026-07-28',
    prefix: 'native_',
    journalDirectory: '/private-fixture/receipts',
    timeoutMs: 2000,
    timeoutSeconds: 2,
    shutdownGraceMs: 30,
    transport: {
      kind: 'http',
      endpoint: 'https://mcp.example/mcp',
      allowHttp: false,
      allowPrivateNetwork: false,
      authorization: { header: 'Authorization', environment: 'MCP_TOKEN' },
    },
  }),
};
const host: NativeManagedStatus = {
  instance: 'desktop',
  profile: 'echo',
  project: 'Research',
  transport: 'http',
  active: true,
  sharing: 'local',
  review: {
    executable: null,
    argumentCount: 0,
    environmentNames: ['MCP_TOKEN'],
    endpoint: 'https://mcp.example/mcp',
    authorizationConfigured: true,
  },
  tools: [
    {
      name: 'native_echo',
      description: '<img src=x onerror=alert(1)>',
      timeoutSeconds: 2,
      parameters: [],
      inputSchema: decodeToolSchema({
        type: 'object',
        properties: {
          rows: {
            type: 'array',
            items: { type: 'object', properties: { name: { type: 'string' } } },
          },
        },
      }),
    },
  ],
};
function button(root: HTMLElement, text: string) {
  const button = [...root.querySelectorAll('button')].find(
    (node) => node.textContent === text,
  );
  if (!button) throw new Error('Missing ' + text);
  return button;
}
function field(root: HTMLElement, name: string) {
  const input = root.querySelector<HTMLInputElement>(
    `[data-native-field="${name}"]`,
  );
  if (!input) throw new Error('Missing ' + name);
  return input;
}
function checkbox(root: HTMLElement, label: string) {
  const row = [...root.querySelectorAll('label')].find(
    (node) => node.textContent === label,
  );
  const control = row?.querySelector<HTMLInputElement>('input[type=checkbox]');
  if (!control) throw new Error('Missing checkbox');
  return control;
}
function fixture() {
  let actor = 'owner',
    connected = true;
  let deferred:
    ((command: NativeCommand) => Promise<NativeCommandResult>) | undefined;
  const commands: NativeCommand[] = [];
  const controls = nativeMcpControls({
    project: 'Research',
    context: () => ({
      origin: 'https://fixture.example',
      actor,
      connected,
      serverAdmin: false,
    }),
    execute: async (command) => {
      commands.push(command);
      if (deferred) return deferred(command);
      if (command.action === 'profile-read') return profile;
      if (command.action === 'inspect')
        return { ...host, active: false, tools: [] };
      if (command.action === 'start') return host;
      if (command.action === 'list') return [host];
      throw new Error('Unexpected action');
    },
  });
  controls.element.open = true;
  document.body.append(controls.element);
  field(controls.element, 'file').value = profile.file;
  return {
    controls,
    commands,
    defer: (work: typeof deferred) => {
      deferred = work;
    },
    actor: (value: string) => {
      actor = value;
      controls.update();
    },
    disconnect: () => {
      connected = false;
      controls.update();
    },
  };
}
afterEach(() => document.body.replaceChildren());
test('native form reads do not launch; start binds reviewed revision and nested inventory stays inert', async () => {
  const f = fixture(),
    root = f.controls.element;
  expect(f.commands).toEqual([]);
  button(root, 'Load profile').click();
  await vi.waitFor(() => expect(field(root, 'profile').value).toBe('echo'));
  expect(f.commands.map((command) => command.action)).toEqual(['profile-read']);
  button(root, 'Start local host').click();
  await vi.waitFor(() => expect(root.textContent).toContain('Action refused'));
  expect(f.commands).toHaveLength(1);
  checkbox(
    root,
    'I reviewed this profile and permit this instance to connect or launch its configured process',
  ).checked = true;
  button(root, 'Start local host').click();
  await vi.waitFor(() =>
    expect(root.textContent).toContain('Local host started'),
  );
  expect(f.commands.find((command) => command.action === 'start')).toEqual({
    action: 'start',
    file: profile.file,
    expectedRevision: profile.revision,
    consent: true,
  });
  expect(root.textContent).toContain('items');
  expect(root.textContent).toContain('<img src=x onerror=alert(1)>');
  expect(root.querySelector('img')).toBeNull();
  expect(
    f.commands.some(
      (command) => command.action === 'share' || command.action === 'call',
    ),
  ).toBe(false);
  f.controls.destroy();
  expect(f.commands.some((command) => command.action === 'stop')).toBe(false);
});

test('account changes discard delayed profile reads and clear private drafts', async () => {
  const f = fixture(),
    root = f.controls.element;
  let release: (value: NativeCommandResult) => void = () => {
    throw new Error('Read not started');
  };
  f.defer(
    () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  );
  button(root, 'Load profile').click();
  f.actor('other');
  release(profile);
  await vi.waitFor(() =>
    expect(button(root, 'Load profile').disabled).toBe(false),
  );
  expect(field(root, 'profile').value).toBe('');
  expect(field(root, 'file').value).toBe('');
  expect(root.textContent).not.toContain('MCP_TOKEN');
  expect(f.commands.some((command) => command.action === 'start')).toBe(false);
  f.controls.destroy();
});

test('changing location or closing while launch review is pending prevents a subsequent start', async () => {
  for (const change of ['tab', 'close', 'disconnect'] as const) {
    const f = fixture(),
      root = f.controls.element;
    button(root, 'Load profile').click();
    await vi.waitFor(() => expect(field(root, 'profile').value).toBe('echo'));
    let release: (value: NativeCommandResult) => void = () => {
      throw new Error('Review not started');
    };
    f.defer(
      () =>
        new Promise((resolve) => {
          release = resolve;
        }),
    );
    checkbox(
      root,
      'I reviewed this profile and permit this instance to connect or launch its configured process',
    ).checked = true;
    button(root, 'Start local host').click();
    if (change === 'tab') f.controls.active(false);
    else if (change === 'close') f.controls.destroy();
    else f.disconnect();
    release({ ...host, active: false, tools: [] });
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(f.commands.some((command) => command.action === 'start')).toBe(
      false,
    );
    f.controls.destroy();
    root.remove();
  }
});
