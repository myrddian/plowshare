import { describe, it, expect } from 'vitest';
import { decodeRequest } from './schema.ts';
import { request, resultOf } from './direct.ts';
import { isMutation } from './discovery.ts';

const conversation = 'caller-conversation';
const id = '11111111-1111-1111-1111-111111111111';
const other = '33333333-3333-3333-3333-333333333333';
const scope = '22222222-2222-2222-2222-222222222222';
const revision = '44444444-4444-4444-4444-444444444444';
const create = {
  conversation,
  requestId: id,
  ceiling: 0,
  destination: null,
  writableAreas: [],
  files: [{ path: 'plowshare.json', text: '{"version":1}' }],
};
const child = {
  id,
  scope,
  project: `contained:${id}`,
  revision,
};
const OPERATIONS = [
  'application.child.create',
  'application.child.ceiling',
  'application.child.destroy',
  'application.child.list',
  'application.child.resources',
  'application.child.trajectory',
  'application.child.run',
  'application.child.cancel',
] as const;
const valid = {
  'application.child.create': create,
  'application.child.ceiling': { conversation, child: id, calls: 2 },
  'application.child.destroy': { conversation, child: id },
  'application.child.list': { conversation },
  'application.child.resources': { conversation, child: id },
  'application.child.trajectory': { conversation, child: id },
  'application.child.run': {
    conversation,
    child: id,
    agent: 'worker',
    task: 'do the thing',
    maxTurns: 3,
  },
  'application.child.cancel': { conversation, child: id },
} as const;
/**
 * The only contained operation that answers ACCEPTED: a run is minutes, so the reply is a handle.
 * The conversation in it is the child's own, never the container's that the request named.
 */
const running = {
  child: id,
  job: 'job_1',
  conversation: 'cnv_child',
  agent: 'worker',
};
const resource = {
  path: 'plowshare.json',
  kind: 'manifest',
  revision,
  bytes: 12,
};

