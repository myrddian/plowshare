import type { ServerAccount } from 'plowshare-client-ts/operations/conversation-replies';

/** Fields of an account that the administration dialog toggles with one click. */
export type AccountToggle = 'enabled' | 'serverAdmin' | 'projectCreator';

/** One-line status shown for the selected account. */
export function accountStatus(account: ServerAccount): string {
  const creation = account.projectCreator
    ? account.enabled
      ? 'May create projects'
      : 'Project creator role held; creation blocked while disabled'
    : 'Cannot create projects';
  return `${account.enabled ? 'Enabled' : 'Disabled'} · ${account.serverAdmin ? 'Server administrator' : 'Regular user'} · ${creation}${account.mustChangePassword ? ' · Password change required' : ''}`;
}

/** Label of the button that flips the project creator role. */
export function creatorActionLabel(account: ServerAccount): string {
  return account.projectCreator
    ? 'Revoke project creator role'
    : 'Allow creating projects';
}

/** Update payload that flips one toggled field; other fields stay untouched on the server. */
export function toggledUpdate(
  account: ServerAccount,
  field: AccountToggle,
): { handle: string } & Partial<Record<AccountToggle, boolean>> {
  return { handle: account.handle, [field]: !account[field] };
}
