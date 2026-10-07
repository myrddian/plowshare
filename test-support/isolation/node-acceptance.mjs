import assert from 'node:assert/strict';
import {
  mkdir,
  readdir,
  unlink,
  rmdir,
  mkdtemp,
  readFile,
  realpath,
  stat,
  symlink,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, relative, dirname } from 'node:path';
import { createServer } from 'node:net';
import { setTimeout as pause } from 'node:timers/promises';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import { Bubblewrap } from '../../sdk/node/build/isolation.js';

const arguments_ = process.argv.slice(2);
const ownerRoot =
  arguments_[0] === '--owner'
    ? (arguments_.shift(), arguments_.shift())
    : undefined;
const [executable, launcher, ...runtimeRoots] = arguments_;
const temporary = await realpath(
  await mkdtemp(join(tmpdir(), 'bubblewrap-node-')),
);
const root = ownerRoot ?? join(temporary, 'workspace');
const output = join(root, 'output');
const scratchRoot = join(ownerRoot ? dirname(root) : temporary, 'scratch');
if (!ownerRoot) await mkdir(scratchRoot, { mode: 0o700 });
const sandbox = new Bubblewrap({
  executable,
  launcher,
  runtimeRoots,
  scratchRoot,
});
if (ownerRoot) {
  await sandbox.run(
    command(
      [
        '/bin/sh',
        '-c',
        'setsid sh -c \'while :; do echo tick >> "$1"; sleep 0.05; done\' child "$1" & wait',
        'test',
        join(output, 'crash-heartbeat'),
      ],
      20_000,
    ),
    {
      roots: [root],
      writeRoots: [output],
      permits: (path) =>
        path === root ||
        (!relative(root, path).startsWith('..') &&
          !relative(root, path)
            .split('/')
            .some((part) => part.startsWith('.'))),
    },
    {},
  );
  process.exit(0);
}
await mkdir(output, { recursive: true });
await mkdir(join(root, '.plowshare'));
await writeFile(join(root, 'plowshare.json'), '{"version":1,"name":"test"}');
await writeFile(join(root, '.secret'), 'secret');
const outside = join(temporary, 'outside.txt');
await writeFile(outside, 'secret');
const access = {
  roots: [root],
  writeRoots: [output],
  permits(path) {
    const below = relative(root, path);
    return (
      below === '' ||
      (!below.startsWith('..') &&
        !below.split('/').some((part) => part.startsWith('.')))
    );
  },
};
function command(argv, timeoutMillis = 10_000) {
  return {
    argv,
    cwd: root,
    env: { PATH: '/usr/bin:/bin', EXPLICIT: 'kept' },
    inherit: [],
    timeoutMillis,
    outputBytes: 4096,
  };
}
const filesystem = await sandbox.run(
  command([
    '/bin/sh',
    '-c',
    'test ! -r "$1" && test ! -r .secret && test ! -r .plowshare && ! echo bad > root-write && ! echo bad > plowshare.json && echo ok > output/result && test "$EXPLICIT" = kept && test -z "${SECRET+x}" && ! dd if=/dev/zero of=/tmp/too-large bs=1048576 count=65 2>/dev/null',
    'test',
    outside,
  ]),
  access,
  { SECRET: 'not-inherited' },
);
assert.equal(filesystem.exitCode, 0, filesystem.stderr);
assert.equal((await readFile(join(output, 'result'), 'utf8')).trim(), 'ok');
const input = await sandbox.run(
  {
    ...command(['/bin/cat']),
    stdin: 'stdin survives the private descriptor\n',
  },
  access,
  {},
);
assert.equal(input.stdout, 'stdin survives the private descriptor\n');
const descriptors = await sandbox.run(
  command(['/bin/sh', '-c', 'test ! -e /proc/self/fd/3']),
  access,
  {},
);
assert.equal(
  descriptors.exitCode,
  0,
  'private arguments descriptor leaked to user code',
);
const loaderSource = join(temporary, 'loader.c');
const loaderLibrary = join(temporary, 'loader.so');
const loaderMarker = join(temporary, 'loader-escaped');
await writeFile(
  loaderSource,
  '#include <stdio.h>\n#include <stdlib.h>\n__attribute__((constructor)) void inject(void) { const char *p=getenv("ESCAPE_MARKER"); if(p) { FILE *f=fopen(p,"w"); if(f) {fputs("escaped",f);fclose(f);} } }\n',
);
const compiler = spawn(
  '/usr/bin/cc',
  ['-shared', '-fPIC', loaderSource, '-o', loaderLibrary],
  { stdio: 'inherit' },
);
assert.equal((await once(compiler, 'exit'))[0], 0);
const loader = await sandbox.run(
  {
    ...command([
      '/bin/sh',
      '-c',
      'test -n "$LD_PRELOAD" && test -n "$ESCAPE_MARKER"',
    ]),
    env: { LD_PRELOAD: loaderLibrary, ESCAPE_MARKER: loaderMarker },
  },
  access,
  {},
);
assert.equal(loader.exitCode, 0);
assert.equal(
  await stat(loaderMarker).catch(() => undefined),
  undefined,
  'command environment injected code before isolation',
);
const unavailable = new Bubblewrap({
  scratchRoot,
  executable,
  launcher: '/usr/bin/false',
  runtimeRoots,
});
await assert.rejects(
  unavailable.run(
    command(['/bin/sh', '-c', 'echo bad > output/launcher-escaped']),
    access,
    {},
  ),
);
assert.equal(
  await stat(join(output, 'launcher-escaped')).catch(() => undefined),
  undefined,
);
const full = await sandbox.run(
  command([
    '/bin/sh',
    '-c',
    '! echo bad > plowshare.json && echo ok > root-result',
  ]),
  { ...access, writeRoots: [root] },
  {},
);
assert.equal(full.exitCode, 0, full.stderr);
const server = createServer();
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
try {
  const address = server.address();
  assert.equal(typeof address, 'object');
  const network = await sandbox.run(
    command(
      [
        '/usr/bin/node',
        '-e',
        "const s=require('net').connect(Number(process.argv[1]),'127.0.0.1');s.on('connect',()=>process.exit(1));s.on('error',()=>process.exit(0));",
        String(address.port),
      ],
      3000,
    ),
    access,
    {},
  );
  assert.equal(network.exitCode, 0, network.stderr);
} finally {
  await new Promise((resolve) => server.close(resolve));
}
const heartbeat = join(output, 'heartbeat');
const abort = new AbortController();
const running = sandbox.run(
  command([
    '/bin/sh',
    '-c',
    'setsid sh -c \'while :; do echo tick >> "$1"; sleep 0.05; done\' child "$1" & wait',
    'test',
    heartbeat,
  ]),
  access,
  {},
  abort.signal,
);
for (
  let count = 0;
  count < 100 && !(await stat(heartbeat).catch(() => undefined));
  count++
)
  await pause(50);
