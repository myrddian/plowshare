import { createHash } from 'node:crypto';
import { lstat, mkdir, readFile, realpath } from 'node:fs/promises';
import { basename, dirname, isAbsolute, join } from 'node:path';
import {
  atomicPrivateWrite,
  canonicalServer,
  connectionAccount,
  localLock,
  privateDirectory,
  privateFile,
  userConfigDirectory,
} from 'plowshare-client-node/connections';
import { errorCode } from 'plowshare-client-ts/binding/values';
import { decodeNativeConfig } from './native-config.ts';
import type { NativeMcpConfig, NativeOwner } from './native-config.ts';
import { NativeProfiles } from './native-profiles.ts';
import type { NativeProfile, NativeProfileStore } from './native-profiles.ts';
import { object, text } from './codec.ts';

export interface NativeConfigurationFile {
  readonly scope: 'user' | 'project';
  readonly file: string;
  readonly profiles: readonly string[];
}
/** Shared authoring files, never auto-started. Each entry retains its server/account owner.
 * Project files contain environment references, not resolved credentials. Process locks,
 * receipts and runtime ownership stay in the explicit private journal directory.
 */
export interface NativeConfigurationStore extends NativeProfileStore {
  /** Lists owned names without creating files or starting hosts. Project roots must be canonical. */
  files(root?: string): Promise<readonly NativeConfigurationFile[]>;
}
interface Configuration {
  readonly version: 1;
  readonly profiles: readonly NativeMcpConfig[];
}
const sameOwner = (a: NativeOwner, b: NativeOwner) =>
  a.origin === b.origin && a.account === b.account;
const revision = (config: NativeMcpConfig) =>
  createHash('sha256').update(JSON.stringify(config)).digest('hex');
