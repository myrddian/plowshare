import { errorMessage } from 'plowshare-client-ts/binding/values';
/**
 * One command, started with no shell, an environment built from nothing, stdin only
 * when given, bounded output and a deadline — the whole of what `run` does on this
 * machine once this client's own `.plowshare/environment.yml` has let it through.
 *
 * <h2>`CommandRunner`'s copy, held to its table</h2>
 *
 * <p>`plowshare-protocol`'s `CommandRunner` is what the server's `LocalProvider` and
 * the Java client execute, so that a command cannot mean one thing on the server and
 * another on a laptop. This is the Node platform copy of its rules, and
 * `runner.test.ts` runs the shared case table `commands.json` against it wherever
 * the platform has a `sh` to run it with.
 *
 * <h2>In the Node platform because it spawns</h2>
 *
 * <p>`binding/` has no Node types and cannot start a process; the enforcer beside
 * this is the only caller.
 *
 * <h2>What it does not do</h2>
 *
 * <p>This process primitive does not isolate by itself. The enforcer uses `CommandIsolation`
 * for a configured bubblewrap command; that backend wraps argv with the mount/namespace policy.
 * Raw execution still has the OS user's access; a working directory does not contain it.
 *
 * <h2>One difference from the Java, stated</h2>
 *
 * <p>The tree is killed by process group rather than by walking descendants: the
 * child is started `detached`, which makes it a group leader, and the group is
 * signalled. A descendant that left the group (`setsid`) is out of reach here, where
 * `ProcessHandle.descendants` would still find it; one that did not is killed
 * however deep it is, even after its parent has exited and it was re-parented.
 */
import { spawn } from 'node:child_process';
import { access, stat } from 'node:fs/promises';
import { constants as fsConstants } from 'node:fs';
import { constants as osConstants } from 'node:os';
import { delimiter, isAbsolute, resolve, sep } from 'node:path';

/** How long a process gets after being asked to stop before it is forced. */
export const GRACE_MILLIS = 5_000;

/**
 * The most input a command is given, in UTF-8 bytes — `CommandRunner.MAX_STDIN_BYTES`,
 * bounded as output is: an acceptance command's `stdin:` is a model's to write, and it
 * rides in one frame. Past it the command is refused, never cut: a truncated script
 * would answer a program's prompts differently from the one the person allowed.
 */
export const MAX_STDIN_BYTES = 64 * 1024;

/** What to run, already resolved from the environment. */
export interface Command {
  readonly argv: readonly string[];
  readonly cwd: string;
  readonly env: Readonly<Record<string, string>>;
  readonly inherit: readonly string[];
  readonly timeoutMillis: number;
  readonly outputBytes: number;
  /** What to write to the command's input; absent closes it at once, as before stdin existed. */
  readonly stdin?: string;
}

/** How a command ended — `CommandRunner.Outcome`. */
export interface Outcome {
  /** Null when it never exited on its own: timed out or cancelled. */
  readonly exitCode: number | null;
  readonly timedOut: boolean;
  readonly cancelled: boolean;
  readonly stdout: string;
  /** Bytes dropped from the front of stdout, which keeps its tail. */
  readonly stdoutCut: number;
  readonly stderr: string;
  readonly stderrCut: number;
  readonly millis: number;
}

/** How much earlier than its deadline a stopped command may say it stopped and still have run for it. */
export const RAN_FOR_SLACK_MILLIS = 100;

/**
 * Whether a command given `runsForMillis` as its deadline was still running when the deadline came —
 * `CommandRunner.ranFor`, an acceptance command's `runs-for:` (spec 2026-10-01, the acceptance
 * checker §1). The deadline is the stop: this runner kills the whole group at it, as the Java runners
 * kill the tree, so a timed-out outcome is the pass and an exit before it the failure. A side whose
 * own timeout is shorter stopped it sooner, which is no pass.
 */
export function ranFor(outcome: Outcome, runsForMillis: number): boolean {
  return (
    outcome.timedOut &&
    !outcome.cancelled &&
    outcome.exitCode === null &&
    outcome.millis >= runsForMillis - RAN_FOR_SLACK_MILLIS
  );
}

/** A command that could not be started, with the sentence saying why. */
export class CommandRefused extends Error {
  constructor(sentence: string) {
    super(sentence);
    this.name = 'CommandRefused';
  }
}

