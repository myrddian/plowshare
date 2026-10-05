import { describe, expect, it, vi } from 'vitest';

import type {
  Agent,
  Definition,
  Entry,
  Proposal,
  Run,
  RunStage,
  Structure,
  Waiting,
} from './session.ts';
import {
  COMMANDS,
  describeCommand,
  describeEnding,
  describeFailure,
  describeFired,
  describeFirings,
  describeHelp,
  describeInbox,
  describeNotSaved,
  describeProposal,
  describeSchedules,
  describeUsage,
  describeWhen,
  describeDiagnosing,
  describeNoDiagnosis,
  describePace,
  describePhase,
  describeStanding,
  describeAgent,
  describeOrchestration,
  describeOrchestrations,
  describeRun,
  describeRuns,
  AGENTS_COMMAND,
  DIAGNOSE_COMMAND,
  FIRE_COMMAND,
  FIRINGS_COMMAND,
  INBOX_COMMAND,
  ORCHESTRATIONS_COMMAND,
  RUNS_COMMAND,
  SCHEDULE_COMMAND,
  CANCEL_COMMAND,
  ANSWER_COMMAND,
  describeNowAsking,
  describeWaitingCount,
  describeWaitingRuns,
  waitingOffers,
  describeApprovalDialog,
  describeApprovalSettled,
  describeApprovalAnswered,
  ALWAYS_COMMAND,
  describeAlways,
  describeAlwaysNeedsAProject,
  describeCut,
  describeHarness,
  describeSpokenIn,
  describeChecklist,
  describeChecklistTinted,
  describePanel,
  describePhaseChildren,
  describeRecorded,
  describeTree,
  describeWatch,
  watchRowHeight,
  WATCH_COMMAND,
  describeCapDialog,
  describeCapSettled,
  describeQuestionDialog,
  describeQuestionsDialog,
  describeQuestionSettled,
  describeReplyWith,
  DIALOG_QUESTION_LINES,
  DIALOG_CHECK_LINES,
  CAP_COMMAND,
  describeCaps,
  dialogOptionsOf,
  describePickingDialog,
  describeCallLines,
  describePendingLine,
  describeTraceTurn,
  describeTurnTimes,
  nextDensity,
  describeDensity,
  describeReasoningLines,
  describeExplorer,
  describeExplorerListing,
  explorerScrollLimit,
} from './wording.ts';
import type { Recorded, Tree, Watch } from './record.ts';
import { askingAbout } from './approval.ts';
import {
  answeringAbout,
  answeringOn,
  type QuestionStroke,
} from './questions.ts';
import type { Call, Step } from './trajectory.ts';
import { plainOf } from './tints.ts';
import {
  explorerKeyed,
  explorerOpened,
  levelOf,
  type Explorer,
} from './explorer.ts';
import { pickingAbout } from './caps.ts';

describe('the diagnosis command', () => {
  it('is advertised with its optional target and explains both honest states', () => {
    expect(COMMANDS).toContain(DIAGNOSE_COMMAND);
    expect(describeHelp().join('\n')).toContain(
      '/diagnose [conversation-id] [-- question]',
    );
    expect(describeNoDiagnosis()).toContain(
      '/diagnose <conversation-id> [-- question]',
    );
    expect(describeDiagnosing('cnv_target', 'cnv_diagnostic')).toContain(
      'cnv_target',
    );
    expect(describeDiagnosing('cnv_target', 'cnv_diagnostic')).toContain(
      'cnv_diagnostic',
    );
  });
});

describe('the inbox command', () => {
  it('offers /inbox and says what it is', () => {
    expect(COMMANDS).toContain(INBOX_COMMAND);
    expect(describeHelp().join('\n')).toContain('/inbox');
  });

  it('describes an empty inbox plainly and an item by when, how it ended and what it said', () => {
    expect(describeInbox({ items: [], unread: 0 })).toEqual([
      'nothing unread in your inbox',
    ]);
    const lines = describeInbox({
      items: [
        {
          id: 'inb_1',
          arrivedAt: '2026-09-13T09:02:00Z',
          kind: 'run',
          ending: 'ANSWERED',
          answer: 'three PRs',
        },
      ],
      unread: 1,
    });
    expect(lines.join('\n')).toContain('2026-09-13T09:02:00Z · ANSWERED');
    expect(lines.join('\n')).toContain('three PRs');
  });
});

describe('describeInbox with notices', () => {
  it('heads a notice with its kind, since it has no ending', () => {
    expect(
      describeInbox({
        unread: 1,
        items: [
          {
            id: 'inb_1',
            arrivedAt: '2026-09-14T10:00:00Z',
            kind: 'sync.conflict',
            answer: 'ledger: src/a.ts',
          },
        ],
      }),
    ).toEqual(['2026-09-14T10:00:00Z · sync conflict', '  ledger: src/a.ts']);
  });

  it('heads a run result with its ending, as before', () => {
    expect(
      describeInbox({
        unread: 1,
        items: [
          {
            id: 'inb_2',
            arrivedAt: '2026-09-14T10:00:00Z',
            kind: 'run',
            ending: 'DONE',
            answer: 'ok',
          },
        ],
      }),
    ).toEqual(['2026-09-14T10:00:00Z · DONE', '  ok']);
  });
});

describe('the status line', () => {
  const at = { who: 'coder', server: 'http://127.0.0.1:8080' };

  it('names who, the server signed in to, and the model, colon-separated', () => {
    expect(describeStanding({ ...at, model: 'qwen3-coder' }).triplet).toBe(
      'coder:127.0.0.1:8080:qwen3-coder',
    );
    expect(
      describeStanding({ ...at, server: 'https://plowshare.lan/' }).triplet,
    ).toBe('coder:plowshare.lan');
    expect(
      describeStanding({ ...at, server: 'http://me@studio:8080/api?x#y' })
        .triplet,
    ).toBe('coder:studio:8080');
  });

  it('leaves the model off rather than inventing one', () => {
    expect(describeStanding(at)).toEqual({ triplet: 'coder:127.0.0.1:8080' });
  });

  it('shows the load as used over window and a percentage', () => {
    expect(describeStanding({ ...at, sent: 16_234, limit: 120_000 })).toEqual({
      triplet: 'coder:127.0.0.1:8080',
      load: 'peak 16.2K/120K 14%',
      filled: 16_234 / 120_000,
    });
    expect(describeStanding({ ...at, sent: 950, limit: 64_000 }).load).toBe(
      'peak 950/64K 1%',
    );
    expect(
      describeStanding({ ...at, sent: 1_234_567, limit: 2_000_000 }).load,
    ).toBe('peak 1.2M/2M 62%');
  });

  it('says a dash before anything is measured, never a zero', () => {
    expect(describeStanding({ ...at, limit: 120_000 }).load).toBe('—/120K');
    expect(describeStanding({ ...at, limit: 120_000 }).filled).toBe(0);
    expect(describeStanding({ ...at, sent: 40, limit: 120_000 }).load).toBe(
      'peak 40/120K 1%',
    );
  });

  it('shows what was sent alone when the window is unknown', () => {
    expect(describeStanding({ ...at, sent: 16_234 })).toEqual({
      triplet: 'coder:127.0.0.1:8080',
      load: 'peak 16.2K',
    });
  });

  it('is filling from 70% and full from 90%', () => {
    expect(
      describeStanding({ ...at, sent: 69_000, limit: 100_000 }).pressure,
    ).toBeUndefined();
    expect(
      describeStanding({ ...at, sent: 70_000, limit: 100_000 }).pressure,
    ).toBe('filling');
    expect(
      describeStanding({ ...at, sent: 90_000, limit: 100_000 }).pressure,
    ).toBe('full');
    expect(
      describeStanding({ ...at, sent: 130_000, limit: 100_000 }).load,
    ).toBe('peak 130K/100K 130%');
  });
});

describe('scheduling, in the client voice', () => {
  const PROPOSAL: Proposal = {
    cron: '0 9 * * 1-5',
    zone: 'Australia/Sydney',
    when: 'every weekday at 9am',
    agent: 'interlocutor',
    task: 'summarise what changed yesterday',
    intoConversation: false,
    nextFires: [
      '2026-09-14T23:00:00Z',
      '2026-09-15T23:00:00Z',
      '2026-09-16T23:00:00Z',
    ],
    names: { schedule: 'summarise', trigger: 'summarise', event: 'summarise' },
  };

  it('lists the three scheduling commands in the help, each with what it is for', () => {
    for (const command of [SCHEDULE_COMMAND, FIRE_COMMAND, FIRINGS_COMMAND]) {
      expect(COMMANDS).toContain(command);
      expect(describeCommand(command)).not.toBe('');
    }
    const help = describeHelp().join('\n');
    expect(help).toContain(
      '/schedule <sentence> | list | pause|resume|forget <name>',
    );
    expect(help).not.toContain('/schedules');
    expect(help).toContain('/fire <name>');
  });

  it('formats a moment in the zone it is shown in', () => {
    expect(describeWhen('2026-09-14T23:00:00Z', 'Australia/Sydney')).toBe(
      'Tue 15 Sep 09:00',
    );
    expect(describeWhen('2026-09-14T23:00:00Z', 'UTC')).toBe(
      'Mon 14 Sep 23:00',
    );
    // A zone this runtime cannot name, or a moment it cannot read, is shown
    // as it arrived rather than thrown over.
    expect(describeWhen('2026-09-14T23:00:00Z', 'Nowhere/Atlantis')).toBe(
      '2026-09-14T23:00:00Z',
    );
    expect(describeWhen('not a time', 'UTC')).toBe('not a time');
    // Midnight is 00, never the 24 some hour cycles write.
    expect(describeWhen('2026-09-14T00:05:00Z', 'UTC')).toBe(
      'Mon 14 Sep 00:05',
    );
  });

  it('shows the whole proposal: when beside the fire times, who, where, results and the task', () => {
    expect(describeProposal({ ...PROPOSAL, project: 'plowshare' })).toEqual([
      'every weekday at 9am → 0 9 * * 1-5 (Australia/Sydney)',
      'next: Tue 15 Sep 09:00 · Wed 16 Sep 09:00 · Thu 17 Sep 09:00',
      'agent: interlocutor · runs in: plowshare · results: your inbox',
      'task: summarise what changed yesterday',
      'save? [y/n]',
    ]);
  });

  it('says global for no project, and this conversation for results that go there', () => {
    expect(describeProposal(PROPOSAL)[2]).toBe(
      'agent: interlocutor · runs in: global · results: your inbox',
    );
    expect(
      describeProposal({
        ...PROPOSAL,
        intoConversation: true,
        conversation: 'cnv_1',
      })[2],
    ).toContain('results: this conversation');
  });

  it('keeps a task that spans lines, every line of it', () => {
    const lines = describeProposal({
      ...PROPOSAL,
      task: 'first *this*\nthen _that_\n\nlast',
    });
    expect(lines.join('\n')).toContain(
      'task: first *this*\n      then _that_\n      \n      last',
    );
    expect(lines.at(-1)).toBe('save? [y/n]');
  });

  it('lists each schedule with the triggers listening to it, and orphans at the end', () => {
    const lines = describeSchedules(
      [
        {
          name: 'daily',
          cron: '0 9 * * *',
          zone: 'UTC',
          emits: 'daily',
          paused: false,
          nextFireAt: '2026-09-14T09:00:00Z',
        },
        {
          name: 'weekly',
          cron: '0 9 * * 1',
          zone: 'UTC',
          emits: 'weekly',
          paused: true,
        },
      ],
      [
        {
          name: 'daily',
          event: 'daily',
          agent: 'interlocutor',
          paused: false,
          task: `say hello ${'and more '.repeat(20)}\nsecond line`,
        },
        {
          name: 'stray',
          event: 'nothing',
          agent: 'close_reader',
          paused: true,
          task: 'lost',
        },
      ],
    );
    expect(lines[0]).toBe('daily · 0 9 * * * (UTC) · next Mon 14 Sep 09:00');
    expect(lines[1]).toMatch(/^ {2}→ interlocutor: say hello and more/);
    expect(lines[1]).toContain('…');
    expect(lines[1]).not.toContain('second line');
    expect(lines[1]?.length ?? 0).toBeLessThan(90);
    expect(lines[2]).toBe('weekly · 0 9 * * 1 (UTC) · paused');
    expect(lines[3]).toBe('triggers with no schedule:');
    expect(lines[4]).toBe('  → close_reader: lost · paused');
    expect(describeSchedules([], [])).toEqual(['no schedules']);
    expect(
      describeSchedules(
        [],
        [{ name: 's', event: 'e', agent: 'a', paused: false, task: 't' }],
      ),
    ).toEqual(['no schedules', 'triggers with no schedule:', '  → a: t']);
  });

  it('lists firings newest first as when, who and how it went', () => {
    expect(
      describeFirings(
        [
          {
            id: 'f2',
            event: 'daily',
            status: 'REFUSED',
            arrivedAt: '2026-09-14T09:00:01Z',
            reason: 'the trigger is paused',
          },
          {
            id: 'f1',
            event: 'daily',
            status: 'DONE',
            arrivedAt: '2026-09-13T09:00:01Z',
            trigger: 'daily',
          },
        ],
        'UTC',
      ),
    ).toEqual([
      'Mon 14 Sep 09:00 · (nobody listening) · REFUSED · the trigger is paused',
      'Sun 13 Sep 09:00 · daily · DONE',
    ]);
    expect(describeFirings([], 'UTC')).toEqual(['no firings yet']);
  });

  it('says what firing a schedule by hand did, one line a firing', () => {
    expect(
      describeFired('daily', [
        {
          id: 'f1',
          event: 'daily',
          status: 'QUEUED',
          arrivedAt: 'x',
          trigger: 'daily',
        },
        {
          id: 'f2',
          event: 'daily',
          status: 'REFUSED',
          arrivedAt: 'x',
          trigger: 'other',
          reason: 'paused',
        },
      ]),
    ).toEqual(['daily · QUEUED', 'other · REFUSED · paused']);
    expect(describeFired('daily', [])[0]).toContain('nothing is listening');
  });

  it('has a usage line for each scheduling command that needs more', () => {
    expect(describeUsage('/schedule pause')).toContain(
      '/schedule pause <name>',
    );
    expect(describeUsage('/schedule forget')).toContain(
      '/schedule forget <name>',
    );
    expect(describeUsage('/fire')).toContain('/fire <name>');
    expect(describeUsage('/fire')).toContain('/schedule list');
    expect(describeUsage('/project')).toContain('/projects');
    expect(describeUsage('/cd')).toContain('directory');
  });

  it('says a declined proposal was not saved, and that a line typed instead was not run', () => {
    expect(describeNotSaved(false)).toBe('not saved');
    expect(describeNotSaved(true)).toContain('not saved');
    expect(describeNotSaved(true)).toContain('type it again');
  });
});

