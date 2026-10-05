import type { Payloads } from './direct.ts';
import type { OperationTransport } from './response.ts';
import type { InformationPayloads } from './information-payloads.ts';
import type { Replies } from './replies.ts';
import { decodePayload, decodeReply } from './schema.ts';
import { isList } from '../binding/values.ts';
export type InformationCorpus = 'documents' | 'code';
export type InformationScope =
  | { kind: 'personal' | 'shared'; includeShared?: boolean }
  | { kind: 'project'; project: string; includeShared?: boolean };
export const INFORMATION_OPERATIONS = [
  'upload',
  'acquire',
  'refresh',
  'revise',
  'replace',
  'list',
  'facets',
  'tags',
  'tags.groups',
  'inventory',
  'acquisitions',
  'status',
  'await',
  'read',
  'outline',
  'symbols',
  'search',
  'rank',
  'ask',
  'evidence.record',
  'evidence.read',
  'record.report',
  'finalise',
  'link',
  'unlink',
  'share',
  'unshare',
  'withdraw',
  'exclude',
  'unexclude',
  'restore',
  'delete',
  'retry',
  'rebuild',
  'allowance',
  'events',
  'migration.list',
  'migration.adopt',
  'migration.inspect',
  'migration.release',
] as const;
export type InformationOperation = (typeof INFORMATION_OPERATIONS)[number];
export type InformationInput<K extends InformationOperation> = Omit<
  InformationPayloads[`information.${K}`],
  'scope' | 'corpus'
>;
export type InformationCall = {
  [K in InformationOperation]: {
    readonly operation: K;
    readonly payload: InformationInput<K>;
  };
}[InformationOperation];
/** Validation at dynamic command/IPC boundaries preserves operation and DTO pairing. */
export function decodeInformationCall(
  operation: InformationOperation,
  value: unknown,
  scope: InformationScope,
  corpus: InformationCorpus = 'documents',
): InformationCall {
  if (value === null || typeof value !== 'object' || isList(value))
    throw new Error('Information payload must be an object.');
  if ('scope' in value || 'corpus' in value)
    throw new Error(
      'Information scope and corpus belong to the selected client.',
    );
  const {
    scope: _scope,
    corpus: _corpus,
    ...payload
  } = decodePayload(`information.${operation}`, {
    ...value,
    scope,
    ...(corpus === 'code' ? { corpus } : {}),
  });
  // The generated decoder verifies the complete operation-specific DTO.
  return { operation, payload } as InformationCall;
}
export type InformationTransport = OperationTransport;

/** All operations use the existing socket. Callers keep mutation request IDs across uncertain delivery. */
export class InformationClient {
  private readonly transport: InformationTransport;
  readonly scope: InformationScope;
  readonly corpus: InformationCorpus;
  constructor(
    transport: InformationTransport,
    scope: InformationScope,
    corpus: InformationCorpus = 'documents',
  ) {
    this.transport = transport;
    this.scope = scope;
    this.corpus = corpus;
  }
  async call<K extends InformationOperation>(
    operation: K,
    ...args: Record<string, never> extends InformationInput<K>
      ? [payload?: InformationInput<K>]
      : [payload: InformationInput<K>]
  ): Promise<Replies[`information.${K}`]> {
    const payload = decodePayload(`information.${operation}`, {
      ...(args[0] ?? {}),
      scope: this.scope,
      ...(this.corpus === 'code' ? { corpus: this.corpus } : {}),
    });
    return this.exchange(operation, payload);
  }
  async invoke(
    call: InformationCall,
  ): Promise<Replies[`information.${InformationOperation}`]> {
    const payload = decodePayload(`information.${call.operation}`, {
      ...call.payload,
      scope: this.scope,
      ...(this.corpus === 'code' ? { corpus: this.corpus } : {}),
    });
    return this.exchange(call.operation, payload);
  }
  private async exchange<K extends InformationOperation>(
    operation: K,
    payload: Payloads[`information.${K}`],
  ): Promise<Replies[`information.${K}`]> {
    const outcome = await this.transport.ask(
      `information.${operation}`,
      payload,
    );
    if (outcome.code !== 'OK' && outcome.code !== 'ACCEPTED')
      throw new Error(
        outcome.said ?? `Information request answered ${outcome.code}.`,
      );
    return decodeReply(`information.${operation}`, outcome.payload);
  }
}
export interface InformationChanged {
  kind: 'information.changed';
  sequence: number;
  revision: string;
  generation: number;
}
export function informationChanged(
  value: unknown,
): InformationChanged | undefined {
  if (!value || typeof value !== 'object') return undefined;
  const row = value as Record<string, unknown>;
  if (
    row.kind !== 'information.changed' ||
    typeof row.revision !== 'string' ||
    typeof row.sequence !== 'number' ||
    !Number.isSafeInteger(row.sequence) ||
    row.sequence < 1 ||
    typeof row.generation !== 'number' ||
    !Number.isSafeInteger(row.generation) ||
    row.generation < 1
  )
    return undefined;
  return {
    kind: 'information.changed',
    revision: row.revision,
    sequence: row.sequence,
    generation: row.generation,
  };
}

export type InformationCommand = InformationCall & {
  readonly scope: InformationScope;
};
export const INFORMATION_HELP =
  'information <operation> [--scope personal|shared|project] [--project NAME] {JSON payload}. Mutations need a stable UUID requestId; retain it across disconnects. Operations: ' +
  INFORMATION_OPERATIONS.join(', ');
/** JSON remains intact; shell/slash parsing must not strip quotes inside source text. */
export function informationCommand(
  text: string,
  project?: string,
): InformationCommand {
  const at = text.indexOf('{');
  const prefix = (
    (at < 0 ? text : text.slice(0, at))
      .trim()
      .match(/"(?:\\.|[^"\\])*"|'[^']*'|[^\s]+/g) ?? []
  ).map((word) =>
    word.startsWith('"')
      ? (JSON.parse(word) as string)
      : word.startsWith("'")
        ? word.slice(1, -1)
        : word,
  );
  if (prefix.shift() !== 'information') throw new Error(INFORMATION_HELP);
  const operation = prefix[0]?.startsWith('--')
    ? 'list'
    : (prefix.shift() ?? 'list');
  if (!(INFORMATION_OPERATIONS as readonly string[]).includes(operation))
    throw new Error(INFORMATION_HELP);
  let kind: string = project ? 'project' : 'personal',
    chosen = project;
  while (prefix.length) {
    const flag = prefix.shift(),
      value = prefix.shift();
    if (!value || value.startsWith('--'))
      throw new Error(`${String(flag)} needs a value.`);
    if (flag === '--scope') kind = value;
    else if (flag === '--project') {
      chosen = value;
      kind = 'project';
    } else throw new Error(`Unknown information option: ${String(flag)}`);
  }
  if (!['personal', 'shared', 'project'].includes(kind))
    throw new Error('Scope must be personal, shared or project.');
  if (kind === 'project' && !chosen?.trim())
    throw new Error('Select a project.');
  const payload: unknown = at < 0 ? {} : JSON.parse(text.slice(at));
  if (!payload || typeof payload !== 'object' || isList(payload))
    throw new Error('Information payload must be a JSON object.');
  const scope: InformationScope =
    kind === 'project' && chosen !== undefined
      ? { kind: 'project', project: chosen }
      : kind === 'shared'
        ? { kind: 'shared' }
        : { kind: 'personal' };
  return {
    ...decodeInformationCall(operation as InformationOperation, payload, scope),
    scope,
  };
}
