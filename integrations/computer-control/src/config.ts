import { isAbsolute } from 'node:path';
import type { ToolBinding } from 'plowshare-client-ts';
import { connectionAccount } from 'plowshare-client-node/connections';
import { computerId } from './contracts.ts';
import { positiveBudget } from './deadline.ts';
import { computerDeclarations } from './provider.ts';
import { decodeBrowserBackendConfig } from './backends/browser.ts';
import type { BrowserBackendConfig } from './backends/browser.ts';
import { decodeMacOSBackendConfig } from './backends/macos.ts';
import type { MacOSBackendConfig } from './backends/macos.ts';

export type ComputerCredential =
  | { readonly kind: 'saved'; readonly directory: string }
  | { readonly kind: 'service-token'; readonly environment: string };
export interface HostBudgets {
  readonly startupMs: number;
  readonly controlMs: number;
  readonly observeMs: number;
  readonly passMs: number;
  readonly cleanupMs: number;
}
export interface ComputerHostConfig {
  readonly version: 1;
  readonly origin: string;
  readonly binding: ToolBinding;
  readonly credential: ComputerCredential;
  readonly journalDirectory: string;
  readonly prefix: string;
  readonly budgets: HostBudgets;
  readonly pollIntervalMs: number;
  readonly catalogue: {
    readonly leaseSeconds: number;
    readonly renewalMs: number;
  };
  readonly browser?: BrowserBackendConfig;
  readonly macos?: MacOSBackendConfig;
}

function object(
  value: unknown,
  keys: readonly string[],
): Readonly<Record<string, unknown>> {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    throw new Error('Invalid computer host configuration');
  const row = value as Readonly<Record<string, unknown>>;
  if (Object.keys(row).some((key) => !keys.includes(key)))
    throw new Error('Unknown computer host configuration field');
  return row;
}
export function absolutePrivatePath(value: unknown): string {
  if (
    typeof value !== 'string' ||
    !isAbsolute(value) ||
    value.trim() !== value ||
    Array.from(value).some((character) => (character.codePointAt(0) ?? 0) < 32)
  )
    throw new Error('Computer host paths must be explicit absolute paths');
  return value;
}
function budget(
  row: Readonly<Record<string, unknown>>,
  key: string,
  fallback: number,
): number {
  const value = row[key] === undefined ? fallback : row[key];
  if (typeof value !== 'number')
    throw new Error('Invalid computer host duration');
  return positiveBudget(value);
}

