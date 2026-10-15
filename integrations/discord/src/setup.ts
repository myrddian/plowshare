import { SetupProblem } from './setup-error.ts';
import { readFile } from 'node:fs/promises';
import { isAbsolute, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { applicationFiles } from 'plowshare-client-node/applications';
import { Credentials } from 'plowshare-client-node/credentials';
import { connectPlowshare } from 'plowshare-client-node';
import { FileSetup } from './setup-store.ts';
import { decodeSetupConfig } from './setup-config.ts';
import { DiscordSetup } from './setup-engine.ts';
import { SdkSetupGateway, verifyServiceCredential } from './setup-gateway.ts';
import type { SetupConfiguration } from './setup-contracts.ts';

/** Only the explicit start command opens Discord; tokens travel in the child environment, not argv. */
async function start(
  config: SetupConfiguration,
  repository: FileSetup,
): Promise<void> {
  if (!process.env[config.adapter.discordTokenEnvironment])
    throw new SetupProblem(
      'Supply the configured Discord bot token environment variable before start',
    );
  const value = await repository.published();
  await verifyServiceCredential(
    value.credential,
    value.receipt.principal,
    config.adapter,
  );
  const child = spawn(
    process.execPath,
    [
      fileURLToPath(new URL('./main.js', import.meta.url)),
      join(config.outputDirectory, 'adapter.json'),
    ],
    {
      shell: false,
      stdio: 'inherit',
      env: {
        ...process.env,
        [config.adapter.serviceTokenEnvironment]: value.credential,
      },
    },
  );
  const interrupt = () => {
    child.kill('SIGINT');
  };
  const terminate = () => {
    child.kill('SIGTERM');
  };
  process.once('SIGINT', interrupt);
  process.once('SIGTERM', terminate);
  try {
    await new Promise<void>((resolve, reject) => {
      child.once('error', reject);
      child.once('exit', (code, signal) => {
        process.exitCode = signal === null ? (code ?? 1) : 1;
        resolve();
      });
    });
  } finally {
    process.removeListener('SIGINT', interrupt);
    process.removeListener('SIGTERM', terminate);
  }
}
async function main(): Promise<void> {
  if (process.argv.length === 3 && process.argv[2] === '--help') {
    console.info(
      'Usage: node build/discord/setup.js install|recover|rotate|start /absolute/path/setup.json',
    );
    return;
  }
  const mode = process.argv[2];
  const path = process.argv[3];
  if (
    process.argv.length !== 4 ||
    !['install', 'recover', 'rotate', 'start'].includes(mode ?? '') ||
    path === undefined ||
    !isAbsolute(path)
  )
    throw new SetupProblem(
      'Usage: node build/discord/setup.js install|recover|rotate|start /absolute/path/setup.json',
    );
  const bytes = await readFile(path);
  if (bytes.length > 65536)
    throw new SetupProblem('Setup configuration exceeds its bound');
  const raw: unknown = JSON.parse(bytes.toString('utf8'));
  const config = decodeSetupConfig(raw);
  const repository = await FileSetup.open(config);
  try {
    if (mode === 'start') {
      await start(config, repository);
      return;
    }
    if (mode !== 'install' && repository.state === null)
      throw new SetupProblem(
        'No installation intent exists; run install first',
      );
    // Read/renew only this explicitly selected saved human session. Never consult service-token
    // environment variables or retain administrator credentials in installation output.
    const credentials = new Credentials(
      config.adapter.origin,
      undefined,
      undefined,
      config.account,
    );
    if ((await credentials.session()).handle !== config.account)
      throw new SetupProblem('Saved administrator account differs from setup');
    const tokens = await credentials.renew({
      base: config.adapter.origin,
      fetch: (url, options) =>
        fetch(url, {
          ...options,
          signal: AbortSignal.timeout(config.adapter.requestTimeoutMs),
        }),
    });
    const client = await connectPlowshare({
      origin: config.adapter.origin,
      token: tokens.access,
      timeoutMs: config.adapter.requestTimeoutMs,
    });
    try {
      const setup = new DiscordSetup(
        config,
        repository,
        new SdkSetupGateway(client),
      );
      if (mode === 'rotate') await setup.rotate();
      else if (repository.state !== null || mode === 'recover')
        await setup.recover();
      else await setup.install(await applicationFiles(config.sourceDirectory));
      console.info(
        'Discord Application setup is ready. Credential and runtime configuration are saved privately. Use start to connect Discord.',
      );
    } finally {
      client.close();
    }
  } finally {
    await repository.close();
  }
}
if (
  process.argv[1] !== undefined &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  main().catch((error: unknown) => {
    if (error instanceof SetupProblem) console.error(error.message);
    console.error(
      'Discord setup did not complete. Check setup.template.json, selected directories and saved administrator login. Keep any private setup state and reconcile with recover. No uncertain mutation was replayed.',
    );
    process.exitCode = 1;
  });
}
