import { mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';

/** Receipts only: no credentials, prompts, output or executable requests. */
export interface SavedJob { id: string; handle?: string; conversation: string; agent: string; project?: string; source?: 'approval' | 'information' | 'maintenance'; revision?: string }
export interface JobStore { load(server: string, account: string): Promise<SavedJob[]>; save(server: string, account: string, jobs: SavedJob[]): Promise<void>; flush(): Promise<void> }
const named = (v: unknown): v is string => typeof v === 'string' && v.length > 0 && v.length <= 512;
function valid(v: unknown): v is SavedJob {
  if (!v || typeof v !== 'object') return false;
  const r = v as SavedJob;
  return named(r.id) && typeof r.conversation === 'string' && r.conversation.length <= 512 && named(r.agent)
    && (r.handle === undefined || named(r.handle) && r.handle === r.id)
    && (r.project === undefined || named(r.project)) && (r.revision === undefined || named(r.revision))
    && (r.source === undefined || ['approval', 'information', 'maintenance'].includes(r.source));
}
export class JobJournal implements JobStore {
  readonly path: string; private directory: string; private writes = Promise.resolve();
  constructor(directory: string) { this.directory = directory; this.path = join(directory, 'desktop-jobs.json'); }
  private async read(): Promise<Record<string, SavedJob[]>> {
    let text: string;
    try { text = await readFile(this.path, 'utf8'); } catch (e) { if ((e as NodeJS.ErrnoException).code === 'ENOENT') return {}; throw e; }
    if (text.length > 2_000_000) throw new Error('Saved job receipts exceed the recovery limit.');
    const data = JSON.parse(text);
    if (data?.version !== 1 || !data.accounts || typeof data.accounts !== 'object' || Array.isArray(data.accounts)
      || !Object.values(data.accounts).every(v => Array.isArray(v) && v.length <= 500 && v.every(valid))) throw new Error('Saved job receipts are invalid.');
    return data.accounts;
  }
  async load(server: string, account: string) { await this.writes; return (await this.read())[JSON.stringify([server, account])] ?? []; }
  save(server: string, account: string, jobs: SavedJob[]) {
    if (!jobs.every(valid) || jobs.length > 500) return Promise.reject(new Error('Invalid job receipts.'));
    const snapshot = structuredClone(jobs);
    const next = this.writes.then(async () => {
      const accounts = await this.read(); accounts[JSON.stringify([server, account])] = snapshot;
      await mkdir(this.directory, { recursive: true, mode: 0o700 });
      const temporary = `${this.path}.${randomUUID()}.tmp`;
      try { await writeFile(temporary, JSON.stringify({ version: 1, accounts }) + '\n', { mode: 0o600, flag: 'wx' }); await rename(temporary, this.path); }
      finally { await rm(temporary, { force: true }); }
    });
    this.writes = next.catch(() => undefined); return next;
  }
  async flush() { await this.writes; }
}
