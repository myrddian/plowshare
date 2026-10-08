import { describe, it, expect } from 'vitest';
import { decodeRequest } from './schema.ts';
import { request, resultOf } from './direct.ts';
import { checkedTransport } from './transport.ts';
const id = '11111111-1111-1111-1111-111111111111';
const revision = '22222222-2222-2222-2222-222222222222';
const payload = {
  project: 'app',
  requestId: id,
  expectedRevision: null,
  destination: { store: 'applications', path: 'app' },
  writableAreas: [],
  files: [{ path: 'plowshare.json', text: '{"version":1}' }],
};
describe('Application deployments', () => {
  it('validates complete packages and retains null first-install expectations', () => {
    expect(decodeRequest('application.deploy', payload).payload).toEqual(
      payload,
    );
    for (const files of [
      [],
      [{ path: '../escape', text: 'x' }],
      [...payload.files, { path: '.plowshare/agents/worker.md', text: 'x' }],
      [
        { path: 'plowshare.json', text: 'x' },
        { path: 'Plowshare.json', text: 'x' },
      ],
      [
        { path: 'plowshare.json', text: 'x' },
        { path: 'a', text: 'x' },
        { path: 'a/b', text: 'x' },
      ],
      [{ path: 'plowshare.json', text: 'x'.repeat(65537) }],
      [
        { path: 'plowshare.json', text: 'x' },
        { path: 'a', text: '😀'.repeat(16384) },
        { path: 'b', text: '😀'.repeat(16384) },
      ],
    ])
      expect(() =>
        decodeRequest('application.deploy', { ...payload, files }),
      ).toThrow();
    expect(() =>
      decodeRequest('application.deploy', {
        ...payload,
        expectedRevision: undefined,
      }),
    ).toThrow();
  });
  it('rejects foreign receipts and never replays an uncertain deployment', async () => {
    const asked = request('application.deploy', payload);
    const receipt = {
      requestId: id,
      project: 'app',
      release: { revision, digest: 'a'.repeat(64), fileCount: 1 },
    };
    expect(resultOf(asked, { code: 'OK', payload: receipt }).kind).toBe(
      'completed',
    );
    expect(
      resultOf(asked, { code: 'OK', payload: { ...receipt, project: 'other' } })
        .kind,
    ).toBe('invalid-response');
    let calls = 0;
    const transport = checkedTransport({
      ask: async () => {
        calls++;
        throw new Error('lost reply');
      },
    });
    await expect(transport.ask('application.deploy', payload)).rejects.toThrow(
      'lost reply',
    );
    expect(calls).toBe(1);
  });
});
