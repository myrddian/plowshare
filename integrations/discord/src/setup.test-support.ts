import { randomUUID } from 'node:crypto';
import type { Payloads } from 'plowshare-client-node';
import type { Configuration } from './contracts.ts';
import type {
  CredentialAttempt,
  DeploymentReceipt,
  Identity,
  SavedCredential,
  SetupConfiguration,
  SetupGateway,
  SetupRepository,
  SetupState,
  TokenReceipt,
} from './setup-contracts.ts';
import { configuration } from './adapter.test-support.ts';
import { decodeSetupConfig } from './setup-config.ts';

export function setupConfiguration(): SetupConfiguration {
  return decodeSetupConfig({
    account: 'operator',
    project: 'research',
    serviceHandle: 'discord-worker-fixture',
    sourceDirectory: '/tmp/discord-source-fixture',
    outputDirectory: '/tmp/discord-install-fixture',
    destination: { store: 'applications', path: 'discord' },
    writableAreas: [],
    expiresInDays: 30,
    adapter: configuration(),
  });
}
export function setupSource() {
  return [
    {
      path: 'plowshare.json',
      text: JSON.stringify({
        version: 1,
        name: 'discord',
        service: { handle: 'discord-worker', role: 'CONTRIBUTOR' },
        access: { accounts: [] },
        caps: { steps: 8, budget: 32, autoIncrease: false },
        commands: { local: { mode: 'off' }, server: { mode: 'off' } },
      }),
    },
  ];
}
export class MemorySetup implements SetupRepository {
  state: SetupState | null = null;
  persisted = '';
  readonly secrets = new Map<string, SavedCredential>();
  published: SavedCredential | null = null;
  failSave = false;
  saves = 0;
  failOnSave = 0;
  failSecret = false;
  failPublish = false;
  async save() {
    this.saves++;
    if (this.failSave || this.saves === this.failOnSave)
      throw new Error('Fixture save failed');
    this.persisted = JSON.stringify(this.state);
  }
  async saveCredential(value: SavedCredential) {
    if (this.failSecret) throw new Error('Fixture secret save failed');
    this.secrets.set(value.receipt.requestId, value);
  }
  async credential(requestId: string) {
    return this.secrets.get(requestId) ?? null;
  }
  async publish(_config: Configuration, value: SavedCredential) {
    if (this.failPublish) throw new Error('Fixture publish failed');
    this.published = value;
  }
}
export class SetupServer implements SetupGateway {
  readonly deployments: Payloads['application.deploy'][] = [];
  readonly issuances: Payloads['application.token.issue'][] = [];
  readonly tokenReceipts = new Map<string, TokenReceipt>();
  readonly verifications: string[] = [];
  admin = true;
  handle = 'operator';
  release: DeploymentReceipt | null = null;
  identityValue: Identity | null = null;
  failDeploy = false;
  failIssue = false;
  missingReceipt = false;
  failVerify = false;
  constructor(readonly config = setupConfiguration()) {}
  async administrator() {
    return { handle: this.handle, serverAdmin: this.admin };
  }
  async deploymentStatus(project: string) {
    return {
      project,
      activeRevision: this.release?.release.revision ?? null,
      releases: this.release === null ? [] : [this.release.release],
    };
  }
  async deploy(request: Payloads['application.deploy']) {
    this.deployments.push(request);
    this.release = {
      project: request.project,
      requestId: request.requestId,
      release: {
        revision: randomUUID(),
        digest: 'a'.repeat(64),
        fileCount: request.files.length,
      },
    };
    const tokenId = randomUUID();
    this.identityValue = {
      project: request.project,
      service: { handle: this.config.serviceHandle, role: 'CONTRIBUTOR' },
      tokenId,
      principal: `@service/${tokenId}`,
      state: 'ACTIVE',
      credentialIssued: false,
      credentialRequestId: null,
      expiresAt: new Date(Date.now() + 86400000).toISOString(),
      revokedAt: null,
    };
    if (this.failDeploy) throw new Error('Fixture lost deployment reply');
    return this.release;
  }
  async deploymentReceipt(project: string, requestId: string) {
    if (
      this.missingReceipt ||
      this.release?.project !== project ||
      this.release.requestId !== requestId
    )
      throw new Error('Fixture receipt unavailable');
    return this.release;
  }
  async identity(project: string) {
    return { project, identity: this.identityValue };
  }
  async issue(request: Payloads['application.token.issue']) {
    this.issuances.push(request);
    const identity = this.identityValue;
    if (identity === null) throw new Error('Fixture identity missing');
    const receipt = {
      requestId: request.requestId,
      project: request.project,
      tokenId: identity.tokenId,
      principal: identity.principal,
      expiresAt: new Date(Date.now() + 86400000).toISOString(),
    };
    this.tokenReceipts.set(request.requestId, receipt);
    this.identityValue = {
      ...identity,
      credentialIssued: true,
      credentialRequestId: request.requestId,
      expiresAt: receipt.expiresAt,
      revokedAt: null,
    };
    if (this.failIssue) throw new Error('Fixture lost credential reply');
    return { receipt, credential: 'pss_' + '1'.repeat(48) };
  }
  async tokenReceipt(project: string, requestId: string) {
    const receipt = this.tokenReceipts.get(requestId);
    if (
      this.missingReceipt ||
      receipt === undefined ||
      receipt.project !== project
    )
      throw new Error('Fixture receipt unavailable');
    return receipt;
  }
  async verify(_credential: string, principal: string) {
    this.verifications.push(principal);
    if (this.failVerify || principal !== this.identityValue?.principal)
      throw new Error('Fixture service verification failed');
  }
}
export function latest(repository: MemorySetup): CredentialAttempt {
  const value = repository.state?.credentials.at(-1);
  if (value === undefined) throw new Error('Fixture credential intent missing');
  return value;
}
