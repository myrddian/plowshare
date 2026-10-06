import { test, expect, vi } from 'vitest';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Connections } from 'plowshare-client-node/connections';
import { run } from './main.ts';

test('TUI management uses the shared registry and selection fails before creating a terminal for an unknown connection', async () => {
  const root = await mkdtemp(join(tmpdir(), 'plowshare-tui-connections-'));
  vi.stubEnv('PLOWSHARE_CONFIG_DIR', root);
  vi.stubEnv('PLOWSHARE_DESKTOP_CONFIG', '');
  const write = vi
    .spyOn(process.stdout, 'write')
    .mockImplementation(() => true);
  try {
    await run([
      'connection',
      'add',
      'Terminal account',
      'https://fixture.example',
      'alice',
    ]);
    const registry = new Connections(root);
    expect((await registry.load()).connections[0]?.name).toBe(
      'Terminal account',
    );
    await run(['connection', 'rename', 'Terminal account', 'Renamed terminal']);
    await run(['connection', 'select', 'Renamed terminal']);
    expect((await registry.load()).selected).toBe(
      (await registry.load()).connections[0]?.key,
    );
    await expect(run(['--connection', 'Missing'])).rejects.toThrow(
      'Unknown named connection',
    );
    await run(['connection', 'remove', 'Renamed terminal']);
    expect((await registry.load()).connections).toEqual([]);
    expect(write).toHaveBeenCalled();
  } finally {
    write.mockRestore();
    vi.unstubAllEnvs();
    await rm(root, { recursive: true, force: true });
  }
});
