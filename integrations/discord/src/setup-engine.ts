import { SetupProblem } from './setup-error.ts';
import { randomUUID } from 'node:crypto';
import { decodePayload } from 'plowshare-client-ts/operations/schema';
import type { ApplicationSourceFile } from 'plowshare-client-ts/operations/application-deployments';
import { setupFiles, setupHash } from './setup-config.ts';
import { sameTokenReceipt } from './setup-contracts.ts';
import type {
  CredentialAttempt,
  Identity,
  SetupConfiguration,
  SetupGateway,
  SetupRepository,
  SetupState,
  TokenReceipt,
} from './setup-contracts.ts';

/** First deployment only. Callers serialize commands and exclusively own the repository.
 * Every uncertain attempt is reconciled by its owning receipt, never replayed. */
export class DiscordSetup {
  constructor(
    private readonly config: SetupConfiguration,
    private readonly repository: SetupRepository,
    private readonly gateway: SetupGateway,
  ) {}
  private state(): SetupState {
    const state = this.repository.state;
    if (state === null || state.configurationHash !== setupHash(this.config))
      throw new SetupProblem('No matching setup intent');
    return state;
  }
  private async administrator(): Promise<void> {
    const identity = await this.gateway.administrator();
    if (!identity.serverAdmin || identity.handle !== this.config.account)
      throw new SetupProblem(
        'Setup requires the selected signed-in server administrator',
      );
  }
  async install(files: readonly ApplicationSourceFile[]): Promise<void> {
    await this.administrator();
    if (this.repository.state !== null) return this.recover();
    const deployment = decodePayload('application.deploy', {
      project: this.config.project,
      requestId: randomUUID(),
      expectedRevision: null,
      destination: this.config.destination,
      writableAreas: this.config.writableAreas,
      files: setupFiles(this.config, files),
    });
    const status = await this.gateway.deploymentStatus(this.config.project);
    const identity = await this.gateway.identity(this.config.project);
    if (
      status.activeRevision !== null ||
      status.releases.length > 0 ||
      identity.identity !== null
    )
      throw new SetupProblem(
        'Setup needs a new Application project; existing work cannot be adopted',
      );
    this.repository.state = {
      version: 1,
      configurationHash: setupHash(this.config),
      deployment,
      deploymentReceipt: null,
      credentials: [],
    };
    await this.repository.save();
    const receipt = await this.gateway.deploy(deployment);
    this.checkDeployment(receipt);
    this.state().deploymentReceipt = receipt;
    await this.repository.save();
    await this.recover();
  }
  private checkDeployment(receipt: SetupState['deploymentReceipt']): void {
    if (
      receipt === null ||
      receipt.project !== this.config.project ||
      receipt.requestId !== this.state().deployment.requestId
    )
      throw new SetupProblem('Foreign setup deployment receipt');
  }
  private async active(): Promise<Identity> {
    const state = this.state();
    if (state.deploymentReceipt === null) {
      const receipt = await this.gateway.deploymentReceipt(
        this.config.project,
        state.deployment.requestId,
      );
      this.checkDeployment(receipt);
      state.deploymentReceipt = receipt;
      await this.repository.save();
    }
    const status = await this.gateway.deploymentStatus(this.config.project);
    if (status.activeRevision !== state.deploymentReceipt.release.revision)
      throw new SetupProblem(
        'The retained deployment is no longer active; inspect source before setup changes',
      );
    const identity = (await this.gateway.identity(this.config.project))
      .identity;
    if (
      identity === null ||
      identity.project !== this.config.project ||
      identity.state !== 'ACTIVE' ||
      identity.service.handle !== this.config.serviceHandle ||
      identity.service.role !== 'CONTRIBUTOR' ||
      identity.principal !== `@service/${identity.tokenId}`
    )
      throw new SetupProblem(
        'The Application does not own the configured Contributor identity',
      );
    return identity;
  }
  private checkToken(receipt: TokenReceipt, attempt: CredentialAttempt): void {
    if (
      receipt.project !== this.config.project ||
      receipt.requestId !== attempt.request.requestId ||
      receipt.tokenId !== attempt.tokenId ||
      receipt.principal !== attempt.principal
    )
      throw new SetupProblem('Foreign setup credential receipt');
  }
  private async confirmed(attempt: CredentialAttempt): Promise<TokenReceipt> {
    const receipt = await this.gateway.tokenReceipt(
      this.config.project,
      attempt.request.requestId,
    );
    this.checkToken(receipt, attempt);
    attempt.receipt = receipt;
    await this.repository.save();
    return receipt;
  }
  private async issue(identity: Identity, rotate: boolean): Promise<void> {
    const state = this.state();
    if (state.credentials.length >= 50)
      throw new SetupProblem('Setup credential history capacity reached');
    if (identity.credentialIssued !== rotate)
      throw new SetupProblem('Inspect identity before issuance or rotation');
    const attempt: CredentialAttempt = {
      request: {
        project: this.config.project,
        requestId: randomUUID(),
        expiresInDays: this.config.expiresInDays,
        rotate,
      },
      tokenId: identity.tokenId,
      principal: identity.principal,
      receipt: null,
    };
    state.credentials.push(attempt);
    await this.repository.save();
    const issued = await this.gateway.issue(attempt.request);
    this.checkToken(issued.receipt, attempt);
    await this.repository.saveCredential(issued);
    attempt.receipt = issued.receipt;
    await this.repository.save();
  }
  async recover(): Promise<void> {
    await this.administrator();
    let identity = await this.active();
    let attempt = this.state().credentials.at(-1);
    if (attempt === undefined) {
      // No persisted credential intent means this planned step has never been attempted.
      await this.issue(identity, false);
      attempt = this.state().credentials.at(-1);
      identity = await this.active();
    }
    if (attempt === undefined)
      throw new SetupProblem('Credential intent is unavailable');
    const receipt = await this.confirmed(attempt);
    if (
      identity.tokenId !== attempt.tokenId ||
      identity.credentialRequestId !== receipt.requestId ||
      !identity.credentialIssued ||
      identity.revokedAt !== null ||
      Date.parse(identity.expiresAt) <= Date.now() ||
      Date.parse(receipt.expiresAt) <= Date.now()
    )
      throw new SetupProblem(
        'Credential was superseded, revoked or expired; inspect identity and explicitly rotate',
      );
    const secret = await this.repository.credential(receipt.requestId);
    if (secret === null)
      throw new SetupProblem(
        'Credential issuance is confirmed but its secret was lost; explicitly rotate',
      );
    if (!sameTokenReceipt(secret.receipt, receipt))
      throw new SetupProblem(
        'Saved secret does not match its retained receipt',
      );
    await this.gateway.verify(
      secret.credential,
      receipt.principal,
      this.config.adapter,
    );
    await this.repository.publish(this.config.adapter, secret);
  }
  async rotate(): Promise<void> {
    await this.administrator();
    const identity = await this.active();
    const attempt = this.state().credentials.at(-1);
    if (attempt === undefined)
      throw new SetupProblem(
        'Recover initial issuance before choosing rotation',
      );
    // Even an explicit rotation cannot bypass an unresolved earlier submission.
    await this.confirmed(attempt);
    if (
      identity.tokenId !== attempt.tokenId ||
      identity.principal !== attempt.principal
    )
      throw new SetupProblem(
        'Owned identity changed; no credential was rotated',
      );
    await this.issue(identity, true);
    await this.recover();
  }
}
