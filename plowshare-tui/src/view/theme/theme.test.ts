import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { stripVTControlCharacters as bare } from 'node:util';

import { afterEach, describe, expect, it } from 'vitest';

import { parse } from '../../logic/markdown.ts';
import { highlight } from '../highlight.ts';
import { colourCode, DEFAULT_LOOK, inkColour } from '../look.ts';
import { toTerminal } from '../scrollback.ts';
import { BUILTIN, PLOWSHARE } from './builtin.ts';
import { resolve } from './format.ts';
import type { ThemeFile } from './format.ts';
import { loadLibrary } from './loader.ts';
import { parseReplies, QUESTIONS } from './probe.ts';
import { luminance, mix, modeOf, systemTheme } from './system.ts';
import { theming } from './theming.ts';
import { rgb, TOKENS } from './tokens.ts';

/** A lookup over the built-ins and whatever the case adds. */
function library(
  ...extra: ThemeFile[]
): (name: string) => ThemeFile | undefined {
  const all = [...BUILTIN, ...extra];
  return (name) => all.find((theme) => theme.name === name);
}

describe('the plowshare theme', () => {
  it('gives every token a colour in both modes, with nothing to report', () => {
    for (const mode of ['dark', 'light'] as const) {
      const { palette, problems } = resolve('plowshare', mode, library());
      expect(problems).toEqual([]);
      expect(Object.keys(palette).sort()).toEqual([...TOKENS].sort());
    }
  });

  it('differs between dark and light', () => {
    expect(resolve('plowshare', 'light', library()).palette.accent).not.toEqual(
      resolve('plowshare', 'dark', library()).palette.accent,
    );
  });

  it("carries the trajectory's tokens", () => {
    for (const token of [
      'tool',
      'ok',
      'fail',
      'waiting',
      'reasoning',
      'selection',
      'actor1',
      'actor4',
      'badgeUser',
      'badgePlan',
      'timelineModel',
      'timelineTool',
    ]) {
      expect(TOKENS).toContain(token);
    }
  });

  it('keeps reasoning readable on the selection background, in both modes', () => {
    const dark = resolve('plowshare', 'dark', library()).palette;
    expect(dark.selection).toEqual(rgb('#26344d'));
    expect(dark.reasoning).toEqual(rgb('#8b9bb4'));
    const light = resolve('plowshare', 'light', library()).palette;
    expect(light.selection).toEqual(rgb('#e1e8f2'));
    expect(light.reasoning).toEqual(rgb('#5b6878'));
  });
});

