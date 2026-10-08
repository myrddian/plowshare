import { isList } from '../binding/values.ts';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { describe, expect, expectTypeOf, it } from 'vitest';
import type { Outcome } from '../binding/envelope.ts';
import {
  INSPECTION_OPERATIONS,
  inspectionReply,
  type InspectionOperation,
} from './inspection-replies.ts';
import {
  dispatch,
  request,
  VALIDATED_OPERATIONS,
  MEMORY_OPERATIONS,
  JOB_SUBMISSIONS,
  type Request,
} from './direct.ts';
import { CLI_OPERATIONS } from './catalog.ts';
import type { Replies } from './replies.ts';
import type { Operation } from './direct.ts';

const fixtures = JSON.parse(
  readFileSync(
    new URL(
      '../../../../test-support/contracts/ws-inspection-fixtures.json',
      import.meta.url,
    ),
    'utf8',
  ),
) as Record<InspectionOperation, Outcome>;
const asked = (type: InspectionOperation): Request =>
  ({ type, payload: { job: 'j' } }) as Request;
const object = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !isList(value);
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
        ...missingFields(value[key]).map((changed) => ({
          ...value,
          [key]: changed,
        })),
      ];
    });
}

describe('shared Board, Swarm, union and job inspection replies', () => {
  it('accounts for every one-shot command and never claims bound sync/subscription validation', () => {
    const exposed = new Set<string>([
      ...Object.values(CLI_OPERATIONS),
      ...Object.values(MEMORY_OPERATIONS),
      ...JOB_SUBMISSIONS,
      'conversation.search',
      'job.status',
      'job.cancel',
    ]);
    expect([...VALIDATED_OPERATIONS].sort()).toEqual([...exposed].sort());
    expect(exposed.size).toBe(191);
    expectTypeOf<keyof Replies>().toEqualTypeOf<Operation>();
    expect(Object.keys(fixtures).sort()).toEqual(
      [...INSPECTION_OPERATIONS].sort(),
    );
  });
  it.each(INSPECTION_OPERATIONS)(
    '%s retains complete raw snapshots after one WS request',
    async (type) => {
      const outcome = fixtures[type],
        calls: unknown[] = [];
      expect(inspectionReply(type, outcome)).toBe(outcome);
      expect(
        await dispatch(
          {
            async ask(type, payload) {
              calls.push({ type, payload });
              return outcome;
            },
          },
          asked(type),
        ),
      ).toEqual({ kind: 'completed', outcome });
      expect(calls).toEqual([asked(type)]);
    },
  );
  it.each(INSPECTION_OPERATIONS)(
    '%s rejects partial snapshots instead of inventing an empty result',
    async (type) => {
      const original = fixtures[type];
      const invalid: Outcome[] = [
        { ...original, code: 'CREATED' },
        { ...original, code: 'NO_CONTENT' },
        { code: 'OK', payload: {} },
      ];
      if (!type.startsWith('job.'))
        invalid.push(
          ...missingFields(original.payload).map((payload) => ({
            ...original,
            payload,
          })),
        );
      else
        invalid.push({
          code: 'OK',
          payload:
            type === 'job.list' ? [{ state: 'RUNNING' }] : { state: 'RUNNING' },
        });
      for (const outcome of invalid) {
        expect(
          inspectionReply(type, outcome),
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
        expect(result.kind).toBe('invalid-response');
        expect(result.outcome).toBe(outcome);
        expect(calls).toBe(1);
      }
    },
  );
  it('preserves the deliberately small unrooted union reply, refusing malformed eligible state', () => {
    expect(
      inspectionReply('union.status', {
        code: 'OK',
        payload: { eligible: false, enabled: false, future: true },
      }),
    ).toBeDefined();
    expect(
      inspectionReply('union.status', {
        code: 'OK',
        payload: { eligible: false, enabled: true },
      }),
    ).toBeUndefined();
    expect(
      inspectionReply('union.status', {
        code: 'OK',
        payload: { eligible: true, enabled: false },
      }),
    ).toBeUndefined();
  });
  it('requires job limits to name the requested job without turning a limit change into a wait', async () => {
    const outcome = {
      ...fixtures['job.limits'],
      payload: { ...(fixtures['job.limits'].payload as object), id: 'other' },
    };
    expect(
      (
        await dispatch(
          {
            async ask() {
              return outcome;
            },
          },
          request('job.limits', { job: 'j', maxTurns: 10 }),
        )
      ).kind,
    ).toBe('invalid-response');
    expect(
      inspectionReply('job.list', { code: 'OK', payload: [] }),
    ).toBeDefined();
    // Documented legacy job metadata may be absent; identity/state may never be.
    expect(
      inspectionReply('job.list', {
        code: 'OK',
        payload: [{ id: 'j', state: 'RUNNING' }],
      }),
    ).toBeDefined();
  });
  it('rejects Board detail for a different tree or mismatched seat/message topic', () => {
    for (const field of ['root', 'seats', 'messages']) {
      const detail = structuredClone(
        fixtures['board.messages'].payload,
      ) as Record<string, unknown>;
      if (field === 'root')
        (detail['root'] as Record<string, unknown>)['id'] = 'other';
      if (field === 'seats')
        (
          (detail['seats'] as Record<string, unknown>[])[0]!['seat'] as Record<
            string,
            unknown
          >
        )['topic'] = 'other';
      if (field === 'messages')
        (detail['messages'] as Record<string, unknown>[])[0]!['topic'] =
          'other';
      expect(
        inspectionReply('board.messages', { code: 'OK', payload: detail }),
      ).toBeUndefined();
    }
  });
  it('mirrors Java inspection records and the exact union conflict map fields', () => {
    const source = ts.createSourceFile(
      'inspection-replies.ts',
      readFileSync(new URL('./inspection-replies.ts', import.meta.url), 'utf8'),
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
      ['BoardSummary', 'board/BoardStore', 'Summary', []],
      [
        'BoardTopics',
        'ws/BoardInspectionFrames',
        'Topics',
        [],
        { 'BoardStore.Summary': 'BoardSummary' },
      ],
      [
        'BoardSeat',
        'board/BoardSeat',
        'BoardSeat',
        ['failedEnding', 'seenThrough'],
      ],
      [
        'SeatView',
        'ws/BoardInspectionFrames',
        'SeatView',
        ['job', 'reason', 'position', 'waitedMillis'],
      ],
      [
        'BoardMessage',
        'board/BoardMessage',
        'BoardMessage',
        ['replyTo', 'conversation', 'entry', 'title'],
      ],
      ['BoardDecision', 'board/BoardStore', 'Decision', ['child']],
      [
        'BoardMessages',
        'ws/BoardInspectionFrames',
        'Messages',
        [],
        { 'BoardStore.Decision': 'BoardDecision' },
      ],
      ['Ready', 'ws/BoardInspectionFrames', 'Ready', []],
      ['PoolUse', 'swarm/SwarmScheduler', 'PoolUse', []],
      [
        'Swarm',
        'ws/BoardInspectionFrames',
        'Swarm',
        [],
        {
          'SwarmScheduler.PoolUse': 'PoolUse',
          'BoardStore.Summary': 'BoardSummary',
        },
      ],
      [
        'UnionConflict',
        'union/UnionStore',
        'Conflict',
        ['baseBlob', 'oursBlob', 'theirsBlob', 'runId'],
      ],
    ];
    for (const [type, path, name, nullable, renamed = {}] of authorities) {
      const java = readFileSync(
        new URL(
          `../../../../plowshare-server/src/main/java/io/aeyer/plowshare/server/${path}.java`,
          import.meta.url,
        ),
        'utf8',
      );
      const fields = new RegExp(`public record ${name}\\(([^)]*)\\)`, 's')
        .exec(java)![1]!
        .split(',')
        .map((part) => {
          const [wireType, key] = part.trim().split(/\s+/);
          function mapped(raw: string): string {
            const list = /^List<(.+)>$/.exec(raw);
            if (list) return `readonly ${mapped(list[1]!)}[]`;
            return (
              (
                {
                  String: 'string',
                  Instant: 'string',
                  int: 'number',
                  Integer: 'number',
                  long: 'number',
                  Long: 'number',
                  boolean: 'boolean',
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
      expect(
        dto.members.map((member) => {
          const field = member as ts.PropertySignature;
          return [field.name.getText(source), field.type!.getText(source)];
        }),
        type,
      ).toEqual(fields);
    }
    // UnionFrames writes maps rather than public response records.
    const java = readFileSync(
      new URL(
        '../../../../plowshare-server/src/main/java/io/aeyer/plowshare/server/union/UnionFrames.java',
        import.meta.url,
      ),
      'utf8',
    );
    const status = java.slice(
      java.indexOf('private Outcome status('),
      java.indexOf('private Outcome enable('),
    );
    const conflict = java.slice(
      java.indexOf('private Outcome listConflicts('),
      java.indexOf('private Outcome resolveConflict('),
    );
    const names = (type: string) =>
      (
        source.statements.find(
          (node) => ts.isInterfaceDeclaration(node) && node.name.text === type,
        ) as ts.InterfaceDeclaration
      ).members.map((member) =>
        (member as ts.PropertySignature).name.getText(source),
      );
    expect(
      [...status.matchAll(/said.put\("(\w+)"/g)].map((match) => match[1]),
    ).toEqual(names('EligibleUnion'));
    expect(
      [...conflict.matchAll(/row.put\("(\w+)"/g)].map((match) => match[1]),
    ).toEqual(names('UnionConflict'));
  });
});