function entryName(value: string): string {
  if (!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/.test(value))
    throw new Error('Invalid MCP configuration profile name');
  return value;
}
function decode(source: unknown): Configuration {
  const row = object(source);
  if (
    row.version !== 1 ||
    Object.keys(row).some((key) => !['version', 'profiles'].includes(key)) ||
    !Array.isArray(row.profiles) ||
    row.profiles.length > 128
  )
    throw new Error('Invalid MCP configuration file');
  const values: readonly unknown[] = row.profiles;
  const profiles = values.map(decodeNativeConfig);
  const keys = profiles.map((config) =>
    JSON.stringify([config.owner.origin, config.owner.account, config.profile]),
  );
  if (new Set(keys).size !== keys.length)
    throw new Error('Duplicate MCP configuration profile');
  return { version: 1, profiles };
}
/** Checks existing storage without creating it or changing project-directory permissions. */
async function directory(directory: string): Promise<void> {
  if (!isAbsolute(directory))
    throw new Error('MCP configuration path must be absolute');
  try {
    const info = await lstat(directory);
    if (
      !info.isDirectory() ||
      info.isSymbolicLink() ||
      (await realpath(directory)) !== directory
    )
      throw new Error('MCP configuration must use a real directory');
  } catch (error) {
    if (errorCode(error) !== 'ENOENT') throw error;
    // A missing leaf is allowed, but a symlink or missing ancestor is not.
    const parent = dirname(directory);
    if (parent === directory) throw error;
    await directoryParent(parent);
  }
}
async function directoryParent(parent: string): Promise<void> {
  if ((await realpath(parent)) !== parent)
    throw new Error('MCP configuration directory moved');
}
async function read(file: string): Promise<Configuration> {
  if (!isAbsolute(text(file, 4096)))
    throw new Error('MCP configuration path must be absolute');
  await directory(dirname(file));
  await privateFile(file);
  let bytes: Buffer;
  try {
    bytes = await readFile(file);
  } catch (error) {
    if (errorCode(error) === 'ENOENT') return { version: 1, profiles: [] };
    throw error;
  }
  if (bytes.byteLength > 2_000_000)
    throw new Error('MCP configuration file exceeds capacity');
  let source: unknown;
  try {
    source = JSON.parse(bytes.toString('utf8'));
  } catch {
    // Parser diagnostics can include source text. Report only the invalid format.
    throw new Error('MCP configuration file is invalid JSON');
  }
  return decode(source);
}
export class NativeConfigurations implements NativeConfigurationStore {
  private readonly standalone: NativeProfiles;
  private readonly owner: NativeOwner;
  constructor(
    owner: NativeOwner,
    private readonly userDirectory = userConfigDirectory(),
  ) {
    if (!isAbsolute(text(userDirectory, 4096)))
      throw new Error('MCP user configuration path must be absolute');
    this.owner = {
      origin: canonicalServer(owner.origin),
      account: connectionAccount(owner.account),
    };
    this.standalone = new NativeProfiles(this.owner);
  }
  async files(root?: string): Promise<readonly NativeConfigurationFile[]> {
    // Resolve platform aliases (for example a temporary directory's ancestors)
    // once at discovery. The configuration directory itself may not be a link.
    let userDirectory: string;
    try {
      const info = await lstat(this.userDirectory);
      if (!info.isDirectory() || info.isSymbolicLink())
        throw new Error('MCP user configuration must use a real directory');
      userDirectory = await realpath(this.userDirectory);
    } catch (error) {
      if (errorCode(error) !== 'ENOENT') throw error;
      userDirectory = join(
        await realpath(dirname(this.userDirectory)),
        basename(this.userDirectory),
      );
    }
    const locations: { scope: 'user' | 'project'; file: string }[] = [
      { scope: 'user', file: join(userDirectory, 'mcp.json') },
    ];
    if (root !== undefined) {
      if (!isAbsolute(text(root, 4096)) || (await realpath(root)) !== root)
        throw new Error(
          'Reconnect the project folder before editing MCP settings',
        );
      locations.push({
        scope: 'project',
        file: join(root, '.plowshare', 'mcp.json'),
      });
    }
    return Promise.all(
      locations.map(async (location) => ({
        ...location,
        profiles: (await read(location.file)).profiles
          .filter((config) => sameOwner(config.owner, this.owner))
          .map((config) => config.profile),
      })),
    );
  }
  async read(file: string, entry?: string): Promise<NativeProfile> {
    if (entry === undefined) return this.standalone.read(file);
    entryName(entry);
    const config = (await read(file)).profiles.find(
      (config) =>
        config.profile === entry && sameOwner(config.owner, this.owner),
    );
    if (!config) throw new Error('MCP profile is missing for this login');
    return { file, entry, config, revision: revision(config) };
  }
  async save(
    file: string,
    input: NativeMcpConfig,
    expectedRevision: string | null,
    entry?: string,
  ): Promise<NativeProfile> {
    if (entry === undefined)
      return this.standalone.save(file, input, expectedRevision);
    if (!isAbsolute(text(file, 4096)))
      throw new Error('MCP configuration path must be absolute');
    if (expectedRevision !== null && !/^[a-f0-9]{64}$/.test(expectedRevision))
      throw new Error('Invalid MCP configuration profile revision');
    const config = decodeNativeConfig(input);
    if (
      entryName(entry) !== config.profile ||
      !sameOwner(config.owner, this.owner)
    )
      throw new Error(
        'MCP profile name or login differs from its configuration entry',
      );
    // Locks live under the shared private user root: never chmod a checked-in project
    // directory to acquire the SDK lock. Both Desktop and TUI serialize on the same path.
    await privateDirectory(this.userDirectory);
    const locks = join(this.userDirectory, 'mcp-locks');
    const key = createHash('sha256').update(file).digest('hex');
    return localLock(locks, key, async () => {
      const current = await read(file);
      const original = current.profiles.find(
        (value) =>
          value.profile === entry && sameOwner(value.owner, this.owner),
      );
      if ((original ? revision(original) : null) !== expectedRevision)
        throw new Error('MCP profile changed; reload before saving');
      const profiles = original
        ? current.profiles.map((value) => (value === original ? config : value))
        : [...current.profiles, config];
      const next = decode({ version: 1, profiles });
      await directory(dirname(file));
      await mkdir(dirname(file), { recursive: true, mode: 0o700 });
      await directory(dirname(file));
      await atomicPrivateWrite(file, next);
      return { file, entry, config, revision: revision(config) };
    });
  }
}