describe('a theme file', () => {
  it('changes only what it names, over its base', () => {
    const dusk: ThemeFile = {
      name: 'dusk',
      base: 'plowshare',
      colors: { accent: '#ff0000' },
    };
    const { palette } = resolve('dusk', 'dark', library(dusk));
    const base = resolve('plowshare', 'dark', library()).palette;
    expect(palette.accent).toEqual(rgb('#ff0000'));
    expect(palette.mdHeading1).toEqual(base.mdHeading1);
  });

  it('reads vars, dark/light pairs, other tokens and every literal spelling', () => {
    const theme: ThemeFile = {
      name: 't',
      base: 'plowshare',
      vars: { brand: { dark: '#abc', light: '#123456' } },
      colors: {
        accent: 'brand',
        mdBullet: 'accent',
        border: 244,
        muted: 'ansi256(8)',
        person: 'ansi:brightCyan',
        panel: 'none',
      },
    };
    const dark = resolve('t', 'dark', library(theme)).palette;
    expect(dark.accent).toEqual(rgb('#aabbcc'));
    expect(dark.mdBullet).toEqual(rgb('#aabbcc'));
    expect(dark.border).toEqual({ kind: 'index', index: 244 });
    expect(dark.muted).toEqual({ kind: 'index', index: 8 });
    expect(dark.person).toEqual({ kind: 'ansi', slot: 14 });
    expect(dark.panel).toEqual({ kind: 'none' });
    expect(resolve('t', 'light', library(theme)).palette.accent).toEqual(
      rgb('#123456'),
    );
  });

  it('lets a token point at a var of the same name', () => {
    const theme: ThemeFile = {
      name: 't',
      base: 'plowshare',
      vars: { text: '#010203' },
      colors: { text: 'text' },
    };
    expect(resolve('t', 'dark', library(theme)).palette.text).toEqual(
      rgb('#010203'),
    );
  });

  it('reports a bad colour and keeps the base colour for it, rather than refusing the theme', () => {
    const theme: ThemeFile = {
      name: 't',
      base: 'plowshare',
      colors: {
        accent: '#zzz',
        mdCode: 'nowhere',
        sparkle: '#fff',
        border: '#00ff00',
      },
    };
    const { palette, problems } = resolve('t', 'dark', library(theme));
    const base = resolve('plowshare', 'dark', library()).palette;
    expect(palette.accent).toEqual(base.accent);
    expect(palette.mdCode).toEqual(base.mdCode);
    expect(palette.border).toEqual(rgb('#00ff00'));
    expect(problems.join('\n')).toContain('#zzz is not a colour');
    expect(problems.join('\n')).toContain('nothing called nowhere');
    expect(problems.join('\n')).toContain('no colour called sparkle');
  });

  it('reports a circle rather than recursing forever', () => {
    const theme: ThemeFile = {
      name: 't',
      base: 'plowshare',
      vars: { a: 'b', b: 'a' },
      colors: { accent: 'a' },
    };
    expect(resolve('t', 'dark', library(theme)).problems.join('\n')).toContain(
      'circle',
    );
  });

  it('refuses a base chain that loops', () => {
    const a: ThemeFile = { name: 'a', base: 'b' };
    const b: ThemeFile = { name: 'b', base: 'a' };
    expect(() => resolve('a', 'dark', library(a, b))).toThrow(/its own base/u);
  });
});

describe('colours reach a terminal as each kind asks', () => {
  it('writes rgb as 24-bit or the nearest 256 entry', () => {
    expect(colourCode(rgb('#ff0000'), 'truecolor', 38)).toBe('38;2;255;0;0');
    expect(colourCode(rgb('#ff0000'), '256', 38)).toBe('38;5;196');
  });

  it("writes the terminal's own colours as the sixteen codes", () => {
    expect(colourCode({ kind: 'ansi', slot: 6 }, 'truecolor', 38)).toBe('36');
    expect(colourCode({ kind: 'ansi', slot: 14 }, 'truecolor', 48)).toBe('106');
    expect(colourCode({ kind: 'none' }, 'truecolor', 38)).toBe('39');
  });

  it('spells colours the way Ink takes them', () => {
    expect(inkColour(rgb('#123456'))).toBe('#123456');
    expect(inkColour({ kind: 'index', index: 99 })).toBe('ansi256(99)');
    expect(inkColour({ kind: 'ansi', slot: 9 })).toBe('redBright');
    expect(inkColour({ kind: 'none' })).toBeUndefined();
  });

  it('draws markdown in whatever theme it is handed', () => {
    const { palette } = resolve(
      't',
      'dark',
      library({ name: 't', base: 'plowshare', colors: { mdCode: '#010203' } }),
    );
    const rendered = toTerminal(parse('a `b`'), { ...DEFAULT_LOOK, palette });
    expect(rendered).toContain('[38;2;1;2;3mb');
  });
});

describe("the terminal's answers", () => {
  it('reads foreground, background and palette replies in either terminator', () => {
    const replies =
      ']10;rgb:ffff/ffff/ffff' +
      ']11;rgb:00/2b/36\\' +
      ']4;4;rgb:2626/8b8b/d2d2' +
      '[?62;22c';
    const probe = parseReplies(replies);
    expect(probe.foreground).toBe('#ffffff');
    expect(probe.background).toBe('#002b36');
    expect(probe.palette[4]).toBe('#268bd2');
    expect(probe.palette[0]).toBeUndefined();
  });

  it('asks the one question every terminal answers last', () => {
    expect(QUESTIONS.endsWith('[c')).toBe(true);
    expect(QUESTIONS).toContain(']11;?');
  });

  it('decides light or dark from the background alone', () => {
    expect(modeOf({ background: '#fdf6e3', palette: [] })).toBe('light');
    expect(modeOf({ background: '#002b36', palette: [] })).toBe('dark');
    expect(modeOf(undefined)).toBeUndefined();
  });
});

