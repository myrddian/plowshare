import { randomUUID } from 'node:crypto';
import type { Configuration } from './contracts.ts';
import type { SetupConfiguration } from './setup-contracts.ts';
import { decodeSetupConfig, setupHash } from './setup-config.ts';
import { object } from './config.ts';
import { uuid } from './state.ts';
import { SetupProblem } from './setup-error.ts';

/** The installation journal is immutable; operational edits have their own compare-and-set revision. */
export interface ConfigurationRevision {
  readonly version: 1;
  readonly installation: string;
  readonly revision: string;
  readonly adapter: Configuration;
}
/** Caller holds the installation and runtime locks for this repository's entire lifetime. */
export interface ConfigurationRepository {
  readonly config: SetupConfiguration;
  configuration(): Promise<ConfigurationRevision>;
  saveConfiguration(
    expectedRevision: string,
    value: ConfigurationRevision,
  ): Promise<void>;
}
/** Verification is read-only: authenticate the retained service and inspect receiving catalogues. */
export interface ConfigurationVerifier {
  verify(adapter: Configuration): Promise<void>;
}
export function checkedConfiguration(
  base: SetupConfiguration,
  value: unknown,
): Configuration {
  const adapter = decodeSetupConfig({ ...base, adapter: value }).adapter;
  if (
    adapter.origin !== base.adapter.origin ||
    adapter.stateDirectory !== base.adapter.stateDirectory ||
    adapter.serviceTokenEnvironment !== base.adapter.serviceTokenEnvironment ||
    adapter.discordTokenEnvironment !== base.adapter.discordTokenEnvironment
  )
    throw new SetupProblem(
      'Configuration edits cannot change server, state directory or credential identities',
    );
  return adapter;
}
export function decodeConfigurationRevision(
  value: unknown,
  base: SetupConfiguration,
): ConfigurationRevision {
  const raw = object(value, ['version', 'installation', 'revision', 'adapter']);
  if (raw.version !== 1 || raw.installation !== setupHash(base))
    throw new SetupProblem(
      'Operational configuration belongs to another installation',
    );
  return {
    version: 1,
    installation: setupHash(base),
    revision: uuid(raw.revision),
    adapter: checkedConfiguration(base, raw.adapter),
  };
}
/** Filesystem access is operator authority for this headless interface, not browser user authentication. */
export class ConfigurationManager {
  constructor(
    private readonly repository: ConfigurationRepository,
    private readonly verifier: ConfigurationVerifier,
  ) {}
  async update(
    expectedRevision: string,
    value: unknown,
  ): Promise<ConfigurationRevision> {
    const expected = uuid(expectedRevision);
    const current = await this.repository.configuration();
    if (current.revision !== expected)
      throw new SetupProblem(
        'Configuration revision changed; read the current revision before another edit',
      );
    const adapter = checkedConfiguration(this.repository.config, value);
    await this.verifier.verify(adapter);
    const next: ConfigurationRevision = {
      version: 1,
      installation: current.installation,
      revision: randomUUID(),
      adapter,
    };
    // A failed response after this replacement is reconciled by reading configuration, never by retrying blindly.
    await this.repository.saveConfiguration(expected, next);
    return next;
  }
}
