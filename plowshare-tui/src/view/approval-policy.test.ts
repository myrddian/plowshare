import { describe, expect, it } from 'vitest';
import { approvalModeCommand } from '../logic/approval-policy.ts';
import { changeApprovalMode } from './approval-policy.ts';
import type { Transport } from 'plowshare-client-ts/operations/direct';

describe('personal approval mode controls', () => {
  it('requires an explicit personal bot for Auto and never guesses a mode', () => {
    expect(approvalModeCommand('/approval-mode smart')).toEqual({
      kind: 'change',
      mode: 'SMART',
      bot: null,
    });
    expect(approvalModeCommand('/approval-mode auto my-bot')).toEqual({
      kind: 'change',
      mode: 'AUTO',
      bot: 'my-bot',
    });
    expect(approvalModeCommand('/approval-mode auto').kind).toBe('usage');
    expect(approvalModeCommand('/approval-mode manual extra').kind).toBe(
      'usage',
    );
  });
  it('preserves rules and the exact saved revision without injecting project or owner', async () => {
    const calls: { type: string; payload: unknown }[] = [];
    const policy = { revision: 7, mode: 'MANUAL', bot: null, rules: [] };
    const capabilities = {
      operations: ['COMMAND'],
      humanHistory: true,
      additionalFileAccess: false,
    };
    const transport: Transport = {
      ask: async (type, payload, options) => {
        calls.push({ type, payload });
        expect(options?.timeoutMs).toBe(2000);
        return {
          code: 'OK',
          payload: {
            policy: type.endsWith('.get')
              ? policy
              : { ...policy, revision: 8, mode: 'SMART' },
            capabilities,
          },
        };
      },
    };
    expect(
      (
        await changeApprovalMode(transport, {
          kind: 'change',
          mode: 'SMART',
          bot: null,
        })
      ).policy.revision,
    ).toBe(8);
    expect(calls).toEqual([
      { type: 'approval.policy.get', payload: {} },
      {
        type: 'approval.policy.save',
        payload: { policy: { ...policy, mode: 'SMART' } },
      },
    ]);
  });
  it('does not retry an uncertain write', async () => {
    const calls: string[] = [];
    const transport: Transport = {
      ask: async (type) => {
        calls.push(type);
        if (type.endsWith('.save')) throw new Error('Connection lost');
        return {
          code: 'OK',
          payload: {
            policy: { revision: 1, mode: 'MANUAL', bot: null, rules: [] },
            capabilities: {
              operations: ['COMMAND'],
              humanHistory: true,
              additionalFileAccess: false,
            },
          },
        };
      },
    };
    await expect(
      changeApprovalMode(transport, {
        kind: 'change',
        mode: 'AUTO',
        bot: 'my-bot',
      }),
    ).rejects.toThrow('Connection lost');
    expect(calls).toEqual(['approval.policy.get', 'approval.policy.save']);
  });
});
