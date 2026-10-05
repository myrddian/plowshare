import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { CommandEntry } from 'plowshare-client-ts/operations/conversation-replies';
import {
  commandDraft,
  commandOffers,
  composerCommand,
  workflowArguments,
} from './commands.ts';

const skill: CommandEntry = {
  command: '/skill:review',
  aliases: [],
  kind: 'skill',
  name: 'review',
  description: 'Review',
  argumentHint: 'Change to review',
  executor: 'interlocutor',
  mode: null,
  tier: 'PROJECT',
  hash: 'source-hash',
};
await test('a portable skill requires an explicit context selection and preserves draft arguments', () => {
  assert.throws(
    () => commandDraft(skill, '', 'Review\nthis exact change'),
    /Choose/,
  );
  assert.throws(() => commandDraft(skill, 'AUTOMATIC', 'Review'), /Choose/);
  assert.equal(
    commandDraft(skill, 'INHERITED', 'Review\nthis exact change'),
    '/skill:review --mode=INHERITED Review\nthis exact change',
  );
  assert.equal(
    commandDraft({ ...skill, mode: 'NEW' }, '', 'Review'),
    '/skill:review Review',
  );
});
await test('orchestrations use the same draft path without skill context flags', () => {
  assert.equal(
    commandDraft(
      { ...skill, kind: 'orchestration', command: '/orchestration:review' },
      '',
      'Review the plan',
    ),
    '/orchestration:review Review the plan',
  );
});

await test('direct workflow launch accepts editable multiline work and strips only its own command', () => {
  const command = {
    ...skill,
    kind: 'orchestration' as const,
    command: '/orchestration:review',
  };
  assert.equal(
    workflowArguments(command, '/orchestration:review Review\nthis exact plan'),
    'Review\nthis exact plan',
  );
  assert.equal(
    workflowArguments(command, 'Review\nthis exact plan'),
    'Review\nthis exact plan',
  );
  assert.throws(() => workflowArguments(command, '  '), /Describe/);
  assert.throws(() => workflowArguments(skill, 'Review'), /orchestration/);
});

await test('human completion includes runnable skills hidden from the model and only the supplied bot catalog', () => {
  const hidden = { ...skill, agentVisible: false };
  assert.deepEqual(commandOffers('/skill:r', [hidden]), [
    { command: hidden.command, description: hidden.description },
  ]);
  assert.deepEqual(commandOffers('/skill:r', []), []);
  assert.deepEqual(commandOffers('Explain /skill:r', [hidden]), []);
  assert.deepEqual(commandOffers('/skill:review arguments', [hidden]), []);
  assert.ok(
    commandOffers('/', [hidden]).some((row) => row.command === '/help'),
  );
  assert.equal(composerCommand(' /help ')?.name, '/help');
  assert.equal(composerCommand(' / ')?.name, '/');
  assert.equal(composerCommand('Ask about /help'), undefined);
  assert.deepEqual(composerCommand('/skill:review Review\nthis'), {
    name: '/skill:review',
    argumentsText: 'Review\nthis',
  });
});

await test('choosing context after completion updates the draft without nesting commands or mode flags', () => {
  assert.equal(
    commandDraft(skill, 'NEW', '/skill:review Review\nthis'),
    '/skill:review --mode=NEW Review\nthis',
  );
  assert.equal(
    commandDraft(skill, 'NEW', '/skill:review --mode=DIRECT Review'),
    '/skill:review --mode=NEW Review',
  );
  assert.equal(
    commandDraft(
      { ...skill, kind: 'orchestration', command: '/orchestration:review' },
      '',
      '/orchestration:review --mode=NEW Review',
    ),
    '/orchestration:review --mode=NEW Review',
  );
});
