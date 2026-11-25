import { expect, it } from 'vitest';
import type { Outcome } from '../binding/envelope.ts';
import { administrativeReply } from './administrative-replies.ts';
import { commandProblem, parseCommand } from './commands.ts';

it('requires the previewed revision and refuses caller-selected subscription ownership', () => {
  const revision = 'a'.repeat(64);
  expect(parseCommand('personal subscription preview').kind).toBe('request');
  expect(
    commandProblem('personal.subscription.preview', { owner: 'another' }),
  ).toBeDefined();
  expect(
    commandProblem('personal.subscription.activate', { revision }),
  ).toBeDefined();
  expect(
    commandProblem('personal.subscription.activate', {
      revision,
      expectedActiveRevision: '',
    }),
  ).toBeUndefined();
  expect(
    commandProblem('personal.subscription.activate', {
      revision: 'ABCD',
      expectedActiveRevision: '',
    }),
  ).toBeDefined();
  const outcome: Outcome = {
    code: 'OK',
    payload: { revision, activeRevision: '', subscriptions: [], active: [] },
  };
  expect(administrativeReply('personal.subscription.preview', outcome)).toEqual(
    outcome,
  );
});

it('decodes a Personal reaction with a named message route', () => {
  const outcome: Outcome = {
    code: 'OK',
    payload: {
      revision: 'a'.repeat(64),
      activeRevision: '',
      subscriptions: [
        {
          id: 'prices',
          source: {
            kind: 'application-inbox',
            project: 'research',
            copy: 'project',
          },
          handler: { kind: 'message', route: 'personal-alerts' },
          paused: false,
        },
      ],
      active: [],
    },
  };
  expect(administrativeReply('personal.subscription.preview', outcome)).toEqual(
    outcome,
  );
});

it('accepts a Mailbox schedule definition containing a named alert route', () => {
  expect(
    commandProblem('schedule.save', {
      name: 'prices',
      source: 'server',
      overwrite: false,
      definition: {
        version: 1,
        cron: '0 0 9 * * *',
        zone: 'UTC',
        paused: false,
        action: {
          kind: 'agent',
          agent: 'worker',
          name: null,
          input: 'Watch prices',
          mode: null,
        },
        target: {
          kind: 'mailbox',
          project: null,
          conversation: null,
          to: null,
          route: null,
        },
        limits: { maxModelCalls: null, maxTurns: null, queueCap: 1 },
        notifyPersonalBot: { route: 'personal-alerts' },
      },
    }),
  ).toBeUndefined();
});