describe("a run's pace and the phase it is in", () => {
  it('says each phase as the working line names it', () => {
    expect(describePhase({ kind: 'processing' })).toBe('processing prompt');
    expect(describePhase({ kind: 'thinking' })).toBe('thinking');
    expect(describePhase({ kind: 'responding' })).toBe('responding');
    expect(describePhase({ kind: 'tool', tool: 'read_file' })).toBe(
      'calling read_file',
    );
  });

  it('says a measured pace in parts, most important first', () => {
    expect(
      describePace({
        toolCalls: 3,
        completionTokens: 1_210,
        reasoningTokens: 412,
        firstTokenMillis: 1_830,
        tokensPerSecond: 38.4,
      }),
    ).toEqual([
      { kind: 'tools', text: '3' },
      { kind: 'thinking', text: 'think 412' },
      { kind: 'responding', text: 'out 1.2K' },
      { kind: 'waiting', text: 'ttft 1.8s' },
      { kind: 'speed', text: '38 tok/s' },
    ]);
  });

  it('marks thinking the server estimated, and only that', () => {
    expect(
      describePace({
        toolCalls: 0,
        reasoningTokens: 812,
        reasoningEstimated: true,
      })[1],
    ).toEqual({ kind: 'thinking', text: 'think ~812' });
    expect(describePace({ toolCalls: 0, reasoningTokens: 0 })[1]).toEqual({
      kind: 'thinking',
      text: 'think 0',
    });
  });

  it('says a dash for what was not measured, never a zero', () => {
    expect(describePace({ toolCalls: 0 }).map((part) => part.text)).toEqual([
      '0',
      'think —',
      'out —',
      'ttft —',
      '— tok/s',
    ]);
  });

  it('keeps the units a person reads at a glance', () => {
    const text = (pace: Parameters<typeof describePace>[0]) =>
      describePace(pace).map((part) => part.text);
    expect(
      text({ toolCalls: 0, firstTokenMillis: 850, tokensPerSecond: 7.46 }),
    ).toContain('ttft 850ms');
    expect(
      text({ toolCalls: 0, firstTokenMillis: 850, tokensPerSecond: 7.46 }),
    ).toContain('7.5 tok/s');
    expect(text({ toolCalls: 0, firstTokenMillis: 2_000 })).toContain(
      'ttft 2s',
    );
    expect(text({ toolCalls: 0, firstTokenMillis: 75_000 })).toContain(
      'ttft 1m 15s',
    );
  });

  it('carries the pace on the status line only when there is one', () => {
    const at = { who: 'coder', server: 'http://127.0.0.1:8080' };
    expect(describeStanding(at)).not.toHaveProperty('pace');
    expect(
      describeStanding({ ...at, pace: { toolCalls: 2 } }).pace?.[0],
    ).toEqual({ kind: 'tools', text: '2' });
  });
});

describe('the orchestration listings', () => {
  const SPEC: Definition = {
    name: 'spec_driven_coding',
    description: 'writes the spec, then the code',
    tier: 'project',
    served: true,
    withheld: '',
    stages: [
      { id: 'design', doneWhen: 'the design is agreed' },
      { id: 'build' },
    ],
    triggers: ['somebody asks for a feature'],
  };
  const BROKEN: Definition = {
    name: 'broken',
    description: '',
    tier: '',
    served: false,
    withheld: 'stages: expected a list',
    stages: [],
    triggers: [],
  };

  it('offers both commands and says what each is', () => {
    expect(COMMANDS).toContain(ORCHESTRATIONS_COMMAND);
    expect(COMMANDS).toContain(RUNS_COMMAND);
    expect(describeHelp().join('\n')).toContain('/orchestrations [name]');
    expect(describeHelp().join('\n')).toContain('/runs [id]');
    expect(describeCommand(RUNS_COMMAND)).toContain('orchestration');
  });

  it('says which set it showed, and shows a refused file as a row with its reason', () => {
    const lines = describeOrchestrations([SPEC, BROKEN], 'plowshare');

    expect(lines[0]).toContain('plowshare');
    const said = lines.join('\n');
    expect(said).toContain('spec_driven_coding');
    expect(said).toContain('project');
    expect(said).toContain('writes the spec, then the code');
    expect(said).toContain('design');
    expect(said).toContain('build');
    expect(said).toContain('somebody asks for a feature');
    expect(said).toContain('stages: expected a list');
  });

  it('names the global tier when there is no project, and says when there are none', () => {
    expect(describeOrchestrations([], undefined)[0]).toContain(
      'global resources',
    );
    expect(describeOrchestrations([], 'plowshare')[0]).toContain(
      'no orchestrations',
    );
  });

  it('shows one definition with its stages one per line, and its done-when where there is one', () => {
    const lines = describeOrchestration(
      'spec_driven_coding',
      [SPEC, BROKEN],
      'plowshare',
    );

    expect(lines.join('\n')).toContain('the design is agreed');
    expect(lines.filter((line) => line.includes('design'))).toHaveLength(1);
    expect(lines.filter((line) => line.includes('build'))).toHaveLength(1);
    // A name nothing answers to is said as that, not as an empty view.
    expect(describeOrchestration('nope', [SPEC], 'plowshare')[0]).toContain(
      'nope',
    );
    expect(describeOrchestration('nope', [SPEC], 'plowshare')[0]).toContain(
      ORCHESTRATIONS_COMMAND,
    );
    // And a refused file's one screen is its reason.
    expect(
      describeOrchestration('broken', [BROKEN], undefined).join('\n'),
    ).toContain('stages: expected a list');
    // A reason this build could not read still says it was refused,
    // rather than trailing off after a colon.
    const silent = describeOrchestration(
      'broken',
      [{ ...BROKEN, withheld: '' }],
      undefined,
    );
    expect(silent[0]).toContain('could not be read');
    expect(silent[0]).not.toMatch(/:\s*$/);
    expect(
      describeOrchestrations([{ ...BROKEN, withheld: '' }], 'plowshare').join(
        '\n',
      ),
    ).not.toMatch(/:\s*$/);
  });
});

describe('the run listings', () => {
  const RUN: Run = {
    id: 'orc_1',
    definition: 'spec_driven_coding',
    tier: 'project',
    project: 'plowshare',
    state: 'running',
    depth: 0,
    createdAt: '2026-09-23T09:00:00Z',
  };
  const CHILD: Run = {
    ...RUN,
    id: 'orc_2',
    parent: 'orc_1',
    depth: 1,
    state: 'finished',
  };

  it('shows each run newest first with the parent of a child, and points at one run', () => {
    const lines = describeRuns([RUN, CHILD], 'UTC');

    expect(lines[0]).toContain('orc_1');
    expect(lines[0]).toContain('spec_driven_coding');
    expect(lines[0]).toContain('running');
    expect(lines[0]).toContain('23 Sep');
    expect(lines[1]).toContain('orc_1');
    expect(lines.join('\n')).toContain(`${RUNS_COMMAND} <id>`);
    expect(describeRuns([], 'UTC')[0]).toContain('no orchestration runs');
  });

  it("shows one run's stages, what it asked and the children it started", () => {
    const lines = describeRun(
      {
        run: { ...RUN, state: 'asking', waitingFor: 'orc_3' },
        stages: [
          {
            text: 'design it',
            status: 'done',
            summary: 'agreed',
            stage: 'design',
          },
        ],
        messages: [
          { kind: 'question', author: 'conductor', text: 'which database?' },
        ],
        children: [{ id: 'orc_2', state: 'finished' }],
      },
      'UTC',
    );
    const said = lines.join('\n');

    expect(said).toContain('orc_1');
    expect(said).toContain('asking');
    expect(said).toContain('orc_3');
    expect(said).toContain('design it');
    expect(said).toContain('done');
    expect(said).toContain('agreed');
    expect(said).toContain('question');
    expect(said).toContain('conductor');
    expect(said).toContain('which database?');
    expect(said).toContain('orc_2');
    expect(said).toContain('finished');
  });

  it('shows what a run came to, which is why a person opens one that has ended', () => {
    const finished = describeRun(
      {
        run: {
          ...RUN,
          state: 'finished',
          result: 'the suite is green',
          endedAt: '2026-09-23T10:00:00Z',
        },
        stages: [],
        messages: [],
        children: [],
      },
      'UTC',
    ).join('\n');
    expect(finished).toContain('result: the suite is green');

    const failed = describeRun(
      {
        run: {
          ...RUN,
          state: 'failed',
          failure: 'the conductor ran out of turns',
        },
        stages: [],
        messages: [],
        children: [],
      },
      'UTC',
    ).join('\n');
    expect(failed).toContain('the conductor ran out of turns');
    expect(failed).not.toContain('result:');
  });

  it('names the cap beside the state, since asking for one is not asking about the work', () => {
    const capped = { ...RUN, state: 'asking', pendingCap: 'turn_cap' };
    expect(describeRuns([capped], 'UTC')[0]).toContain('asking (turn_cap)');
    expect(
      describeRun(
        { run: capped, stages: [], messages: [], children: [] },
        'UTC',
      )[0],
    ).toContain('asking (turn_cap)');
  });

  it('leaves out a part that came back empty rather than separating nothing', () => {
    // `runIn` requires only the id and the state, so the rest can be empty —
    // and `orc_1 ·  ·  ·` reads as a broken client rather than as a gap.
    const bare = describeRuns(
      [
        {
          id: 'orc_1',
          definition: '',
          tier: '',
          state: 'running',
          depth: 0,
          createdAt: '',
        },
      ],
      'UTC',
    );
    expect(bare[0]).toBe('orc_1 · running');
  });

  it('says a run with nothing under it yet is that, rather than showing empty headings', () => {
    const lines = describeRun(
      { run: RUN, stages: [], messages: [], children: [] },
      'UTC',
    );

    expect(lines.join('\n')).not.toContain('stages:');
    expect(lines.join('\n')).not.toContain('messages:');
    expect(lines.join('\n')).not.toContain('children:');
  });
});

describe("/agents <name>, which is the only place an agent's capabilities are listed", () => {
  const READER: Agent = {
    name: 'close_reader',
    bot: false,
    served: true,
    withheld: [],
    description: 'reads a diff and says what changed',
    preferred: false,
    model: 'reasoning',
    tools: ['read_file', 'memory_write'],
    calls: [],
    scopes: ['workspace:read'],
    orchestrations: ['spec_driven_coding'],
  };

  it('is advertised as taking an optional name', () => {
    expect(describeHelp().join('\n')).toContain('/agents [name]');
  });

  it('shows what this one agent holds, and not what the server has', () => {
    const lines = describeAgent('close_reader', [READER]);
    const said = lines.join('\n');

    expect(said).toContain('close_reader');
    expect(said).toContain('reads a diff and says what changed');
    expect(said).toContain('reasoning');
    expect(said).toContain('read_file');
    expect(said).toContain('workspace:read');
    expect(said).toContain('spec_driven_coding');
    // It may not delegate, and that is said rather than left blank.
    expect(said).toMatch(/delegate/);
  });

  it('says why an agent is not served, and says so when nothing answers to the name', () => {
    const refused = describeAgent('hermippus', [
      {
        name: 'hermippus',
        bot: false,
        served: false,
        withheld: ['bot: expected a boolean'],
        description: '',
        preferred: false,
        tools: [],
        calls: [],
        scopes: [],
        orchestrations: [],
      },
    ]);
    expect(refused.join('\n')).toContain('bot: expected a boolean');
    expect(describeAgent('nobody', [READER])[0]).toContain('nobody');
    expect(describeAgent('nobody', [READER])[0]).toContain(AGENTS_COMMAND);
  });
});

describe('how a failed run is accounted for', () => {
  const FAILED = {
    ending: 'UNAVAILABLE',
    answered: false,
    sentence:
      "This run could not go on: something the tool 'memory_recall' needs could not" +
      ' be reached.',
    detail:
      'memory_recall: EmbeddingException: no embedding model is configured',
  };

  it('keeps the ending a clause, so the line it is built into still reads', () => {
    // The push-event path has no sentence to prefer and never will:
    // `JobEvent` carries the ending's name and no failure text at all. So
    // this stays a clause finishing "this run ...", and describeFailure
    // carries the server's words separately.
    expect(`this run ${describeEnding(FAILED)}`).toBe(
      'this run could not reach something it depends on, and gave up',
    );
  });

  it("passes on both of the server's facts, because neither names the other", () => {
    const why = describeFailure(FAILED);

    // The sentence names the tool and not the endpoint; the detail names the
    // endpoint's complaint and not the tool.
    expect(why).toContain(
      "the tool 'memory_recall' needs could not be reached",
    );
    expect(why).toContain('no embedding model is configured');
    expect(why).toBe(
      'the server said: ' + FAILED.sentence + ' — ' + FAILED.detail,
    );
  });

  it('distinguishes the three stops one UNAVAILABLE is', () => {
    const model = describeFailure({
      ending: 'UNAVAILABLE',
      answered: false,
      sentence: 'This run could not go on: the model could not be reached.',
      detail:
        "LlmTransportException: pool 'local' chat request failed: HTTP 404",
    });
    const submitting = describeFailure({
      ending: 'UNAVAILABLE',
      answered: false,
      sentence:
        'This run could not go on: submitting to the model failed for a reason that' +
        ' is not the endpoint being unreachable.',
      detail:
        'IllegalArgumentException: no pool serves model ollama/qwen3.5-9b',
    });

    expect(model).not.toBe(submitting);
    expect(model).toContain('HTTP 404');
    expect(submitting).toContain('no pool serves model');
  });

  it('says nothing about a run that answered, whose text is its answer', () => {
    // `text` is the whole answer on ANSWERED and is shown as the answer; a
    // truncation note rides in `detail` there, and no surface has been
    // designed for it.
    expect(
      describeFailure({
        ending: 'ANSWERED',
        answered: true,
        detail: "the answer was truncated at the model's output ceiling",
      }),
    ).toBeUndefined();
  });

  it('says nothing when the server said nothing, rather than an empty quote', () => {
    expect(
      describeFailure({ ending: 'CANCELLED', answered: false }),
    ).toBeUndefined();
    expect(describeFailure(undefined)).toBeUndefined();
  });

  it('passes on a detail with no sentence, and a sentence with no detail', () => {
    expect(
      describeFailure({
        ending: 'STUCK',
        answered: false,
        detail: 'IllegalStateException',
      }),
    ).toBe('the server said: IllegalStateException');
    expect(
      describeFailure({
        ending: 'CALL_BUDGET',
        answered: false,
        sentence:
          'This run stopped after spending its whole budget of 240 model calls' +
          ' without reaching an answer.',
      }),
    ).toBe(
      'the server said: This run stopped after spending its whole budget of 240 model' +
        ' calls without reaching an answer.',
    );
  });
});

