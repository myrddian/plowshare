import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { setTimeout as pause } from 'node:timers/promises';
import { localLock } from 'plowshare-client-node/connections';
import { requirePayload } from 'plowshare-client-ts';
import type { HostedToolRegistration } from 'plowshare-client-ts';
import type { IntegrationConfig, WorkerConfig } from './config.ts';
import type { McpSessions } from './contracts.ts';
import type { ProviderConnection } from './provider.ts';
import { McpProvider, WorkerCredentials, publish } from './provider.ts';
import {
  FileToolJournal,
  CatalogueIntent,
  RegistrationIntent,
} from './journal.ts';
import { FileRecoveryCatalogues, reconcileCatalogues } from './recovery.ts';

/** Renewal uses the smaller of the server lease and actual remaining lifetime; polling cannot delay it. */
export function renewalTime(
  now: number,
  expiresAt: number,
  leaseSeconds: number,
  fraction: number,
): number {
  return (
    now + Math.max(0, Math.min(expiresAt - now, leaseSeconds * 1000)) * fraction
  );
}
async function registration(
  connection: ProviderConnection,
  config: WorkerConfig,
  intent: RegistrationIntent,
): Promise<HostedToolRegistration> {
  const status = requirePayload(
    await connection.request('tool.scope.worker.status', {
      binding: config.binding,
    }),
  );
  let value = status.registration;
  if (value === null) {
    const requestId = randomUUID();
    await intent.begin(requestId);
    value = requirePayload(
      await connection.request('tool.scope.worker.register', {
        binding: config.binding,
        expectedGeneration: config.generation,
        requestId,
      }),
    );
    if (value.registration !== requestId)
      throw new Error('Foreign MCP worker registration receipt');
  }
  if (
    value.binding.binding !== config.binding ||
    value.binding.generation !== config.generation ||
    Date.parse(value.expiresAt) <= Date.now()
  )
    throw new Error('Configured MCP registration is stale');
  await intent.settled();
  return value;
}
async function runWorker(
  connection: ProviderConnection,
  config: IntegrationConfig,
  worker: WorkerConfig,
  sessions: McpSessions,
  signal: AbortSignal,
): Promise<void> {
  const directory = join(config.journalDirectory, worker.binding);
  await localLock(
    directory,
    'worker',
    async () => {
      const recovery = new FileRecoveryCatalogues(
        directory,
        worker.binding,
        config.maxRecoveryCatalogues,
      );
      await reconcileCatalogues(connection, recovery, directory);
      const intent = await CatalogueIntent.open(directory);
      const registrationIntent = await RegistrationIntent.open(directory);
      const registered = await registration(
        connection,
        worker,
        registrationIntent,
      );
      const adapter = await McpProvider.discover(
        registered,
        new WorkerCredentials(connection, registered),
        sessions,
        worker.options,
        signal,
      );
      try {
        await recovery.save({
          identity: adapter.journalIdentity,
          registration: registered,
          declarations: adapter.tools.map((tool) => tool.declaration),
        });
        const journal = await FileToolJournal.open(
          directory,
          adapter.journalIdentity,
        );
        const provider = await adapter.attach(connection, journal);
        await provider.reconcile();
        await publish(provider, intent, 'publish');
        let safeToWithdraw = true;
        let expiresAt = Date.parse(registered.expiresAt);
        let renewAt = renewalTime(
          Date.now(),
          expiresAt,
          registered.binding.definition.leaseSeconds,
          worker.renewalFraction,
        );
        try {
          while (!signal.aborted) {
            if (Date.now() >= expiresAt)
              throw new Error('MCP worker lease expired');
            if (Date.now() >= renewAt) {
              await adapter.refresh();
              const renewed = requirePayload(
                await connection.request('tool.scope.worker.renew', {
                  binding: worker.binding,
                  registration: registered.registration,
                }),
              );
              if (
                renewed.registration !== registered.registration ||
                renewed.binding.generation !== worker.generation
              )
                throw new Error('MCP worker fence changed');
              expiresAt = Date.parse(renewed.expiresAt);
              safeToWithdraw = false;
              await publish(provider, intent, 'publish');
              safeToWithdraw = true;
              renewAt = renewalTime(
                Date.now(),
                expiresAt,
                renewed.binding.definition.leaseSeconds,
                worker.renewalFraction,
              );
            }
            await provider.reconcile();
            await provider.poll();
            adapter.assertActive();
            if (!signal.aborted) {
              try {
                await pause(
                  Math.min(
                    worker.pollMs,
                    Math.max(1, renewAt - Date.now()),
                    Math.max(1, expiresAt - Date.now()),
                  ),
                  undefined,
                  { signal },
                );
              } catch (error) {
                if (!signal.aborted) throw error;
              }
            }
          }
        } finally {
          if (safeToWithdraw) await publish(provider, intent, 'withdraw');
        }
      } finally {
        await adapter.close();
      }
    },
    signal,
  );
}
/** All tasks and failures have one owner. A failed binding stops this executor process's intake and renewal. */
export async function runIntegration(
  connection: ProviderConnection,
  config: IntegrationConfig,
  sessions: McpSessions,
  signal: AbortSignal,
): Promise<void> {
  const controller = new AbortController();
  const stop = AbortSignal.any([signal, controller.signal]);
  const outcomes = await Promise.allSettled(
    config.workers.map(async (worker) => {
      try {
        await runWorker(connection, config, worker, sessions, stop);
      } catch (error) {
        controller.abort();
        throw error;
      }
    }),
  );
  if (outcomes.some((outcome) => outcome.status === 'rejected'))
    throw new Error(
      'MCP integration stopped; inspect retained SDK receipts and catalogue intent',
    );
}
