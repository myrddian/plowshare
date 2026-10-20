import { SetupProblem } from './setup-error.ts';
import { isAbsolute, relative, sep } from 'node:path';
import { createHash } from 'node:crypto';
import { decodePayload } from 'plowshare-client-ts/operations/schema';
import type { ApplicationSourceFile } from 'plowshare-client-ts/operations/application-deployments';
import { object, text, integer, decodeConfig } from './config.ts';
import type { SetupConfiguration } from './setup-contracts.ts';

function handle(value: unknown): string {
  const name = text(value, 64);
  if (!/^[A-Za-z0-9][A-Za-z0-9_.-]*$/.test(name))
    throw new SetupProblem('Invalid setup account handle');
  return name;
}
function path(value: unknown): string {
  const result = text(value, 4096);
  if (!isAbsolute(result))
    throw new SetupProblem('Setup directories must be absolute');
  return result;
}
function overlaps(a: string, b: string): boolean {
  const inside = (first: string, second: string) => {
    const difference = relative(first, second);
    return (
      difference === '' ||
      (difference !== '..' &&
        !difference.startsWith(`..${sep}`) &&
        !isAbsolute(difference))
    );
  };
  return inside(a, b) || inside(b, a);
}
export function separateSetupDirectories(
  source: string,
  output: string,
  runtime: string,
): void {
  if (
    overlaps(source, output) ||
    overlaps(source, runtime) ||
    overlaps(output, runtime)
  )
    throw new SetupProblem(
      'Source, setup output and runtime state directories must be disjoint',
    );
}
export function setupHash(config: SetupConfiguration): string {
  return createHash('sha256').update(JSON.stringify(config)).digest('hex');
}
/** Previous setup DTOs had no DM policy. Accept that digest only with DMs still disabled. */
export function legacySetupHash(
  config: SetupConfiguration,
): string | undefined {
  if (config.adapter.dm.mode !== 'disabled') return undefined;
  const { dm: _dm, ...adapter } = config.adapter;
  return createHash('sha256')
    .update(JSON.stringify({ ...config, adapter }))
    .digest('hex');
}
/** Closed installer configuration. A single owned token cannot gain another project's ceiling. */
export function decodeSetupConfig(value: unknown): SetupConfiguration {
  const raw = object(value, [
    'account',
    'project',
    'serviceHandle',
    'sourceDirectory',
    'outputDirectory',
    'destination',
    'writableAreas',
    'expiresInDays',
    'adapter',
  ]);
  const project = text(raw.project, 256);
  if (/^(Personal:|personal:|client:)/.test(project))
    throw new SetupProblem('Choose an ordinary Application project');
  const account = handle(raw.account);
  const serviceHandle = handle(raw.serviceHandle);
  if (account === serviceHandle)
    throw new SetupProblem(
      'The Application service must be a separate account',
    );
  const sourceDirectory = path(raw.sourceDirectory);
  const outputDirectory = path(raw.outputDirectory);
  const adapter = decodeConfig(raw.adapter);
  // start injects this value into a child process. Loader/shell controls could echo the secret
  // as a rejected option or path before the adapter gets a chance to handle it safely.
  if (
    /^(NODE_|LD_|DYLD_)|^(PATH|HOME|SHELL|ENV|BASH_ENV|OPENSSL_CONF|SSL_CERT_FILE|SSL_CERT_DIR|SSLKEYLOGFILE)$/.test(
      adapter.serviceTokenEnvironment,
    )
  )
    throw new SetupProblem(
      'The service credential environment name cannot control the process loader or shell',
    );
  if (
    adapter.routes.some((r) => r.project !== project) ||
    (adapter.dm.mode === 'service' && adapter.dm.project !== project)
  )
    throw new SetupProblem(
      'Owned setup routes must use the Application project',
    );
  separateSetupDirectories(
    sourceDirectory,
    outputDirectory,
    adapter.stateDirectory,
  );
  // Reuse the public deployment boundary for FileStore placement and writable-area validation.
  const placement = decodePayload('application.deploy', {
    project,
    requestId: '00000000-0000-0000-0000-000000000001',
    expectedRevision: null,
    destination: raw.destination,
    writableAreas: raw.writableAreas,
    files: [{ path: 'plowshare.json', text: '{}' }],
  });
  return {
    account,
    project,
    serviceHandle,
    sourceDirectory,
    outputDirectory,
    destination: placement.destination,
    writableAreas: placement.writableAreas,
    expiresInDays: integer(raw.expiresInDays, 1, 365),
    adapter,
  };
}

/** Prepare the basic manifest at the source boundary, preserving declared human grants and caps.
 * Advanced manifest shapes require the normal deployment workflow, never silent field removal. */
export function setupFiles(
  config: SetupConfiguration,
  files: readonly ApplicationSourceFile[],
): readonly ApplicationSourceFile[] {
  const root = files.find((f) => f.path === 'plowshare.json');
  if (root === undefined)
    throw new SetupProblem('Application source needs its root manifest');
  const value: unknown = JSON.parse(root.text);
  const manifest = object(value, [
    'version',
    'name',
    'service',
    'access',
    'caps',
    'commands',
  ]);
  if (manifest.version !== 1)
    throw new SetupProblem(
      'Setup requires a version 1 basic Application manifest',
    );
  text(manifest.name, 256);
  const service = object(manifest.service, ['handle', 'role']);
  handle(service.handle);
  if (service.role !== 'CONTRIBUTOR')
    throw new SetupProblem(
      'Discord setup requires an owned Contributor declaration',
    );
  const access = object(manifest.access, ['accounts']);
  if (!Array.isArray(access.accounts) || access.accounts.length > 254)
    throw new SetupProblem('Invalid Application grants');
  const accounts = access.accounts.map((value: unknown) => {
    const entry = object(value, ['handle', 'role']);
    const account = handle(entry.handle);
    if (
      entry.role !== 'VIEWER' &&
      entry.role !== 'CONTRIBUTOR' &&
      entry.role !== 'MANAGER'
    )
      throw new SetupProblem('Invalid Application grant role');
    if (account === config.serviceHandle || account === service.handle)
      throw new SetupProblem('Do not repeat the service owner in human grants');
    return { handle: account, role: entry.role };
  });
  if (new Set(accounts.map((a) => a.handle)).size !== accounts.length)
    throw new SetupProblem('Duplicate Application grant');
  const caps = object(manifest.caps, ['steps', 'budget', 'autoIncrease']);
  if (typeof caps.autoIncrease !== 'boolean')
    throw new SetupProblem('Invalid Application cap flag');
  const commands = object(manifest.commands, ['local', 'server']);
  for (const mode of [commands.local, commands.server]) {
    if (object(mode, ['mode']).mode !== 'off')
      throw new SetupProblem('Basic setup keeps command execution off');
  }
  const prepared = {
    version: 1,
    name: config.project,
    service: { handle: config.serviceHandle, role: 'CONTRIBUTOR' },
    access: {
      accounts: [
        ...accounts.filter((a) => a.handle !== config.account),
        { handle: config.account, role: 'MANAGER' },
      ],
    },
    caps: {
      steps: integer(caps.steps, 1, 1000000),
      budget: integer(caps.budget, 1, 1000000),
      autoIncrease: caps.autoIncrease,
    },
    commands: { local: { mode: 'off' }, server: { mode: 'off' } },
  };
  return files.map((file) =>
    file.path === root.path
      ? { path: file.path, text: JSON.stringify(prepared, null, 2) + '\n' }
      : file,
  );
}
