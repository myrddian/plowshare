import { describe, expect, it, vi } from 'vitest';

import { parse } from '../logic/markdown.ts';
import { entered } from '../logic/screen.ts';
import { recording } from './surface.ts';

describe('recording', () => {
  it('keeps what was shown, in order', () => {
    const surface = recording();
    surface.show(entered(1, 'person', parse('one')));
    surface.show(entered(2, 'bot', parse('two')));
    expect(surface.shown.map((entry) => entry.voice)).toEqual([
      'person',
      'bot',
    ]);
  });

  it('keeps every working state including the clearing one', () => {
    const surface = recording();
    surface.working({ job: 'job_1', since: 0 });
    surface.working(undefined);
    // The clearing call is the one worth keeping: a run that ended without
    // it leaves a spinner turning forever, and a double that dropped
    // `undefined` could not tell the two apart.
    expect(surface.states).toEqual([{ job: 'job_1', since: 0 }, undefined]);
  });

  it('answers an ask made before the line arrives', async () => {
    const surface = recording();
    const asking = surface.asked();
    surface.answer('hello');
    await expect(asking).resolves.toBe('hello');
  });

  it('answers an ask made after the line arrives', async () => {
    const surface = recording();
    surface.answer('typed ahead');
    await expect(surface.asked()).resolves.toBe('typed ahead');
  });

  it('answers undefined once closed, so no loop waits forever', async () => {
    const surface = recording();
    const asking = surface.asked();
    surface.close();
    await expect(asking).resolves.toBeUndefined();
  });

  it('answers undefined to an ask made after closing', async () => {
    const surface = recording();
    surface.close();
    await expect(surface.asked()).resolves.toBeUndefined();
  });

  it('calls the interrupt listener rather than ending', () => {
    const surface = recording();
    const listener = vi.fn();
    surface.onInterrupt(listener);
    surface.press();
    expect(listener).toHaveBeenCalledOnce();
  });

  it('ends the input on a press with no listener, which is the old way out', async () => {
    const surface = recording();
    const asking = surface.asked();
    surface.press();
    await expect(asking).resolves.toBeUndefined();
  });
});
