import { test } from 'node:test';
import assert from 'node:assert/strict';
import { noticeApproval, installApprovalControls } from './approvals.ts';
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

await test('budget prompts show the grant and reason with only exact once/deny buttons', () => {
  const host = { addEventListener() {} } as unknown as Document;
  const controls = installApprovalControls(
    host,
    () => state,
    async () => {
      throw new Error('No click expected');
    },
    () => {},
  );
  const html = controls.prompt({
    ...approvals[0]!,
    command: [],
    budget: { calls: 8, steps: 4 },
    reason: '<review the evidence>',
  });
  assert.match(html, /8 additional model calls/);
  assert.match(html, /4 additional steps/);
  assert.match(html, /&lt;review the evidence&gt;/);
  assert.match(html, /data-decision="once"/);
  assert.match(html, /data-decision="deny"/);
  assert.doesNotMatch(html, /data-decision="(?:conversation|project)"/);
});

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

await test('typed file prompts escape the request and explain its exact once-only scope', () => {
  const host = { addEventListener() {} } as unknown as Document;
  const controls = installApprovalControls(
    host,
    () => state,
    async () => {
      throw new Error('No click expected');
    },
    () => {},
  );
  const html = controls.prompt({
    ...approvals[0]!,
    command: [],
    file: {
      kind: 'FILE_WRITE',
      provider: 'remote:session',
      paths: ['/repo/<config>'],
      inputDigest: 'a'.repeat(64),
      inputPreview: '<script>untrusted</script>',
    },
    reason: 'Inspection does not request a write.',
  });
  assert.match(html, /File operation approval/);
  assert.match(html, /FILE_WRITE/);
  assert.match(html, /&lt;script&gt;untrusted&lt;\/script&gt;/);
  assert.match(html, /Inspection does not request a write/);
  assert.doesNotMatch(html, /data-decision="(?:conversation|project)"/);
});
