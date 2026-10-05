import { errorCode } from 'plowshare-client-ts/binding/values';
import { createHash, randomUUID } from 'node:crypto';
import {
  chmod,
  mkdir,
  open,
  readFile,
  readdir,
  rename,
  rm,
} from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as pause } from 'node:timers/promises';
import {
  MustChangePassword,
  refresh,
  signIn,
} from 'plowshare-client-ts/binding/auth';
import type { Door, Session, Tokens } from 'plowshare-client-ts/binding/auth';

export class CredentialError extends Error {}
export class LoginRequired extends CredentialError {
  constructor() {
    super(
      'Sign in first with plowshare-cli login -- no saved session is available for this server.',
    );
    this.name = 'LoginRequired';
  }
}
interface Saved {
  version: 1;
  origin: string;
  handle: string;
  tokens: Tokens;
  renewing?: boolean;
}
export function credentialDirectory(
  env: Readonly<Record<string, string | undefined>> = process.env,
): string {
  return join(
    env['PLOWSHARE_CONFIG_DIR'] ??
      join(env['XDG_CONFIG_HOME'] ?? join(homedir(), '.config'), 'plowshare'),
    'credentials',
  );
}
/** Discover login metadata for a client with no last-server preference yet.
 * Never return token values or select between multiple servers for the user. */
export async function savedLoginServers(
  directory = credentialDirectory(),
): Promise<{ server: string; account: string }[]> {
  let names: string[];
  try {
    names = await readdir(directory);
  } catch (error) {
    if (errorCode(error) === 'ENOENT') return [];
    throw new CredentialError(
      'Could not read the shared credential directory.',
    );
  }
  const servers: { server: string; account: string }[] = [];
  for (const name of names
    .filter((name) => /^[a-f0-9]{64}\.json$/.test(name))
    .sort()) {
    let saved: Saved;
    try {
      saved = JSON.parse(
        await readFile(join(directory, name), 'utf8'),
      ) as Saved;
    } catch {
      continue;
    }
    if (!saved || typeof saved.origin !== 'string') continue;
    try {
      const store = new Credentials(saved.origin, directory);
      if (
        name !==
        createHash('sha256').update(store.origin).digest('hex') + '.json'
      )
        continue;
      const session = await store.session();
      servers.push({ server: store.origin, account: session.handle });
    } catch {
      /* A corrupt or interrupted session requires an explicit login. */
    }
  }
  return servers;
}
/** One private file per server. Every rotation reloads under a cross-process lock;
 * no caller may renew a cached refresh token. Passwords never reach this store. */
