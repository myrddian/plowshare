import { describe, expect, it } from 'vitest';
import { parseCommand } from './commands.ts';
import { decodeReply, decodeRequest } from './schema.ts';
import { discovery } from './discovery.ts';
import { resultOf } from './direct.ts';

const conversation = 'cnv_project';
const requestId = '00000000-0000-0000-0000-000000000001';
const goal = '00000000-0000-0000-0000-000000000002';

describe('conversation goal contracts', () => {
  it('exposes the CLI controls without adding a default project or session', () => {
    expect(
      parseCommand('conversation goal get cnv_project', 'different-project'),
    ).toEqual({
      kind: 'request',
      request: { type: 'conversation.goal.get', payload: { conversation } },
    });
    const payload = {
      conversation,
      requestId,
      agent: 'agent',
      objective: 'Check the report',
    };
    expect(
      parseCommand('conversation goal start ' + JSON.stringify(payload)),
    ).toEqual({
      kind: 'request',
      request: { type: 'conversation.goal.start', payload },
    });
    expect(discovery('conversation goal').commands).toHaveLength(5);
  });

  it('accepts an explicit absolute model-call ceiling only on resume', () => {
    const control = { conversation, requestId, goal, version: 1 };
    for (const maxModelCalls of [1, 1000000]) {
      expect(
        decodeRequest('conversation.goal.resume', {
          ...control,
          maxModelCalls,
        }),
      ).toEqual({
        type: 'conversation.goal.resume',
        payload: { ...control, maxModelCalls },
      });
    }
    for (const maxModelCalls of [0, 1.5, 1000001, null, '10']) {
      expect(() =>
        decodeRequest('conversation.goal.resume', {
          ...control,
          maxModelCalls,
        }),
      ).toThrow();
    }
    expect(() =>
      decodeRequest('conversation.goal.pause', {
        ...control,
        maxModelCalls: 10,
      }),
    ).toThrow();
  });

  it('rejects malformed controls and decodes a reconnectable receipt', () => {
    expect(() =>
      decodeRequest('conversation.goal.pause', {
        conversation,
        requestId,
        goal,
        version: 0,
      }),
    ).toThrow();
    expect(() =>
      decodeRequest('conversation.goal.resume', {
        conversation,
        requestId,
        goal,
        version: 1,
        maxActivations: 1.2,
      }),
    ).toThrow();
    expect(() =>
      decodeRequest('conversation.goal.get', {
        conversation,
        requestId: 'bogus',
      }),
    ).toThrow();
    const status = {
      goal,
      conversation,
      agent: 'agent',
      objective: 'Check the report',
      criteria: null,
      state: 'BLOCKED',
      version: 3,
      activations: 1,
      maxActivations: 8,
      lastJob: 'job_1',
      lastTurn: null,
      reason: 'UNCONFIRMED_TURN',
      detail: 'Inspect the original job',
      modelCallsSpent: 1,
      modelCallLimit: 8,
    };
    expect(decodeReply('conversation.goal.get', status)).toEqual(status);
    const waiting = {
      ...status,
      state: 'WAITING',
      reason: null,
      detail: 'Waiting for the dependency to settle',
    };
    expect(decodeReply('conversation.goal.get', waiting)).toEqual(waiting);
    expect(() =>
      decodeReply('conversation.goal.get', {
        ...status,
        state: 'UNRECOGNIZED',
      }),
    ).toThrow();
  });
});

