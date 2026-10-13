import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { basename, isAbsolute, join } from 'node:path';
import {
  canonicalServer,
  privateDirectory,
  privateFile,
  localLock,
  atomicPrivateWrite,
} from './connections.ts';
import { errorCode, isObject } from 'plowshare-client-ts/binding/values';
import {
  decodeManagementIntent,
  sameManagementResource,
  ProviderManagementPending,
} from 'plowshare-client-ts/operations/provider-management';
import type {
  ManagementIntent,
  ManagementIntentStore,
} from 'plowshare-client-ts/operations/provider-management';

/** Private atomic recovery bookmarks shared by native clients, scoped to the authenticated
 * origin/actor, including service principals. They contain no executable request or secret.
 * Locks fence concurrent client actions, but never steal an interrupted writer's lock. */
export class ProviderManagementIntents implements ManagementIntentStore {
  private readonly directory: string;
  readonly file: string;
  private readonly server: string;
  private readonly actor: string;
  constructor(server: string, actor: string, root: string) {
    this.server = canonicalServer(server);
    this.actor = actor;
    if (
      !/^(?:[A-Za-z0-9_.-]{1,64}|@service\/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12})$/u.test(
        actor,
      )
    )
      throw new Error(
        'An authenticated actor is required for management bookmarks.',
      );
    if (!isAbsolute(root))
      throw new Error(
        'An absolute private configuration directory is required.',
      );
    this.directory = join(root, 'provider-management');
    const key = createHash('sha256')
      .update(JSON.stringify([this.server, actor]))
      .digest('hex');
    this.file = join(this.directory, key + '.json');
  }
  private async read(): Promise<ManagementIntent[]> {
    await privateFile(this.file);
    let value: unknown;
    try {
      value = JSON.parse(await readFile(this.file, 'utf8')) as unknown;
    } catch (error) {
      if (errorCode(error) === 'ENOENT') return [];
      throw error;
    }
    if (
      !isObject(value) ||
      Object.keys(value).some(
        (key) => !['version', 'server', 'actor', 'intents'].includes(key),
      ) ||
      value.version !== 1 ||
      value.server !== this.server ||
      value.actor !== this.actor ||
      !Array.isArray(value.intents) ||
      value.intents.length > 100
    )
      throw new Error('Invalid saved management bookmarks.');
    const intents = value.intents.map((entry: unknown) =>
      decodeManagementIntent(entry),
    );
    if (
      new Set(intents.map((intent) => intent.requestId)).size !== intents.length
    )
      throw new Error('Duplicate saved management request UUID.');
    return intents;
  }
  private async locked<T>(work: () => Promise<T>): Promise<T> {
    await privateDirectory(join(this.directory, '..'));
    await privateDirectory(this.directory);
    return localLock(this.directory, basename(this.file) + '.intent', work);
  }
  list(): Promise<readonly ManagementIntent[]> {
    return this.locked(() => this.read());
  }
  reserve(input: ManagementIntent): Promise<void> {
    const intent = decodeManagementIntent(input);
    return this.locked(async () => {
      const saved = await this.read();
      const pending = saved.find(
        (entry) =>
          entry.requestId === intent.requestId ||
          sameManagementResource(entry, intent),
      );
      if (pending) throw new ProviderManagementPending(pending);
      if (saved.length >= 100)
        throw new Error(
          'Management bookmark limit reached; reconcile original receipts.',
        );
      await this.write([...saved, intent]);
    });
  }
  remove(requestId: string): Promise<void> {
    return this.locked(async () => {
      const saved = await this.read();
      await this.write(
        saved.filter((intent) => intent.requestId !== requestId),
      );
    });
  }
  private write(intents: readonly ManagementIntent[]): Promise<void> {
    return atomicPrivateWrite(this.file, {
      version: 1,
      server: this.server,
      actor: this.actor,
      intents,
    });
  }
}

/** Only an explicit authorization action calls this OS browser opener. Never log the URL,
 * invoke a shell or include it in an error. The server owns the authenticated callback. */
export async function openProviderAuthorization(
  authorizationUrl: string,
): Promise<void> {
  const url = new URL(authorizationUrl);
  if (url.protocol !== 'https:' || url.username || url.password)
    throw new Error('Invalid provider authorization destination.');
  const command =
    process.platform === 'darwin'
      ? 'open'
      : process.platform === 'win32'
        ? 'rundll32.exe'
        : 'xdg-open';
  const args =
    process.platform === 'win32'
      ? ['url.dll,FileProtocolHandler', url.href]
      : [url.href];
  await new Promise<void>((resolve, reject) => {
    const child = spawn(command, args, { stdio: 'ignore', shell: false });
    const timer = setTimeout(() => {
      child.kill();
      reject(
        new Error(
          'Browser opener timed out. Inspect credential status before a new authorization.',
        ),
      );
    }, 10_000);
    child.once('error', () => {
      clearTimeout(timer);
      reject(
        new Error(
          'Browser authorization could not be opened. Inspect credential status before a new authorization.',
        ),
      );
    });
    child.once('exit', (code) => {
      clearTimeout(timer);
      if (code === 0) resolve();
      else
        reject(
          new Error(
            'Browser authorization could not be opened. Inspect credential status before a new authorization.',
          ),
        );
    });
  });
}
