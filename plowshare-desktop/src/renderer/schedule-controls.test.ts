import assert from 'node:assert/strict';
import { test } from 'node:test';
import { demoState } from '../demo.ts';
import { schedulePagingControls } from './schedule-controls.ts';

await test('independent schedule page controls are labelled and expose retained-page errors safely', () => {
  const state = demoState();
  state.connected = true;
  state.activity.schedules = {
    schedules: [],
    triggers: [],
    firings: [],
    schedulePage: { number: 2, more: true },
    filePage: { number: 1, more: false, error: '<script>refused</script>' },
  };
  const schedules = schedulePagingControls(state, 'schedules');
  assert.match(schedules, /aria-label="Schedules pages"/);
  assert.match(schedules, /aria-label="Previous schedules"/);
  assert.match(schedules, /aria-label="More schedules"/);
  assert.match(schedules, /data-schedule-section="schedules"/);
  assert.doesNotMatch(schedules, / disabled /);
  const files = schedulePagingControls(state, 'files');
  assert.match(files, /aria-label="Schedule files pages"/);
  assert.match(files, /role="alert"/);
  assert.match(files, /&lt;script&gt;/);
  assert.doesNotMatch(files, /<script>/);
  assert.equal((files.match(/ disabled /g) ?? []).length, 3);
});