describe('the runs waiting on a person', () => {
  const asking: Waiting = {
    id: 'orc_1',
    definition: 'implement_specification',
    question: 'Which database?\nSay why.',
  };
  const capped: Waiting = {
    id: 'orc_2',
    definition: 'code_implementation',
    pendingCap: 'turn_cap',
  };

  it('counts them for the status line, and says nothing about none', () => {
    expect(describeWaitingCount(0)).toBeUndefined();
    expect(describeWaitingCount(1)).toBe(
      `1 waiting on you · ${ANSWER_COMMAND}`,
    );
    expect(describeWaitingCount(2)).toBe(
      `2 waiting on you · ${ANSWER_COMMAND}`,
    );
  });

  it('says what a run that has started asking asked, on one line', () => {
    const line = describeNowAsking(asking);

    expect(line).toContain(
      'implement_specification (orc_1) is asking: Which database? Say why.',
    );
    expect(line).toContain(ANSWER_COMMAND);
    expect(line).not.toContain('\n');
    expect(describeNowAsking(capped)).toContain('asking to raise its turn_cap');
  });

  it('says a run asking whether it goes on stopped making progress, before its question is read', () => {
    const stuck: Waiting = {
      id: 'orc_4',
      definition: 'code_implementation',
      pendingCap: 'stuck',
    };

    expect(describeNowAsking(stuck)).toContain(
      'stopped making progress — go on?',
    );
    expect(describeNowAsking(stuck)).not.toContain('raise its stuck');
  });

  it('points at the run when its question could not be read', () => {
    expect(describeNowAsking({ id: 'orc_3', definition: 'd' })).toContain(
      `${RUNS_COMMAND} orc_3`,
    );
  });

  it('lists them for a bare /answer, with the shortcut only when there is one', () => {
    const one = describeWaitingRuns([asking]);
    expect(one.join('\n')).toContain('orc_1');
    expect(one.join('\n')).toContain('Which database?');
    expect(one.join('\n')).toContain(`${ANSWER_COMMAND} <your answer>`);

    const two = describeWaitingRuns([asking, capped]);
    expect(two.join('\n')).toContain('orc_2');
    expect(two.join('\n')).toContain(`${ANSWER_COMMAND} <id> <your answer>`);
    expect(two.join('\n')).not.toContain(`${ANSWER_COMMAND} <your answer>`);
  });

  it('says an answer was not sent when it could not say which run it was for', () => {
    expect(describeWaitingRuns([asking, capped], true)[0]).toContain(
      'not sent',
    );
    expect(describeWaitingRuns([], true).join('\n')).toContain('not sent');
    expect(describeWaitingRuns([]).join('\n')).toContain(
      'nothing is waiting for an answer',
    );
  });

  it('offers each as a menu row, by id, described by what it is and what it asked', () => {
    expect(waitingOffers([asking, capped])).toEqual([
      {
        name: 'orc_1',
        detail: 'implement_specification — Which database? Say why.',
      },
      {
        name: 'orc_2',
        detail: 'code_implementation — asking to raise its turn_cap',
      },
    ]);
  });
});

describe('a stalled run, waiting on a person for a look or a cancel rather than an answer', () => {
  const stalled: Waiting = {
    id: 'orc_9',
    definition: 'implement_specification',
    kind: 'stalled',
  };

  it('says it has done nothing for a while, and how to look at it or stop it', () => {
    expect(describeNowAsking(stalled)).toBe(
      'implement_specification (orc_9) has done' +
        ` nothing for a while — ${RUNS_COMMAND} orc_9 to look, ${CANCEL_COMMAND} orc_9` +
        ' to stop it',
    );
  });

  it('is listed as stalled rather than asking, with no bare-answer shortcut for the one of it', () => {
    const lines = describeWaitingRuns([stalled]).join('\n');
    expect(lines).toContain('orc_9  implement_specification — stalled');
    // A lone stalled run is still nothing a bare answer can reach.
    expect(lines).not.toContain(`${ANSWER_COMMAND} <your answer>`);
  });

  it('is not offered as an /answer argument, since there is nothing to answer it with', () => {
    const asking: Waiting = {
      id: 'orc_1',
      definition: 'code_implementation',
      question: 'Which?',
    };

    expect(waitingOffers([stalled])).toEqual([]);
    expect(waitingOffers([asking, stalled])).toEqual([
      { name: 'orc_1', detail: 'code_implementation — Which?' },
    ]);
  });
});

describe('a command approval waiting on a person', () => {
  const approval: Waiting = {
    id: 'apr_1',
    definition: 'code_implementation',
    kind: 'approval',
    question: 'run `pytest -q` in /repo (local)',
  };

  it('says who wants to run what, where, and the two answers', () => {
    const line = describeNowAsking(approval);

    expect(line).toContain(
      'code_implementation wants to run `pytest -q` in /repo (local)',
    );
    expect(line).toContain(`${ANSWER_COMMAND} apr_1 once`);
    expect(line).toContain(`${ANSWER_COMMAND} apr_1 deny`);
  });

  it('shows an acceptance set as one item, its commands one per line and wrapped (V67)', () => {
    const set: Waiting = {
      id: 'apr_9',
      definition: 'implement_specification',
      kind: 'approval',
      question:
        'run 15 acceptance commands at its acceptance stage, in /repo (local)',
      approval: {
        id: 'apr_9',
        conversation: 'cnv_c',
        agent: 'implement_specification',
        side: 'local',
        command: [],
        cwd: '/repo',
        state: 'asked',
        defaultPrefix: [],
        commands: Array.from({ length: 15 }, (_, n) => [
          'python',
          '-m',
          'pytest',
          '-q',
          `tests/test_main_loop.py::test_${n}`,
        ]),
      },
    };

    const lines = describeNowAsking(set).split('\n');

    expect(lines[0]).toBe(
      'implement_specification wants to run 15 acceptance commands at its' +
        ' acceptance stage:',
    );
    expect(lines.slice(1, 16)).toEqual(
      Array.from(
        { length: 15 },
        (_, n) => `    python -m pytest -q tests/test_main_loop.py::test_${n}`,
      ),
    );
    expect(lines[16]).toBe(
      `— ${ANSWER_COMMAND} apr_9 once, or ${ANSWER_COMMAND} apr_9 deny`,
    );

    const narrow = describeNowAsking(set, 30).split('\n');
    expect(
      narrow.every((line) => line.length <= 30 || !line.startsWith('    ')),
      narrow.join('\n'),
    ).toBe(true);
    expect(narrow.length).toBeGreaterThan(17);
  });

  it("cuts a long set to the dialog's room, saying how much more, and never loses the keys", () => {
    const approval = {
      id: 'apr_9',
      conversation: 'cnv_c',
      agent: 'implement_specification',
      side: 'local',
      command: [],
      cwd: '/repo',
      state: 'asked',
      defaultPrefix: [],
      commands: Array.from({ length: 15 }, (_, n) => [
        'pytest',
        `tests/test_${n}.py`,
      ]),
    };

    const lines = describeApprovalDialog(
      approval,
      askingAbout(approval),
      false,
      80,
      8,
    );

    expect(lines[0]).toBe('apr_9 (implement_specification) asks:');
    expect(lines.at(-1)).toBe(
      'o once · c for this conversation · d deny · esc leave it for later',
    );
    expect(lines.at(-2)).toBe(
      '… 13 more lines — one answer covers every command',
    );
    expect(lines).toHaveLength(8);
    expect(describeApprovalSettled('apr_9')).toContain(
      'apr_9 is no longer asked',
    );
  });

  it('says what answering did, and that the run carries on', () => {
    expect(describeApprovalAnswered('apr_1', 'allowed')).toBe(
      'apr_1 allowed — the run carries on',
    );
    expect(describeApprovalAnswered('apr_1', 'denied')).toBe(
      'apr_1 denied — the run carries on without it',
    );
  });
});

describe('/always', () => {
  it('is offered and said, with the way back', () => {
    expect(COMMANDS).toContain(ALWAYS_COMMAND);
    expect(describeHelp().join('\n')).toContain(ALWAYS_COMMAND);
    const on = describeAlways(
      'game_test',
      '/repo/.plowshare/environment.yml',
      true,
      1,
    );
    expect(on).toContain('commands in game_test now run without asking');
    expect(on).toContain('/repo/.plowshare/environment.yml');
    expect(on).toContain('1 waiting approval allowed');
    expect(on).toContain(`${ALWAYS_COMMAND} off`);
    expect(
      describeAlways('game_test', '/repo/.plowshare/environment.yml', false, 0),
    ).toContain('commands in game_test ask first again');
    expect(describeAlwaysNeedsAProject()).toContain('/here');
  });
});

describe('a harness question drawn as a list', () => {
  it("says the auto-continue a cap's always will write, as its keys line does", () => {
    expect(
      dialogOptionsOf('cap', 5).find((option) => option.key === 'always'),
    ).toEqual({
      key: 'always',
      letter: 'a',
      label: 'always go on (auto-continue 5)',
    });
    expect(
      dialogOptionsOf('cap').find((option) => option.key === 'always')?.label,
    ).toBe('always go on (auto-continue 3)');
  });

  it("keeps the question's own lines, then the options with the focus, then how to pick", () => {
    const state = pickingAbout(dialogOptionsOf('question'));
    expect(
      describePickingDialog(['orc_1 (d) asks:', 'Which database?'], state),
    ).toEqual([
      'orc_1 (d) asks:',
      'Which database?',
      '  r  reply in words',
      '  w  watch the run first',
      '›    decide later',
      '↑↓ move · enter or a letter picks · esc later',
    ]);
  });
});

describe('/cap', () => {
  it('says each value and where it came from, or that the definition decides', () => {
    expect(
      describeCaps({
        project: 'story',
        applied: 1,
        steps: { value: 40, source: '.plowshare/environment.yml' },
        budget: { source: 'definition' },
        autoContinue: { value: 3, source: '.plowshare/environment.yml' },
        time: { source: 'definition' },
        failedChecks: { value: 5, source: 'default' },
      }),
    ).toBe(
      "caps for story: steps 40 (.plowshare/environment.yml) · budget each run's own" +
        ' max-model-calls · auto-continue 3 (.plowshare/environment.yml) · time none' +
        ' · checks 5 (default), never auto-continued — applied to 1 live run',
    );
  });

  it('says the time cap and the failed-checks limit with their sources, and that checks are never auto-continued', () => {
    // V69: no time cap is none; no failed-checks limit is the harness's five.
    expect(
      describeCaps({
        project: 'story',
        applied: 0,
        steps: { source: 'definition' },
        budget: { source: 'definition' },
        autoContinue: { source: 'definition' },
        time: { source: 'definition' },
        failedChecks: { value: 5, source: 'default' },
      }),
    ).toBe(
      "caps for story: steps each run's own max-turns · budget each run's own max-model-calls" +
        ' · auto-continue off · time none · checks 5 (default), never auto-continued',
    );
    expect(
      describeCaps({
        project: 'story',
        applied: 0,
        steps: { source: 'definition' },
        budget: { source: 'definition' },
        autoContinue: { value: 3, source: '.plowshare/environment.yml' },
        time: { value: 90, source: '.plowshare/environment.yml' },
        failedChecks: { value: 3, source: "the server's environment.yml" },
      }),
    ).toContain(
      ' · time 90 minutes (.plowshare/environment.yml)' +
        " · checks 3 (the server's environment.yml), never auto-continued",
    );
  });

  it('is on the help list', () => {
    expect(COMMANDS).toContain(CAP_COMMAND);
  });
});

describe('lines read back out of the log', () => {
  it("heads a person's utterance with the turn it opened, and only a person's", () => {
    expect(describeSpokenIn(21)).toBe('turn 21, you said:');
  });

  it('says a harness utterance with ⚙, where it came from, and its first line — never "you said"', () => {
    const said = describeHarness(
      'orchestration orc_318408A44038F859',
      "The orchestration 'build' (id orc_318408A44038F859) stopped: it is stuck.\n\nIts work is left as it stopped.",
    );
    expect(said.heading).toBe(
      "⚙ run orc_318408A44038F859: The orchestration 'build' (id orc_318408A44038F859) stopped: it is stuck.",
    );
    expect(said.rest).toBe('Its work is left as it stopped.');
    expect(said.heading).not.toContain('you said');
  });

  it('names each harness source the server records', () => {
    expect(describeHarness('approval apr_1', 'Approve?').heading).toBe(
      '⚙ approval apr_1: Approve?',
    );
    expect(describeHarness('event nightly', 'Summarise.').heading).toBe(
      '⚙ event nightly: Summarise.',
    );
    expect(describeHarness('harness', 'Carry on.').heading).toBe(
      '⚙ harness: Carry on.',
    );
    expect(describeHarness('harness', 'one line').rest).toBe('');
  });

  it('says an answer was cut, and by how much', () => {
    expect(describeCut(8000, 9000)).toContain('9000');
    expect(describeCut(8000, 9000)).toContain('8000');
  });
});

