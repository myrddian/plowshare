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
    if (key === 'filter') {
      if (!value || typeof value !== 'object' || isList(value))
        return 'filter must be an object';
      const filter = value as Record<string, unknown>;
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
          ].includes(facet)
        )
          return 'unknown information facet: ' + facet;
        if (facet === 'tags' || facet === 'autoTag') {
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
        return 'scope must be a personal, project or shared selection';
      const scope = value as Record<string, unknown>;
      if (
        !['personal', 'project', 'shared'].includes(String(scope['kind'])) ||
        (scope['kind'] === 'project' &&
          (typeof scope['project'] !== 'string' || !scope['project'].trim()))
      )
        return 'select a valid information scope';
      if (
        Object.keys(scope).some(
          (key) => !['kind', 'project', 'includeShared'].includes(key),
        ) ||
        ('includeShared' in scope &&
          typeof scope['includeShared'] !== 'boolean') ||
        (scope['kind'] !== 'project' && 'project' in scope)
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
      ].includes(key)
    ) {
      const minimum = ['limit', 'maxModelCalls', 'end'].includes(key) ? 1 : 0;
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
