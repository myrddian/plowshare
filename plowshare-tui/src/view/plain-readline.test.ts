import { PassThrough } from 'node:stream';
import { describe, expect, it } from 'vitest';

import { plain } from './plain.ts';

/**
 * The prompt, smoke-tested.
 *
 * <b>Deliberately thin, and the plan says so: "test the emitter, not the
 * loop".</b> `toTerminal` is a pure function and gets thirty-two real
 * assertions next door; this is wiring around `node:readline`, and a suite that
 * re-asserted readline's own behaviour would be asserting Node.
 *
 * <p>What is worth holding is the part that is <i>this file's</i> and not
 * readline's: that a line typed comes back, that end-of-input is a `undefined`
 * rather than a hang — a person pressing Ctrl-D has to end the session, and a
 * loop that awaited forever there would need killing — and that {@link
 * Prompt.say} writes above the prompt without the cursor games when the output
 * is not a terminal. That last one is not decoration: `readline.cursorTo`
 * writes its escape sequence to whatever stream it is handed, so a scrollback
 * piped to a file would collect cursor moves nobody can read. The guard is one
 * `isTTY` check and this is where it is held.
 */

/**
 * The same, over an output that says it is a terminal.
 *
 * <b>Not a detail: it is what makes the two new cases below real ones.</b>
 * `readline` reads keys — a Tab, a Ctrl-C — only in terminal mode, and it takes
 * that mode from `output.isTTY`. A test driving them over the plain
 * {@link wired} pair would write three bytes into a line buffer and assert
 * nothing at all, which is precisely the "a completer nobody has invoked" trap
 * the plan names. So the output claims to be a TTY, real `readline` does its
 * own key handling, and what is asserted below is what a person pressing the
 * key would get.
 */
function atTerminal() {
  const input = new PassThrough();
  const output = new PassThrough();
  const written: string[] = [];
  output.on('data', (chunk: Buffer) => {
    written.push(chunk.toString('utf8'));
  });
  Object.defineProperty(output, 'isTTY', { value: true });
  // Node selects a deliberately reduced readline implementation when the
  // surrounding test runner has TERM=dumb. That implementation inserts Tab
  // literally and ignores the cursor-edit keys used to clear Ctrl-C's line,
  // contradicting this fixture's claim that it is a real terminal. TERM is
  // read only while the interface is built, so give that construction a
  // terminal type and put the runner's environment back immediately.
  const term = process.env['TERM'];
  process.env['TERM'] = 'xterm-256color';
  try {
    return { input, output, written, prompt: plain({ input, output }) };
  } finally {
    if (term === undefined) {
      delete process.env['TERM'];
    } else {
      process.env['TERM'] = term;
    }
  }
}

/** One turn of the loop, which is what readline acts on a key within. */
function tick(): Promise<void> {
  return new Promise<void>((done) => {
    setImmediate(done);
  });
}

/**
 * Keys pressed one at a time, as a terminal delivers them.
 *
 * <b>One write per key, and it is load-bearing rather than tidy.</b> Node's
 * keypress parser reads a run of printable characters arriving together as
 * text, so a Tab written in the same chunk as the word in front of it lands in
 * the line buffer as a tab character and completes nothing — measured, with
 * `/bo` + Tab + Return in one write coming back as the line `/bo\t`. A person
 * types a key at a time and that is what this does, so the cases below fail for
 * a completer that is missing rather than for a chunk that was written wrong.
 */
async function pressed(input: PassThrough, keys: string): Promise<void> {
  for (const key of keys) {
    input.write(key);
    await tick();
  }
  // One more: `_tabComplete` pauses the stream and resumes it from a
  // callback, so the last key is not always done with when its write is.
  await tick();
}

/** Tab. */
const TAB = '\t';
/** Return, which readline reads as the end of a line. */
const ENTER = '\r';
/** Ctrl-C, as a terminal delivers it to a program in raw mode. */
const INTERRUPT = '\u0003';

/** An input, an output, and the prompt over them. */
function wired() {
  const input = new PassThrough();
  const output = new PassThrough();
  const written: string[] = [];
  output.on('data', (chunk: Buffer) => {
    written.push(chunk.toString('utf8'));
  });
  return { input, output, written, prompt: plain({ input, output }) };
}

/**
 * The readline half of {@link plain}, which `prompt.ts` used to be.
 *
 * <b>These cases outlived the file they were written for, deliberately.</b>
 * `plain.ts` lifted the queues, the completer and the SIGINT handling verbatim
 * — that is the part with the bug history, and re-deriving it would have been
 * re-earning those bugs. Deleting the tests along with `prompt.ts` would have
 * left the lifted code uncovered, so they moved instead. The two that asserted
 * on `say` are gone: `plain.test.ts` re-asserts both against `show`, which is
 * the method that replaced it.
 */
