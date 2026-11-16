import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { Connection } from 'plowshare-client-ts/binding/connection';
import type { Claim } from 'plowshare-client-ts/binding/auth';
import {
  errorCode,
  isList,
  isObject,
} from 'plowshare-client-ts/binding/values';
import { projectLabel } from 'plowshare-client-ts/operations/project-label';
import {
  atomicPrivateWrite,
  connectionDirectory,
  localLock,
  privateFile,
} from './connections.ts';

interface Slot {
  key: string;
  owner?: string;
  pid?: number;
}
interface Store {
  directory: string;
  owner: string;
  held: Map<string, string>;
  closed: boolean;
}
const stores = new WeakMap<Connection, Store>();

/** Private process ownership prevents concurrent clients sharing a logical file session. */
function alive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    if (errorCode(error) === 'ESRCH') return false;
    if (errorCode(error) === 'EPERM') return true;
    throw error;
  }
}
async function readSlots(file: string): Promise<Slot[]> {
  await privateFile(file);
  try {
    const value: unknown = JSON.parse(await readFile(file, 'utf8'));
    if (typeof value === 'string' && /^[a-f0-9]{64}$/.test(value))
      return [{ key: value }];
    if (!isList(value) || value.length > 64)
      throw new Error('Invalid workspace recovery storage.');
    return value.map((slot: unknown) => {
      if (
        !isObject(slot) ||
        typeof slot.key !== 'string' ||
        !/^[a-f0-9]{64}$/.test(slot.key) ||
        Object.keys(slot).some(
          (key) => !['key', 'owner', 'pid'].includes(key),
        ) ||
        (slot.owner !== undefined &&
          (typeof slot.owner !== 'string' ||
            !/^[a-f0-9-]{36}$/.test(slot.owner))) ||
        (slot.pid !== undefined &&
          (typeof slot.pid !== 'number' ||
            !Number.isSafeInteger(slot.pid) ||
            slot.pid < 1 ||
            slot.pid > 2147483647)) ||
        (slot.owner === undefined) !== (slot.pid === undefined)
      )
        throw new Error('Invalid workspace recovery storage.');
      return {
        key: slot.key,
        ...(typeof slot.owner === 'string' && typeof slot.pid === 'number'
          ? { owner: slot.owner, pid: slot.pid }
          : {}),
      };
    });
  } catch (error) {
    if (errorCode(error) === 'ENOENT') return [];
    throw error;
  }
}

/** Register at authenticated composition boundaries. Explicit close releases private local claims. */
export function enableWorkspaceRecovery(
  connection: Connection,
  origin: string,
  account: string,
  configurationRoot?: string,
): void {
  if (stores.has(connection))
    throw new Error(
      'Workspace recovery is already configured for this connection.',
    );
  stores.set(connection, {
    directory: join(
      connectionDirectory(origin, account, configurationRoot),
      'workspaces',
    ),
    owner: randomUUID(),
    held: new Map(),
    closed: false,
  });
  const close = connection.close.bind(connection);
  connection.close = () => {
    close();
    releaseWorkspaceRecovery(connection).catch(() =>
      process.emitWarning(
        'Workspace recovery identity cleanup failed; inspect private client configuration.',
      ),
    );
  };
}

/** Bound cleanup; a killed process is detected by PID on the next acquisition. Keys are retained. */
export async function releaseWorkspaceRecovery(
  connection: Connection,
): Promise<void> {
  const store = stores.get(connection);
  if (!store || store.closed) return;
  store.closed = true;
  for (const name of store.held.keys()) {
    await localLock(store.directory, name, async () => {
      const file = join(store.directory, name + '.json');
      const slots = await readSlots(file);
      await atomicPrivateWrite(
        file,
        slots.map((slot) =>
          slot.owner === store.owner ? { key: slot.key } : slot,
        ),
      );
    });
  }
}

/** Stable per-workspace capability, with a separate slot for every concurrent local client. */
export async function workspaceRecoveryKey(
  connection: Connection,
  claim: Claim,
): Promise<string | undefined> {
  const store = stores.get(connection);
  if (store === undefined) return undefined;
  if (store.closed)
    throw new Error('The workspace recovery connection is closed.');
  const name = createHash('sha256')
    .update(
      JSON.stringify([claim.machine, claim.root, projectLabel(claim.project)]),
    )
    .digest('hex');
  const held = store.held.get(name);
  if (held !== undefined) return held;
  return localLock(store.directory, name, async () => {
    if (store.closed)
      throw new Error('The workspace recovery connection is closed.');
    const already = store.held.get(name);
    if (already !== undefined) return already;
    const file = join(store.directory, name + '.json');
    const slots = await readSlots(file);
    let slot = slots.find((slot) => slot.pid === undefined || !alive(slot.pid));
    if (!slot) {
      if (slots.length >= 64)
        throw new Error(
          'Too many active clients for this workspace. Close an existing client.',
        );
      slot = { key: randomBytes(32).toString('hex') };
      slots.push(slot);
    }
    slot.owner = store.owner;
    slot.pid = process.pid;
    await atomicPrivateWrite(file, slots);
    store.held.set(name, slot.key);
    return slot.key;
  });
}

/** Add private proof only at the file connection boundary, never to displayed claims. */
export async function recoverableClaim(
  connection: Connection,
  claim: Claim,
): Promise<Claim> {
  const recovery = await workspaceRecoveryKey(connection, claim);
  return recovery === undefined ? claim : { ...claim, recovery };
}
