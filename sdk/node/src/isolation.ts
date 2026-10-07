import {
  lstat,
  mkdir,
  open,
  readdir,
  realpath,
  writeFile,
} from 'node:fs/promises';
import { acquireScratch, removeScratch } from './isolation-scratch.js';
import {
  delimiter,
  dirname,
  isAbsolute,
  join,
  relative,
  resolve,
  sep,
} from 'node:path';
import {
  CommandRefused,
  executable,
  environment,
  runCommand,
} from './runner.js';
import type { Command, Outcome } from './runner.js';

/** Local policy; a file-channel request never supplies roots, mounts or backend executables. */
export interface IsolationAccess {
  readonly roots: readonly string[];
  readonly writeRoots: readonly string[];
  permits(path: string): boolean;
}

/** Executes once inside an OS boundary; unavailable isolation must refuse without fallback. */
export interface CommandIsolation {
  run(
    command: Command,
    access: IsolationAccess,
    host: Readonly<Record<string, string | undefined>>,
    cancel?: AbortSignal,
  ): Promise<Outcome>;
}

export interface BubblewrapConfiguration {
  readonly executable: string;
  readonly launcher: string;
  readonly runtimeRoots: readonly string[];
  readonly scratchRoot: string;
}

const UNAVAILABLE: CommandIsolation = {
  run() {
    return Promise.reject(
      new CommandRefused(
        'Linux bubblewrap isolation is not configured on this machine',
      ),
    );
  },
};

/** Decode host configuration at the Node composition boundary, independently of command env/PATH. */
export function configuredIsolation(
  host: Readonly<Record<string, string | undefined>>,
): CommandIsolation {
  const binary = host['PLOWSHARE_BUBBLEWRAP'];
  const launcher = host['PLOWSHARE_SANDBOX_LAUNCHER'];
  const runtime = host['PLOWSHARE_SANDBOX_RUNTIME'];
  const scratchRoot = host['PLOWSHARE_SANDBOX_SCRATCH'];
  if (
    binary === undefined &&
    launcher === undefined &&
    runtime === undefined &&
    scratchRoot === undefined
  )
    return UNAVAILABLE;
  if (!binary || !launcher || !runtime || !scratchRoot)
    throw new CommandRefused(
      'Set PLOWSHARE_BUBBLEWRAP, PLOWSHARE_SANDBOX_LAUNCHER, PLOWSHARE_SANDBOX_RUNTIME and PLOWSHARE_SANDBOX_SCRATCH together',
    );
  return new Bubblewrap({
    executable: binary,
    launcher,
    runtimeRoots: runtime.split(delimiter),
    scratchRoot,
  });
}

interface Mount {
  readonly path: string;
  readonly source: string;
  readonly option: '--ro-bind' | '--bind';
}
function contains(root: string, path: string): boolean {
  const below = relative(root, path);
  return (
    below === '' ||
    (below !== '..' && !below.startsWith('..' + sep) && !isAbsolute(below))
  );
}

/** Only the two-byte shebang determines whether the legacy marker is operator configuration. */
async function isScript(path: string, regular: boolean): Promise<boolean> {
  if (!regular) return false;
  const file = await open(path, 'r');
  try {
    const header = Buffer.alloc(2);
    const { bytesRead } = await file.read(header, 0, 2, 0);
    return bytesRead === 2 && header.toString('ascii') === '#!';
  } finally {
    await file.close();
  }
}

/** Linux mount/network/PID isolation. Visible links and special files are intentionally unsupported. */
export class Bubblewrap implements CommandIsolation {
  private readonly configuration: BubblewrapConfiguration;
  constructor(configuration: BubblewrapConfiguration) {
    if (
      !isAbsolute(configuration.executable) ||
      !isAbsolute(configuration.launcher) ||
      !isAbsolute(configuration.scratchRoot) ||
      dirname(configuration.scratchRoot) === configuration.scratchRoot ||
      configuration.runtimeRoots.length === 0 ||
      configuration.runtimeRoots.some(
        (path) => !isAbsolute(path) || dirname(path) === path,
      )
    ) {
      throw new CommandRefused(
        'Bubblewrap requires absolute executable/launcher/scratch paths and non-root runtime paths',
      );
    }
    this.configuration = {
      ...configuration,
      runtimeRoots: [...configuration.runtimeRoots],
    };
  }