describe('the runs panel', () => {
  const run = (
    id: string,
    state = 'running',
    createdAt = '2026-09-28T09:00:00Z',
  ): Run => ({
    id,
    definition: 'implement_specification',
    tier: 'project',
    state,
    depth: 0,
    createdAt,
  });
  const stage = (name: string, status: string) => ({
    text: name,
    status,
    stage: name,
  });
  const mark = (
    ordinal: number,
    text: string,
    tool = false,
    detail?: string,
  ): Recorded => ({
    ordinal,
    at: '2026-09-28T09:05:03Z',
    run: 'orc_1',
    actor: 'conductor',
    kind: tool ? 'tool_call' : 'stage_moved',
    text,
    tool,
    ...(detail === undefined ? {} : { detail }),
  });

  it('draws the stages as a checklist, done, going and not started', () => {
    expect(
      describeChecklist([
        stage('goal', 'done'),
        stage('spec', 'done'),
        stage('code', 'in_progress'),
        stage('review', 'pending'),
        { text: 'a note the conductor added', status: 'pending' },
      ]),
    ).toBe('goal ✓ spec ✓ code ● review ○');
  });

  it('says a tool line with its outcome, or … while it runs, and a milestone with its detail', () => {
    expect(
      plainOf(
        describeRecorded(
          mark(2, 'coder · run ./gradlew test', true, 'exit 1'),
          'UTC',
        ),
      ),
    ).toBe('09:05:03  conductor  ● coder · run ./gradlew test  ✗ exit 1');
    expect(
      plainOf(
        describeRecorded(mark(2, 'coder · run ./gradlew test', true), 'UTC'),
      ),
    ).toBe('09:05:03  conductor  ◌ coder · run ./gradlew test  …');
    expect(
      plainOf(
        describeRecorded(
          mark(3, 'code: in_progress → done', false, 'built it'),
          'UTC',
        ),
      ),
    ).toBe('09:05:03  conductor  ◆ code: in_progress → done — built it');
    // A summary over lines is one: the view draws each tinted line as one row.
    expect(
      plainOf(
        describeRecorded(
          mark(
            3,
            'code: in_progress → done',
            false,
            'built it\n  and tested it',
          ),
          'UTC',
        ),
      ),
    ).toBe(
      '09:05:03  conductor  ◆ code: in_progress → done — built it and tested it',
    );
  });

  it("draws the acceptance checker's concern lines under the checker, with their own mark", () => {
    // V77: a concern, a WHY and its answer, a verdict — the checker's work, in the story.
    const concern: Recorded = {
      ordinal: 4,
      at: '2026-09-28T09:05:03Z',
      run: 'orc_1',
      actor: 'acceptance_checker',
      kind: 'concern',
      text: 'c1 does not hold: main.py is a no-op',
      tool: false,
    };
    expect(plainOf(describeRecorded(concern, 'UTC', false, 18))).toBe(
      '09:05:03  acceptance_checker  │ ◇ c1 does not hold: main.py is a no-op',
    );
  });

  it('draws each live root with its phases, what it is doing and its last milestones', () => {
    const tree: Tree = {
      run: run('orc_1'),
      stages: [stage('goal', 'done'), stage('code', 'in_progress')],
      phases: [
        {
          run: {
            ...run('orc_2', 'waiting', '2026-09-28T09:30:00Z'),
            definition: 'code_implementation',
          },
          stages: [stage('tests', 'done'), stage('code', 'pending')],
        },
      ],
      activity: mark(9, 'coder · run ./gradlew test', true),
      milestones: [
        mark(7, 'tests: in_progress → done'),
        mark(8, 'code: pending → in_progress'),
      ],
    };
    const panel = describePanel(
      [tree],
      Date.parse('2026-09-28T10:00:00Z'),
      'UTC',
    );
    expect(panel.lines.map(plainOf)).toEqual([
      'orc_1  implement_specification  60m 0s',
      '  goal ✓ code ●',
      '  └ orc_2  code_implementation  30m 0s  waiting',
      '    tests ✓ code ○',
      '  now: conductor  ◌ coder · run ./gradlew test  …',
      '  09:05:03  conductor  ◆ tests: in_progress → done',
      '  09:05:03  conductor  ◆ code: pending → in_progress',
    ]);
    expect(panel.settled).toEqual([
      'orc_1  implement_specification',
      '  goal ✓ code ●',
      '  └ orc_2  code_implementation  waiting',
      '    tests ✓ code ○',
      '  09:05:03  conductor  ◆ tests: in_progress → done',
      '  09:05:03  conductor  ◆ code: pending → in_progress',
    ]);
  });

  describe('the question an asking tree waits on', () => {
    const asked = (text: string, body?: string, ordinal = 5): Recorded => ({
      ordinal,
      at: '2026-09-28T09:05:03Z',
      run: 'orc_1',
      actor: 'conductor',
      kind: 'question_asked',
      text,
      tool: false,
      ...(body === undefined ? {} : { body }),
    });
    const asking = (question: Recorded, state = 'asking'): Tree => ({
      run: run('orc_1', state),
      stages: [],
      phases: [],
      milestones: [],
      question,
    });

    it('is drawn whole under the tree, its own line breaks kept', () => {
      const tree = asking(
        asked('asked: Which database?', 'Which database?\nPostgres or SQLite'),
      );
      expect(describeTree(tree, undefined, 'UTC', false).map(plainOf)).toEqual([
        'orc_1  implement_specification  asking',
        '  ? Which database?',
        '    Postgres or SQLite',
      ]);
      expect(
        describeTree(tree, undefined, 'UTC', false)[1]?.find(
          (each) => each.text === '? ',
        )?.role,
      ).toBe('milestone');
    });

    it("is wrapped to the columns the surface says it has, under the panel's own indent", () => {
      const tree = asking(
        asked(
          'asked: The spec now…',
          'The spec now includes commands that import modules',
        ),
      );
      // Thirty columns: the panel's padding and the question's indent leave twenty-five.
      expect(
        describeTree(tree, undefined, 'UTC', false, 30).map(plainOf).slice(1),
      ).toEqual([
        '  ? The spec now includes',
        '    commands that import',
        '    modules',
      ]);
      expect(describePanel([tree], 0, 'UTC', 30).settled.slice(1)).toEqual([
        '  ? The spec now includes',
        '    commands that import',
        '    modules',
      ]);
    });

    it('is cut at six lines, the last saying where the rest is', () => {
      const body = Array.from({ length: 10 }, (_, at) => `line ${at + 1}`).join(
        '\n',
      );
      const lines = describeTree(
        asking(asked('asked: line 1', body)),
        undefined,
        'UTC',
        false,
      ).map(plainOf);
      expect(lines.slice(1)).toEqual([
        '  ? line 1',
        '    line 2',
        '    line 3',
        '    line 4',
        '    line 5',
        '    … /watch orc_1 for the rest',
      ]);
    });

    it('is its line when the record kept no body, and nothing for a tree no longer asking', () => {
      expect(
        describeTree(
          asking(asked('asked: Which database?')),
          undefined,
          'UTC',
          false,
        )
          .map(plainOf)
          .slice(1),
      ).toEqual(['  ? Which database?']);
      expect(
        describeTree(
          asking(asked('asked: Which database?'), 'running'),
          undefined,
          'UTC',
          false,
        ).map(plainOf),
      ).toEqual(['orc_1  implement_specification']);
    });

    it('comes before what the tree is doing, so a panel cut at its foot keeps it', () => {
      const tree: Tree = {
        ...asking(
          asked(
            'asked: Which database?',
            'Which database?\nPostgres or SQLite',
          ),
        ),
        activity: mark(9, 'coder · run ./gradlew test', true),
        milestones: [
          mark(4, 'code: pending → in_progress'),
          { ...asked('asked: Which database?'), ordinal: 5 },
        ],
      };
      expect(describeTree(tree, undefined, 'UTC', true).map(plainOf)).toEqual([
        'orc_1  implement_specification  asking',
        '  ? Which database?',
        '    Postgres or SQLite',
        '  now: conductor  ◌ coder · run ./gradlew test  …',
        '  09:05:03  conductor  ◆ code: pending → in_progress',
        '  09:05:03  conductor  ? asked: Which database?',
      ]);
    });
  });

  describe('the todo list with its phase children', () => {
    it('puts the phases under their stage, dropped ones marked', () => {
      const stages: RunStage[] = [
        { id: 'td_plan', text: 'plan', status: 'done', stage: 'plan' },
        {
          id: 'td_phases',
          text: 'phases',
          status: 'in_progress',
          stage: 'phases',
        },
        { id: 'a', parent: 'td_phases', text: 'utils', status: 'done' },
        { id: 'b', parent: 'td_phases', text: 'inventory', status: 'done' },
        { id: 'c', parent: 'td_phases', text: 'combat', status: 'in_progress' },
        { id: 'd', parent: 'td_phases', text: 'readme', status: 'pending' },
        {
          id: 'e',
          parent: 'td_phases',
          text: 'multiplayer',
          status: 'dropped',
        },
      ];
      expect(describePhaseChildren(stages)).toEqual([
        'phases: utils ✓ inventory ✓ combat ● readme ○ multiplayer –',
      ]);
      expect(describeChecklist(stages)).toBe('plan ✓ phases ●');
    });

    it('wraps the children rather than let a narrow row cut off the last ones', () => {
      // The measured run: eight phases, and the one that never ran was the last.
      const names = [
        'utils',
        'inventory',
        'character',
        'combat',
        'save-load',
        'world-map',
        'shops-and-trading',
        'readme',
      ];
      const stages: RunStage[] = [
        {
          id: 'td_phases',
          text: 'phases',
          status: 'in_progress',
          stage: 'phases',
        },
        ...names.map((text, at): RunStage => ({
          id: `c${at}`,
          parent: 'td_phases',
          text,
          status: at < 4 ? 'done' : 'pending',
        })),
      ];

      const lines = describePhaseChildren(stages);

      expect(lines.length).toBeGreaterThan(1);
      expect(lines[0]).toMatch(/^phases: utils ✓ inventory ✓/u);
      expect(lines.at(-1)).toMatch(/readme ○$/u);
      for (const line of lines.slice(1)) {
        expect(line.startsWith(' '.repeat('phases: '.length))).toBe(true);
      }
      for (const line of lines) {
        expect(line.length).toBeLessThanOrEqual('phases: '.length + 60);
      }
      expect(lines.join(' ').replace(/\s+/gu, ' ')).toBe(
        `phases: ${names.map((name, at) => `${name} ${at < 4 ? '✓' : '○'}`).join(' ')}`,
      );
    });

    it("cuts a child's text to 24 characters", () => {
      const stages: RunStage[] = [
        {
          id: 'td_phases',
          text: 'phases',
          status: 'in_progress',
          stage: 'phases',
        },
        // 25 letters: past the 24-character budget by one.
        {
          id: 'a',
          parent: 'td_phases',
          text: 'abcdefghijklmnopqrstuvwxy',
          status: 'done',
        },
      ];
      expect(describePhaseChildren(stages)).toEqual([
        'phases: abcdefghijklmnopqrstuvw… ✓',
      ]);
    });

    it("prints under the checklist line, and under each phase's own", () => {
      const tree: Tree = {
        run: run('orc_1'),
        stages: [
          stage('goal', 'done'),
          {
            id: 'td_phases',
            text: 'phases',
            status: 'in_progress',
            stage: 'phases',
          },
          { id: 'a', parent: 'td_phases', text: 'utils', status: 'done' },
          { id: 'b', parent: 'td_phases', text: 'readme', status: 'pending' },
        ],
        phases: [
          {
            run: {
              ...run('orc_2', 'waiting'),
              definition: 'code_implementation',
            },
            stages: [
              stage('plan', 'done'),
              {
                id: 'td_p',
                text: 'phases',
                status: 'in_progress',
                stage: 'phases',
              },
              { id: 'x', parent: 'td_p', text: 'lexer', status: 'done' },
            ],
          },
        ],
        milestones: [],
      };
      expect(describeTree(tree, undefined, 'UTC', false).map(plainOf)).toEqual([
        'orc_1  implement_specification',
        '  goal ✓ phases ●',
        '  phases: utils ✓ readme ○',
        '  └ orc_2  code_implementation  waiting',
        '    plan ✓ phases ●',
        '    phases: lexer ✓',
      ]);
      // Drawn as the checklist is: each child's glyph in the colour of where it stands.
      const children = describeTree(tree, undefined, 'UTC', false)[2] ?? [];
      expect(children.find((each) => each.text === ' ✓')?.role).toBe('ok');
      expect(children.find((each) => each.text === ' ○')?.role).toBe('muted');
    });
  });
});

