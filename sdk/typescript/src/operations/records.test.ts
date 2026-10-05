import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';
import { dispatch, request } from './direct.ts';
import {
  recordReply,
  readingRecordEarlier,
  readingRecordTail,
  readingToolLine,
} from './records.ts';

const page = () => ({
  root: 'orc_root',
  rows: [
    {
      ordinal: 9,
      at: '2026-10-02T00:00:00Z',
      run: 'orc_child',
      actor: 'worker',
      kind: 'future_kind',
      text: 'source text',
      detail: null,
      body: 'full\nsource',
      future: true,
    },
    {
      ordinal: 8,
      at: '2026-10-01T23:59:59Z',
      run: 'orc_root',
      actor: 'conductor',
      kind: 'tool_call',
      text: 'called',
      detail: 'awaiting',
    },
  ],
  total: 90,
  limit: 2,
  through: 100,
  oldest: 8,
  more: true,
  future: { retained: true },
});

describe('shared orchestration record contracts', () => {
  it('preserves order, coordinates, bodies and future kinds while projecting fields', async () => {
    const payload = page();
    const { future: _future, ...known } = payload;
    const rows = known.rows.map((row) => {
      const { future: _field, ...fields } = row;
      return fields;
    });
    expect(recordReply(payload)).toStrictEqual({ ...known, rows });
    expect(payload.rows.map((row) => row.ordinal)).toEqual([9, 8]);
    const result = await dispatch(
      {
        async ask() {
          return { code: 'OK', payload };
        },
      },
      request('orchestration.record', { root: 'orc_root', before: 10 }),
    );
    expect(result.kind).toBe('completed');
    expect(result.outcome.payload).toBe(payload);
  });
  it('accepts genuine empty forward and backward windows without inventing rows', () => {
    for (const more of [false, null]) {
      const payload = {
        root: 'orc_root',
        rows: [],
        total: 100,
        limit: 20,
        through: 100,
        oldest: null,
        more,
      };
      expect(recordReply(payload)).toStrictEqual(payload);
    }
  });
  it('rejects incomplete or malformed pages instead of dropping bad rows or claiming empty success', () => {
    for (const missing of [
      'root',
      'rows',
      'total',
      'limit',
      'through',
      'oldest',
      'more',
    ]) {
      const payload: Record<string, unknown> = page();
      delete payload[missing];
      expect(recordReply(payload), missing).toBeUndefined();
    }
    for (const [field, value] of [
      ['total', -1],
      ['limit', 0],
      ['through', NaN],
      ['oldest', 1.5],
      ['more', 'yes'],
      ['rows', {}],
      ['root', ''],
    ] as const) {
      expect(recordReply({ ...page(), [field]: value }), field).toBeUndefined();
    }
    for (const [field, value] of [
      ['ordinal', 0],
      ['ordinal', 101],
      ['ordinal', 1.5],
      ['detail', false],
      ['at', null],
      ['body', null],
      ['text', {}],
    ] as const) {
      const payload = page();
      Object.assign(payload.rows[0]!, { [field]: value });
      expect(recordReply(payload), field).toBeUndefined();
    }
    const duplicate = page();
    duplicate.rows.push(duplicate.rows[0]!);
    expect(recordReply(duplicate)).toBeUndefined();
  });
  it('returns the original malformed/refused outcome after one WS request', async () => {
    for (const outcome of [
      { code: 'OK', payload: { root: 'orc_root', rows: [] } },
      { code: 'CREATED', payload: page() },
      { code: 'NOT_FOUND', said: 'outside account' },
    ] as const) {
      const calls: unknown[] = [];
      const asked = request('orchestration.record', {
        root: 'orc_root',
        after: 12,
      });
      const result = await dispatch(
        {
          async ask(type, payload) {
            calls.push({ type, payload });
            return outcome;
          },
        },
        asked,
      );
      expect(result.outcome).toBe(outcome);
      expect(result.kind).toBe(
        outcome.code === 'NOT_FOUND' ? 'refused' : 'invalid-response',
      );
      expect(calls).toEqual([asked]);
    }
  });
  it('keeps tail, ordinal paging, filter and settled-tool rereads on the same WS contract', () => {
    expect(readingRecordTail('orc_root', ['question_asked'], 1)).toEqual({
      type: 'orchestration.record',
      payload: {
        root: 'orc_root',
        tail: true,
        limit: 1,
        kinds: ['question_asked'],
      },
    });
    expect(readingRecordEarlier('orc_root', 51).payload).toEqual({
      root: 'orc_root',
      before: 51,
      limit: 100,
    });
    expect(readingToolLine('orc_root', 29).payload).toEqual({
      root: 'orc_root',
      after: 28,
      limit: 1,
      kinds: ['tool_call'],
    });
  });
  it('matches every field, nullable value and omitted body in the Java response records', () => {
    const source = ts.createSourceFile(
      'records.ts',
      readFileSync(new URL('./records.ts', import.meta.url), 'utf8'),
      ts.ScriptTarget.Latest,
      true,
    );
    for (const name of ['RecordView', 'RecordPageView']) {
      const java = readFileSync(
        new URL(
          `../../../../plowshare-server/src/main/java/io/aeyer/plowshare/server/api/${name}.java`,
          import.meta.url,
        ),
        'utf8',
      ).replace(/@JsonInclude\([^)]*\)/g, '');
      const raw = new RegExp(`record ${name}\\(([^)]*)\\)`, 's').exec(
        java,
      )![1]!;
      const authority = raw.split(',').map((field) => {
        const [type, fieldName] = field.trim().split(/\s+/);
        const mapped = (
          {
            int: 'number',
            Integer: 'number | null',
            Boolean: 'boolean | null',
            Instant: 'string',
            String: fieldName === 'detail' ? 'string | null' : 'string',
            'List<RecordView>': 'readonly RecordView[]',
          } as Record<string, string>
        )[type!]!;
        return [fieldName, mapped, fieldName === 'body'];
      });
      const dto = source.statements.find(
        (node) => ts.isInterfaceDeclaration(node) && node.name.text === name,
      ) as ts.InterfaceDeclaration;
      expect(
        dto.members.map((member) => {
          const field = member as ts.PropertySignature;
          return [
            field.name.getText(source),
            field.type!.getText(source),
            !!field.questionToken,
          ];
        }),
      ).toEqual(authority);
    }
  });
});
