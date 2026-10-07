import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import {
  nodeToolHost,
  decodeToolCall,
  toolResultRequestId,
} from '../build/tools.js';

const fixture = JSON.parse(
  await readFile(
    new URL(
      '../../../test-support/contracts/relay-tools.json',
      import.meta.url,
    ),
    'utf8',
  ),
);
const call = decodeToolCall(fixture.cases[0].source);
await test('Node supplies the same tool identities and typed handler context', async () => {
  assert.equal(
    await toolResultRequestId(nodeToolHost, call.invocationId),
    fixture.resultRequestId,
  );
  const result = await nodeToolHost.execute(
    async (context) => {
      assert.equal(context.invocationId, fixture.invocationId);
      return { state: 'COMPLETED', text: 'scope' };
    },
    call,
    new Date(Date.now() + 1000),
  );
  assert.deepEqual(result, { state: 'COMPLETED', text: 'scope' });
});
await test('Node bounds waiting without claiming that external effects stopped', async () => {
  await assert.rejects(
    nodeToolHost.execute(
      () => new Promise(() => {}),
      call,
      new Date(Date.now() + 5),
    ),
    /deadline expired/,
  );
  let executions = 0;
  await assert.rejects(
    nodeToolHost.execute(
      async () => {
        executions++;
        return { state: 'COMPLETED', text: 'unexpected' };
      },
      call,
      new Date(0),
    ),
    /deadline expired/,
  );
  assert.equal(executions, 0);
});
