import { syncer, type Syncer, type SyncerOptions } from 'plowshare-client-node/sync/syncer';
import { conflictIdentity, conflictsOf, unionStatusOf, type SyncAction } from 'plowshare-client-ts/operations/union';
import type { Outcome } from 'plowshare-client-ts/binding/envelope';
import type { Claim } from 'plowshare-client-ts/binding/auth';
import { resultOf } from 'plowshare-client-ts/operations/direct';
import { previewIdentity, syncIdentity, type SyncState } from './sync-shared.ts';

/** One accepted file claim owns one syncer. Tokens and disk paths stay in the main process. */
export class DesktopSync {
  value?: SyncState;
  private runtime?: Syncer;
  private lifetime?: AbortController;
  private token = {};
  private timer?: ReturnType<typeof setInterval>;
  private starting?: Promise<void>;
  private flight?: Promise<void>;
  private pending = new Set<Promise<unknown>>();
  private ask: (type: string, payload: unknown) => Promise<Outcome>;
  private changed: (value: SyncState | undefined) => void;
  private make: (options: SyncerOptions) => Syncer;
  constructor(ask: (type: string, payload: unknown) => Promise<Outcome>,
    changed: (value: SyncState | undefined) => void, make: (options: SyncerOptions) => Syncer = syncer) {
    this.ask = ask; this.changed = changed; this.make = make;
  }
  private emit() { this.changed(this.value); }
  stop() {
    this.token = {}; this.runtime?.stop(); this.lifetime?.abort(); this.runtime = undefined;
    clearInterval(this.timer); this.timer = undefined; this.flight = undefined;
    this.value = undefined; this.emit();
  }
  async close() { this.stop(); await Promise.allSettled([...this.pending]); }
  changedFiles() { this.runtime?.changed(); }
  private track<T>(work: Promise<T>): Promise<T> {
    this.pending.add(work); return work.finally(() => this.pending.delete(work));
  }
  attach(claim: Claim, base: string, handle: string, bearer?: () => Promise<string>) {
    this.stop(); const token = this.token;
    const value: SyncState = { project: claim.project, conflicts: [] }; this.value = value;
    if (!bearer) { value.error = 'This connection cannot authenticate file synchronization.'; this.emit(); return; }
    this.lifetime = new AbortController();
    this.runtime = this.make({ claim, base, handle, bearer, strict: true, signal: this.lifetime.signal,
      asker: { ask: async (type, payload) => {
        if (token !== this.token) throw new Error('The project files changed.');
        const answer = await this.ask(type, payload);
        if (token !== this.token) throw new Error('The project files changed.');
        // Union control acknowledgements carry maps; begin/enable also identify the Git endpoint.
        if (answer.code === 'OK' && !['union.status', 'union.conflict.list'].includes(type)) {
          const row = answer.payload;
          if (!row || typeof row !== 'object' || Array.isArray(row)
            || (['union.enable', 'union.begin'].includes(type) && typeof (row as any).url !== 'string')
            || (type === 'union.conflict.open' && (!Number.isSafeInteger((row as any).n) || (row as any).n < 1))) {
            throw new Error(`The server returned an unreadable ${type} acknowledgement.`);
          }
        }
        return answer;
      } },
      tell: (trouble, lines) => { if (token === this.token) { value[trouble ? 'error' : 'notice'] = lines.join('\n'); this.emit(); } },
      standing: notice => { if (token === this.token) { value.standing = notice; this.emit(); } },
    });
    value.loading = true; this.emit();
    const runtime = this.runtime;
    this.starting = this.track((async () => {
      try { await this.refresh(); await runtime.connect(); await this.refresh(); }
      catch (error) { if (token === this.token) value.error = String(error); }
      finally { if (token === this.token) { value.loading = false; this.emit(); } }
    })());
    this.timer = setInterval(() => { void this.refresh().catch(() => undefined); }, 30_000); this.timer.unref?.();
  }
  refresh(): Promise<void> {
    if (this.flight) return this.flight;
    const value = this.value, token = this.token;
    if (!value) return Promise.reject(new Error('Connect a project folder first.'));
    const work = this.track((async () => {
      value.loading = true; this.emit();
      try {
        const statusAnswer = await this.ask('union.status', { project: value.project });
        if (resultOf({ type: 'union.status', payload: { project: value.project } }, statusAnswer).kind === 'invalid-response') throw new Error('The sync status is incomplete.');
        const status = unionStatusOf(statusAnswer);
        if (!status) throw new Error(statusAnswer.said ?? 'The sync status is incomplete.');
        const rowsAnswer = await this.ask('union.conflict.list', { project: value.project });
        if (resultOf({ type: 'union.conflict.list', payload: { project: value.project } }, rowsAnswer).kind === 'invalid-response') throw new Error('The conflict listing is incomplete.');
        const rows = conflictsOf(rowsAnswer);
        if (!rows) throw new Error(rowsAnswer.said ?? 'The conflict listing is incomplete.');
        if (token !== this.token) return;
        value.status = status; value.conflicts = rows;
        if (value.preview && !rows.some(row => conflictIdentity(row) === conflictIdentity(value.preview!.row))) delete value.preview;
        if (!value.uncertain) delete value.error;
      } catch (error) { if (token === this.token) value.error = error instanceof Error ? error.message : String(error); throw error; }
      finally { if (token === this.token) { value.loading = false; this.emit(); } }
    })());
    this.flight = work;
    void work.finally(() => { if (this.flight === work) this.flight = undefined; }).catch(() => undefined);
    return work;
  }
  private async use<T>(work: (runtime: Syncer, value: SyncState, token: object) => Promise<T>): Promise<T> {
    const runtime = this.runtime, value = this.value, token = this.token;
    if (!runtime || !value) throw new Error('Connect a project folder with a saved login first.');
    if (value.busy) throw new Error('A sync operation is already running.');
    value.busy = true; delete value.error; delete value.notice; this.emit();
    return this.track((async () => {
      try {
        await this.starting;
        if (token !== this.token) throw new Error('The project files changed.');
        return await work(runtime, value, token);
      } catch (error) { if (token === this.token) value.error = error instanceof Error ? error.message : String(error); throw error; }
      finally { if (token === this.token) { value.busy = false; this.emit(); } }
    })());
  }
  inspect(path: string) {
    return this.use(async (runtime, value, token) => {
      if (!value.conflicts.some(row => row.path === path)) throw new Error('Choose a displayed conflict.');
      const preview = await runtime.inspect(path);
      if (token === this.token) { value.preview = preview; this.emit(); }
    });
  }
  run(kind: 'on' | 'off' | 'now', identity: string) {
    return this.use(async (runtime, value, token) => {
      await this.refresh();
      if (token !== this.token || syncIdentity(value) !== identity) throw new Error('The sync status changed. Review it before trying again.');
      if (kind === 'on' && value.status?.enabled) throw new Error('The server copy is already enabled.');
      if (kind !== 'on' && !value.status?.enabled) throw new Error('Enable the server copy first.');
      if (kind === 'off' && value.conflicts.length) throw new Error('Resolve open conflicts before removing the server copy.');
      try {
        if (kind === 'now') await runtime.connect(); else await runtime.run({ kind });
        if (token !== this.token) return;
        value.uncertain = false; value.notice = kind === 'off' ? 'Sync disabled. Your local files are retained; the server copy was removed.' : 'Synchronization completed.';
      } catch (error) { if (token === this.token) { value.uncertain = true; value.notice = 'The operation may have partially completed. Refresh and inspect before making another decision. It will not be replayed.'; } throw error; }
      await this.refresh();
    });
  }
  resolve(how: 'mine' | 'theirs' | 'done', identity: string, text?: string) {
    return this.use(async (runtime, value, token) => {
      const preview = value.preview;
      if (!preview || previewIdentity(preview) !== identity) throw new Error('Inspect the current conflict before deciding.');
      await this.refresh();
      if (token !== this.token || !value.preview || previewIdentity(value.preview) !== identity) throw new Error('The conflict changed. Inspect it again.');
      const action: SyncAction = { kind: 'resolve', path: preview.row.path, how,
        expected: preview.row, localHash: preview.localHash, ...(text === undefined ? {} : { text }) };
      try {
        await runtime.run(action);
        if (token !== this.token) return;
        delete value.preview; value.uncertain = false; value.notice = `Resolved ${preview.row.path}.`;
      } catch (error) { if (token === this.token) { value.uncertain = true; value.notice = 'The resolution may have partially completed. Inspect the file and refresh; the decision will not be replayed.'; } throw error; }
      await this.refresh();
    });
  }
}
