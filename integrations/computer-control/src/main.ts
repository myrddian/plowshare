import { readFile, stat } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { Credentials } from 'plowshare-client-node/credentials';
import { privateFile } from 'plowshare-client-node/connections';
import {
  authenticate,
  authenticateService,
} from 'plowshare-client-node/session';
import type { Session } from 'plowshare-client-node/session';
import { checkedTransport } from 'plowshare-client-ts/operations/transport';
import { absolutePrivatePath, decodeComputerHostConfig } from './config.ts';
import { OperationDeadline } from './deadline.ts';
import { computerProviderConnection } from './provider-connection.ts';
import { runComputerHost } from './runner.ts';

/** Foreground composition only; importing the reusable package never authenticates or launches. */
export async function main(
  args: readonly string[],
  env: Readonly<Record<string, string | undefined>> = process.env,
): Promise<void> {
  if (
    args.length < 1 ||
    args.length > 2 ||
    (args.length === 2 && args[1] !== '--once')
  )
    throw new Error('Usage: computer-control ABSOLUTE_CONFIG_PATH [--once]');
  const file = absolutePrivatePath(args[0]);
  await privateFile(file);
  if ((await stat(file)).size > 65_536)
    throw new Error('Computer host configuration exceeds 64 KiB');
  const config = decodeComputerHostConfig(
    JSON.parse(await readFile(file, 'utf8')),
  );
  const stopping = new AbortController(),
    sessionLifetime = new AbortController();
  const startup = new OperationDeadline(config.budgets.startupMs);
  let authenticating = true,
    session: Session | undefined;
  const stop = () => {
    stopping.abort();
    if (authenticating) startup.cancel();
  };
  process.once('SIGINT', stop);
  process.once('SIGTERM', stop);
  try {
    const authenticationSignal = AbortSignal.any([
      sessionLifetime.signal,
      startup.signal,
    ]);
    if (config.credential.kind === 'saved') {
      const credentials = new Credentials(
        config.origin,
        config.credential.directory,
        authenticationSignal,
        config.binding.account,
      );
      session = await authenticate(
        config.origin,
        '',
        '',
        authenticationSignal,
        () =>
          process.stderr.write(
            'computer-control: SDK authentication requires operator attention.\n',
          ),
        { credentials },
      );
    } else {
      const token = env[config.credential.environment];
      if (
        token === undefined ||
        !token.trim() ||
        token !== token.trim() ||
        /[\r\n]/u.test(token)
      )
        throw new Error(
          'Configured service-token environment source is unavailable',
        );
      session = await authenticateService(
        config.origin,
        token,
        authenticationSignal,
      );
    }
    startup.requireActive();
    authenticating = false;
    if (session.handle !== config.binding.account)
      throw new Error(
        'Authenticated provider account differs from configured binding',
      );
    if (stopping.signal.aborted) return;
    const authenticated = session;
    await runComputerHost({
      config,
      connection: computerProviderConnection(checkedTransport(authenticated)),
      currentBearer: () => authenticated.bearer(),
      signal: stopping.signal,
      startupDeadline: startup,
      ...(args[1] === '--once' ? { maxPasses: 1 } : {}),
      onReady: () => {
        startup.dispose();
        process.stdout.write('computer-control: observation provider ready.\n');
      },
    });
  } finally {
    startup.dispose();
    process.removeListener('SIGINT', stop);
    process.removeListener('SIGTERM', stop);
    session?.close();
    sessionLifetime.abort();
  }
}

const entry = process.argv[1];
if (entry !== undefined && import.meta.url === pathToFileURL(entry).href) {
  try {
    await main(process.argv.slice(2));
  } catch {
    process.stderr.write(
      'computer-control: host stopped with an error; inspect its private receipts and ownership markers. No operation was replayed.\n',
    );
    process.exitCode = 1;
  }
}
