import { describe, expect, it } from 'vitest';
import { JobLifecycle } from './lifecycle.ts';

describe('shared job lifetime', () => {
  it('routes simultaneous submissions by server handles, including early events in reverse acceptance order', () => {
    const jobs = new JobLifecycle();
    const first = jobs.begin('first'),
      second = jobs.begin('second');
    jobs.push({ job: 'a', kind: 'started' });
    jobs.push({ job: 'b', part: 'ANSWER', text: 'second' });
    jobs.push({ job: 'foreign', kind: 'ended' });
    jobs.push({ job: 'a', kind: 'ended' });
    expect(jobs.accept(second, 'b').events).toEqual([
      { job: 'b', part: 'ANSWER', text: 'second' },
    ]);
    const accepted = jobs.accept(first, 'a');
    expect(accepted.events.map((row) => row['kind'])).toEqual([
      'started',
      'ended',
    ]);
    expect(jobs.push({ job: 'a', kind: 'alive' })?.ticket.conversation).toBe(
      'first',
    );
    expect(jobs.push({ job: 'foreign', kind: 'alive' })).toBeUndefined();
  });

  it('bounds early buffers and makes loss an explicit reconciliation obligation', () => {
    const jobs = new JobLifecycle({ handles: 1, events: 2 });
    const ticket = jobs.begin();
    jobs.push({ job: 'foreign', kind: 'started' });
    jobs.push({ job: 'ours', kind: 'started' });
    jobs.push({ job: 'ours', kind: 'alive' });
    jobs.push({ job: 'ours', kind: 'ended' });
    const accepted = jobs.accept(ticket, 'ours');
    expect(accepted.needsReconcile).toBe(true);
    expect(accepted.events.map((event) => event['kind'])).toEqual([
      'alive',
      'ended',
    ]);
  });

  it('never finishes on cancellation acknowledgement or on an ended push alone', () => {
    const jobs = new JobLifecycle(),
      ticket = jobs.begin('chat');
    jobs.accept(ticket, 'job');
    jobs.cancelling(ticket);
    expect(
      jobs.observe(ticket, {
        code: 'OK',
        payload: { id: 'job', state: 'RUNNING' },
      })?.state,
    ).toBe('cancelling');
    jobs.push({ job: 'job', kind: 'ended' });
    expect(jobs.snapshot(ticket)?.state).toBe('awaiting-outcome');
    expect(
      jobs.observe(ticket, {
        code: 'OK',
        payload: { id: 'job', state: 'DONE', outcome: null },
      })?.state,
    ).toBe('awaiting-outcome');
    expect(
      jobs.observe(ticket, {
        code: 'OK',
        payload: {
          id: 'job',
          state: 'DONE',
          outcome: { ending: 'CANCELLED', answered: false },
        },
      })?.state,
    ).toBe('finished');
  });

  it('retains known handles across disconnect, rejects stale replies and preserves cancellation intent', () => {
    const jobs = new JobLifecycle(),
      ticket = jobs.begin('chat'),
      epoch = jobs.generation;
    jobs.accept(ticket, 'job');
    jobs.cancelling(ticket);
    jobs.reset(true);
    expect(jobs.snapshot(ticket)).toMatchObject({
      handle: 'job',
      state: 'unknown',
      needsReconcile: true,
    });
    expect(jobs.push({ job: 'job', kind: 'ended' }, epoch)).toBeUndefined();
    expect(
      jobs.observe(
        ticket,
        {
          code: 'OK',
          payload: {
            id: 'job',
            state: 'DONE',
            outcome: { ending: 'ANSWERED' },
          },
        },
        epoch,
      )?.state,
    ).toBe('unknown');
    expect(
      jobs.observe(ticket, {
        code: 'OK',
        payload: { id: 'job', state: 'RUNNING' },
      })?.state,
    ).toBe('cancelling');
  });

  it('leaves a disconnected submission without a handle uncertain, not eligible for acceptance on a new connection', () => {
    const jobs = new JobLifecycle(),
      ticket = jobs.begin();
    jobs.push({ job: 'maybe', kind: 'started' });
    jobs.reset(true);
    expect(jobs.snapshot(ticket)).toMatchObject({
      state: 'unknown',
      needsReconcile: true,
    });
    expect(jobs.snapshot(ticket)?.handle).toBeUndefined();
    expect(() => jobs.accept(ticket, 'maybe')).toThrow(/obsolete/);
  });

  it('distinguishes a refused submission from transport uncertainty and drops obsolete buffers', () => {
    const jobs = new JobLifecycle(),
      refused = jobs.begin();
    jobs.push({ job: 'old', kind: 'started' });
    jobs.failed(refused, true);
    expect(jobs.snapshot(refused)?.state).toBe('refused');
    const newTicket = jobs.begin();
    expect(jobs.accept(newTicket, 'old').events).toEqual([]);
    jobs.failed(newTicket, true);
    expect(jobs.snapshot(newTicket)?.state).toBe('running');
    const uncertain = jobs.begin();
    jobs.failed(uncertain, false);
    expect(jobs.snapshot(uncertain)?.state).toBe('unknown');
  });

  it('requires matching status identity and a readable durable outcome before resolving uncertainty', () => {
    const jobs = new JobLifecycle(),
      ticket = jobs.begin();
    jobs.accept(ticket, 'job');
    jobs.reset(true);
    expect(
      jobs.observe(ticket, { code: 'NOT_FOUND', said: 'Job expired.' })?.state,
    ).toBe('unknown');
    expect(jobs.observe(ticket, { code: 'OK', payload: {} })?.state).toBe(
      'unknown',
    );
    expect(
      jobs.observe(ticket, {
        code: 'OK',
        payload: { id: 'foreign', outcome: { ending: 'ANSWERED' } },
      })?.state,
    ).toBe('unknown');
    expect(
      jobs.observe(ticket, {
        code: 'OK',
        payload: {
          id: 'job',
          state: 'DONE',
          outcome: { ending: 'ANSWERED', answered: true },
        },
      })?.state,
    ).toBe('finished');
  });

  it('detaches an observer without issuing I/O, accepting forged tickets or letting finished jobs restart', () => {
    const jobs = new JobLifecycle(),
      ticket = jobs.begin();
    jobs.accept(ticket, 'job');
    expect(jobs.snapshot({ ...ticket })).toBeUndefined();
    jobs.observe(ticket, {
      code: 'OK',
      payload: {
        id: 'job',
        state: 'DONE',
        outcome: { ending: 'AWAITING', answered: false },
      },
    });
    jobs.cancelling(ticket);
    expect(jobs.snapshot(ticket)?.state).toBe('finished');
    jobs.forget(ticket);
    expect(jobs.snapshot(ticket)).toBeUndefined();
    expect(jobs.push({ job: 'job', kind: 'alive' })).toBeUndefined();
  });

  it('tracks an approval continuation as a separate job in the same conversation', () => {
    const jobs = new JobLifecycle(),
      previous = jobs.begin('chat');
    jobs.accept(previous, 'old');
    jobs.observe(previous, {
      code: 'OK',
      payload: {
        id: 'old',
        state: 'DONE',
        outcome: { ending: 'AWAITING', answered: false },
      },
    });
    const continuation = jobs.begin('chat');
    jobs.push({ job: 'new', part: 'ANSWER', text: 'continued' });
    expect(jobs.accept(continuation, 'new').events).toHaveLength(1);
    expect(jobs.snapshot(previous)?.state).toBe('finished');
    expect(jobs.snapshot(continuation)?.state).toBe('running');
  });
});

describe('job response uncertainty', () => {
  it('never reconciles a missing identity or unreadable answered flag as a finished job', () => {
    const jobs = new JobLifecycle(),
      ticket = jobs.begin();
    jobs.accept(ticket, 'job');
    jobs.reset(true);
    for (const payload of [
      { state: 'DONE', outcome: { ending: 'ANSWERED', answered: true } },
      { id: 'job', state: 'DONE', outcome: { ending: 'ANSWERED' } },
      {
        id: 'job',
        state: 'DONE',
        outcome: { ending: 'ANSWERED', answered: false },
      },
    ]) {
      expect(jobs.observe(ticket, { code: 'OK', payload })).toMatchObject({
        state: 'unknown',
        needsReconcile: true,
      });
    }
    expect(
      jobs.observe(ticket, {
        code: 'OK',
        payload: {
          id: 'job',
          state: 'DONE',
          outcome: { ending: 'FUTURE_ENDING', answered: false, detail: 'kept' },
        },
      }),
    ).toMatchObject({
      state: 'finished',
      needsReconcile: false,
      outcome: { ending: 'FUTURE_ENDING', detail: 'kept' },
    });
  });
});
