import { isAbsolute } from 'node:path';
import { canonicalServer } from 'plowshare-client-node/connections';
import { object, text } from './codec.ts';
import { uuid } from './journal.ts';
import type { McpWorkerOptions } from './provider.ts';

export interface WorkerConfig {
  readonly binding: string;
  readonly generation: number;
  readonly options: McpWorkerOptions;
}
export interface IntegrationConfig {
  readonly origin: string;
  readonly tokenEnvironment: string;
  readonly journalDirectory: string;
  readonly pollMs: number;
  readonly workers: readonly WorkerConfig[];
}
function fields(
  raw: Record<string, unknown>,
  allowed: readonly string[],
): void {
  if (Object.keys(raw).some((key) => !allowed.includes(key)))
    throw new Error('Unknown MCP configuration field');
}
function integer(value: unknown, min: number, max: number): number {
  if (
    typeof value !== 'number' ||
    !Number.isSafeInteger(value) ||
    value < min ||
    value > max
  )
    throw new Error('Invalid bounded MCP configuration number');
  return value;
}
function boolean(value: unknown): boolean {
  if (typeof value !== 'boolean')
    throw new Error('Expected an explicit MCP network policy');
  return value;
}
export function decodeConfig(source: unknown): IntegrationConfig {
  const raw = object(source);
  fields(raw, [
    'origin',
    'tokenEnvironment',
    'journalDirectory',
    'pollMs',
    'workers',
  ]);
  const tokenEnvironment = text(raw.tokenEnvironment, 100);
  if (!/^[A-Z][A-Z0-9_]*$/.test(tokenEnvironment))
    throw new Error('Invalid executor token environment name');
  const journalDirectory = text(raw.journalDirectory, 4096);
  if (!isAbsolute(journalDirectory))
    throw new Error('MCP journal directory must be absolute');
  if (
    !Array.isArray(raw.workers) ||
    !raw.workers.length ||
    raw.workers.length > 32
  )
    throw new Error('Configure between one and 32 MCP bindings');
  const sources: readonly unknown[] = raw.workers;
  const workers = sources.map((source): WorkerConfig => {
    const worker = object(source);
    fields(worker, [
      'binding',
      'generation',
      'protocol',
      'allowHttp',
      'allowPrivateNetwork',
      'timeoutMs',
      'timeoutSeconds',
    ]);
    const protocol = worker.protocol;
    if (protocol !== '2026-07-28' && protocol !== '2025-11-25')
      throw new Error('Unsupported MCP protocol profile');
    return Object.freeze<WorkerConfig>({
      binding: uuid(worker.binding),
      generation: integer(worker.generation, 1, Number.MAX_SAFE_INTEGER),
      options: {
        protocol,
        timeoutSeconds: integer(worker.timeoutSeconds, 1, 300),
        policy: {
          allowHttp: boolean(worker.allowHttp),
          allowPrivateNetwork: boolean(worker.allowPrivateNetwork),
          timeoutMs: integer(worker.timeoutMs, 100, 300000),
        },
      },
    });
  });
  if (new Set(workers.map((worker) => worker.binding)).size !== workers.length)
    throw new Error('Duplicate MCP worker binding');
  return Object.freeze({
    origin: canonicalServer(text(raw.origin, 2048)),
    tokenEnvironment,
    journalDirectory,
    pollMs: integer(raw.pollMs, 50, 10000),
    workers: Object.freeze(workers),
  });
}
