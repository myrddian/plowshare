import { bool, nullable, object, record, type Check } from './wire-checks.ts';
import {
  providerUuid,
  providerProject,
  providerConnectionCheck,
  type ProviderConnection,
} from './provider-credentials.ts';
import type { ToolScopeConnection } from './tool-scopes.ts';
import {
  relayPortProblem,
  validateRelayPortReply,
  type RelayPortReplies,
} from './relay-ports.ts';

/** Durable owner configuration; the executor and credential subject are separate principals. */
export interface HostedToolDefinition {
  readonly scope: string;
  readonly provider: string;
  readonly prefix: string;
  readonly grants: readonly string[];
  readonly agents: readonly string[];
  readonly leaseSeconds: number;
  readonly credentialProject: string;
  readonly connection: string;
  readonly subject: string | null;
  readonly executor: string;
}
export interface HostedToolBinding {
  readonly binding: string;
  readonly project: string;
  readonly owner: string;
  readonly generation: number;
  readonly enabled: boolean;
  readonly deleted: boolean;
  readonly application: boolean;
  readonly definition: HostedToolDefinition;
  readonly credentialSubject: string;
  readonly route: ToolScopeConnection;
}
export interface HostedToolReceipt {
  readonly requestId: string;
  readonly operation: 'tool.scope.host.save' | 'tool.scope.host.change';
  readonly binding: HostedToolBinding;
}
export interface HostedToolRegistration {
  readonly binding: HostedToolBinding;
  readonly registration: string;
  readonly registeredAt: string;
  readonly expiresAt: string;
}
export interface HostedToolWorker {
  readonly binding: string;
  readonly registration: string;
}
export interface HostedToolWorkerStatus {
  readonly binding: string;
  readonly registration: HostedToolRegistration | null;
}
export interface HostedToolCredentialRequest extends HostedToolWorker {
  readonly purpose: 'DISCOVERY' | 'EXECUTION';
  readonly invocation: string | null;
}
/** Secret-bearing reply. Never log, persist or include value in diagnostics. */
export interface HostedToolCredential extends HostedToolCredentialRequest {
  readonly generation: number;
  readonly connection: ProviderConnection;
  readonly subject: string;
  readonly credentialGeneration: string;
  readonly header: 'Authorization' | 'X-API-Key' | 'Api-Key';
  readonly value: string;
}
export interface HostedToolPublication {
  readonly requestId: string;
  readonly project: string;
  readonly topic: string;
  readonly text: string;
  readonly occurredAt: string;
  readonly correlationId: string | null;
  readonly parentTopic: string | null;
  readonly parentEventId: string | null;
}
export interface HostedToolConsumption {
  readonly project: string;
  readonly topic: string;
  readonly group: string;
  readonly consumerId: string;
  readonly start: 'OLDEST_RETAINED' | 'LATEST';
  readonly limit: number;
  readonly waitMs: number;
}
export interface HostedToolAcknowledgement {
  readonly project: string;
  readonly topic: string;
  readonly group: string;
  readonly consumerId: string;
  readonly batchId: string;
  readonly fence: string;
  readonly expiredThrough: string | null;
}
export interface HostedToolPayloads {
  'tool.scope.host.save': {
    readonly project: string;
    readonly binding: string;
    readonly requestId: string;
    readonly expectedGeneration: number | null;
    readonly definition: HostedToolDefinition;
  };
  'tool.scope.host.change': {
    readonly project: string;
    readonly binding: string;
    readonly requestId: string;
    readonly expectedGeneration: number;
    readonly action: 'ENABLE' | 'DISABLE' | 'DELETE';
  };
  'tool.scope.host.list': { readonly project: string };
  'tool.scope.host.status': {
    readonly project: string;
    readonly binding: string;
  };
  'tool.scope.host.receipt': {
    readonly project: string;
    readonly requestId: string;
  };
  'tool.scope.worker.register': {
    readonly binding: string;
    readonly requestId: string;
    readonly expectedGeneration: number;
  };
  'tool.scope.worker.renew': HostedToolWorker;
  'tool.scope.worker.status': { readonly binding: string };
  'tool.scope.worker.credential': HostedToolCredentialRequest;
  'tool.scope.worker.publish': HostedToolWorker & {
    readonly publication: HostedToolPublication;
  };
  'tool.scope.worker.consume': HostedToolWorker & {
    readonly consumption: HostedToolConsumption;
  };
  'tool.scope.worker.read': HostedToolWorker & { readonly invocation: string };
  'tool.scope.worker.ack': HostedToolWorker & {
    readonly acknowledgement: HostedToolAcknowledgement;
  };
}
export interface HostedToolReconciliation extends HostedToolWorker {
  readonly invocation: string;
  readonly verified: boolean;
  readonly state: 'COMPLETED' | 'REJECTED' | 'UNKNOWN';
  readonly text: string;
}
export interface HostedToolReplies {
  'tool.scope.host.save': HostedToolReceipt;
  'tool.scope.host.change': HostedToolReceipt;
  'tool.scope.host.list': readonly HostedToolBinding[];
  'tool.scope.host.status': HostedToolBinding;
  'tool.scope.host.receipt': HostedToolReceipt;
  'tool.scope.worker.register': HostedToolRegistration;
  'tool.scope.worker.renew': HostedToolRegistration;
  'tool.scope.worker.status': HostedToolWorkerStatus;
  'tool.scope.worker.credential': HostedToolCredential;
  'tool.scope.worker.publish': RelayPortReplies['relay.publish'];
  'tool.scope.worker.consume': RelayPortReplies['relay.consume'];
  'tool.scope.worker.read': HostedToolReconciliation;
  'tool.scope.worker.ack': RelayPortReplies['relay.ack'];
}
const oneOf =
  (...options: string[]): Check =>
  (v) =>
    typeof v === 'string' && options.includes(v);