export class Credentials {
  readonly origin: string;
  private readonly file: string;
  constructor(
    base: string,
    private readonly directory = credentialDirectory(),
    private readonly signal?: AbortSignal,
  ) {
    const url = new URL(base);
    if (
      !['http:', 'https:'].includes(url.protocol) ||
      url.username ||
      url.password ||
      url.pathname !== '/' ||
      url.search ||
      url.hash
    )
      throw new CredentialError('Use an HTTP(S) server origin.');
    this.origin = url.origin;
    this.file = join(
      directory,
      createHash('sha256').update(this.origin).digest('hex') + '.json',
    );
  }
  private async locked<T>(work: () => Promise<T>): Promise<T> {
    this.signal?.throwIfAborted();
    await mkdir(this.directory, { recursive: true, mode: 0o700 });
    await chmod(this.directory, 0o700);
    const lock = this.file + '.lock',
      deadline = Date.now() + 10_000;
    for (;;) {
      this.signal?.throwIfAborted();
      try {
        await mkdir(lock, { mode: 0o700 });
        break;
      } catch (error) {
        if (errorCode(error) !== 'EEXIST')
          throw new CredentialError(
            'Could not lock the local credential store.',
          );
        if (Date.now() >= deadline)
          throw new CredentialError(
            'The credential store is busy. Retry after other clients finish authentication; an interrupted login may have left a lock directory.',
          );
        await pause(
          25,
          undefined,
          this.signal === undefined ? {} : { signal: this.signal },
        );
      }
    }
    try {
      return await work();
    } finally {
      await rm(lock, { recursive: true, force: true });
    }
  }
  private async load(): Promise<Saved> {
    let raw: unknown;
    try {
      raw = JSON.parse(await readFile(this.file, 'utf8'));
    } catch (error) {
      if (errorCode(error) === 'ENOENT') throw new LoginRequired();
      throw new CredentialError(
        'The saved session is unreadable. Sign in again.',
      );
    }
    const saved = raw as Partial<Saved> | null;
    if (
      !saved ||
      saved.version !== 1 ||
      saved.origin !== this.origin ||
      typeof saved.handle !== 'string' ||
      !saved.handle.trim() ||
      typeof saved.tokens?.access !== 'string' ||
      !saved.tokens.access ||
      typeof saved.tokens.refresh !== 'string' ||
      !saved.tokens.refresh
    )
      throw new CredentialError('The saved session is invalid. Sign in again.');
    if (saved.renewing)
      throw new CredentialError(
        'A previous token renewal was interrupted. Sign in again; the saved refresh token will not be replayed.',
      );
    return saved as Saved;
  }
  private async save(saved: Saved): Promise<void> {
    const temporary = this.file + '.' + randomUUID();
    try {
      const file = await open(temporary, 'wx', 0o600);
      try {
        await file.writeFile(JSON.stringify(saved));
        await file.sync();
      } finally {
        await file.close();
      }
      await rename(temporary, this.file);
    } finally {
      await rm(temporary, { force: true });
    }
  }
  async session(): Promise<Session & { handle: string }> {
    return this.locked(async () => {
      const saved = await this.load();
      return {
        tokens: saved.tokens,
        handle: saved.handle,
        mustChangePassword: false,
      };
    });
  }
  async login(door: Door, handle: string, password: string): Promise<Session> {
    return this.locked(async () => {
      const signed = await signIn(door, handle, password);
      if (!signed.mustChangePassword && signed.tokens.refresh !== undefined)
        await this.save({
          version: 1,
          origin: this.origin,
          handle,
          tokens: signed.tokens,
        });
      return signed;
    });
  }
  async renew(door: Door): Promise<Tokens> {
    return this.locked(async () => {
      const saved = await this.load();
      let tokens: Tokens;
      await this.save({ ...saved, renewing: true });
      try {
        tokens = await refresh(door, saved.tokens);
      } catch (error) {
        throw new CredentialError(
          'Saved-session renewal did not complete. Sign in again; an uncertain refresh is never retried.',
          { cause: error },
        );
      }
      // Persist BEFORE ticket/upgrade: those may fail after rotation succeeded.
      await this.save({ ...saved, tokens });
      return tokens;
    });
  }
  async logout(door: Door): Promise<void> {
    await this.locked(async () => {
      let saved: Saved;
      try {
        saved = await this.load();
      } catch (error) {
        if (error instanceof LoginRequired) return;
        throw error;
      }
      // Refresh is necessary if the saved access token expired. Retain the new
      // pair if revocation fails, so logout can be retried without token reuse.
      await this.save({ ...saved, renewing: true });
      const tokens = await refresh(door, saved.tokens);
      await this.save({ ...saved, tokens });
      const answer = await door.fetch(`${door.base}/v1/auth/logout`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${tokens.access}` },
      });
      if (answer.status !== 204)
        throw new CredentialError(
          'Server logout failed; local credentials were retained so you can retry.',
        );
      await rm(this.file, { force: true });
    });
  }
}
export async function savedOrLogin(
  door: Door,
  store: Credentials,
  handle?: string,
  password?: string,
): Promise<Session> {
  if (handle?.trim() && password) {
    const signed = await store.login(door, handle, password);
    if (signed.mustChangePassword) throw new MustChangePassword();
    return signed;
  }
  if (handle || password)
    throw new CredentialError(
      'Provide both login handle and password, or use the saved session.',
    );
  return store.session();
}
