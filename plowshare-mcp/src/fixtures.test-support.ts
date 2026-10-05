import { decodeReply } from 'plowshare-client-ts/operations/schema';
import type { Replies } from 'plowshare-client-ts/operations/replies';
import { record } from './values.js';
import { displayText } from 'plowshare-client-ts/binding/values';
import type { Operation } from 'plowshare-client-ts/operations/direct';
import type { Data } from './values.js';
function optional(key: string, value: unknown): Data {
  return value == null ? {} : { [key]: value };
}
export function ws(method: string, args: Data): [Operation, Data] {
  switch (method) {
    case 'information':
      return [
        `information.${displayText(args['operation'])}` as Operation,
        args['payload'] as Data,
      ];
    case 'index':
      return ['memory.index', args];
    case 'read':
      return ['memory.read', { memory: args['id'] }];
    case 'recall':
      return [
        'memory.recall',
        {
          project: args['project'],
          question: args['question'],
          ...optional('limit', args['limit']),
        },
      ];
    case 'write':
      return ['memory.write', args];
    case 'navigateMemory':
      return ['memory.navigate', args];
    case 'digestMemory':
      return ['memory.digest', args];
    case 'run':
      return ['agent.run', args];
    case 'curate':
      return [
        'agent.curate',
        {
          project: args['project'],
          ...optional('maxModelCalls', args['maxModelCalls']),
        },
      ];
    case 'job':
    case 'cancelJob':
      return [
        method === 'job' ? 'job.status' : 'job.cancel',
        { job: args['id'] },
      ];
    case 'proposals':
      return ['proposal.list', args];
    case 'resolve':
      return [
        'proposal.resolve',
        {
          proposal: args['id'],
          accept: args['accept'],
          by: args['by'],
          ...optional('reason', args['reason']),
        },
      ];
    case 'defineProject':
      return ['project.define', args];
    case 'setProjectWorkspace':
      return [
        'project.workspace',
        { project: args['name'], workspace: args['workspace'] },
      ];
    case 'lendProject':
    case 'unlendProject':
      return [
        method === 'lendProject' ? 'project.lend' : 'project.unlend',
        { project: args['name'], roots: args['roots'] },
      ];
    case 'moveProject':
      return ['project.move', { project: args['name'], to: args['to'] }];
    case 'forgetProject':
      return ['project.forget', { project: args['name'] }];
    case 'conversations':
      return ['conversation.list', args];
    case 'searchEntries':
      return [
        'conversation.search',
        {
          project: args['project'],
          q: args['query'],
          offset: args['offset'],
          limit: args['limit'],
        },
      ];
    case 'chat':
    case 'trajectory':
      return [
        method === 'chat' ? 'conversation.chat' : 'conversation.trajectory',
        {
          conversation: args['conversationId'],
          offset: args['offset'],
          limit: args['limit'],
        },
      ];
    case 'context':
      return [
        'conversation.context',
        {
          conversation: args['conversationId'],
          ...optional('agent', args['agent']),
        },
      ];
    case 'searchDocuments':
      return [
        'document.search',
        { query: args['query'], ...optional('limit', args['limit']) },
      ];
    case 'citations':
      return [
        'document.citations',
        {
          ...optional('conversation', args['conversationId']),
          ...optional('document', args['documentId']),
          ...optional('limit', args['limit']),
        },
      ];
    case 'askDocument':
      return [
        'document.ask',
        { document: args['documentId'], question: args['question'] },
      ];
    case 'retrieve':
      return [
        'document.retrieve',
        {
          query: args['query'],
          ...optional('document', args['documentId']),
          limit: args['limit'],
        },
      ];
    case 'listDocuments':
      return [
        'document.list',
        {
          ...optional('q', args['naming']),
          ...optional('limit', args['limit']),
          ...optional('offset', args['offset']),
        },
      ];
    case 'rankDocuments':
      return [
        'document.rank',
        { query: args['query'], ...optional('limit', args['limit']) },
      ];
    case 'describeDocument':
      return ['document.detail', { document: args['documentId'] }];
    case 'search':
      return ['web.search', args];
    case 'fetch':
      return ['web.fetch', args];
    default:
      throw new Error('unmapped Java method: ' + method);
  }
}

/** Frozen Java formatter cases predate newer WS metadata. Supply actual DTOs
 * at the transport fixture while retaining the reviewed formatter expectations. */
export function currentReply<K extends Operation>(
  type: K,
  value: unknown,
): Replies[K] {
  let payload = value;
  if (
    [
      'project.define',
      'project.workspace',
      'project.lend',
      'project.unlend',
    ].includes(type)
  )
    payload = { ...record(value), machine: null, members: [] };
  if (type === 'conversation.chat' || type === 'conversation.trajectory')
    payload = { ...record(value), through: 2, oldest: null, more: null };
  if (type === 'conversation.context') {
    const row = record(value),
      prefix = record(row['prefix']);
    const count = {
      tokens: 2,
      basis: 'ESTIMATED',
      how: 'synthetic fixture estimate',
    };
    payload = {
      ...row,
      measuredTurns: [
        { turn: 1, promptTokens: 1, grewBy: null, since: null },
        { turn: 2, promptTokens: 2, grewBy: 1, since: 1 },
      ],
      systemPromptTokens: count,
      toolTokens: count,
      messageTokens: count,
      prefix: {
        ...prefix,
        systemPromptTokens: count,
        toolTokens: count,
        contextLength: null,
      },
    };
  }
  return decodeReply(type, payload);
}