describe('dedicated Goal Agent contracts', () => {
  const generation = '00000000-0000-0000-0000-000000000003';
  const binding = '00000000-0000-0000-0000-000000000004';
  const identity = { goal, conversation: 'cnv_Goal' };
  it('lists eligible sources without accepting a project override', () => {
    expect(
      decodeRequest('goal.sources', {
        ...identity,
        prefix: 'news.',
        kind: 'EMPTY',
      }),
    ).toMatchObject({ type: 'goal.sources' });
    expect(() =>
      decodeRequest('goal.sources', { ...identity, project: 'other' }),
    ).toThrow();
    expect(() =>
      decodeRequest('goal.sources', { ...identity, kind: 'BLOB' }),
    ).toThrow();
    expect(() =>
      decodeRequest('goal.sources', { ...identity, limit: 101 }),
    ).toThrow();
  });
  it('requires the topic generation and correlates the listener receipt', () => {
    expect(() =>
      decodeRequest('message.listen', { ...identity, topic: 'news.feed' }),
    ).toThrow();
    const request = decodeRequest('message.listen', {
      ...identity,
      topic: 'news.feed',
      generation,
    });
    const receipt = { binding, goal, topic: 'news.feed', generation };
    expect(resultOf(request, { code: 'OK', payload: receipt }).kind).toBe(
      'completed',
    );
    expect(
      resultOf(request, {
        code: 'OK',
        payload: { ...receipt, generation: requestId },
      }).kind,
    ).toBe('invalid-response');
  });
  it('validates the exact question and preserves answer text within its UTF-16 bound', () => {
    const type = 'goal.answer';
    const question = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
    const receipt = { ...identity, question, requestId };
    const payload = {
      ...receipt,
      question: question.toUpperCase(),
      answer: '  Use the dataset.\n',
    };
    const request = decodeRequest(type, payload);
    expect(request.payload).toEqual(payload);
    expect(discovery('goal answer').commands).toMatchObject([
      { operation: type, mutation: true },
    ]);
    expect(parseCommand('goal answer ' + JSON.stringify(payload))).toEqual({
      kind: 'request',
      request,
    });
    const maximum = '😀'.repeat(8000);
    expect(
      decodeRequest(type, { ...payload, answer: maximum }).payload,
    ).toMatchObject({ answer: maximum });
    for (const answer of [
      maximum + 'x',
      '',
      '\t\u001c\u2008',
      'data\0set',
      'a\ud800',
    ]) {
      expect(() => decodeRequest(type, { ...payload, answer })).toThrow();
    }
    expect(() => decodeRequest(type, { ...payload, version: 1 })).toThrow();

    expect(decodeReply(type, { ...receipt, future: true })).toEqual(receipt);
    expect(resultOf(request, { code: 'OK', payload: receipt }).kind).toBe(
      'completed',
    );
    for (const change of [
      { goal: generation },
      { conversation: 'cnv_foreign' },
      { question: generation },
      { question: question.toUpperCase() },
      { requestId: generation },
    ]) {
      expect(
        resultOf(request, { code: 'OK', payload: { ...receipt, ...change } })
          .kind,
      ).toBe('invalid-response');
    }
    expect(resultOf(request, { code: 'ACCEPTED', payload: receipt }).kind).toBe(
      'invalid-response',
    );
    expect(resultOf(request, { code: 'CONFLICT' }).kind).toBe('refused');
  });

  it('requires both batching fields and checks their bounds', () => {
    const listener = { ...identity, topic: 'news.feed', generation };
    const valid = [
      listener,
      { ...listener, batchCount: 2, maxDelaySeconds: 1 },
      { ...listener, batchCount: 16, maxDelaySeconds: 86400 },
    ];
    for (const payload of valid) {
      expect(decodeRequest('message.listen', payload)).toEqual({
        type: 'message.listen',
        payload,
      });
    }
    for (const invalid of [
      { batchCount: 2 },
      { maxDelaySeconds: 1 },
      { batchCount: 1, maxDelaySeconds: 1 },
      { batchCount: 17, maxDelaySeconds: 1 },
      { batchCount: 2, maxDelaySeconds: 0 },
      { batchCount: 2, maxDelaySeconds: 86401 },
      { batchCount: 2.5, maxDelaySeconds: 1 },
      { batchCount: 2, maxDelaySeconds: 1.5 },
      { batchCount: null, maxDelaySeconds: null },
    ]) {
      expect(() =>
        decodeRequest('message.listen', { ...listener, ...invalid }),
      ).toThrow();
    }
  });

  it('discovers versioned unlisten and requires an exact mutation receipt', () => {
    const payload = { ...identity, binding, version: 5, requestId };
    const type = 'message.unlisten';
    const request = decodeRequest(type, payload);
    expect(discovery('message unlisten').commands).toMatchObject([
      { operation: type, mutation: true },
    ]);
    expect(parseCommand('message unlisten ' + JSON.stringify(payload))).toEqual(
      {
        kind: 'request',
        request,
      },
    );
    for (const invalid of [
      { binding: '00000000-0000-0000-0000-000000000000' },
      { requestId: 'foreign' },
      { conversation: 'foreign' },
      { version: 0 },
      { version: 2147483648 },
      { project: 'foreign' },
    ]) {
      expect(() => decodeRequest(type, { ...payload, ...invalid })).toThrow();
    }

    const receipt = { goal, binding, requestId, version: 6 };
    expect(decodeReply(type, { ...receipt, extra: true })).toEqual(receipt);
    expect(resultOf(request, { code: 'OK', payload: receipt }).kind).toBe(
      'completed',
    );
    for (const invalid of [
      { version: 5 },
      { goal: generation },
      { binding: generation },
      { requestId: generation },
      { requestId: null },
      { version: 6.5 },
    ]) {
      expect(
        resultOf(request, { code: 'OK', payload: { ...receipt, ...invalid } })
          .kind,
      ).toBe('invalid-response');
    }
    expect(resultOf(request, { code: 'ACCEPTED', payload: receipt }).kind).toBe(
      'invalid-response',
    );
    expect(resultOf(request, { code: 'CONFLICT' }).kind).toBe('refused');
  });
});
