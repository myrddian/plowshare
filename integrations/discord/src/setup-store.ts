import { SetupProblem } from './setup-error.ts';
import { mkdir, open, readFile, readdir, realpath, rm } from 'node:fs/promises';
import { basename, dirname, join } from 'node:path';
import {
  atomicPrivateWrite,
  privateDirectory,
  privateFile,
} from 'plowshare-client-node/connections';
import {
  decodePayload,
  decodeReply,
} from 'plowshare-client-ts/operations/schema';
import { object, text, decodeConfig } from './config.ts';
import { sameTokenReceipt } from './setup-contracts.ts';
import { uuid, decodeState } from './state.ts';
import {
  separateSetupDirectories,
  setupHash,
  legacySetupHash,
} from './setup-config.ts';
import { snowflake } from './config.ts';
import { decodeConfigurationRevision } from './management.ts';
import type {
  ConfigurationRevision,
  ConfigurationRepository,
} from './management.ts';
import type {
  SavedCredential,
  SetupConfiguration,
  SetupRepository,
  SetupState,
  CredentialAttempt,
} from './setup-contracts.ts';
import type { Configuration } from './contracts.ts';

async function read(file: string): Promise<unknown> {
  await privateFile(file);
  try {
    const bytes = await readFile(file);
    if (bytes.length > 2_000_000)
      throw new SetupProblem('Setup artifact exceeds its bound');
    const value: unknown = JSON.parse(bytes.toString('utf8'));
    return value;
  } catch (error) {
    if (error instanceof Error && 'code' in error && error.code === 'ENOENT')
      return undefined;
    throw error;
  }
}
/** Resolve existing ancestors too: a missing leaf must not conceal a symlink alias into source. */
async function storagePath(path: string): Promise<string> {
  const suffix: string[] = [];
  let ancestor = path;
  for (;;) {
    try {
      return join(await realpath(ancestor), ...suffix);
    } catch (error) {
      if (!(
        error instanceof Error &&
        'code' in error &&
        error.code === 'ENOENT'
      ))
        throw error;
      const parent = dirname(ancestor);
      if (parent === ancestor) throw error;
      suffix.unshift(basename(ancestor));
      ancestor = parent;
    }
  }
}
export function decodeSavedCredential(value: unknown): SavedCredential {
  const raw = object(value, ['receipt', 'credential']);
  const receipt = decodeReply('application.token.receipt', raw.receipt);
  if (
    typeof raw.credential !== 'string' ||
    !/^pss_[0-9a-f]{48}$/.test(raw.credential)
  )
    throw new SetupProblem('Invalid private credential artifact');
  return { receipt, credential: raw.credential };
}
export function decodeSetupState(
  value: unknown,
  config: SetupConfiguration,
): SetupState {
  const raw = object(value, [
    'version',
    'configurationHash',
    'deployment',
    'deploymentReceipt',
    'credentials',
  ]);
  if (
    raw.version !== 1 ||
    (raw.configurationHash !== setupHash(config) &&
      !(
        typeof raw.configurationHash === 'string' &&
        raw.configurationHash === legacySetupHash(config)
      ))
  )
    throw new SetupProblem(
      'Setup state belongs to another configuration, server or administrator',
    );
  const deployment = decodePayload('application.deploy', raw.deployment);
  if (
    deployment.project !== config.project ||
    deployment.expectedRevision !== null ||
    JSON.stringify(deployment.destination) !==
      JSON.stringify(config.destination) ||
    JSON.stringify(deployment.writableAreas) !==
      JSON.stringify(config.writableAreas)
  )
    throw new SetupProblem('Foreign setup deployment');
  const deploymentReceipt =
    raw.deploymentReceipt === null
      ? null
      : decodeReply('application.deploy', raw.deploymentReceipt);
  if (
    deploymentReceipt !== null &&
    (deploymentReceipt.project !== deployment.project ||
      deploymentReceipt.requestId !== deployment.requestId)
  )
    throw new SetupProblem('Foreign setup deployment receipt');
  if (!Array.isArray(raw.credentials) || raw.credentials.length > 50)
    throw new SetupProblem('Invalid credential attempt history');
  const credentials = raw.credentials.map(
    (value: unknown): CredentialAttempt => {
      const attempt = object(value, [
        'request',
        'tokenId',
        'principal',
        'receipt',
      ]);
      const request = decodePayload('application.token.issue', attempt.request);
      const tokenId = uuid(attempt.tokenId);
      const principal = text(attempt.principal, 64);
      if (
        request.project !== config.project ||
        principal !== `@service/${tokenId}` ||
        request.expiresInDays !== config.expiresInDays
      )
        throw new SetupProblem('Foreign setup credential attempt');
      const receipt =
        attempt.receipt === null
          ? null
          : decodeReply('application.token.receipt', attempt.receipt);
      if (
        receipt !== null &&
        (receipt.requestId !== request.requestId ||
          receipt.project !== request.project ||
          receipt.tokenId !== tokenId ||
          receipt.principal !== principal)
      )
        throw new SetupProblem('Foreign setup credential receipt');
      return { request, tokenId, principal, receipt };
    },
  );
  if (
    new Set(credentials.map((c) => c.request.requestId)).size !==
      credentials.length ||
    credentials.some(
      (c, index) =>
        (index === 0 ? c.request.rotate : !c.request.rotate) ||
        (index < credentials.length - 1 && c.receipt === null),
    ) ||
    credentials.some((c) => c.tokenId !== credentials[0]?.tokenId) ||
    (credentials.length > 0 && deploymentReceipt === null)
  )
    throw new SetupProblem('Inconsistent setup credential history');
  return {
    version: 1,
    configurationHash: setupHash(config),
    deployment,
    deploymentReceipt,
    credentials,
  };
}

