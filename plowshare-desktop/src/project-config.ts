import { mkdir, readFile, writeFile, rename, rm } from 'node:fs/promises';
import { join, isAbsolute } from 'node:path';
import { randomUUID } from 'node:crypto';

export interface SavedProject { server: string; account: string; name: string; path: string; machine: string; enabled: boolean }
export interface ProjectStore {
  list(server: string, account: string): Promise<SavedProject[]>;
  put(project: SavedProject): Promise<void>;
  remove(server: string, account: string, name: string): Promise<void>;
}
function valid(row: unknown): row is SavedProject {
  if (!row || typeof row !== 'object') return false;
  const value = row as SavedProject;
  return [value.server, value.account, value.name, value.path, value.machine].every(item => typeof item === 'string' && item.length > 0)
    && isAbsolute(value.path) && typeof value.enabled === 'boolean';
}
/** Account/server-scoped directory bookmarks. No credentials or conversation data. */
export class ProjectConfig implements ProjectStore {
  readonly path: string;
  private directory: string;
  private writes = Promise.resolve();
  constructor(directory: string) { this.directory = directory; this.path = join(directory, 'desktop-projects.json'); }
  private async read(): Promise<SavedProject[]> {
    let data: string;
    try { data = await readFile(this.path, 'utf8'); }
    catch (error) { if ((error as NodeJS.ErrnoException).code === 'ENOENT') return []; throw error; }
    const value = JSON.parse(data);
    if (value?.version !== 1 || !Array.isArray(value.projects) || !value.projects.every(valid)) throw new Error(`Invalid project configuration: ${this.path}`);
    const keys = value.projects.map((row: SavedProject) => JSON.stringify([row.server, row.account, row.name]));
    if (new Set(keys).size !== keys.length) throw new Error(`Duplicate projects in ${this.path}`);
    return value.projects;
  }
  async list(server: string, account: string) {
    await this.writes;
    return (await this.read()).filter(row => row.server === server && row.account === account);
  }
  private update(change: (rows: SavedProject[]) => SavedProject[]) {
    const work = this.writes.then(async () => {
      const projects = change(await this.read());
      await mkdir(this.directory, { recursive: true, mode: 0o700 });
      const temporary = `${this.path}.${randomUUID()}.tmp`;
      try {
        await writeFile(temporary, JSON.stringify({ version: 1, projects }, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
        await rename(temporary, this.path);
      } finally { await rm(temporary, { force: true }); }
    });
    this.writes = work.catch(() => undefined);
    return work;
  }
  put(project: SavedProject) {
    if (!valid(project)) return Promise.reject(new Error('Invalid project mapping.'));
    return this.update(rows => [...rows.filter(row => row.server !== project.server || row.account !== project.account || row.name !== project.name), { ...project }]);
  }
  remove(server: string, account: string, name: string) { return this.update(rows => rows.filter(row => row.server !== server || row.account !== account || row.name !== name)); }
}
