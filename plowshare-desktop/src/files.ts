/** Node platform boundary. Only the main process may construct or choose a root. */
import { realpath, stat, lstat, readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { hostname } from 'node:os';
import type { Claim } from 'plowshare-client-ts/binding/auth';
import type { Socket } from 'plowshare-client-ts/binding/connection';
import { rooter, sameClaim } from 'plowshare-client-node/rooter';
import { enforcing } from 'plowshare-client-node/enforcer';
import { discover, mark, markedName, resolveMarked, type Marked, namedAfter, thisMachine } from 'plowshare-client-node/marker';
import { projectLabel } from 'plowshare-client-ts/operations/project-label';
import { noticingWrites } from 'plowshare-client-node/sync/syncer';

import type { FilePresenceState } from './files-shared.ts';

export type FileOpener = (claim: Claim) => Promise<Socket>;

async function validateMarker(root: string) {
  for (const [path, directory] of [[join(root, '.plowshare'), true], [join(root, '.plowshare', 'project'), false]] as const) {
    let found;
    try { found = await lstat(path); }
    catch (error) { if ((error as NodeJS.ErrnoException).code === 'ENOENT') continue; throw error; }
    if (found.isSymbolicLink() || (directory ? !found.isDirectory() : !found.isFile())) throw new Error(`The project marker must use a regular file and directory: ${path}`);
    if (!directory) await readFile(path, 'utf8'); // Unreadable markers must not silently select another project.
  }
}

export async function identifyFolder(directory: string, project?: string): Promise<Marked> {
  let root = await realpath(directory);
  if (!(await stat(root)).isDirectory()) throw new Error('Choose a directory.');
  await validateMarker(root);
  const found = await discover(root);
  if (found) { root = found.root; await validateMarker(root); }
  const existing = await markedName(root);
  if (found?.kind === 'DISJOINT' && project && projectLabel(project) !== found.project) throw new Error('The manifest belongs to a different project.');
  const name = found?.kind === 'DISJOINT' ? found.project : project ?? existing ?? namedAfter(root);
  if (!name.trim() || name.length > 512 || /[\r\n\0]/.test(name)) throw new Error('Invalid project name.');
  if (existing && existing !== name) throw new Error(`This folder belongs to ${existing}. Select that workspace first.`);
  return { root, project: name, ...(found?.kind ? {kind:found.kind}: {}) };
}

export class FilePresence {
  private readonly connection: import('plowshare-client-ts/binding/connection').Connection | undefined;
  private alive = true;
  private busy = false;
  private lifetime?: AbortController;
  private opening?: Promise<string>;
  private commands = new Set<Promise<unknown>>();
  private readonly held;
  private readonly changed: (state: FilePresenceState) => void;
  constructor(open: FileOpener, changed: (state: FilePresenceState) => void, wrote: () => void = () => {}, connection?: import('plowshare-client-ts/binding/connection').Connection) {
    this.changed = changed; this.connection = connection;
    this.held = rooter({
      requireReadyProject: true,
      open: async claim => {
        if (!this.alive) throw new Error('The connection changed while choosing files.');
        const socket = await open(claim);
        if (!this.alive) { socket.close(); throw new Error('The connection changed while choosing files.'); }
        return socket;
      },
      answering: root => {
        this.lifetime?.abort();
        this.lifetime = new AbortController();
        const answer = noticingWrites(enforcing(root, this.lifetime.signal), wrote);
        return request => {
          const result = answer(request);
          if (request.op === 'run') {
            this.commands.add(result);
            void result.finally(() => this.commands.delete(result));
          }
          return result;
        };
      },
      onLost: (claim, closing) => {
        this.lifetime?.abort();
        if (this.alive) this.changed({ status: 'lost', ...claim, detail: closing.reason || 'File connection lost. Choose the folder again to restore access.' });
      },
    });
  }
  /** Main supplies a native selection or a validated stored/recorded path, never a renderer path. */
  choose(directory: string, project?: string): Promise<string> {
    if (!this.alive) throw new Error('Connect to the server before choosing files.');
    if (this.busy) throw new Error('A folder change is already in progress.');
    this.busy = true;
    const operation = this.root(directory, project);
    this.opening = operation;
    return operation.finally(() => { this.busy = false; if (this.opening === operation) this.opening = undefined; });
  }
  private async root(directory: string, project?: string): Promise<string> {
    let found = await identifyFolder(directory, project);
    if (found.kind === 'DISJOINT') {
      if (!this.connection) throw new Error('A client project needs its signed-in connection');
      found = await resolveMarked(found, this.connection, thisMachine(process.env, hostname()));
    }
    const {root, project:name} = found;
    if (!this.alive) throw new Error('The connection changed while choosing files.');
    const claim = { project: name, root, machine: thisMachine(process.env, hostname()) };
    if (sameClaim(this.held.current(), claim)) return name;
    const previous = this.held.current();
    this.changed({ status: 'opening', ...claim });
    await this.stopCommands();
    try {
      await this.held.root(claim);
      if (!this.alive || !sameClaim(this.held.current(), claim)) throw new Error('The file claim was lost while opening.');
      // As in /here, write the project marker only after the server accepts it.
      let detail: string | undefined;
      try { await validateMarker(root); await mark(root, name); } catch (error) { detail = `Files connected; project marker could not be saved: ${String(error)}`; }
      if (!this.alive || !sameClaim(this.held.current(), claim)) throw new Error('The file claim was lost while opening.');
      this.changed({ status: 'ready', ...claim, ...(detail ? { detail } : {}) });
      return name;
    } catch (error) {
      if (!this.alive) { await this.held.release(); throw error; }
      this.lifetime?.abort();
      await this.held.release();
      const detail = error instanceof Error ? error.message : String(error);
      // A refused move retains the previously selected project if still available.
      if (previous) {
        try {
          await this.held.root(previous);
          if (this.alive && sameClaim(this.held.current(), previous)) this.changed({ status: 'ready', ...previous, detail: `Folder change refused: ${detail}` });
          else throw new Error('Previous file claim could not be restored.');
        } catch {
          this.lifetime?.abort(); await this.held.release();
          if (this.alive) this.changed({ status: 'lost', ...previous, detail: `Folder change refused; previous files unavailable: ${detail}` });
        }
      } else this.changed({ status: 'off', detail });
      throw error;
    }
  }
  private async stopCommands() {
    this.lifetime?.abort();
    await Promise.allSettled([...this.commands]);
  }
  async withdraw(): Promise<void> {
    if (this.busy) throw new Error('Wait for the folder change to finish before disconnecting files.');
    this.busy = true;
    try {
      this.lifetime?.abort();
      await this.held.release();
      await this.stopCommands();
      if (this.alive) this.changed({ status: 'off' });
    } finally { this.busy = false; }
  }
  async close(): Promise<void> {
    this.alive = false;
    this.lifetime?.abort();
    await this.held.release();
    await this.opening?.catch(() => undefined);
    await this.held.release();
    await this.stopCommands();
  }
}