const generation: Check = (v) =>
  typeof v === 'number' && Number.isSafeInteger(v) && v >= 1;
const project: Check = (v) =>
  typeof v === 'string' &&
  v.length >= 1 &&
  v.length <= 256 &&
  v === v.trim() &&
  !v.startsWith('client:') &&
  !Array.from(v).some((c) => {
    const n = c.codePointAt(0)!;
    return n <= 31 || (n >= 127 && n <= 159) || n === 0x2028 || n === 0x2029;
  });
const service: Check = (v) =>
  typeof v === 'string' &&
  v.startsWith('@service/') &&
  providerUuid(v.slice(9));
const principal: Check = (v) =>
  service(v) ||
  (typeof v === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/u.test(v));
const identifier: Check = (v) =>
  typeof v === 'string' &&
  v.length <= 64 &&
  /^[a-z][a-z0-9]*(?:[_-][a-z0-9]+)*$/u.test(v);
const provider: Check = (v) =>
  typeof v === 'string' &&
  v.length <= 48 &&
  /^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/u.test(v);
const prefix: Check = (v) =>
  typeof v === 'string' && v.length <= 48 && /^[a-z][a-z0-9_]*_$/u.test(v);
const tool: Check = (v) =>
  typeof v === 'string' &&
  v.length <= 64 &&
  /^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$/u.test(v);
const entries =
  (check: Check): Check =>
  (v) =>
    Array.isArray(v) &&
    v.length >= 1 &&
    v.length <= 128 &&
    new Set(v).size === v.length &&
    v.every(check);
const lease: Check = (v) =>
  typeof v === 'number' && Number.isInteger(v) && v >= 1 && v <= 300;
const instant: Check = (v) =>
  typeof v === 'string' &&
  v.length <= 128 &&
  /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/u.test(v) &&
  Number.isFinite(Date.parse(v));
