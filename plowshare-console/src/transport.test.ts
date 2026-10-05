import { describe, it, expect, vi } from 'vitest';
import { socketTransport, decodeSetting } from './transport';
import type { EventStream, FrameOutcome } from './events';
function fixture(reply: FrameOutcome) {
  const ask = vi.fn(async () => reply);
  const stream: EventStream = {
    ask,
    status: () => ({ state: 'open', attempt: 0, retryInMs: null }),
    close: () => {},
  };
  return { transport: socketTransport(() => stream), ask };
}
describe('checked console transport', () => {
  it('routes reads through the existing socket and projects known fields', async () => {
    const { transport, ask } = fixture({
      code: 'OK',
      payload: [
        {
          name: 'p',
          workspace: '/workspace',
          machine: null,
          members: [],
          lent: [],
          exclusions: [],
          future: { private: true },
        },
      ],
    });
    expect(await transport.get('/v1/projects')).toEqual([
      { name: 'p', workspace: '/workspace', lent: [], exclusions: [] },
    ]);
    expect(ask).toHaveBeenCalledExactlyOnceWith('project.list', {});
  });
  it('rejects invalid requests before any mutation is sent', async () => {
    const { transport, ask } = fixture({ code: 'OK' });
    await expect(
      transport.post('/v1/jobs/job/limits', { maxTurns: -1 }),
    ).rejects.toThrow();
    expect(ask).not.toHaveBeenCalled();
  });
  it('does not replay a mutation with an unreadable response', async () => {
    const { transport, ask } = fixture({
      code: 'OK',
      payload: { id: 'job', state: 'RUNNING' },
    });
    await expect(transport.post('/v1/jobs/job/cancel')).rejects.toThrow();
    expect(ask).toHaveBeenCalledExactlyOnceWith('job.cancel', { job: 'job' });
  });
  it('keeps refusal detail without admitting its raw payload', async () => {
    const { transport, ask } = fixture({
      code: 'CONFLICT',
      said: 'Already ended',
      payload: { secret: true },
    });
    await expect(transport.post('/v1/jobs/job/cancel')).rejects.toMatchObject({
      message: 'Already ended',
      status: 409,
    });
    expect(ask).toHaveBeenCalledOnce();
  });
  it('checks the explicit configuration HTTP DTO', () => {
    expect(
      decodeSetting({
        key: 'key',
        value: null,
        pinned: false,
        updatedAt: null,
        updatedBy: null,
        future: true,
      }),
    ).toEqual({
      key: 'key',
      value: null,
      pinned: false,
      updatedAt: null,
      updatedBy: null,
    });
    expect(() =>
      decodeSetting({ key: 'key', value: { bad: true }, pinned: false }),
    ).toThrow();
  });
});