describe('the viewer', () => {
  const rows = [1, 2, 3].map((ordinal) => ({
    ordinal,
    at: '2026-09-28T09:05:03Z',
    run: 'orc_1',
    actor: 'conductor',
    kind: 'stage_moved',
    text: `line ${ordinal}`,
    tool: false,
  }));
  const watch: Watch = {
    root: 'orc_1',
    rows,
    through: 3,
    more: true,
    tools: true,
    back: 1,
  };

  it('shows the rows up to where it is scrolled, the way to earlier at the top, and its keys', () => {
    const viewed = describeWatch(watch, undefined, 0, 'UTC');
    expect(viewed.head.map(plainOf)).toEqual(['orc_1']);
    expect(viewed.body.map(plainOf)).toEqual([
      '— earlier: e —',
      ' 09:05:03  conductor  ◆ line 1',
      '▸09:05:03  conductor  ◆ line 2',
    ]);
    expect(viewed.body.at(-1)?.every((each) => each.back === 'selection')).toBe(
      true,
    );
    expect(viewed.foot).toBe(
      'milestones and tool activity · ↑↓ PgUp PgDn scroll · ⏎ open' +
        ' · x X failure · [ ] milestone · e earlier · t milestones only · f follow · esc back',
    );
    expect(
      plainOf(
        describeWatch(
          { ...watch, more: false, tools: false, back: 0 },
          undefined,
          0,
          'UTC',
        ).body[0] ?? [],
      ),
    ).toBe('— the beginning of this record —');
    expect(viewed.said).toBeUndefined();
    expect(describeWatch(watch, undefined, 0, 'UTC', 'no such run').said).toBe(
      'no such run',
    );
  });

  it('words only the rows the surface has room for, however many it holds', () => {
    const held = Array.from({ length: 5000 }, (_, at) => ({
      ordinal: at + 1,
      at: '2026-09-28T09:05:03Z',
      run: 'orc_1',
      actor: 'conductor',
      kind: 'stage_moved',
      text: `line ${at + 1}`,
      tool: false,
    }));
    const many: Watch = {
      root: 'orc_1',
      rows: held,
      through: 5000,
      more: true,
      tools: true,
      back: 10,
    };
    // `format` is a getter handing back a bound function: one read of it per row formatted.
    const formatted = vi.spyOn(
      Intl.DateTimeFormat.prototype as unknown as { format: unknown },
      'format',
      'get',
    );
    try {
      const viewed = describeWatch(many, undefined, 0, 'UTC', undefined, {
        room: 20,
      });
      expect(viewed.body).toHaveLength(20);
      expect(plainOf(viewed.body[0] ?? [])).toBe(
        ' 09:05:03  conductor  ◆ line 4971',
      );
      expect(plainOf(viewed.body.at(-1) ?? [])).toBe(
        '▸09:05:03  conductor  ◆ line 4990',
      );
      expect(formatted).toHaveBeenCalledTimes(20);
    } finally {
      formatted.mockRestore();
    }
    // Room for all of it and the line above: that line is drawn too.
    expect(
      plainOf(
        describeWatch({ ...watch, back: 0 }, undefined, 0, 'UTC', undefined, {
          room: 4,
        }).body[0] ?? [],
      ),
    ).toBe('— earlier: e —');
    expect(
      plainOf(
        describeWatch({ ...watch, back: 0 }, undefined, 0, 'UTC', undefined, {
          room: 3,
        }).body[0] ?? [],
      ),
    ).toBe(' 09:05:03  conductor  ◆ line 1');
    expect(
      describeWatch(many, undefined, 0, 'UTC', undefined, { room: 0 }).body,
    ).toEqual([]);
  });

  describe('a row a person reads whole', () => {
    const at = (
      ordinal: number,
      text: string,
      kind = 'stage_moved',
      body?: string,
    ): Recorded => ({
      ordinal,
      at: '2026-09-28T09:05:03Z',
      run: 'orc_1',
      actor: 'conductor',
      kind,
      text,
      tool: false,
      ...(body === undefined ? {} : { body }),
    });
    const held: Watch = {
      root: 'orc_1',
      through: 3,
      more: false,
      tools: true,
      back: 0,
      rows: [
        at(1, 'line 1'),
        at(
          2,
          'asked: Which database?',
          'question_asked',
          'Which database?\nPostgres or SQLite',
        ),
        at(3, 'line 3'),
      ],
    };
    // The cursor's column, the clock and the actor column: where a row's text starts.
    const indent = ' '.repeat(1 + 10 + 9 + 2);

    it("is drawn under its line, indented to where the line's text starts", () => {
      const body = describeWatch(held, undefined, 0, 'UTC').body;
      expect(body.map(plainOf)).toEqual([
        '— the beginning of this record —',
        ' 09:05:03  conductor  ◆ line 1',
        ' 09:05:03  conductor  ? asked: Which database?',
        `${indent}Which database?`,
        `${indent}Postgres or SQLite`,
        '▸09:05:03  conductor  ◆ line 3',
      ]);
      expect(body[3]?.at(-1)).toEqual({
        text: 'Which database?',
        role: 'text',
      });
    });

    it('is wrapped to the columns it is given, and at 80 when it is given none', () => {
      const long =
        'The spec now includes commands that import modules and run the game.';
      const one: Watch = {
        ...held,
        rows: [at(1, 'asked: The spec now…', 'question_asked', long)],
      };
      // Fifty columns, less the padding and the indent: twenty-seven.
      expect(
        describeWatch(one, undefined, 0, 'UTC', undefined, { columns: 50 })
          .body.slice(2)
          .map(plainOf),
      ).toEqual([
        `${indent}The spec now includes`,
        `${indent}commands that import`,
        `${indent}modules and run the game.`,
      ]);
      // None: eighty, which leaves fifty-seven.
      expect(
        describeWatch(one, undefined, 0, 'UTC').body.slice(2).map(plainOf),
      ).toEqual([
        `${indent}The spec now includes commands that import modules and`,
        `${indent}run the game.`,
      ]);
    });

    it("draws an acceptance set's one approval row with its commands under it (V67)", () => {
      const set: Watch = {
        ...held,
        rows: [
          at(
            1,
            'approval asked to run 2 acceptance commands in' + ' /repo (local)',
            'approval_asked',
            'python -m pytest -q tests/test_main_loop.py\n' +
              'python -m rpg.main',
          ),
        ],
      };

      expect(
        describeWatch(set, undefined, 0, 'UTC', undefined, { columns: 50 })
          .body.slice(1)
          .map(plainOf),
      ).toEqual([
        '▸09:05:03  conductor  ? approval asked to run 2 acceptance commands in /repo (local)',
        `${indent}python -m pytest -q`,
        `${indent}tests/test_main_loop.py`,
        `${indent}python -m rpg.main`,
      ]);
    });

    it('counts its lines against the room, so the screen never holds more than it has', () => {
      // Four lines: row 3, and row 2 with its two of body; row 1 does not fit above them.
      const four = describeWatch(held, undefined, 0, 'UTC', undefined, {
        room: 4,
      }).body.map(plainOf);
      expect(four).toEqual([
        ' 09:05:03  conductor  ? asked: Which database?',
        `${indent}Which database?`,
        `${indent}Postgres or SQLite`,
        '▸09:05:03  conductor  ◆ line 3',
      ]);
      // Too little room for all of a body: what fits, and how much more there is.
      const three = describeWatch(held, undefined, 0, 'UTC', undefined, {
        room: 3,
      }).body.map(plainOf);
      expect(three).toEqual([
        ' 09:05:03  conductor  ? asked: Which database?',
        `${indent}… 2 more lines`,
        '▸09:05:03  conductor  ◆ line 3',
      ]);
      // And the heights it counted are the ones the keys scroll by.
      const tall = watchRowHeight(held, 3, 80);
      expect(held.rows.map(tall)).toEqual([1, 3, 1]);
      expect(watchRowHeight(held, 2, 80)(held.rows[1] as Recorded)).toBe(2);
    });
  });

  it("says which phase a phase run's line is from, in its own colour, after the glyph", () => {
    // As RecordKeeper.labelled writes them (spec 2026-09-29 §4): the phase, then the line.
    const tool = {
      ordinal: 1,
      at: '2026-09-28T09:05:03Z',
      run: 'orc_2',
      actor: 'conductor',
      kind: 'tool_call',
      text: '03-character · conductor · todo_write 1 op',
      tool: true,
      detail: 'ok',
    };
    const moved = {
      ordinal: 2,
      at: '2026-09-28T09:05:04Z',
      run: 'orc_2',
      actor: 'conductor',
      kind: 'stage_moved',
      text: '03-character · spec: in_progress → done',
      tool: false,
    };
    const one: Watch = {
      root: 'orc_1',
      rows: [tool, moved],
      through: 2,
      more: false,
      tools: true,
      back: 0,
    };
    const body = describeWatch(one, undefined, 0, 'UTC').body;
    expect(body.slice(1).map(plainOf)).toEqual([
      ' 09:05:03  conductor  │ ● 03-character · todo_write 1 op  ✓',
      '▸09:05:04  conductor  │ ◆ 03-character · spec: in_progress → done',
    ]);
    expect(body[1]?.find((each) => each.text === '03-character · ')?.role).toBe(
      'accent',
    );
    // The root's own lines carry no phase, and a tool line's actor is not read as one.
    expect(
      plainOf(
        describeRecorded(
          { ...tool, run: 'orc_1', text: 'conductor · todo_write 1 op' },
          'UTC',
          false,
          9,
          'orc_1',
        ),
      ),
    ).toBe('09:05:03  conductor  ● todo_write 1 op  ✓');
  });

  it('says a tool line whose run ended first is unknown, and one still running is going', () => {
    const open = {
      ordinal: 1,
      at: '2026-09-28T09:05:03Z',
      run: 'orc_1',
      actor: 'coder',
      kind: 'tool_call',
      text: 'coder · run ./gradlew test',
      tool: true,
    };
    const one: Watch = {
      root: 'orc_1',
      rows: [open],
      through: 1,
      more: false,
      tools: true,
      back: 0,
    };
    expect(
      plainOf(describeWatch(one, undefined, 0, 'UTC').body.at(-1) ?? []),
    ).toBe('▸09:05:03  coder      │ ◌ run ./gradlew test  …');
    expect(
      plainOf(
        describeWatch(one, undefined, 0, 'UTC', undefined, {
          lost: () => true,
        }).body.at(-1) ?? [],
      ),
    ).toBe('▸09:05:03  coder      │ ● run ./gradlew test  unknown');
  });

  it("draws a delegate's tool line on a rail in its own colour, with its outcome coloured", () => {
    const line = describeRecorded(
      {
        ordinal: 9,
        at: '2026-09-28T09:05:03Z',
        run: 'orc_1',
        actor: 'coder',
        kind: 'tool_call',
        text: 'coder · run ./gradlew test',
        detail: 'exit 1',
        tool: true,
      },
      'UTC',
      false,
      9,
      'orc_1',
    );
    expect(plainOf(line)).toBe(
      '09:05:03  coder      │ ● run ./gradlew test  ✗ exit 1',
    );
    expect(line.find((each) => each.text.startsWith('coder'))?.role).toMatch(
      /^actor[1-4]$/u,
    );
    expect(line.find((each) => each.text === '✗ exit 1')?.role).toBe('fail');
    expect(
      plainOf(
        describeRecorded(
          {
            ordinal: 3,
            at: '2026-09-28T09:05:03Z',
            run: 'orc_1',
            actor: 'conductor',
            kind: 'stage_moved',
            text: 'spec → code',
            tool: false,
          },
          'UTC',
          false,
          9,
          'orc_1',
        ),
      ),
    ).toBe('09:05:03  conductor  ◆ spec → code');
    // A settled outcome that is neither a pass nor a failure is no longer running.
    expect(
      describeRecorded(
        {
          ordinal: 9,
          at: '2026-09-28T09:05:03Z',
          run: 'orc_1',
          actor: 'coder',
          kind: 'tool_call',
          text: 'coder · ask_user',
          detail: 'cancelled',
          tool: true,
        },
        'UTC',
        false,
        9,
        'orc_1',
      ).find((each) => each.text === '● ')?.role,
    ).toBe('waiting');
    expect(
      describeChecklistTinted([
        { stage: 'goal', status: 'done' },
        { stage: 'code', status: 'in_progress' },
      ] as never).map((each) => each.role),
    ).toEqual(['text', 'ok', 'text', 'text', 'waiting']);
  });

  it('marks a check by what the server wrote of it: passed, failed or timed out', () => {
    const check = (text: string): Recorded => ({
      ordinal: 4,
      at: '2026-09-28T09:05:03Z',
      run: 'orc_1',
      actor: 'conductor',
      kind: 'check_ran',
      text,
      tool: false,
    });
    const failed = describeRecorded(
      check('check `./gradlew test` failed (exit 1)'),
      'UTC',
      false,
      9,
      'orc_1',
    );
    expect(plainOf(failed)).toBe(
      '09:05:03  conductor  ✗ check `./gradlew test` failed (exit 1)',
    );
    expect(failed.find((each) => each.text === '✗ ')?.role).toBe('fail');
    const passed = describeRecorded(
      check('check `./gradlew test` passed'),
      'UTC',
      false,
      9,
      'orc_1',
    );
    expect(plainOf(passed)).toBe(
      '09:05:03  conductor  ✓ check `./gradlew test` passed',
    );
    expect(passed.find((each) => each.text === '✓ ')?.role).toBe('ok');
    const timedOut = describeRecorded(
      check('check `pytest` timed out'),
      'UTC',
      false,
      9,
      'orc_1',
    );
    expect(timedOut.find((each) => each.text === '✗ ')?.role).toBe('fail');
  });

  it('keeps the actor column as wide as the widest actor held, and selects the cursor row', () => {
    const at = (ordinal: number, actor: string): Recorded => ({
      ordinal,
      at: '2026-09-28T09:05:03Z',
      run: 'orc_1',
      actor,
      kind: 'stage_moved',
      text: `line ${ordinal}`,
      tool: false,
    });
    const held: Watch = {
      root: 'orc_1',
      rows: [at(1, 'code_reviewer_x'), at(2, 'conductor'), at(3, 'conductor')],
      through: 3,
      more: false,
      tools: true,
      back: 0,
      at: 2,
    };
    const viewed = describeWatch(held, undefined, 0, 'UTC', undefined, {
      room: 2,
    });
    expect(viewed.body.map(plainOf)).toEqual([
      '▸09:05:03  conductor        ◆ line 2',
      ' 09:05:03  conductor        ◆ line 3',
    ]);
    // The cursor is marked by a glyph as well as a background, which NO_COLOR does not draw.
    expect(viewed.body[0]?.[0]).toEqual({
      text: '▸',
      role: 'selected',
      back: 'selection',
    });
    expect(viewed.body[0]?.every((each) => each.back === 'selection')).toBe(
      true,
    );
    expect(viewed.body[1]?.some((each) => each.back === 'selection')).toBe(
      false,
    );
  });

  it('is a command the help names', () => {
    expect(COMMANDS).toContain(WATCH_COMMAND);
    expect(describeUsage(WATCH_COMMAND)).toBe(
      '/watch takes one run id, or nothing at all — the newest live run',
    );
  });
});

