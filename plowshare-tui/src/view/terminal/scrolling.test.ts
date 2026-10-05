import { execFileSync, spawn } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { DIALOG_GRACE } from './mounting.ts';

/**
 * The three properties that make this a terminal client and not a takeover.
 *
 * <h2>Against a real pty, because nothing else can see them</h2>
 *
 * <p>Each completed entry written exactly once; the alternate screen never
 * taken; synchronised output in use. All three are facts about <b>the byte
 * stream</b>. A component-tree assertion cannot see any of them, and — measured
 * — a fake stream cannot produce them: handed a `PassThrough` carrying
 * `isTTY: true`, Ink drew its first frame and then never wrote again and never
 * delivered a keypress. Reduced to a spike containing none of this project's
 * code, Ink did the same, so the fake was the problem. Assertions written
 * against a harness that quiet would pass and fail for reasons unconnected to
 * this client.
 *
 * <p><b>What they buy.</b> Written once means the scrollback belongs to the
 * terminal: the wheel works, the history survives the client exiting, and a
 * `tee` still collects a transcript. No `?1049h` means none of that was traded
 * for a full-screen frame. Synchronised output means a frame cannot tear while
 * the transcript grows under the composer.
 *
 * <h2>Getting a pty, which is the awkward part</h2>
 *
 * <p>Node has no pty in its standard library; `node-pty` is a native
 * dependency this client will not take for a test; and `script`, which is the
 * usual answer, calls `tcgetattr` on <i>its own</i> stdin and fails outright
 * under a test runner that has no terminal — measured:
 * `script: tcgetattr/ioctl: Operation not supported on socket`.
 *
 * <p>So the pty comes from Python's `pty.fork`, which is in its standard
 * library and needs nothing installed. <b>And because that makes these tests
 * conditional, {@link describe} below is not the whole guard.</b> A second
 * group reads the source and holds the two lines that produce all three
 * properties — that group always runs, and it carries a self-check so it
 * cannot pass by failing to find the file.
 */

const HARNESS = fileURLToPath(new URL('./harness.ts', import.meta.url));
const MOUNTING = fileURLToPath(new URL('./mounting.ts', import.meta.url));
const APP = fileURLToPath(new URL('./app.ts', import.meta.url));

