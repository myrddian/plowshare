import { utf8Length } from '../binding/files.ts';
import {
  decodeSchemaInstance,
  decodeSchemaDefinition,
  validateSchemaDefinition,
} from './schema-instances.ts';
import { isList, hasControlCharacters } from '../binding/values.ts';
/** Validate dispatch structure; the server owns grants, UUID references and report semantics. */
export function informationPayloadProblem(
  type: string,
  body: Record<string, unknown>,
  required: readonly string[],
): string | undefined {
  if (type === 'information.outline' || type === 'information.symbols') {
    if (body['corpus'] !== 'code')
      return 'syntax navigation requires corpus code';
    if (
      'limit' in body &&
      (typeof body['limit'] !== 'number' || body['limit'] > 100)
    )
      return 'syntax navigation limit must be between 1 and 100';
    if (
      type === 'information.symbols' &&
      (typeof body['query'] !== 'string' ||
        !body['query'].trim() ||
        body['query'].length > 128)
    )
      return 'symbol query must have 1 to 128 characters';
  }
  for (const key of required) if (!(key in body)) return `${type} needs ${key}`;
  for (const [key, value] of Object.entries(body)) {
    if (key === 'expectedTagsVersion') {
      if (
        typeof value !== 'number' ||
        !Number.isSafeInteger(value) ||
        value < 0
      )
        return 'expectedTagsVersion must be a nonnegative safe integer';
      continue;
    }
    if (['typing', 'metadata', 'presentation', 'schema'].includes(key)) {
      try {
        if (key === 'schema') {
          validateSchemaDefinition(decodeSchemaDefinition(value));
          if (utf8Length(JSON.stringify(value)) > 65536)
            return 'schema exceeds 64 KiB';
        } else if (key === 'typing') {
          if (!value || typeof value !== 'object' || isList(value))
            return 'typing must be an object';
          const typing = value as Record<string, unknown>;
          if (
            !['set', 'inherit', 'clear'].includes(String(typing['mode'])) ||
            Object.keys(typing).some(
              (field) => !['mode', 'version', 'metadata'].includes(field),
            )
          )
            return 'invalid typing alternative';
          if (
            (typing['mode'] === 'set' &&
              (typeof typing['version'] !== 'string' ||
                !('metadata' in typing))) ||
            (typing['mode'] === 'inherit' && 'version' in typing) ||
            (typing['mode'] === 'clear' &&
              ('version' in typing || 'metadata' in typing))
          )
            return 'invalid typing alternative';
          if ('metadata' in typing) {
            const instance = decodeSchemaInstance(typing['metadata']);
            if (
              !instance ||
              typeof instance !== 'object' ||
              isList(instance) ||
              utf8Length(JSON.stringify(instance)) > 16384
            )
              return 'metadata must be an object within 16 KiB';
          }
          if (body['view'] !== 'content-types-v1')
            return 'typing requires content-types-v1';
        } else {
          const instance = decodeSchemaInstance(value);
          if (
            !instance ||
            typeof instance !== 'object' ||
            isList(instance) ||
            utf8Length(JSON.stringify(instance)) >
              (key === 'metadata' ? 16384 : 8192)
          )
            return 'bounded metadata object required';
        }
      } catch {
        return 'invalid bounded schema value';
      }
    } else if (key === 'description') {
      if (
        typeof value !== 'string' ||
        value.length > 4096 ||
        value.includes('\0')
      )
        return 'invalid bounded type description';
    } else if (key === 'drafts' || key === 'publishedOnly') {
      if (typeof value !== 'boolean') return 'drafts must be boolean';
    } else if (key === 'view') {
      if (value !== 'content-types-v1') return 'unsupported information view';
    } else if (key === 'stages') {
      if (
        !isList(value) ||
        !value.length ||
        value.length > 7 ||
        new Set(value).size !== value.length ||
        value.some(
          (stage) =>
            ![
              'extract',
              'derive',
              'embed',
              'summarise',
              'summary_embed',
              'autoTag',
              'tagGroups',
            ].includes(String(stage)),
        )
      )
        return 'invalid selected stages';
    } else if (key === 'filter') {
      if (!value || typeof value !== 'object' || isList(value))
        return 'filter must be an object';
      const filter = value as Record<string, unknown>;
      if (
        ['contentType', 'contentTypeVersion', 'untyped'].some(
          (key) => key in filter,
        ) &&
        body['view'] !== 'content-types-v1'
      )
        return 'type filters require supporting view';
      if (
        filter['untyped'] === true &&
        ('contentType' in filter || 'contentTypeVersion' in filter)
      )
        return 'untyped conflicts with type filters';
      for (const [facet, selection] of Object.entries(filter)) {
        if (
          ![
            'kind',
            'tags',
            'autoTag',
            'tagGroup',
            'author',
            'documentAuthor',
            'when',
            'subtype',
            'search',
            'contentType',
            'contentTypeVersion',
            'untyped',
          ].includes(facet)
        )
          return 'unknown information facet: ' + facet;
        if (facet === 'untyped') {
          if (typeof selection !== 'boolean') return 'untyped must be boolean';
        } else if (facet === 'tags' || facet === 'autoTag') {
          if (
            !isList(selection) ||
            selection.length > 32 ||
            selection.some(
              (tag) =>
                typeof tag !== 'string' ||
                !tag.trim() ||
                tag.length > 64 ||
                hasControlCharacters(tag),
            )
          )
            return 'tags must contain at most 32 bounded printable strings';
        } else {
          if (
            typeof selection !== 'string' ||
            !selection.trim() ||
            selection.length > 512
          )
            return facet + ' must be bounded nonblank text';
          if (facet === 'kind' && !['source', 'report'].includes(selection))
            return 'kind must be source or report';
          if (facet === 'subtype' && !/^[a-z][a-z0-9_]*$/.test(selection))
            return 'invalid subtype facet';
          if (
            facet === 'when' &&
            !/^[0-9]{4}(-(?:0[1-9]|1[0-2]))?$/.test(selection)
          )
            return 'when must be a UTC year or year-month';
        }
      }
    } else if (key === 'groups') {
      if (value !== null) {
        if (
          typeof value !== 'object' ||
          isList(value) ||
          Object.keys(value).length > 16
        )
          return 'groups must be an object of at most 16 categories or null';
        for (const [name, members] of Object.entries(value))
          if (
            !name.trim() ||
            name.length > 64 ||
            hasControlCharacters(name) ||
            !isList(members) ||
            !members.length ||
            members.length > 32 ||
            members.some(
              (tag) =>
                typeof tag !== 'string' ||
                !tag.trim() ||
                tag.length > 64 ||
                hasControlCharacters(tag),
            )
          )
            return 'groups must contain bounded names and existing tag arrays';
      }
    } else if (key === 'tags') {
      if (
        !isList(value) ||
        value.length > 32 ||
        value.some(
          (tag) =>
            typeof tag !== 'string' ||
            !tag.trim() ||
            tag.length > 64 ||
            hasControlCharacters(tag),
        )
      )
        return 'tags must contain at most 32 bounded printable strings';
    } else if (key === 'corpus') {
      if (value !== 'documents' && value !== 'code')
        return 'corpus must be documents or code';
    } else if (key === 'scope') {
      if (!value || typeof value !== 'object' || isList(value))
        return 'scope must be a personal, project or global_read selection';
      const scope = value as Record<string, unknown>;
      if (
        scope['kind'] === 'account' &&
        (![
          'upload',
          'list',
          'search',
          'rank',
          'status',
          'await',
          'read',
          'tags',
          'tags.groups',
          'exclude',
          'unexclude',
          'withdraw',
          'restore',
          'delete',
          'retry',
          'rebuild',
          'allowance',
          'share',
          'unshare',
        ].includes(type.replace('information.', '')) ||
          'typing' in body ||
          'expectedRevision' in body ||
          (body['corpus'] !== undefined && body['corpus'] !== 'documents'))
      )
        return 'this operation does not support account knowledge targets';
      if (
        !['personal', 'project', 'global_read', 'account'].includes(
          String(scope['kind']),
        ) ||
        (scope['kind'] === 'project' &&
          (typeof scope['project'] !== 'string' || !scope['project'].trim()))
      )
        return 'select a valid information scope';
      if (
        Object.keys(scope).some(
          (key) =>
            !['kind', 'project', 'includeGlobal', 'account'].includes(key),
        ) ||
        ('includeGlobal' in scope &&
          typeof scope['includeGlobal'] !== 'boolean') ||
        (scope['kind'] === 'global_read' && 'includeGlobal' in scope) ||
        (scope['kind'] !== 'project' && 'project' in scope) ||
        (scope['kind'] !== 'account' && 'account' in scope) ||
        (scope['kind'] === 'account' &&
          (scope['account'] !== '00000000-0000-0000-0000-000000000004' ||
            'includeGlobal' in scope))
      )
        return 'invalid information scope fields';
    } else if (
      [
        'start',
        'end',
        'offset',
        'limit',
        'after',
        'maxModelCalls',
        'waitMs',
        'expectedControlRevision',
      ].includes(key)
    ) {
      const minimum = [
        'limit',
        'maxModelCalls',
        'end',
        'expectedControlRevision',
      ].includes(key)
        ? 1
        : 0;
      if (
        typeof value !== 'number' ||
        !Number.isSafeInteger(value) ||
        value < minimum ||
        value > 2147483647
      )
        return `${key} must be an integer between ${minimum} and 2147483647`;
    } else if (
      ['inputs', 'objectives', 'scopeChanges'].includes(key) ||
      (key === 'evidence' && type === 'information.record.report')
    ) {
      if (
        !isList(value) ||
        value.some((item) => typeof item !== 'string' || !item.trim())
      )
        return `${key} must be an array of nonblank strings`;
    } else if (key === 'sources') {
      if (
        !isList(value) ||
        value.length < 1 ||
        value.length > 100 ||
        value.some(
          (item) =>
            !item ||
            typeof item !== 'object' ||
            isList(item) ||
            Number('revision' in item) + Number('acquisition' in item) !== 1 ||
            Object.values(item).some(
              (id) => typeof id !== 'string' || !id.trim(),
            ) ||
            Object.keys(item).some(
              (key) => !['revision', 'acquisition'].includes(key),
            ),
        )
      )
        return 'sources must contain 1..100 revision or acquisition references';
    } else if (['findings', 'reviews'].includes(key)) {
      if (
        !isList(value) ||
        value.some((item) => !item || typeof item !== 'object' || isList(item))
      )
        return `${key} must be an array of objects`;
    } else if (typeof value !== 'string' || !value.trim())
      return `${key} must be nonblank text`;
  }
  if ('waitMs' in body && Number(body['waitMs']) > 30000)
    return 'waitMs must be at most 30000';
  if (['information.status', 'information.retry'].includes(type)) {
    if (Number('revision' in body) + Number('acquisition' in body) !== 1)
      return `${type} needs exactly one of revision or acquisition`;
    if (
      type === 'information.retry' &&
      'revision' in body &&
      !('requestId' in body)
    )
      return 'revision retry needs a stable requestId';
  }
  if (
    type === 'information.evidence.record' &&
    Number(body['end']) <= Number(body['start'])
  )
    return 'evidence end must follow start';
  return undefined;
}

/** Versions are checked before a displayed snapshot can become a mutation precondition. */
export function validateInformationTagVersions(
  type: string,
  value: unknown,
): void {
  if (!type.startsWith('information.')) return;
  const visit = (row: unknown, depth: number): void => {
    if (depth > 6 || row === null || typeof row !== 'object') return;
    if (isList(row)) {
      for (const item of row) visit(item, depth + 1);
      return;
    }
    const fields = row as Record<string, unknown>;
    if (
      'tagsVersion' in fields &&
      (typeof fields['tagsVersion'] !== 'number' ||
        !Number.isSafeInteger(fields['tagsVersion']) ||
        fields['tagsVersion'] < 0)
    )
      throw new Error('Invalid resource tag version');
    // Follow only public navigation containers, never arbitrary user metadata or source content.
    for (const key of ['documents', 'connections', 'selection', 'document'])
      if (key in fields) visit(fields[key], depth + 1);
  };
  visit(value, 0);
}
