import type { InformationScope } from 'plowshare-client-ts/operations/information';
import type { PortableImportPlan } from 'plowshare-client-ts/operations/information-interchange';
import { decodePayload } from 'plowshare-client-ts/operations/schema';
import { isObject } from 'plowshare-client-ts/binding/values';

export const RECOVERY_FORMAT = 'plowshare-information-import-recovery/1';
/** Caller-owned recovery data only; never an automatic replay instruction or a credential. */
export interface ImportRecovery {
  readonly format: typeof RECOVERY_FORMAT;
  readonly requestId: string;
  readonly context: string;
  readonly scope: InformationScope;
  readonly plan: PortableImportPlan;
}
export function scopeKey(scope: InformationScope): string {
  if (scope.kind === 'account')
    throw new Error('Account destinations do not support information import');
  return JSON.stringify([
    scope.kind,
    scope.kind === 'project' ? scope.project : null,
    scope.includeShared ?? scope.kind !== 'shared',
  ]);
}
export function recoveryContext(
  identity: string,
  scope: InformationScope,
): string {
  return JSON.stringify([identity, scopeKey(scope)]);
}
/** Validate files and IPC independently, before local persistence or any server submission. */
export function decodeImportRecovery(value: unknown): ImportRecovery {
  if (
    !isObject(value) ||
    value['format'] !== RECOVERY_FORMAT ||
    Object.keys(value).some(
      (name) =>
        !['format', 'requestId', 'context', 'scope', 'plan'].includes(name),
    ) ||
    typeof value['context'] !== 'string' ||
    !value['context'] ||
    value['context'].length > 8192 ||
    value['context'].includes('\0')
  )
    throw new Error('Invalid import recovery file');
  const decoded = decodePayload('information.interchange.import', {
    requestId: value['requestId'],
    scope: value['scope'],
    plan: value['plan'],
  });
  return { format: RECOVERY_FORMAT, context: value['context'], ...decoded };
}