/** Whether a pty can be had here at all. */
function pythonic(): boolean {
  try {
    execFileSync('python3', ['-c', 'import pty'], { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
}

/**
 * Drives the harness under a real pty and returns every byte it wrote.
 *
 * <p>One key per write with a gap between, because a terminal coalesces keys
 * that arrive together into a single text chunk — which is how the composer's
 * Return came to arrive as ordinary text. The client copes with that now; this
 * types the way a person does so the test is about the client and not about a
 * buffer.
 */
const DRIVER = `
import fcntl, os, pty, select, struct, sys, termios, time
keys = sys.argv[2]
env = dict(os.environ)
pid, fd = pty.fork()
if pid == 0:
    os.execvpe('node', ['node', sys.argv[1]], env)
fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack('HHHH', 20, 80, 0, 0))
buf = bytearray()
def pump(seconds):
    end = time.time() + seconds
    while time.time() < end:
        r, _, _ = select.select([fd], [], [], 0.1)
        if r:
            try:
                chunk = os.read(fd, 65536)
            except OSError:
                return False
            if not chunk:
                return False
            buf.extend(chunk)
    return True
ready = env.get('PLOWSHARE_PTY_DIALOG_READY', '').encode()
if ready:
    deadline = time.monotonic() + 10
    while ready not in buf and time.monotonic() < deadline:
        if not pump(0.1):
            break
    # Input belongs to a visible dialog only after its accidental-typing grace.
    # Startup can take longer than a fixed sleep on a loaded CI runner.
    pump(float(env['PLOWSHARE_PTY_DIALOG_GRACE']) / 1000 + 0.1)
else:
    pump(1.2)
for key in keys:
    os.write(fd, key.encode())
    time.sleep(0.02)
    pump(0.05)
pump(1.0)
try:
    os.close(fd)
except OSError:
    pass
try:
    os.waitpid(pid, 0)
except ChildProcessError:
    pass
sys.stdout.buffer.write(bytes(buf))
`;

async function drawn(
  entries: number,
  keys = '',
  env: Record<string, string> = {},
): Promise<string> {
  const dialog = env['PLOWSHARE_HARNESS_DIALOG'];
  const ready =
    dialog === 'question'
      ? 'Which database?'
      : dialog === 'approval' || dialog === 'set'
        ? 'asks:'
        : dialog === undefined
          ? ''
          : 'stopped at its turn cap';
  const child = spawn('python3', ['-c', DRIVER, HARNESS, keys], {
    env: {
      ...process.env,
      PLOWSHARE_HARNESS_ENTRIES: String(entries),
      // Ink asks before it emits a single colour, and it does not know
      // this pty on its own.
      FORCE_COLOR: '3',
      TERM: 'xterm-256color',
      PLOWSHARE_PTY_DIALOG_READY: ready,
      PLOWSHARE_PTY_DIALOG_GRACE: String(DIALOG_GRACE),
      ...env,
    },
    stdio: ['ignore', 'pipe', 'ignore'],
  });
  const chunks: Buffer[] = [];
  child.stdout.on('data', (chunk: Buffer) => chunks.push(chunk));
  await new Promise<void>((done) => {
    child.on('close', () => done());
  });
  return Buffer.concat(chunks).toString('utf8');
}

/** How many times `text` appears in `stream`. */
function occurrences(stream: string, text: string): number {
  return stream.split(text).length - 1;
}

const withAPty = pythonic() ? describe : describe.skip;

withAPty('what reaches the terminal', () => {
  it('writes each completed entry exactly once, however many scroll past', async () => {
    // Forty entries into twenty rows: most scroll away, and that
    // scrolling has to be the terminal's doing rather than a redraw. A
    // repainting client writes the early lines again on every frame and
    // these counts run into the dozens.
    const stream = await drawn(40);
    expect(occurrences(stream, 'transcript line 003')).toBe(1);
    expect(occurrences(stream, 'transcript line 021')).toBe(1);
    expect(occurrences(stream, 'transcript line 040')).toBe(1);
  }, 30_000);

  it('never takes the alternate screen', async () => {
    expect(await drawn(5)).not.toContain('[?1049h');
  }, 30_000);

  it('uses synchronised output, so a frame cannot tear', async () => {
    expect(await drawn(5)).toContain('[?2026h');
  }, 30_000);

  it('draws the composer as a panel with an edge', async () => {
    const stream = await drawn(1);
    expect(stream).toContain('┃');
    expect(stream).toContain('❯');
  }, 30_000);

  it('draws the composer edge in a colour no theme maps to its background', async () => {
    // MEASURED FROM A REPORT: "the bar doesn't render unless I highlight
    // it". The border was `borderColor: 'gray'`, which Ink emits as SGR 90
    // -- bright black -- and on a dark theme bright black is frequently the
    // BACKGROUND. The box was drawn in the colour of the thing behind it and
    // only a text selection, inverting it, brought it back.
    //
    // The edge is now a palette colour, emitted as 256-colour or 24-bit,
    // which a theme cannot remap onto its background the way it remaps the
    // sixteen named colours. What must never come back is the named
    // bright-black (SGR 90) or dim (SGR 2) anywhere in the chrome.
    const stream = await drawn(1);
    // eslint-disable-next-line no-control-regex -- Intentional terminal control-sequence removal or its regression assertion.
    expect(stream).toMatch(/\u001b\[38;[25];[0-9;]+m┃/u);
    expect(stream).not.toContain('\u001b[90m');
    expect(stream).not.toContain('\u001b[2m');
  }, 30_000);

  it('shows the runs panel above the composer and hides it on Ctrl-O', async () => {
    const stream = await drawn(0, '\u000f', {
      PLOWSHARE_HARNESS_PANEL: 'orc_1  implement_specification',
    });
    expect(stream).toContain('orc_1  implement_specification');
    expect(stream).toContain('ctrl-o hides runs');
    expect(stream).toContain('ctrl-o shows runs');
    expect(stream).not.toContain('[?1049h');
  }, 30_000);

  it('fills the redrawn region with the viewer, never the whole terminal, and holds the chat until it closes', async () => {
    const stream = await drawn(30, 'q', { PLOWSHARE_HARNESS_VIEW: '60' });
    // Twenty rows: the newest record lines, not the oldest, and the transcript written once.
    expect(stream).toContain('record line 060');
    expect(stream).not.toContain('record line 001');
    expect(occurrences(stream, 'transcript line 003')).toBe(1);
    // As tall as the terminal, Ink would clear it and write the scrollback again.
    expect(stream).not.toContain('\u001b[2J');
    expect(stream).not.toContain('[?1049h');
    // Shown while the viewer was up, and written once, after it went away.
    expect(occurrences(stream, 'shown while the viewer was up')).toBe(1);
    expect(stream.indexOf('shown while the viewer was up')).toBeGreaterThan(
      stream.lastIndexOf('record line 060'),
    );
  }, 30_000);

  it('writes what was held for the viewer when Ctrl-D leaves from inside it', async () => {
    const stream = await drawn(3, '\u0004', { PLOWSHARE_HARNESS_VIEW: '60' });
    expect(stream).toContain('record line 060');
    expect(occurrences(stream, 'shown while the viewer was up')).toBe(1);
  }, 30_000);

  it('leaves the viewer on Ctrl-C and goes back to the composer', async () => {
    const stream = await drawn(3, '\u0003', { PLOWSHARE_HARNESS_VIEW: '60' });
    const held = stream.indexOf('shown while the viewer was up');
    expect(held).toBeGreaterThan(stream.lastIndexOf('record line 060'));
    expect(stream.indexOf('❯', held)).toBeGreaterThan(held);
  }, 30_000);

  it('draws the explorer over tabbed, escaped tool output without the alternate screen or a second copy', async () => {
    // Down into the inspector, over to the Result pane, then q: the stack trace is drawn.
    const stream = await drawn(3, '\r\tq', { PLOWSHARE_HARNESS_EXPLORE: '1' });
    // The tab became spaces and the tool's own colour and carriage return never reached the pty.
    expect(stream).toContain('    at explored.TokenizerTest.counts');
    expect(stream).not.toContain('\tat explored');
    expect(stream).not.toContain('\u001b[31mBUILD');
    expect(stream).not.toContain('[?1049h');
    expect(stream).not.toContain('\u001b[2J');
    expect(occurrences(stream, 'transcript line 003')).toBe(1);
  }, 30_000);

  it('gives a cap dialog its keys, and nothing else it does not mean', async () => {
    // `x` and Return mean nothing to it, and nothing reaches the composer behind it either.
    const stream = await drawn(0, 'x\ry', { PLOWSHARE_HARNESS_DIALOG: '1' });
    expect(stream).toContain('stopped at its turn cap');
    expect(stream).toContain('dialog answered: continue');
    expect(stream).not.toContain('Ask anything…x');
  }, 30_000);

  it('puts a cap dialog away for later on Esc, and the composer is back', async () => {
    const stream = await drawn(0, '\u001b', { PLOWSHARE_HARNESS_DIALOG: '1' });
    const later = stream.indexOf('dialog answered: later');
    expect(later).toBeGreaterThan(-1);
    expect(stream.indexOf('Ask anything…', later)).toBeGreaterThan(later);
  }, 30_000);

  it('takes a key that outran a cap dialog as typing, not as its answer', async () => {
    // The window stretched past the whole case: "now" typed as the dialog came up is the start
    // of a line, and its `n` never stops the run.
    const stream = await drawn(0, 'now\r', {
      PLOWSHARE_HARNESS_DIALOG: '1',
      PLOWSHARE_HARNESS_GRACE: '60000',
    });
    expect(stream).toContain('stopped at its turn cap');
    expect(stream).toContain('now');
    expect(stream).not.toContain('dialog answered: stop');
    expect(stream).not.toContain('dialog answered');
  }, 30_000);

  it("takes only a question dialog's own keys, and r leaves /answer and its id in the composer", async () => {
    // `y` is a cap's key and nothing to a question answered in words; the line sent after `r`
    // is the prefilled command finished by the person.
    const stream = await drawn(0, 'yrPostgreSQL\r', {
      PLOWSHARE_HARNESS_DIALOG: 'question',
    });
    expect(stream).toContain('Which database?');
    expect(stream).not.toContain('dialog answered: continue');
    expect(stream).toContain('dialog answered: reply');
    expect(stream).toContain('/answer orc_1 PostgreSQL');
  }, 30_000);

  it("answers a run's command approval with the approval prompt's own keys, p and its picker included", async () => {
    // `x` means nothing to it; `p` opens the prefix picker, redrawn at once with no grace; enter
    // allows the prefix it showed for the project.
    const stream = await drawn(0, 'xp\r', {
      PLOWSHARE_HARNESS_DIALOG: 'approval',
    });
    expect(stream).toContain('apr_1 (code_implementation) asks:');
    expect(stream).toContain(
      'o once · c for this conversation · p for this project · d deny',
    );
    expect(stream).toContain('allow any command starting: pytest -q');
    expect(stream).toContain(
      'approval answered: {"id":"apr_1","decision":"project","prefix":["pytest","-q"]}',
    );
    expect(stream).not.toContain('Ask anything…x');
  }, 30_000);

  it("shows an acceptance set's commands in the approval dialog, and takes no p for one", async () => {
    const stream = await drawn(0, 'po', { PLOWSHARE_HARNESS_DIALOG: 'set' });
    expect(stream).toContain('pytest -q tests/test_a.py');
    expect(stream).toContain('pytest -q tests/test_b.py');
    expect(stream).not.toContain('allow any command starting');
    expect(stream).toContain(
      'approval answered: {"id":"apr_1","decision":"once"}',
    );
  }, 30_000);

  it("leaves a run's approval for later on Esc, and the composer is back", async () => {
    const stream = await drawn(0, '\u001b', {
      PLOWSHARE_HARNESS_DIALOG: 'approval',
    });
    const later = stream.indexOf('approval answered: left');
    expect(later).toBeGreaterThan(-1);
    expect(stream.indexOf('Ask anything…', later)).toBeGreaterThan(later);
  }, 30_000);

  it('takes a typed line and shows it back', async () => {
    // The harness echoes whatever it is handed, so this reaching the screen
    // means the whole path worked: raw mode, `useInput`, the composer's
    // Return, the surface's queue, and the entry coming back out.
    expect(await drawn(0, 'a typed question\r')).toContain('a typed question');
  }, 30_000);
});

/**
 * The same three properties, read off the source, so something always runs.
 *
 * <p>Weaker than the group above and not a replacement for it — this cannot
 * tell whether `Static` <i>works</i>, only that it is what is being asked for.
 * It exists because the group above is conditional on a Python being present,
 * and a guard that quietly does not run is the failure mode this repository has
 * been bitten by more than once.
 */
describe('the two lines that produce them', () => {
  it('can see the files it asserts over', () => {
    // The self-check. Without it every assertion below passes on an empty
    // string the moment one of these files is renamed.
    expect(readFileSync(APP, 'utf8').length).toBeGreaterThan(1_000);
    expect(readFileSync(MOUNTING, 'utf8').length).toBeGreaterThan(1_000);
  });

  it('draws the transcript through Static, which is what writes it once', () => {
    const source = readFileSync(APP, 'utf8');
    expect(source).toContain("from 'ink'");
    expect(source).toMatch(/\bStatic\b/u);
    // Narrowed to `Transcript` for its item type; the alias is what is
    // actually rendered, so a mapped Box replacing it fails here.
    expect(source).toMatch(/el\(Transcript,/u);
  });

  it('leaves Ctrl-C to the conversation rather than to Ink', () => {
    // Ink's default exits the process, which is the behaviour this client
    // spent a task removing: the run goes on server-side whether or not
    // anybody listens, so the key everybody presses to stop something was
    // the key that abandoned it.
    //
    // MATCHED AS A LINE OF CODE AND NOT AS A STRING, which this got wrong
    // first time round. `mounting.ts` names the option twice — once where
    // it is passed and once in the paragraph above explaining why — so a
    // `toContain` was satisfied by the PROSE, and flipping the real option
    // to `true` changed nothing it could see. Measured, by doing exactly
    // that and watching every test pass.
    expect(readFileSync(MOUNTING, 'utf8')).toMatch(
      /^\s+exitOnCtrlC: false,$/mu,
    );
  });

  it('is not fooled by prose about the option', () => {
    // The self-check for the line above, in the shape `neutrality.test.ts`
    // established: a scan that has only ever been run against source that
    // satisfies it has not been shown to refuse anything.
    const prose =
      ' * <h2>`exitOnCtrlC: false`, which is not a detail</h2>\n' +
      '        exitOnCtrlC: true,\n';
    expect(prose).not.toMatch(/^\s+exitOnCtrlC: false,$/mu);
    expect(prose).toContain('exitOnCtrlC: false');
  });
});
