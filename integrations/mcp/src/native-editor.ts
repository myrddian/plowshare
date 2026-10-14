import type { NativeMcpConfig, NativeOwner } from './native-config.ts';
import {
  DEFAULT_CLIENT_INFO,
  DEFAULT_LIMITS,
  boundedInteger,
  MAX_TIMER_MS,
  LIMIT_CEILINGS,
} from './settings.ts';

/** Neutral form vocabulary. Secret material is never part of this model: transports
 * select environment references only. Native command decoding remains authoritative.
 */
export const NATIVE_FIELDS = [
  ['instance', 'Client instance ID', 'common'],
  ['profile', 'Connection name', 'common'],
  ['project', 'Execution project', 'common'],
  ['prefix', 'Tool prefix', 'common'],
  ['journalDirectory', 'Private receipt directory (absolute path)', 'common'],
  ['protocol', 'MCP protocol', 'common'],
  ['endpoint', 'MCP HTTP endpoint', 'http'],
  ['header', 'Authorization header', 'http'],
  [
    'authorizationEnvironment',
    'Credential environment reference (empty for anonymous)',
    'http',
  ],
  ['executable', 'Executable (absolute path)', 'stdio'],
  ['directory', 'Working directory (absolute path)', 'stdio'],
  [
    'treeTerminationExecutable',
    'Windows taskkill executable (absolute path)',
    'stdio',
  ],
  ['timeoutMs', 'Connection timeout (milliseconds)', 'advanced'],
  ['timeoutSeconds', 'Tool deadline (seconds)', 'advanced'],
  ['shutdownGraceMs', 'Shutdown grace (milliseconds)', 'advanced'],
  ['maxRecoveryCatalogues', 'Receipt catalogue archive budget', 'advanced'],
  ['clientName', 'MCP client name', 'advanced'],
  ['clientVersion', 'MCP client version', 'advanced'],
] as const;
export type NativeField = (typeof NATIVE_FIELDS)[number][0];
export type NativeDraft = Record<NativeField, string>;
export interface NativeEnvironmentReference {
  readonly name: string;
  readonly reference: string;
}
export function nativeDraft(
  project: string,
  config?: NativeMcpConfig,
): NativeDraft {
  return {
    instance: config?.instance ?? '',
    profile: config?.profile ?? '',
    project: config?.project ?? project,
    prefix: config?.prefix ?? '',
    journalDirectory: config?.journalDirectory ?? '',
    protocol: config?.protocol ?? '2026-07-28',
    endpoint:
      config?.transport.kind === 'http' ? config.transport.endpoint : '',
    header:
      config?.transport.kind === 'http'
        ? (config.transport.authorization?.header ?? 'Authorization')
        : 'Authorization',
    authorizationEnvironment:
      config?.transport.kind === 'http'
        ? (config.transport.authorization?.environment ?? '')
        : '',
    executable:
      config?.transport.kind === 'stdio' ? config.transport.executable : '',
    directory:
      config?.transport.kind === 'stdio' ? config.transport.directory : '',
    treeTerminationExecutable:
      config?.transport.kind === 'stdio'
        ? (config.transport.treeTerminationExecutable ?? '')
        : '',
    timeoutMs: String(config?.timeoutMs ?? 30000),
    timeoutSeconds: String(config?.timeoutSeconds ?? 30),
    shutdownGraceMs: String(config?.shutdownGraceMs ?? 2000),
    maxRecoveryCatalogues: String(config?.maxRecoveryCatalogues ?? 128),
    clientName: config?.clientInfo.name ?? DEFAULT_CLIENT_INFO.name,
    clientVersion: config?.clientInfo.version ?? DEFAULT_CLIENT_INFO.version,
  };
}
export function nativeFormConfig(options: {
  readonly owner: NativeOwner;
  readonly draft: NativeDraft;
  readonly kind: 'http' | 'stdio';
  readonly args: readonly string[];
  readonly environment: readonly NativeEnvironmentReference[];
  readonly allowHttp: boolean;
  readonly allowPrivateNetwork: boolean;
  readonly limits?: NativeMcpConfig['limits'];
}): NativeMcpConfig {
  const { draft, kind } = options;
  if (draft.protocol !== '2026-07-28' && draft.protocol !== '2025-11-25')
    throw new Error('Select a supported MCP protocol');
  if (!['Authorization', 'X-API-Key', 'Api-Key'].includes(draft.header))
    throw new Error('Select a supported credential header');
  const header = draft.header;
  if (
    header !== 'Authorization' &&
    header !== 'X-API-Key' &&
    header !== 'Api-Key'
  )
    throw new Error('Invalid header');
  if (
    new Set(options.environment.map((row) => row.name)).size !==
    options.environment.length
  )
    throw new Error('Duplicate process environment name');
  const number = (field: NativeField, max: number) =>
    boundedInteger(Number(draft[field]), 1, max);
  const limits = options.limits ?? DEFAULT_LIMITS;
  for (const key of Object.keys(
    DEFAULT_LIMITS,
  ) as (keyof typeof DEFAULT_LIMITS)[])
    boundedInteger(limits[key], 1, LIMIT_CEILINGS[key]);
  return {
    owner: options.owner,
    clientInfo: { name: draft.clientName, version: draft.clientVersion },
    instance: draft.instance,
    profile: draft.profile,
    project: draft.project,
    protocol: draft.protocol,
    prefix: draft.prefix,
    journalDirectory: draft.journalDirectory,
    timeoutMs: number('timeoutMs', MAX_TIMER_MS),
    timeoutSeconds: number('timeoutSeconds', 300),
    shutdownGraceMs: number('shutdownGraceMs', MAX_TIMER_MS),
    maxRecoveryCatalogues: number(
      'maxRecoveryCatalogues',
      Number.MAX_SAFE_INTEGER,
    ),
    limits,
    transport:
      kind === 'http'
        ? {
            kind,
            endpoint: draft.endpoint,
            allowHttp: options.allowHttp,
            allowPrivateNetwork: options.allowPrivateNetwork,
            authorization: draft.authorizationEnvironment
              ? { header, environment: draft.authorizationEnvironment }
              : null,
          }
        : {
            kind,
            executable: draft.executable,
            directory: draft.directory,
            args: options.args,
            environment: Object.fromEntries(
              options.environment.map((row) => [row.name, row.reference]),
            ),
            treeTerminationExecutable: draft.treeTerminationExecutable || null,
          },
  };
}
export { DEFAULT_LIMITS } from './settings.ts';
