import { expect, test } from 'vitest';
import type { NativeCommand } from 'plowshare-mcp-integration';
import type { ProviderManagement } from 'plowshare-client-ts/operations/provider-management';
import { mcpControls } from './mcp-controls.ts';
import { nativeFixture } from '../../../integrations/mcp/build/mcp/native.test-support.js';
import {
  NativeHostCommands,
  NativeMcpSessions,
} from 'plowshare-mcp-integration';

function refusedServer(): ProviderManagement {
  return {
    async request() {
      throw new Error('Local controls must not use server operations');
    },
    async execute() {
      throw new Error('Unexpected mutation');
    },
    async pending() {
      return [];
    },
    async recover() {
      throw new Error('Unexpected recovery');
    },
  };
}
test('guided native review/start/list/stop uses the original native owner without server operations', async () => {
  const fixture = await nativeFixture();
  const owner = new NativeHostCommands(
    fixture.config.owner,
    new NativeMcpSessions({ NATIVE_SELECTED: 'selected-value' }),
  );
  const messages: string[] = [],
    commands: NativeCommand[] = [];
  let replies: string[] = [];
  const run = () =>
    mcpControls({
      prompt: {
        show: (message) => messages.push(message),
        read: async () => replies.shift(),
      },
      project: fixture.config.project,
      owner: fixture.config.owner,
      current: () => true,
      native: async (command) => {
        commands.push(command);
        return owner.execute(command);
      },
      server: refusedServer(),
      authorize: async () => {
        throw new Error('Unexpected authorization');
      },
    });
  try {
    replies = ['1', '4', fixture.file, '/cancel'];
    await run();
    expect((await fixture.seen()).length).toBe(0);
    expect(commands.some((command) => command.action === 'start')).toBe(false);
    replies = ['1', '4', fixture.file, 'yes'];
    await run();
    expect(
      (await fixture.seen()).some((row) => row.method === 'tools/list'),
    ).toBe(true);
    expect(
      commands.find((command) => command.action === 'start'),
    ).toHaveProperty('expectedRevision');
    replies = ['1', '1'];
    await run();
    expect(messages.join('\n')).toContain('local');
    expect(messages.join('\n')).toContain('Echo nested values');
    expect(messages.join('\n')).not.toContain('selected-value');
    expect(messages.join('\n')).not.toContain('fixture-secret-stderr');
    replies = [
      '1',
      '5',
      fixture.config.instance,
      fixture.config.profile,
      'yes',
    ];
    await run();
    expect(owner.hosts.list()).toEqual([]);
  } finally {
    await owner.close();
    await fixture.close();
  }
});

test('guided controls fence the last confirmation after account/socket loss', async () => {
  const fixture = await nativeFixture();
  const commands: NativeCommand[] = [],
    replies = ['1', '4', fixture.file, 'yes'];
  const owner = new NativeHostCommands(
    fixture.config.owner,
    new NativeMcpSessions({ NATIVE_SELECTED: 'selected-value' }),
  );
  let current = true;
  try {
    await mcpControls({
      prompt: {
        show() {},
        read: async () => {
          const reply = replies.shift();
          if (reply === 'yes') current = false;
          return reply;
        },
      },
      owner: fixture.config.owner,
      project: fixture.config.project,
      current: () => current,
      native: async (command) => {
        commands.push(command);
        return owner.execute(command);
      },
      server: refusedServer(),
      authorize: async () => {},
    });
    expect(commands.some((command) => command.action === 'start')).toBe(false);
    expect((await fixture.seen()).length).toBe(0);
  } finally {
    await owner.close();
    await fixture.close();
  }
});

test('server menu uses owner capability and presents original unconfirmed receipts without reissuing', async () => {
  const replies = ['2', '8', '1'],
    messages: string[] = [];
  const intent = {
    operation: 'provider.connection.save' as const,
    expectedRevision: null,
    project: 'Research',
    connection: '11111111-1111-1111-1111-111111111111',
    requestId: '22222222-2222-2222-2222-222222222222',
  };
  await mcpControls({
    prompt: {
      show: (value) => messages.push(value),
      read: async () => replies.shift(),
    },
    project: 'Research',
    owner: { origin: 'https://fixture.example', account: 'fixture' },
    current: () => true,
    native: async () => {
      throw new Error('Server view must not use local commands');
    },
    server: {
      ...refusedServer(),
      pending: async () => [intent],
      recover: async () => {
        throw new Error('Original receipt missing');
      },
    },
    authorize: async () => {},
  });
  expect(messages.join('\n')).toContain('Server (HTTP)');
  expect(messages.join('\n')).toContain('no mutation was replayed');
});
