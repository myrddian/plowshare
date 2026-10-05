import { execFileSync, spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const DEMO = fileURLToPath(new URL('./demo.ts', import.meta.url));
let pty = true;
try {
  execFileSync('python3', ['-c', 'import pty'], { stdio: 'ignore' });
} catch {
  pty = false;
}
const DRIVER = `
import fcntl, json, os, pty, select, struct, sys, termios, time
pid, fd = pty.fork()
if pid == 0:
    os.execvpe('node', ['node', sys.argv[1], sys.argv[2]], dict(os.environ))
buf = bytearray()
def resize(cols):
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack('HHHH', 30, cols, 0, 0))
def pump(seconds):
    end = time.time() + seconds
    while time.time() < end:
        r, _, _ = select.select([fd], [], [], 0.05)
        if r:
            try: chunk = os.read(fd, 65536)
            except OSError: return
            if not chunk: return
            buf.extend(chunk)
resize(140)
pump(1.5)
for key in json.loads(sys.argv[3]):
    if isinstance(key, int): resize(key)
    else: os.write(fd, key.encode())
    pump(0.3)
pump(0.5)
os.close(fd)
os.waitpid(pid, 0)
sys.stdout.buffer.write(bytes(buf))
`;
async function drawn(
  view: 'board' | 'swarm',
  keys: (string | number)[],
): Promise<string> {
  const child = spawn(
    'python3',
    ['-c', DRIVER, DEMO, `--${view}`, JSON.stringify(keys)],
    {
      env: {
        ...process.env,
        PLOWSHARE_DEMO_PAUSE: '100',
        FORCE_COLOR: '3',
        TERM: 'xterm-256color',
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  );
  const chunks: Buffer[] = [],
    errors: Buffer[] = [];
  child.stdout.on('data', (chunk: Buffer) => {
    chunks.push(chunk);
  });
  child.stderr.on('data', (chunk: Buffer) => {
    errors.push(chunk);
  });
  const code = await new Promise<number | null>((resolve) => {
    child.on('close', resolve);
  });
  if (code !== 0) throw new Error(Buffer.concat(errors).toString('utf8'));
  return Buffer.concat(chunks).toString('utf8');
}
// eslint-disable-next-line no-control-regex -- Intentional terminal control-sequence removal or its regression assertion.
const plain = (text: string) => text.replace(/\u001b\[[0-9;?]*[a-zA-Z]/gu, '');

(pty ? describe : describe.skip)(
  'board inspection through a real terminal',
  () => {
    it('navigates seat trajectories, decisions, child topics and swarm queues without taking scrollback', async () => {
      const stream = await drawn('board', [
        '\u001b[C',
        '\u001b[C',
        'q', // root, researcher trajectory, back to board
        'j',
        'j',
        'j',
        '\u001b[C',
        '\u001b[D', // child, back
        'G',
        '\t',
        80, // complete request decision on a narrow terminal
        '\u001b[D',
        'v',
        'j',
        '\t',
        140,
        'q',
      ]);
      const text = plain(stream);
      expect(text).toContain('49/120 model calls spent');
      expect(text).toContain('researcher');
      expect(text).toContain('trajectory');
      expect(text).toContain('Compare conflict strategies');
      expect(text).toContain('Investigate independently');
      expect(text).toContain('spark 1/3 slots');
      expect(text).toContain('Model: reasoning');
      expect(stream).not.toContain('\u001b[?1049h');
      expect(stream).toContain('\u001b[?2026h');
    }, 30_000);
    it('starts directly on the swarm screen and leaves with Escape', async () => {
      const text = plain(await drawn('swarm', ['\u001b']));
      expect(text).toContain('Swarm · Your account');
      expect(text).toContain('spark 1/3 slots');
      expect(text).toContain('spec_writer');
      expect(text).toContain('search');
    }, 30_000);
  },
);
