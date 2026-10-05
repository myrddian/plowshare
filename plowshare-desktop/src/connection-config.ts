import { errorCode } from 'plowshare-client-ts/binding/values';
import { isObject } from 'plowshare-client-ts/binding/values';
import { mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { homedir } from 'node:os';
import { randomUUID } from 'node:crypto';

export interface SavedConnection {
  server: string;
  account: string;
  reconnect: boolean;
}
export interface ConnectionStore {
  load(): Promise<SavedConnection | undefined>;
  save(connection: SavedConnection): Promise<void>;
}
export function desktopConfigDirectory(
  env: Readonly<Record<string, string | undefined>> = process.env,
) {
  return (
    env['PLOWSHARE_DESKTOP_CONFIG'] ??
    env['PLOWSHARE_CONFIG_DIR'] ??
    join(env['XDG_CONFIG_HOME'] ?? join(homedir(), '.config'), 'plowshare')
  );
}
function valid(value: unknown): value is SavedConnection {
  if (!value || typeof value !== 'object') return false;
  const row = value as SavedConnection;
  if (
    typeof row.server !== 'string' ||
    typeof row.account !== 'string' ||
    !row.account.trim() ||
    typeof row.reconnect !== 'boolean'
  )
    return false;
  try {
    const url = new URL(row.server);
    return (
      ['http:', 'https:'].includes(url.protocol) &&
      !url.username &&
      !url.password &&
      !url.search &&
      !url.hash &&
      url.pathname === '/' &&
      url.origin === row.server
    );
  } catch {
    return false;
  }
}
/** Last selected server/account only. Tokens stay in the shared credential store. */
export class ConnectionConfig implements ConnectionStore {
  readonly path: string;
  private writes = Promise.resolve();
  private directory: string;
  constructor(directory: string) {
    this.directory = directory;
    this.path = join(directory, 'desktop-connection.json');
  }
  async load(): Promise<SavedConnection | undefined> {
    await this.writes;
    let data: string;
    try {
      data = await readFile(this.path, 'utf8');
    } catch (error) {
      if (errorCode(error) === 'ENOENT') return undefined;
      throw error;
    }
    const value: unknown = JSON.parse(data);
    if (!isObject(value) || value.version !== 1 || !valid(value))
      throw new Error(
        'Saved desktop connection is invalid. Connect to your server again.',
      );
    return {
      server: value.server,
      account: value.account,
      reconnect: value.reconnect,
    };
  }
  save(connection: SavedConnection) {
    if (!valid(connection))
      return Promise.reject(new Error('Invalid desktop connection.'));
    const { server, account, reconnect } = connection;
    const next = this.writes.then(async () => {
      await mkdir(this.directory, { recursive: true, mode: 0o700 });
      const temporary = `${this.path}.${randomUUID()}.tmp`;
      try {
        await writeFile(
          temporary,
          JSON.stringify({ version: 1, server, account, reconnect }) + '\n',
          { mode: 0o600, flag: 'wx' },
        );
        await rename(temporary, this.path);
      } finally {
        await rm(temporary, { force: true });
      }
    });
    this.writes = next.catch(() => undefined);
    return next;
  }
}
