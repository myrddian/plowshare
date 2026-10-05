import { ANSI_NAMES } from './tokens.ts';
import type { Mode } from './tokens.ts';
import type { ThemeFile } from './format.ts';

/**
 * The `system` theme: a theme built from what the terminal says its colours are.
 *
 * <h2>Why this exists, and why it is not the sixteen named colours</h2>
 *
 * <p>Drawing with `ansi:cyan` and friends follows the terminal's scheme — and
 * inherits its worst decisions, which is how Solarized made this client's grey
 * text disappear. Asking the terminal for the actual values (`probe.ts`) gives
 * both: the accents are the scheme's own, and the greys, the panel and the
 * borders are <i>computed</i> between its real foreground and background, so
 * they are legible against that background by construction.
 *
 * <p>Pure: the answers come in as a {@link Probe}, a theme file goes out.
 */

/** What a terminal answered. Every field is what it said, or absent if it did not. */
export interface Probe {
  readonly foreground?: string;
  readonly background?: string;
  /** The sixteen palette slots, by index. */
  readonly palette: readonly (string | undefined)[];
}

function channels(hex: string): [number, number, number] {
  const value = Number.parseInt(hex.slice(1), 16);
  return [(value >> 16) & 255, (value >> 8) & 255, value & 255];
}

const hexOf = (red: number, green: number, blue: number): string =>
  `#${[red, green, blue]
    .map((channel) => Math.round(channel).toString(16).padStart(2, '0'))
    .join('')}`;

/** `from` moved `amount` of the way to `to`. */
export function mix(from: string, to: string, amount: number): string {
  const [r1, g1, b1] = channels(from);
  const [r2, g2, b2] = channels(to);
  return hexOf(
    r1 + (r2 - r1) * amount,
    g1 + (g2 - g1) * amount,
    b1 + (b2 - b1) * amount,
  );
}

/** Relative luminance, 0 for black and 1 for white. */
export function luminance(hex: string): number {
  const [red, green, blue] = channels(hex).map((channel) => {
    const unit = channel / 255;
    return unit <= 0.03928 ? unit / 12.92 : ((unit + 0.055) / 1.055) ** 2.4;
  }) as [number, number, number];
  return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
}

/** Whether a background is light or dark, when the terminal said what it is. */
export function modeOf(probe: Probe | undefined): Mode | undefined {
  const background = probe?.background;
  return background === undefined
    ? undefined
    : luminance(background) > 0.5
      ? 'light'
      : 'dark';
}

/**
 * The theme, or `undefined` when the terminal did not say what its background
 * is — without it nothing can be computed, and `main.ts` falls back to
 * `plowshare`.
 */
export function systemTheme(probe: Probe | undefined): ThemeFile | undefined {
  const background = probe?.background;
  if (probe === undefined || background === undefined) {
    return undefined;
  }
  const light = luminance(background) > 0.5;
  const foreground = probe.foreground ?? (light ? '#000000' : '#ffffff');
  // A slot the terminal did not report is still one it can draw: name it, and
  // let the terminal fill it in.
  const slot = (index: number): string =>
    probe.palette[index] ?? `ansi:${ANSI_NAMES[index] ?? 'white'}`;
  const [red, green, yellow, blue, magenta, cyan] = [1, 2, 3, 4, 5, 6].map(
    slot,
  ) as [string, string, string, string, string, string];
  const muted = mix(foreground, background, 0.4);
  const border = mix(foreground, background, 0.62);
  return {
    name: 'system',
    colors: {
      text: 'none',
      muted,
      accent: blue,
      border,
      panel: mix(background, foreground, light ? 0.06 : 0.08),
      person: cyan,
      bot: green,
      trouble: red,
      busy: yellow,
      success: green,
      warning: yellow,
      wordmarkFaint: mix(foreground, background, 0.5),
      wordmarkBright: foreground,
      mdHeading1: magenta,
      mdHeading2: blue,
      mdHeading3: cyan,
      mdLink: cyan,
      mdLinkUrl: muted,
      mdCode: yellow,
      mdCodeBlockBorder: border,
      mdCodeBlockLabel: muted,
      mdQuote: magenta,
      mdRule: border,
      mdBullet: blue,
      mdTableBorder: border,
      mdTableHeader: blue,
      syntaxComment: muted,
      syntaxKeyword: magenta,
      syntaxFunction: blue,
      syntaxVariable: 'none',
      syntaxString: green,
      syntaxNumber: yellow,
      syntaxType: cyan,
      syntaxOperator: cyan,
      syntaxPunctuation: muted,
      syntaxMeta: muted,
      diffAdded: green,
      diffRemoved: red,
      diffHunk: blue,
      tool: blue,
      ok: green,
      fail: red,
      waiting: yellow,
      reasoning: muted,
      selection: mix(background, foreground, light ? 0.12 : 0.16),
      actor1: magenta,
      actor2: cyan,
      actor3: yellow,
      actor4: green,
      badgeUser: cyan,
      badgeThink: muted,
      badgeAnswer: green,
      badgeTool: blue,
      badgeFold: magenta,
      badgeNote: muted,
      badgeFail: red,
      badgeHook: yellow,
      badgePlan: yellow,
      timelineModel: magenta,
      timelineTool: blue,
    },
  };
}
