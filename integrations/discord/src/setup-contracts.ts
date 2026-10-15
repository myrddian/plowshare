import type { Payloads, Replies } from 'plowshare-client-node';
import type { Configuration } from './contracts.ts';

export interface SetupConfiguration {
  readonly account: string;
  readonly project: string;
  readonly serviceHandle: string;
  readonly sourceDirectory: string;
  readonly outputDirectory: string;
  readonly destination: Payloads['application.deploy']['destination'];
  readonly writableAreas: Payloads['application.deploy']['writableAreas'];
  readonly expiresInDays: number;
  readonly adapter: Configuration;
}
export type DeploymentReceipt = Replies['application.deploy'];
export type TokenReceipt = Replies['application.token.receipt'];
export type Identity = NonNullable<
  Replies['application.identity.status']['identity']
>;
export interface CredentialAttempt {
  readonly request: Payloads['application.token.issue'];
  readonly tokenId: string;
  readonly principal: string;
  receipt: TokenReceipt | null;
}
/** Immutable admission coordinates, bounded credential history, and receipts; never secrets. */
export interface SetupState {
  readonly version: 1;
  readonly configurationHash: string;
  readonly deployment: Payloads['application.deploy'];
  deploymentReceipt: DeploymentReceipt | null;
  readonly credentials: CredentialAttempt[];
}
export interface SavedCredential {
  readonly receipt: TokenReceipt;
  readonly credential: string;
}
/** Receipt identity is semantic; transport/object property order is not part of the contract. */
export function sameTokenReceipt(
  first: TokenReceipt,
  second: TokenReceipt,
): boolean {
  return (
    first.project === second.project &&
    first.requestId === second.requestId &&
    first.tokenId === second.tokenId &&
    first.principal === second.principal &&
    first.expiresAt === second.expiresAt
  );
}
/** Public SDK capabilities only. All mutations are single submissions after durable intent. */
export interface SetupGateway {
  administrator(): Promise<Replies['admin.status']>;
  deploymentStatus(
    project: string,
  ): Promise<Replies['application.deployment.status']>;
  deploy(request: Payloads['application.deploy']): Promise<DeploymentReceipt>;
  deploymentReceipt(
    project: string,
    requestId: string,
  ): Promise<DeploymentReceipt>;
  identity(project: string): Promise<Replies['application.identity.status']>;
  issue(
    request: Payloads['application.token.issue'],
  ): Promise<Replies['application.token.issue']>;
  tokenReceipt(project: string, requestId: string): Promise<TokenReceipt>;
  verify(
    credential: string,
    principal: string,
    config: Configuration,
  ): Promise<void>;
}
/** The setup process owns this repository exclusively; save completes after durable replacement. */
export interface SetupRepository {
  state: SetupState | null;
  save(): Promise<void>;
  saveCredential(value: SavedCredential): Promise<void>;
  credential(requestId: string): Promise<SavedCredential | null>;
  publish(config: Configuration, value: SavedCredential): Promise<void>;
}
