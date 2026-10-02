import { test } from 'node:test';
import assert from 'node:assert/strict';
import { desktopProfile } from './profile.ts';

test('installed profile stays outside the application, including an asar path', () => {
  assert.equal(desktopProfile(true, '/Applications/Plowshare.app/Contents/Resources/app.asar', '/Users/fixture/Library/Application Support'), '/Users/fixture/Library/Application Support/Plowshare');
  assert.equal(desktopProfile(false, '/development/desktop', '/user'), '/development/desktop/build/profile');
  assert.equal(desktopProfile(true, '/application', '/user', '/isolated/profile'), '/isolated/profile');
});
