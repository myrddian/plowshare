#!/usr/bin/env node
import { pathToFileURL } from 'node:url';
import { Credentials } from 'plowshare-client-node/credentials';
import {
  authenticate,
  authenticateService,
} from 'plowshare-client-node/session';
import { checkedTransport } from 'plowshare-client-ts/operations/transport';
import type { Plowshare } from 'plowshare-client-ts';
import type { ComputerHostConfig } from './config.ts';
import { DeadlineExceeded, OperationDeadline } from './deadline.ts';
import { createHostDiagnostics } from './host-diagnostics.ts';
import type { HostDiagnostics } from './host-diagnostics.ts';
import { FileComputerConfigReader } from './private-config.ts';
import type { ComputerConfigReader } from './private-config.ts';
import { computerProviderConnection } from './provider-connection.ts';
import { runComputerHost } from './runner.ts';
import type { ComputerHostOptions } from './runner.ts';

/** The login owns its original SDK Session/socket. No scope adoption or replacement connection. */
export interface ComputerHostLogin {
  readonly account: string;
  readonly connection: Pick<Plowshare, 'request'>;
  currentBearer(): Promise<string>;
  close(): void;
}

export interface ComputerMainPorts {
  readonly configReader: ComputerConfigReader;
  readonly diagnostics: HostDiagnostics;
  authenticate(
    config: ComputerHostConfig,
    environment: Readonly<Record<string, string | undefined>>,
    signal: AbortSignal,
    onAttention: () => void,
  ): Promise<ComputerHostLogin>;
  runHost(options: ComputerHostOptions): Promise<void>;
  subscribeStop(stop: () => void): () => void;
}

function defaultPorts(): ComputerMainPorts {
  return {
    configReader: new FileComputerConfigReader(),
    diagnostics: createHostDiagnostics((line) => process.stderr.write(line)),
    async authenticate(config, environment, signal, onAttention) {
      let session;
      if (config.credential.kind === 'saved') {
        const credentials = new Credentials(
          config.origin,
          config.credential.directory,
          signal,
          config.binding.account,
        );
        session = await authenticate(
          config.origin,
          '',
          '',
          signal,
          onAttention,
          { credentials },
        );
      } else {
        const token = environment[config.credential.environment];
        if (
          token === undefined ||
          !token.trim() ||
          token !== token.trim() ||
          /[\r\n]/u.test(token)
        )
          throw new Error('Configured service credential unavailable');
        session = await authenticateService(config.origin, token, signal);
      }
      return {
        account: session.handle,
        connection: computerProviderConnection(checkedTransport(session)),
        currentBearer: () => session.bearer(),
        close: () => session.close(),
      };
    },
    runHost: runComputerHost,
    subscribeStop(stop) {
      process.once('SIGINT', stop);
      process.once('SIGTERM', stop);
      return () => {
        process.removeListener('SIGINT', stop);
        process.removeListener('SIGTERM', stop);
      };
    },
  };
}

async function authenticateWithin(
  work: Promise<ComputerHostLogin>,
  deadline: OperationDeadline,
): Promise<ComputerHostLogin> {
  let detach = () => {};
  try {
    deadline.requireActive();
    const login = await Promise.race([
      work,
      new Promise<never>((_, reject) => {
        const expired = () => reject(new DeadlineExceeded());
        deadline.signal.addEventListener('abort', expired, { once: true });
        detach = () => deadline.signal.removeEventListener('abort', expired);
        if (deadline.signal.aborted) expired();
      }),
    ]);
    deadline.requireActive();
    return login;
  } finally {
    detach();
  }
}

/** Importing this module is inert. Configuration is a separate 2s unit before its budgets exist. */
export async function main(
  args: readonly string[],
  env: Readonly<Record<string, string | undefined>> = process.env,
  ports: ComputerMainPorts = defaultPorts(),
): Promise<void> {
  const { diagnostics } = ports;
  if (
    args.length < 1 ||
    args.length > 2 ||
    (args.length === 2 && args[1] !== '--once')
  ) {
    diagnostics.emit({ stage: 'config', code: 'CONFIG_FAILED' });
    throw new Error('Usage: computer-control ABSOLUTE_CONFIG_PATH [--once]');
  }
  const loading = new OperationDeadline(2000);
  let config: ComputerHostConfig;
  try {
    const file = args[0];
    if (file === undefined) throw new Error('Configuration required');
    config = await ports.configReader.read(file, loading);
    loading.requireActive();
    diagnostics.emit({ stage: 'config', code: 'CONFIG_LOADED' });
  } catch (error) {
    diagnostics.emit({ stage: 'config', code: 'CONFIG_FAILED' });
    throw error;
  } finally {
    loading.dispose();
    const cleanup = new OperationDeadline(2000);
    try {
      await ports.configReader.drain(cleanup);
    } finally {
      cleanup.dispose();
    }
  }
  const stopping = new AbortController(),
    lifetime = new AbortController();
  const startup = new OperationDeadline(config.budgets.startupMs);
  let acceptingLogin = true,
    authenticating = true;
  let login: ComputerHostLogin | undefined;
  let detachStop = () => {};
  try {
    detachStop = ports.subscribeStop(() => {
      stopping.abort();
      if (authenticating) startup.cancel();
    });
    startup.requireActive();
    const physicalLogin = ports
      .authenticate(
        config,
        env,
        AbortSignal.any([startup.signal, lifetime.signal]),
        () =>
          diagnostics.emit({
            stage: 'authentication',
            code: 'AUTHENTICATION_ATTENTION',
          }),
      )
      .then((candidate) => {
        // A noncooperative authenticator still owns a late socket; never leak it or start a host.
        if (
          !acceptingLogin ||
          startup.remainingMs() <= 0 ||
          stopping.signal.aborted
        ) {
          candidate.close();
          throw new DeadlineExceeded();
        }
        login = candidate;
        return candidate;
      });
    const authenticated = await authenticateWithin(physicalLogin, startup);
    authenticating = false;
    if (authenticated.account !== config.binding.account)
      throw new Error('Authenticated account differs from configured binding');
    diagnostics.emit({ stage: 'authentication', code: 'AUTHENTICATED' });
    if (stopping.signal.aborted) return;
    await ports.runHost({
      config,
      diagnostics,
      connection: authenticated.connection,
      currentBearer: () => authenticated.currentBearer(),
      signal: stopping.signal,
      startupDeadline: startup,
      ...(args[1] === '--once' ? { maxPasses: 1 } : {}),
      onReady: () => {
        startup.dispose();
        diagnostics.emit({ stage: 'serving', code: 'READY' });
      },
    });
  } catch (error) {
    diagnostics.emit(
      authenticating
        ? { stage: 'authentication', code: 'AUTHENTICATION_FAILED' }
        : { stage: 'serving', code: 'HOST_FAILED' },
    );
    throw error;
  } finally {
    acceptingLogin = false;
    startup.dispose();
    try {
      detachStop();
    } finally {
      try {
        login?.close();
      } finally {
        lifetime.abort();
      }
    }
  }
}

const entry = process.argv[1];
if (entry !== undefined && import.meta.url === pathToFileURL(entry).href) {
  try {
    await main(process.argv.slice(2));
  } catch {
    process.stderr.write(
      'computer-control: host stopped; inspect private receipts and ownership markers. No operation was replayed.\n',
    );
    process.exitCode = 1;
  }
}
