import { isAbsolute } from 'node:path';
import { canonicalServer } from 'plowshare-client-node/connections';
import { object, text } from './codec.ts';
import { uuid } from './journal.ts';
import type { McpWorkerOptions } from './provider.ts';
import {
  boundedInteger as integer,
  renewalFraction,
  DEFAULT_LIMITS,
  LIMIT_CEILINGS,
  DEFAULT_CLIENT_INFO,
  MAX_TIMER_MS,
} from './settings.ts';
import type { McpLimits, McpClientInfo } from './settings.ts';

export interface WorkerConfig {
  readonly binding: string;
  readonly generation: number;
  readonly options: McpWorkerOptions;
  readonly pollMs: number;
  readonly renewalFraction: number;
}
export interface IntegrationConfig {
  readonly origin: string;
  readonly tokenEnvironment: string;
  readonly journalDirectory: string;
  readonly pollMs: number;
  readonly requestTimeoutMs: number;
  readonly clientInfo: McpClientInfo;
  readonly maxRecoveryCatalogues: number;
  readonly workers: readonly WorkerConfig[];
}
function fields(
  raw: Record<string, unknown>,
  allowed: readonly string[],
): void {
  if (Object.keys(raw).some((key) => !allowed.includes(key)))
    throw new Error('Unknown MCP configuration field');
}
/** Timeouts must come from the deployment file, never a hidden adapter or SDK fallback. */
function timeout(
  value: unknown,
  name: string,
  min: number,
  max: number,
): number {
  if (value === undefined)
    throw new Error(`Missing required configuration timeout: ${name}`);
  return integer(value, min, max);
}
function boolean(value: unknown): boolean {
  if (typeof value !== 'boolean')
    throw new Error('Expected an explicit MCP network policy');
  return value;
}
const WORKER_FIELDS = [
  'protocol',
  'allowHttp',
  'allowPrivateNetwork',
  'timeoutMs',
  'timeoutSeconds',
  'pollMs',
  'renewalFraction',
  'limits',
] as const;
export function decodeLimits(
  source: unknown,
  inherited: McpLimits = DEFAULT_LIMITS,
): McpLimits {
  const raw = source === undefined ? {} : object(source);
  fields(raw, Object.keys(DEFAULT_LIMITS));
  if (Object.values(raw).some((value) => value === null))
    throw new Error('Null MCP limit');
  return Object.freeze({
    maxToolNameLength: integer(
      raw.maxToolNameLength ?? inherited.maxToolNameLength,
      1,
      LIMIT_CEILINGS.maxToolNameLength,
    ),
    maxDescriptionLength: integer(
      raw.maxDescriptionLength ?? inherited.maxDescriptionLength,
      1,
      LIMIT_CEILINGS.maxDescriptionLength,
    ),
    maxCursorLength: integer(
      raw.maxCursorLength ?? inherited.maxCursorLength,
      1,
      LIMIT_CEILINGS.maxCursorLength,
    ),
    // The shortest prefix, one slug character and 128-bit hash require 36 characters.
    // Discovery also validates the actual binding prefix against this budget.
    maxOfferedNameLength: integer(
      raw.maxOfferedNameLength ?? inherited.maxOfferedNameLength,
      36,
      LIMIT_CEILINGS.maxOfferedNameLength,
    ),
    maxPages: integer(
      raw.maxPages ?? inherited.maxPages,
      1,
      LIMIT_CEILINGS.maxPages,
    ),
    maxWireBytes: integer(
      raw.maxWireBytes ?? inherited.maxWireBytes,
      1,
      LIMIT_CEILINGS.maxWireBytes,
    ),
    maxCatalogueBytes: integer(
      raw.maxCatalogueBytes ?? inherited.maxCatalogueBytes,
      1,
      LIMIT_CEILINGS.maxCatalogueBytes,
    ),
    maxResultBytes: integer(
      raw.maxResultBytes ?? inherited.maxResultBytes,
      1,
      LIMIT_CEILINGS.maxResultBytes,
    ),
    maxContentBlocks: integer(
      raw.maxContentBlocks ?? inherited.maxContentBlocks,
      1,
      LIMIT_CEILINGS.maxContentBlocks,
    ),
  });
}
/** Shared defaults are explicit operator policy; each binding can override them independently. */
export function decodeConfig(source: unknown): IntegrationConfig {
  const raw = object(source);
  fields(raw, [
    'origin',
    'tokenEnvironment',
    'journalDirectory',
    'pollMs',
    'workers',
    'requestTimeoutMs',
    'clientInfo',
    'maxClientInfoLength',
    'maxWorkers',
    'maxRecoveryCatalogues',
    'defaults',
  ]);
  if (Object.values(raw).some((value) => value === null))
    throw new Error('Null MCP configuration field');
  const defaults = raw.defaults === undefined ? {} : object(raw.defaults);
  fields(defaults, WORKER_FIELDS);
  if (Object.values(defaults).some((value) => value === null))
    throw new Error('Null MCP default');
  // Validate even unused defaults, so an overriding worker cannot hide invalid configuration.
  if (
    defaults.protocol !== undefined &&
    defaults.protocol !== '2026-07-28' &&
    defaults.protocol !== '2025-11-25'
  )
    throw new Error('Unsupported MCP protocol profile');
  if (defaults.allowHttp !== undefined) boolean(defaults.allowHttp);
  if (defaults.allowPrivateNetwork !== undefined)
    boolean(defaults.allowPrivateNetwork);
  if (defaults.timeoutMs !== undefined)
    integer(defaults.timeoutMs, 1, MAX_TIMER_MS);
  if (defaults.timeoutSeconds !== undefined)
    integer(defaults.timeoutSeconds, 1, 300);
  if (defaults.pollMs !== undefined) integer(defaults.pollMs, 1, MAX_TIMER_MS);
  if (defaults.renewalFraction !== undefined)
    renewalFraction(defaults.renewalFraction);
  const sharedLimits = decodeLimits(defaults.limits);
  const pollMs = integer(raw.pollMs ?? 250, 1, MAX_TIMER_MS);
  const client =
    raw.clientInfo === undefined
      ? { ...DEFAULT_CLIENT_INFO }
      : object(raw.clientInfo);
  fields(client, ['name', 'version']);
  const maxClientInfoLength = integer(
    raw.maxClientInfoLength ?? 128,
    1,
    Number.MAX_SAFE_INTEGER,
  );
  const clientInfo = Object.freeze({
    name: text(client.name, maxClientInfoLength),
    version: text(client.version, maxClientInfoLength),
  });
  const tokenEnvironment = text(raw.tokenEnvironment, 100);
  if (!/^[A-Z][A-Z0-9_]*$/.test(tokenEnvironment))
    throw new Error('Invalid executor token environment name');
  const journalDirectory = text(raw.journalDirectory, 4096);
  if (!isAbsolute(journalDirectory))
    throw new Error('MCP journal directory must be absolute');
  const maxWorkers = integer(raw.maxWorkers ?? 32, 1, Number.MAX_SAFE_INTEGER);
  if (
    !Array.isArray(raw.workers) ||
    !raw.workers.length ||
    raw.workers.length > maxWorkers
  )
    throw new Error('MCP bindings exceed configured worker count');
  const sources: readonly unknown[] = raw.workers;
  const workers = sources.map((source): WorkerConfig => {
    const worker = object(source);
    fields(worker, ['binding', 'generation', ...WORKER_FIELDS]);
    if (Object.values(worker).some((value) => value === null))
      throw new Error('Null MCP worker field');
    const effective = { ...defaults, ...worker };
    const protocol = effective.protocol;
    if (protocol !== '2026-07-28' && protocol !== '2025-11-25')
      throw new Error('Unsupported MCP protocol profile');
    const budget = decodeLimits(worker.limits, sharedLimits);
    return Object.freeze<WorkerConfig>({
      binding: uuid(worker.binding),
      generation: integer(worker.generation, 1, Number.MAX_SAFE_INTEGER),
      pollMs: integer(effective.pollMs ?? pollMs, 1, MAX_TIMER_MS),
      renewalFraction: renewalFraction(effective.renewalFraction ?? 1 / 3),
      options: Object.freeze({
        protocol,
        limits: budget,
        timeoutSeconds: timeout(
          effective.timeoutSeconds,
          'timeoutSeconds',
          1,
          300,
        ),
        policy: Object.freeze({
          allowHttp: boolean(effective.allowHttp),
          allowPrivateNetwork: boolean(effective.allowPrivateNetwork),
          timeoutMs: timeout(effective.timeoutMs, 'timeoutMs', 1, MAX_TIMER_MS),
          maxWireBytes: budget.maxWireBytes,
        }),
      }),
    });
  });
  if (new Set(workers.map((worker) => worker.binding)).size !== workers.length)
    throw new Error('Duplicate MCP worker binding');
  return Object.freeze({
    origin: canonicalServer(text(raw.origin, 2048)),
    tokenEnvironment,
    journalDirectory,
    pollMs,
    requestTimeoutMs: timeout(
      raw.requestTimeoutMs,
      'requestTimeoutMs',
      1,
      MAX_TIMER_MS,
    ),
    clientInfo,
    maxRecoveryCatalogues: integer(
      raw.maxRecoveryCatalogues ?? 128,
      1,
      Number.MAX_SAFE_INTEGER,
    ),
    workers: Object.freeze(workers),
  });
}
