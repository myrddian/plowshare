import { errorCode, isObject } from 'plowshare-client-ts/binding/values';
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
import { dirname, join } from 'node:path';
import {
  atomicPrivateWrite,
  canonicalServer,
  connectionAccount,
  connectionDirectory,
  localLock,
  privateDirectory,
  privateFile,
  userConfigDirectory,
} from './connections.ts';
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
  return join(userConfigDirectory(env), 'credentials');
}
/** Discover login metadata for a client with no last-server preference yet.
 * Never return token values or select between multiple servers for the user. */
export async function savedLoginServers(
  directory = credentialDirectory(),
): Promise<{ server: string; account: string }[]> {
  const sources = [directory];
  const root = dirname(directory);
  try {
    for (const name of (await readdir(join(root, 'connections'))).filter(
      (name) => /^[a-f0-9]{64}$/.test(name),
    ))
      sources.push(join(root, 'connections', name, 'credentials'));
  } catch (error) {
    if (errorCode(error) !== 'ENOENT') throw error;
  }
  if (
    directory === credentialDirectory() &&
    !process.env['PLOWSHARE_CONFIG_DIR'] &&
    !process.env['PLOWSHARE_DESKTOP_CONFIG']
  )
    sources.push(
      join(
        process.env['XDG_CONFIG_HOME'] ?? join(homedir(), '.config'),
        'plowshare',
        'credentials',
      ),
    );
  const servers: { server: string; account: string }[] = [];
  for (const source of new Set(sources)) {
    let names: string[];
    try {
      names = await readdir(source);
    } catch (error) {
      if (errorCode(error) === 'ENOENT') continue;
      throw new CredentialError(
        'Could not read the shared credential directory.',
        { cause: error },
      );
    }
    for (const name of names
      .filter((name) => /^[a-f0-9]{64}\.json$/.test(name))
      .sort()) {
      try {
        const file = join(source, name);
        await privateFile(file);
        const saved: unknown = JSON.parse(await readFile(file, 'utf8'));
        if (
          !isObject(saved) ||
          typeof saved.origin !== 'string' ||
          typeof saved.handle !== 'string'
        )
          continue;
        const scoped =
          source !== directory &&
          source.startsWith(join(root, 'connections') + '/');
        const store = new Credentials(
          saved.origin,
          scoped ? directory : source,
          undefined,
          scoped ? saved.handle : undefined,
        );
        if (
          name !==
            createHash('sha256').update(store.origin).digest('hex') + '.json' ||
          (scoped &&
            source !==
              join(
                connectionDirectory(store.origin, saved.handle, root),
                'credentials',
              ))
        )
          continue;
        const session = await store.session();
        if (
          !servers.some(
            (row) =>
              row.server === store.origin && row.account === session.handle,
          )
        )
          servers.push({ server: store.origin, account: session.handle });
      } catch {
        /* Corrupt or interrupted sessions require explicit login; discovery never renews them. */
      }
    }
  }
  return servers;
}

/** One private file per server/account. Every rotation reloads under a cross-process lock;
 * no caller may renew a cached refresh token. Passwords never reach this store. */
