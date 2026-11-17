import { constants } from 'node:fs';
import { open } from 'node:fs/promises';
import { absolutePrivatePath, decodeComputerHostConfig } from './config.ts';
import type { ComputerHostConfig } from './config.ts';
import { DeadlineExceeded } from './deadline.ts';
import type { OperationDeadline } from './deadline.ts';

export type PrivateConfigCode =
  | 'CONFIG_PATH'
  | 'CONFIG_IO'
  | 'CONFIG_LIMIT'
  | 'CONFIG_ENCODING'
  | 'CONFIG_JSON'
  | 'CONFIG_PERMISSIONS'
  | 'CONFIG_BUSY'
  | 'CONFIG_CLEANUP_UNCERTAIN'
  | 'DEADLINE_EXPIRED';

/** Fixed diagnostics deliberately omit filesystem paths and untrusted JSON. */
export class PrivateConfigError extends Error {
  constructor(readonly code: PrivateConfigCode) {
    super(code);
    this.name = 'PrivateConfigError';
  }
}

export interface PrivateConfigFile {
  metadata(): Promise<{
    readonly regular: boolean;
    readonly size: number;
    readonly uid: number;
    readonly mode: number;
  }>;
  read(bytes: Uint8Array, offset: number, length: number): Promise<number>;
  close(): Promise<void>;
}

/** POSIX boundary: implementations must open the exact path without following links. */
export interface PrivateConfigFiles {
  readonly uid: number | undefined;
  open(path: string): Promise<PrivateConfigFile>;
}

const localFiles: PrivateConfigFiles = {
  uid: process.getuid?.(),
  async open(path) {
    const handle = await open(
      path,
      constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK,
    );
    return {
      async metadata() {
        const info = await handle.stat();
        return {
          regular: info.isFile(),
          size: info.size,
          uid: info.uid,
          mode: info.mode,
        };
      },
      async read(bytes, offset, length) {
        return (await handle.read(bytes, offset, length, offset)).bytesRead;
      },
      close: () => handle.close(),
    };
  },
};

/** Reject duplicate keys (including escaped aliases) before projecting operator DTOs. */
export function parsePrivateConfigJson(source: string): unknown {
  try {
    const tokens =
      source.match(/"(?:\\.|[^"\\])*"|[{}[\]:,]|[^\s{}[\]:,]+/gu) ?? [];
    const stack: (Set<string> | null)[] = [];
    for (let i = 0; i < tokens.length; i++) {
      const token = tokens[i];
      if (token === '{' || token === '[') {
        stack.push(token === '{' ? new Set() : null);
        if (stack.length > 64) throw new PrivateConfigError('CONFIG_JSON');
      } else if (token === '}' || token === ']') stack.pop();
      else if (token?.startsWith('"') && tokens[i + 1] === ':') {
        const keys = stack.at(-1);
        const key: unknown = JSON.parse(token);
        if (typeof key !== 'string' || keys == null || keys.has(key))
          throw new PrivateConfigError('CONFIG_JSON');
        keys.add(key);
      }
    }
    return JSON.parse(source) as unknown;
  } catch {
    throw new PrivateConfigError('CONFIG_JSON');
  }
}

async function within<T>(
  work: Promise<T>,
  deadline: OperationDeadline,
): Promise<T> {
  let detach = () => {};
  try {
    deadline.requireActive();
    const result = await Promise.race([
      work,
      new Promise<never>((_, reject) => {
        const expired = () =>
          reject(new PrivateConfigError('DEADLINE_EXPIRED'));
        deadline.signal.addEventListener('abort', expired, { once: true });
        detach = () => deadline.signal.removeEventListener('abort', expired);
        if (deadline.signal.aborted) expired();
      }),
    ]);
    deadline.requireActive();
    return result;
  } catch (error) {
    if (error instanceof DeadlineExceeded)
      throw new PrivateConfigError('DEADLINE_EXPIRED');
    throw error;
  } finally {
    detach();
  }
}

export interface ComputerConfigReader {
  read(path: string, deadline: OperationDeadline): Promise<ComputerHostConfig>;
  /** A timeout does not close an FD: drain waits the original read and close chain. */
  drain(deadline: OperationDeadline): Promise<void>;
}

