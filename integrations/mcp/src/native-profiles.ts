import { createHash } from 'node:crypto';
import { dirname, isAbsolute } from 'node:path';
import { lstat, mkdir } from 'node:fs/promises';
import {
  atomicPrivateWrite,
  localLock,
} from 'plowshare-client-node/connections';
import { decodeNativeConfig, readNativeConfig } from './native-config.ts';
import type { NativeMcpConfig, NativeOwner } from './native-config.ts';

export interface NativeProfile {
  readonly file: string;
  readonly revision: string;
  readonly config: NativeMcpConfig;
}
function owned(config: NativeMcpConfig, owner: NativeOwner): void {
  if (
    config.owner.origin !== owner.origin ||
    config.owner.account !== owner.account
  )
    throw new Error('Native MCP profile belongs to another login');
}
function revision(config: NativeMcpConfig): string {
  return createHash('sha256').update(JSON.stringify(config)).digest('hex');
}
/** Profile authoring only. Reads/saves never resolve environment references or start a host.
 * Compare the complete validated revision under the SDK's exclusive private writer lock;
 * a stale editor cannot overwrite another editor or adopt a foreign account's profile.
 */
export interface NativeProfileStore {
  read(file: string): Promise<NativeProfile>;
  save(
    file: string,
    config: NativeMcpConfig,
    expectedRevision: string | null,
  ): Promise<NativeProfile>;
}
export class NativeProfiles implements NativeProfileStore {
  constructor(private readonly owner: NativeOwner) {}
  async read(file: string): Promise<NativeProfile> {
    const config = await readNativeConfig(file);
    owned(config, this.owner);
    return { file, revision: revision(config), config };
  }
  async save(
    file: string,
    input: NativeMcpConfig,
    expectedRevision: string | null,
  ): Promise<NativeProfile> {
    const config = decodeNativeConfig(input);
    owned(config, this.owner);
    if (Buffer.byteLength(JSON.stringify(config)) + 1 > 65536)
      throw new Error('Native MCP profile exceeds authoring-file capacity');
    if (!isAbsolute(file))
      throw new Error('Native MCP profile path must be absolute');
    const directory = dirname(file);
    await mkdir(directory, { recursive: true, mode: 0o700 });
    const info = await lstat(directory);
    // Do not tighten permissions on a user's unrelated/shared directory. The SDK lock
    // operates only in a directory already private to this native instance.
    if (
      !info.isDirectory() ||
      info.isSymbolicLink() ||
      (process.platform !== 'win32' && (info.mode & 0o077) !== 0)
    )
      throw new Error(
        'Choose a dedicated private directory for native MCP profiles',
      );
    return localLock(directory, 'mcp-profile', async () => {
      let present = true;
      try {
        await lstat(file);
      } catch (error) {
        if (!(
          error instanceof Error &&
          'code' in error &&
          error.code === 'ENOENT'
        ))
          throw error;
        present = false;
      }
      if (present) {
        const original = await this.read(file);
        if (original.revision !== expectedRevision)
          throw new Error('Native MCP profile changed; reload before saving');
      } else if (expectedRevision !== null) {
        throw new Error('Native MCP profile is missing; reload before saving');
      }
      await atomicPrivateWrite(file, config);
      return { file, revision: revision(config), config };
    });
  }
}