/** The environment a command starts with: nothing, then the named host variables, then the explicit ones. */
export function environment(
  host: Readonly<Record<string, string | undefined>>,
  inherit: readonly string[],
  env: Readonly<Record<string, string>>,
): Record<string, string> {
  const built: Record<string, string> = {};
  for (const name of inherit) {
    const value = host[name];
    if (value !== undefined) {
      built[name] = value;
    }
  }
  return Object.assign(built, env);
}

async function runnable(candidate: string): Promise<boolean> {
  try {
    if (!(await stat(candidate)).isFile()) {
      return false;
    }
    await access(candidate, fsConstants.X_OK);
    return true;
  } catch {
    return false;
  }
}

/**
 * The executable `argv[0]` names: itself when it has a separator in it, resolved
 * against the working directory, and otherwise the first match on the command's own
 * `PATH` — never this process's.
 */
export async function executable(
  argv0: string,
  cwd: string,
  built: Readonly<Record<string, string>>,
): Promise<string> {
  if (argv0 === '') {
    throw new CommandRefused(
      "a command's first argument is the program to run, and it was empty",
    );
  }
  if (argv0.includes('/') || argv0.includes(sep)) {
    const named = isAbsolute(argv0) ? argv0 : resolve(cwd, argv0);
    if (!(await runnable(named))) {
      throw new CommandRefused(
        `there is no program at ${argv0} that can be run`,
      );
    }
    return named;
  }
  const path = built['PATH'];
  if (path !== undefined) {
    for (const directory of path.split(delimiter)) {
      if (directory === '') {
        continue;
      }
      for (const suffix of suffixes()) {
        const candidate = resolve(directory, argv0 + suffix);
        if (await runnable(candidate)) {
          return candidate;
        }
      }
    }
  }
  throw new CommandRefused(
    `no program called '${argv0}' is on this command's PATH` +
      (path === undefined
        ? ', which is not set; inherit PATH in environment.yml'
        : ''),
  );
}

function suffixes(): readonly string[] {
  return process.platform === 'win32' ? ['', '.exe', '.cmd', '.bat'] : [''];
}

/** The last `limit` bytes of a stream, and how many came before them. */
class Tail {
  private readonly limit: number;
  private readonly chunks: Buffer[] = [];
  private kept = 0;
  private total = 0;

  constructor(limit: number) {
    this.limit = Math.max(1, Math.floor(limit));
  }

  add(chunk: Buffer): void {
    this.total += chunk.length;
    this.chunks.push(chunk);
    this.kept += chunk.length;
    while (this.kept > this.limit) {
      const first = this.chunks[0] ?? Buffer.alloc(0);
      const over = this.kept - this.limit;
      if (first.length <= over) {
        this.chunks.shift();
        this.kept -= first.length;
      } else {
        this.chunks[0] = first.subarray(over);
        this.kept -= over;
      }
    }
  }

  dropped(): number {
    return this.total - this.kept;
  }

  /** Decoded as UTF-8 with replacement, as `CodingErrorAction.REPLACE` decodes it. */
  text(): string {
    return Buffer.concat(this.chunks).toString('utf8');
  }
}

/**
 * Run it to the end, the deadline, or the cancel.
 *
 * @param host the variables `inherit` is read from — this process's, outside tests
 * @param cancel aborting it kills the command, which is then answered as cancelled
 * @throws CommandRefused when the command is empty, the directory is not one, its input
 *     is past `MAX_STDIN_BYTES`, or the program cannot be found or started
 */
