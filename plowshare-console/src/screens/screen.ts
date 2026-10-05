/**
 * What every screen is, from the shell's point of view.
 *
 * Six views are built by six factories with the same shape, so that `shell.ts`
 * can hold them in one list and know nothing else about any of them.
 *
 * The REPL is not one of them and is no longer adapted into one. It predates
 * this interface and happens to satisfy it, and `shell.ts` used to wrap it on
 * that basis; `chat.ts` owns it now, beside a picker and a trajectory reading,
 * and implements this interface itself over the three of them. So this stays
 * the shell's contract with a view rather than growing a special case for the
 * one component that is not a view.
 *
 * A view may declare more than this -- `trajectory.ts` returns a `Trajectory`,
 * which is a `Screen` plus the verb `chat.ts` needs to tell it which
 * conversation to read. Wider is fine and narrower is not: what `shell.ts`
 * holds is the interface below.
 */

/**
 * The two verbs a screen needs from `api.ts`, so a test can replace them.
 *
 * Re-exported from the REPL rather than declared again: it is the same seam,
 * and a second copy of an interface is the copy that drifts.
 */
export type { Transport } from '../repl/repl';

export interface Screen {
  /** The element this screen built, for a caller that wants to place it. */
  element(): HTMLElement;
  /**
   * Read the server and draw. Called on first mount and on every reload.
   *
   * Never rejects: a screen that threw here would take the shell's switch
   * with it. What went wrong is drawn on the screen instead.
   */
  load(): Promise<void>;
  /** Stop whatever is running. Idempotent. */
  destroy(): void;
}
