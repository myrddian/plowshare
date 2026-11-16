import { object } from './wire-checks.ts';

export type ApprovalMode = 'MANUAL' | 'SMART' | 'AUTO';
export type ApprovalEffect = 'MAY_APPROVE' | 'ASK_HUMAN' | 'REJECT';
export type ApprovalKind =
  'COMMAND' | 'FILE_READ' | 'FILE_WRITE' | 'FILE_DELETE' | 'FILE_MOVE';

/** Exact, account-owned consent conditions. These never grant provider or role access. */
export interface ApprovalRule {
  readonly id: string;
  readonly effect: ApprovalEffect;
  readonly kind: ApprovalKind;
  readonly project: string | null;
  readonly provider: string | null;
  readonly cwd: string | null;
  readonly inputDigest: string | null;
  readonly argv: readonly string[];
  readonly paths: readonly string[];
  readonly guidance: string | null;
}

export interface ApprovalPolicy {
  readonly revision: number;
  readonly mode: ApprovalMode;
  readonly bot: string | null;
  readonly rules: readonly ApprovalRule[];
}

export interface ApprovalCapabilities {
  readonly operations: readonly ApprovalKind[];
  readonly humanHistory: boolean;
  readonly additionalFileAccess: boolean;
}

export interface ApprovalPolicyView {
  readonly policy: ApprovalPolicy;
  readonly capabilities: ApprovalCapabilities;
}

export function isApprovalCapabilities(
  value: unknown,
): value is ApprovalCapabilities {
  return (
    object(value) &&
    Array.isArray(value['operations']) &&
    value['operations'].every((entry: unknown) =>
      oneOf(entry, [
        'COMMAND',
        'FILE_READ',
        'FILE_WRITE',
        'FILE_DELETE',
        'FILE_MOVE',
      ]),
    ) &&
    new Set(value['operations']).size === value['operations'].length &&
    typeof value['humanHistory'] === 'boolean' &&
    typeof value['additionalFileAccess'] === 'boolean'
  );
}

const named = (value: unknown, maximum: number): value is string =>
  typeof value === 'string' &&
  value.trim().length > 0 &&
  value.length <= maximum &&
  !value.includes('\0');
const optional = (value: unknown, maximum: number) =>
  value === null || named(value, maximum);
const oneOf = (value: unknown, values: readonly string[]) =>
  typeof value === 'string' && values.includes(value);
const strings = (value: unknown, maximum: number): value is string[] =>
  Array.isArray(value) &&
  value.length <= maximum &&
  value.every(
    (entry: unknown) =>
      typeof entry === 'string' &&
      entry.length <= 8192 &&
      !entry.includes('\0'),
  );

export function isApprovalRule(value: unknown): value is ApprovalRule {
  if (
    !object(value) ||
    !named(value['id'], 128) ||
    !oneOf(value['effect'], ['MAY_APPROVE', 'ASK_HUMAN', 'REJECT']) ||
    !oneOf(value['kind'], [
      'COMMAND',
      'FILE_READ',
      'FILE_WRITE',
      'FILE_DELETE',
      'FILE_MOVE',
    ]) ||
    !optional(value['project'], 512) ||
    !optional(value['provider'], 1024) ||
    !optional(value['cwd'], 8192) ||
    !optional(value['inputDigest'], 128) ||
    (typeof value['inputDigest'] === 'string' &&
      !/^[0-9a-f]{64}$/.test(value['inputDigest'])) ||
    !optional(value['guidance'], 2000) ||
    !strings(value['argv'], 256) ||
    !strings(value['paths'], 2)
  )
    return false;
  const command = value['kind'] === 'COMMAND';
  if (
    (command ? value['paths'].length !== 0 : value['argv'].length !== 0) ||
    value['paths'].some((path) => !named(path, 8192)) ||
    (value['argv'].length > 0 && !named(value['argv'][0], 8192)) ||
    (!command &&
      value['paths'].length !== 0 &&
      value['paths'].length !== (value['kind'] === 'FILE_MOVE' ? 2 : 1))
  )
    return false;
  return (
    value['effect'] !== 'MAY_APPROVE' ||
    (value['project'] !== null &&
      value['provider'] !== null &&
      value['cwd'] !== null &&
      (command ? value['argv'].length > 0 : value['paths'].length > 0))
  );
}

export function isApprovalPolicy(value: unknown): value is ApprovalPolicy {
  if (
    !object(value) ||
    typeof value['revision'] !== 'number' ||
    !Number.isSafeInteger(value['revision']) ||
    value['revision'] < 0 ||
    !oneOf(value['mode'], ['MANUAL', 'SMART', 'AUTO']) ||
    !optional(value['bot'], 128) ||
    (value['mode'] === 'AUTO' && value['bot'] === null) ||
    !Array.isArray(value['rules']) ||
    value['rules'].length > 128 ||
    !value['rules'].every(isApprovalRule)
  )
    return false;
  const textSize =
    (value['bot']?.length ?? 0) +
    value['rules'].reduce(
      (size, rule) =>
        size +
        [
          rule.id,
          rule.project,
          rule.provider,
          rule.cwd,
          rule.inputDigest,
          rule.guidance,
          ...rule.argv,
          ...rule.paths,
        ].reduce((sum, text) => sum + (text?.length ?? 0), 0),
      0,
    );
  return (
    textSize <= 100000 &&
    new Set(value['rules'].map((rule) => rule.id)).size ===
      value['rules'].length
  );
}

/** Mutation inputs reject unknown fields; forward-compatible response checks may ignore them. */
export function exactApprovalPolicy(value: unknown): value is ApprovalPolicy {
  if (!isApprovalPolicy(value)) return false;
  const policyKeys = ['revision', 'mode', 'bot', 'rules'];
  const ruleKeys = [
    'id',
    'effect',
    'kind',
    'project',
    'provider',
    'cwd',
    'inputDigest',
    'argv',
    'paths',
    'guidance',
  ];
  return (
    Object.keys(value).every((key) => policyKeys.includes(key)) &&
    value.rules.every((rule) =>
      Object.keys(rule).every((key) => ruleKeys.includes(key)),
    )
  );
}

/** Typed file consent; exact retained arguments are never reconstructed from this display. */
export interface FileApproval {
  readonly kind: Exclude<ApprovalKind, 'COMMAND'>;
  readonly provider: string;
  readonly paths: readonly string[];
  readonly inputDigest: string;
  readonly inputPreview: string | null;
}
export function isFileApproval(value: unknown): value is FileApproval {
  return (
    object(value) &&
    oneOf(value['kind'], [
      'FILE_READ',
      'FILE_WRITE',
      'FILE_DELETE',
      'FILE_MOVE',
    ]) &&
    named(value['provider'], 1024) &&
    strings(value['paths'], 2) &&
    value['paths'].length === (value['kind'] === 'FILE_MOVE' ? 2 : 1) &&
    value['paths'].every((path) => named(path, 8192)) &&
    typeof value['inputDigest'] === 'string' &&
    /^[0-9a-f]{64}$/.test(value['inputDigest']) &&
    (value['inputPreview'] === null ||
      (typeof value['inputPreview'] === 'string' &&
        value['inputPreview'].length <= 16000))
  );
}