export const hostedDefinitionCheck = record(
  {
    scope: identifier,
    provider,
    prefix,
    grants: entries((v) => v === '*' || tool(v)),
    agents: entries(identifier),
    leaseSeconds: lease,
    credentialProject: providerProject,
    connection: providerUuid,
    subject: nullable(service),
    executor: service,
  },
  (v) =>
    Array.isArray(v['grants']) &&
    v['grants'].every((g: unknown) =>
      g === '*'
        ? Array.isArray(v['grants']) && v['grants'].length === 1
        : typeof g === 'string' &&
          typeof v['prefix'] === 'string' &&
          g.startsWith(v['prefix']),
    ),
);
const routeCheck = record({
  project,
  scope: identifier,
  sourceProvider: provider,
  provider: (v) => typeof v === 'string' && /^host-[0-9a-f]{32}$/u.test(v),
  account: service,
  prefix,
  grants: entries((v) => v === '*' || tool(v)),
  agents: entries(identifier),
  leaseSeconds: lease,
});
export const hostedBindingCheck = record(
  {
    binding: providerUuid,
    project,
    owner: principal,
    generation,
    enabled: bool,
    deleted: bool,
    application: bool,
    definition: hostedDefinitionCheck,
    credentialSubject: principal,
    route: routeCheck,
  },
  (v) => {
    const d = v['definition'],
      r = v['route'];
    if (!object(d) || !object(r)) return false;
    return (
      !(v['deleted'] === true && v['enabled'] === true) &&
      r['project'] === v['project'] &&
      r['account'] === d['executor'] &&
      r['sourceProvider'] === d['provider'] &&
      ['scope', 'prefix', 'leaseSeconds'].every((k) => r[k] === d[k]) &&
      ['grants', 'agents'].every(
        (k) => JSON.stringify(r[k]) === JSON.stringify(d[k]),
      ) &&
      v['credentialSubject'] === (d['subject'] ?? v['owner'])
    );
  },
);
export const hostedReconciliationCheck = record({
  binding: providerUuid,
  registration: providerUuid,
  invocation: providerUuid,
  verified: bool,
  state: oneOf('COMPLETED', 'REJECTED', 'UNKNOWN'),
  text: (v) => typeof v === 'string' && v.length <= 65536,
});
export const hostedReceiptCheck = record({
  requestId: providerUuid,
  operation: oneOf('tool.scope.host.save', 'tool.scope.host.change'),
  binding: hostedBindingCheck,
});
export const hostedRegistrationCheck = record(
  {
    binding: hostedBindingCheck,
    registration: providerUuid,
    registeredAt: instant,
    expiresAt: instant,
  },
  (v) =>
    typeof v['registeredAt'] === 'string' &&
    typeof v['expiresAt'] === 'string' &&
    Date.parse(v['registeredAt']) < Date.parse(v['expiresAt']),
);
export const hostedWorkerStatusCheck = record(
  { binding: providerUuid, registration: nullable(hostedRegistrationCheck) },
  (v) =>
    v['registration'] === null ||
    (object(v['registration']) &&
      object(v['registration']['binding']) &&
      v['registration']['binding']['binding'] === v['binding']),
);
const credentialRequestCheck = record(
  {
    binding: providerUuid,
    registration: providerUuid,
    purpose: oneOf('DISCOVERY', 'EXECUTION'),
    invocation: nullable(providerUuid),
  },
  (v) => (v['purpose'] === 'EXECUTION') === (v['invocation'] !== null),
);
export const hostedCredentialCheck = record(
  {
    binding: providerUuid,
    registration: providerUuid,
    purpose: oneOf('DISCOVERY', 'EXECUTION'),
    invocation: nullable(providerUuid),
    generation,
    connection: providerConnectionCheck,
    subject: principal,
    credentialGeneration: providerUuid,
    header: oneOf('Authorization', 'X-API-Key', 'Api-Key'),
    value: (v) =>
      typeof v === 'string' &&
      v.length >= 1 &&
      v.length <= 8200 &&
      /^[\x20-\x7e]+$/u.test(v),
  },
  credentialRequestCheck,
);
export const hostedPortCheck =
  (operation: string): Check =>
  (v) => {
    try {
      validateRelayPortReply(operation, v);
      return true;
    } catch {
      return false;
    }
  };
export const isHostedOperation = (type: string): boolean =>
  type.startsWith('tool.scope.host.') || type.startsWith('tool.scope.worker.');
