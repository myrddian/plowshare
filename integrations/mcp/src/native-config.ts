import { isAbsolute } from 'node:path';
import { readFile } from 'node:fs/promises';
import {
  canonicalServer,
  connectionAccount,
  privateFile,
} from 'plowshare-client-node/connections';
import { object, text } from './codec.ts';
import { decodeLimits } from './config.ts';
import { endpoint } from './http.ts';
import {
  boundedInteger,
  MAX_TIMER_MS,
  DEFAULT_CLIENT_INFO,
} from './settings.ts';
import type { McpLimits, McpClientInfo } from './settings.ts';
import type { McpProtocol } from './contracts.ts';

export interface NativeOwner {
  readonly origin: string;
  readonly account: string;
}
export type NativeTransport =
  | {
      readonly kind: 'http';
      readonly endpoint: string;
      readonly allowHttp: boolean;
      readonly allowPrivateNetwork: boolean;
      readonly authorization: {
        readonly header: 'Authorization' | 'X-API-Key' | 'Api-Key';
        readonly environment: string;
      } | null;
    }
  | {
      readonly kind: 'stdio';
      readonly executable: string;
      readonly args: readonly string[];
      readonly directory: string;
      readonly environment: Readonly<Record<string, string>>;
      readonly treeTerminationExecutable: string | null;
    };
