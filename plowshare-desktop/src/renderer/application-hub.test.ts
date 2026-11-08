import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  scheduleNamesProject,
  scheduledIdForProject,
} from './application-hub.ts';

const file = (internalName: string, project: string | null) => ({
  internalName,
  project,
});
const id = (kind: 'schedule' | 'trigger', name: string) =>
  JSON.stringify([kind, name]);

const files = [
  file('scheduled-1-morning', 'Managed app'),
  file('scheduled-2-digest', 'Other'),
];
const schedules = [{ name: 'scheduled-1-morning' }, { name: 'morning' }];
const triggers = [
  { name: 'wake', project: 'Managed app' },
  { name: 'other', project: 'Other' },
  { name: 'global', project: null },
];

await test('file-backed schedules match internalName and not the file stem', () => {
  assert.equal(
    scheduleNamesProject(
      'Managed app',
      'schedule',
      { name: 'scheduled-1-morning' },
      files,
    ),
    true,
  );
  assert.equal(
    scheduleNamesProject('Managed app', 'schedule', { name: 'morning' }, files),
    false,
  );
  assert.equal(
    scheduleNamesProject(
      'Managed app',
      'schedule',
      { name: 'scheduled-2-digest' },
      files,
    ),
    false,
  );
  assert.equal(
    scheduleNamesProject(
      'Managed app',
      'trigger',
      { name: 'wake', project: 'Managed app' },
      files,
    ),
    true,
  );
  assert.equal(
    scheduleNamesProject(
      'Managed app',
      'trigger',
      { name: 'other', project: 'Other' },
      files,
    ),
    false,
  );
  assert.equal(
    scheduleNamesProject(
      'Managed app',
      'trigger',
      { name: 'global', project: null },
      files,
    ),
    false,
  );
  assert.equal(
    scheduleNamesProject('', 'schedule', { name: 'morning' }, files),
    true,
  );
});

await test('an Application filter drops a selection the list no longer shows', () => {
  const kept = id('schedule', 'scheduled-1-morning');
  const stem = id('schedule', 'morning');
  const wake = id('trigger', 'wake');
  const hidden = id('trigger', 'other');
  const missing = id('schedule', 'absent');
  assert.equal(
    scheduledIdForProject(kept, 'Managed app', schedules, triggers, files),
    kept,
  );
  assert.equal(
    scheduledIdForProject(stem, 'Managed app', schedules, triggers, files),
    '',
  );
  assert.equal(
    scheduledIdForProject(wake, 'Managed app', schedules, triggers, files),
    wake,
  );
  assert.equal(
    scheduledIdForProject(hidden, 'Managed app', schedules, triggers, files),
    '',
  );
  assert.equal(
    scheduledIdForProject(stem, '', schedules, triggers, files),
    stem,
  );
  assert.equal(
    scheduledIdForProject('', 'Managed app', schedules, triggers, files),
    '',
  );
  assert.equal(
    scheduledIdForProject(
      'not-json',
      'Managed app',
      schedules,
      triggers,
      files,
    ),
    'not-json',
  );
  assert.equal(
    scheduledIdForProject(missing, 'Managed app', schedules, triggers, files),
    missing,
  );
});
