import { appendFileSync, existsSync, mkdirSync, statSync } from 'node:fs';
import { dirname, isAbsolute } from 'node:path';

/**
 * Optional client timing, written as CSV so a slow interaction can be decomposed rather than
 * guessed at.
 *
 * <p>Off unless {@code PLOWSHARE_DESKTOP_TELEMETRY} names an absolute CSV path. The path is
 * configuration and has no default: a diagnostic that writes somewhere unasked is worse than one
 * that writes nowhere, and a relative path would land wherever the app happened to be started.
 * A malformed value fails loudly at startup rather than silently disabling itself, because a
 * measurement you believe is running and is not is the expensive failure.
 *
 * <p>Every row is one command. The three durations are cumulative from the moment the command was
 * issued in the renderer, so they subtract into the three stages a slow command can be slow in:
 *
 * <ul>
 *   <li>{@code serverMs} — the main process's own dispatch, server time plus SDK
 *   <li>{@code replyMs} − {@code serverMs} — IPC and main-process overhead
 *   <li>{@code renderMs} − {@code replyMs} — the renderer's update, to the first frame painted
 *       after the reply
 * </ul>
 *
 * <p>{@code depth} is how many requests that renderer already had in flight when this one was
 * issued, which is the number to look at when latency rises under concurrency rather than in
 * isolation.
 */
export interface TelemetryRow {
  /** Which surface issued it: the main window, a library window, proc, and so on. */
  readonly surface: string;
  readonly action: string;
  /** Requests already in flight from that renderer when this one was issued. */
  readonly depth: number;
  /** Issue to reply resolved, in the renderer's clock. */
  readonly replyMs: number;
  /**
   * Issue to the first frame painted after the reply, or null when the renderer could not observe
   * one — a backgrounded window is not asked to paint, so this is absent rather than zero.
   */
  readonly renderMs: number | null;
  /** The main process's own dispatch duration, absent when the request never reached it. */
  readonly serverMs: number | null;
  readonly ok: boolean;
}

const COLUMNS = [
  'issuedAt',
  'surface',
  'action',
  'depth',
  'serverMs',
  'replyMs',
  'renderMs',
  'ok',
] as const;

/** Quote only what has to be quoted, and never let a field break the row shape. */
function field(value: string | number | boolean | null): string {
  if (value === null) return '';
  const text = String(value);
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

export class Telemetry {
  private readonly path: string | undefined;
  private readonly warn: (message: string) => void;
  private failed = false;

  /**
   * @param environment the process environment; injected so tests need not mutate the real one
   * @param warn where a write failure is reported, once
   */
  constructor(
    environment: Readonly<Record<string, string | undefined>> = process.env,
    warn: (message: string) => void = (message) => console.warn(message),
  ) {
    this.warn = warn;
    const configured = environment['PLOWSHARE_DESKTOP_TELEMETRY']?.trim();
    if (!configured) return;
    if (!isAbsolute(configured))
      throw new Error(
        'PLOWSHARE_DESKTOP_TELEMETRY must be an absolute path to a CSV file',
      );
    this.path = configured;
  }

  get enabled(): boolean {
    return this.path !== undefined;
  }

  /**
   * Appends one row, creating the file and its header on first use.
   *
   * <p>Never throws: telemetry must not be able to fail an interaction it is only observing. A
   * write failure is reported once and then the writer stays quiet, so a full or unwritable disk
   * does not produce a warning per command.
   */
  record(row: TelemetryRow): void {
    const path = this.path;
    if (path === undefined || this.failed) return;
    try {
      const directory = dirname(path);
      if (!existsSync(directory)) mkdirSync(directory, { recursive: true });
      const header =
        !existsSync(path) || statSync(path).size === 0
          ? COLUMNS.join(',') + '\n'
          : '';
      appendFileSync(
        path,
        header +
          [
            new Date().toISOString(),
            row.surface,
            row.action,
            row.depth,
            row.serverMs,
            row.replyMs,
            row.renderMs,
            row.ok,
          ]
            .map(field)
            .join(',') +
          '\n',
      );
    } catch (failure) {
      this.failed = true;
      this.warn(
        `Client telemetry is off: ${path} could not be written (${
          failure instanceof Error ? failure.message : String(failure)
        })`,
      );
    }
  }
}
