import {
  scopedStateFile,
  readStateFile,
  atomicPrivateWrite,
  localLock,
  connectionKey,
  canonicalServer,
  connectionAccount,
} from 'plowshare-client-node/connections';
import { errorCode } from 'plowshare-client-ts/binding/values';
import { isList, isObject } from 'plowshare-client-ts/binding/values';
import { join } from 'node:path';

/** Receipts only: no credentials, prompts, output or executable requests. */
export interface SavedJob {
  id: string;
  handle?: string;
  conversation: string;
  agent: string;
  project?: string;
  source?: 'approval' | 'information' | 'maintenance';
  revision?: string;
}
export interface JobStore {
  load(server: string, account: string): Promise<SavedJob[]>;
  save(server: string, account: string, jobs: SavedJob[]): Promise<void>;
  flush(): Promise<void>;
}
const named = (v: unknown): v is string =>
  typeof v === 'string' && v.length > 0 && v.length <= 512;
function valid(v: unknown): v is SavedJob {
  if (!v || typeof v !== 'object') return false;
  const r = v as SavedJob;
  return (
    named(r.id) &&
    typeof r.conversation === 'string' &&
    r.conversation.length <= 512 &&
    named(r.agent) &&
    (r.handle === undefined || (named(r.handle) && r.handle === r.id)) &&
    (r.project === undefined || named(r.project)) &&
    (r.revision === undefined || named(r.revision)) &&
    (r.source === undefined ||
      ['approval', 'information', 'maintenance'].includes(r.source))
  );
}
export class JobJournal implements JobStore {
  readonly path: string;
  private directory: string;
  private writes = Promise.resolve();
  private observed = new Map<string, Set<string>>();
  constructor(directory: string) {
    this.directory = directory;
    this.path = join(directory, 'desktop-jobs.json');
  }
  private async read(file = this.path): Promise<Record<string, SavedJob[]>> {
    let text: string;
    try {
      text = await readStateFile(file, this.path, this.directory);
    } catch (e) {
      if (errorCode(e) === 'ENOENT') return {};
      throw e;
    }
    if (text.length > 2_000_000)
      throw new Error('Saved job receipts exceed the recovery limit.');
    const data: unknown = JSON.parse(text);
    if (
      !isObject(data) ||
      data.version !== 1 ||
      !isObject(data.accounts) ||
      !Object.values(data.accounts).every(
        (v) => isList(v) && v.length <= 500 && v.every(valid),
      )
    )
      throw new Error('Saved job receipts are invalid.');
    const entries: [string, SavedJob[]][] = [];
    if (
      Object.keys(data).some((key) => !['version', 'accounts'].includes(key)) ||
      Object.keys(data.accounts).length > 100
    )
      throw new Error('Saved job receipts are invalid.');
    for (const [key, value] of Object.entries(data.accounts)) {
      const identity: unknown = JSON.parse(key);
      if (
        !Array.isArray(identity) ||
        identity.length !== 2 ||
        typeof identity[0] !== 'string' ||
        typeof identity[1] !== 'string' ||
        canonicalServer(identity[0]) !== identity[0]
      )
        throw new Error('Saved job identity is invalid.');
      connectionAccount(identity[1]);
      if (!isList(value) || !value.every(valid))
        throw new Error('Invalid job receipts.');
      entries.push([
        key,
        value.map((row) => ({
          id: row.id,
          conversation: row.conversation,
          agent: row.agent,
          ...(row.handle === undefined ? {} : { handle: row.handle }),
          ...(row.project === undefined ? {} : { project: row.project }),
          ...(row.revision === undefined ? {} : { revision: row.revision }),
          ...(row.source === undefined ? {} : { source: row.source }),
        })),
      ]);
    }
    return Object.fromEntries(entries);
  }
  async load(server: string, account: string) {
    await this.writes;
    server = canonicalServer(server);
    const rows =
      (
        await this.read(
          await scopedStateFile(
            server,
            account,
            this.directory,
            'desktop-jobs.json',
          ),
        )
      )[JSON.stringify([server, account])] ?? [];
    this.observed.set(
      connectionKey(server, account),
      new Set(rows.map((row) => row.id)),
    );
    return rows;
  }

  save(server: string, account: string, jobs: SavedJob[]) {
    if (!jobs.every(valid) || jobs.length > 500)
      return Promise.reject(new Error('Invalid job receipts.'));
    server = canonicalServer(server);
    const snapshot = structuredClone(jobs);
    const next = this.writes.then(async () => {
      const file = await scopedStateFile(
        server,
        account,
        this.directory,
        'desktop-jobs.json',
      );
      return localLock(
        this.directory,
        'jobs-' + connectionKey(server, account),
        async () => {
          const key = connectionKey(server, account);
          const previous =
            (await this.read(file))[JSON.stringify([server, account])] ?? [];
          const observed = this.observed.get(key) ?? new Set<string>();
          // A client may retire receipts it observed/reconciled, but never erase work
          // admitted by another client after its last snapshot. Unknown handles stay recoverable.
          const retained = previous.filter((row) => !observed.has(row.id));
          const merged = [
            ...new Map(
              [...retained, ...snapshot].map((row) => [row.id, row]),
            ).values(),
          ];
          if (merged.length > 500)
            throw new Error('Saved job receipts exceed the recovery limit.');
          const accounts = { [JSON.stringify([server, account])]: merged };
          await atomicPrivateWrite(file, { version: 1, accounts });
          this.observed.set(key, new Set(snapshot.map((row) => row.id)));
        },
      );
    });
    this.writes = next.catch(() => undefined);
    return next;
  }
  async flush() {
    await this.writes;
  }
}
