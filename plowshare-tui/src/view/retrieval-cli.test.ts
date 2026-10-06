import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
const fixture = vi.hoisted(() => ({
  authenticate: vi.fn(),
  ask: vi.fn(),
  close: vi.fn(),
}));
vi.mock('plowshare-client-node/session', () => ({
  authenticateConfigured: fixture.authenticate,
}));
import { retrievalCli } from './retrieval-cli.ts';

beforeEach(() => {
  vi.clearAllMocks();
  fixture.authenticate.mockResolvedValue({
    ask: fixture.ask,
    close: fixture.close,
  });
});
describe('retrieval CLI without a terminal or selected agent', () => {
  it('gives help without authenticating and usage errors have status 2', async () => {
    const write = vi.fn(),
      error = vi.fn();
    expect(
      await retrievalCli(['memory', 'navigate', '--help'], {}, write, error),
    ).toBe(0);
    expect(fixture.authenticate).not.toHaveBeenCalled();
    expect(write).toHaveBeenCalledWith(
      expect.stringContaining('separate system allowance') as unknown,
    );
    expect(
      await retrievalCli(['conversation', 'search'], {}, write, error),
    ).toBe(2);
  });
  it('requires an origin when there is no saved connection', async ({
    onTestFinished,
  }) => {
    const config = await mkdtemp(
      join(tmpdir(), 'plowshare-tui-no-connections-'),
    );
    onTestFinished(() => rm(config, { recursive: true, force: true }));
    const error = vi.fn();
    expect(
      await retrievalCli(
        ['conversation', 'search', '--json', 'q'],
        { PLOWSHARE_CONFIG_DIR: config },
        vi.fn(),
        error,
      ),
    ).toBe(1);
    expect(fixture.authenticate).not.toHaveBeenCalled();
    expect(error).toHaveBeenCalledWith(
      expect.stringContaining('PLOWSHARE_URL') as unknown,
    );
  });
  it('runs search directly, writes JSON and closes its socket', async () => {
    fixture.ask.mockResolvedValue({
      code: 'OK',
      payload: {
        hits: [],
        total: 0,
        offset: 0,
        limit: 10,
        reach: { searched: 0, ejected: 0, recordedOnly: 0 },
        retrieval: {
          requestedMode: 'hybrid',
          effectiveMode: 'hybrid',
          complete: true,
          truncated: false,
          totalMeaning: 'bounded snapshot',
          snapshot: null,
          fallback: null,
          coverage: null,
          generation: null,
          queryEmbeddingCalls: 1,
          provenance: 'retained entries',
        },
      },
    });
    const write = vi.fn();
    expect(
      await retrievalCli(
        ['conversation', 'search', '--json', 'q'],
        {
          PLOWSHARE_PROJECT: 'p',
          PLOWSHARE_URL: 'https://server.example.test',
        },
        write,
        vi.fn(),
      ),
    ).toBe(0);
    expect(fixture.ask).toHaveBeenCalledExactlyOnceWith('conversation.search', {
      q: 'q',
      mode: 'hybrid',
      project: 'p',
      offset: 0,
      limit: 10,
    });
    expect(JSON.parse(write.mock.calls[0]![0] as string)).toHaveProperty(
      'hits',
      [],
    );
    expect(fixture.close).toHaveBeenCalledOnce();
  });
  it('reports an incomplete navigation with status 3 and service refusals with status 1', async () => {
    fixture.ask.mockResolvedValue({
      code: 'OK',
      payload: {
        level: 'digest',
        ids: ['dig_1'],
        text: 'allowance exhausted',
        complete: false,
        modelCalls: 2,
      },
    });
    expect(
      await retrievalCli(
        ['memory', 'navigate', 'q'],
        { PLOWSHARE_URL: 'https://server.example.test' },
        vi.fn(),
        vi.fn(),
      ),
    ).toBe(3);
    fixture.ask.mockResolvedValue({
      code: 'MODEL_UNAVAILABLE',
      said: 'offline',
    });
    const error = vi.fn();
    expect(
      await retrievalCli(
        ['memory', 'navigate', 'q'],
        { PLOWSHARE_URL: 'https://server.example.test' },
        vi.fn(),
        error,
      ),
    ).toBe(1);
    expect(error).toHaveBeenCalledWith('offline');
    expect(fixture.close).toHaveBeenCalledTimes(2);
  });
});