describe('tool lines', () => {
  const call = (extra: Partial<Call> = {}): Call => ({
    kind: 'call',
    ordinal: 4,
    turn: 2,
    id: 'c1',
    tool: 'run',
    salient: './gradlew :server:test',
    arguments: '{}',
    argumentsCut: false,
    argumentsLength: 2,
    pending: false,
    unanswered: false,
    hooks: [],
    entry: { ordinal: 4, turnOrdinal: 2, kind: 'answer', state: 'stands' },
    result: {
      ordinal: 5,
      turnOrdinal: 2,
      kind: 'tool_result',
      state: 'stands',
      outcome: 'exit 1',
      tookMillis: 4200,
      text: [
        '> Task :server:test',
        'TokenizerTest > counts FAILED',
        'x',
        'y',
        'z',
        'BUILD FAILED',
      ].join('\n'),
      length: 60,
    },
    ...extra,
  });

  it('draws a failed call as a line, then the line that says why and its tail on a rail', () => {
    const lines = describeCallLines(call(), 'compact', 80).map(plainOf);
    expect(lines[0]).toBe(
      '  ● run        ./gradlew :server:test                            ✗ exit 1   4.2s',
    );
    expect(lines.slice(1)).toEqual([
      '  │ TokenizerTest > counts FAILED',
      '  │ x',
      '  │ z',
      '  │ BUILD FAILED',
      '  │ … +2 lines · /trajectory',
    ]);
    expect(
      describeCallLines(call(), 'compact', 80)[0]?.find(
        (each) => each.text === '✗ exit 1',
      )?.role,
    ).toBe('fail');
  });

  it('draws a call that went well as one line with its size, and nothing in hidden', () => {
    const fine = call({
      tool: 'file_read',
      salient: 'plowshare-server/src/main/java/io/aeyer/Tokenizer.java',
      result: {
        ordinal: 5,
        turnOrdinal: 2,
        kind: 'tool_result',
        state: 'stands',
        outcome: 'ok',
        tookMillis: 41,
        text: 'a\nb\nc',
        length: 5,
      },
    });
    const lines = describeCallLines(fine, 'compact', 72).map(plainOf);
    expect(lines).toHaveLength(1);
    expect(lines[0]).toBe(
      '  ● file_read  plowshare-server/src…eyer/Tokenizer.java ✓ 3 lines   41ms',
    );
    expect(lines[0]?.length).toBeLessThanOrEqual(72);
    expect(describeCallLines(fine, 'hidden', 72)).toEqual([]);
  });

  it('keeps a tab-indented stack trace, and whatever escapes a tool printed, inside the width', () => {
    const trace = [
      'exit 1',
      '--- stdout ---',
      'java.lang.AssertionError: expected 3',
      '\tat TokenizerTest.counts(TokenizerTest.java:41)',
      '\t\tat org.junit.Runner.run(Runner.java:1)',
      '\u001b[31mBUILD FAILED\u001b[0m\r',
    ].join('\n');
    const failed = call({
      result: {
        ordinal: 5,
        turnOrdinal: 2,
        kind: 'tool_result',
        state: 'stands',
        outcome: 'exit 1',
        text: trace,
      },
    });
    for (const density of ['compact', 'full'] as const) {
      const lines = describeCallLines(failed, density, 40).map(plainOf);
      expect(lines.every((line) => [...Array.from(line)].length <= 40)).toBe(
        true,
      );
      // eslint-disable-next-line no-control-regex -- Intentional terminal control-sequence removal or its regression assertion.
      expect(lines.join('\n')).not.toMatch(/[\t\r\u001b]/u);
    }
    expect(
      describeCallLines(failed, 'compact', 80).map(plainOf).slice(1, 3),
    ).toEqual([
      '  │ java.lang.AssertionError: expected 3',
      '  │     at TokenizerTest.counts(TokenizerTest.java:41)',
    ]);
  });

  it('draws a run whose status line the display cut took as not known, never as a pass', () => {
    const ran = call({
      result: {
        ordinal: 5,
        turnOrdinal: 2,
        kind: 'tool_result',
        state: 'stands',
        outcome: 'ran',
        text: 'a',
      },
    });
    const line = describeCallLines(ran, 'compact', 80)[0] ?? [];
    expect(plainOf(line)).toMatch(/· ran$/u);
    expect(plainOf(line)).not.toContain('✓');
    expect(
      plainOf(
        describeRecorded(
          {
            ordinal: 2,
            at: '2026-09-28T09:05:03Z',
            run: 'orc_1',
            actor: 'conductor',
            kind: 'tool_call',
            text: 'run ./gradlew test',
            tool: true,
            detail: 'ran',
          },
          'UTC',
        ),
      ),
    ).toBe('09:05:03  conductor  ● run ./gradlew test  · ran');
  });

  it('drops the time, then the size, before it would wrap, and never drops the mark', () => {
    expect(plainOf(describeCallLines(call(), 'compact', 34)[0] ?? [])).toBe(
      '  ● run        ./gra…test ✗ exit 1',
    );
  });

  it('sizes an edit by the lines it changed, and shows its diff in full', () => {
    const edit = call({
      tool: 'file_edit',
      salient: 'Tokenizer.java',
      arguments: JSON.stringify({
        path: 'Tokenizer.java',
        old: 'return bytes;',
        new: 'return points;\n// counted',
      }),
      result: {
        ordinal: 5,
        turnOrdinal: 2,
        kind: 'tool_result',
        state: 'stands',
        outcome: 'ok',
        tookMillis: 12,
        text: 'edited',
        length: 6,
      },
    });
    expect(plainOf(describeCallLines(edit, 'compact', 80)[0] ?? [])).toMatch(
      /✓ \+2 −1 {3}12ms$/u,
    );
    const full = describeCallLines(edit, 'full', 80);
    expect(full.slice(1).map(plainOf)).toEqual([
      '  │ - return bytes;',
      '  │ + return points;',
      '  │ + // counted',
    ]);
    expect(full[1]?.at(-1)?.role).toBe('diffRemoved');
  });

  it('says where a delegation went', () => {
    const delegated = call({
      tool: 'agent_run',
      salient: 'code_reviewer',
      opened: { conversation: 'cnv_2', agent: 'code_reviewer' },
      result: {
        ordinal: 5,
        turnOrdinal: 2,
        kind: 'tool_result',
        state: 'stands',
        outcome: 'answered',
        tookMillis: 48_000,
        text: 'ok',
        length: 2,
      },
    });
    expect(
      plainOf(describeCallLines(delegated, 'compact', 80)[0] ?? []),
    ).toMatch(/✓ ↳ code_reviewer +48\.0s$/u);
  });

  it('draws a pending call with its live time, and a turn in one line when hidden', () => {
    expect(
      plainOf(describePendingLine(call({ pending: true }), 1300, 60)),
    ).toBe('  ◌ run        ./gradlew :server:test                   1.3s');
    expect(
      plainOf(
        describeTraceTurn({
          ordinal: 2,
          steps: [],
          modelCalls: 3,
          modelMillis: 9400,
          toolMillis: 11_100,
          calls: 5,
          failed: 1,
        }),
      ),
    ).toBe('  ⚙ 5 calls · 1 failed · 11.1s');
    expect(
      describeTurnTimes({
        ordinal: 2,
        steps: [],
        modelCalls: 3,
        modelMillis: 9400,
        toolMillis: 11_100,
        calls: 5,
        failed: 1,
      }),
    ).toBe('model 9.4s · 5 tools 11.1s · 1 failed');
  });

  it('cycles density and says so', () => {
    expect(nextDensity('compact')).toBe('full');
    expect(nextDensity('full')).toBe('hidden');
    expect(nextDensity('hidden')).toBe('compact');
    expect(describeDensity('hidden')).toBe(
      'tool lines: hidden — one line per turn from here on · ctrl-t for more',
    );
  });

  it("draws a turn's reasoning as one line in compact, up to six in full, and none hidden", () => {
    const step: Step = {
      kind: 'reasoning',
      ordinal: 3,
      turn: 2,
      entry: {
        ordinal: 3,
        turnOrdinal: 2,
        kind: 'thinking',
        state: 'stands',
        text: [
          'first line',
          'second line',
          'third',
          'fourth',
          'fifth',
          'sixth',
          'seventh',
        ].join('\n'),
      },
    };
    const compact = describeReasoningLines(step, 'compact', 80);
    expect(compact).toHaveLength(1);
    expect(plainOf(compact[0] ?? [])).toBe('  ✻ first line');
    expect(compact[0]?.[1]?.role).toBe('reasoning');

    const full = describeReasoningLines(step, 'full', 80);
    expect(full.map(plainOf)).toEqual([
      '  ✻ first line',
      '    second line',
      '    third',
      '    fourth',
      '    fifth',
      '    sixth',
    ]);

    expect(describeReasoningLines(step, 'hidden', 80)).toEqual([]);
  });
});