/** Decode private operator configuration before authentication, backend launch or filesystem writes. */
export function decodeComputerHostConfig(value: unknown): ComputerHostConfig {
  const row = object(value, [
    'version',
    'origin',
    'binding',
    'credential',
    'journalDirectory',
    'prefix',
    'budgets',
    'pollIntervalMs',
    'catalogue',
    'browser',
    'macos',
  ]);
  if (
    row['version'] !== 1 ||
    typeof row['origin'] !== 'string' ||
    row['origin'].trim() !== row['origin']
  )
    throw new Error('Computer host requires version 1 and an explicit origin');
  const origin = new URL(row['origin']);
  if (
    !['https:', 'http:'].includes(origin.protocol) ||
    origin.username ||
    origin.password ||
    origin.pathname !== '/' ||
    origin.search ||
    origin.hash
  )
    throw new Error(
      'Computer host requires a server origin without credentials or path',
    );
  const rawBinding = object(row['binding'], ['project', 'provider', 'account']);
  const rawAccount = computerId(rawBinding['account']);
  const servicePrincipal =
    /^@service\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/u.test(rawAccount);
  const binding = Object.freeze({
    project: computerId(rawBinding['project']),
    provider: computerId(rawBinding['provider']),
    account: servicePrincipal ? rawAccount : connectionAccount(rawAccount),
  });
  if (binding.project.toLowerCase().startsWith('personal:'))
    throw new Error('Image observations require an ordinary explicit project');
  const rawCredential = object(row['credential'], [
    'kind',
    'directory',
    'environment',
  ]);
  let credential: ComputerCredential;
  if (
    rawCredential['kind'] === 'saved' &&
    rawCredential['environment'] === undefined
  )
    credential = Object.freeze({
      kind: 'saved',
      directory: absolutePrivatePath(rawCredential['directory']),
    });
  else if (
    rawCredential['kind'] === 'service-token' &&
    rawCredential['directory'] === undefined &&
    typeof rawCredential['environment'] === 'string' &&
    /^[A-Z][A-Z0-9_]{0,127}$/u.test(rawCredential['environment'])
  )
    credential = Object.freeze({
      kind: 'service-token',
      environment: rawCredential['environment'],
    });
  else
    throw new Error(
      'Choose one explicit saved credential directory or service-token environment source',
    );
  if ((credential.kind === 'service-token') !== servicePrincipal)
    throw new Error(
      'Service tokens require their exact @service principal; saved credentials require a user account',
    );
  const rawBudgets =
    row['budgets'] === undefined
      ? {}
      : object(row['budgets'], [
          'startupMs',
          'controlMs',
          'observeMs',
          'passMs',
          'cleanupMs',
        ]);
  const budgets = Object.freeze({
    startupMs: budget(rawBudgets, 'startupMs', 30_000),
    controlMs: budget(rawBudgets, 'controlMs', 2000),
    observeMs: budget(rawBudgets, 'observeMs', 10_000),
    passMs: budget(rawBudgets, 'passMs', 30_000),
    cleanupMs: budget(rawBudgets, 'cleanupMs', 2000),
  });
  const prefix =
    row['prefix'] === undefined ? 'computer_' : computerId(row['prefix']);
  computerDeclarations(prefix, {
    controlBudgetMs: budgets.controlMs,
    observeBudgetMs: budgets.observeMs,
  });
  const pollIntervalMs = row['pollIntervalMs'] ?? 250;
  if (
    typeof pollIntervalMs !== 'number' ||
    !Number.isSafeInteger(pollIntervalMs) ||
    pollIntervalMs < 50 ||
    pollIntervalMs > 60_000
  )
    throw new Error('Computer polling interval must be 50..60000 milliseconds');
  const rawCatalogue = object(row['catalogue'], ['leaseSeconds', 'renewalMs']);
  const leaseSeconds = rawCatalogue['leaseSeconds'],
    renewalMs = rawCatalogue['renewalMs'];
  if (
    typeof leaseSeconds !== 'number' ||
    !Number.isSafeInteger(leaseSeconds) ||
    leaseSeconds < 1 ||
    leaseSeconds > 300 ||
    typeof renewalMs !== 'number' ||
    !Number.isSafeInteger(renewalMs) ||
    renewalMs < 1 ||
    renewalMs > leaseSeconds * 500
  )
    throw new Error(
      'Catalogue renewal must fit within half its explicit server-configured lease',
    );
  // A serial host can finish one current pass before noticing renewal is due; the renewal pass
  // itself also has a finite allowance. Refuse configurations that cannot preserve availability.
  if (renewalMs + 2 * budgets.passMs + pollIntervalMs >= leaseSeconds * 1000)
    throw new Error(
      'Catalogue lease must cover renewal, two bounded passes and the poll interval',
    );
  const browser =
    row['browser'] === undefined
      ? undefined
      : decodeBrowserBackendConfig(row['browser']);
  const macos =
    row['macos'] === undefined
      ? undefined
      : decodeMacOSBackendConfig(row['macos']);
  if (browser === undefined && macos === undefined)
    throw new Error('Configure at least one computer backend');
  const targets = [...(browser?.targets ?? []), ...(macos?.targets ?? [])];
  if (
    targets.length > 32 ||
    new Set(targets.map((target) => target.targetId)).size !== targets.length
  )
    throw new Error(
      'Computer backend target identities must be distinct and bounded to 32',
    );
  return Object.freeze({
    version: 1,
    origin: origin.origin,
    binding,
    credential,
    journalDirectory: absolutePrivatePath(row['journalDirectory']),
    prefix,
    budgets,
    pollIntervalMs,
    catalogue: Object.freeze({ leaseSeconds, renewalMs }),
    ...(browser === undefined ? {} : { browser }),
    ...(macos === undefined ? {} : { macos }),
  });
}
