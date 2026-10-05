import type { Schema } from './schema.ts';
import type { JsonValue } from './administration.ts';
import { CLI_OPERATIONS } from './catalog.ts';
import { SHAPES } from './commands.ts';
import { MEMORY_OPERATIONS, JOB_SUBMISSIONS } from './direct.ts';
import type { Request } from './direct.ts';
import { OPERATION_SCHEMAS } from './operation-schemas.ts';

/** Explicit mutation classification shared by help and recovery. No operation is replayed. */
const MUTATIONS = new Set<string>([
  'admin.service.account.create',
  'admin.service.account.update',
  'admin.service.token.create',
  'admin.service.token.rotate',
  'admin.service.token.revoke',
  'admin.pricing.set',
  'admin.account.create',
  'admin.account.update',
  'admin.account.reset',
  'admin.session.revoke',
  'message.instance.open',
  'message.instance.default',
  'message.instance.stop',
  'message.instance.archive',
  'message.cancel',
  ...JOB_SUBMISSIONS,
  'outgoing.send',
  'outgoing.cancel',
  'memory.write',
  'memory.invalidate',
  'memory.reembed',
  'proposal.resolve',
  'proposal.reconsider',
  'conversation.open',
  'conversation.lifecycle',
  'agent.define',
  'job.cancel',
  'job.limits',
  'project.attach',
  'project.create',
  'project.define',
  'project.lend',
  'project.unlend',
  'project.workspace',
  'project.move',
  'project.forget',
  'project.member.add',
  'project.member.remove',
  'approval.answer',
  'approval.revoke',
  'board.topup',
  'board.post',
  'board.open',
  'board.retry',
  'buffer.purge',
  'retention.sweep',
  'provider.deregister',
  'inbox.read',
  'orchestration.start',
  'orchestration.answer',
  'orchestration.cancel',
  'orchestration.resume',
  'schedule.save',
  'schedule.sync',
  'schedule.define',
  'schedule.read',
  'schedule.pause',
  'schedule.forget',
  'trigger.define',
  'trigger.pause',
  'trigger.forget',
  'event.fire',
  'information.upload',
  'information.acquire',
  'information.ask',
  'information.evidence.record',
  'information.record.report',
  ...[
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
    'revise',
    'replace',
    'refresh',
    'rebuild',
    'allowance',
    'migration.adopt',
    'migration.release',
  ].map((v) => 'information.' + v),
]);
export const isMutation = (operation: string): boolean =>
  MUTATIONS.has(operation);
export const EXIT_SEMANTICS = {
  0: 'completed',
  1: 'refused or failed',
  2: 'usage or authentication',
  3: 'accepted, running or cancelling; inspect or wait',
  4: 'incomplete, cancelled or asking',
  5: 'unknown, transport, deadline or protocol; follow recovery actions',
};

export function effectiveScope(asked: Request): unknown {
  const payload = asked.payload as Record<string, unknown>;
  if (asked.type.startsWith('admin.'))
    return {
      kind: 'server-administration',
      ...(payload['handle'] === undefined ? {} : { handle: payload['handle'] }),
    };
  if (asked.type.startsWith('usage.'))
    return {
      kind: 'usage',
      project: payload['project'] ?? null,
      conversation: payload['conversation'] ?? null,
      agent: payload['agent'] ?? null,
      run: payload['run'] ?? null,
      orchestration: payload['orchestration'] ?? null,
      scope: payload['scope'] ?? 'direct',
    };
  if ('scope' in payload) return payload['scope'];
  if (payload['conversation'] != null)
    return { kind: 'conversation', id: payload['conversation'] };
  if (payload['project'] != null)
    return { kind: 'project', project: payload['project'] };
  if (
    asked.type.startsWith('orchestration.') &&
    (payload['id'] ?? payload['root']) != null
  )
    return { kind: 'orchestration', id: payload['id'] ?? payload['root'] };
  if (asked.type.startsWith('outgoing.') && payload['id'] != null)
    return { kind: 'outgoing', id: payload['id'] };
  if (payload['instance'] != null)
    return { kind: 'message-instance', id: payload['instance'] };
  if (payload['message'] != null)
    return { kind: 'message', id: payload['message'] };
  if (payload['job'] != null) return { kind: 'job', id: payload['job'] };
  return { kind: 'global' };
}

