import { bool, nullable, object, record, type Check } from './wire-checks.ts';

/** Shared settings do not contain credentials. A null requested subject derives authentication. */
export interface ProviderOAuthSettings {
  readonly issuer: string;
  readonly resource: string;
  readonly resourceMetadataUrl: string;
  readonly issuerMetadataUrl: string;
  readonly authorizationEndpoint: string;
  readonly tokenEndpoint: string;
  readonly clientId: string;
  readonly redirectUri: string;
  readonly scopes: readonly string[];
}
export interface ProviderConnectionDefinition {
  readonly name: string;
  readonly endpoint: string;
  readonly authentication: 'API_KEY' | 'OAUTH_PKCE' | 'CLIENT_CREDENTIALS';
  readonly apiKeyHeader: 'Authorization' | 'X-API-Key' | 'Api-Key' | null;
  readonly oauth: ProviderOAuthSettings | null;
}
export interface ProviderConnection {
  readonly connection: string;
  readonly project: string;
  readonly revision: string;
  readonly enabled: boolean;
  readonly deleted: boolean;
  readonly definition: ProviderConnectionDefinition;
}
export interface ProviderCredentialStatus {
  readonly connection: string;
  readonly project: string;
  readonly subject: string;
  readonly generation: string | null;
  readonly connectionRevision: string;
  readonly state:
    'NONE' | 'PENDING' | 'READY' | 'REFRESHING' | 'UNCERTAIN' | 'REVOKED';
  readonly expiresAt: string | null;
  readonly keyId: string | null;
}
export interface ProviderMutationReceipt {
  readonly requestId: string;
  readonly operation:
    | 'provider.connection.save'
    | 'provider.connection.change'
    | 'provider.credential.put'
    | 'provider.credential.revoke'
    | 'provider.credential.rekey'
    | 'provider.credential.authorize'
    | 'provider.credential.refresh';
  readonly connection: ProviderConnection;
  readonly credential: ProviderCredentialStatus | null;
}
export interface ProviderAuthorization {
  readonly receipt: ProviderMutationReceipt;
  readonly authorizationUrl: string;
  readonly expiresAt: string;
}
export interface ProviderCredentialTarget {
  readonly project: string;
  readonly connection: string;
  readonly subject: string | null;
}
export interface ProviderCredentialMutation extends ProviderCredentialTarget {
  readonly requestId: string;
  readonly expectedGeneration: string | null;
}
export interface ProviderCredentialPayloads {
  'provider.connection.list': { readonly project: string };
  'provider.connection.status': {
    readonly project: string;
    readonly connection: string;
  };
  'provider.connection.receipt': {
    readonly project: string;
    readonly requestId: string;
  };
  'provider.connection.save': {
    readonly project: string;
    readonly connection: string;
    readonly requestId: string;
    readonly expectedRevision: string | null;
    readonly definition: ProviderConnectionDefinition;
  };
  'provider.connection.change': {
    readonly project: string;
    readonly connection: string;
    readonly requestId: string;
    readonly expectedRevision: string;
    readonly action: 'ENABLE' | 'DISABLE' | 'DELETE';
  };
  'provider.credential.status': ProviderCredentialTarget;
  'provider.credential.put': ProviderCredentialMutation & {
    readonly secret: string;
  };
  'provider.credential.revoke': ProviderCredentialMutation;
  'provider.credential.rekey': ProviderCredentialMutation;
  'provider.credential.authorize': ProviderCredentialMutation;
  'provider.credential.refresh': ProviderCredentialMutation;
}
const oneOf =
  (...values: string[]): Check =>
  (v) =>
    typeof v === 'string' && values.includes(v);
const text =
  (max: number): Check =>
  (v) =>
    typeof v === 'string' &&
    v.length >= 1 &&
    v.length <= max &&
    v === v.trim() &&
    !Array.from(v).some((c) => {
      const n = c.codePointAt(0)!;
      return n <= 31 || (n >= 127 && n <= 159);
    });
