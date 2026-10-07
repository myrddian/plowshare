import { decodeFileStoreDefault } from './filestore-config.ts';
import type { FileStoreDefault } from './filestore-config.ts';
import { createHash, randomUUID } from 'node:crypto';
import {
  chmod,
  lstat,
  mkdir,
  open,
  readFile,
  rename,
  rm,
} from 'node:fs/promises';
import { homedir } from 'node:os';
import { basename, isAbsolute, join } from 'node:path';
import { setTimeout as pause } from 'node:timers/promises';
import { errorCode, isObject } from 'plowshare-client-ts/binding/values';

export interface NamedConnection {
  key: string;
  name: string;
  server: string;
  account: string;
  reconnect: boolean;
}
export interface ConnectionConfiguration {
  version: 1;
  selected?: string;
  connections: NamedConnection[];
  fileStoreDefault?: FileStoreDefault;
  /** A FileStore-only config must not suppress the first legacy desktop login migration. */
  connectionSetupPending?: true;
}
export interface ConnectionRegistry {
  load(): Promise<ConnectionConfiguration>;
  put(connection: Omit<NamedConnection, 'key'>): Promise<NamedConnection>;
  select(name: string): Promise<NamedConnection>;
  rename(name: string, nextName: string): Promise<void>;
  remove(name: string): Promise<void>;
}
/** All local clients share this root. The older XDG stores are migration sources only. */
export function userConfigDirectory(
  env: Readonly<Record<string, string | undefined>> = process.env,
  home = homedir(),
): string {
  const root =
    env['PLOWSHARE_CONFIG_DIR'] ??
    env['PLOWSHARE_DESKTOP_CONFIG'] ??
    join(home, '.plowshare');
  if (!isAbsolute(root))
    throw new Error('PLOWSHARE_CONFIG_DIR must be an absolute directory.');
  return root;
}
export function canonicalServer(server: string): string {
  try {
    if (typeof server !== 'string' || server.length > 2048) throw new Error();
    const url = new URL(server);
    if (
      !['http:', 'https:'].includes(url.protocol) ||
      url.username ||
      url.password ||
      url.pathname !== '/' ||
      url.search ||
      url.hash
    )
      throw new Error();
    return url.origin;
  } catch {
    throw new Error(
      'Use an HTTP(S) server origin without credentials, path, query or fragment.',
    );
  }
}
export function connectionAccount(account: string): string {
  if (typeof account !== 'string' || !/^[A-Za-z0-9_.-]{1,64}$/.test(account))
    throw new Error('Use a nonblank account handle of at most 64 characters.');
  return account;
}
export function connectionName(name: string): string {
  if (
    typeof name !== 'string' ||
    !name.trim() ||
    name !== name.trim() ||
    name.length > 100 ||
    Array.from(name).some(
      (character) =>
        character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127,
    )
  )
    throw new Error(
      'Use a nonblank connection name of at most 100 characters.',
    );
  return name;
}
/** Names never become filesystem paths. Identity is immutable, including across removal/re-addition. */
export function connectionKey(server: string, account: string): string {
  return createHash('sha256')
    .update(
      JSON.stringify([canonicalServer(server), connectionAccount(account)]),
    )
    .digest('hex');
}
export function connectionDirectory(
  server: string,
  account: string,
  root = userConfigDirectory(),
): string {
  return join(root, 'connections', connectionKey(server, account));
}
/** Refuse symlinks at storage boundaries before creating private state. */
export async function privateDirectory(directory: string): Promise<void> {
  await mkdir(directory, { recursive: true, mode: 0o700 });
  const info = await lstat(directory);
  if (!info.isDirectory() || info.isSymbolicLink())
    throw new Error('Local configuration must use real directories.');
  await chmod(directory, 0o700);
}
export async function privateFile(file: string): Promise<void> {
  try {
    const info = await lstat(file);
    if (!info.isFile() || info.isSymbolicLink() || info.size > 2_000_000)
      throw new Error(
        'Local configuration must be a regular file within its size limit.',
      );
  } catch (error) {
    if (errorCode(error) !== 'ENOENT') throw error;
  }
}
/** Cross-process serialization; an interrupted writer leaves an actionable lock, never steals it. */
export async function localLock<T>(
  directory: string,
  name: string,
  work: () => Promise<T>,
  signal?: AbortSignal,
): Promise<T> {
  await privateDirectory(directory);
  const lock = join(directory, name + '.lock');
  const deadline = Date.now() + 10_000;
  for (;;) {
    signal?.throwIfAborted();
    try {
      await mkdir(lock, { mode: 0o700 });
      break;
    } catch (error) {
      if (errorCode(error) !== 'EEXIST') throw error;
      if (Date.now() >= deadline)
        throw new Error(
          'Local configuration is busy. Retry after other clients finish; inspect an interrupted writer before removing its lock.',
          { cause: error },
        );
      await pause(25);
    }
  }
  try {
    signal?.throwIfAborted();
    return await work();
  } finally {
    await rm(lock, { recursive: true, force: true });
  }
}
export async function atomicPrivateWrite(
  file: string,
  value: unknown,
): Promise<void> {
  await privateFile(file);
  const temporary = file + '.' + randomUUID();
  try {
    const text = JSON.stringify(value) + '\n';
    if (Buffer.byteLength(text) > 2_000_000)
      throw new Error('Local configuration exceeds its size limit.');
    const handle = await open(temporary, 'wx', 0o600);
    try {
      await handle.writeFile(text);
      await handle.sync();
    } finally {
      await handle.close();
    }
    await rename(temporary, file);
  } finally {
    await rm(temporary, { force: true });
  }
}
function fields(
  value: unknown,
  allowed: string[],
): asserts value is Record<string, unknown> {
  if (
    !isObject(value) ||
    Object.keys(value).some((key) => !allowed.includes(key))
  )
    throw new Error('Saved connection configuration contains invalid fields.');
}
export function decodeConnectionConfiguration(
  value: unknown,
): ConnectionConfiguration {
  fields(value, [
    'version',
    'selected',
    'connections',
    'fileStoreDefault',
    'connectionSetupPending',
  ]);
  if (
    value.version !== 1 ||
    !Array.isArray(value.connections) ||
    value.connections.length > 100
  )
    throw new Error('Unsupported or invalid connection configuration.');
  const connections = value.connections.map((raw: unknown): NamedConnection => {
    fields(raw, ['key', 'name', 'server', 'account', 'reconnect']);
    if (
      typeof raw.name !== 'string' ||
      typeof raw.server !== 'string' ||
      typeof raw.account !== 'string' ||
      typeof raw.reconnect !== 'boolean'
    )
      throw new Error('Invalid saved connection.');
    const name = connectionName(raw.name),
      server = canonicalServer(raw.server),
      account = connectionAccount(raw.account);
    const key = connectionKey(server, account);
    if (server !== raw.server || raw.key !== key)
      throw new Error(
        'Saved connection identity does not match its storage key.',
      );
    return { key, name, server, account, reconnect: raw.reconnect };
  });
  if (
    new Set(connections.map((row) => row.name)).size !== connections.length ||
    new Set(connections.map((row) => row.key)).size !== connections.length
  )
    throw new Error('Duplicate connection name or identity.');
  if (
    value.selected !== undefined &&
    (typeof value.selected !== 'string' ||
      !connections.some((row) => row.key === value.selected))
  )
    throw new Error(
      'Selected connection is missing. Select a saved connection explicitly.',
    );
  if (
    value.connectionSetupPending !== undefined &&
    (value.connectionSetupPending !== true ||
      connections.length !== 0 ||
      value.selected !== undefined ||
      value.fileStoreDefault === undefined)
  )
    throw new Error('Invalid pending connection setup metadata.');
  return {
    version: 1,
    ...(value.connectionSetupPending === true
      ? { connectionSetupPending: true }
      : {}),
    ...(value.fileStoreDefault === undefined
      ? {}
      : { fileStoreDefault: decodeFileStoreDefault(value.fileStoreDefault) }),
    connections,
    ...(value.selected === undefined ? {} : { selected: value.selected }),
  };
}
export class Connections implements ConnectionRegistry {
  readonly path: string;
  constructor(readonly directory = userConfigDirectory()) {
    this.path = join(directory, 'config.json');
  }
  async load(): Promise<ConnectionConfiguration> {
    await privateFile(this.path);
    try {
      return decodeConnectionConfiguration(
        JSON.parse(await readFile(this.path, 'utf8')),
      );
    } catch (error) {
      if (errorCode(error) === 'ENOENT') return { version: 1, connections: [] };
      throw error;
    }
  }
  private update<T>(
    change: (config: ConnectionConfiguration) => T | Promise<T>,
  ): Promise<T> {
    return localLock(this.directory, 'config', async () => {
      const config = await this.load();
      const result = await change(config);
      decodeConnectionConfiguration(config);
      await atomicPrivateWrite(this.path, config);
      return result;
    });
  }
  /** Bootstrap policy lives outside filestore.js, so a missing registry can be recreated. */
  setFileStoreDefault(input: FileStoreDefault): Promise<void> {
    const definition = decodeFileStoreDefault(input);
    return this.update(async (config) => {
      try {
        await lstat(this.path);
      } catch (error) {
        if (errorCode(error) !== 'ENOENT') throw error;
        config.connectionSetupPending = true;
      }
      config.fileStoreDefault = definition;
    });
  }
  put(input: Omit<NamedConnection, 'key'>): Promise<NamedConnection> {
    const server = canonicalServer(input.server),
      account = connectionAccount(input.account),
      name = connectionName(input.name);
    if (typeof input.reconnect !== 'boolean')
      return Promise.reject(new Error('Reconnect must be a boolean.'));
    const row = {
      server,
      account,
      name,
      reconnect: input.reconnect,
      key: connectionKey(server, account),
    };
    return this.update((config) => {
      const previous = config.connections.find((item) => item.name === name);
      if (previous && previous.key !== row.key)
        throw new Error(
          'This name belongs to another server/account. Use a new name.',
        );
      const identity = config.connections.find((item) => item.key === row.key);
      if (identity && identity.name !== name)
        throw new Error(
          'This identity already has a name. Rename that connection.',
        );
      delete config.connectionSetupPending;
      config.connections = [
        ...config.connections.filter((item) => item.key !== row.key),
        row,
      ];
      config.selected = row.key;
      return row;
    });
  }
  /** Import a legacy default only before connection setup; FileStore-only bootstrap must not suppress migration. Concurrent explicit selections win. */
  initialize(input: Omit<NamedConnection, 'key'>): Promise<void> {
    const server = canonicalServer(input.server);
    const row = { ...input, server, key: connectionKey(server, input.account) };
    const initial = decodeConnectionConfiguration({
      version: 1,
      selected: row.key,
      connections: [row],
    });
    return localLock(this.directory, 'config', async () => {
      await privateFile(this.path);
      const previous = await this.load();
      try {
        await readFile(this.path);
        if (!previous.connectionSetupPending) return;
      } catch (error) {
        if (errorCode(error) !== 'ENOENT') throw error;
      }
      await atomicPrivateWrite(this.path, {
        ...initial,
        ...(previous.fileStoreDefault
          ? { fileStoreDefault: previous.fileStoreDefault }
          : {}),
      });
    });
  }
  select(name: string): Promise<NamedConnection> {
    return this.update((config) => {
      const row = config.connections.find(
        (item) => item.name === connectionName(name),
      );
      if (!row)
        throw new Error('Unknown connection. List saved connections first.');
      config.selected = row.key;
      return row;
    });
  }
  rename(name: string, nextName: string): Promise<void> {
    return this.update((config) => {
      const row = config.connections.find(
        (item) => item.name === connectionName(name),
      );
      if (!row) throw new Error('Unknown connection.');
      if (
        config.connections.some(
          (item) => item !== row && item.name === connectionName(nextName),
        )
      )
        throw new Error('Connection name is already used.');
      row.name = connectionName(nextName);
    });
  }
  /** Removes only selection metadata. Local files and sessions remain owned by the same identity. */
  remove(name: string): Promise<void> {
    return this.update((config) => {
      const row = config.connections.find(
        (item) => item.name === connectionName(name),
      );
      if (!row) throw new Error('Unknown connection.');
      config.connections = config.connections.filter((item) => item !== row);
      if (config.selected === row.key) delete config.selected;
    });
  }
}
export interface ConnectionSelection {
  name?: string;
  server?: string;
  account?: string;
}
/** Arguments override environment within a field; identity fields must agree with a named selection.
 * Explicit origin/account bypass the saved default, never silently choose another named identity. */
