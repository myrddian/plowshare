import { describe, it, expect, vi } from 'vitest';
import {
  retrievalCommand,
  retrievalWords,
  retrieve,
  describeRetrieval,
} from './retrieval.ts';
import { typed } from './session.ts';

describe('direct retrieval commands', () => {
  it('shares hybrid defaults and explicit scope with CLI and slash commands', () => {
    const command = retrievalCommand(
      ['conversation', 'search', 'old decision'],
      'project',
    );
    expect(command.payload).toEqual({
      q: 'old decision',
      project: 'project',
      mode: 'hybrid',
      offset: 0,
      limit: 10,
    });
    expect(typed('/conversation search "old decision"', 'project')).toEqual({
      kind: 'retrieval',
      command,
    });
    expect(
      retrievalCommand(
        ['conversation', 'search', '--global', 'decision'],
        'project',
      ).payload,
    ).not.toHaveProperty('project');
    expect(typed('/memory navigate historical reason', 'project')).toEqual({
      kind: 'retrieval',
      command: {
        type: 'memory.navigate',
        payload: { project: 'project', question: 'historical reason' },
        json: false,
      },
    });
  });
  it('enforces scope, paging and modes before issuing a request', () => {
    for (const args of [
      ['conversation', 'search'],
      ['conversation', 'search', '--mode', 'bad', 'q'],
      ['conversation', 'search', '--offset', '1', 'q'],
      ['memory', 'navigate', '--mode', 'hybrid', 'q'],
      ['conversation', 'search', '--project', 'p', '--global', 'q'],
      ['conversation', 'search', '--limit', '0', 'q'],
    ])
      expect(() => retrievalCommand(args)).toThrow();
    expect(
      retrievalCommand([
        'conversation',
        'search',
        '--mode',
        'lexical',
        '--offset',
        '1',
        'q',
      ]).payload,
    ).toMatchObject({ offset: 1 });
    expect(retrievalWords('conversation search "why this changed"')).toEqual([
      'conversation',
      'search',
      'why this changed',
    ]);
  });
  it('runs exactly one shared WS operation and displays incomplete coverage', async () => {
    const ask = vi.fn().mockResolvedValue({
      code: 'OK',
      payload: {
        hits: [
          {
            conversationId: 'cnv_old',
            ordinal: 9,
            turnOrdinal: 2,
            kind: 'tool_result',
            snippet: 'historical bytes',
            length: 20000,
            supersededBy: 10,
            handle: 'old-handle',
            rank: 0.5,
            recordedAt: null,
          },
        ],
        total: 1,
        offset: 0,
        limit: 10,
        reach: { searched: 4, ejected: 2, recordedOnly: 1 },
        retrieval: {
          requestedMode: 'hybrid',
          effectiveMode: 'lexical',
          truncated: false,
          totalMeaning: 'bounded snapshot',
          generation: null,
          queryEmbeddingCalls: 1,
          provenance: 'retained entries',
          complete: false,
          fallback: 'embedding unavailable',
          snapshot: 'window',
          coverage: {
            eligible: 4,
            indexed: 1,
            passages: 2,
            pending: 2,
            failed: 1,
            stale: 0,
          },
        },
      },
    });
    const command = retrievalCommand(['conversation', 'search', 'q']);
    const result = await retrieve({ ask }, command);
    expect(ask).toHaveBeenCalledExactlyOnceWith(
      'conversation.search',
      command.payload,
    );
    expect(describeRetrieval(result)).toContain('/trajectory cnv_old');
    expect(describeRetrieval(result)).toContain('complete=false');
    expect(describeRetrieval(result)).toContain('embedding unavailable');
  });
  it('labels navigation cost, provenance and actual global fallback', async () => {
    const ask = vi.fn().mockResolvedValue({
      code: 'OK',
      payload: {
        level: 'fold_summary',
        ids: ['dig_old'],
        text: 'surviving summary',
        complete: true,
        modelCalls: 3,
        retrieval: {
          tier: 'global',
          coverage: null,
          fallback: null,
          generation: null,
          globalFallback: true,
          seedMode: 'tree',
          queryEmbeddingCalls: 1,
        },
      },
    });
    const result = await retrieve(
      { ask },
      retrievalCommand(['memory', 'navigate', 'q'], 'project'),
    );
    expect(describeRetrieval(result)).toContain('global fallback');
    expect(describeRetrieval(result)).toContain('model calls=3');
    expect(describeRetrieval(result)).toContain('dig_old');
  });
});
