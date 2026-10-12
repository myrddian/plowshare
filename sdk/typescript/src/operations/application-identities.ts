import { record, named, nullable, bool } from './wire-checks.ts';
import { deploymentUuid } from './application-deployments.ts';

/** The reservation is metadata; only an explicit issuance returns a bearer secret. */
export interface ApplicationServiceDeclaration {
  readonly handle: string;
  readonly role: 'VIEWER' | 'CONTRIBUTOR';
}
export interface ApplicationIdentityBinding {
  readonly project: string;
  readonly service: ApplicationServiceDeclaration;
  readonly tokenId: string;
  readonly principal: string;
  readonly state: 'RESERVED' | 'ACTIVE';
  readonly credentialRequestId: string | null;
  readonly credentialIssued: boolean;
  readonly expiresAt: string;
  readonly revokedAt: string | null;
}
export interface ApplicationIdentityStatus {
  readonly project: string;
  readonly identity: ApplicationIdentityBinding | null;
}
export interface ApplicationTokenReceipt {
  readonly requestId: string;
  readonly project: string;
  readonly tokenId: string;
  readonly principal: string;
  readonly expiresAt: string;
}
export interface ApplicationCredential {
  readonly receipt: ApplicationTokenReceipt;
  readonly credential: string;
}

const oneOf =
  (...values: string[]) =>
  (value: unknown): boolean =>
    typeof value === 'string' && values.includes(value);
const handle = (value: unknown): value is string =>
  typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/.test(value);
const timestamp = (value: unknown): value is string =>
  typeof value === 'string' &&
  value.length <= 128 &&
  Number.isFinite(Date.parse(value));
const binding = record({
  project: named,
  service: record({ handle, role: oneOf('VIEWER', 'CONTRIBUTOR') }),
  tokenId: deploymentUuid,
  principal: named,
  state: oneOf('RESERVED', 'ACTIVE'),
  credentialIssued: bool,
  expiresAt: timestamp,
  revokedAt: nullable(timestamp),
  credentialRequestId: nullable(deploymentUuid),
});
const samePrincipal = (value: unknown): boolean =>
  typeof value === 'object' &&
  value !== null &&
  'tokenId' in value &&
  'principal' in value &&
  value.principal === `@service/${String(value.tokenId)}`;
const receipt = record({
  requestId: deploymentUuid,
  project: named,
  tokenId: deploymentUuid,
  principal: named,
  expiresAt: timestamp,
});
export const identityStatusCheck = (value: unknown): boolean => {
  if (!record({ project: named, identity: nullable(binding) })(value))
    return false;
  const row = value as ApplicationIdentityStatus;
  return (
    row.identity === null ||
    (row.identity.project === row.project && samePrincipal(row.identity))
  );
};
export const tokenReceiptCheck = (value: unknown): boolean =>
  receipt(value) && samePrincipal(value);
export const applicationCredentialCheck = (value: unknown): boolean =>
  record({
    receipt: tokenReceiptCheck,
    credential: (secret) =>
      typeof secret === 'string' && /^pss_[0-9a-f]{48}$/.test(secret),
  })(value);

export function identityProblem(
  type: string,
  body: Record<string, unknown>,
): string | undefined {
  const project = body['project'];
  if (
    typeof project !== 'string' ||
    !project.trim() ||
    project !== project.trim() ||
    project.length > 256 ||
    Array.from(project).some((character) => {
      const point = character.codePointAt(0)!;
      return point <= 31 || (point >= 127 && point <= 159);
    }) ||
    /^(personal:|Personal:|client:)/.test(project)
  )
    return 'Choose an ordinary Application project';
  if (type === 'application.identity.status') return undefined;
  if (!deploymentUuid(body['requestId']))
    return 'Retain a UUID requestId for credential recovery';
  if (type === 'application.token.receipt') return undefined;
  if (
    typeof body['rotate'] !== 'boolean' ||
    typeof body['expiresInDays'] !== 'number' ||
    !Number.isInteger(body['expiresInDays']) ||
    Number(body['expiresInDays']) < 1 ||
    Number(body['expiresInDays']) > 365
  )
    return 'Choose expiresInDays 1–365 and an explicit rotate boolean';
  return undefined;
}