export class Credentials {
  readonly origin: string;
  private readonly file: string;
  private readonly directory: string;
  constructor(
    base: string,
    private readonly legacyDirectory = credentialDirectory(),
    private readonly signal?: AbortSignal,
    private readonly account?: string,
  ) {
    this.origin = canonicalServer(base);
    if (account !== undefined) connectionAccount(account);
    this.directory =
      account === undefined
        ? legacyDirectory
        : join(
            connectionDirectory(this.origin, account, dirname(legacyDirectory)),
            'credentials',
          );
    this.file = join(
      this.directory,
      createHash('sha256').update(this.origin).digest('hex') + '.json',
    );
  }
  private async locked<T>(
    work: () => Promise<T>,
    forLogin = false,
  ): Promise<T> {
    this.signal?.throwIfAborted();
    if (this.account !== undefined) {
      await privateDirectory(dirname(this.legacyDirectory));
      await privateDirectory(
        join(dirname(this.legacyDirectory), 'connections'),
      );
      await privateDirectory(dirname(this.directory));
    }
    await privateDirectory(this.directory);
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
      await this.migrate(forLogin);
      return await work();
    } finally {
      await rm(lock, { recursive: true, force: true });
    }
  }
  /** Move a proven legacy identity under both locks. Never copy a refresh token that another
   * process could rotate; missing/foreign ownership leaves the legacy file intact. */
  private migrationMarker(): string {
    return join(dirname(this.directory), 'credential-migration.json');
  }
  private async markMigrated(): Promise<void> {
    if (this.account === undefined) return;
    await atomicPrivateWrite(this.migrationMarker(), {
      version: 1,
      origin: this.origin,
      account: this.account,
    });
  }
  private async migrate(forLogin = false): Promise<void> {
    if (this.account === undefined) return;
    const marker = this.migrationMarker();
    await privateFile(marker);
    try {
      const raw: unknown = JSON.parse(await readFile(marker, 'utf8'));
      if (
        !forLogin &&
        (!isObject(raw) ||
          raw.version !== 1 ||
          raw.origin !== this.origin ||
          raw.account !== this.account ||
          Object.keys(raw).some(
            (key) => !['version', 'origin', 'account'].includes(key),
          ))
      )
        throw new CredentialError(
          'Credential migration ownership is invalid. Sign in again.',
        );
      return;
    } catch (error) {
      if (errorCode(error) !== 'ENOENT') {
        if (forLogin) return;
        throw error;
      }
    }
    await privateFile(this.file);
    try {
      await readFile(this.file);
      return;
    } catch (error) {
      if (errorCode(error) !== 'ENOENT') throw error;
    }
    const sources = [this.legacyDirectory];
    if (
      this.legacyDirectory === credentialDirectory() &&
      !process.env['PLOWSHARE_CONFIG_DIR'] &&
      !process.env['PLOWSHARE_DESKTOP_CONFIG']
    )
      sources.push(
        join(
          process.env['XDG_CONFIG_HOME'] ?? join(homedir(), '.config'),
          'plowshare',
          'credentials',
        ),
      );
    for (const directory of new Set(sources)) {
      const file = join(
        directory,
        createHash('sha256').update(this.origin).digest('hex') + '.json',
      );
      try {
        await privateFile(file);
        await readFile(file);
      } catch (error) {
        if (errorCode(error) === 'ENOENT') continue;
        throw error;
      }
      let moved = false;
      await localLock(
        directory,
        createHash('sha256').update(this.origin).digest('hex') + '.json',
        async () => {
          let raw: unknown;
          try {
            raw = JSON.parse(await readFile(file, 'utf8'));
          } catch (error) {
            if (errorCode(error) === 'ENOENT') return;
            if (forLogin) return;
            throw new CredentialError(
              'Legacy session is unreadable. Sign in to this account explicitly.',
            );
          }
          if (
            isObject(raw) &&
            typeof raw.handle === 'string' &&
            raw.handle !== this.account
          )
            return;
          let saved: Saved;
          try {
            saved = this.decode(raw, false, forLogin);
          } catch (error) {
            if (forLogin) return;
            throw error;
          }
          if (saved.handle !== this.account) return;
          await chmod(file, 0o600);
          await rename(file, this.file);
          await this.markMigrated();
          moved = true;
        },
      );
      if (moved) return;
    }
  }
  private decode(
    raw: unknown,
    enforceAccount = true,
    allowInterrupted = false,
  ): Saved {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw))
      throw new CredentialError('The saved session is invalid. Sign in again.');
    const saved = raw as Partial<Saved>;
    if (
      Object.keys(saved).some(
        (key) =>
          !['version', 'origin', 'handle', 'tokens', 'renewing'].includes(key),
      ) ||
      saved.version !== 1 ||
      saved.origin !== this.origin ||
      typeof saved.handle !== 'string' ||
      !saved.handle.trim() ||
      (enforceAccount &&
        this.account !== undefined &&
        saved.handle !== this.account) ||
      (saved.renewing !== undefined && typeof saved.renewing !== 'boolean') ||
      !saved.tokens ||
      typeof saved.tokens !== 'object' ||
      Object.keys(saved.tokens).some(
        (key) => !['access', 'refresh'].includes(key),
      ) ||
      typeof saved.tokens.access !== 'string' ||
      !saved.tokens.access ||
      typeof saved.tokens.refresh !== 'string' ||
      !saved.tokens.refresh
    )
      throw new CredentialError(
        'The saved session is invalid or belongs to another account. Sign in again.',
      );
    connectionAccount(saved.handle);
    if (saved.renewing && !allowInterrupted)
      throw new CredentialError(
        'A previous token renewal was interrupted. Sign in again; the saved refresh token will not be replayed.',
      );
    return {
      version: 1,
      origin: this.origin,
      handle: saved.handle,
      tokens: { access: saved.tokens.access, refresh: saved.tokens.refresh },
    };
  }
  private async load(): Promise<Saved> {
    let raw: unknown;
    try {
      await privateFile(this.file);
      raw = JSON.parse(await readFile(this.file, 'utf8'));
    } catch (error) {
      if (errorCode(error) === 'ENOENT') throw new LoginRequired();
      throw new CredentialError(
        'The saved session is unreadable. Sign in again.',
      );
    }
    return this.decode(raw);
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
  private async inferred(): Promise<Credentials | undefined> {
    if (this.account !== undefined) return undefined;
    let names: string[];
    const root = dirname(this.legacyDirectory);
    try {
      names = await readdir(join(root, 'connections'));
    } catch (error) {
      if (errorCode(error) === 'ENOENT') return undefined;
      throw error;
    }
    const accounts = new Set<string>();
    for (const name of names.filter((name) => /^[a-f0-9]{64}$/.test(name))) {
      const file = join(
        root,
        'connections',
        name,
        'credentials',
        createHash('sha256').update(this.origin).digest('hex') + '.json',
      );
      await privateDirectory(join(root, 'connections'));
      await privateDirectory(join(root, 'connections', name));
      try {
        await privateDirectory(join(root, 'connections', name, 'credentials'));
      } catch (error) {
        if (errorCode(error) === 'ENOENT') continue;
        throw error;
      }
      await privateFile(file);
      try {
        const migration: unknown = JSON.parse(
          await readFile(
            join(root, 'connections', name, 'credential-migration.json'),
            'utf8',
          ),
        );
        if (
          isObject(migration) &&
          migration.origin === this.origin &&
          typeof migration.account === 'string' &&
          connectionDirectory(this.origin, migration.account, root) ===
            join(root, 'connections', name)
        )
          accounts.add(migration.account);
      } catch (error) {
        if (errorCode(error) !== 'ENOENT') throw error;
      }
      let raw: unknown;
      try {
        raw = JSON.parse(await readFile(file, 'utf8'));
      } catch (error) {
        if (errorCode(error) === 'ENOENT') continue;
        throw new CredentialError(
          'A saved account session is unreadable. Select an account explicitly.',
          { cause: error },
        );
      }
      const saved = this.decode(raw);
      if (
        join(root, 'connections', name) !==
        connectionDirectory(this.origin, saved.handle, root)
      )
        throw new CredentialError(
          'Saved account identity does not match its storage directory.',
        );
      accounts.add(saved.handle);
    }
    if (accounts.size > 1)
      throw new CredentialError(
        'Multiple accounts are saved for this server. Select a named connection or account explicitly.',
      );
    const account = [...accounts][0];
    return account === undefined
      ? undefined
      : new Credentials(
          this.origin,
          this.legacyDirectory,
          this.signal,
          account,
        );
  }
  async session(): Promise<Session & { handle: string }> {
    const inferred = await this.inferred();
    if (inferred) return inferred.session();
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
    if (this.account !== undefined && handle !== this.account)
      throw new CredentialError(
        'Login handle conflicts with the selected account.',
      );
    return this.locked(async () => {
      const signed = await signIn(door, handle, password);
      if (!signed.mustChangePassword && signed.tokens.refresh !== undefined)
        await this.save({
          version: 1,
          origin: this.origin,
          handle,
          tokens: signed.tokens,
        });
      if (!signed.mustChangePassword && signed.tokens.refresh !== undefined)
        await this.markMigrated();
      return signed;
    }, true);
  }
  async renew(door: Door): Promise<Tokens> {
    const inferred = await this.inferred();
    if (inferred) return inferred.renew(door);
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
    const inferred = await this.inferred();
    if (inferred) return inferred.logout(door);
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
      await this.markMigrated();
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
