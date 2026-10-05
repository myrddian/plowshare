import { afterEach, describe, expect, it, vi } from 'vitest';
import { run } from './main.ts';

afterEach(() => vi.unstubAllEnvs());
describe('TUI configuration boundary', () => {
  it('requires a server origin before mounting a terminal or opening credentials', async () => {
    vi.stubEnv('PLOWSHARE_URL', '');
    await expect(run()).rejects.toThrow('Set PLOWSHARE_URL');
  });
});
