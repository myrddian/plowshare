import { isList } from '../binding/values.ts';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';
import type { Outcome } from '../binding/envelope.ts';
import {
  ADMINISTRATIVE_OPERATIONS,
  administrativeReply,
  type AdministrativeOperation,
} from './administrative-replies.ts';
import { dispatch, request, type Request } from './direct.ts';

const fixtures = JSON.parse(
  readFileSync(
    new URL(
      '../../../../test-support/contracts/ws-administrative-fixtures.json',
      import.meta.url,
    ),
    'utf8',
  ),
) as Record<AdministrativeOperation, Outcome>;
const asked = (type: AdministrativeOperation): Request =>
  ({
    type,
    payload:
      type === 'orchestration.status' ||
      type === 'orchestration.answer' ||
      type === 'orchestration.cancel' ||
      type === 'orchestration.resume'
        ? { id: 'o' }
        : type === 'approval.answer' || type === 'approval.revoke'
          ? { id: 'a', decision: 'once' }
          : {},
  }) as Request;
const object = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !isList(value);

// Remove one contract field at a time, including nested rows. Model-authored JSON stays open.
function missingFields(value: unknown): unknown[] {
  if (isList(value))
    return value.flatMap((row, index) =>
      missingFields(row).map((changed) =>
        value.map((each, n) => (n === index ? changed : each)),
      ),
    );
  if (!object(value)) return [];
  return Object.keys(value)
    .filter((key) => key !== 'future')
    .flatMap((key) => {
      const without = { ...value };
      delete without[key];
      return [
        without,
        ...(key === 'structure'
          ? []
          : missingFields(value[key]).map((changed) => ({
              ...value,
              [key]: changed,
            }))),
      ];
    });
}

