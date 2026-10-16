import {
  decodeSchemaDefinition,
  validateSchemaDefinition,
  decodeSchemaInstance,
} from './schema-instances.ts';
import { sha256 } from '../binding/sha256.ts';
import { isList, isObject } from '../binding/values.ts';
import { utf8Length } from '../binding/files.ts';
const uuid = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/;
const digest = /^[0-9a-f]{64}$/;
const base64 =
  /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/][AQgw]==|[A-Za-z0-9+/]{2}[AEIMQUYcgkosw048]=)?$/;
/** Canonical bounded base64 conversion without Node or DOM dependencies. */
export function portableBytes(encoded: string): Uint8Array {
  if (!encoded || encoded.length > 2796204 || !base64.test(encoded)) fail();
  const alphabet =
    'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  const size =
    (encoded.length / 4) * 3 -
    (encoded.endsWith('==') ? 2 : encoded.endsWith('=') ? 1 : 0);
  const result = new Uint8Array(size);
  let offset = 0;
  for (let at = 0; at < encoded.length; at += 4) {
    const a = alphabet.indexOf(encoded.charAt(at)),
      b = alphabet.indexOf(encoded.charAt(at + 1)),
      c =
        encoded.charAt(at + 2) === '='
          ? 0
          : alphabet.indexOf(encoded.charAt(at + 2)),
      d =
        encoded.charAt(at + 3) === '='
          ? 0
          : alphabet.indexOf(encoded.charAt(at + 3));
    if (offset < size) result[offset++] = (a << 2) | (b >> 4);
    if (offset < size) result[offset++] = (b << 4) | (c >> 2);
    if (offset < size) result[offset++] = (c << 6) | d;
  }
  return result;
}
function fail(): never {
  throw new Error(
    'Invalid portable data: check paths, identities, hashes and dependency mappings',
  );
}
function object(value: unknown): Record<string, unknown> {
  return isObject(value) ? value : fail();
}
function text(value: unknown, max: number, nonblank = true): string {
  return typeof value === 'string' &&
    value.length <= max &&
    !value.includes('\0') &&
    (!nonblank || !!value.trim())
    ? value
    : fail();
}
function id(value: unknown): string {
  const s = text(value, 36);
  return uuid.test(s) ? s : fail();
}
function hash(value: unknown): void {
  if (!digest.test(text(value, 64))) fail();
}
function list(value: unknown, max: number, min = 0): readonly unknown[] {
  return isList(value) && value.length >= min && value.length <= max
    ? value
    : fail();
}
function ids(value: unknown, max = 16): readonly string[] {
  const values = list(value, max).map(id);
  if (new Set(values).size !== values.length) fail();
  return values;
}
function integer(value: unknown, min: number): number {
  return typeof value === 'number' &&
    Number.isSafeInteger(value) &&
    value >= min &&
    value <= 2147483647
    ? value
    : fail();
}
export function portablePath(value: unknown): string {
  const path = text(value, 240);
  if (
    !/^[A-Za-z0-9][A-Za-z0-9._/-]*$/.test(path) ||
    path
      .split('/')
      .some(
        (part) =>
          !part ||
          part === '.' ||
          part === '..' ||
          ['.git', '.hg', '.svn'].includes(part.toLowerCase()) ||
          part.endsWith('.') ||
          /^(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\..*)?$/i.test(part),
      )
  )
    fail();
  return path;
}
function documentNames(value: unknown, depth = 0): void {
  if (depth > 64) fail();
  if (isList(value)) {
    for (const item of value) documentNames(item, depth + 1);
    return;
  }
  if (!isObject(value)) return;
  for (const [name, item] of Object.entries(value)) {
    if (['__proto__', 'prototype', 'constructor'].includes(name)) fail();
    documentNames(item, depth + 1);
  }
}
function binding(value: unknown): void {
  const b = object(value),
    c = object(b['contract']);
  id(c['type']);
  id(c['version']);
  integer(c['ordinal'], 1);
  if (!/^[a-z](?:[a-z0-9-]{0,62}[a-z0-9])?$/.test(text(c['slug'], 64))) fail();
  text(c['label'], 120);
  text(c['description'], 4096, false);
  hash(c['definitionHash']);
  hash(b['metadataHash']);
  if (c['profile'] !== 'plowshare-document-metadata/1') fail();
  documentNames(c['schema']);
  documentNames(c['presentation']);
  documentNames(b['metadata']);
  if (
    utf8Length(JSON.stringify(c['schema'])) > 65536 ||
    utf8Length(JSON.stringify(c['presentation'])) > 8192 ||
    utf8Length(JSON.stringify(b['metadata'])) > 16384
  )
    fail();
  validateSchemaDefinition(decodeSchemaDefinition(c['schema']));
  object(decodeSchemaInstance(c['presentation']));
  object(decodeSchemaInstance(b['metadata']));
}
function descriptor(value: unknown): Record<string, unknown> {
  const d = object(value);
  id(d['resource']);
  id(d['revision']);
  integer(d['ordinal'], 1);
  text(d['sourceName'], 1024);
  text(d['title'], 32768, false);
  portablePath(d['path']);
  hash(d['contentHash']);
  if (
    !/^[A-Za-z0-9!#$&^_.+-]+\/[A-Za-z0-9!#$&^_.+-]+(?:;[ -~]{1,128})?$/.test(
      text(d['mediaType'], 256),
    )
  )
    fail();
  list(d['tags'], 32).forEach((t) => text(t, 64));
  if ('contentType' in d) binding(d['contentType']);
  const p = object(d['provenance']);
  hash(p['sourceHash']);
  if ('producer' in p) text(p['producer'], 1024);
  if ('producerDefinitionHash' in p) {
    hash(p['producerDefinitionHash']);
    if (!('producer' in p)) fail();
  }
  if ('importedFrom' in p) {
    const origin = object(p['importedFrom']);
    id(origin['bundle']);
    id(origin['resource']);
    id(origin['revision']);
    hash(origin['manifestHash']);
    hash(origin['sourceHash']);
    text(origin['owner'], 1024);
  }

  text(p['owner'], 1024);
  if (
    !['source', 'report'].includes(text(p['kind'], 6)) ||
    !Number.isFinite(Date.parse(text(p['createdAt'], 64)))
  )
    fail();
  ids(p['dependencies']);
  for (const raw of list(p['evidence'], 64)) {
    const e = object(raw);
    id(e['id']);
    id(e['revision']);
    const start = integer(e['start'], 0),
      end = integer(e['end'], 1),
      quote = text(e['quote'], 65536);
    if (quote.length !== end - start) fail();
    if ('locator' in e) text(e['locator'], 4096, false);
  }
  return d;
}
function bundle(value: unknown): void {
  const b = object(value);
  id(b['id']);
  if (
    b['format'] !== 'plowshare-information-bundle/1' ||
    b['relationshipCoverage'] !== 'included-endpoints-only'
  )
    fail();
  const docs = list(b['documents'], 16, 1),
    revisions = new Map<string, Record<string, unknown>>(),
    paths = new Set<string>(),
    ordinals = new Set<string>(),
    resources = new Set<string>();
  let total = 0;
  for (const raw of docs) {
    const doc = object(raw),
      d = descriptor(doc['descriptor']),
      revision = id(d['revision']),
      resource = id(d['resource']),
      path = portablePath(d['path']).toLowerCase(),
      ordinal = `${resource}/${integer(d['ordinal'], 1)}`;
    if (revisions.has(revision) || paths.has(path) || ordinals.has(ordinal))
      fail();
    revisions.set(revision, d);
    paths.add(path);
    ordinals.add(ordinal);
    resources.add(resource);
    const encoded = text(doc['bytesBase64'], 2796204);
    if (!base64.test(encoded) || !encoded) fail();
    if (sha256(portableBytes(encoded)) !== d['contentHash']) fail();
    total +=
      (encoded.length / 4) * 3 -
      (encoded.endsWith('==') ? 2 : encoded.endsWith('=') ? 1 : 0);
  }
  if (
    total > 2097152 ||
    [...paths].some((path) =>
      [...paths].some((other) => other.startsWith(path + '/')),
    )
  )
    fail();
  for (const d of revisions.values()) {
    const p = object(d['provenance']);
    if (
      ids(p['dependencies']).some(
        (dep) => !revisions.has(dep) || dep === d['revision'],
      )
    )
      fail();
    for (const e of list(p['evidence'], 64))
      if (!revisions.has(id(object(e)['revision']))) fail();
  }
  const edges = new Set<string>();
  for (const raw of list(b['relationships'], 64)) {
    const e = object(raw),
      source = id(e['source']),
      target = id(e['target']),
      pin = e['pinnedRevision'],
      relation = text(e['relation'], 64);
    if (
      !resources.has(source) ||
      !resources.has(target) ||
      !/^[a-z][a-z0-9_-]{0,63}$/.test(relation)
    )
      fail();
    if (pin !== undefined && revisions.get(id(pin))?.['resource'] !== target)
      fail();
    const key = JSON.stringify([source, target, pin, relation]);
    if (edges.has(key)) fail();
    edges.add(key);
  }
}
/** Shared shape decoding runs first; this boundary adds bounded identity/graph invariants. */
export function interchangeProblem(
  type: string,
  value: unknown,
  reply = false,
): string | undefined {
  if (!type.startsWith('information.interchange.')) return undefined;
  try {
    if (utf8Length(JSON.stringify(value)) > 8388608) fail();
    const row = object(value);
    if (reply) {
      if (type.endsWith('.export')) bundle(row);
      if (type.endsWith('.origin')) {
        id(row['bundle']);
        hash(row['manifestHash']);
        descriptor(row['foreignDocument']);
      }
      if (type.endsWith('.readable')) {
        portablePath(row['path']);
        hash(row['textHash']);
        descriptor(row['sidecar']);
        text(row['markdown'], 2097152, false);
      }
      if (type.endsWith('.import')) {
        id(row['bundle']);
        const foreign = new Set<string>(),
          local = new Set<string>();
        for (const raw of list(row['mappings'], 16, 1)) {
          const m = object(raw);
          id(m['foreignResource']);
          id(m['resource']);
          const a = id(m['foreignRevision']),
            b = id(m['revision']);
          if (foreign.has(a) || local.has(b)) fail();
          foreign.add(a);
          local.add(b);
        }
      }
    } else {
      const scope = object(row['scope']);
      if (!['personal', 'project', 'shared'].includes(text(scope['kind'], 8)))
        fail();
      if (scope['kind'] === 'project') text(scope['project'], 1024);
      if (row['corpus'] !== undefined && row['corpus'] !== 'documents') fail();
      if (type.endsWith('.export') && ids(row['revisions']).length === 0)
        fail();
      if (type.endsWith('.readable') || type.endsWith('.origin'))
        id(row['revision']);
      if (type.endsWith('.import')) {
        if (scope['kind'] === 'shared') fail();
        id(row['requestId']);
        const plan = object(row['plan']);
        bundle(plan['bundle']);
        const documents = list(object(plan['bundle'])['documents'], 16, 1);
        const all = new Set(
          documents.map((d) => id(object(object(d)['descriptor'])['revision'])),
        );
        const reused = new Set<string>(),
          local = new Set<string>();
        for (const raw of list(plan['reuse'], 16)) {
          const r = object(raw),
            foreign = id(r['foreignRevision']),
            revision = id(r['localRevision']);
          if (!all.has(foreign) || reused.has(foreign) || local.has(revision))
            fail();
          reused.add(foreign);
          local.add(revision);
        }
        const resources = new Set(
          documents
            .filter(
              (d) =>
                !reused.has(id(object(object(d)['descriptor'])['revision'])),
            )
            .map((d) => id(object(object(d)['descriptor'])['resource'])),
        );
        const selected = new Set<string>(),
          names = new Set<string>();
        for (const raw of list(plan['destinations'], 16, 1)) {
          const d = object(raw),
            resource = id(d['resource']),
            name = text(d['name'], 1024);
          if (selected.has(resource) || names.has(name)) fail();
          selected.add(resource);
          names.add(name);
          if (d['mode'] === 'revise') id(d['expectedRevision']);
          else if (d['mode'] !== 'create' || 'expectedRevision' in d) fail();
        }
        if (
          !resources.size ||
          selected.size !== resources.size ||
          [...selected].some((r) => !resources.has(r)) ||
          typeof plan['restoreRelationships'] !== 'boolean'
        )
          fail();
        if (
          plan['restoreRelationships'] === true &&
          list(object(plan['bundle'])['relationships'], 64).some(
            (e) => !resources.has(id(object(e)['source'])),
          )
        )
          fail();
        if (plan['restoreRelationships'] === true)
          for (const raw of list(object(plan['bundle'])['relationships'], 64)) {
            const link = object(raw);
            if (link['pinnedRevision'] === undefined) continue;
            const pinned = documents
              .map((d) => object(object(d)['descriptor']))
              .find((d) => d['revision'] === link['pinnedRevision']);
            if (
              !pinned ||
              pinned['contentHash'] !==
                object(pinned['provenance'])['sourceHash']
            )
              fail();
          }
        const versions = new Set<string>();
        for (const raw of list(plan['types'], 16)) {
          const t = object(raw),
            v = id(t['foreignVersion']);
          id(t['localVersion']);
          if (versions.has(v)) fail();
          versions.add(v);
        }
      }
    }
  } catch {
    return 'Invalid portable data: check paths, identities, hashes and dependency mappings';
  }
  return undefined;
}