export async function runCommand(
  command: Command,
  host: Readonly<Record<string, string | undefined>> = process.env,
  cancel?: AbortSignal,
): Promise<Outcome> {
  const [program, ...rest] = command.argv;
  if (program === undefined) {
    throw new CommandRefused('a command needs at least the program to run');
  }
  const directory = await stat(command.cwd).catch(() => undefined);
  if (directory?.isDirectory() !== true) {
    throw new CommandRefused(
      `the working directory ${command.cwd} is not a directory`,
    );
  }
  if (command.stdin !== undefined) {
    const bytes = Buffer.byteLength(command.stdin, 'utf8');
    if (bytes > MAX_STDIN_BYTES) {
      throw new CommandRefused(
        `a command's input is ${bytes} bytes, more than the` +
          ` ${MAX_STDIN_BYTES} a command may be given`,
      );
    }
  }
  const built = environment(host, command.inherit, command.env);
  const resolved = await executable(program, command.cwd, built);

  const started = performance.now();
  let child;
  try {
    child = spawn(resolved, rest, {
      cwd: command.cwd,
      env: built,
      stdio: [command.stdin === undefined ? 'ignore' : 'pipe', 'pipe', 'pipe'],
      shell: false,
      // A group leader, so that a kill reaches the whole tree and not only this process.
      detached: true,
      windowsHide: true,
    });
  } catch (trouble) {
    throw new CommandRefused(
      `'${program}' could not be started: ${trouble instanceof Error ? trouble.message : errorMessage(trouble)}`,
    );
  }
  await new Promise<void>((spawned, failed) => {
    child.once('spawn', () => spawned());
    child.once('error', (trouble) =>
      failed(
        new CommandRefused(
          `'${program}' could not be started: ${trouble.message}`,
        ),
      ),
    );
  });
  if (command.stdin !== undefined && child.stdin !== null) {
    child.stdin.on('error', () => undefined); // a program that exits without reading
    child.stdin.end(command.stdin);
  }

  const group = child.pid;
  if (group !== undefined) {
    live.add(group);
  }
  const stdout = new Tail(command.outputBytes);
  const stderr = new Tail(command.outputBytes);
  // Always piped, whatever stdin's own descriptor is — only that one varies with `command.stdin`.
  child.stdout?.on('data', (chunk: Buffer) => stdout.add(chunk));
  child.stderr?.on('data', (chunk: Buffer) => stderr.add(chunk));
  const closed = new Promise<void>((done) => child.once('close', () => done()));
  const exited = new Promise<number>((done) => {
    if (child.exitCode !== null || child.signalCode !== null) {
      done(statusOf(child.exitCode, child.signalCode));
      return;
    }
    child.once('exit', (code, signal) => done(statusOf(code, signal)));
  });

  let stopping: 'timedOut' | 'cancelled' | undefined;
  let stop: () => void = () => undefined;
  const stopped = new Promise<void>((done) => {
    stop = done;
  });
  const deadline = setTimeout(
    () => {
      stopping ??= 'timedOut';
      stop();
    },
    Math.max(0, command.timeoutMillis),
  );
  const cancelled = (): void => {
    stopping ??= 'cancelled';
    stop();
  };
  if (cancel?.aborted === true) {
    cancelled();
  } else {
    cancel?.addEventListener('abort', cancelled, { once: true });
  }

  let status: number | undefined;
  try {
    const first = await Promise.race([exited, stopped.then(() => undefined)]);
    status = stopping === undefined ? first : undefined;
    if (stopping !== undefined) {
      await killed(child.pid, exited);
    }
  } finally {
    clearTimeout(deadline);
    cancel?.removeEventListener('abort', cancelled);
  }
  // A reader that stops waiting: a descendant that outlived the kill and holds a
  // pipe open would otherwise keep this call from ever returning.
  await Promise.race([closed, pause(GRACE_MILLIS)]);
  if (group !== undefined) {
    live.delete(group);
  }
  child.stdout?.destroy();
  child.stderr?.destroy();

  return {
    exitCode: stopping === undefined ? (status ?? null) : null,
    timedOut: stopping === 'timedOut',
    cancelled: stopping === 'cancelled',
    stdout: stdout.text(),
    stdoutCut: stdout.dropped(),
    stderr: stderr.text(),
    stderrCut: stderr.dropped(),
    millis: Math.round(performance.now() - started),
  };
}

/**
 * Every group still running, killed when this process exits.
 *
 * <p>`detached` is what lets a kill reach the tree, and it is also what lets the
 * tree outlive this client: a command started for a run would otherwise go on
 * after the terminal that started it had quit.
 */
const live = new Set<number>();
process.once('exit', () => {
  for (const group of live) {
    signalled(group, 'SIGKILL');
  }
});

/** `Process.exitValue()`: a process a signal ended reports 128 plus the signal's number. */
function statusOf(code: number | null, signal: NodeJS.Signals | null): number {
  if (code !== null) {
    return code;
  }
  return 128 + (signal === null ? 0 : osConstants.signals[signal]);
}

/** The whole group asked to stop, then — once its leader is gone or the grace is up — forced. */
async function killed(
  pid: number | undefined,
  exited: Promise<number>,
): Promise<void> {
  if (pid === undefined) {
    return;
  }
  signalled(pid, 'SIGTERM');
  await Promise.race([exited, pause(GRACE_MILLIS)]);
  signalled(pid, 'SIGKILL');
}

function signalled(pid: number, signal: NodeJS.Signals): void {
  try {
    process.kill(process.platform === 'win32' ? pid : -pid, signal);
  } catch {
    // Already gone, every one of them.
  }
}

function pause(millis: number): Promise<void> {
  return new Promise((done) => {
    setTimeout(done, millis).unref();
  });
}
