import {
  scopedStateFile,
  readStateFile,
  atomicPrivateWrite,
  localLock,
  connectionKey,
} from 'plowshare-client-node/connections';
import { errorCode } from 'plowshare-client-ts/binding/values';
import { isList, isObject } from 'plowshare-client-ts/binding/values';
import { join, isAbsolute } from 'node:path';

export interface SavedProject {
  server: string;
  account: string;
  name: string;
  path: string;
  machine: string;
  enabled: boolean;
}
export interface ProjectStore {
  list(server: string, account: string): Promise<SavedProject[]>;
  put(project: SavedProject): Promise<void>;
  remove(server: string, account: string, name: string): Promise<void>;
}
function valid(row: unknown): row is SavedProject {
  if (!row || typeof row !== 'object') return false;
  const value = row as SavedProject;
  return (
    [value.server, value.account, value.name, value.path, value.machine].every(
      (item) => typeof item === 'string' && item.length > 0,
    ) &&
    isAbsolute(value.path) &&
    typeof value.enabled === 'boolean'
  );
}
/** Account/server-scoped directory bookmarks. No credentials or conversation data. */
export class ProjectConfig implements ProjectStore {
  readonly path: string;
  private directory: string;
  private writes = Promise.resolve();
  constructor(directory: string) {
    this.directory = directory;
    this.path = join(directory, 'desktop-projects.json');
  }
  private async read(file = this.path): Promise<SavedProject[]> {
    let data: string;
    try {
      data = await readStateFile(file, this.path, this.directory);
    } catch (error) {
      if (errorCode(error) === 'ENOENT') return [];
      throw error;
    }
    const value: unknown = JSON.parse(data);
    if (
      !isObject(value) ||
      value.version !== 1 ||
      !isList(value.projects) ||
      !value.projects.every(valid)
    )
      throw new Error(`Invalid project configuration: ${this.path}`);
    const keys = value.projects.map((row: SavedProject) =>
      JSON.stringify([row.server, row.account, row.name]),
    );
    if (new Set(keys).size !== keys.length)
      throw new Error(`Duplicate projects in ${this.path}`);
    return value.projects.map((row) => ({
      server: row.server,
      account: row.account,
      name: row.name,
      path: row.path,
      machine: row.machine,
      enabled: row.enabled,
    }));
  }
  async list(server: string, account: string) {
    await this.writes;
    return (
      await this.read(
        await scopedStateFile(
          server,
          account,
          this.directory,
          'desktop-projects.json',
        ),
      )
    ).filter((row) => row.server === server && row.account === account);
  }
  private update(
    server: string,
    account: string,
    change: (rows: SavedProject[]) => SavedProject[],
  ) {
    const work = this.writes.then(async () => {
      const file = await scopedStateFile(
        server,
        account,
        this.directory,
        'desktop-projects.json',
      );
      return localLock(
        this.directory,
        'projects-' + connectionKey(server, account),
        async () => {
          const projects = change(
            (await this.read(file)).filter(
              (row) => row.server === server && row.account === account,
            ),
          );
          await atomicPrivateWrite(file, { version: 1, projects });
        },
      );
    });
    this.writes = work.catch(() => undefined);
    return work;
  }
  put(project: SavedProject) {
    if (!valid(project))
      return Promise.reject(new Error('Invalid project mapping.'));
    return this.update(project.server, project.account, (rows) => [
      ...rows.filter(
        (row) =>
          row.server !== project.server ||
          row.account !== project.account ||
          row.name !== project.name,
      ),
      { ...project },
    ]);
  }
  remove(server: string, account: string, name: string) {
    return this.update(server, account, (rows) =>
      rows.filter(
        (row) =>
          row.server !== server || row.account !== account || row.name !== name,
      ),
    );
  }
}