describe('contained Application operations', () => {
  it('accepts exactly the payload the server accepts', () => {
    for (const type of OPERATIONS)
      expect(decodeRequest(type, valid[type]).payload).toEqual(valid[type]);
  });

  it('refuses unknown and missing fields', () => {
    for (const type of OPERATIONS) {
      expect(() =>
        decodeRequest(type, { ...valid[type], scope: scope }),
      ).toThrow(/does not match the operation contract/);
      for (const field of Object.keys(valid[type])) {
        const { [field]: _omitted, ...rest } = valid[type] as Record<
          string,
          unknown
        >;
        expect(() => decodeRequest(type, rest)).toThrow(
          /does not match the operation contract/,
        );
      }
    }
  });

  it('refuses what the server refuses before anything is sent, for the stated reason', () => {
    const count = /explicit non-negative call count/,
      shape = /does not match the operation contract/;
    for (const [bad, reason] of [
      [{ ceiling: -1 }, count],
      [{ ceiling: 1.5 }, count],
      [{ ceiling: null }, shape],
      [{ ceiling: '3' }, shape],
      [{ requestId: 'not-a-uuid' }, /UUID requestId/],
      [{ files: [] }, /needs 1.128 text files/],
      [
        { files: [{ path: '../escape', text: 'x' }] },
        /unique portable relative source paths/,
      ],
      [{ writableAreas: 'all' }, shape],
      [{ destination: { store: 'applications' } }, shape],
      [{ conversation: ' ' }, /container conversation/],
    ] as const)
      expect(() =>
        decodeRequest('application.child.create', { ...create, ...bad }),
      ).toThrow(reason);
    for (const [type, bad, reason] of [
      ['application.child.ceiling', { child: 'nope' }, /child must be a UUID/],
      ['application.child.ceiling', { calls: -1 }, count],
      ['application.child.ceiling', { calls: null }, shape],
      ['application.child.ceiling', { calls: 1.5 }, count],
      ['application.child.destroy', { child: 'nope' }, /child must be a UUID/],
      [
        'application.child.resources',
        { child: 'nope' },
        /child must be a UUID/,
      ],
      ['application.child.resources', { child: 7 }, shape],
      [
        'application.child.resources',
        { conversation: ' ' },
        /container conversation/,
      ],
      [
        'application.child.trajectory',
        { child: 'nope' },
        /child must be a UUID/,
      ],
      [
        'application.child.list',
        { conversation: '' },
        /container conversation/,
      ],
      ['application.child.cancel', { child: 'nope' }, /child must be a UUID/],
      [
        'application.child.cancel',
        { conversation: ' ' },
        /container conversation/,
      ],
      ['application.child.run', { child: 'nope' }, /child must be a UUID/],
      ['application.child.run', { agent: ' ' }, /agent must be a bounded/],
      // A wrong JSON type is refused structurally, before the semantic gate runs.
      ['application.child.run', { agent: 7 }, shape],
      ['application.child.run', { task: '' }, /task must be a bounded/],
      ['application.child.run', { task: null }, shape],
      // Zero is refused rather than read as "no turns", which would be a run that cannot run,
      // and a cap is an integer. Null is the one absence the shape allows.
      ['application.child.run', { maxTurns: 0 }, /positive turn cap/],
      ['application.child.run', { maxTurns: -1 }, /positive turn cap/],
      ['application.child.run', { maxTurns: 1.5 }, /positive turn cap/],
      ['application.child.run', { maxTurns: '3' }, shape],
    ] as const)
      expect(() => decodeRequest(type, { ...valid[type], ...bad })).toThrow(
        reason,
      );
  });

  it('accepts the one absence the run shape allows, and no other', () => {
    expect(
      decodeRequest('application.child.run', {
        ...valid['application.child.run'],
        maxTurns: null,
      }).payload,
    ).toEqual({ ...valid['application.child.run'], maxTurns: null });
  });

  it('classifies the five that change state as mutations', () => {
    expect(isMutation('application.child.create')).toBe(true);
    expect(isMutation('application.child.ceiling')).toBe(true);
    expect(isMutation('application.child.destroy')).toBe(true);
    expect(isMutation('application.child.run')).toBe(true);
    expect(isMutation('application.child.cancel')).toBe(true);
    expect(isMutation('application.child.list')).toBe(false);
    expect(isMutation('application.child.resources')).toBe(false);
    expect(isMutation('application.child.trajectory')).toBe(false);
  });

  it('reads the replies the server gives and nothing else', () => {
    const replies = {
      'application.child.create': child,
      'application.child.ceiling': { child: id },
      'application.child.destroy': {
        child: id,
        collected: [other, id],
        sources: 1,
      },
      'application.child.list': { children: [child] },
      'application.child.resources': { resources: [resource] },
      'application.child.trajectory': {
        steps: [
          {
            conversation: 'c',
            agent: 'worker',
            parent: null,
            budget: other,
            capabilities: 'a'.repeat(64),
          },
        ],
      },
      'application.child.cancel': { child: id },
    } as const;
    for (const type of OPERATIONS)
      if (type !== 'application.child.run')
        expect(
          resultOf(request(type, valid[type]), {
            code: 'OK',
            payload: replies[type],
          }).kind,
        ).toBe('completed');
  });

  it('reads a started run as a handle and not as a completed answer', () => {
    const asked = request(
      'application.child.run',
      valid['application.child.run'],
    );
    expect(resultOf(asked, { code: 'ACCEPTED', payload: running })).toEqual({
      kind: 'accepted',
      outcome: { code: 'ACCEPTED', payload: running },
      job: running.job,
      conversation: running.conversation,
    });
    // OK is the wrong success for a run: the work has not finished, so an OK answer is a reply
    // this SDK does not understand rather than a result to act on.
    for (const code of ['OK', 'CREATED', 'NO_CONTENT'] as const)
      expect(resultOf(asked, { code, payload: running }).kind).toBe(
        'invalid-response',
      );
    expect(
      resultOf(asked, { code: 'BAD_REQUEST', payload: running }).kind,
    ).toBe('refused');
    for (const bad of [
      { ...running, job: '' },
      { ...running, conversation: '' },
      { ...running, agent: '' },
      { ...running, child: 'nope' },
      { child: running.child, job: running.job, agent: running.agent },
    ])
      expect(resultOf(asked, { code: 'ACCEPTED', payload: bad }).kind).toBe(
        'invalid-response',
      );
  });

  it('refuses a reply that carries an internal value object or a malformed project', () => {
    const asked = request(
      'application.child.list',
      valid['application.child.list'],
    );
    for (const bad of [
      { ...child, scope: { value: scope } },
      { ...child, project: 'app' },
      { ...child, project: `contained:${id}/${other}` },
    ])
      expect(
        resultOf(asked, { code: 'OK', payload: { children: [bad] } }).kind,
      ).toBe('invalid-response');
  });

  it('refuses a resource listing that has an unknown kind, an impossible size or no revision', () => {
    const asked = request(
      'application.child.resources',
      valid['application.child.resources'],
    );
    for (const bad of [
      { ...resource, kind: 'directory' },
      { ...resource, bytes: -1 },
      { ...resource, bytes: 1.5 },
      { path: resource.path, kind: resource.kind, revision },
    ])
      expect(
        resultOf(asked, { code: 'OK', payload: { resources: [bad] } }).kind,
      ).toBe('invalid-response');
  });

  it('refuses a reply about a different child than the one asked of', () => {
    for (const [type, payload] of [
      ['application.child.ceiling', { child: other }],
      [
        'application.child.destroy',
        { child: other, collected: [other], sources: 1 },
      ],
      ['application.child.cancel', { child: other }],
    ] as const)
      expect(
        resultOf(request(type, valid[type]), { code: 'OK', payload }).kind,
      ).toBe('invalid-response');
    // A started run answered about another child is refused rather than published: a container
    // acting on it would be acting inside an Application it never named.
    expect(
      resultOf(
        request('application.child.run', valid['application.child.run']),
        { code: 'ACCEPTED', payload: { ...running, child: other } },
      ).kind,
    ).toBe('invalid-response');
  });

  it('refuses a destruction that reports more sources than records', () => {
    expect(
      resultOf(
        request(
          'application.child.destroy',
          valid['application.child.destroy'],
        ),
        {
          code: 'OK',
          payload: { child: id, collected: [id], sources: 2 },
        },
      ).kind,
    ).toBe('invalid-response');
    expect(
      resultOf(
        request(
          'application.child.destroy',
          valid['application.child.destroy'],
        ),
        {
          code: 'OK',
          payload: { child: id, collected: [other], sources: 1 },
        },
      ).kind,
    ).toBe('invalid-response');
  });
});
