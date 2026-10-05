import { background } from '../background.ts';
import process from 'node:process';

import {
  askingAbout,
  pressingOn,
  settledAsking,
} from '../../logic/approval.ts';
import { parse } from '../../logic/markdown.ts';
import { entered } from '../../logic/screen.ts';
import type { Approval, Entry } from '../../logic/session.ts';
import { tint } from '../../logic/tints.ts';
import { describeApprovalDialog } from '../../logic/wording.ts';
import { explore } from '../exploring.ts';
import { terminal } from './mounting.ts';

/**
 * A terminal surface with nothing behind it, for `scrolling.test.ts` to drive.
 *
 * <h2>Why this exists as a file rather than as a fixture inside the test</h2>
 *
 * <p><b>Because the properties being tested are properties of a real
 * terminal</b>, and the only way to have one is to be a process with a pty for
 * a stdout. So the test spawns this, under `script`, and reads the bytes that
 * come back.
 *
 * <p><b>A fake stream will not do, and that is measured rather than assumed.</b>
 * The first version of these tests handed Ink a `PassThrough` carrying
 * `isTTY: true` and a `setRawMode` that did nothing. Ink mounted and drew the
 * first frame — and then `rerender` never reached the stream again and
 * `useInput` never fired once. Reduced to a spike with no code of this project
 * in it at all, Ink behaved the same way, so the fake was the problem and not
 * the client. A harness that quiet about failing is worse than none: every
 * assertion written against it would have passed or failed for reasons that
 * had nothing to do with this code.
 *
 * <h2>What it does</h2>
 *
 * <p>Shows `PLOWSHARE_HARNESS_ENTRIES` transcript entries — and a runs panel of the one line
 * `PLOWSHARE_HARNESS_PANEL`, when that is set — then, with `PLOWSHARE_HARNESS_VIEW` set, opens the
 * `/watch` viewer on that many record lines, shows an entry while it is up and closes it on the
 * first key it means something by; with `PLOWSHARE_HARNESS_EXPLORE` set, opens the explorer on a
 * log whose failed call printed a tab-indented stack trace and escape sequences, until `q` — then
 * echoes anything typed at it as another entry, until the input ends. With
 * `PLOWSHARE_HARNESS_DIALOG` set it puts up a cap dialog beside that prompt — a root's question
 * when it is `question`, whose `r` fills the composer; a command approval when it is `approval`,
 * or an acceptance set's when it is `set`, answered a key at a time as `converse` answers one —
 * taking keys as answers after
 * `PLOWSHARE_HARNESS_GRACE` milliseconds, and shows what answered it as an entry. Nothing
 * here talks to a
 * server: these tests are about drawing, and a run would make them about the
 * network.
 */

const many = Number.parseInt(
  process.env['PLOWSHARE_HARNESS_ENTRIES'] ?? '0',
  10,
);

const grace = process.env['PLOWSHARE_HARNESS_GRACE'];
const surface = terminal({
  colour: true,
  ...(grace === undefined ? {} : { dialogGrace: Number.parseInt(grace, 10) }),
});

for (let at = 1; at <= many; at += 1) {
  surface.show(
    entered(
      at,
      'client',
      parse(`transcript line ${String(at).padStart(3, '0')}`),
    ),
  );
}

const panel = process.env['PLOWSHARE_HARNESS_PANEL'];
if (panel !== undefined) {
  surface.panel?.({ lines: [[tint(panel)]], settled: [panel] });
}

const viewing = process.env['PLOWSHARE_HARNESS_VIEW'];
if (viewing !== undefined) {
  surface.view?.({
    head: [
      [tint('orc_1  implement_specification  3m 0s', 'strong')],
      [tint('  goal ✓ code ●')],
    ],
    body: Array.from({ length: Number.parseInt(viewing, 10) }, (_, at) => [
      tint(`09:05:03 · record line ${String(at + 1).padStart(3, '0')}`),
    ]),
    foot: 'milestones and tool activity · esc back',
  });
  surface.show(
    entered(many + 1, 'client', parse('shown while the viewer was up')),
  );
  // Undefined once the input ends: Ctrl-D, or the pty closing.
  await surface.viewKey?.();
  surface.view?.(undefined);
}

