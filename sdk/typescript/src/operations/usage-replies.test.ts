import { describe, expect, it } from 'vitest';
import { parseCommand } from './commands.ts';
import type { request } from './direct.ts';
import { resultOf } from './direct.ts';
import { USAGE_OPERATIONS } from './usage.ts';

describe('authenticated usage ledger reads', () => {
  it('uses named project and explicit bounds for combined processing/analysis measurements', () => {
    expect(
      parseCommand(
        'usage project {"from":"2026-10-02T00:00:00Z","to":"2026-10-02T00:10:00Z","group_by":["operation"]}',
        'isolated',
      ),
    ).toMatchObject({
      kind: 'request',
      request: {
        type: 'usage.project',
        payload: { project: 'isolated', group_by: ['operation'] },
      },
    });
    expect(
      parseCommand(
        'usage orchestration {"orchestration":"orc_one","scope":"personal"}',
      ).kind,
    ).toBe('usage');
  });
  it.each(USAGE_OPERATIONS)(
    '%s preserves exact decimal strings and incomplete usage markers',
    (type) => {
      const payload = {
        filters: { type, filter: {} },
        cursor: null,
        health: {
          watermark: '1',
          as_of: '2026-10-02T00:00:00Z',
          capture_enabled: true,
          historical_usage: 'not_imported',
        },
        ...(type === 'usage.calls'
          ? { calls: [] }
          : {
              totals: {
                calls: '1',
                attempts: '1',
                active_calls: '0',
                incomplete_attempts: '1',
                unknown_cost_attempts: '1',
                input_tokens: '9007199254740993',
                output_tokens: '0',
                input_tokens_known: '1',
                output_tokens_known: '0',
                costs: {},
                usage_complete: false,
                cost_complete: false,
                complete: false,
              },
              groups: [],
            }),
      };
      const asked = { type, payload: {} } as ReturnType<typeof request>;
      expect(resultOf(asked, { code: 'OK', payload })).toEqual({
        kind: 'completed',
        outcome: { code: 'OK', payload },
      });
      expect(resultOf(asked, { code: 'OK', payload: {} }).kind).toBe(
        'invalid-response',
      );
      expect(
        resultOf(asked, { code: 'BAD_REQUEST', said: 'unavailable' }).kind,
      ).toBe('refused');
    },
  );
});

describe('constructed context snapshots', () => {
  const payload = {
    conversation: 'conversation',
    agent: 'agent',
    projection: 'next',
    captured_at: '2026-10-03T00:00:00Z',
    model: 'fixture',
    sampling: {},
    messages: [
      {
        role: 'system',
        parts: [{ type: 'text', text: 'Exact system block' }],
        tool_calls: [],
        tool_call_id: null,
      },
    ],
    tools: [],
    count: null,
  };
  const asked = {
    type: 'conversation.context.snapshot',
    payload: { conversation: 'conversation', agent: 'agent' },
  } as ReturnType<typeof request>;
  it('retains a complete projection and refuses partial content', () => {
    expect(resultOf(asked, { code: 'OK', payload })).toEqual({
      kind: 'completed',
      outcome: { code: 'OK', payload },
    });
    for (const key of Object.keys(payload)) {
      const incomplete = { ...payload } as Record<string, unknown>;
      delete incomplete[key];
      expect(resultOf(asked, { code: 'OK', payload: incomplete }).kind).toBe(
        'invalid-response',
      );
    }
    expect(
      resultOf(asked, {
        code: 'OK',
        payload: { ...payload, messages: [{ role: 'tool', parts: [] }] },
      }).kind,
    ).toBe('invalid-response');
  });
});