const examples: Record<string, unknown> = {
  'admin.service.account.create': { handle: 'ha-integration' },
  'admin.service.account.update': { handle: 'ha-integration', enabled: false },
  'admin.service.token.create': {
    handle: 'ha-integration',
    name: 'production',
    scopes: [{ project: 'automation', role: 'CONTRIBUTOR' }],
    expiresInDays: 30,
  },
  'admin.service.token.rotate': {
    handle: 'ha-integration',
    id: '00000000-0000-0000-0000-000000000001',
    expiresInDays: 30,
  },
  'admin.service.token.revoke': {
    handle: 'ha-integration',
    id: '00000000-0000-0000-0000-000000000001',
  },
  'admin.pricing.set': {
    billingRoute: 'hosted',
    model: 'deployment-name',
    expectedVersion: 'boot:',
    mode: 'TOKEN',
    currency: 'USD',
    rates: { input: '0.40', output: '1.60' },
  },
  'admin.account.create': { handle: 'sam', serverAdmin: false },
  'admin.account.update': { handle: 'sam', enabled: false },
  'admin.audit': { limit: 25 },
  'outgoing.send': {
    requestId: '00000000-0000-0000-0000-000000000001',
    peer: 'research',
    message: { parts: [{ text: 'Research this question' }] },
  },
  'information.acquire': {
    scope: { kind: 'personal' },
    requestId: '00000000-0000-0000-0000-000000000001',
    url: 'https://example.com/source',
  },
  'message.instance.open': {
    project: 'my_project',
    agent: 'reviewer',
    makeDefault: true,
    requestId: '00000000-0000-0000-0000-000000000001',
  },
  'agent.run': { agent: 'my_agent', task: 'Inspect retained sources' },
  'project.attach': {
    name: 'local-integration',
    workspace: '/home/me/integration',
    machine: 'client',
  },
  'project.create': {
    name: 'home-assistant',
    type: 'DISJOINT',
    workspace: '/srv/plowshare-workspaces/home-assistant',
    writePaths: ['generated', 'reports'],
  },
  'project.define': {
    name: 'home-assistant',
    workspace: '/srv/plowshare-workspaces/home-assistant',
  },
  'orchestration.start': {
    agent: 'farnsworth',
    definition: 'deep_research',
    request: 'Compare the evidence and contrary findings',
    requestId: '00000000-0000-0000-0000-000000000001',
  },
};