  async run(
    command: Command,
    access: IsolationAccess,
    host: Readonly<Record<string, string | undefined>>,
    cancel?: AbortSignal,
  ): Promise<Outcome> {
    if (process.platform !== 'linux')
      throw new CommandRefused(
        'bubblewrap isolation is supported only on Linux',
      );
    if (cancel?.aborted)
      throw new CommandRefused(
        'the command was cancelled before sandbox startup',
      );
    const scratch = await acquireScratch(this.configuration.scratchRoot, [
      ...access.roots,
      ...(await Promise.all(
        this.configuration.runtimeRoots.map((path) => realpath(path)),
      )),
    ]).catch(() => {
      throw new CommandRefused(
        'the sandbox filesystem policy could not be prepared',
      );
    });
    try {
      const file = join(scratch, 'file');
      const directory = join(scratch, 'directory');
      const options = join(scratch, 'options');
      const prefix = await this.prepare(access, command.cwd, file, directory);
      const launcher = await runCommand(
        {
          argv: await this.launch(
            [...prefix, this.configuration.launcher, '-c', ':'],
            options,
            prefix.length,
          ),
          cwd: command.cwd,
          env: {},
          inherit: [],
          timeoutMillis: 5_000,
          outputBytes: 4096,
        },
        {},
        cancel,
      );
      if (launcher.exitCode !== 0 || launcher.cancelled || launcher.timedOut)
        throw new CommandRefused(
          'bubblewrap could not establish the sandbox; check user namespaces, runtime mounts and the configured launcher',
        );
      if (cancel?.aborted)
        throw new CommandRefused(
          'the command was cancelled before sandbox startup',
        );
      const [program, ...rest] = command.argv;
      if (program === undefined)
        throw new CommandRefused('a command needs a program');
      const built = environment(host, command.inherit, command.env);
      // Start the native wrapper with no loader variables. It sets the child's environment
      // only after entering the boundary, so LD_PRELOAD cannot run host code during startup.
      const childEnvironment = Object.entries(built).flatMap(
        ([name, value]) => ['--setenv', name, value],
      );
      const argv = [
        ...prefix.slice(0, -1),
        ...childEnvironment,
        '--',
        await executable(program, command.cwd, built),
        ...rest,
      ];
      checkArgumentBound(argv);
      return await runCommand(
        {
          ...command,
          env: {},
          inherit: [],
          argv: await this.launch(
            argv,
            options,
            prefix.length + childEnvironment.length,
          ),
        },
        {},
        cancel,
      );
    } finally {
      // Only empty policy placeholders exist here. No workspace copies or user output are removed.
      // Preserve the delivered result: a cleanup failure must not invite replay of a mutation.
      await removeScratch(scratch).catch(() => {
        process.emitWarning(
          'sandbox temporary policy files could not be removed',
        );
      });
    }
  }

  /** Keep environment values in a private descriptor, not host argv; fd 0 stays command stdin. */
  private async launch(
    argv: readonly string[],
    options: string,
    commandStart: number,
  ): Promise<readonly string[]> {
    checkArgumentBound(argv);
    if (argv.some((value) => value.includes('\0')))
      throw new CommandRefused('sandbox arguments cannot contain NUL bytes');
    await writeFile(
      options,
      argv.slice(1, commandStart - 1).join('\0') + '\0',
      { mode: 0o600 },
    );
    // This fixed host script only opens the descriptor and execs the configured native binary.
    // Every path is a positional argument, never shell source. exec preserves owner death.
    return [
      this.configuration.launcher,
      '-c',
      'exec 3<"$1"; shift; plowshare_wrapper=$1; shift; exec "$plowshare_wrapper" --args 3 -- "$@"',
      'plowshare-bubblewrap',
      options,
      this.configuration.executable,
      ...argv.slice(commandStart),
    ];
  }

  private async prepare(
    access: IsolationAccess,
    cwd: string,
    file: string,
    directory: string,
  ): Promise<readonly string[]> {
    try {
      await writeFile(file, '', { mode: 0 });
      await mkdir(directory, { mode: 0 });
      return await this.arguments(access, cwd, file, directory);
    } catch (error) {
      if (error instanceof CommandRefused) throw error;
      throw new CommandRefused(
        'the sandbox filesystem policy could not be prepared',
      );
    }
  }