/** One physical read at a time; late open/read owns its FD until close settles. */
export class FileComputerConfigReader implements ComputerConfigReader {
  private pending: Promise<void> | undefined;
  private uncertainFile: PrivateConfigFile | undefined;

  constructor(private readonly files: PrivateConfigFiles = localFiles) {}

  async read(
    path: string,
    deadline: OperationDeadline,
  ): Promise<ComputerHostConfig> {
    if (this.uncertainFile !== undefined)
      throw new PrivateConfigError('CONFIG_CLEANUP_UNCERTAIN');
    if (this.pending !== undefined) throw new PrivateConfigError('CONFIG_BUSY');
    deadline.requireActive();
    try {
      absolutePrivatePath(path);
    } catch {
      throw new PrivateConfigError('CONFIG_PATH');
    }
    // uid/mode enforcement is intentionally POSIX-only; no Windows ACL equivalence is claimed.
    if (this.files.uid === undefined)
      throw new PrivateConfigError('CONFIG_PERMISSIONS');
    const physical = this.load(path, deadline);
    const owned = physical
      .then(
        () => {},
        () => {},
      )
      .then(() => {
        if (this.pending === owned) this.pending = undefined;
      });
    this.pending = owned;
    return within(physical, deadline);
  }

  private async load(
    path: string,
    deadline: OperationDeadline,
  ): Promise<ComputerHostConfig> {
    let file: PrivateConfigFile | undefined;
    let result: ComputerHostConfig | undefined;
    let failure: PrivateConfigError | undefined;
    try {
      deadline.requireActive();
      file = await this.files.open(path);
      deadline.requireActive();
      const info = await file.metadata();
      deadline.requireActive();
      if (
        !info.regular ||
        info.uid !== this.files.uid ||
        (info.mode & 0o177) !== 0
      )
        throw new PrivateConfigError('CONFIG_PERMISSIONS');
      if (
        !Number.isSafeInteger(info.size) ||
        info.size < 0 ||
        info.size > 65_536
      )
        throw new PrivateConfigError('CONFIG_LIMIT');
      const bytes = new Uint8Array(65_537);
      let used = 0;
      while (used < bytes.length) {
        deadline.requireActive();
        const count = await file.read(bytes, used, bytes.length - used);
        deadline.requireActive();
        if (
          !Number.isSafeInteger(count) ||
          count < 0 ||
          count > bytes.length - used
        )
          throw new PrivateConfigError('CONFIG_IO');
        if (count === 0) break;
        used += count;
      }
      if (used > 65_536) throw new PrivateConfigError('CONFIG_LIMIT');
      let source: string;
      try {
        source = new TextDecoder('utf-8', { fatal: true }).decode(
          bytes.subarray(0, used),
        );
      } catch {
        throw new PrivateConfigError('CONFIG_ENCODING');
      }
      const value = parsePrivateConfigJson(source);
      deadline.requireActive();
      try {
        result = decodeComputerHostConfig(value);
      } catch {
        throw new PrivateConfigError('CONFIG_JSON');
      }
    } catch (error) {
      failure =
        error instanceof PrivateConfigError
          ? error
          : new PrivateConfigError(
              error instanceof DeadlineExceeded
                ? 'DEADLINE_EXPIRED'
                : 'CONFIG_IO',
            );
    }
    if (file !== undefined) {
      try {
        await file.close();
      } catch {
        // A rejected close is not proof the FD closed. Retain its owner and refuse replacement.
        this.uncertainFile = file;
        failure = new PrivateConfigError('CONFIG_CLEANUP_UNCERTAIN');
      }
    }
    if (failure !== undefined) throw failure;
    if (result === undefined) throw new PrivateConfigError('CONFIG_IO');
    return result;
  }

  async drain(deadline: OperationDeadline): Promise<void> {
    deadline.requireActive();
    if (this.pending !== undefined) await within(this.pending, deadline);
    if (this.uncertainFile !== undefined)
      throw new PrivateConfigError('CONFIG_CLEANUP_UNCERTAIN');
  }
}