const commands = {
  ...CLI_OPERATIONS,
  ...Object.fromEntries(
    Object.entries(MEMORY_OPERATIONS).map(([verb, type]) => [
      'memory ' + verb,
      type,
    ]),
  ),
  search: 'conversation.search',
  'job status': 'job.status',
  'job cancel': 'job.cancel',
};
export function discovery(target = '') {
  const matches = (command: string) =>
    !target || command === target || command.startsWith(target + ' ');
  const aliases = {
    'orchestration wait': { command: 'orchestration status', wait: true },
    'job wait': { command: 'job status', wait: true },
    'job poll': { command: 'job status' },
    'job result': { command: 'job status' },
    'job watch': { command: 'job status', watch: true },
  };
  const selected =
    target && Object.hasOwn(aliases, target)
      ? aliases[target as keyof typeof aliases].command
      : target;
  return {
    version: 1,
    $defs: OPERATION_SCHEMAS.$defs,
    exits: EXIT_SEMANTICS,
    options: [
      '--json',
      '--help',
      '--version',
      '--new-conversation',
      '--standalone',
      '--validate',
      '--server',
      '--url',
      '--project',
      '--global',
      '--payload -',
      '--wait',
      '--watch',
      '--root',
      '--sync',
      '--poll-ms',
      '--timeout-ms',
    ],
    optionPlacement: 'before or after commands; -- ends option parsing',
    authentication:
      'Help and validation are offline. Execution needs authenticated WS and existing account/project grants.',
    replay: 'disabled',
    projectModel:
      'A server-side scope of work managed by the Plowshare agent framework: agents, skills, conversations, memory, information and any server workspace files. SDK integrations submit tasks within that scope. --project selects it; project create provisions a MANAGED server workspace or registers a DISJOINT pipeline workspace without client sync. writePaths limits server writes to relative areas; DISJOINT defaults to no writes. Creation requires an administrator. project define registers an existing workspace. --root explicitly serves local files.',
    conversationPolicy: {
      command: 'agent run',
      default:
        'Open a fresh conversation if none is supplied; omitted project selects global. Explicit environment project still applies.',
      options: {
        '--new-conversation':
          'Open a fresh conversation; conflicts with a conversation id.',
        '--standalone':
          'Submit a job without a reusable conversation; supports images.',
      },
      wireField: 'newConversation',
    },
    aliases: Object.fromEntries(
      Object.entries(aliases).filter(([command]) => matches(command)),
    ),
    platformCommands: [
      'setup',
      'login',
      'logout',
      'client root',
      'sync on',
      'sync off',
      'sync status',
      'sync conflicts',
      'sync hidden',
      'sync resolve',
      'conversation follow',
    ].filter(matches),
    commands: Object.entries(commands)
      .filter(
        ([command]) =>
          !selected ||
          command === selected ||
          command.startsWith(selected + ' '),
      )
      .map(([command, operation]) => {
        const shape = SHAPES[operation as keyof typeof SHAPES];
        return {
          command: selected !== target && target ? target : command,
          operation,
          input: commandInput(operation, shape),
          result: OPERATION_SCHEMAS.results[operation],
          ...(shape ? { required: shape[0], optional: shape[1] } : {}),
          mutation: isMutation(operation),
          scope: operation.startsWith('admin.')
            ? 'Server administration; target account from handle; caller from the authenticated socket. Requires a server administrator except admin.status.'
            : operation.startsWith('information.')
              ? 'Explicit scope selection; migration controls have their declared exceptions; account from authenticated socket.'
              : 'Project from payload or --project; project:null selects global. Conversations, jobs and run IDs own their scope. Authorization uses the authenticated account and is checked by the server.',
          pagination: shape
            ? [...shape[0], ...shape[1]].filter((f) =>
                [
                  'limit',
                  'offset',
                  'after',
                  'before',
                  'tail',
                  'page',
                  'pageSize',
                  'max',
                  'cursor',
                  'attempt_cursor',
                ].includes(f),
              )
            : [],
          defaults:
            'Omitted paging and budget fields are server-owned. web.search requires pageSize, max, page.',
          example:
            examples[operation] ?? sample(commandInput(operation, shape)),
        };
      }),
  };
}

function commandInput(
  operation: string,
  shape: readonly [readonly string[], readonly string[]] | undefined,
): Schema | undefined {
  let input = OPERATION_SCHEMAS.inputs[operation];
  while (input?.['$ref'])
    input = OPERATION_SCHEMAS.$defs[String(input['$ref']).split('/').at(-1)!];
  return input && shape
    ? { ...input, required: shape[0], additionalProperties: false }
    : input;
}

function sample(schema: Schema | undefined, depth = 0): JsonValue {
  if (!schema || depth > 6) return null;
  if ('$ref' in schema)
    return sample(
      OPERATION_SCHEMAS.$defs[String(schema['$ref']).split('/').at(-1)!],
      depth + 1,
    );
  if ('const' in schema) return schema.const ?? null;
  if ('anyOf' in schema)
    return sample(
      (schema.anyOf ?? []).find((s) => s['type'] !== 'null') ?? {},
      depth + 1,
    );
  if (schema['type'] === 'object') {
    const props = schema.properties ?? {};
    return Object.fromEntries(
      (schema.required ?? []).map((key) => [
        key,
        sample(props[key], depth + 1),
      ]),
    );
  }
  if (schema['type'] === 'array') return [];
  if (schema['type'] === 'number') return 1;
  if (schema['type'] === 'boolean') return false;
  return schema['type'] === 'string' ? 'value' : null;
}

export function commandHelp(target = ''): string {
  const help = discovery(target);
  const projectNote =
    target === 'project' || target.startsWith('project ')
      ? help.projectModel + '\n\n'
      : '';
  return (
    projectNote +
    help.commands
      .map(
        (row) =>
          `${row.command} ${row.required?.length ? '{' + row.required.join(', ') + '}' : '[JSON]'}`,
      )
      .join('\n')
  );
}
