import { demoActivityPage } from 'plowshare-client-ts/operations/board-demo';
import { decodeReply } from 'plowshare-client-ts/operations/schema';
import type { Answer } from 'plowshare-client-ts/operations/response';
import { background } from '../background.ts';
import { demoBoard } from '../../logic/board-demo.ts';
import { inspectBoard } from '../board.ts';
import process from 'node:process';

import { parse } from '../../logic/markdown.ts';
import { entered, traced } from '../../logic/screen.ts';
import { traceGrew, traceOpened, turnOf } from '../../logic/trace.ts';
import {
  describeCallLines,
  describeCost,
  describeEnding,
  describeReasoningLines,
  describeTurnTimes,
} from '../../logic/wording.ts';
import { explore } from '../exploring.ts';
import { DEMO_ROOT, demoReads } from './demo-log.ts';
import { terminal } from './mounting.ts';

/**
 * `pnpm demo` — the trajectory UI over a canned session, no server. Draws the chat the way a
 * live turn would (a person's question, tool lines as they settle, one call still running, the
 * answer with its times), then opens the explorer. Keys work as they do for real; `q` leaves.
 *
 * <p><b>No `PLOWSHARE_DEMO_KEYS`.</b> The task brief asked for an optional string of keys fed to
 * the explorer after it opens, but there is no way to do that through {@link Surface} that is
 * simple rather than a second pty: `exploreKey` is answered by real keypresses `mounting.ts`
 * reads off its `stdin`, and a fabricated `stdin` was tried once already, for
 * `scrolling.test.ts` — Ink mounted, drew once, and then never read from it again. Task 15 drives
 * keys through a real pty instead; this file does not reinvent that.
 */

const surface = terminal({ colour: process.env['NO_COLOR'] === undefined });
const width = (process.stdout.columns ?? 100) - 1;
let keyed = 0;
const show = (
  voice: 'person' | 'bot' | 'client',
  text: string,
  note?: string,
): void => {
  keyed += 1;
  surface.show(entered(keyed, voice, parse(text), note));
};

show('person', 'fix the failing tokenizer test');
const turn = DEMO_ROOT.filter((entry) => entry.turnOrdinal === 3);
const settled = traceGrew(
  traceOpened(3),
  turn.filter(
    (entry) => entry.kind !== 'answer' || (entry.calls ?? []).length > 0,
  ),
);
keyed += 1;
surface.show(
  traced(
    keyed,
    settled.written.flatMap((step) =>
      step.kind === 'call'
        ? describeCallLines(step, 'compact', width)
        : describeReasoningLines(step, 'compact', width),
    ),
  ),
);
const sum = turnOf(settled.trace, 3);
// THE SAME COMPOSITION `main.ts` WRITES FOR A FINISHED TURN'S NOTE — `describeEnding` and
// `describeCost` over what this turn actually spent, `describeTurnTimes` appended the same way —
// so nothing here invents a count or a phrase of its own.
const times =
  sum !== undefined && sum.calls > 0 ? ` · ${describeTurnTimes(sum)}` : '';
const note =
  `this run ${describeEnding({ ending: 'ANSWERED' })}` +
  ` — ${describeCost({ steps: sum?.steps.length ?? 0, modelCalls: sum?.modelCalls ?? 0 })}${times}`;
show('bot', DEMO_ROOT.find((entry) => entry.ordinal === 19)?.text ?? '', note);
show('person', 'and the docs?');
const pending = traceGrew(
  traceOpened(19),
  DEMO_ROOT.filter((entry) => entry.turnOrdinal === 4),
);
surface.working({
  job: 'demo',
  since: Date.now() - 1300,
  phase: { kind: 'tool', tool: 'file_grep' },
  calls: pending.pending,
});

const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;

setTimeout(
  () => {
    surface.working(undefined);
    const context = {
      surface,
      reads: demoReads(),
      zone,
      appended: () => () => undefined,
      print: (lines: readonly string[]) =>
        lines.forEach((line) => show('client', line)),
    };
    const board = process.argv.includes('--board'),
      swarm = process.argv.includes('--swarm');
    const fixture = demoBoard();
    const viewing =
      board || swarm
        ? inspectBoard(
            {
              surface,
              print: context.print,
              read: (ask) =>
                Promise.resolve(
                  (() => {
                    const reply: Answer = (() => {
                      if (ask.type === 'board.topics')
                        return {
                          code: 'OK',
                          payload: {
                            topics:
                              Number(ask.payload['offset']) === 0
                                ? (fixture.topics.value ?? [])
                                : [],
                            more: false,
                            offset: ask.payload['offset'],
                          },
                        };
                      if (ask.type === 'board.messages')
                        return {
                          code: 'OK',
                          payload:
                            fixture.details[String(ask.payload['topic'])]
                              ?.value,
                        };
                      if (ask.type === 'conversation.trajectory') {
                        const activity =
                          fixture.activity?.[
                            String(ask.payload['conversation'])
                          ]?.value;
                        return {
                          code: 'OK',
                          payload: demoActivityPage(activity),
                        };
                      }
                      if (ask.type === 'swarm.status')
                        return { code: 'OK', payload: fixture.swarm.value };
                      return { code: 'NOT_FOUND' };
                    })();
                    return {
                      code: reply.code,
                      ...(reply.payload === undefined
                        ? {}
                        : { payload: decodeReply(ask.type, reply.payload) }),
                    };
                  })(),
                ),
              trajectory: async (_conversation, label) => {
                await explore(context, {
                  conversation: 'demo_root',
                  label,
                  view: 'trajectory',
                });
              },
            },
            swarm ? 'swarm' : 'board',
          )
        : explore(context, {
            conversation: 'demo_root',
            label: 'plowshare',
            view: 'trajectory',
            land: 'failure',
          });
    background(viewing.finally(() => surface.close()));
  },
  Number(process.env['PLOWSHARE_DEMO_PAUSE'] ?? '2500'),
);
