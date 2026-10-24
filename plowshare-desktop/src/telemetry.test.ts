import { strict as assert } from 'node:assert';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { Telemetry } from './telemetry.ts';

const COLUMNS = 'issuedAt,surface,action,depth,serverMs,replyMs,renderMs,ok';

const row = {
  surface: 'library',
  action: 'information',
  depth: 2,
  replyMs: 31.4,
  renderMs: 48.2,
  serverMs: 12.5,
  ok: true,
};

function scratch() {
  return mkdtempSync(join(tmpdir(), 'plowshare-telemetry-'));
}

await test('telemetry stays off unless the environment names a file', () => {
  for (const environment of [{}, { PLOWSHARE_DESKTOP_TELEMETRY: '   ' }]) {
    const telemetry = new Telemetry(environment);
    assert.equal(telemetry.enabled, false);
    // Recording while off must not create anything, including a header.
    assert.doesNotThrow(() => telemetry.record(row));
  }
});

await test('a relative path is refused rather than silently resolved', () => {
  // A diagnostic that writes relative to wherever the app was started is worse than one that
  // refuses: the file would appear somewhere nobody looks.
  assert.throws(
    () => new Telemetry({ PLOWSHARE_DESKTOP_TELEMETRY: 'telemetry.csv' }),
    /absolute path/,
  );
});

await test('the header is written once and rows append under it', () => {
  const path = join(scratch(), 'client.csv');
  const telemetry = new Telemetry({ PLOWSHARE_DESKTOP_TELEMETRY: path });
  assert.equal(telemetry.enabled, true);
  telemetry.record(row);
  telemetry.record({ ...row, action: 'library', depth: 0 });

  const lines = readFileSync(path, 'utf8').trimEnd().split('\n');
  assert.equal(lines.length, 3);
  assert.equal(
    lines[0],
    'issuedAt,surface,action,depth,serverMs,replyMs,renderMs,ok',
  );
  const first = lines[1]!.split(',');
  assert.equal(first.length, 8);
  assert.deepEqual(first.slice(1), [
    'library',
    'information',
    '2',
    '12.5',
    '31.4',
    '48.2',
    'true',
  ]);
  assert.match(first[0]!, /^\d{4}-\d{2}-\d{2}T/);
  assert.equal(lines[2]!.split(',')[2], 'library');
});

await test('a missing directory is created, and an existing file keeps its header', () => {
  const path = join(scratch(), 'nested', 'deeper', 'client.csv');
  new Telemetry({ PLOWSHARE_DESKTOP_TELEMETRY: path }).record(row);
  assert.equal(readFileSync(path, 'utf8').trimEnd().split('\n').length, 2);
  // A second writer against the same file must not repeat the header.
  new Telemetry({ PLOWSHARE_DESKTOP_TELEMETRY: path }).record(row);
  const lines = readFileSync(path, 'utf8').trimEnd().split('\n');
  assert.equal(lines.length, 3);
  assert.equal(lines.filter((line) => line.startsWith('issuedAt')).length, 1);
});

await test('an absent measurement is empty rather than zero', () => {
  // A backgrounded window is never asked to paint, and zero would read as "rendered instantly".
  const path = join(scratch(), 'client.csv');
  new Telemetry({ PLOWSHARE_DESKTOP_TELEMETRY: path }).record({
    ...row,
    renderMs: null,
    serverMs: null,
  });
  const fields = readFileSync(path, 'utf8')
    .trimEnd()
    .split('\n')[1]!
    .split(',');
  assert.equal(fields[4], '');
  assert.equal(fields[6], '');
});

await test('a field that could break the row shape is quoted', () => {
  const path = join(scratch(), 'client.csv');
  new Telemetry({ PLOWSHARE_DESKTOP_TELEMETRY: path }).record({
    ...row,
    action: 'odd,"action"\nwith breaks',
  });
  const text = readFileSync(path, 'utf8');
  assert.match(text, /"odd,""action""\nwith breaks"/);
  // One record, however many newlines it contains: the quoted field keeps it one CSV row.
  assert.equal(text.split('\n')[0], COLUMNS);
});

await test('a write failure disables the writer and is reported once', () => {
  // A directory where the file should be: every append fails.
  const directory = scratch();
  const warnings: string[] = [];
  const telemetry = new Telemetry(
    { PLOWSHARE_DESKTOP_TELEMETRY: join(directory, 'taken') },
    (message) => warnings.push(message),
  );
  writeFileSync(join(directory, 'placeholder'), '');
  // Point at the directory itself, which cannot be appended to as a file.
  const blocked = new Telemetry(
    { PLOWSHARE_DESKTOP_TELEMETRY: directory },
    (message) => warnings.push(message),
  );
  for (let attempt = 0; attempt < 3; attempt += 1) blocked.record(row);
  assert.equal(warnings.length, 1);
  assert.match(warnings[0]!, /telemetry is off/);
  // Telemetry must never fail the interaction it only observes.
  assert.doesNotThrow(() => blocked.record(row));
  assert.equal(telemetry.enabled, true);
});