describe('the system theme', () => {
  const solarized = {
    foreground: '#839496',
    background: '#002b36',
    palette: [
      '#073642',
      '#dc322f',
      '#859900',
      '#b58900',
      '#268bd2',
      '#d33682',
      '#2aa198',
      '#eee8d5',
    ],
  };

  it("takes its accents from the terminal's own palette", () => {
    const theme = systemTheme(solarized);
    expect(theme?.colors?.['accent']).toBe('#268bd2');
    expect(theme?.colors?.['trouble']).toBe('#dc322f');
  });

  it('computes muted text between the real foreground and background, so it is never the background', () => {
    const { palette, problems } = resolve(
      'system',
      'dark',
      library(systemTheme(solarized) as ThemeFile),
    );
    expect(problems).toEqual([]);
    const muted = palette.muted.kind === 'rgb' ? palette.muted.hex : '';
    expect(muted).not.toBe('#002b36');
    expect(luminance(muted)).toBeGreaterThan(luminance('#002b36'));
    expect(muted).toBe(mix('#839496', '#002b36', 0.4));
  });

  it('names a slot the terminal did not report, for the terminal to fill', () => {
    expect(
      systemTheme({ background: '#000000', palette: [] })?.colors?.['bot'],
    ).toBe('ansi:green');
  });

  it('is not available when the terminal did not say its background', () => {
    expect(systemTheme({ palette: [] })).toBeUndefined();
    expect(systemTheme(undefined)).toBeUndefined();
  });
});

describe('highlight.js, in theme colours', () => {
  const palette = DEFAULT_LOOK.palette;

  it('maps scopes to syntax tokens', () => {
    const runs = highlight('const x = "s" // note', 'ts', palette) ?? [];
    const colourOf = (text: string) =>
      runs.find((run) => run.text === text)?.style.fg;
    expect(colourOf('const')).toEqual(palette.syntaxKeyword);
    expect(colourOf('"s"')).toEqual(palette.syntaxString);
    expect(runs.find((run) => run.text.startsWith('//'))?.style.italic).toBe(
      true,
    );
  });

  it('gives back the code exactly, entities and all', () => {
    const code = 'if (a < b && c > "d") { return \'&amp;\' }';
    expect(
      (highlight(code, 'javascript', palette) ?? [])
        .map((run) => run.text)
        .join(''),
    ).toBe(code);
  });

  it('knows a language by its aliases', () => {
    expect(highlight('echo hi', 'sh', palette)).toBeDefined();
    expect(highlight('x', 'py', palette)).toBeDefined();
  });

  it('draws diffs in the diff colours', () => {
    const runs = highlight('@@ -1 +1 @@\n-a\n+b', 'diff', palette) ?? [];
    expect(runs.find((run) => run.text === '+b')?.style.fg).toEqual(
      palette.diffAdded,
    );
    expect(runs.find((run) => run.text.startsWith('@@'))?.style.fg).toEqual(
      palette.diffHunk,
    );
  });

  it('guesses nothing about a language it does not know', () => {
    expect(highlight('whatever', 'klingon', palette)).toBeUndefined();
    expect(highlight('whatever', '', palette)).toBeUndefined();
  });

  it('keeps every character of a highlighted fence when rendered', () => {
    const rendered = toTerminal(
      parse('```python\ndef f(x):\n    return x  # y\n```'),
      true,
    );
    expect(bare(rendered).split('\n').slice(1, -1)).toEqual([
      'def f(x):',
      '    return x  # y',
    ]);
  });
});

