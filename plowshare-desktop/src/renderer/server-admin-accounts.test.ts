import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { ServerAccount } from 'plowshare-client-ts/operations/conversation-replies';
import {
  accountStatus,
  creatorActionLabel,
  toggledUpdate,
} from './server-admin-accounts.ts';
import { decodeAdminCall } from '../admin-input.ts';

const account = (projectCreator: boolean): ServerAccount => ({
  handle: 'sam',
  enabled: true,
  serverAdmin: false,
  mustChangePassword: false,
  createdAt: '2026-10-04T00:00:00Z',
  projectCreator,
});

test('account detail states whether the account may create projects', () => {
  assert.match(accountStatus(account(true)), /May create projects/);
  assert.match(accountStatus(account(false)), /Cannot create projects/);
  assert.equal(creatorActionLabel(account(true)), 'Revoke project creator role');
  assert.equal(creatorActionLabel(account(false)), 'Allow creating projects');
});

test('the creator toggle sends only the flipped projectCreator field', () => {
  for (const held of [true, false]) {
    const update = toggledUpdate(account(held), 'projectCreator');
    assert.deepEqual(update, { handle: 'sam', projectCreator: !held });
    assert.deepEqual(decodeAdminCall('admin.account.update', update).payload, update);
  }
});
