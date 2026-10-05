import { describe, expect, it } from 'vitest';

import type { Standing } from '../../logic/wording.ts';
import { BAR_CELLS, bar, fit, widthOf } from './meter.ts';

describe('the bar', () => {
  it('is an empty track at nothing and a full one at everything', () => {
    expect(bar(0)).toEqual({ fill: '', track: '░'.repeat(BAR_CELLS) });
    expect(bar(1)).toEqual({ fill: '█'.repeat(BAR_CELLS), track: '' });
    expect(bar(1.4)).toEqual(bar(1));
    expect(bar(-1)).toEqual(bar(0));
  });

  it('moves by eighths at its edge, and always spans every cell', () => {
    expect(bar(0.14)).toEqual({ fill: '█▍', track: '░'.repeat(8) });
    expect(bar(0.05)).toEqual({ fill: '▌', track: '░'.repeat(9) });
    for (const filled of [0.01, 0.33, 0.5, 0.77, 0.99]) {
      const { fill, track } = bar(filled);
      expect(fill.length + track.length).toBe(BAR_CELLS);
    }
  });
});

describe('what of the status line fits', () => {
  const standing: Standing = {
    triplet: 'coder:127.0.0.1:8080:qwen3-coder',
    load: '16.2K/120K 14%',
    filled: 0.14,
    pace: [
      { kind: 'tools', text: '3' },
      { kind: 'thinking', text: 'think 412' },
      { kind: 'responding', text: 'out 1.2K' },
      { kind: 'waiting', text: 'ttft 1.8s' },
      { kind: 'speed', text: '38 tok/s' },
    ],
  };
  const whole = widthOf(
    { triplet: standing.triplet, bar: true, pace: standing.pace ?? [] },
    standing.load,
  );

  it('shows everything when there is room', () => {
    expect(whole).toBe(
      (
        'coder:127.0.0.1:8080:qwen3-coder │ ▕█▍░░░░░░░░▏ 16.2K/120K 14%' +
        ' │ ⚙ 3  think 412  out 1.2K  ttft 1.8s  38 tok/s'
      ).length,
    );
    expect(fit(standing, whole)).toEqual({
      triplet: standing.triplet,
      bar: true,
      pace: standing.pace,
    });
  });

  it('drops how long and how fast first, then the rest of the pace, then the bar', () => {
    const brief = fit(standing, whole - 1);
    expect(brief.pace.map((part) => part.kind)).toEqual([
      'tools',
      'thinking',
      'responding',
    ]);
    expect(brief.bar).toBe(true);

    const bare = fit(standing, widthOf({ ...brief, pace: [] }, standing.load));
    expect(bare).toEqual({ triplet: standing.triplet, bar: true, pace: [] });

    const barless = fit(
      standing,
      widthOf({ ...bare, bar: false }, standing.load),
    );
    expect(barless).toEqual({
      triplet: standing.triplet,
      bar: false,
      pace: [],
    });
  });

  it('keeps the load and cuts the triplet from its model end last', () => {
    const narrow = fit(standing, 30);
    expect(narrow.triplet).toBe('coder:127.0.…');
    expect(widthOf(narrow, standing.load)).toBe(30);
  });

  it('has no bar to drop when no window is known', () => {
    const unsized: Standing = {
      triplet: 'coder:127.0.0.1:8080',
      load: '16.2K',
    };
    expect(fit(unsized, 80)).toEqual({
      triplet: 'coder:127.0.0.1:8080',
      bar: false,
      pace: [],
    });
  });
});

describe('fit with a sync notice', () => {
  it('never drops the notice, and counts it in the width', () => {
    const standing = {
      triplet: 'enzo · server · a-very-long-model-name',
      load: '10%',
      sync: '⚠ 2 sync conflicts',
    };
    const fitted = fit(standing, 40);
    expect(
      widthOf(fitted, standing.load) + ' · '.length + standing.sync.length,
    ).toBeLessThanOrEqual(40);
  });
});