/** Private instance profile, independent of named Plowshare login and server-held credentials. */
export interface NativeMcpConfig {
  readonly owner: NativeOwner;
  readonly clientInfo: McpClientInfo;
  readonly instance: string;
  readonly profile: string;
  readonly project: string;
  readonly protocol: McpProtocol;
  readonly prefix: string;
  readonly journalDirectory: string;
  readonly timeoutMs: number;
  readonly timeoutSeconds: number;
  readonly shutdownGraceMs: number;
  readonly maxRecoveryCatalogues: number;
  readonly limits: McpLimits;
  readonly transport: NativeTransport;
}
function fields(
  row: Record<string, unknown>,
  allowed: readonly string[],
): void {
  if (Object.keys(row).some((key) => !allowed.includes(key)))
    throw new Error('Unknown native MCP configuration field');
}
function path(value: unknown): string {
  const result = text(value, 4096);
  if (!isAbsolute(result)) throw new Error('Native MCP paths must be absolute');
  return result;
}
function name(value: unknown): string {
  const result = text(value, 64);
  if (!/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(result))
    throw new Error('Invalid native MCP identity');
  return result;
}
export function environmentName(value: unknown): string {
  const result = text(value, 1024);
  if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(result))
    throw new Error('Invalid native MCP environment reference');
  return result;
}
function boolean(value: unknown): boolean {
  if (typeof value !== 'boolean')
    throw new Error('Native MCP network policy must be explicit');
  return value;
}
/** Decode every nested field before opening a network connection, lock or process. */
export function decodeNativeConfig(source: unknown): NativeMcpConfig {
  const row = object(source);
  fields(row, [
    'owner',
    'clientInfo',
    'instance',
    'profile',
    'project',
    'protocol',
    'prefix',
    'journalDirectory',
    'timeoutMs',
    'timeoutSeconds',
    'shutdownGraceMs',
    'maxRecoveryCatalogues',
    'limits',
    'transport',
  ]);
  const client =
    row.clientInfo === undefined
      ? { ...DEFAULT_CLIENT_INFO }
      : object(row.clientInfo);
  fields(client, ['name', 'version']);
  const clientInfo = Object.freeze({
    name: text(client.name, 4096),
    version: text(client.version, 4096),
  });
  const owner = object(row.owner);
  fields(owner, ['origin', 'account']);
  const origin = canonicalServer(text(owner.origin, 2048)),
    account = connectionAccount(text(owner.account, 64));
  const protocol = row.protocol;
  if (protocol !== '2026-07-28' && protocol !== '2025-11-25')
    throw new Error('Unsupported native MCP protocol profile');
  const timeoutMs = boundedInteger(row.timeoutMs, 1, MAX_TIMER_MS);
  const raw = object(row.transport);
  let transport: NativeTransport;
  if (raw.kind === 'http') {
    fields(raw, [
      'kind',
      'endpoint',
      'allowHttp',
      'allowPrivateNetwork',
      'authorization',
    ]);
    const allowHttp = boolean(raw.allowHttp);
    const target = endpoint(text(raw.endpoint, 2048), allowHttp).href;
    let authorization: Extract<
      NativeTransport,
      { kind: 'http' }
    >['authorization'] = null;
    if (raw.authorization !== undefined && raw.authorization !== null) {
      const auth = object(raw.authorization);
      fields(auth, ['header', 'environment']);
      if (
        auth.header !== 'Authorization' &&
        auth.header !== 'X-API-Key' &&
        auth.header !== 'Api-Key'
      )
        throw new Error('Invalid native MCP authorization header');
      authorization = Object.freeze({
        header: auth.header,
        environment: environmentName(auth.environment),
      });
    }
    transport = {
      kind: 'http',
      endpoint: target,
      allowHttp,
      allowPrivateNetwork: boolean(raw.allowPrivateNetwork),
      authorization,
    };
  } else if (raw.kind === 'stdio') {
    fields(raw, [
      'kind',
      'executable',
      'args',
      'directory',
      'environment',
      'treeTerminationExecutable',
    ]);
    if (!Array.isArray(raw.args))
      throw new Error('Native MCP arguments must be an explicit vector');
    const arguments_: readonly unknown[] = raw.args;
    const args = arguments_.map((argument) => {
      if (typeof argument !== 'string' || argument.includes('\0'))
        throw new Error('Invalid native MCP process argument');
      return argument;
    });
    const references = object(raw.environment);
    const environment = Object.fromEntries(
      Object.entries(references).map(([key, value]) => [
        environmentName(key),
        environmentName(value),
      ]),
    );
    transport = {
      kind: 'stdio',
      executable: path(raw.executable),
      args: Object.freeze(args),
      directory: path(raw.directory),
      environment: Object.freeze(environment),
      treeTerminationExecutable:
        raw.treeTerminationExecutable === undefined ||
        raw.treeTerminationExecutable === null
          ? null
          : path(raw.treeTerminationExecutable),
    };
  } else throw new Error('Native MCP transport must be HTTP or STDIO');
  const prefix = text(row.prefix, 48);
  if (!/^[a-z][a-z0-9]*(?:_[a-z0-9]+)*_$/.test(prefix))
    throw new Error('Invalid native MCP tool prefix');
  return Object.freeze({
    owner: Object.freeze({ origin, account }),
    clientInfo,
    instance: name(row.instance),
    profile: name(row.profile),
    project: text(row.project, 512),
    protocol,
    prefix,
    journalDirectory: path(row.journalDirectory),
    timeoutMs,
    timeoutSeconds: boundedInteger(row.timeoutSeconds, 1, 300),
    shutdownGraceMs: boundedInteger(row.shutdownGraceMs, 1, MAX_TIMER_MS),
    maxRecoveryCatalogues: boundedInteger(
      row.maxRecoveryCatalogues ?? 128,
      1,
      Number.MAX_SAFE_INTEGER,
    ),
    limits: decodeLimits(row.limits),
    transport: Object.freeze(transport),
  });
}
export async function readNativeConfig(file: string): Promise<NativeMcpConfig> {
  path(file);
  await privateFile(file);
  const bytes = await readFile(file);
  // Profile files are configuration, independently of complete tool catalogue capacity.
  if (bytes.byteLength > 65536)
    throw new Error('Native MCP configuration exceeds authoring-file capacity');
  let raw: unknown;
  try {
    raw = JSON.parse(bytes.toString('utf8'));
  } catch {
    throw new Error('Native MCP profile is invalid JSON');
  }
  return decodeNativeConfig(raw);
}
/** Resolve only explicitly selected secrets/environment; never inherit the parent environment. */
export function nativeEnvironment(
  references: Readonly<Record<string, string>>,
  source: Readonly<Record<string, string | undefined>>,
): Readonly<Record<string, string>> {
  return Object.freeze(
    Object.fromEntries(
      Object.entries(references).map(([key, reference]) => {
        const value = Object.hasOwn(source, reference)
          ? source[reference]
          : undefined;
        if (typeof value !== 'string' || value.includes('\0'))
          throw new Error(
            'Selected native MCP environment value is unavailable',
          );
        return [key, value];
      }),
    ),
  );
}