export function hostedProblem(
  type: string,
  p: Record<string, unknown>,
): string | undefined {
  if (!isHostedOperation(type)) return;
  if (
    ('project' in p && !project(p['project'])) ||
    ['binding', 'registration', 'requestId'].some(
      (k) => k in p && !providerUuid(p[k]),
    )
  )
    return 'Invalid hosted coordinates';
  if (
    'expectedGeneration' in p &&
    !(p['expectedGeneration'] === null && type === 'tool.scope.host.save') &&
    !generation(p['expectedGeneration'])
  )
    return 'Invalid hosted generation';
  if (
    type === 'tool.scope.host.save' &&
    !hostedDefinitionCheck(p['definition'])
  )
    return 'Invalid hosted definition';
  if (
    type === 'tool.scope.host.change' &&
    !oneOf('ENABLE', 'DISABLE', 'DELETE')(p['action'])
  )
    return 'Invalid hosted action';
  if (type === 'tool.scope.worker.read' && !providerUuid(p['invocation']))
    return 'Invalid hosted invocation';
  if (type === 'tool.scope.worker.credential' && !credentialRequestCheck(p))
    return 'Invalid hosted credential purpose';
  const nested =
    type === 'tool.scope.worker.publish'
      ? 'publication'
      : type === 'tool.scope.worker.consume'
        ? 'consumption'
        : type === 'tool.scope.worker.ack'
          ? 'acknowledgement'
          : undefined;
  if (nested) {
    const b = p[nested];
    if (!object(b)) return 'Invalid hosted port';
    return relayPortProblem('relay.' + type.split('.').at(-1), b);
  }
  return;
}
export function validateHostedReply(type: string, v: unknown): void {
  if (!isHostedOperation(type)) return;
  const check =
    type === 'tool.scope.worker.read'
      ? hostedReconciliationCheck
      : type === 'tool.scope.host.list'
        ? (x: unknown) =>
            Array.isArray(x) && x.length <= 256 && x.every(hostedBindingCheck)
        : type === 'tool.scope.host.status'
          ? hostedBindingCheck
          : type === 'tool.scope.worker.status'
            ? hostedWorkerStatusCheck
            : type === 'tool.scope.worker.register' ||
                type === 'tool.scope.worker.renew'
              ? hostedRegistrationCheck
              : type === 'tool.scope.worker.credential'
                ? hostedCredentialCheck
                : type.startsWith('tool.scope.host.')
                  ? hostedReceiptCheck
                  : undefined;
  if (check) {
    if (!check(v)) throw new Error('Invalid hosted scope reply');
  } else validateRelayPortReply('relay.' + type.split('.').at(-1), v);
}
/** Reject foreign coordinates; a parseable reply is not evidence of this request's completion. */
export function hostedReplyMatches(
  type: string,
  p: unknown,
  v: unknown,
): boolean {
  if (!isHostedOperation(type)) return true;
  if (!object(p)) return false;
  if (type === 'tool.scope.host.list')
    return (
      Array.isArray(v) &&
      v.every((b) => object(b) && b['project'] === p['project'])
    );
  if (!object(v)) return false;
  if (type === 'tool.scope.worker.read')
    return ['binding', 'registration', 'invocation'].every(
      (k) => p[k] === v[k],
    );
  if (type === 'tool.scope.worker.credential')
    return ['binding', 'registration', 'purpose', 'invocation'].every(
      (k) => p[k] === v[k],
    );
  if (type === 'tool.scope.worker.status') return v['binding'] === p['binding'];
  const nested = type.endsWith('.publish')
    ? 'publication'
    : type.endsWith('.consume')
      ? 'consumption'
      : type.endsWith('.ack')
        ? 'acknowledgement'
        : undefined;
  if (nested) {
    const b = p[nested];
    return (
      object(b) &&
      ['project', 'topic', 'group', 'consumerId', 'requestId', 'batchId'].every(
        (k) => !(k in b) || !(k in v) || b[k] === v[k],
      ) &&
      (type !== 'tool.scope.worker.consume' ||
        (Array.isArray(v['events']) &&
          typeof b['limit'] === 'number' &&
          v['events'].length <= b['limit']))
    );
  }
  const b = type === 'tool.scope.host.status' ? v : v['binding'];
  if (
    !object(b) ||
    ('project' in p && b['project'] !== p['project']) ||
    ('binding' in p && b['binding'] !== p['binding'])
  )
    return false;
  if (type === 'tool.scope.worker.renew')
    return v['registration'] === p['registration'];
  if (type === 'tool.scope.worker.register')
    return (
      v['registration'] === p['requestId'] &&
      b['generation'] === p['expectedGeneration']
    );
  if ('requestId' in p && v['requestId'] !== p['requestId']) return false;
  if (type === 'tool.scope.host.receipt' || type === 'tool.scope.host.status')
    return true;
  if (
    v['operation'] !== type ||
    b['generation'] !==
      (p['expectedGeneration'] === null
        ? 1
        : Number(p['expectedGeneration']) + 1)
  )
    return false;
  if (type === 'tool.scope.host.save') {
    const declared = b['definition'],
      expected = p['definition'];
    return (
      object(declared) &&
      object(expected) &&
      Object.keys(expected).every(
        (k) => JSON.stringify(declared[k]) === JSON.stringify(expected[k]),
      )
    );
  }
  return p['action'] === 'ENABLE'
    ? b['enabled'] === true && b['deleted'] === false
    : p['action'] === 'DISABLE'
      ? b['enabled'] === false && b['deleted'] === false
      : b['enabled'] === false && b['deleted'] === true;
}
