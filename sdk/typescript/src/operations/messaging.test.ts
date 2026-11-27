import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  MESSAGING_OPERATIONS,
  messagingReply,
  type MessagingReplies,
} from './messaging.ts';
import { parseCommand } from './commands.ts';
import { request, resultOf, dispatch } from './direct.ts';
import { discovery, effectiveScope, isMutation } from './discovery.ts';
import type { Outcome } from '../binding/envelope.ts';
const fixtures = JSON.parse(
  readFileSync(
    new URL(
      '../../../../test-support/contracts/ws-messaging-fixtures.json',
      import.meta.url,
    ),
    'utf8',
  ),
) as Record<keyof MessagingReplies, Outcome>;
describe('messaging contracts and controls', () => {
  it.each(MESSAGING_OPERATIONS)(
    '%s keeps complete server data and refuses malformed replies',
    (type) => {
      expect(messagingReply(type, fixtures[type])).toEqual(fixtures[type]);
      expect(messagingReply(type, { code: 'OK', payload: {} })).toBeUndefined();
      expect(
        messagingReply(type, { code: 'BAD_REQUEST', said: 'No access' }),
      ).toBeUndefined();
    },
  );
  it('accepts correlated generated failure status while preserving final reply semantics', () => {
    const notice: MessagingReplies['message.delivery'] = {
      message: 'bdm_Error',
      sender: 'ins_Worker',
      recipient: 'ins_Sender',
      replyTo: 'bdm_Request',
      replyExpected: false,
      finalReply: false,
      generated: true,
      state: 'handled',
      ending: 'UNAVAILABLE',
      reply: null,
      job: null,
      deadlineAt: null,
      postedAt: '2026-11-16T00:00:00Z',
      body: 'The recipient job had an error.',
    };
    expect(
      messagingReply('message.delivery', { code: 'OK', payload: notice })
        ?.payload,
    ).toEqual(notice);
    for (const invalid of [
      { ...notice, ending: 'ANSWERED' },
      { ...notice, ending: 'AWAITING' },
      { ...notice, replyExpected: true },
      { ...notice, replyTo: null },
    ])
      expect(
        messagingReply('message.delivery', { code: 'OK', payload: invalid }),
      ).toBeUndefined();
  });

  it('discovers every operation, mutation and retained opening identity offline', () => {
    const operations = discovery().commands.filter((row) =>
      row.operation.startsWith('message.'),
    );
    expect(operations).toHaveLength(11);
    expect(isMutation('message.listen')).toBe(true);
    expect(isMutation('message.unlisten')).toBe(true);
    expect(isMutation('message.instance.open')).toBe(true);
    expect(isMutation('message.cancel')).toBe(true);
    expect(isMutation('message.delivery')).toBe(false);
    expect(parseCommand('message instances', 'p')).toEqual({
      kind: 'request',
      request: request('message.instances', { project: 'p' }),
    });
    expect(parseCommand('message instance ins_reviewer', 'other')).toEqual({
      kind: 'request',
      request: request('message.instance', { instance: 'ins_reviewer' }),
    });
    expect(
      parseCommand(
        'message open {"agent":"reviewer","requestId":"00000000-0000-0000-0000-000000000001","makeDefault":true}',
        'p',
      ),
    ).toMatchObject({
      kind: 'request',
      request: {
        type: 'message.instance.open',
        payload: { project: 'p', makeDefault: true },
      },
    });
    expect(
      parseCommand(
        'message open {"agent":"reviewer","requestId":"invalid"}',
        'p',
      ).kind,
    ).toBe('usage');
    expect(
      effectiveScope(request('message.cancel', { message: 'bdm_request' })),
    ).toEqual({ kind: 'message', id: 'bdm_request' });
  });
  it('checks receipt identity and never replays a control after a lost response', async () => {
    expect(
      resultOf(
        request('message.instance', { instance: 'foreign' }),
        fixtures['message.instance'],
      ).kind,
    ).toBe('invalid-response');
    let calls = 0;
    await expect(
      dispatch(
        {
          async ask() {
            calls++;
            throw new Error('Lost after submission');
          },
        },
        request('message.cancel', { message: 'bdm_request' }),
      ),
    ).rejects.toThrow('Lost');
    expect(calls).toBe(1);
  });
});