if (process.env['PLOWSHARE_HARNESS_EXPLORE'] !== undefined) {
  const row = (
    ordinal: number,
    kind: string,
    text: string,
    extra: Partial<Entry> = {},
  ): Entry => ({
    ordinal,
    turnOrdinal: 1,
    kind,
    state: 'stands',
    text,
    ...extra,
  });
  const entries: Entry[] = [
    row(1, 'utterance', 'run the tests'),
    row(2, 'answer', '', {
      calls: [
        {
          id: 'c1',
          name: 'run',
          arguments: '{"command":"./gradlew test"}',
          length: 28,
          cut: false,
          salient: './gradlew test',
        },
      ],
    }),
    row(
      3,
      'tool_result',
      [
        'exit 1',
        'java.lang.AssertionError: expected 3',
        '\tat explored.TokenizerTest.counts(TokenizerTest.java:41)',
        '\u001b[31mBUILD FAILED\u001b[0m\r',
      ].join('\n'),
      { toolCallId: 'c1', outcome: 'exit 1', tookMillis: 4200 },
    ),
  ];
  const page = { entries, through: 3, more: false, total: 3, oldest: 1 };
  await explore(
    {
      surface,
      zone: 'UTC',
      appended: () => () => undefined,
      print: () => undefined,
      reads: {
        tail: () => Promise.resolve(page),
        before: () => Promise.resolve(undefined),
        after: () => Promise.resolve([]),
        follow: () => Promise.resolve(undefined),
      },
    },
    { conversation: 'cnv_harness', label: 'harness', view: 'trajectory' },
  );
}

let next = many + 1;
const approving = process.env['PLOWSHARE_HARNESS_DIALOG'];
if (approving === 'approval' || approving === 'set') {
  // A COMMAND APPROVAL, answered with the approval prompt's own keys, redrawn a key at a time.
  const approval: Approval = {
    id: 'apr_1',
    conversation: 'cnv_conductor',
    agent: 'code_implementation',
    side: 'local',
    command: approving === 'set' ? [] : ['pytest', '-q', 'tests'],
    cwd: '/repo',
    state: 'asked',
    defaultPrefix: approving === 'set' ? [] : ['pytest', '-q'],
    ...(approving === 'set'
      ? {
          commands: [
            ['pytest', '-q', 'tests/test_a.py'],
            ['pytest', '-q', 'tests/test_b.py'],
          ],
        }
      : {}),
  };
  background(
    (async () => {
      let state = askingAbout(approval);
      let again = false;
      while (!settledAsking(state)) {
        const stroke = await surface.approvalDialog?.(
          describeApprovalDialog(approval, state, false),
          again,
        );
        if (stroke === undefined) {
          break;
        }
        state = pressingOn(state, stroke);
        again = true;
      }
      next += 1;
      surface.show(
        entered(
          next,
          'client',
          parse(
            `approval answered: ${
              state.kind === 'answered'
                ? JSON.stringify(state.ask.payload)
                : state.kind
            }`,
          ),
        ),
      );
    })(),
  );
} else if (process.env['PLOWSHARE_HARNESS_DIALOG'] === 'question') {
  // A ROOT'S QUESTION, whose `r` leaves `/answer` and its id in the composer as `converse` does.
  background(
    surface
      .capDialog?.(
        [
          'orc_1 (implement_specification) asks:',
          'Which database?',
          'r reply · w watch · esc later',
        ],
        ['reply', 'watch', 'later'],
      )
      .then((key) => {
        next += 1;
        surface.show(
          entered(
            next,
            'client',
            parse(`dialog answered: ${key ?? 'nothing'}`),
          ),
        );
        if (key === 'reply') {
          surface.prefill?.('/answer orc_1 ');
        }
      }),
  );
} else if (process.env['PLOWSHARE_HARNESS_DIALOG'] !== undefined) {
  background(
    surface
      .capDialog?.([
        'code_implementation (orc_2) stopped at its turn cap',
        'last: nothing recorded yet',
        'y continue · n stop · a always (auto-continue 3) · w watch · esc later',
      ])
      .then((key) => {
        next += 1;
        surface.show(
          entered(
            next,
            'client',
            parse(`dialog answered: ${key ?? 'nothing'}`),
          ),
        );
      }),
  );
}
for (;;) {
  const line = await surface.asked();
  if (line === undefined) {
    break;
  }
  next += 1;
  surface.show(entered(next, 'person', parse(line)));
}
surface.close();
