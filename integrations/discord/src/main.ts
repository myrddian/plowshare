import { readFile } from 'node:fs/promises';
import { isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as pause } from 'node:timers/promises';
import { ConnectionFault, connectPlowshare } from 'plowshare-client-node';
import { decodeConfig, botTargets } from './config.ts';
import { DiscordAdapter } from './adapter.ts';
import { DiscordNetwork } from './discord.ts';
import { SdkGateway } from './gateway.ts';
import { FileState } from './state.ts';

/** A refused queue entry owns no work and does not stop already admitted operations. */
export class IntakeFull extends Error {}

/** Serializes all state mutations, including polling. The queue never owns unbounded intake. */
export class SerialOwner {
  private tail: Promise<void> = Promise.resolve();
  private pending = 0;
  run(work: () => Promise<void>): Promise<void> {
    if (this.pending >= 32)
      return Promise.reject(
        new IntakeFull('Discord intake queue is full; event was not admitted'),
      );
    this.pending++;
    const previous = this.tail;
    let release: () => void = () => {
      throw new Error('Queue gate not initialized');
    };
    this.tail = new Promise<void>((resolve) => {
      release = resolve;
    });
    return (async () => {
      await previous;
      try {
        await work();
      } finally {
        this.pending--;
        release();
      }
    })();
  }
  settle(): Promise<void> {
    return this.tail;
  }
}
function credential(name: string): string {
  const value = process.env[name];
  if (!value || value.length > 16384 || value !== value.trim())
    throw new Error('Configured credential is missing or invalid');
  return value;
}
async function main(): Promise<void> {
  const path = process.argv[2];
  if (process.argv.length !== 3 || path === undefined || !isAbsolute(path))
    throw new Error(
      'Usage: node build/discord/main.js /absolute/path/discord-config.json',
    );
  const bytes = await readFile(path);
  if (bytes.byteLength > 65536)
    throw new Error('Discord configuration exceeds its bound');
  const raw: unknown = JSON.parse(bytes.toString('utf8'));
  const config = decodeConfig(raw);
  const serviceToken = credential(config.serviceTokenEnvironment);
  if (!serviceToken.startsWith('pss_'))
    throw new Error('A scoped Plowshare service token is required');
  const discordToken = credential(config.discordTokenEnvironment);
  const controller = new AbortController();
  const stop = () => controller.abort();
  process.once('SIGINT', stop);
  process.once('SIGTERM', stop);
  const network = new DiscordNetwork(config.requestTimeoutMs);
  const owner = new SerialOwner();
  let repository: FileState | undefined;
  let adapter: DiscordAdapter | null = null;
  // Every promise has an owner. Failure to persist a receipt stops intake immediately.
  network.onMessage((input) => {
    const current = adapter;
    if (current === null || controller.signal.aborted) {
      console.warn('Discord event not admitted: adapter is offline.');
      return;
    }
    owner
      .run(async () => {
        if (adapter === current && !controller.signal.aborted)
          await current.accept(input);
      })
      .catch((error: unknown) => {
        if (error instanceof IntakeFull) {
          console.warn('Discord event not admitted: intake queue is full.');
          return;
        }
        console.error(
          'Discord intake failed; inspect configuration and private receipts.',
        );
        process.exitCode = 1;
        controller.abort();
      });
  });
  network.client.on('error', () => {
    console.error(
      'Discord Gateway reported an error; inspect connection and permissions.',
    );
  });
  try {
    const botId = await network.login(discordToken);
    while (!controller.signal.aborted) {
      try {
        const client = await connectPlowshare({
          origin: config.origin,
          token: serviceToken,
          timeoutMs: config.requestTimeoutMs,
        });
        try {
          const gateway = new SdkGateway(client);
          const principal = await gateway.identity();
          if (repository === undefined)
            repository = await FileState.open(config, principal, botId);
          if (repository.state.principal !== principal)
            throw new Error('Authenticated service principal changed');
          for (const route of botTargets(config)) await gateway.catalog(route);
          const active = new DiscordAdapter(
            config,
            repository,
            gateway,
            network,
          );
          adapter = active;
          console.info(
            'Discord integration connected; configured routes are active.',
          );
          while (!controller.signal.aborted) {
            // Only Gateway intake is capacity-limited. Drain it before scheduling the
            // next owned heartbeat so a burst cannot terminate recovery/polling.
            await owner.settle();
            await owner.run(async () => {
              if ((await gateway.identity()) !== principal)
                throw new Error('Authenticated principal changed');
              await active.poll();
            });
            try {
              await pause(config.pollMs, undefined, {
                signal: controller.signal,
              });
            } catch (error) {
              if (!controller.signal.aborted) throw error;
            }
          }
        } finally {
          adapter = null;
          await owner.settle();
          client.close();
        }
      } catch (error) {
        if (controller.signal.aborted) break;
        if (!(error instanceof ConnectionFault)) throw error;
        console.warn(
          'Plowshare connection lost; reconnecting for fresh reads. Mutations are not replayed.',
        );
        try {
          await pause(config.pollMs, undefined, { signal: controller.signal });
        } catch (interrupted) {
          if (!controller.signal.aborted) throw interrupted;
        }
      }
    }
  } finally {
    adapter = null;
    controller.abort();
    await network.close();
    await owner.settle();
    await repository?.close();
    process.removeListener('SIGINT', stop);
    process.removeListener('SIGTERM', stop);
  }
}
// The imported SerialOwner can be tested without logging in or reading deployment credentials.
if (
  process.argv[1] !== undefined &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  main().catch(() => {
    console.error(
      'Discord integration stopped. Check configuration, access, and retained private state.',
    );
    process.exitCode = 1;
  });
}
