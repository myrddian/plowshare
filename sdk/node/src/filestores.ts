import { Worker } from 'node:worker_threads';
import {
  access,
  link,
  lstat,
  mkdir,
  open,
  readFile,
  realpath,
  rm,
} from 'node:fs/promises';
import { constants } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { isAbsolute, join, relative, sep } from 'node:path';
import { createInterface } from 'node:readline/promises';
import { errorCode, errorMessage } from 'plowshare-client-ts/binding/values';
import {
  Connections,
  localLock,
  privateFile,
  userConfigDirectory,
} from './connections.ts';
import {
  decodeFileStoreConfiguration,
  decodeFileStoreDefault,
  fileStoreAlias,
  hasPathControls,
} from './filestore-config.ts';
import type {
  FileStoreConfiguration,
  FileStoreDefault,
} from './filestore-config.ts';
export type {
  FileStoreConfiguration,
  FileStoreDefault,
} from './filestore-config.ts';

export interface LocalFileStore {
  alias: string;
  root: string;
}
export interface FileStoreState {
  status: 'loaded' | 'needs-setup' | 'unavailable';
  registryPath: string;
  stores: LocalFileStore[];
  defaultStore?: string;
  message: string;
}
/** Host registration is independent of authenticated connection state and grants no agent access. */
export interface FileStoreRegistry {
  load(): Promise<FileStoreState>;
  initialize(definition: FileStoreDefault): Promise<FileStoreState>;
  configureDefault(definition: FileStoreDefault): Promise<FileStoreState>;
  resolve(alias: string, path: string): Promise<string>;
}
/** Bound JavaScript evaluation independently of the caller's event loop. Raw values are
 * confined to this configuration boundary and validated before any filesystem operation. */
async function evaluateDefinition(source: string): Promise<unknown> {
  const worker = new Worker(
    `
    const {parentPort, workerData} = require('node:worker_threads');
    import('data:text/javascript;base64,' + Buffer.from(workerData).toString('base64'))
      .then(module => {
        if (!('default' in module) || Object.keys(module).some(key => key !== 'default'))
          throw new Error('Expected only a default export');
        parentPort.postMessage(module.default);
      }).catch(error => { throw error; });
  `,
    {
      eval: true,
      workerData: source,
      resourceLimits: { maxOldGenerationSizeMb: 64 },
    },
  );
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await new Promise<unknown>((resolve, reject) => {
      timer = setTimeout(
        () =>
          reject(
            new Error(
              'filestore.js took longer than two seconds to load. Remove blocking configuration code and retry.',
            ),
          ),
        2000,
      );
      worker.once('message', (value: unknown) => resolve(value));
      worker.once('error', (cause: unknown) =>
        reject(
          new Error(
            'filestore.js could not evaluate. Check its syntax and single default export.',
            { cause },
          ),
        ),
      );
      worker.once('exit', () =>
        reject(new Error('filestore.js exited before returning a definition.')),
      );
    });
  } finally {
    clearTimeout(timer);
    await worker.terminate();
  }
}
/** Only the user's host configuration is evaluated. Never load a registry from an Application or checkout.
 * JavaScript is trusted local configuration, not a sandbox. A short-lived, memory-bounded worker
 * evaluates the exact file bytes with a two-second limit, so malformed or spinning definitions cannot
 * block a client renderer. Each load observes edits; relative imports are unsupported.
 * Cross-process initialization uses an exclusive link to publish a complete file without overwriting an
 * existing registry. Every load rechecks directories; invalid definitions never trigger bootstrap fallback.
 */