describe('the readline surface', () => {
  it('hands back a line somebody typed', async () => {
    const { input, prompt } = wired();
    input.write('how many modules?\n');
    expect(await prompt.asked()).toBe('how many modules?');
    prompt.close();
  });

  it('hands back the lines in the order they were typed', async () => {
    const { input, prompt } = wired();
    input.write('one\ntwo\n');
    expect(await prompt.asked()).toBe('one');
    expect(await prompt.asked()).toBe('two');
    prompt.close();
  });

  it('answers undefined at the end of input rather than waiting forever', async () => {
    const { input, prompt } = wired();
    input.end();
    expect(await prompt.asked()).toBeUndefined();
  });

  it('answers undefined for every later ask once the input has ended', async () => {
    const { input, prompt } = wired();
    input.end();
    expect(await prompt.asked()).toBeUndefined();
    expect(await prompt.asked()).toBeUndefined();
  });
});

/**
 * <b>Tab, driven through the readline that ships rather than asserted off an
 * options object.</b>
 *
 * <p>The plan's instruction: <i>"a completer nobody has called is a completer
 * that might throw on its first Tab"</i>. So every case below presses the key
 * and reads back the line that came out of the interface — which is the only
 * form of this test that would catch a completer returning the wrong shape,
 * throwing on an empty line, or never being wired in at all.
 */
describe('what Tab does, which until now was nothing', () => {
  it('finishes a command, which this prompt used to ignore the key for', async () => {
    const { input, prompt } = atTerminal();
    await pressed(input, `/bot${TAB}${ENTER}`);
    expect(await prompt.asked()).toBe('/bots');
    prompt.close();
  });

  it('finishes a name once the server has declared it', async () => {
    const { input, prompt } = atTerminal();
    prompt.completing(['aristoxenus', 'close_reader']);
    await pressed(input, `aris${TAB}${ENTER}`);
    expect(await prompt.asked()).toBe('aristoxenus');
    prompt.close();
  });

  it('finishes no name the server did not send, and leaves the line as typed', async () => {
    const { input, prompt } = atTerminal();
    prompt.completing(['aristoxenus', 'close_reader']);
    await pressed(input, `hypa${TAB}${ENTER}`);
    expect(await prompt.asked()).toBe('hypa');
    prompt.close();
  });

  it('completes no name at all before a roster has arrived', async () => {
    // The window between the interface being built and `agent.list` being
    // answered. Commands are this client's own and complete throughout it;
    // a name completed here would be one nothing had declared.
    const { input, prompt } = atTerminal();
    await pressed(input, `aris${TAB}${ENTER}`);
    expect(await prompt.asked()).toBe('aris');
    prompt.close();
  });

  it('does not throw on a Tab with nothing in front of it', async () => {
    const { input, prompt } = atTerminal();
    prompt.completing(['aristoxenus']);
    await pressed(input, `${TAB}${ENTER}`);
    expect(await prompt.asked()).toBe('');
    prompt.close();
  });
});

/**
 * <b>Ctrl-C, raised as a key and not as an emitted event.</b>
 *
 * <p>What this file owns of it is the key handling: turning the byte into one
 * call, and clearing the half-typed line underneath. <i>What</i> the call means
 * — a cancel, and then a way out — is `main.ts`'s, and `composition.test.ts`
 * drives that end over a real socket.
 */
describe('what Ctrl-C does, which until now was quit without a word', () => {
  it('hands the press to whoever asked for it, and keeps the session', async () => {
    const { input, prompt } = atTerminal();
    let presses = 0;
    prompt.onInterrupt(() => {
      presses += 1;
    });
    await pressed(input, INTERRUPT);

    expect(presses).toBe(1);
    // AND THE PROMPT IS STILL LIVE. A person who pressed it once is still
    // in their session, with a prompt that takes the next thing they type.
    await pressed(input, `still here${ENTER}`);
    expect(await prompt.asked()).toBe('still here');
    prompt.close();
  });

  it('throws the half-typed line away, which is what the key means at a prompt', async () => {
    const { input, prompt } = atTerminal();
    prompt.onInterrupt(() => undefined);
    await pressed(input, 'half a quest');
    await pressed(input, INTERRUPT);
    await pressed(input, `what I meant${ENTER}`);

    // Not `half a questwhat I meant`, which is what a press that only
    // reported itself would have left behind.
    expect(await prompt.asked()).toBe('what I meant');
    prompt.close();
  });

  it('hands over the second press too, because the second press is the way out', async () => {
    // The counting is the caller's: this file knows nothing about runs, and
    // `main.ts` is where a first press differs from a second.
    const { input, prompt } = atTerminal();
    let presses = 0;
    prompt.onInterrupt(() => {
      presses += 1;
    });
    await pressed(input, INTERRUPT);
    await pressed(input, INTERRUPT);

    expect(presses).toBe(2);
    prompt.close();
  });

  it('ends the input when nobody has asked for the press', async () => {
    // The window before `converse` has a connection to cancel through. The
    // old behaviour for every press, kept for the one moment it is still
    // the honest one: there is nothing to stop, so the key leaves.
    const { input, prompt } = atTerminal();
    await pressed(input, INTERRUPT);
    expect(await prompt.asked()).toBeUndefined();
  });
});
