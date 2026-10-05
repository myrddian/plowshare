import { describe, expect, it } from 'vitest';

import {
  ASK,
  OPEN,
  Unreadable,
  parseEnvironment,
  withCaps,
  withLocalMode,
} from './environment.ts';

describe('changing the local mode, which is what /always writes', () => {
  it('writes a whole file where there was none', () => {
    const written = withLocalMode(undefined, OPEN);

    expect(written).toBe('local:\n  mode: open\n');
    expect(parseEnvironment(written).local?.mode).toBe(OPEN);
  });

  it('changes the mode line alone and keeps every other line, comments included', () => {
    const before =
      '# the game\nlocal:\n  mode: ask   # asked at first\n  timeout: 10m\nserver:\n  mode: off\n';

    const written = withLocalMode(before, OPEN);

    expect(written).toBe(
      '# the game\nlocal:\n  mode: open\n  timeout: 10m\nserver:\n  mode: off\n',
    );
    expect(parseEnvironment(written).server?.mode).toBe('off');
  });

  it('adds the mode to a local section that set none', () => {
    const written = withLocalMode('local:\n  shells: false\n', ASK);

    expect(written).toBe('local:\n  mode: ask\n  shells: false\n');
  });

  it('adds a local section to a file that had only the server one', () => {
    const written = withLocalMode('server:\n  mode: off\n', OPEN);

    expect(parseEnvironment(written)).toMatchObject({
      local: { mode: OPEN },
      server: { mode: 'off' },
    });
  });

  it('refuses to rewrite a file it cannot read, rather than replace what someone wrote', () => {
    expect(() => withLocalMode('local:\n\tmode: ask\n', OPEN)).toThrow(
      Unreadable,
    );
  });
});

describe('withCaps, which /cap writes with', () => {
  it('writes a caps section into a file that has none', () => {
    expect(withCaps(undefined, 'steps', 40)).toBe('caps:\n  steps: 40\n');
    expect(withCaps('local:\n  mode: ask\n', 'auto-continue', 3)).toBe(
      'local:\n  mode: ask\ncaps:\n  auto-continue: 3\n',
    );
  });

  it('changes the one line and keeps everything else', () => {
    const before =
      '# mine\ncaps:\n  steps: 40 # kept\n  budget: 600\nlocal:\n  mode: ask\n';
    expect(withCaps(before, 'steps', 20)).toBe(
      '# mine\ncaps:\n  steps: 20\n  budget: 600\nlocal:\n  mode: ask\n',
    );
    expect(withCaps(before, 'auto-continue', 3)).toBe(
      '# mine\ncaps:\n  auto-continue: 3\n  steps: 40 # kept\n  budget: 600\nlocal:\n  mode: ask\n',
    );
  });

  it('refuses a file that does not parse rather than rewriting it', () => {
    expect(() => withCaps('local:\n  mode: yes\n', 'steps', 40)).toThrow(
      Unreadable,
    );
  });

  it('refuses a value the grammar would refuse', () => {
    expect(() => withCaps(undefined, 'steps', 0)).toThrow(Unreadable);
  });

  it('writes the time cap and the failed-checks limit as the other settings are written', () => {
    expect(withCaps(undefined, 'time', 90)).toBe('caps:\n  time: 90\n');
    expect(withCaps('caps:\n  time: 90\n', 'failed-checks', 3)).toBe(
      'caps:\n  failed-checks: 3\n  time: 90\n',
    );
    expect(
      parseEnvironment('caps:\n  failed-checks: 3\n  time: 90\n').caps,
    ).toEqual({ time: 90, failedChecks: 3 });
    expect(() => withCaps(undefined, 'time', 0)).toThrow(Unreadable);
    expect(() => withCaps(undefined, 'failed-checks', 101)).toThrow(Unreadable);
  });
});

it('auto-increase has a strict boolean grammar and preserves other policy when edited', () => {
  const before = 'local:\n  mode: ask\ncaps:\n  budget: 20\n';
  const enabled = withCaps(before, 'auto-increase', 1);
  expect(parseEnvironment(enabled).caps).toEqual({
    budget: 20,
    autoIncrease: true,
  });
  expect(
    parseEnvironment(withCaps(enabled, 'auto-increase', 0)).caps?.autoIncrease,
  ).toBe(false);
  for (const bad of ['yes', '1', 'null', 'True'])
    expect(() => parseEnvironment(`caps:\n  auto-increase: ${bad}\n`)).toThrow(
      Unreadable,
    );
  expect(() => withCaps(enabled, 'auto-increase', 2)).toThrow();
});