export class FileStores implements FileStoreRegistry {
  readonly path: string;
  constructor(readonly directory = userConfigDirectory()) {
    if (!isAbsolute(directory))
      throw new Error('Use an absolute user configuration directory.');
    this.path = join(directory, 'filestore.js');
  }
  private async definition(): Promise<FileStoreConfiguration | undefined> {
    await privateFile(this.path);
    let source: string;
    try {
      source = await readFile(this.path, 'utf8');
    } catch (error) {
      if (errorCode(error) === 'ENOENT') return undefined;
      throw new Error(
        'filestore.js is unreadable. Correct local storage access and retry.',
        { cause: error },
      );
    }
    if (Buffer.byteLength(source) > 65_536)
      throw new Error('filestore.js exceeds 64 KiB.');
    return decodeFileStoreConfiguration(await evaluateDefinition(source));
  }
  private async create(definition: FileStoreDefault): Promise<void> {
    const config: FileStoreConfiguration = {
      version: 1,
      defaultStore: definition.alias,
      fileStores: { [definition.alias]: { root: definition.root } },
    };
    const temporary = this.path + '.' + randomUUID();
    try {
      const handle = await open(temporary, 'wx', 0o600);
      try {
        await handle.writeFile(
          'export default ' + JSON.stringify(config, null, 2) + ';\n',
        );
        await handle.sync();
      } finally {
        await handle.close();
      }
      // Unlike rename, link cannot replace a registry created by an editor or another client.
      await link(temporary, this.path);
    } finally {
      await rm(temporary, { force: true });
    }
  }
  private async prepared(
    config: FileStoreConfiguration,
  ): Promise<FileStoreState> {
    const stores: LocalFileStore[] = [];
    for (const [alias, definition] of Object.entries(config.fileStores)) {
      try {
        await mkdir(definition.root, { recursive: true, mode: 0o700 });
        const info = await lstat(definition.root);
        if (!info.isDirectory() || info.isSymbolicLink())
          throw new Error('Use a real directory, not a file or symbolic link.');
        await access(definition.root, constants.R_OK | constants.X_OK);
        stores.push({ alias, root: await realpath(definition.root) });
      } catch (cause) {
        throw new Error(
          `FileStore ${alias} is unavailable. Check its configured root and directory permissions.`,
          { cause },
        );
      }
    }
    return {
      status: 'loaded',
      registryPath: this.path,
      stores,
      defaultStore: config.defaultStore,
      message: 'Local FileStores loaded.',
    };
  }
  async load(): Promise<FileStoreState> {
    try {
      return await localLock(this.directory, 'filestore', async () => {
        let config = await this.definition();
        if (!config) {
          const defaults = (await new Connections(this.directory).load())
            .fileStoreDefault;
          if (!defaults)
            return {
              status: 'needs-setup',
              registryPath: this.path,
              stores: [],
              message:
                'Create a local FileStore: choose an alias and an absolute directory.',
            };
          try {
            await this.create(defaults);
          } catch (error) {
            if (errorCode(error) !== 'EEXIST') throw error;
          }
          config = await this.definition();
          if (!config)
            throw new Error(
              'filestore.js disappeared during setup. Retry after checking local storage.',
            );
        }
        return this.prepared(config);
      });
    } catch (error) {
      return {
        status: 'unavailable',
        registryPath: this.path,
        stores: [],
        message: `FileStores could not load: ${errorMessage(error)} Correct filestore.js or local storage access, then retry.`,
      };
    }
  }
  async initialize(input: FileStoreDefault): Promise<FileStoreState> {
    const definition = decodeFileStoreDefault(input);
    await localLock(this.directory, 'filestore', async () => {
      const existing = await this.definition();
      if (existing)
        throw new Error(
          'filestore.js already exists. Edit the existing registry, then reload it; setup will not overwrite it.',
        );
      await this.create(definition);
    });
    const state = await this.load();
    if (state.status === 'loaded')
      await new Connections(this.directory).setFileStoreDefault(definition);
    return state;
  }
  async configureDefault(input: FileStoreDefault): Promise<FileStoreState> {
    await new Connections(this.directory).setFileStoreDefault(
      decodeFileStoreDefault(input),
    );
    return this.load();
  }
  async resolve(alias: string, path: string): Promise<string> {
    fileStoreAlias(alias);
    if (
      typeof path !== 'string' ||
      path.length > 4096 ||
      hasPathControls(path) ||
      path.includes('\\') ||
      isAbsolute(path) ||
      path.split('/').some((part) => part === '..' || part === '.')
    )
      throw new Error(
        'Use a relative FileStore path without traversal or control characters.',
      );
    const state = await this.load();
    if (state.status !== 'loaded') throw new Error(state.message);
    const store = state.stores.find((row) => row.alias === alias);
    if (!store)
      throw new Error('Unknown local FileStore alias. Run filestore status.');
    const result = await realpath(join(store.root, path));
    const offset = relative(store.root, result);
    if (isAbsolute(offset) || offset === '..' || offset.startsWith('..' + sep))
      throw new Error('The selected path escapes its FileStore.');
    return result;
  }
}
/** Shared offline management; ordinary online commands never pause for FileStore setup. */
export async function manageFileStores(
  registry: FileStoreRegistry,
  args: readonly string[],
  prompt?: () => Promise<FileStoreDefault | undefined>,
): Promise<FileStoreState | { root: string }> {
  const [action, alias, root] = args;
  if (action === 'status' && args.length === 1) return registry.load();
  if (action === 'setup' && args.length === 1 && prompt) {
    const state = await registry.load();
    if (state.status !== 'needs-setup') return state;
    const input = await prompt();
    return input ? registry.initialize(input) : state;
  }
  if (
    (action === 'setup' || action === 'default') &&
    args.length === 3 &&
    alias &&
    root
  )
    return action === 'setup'
      ? registry.initialize({ alias, root })
      : registry.configureDefault({ alias, root });
  if (action === 'resolve' && (args.length === 2 || args.length === 3) && alias)
    return { root: await registry.resolve(alias, root ?? '') };
  throw new Error(
    'Use filestore status | setup [ALIAS ABSOLUTE_ROOT] | default ALIAS ABSOLUTE_ROOT | resolve ALIAS RELATIVE_PATH.',
  );
}
/** Prompt before a terminal renderer starts; blank input leaves setup pending. */
export async function promptFileStore(
  signal?: AbortSignal,
): Promise<FileStoreDefault | undefined> {
  if (!process.stdin.isTTY || !process.stderr.isTTY)
    throw new Error(
      'FileStore setup needs a terminal, or use filestore setup ALIAS ABSOLUTE_ROOT.',
    );
  const line = createInterface({
    input: process.stdin,
    output: process.stderr,
  });
  try {
    const alias = (
      await line.question('Local FileStore alias (blank to skip): ', {
        ...(signal ? { signal } : {}),
      })
    ).trim();
    if (!alias) return undefined;
    const root = await line.question('Absolute FileStore directory: ', {
      ...(signal ? { signal } : {}),
    });
    return decodeFileStoreDefault({ alias, root });
  } finally {
    line.close();
  }
}
/** The store: prefix is opt-in host resolution; the existing local-root grant remains explicit. */
export async function localStorePath(
  registry: FileStoreRegistry,
  value: string,
): Promise<string> {
  if (!value.startsWith('store:')) return value;
  const reference = value.slice(6),
    split = reference.indexOf('/');
  return registry.resolve(
    split < 0 ? reference : reference.slice(0, split),
    split < 0 ? '' : reference.slice(split + 1),
  );
}
