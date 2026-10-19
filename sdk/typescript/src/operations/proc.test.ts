import { describe, it, expect, vi } from 'vitest';
import { decodePayload, decodeReply } from './schema.ts';
import { ProcClient, procText } from './proc.ts';

const selection = {
  visibility: 'mine',
  project: null,
  conversation: null,
  root: null,
  causation: null,
  active: null,
  after: null,
  limit: 50,
};
const page = {
  snapshotAt: '2026-10-19T01:00:00Z',
  selection,
  conversations: [],
  total: 0,
  cursor: null,
  coverage: [],
};
describe('PROC boundary', () => {
  it('rejects malformed selectors before sending any request', async () => {
    const ask = vi.fn();
    const client = new ProcClient({ ask });
    await expect(client.read('proc.list', { limit: 0 })).rejects.toThrow();
    await expect(client.read('proc.inspect', {})).rejects.toThrow();
    await expect(
      client.read('proc.list', { conversation: 'x\0y' }),
    ).rejects.toThrow();
    expect(ask).not.toHaveBeenCalled();
    for (const value of [
      { admin: true },
      { visibility: 'admin' },
      { active: 'true' },
      { after: null },
      { limit: 1.5 },
    ])
      expect(() => decodePayload('proc.list', value)).toThrow();
  });
  it('accepts explicit fleet visibility and bounded causal selections', () => {
    expect(
      decodePayload('proc.inspect', {
        visibility: 'server',
        causation: 'chain-1',
        limit: 100,
      }),
    ).toEqual({ visibility: 'server', causation: 'chain-1', limit: 100 });
    expect(decodeReply('proc.list', page).total).toBe(0);
  });
  it('refuses malformed measurements and omits unknown response fields', () => {
    expect(() => decodeReply('proc.list', { ...page, total: -1 })).toThrow();
    expect(() =>
      decodeReply('proc.list', { ...page, snapshotAt: 'yesterday' }),
    ).toThrow();
    expect(() =>
      decodeReply('proc.list', { ...page, snapshotAt: '12' }),
    ).toThrow();
    expect(
      decodeReply('proc.list', {
        ...page,
        privateContent: 'must not cross boundary',
      }),
    ).toEqual(page);
  });
  it('keeps unavailable reads explicit without reporting an empty successful list', async () => {
    const client = new ProcClient({
      ask: () =>
        Promise.resolve({ code: 'BAD_REQUEST', said: 'not authorized' }),
    });
    await expect(client.read('proc.list')).rejects.toThrow('not authorized');
    expect(procText(decodeReply('proc.list', page))).toContain(
      '0 authorized non-archived',
    );
  });
});