describe('complete administrative WS replies', () => {
  it('has a nonempty fixture for every supported operation', () => {
    expect(Object.keys(fixtures).sort()).toEqual(
      [...ADMINISTRATIVE_OPERATIONS].sort(),
    );
  });
  it.each(ADMINISTRATIVE_OPERATIONS)(
    '%s retains the entire server outcome after one WS request',
    async (type) => {
      const outcome = fixtures[type],
        calls: unknown[] = [];
      expect(administrativeReply(type, outcome)).toBe(outcome);
      const result = await dispatch(
        {
          async ask(type, payload) {
            calls.push({ type, payload });
            return outcome;
          },
        },
        asked(type),
      );
      expect(result.outcome).toBe(outcome);
      expect(result.kind).toBe(
        type === 'approval.answer' ? 'accepted' : 'completed',
      );
      expect(calls).toEqual([asked(type)]);
    },
  );
  it.each(ADMINISTRATIVE_OPERATIONS)(
    '%s refuses incomplete payloads and incorrect success codes',
    async (type) => {
      const original = fixtures[type];
      const invalid: Outcome[] = [
        { ...original, code: original.code === 'OK' ? 'NO_CONTENT' : 'OK' },
        { ...original, code: 'CREATED' },
        { ...original, code: 'ACCEPTED' },
        { ...original, payload: { unread: 0, applied: 0, future: true } },
        ...missingFields(original.payload).map((payload) => ({
          ...original,
          payload,
        })),
      ];
      if (isList(original.payload))
        invalid.push({ ...original, payload: [...original.payload, {}] });
      for (const outcome of invalid) {
        expect(
          administrativeReply(type, outcome),
          JSON.stringify(outcome),
        ).toBeUndefined();
        let calls = 0;
        const result = await dispatch(
          {
            async ask() {
              calls++;
              return outcome;
            },
          },
          asked(type),
        );
        expect(result.kind, JSON.stringify(outcome)).toBe('invalid-response');
        expect(result.outcome).toBe(outcome);
        expect(calls).toBe(1);
      }
      const refusal: Outcome = {
        code: 'CONFLICT',
        said: 'decision already changed; nothing replayed',
      };
      expect(
        await dispatch(
          {
            async ask() {
              return refusal;
            },
          },
          asked(type),
        ),
      ).toEqual({ kind: 'refused', outcome: refusal });
    },
  );
  it('keeps acknowledgements, empty lists and partial inbox receipt counts distinct', () => {
    for (const type of [
      'schedule.pause',
      'trigger.forget',
      'provider.deregister',
    ] as const) {
      expect(
        administrativeReply(type, { code: 'NO_CONTENT', payload: null }),
      ).toBeDefined();
      expect(
        administrativeReply(type, { code: 'NO_CONTENT', payload: [] }),
      ).toBeUndefined();
    }
    for (const type of [
      'provider.list',
      'todos.read',
      'schedule.list',
      'trigger.list',
      'event.fire',
      'firing.list',
    ] as const) {
      expect(
        administrativeReply(type, { code: 'OK', payload: [] }),
      ).toBeDefined();
      expect(administrativeReply(type, { code: 'OK' })).toBeUndefined();
    }
    expect(
      administrativeReply('inbox.list', {
        code: 'OK',
        payload: { items: [], unread: 8 },
      }),
    ).toBeDefined();
    expect(
      administrativeReply('inbox.read', {
        code: 'OK',
        payload: { marked: 0, unread: 8 },
      }),
    ).toBeDefined();
    expect(
      administrativeReply('inbox.read', {
        code: 'OK',
        payload: { marked: -1, unread: 8 },
      }),
    ).toBeUndefined();
  });
  it('preserves busy/conductor approval decisions without inventing jobs', async () => {
    for (const busy of [true, false]) {
      const outcome: Outcome = {
        code: 'OK',
        payload: {
          id: 'a',
          state: 'allowed',
          job: null,
          busy,
          note: busy ? 'conversation busy' : null,
        },
      };
      expect(
        await dispatch(
          {
            async ask() {
              return outcome;
            },
          },
          asked('approval.answer'),
        ),
      ).toEqual({ kind: 'completed', outcome });
    }
    const outcome: Outcome = {
      code: 'OK',
      payload: { id: 'a', state: 'allowed', job: 'j', busy: true, note: null },
    };
    expect(
      (
        await dispatch(
          {
            async ask() {
              return outcome;
            },
          },
          asked('approval.answer'),
        )
      ).kind,
    ).toBe('invalid-response');
  });
  it('requires matching identities while permitting a topup to return the root topic', async () => {
    for (const type of [
      'orchestration.status',
      'orchestration.answer',
      'orchestration.cancel',
      'approval.answer',
      'approval.revoke',
    ] as const) {
      const payload = structuredClone(fixtures[type].payload) as Record<
        string,
        unknown
      >;
      if (type === 'orchestration.status')
        (payload['orchestration'] as Record<string, unknown>)['id'] = 'other';
      else payload['id'] = 'other';
      const result = await dispatch(
        {
          async ask() {
            return { code: 'OK', payload };
          },
        },
        asked(type),
      );
      expect(result.kind).toBe('invalid-response');
    }
    expect(
      (
        await dispatch(
          {
            async ask() {
              return fixtures['board.topup'];
            },
          },
          request('board.topup', { topic: 'child', maxModelCalls: 100 }),
        )
      ).kind,
    ).toBe('completed');
  });
  it('keeps proposals unsaved and rejects a conflicting destination or provider identity', () => {
    const proposal = fixtures['schedule.read'].payload as Record<
      string,
      unknown
    >;
    expect(
      administrativeReply('schedule.read', {
        code: 'OK',
        payload: { ...proposal, project: 'p' },
      }),
    ).toBeUndefined();
    expect(
      administrativeReply('schedule.read', {
        code: 'OK',
        payload: { ...proposal, conversation: null },
      }),
    ).toBeUndefined();
    const providers = fixtures['provider.list'].payload as Record<
      string,
      unknown
    >[];
    expect(
      administrativeReply('provider.list', {
        code: 'OK',
        payload: [{ ...providers[0], providerKey: 'other' }],
      }),
    ).toBeUndefined();
  });
  it('mirrors every Java response record field, collection, nullable value and primitive type', () => {
    const source = ts.createSourceFile(
      'administrative-replies.ts',
      readFileSync(
        new URL('./administrative-replies.ts', import.meta.url),
        'utf8',
      ),
      ts.ScriptTarget.Latest,
      true,
    );
    const authorities: readonly [
      string,
      string,
      string,
      readonly string[],
      Record<string, string>?,
    ][] = [
      [
        'ApprovalView',
        'server/ws/ApprovalFrames',
        'View',
        ['scope', 'prefix', 'answeredAt', 'commands', 'judged'],
      ],
      [
        'ApprovalListed',
        'server/ws/ApprovalFrames',
        'Listed',
        [],
        { View: 'ApprovalView' },
      ],
      [
        'ApprovalAnswered',
        'server/ws/ApprovalFrames',
        'Answered',
        ['job', 'note'],
      ],
      ['ApprovalRevoked', 'server/ws/ApprovalFrames', 'Revoked', []],
      ['BoardRetried', 'server/ws/BoardPostingFrames', 'RetryReceipt', []],
      ['BoardPosted', 'server/ws/BoardPostingFrames', 'Receipt', []],
      ['BoardOpened', 'server/ws/BoardPostingFrames', 'OpenReceipt', []],
      [
        'BoardTopic',
        'server/board/BoardTopic',
        'BoardTopic',
        [
          'parent',
          'originConversation',
          'resolution',
          'potTotal',
          'potSpent',
          'reserve',
          'quietNotifiedAt',
          'closedAt',
        ],
      ],
      ['BufferPurgeReport', 'server/buffers/Buffers', 'BufferPurgeReport', []],
      ['SweepReport', 'server/archive/Retention', 'SweepReport', []],
      [
        'ProviderFacts',
        'protocol/search/ProviderFacts',
        'ProviderFacts',
        ['description'],
        { Verb: 'string', CostClass: 'string', NetworkTier: 'string' },
      ],
      [
        'ProviderRegistration',
        'server/search/Registration',
        'Registration',
        ['lastHealthAt', 'lastHealthStatus'],
      ],
      [
        'InboxItem',
        'server/events/InboxItem',
        'InboxItem',
        ['firing', 'conversation', 'ending', 'answer', 'readAt', 'about'],
      ],
      ['InboxPage', 'server/ws/InboxListHandler', 'Page', []],
      ['InboxMarked', 'server/ws/InboxReadHandler', 'Marked', []],
      [
        'TodoView',
        'server/ws/TodoView',
        'TodoView',
        ['parent', 'summary', 'stage'],
      ],
      ['StageView', 'protocol/Orchestration', 'StageView', []],
      [
        'DefinitionView',
        'protocol/Orchestration',
        'DefinitionView',
        ['description', 'tier', 'withheld'],
      ],
      [
        'RunView',
        'protocol/Orchestration',
        'RunView',
        [
          'project',
          'pendingCap',
          'result',
          'failure',
          'callerConversation',
          'parent',
          'waitingFor',
          'endedAt',
          'stalledSince',
        ],
      ],
      ['ChildView', 'protocol/Orchestration', 'ChildView', []],
      [
        'MessageView',
        'protocol/Orchestration',
        'MessageView',
        ['deliveredAt', 'capKind', 'structure'],
        { Structure: 'MessageStructure' },
      ],
      ['Definitions', 'protocol/Orchestration', 'Definitions', []],
      ['OrchestrationListed', 'protocol/Orchestration', 'Listed', []],
      [
        'OrchestrationStatus',
        'protocol/Orchestration',
        'Status',
        [],
        { Todo: 'TodoView' },
      ],
      ['OrchestrationAnswered', 'protocol/Orchestration', 'Changed', []],
      ['OrchestrationCancelled', 'protocol/Orchestration', 'Changed', []],
      ['SettingView', 'server/ws/CapsFrames', 'SettingView', ['value']],
      [
        'BooleanSettingView',
        'server/ws/CapsFrames',
        'BooleanSettingView',
        ['value'],
      ],
      ['CapsView', 'server/ws/CapsFrames', 'CapsView', ['said']],
      ['ScheduleRecord', 'server/events/ScheduleRecord', 'ScheduleRecord', []],
      ['ScheduleNames', 'server/events/ScheduleProposal', 'Names', []],
      [
        'ScheduleProposal',
        'server/events/ScheduleProposal',
        'ScheduleProposal',
        ['project', 'conversation'],
        { Names: 'ScheduleNames' },
      ],
      [
        'TriggerRecord',
        'server/events/TriggerRecord',
        'TriggerRecord',
        ['project', 'conversation', 'maxModelCalls', 'maxTurns'],
      ],
      [
        'FiringRecord',
        'server/ws/FiringView',
        'FiringView',
        [
          'schedule',
          'fireAt',
          'trigger',
          'target',
          'supersededBy',
          'reason',
          'jobId',
          'startedAt',
          'finishedAt',
          'topic',
        ],
      ],
    ];
    expect(
      source.statements.filter(
        (node) =>
          ts.isInterfaceDeclaration(node) &&
          node.name.text !== 'AdministrativeReplies',
      ),
    ).toHaveLength(authorities.length);
    for (const [type, path, name, nullable, renamed = {}] of authorities) {
      const [module, ...rest] = path.split('/');
      const java = readFileSync(
        new URL(
          `../../../../plowshare-${module}/src/main/java/io/aeyer/plowshare/${module}/${rest.join('/')}.java`,
          import.meta.url,
        ),
        'utf8',
      );
      // Split record fields without splitting nested List<List<String>> types.
      const raw = new RegExp(
        `(?:public\\s+)?record ${name}\\(([^)]*)\\)`,
        's',
      ).exec(java.replace(/@[\w.]+\([^)]*\)/g, ''))![1]!;
      const fields = raw.split(',').map((part) => {
        const [wireType, key] = part.trim().split(/\s+/);
        function mapped(raw: string): string {
          const list = /^(?:List|Set)<(.+)>$/.exec(raw);
          if (list) {
            const inner = mapped(list[1]!);
            return `readonly ${inner.startsWith('readonly ') ? '(' + inner + ')' : inner}[]`;
          }
          return (
            (
              {
                String: 'string',
                Instant: 'string',
                int: 'number',
                Integer: 'number',
                long: 'number',
                boolean: 'boolean',
                Boolean: 'boolean',
                ...renamed,
              } as Record<string, string>
            )[raw] ?? raw
          );
        }
        return [
          key!,
          mapped(wireType!) + (nullable.includes(key!) ? ' | null' : ''),
        ];
      });
      const dto = source.statements.find(
        (node) => ts.isInterfaceDeclaration(node) && node.name.text === type,
      ) as ts.InterfaceDeclaration;
      expect(dto, type).toBeDefined();
      expect(
        dto.members.map((member) => {
          const field = member as ts.PropertySignature;
          if (type === 'InboxItem' && field.name.getText(source) === 'about')
            expect(field.questionToken, type).toBeDefined();
          else expect(field.questionToken, type).toBeUndefined();
          return [field.name.getText(source), field.type!.getText(source)];
        }),
        type,
      ).toEqual(fields);
    }
  });
});