export const providerUuid: Check = (v) =>
  typeof v === 'string' &&
  /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/u.test(v);
export const providerProject: Check = (v) =>
  text(256)(v) &&
  typeof v === 'string' &&
  !/^(personal:|Personal:|client:)/u.test(v);
const service: Check = (v) =>
  typeof v === 'string' &&
  v.startsWith('@service/') &&
  providerUuid(v.slice(9));
const subject: Check = (v) =>
  service(v) ||
  (typeof v === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/u.test(v));
const https: Check = (v) => {
  if (
    typeof v !== 'string' ||
    v.length > 2048 ||
    !/^https:\/\/[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?(?::[1-9][0-9]{0,4})?(?:\/[A-Za-z0-9._~!$&'()*+,;=:@/-]*)?$/u.test(
      v,
    )
  )
    return false;
  const port = /^https:\/\/[^/:]+:([0-9]+)/u.exec(v)?.[1];
  return port === undefined || Number(port) <= 65535;
};
const scope: Check = (v) =>
  typeof v === 'string' && /^[\x21\x23-\x5b\x5d-\x7e]{1,128}$/u.test(v);
const scopes: Check = (v) =>
  Array.isArray(v) &&
  v.length <= 64 &&
  new Set(v).size === v.length &&
  v.every(scope);
const oauth = record({
  issuer: https,
  resource: https,
  resourceMetadataUrl: https,
  issuerMetadataUrl: https,
  authorizationEndpoint: https,
  tokenEndpoint: https,
  clientId: text(256),
  redirectUri: https,
  scopes,
});
export const providerDefinitionCheck = record(
  {
    name: text(128),
    endpoint: https,
    authentication: oneOf('API_KEY', 'OAUTH_PKCE', 'CLIENT_CREDENTIALS'),
    apiKeyHeader: nullable(oneOf('Authorization', 'X-API-Key', 'Api-Key')),
    oauth: nullable(oauth),
  },
  (v) =>
    v['authentication'] === 'API_KEY'
      ? v['oauth'] === null && v['apiKeyHeader'] !== null
      : v['oauth'] !== null && v['apiKeyHeader'] === null,
);
const timestamp: Check = (v) =>
  typeof v === 'string' &&
  v.length <= 128 &&
  /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/u.test(v) &&
  Number.isFinite(Date.parse(v));
export const providerConnectionCheck = record(
  {
    connection: providerUuid,
    project: providerProject,
    revision: providerUuid,
    enabled: bool,
    deleted: bool,
    definition: providerDefinitionCheck,
  },
  (v) => v['deleted'] !== true || v['enabled'] === false,
);
export const providerCredentialCheck = record(
  {
    connection: providerUuid,
    project: providerProject,
    subject,
    generation: nullable(providerUuid),
    connectionRevision: providerUuid,
    state: oneOf(
      'NONE',
      'PENDING',
      'READY',
      'REFRESHING',
      'UNCERTAIN',
      'REVOKED',
    ),
    expiresAt: nullable(timestamp),
    keyId: nullable(text(64)),
  },
  (v) =>
    (v['state'] === 'NONE') === (v['generation'] === null) &&
    ['READY', 'PENDING', 'REFRESHING'].includes(String(v['state'])) ===
      (v['keyId'] !== null) &&
    (v['state'] === 'READY' || v['expiresAt'] === null),
);
export const providerReceiptCheck = record(
  {
    requestId: providerUuid,
    operation: oneOf(
      'provider.connection.save',
      'provider.connection.change',
      'provider.credential.put',
      'provider.credential.revoke',
      'provider.credential.rekey',
      'provider.credential.authorize',
      'provider.credential.refresh',
    ),
    connection: providerConnectionCheck,
    credential: nullable(providerCredentialCheck),
  },
  (v) => {
    const c = v['connection'],
      g = v['credential'];
    return (
      object(c) &&
      (g === null ||
        (object(g) &&
          g['connection'] === c['connection'] &&
          g['project'] === c['project']))
    );
  },
);
export const providerAuthorizationCheck = record({
  receipt: providerReceiptCheck,
  authorizationUrl: (v) =>
    typeof v === 'string' && v.length <= 16384 && /^https:\/\//u.test(v),
  expiresAt: timestamp,
});
const secret: Check = (v) =>
  typeof v === 'string' &&
  /^[\x20-\x7e]{1,8192}$/u.test(v) &&
  !/^(pss_|pst_)/u.test(v);
export function providerCredentialProblem(
  type: string,
  body: Record<string, unknown>,
): string | undefined {
  if (
    !providerProject(body['project']) ||
    ('connection' in body && !providerUuid(body['connection'])) ||
    ('requestId' in body && !providerUuid(body['requestId']))
  )
    return 'Invalid provider coordinates';
  for (const field of ['expectedRevision', 'expectedGeneration'])
    if (field in body && body[field] !== null && !providerUuid(body[field]))
      return 'Invalid provider revision';
  if (
    'subject' in body &&
    body['subject'] !== null &&
    !service(body['subject'])
  )
    return 'Select only an exact service principal or null for the authenticated owner';
  if (
    type === 'provider.connection.save' &&
    !providerDefinitionCheck(body['definition'])
  )
    return 'Invalid provider definition';
  if (
    type === 'provider.connection.change' &&
    !oneOf('ENABLE', 'DISABLE', 'DELETE')(body['action'])
  )
    return 'Invalid provider action';
  if (type === 'provider.credential.put' && !secret(body['secret']))
    return 'Invalid upstream secret';
  return undefined;
}
export function validateProviderCredentialReply(
  type: string,
  value: unknown,
): void {
  if (
    !type.startsWith('provider.connection.') &&
    !type.startsWith('provider.credential.')
  )
    return;
  const check =
    type === 'provider.connection.list'
      ? (v: unknown) =>
          Array.isArray(v) &&
          v.length <= 256 &&
          v.every(providerConnectionCheck)
      : type === 'provider.connection.status'
        ? providerConnectionCheck
        : type === 'provider.credential.status'
          ? providerCredentialCheck
          : type === 'provider.credential.authorize'
            ? providerAuthorizationCheck
            : providerReceiptCheck;
  if (!check(value)) throw new Error('Invalid provider credential reply');
}

/** Correlate server replies before callers treat them as their own management outcome. */
export function providerReplyMatches(
  type: string,
  payload: unknown,
  value: unknown,
): boolean {
  if (
    !type.startsWith('provider.connection.') &&
    !type.startsWith('provider.credential.')
  )
    return true;
  if (!object(payload)) return false;
  if (type === 'provider.connection.list')
    return (
      Array.isArray(value) &&
      value.every(
        (v: unknown) => object(v) && v['project'] === payload['project'],
      )
    );
  if (!object(value)) return false;
  const receipt =
    type === 'provider.credential.authorize' ? value['receipt'] : value;
  if (!object(receipt)) return false;
  const row =
    type === 'provider.connection.status' ||
    type === 'provider.credential.status'
      ? receipt
      : receipt['connection'];
  if (
    !object(row) ||
    row['project'] !== payload['project'] ||
    ('connection' in payload && row['connection'] !== payload['connection'])
  )
    return false;
  if ('requestId' in payload && receipt['requestId'] !== payload['requestId'])
    return false;
  if (
    'requestId' in payload &&
    type !== 'provider.connection.receipt' &&
    receipt['operation'] !== type
  )
    return false;
  const credential =
    type === 'provider.credential.status' ? row : receipt['credential'];
  if (
    payload['subject'] !== null &&
    'subject' in payload &&
    (!object(credential) || credential['subject'] !== payload['subject'])
  )
    return false;
  return true;
}
