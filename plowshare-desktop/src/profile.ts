import { join } from 'node:path';

/** Installed applications can be read-only; state belongs to the user. */
export function desktopProfile(
  packaged: boolean,
  application: string,
  appData: string,
  override?: string,
): string {
  return (
    override ??
    (packaged
      ? join(appData, 'Plowshare')
      : join(application, 'build', 'profile'))
  );
}