describe('themes on disk, and choosing one', () => {
  let root = '';
  afterEach(() => {
    rmSync(root, { recursive: true, force: true });
  });

  const setUp = (): {
    home: string;
    cwd: string;
    env: Record<string, string>;
  } => {
    root = mkdtempSync(join(tmpdir(), 'plowshare-theme-'));
    const home = join(root, 'home');
    const cwd = join(root, 'work');
    mkdirSync(join(home, '.config', 'plowshare', 'themes'), {
      recursive: true,
    });
    mkdirSync(join(cwd, '.plowshare', 'themes'), { recursive: true });
    return { home, cwd, env: {} };
  };

  it('names a theme by its file, lets a project override a global one, and reports a broken file', () => {
    const { home, cwd } = setUp();
    const global = join(home, '.config', 'plowshare', 'themes');
    const project = join(cwd, '.plowshare', 'themes');
    writeFileSync(
      join(global, 'dusk.json'),
      JSON.stringify({
        name: 'ignored',
        base: 'plowshare',
        colors: { accent: '#111111' },
      }),
    );
    writeFileSync(
      join(project, 'dusk.json'),
      JSON.stringify({ base: 'plowshare', colors: { accent: '#222222' } }),
    );
    writeFileSync(join(global, 'broken.json'), '{ nope');
    const lib = loadLibrary([global, project]);
    expect(lib.names).toContain('dusk');
    expect(lib.names).not.toContain('ignored');
    expect(lib.source('dusk')).toBe(join(project, 'dusk.json'));
    expect(lib.problems.join('\n')).toContain('broken.json');
    expect(resolve('dusk', 'dark', lib.get).palette.accent).toEqual(
      rgb('#222222'),
    );
  });

  it('starts in auto, follows the background, and saves what /theme chooses', () => {
    const { home, cwd, env } = setUp();
    writeFileSync(
      join(home, '.config', 'plowshare', 'themes', 'dusk.json'),
      JSON.stringify({ base: 'plowshare', colors: { accent: '#333333' } }),
    );
    const themes = theming({
      env,
      home,
      cwd,
      depth: 'truecolor',
      probe: { background: '#ffffff', palette: [] },
    });
    expect(themes.current().mode).toBe('light');
    expect(themes.current().name).toBe('plowshare');

    let heard = '';
    themes.onChange((look) => {
      heard = look.name;
    });
    expect(themes.choose('dusk').ok).toBe(true);
    expect(heard).toBe('dusk');
    expect(themes.current().palette.accent).toEqual(rgb('#333333'));
    const saved = JSON.parse(
      readFileSync(join(home, '.config', 'plowshare', 'tui.json'), 'utf8'),
    ) as { theme: string };
    expect(saved.theme).toBe('dusk');

    // The next start opens in it.
    expect(theming({ env, home, cwd, depth: 'truecolor' }).current().name).toBe(
      'dusk',
    );
  });

  it('refuses a name it does not have, and changes nothing', () => {
    const { home, cwd, env } = setUp();
    const themes = theming({ env, home, cwd, depth: 'truecolor' });
    const chose = themes.choose('nope');
    expect(chose.ok).toBe(false);
    expect(chose.lines.join('')).toContain('no theme called nope');
    expect(themes.current().name).toBe('plowshare');
  });

  it('lets PLOWSHARE_THEME win over what was saved, and keeps light and dark working', () => {
    const { home, cwd } = setUp();
    writeFileSync(
      join(home, '.config', 'plowshare', 'tui.json'),
      JSON.stringify({ theme: 'dark' }),
    );
    const themes = theming({
      env: { PLOWSHARE_THEME: 'light' },
      home,
      cwd,
      depth: '256',
    });
    expect(themes.current().mode).toBe('light');
  });

  it('offers system only when the terminal answered, and falls back with a sentence when it did not', () => {
    const { home, cwd, env } = setUp();
    const answered = theming({
      env,
      home,
      cwd,
      depth: 'truecolor',
      probe: { background: '#000000', palette: [] },
    });
    expect(answered.describe().join('\n')).toContain('system');
    const silent = theming({ env, home, cwd, depth: 'truecolor' });
    expect(silent.choose('system').lines.join('\n')).toContain(
      'did not say what its colours are',
    );
    expect(silent.current().name).toBe('plowshare');
  });

  it('is described by the built-in it is written in', () => {
    expect(PLOWSHARE.name).toBe('plowshare');
  });
});