describe('the explorer, laid out', () => {
  const row = (
    ordinal: number,
    turn: number,
    kind: string,
    extra: Partial<Entry> = {},
  ): Entry => ({
    ordinal,
    turnOrdinal: turn,
    kind,
    state: 'stands',
    text: `row ${ordinal}`,
    ...extra,
  });
  const LOG: Entry[] = [
    row(1, 1, 'utterance', { text: 'run the tests' }),
    row(2, 1, 'answer', {
      text: '',
      asked: 1,
      tookMillis: 3000,
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
    row(3, 1, 'tool_result', {
      toolCallId: 'c1',
      outcome: 'exit 1',
      tookMillis: 4200,
      text: 'FAILED\nBUILD FAILED',
      length: 19,
      recordedAt: '2026-09-29T10:00:04Z',
    }),
    row(4, 1, 'answer', {
      text: 'One test fails.',
      tookMillis: 900,
      wireModel: 'qwen3.5-9b',
      dispatch: 'primary',
      completion: 'answered',
    }),
  ];
  const open = (view: 'trajectory' | 'log' = 'trajectory'): Explorer =>
    explorerOpened(
      levelOf(
        'cnv_1',
        'plowshare',
        { entries: LOG, through: 4, more: false, total: 4 },
        view,
        'failure',
      ),
      view,
    );
  const extras = { children: new Map(), zone: 'UTC' };

  it('fills a wide screen: a header, the strip, the list beside the inspector, and the keys', () => {
    const lines = describeExplorer(
      open(),
      { rows: 20, columns: 140 },
      extras,
    ).map(plainOf);
    expect(lines.length).toBeLessThanOrEqual(19);
    expect(lines.every((line) => [...Array.from(line)].length <= 139)).toBe(
      true,
    );
    expect(lines[0]).toMatch(
      /^ plowshare · 1 turn · 3 steps · model 3\.9s · tools 4\.2s · 1 failed +○ paused$/u,
    );
    expect(lines[1]).toMatch(/^ model ▀+/u);
    expect(lines[2]).toMatch(/^ tools +▲▀*/u);
    expect(
      lines.some((line) =>
        /▸ +TOOL +run +FAILED · \.\/gradlew test .*✗ exit 1 +4\.2s +│/u.test(
          line,
        ),
      ),
    ).toBe(true);
    expect(lines.some((line) => line.includes('│ TOOL · turn 1 · #2'))).toBe(
      true,
    );
    expect(
      lines.some((line) => line.includes('│ [Result]  Payload  Timing')),
    ).toBe(true);
    expect(lines.some((line) => line.includes('BUILD FAILED'))).toBe(true);
    expect(lines.at(-1)).toBe(
      ' ↑↓ move  ⏎ inspect  → open  ← back  [ ] turn  x failure  / search  t fold  v log  q close',
    );
  });

  it('draws the list alone when narrow, and the inspector alone once it has focus', () => {
    const narrow = describeExplorer(
      open(),
      { rows: 16, columns: 80 },
      extras,
    ).map(plainOf);
    expect(narrow.some((line) => line.includes('│'))).toBe(false);
    expect(narrow.some((line) => /▸ +TOOL +run/u.test(line))).toBe(true);
    const inspecting = describeExplorer(
      explorerKeyed(open(), 'inspect', 5).explorer,
      { rows: 16, columns: 80 },
      extras,
    ).map(plainOf);
    expect(
      inspecting.some((line) => line.includes('[Result]  Payload  Timing')),
    ).toBe(true);
    expect(inspecting.some((line) => line.includes('TOOL  run'))).toBe(false);
    expect(inspecting.at(-1)).toBe(
      ' ↑↓ scroll  tab next pane  esc back to the list  q close',
    );
  });

  it("shows a result with its cut drawn as a cut, and a call's timing", () => {
    const cutLog = LOG.map((each) =>
      each.ordinal === 3
        ? { ...each, cut: true, length: 38_201, handle: 'h-1' }
        : each,
    );
    const ex = explorerOpened(
      levelOf(
        'cnv_1',
        'plowshare',
        { entries: cutLog, through: 4, more: false },
        'trajectory',
        'failure',
      ),
      'trajectory',
    );
    const result = describeExplorer(
      explorerKeyed(ex, 'inspect', 5).explorer,
      { rows: 20, columns: 80 },
      extras,
    ).map(plainOf);
    expect(result.some((line) => line.includes('BUILD FAILED'))).toBe(true);
    expect(
      result.some((line) => line.includes('shows 19 of 38,201 characters')),
    ).toBe(true);
    expect(result.some((line) => line.includes('through result_read'))).toBe(
      true,
    );
    const timing = describeExplorer(
      explorerKeyed(
        explorerKeyed(explorerKeyed(ex, 'inspect', 5).explorer, 'tab', 5)
          .explorer,
        'tab',
        5,
      ).explorer,
      { rows: 20, columns: 80 },
      extras,
    ).map(plainOf);
    expect(timing.some((line) => /recorded +10:00:04/u.test(line))).toBe(true);
    expect(timing.some((line) => /took +4\.2s/u.test(line))).toBe(true);
    expect(timing.some((line) => /outcome +exit 1/u.test(line))).toBe(true);
  });

  it('shows output by default, including a failure with no output text', () => {
    for (const [outcome, output, expected] of [
      ['ok', 'all tests passed', 'all tests passed'],
      ['permission denied', '', 'no tool output recorded'],
      [
        'exit 1',
        'starting build\nstdout:\nbuilding\nerror: missing dependency\ninstall it first',
        'error: missing dependency',
      ],
    ] as const) {
      const entries = LOG.map((each) =>
        each.ordinal === 3 ? { ...each, outcome, text: output } : each,
      );
      const ex = explorerKeyed(
        explorerOpened(
          levelOf(
            'cnv_1',
            'plowshare',
            { entries, through: 4, more: false },
            'trajectory',
          ),
          'trajectory',
        ),
        'up',
        5,
      ).explorer;
      const rendered = describeExplorer(ex, { rows: 20, columns: 140 }, extras)
        .map(plainOf)
        .join('\n');
      expect(rendered).toContain(expected);
      expect(rendered).toContain(`outcome    ${outcome}`);
      expect(describeExplorerListing(ex, 100).join('\n')).toContain(expected);
      if (output !== '') {
        expect(
          describeExplorer(ex, { rows: 20, columns: 80 }, extras)
            .map(plainOf)
            .join('\n'),
        ).toContain(outcome === 'exit 1' ? 'error: missing' : expected);
      }
      const payload = describeExplorer(
        explorerKeyed(explorerKeyed(ex, 'inspect', 5).explorer, 'tab', 5)
          .explorer,
        { rows: 20, columns: 80 },
        extras,
      )
        .map(plainOf)
        .join('\n');
      expect(payload).toContain('"command": "./gradlew test"');
    }
  });

  it('draws the log view as every row with its state', () => {
    const log = describeExplorer(
      open('log'),
      { rows: 16, columns: 120 },
      extras,
    ).map(plainOf);
    expect(log[0]).toContain(' · log');
    expect(
      log.some((line) => /#3 +t1 +tool_result +stands +4\.2s/u.test(line)),
    ).toBe(true);
    expect(log.at(-1)).toContain('v trajectory');
  });

  it('badges a log row as the trajectory does, and lists what an answer that only called tools asked for', () => {
    // Landed on the failed result, #3; one up is #2, the answer that asked for the run.
    const onAsking = explorerKeyed(open('log'), 'up', 5).explorer;
    const lines = describeExplorer(
      onAsking,
      { rows: 20, columns: 140 },
      extras,
    ).map(plainOf);
    expect(lines.some((line) => line.includes('│ ASST · turn 1 · #2'))).toBe(
      true,
    );
    expect(lines.some((line) => line.includes('ANSWE'))).toBe(false);
    expect(lines.some((line) => /│ run {2}\.\/gradlew test/u.test(line))).toBe(
      true,
    );
    expect(
      lines.some((line) => line.includes('"command": "./gradlew test"')),
    ).toBe(true);
    const badges = (kind: string): string => {
      const one = explorerOpened(
        levelOf(
          'cnv_1',
          'plowshare',
          { entries: [row(1, 1, kind)], through: 1, more: false },
          'log',
        ),
        'log',
      );
      const head =
        describeExplorer(one, { rows: 20, columns: 140 }, extras)
          .map(plainOf)
          .find((line) => line.includes('· turn 1 · #1')) ?? '';
      return head.split('│ ')[1]?.split(' ')[0] ?? '';
    };
    expect(
      [
        'utterance',
        'answer',
        'tool_result',
        'thinking',
        'summary',
        'hook',
        'plan',
        'attempt_failed',
        'refusal',
        'notice',
      ].map(badges),
    ).toEqual([
      'USER',
      'ASST',
      'TOOL',
      'THINK',
      'FOLD',
      'HOOK',
      'PLAN',
      'FAIL',
      'FAIL',
      'NOTE',
    ]);
  });

  it('scrolls the inspector no further than its last line', () => {
    const long = LOG.map((each) =>
      each.ordinal === 3
        ? {
            ...each,
            text: Array.from({ length: 40 }, (_, at) => `line ${at + 1}`).join(
              '\n',
            ),
          }
        : each,
    );
    const ex = explorerKeyed(
      explorerOpened(
        levelOf(
          'cnv_1',
          'plowshare',
          { entries: long, through: 4, more: false },
          'trajectory',
          'failure',
        ),
        'trajectory',
      ),
      'inspect',
      5,
    ).explorer;
    const size = { rows: 20, columns: 80 };
    const limit = explorerScrollLimit(ex, size, extras);
    // Header, pane tabs, rule and outcome, then 40 lines, less the twelve rows the list has at this size.
    expect(limit).toBe(44 - 12);
    const bottom = describeExplorer({ ...ex, scroll: limit }, size, extras).map(
      plainOf,
    );
    expect(bottom.some((line) => line.includes('line 40'))).toBe(true);
    // Scrolled past it by a state from before a resize, it still draws the bottom, not blank rows.
    expect(describeExplorer({ ...ex, scroll: 500 }, size, extras)).toEqual(
      describeExplorer({ ...ex, scroll: limit }, size, extras),
    );
  });

  it('says what the search covered and why a key did nothing', () => {
    const searched = explorerKeyed(
      explorerKeyed(
        explorerKeyed(open(), 'search', 5).explorer,
        { typed: 'fail' },
        5,
      ).explorer,
      'enter',
      5,
    ).explorer;
    expect(
      describeExplorer(searched, { rows: 16, columns: 120 }, extras).map(
        plainOf,
      ),
    ).toContain(' 2 matches in 4 of 4 entries');
    const typing = explorerKeyed(
      explorerKeyed(open(), 'search', 5).explorer,
      { typed: 'fa' },
      5,
    ).explorer;
    expect(
      describeExplorer(typing, { rows: 16, columns: 120 }, extras)
        .map(plainOf)
        .at(-1),
    ).toBe(' search: fa▏  enter keeps · esc cancels');
    expect(
      describeExplorer(
        explorerKeyed(open(), 'descend', 5).explorer,
        { rows: 16, columns: 120 },
        extras,
      ).map(plainOf),
    ).toContain(
      ' nothing opens from this row — → opens the log of an agent a call handed work to',
    );
  });

  it('prints the same rows for a surface with no keys', () => {
    expect(describeExplorerListing(open(), 100)).toEqual([
      '   1 USER  run the tests',
      '     TOOL  run        FAILED · ./gradlew test' +
        ' '.repeat(40) +
        '✗ exit 1   4.2s',
      '    outcome    exit 1',
      '    FAILED',
      '    BUILD FAILED',
      '     ASST  One test fails.',
    ]);
  });

  it("keeps a tab-indented stack trace inside the inspector's width", () => {
    const tabbed = LOG.map((each) =>
      each.ordinal === 3
        ? {
            ...each,
            text: 'FAILED\n\tat A.b(A.java:1)\n\t\tat C.d(C.java:2)\u001b[0m\r\nend',
          }
        : each,
    );
    const ex = explorerOpened(
      levelOf(
        'cnv_1',
        'plowshare',
        { entries: tabbed, through: 4, more: false },
        'trajectory',
        'failure',
      ),
      'trajectory',
    );
    for (const size of [
      { rows: 20, columns: 60 },
      { rows: 20, columns: 140 },
    ]) {
      const lines = describeExplorer(
        explorerKeyed(ex, 'inspect', 5).explorer,
        size,
        extras,
      ).map(plainOf);
      expect(
        lines.every((line) => [...Array.from(line)].length <= size.columns - 1),
      ).toBe(true);
      // eslint-disable-next-line no-control-regex -- Intentional terminal control-sequence removal or its regression assertion.
      expect(lines.join('\n')).not.toMatch(/[\t\r\u001b]/u);
      expect(
        lines.some((line) => line.includes('        at C.d(C.java:2)')),
      ).toBe(true);
    }
  });

  it('never overflows a very narrow terminal', () => {
    const lines = describeExplorer(
      open(),
      { rows: 10, columns: 15 },
      extras,
    ).map(plainOf);
    expect(lines.length).toBeLessThanOrEqual(9);
    expect(lines.every((line) => [...Array.from(line)].length <= 14)).toBe(
      true,
    );
  });
});

describe('the question dialog', () => {
  /** A question of `rows` lines at 80 columns, each a numbered sentence long enough to fill one. */
  const tall = (rows: number): string =>
    Array.from(
      { length: rows },
      (_, at) =>
        `line ${String(at + 1).padStart(2, '0')} ${'word '.repeat(13).trim()}`,
    ).join('\n');

  it("says a root's question whole, who else has it, and the keys", () => {
    expect(
      describeQuestionDialog(
        {
          id: 'orc_1',
          definition: 'implement_specification',
          question: 'Which database?',
          callerAgent: 'sophron',
        },
        false,
      ),
    ).toEqual([
      'orc_1 (implement_specification) asks:',
      'Which database?',
      'sophron, which started it, has it too; the first answer settles it.',
      'r reply · w watch · esc later',
    ]);
    // No caller named, no line about one; a surface that reads lines is told what esc is there.
    expect(
      describeQuestionDialog(
        {
          id: 'orc_1',
          definition: 'implement_specification',
          question: 'Which database?',
        },
        true,
      ),
    ).toEqual([
      'orc_1 (implement_specification) asks:',
      'Which database?',
      'r reply · w watch · an empty line decides later',
    ]);
  });

  it('wraps the question at 80 columns by default and at the width it is given, inside the box', () => {
    const question =
      'Should the character sheet keep hit points and mana on one line, or ' +
      'give each its own, given that the inventory already takes the right-hand column ' +
      'and a narrow terminal would push the mana line off the edge of the screen?';
    for (const [columns, room] of [
      [undefined, 76],
      [80, 76],
      [40, 36],
    ] as const) {
      const lines = describeQuestionDialog(
        { id: 'orc_1', definition: 'd', question },
        false,
        columns,
      );
      const asked = lines.slice(1, -1);
      expect(asked.length, `${columns}`).toBeGreaterThan(1);
      expect(
        asked.every((line) => [...Array.from(line)].length <= room),
        `${columns}`,
      ).toBe(true);
      expect(asked.join(' ')).toBe(question);
    }
  });

  it('shows twelve lines of a longer question and says where the rest is', () => {
    expect(DIALOG_QUESTION_LINES).toBe(12);
    const whole = describeQuestionDialog(
      { id: 'orc_1', definition: 'd', question: tall(12) },
      false,
    );
    expect(whole).toHaveLength(1 + 12 + 1);
    expect(whole.join('\n')).not.toContain('for the rest');
    const cut = describeQuestionDialog(
      {
        id: 'orc_1',
        definition: 'd',
        question: tall(30),
        callerAgent: 'sophron',
      },
      false,
    );
    expect(cut).toHaveLength(1 + 12 + 1 + 1 + 1);
    expect(cut[12]).toMatch(/^line 12 /u);
    expect(cut[13]).toBe('… /watch orc_1 for the rest');
    expect(cut.at(-2)).toBe(
      'sophron, which started it, has it too; the first answer settles it.',
    );
  });

  it('takes no more rows than the surface has room for, the keys kept', () => {
    const lines = describeQuestionDialog(
      {
        id: 'orc_1',
        definition: 'd',
        question: tall(30),
        callerAgent: 'sophron',
      },
      false,
      80,
      10,
    );
    expect(lines).toHaveLength(10);
    expect(lines.at(-3)).toBe('… /watch orc_1 for the rest');
    expect(lines.at(-1)).toBe('r reply · w watch · esc later');
  });

  it('asks a stuck run to go on or stop, and says no other has it', () => {
    const question =
      'orc_2 has gone three turns without progress. Go on, or stop it?';
    expect(
      describeQuestionDialog(
        {
          id: 'orc_2',
          definition: 'code_implementation',
          pendingCap: 'stuck',
          parent: 'orc_1',
          callerAgent: 'implement_specification',
          question,
        },
        false,
      ),
    ).toEqual([
      'orc_2 (code_implementation) asks:',
      question,
      'y go on · n stop · w watch · esc later',
    ]);
    expect(
      describeQuestionDialog(
        {
          id: 'orc_2',
          definition: 'code_implementation',
          pendingCap: 'stuck',
          question,
        },
        true,
      ).at(-1),
    ).toBe('y go on · n stop · w watch · an empty line decides later');
  });

  it("shows a failing check's output wrapped, asks to go on or stop, and says no other has it", () => {
    // V69: the output is the reason to ask, so more of it is shown than of other questions.
    const output = Array.from({ length: 20 }, (_, i) => `line ${i + 1}`).join(
      '\n',
    );
    const question =
      `\`orc_2\` (\`code_implementation\`)'s check \`pytest -q\` has failed 5 times.` +
      ` The last failure:\n--- stderr ---\n${output}\npygame.error: No available audio device, and a` +
      ' long line that is wrapped to the box\nGo on, or stop?';
    const lines = describeQuestionDialog(
      {
        id: 'orc_2',
        definition: 'code_implementation',
        pendingCap: 'check_failures',
        parent: 'orc_1',
        callerAgent: 'implement_specification',
        question,
      },
      false,
      50,
    );
    expect(lines[0]).toBe('orc_2 (code_implementation) asks:');
    expect(lines).toContain('line 20');
    expect(lines).toContain('pygame.error: No available audio device, and a');
    expect(lines).toContain('long line that is wrapped to the box');
    expect(lines.every((line) => line.length <= 46)).toBe(true);
    expect(lines.some((line) => line.includes('has it too'))).toBe(false);
    expect(lines.at(-1)).toBe('y go on · n stop · w watch · esc later');
    expect(
      describeQuestionDialog(
        {
          id: 'orc_2',
          definition: 'd',
          pendingCap: 'check_failures',
          question,
        },
        true,
      ).at(-1),
    ).toBe('y go on · n stop · w watch · an empty line decides later');
    const cut = describeQuestionDialog(
      {
        id: 'orc_2',
        definition: 'd',
        pendingCap: 'check_failures',
        question: tall(60),
      },
      false,
      80,
    );
    expect(cut.length).toBe(1 + DIALOG_CHECK_LINES + 2);
    expect(cut.at(-2)).toBe('… /watch orc_2 for the rest');
  });

  it('says what an unread failing-check question and time cap are about', () => {
    expect(
      describeNowAsking({
        id: 'orc_2',
        definition: 'code_implementation',
        pendingCap: 'check_failures',
      }),
    ).toBe(
      'code_implementation (orc_2) is asking: whether it goes on after its check kept failing' +
        ' — /answer to reply',
    );
    expect(
      describeNowAsking({
        id: 'orc_2',
        definition: 'code_implementation',
        pendingCap: 'time_cap',
      }),
    ).toBe(
      'code_implementation (orc_2) is asking: whether it goes on past its time cap — /answer to reply',
    );
  });

  it('asks whether a spec the verifier found wanting stands, and says no other has it', () => {
    // V65: the verifier's third finding is the person's alone. `y` accepts the spec as
    // written; `r` answers in words, which the conductor is given as their direction.
    const question =
      "`orc_1` (`implement_specification`) wrote spec.md's acceptance commands," +
      ' and the verifier found no command that would observe:\n- a main game loop\n' +
      'Its commands are:\n- run: make check | exit: 0\n' +
      '`/answer orc_1 accept` lets the spec stand as written; any other answer is passed to' +
      ' the conductor as your direction.';
    const lines = describeQuestionDialog(
      {
        id: 'orc_1',
        definition: 'implement_specification',
        pendingCap: 'uncovered',
        callerAgent: 'sophron',
        question,
      },
      false,
      80,
    );
    expect(lines[0]).toBe('orc_1 (implement_specification) asks:');
    expect(lines).toContain('- a main game loop');
    expect(lines).toContain('- run: make check | exit: 0');
    expect(lines.some((line) => line.includes('has it too'))).toBe(false);
    expect(lines.at(-1)).toBe('y accept · r reply · w watch · esc later');
    expect(
      describeQuestionDialog(
        {
          id: 'orc_1',
          definition: 'implement_specification',
          pendingCap: 'uncovered',
          question,
        },
        true,
      ).at(-1),
    ).toBe('y accept · r reply · w watch · an empty line decides later');
    // Shown as long as a failing check's output (V77): its checklist is why it is asked.
    const cut = describeQuestionDialog(
      {
        id: 'orc_1',
        definition: 'd',
        pendingCap: 'uncovered',
        question: tall(DIALOG_CHECK_LINES + 10),
      },
      false,
      80,
    );
    expect(cut.at(-2)).toBe('… /watch orc_1 for the rest');
    expect(cut.at(-1)).toBe('y accept · r reply · w watch · esc later');
  });

  it("says what an unread verifier question is about, in no tool's words", () => {
    expect(
      describeNowAsking({
        id: 'orc_1',
        definition: 'implement_specification',
        pendingCap: 'uncovered',
      }),
    ).toBe(
      'implement_specification (orc_1) is asking: whether its acceptance commands stand as' +
        ' written — /answer to reply',
    );
  });

  it('asks the person to check the product, with its checklist and no other who has it', () => {
    // V77: every run: line passed; what no command can observe is the person's alone. `y`
    // accepts the product; `r` answers in words, which send the run back as their notes.
    const question =
      "`orc_1` (`implement_specification`)'s acceptance commands all passed: 2 of 2." +
      ' What no command here can observe is yours to check.\n\nHow it starts:\n' +
      '- `npm start` in /repo (it kept running for 5s)\n\nCheck:\n' +
      '1. press the arrow keys — you should see: the ship moves\n\n' +
      '`/answer orc_1 accept` accepts the product.';
    const lines = describeQuestionDialog(
      {
        id: 'orc_1',
        definition: 'implement_specification',
        pendingCap: 'product_check',
        callerAgent: 'sophron',
        question,
      },
      false,
      80,
    );
    expect(lines).toContain(
      '1. press the arrow keys — you should see: the ship moves',
    );
    expect(lines.some((line) => line.includes('has it too'))).toBe(false);
    expect(lines.at(-1)).toBe('y accept · r reply · w watch · esc later');
    expect(
      describeNowAsking({
        id: 'orc_1',
        definition: 'implement_specification',
        pendingCap: 'product_check',
      }),
    ).toBe(
      'implement_specification (orc_1) is asking: to check' +
        ' the product: what no command could — /answer to reply',
    );
    expect(
      describeNowAsking({
        id: 'orc_1',
        definition: 'implement_specification',
        pendingCap: 'concerns',
      }),
    ).toBe(
      'implement_specification (orc_1) is asking: about its' +
        " acceptance checker's concerns — /answer to reply",
    );
  });

  it('asks an install question with no line about who else has it, though a caller started it', () => {
    // V71: install is person-only ({@link PERSON_ONLY_KINDS}), so unlike a root's own question
    // it says nothing about the caller model that started the run, even when there is one.
    const question =
      '`orc_1` (`design_orchestration`) drafted an orchestration. Install it?';
    const lines = describeQuestionDialog(
      {
        id: 'orc_1',
        definition: 'design_orchestration',
        pendingCap: 'install',
        callerAgent: 'sophron',
        question,
      },
      false,
      80,
    );
    expect(lines[0]).toBe('orc_1 (design_orchestration) asks:');
    expect(lines.some((line) => line.includes('has it too'))).toBe(false);
  });

  it('says the question could not be read rather than showing nothing', () => {
    expect(
      describeQuestionDialog({ id: 'orc_1', definition: 'd' }, false)[1],
    ).toBe('its question could not be read — w watches the run');
  });

  it('says who answered first, and what to type to reply on a surface that reads lines', () => {
    expect(describeQuestionSettled('orc_1', 'sophron', 'PostgreSQL')).toBe(
      "orc_1's question was answered by sophron: PostgreSQL",
    );
    expect(describeReplyWith('orc_1')).toBe(
      'to reply, type: /answer orc_1 <your answer>',
    );
  });
});

describe('the question modal', () => {
  const run: Waiting = { id: 'orc_1', definition: 'design_orchestration' };
  const structure: Structure = {
    lead: 'First:',
    questions: [
      {
        header: 'Store',
        question: 'Which database?',
        multi: false,
        options: [
          { label: 'Postgres', description: 'p' },
          { label: 'SQLite', description: 's', preview: 'db.sqlite\nwal mode' },
        ],
      },
      {
        header: 'Clients',
        question: 'Which clients?',
        multi: true,
        options: [
          { label: 'TUI', description: 't' },
          { label: 'Console', description: 'c' },
        ],
      },
    ],
  };
  const start = answeringAbout('orc_1', structure);

  it('draws the chips, the lead, the question, its options and its keys', () => {
    expect(describeQuestionsDialog(run, start, 80)).toEqual([
      'orc_1 (design_orchestration) asks:',
      '[Store ]  Clients ',
      'First:',
      'Which database?',
      '› 1. ( ) Postgres — p',
      '  2. ( ) SQLite — s',
      '↑↓ move · 1-2 or enter picks · o other · n note · tab next · esc later',
    ]);
  });

  it('marks what is chosen and answered, and says enter sends on the last question', () => {
    const state = (
      [
        { kind: 'text', text: '2' },
        { kind: 'tab' },
        { kind: 'text', text: '1' },
      ] as QuestionStroke[]
    ).reduce(answeringOn, start);
    const lines = describeQuestionsDialog(run, state, 80);
    expect(lines[1]).toBe(' Store✓  [Clients✓]');
    expect(lines).toContain('› 1. [x] TUI — t');
    expect(lines.at(-1)).toBe(
      '↑↓ move · 1-2 or space toggles · o other · n note · tab next · enter sends · esc later',
    );
  });

  it('shows a focused preview below the options when narrow, beside them when wide', () => {
    const focused = answeringOn(start, { kind: 'down' });
    const narrow = describeQuestionsDialog(run, focused, 80);
    expect(narrow.slice(6, 9)).toEqual([
      '─'.repeat(40),
      'db.sqlite',
      'wal mode',
    ]);
    // Beside: the preview's lines ride the option rows from the top, one each.
    const wide = describeQuestionsDialog(run, focused, 120);
    expect(
      wide.some(
        (line) =>
          line.startsWith('  1. ( ) Postgres — p') &&
          line.endsWith('│ db.sqlite'),
      ),
    ).toBe(true);
    expect(
      wide.some(
        (line) =>
          line.startsWith('› 2. ( ) SQLite — s') && line.endsWith('│ wal mode'),
      ),
    ).toBe(true);
    expect(wide).not.toContain('─'.repeat(40));
  });

  it("offers v for an install question's draft only where the surface can show it", () => {
    const drafted = answeringAbout('orc_1', {
      ...structure,
      draft: { name: 'x', path: 'p', text: 't' },
    });
    expect(
      describeQuestionsDialog(run, drafted, 80, undefined, true).at(-1),
    ).toBe(
      '↑↓ move · 1-2 or enter picks · o other · n note · tab next · v view draft · esc later',
    );
    // No viewer to show it in, or no draft to show: no v.
    expect(describeQuestionsDialog(run, drafted, 80).at(-1)).not.toContain(
      'v view draft',
    );
    expect(
      describeQuestionsDialog(run, start, 80, undefined, true).at(-1),
    ).not.toContain('v view draft');
    // Typing, v is a letter.
    const typing = answeringOn(drafted, { kind: 'text', text: 'o' });
    expect(
      describeQuestionsDialog(run, typing, 80, undefined, true).at(-1),
    ).toBe('type · enter keeps it · esc drops it');
  });

  it('shows what is being typed, and names a question left unanswered', () => {
    const typing = answeringOn(start, { kind: 'text', text: 'o' });
    expect(describeQuestionsDialog(run, typing, 80)).toContain('  o. Other: ▏');
    expect(describeQuestionsDialog(run, typing, 80).at(-1)).toBe(
      'type · enter keeps it · esc drops it',
    );
    const missing = (
      [
        { kind: 'tab' },
        { kind: 'text', text: '1' },
        { kind: 'enter' },
      ] as QuestionStroke[]
    ).reduce(answeringOn, start);
    expect(describeQuestionsDialog(run, missing, 80)).toContain(
      'Answer "Store" first.',
    );
  });

  it('gives the preview up before the keys when there is no room', () => {
    const focused = answeringOn(start, { kind: 'down' });
    const lines = describeQuestionsDialog(run, focused, 80, 8);
    expect(lines).not.toContain('db.sqlite');
    expect(lines.at(-1)).toContain('esc later');
  });

  it("shortens the options' descriptions to fit the room, never the labels or the keys", () => {
    // Final review: four long descriptions wrapped to four rows each ran past a 24-row terminal.
    const long: Structure = {
      lead: '',
      questions: [
        {
          header: 'Store',
          question: 'Which database?',
          multi: false,
          options: ['Postgres', 'SQLite', 'MySQL', 'DuckDB'].map((label) => ({
            label,
            description: 'd'.repeat(300),
          })),
        },
      ],
    };
    const lines = describeQuestionsDialog(
      run,
      answeringAbout('orc_1', long),
      80,
      20,
    );
    expect(lines.length).toBeLessThanOrEqual(20);
    expect(lines.at(-1)).toBe(
      '↑↓ move · 1-4 or enter picks · o other · n note · tab next · enter sends · esc later',
    );
    for (const label of ['Postgres', 'SQLite', 'MySQL', 'DuckDB']) {
      expect(
        lines.some(
          (line) => line.includes(`( ) ${label} — d`) && line.endsWith('…'),
        ),
      ).toBe(true);
    }
    // Long words being typed are one row, their end shown, where the cursor is.
    const typing = answeringOn(answeringAbout('orc_1', long), {
      kind: 'text',
      text: 'o',
    });
    const typed = describeQuestionsDialog(
      run,
      answeringOn(typing, { kind: 'text', text: `${'w'.repeat(299)}z` }),
      80,
      20,
    );
    const other = typed.find((line) => line.startsWith('  o. Other: '));
    expect(other?.length).toBeLessThanOrEqual(76);
    expect(other?.endsWith('z▏')).toBe(true);
    expect(typed.length).toBeLessThanOrEqual(20);
  });

  it('cuts a long lead and question to the room, saying where the rest is, before any option goes', () => {
    const long: Structure = {
      lead: Array.from({ length: 30 }, (_, at) => `Lead line ${at + 1}.`).join(
        '\n',
      ),
      questions: [
        {
          header: 'Store',
          question: 'Which database?',
          multi: false,
          options: [
            { label: 'Postgres', description: 'p' },
            { label: 'SQLite', description: 's' },
          ],
        },
      ],
    };
    const lines = describeQuestionsDialog(
      run,
      answeringAbout('orc_1', long),
      80,
      10,
    );
    expect(lines.length).toBeLessThanOrEqual(10);
    expect(lines).toContain('… /watch orc_1 for the rest');
    expect(lines).toContain('› 1. ( ) Postgres — p');
    expect(lines).toContain('  2. ( ) SQLite — s');
    expect(lines.at(-1)).toContain('esc later');
    // No room given, at most DIALOG_QUESTION_LINES of it, as a question in words.
    const unbounded = describeQuestionsDialog(
      run,
      answeringAbout('orc_1', long),
      80,
    );
    expect(
      unbounded.filter((line) => line.startsWith('Lead line')),
    ).toHaveLength(DIALOG_QUESTION_LINES);
  });

  it('says the caller that started it has it too, above the keys, as a question in words does', () => {
    const also =
      'sophron, which started it, has it too; the first answer settles it.';
    const started: Waiting = { ...run, callerAgent: 'sophron' };
    const lines = describeQuestionsDialog(started, start, 80);
    expect(lines.slice(-2)).toEqual([
      also,
      '↑↓ move · 1-2 or enter picks · o other · n note · tab next · esc later',
    ]);
    // Above the rule too, when a preview is drawn below the options.
    const below = describeQuestionsDialog(
      started,
      answeringOn(start, { kind: 'down' }),
      80,
    );
    expect(below.indexOf(also)).toBeLessThan(below.indexOf('─'.repeat(40)));
    // Nobody else has a question only the person may answer.
    expect(
      describeQuestionsDialog({ ...started, pendingCap: 'stuck' }, start, 80),
    ).not.toContain(also);
    expect(describeQuestionsDialog(run, start, 80)).not.toContain(also);
  });

  it("draws the model's labels, descriptions and previews cleaned — no tab or escape reaches the terminal", () => {
    const raw: Structure = {
      lead: '',
      questions: [
        {
          header: 'St\u001b[1more',
          question: 'Which?',
          multi: false,
          options: [
            {
              label: 'Post\tgres',
              description: 'a \u001b[31mred\u001b[0m\tthing',
              preview: '\u001b]0;retitled\u0007one\n\ttwo',
            },
            { label: 'SQLite', description: 's\nfile' },
          ],
        },
      ],
    };
    for (const columns of [80, 120]) {
      const lines = describeQuestionsDialog(
        run,
        answeringAbout('orc_1', raw),
        columns,
      );
      // eslint-disable-next-line no-control-regex -- Intentional terminal control-sequence removal or its regression assertion.
      expect(lines.join('\n')).not.toMatch(/[\t\u001b\u0007]/u);
      expect(
        lines.some((line) => /Post +gres — a red +thing/u.test(line)),
      ).toBe(true);
      expect(lines.some((line) => line.includes('SQLite — s file'))).toBe(true);
      expect(lines[1]).toBe('[Store ]');
    }
    expect(
      describeQuestionsDialog(run, answeringAbout('orc_1', raw), 80),
    ).toContain('    two');
    // What is sent is the label the server wrote, uncleaned: it is matched against it.
    const sent = answeringOn(answeringAbout('orc_1', raw), { kind: 'enter' });
    expect(sent.kind === 'answered' ? sent.ask.payload : undefined).toEqual({
      id: 'orc_1',
      choices: [{ header: 'St\u001b[1more', chosen: ['Post\tgres'] }],
    });
  });

  it('puts kept words above the rule and the preview, below them, with the keys last', () => {
    const state = (
      [
        { kind: 'text', text: 'o' },
        { kind: 'text', text: 'MySQL' },
        { kind: 'enter' },
        { kind: 'down' },
      ] as QuestionStroke[]
    ).reduce(answeringOn, start);
    const lines = describeQuestionsDialog(run, state, 80);
    const other = lines.indexOf('  o. Other: MySQL');
    const rule = lines.indexOf('─'.repeat(40));
    expect(other).toBeGreaterThan(-1);
    expect(rule).toBeGreaterThan(other);
    expect(lines.at(-1)).toBe(
      '↑↓ move · 1-2 or enter picks · o other · n note · tab next · esc later',
    );
  });
});

describe('the cap dialog', () => {
  it("says a run past its time cap stopped there, with the cap's keys", () => {
    expect(
      describeCapDialog(
        {
          id: 'orc_2',
          definition: 'code_implementation',
          pendingCap: 'time_cap',
        },
        'check `pytest -q` failed (exit 1)',
        false,
      ),
    ).toEqual([
      'code_implementation (orc_2) ran past its time cap',
      'last: check `pytest -q` failed (exit 1)',
      'y continue · n stop · a always (auto-continue 3) · w watch · esc later',
    ]);
  });

  it('says the run, why it stopped, its last milestone and the keys', () => {
    expect(
      describeCapDialog(
        {
          id: 'orc_2',
          definition: 'code_implementation',
          pendingCap: 'turn_cap',
        },
        '05:21:07 · 03-character · spec: in_progress → done',
        false,
      ),
    ).toEqual([
      'code_implementation (orc_2) stopped at its turn cap',
      'last: 05:21:07 · 03-character · spec: in_progress → done',
      'y continue · n stop · a always (auto-continue 3) · w watch · esc later',
    ]);
    expect(
      describeCapDialog(
        {
          id: 'orc_2',
          definition: 'code_implementation',
          pendingCap: 'call_budget',
        },
        undefined,
        true,
      ),
    ).toEqual([
      'code_implementation (orc_2) spent its model-call budget',
      'last: nothing recorded yet',
      'y continue · n stop · a always (auto-continue 3) · w watch · an empty line decides later',
    ]);
  });

  it('says who answered a cap question first, when its dialog is taken away', () => {
    expect(describeCapSettled('orc_2', 'implement_specification', 'yes')).toBe(
      "orc_2's cap question was answered by implement_specification: yes",
    );
  });
});
