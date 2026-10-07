import {
  atomicPrivateWrite,
  localLock,
  scopedStateFile,
  privateFile,
  connectionKey,
} from 'plowshare-client-node/connections';
import { readPreferences } from './renderer/preferences.ts';
import type { Preference } from './renderer/preferences.ts';
import { readFile, stat } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { errorCode, isObject } from 'plowshare-client-ts/binding/values';
import {
  Connections,
  canonicalServer,
  userConfigDirectory,
} from 'plowshare-client-node/connections';
import type { NamedConnection } from 'plowshare-client-node/connections';
export interface SavedConnection {
  server: string;
  account: string;
  reconnect: boolean;
  name?: string;
}
export interface ConnectionStore {
  load(): Promise<SavedConnection | undefined>;
  loadPreferences?(
    server: string,
    account: string,
  ): Promise<Preference | undefined>;
  savePreferences?(
    server: string,
    account: string,
    preference: Preference,
  ): Promise<void>;
  save(connection: SavedConnection): Promise<void>;
  list?(): Promise<NamedConnection[]>;
  select?(name: string): Promise<NamedConnection>;
  rename?(name: string, next: string): Promise<void>;
  remove?(name: string): Promise<void>;
}
export function desktopConfigDirectory(
  env: Readonly<Record<string, string | undefined>> = process.env,
) {
  return userConfigDirectory(env);
}
/** Desktop projects and sessions share the Node SDK connection registry. */
export class ConnectionConfig implements ConnectionStore {
  readonly path: string;
  private readonly registry: Connections;
  private readonly directory: string;
  constructor(directory: string) {
    this.directory = directory;
    this.registry = new Connections(directory);
    this.path = this.registry.path;
  }
  async load(): Promise<SavedConnection | undefined> {
    let config = await this.registry.load();
    let exists = true;
    try {
      await stat(this.path);
    } catch (error) {
      if (errorCode(error) !== 'ENOENT') throw error;
      exists = false;
    }
    if (!exists) {
      // Preserve the old preference; only metadata is imported, never a password.
      for (const directory of new Set([
        this.directory,
        ...(this.directory === userConfigDirectory() &&
        process.env['PLOWSHARE_DESKTOP_CONFIG']
          ? [process.env['PLOWSHARE_DESKTOP_CONFIG']]
          : []),
        ...(this.directory === userConfigDirectory() &&
        !process.env['PLOWSHARE_CONFIG_DIR'] &&
        !process.env['PLOWSHARE_DESKTOP_CONFIG']
          ? [
              join(
                process.env['XDG_CONFIG_HOME'] ?? join(homedir(), '.config'),
                'plowshare',
              ),
            ]
          : []),
      ])) {
        let raw: unknown;
        try {
          raw = JSON.parse(
            await readFile(join(directory, 'desktop-connection.json'), 'utf8'),
          );
        } catch (error) {
          if (errorCode(error) === 'ENOENT') continue;
          throw new Error(
            'Legacy desktop connection is unreadable. Preserve it and add a connection explicitly.',
            { cause: error },
          );
        }
        if (
          !isObject(raw) ||
          raw.version !== 1 ||
          typeof raw.server !== 'string' ||
          typeof raw.account !== 'string' ||
          typeof raw.reconnect !== 'boolean' ||
          Object.keys(raw).some(
            (key) =>
              !['version', 'server', 'account', 'reconnect'].includes(key),
          )
        )
          throw new Error(
            'Legacy desktop connection is invalid. Preserve it and add a connection explicitly.',
          );
        await this.registry.initialize({
          name: `${raw.account} @ ${new URL(canonicalServer(raw.server)).host}`,
          server: canonicalServer(raw.server),
          account: raw.account,
          reconnect: raw.reconnect,
        });
        config = await this.registry.load();
        break;
      }
    }
    const row = config.connections.find((row) => row.key === config.selected);
    return (
      row && {
        server: row.server,
        account: row.account,
        reconnect: row.reconnect,
        name: row.name,
      }
    );
  }
  async save(connection: SavedConnection): Promise<void> {
    const server = canonicalServer(connection.server);
    const config = await this.registry.load();
    const identity = config.connections.find(
      (row) => row.server === server && row.account === connection.account,
    );
    await this.registry.put({
      server,
      account: connection.account,
      reconnect: connection.reconnect,
      name:
        connection.name ??
        identity?.name ??
        `${connection.account} @ ${new URL(server).host}`,
    });
  }
  async loadPreferences(
    server: string,
    account: string,
  ): Promise<Preference | undefined> {
    const file = await scopedStateFile(
      server,
      account,
      this.directory,
      'desktop-view.json',
    );
    await privateFile(file);
    let raw: unknown;
    try {
      raw = JSON.parse(await readFile(file, 'utf8'));
    } catch (error) {
      if (errorCode(error) === 'ENOENT') return undefined;
      throw new Error('Saved connection view is unreadable.', { cause: error });
    }
    if (
      !isObject(raw) ||
      raw.version !== 1 ||
      raw.server !== server ||
      raw.account !== account ||
      Object.keys(raw).some(
        (key) => !['version', 'server', 'account', 'preference'].includes(key),
      )
    )
      throw new Error(
        'Saved connection view belongs to another identity or is invalid.',
      );
    return readPreferences({ view: raw.preference }).view;
  }
  async savePreferences(
    server: string,
    account: string,
    preference: Preference,
  ): Promise<void> {
    const validated = readPreferences({ view: preference }).view;
    const file = await scopedStateFile(
      server,
      account,
      this.directory,
      'desktop-view.json',
    );
    await localLock(
      this.directory,
      'view-' + connectionKey(server, account),
      () =>
        atomicPrivateWrite(file, {
          version: 1,
          server,
          account,
          preference: validated,
        }),
    );
  }
  async list() {
    return (await this.registry.load()).connections;
  }
  select(name: string) {
    return this.registry.select(name);
  }
  rename(name: string, next: string) {
    return this.registry.rename(name, next);
  }
  remove(name: string) {
    return this.registry.remove(name);
  }
}