assert.ok(await stat(heartbeat));
abort.abort();
assert.equal((await running).cancelled, true);
const size = (await stat(heartbeat)).size;
await pause(300);
assert.equal(
  (await stat(heartbeat)).size,
  size,
  'a setsid child survived cancellation',
);
const exitedHeartbeat = join(output, 'exit-heartbeat');
const normalExit = await sandbox.run(
  command(
    [
      '/bin/sh',
      '-c',
      'setsid sh -c \'while :; do echo tick >> "$1"; sleep 0.05; done\' child "$1" > /dev/null 2>&1 & while test ! -e "$1"; do sleep 0.05; done',
      'test',
      exitedHeartbeat,
    ],
    3000,
  ),
  access,
  {},
);
assert.equal(normalExit.exitCode, 0);
const exitedSize = (await stat(exitedHeartbeat)).size;
await pause(300);
assert.equal(
  (await stat(exitedHeartbeat)).size,
  exitedSize,
  'child survived normal exit',
);
const crashed = join(output, 'crash-heartbeat');
const owner = spawn(
  process.execPath,
  [fileURLToPath(import.meta.url), '--owner', root, ...arguments_],
  { stdio: 'inherit' },
);
try {
  for (
    let count = 0;
    count < 100 &&
    !(await stat(crashed).catch(() => undefined)) &&
    owner.exitCode === null;
    count++
  )
    await pause(50);
  assert.ok(await stat(crashed));
  const exited = once(owner, 'exit');
  owner.kill('SIGKILL');
  await exited;
  await pause(200);
  const stopped = (await stat(crashed)).size;
  await pause(300);
  assert.equal(
    (await stat(crashed)).size,
    stopped,
    'a child survived owner death',
  );
  assert.equal(
    (await readdir(scratchRoot)).length,
    1,
    'owner crash left no recoverable policy',
  );
} finally {
  owner.kill('SIGKILL');
}
assert.equal(
  (await sandbox.run(command(['/bin/sh', '-c', 'sleep 20'], 200), access, {}))
    .timedOut,
  true,
);
assert.deepEqual(
  await readdir(scratchRoot),
  [],
  'dead owner policy was not recovered',
);
const boot = (await readFile('/proc/sys/kernel/random/boot_id', 'utf8')).trim();
const ownerStat = await readFile('/proc/self/stat', 'utf8');
const ticks = ownerStat.slice(ownerStat.lastIndexOf(')') + 2).split(' ')[19];
const uuid = '00000000-0000-0000-0000-000000000000';
const live = join(scratchRoot, `run-${boot}-${process.pid}-${ticks}-${uuid}`);
const reused = join(scratchRoot, `run-${boot}-${process.pid}-0-${uuid}`);
await mkdir(live, { mode: 0o700 });
await mkdir(reused, { mode: 0o700 });
await symlink(temporary, join(scratchRoot, 'foreign'));
await sandbox.run(command(['/bin/true'], 2000), access, {});
assert.deepEqual(
  (await readdir(scratchRoot)).sort(),
  ['foreign', live.split('/').at(-1)].sort(),
);
await rmdir(live);
await unlink(join(scratchRoot, 'foreign'));
await symlink(outside, join(root, 'escape'));
await assert.rejects(
  sandbox.run(
    command(['/bin/sh', '-c', 'echo bad > output/escaped']),
    access,
    {},
  ),
);
assert.equal(
  await stat(join(output, 'escaped')).catch(() => undefined),
  undefined,
);
console.log(
  'Node bubblewrap acceptance passed: filesystem, configuration, environment, loader injection, private descriptor, stdin, failed startup, tmpfs, network, cancellation, normal-exit cleanup, owner death, restart recovery, live-owner preservation, PID reuse, deadline, symlink refusal',
);