/** A setup lock is independent of adapter runtime state. Crashes leave it for operator inspection. */
export class FileSetup implements SetupRepository, ConfigurationRepository {
  private closed = false;
  private configurationWriting = false;
  private assertOpen(): void {
    if (this.closed) throw new SetupProblem('Setup repository is closed');
  }
  private constructor(
    private readonly directory: string,
    readonly config: SetupConfiguration,
    public state: SetupState | null,
    private readonly runtimeLock: string | null,
  ) {}
  static async open(
    config: SetupConfiguration,
    mode: 'setup' | 'configuration' = 'setup',
  ): Promise<FileSetup> {
    separateSetupDirectories(
      await storagePath(config.sourceDirectory),
      await storagePath(config.outputDirectory),
      await storagePath(config.adapter.stateDirectory),
    );
    await privateDirectory(config.outputDirectory);
    await mkdir(join(config.outputDirectory, 'setup.lock'), { mode: 0o700 });
    let runtimeLock: string | null = null;
    try {
      if (mode === 'configuration') {
        await privateDirectory(config.adapter.stateDirectory);
        const lock = join(config.adapter.stateDirectory, 'adapter.lock');
        await mkdir(lock, { mode: 0o700 });
        runtimeLock = lock;
      }
      const value = await read(
        join(config.outputDirectory, 'setup-state.json'),
      );
      if (
        value === undefined &&
        (await readdir(config.outputDirectory)).some(
          (name) => name !== 'setup.lock',
        )
      )
        throw new SetupProblem('Fresh setup output directory must be empty');
      return new FileSetup(
        config.outputDirectory,
        config,
        value === undefined ? null : decodeSetupState(value, config),
        runtimeLock,
      );
    } catch (error) {
      if (runtimeLock !== null) await rm(runtimeLock, { recursive: true });
      await rm(join(config.outputDirectory, 'setup.lock'), { recursive: true });
      throw error;
    }
  }
  async configuration(): Promise<ConfigurationRevision> {
    this.assertOpen();
    if (this.state?.deploymentReceipt == null)
      throw new SetupProblem(
        'A confirmed installation is required for configuration management',
      );
    const raw = await read(join(this.directory, 'configuration.json'));
    return raw === undefined
      ? {
          version: 1,
          installation: setupHash(this.config),
          revision: this.state.deployment.requestId,
          adapter: this.config.adapter,
        }
      : decodeConfigurationRevision(raw, this.config);
  }
  async validateRuntimeState(adapter: Configuration): Promise<void> {
    this.assertOpen();
    if (this.runtimeLock === null)
      throw new SetupProblem('Stop the adapter before configuration changes');
    const raw = await read(
      join(this.config.adapter.stateDirectory, 'state.json'),
    );
    if (raw === undefined) return;
    const metadata = object(raw, [
      'version',
      'origin',
      'principal',
      'discordBotId',
      'consumerId',
      'contexts',
      'records',
      'heldNotifications',
    ]);
    const principal = this.state?.credentials.at(-1)?.principal;
    if (principal === undefined)
      throw new SetupProblem('The retained service identity is unavailable');
    // Validate the existing journal with the new limits, without rewriting or discarding any evidence.
    decodeState(raw, adapter, principal, snowflake(metadata.discordBotId));
  }
  async saveConfiguration(
    expectedRevision: string,
    value: ConfigurationRevision,
  ): Promise<void> {
    // The process locks exclude other owners; this guard also excludes overlapping calls by this owner.
    if (this.configurationWriting)
      throw new SetupProblem('A configuration edit is already being published');
    if (this.runtimeLock === null)
      throw new SetupProblem('Stop the adapter before configuration changes');
    this.configurationWriting = true;
    try {
      if ((await this.configuration()).revision !== uuid(expectedRevision))
        throw new SetupProblem(
          'Configuration revision changed before publication',
        );
      const checked = decodeConfigurationRevision(value, this.config);
      if (checked.revision === expectedRevision)
        throw new SetupProblem(
          'Configuration replacement needs a fresh revision',
        );
      await this.validateRuntimeState(checked.adapter);
      await this.write('configuration.json', checked);
    } finally {
      this.configurationWriting = false;
    }
  }
  /** adapter.json is a derived launch snapshot. configuration.json is the revisioned source of edits. */
  async prepareRuntime(): Promise<Configuration> {
    const adapter = (await this.configuration()).adapter;
    await this.write('adapter.json', adapter);
    return adapter;
  }
  private async write(name: string, value: unknown): Promise<void> {
    this.assertOpen();
    await atomicPrivateWrite(join(this.directory, name), value);
    const directory = await open(this.directory, 'r');
    try {
      await directory.sync();
    } finally {
      await directory.close();
    }
  }
  async save(): Promise<void> {
    if (this.state === null) throw new SetupProblem('No setup intent to save');
    const checked = decodeSetupState(this.state, this.config);
    await this.write('setup-state.json', checked);
  }
  async saveCredential(value: SavedCredential): Promise<void> {
    const checked = decodeSavedCredential(value);
    const attempt = this.state?.credentials.find(
      (c) => c.request.requestId === checked.receipt.requestId,
    );
    if (
      attempt === undefined ||
      attempt.request.project !== checked.receipt.project ||
      attempt.tokenId !== checked.receipt.tokenId ||
      attempt.principal !== checked.receipt.principal
    )
      throw new SetupProblem(
        'Credential has no matching owned issuance intent',
      );
    const existing = await this.credential(checked.receipt.requestId);
    if (
      existing !== null &&
      (!sameTokenReceipt(existing.receipt, checked.receipt) ||
        existing.credential !== checked.credential)
    )
      throw new SetupProblem(
        'Credential artifact already names different material',
      );
    await this.write(
      `credential-${uuid(checked.receipt.requestId)}.json`,
      checked,
    );
  }
  async credential(requestId: string): Promise<SavedCredential | null> {
    this.assertOpen();
    const value = await read(
      join(this.directory, `credential-${uuid(requestId)}.json`),
    );
    if (value === undefined) return null;
    const credential = decodeSavedCredential(value);
    if (credential.receipt.requestId !== requestId)
      throw new SetupProblem('Foreign private credential artifact');
    return credential;
  }
  async publish(config: Configuration, value: SavedCredential): Promise<void> {
    const checked = decodeSavedCredential(value);
    const adapter = decodeConfig(config);
    const attempt = this.state?.credentials.at(-1);
    if (
      this.state?.deploymentReceipt == null ||
      attempt?.receipt == null ||
      !sameTokenReceipt(attempt.receipt, checked.receipt) ||
      JSON.stringify(adapter) !== JSON.stringify(this.config.adapter)
    )
      throw new SetupProblem(
        'Runtime publication does not match the confirmed installation',
      );
    await this.write('adapter.json', (await this.configuration()).adapter);
    // Last write is the publication point. A crash never exposes a secret for another request.
    await this.write('service-credential.json', checked);
  }
  async serviceCredential(): Promise<SavedCredential> {
    this.assertOpen();
    const state = this.state;
    const attempt = state?.credentials.at(-1);
    const raw = await read(join(this.directory, 'service-credential.json'));
    if (
      state?.deploymentReceipt == null ||
      attempt?.receipt == null ||
      raw === undefined
    )
      throw new SetupProblem(
        'Installation is incomplete; recover before starting',
      );
    const value = decodeSavedCredential(raw);
    if (
      !sameTokenReceipt(value.receipt, attempt.receipt) ||
      Date.parse(value.receipt.expiresAt) <= Date.now()
    )
      throw new SetupProblem(
        'Published credential is stale; recover or explicitly rotate',
      );
    return value;
  }
  async published(): Promise<SavedCredential> {
    const value = await this.serviceCredential();
    const adapter = await read(join(this.directory, 'adapter.json'));
    // JSON field order is irrelevant to an existing artifact; decode before comparing canonical DTOs.
    if (
      JSON.stringify(decodeConfig(adapter)) !==
      JSON.stringify((await this.configuration()).adapter)
    )
      throw new SetupProblem(
        'Published runtime configuration differs from setup',
      );
    return value;
  }
  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    if (this.runtimeLock !== null)
      await rm(this.runtimeLock, { recursive: true });
    await rm(join(this.directory, 'setup.lock'), { recursive: true });
  }
}
