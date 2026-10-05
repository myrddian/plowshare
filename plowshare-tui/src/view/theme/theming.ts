import { errorMessage } from 'plowshare-client-ts/binding/values';
import { watch } from 'node:fs';
import type { FSWatcher } from 'node:fs';

import type { Depth, Look } from '../look.ts';
import { SYSTEM } from './builtin.ts';
import { resolve } from './format.ts';
import type { ThemeFile } from './format.ts';
import {
  loadLibrary,
  readSettings,
  settingsPath,
  themeDirs,
  writeSettings,
} from './loader.ts';
import type { Library } from './loader.ts';
import { modeOf, systemTheme } from './system.ts';
import type { Probe } from './system.ts';
import type { Mode } from './tokens.ts';

/**
 * Which theme is on, how it changes, and the sentences `/theme` answers with.
 *
 * <h2>What a person can write, and what each means</h2>
 *
 * <ul>
 * <li>`auto` — `plowshare`, in whichever mode the terminal's background says.
 *     The default.
 * <li>`dark`, `light` — `plowshare` in that mode, whatever the background.
 * <li>`system` — built from the terminal's own colours; see `system.ts`.
 * <li>any other name — that theme, in the background's mode.
 * </ul>
 *
 * <p>Chosen at startup from `PLOWSHARE_THEME`, then what `/theme` last saved in
 * `~/.config/plowshare/tui.json`, then `auto`. A theme file saved while the
 * client runs is picked up at once — though only what is drawn after that is
 * drawn in it: the transcript above was written to the terminal and is the
 * terminal's now.
 */

export interface Theming {
  readonly current: () => Look;
  /** The themes, as the `/theme ` menu offers them. */
  readonly offers: () => { readonly name: string; readonly detail: string }[];
  /** The lines `/theme` shows. */
  readonly describe: () => string[];
  /** Switches, saves, and answers with what happened. */
  readonly choose: (name: string) => {
    readonly lines: string[];
    readonly ok: boolean;
  };
  /** Called whenever the look changes, by `/theme` or by a file being saved. */
  readonly onChange: (listener: (look: Look) => void) => void;
  readonly close: () => void;
}

export interface Options {
  readonly env: Readonly<Record<string, string | undefined>>;
  readonly home: string;
  readonly cwd: string;
  readonly depth: Depth;
  readonly probe?: Probe;
  /** Whether to watch theme files. Off in tests. */
  readonly watching?: boolean;
}

const MODES: Readonly<Record<string, Mode>> = { dark: 'dark', light: 'light' };

export function theming(options: Options): Theming {
  const dirs = themeDirs(options.env, options.home, options.cwd);
  const saved = settingsPath(options.env, options.home);
  const system = systemTheme(options.probe);
  const background = modeOf(options.probe);
  const extra: ThemeFile[] = system === undefined ? [] : [system];

  let library: Library = loadLibrary(dirs, extra);
  let chosen =
    options.env['PLOWSHARE_THEME'] ?? readSettings(saved).theme ?? 'auto';
  let problems: readonly string[] = [];
  const listeners: ((look: Look) => void)[] = [];

  /** A choice as a theme and a mode, or `undefined` when it names nothing. */
  const reading = (
    choice: string,
  ): { name: string; mode: Mode } | undefined => {
    const fixed = MODES[choice];
    if (fixed !== undefined) {
      return { name: 'plowshare', mode: fixed };
    }
    const name = choice === 'auto' ? 'plowshare' : choice;
    return library.get(name) === undefined
      ? undefined
      : { name, mode: background ?? 'dark' };
  };

  const looking = (choice: string): Look => {
    const read = reading(choice);
    const notes: string[] = [...library.problems];
    let name = read?.name ?? 'plowshare';
    const mode = read?.mode ?? background ?? 'dark';
    if (read === undefined) {
      notes.push(
        choice === SYSTEM
          ? 'this terminal did not say what its colours are, so system is plowshare here'
          : `there is no theme called ${choice}, so this is plowshare`,
      );
    }
    let palette;
    try {
      const resolved = resolve(name, mode, library.get);
      palette = resolved.palette;
      notes.push(...resolved.problems);
    } catch (trouble) {
      notes.push(
        trouble instanceof Error ? trouble.message : errorMessage(trouble),
      );
      name = 'plowshare';
      palette = resolve(name, mode, library.get).palette;
    }
    problems = notes;
    return { name, mode, palette, depth: options.depth };
  };

  let look = looking(chosen);
  const changed = (): void => {
    for (const listener of listeners) {
      listener(look);
    }
  };

  const watchers: FSWatcher[] = [];
  if (options.watching === true) {
    let pending: NodeJS.Timeout | undefined;
    for (const dir of dirs) {
      try {
        const watcher = watch(dir, () => {
          // Editors write a file in several steps; one reload per save.
          clearTimeout(pending);
          pending = setTimeout(() => {
            library = loadLibrary(dirs, extra);
            look = looking(chosen);
            changed();
          }, 120);
        });
        watcher.unref();
        watchers.push(watcher);
      } catch {
        // A directory that does not exist has nothing to watch.
      }
    }
  }

  return {
    current: () => look,
    offers: () =>
      [
        {
          name: 'auto',
          detail: 'plowshare, light or dark to match the background',
        },
        { name: 'dark', detail: 'plowshare, dark' },
        { name: 'light', detail: 'plowshare, light' },
        ...library.names.map((name) => {
          const source = library.source(name);
          return {
            name,
            detail:
              name === SYSTEM
                ? "built from this terminal's own colours"
                : source === 'built-in' || source === undefined
                  ? 'built in'
                  : source,
          };
        }),
      ].map((offer) =>
        offer.name === chosen
          ? { ...offer, detail: `${offer.detail} · current` }
          : offer,
      ),
    describe: () => [
      `${look.name} (${look.mode})${chosen === look.name ? '' : ` — chosen as ${chosen}`}`,
      '',
      ...['auto', 'dark', 'light', ...library.names].map((name) => {
        const source = library.source(name);
        const where =
          source === undefined || source === 'built-in' ? '' : ` — ${source}`;
        return `${name === chosen ? '●' : '○'} ${name}${where}`;
      }),
      ...(problems.length === 0 ? [] : ['', ...problems]),
      '',
      `/theme <name> switches · your own go in ${dirs[0] ?? ''}`,
    ],
    choose: (name) => {
      if (reading(name) === undefined && name !== SYSTEM) {
        return {
          ok: false,
          lines: [`there is no theme called ${name} — /theme lists them`],
        };
      }
      chosen = name;
      look = looking(name);
      let note: string[] = [];
      try {
        writeSettings(saved, { theme: name });
      } catch (trouble) {
        note = [
          `not saved: ${trouble instanceof Error ? trouble.message : errorMessage(trouble)}`,
        ];
      }
      changed();
      return {
        ok: true,
        lines: [
          `theme is now ${look.name} (${look.mode})`,
          ...problems,
          ...note,
        ],
      };
    },
    onChange: (listener) => {
      listeners.push(listener);
    },
    close: () => {
      for (const watcher of watchers) {
        watcher.close();
      }
    },
  };
}
