import assert from 'node:assert/strict';
import test from 'node:test';
import {
  createHostDiagnostics,
  formatHostDiagnostic,
} from './host-diagnostics.ts';
await test('fixed edge diagnostics deduplicate attention and never interpolate forged values', () => {
  const output: string[] = [];
  const diagnostics = createHostDiagnostics((line) => output.push(line));
  for (let i = 0; i < 1000; i++)
    diagnostics.emit({
      stage: 'authentication',
      code: 'AUTHENTICATION_ATTENTION',
    });
  assert.equal(output.length, 1);
  const invalid = { stage: 'private-secret', code: 'TOKEN' };
  const result = formatHostDiagnostic(invalid);
  assert.ok(!result.includes('secret'));
  assert.ok(!result.includes('TOKEN'));
  assert.ok(result.length < 256);
});

await test('writer failure is explicit and prevents future writes without throwing', () => {
  let writes = 0;
  const diagnostics = createHostDiagnostics(() => {
    writes++;
    throw new Error('private sink failure');
  });
  assert.doesNotThrow(() =>
    diagnostics.emit({ stage: 'serving', code: 'READY' }),
  );
  assert.equal(diagnostics.unavailable, true);
  diagnostics.emit({ stage: 'cleanup', code: 'STOPPED' });
  assert.equal(writes, 1);
});