export async function resolveConnection(
  registry: ConnectionRegistry,
  arguments_: ConnectionSelection,
  env: Readonly<Record<string, string | undefined>>,
): Promise<NamedConnection | undefined> {
  const name = arguments_.name ?? env['PLOWSHARE_CONNECTION'];
  const server = arguments_.server ?? env['PLOWSHARE_URL'];
  const account =
    arguments_.account ?? env['PLOWSHARE_ACCOUNT'] ?? env['PLOWSHARE_HANDLE'];
  const config = await registry.load();
  const row =
    name !== undefined
      ? config.connections.find((item) => item.name === name)
      : server !== undefined || account !== undefined
        ? undefined
        : config.connections.find((item) => item.key === config.selected);
  if (name !== undefined && !row)
    throw new Error('Unknown named connection. List or add it first.');
  if (
    row &&
    ((server !== undefined && canonicalServer(server) !== row.server) ||
      (account !== undefined && account !== row.account))
  )
    throw new Error(
      'Connection selection conflicts with the explicit server/account.',
    );
  return row;
}
/** Shared CLI/TUI management. JSON preserves names containing spaces; no credentials are returned. */
export async function manageConnections(
  registry: ConnectionRegistry,
  args: readonly string[],
): Promise<ConnectionConfiguration> {
  const [action, name, second, account] = args;
  switch (action) {
    case 'list':
      if (args.length !== 1) throw new Error('Use connection list.');
      break;
    case 'add':
      if (args.length !== 4 || !name || !second || !account)
        throw new Error('Use connection add NAME SERVER ACCOUNT.');
      await registry.put({ name, server: second, account, reconnect: true });
      break;
    case 'select':
      if (args.length !== 2 || !name)
        throw new Error('Use connection select NAME.');
      await registry.select(name);
      break;
    case 'rename':
      if (args.length !== 3 || !name || !second)
        throw new Error('Use connection rename NAME NEW_NAME.');
      await registry.rename(name, second);
      break;
    case 'remove':
      if (args.length !== 2 || !name)
        throw new Error('Use connection remove NAME.');
      await registry.remove(name);
      break;
    default:
      throw new Error('Use connection list|add|select|rename|remove.');
  }
  return registry.load();
}
/** Establish every directory boundary before a scoped store writes local state. */
export async function scopedStateFile(
  server: string,
  account: string,
  root: string,
  name: string,
): Promise<string> {
  if (!/^[a-z][a-z0-9.-]*\.json$/.test(name))
    throw new Error('Invalid local state filename.');
  await privateDirectory(root);
  await privateDirectory(join(root, 'connections'));
  const scope = connectionDirectory(server, account, root);
  await privateDirectory(scope);
  return join(scope, name);
}
/** Legacy stores remain intact. Callers project only the proven identity before writing. */
export async function readStateFile(
  file: string,
  legacy: string,
  root: string,
): Promise<string> {
  for (const candidate of new Set([
    file,
    legacy,
    ...(root === userConfigDirectory() &&
    process.env['PLOWSHARE_DESKTOP_CONFIG']
      ? [join(process.env['PLOWSHARE_DESKTOP_CONFIG'], basename(legacy))]
      : []),
    ...(root === userConfigDirectory() &&
    !process.env['PLOWSHARE_CONFIG_DIR'] &&
    !process.env['PLOWSHARE_DESKTOP_CONFIG']
      ? [
          join(
            process.env['XDG_CONFIG_HOME'] ?? join(homedir(), '.config'),
            'plowshare',
            basename(legacy),
          ),
        ]
      : []),
  ])) {
    await privateFile(candidate);
    try {
      return await readFile(candidate, 'utf8');
    } catch (error) {
      if (errorCode(error) !== 'ENOENT') throw error;
    }
  }
  const missing = new Error('State file does not exist.');
  Object.assign(missing, { code: 'ENOENT' });
  throw missing;
}
