import { isList, isObject, hasControlCharacters } from '../binding/values.ts';
import { informationPayloadProblem } from './information-validation.ts';
import { decodeSchemaInstance } from './schema-instances.ts';
import { utf8Length } from '../binding/files.ts';
const uuid = /^[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$/;
function identifier(value: unknown): boolean {
  return typeof value === 'string' && uuid.test(value);
}

/** Compact projected fields remain bounded untrusted values at the response boundary. */
export function validateNavigationMetadata(type: string, value: unknown): void {
  if (type !== 'information.documents.list' || !isObject(value)) return;
  const rows = value['documents'];
  if (!isList(rows) || rows.length > 100)
    throw new Error('Invalid document page');
  for (const row of rows) {
    if (!isObject(row) || !('contentMetadata' in row)) continue;
    const binding = row['contentMetadata'];
    if (!isObject(binding)) throw new Error('Invalid projected metadata');
    const identity = binding['type'];
    if (
      !isObject(identity) ||
      !identifier(identity['type']) ||
      !identifier(identity['version']) ||
      typeof identity['ordinal'] !== 'number' ||
      !Number.isSafeInteger(identity['ordinal']) ||
      identity['ordinal'] < 1 ||
      typeof identity['slug'] !== 'string' ||
      !/^[a-z](?:[a-z0-9-]{0,62}[a-z0-9])?$/.test(identity['slug']) ||
      typeof identity['label'] !== 'string' ||
      !identity['label'].trim() ||
      identity['label'].length > 120 ||
      identity['label'].includes('\0')
    )
      throw new Error('Invalid metadata type identity');
    for (const hash of [identity['definitionHash'], binding['metadataHash']])
      if (typeof hash !== 'string' || !/^[a-f0-9]{64}$/.test(hash))
        throw new Error('Invalid metadata digest');
    if (
      !isObject(binding['metadata']) ||
      utf8Length(JSON.stringify(binding['metadata'])) > 16384
    )
      throw new Error('Invalid metadata instance');
    decodeSchemaInstance(binding['metadata']);
  }
}
function bounded(value: unknown, max: number): boolean {
  return (
    typeof value === 'string' &&
    !!value.trim() &&
    value.length <= max &&
    !hasControlCharacters(value)
  );
}
function target(value: unknown, policy: boolean): boolean {
  if (!isObject(value)) return false;
  if (!policy)
    return (
      identifier(value['resource']) &&
      (!('revision' in value) || identifier(value['revision']))
    );
  return (
    value['kind'] === 'platform' ||
    (value['kind'] === 'project' && bounded(value['project'], 1024)) ||
    (value['kind'] === 'document' && identifier(value['resource']))
  );
}
function filter(value: unknown, scope: unknown): string | undefined {
  return informationPayloadProblem(
    'information.documents.list',
    { scope, view: 'content-types-v1', filter: value },
    [],
  );
}
/** Structure is decoded by the generated schema; this checks semantic bounds and selectors. */
export function navigationPayloadProblem(
  type: string,
  body: Record<string, unknown>,
  required: readonly string[],
): string | undefined {
  for (const key of required) if (!(key in body)) return `${type} needs ${key}`;
  const common: Record<string, unknown> = {};
  for (const key of ['scope', 'corpus', 'view', 'filter'])
    if (key in body) common[key] = body[key];
  const commonProblem = informationPayloadProblem(type, common, []);
  if (commonProblem) return commonProblem;
  if (
    'projection' in body &&
    (body['projection'] !== 'metadata-v1' ||
      body['view'] !== 'content-types-v1')
  )
    return 'metadata projection requires its supporting view';
  if ('corpus' in body && body['corpus'] !== 'documents')
    return 'navigation requires documents corpus';
  for (const key of [
    'resource',
    'source',
    'requestId',
    'id',
    'relationship',
    'expectedSourceRevision',
    'relatedTo',
    'otherResource',
  ])
    if (key in body && !identifier(body[key])) return `${key} must be a UUID`;
  if (
    'target' in body &&
    !target(body['target'], type.startsWith('information.policy.'))
  )
    return 'invalid navigation target';
  if (
    'relation' in body &&
    (typeof body['relation'] !== 'string' ||
      !/^[a-z][a-z0-9_-]{0,63}$/.test(body['relation']))
  )
    return 'invalid relationship name';
  if (
    'direction' in body &&
    !['outgoing', 'backlinks'].includes(String(body['direction']))
  )
    return 'invalid relationship direction';
  if (
    ['information.documents.list', 'information.documents.facets'].includes(
      type,
    ) &&
    !('relatedTo' in body) &&
    ('direction' in body || 'relation' in body)
  )
    return 'traversal needs a source';
  for (const key of ['limit', 'offset', 'expectedVersion'])
    if (key in body) {
      const n = body[key],
        minimum =
          key === 'limit' ||
          (key === 'expectedVersion' &&
            [
              'information.relationships.remove',
              'information.views.delete',
            ].includes(type))
            ? 1
            : 0;
      if (
        typeof n !== 'number' ||
        !Number.isSafeInteger(n) ||
        n < minimum ||
        n >
          (key === 'limit'
            ? 100
            : key === 'expectedVersion'
              ? 9007199254740990
              : 2147483647)
      )
        return 'invalid navigation control or page bound';
    }
  if ('label' in body && !bounded(body['label'], 120))
    return 'invalid saved view label';
  if (
    'reason' in body &&
    (typeof body['reason'] !== 'string' ||
      !body['reason'].trim() ||
      body['reason'].length > 4096 ||
      body['reason'].includes('\0'))
  )
    return 'invalid policy reason';
  const settings = body['settings'];
  if (settings !== undefined) {
    if (
      !isObject(settings) ||
      !bounded(settings['referenceLabel'], 120) ||
      typeof settings['allowOverrides'] !== 'boolean'
    )
      return 'invalid policy settings';
    for (const key of ['defaults', 'mandatory']) {
      const policy = settings[key];
      if (
        !isObject(policy) ||
        ['ownerOnly', 'referenceHidden', 'propagate'].some(
          (k) => typeof policy[k] !== 'boolean',
        )
      )
        return 'invalid reference policy';
    }
  }
  const path = body['path'];
  if (path !== undefined) {
    if (
      !isObject(path) ||
      !isList(path['steps']) ||
      path['steps'].length < 1 ||
      path['steps'].length > 32
    )
      return 'path needs 1..32 typed steps';
    const steps = path['steps'];
    for (let index = 0; index < steps.length; index++) {
      const step = steps[index];
      if (!isObject(step)) return 'invalid path step';
      if (
        (index === 0 && step['kind'] !== 'scope') ||
        (index > 0 && step['kind'] === 'scope')
      )
        return 'path must start with its only scope';
      if (step['kind'] === 'scope') {
        const problem = informationPayloadProblem(
          type,
          { scope: step['scope'] },
          [],
        );
        if (problem) return problem;
      } else if (step['kind'] === 'document') {
        if (!target(step['target'], false)) return 'invalid path target';
      } else if (step['kind'] === 'traverse') {
        if (
          typeof step['relation'] !== 'string' ||
          !/^[a-z][a-z0-9_-]{0,63}$/.test(step['relation']) ||
          !['outgoing', 'backlinks'].includes(String(step['direction']))
        )
          return 'invalid traversal step';
      } else if (step['kind'] === 'filter') {
        const problem = filter(step['filter'], body['scope']);
        if (problem) return problem;
      } else return 'unknown path step';
    }
  }
  return undefined;
}
