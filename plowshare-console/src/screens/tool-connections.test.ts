import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { consoleToolConnections } from './tool-connections.ts';
import { decodePayload } from '../../../sdk/typescript/src/operations/schema.ts';
import type { EventStream, FrameOutcome, StreamState } from '../events.ts';

const id = '11111111-1111-1111-1111-111111111111';
const connection = {
  project: 'fixture',
  connection: id,
  revision: id,
  enabled: true,
  deleted: false,
  definition: {
    name: 'Fixture endpoint',
    endpoint: 'https://provider.example/mcp',
    authentication: 'API_KEY',
    apiKeyHeader: 'X-API-Key',
    oauth: null,
  },
};
const credential = {
  project: 'fixture',
  connection: id,
  subject: 'owner',
  generation: id,
  connectionRevision: id,
  state: 'READY',
  expiresAt: null,
  keyId: 'fixture',
};
function button(root: HTMLElement, name: string): HTMLButtonElement {
  const value = [...root.querySelectorAll('button')].find(
    (node) => node.textContent === name,
  );
  if (!value) throw new Error('Missing button ' + name);
  return value;
}
describe('tool connections on the authenticated console socket', () => {
  beforeEach(() => {
    sessionStorage.clear();
    document.body.replaceChildren();
  });
  afterEach(() => {
    document.body.replaceChildren();
    sessionStorage.clear();
  });
  function fixture() {
    let state: StreamState = 'open',
      actor = 'owner',
      serverAdmin = false,
      failPut = false;
    let deferred: (() => Promise<FrameOutcome>) | undefined;
    const calls: string[] = [];
    const saves: ReturnType<typeof savedDefinition>[] = [];
    function savedDefinition(value: unknown) {
      return decodePayload('provider.connection.save', value);
    }
    const socket: EventStream = {
      status: () => ({ state, attempt: 0, retryInMs: null }),
      close() {},
      async ask(type, payload) {
        calls.push(type);
        if (type === 'admin.status')
          return { code: 'OK', payload: { handle: actor, serverAdmin } };
        if (type === 'provider.connection.list')
          return deferred ? deferred() : { code: 'OK', payload: [connection] };
        if (type === 'tool.scope.host.list') return { code: 'OK', payload: [] };
        if (type === 'tool.scope.list')
          return { code: 'OK', payload: { connections: [] } };
        if (type === 'provider.credential.status')
          return { code: 'OK', payload: credential };
        if (type === 'provider.connection.save') {
          const input = savedDefinition(payload);
          saves.push(input);
          return {
            code: 'OK',
            payload: {
              requestId: input.requestId,
              operation: type,
              connection: { ...connection, definition: input.definition },
              credential: null,
            },
          };
        }
        if (type === 'provider.credential.put' && failPut)
          throw new Error('lost reply');
        if (type === 'provider.connection.receipt')
          return { code: 'NOT_FOUND', said: 'receipt missing' };
        throw new Error('Unexpected operation ' + type);
      },
    };
    const controls = consoleToolConnections(() => socket, 'fixture');
    document.body.append(controls.element);
    controls.element.querySelector('summary')?.click();
    return {
      controls,
      calls,
      saves,
      admin: () => {
        serverAdmin = true;
      },
      state: (value: StreamState) => {
        state = value;
        controls.update();
      },
      actor: (value: string) => {
        actor = value;
      },
      fail: () => {
        failPut = true;
      },
      defer: (value: () => Promise<FrameOutcome>) => {
        deferred = value;
      },
    };
  }
  it('shows only server HTTP controls and saves typed endpoint forms without JSON or native operations', async () => {
    const f = fixture(),
      root = f.controls.element;
    f.admin();
    await vi.waitFor(() =>
      expect(root.textContent).toContain('owner · execution project'),
    );
    button(root, 'Refresh').click();
    await vi.waitFor(() =>
      expect(root.textContent).toContain('Snapshot read at'),
    );
    expect(root.dataset['connectionLocation']).toBe('server');
    expect(root.querySelector('[data-connection-location=local]')).toBeNull();
    expect(root.querySelector('textarea')).toBeNull();
    const selector = root.querySelector('select');
    if (!selector) throw new Error('Endpoint selection missing');
    selector.value = id;
    selector.dispatchEvent(new Event('change'));
    const endpoint = root.querySelector<HTMLInputElement>(
      '[data-endpoint-field="HTTPS MCP endpoint"]',
    );
    if (!endpoint) throw new Error('Endpoint field missing');
    expect(endpoint.value).toBe(connection.definition.endpoint);
    endpoint.value = 'http://invalid.example/mcp';
    button(root, 'Save shared endpoint').click();
    await vi.waitFor(() =>
      expect(root.textContent).toContain('Outcome unknown or input refused'),
    );
    expect(f.saves).toHaveLength(0);
    endpoint.value = 'https://updated.example/mcp';
    button(root, 'Save shared endpoint').click();
    await vi.waitFor(() => expect(f.saves).toHaveLength(1));
    expect(f.saves[0]?.definition.endpoint).toBe('https://updated.example/mcp');
    expect(f.saves[0]?.expectedRevision).toBe(id);
    expect(f.saves[0]?.definition.authentication).toBe('API_KEY');
    expect(
      f.calls.some(
        (type) =>
          type.startsWith('tool.scope.worker.') ||
          type === 'tool.scope.connect',
      ),
    ).toBe(false);
    f.controls.destroy();
  });
  it('uses owner reads, masked material and redacted bookmarks; missing receipt never resends', async () => {
    const f = fixture(),
      root = f.controls.element;
    await vi.waitFor(() =>
      expect(root.textContent).toContain('owner · execution project fixture'),
    );
    button(root, 'Refresh').click();
    await vi.waitFor(() =>
      expect(root.textContent).toContain('Snapshot read at'),
    );
    const endpoint = root.querySelector('select');
    if (!endpoint) throw new Error('Missing endpoint selection.');
    endpoint.value = id;
    endpoint.dispatchEvent(new Event('change'));
    button(root, 'Inspect authorization').click();
    await vi.waitFor(() =>
      expect(root.textContent).toContain('Authorization for owner: READY'),
    );
    const secret = root.querySelector<HTMLInputElement>('input[type=password]');
    if (!secret) throw new Error('Missing masked input.');
    secret.value = 'private-material';
    f.fail();
    button(root, 'Set credential').click();
    expect(secret.value).toBe('');
    await vi.waitFor(() =>
      expect(root.textContent).toContain('Outcome unknown'),
    );
    expect(JSON.stringify(sessionStorage)).not.toContain('private-material');
    expect(root.textContent).not.toContain('private-material');
    const pending = [...root.querySelectorAll('select')].at(-1);
    if (!pending) throw new Error('Missing pending selection.');
    expect(pending.options.length).toBe(2);
    pending.selectedIndex = 1;
    button(root, 'Read original receipt').click();
    await vi.waitFor(() =>
      expect(root.textContent).toContain(
        'provider.connection.receipt: NOT_FOUND',
      ),
    );
    expect(
      f.calls.filter((type) => type === 'provider.credential.put'),
    ).toHaveLength(1);
    expect(f.calls.some((type) => type.startsWith('tool.scope.worker.'))).toBe(
      false,
    );
    root.open = false;
    root.dispatchEvent(new Event('toggle'));
    expect(secret.value).toBe('');
    f.controls.destroy();
  });
  it('drops late snapshots and clears fields across reconnect/account changes', async () => {
    const f = fixture(),
      root = f.controls.element;
    await vi.waitFor(() =>
      expect(root.textContent).toContain('owner · execution project'),
    );
    let resolve: (value: FrameOutcome) => void = () => {
      throw new Error('Read not started.');
    };
    f.defer(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    button(root, 'Refresh').click();
    await vi.waitFor(() =>
      expect(f.calls).toContain('provider.connection.list'),
    );
    const secret = root.querySelector<HTMLInputElement>('input[type=password]');
    if (!secret) throw new Error('Missing masked input.');
    secret.value = 'discard-me';
    f.state('closed');
    f.actor('other');
    f.state('open');
    resolve({ code: 'OK', payload: [connection] });
    await vi.waitFor(() =>
      expect(root.textContent).toContain('other · execution project'),
    );
    expect(root.textContent).not.toContain('Fixture endpoint');
    expect(secret.value).toBe('');
    expect(root.querySelector('select')?.options.length).toBe(0);
    f.controls.destroy();
  });
});
