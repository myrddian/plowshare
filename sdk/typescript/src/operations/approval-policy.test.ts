import { describe, expect, it } from 'vitest';
import type { Outcome } from '../binding/envelope.ts';
import { commandProblem, parseCommand } from './commands.ts';
import { administrativeReply } from './administrative-replies.ts';
import {
  exactApprovalPolicy,
  isApprovalPolicy,
  type ApprovalPolicy,
  type ApprovalRule,
  type ApprovalCapabilities,
} from './approval-policy.ts';

const manual: ApprovalPolicy = {
  revision: 0,
  mode: 'MANUAL',
  bot: null,
  rules: [],
};
const rule: ApprovalRule = {
  id: 'git-inspect',
  effect: 'MAY_APPROVE',
  kind: 'COMMAND',
  project: 'atlas',
  provider: 'fixture-machine/session',
  cwd: '/fixture',
  inputDigest: null,
  argv: ['git', 'status'],
  paths: [],
  guidance: null,
};
const capabilities: ApprovalCapabilities = {
  operations: ['COMMAND'],
  humanHistory: false,
  additionalFileAccess: false,
};

describe('account approval policy boundary', () => {
  it('provides the same typed controls to terminal and graphical clients', () => {
    expect(parseCommand('approval policy get').kind).toBe('request');
    expect(
      commandProblem('approval.policy.get', { owner: 'another-user' }),
    ).toBeDefined();
    expect(
      commandProblem('approval.policy.save', { policy: manual }),
    ).toBeUndefined();
    const outcome: Outcome = {
      code: 'OK',
      payload: { policy: manual, capabilities },
    };
    expect(administrativeReply('approval.policy.get', outcome)).toEqual(
      outcome,
    );
  });

  it('rejects invalid modes, duplicate rules, missing bot, unsafe revisions and unknown permission fields', () => {
    for (const invalid of [
      { ...manual, mode: 'anything' },
      { ...manual, mode: 'AUTO' },
      { ...manual, revision: 0.5 },
      { ...manual, revision: '0' },
      { ...manual, revision: Number.MAX_SAFE_INTEGER + 1 },
      { ...manual, rules: [rule, rule] },
      { ...manual, rules: [{ ...rule, provider: null }] },
      { ...manual, rules: [{ ...rule, cwd: null }] },
      { ...manual, rules: [{ ...rule, inputDigest: 'truncated' }] },
      { ...manual, rules: [{ ...rule, allowAll: true }] },
      { ...manual, owner: 'another-user' },
    ]) {
      expect(exactApprovalPolicy(invalid)).toBe(false);
      expect(
        commandProblem('approval.policy.save', { policy: invalid }),
      ).toBeDefined();
    }
    expect(isApprovalPolicy({ ...manual, future: true })).toBe(true);
    expect(exactApprovalPolicy({ ...manual, future: true })).toBe(false);
  });

  it('preserves whole arguments, including an empty argument, without widening path or effect scope', () => {
    expect(
      exactApprovalPolicy({
        ...manual,
        rules: [{ ...rule, argv: ['echo', ''] }],
      }),
    ).toBe(true);
    expect(
      exactApprovalPolicy({ ...manual, rules: [{ ...rule, argv: [''] }] }),
    ).toBe(false);
    expect(
      exactApprovalPolicy({
        ...manual,
        rules: [{ ...rule, paths: ['/other'] }],
      }),
    ).toBe(false);
    expect(
      exactApprovalPolicy({
        ...manual,
        rules: [{ ...rule, kind: 'FILE_READ', paths: ['/fixture'], argv: [] }],
      }),
    ).toBe(true);
  });
});
