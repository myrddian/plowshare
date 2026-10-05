import { test } from 'node:test';
import assert from 'node:assert/strict';
import { noticeApproval } from './approvals.ts';
import { approvalWire } from '../wire-fixtures.ts';
import type { DesktopState } from '../shared.ts';
import { approvalsOf } from 'plowshare-client-ts/operations/client-views';

const approvals = approvalsOf({
  code: 'OK',
  payload: {
    approvals: [approvalWire({ id: 'first' }), approvalWire({ id: 'second' })],
  },
})!;
const state = { approvals } as DesktopState;

await test('Inbox controls answer the server request identity even if notice prose mentions another approval', () => {
  assert.equal(
    noticeApproval(state, {
      kind: 'approval',
      about: 'approval:second',
      answer: 'Approve running echo first? [first]',
    })?.id,
    'second',
  );
  assert.equal(
    noticeApproval(state, {
      kind: 'approval',
      about: 'question:first',
      answer: 'Approve running echo first? [first]',
    }),
    undefined,
  );
  assert.equal(
    noticeApproval(state, {
      kind: 'notice',
      about: 'approval:first',
      answer: '',
    }),
    undefined,
  );
});

await test('legacy notices require an exact trailing pending approval id and the request wording', () => {
  assert.equal(
    noticeApproval(state, {
      kind: 'approval',
      answer: 'Approve running echo in /fixture? [first]',
    })?.id,
    'first',
  );
  for (const answer of [
    'Approval first continued the run',
    'Approve running echo? [first-other]',
    'Approve running echo? [first] trailing text',
  ]) {
    assert.equal(
      noticeApproval(state, { kind: 'approval', answer }),
      undefined,
    );
  }
});

await test('a settled or absent approval never acquires decision buttons from an older notice', () => {
  const settled = {
    approvals: [{ ...approvals[0], state: 'denied' }],
  } as DesktopState;
  assert.equal(
    noticeApproval(settled, {
      kind: 'approval',
      about: 'approval:first',
      answer: '',
    }),
    undefined,
  );
  assert.equal(
    noticeApproval(state, {
      kind: 'approval',
      about: 'approval:missing',
      answer: '',
    }),
    undefined,
  );
});
