import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { taskSnapshot } from './messaging-codec.ts';
import { task } from './adapter.test-support.ts';

function snapshot() {
  const { revision, cursor: _cursor, ...header } = task();
  return {
    schema: 'plowshare-message-task/1',
    requestId: randomUUID(),
    sourceEvent: randomUUID(),
    client: 'discord_fixture',
    revision,
    task: header,
    refusal: null,
    next: null,
    resume: 'follow-cursor',
  };
}
await test('generic messaging decoder rejects unknown, duplicate and malformed nested fields', () => {
  const valid = snapshot();
  assert.equal(taskSnapshot(JSON.stringify(valid)).task?.id, valid.task.id);
  for (const invalid of [
    { ...valid, extra: true },
    { ...valid, revision: 1 },
    { ...valid, revision: '0' },
    { ...valid, task: { ...valid.task, id: 'not-a-uuid' } },
    { ...valid, task: { ...valid.task, body: 'untrusted' } },
    { ...valid, next: 'cursor', resume: 'cursor' },
    {
      ...valid,
      task: {
        ...valid.task,
        replies: [
          {
            id: 'reply',
            body: 'text',
            offset: '1',
            complete: 'true',
            finalReply: false,
            generated: false,
            ending: null,
            postedAt: '2026-01-01T00:00:00Z',
          },
        ],
      },
    },
  ])
    assert.throws(() => taskSnapshot(JSON.stringify(invalid)));
  assert.throws(() =>
    taskSnapshot(
      JSON.stringify(valid).replace(
        '"schema":',
        '"schema":"duplicate","schema":',
      ),
    ),
  );
});
await test('negative snapshots retain correlation before a caller decides whether they are its outcome', () => {
  const valid = snapshot();
  const result = taskSnapshot(
    JSON.stringify({
      ...valid,
      task: null,
      revision: null,
      resume: null,
      refusal: 'NOT_ACCESSIBLE',
    }),
  );
  assert.equal(result.task, null);
  assert.equal(result.refusal, 'NOT_ACCESSIBLE');
  assert.equal(result.requestId, valid.requestId);
  assert.throws(() =>
    taskSnapshot(
      JSON.stringify({
        ...valid,
        task: null,
        revision: null,
        resume: null,
        refusal: 'FOREIGN_DETAILS',
      }),
    ),
  );
});
await test('reply chunks validate codepoint bounds and preserve Unicode exactly', () => {
  const valid = snapshot();
  const chunk = {
    id: 'bdm_Reply',
    body: '😀'.repeat(16384),
    offset: '0',
    complete: false,
    finalReply: true,
    generated: false,
    ending: null,
    postedAt: '2026-01-01T00:00:00Z',
  };
  const decoded = taskSnapshot(
    JSON.stringify({
      ...valid,
      next: 'next-cursor',
      resume: null,
      task: { ...valid.task, replies: [chunk] },
    }),
  );
  assert.equal(decoded.task?.replies[0]?.body, chunk.body);
  assert.throws(() =>
    taskSnapshot(
      JSON.stringify({
        ...valid,
        task: {
          ...valid.task,
          replies: [{ ...chunk, body: chunk.body + 'x' }],
        },
      }),
    ),
  );
});