  /** Pure launch policy plus bounded local filesystem inspection; shared with ordinary policy tests. */
  async arguments(
    access: IsolationAccess,
    cwd: string,
    hiddenFile: string,
    hiddenDirectory: string,
  ): Promise<readonly string[]> {
    if (!access.permits(cwd))
      throw new CommandRefused('sandbox cwd is outside the workspace');
    for (const executable of [
      this.configuration.executable,
      this.configuration.launcher,
    ]) {
      const real = await realpath(executable);
      const attributes = await lstat(real);
      if (
        !attributes.isFile() ||
        (attributes.mode & 0o111) === 0 ||
        access.roots.some((root) => contains(root, real))
      )
        throw new CommandRefused(
          'sandbox executables must be operator-owned programs outside workspace roots',
        );
    }
    const mounts: Mount[] = [];
    for (const configured of this.configuration.runtimeRoots) {
      const runtime = await realpath(configured);
      if (
        dirname(runtime) === runtime ||
        !(await lstat(runtime)).isDirectory() ||
        access.roots.some(
          (root) => contains(runtime, root) || contains(root, runtime),
        ) ||
        ['/proc', '/dev', '/tmp'].includes(resolve(configured))
      ) {
        throw new CommandRefused(
          'sandbox runtime roots must be directories separate from workspace roots',
        );
      }
      mounts.push({
        path: resolve(configured),
        option: '--ro-bind',
        source: runtime,
      });
    }
    let entries = 0;
    for (const root of access.roots) {
      mounts.push({ path: root, option: '--ro-bind', source: root });
      for (const write of access.writeRoots) {
        if (!contains(root, write) || !access.permits(write)) continue;
        if (!(await lstat(write)).isDirectory())
          throw new CommandRefused(
            'sandbox writable areas must already be directories',
          );
        if (write === root) {
          const marker = await lstat(join(root, 'plowshare.json')).catch(
            () => undefined,
          );
          const definitions = await lstat(join(root, '.plowshare')).catch(
            () => undefined,
          );
          if (marker?.isFile() !== true || definitions?.isDirectory() !== true)
            throw new CommandRefused(
              'initialize plowshare.json and .plowshare before sandboxing a fully writable root',
            );
        }
        mounts.push({ path: write, option: '--bind', source: write });
      }
      const inspect = async (path: string): Promise<void> => {
        if (++entries > 100_000)
          throw new CommandRefused(
            'sandbox workspace inspection exceeded its entry bound',
          );
        const attributes = await lstat(path);
        if (attributes.isSymbolicLink())
          throw new CommandRefused('sandbox mountpoints cannot be symlinks');
        if (!access.permits(path)) {
          mounts.push({
            path,
            option: '--ro-bind',
            source: attributes.isDirectory() ? hiddenDirectory : hiddenFile,
          });
          return;
        }
        if (!attributes.isDirectory() && !attributes.isFile())
          throw new CommandRefused(
            'sandbox workspaces cannot contain visible symlinks or special files',
          );
        if (attributes.isFile() && attributes.nlink > 1)
          throw new CommandRefused(
            'sandbox workspaces cannot contain visible hard links',
          );
        if (
          path === join(root, 'plowshare.json') ||
          (path === join(root, 'plowshare') &&
            !(await isScript(path, attributes.isFile())))
        )
          mounts.push({ path, option: '--ro-bind', source: path });
        if (attributes.isDirectory()) {
          for (const name of (await readdir(path)).sort())
            await inspect(join(path, name));
        }
      };
      await inspect(root);
    }
    const argv: string[] = [
      this.configuration.executable,
      '--unshare-all',
      '--unshare-user',
      '--disable-userns',
      '--die-with-parent',
      '--new-session',
      '--cap-drop',
      'ALL',
      '--clearenv',
      '--size',
      '8388608',
      '--tmpfs',
      '/',
      '--proc',
      '/proc',
      '--dev',
      '/dev',
      '--size',
      '67108864',
      '--tmpfs',
      '/tmp',
    ];
    mounts.sort(
      (one, other) => one.path.split(sep).length - other.path.split(sep).length,
    );
    for (const mount of mounts)
      argv.push(mount.option, mount.source, mount.path);
    argv.push('--chdir', cwd, '--');
    checkArgumentBound(argv);
    return argv;
  }
}

function checkArgumentBound(argv: readonly string[]): void {
  if (
    argv.reduce(
      (size, value) => size + Buffer.byteLength(value, 'utf8') + 1,
      0,
    ) > 131072
  )
    throw new CommandRefused('sandbox launch exceeded its argument byte bound');
}
