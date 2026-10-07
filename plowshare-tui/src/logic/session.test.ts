import type { Answer } from 'plowshare-client-ts/operations/response';
import { describe, expect, it } from 'vitest';

/** The id this client listens under. A run must name it or the server
 *  publishes its events to a session nobody holds -- see `speaking`. */
const LISTENING_AS = 'a-listening-session';

import {
  APPROVAL_ANSWER,
  APPROVAL_LIST,
  APPROVAL_REVOKE,
  answeredOf,
  answeringApproval,
  approvalsOf,
  handing,
  listingApprovals,
  listingAsked,
  revokedOf,
  revokingApproval,
  AGENT_LIST,
  AGENT_RUN,
  CONVERSATION_CONTEXT,
  CONVERSATION_LATEST,
  CONVERSATION_LIST,
  CONVERSATION_OPEN,
  CONVERSATION_TRAJECTORY,
  INBOX_LIST,
  INBOX_READ,
  EVENT_FIRE,
  FIRING_LIST,
  JOB_CANCEL,
  JOB_STATUS,
  OK,
  PROJECT_LIST,
  SCHEDULE_DEFINE,
  SCHEDULE_FORGET,
  SCHEDULE_LIST,
  SCHEDULE_PAUSE,
  SCHEDULE_READ,
  TRIGGER_DEFINE,
  TRIGGER_FORGET,
  TRIGGER_LIST,
  TRIGGER_PAUSE,
  agents,
  answeredByFallback,
  answeredYes,
  definingSchedule,
  definingTrigger,
  firingEvent,
  firingsOf,
  forgettingSchedule,
  forgettingTrigger,
  listeningTo,
  listingFirings,
  listingSchedules,
  listingTriggers,
  pausingSchedule,
  pausingTrigger,
  proposalOf,
  readingSchedule,
  schedulesOf,
  triggersOf,
  answering,
  cancelling,
  checking,
  completable,
  completing,
  continued,
  continuing,
  conversations,
  entriesOf,
  finished,
  followed,
  following,
  inboxPageOf,
  listingAgents,
  loadOf,
  measuring,
  listingConversations,
  listingInbox,
  logTotal,
  listingProjects,
  opened,
  opening,
  projects,
  reached,
  readingInbox,
  speaking,
  taking,
  streamed,
  typed,
  unreadOf,
  whoAnswers,
  definitionsOf,
  listingDefinitions,
  listingRuns,
  readingRun,
  runStatusOf,
  runsOf,
  structureOf,
  ORCHESTRATION_ANSWER,
  ORCHESTRATION_CANCEL,
  ORCHESTRATION_LIST,
  answeringRun,
  answeringRunWith,
  cancellingRun,
  changedOf,
  listingAsking,
  questionOf,
  rechecks,
  settledRun,
  waitingAfter,
  CAP_KINDS,
  PERSON_ONLY_KINDS,
  dialogKindOf,
  approvalsWaiting,
  listingMyApprovals,
  listingStalled,
  stalledWaiting,
  CONVERSATION_APPENDED,
  CONVERSATION_FOLLOW,
  DRAWN_KINDS,
  LOG_BACK,
  LOG_PAGE,
  appendedOf,
  backPageOf,
  followingLog,
  followingLogs,
  logThrough,
  readingAfter,
  readingEarlier,
  readingTail,
  readingTrace,
  readingLogTail,
  readingLogBefore,
  catchingUp,
  loggedFrom,
  replaying,
} from './session.ts';
import type { Entry } from './session.ts';
import type {
  Agent,
  Approval,
  Proposal,
  Run,
  RunStatus,
  Waiting,
} from './session.ts';
import {
  APPROVALS_COMMAND,
  describeAnswered,
  describeApprovals,
  describeRevoked,
  describeUsage,
  AGENTS_COMMAND,
  BOTS_COMMAND,
  CD_COMMAND,
  COMMANDS,
  CONVERSATIONS_COMMAND,
  DIAGNOSE_COMMAND,
  HELP_COMMAND,
  EARLIER_COMMAND,
  HERE_COMMAND,
  LOG_COMMAND,
  ORCHESTRATIONS_COMMAND,
  PROJECT_COMMAND,
  PROJECTS_COMMAND,
  RUNS_COMMAND,
  ANSWER_COMMAND,
  CANCEL_COMMAND,
  ALWAYS_COMMAND,
  CAP_COMMAND,
  describeSettled,
  describeAgents,
  describeBots,
  describeCancelling,
  describeChoice,
  describeContinuing,
  describeBeginning,
  describeEarlier,
  describeEarlierHint,
  describeNoEarlier,
  describeNotYetShown,
  describeConversations,
  describeCost,
  describeEnding,
  describeFilesElsewhere,
  describeHelp,
  describeLeaving,
  describeMovedTo,
  describeNew,
  describeNothingRunning,
  describeProvenance,
  describeProgress,
  describeProjects,
  describeRefusedMove,
  describeSeam,
  describeSilence,
  describeSpokenIn,
  describeUnknownCommand,
  describeUnnamedRun,
  refusal,
} from './wording.ts';

/**
 * What a conversation is, in logic that knows no terminal.
 *
 * <b>Nothing in here builds a socket, and that is the point of the file it
 * tests.</b> Every case below hands `session.ts` a plain value — a frame
 * arriving, an answer that came back — and reads a plain value out. The view
 * is what owns a `Connection`; this module owns what the values <i>mean</i>,
 * which is what makes the eventual GUI a port rather than a redo (spec §3).
 *
 * <b>It lives in `logic/` on purpose, and that puts it under
 * `src/logic/tsconfig.json`</b> — its `include` takes every `.ts` file under
 * the directory, so `tsc -b --force` type-checks this file the same as the
 * source beside it, and `build/types/logic/session.test.d.ts` is the evidence.
 * Three test files in this module are outside every project
 * (`src/neutrality.test.ts`, `src/mirrors-the-server.test.ts`,
 * `src/real-socket.test.ts`) and each is there because it imports `node:fs` or
 * drives a real socket. This one needs neither, so it is checked rather than
 * excused.
 */

/** The `conversation.open` answer, as `ConversationView` serialises. */
const OPENED = {
  code: 'OK',
  payload: {
    id: 'conv_000004',
    project: 'plowshare',
    maxModelCalls: 20,
    modelCallsSpent: 0,
    maxTurns: 8,
    noTurnCap: false,
    noBudget: false,
  },
};

/** The `agent.run` answer: 202 and a handle, per `AgentRunHandler`. */
const HANDED = {
  code: 'ACCEPTED',
  payload: { id: 'job_000007', agent: 'scribe' },
};

/**
 * A bare `JobEvent`, exactly as `/v1/events` publishes one — no envelope.
 *
 * The nulls are Jackson's, not decoration: `JobEvent.started` really does put
 * null in `tool` and `ending`, and a reader that treats a present null as a
 * value rather than as an absence gets a tool call named `null`.
 */
function push(over: Record<string, unknown>): unknown {
  return {
    job: 'job_000007',
    kind: 'started',
    agent: 'scribe',
    tool: null,
    ending: null,
    steps: 0,
    modelCalls: 0,
    ...over,
  };
}

describe('the frames a conversation is made of', () => {
  it('opens a conversation under the type the server routes', () => {
    expect(opening()).toEqual({ type: CONVERSATION_OPEN, payload: {} });
    expect(CONVERSATION_OPEN).toBe('conversation.open');
  });

  it('sends an empty payload when no project is named, and not a null one', () => {
    // `OpenConversationRequest` is all-optional and a body naming nothing
    // takes the deployment's own default budget. A client filling in nulls
    // would be stating something it was never asked.
    expect(opening().payload).toEqual({});
    expect(Object.keys(opening().payload)).toEqual([]);
  });

  it('carries the project when there is one', () => {
    expect(opening('plowshare').payload).toEqual({ project: 'plowshare' });
  });

  it('speaks a turn as agent.run, which is what makes a turn a job', () => {
    expect(
      speaking('conv_000004', 'scribe', 'what changed?', LISTENING_AS),
    ).toEqual({
      type: AGENT_RUN,
      payload: {
        agent: 'scribe',
        conversation: 'conv_000004',
        task: 'what changed?',
        // <b>Without this the server drops every event the run produces.</b>
        // JobEvents.publish delivers "to whatever holds the listener role
        // on `session`, or drop[s] it" -- so a run that names none is a run
        // nobody is told about, and a client waits for an ENDED that was
        // thrown away. Measured against a real server: the job finished
        // ANSWERED while the terminal sat waiting eighteen minutes.
        session: LISTENING_AS,
      },
    });
    expect(AGENT_RUN).toBe('agent.run');
  });

  it('follows a handle under job.status, whose field is named for the job', () => {
    expect(checking('job_000007')).toEqual({
      type: JOB_STATUS,
      payload: { job: 'job_000007' },
    });
  });

  it('asks a run to stop under job.cancel, by the same handle it follows it by', () => {
    // ROUTED SINCE THE FRAME SURFACE LANDED AND NEVER CALLED. Ctrl-C used
    // to quit this client and leave the run going server-side, spending a
    // budget nobody was watching.
    expect(cancelling('job_000007')).toEqual({
      type: JOB_CANCEL,
      payload: { job: 'job_000007' },
    });
    expect(JOB_CANCEL).toBe('job.cancel');
    // The same field `checking` names, because it is the same handle: a
    // client that followed one id and cancelled another would stop a run
    // nobody was watching and go on watching the one they meant to stop.
    expect(cancelling('job_000007').payload).toEqual(
      checking('job_000007').payload,
    );
  });
});

describe('what a conversation.open answer says', () => {
  it('reads the id to speak into', () => {
    expect(opened(OPENED)).toEqual({ id: 'conv_000004', project: 'plowshare' });
  });

  it('leaves project absent rather than null when the answer has none', () => {
    const bare = opened({
      code: 'OK',
      payload: { id: 'conv_000004', project: null },
    });

    expect(bare).toEqual({ id: 'conv_000004' });
    expect(bare && 'project' in bare).toBe(false);
  });

  it('reads nothing out of a refusal, so the caller has to look at the code', () => {
    expect(
      opened({ code: 'CONFLICT', said: 'that project has no workspace' }),
    ).toBeUndefined();
  });
});

describe('ACCEPTED is not OK, and a turn that confused them would lie', () => {
  it('takes the handle out of the 202 and has not finished on it', () => {
    const turn = answering(
      taking('conv_000004', 'scribe', 'what changed?', LISTENING_AS),
      HANDED,
    );

    expect(turn.job).toBe('job_000007');
    expect(turn.refused).toBeUndefined();
    expect(finished(turn)).toBe(false);
  });

  it('refuses an OK where a run answers ACCEPTED, rather than reading it as done', () => {
    // The whole distinction, in one case. An `OK` on this type means the
    // server did something other than start a run; a client that folded the
    // two would tell a person their work had finished while holding no
    // handle at all, which is `codes.ts`'s own sentence and the reason the
    // four successes stay four.
    const turn = answering(
      taking('conv_000004', 'scribe', 'what changed?', LISTENING_AS),
      { code: 'OK', payload: { id: 'job_000007' } },
    );

    expect(turn.refused).toContain('ACCEPTED');
    expect(turn.job).toBeUndefined();
    expect(turn.stopped).toBeUndefined();
    expect(finished(turn)).toBe(true);
  });

  it('refuses an ACCEPTED that named no handle, because there is nothing to follow', () => {
    const turn = answering(taking('conv_000004', 'scribe', 'x', LISTENING_AS), {
      code: 'ACCEPTED',
    });

    expect(turn.job).toBeUndefined();
    expect(turn.refused).toBeTruthy();
    expect(finished(turn)).toBe(true);
  });

  it('keeps the server sentence when there is one', () => {
    const turn = answering(taking('conv_000004', 'scribe', 'x', LISTENING_AS), {
      code: 'CONFLICT',
      said: 'a turn is already in flight in this conversation',
    });

    expect(turn.refused).toBe(
      'a turn is already in flight in this conversation',
    );
  });

  it('says only what it knows when the server said nothing', () => {
    const turn = answering(taking('conv_000004', 'scribe', 'x', LISTENING_AS), {
      code: 'ARCHIVE_UNAVAILABLE',
    });

    expect(turn.refused).toContain('ARCHIVE_UNAVAILABLE');
    expect(turn.refused).not.toBe('');
  });
});

describe('a turn tolerates either interleaving, because the wire does', () => {
  /*
   * Spec §4.3 and task 4's own proof, one layer up. A response may arrive
   * before or after an event its own request caused, so a turn that assumed
   * "the handle comes first" would drop every event of a fast run on the
   * floor and sit there watching nothing.
   *
   * MEASURED BY MUTATION, the way task 4 measured the connection: `following`
   * was rewritten to the naive shape — dropping any push while `job` is
   * undefined, with no holding area at all — and exactly two of the six cases
   * below went red, which are exactly the two that depend on the events
   * outrunning the answer:
   *
   *   reaches the same turn whichever order the socket delivered
   *       -> expected { …(4) } to deeply equal { …(5) }
   *   has both events and the ending, with the events arriving first
   *       -> expected [] to deeply equal [ 'started', 'ended' ]
   *
   * The answer-first case stayed green throughout, which is the point: it is
   * the test that happens to pass, and on its own it would have blessed a
   * client that loses every event of a fast run. Restored afterwards.
   */

  const started = push({ kind: 'started' });
  const ended = push({
    kind: 'ended',
    ending: 'ANSWERED',
    steps: 3,
    modelCalls: 4,
  });

  function answerFirst() {
    let turn = taking('conv_000004', 'scribe', 'what changed?', LISTENING_AS);
    turn = answering(turn, HANDED);
    turn = following(turn, started);
    return following(turn, ended);
  }

  function eventsFirst() {
    let turn = taking('conv_000004', 'scribe', 'what changed?', LISTENING_AS);
    turn = following(turn, started);
    turn = following(turn, ended);
    return answering(turn, HANDED);
  }

  it('reaches the same turn whichever order the socket delivered', () => {
    expect(eventsFirst()).toEqual(answerFirst());
  });

  it('has both events and the ending, with the answer arriving first', () => {
    const turn = answerFirst();

    expect(turn.progress.map((seen) => seen.kind)).toEqual([
      'started',
      'ended',
    ]);
    expect(turn.stopped?.ending).toBe('ANSWERED');
    expect(finished(turn)).toBe(true);
  });

  it('has both events and the ending, with the events arriving first', () => {
    const turn = eventsFirst();

    expect(turn.progress.map((seen) => seen.kind)).toEqual([
      'started',
      'ended',
    ]);
    expect(turn.stopped?.ending).toBe('ANSWERED');
    expect(finished(turn)).toBe(true);
  });

  it('holds nothing back once the handle is known', () => {
    expect(eventsFirst().held).toEqual([]);
  });

  it("drops another job's events that arrived while the handle was unknown", () => {
    // One session may have several jobs running at once — `JobEvent.job`
    // exists for exactly that — so events held before the handle are
    // decided by the handle and not kept because they were first.
    let turn = taking('conv_000004', 'scribe', 'x', LISTENING_AS);
    turn = following(turn, push({ job: 'job_000009' }));
    turn = answering(turn, HANDED);

    expect(turn.progress).toEqual([]);
    expect(turn.held).toEqual([]);
  });

  it("drops another job's events after the handle is known", () => {
    let turn = answering(
      taking('conv_000004', 'scribe', 'x', LISTENING_AS),
      HANDED,
    );
    turn = following(
      turn,
      push({ job: 'job_000009', kind: 'ended', ending: 'STUCK' }),
    );

    expect(turn.progress).toEqual([]);
    expect(turn.stopped).toBeUndefined();
  });

  it('does not attach a frame it cannot read to this turn', () => {
    // Something arrived that is not a JobEvent at all. It belongs to no job,
    // so putting it in one person's transcript would be an invention.
    const turn = following(
      answering(taking('conv_000004', 'scribe', 'x', LISTENING_AS), HANDED),
      'nonsense',
    );

    expect(turn.progress).toEqual([]);
  });
});

describe('what one event means', () => {
  it('reads a run beginning', () => {
    expect(followed(push({ kind: 'started' }))).toEqual({
      kind: 'started',
      job: 'job_000007',
      agent: 'scribe',
    });
  });

  it('reads a model call as a step, which is not a turn', () => {
    // `JobEvent.steps` was called `turns` and the word was wrong twice over:
    // a turn is one thing a person said. This module keeps the server's
    // corrected vocabulary so no view has to re-learn it.
    expect(
      followed(push({ kind: 'model_call', steps: 2, modelCalls: 3 })),
    ).toEqual({
      kind: 'step',
      job: 'job_000007',
      agent: 'scribe',
      steps: 2,
      modelCalls: 3,
    });
  });

  it('reads a tool call, under the name the server registered', () => {
    expect(followed(push({ kind: 'tool_called', tool: 'file_read' }))).toEqual({
      kind: 'tool',
      job: 'job_000007',
      agent: 'scribe',
      tool: 'file_read',
    });
  });

  it('reads an ending with what it cost', () => {
    expect(
      followed(
        push({ kind: 'ended', ending: 'TURN_CAP', steps: 8, modelCalls: 9 }),
      ),
    ).toEqual({
      kind: 'ended',
      job: 'job_000007',
      agent: 'scribe',
      ending: 'TURN_CAP',
      steps: 8,
      modelCalls: 9,
    });
  });

  it('does not fail to bind over a kind it has never heard of', () => {
    // `JobEvent.kind` is a String and not an enum precisely so that the two
    // halves of this wire can ship separately. A client that threw here
    // would turn a server upgrade into a dead terminal.
    expect(followed(push({ kind: 'compacted' }))).toEqual({
      kind: 'other',
      job: 'job_000007',
      named: 'compacted',
    });
  });

  it('says so, rather than guessing, when the frame is not an event at all', () => {
    expect(followed('nonsense').kind).toBe('unreadable');
    expect(followed(null).kind).toBe('unreadable');
    expect(followed({ kind: 'started' }).kind).toBe('unreadable');
  });
});

describe('reading a run that is over', () => {
  it('leaves completion unknown for malformed or foreign status replies', () => {
    for (const payload of [
      { outcome: { ending: 'ANSWERED', answered: true } },
      { id: 'job_000007', state: 'DONE', outcome: { ending: 'ANSWERED' } },
      {
        id: 'job_000007',
        state: 'DONE',
        outcome: { ending: 'ANSWERED', answered: false },
      },
      {
        id: 'other-job',
        state: 'DONE',
        outcome: { ending: 'ANSWERED', answered: true },
      },
    ])
      expect(reached({ code: 'OK', payload }, 'job_000007')).toBeUndefined();
  });

  it('says nothing is over while the job is still running', () => {
    // `JobView.outcome` is null until it has finished, and that null is the
    // whole of the difference a caller needs.
    expect(
      reached({ code: 'OK', payload: { id: 'job_000007', state: 'RUNNING' } }),
    ).toBeUndefined();
  });

  it('parses the answer as markdown, because that is what the agents write', () => {
    const ended = reached({
      code: 'OK',
      payload: {
        id: 'job_000007',
        state: 'DONE',
        outcome: {
          ending: 'ANSWERED',
          answered: true,
          text: '## Done\n\nwith `file_read`',
          steps: 3,
          modelCalls: 4,
        },
      },
    });

    expect(ended?.ending).toBe('ANSWERED');
    expect(ended?.answered).toBe(true);
    expect(ended?.said[0]).toEqual({
      kind: 'heading',
      level: 2,
      spans: [{ kind: 'text', text: 'Done' }],
    });
    expect(ended?.said).toHaveLength(2);
  });

  it('reads the pace a run went at, keeping absent numbers absent', () => {
    const paced = (pace: unknown) =>
      reached({
        code: 'OK',
        payload: {
          id: 'job_000007',
          state: 'DONE',
          outcome: {
            ending: 'ANSWERED',
            answered: true,
            text: 'hi',
            steps: 2,
            modelCalls: 2,
            detail: '',
            resumable: false,
            pace,
          },
        },
      });

    expect(
      paced({
        toolCalls: 3,
        completionTokens: 1_210,
        reasoningTokens: null,
        firstTokenMillis: 1_830,
        tokensPerSecond: 38.4,
      })?.pace,
    ).toEqual({
      toolCalls: 3,
      completionTokens: 1_210,
      firstTokenMillis: 1_830,
      tokensPerSecond: 38.4,
    });
    expect(
      paced({ toolCalls: 0, reasoningTokens: 812, reasoningEstimated: true })
        ?.pace,
    ).toEqual({ toolCalls: 0, reasoningTokens: 812, reasoningEstimated: true });
    expect(
      paced({ toolCalls: 0, reasoningTokens: 0, reasoningEstimated: false })
        ?.pace,
    ).toEqual({ toolCalls: 0, reasoningTokens: 0 });
    // Null is a run that measured nothing, and an old server sends no key.
    expect(paced(null)).not.toHaveProperty('pace');
    expect(paced(undefined)).not.toHaveProperty('pace');
  });

  it('never dresses a truncated run as an answer', () => {
    const ended = reached({
      code: 'OK',
      payload: {
        id: 'job_000007',
        state: 'DONE',
        outcome: {
          ending: 'TURN_CAP',
          answered: false,
          text: '',
          steps: 8,
          modelCalls: 9,
        },
      },
    });

    expect(ended?.answered).toBe(false);
    expect(ended?.said).toEqual([]);
    expect(ended?.steps).toBe(8);
  });

  it("keeps the server's own sentence and detail for a run that failed", () => {
    // The three UNAVAILABLE stops are one ending and three outages. This is
    // the third -- a tool's dependency -- and the counters are the ones a
    // real "hello" died with: modelCalls is claimed before dispatch, and a
    // tool failure reports steps - 1, so 0 and 1 is the first turn.
    const ended = reached({
      code: 'OK',
      payload: {
        id: 'job_000007',
        state: 'DONE',
        outcome: {
          ending: 'UNAVAILABLE',
          answered: false,
          text:
            "This run could not go on: something the tool 'memory_recall' needs" +
            ' could not be reached.',
          steps: 0,
          modelCalls: 1,
          detail:
            'memory_recall: EmbeddingException: no embedding model is configured',
        },
      },
    });

    expect(ended?.sentence).toBe(
      'This run could not go on: something the tool' +
        " 'memory_recall' needs could not be reached.",
    );
    expect(ended?.detail).toBe(
      'memory_recall: EmbeddingException: no embedding model is configured',
    );
    // Unparsed and never an answer: the sentence is prose the server wrote
    // about a run that produced nothing.
    expect(ended?.said).toEqual([]);
  });

  it('never carries an answer twice, as a document and as a sentence', () => {
    const ended = reached({
      code: 'OK',
      payload: {
        id: 'job_000007',
        state: 'DONE',
        outcome: {
          ending: 'ANSWERED',
          answered: true,
          text: '## Done',
          steps: 1,
          modelCalls: 1,
          detail: '',
        },
      },
    });

    expect(ended?.said).toHaveLength(1);
    expect(ended).not.toHaveProperty('sentence');
    // A blank is not a sentence and not a detail. An ending that chose to say
    // nothing says nothing here.
    expect(ended).not.toHaveProperty('detail');
  });

  it('reads a detail off an ending that had no sentence, and the other way round', () => {
    const only = (outcome: Record<string, unknown>) =>
      reached({
        code: 'OK',
        payload: {
          id: 'job_000007',
          state: 'DONE',
          outcome: {
            ending: 'UNAVAILABLE',
            answered: false,
            steps: 0,
            modelCalls: 1,
            ...outcome,
          },
        },
      });

    // CALL_BUDGET and CANCELLED send a sentence and an empty detail; a
    // failure translated by JobStore's last resort sends neither.
    expect(
      only({
        text: 'This run stopped after spending its whole budget.',
        detail: '',
      }),
    ).toEqual(
      expect.objectContaining({
        sentence: 'This run stopped after spending its whole budget.',
      }),
    );
    expect(only({ text: '   ', detail: 'IllegalStateException' })).toEqual(
      expect.objectContaining({ detail: 'IllegalStateException' }),
    );
    expect(
      only({ text: '   ', detail: 'IllegalStateException' }),
    ).not.toHaveProperty('sentence');
  });

  it('reads the allowance the run went under, ceiling or none', () => {
    const ended = reached({
      code: 'OK',
      payload: {
        id: 'job_000007',
        state: 'DONE',
        outcome: {
          ending: 'ANSWERED',
          answered: true,
          text: 'hi',
          steps: 1,
          modelCalls: 1,
        },
        limits: {
          maxTurns: null,
          noTurnCap: true,
          maxModelCalls: null,
          noBudget: true,
          modelCallsSpent: 9,
        },
      },
    });

    expect(ended?.allowance).toEqual({
      modelCallsSpent: 9,
      noBudget: true,
      noTurnCap: true,
    });
  });

  it('reads nothing out of a refusal', () => {
    expect(
      reached({ code: 'NOT_FOUND', said: 'there is no job job_000007' }),
    ).toBeUndefined();
  });
});

describe('the conversation a bot continues', () => {
  /** `conversation.latest`'s answer: one `ConversationView`, every field. */
  const STILL_HAVING = {
    code: 'OK',
    payload: {
      id: 'cnv_9F1A2B3C4D5E6F70',
      project: null,
      maxModelCalls: null,
      modelCallsSpent: 7,
      maxTurns: 8,
      noTurnCap: false,
      noBudget: true,
      title: 'how many modules are there',
    },
  };

  it('asks for it under conversation.latest, naming who is answering', () => {
    expect(continuing('aristoxenus')).toEqual({
      type: CONVERSATION_LATEST,
      payload: { agent: 'aristoxenus' },
    });
  });

  it('carries the tier when there is one, and no null when there is not', () => {
    // `opening`'s ruling: a null project would be this client stating
    // something it was never asked, and the handler reads absent and null
    // identically anyway.
    expect(continuing('aristoxenus', 'plowshare').payload).toEqual({
      agent: 'aristoxenus',
      project: 'plowshare',
    });
  });

  it('reads the conversation the server named', () => {
    expect(continued(STILL_HAVING)).toEqual({
      id: 'cnv_9F1A2B3C4D5E6F70',
      title: 'how many modules are there',
    });
  });

  it('reads nothing out of an OK that carries no conversation', () => {
    // Which is every first run: the server answers `{"code":"OK"}` with no
    // payload at all, and the caller tells that from a refusal by the code.
    expect(continued({ code: 'OK' })).toBeUndefined();
  });

  it('reads nothing out of a refusal either, which the code is what tells apart', () => {
    expect(
      continued({ code: 'NOT_FOUND', said: 'no such agent' }),
    ).toBeUndefined();
  });
});

describe('what a person is told about a conversation that is already going', () => {
  const GOING = {
    id: 'cnv_9F1A2B3C4D5E6F70',
    title: 'how many modules are there',
  };

  it('names the conversation being continued, by its name and by its id', () => {
    const lines = describeContinuing(GOING, 3, 3);

    expect(lines[0]).toContain('how many modules are there');
    expect(lines[0]).toContain('cnv_9F1A2B3C4D5E6F70');
  });

  it('says a conversation nothing has named is not named', () => {
    expect(describeContinuing({ id: 'cnv_1' }, 1, 1)[0]).toContain(
      'not yet named',
    );
  });

  it('says how much of the log is below when it is showing a tail of it', () => {
    const lines = describeContinuing(GOING, 37, 20);

    expect(lines).toHaveLength(2);
    expect(lines[1]).toContain('37 turns');
    expect(lines[1]).toContain('20');
  });

  it('says nothing about a tail when the whole log is below', () => {
    expect(describeContinuing(GOING, 4, 4)).toHaveLength(1);
  });

  /**
   * <b>The word matters and this is the guard on it.</b> Enzo: "it's not
   * restored, it's viewed — you can't undo the append-only log... you're just
   * traversing the log." A client that said it had restored or resumed a
   * session would be claiming something nothing here can do.
   */
  it('never claims to have restored or resumed anything', () => {
    for (const line of [
      ...describeContinuing(GOING, 37, 20),
      describeSeam({ throughOrdinal: 4, summary: [] }),
    ]) {
      expect(line).not.toMatch(/restor|resum/i);
    }
  });

  it('says a new conversation is not kept until somebody speaks', () => {
    expect(describeNew()).toContain('nothing is kept');
  });

  it('says where a fold happened and how far back it reaches', () => {
    expect(describeSeam({ throughOrdinal: 4, summary: [] })).toContain(
      'turn 4',
    );
  });

  /**
   * A fold is a fact and not a gap to see through: the turns behind a seam
   * were summarised, and nothing in this client can unfold one. A sentence
   * offering to would be offering something no frame answers.
   */
  it('never offers to show what a fold folded', () => {
    const said = describeSeam({ throughOrdinal: 4, summary: [] });

    expect(said).not.toMatch(/expand|unfold|show all|show more/i);
  });

  it('says whose words are about to be shown without editing them', () => {
    // The utterance itself is printed verbatim on the next line. A prefix
    // woven into a person's own text would be this client editing it, which
    // is this client editing what they typed.
    const heading = describeSpokenIn(7);

    expect(heading).toBe('turn 7, you said:');
  });
});

describe('describeEnding, which is the same sentence in a terminal and a window', () => {
  it('says a run that decided, decided', () => {
    expect(describeEnding({ ending: 'ANSWERED' })).toBe('answered');
  });

  it('says what a turn cap did, in the sentence the spec names', () => {
    expect(describeEnding({ ending: 'TURN_CAP' })).toBe(
      'stopped at its turn cap without reaching an answer',
    );
  });

  it('tells the two stops that look alike apart', () => {
    expect(describeEnding({ ending: 'CALL_BUDGET' })).toContain('budget');
    expect(describeEnding({ ending: 'STUCK' })).not.toBe(
      describeEnding({ ending: 'TURN_CAP' }),
    );
    expect(describeEnding({ ending: 'STUCK' })).not.toContain('turn cap');
  });

  it('has a sentence for every ending Outcome.Ending declares', () => {
    for (const ending of [
      'ANSWERED',
      'TURN_CAP',
      'CALL_BUDGET',
      'CANCELLED',
      'STUCK',
      'UNAVAILABLE',
      'SUB_AGENT_FAILED',
      'SESSION_GONE',
      'AWAITING',
      'CALL_FAILURES',
    ]) {
      expect(describeEnding({ ending })).not.toContain(ending);
    }
  });

  it('names an ending it has never heard of instead of throwing over it', () => {
    // The list is the server's to change — `JobView` says so, and says it is
    // why `ending` travels as a name rather than as an enum.
    expect(() => describeEnding({ ending: 'SOMETHING_LATER' })).not.toThrow();
    expect(describeEnding({ ending: 'SOMETHING_LATER' })).toContain(
      'SOMETHING_LATER',
    );
  });

  it('is not a lookup a prototype key can walk out of', () => {
    expect(describeEnding({ ending: '__proto__' })).toContain('__proto__');
    expect(describeEnding({ ending: 'constructor' })).toContain('constructor');
    expect(describeEnding({ ending: 'toString' })).toContain('toString');
  });

  it('says a run that stopped to ask is waiting on the person, not that it failed', () => {
    expect(describeEnding({ ending: 'AWAITING' })).toBe(
      'stopped to ask you before running a command, and is waiting for your answer',
    );
  });

  it('says a run that kept writing its calls as text was stopped for it', () => {
    expect(describeEnding({ ending: 'CALL_FAILURES' })).toContain('as text');
    expect(describeEnding({ ending: 'CALL_FAILURES' })).not.toBe(
      describeEnding({ ending: 'STUCK' }),
    );
  });

  it('says a run has not ended rather than inventing an ending for it', () => {
    expect(describeEnding(undefined)).toContain('has not');
  });
});

describe('describeCost, which counts steps and never turns', () => {
  it('counts one of each in the singular', () => {
    expect(describeCost({ steps: 1, modelCalls: 1 })).toBe(
      '1 step, 1 model call',
    );
  });

  it('counts the rest in the plural', () => {
    expect(describeCost({ steps: 3, modelCalls: 4 })).toBe(
      '3 steps, 4 model calls',
    );
  });

  it('never calls a step a turn, which once told a person they had spoken four times', () => {
    expect(describeCost({ steps: 4, modelCalls: 4 })).not.toContain('turn');
  });

  it('says what was spent out of a ceiling when there is one', () => {
    expect(
      describeCost({
        steps: 3,
        modelCalls: 4,
        allowance: {
          modelCallsSpent: 9,
          maxModelCalls: 20,
          noBudget: false,
          noTurnCap: false,
        },
      }),
    ).toBe('3 steps, 4 model calls; 9 of 20 spent across the run tree');
  });

  it('says no ceiling as a decision rather than as a large number', () => {
    const said = describeCost({
      steps: 3,
      modelCalls: 4,
      allowance: { modelCallsSpent: 9, noBudget: true, noTurnCap: true },
    });

    expect(said).toContain('no ceiling');
    expect(said).not.toMatch(/of \d/);
  });

  it('says nothing has been counted rather than counting zero', () => {
    expect(describeCost(undefined)).not.toContain('0');
  });
});

describe('refusal, which does not overwrite what the server said', () => {
  it('uses the server sentence whenever there is one', () => {
    // Client design §5.2: a hand-written sentence is correct only when
    // `said` is absent. Guessing by status once threw away every 409
    // sentence the server sent.
    expect(
      refusal({ code: 'CONFLICT', said: 'that conversation is archived' }),
    ).toBe('that conversation is archived');
  });

  it('writes its own only when the server wrote none, and names the code in it', () => {
    expect(refusal({ code: 'VALIDATION_FAILED' })).toContain(
      'VALIDATION_FAILED',
    );
  });

  it('does not treat an empty sentence as no sentence', () => {
    // An empty string is a sentence. The binding keeps `said` absent when
    // the server said nothing, so a present `''` is the server's own doing
    // and replacing it would be this client talking over it.
    expect(refusal({ code: 'CONFLICT', said: '' })).toBe('');
  });
});

describe('describeProgress, which is the line a run writes while it runs', () => {
  // Added by task 7, and in `logic/` rather than in the view for the reason
  // this file's other two wording groups are: `plowshare_memory_write` is
  // the same line in a terminal and in a window, and a view that owned it
  // would own it twice the day the GUI arrives.

  it('names the agent when a run starts', () => {
    expect(
      describeProgress({ kind: 'started', job: 'j1', agent: 'close_reader' }),
    ).toContain('close_reader');
  });

  it('counts steps and never turns', () => {
    // The word the server corrected, kept corrected everywhere it is said.
    // A console once rendered "after 4 turns" for one question and told
    // somebody they had spoken four times.
    const said = describeProgress({
      kind: 'step',
      job: 'j1',
      agent: 'a',
      steps: 4,
      modelCalls: 4,
    });

    expect(said).toContain('4 steps');
    expect(said).not.toContain('turn');
  });

  it('names the tool the server registered', () => {
    expect(
      describeProgress({
        kind: 'tool',
        job: 'j1',
        agent: 'a',
        tool: 'plowshare_memory_write',
      }),
    ).toContain('plowshare_memory_write');
  });

  it('says how a run ended in the same words job.status would', () => {
    const stopped = {
      kind: 'ended',
      job: 'j1',
      agent: 'a',
      ending: 'TURN_CAP',
      steps: 2,
      modelCalls: 2,
    } as const;

    expect(describeProgress(stopped)).toContain(describeEnding(stopped));
  });

  it('names a kind this build has never heard of rather than swallowing it', () => {
    expect(
      describeProgress({ kind: 'other', job: 'j1', named: 'compacted' }),
    ).toContain('compacted');
  });

  it('says nothing about a frame that belongs to no job', () => {
    // Nothing, and not a sentence about nothing: `following` drops these
    // because attaching one would put an invention in a transcript, and a
    // line on the screen is the same invention one layer up.
    expect(
      describeProgress({ kind: 'unreadable', said: 'not a job event' }),
    ).toBeUndefined();
  });
});

describe('the two listings, which are what a terminal can see', () => {
  /** `project.list` as `ProjectListHandler` answers it: every `ProjectView`. */
  const EVERY_PROJECT = {
    code: 'OK',
    payload: [
      {
        name: 'plowshare',
        workspace: '/srv/plowshare',
        lent: [],
        exclusions: ['/etc'],
      },
      {
        name: 'notes',
        workspace: '/srv/notes',
        lent: ['/srv/shared'],
        exclusions: [],
      },
    ],
  };

  /**
   * `conversation.list` as `ConversationListHandler` answers it.
   *
   * <b>The null title is the first row on purpose.</b> There was no backfill,
   * so every conversation that existed before the column has one permanently,
   * and so does every conversation opened and never spoken into — it is the
   * ordinary row rather than the edge. `ConversationView` sends it as
   * `"title": null` rather than as an absent key, so that a client can tell it
   * apart from a server too old to have names at all.
   */
  const EVERY_CONVERSATION = {
    code: 'OK',
    payload: [
      {
        id: 'cnv_3134E666E2D847AD',
        project: null,
        maxModelCalls: 20,
        modelCallsSpent: 0,
        maxTurns: null,
        noTurnCap: false,
        noBudget: false,
        title: null,
      },
      {
        id: 'cnv_9F1A2B3C4D5E6F70',
        project: 'plowshare',
        maxModelCalls: null,
        modelCallsSpent: 7,
        maxTurns: 8,
        noTurnCap: false,
        noBudget: true,
        title: 'how many modules are there',
      },
    ],
  };

  it('asks for the projects under the type the server routes', () => {
    expect(listingProjects()).toEqual({ type: PROJECT_LIST, payload: {} });
    expect(PROJECT_LIST).toBe('project.list');
  });

  it("asks for one home's conversations, and says global as an absence", () => {
    // `RequestedHome.in(null)` reads an absent project as the global tier,
    // which is the endpoint's reading too. A payload of nulls would be this
    // client stating something it was never asked — `opening`'s ruling.
    expect(listingConversations()).toEqual({
      type: CONVERSATION_LIST,
      payload: {},
    });
    expect(listingConversations('plowshare')).toEqual({
      type: CONVERSATION_LIST,
      payload: { project: 'plowshare' },
    });
    expect(CONVERSATION_LIST).toBe('conversation.list');
  });

  it('reads the projects by the name a person chose for them', () => {
    expect(projects(EVERY_PROJECT)).toEqual([
      { name: 'plowshare', workspace: '/srv/plowshare' },
      { name: 'notes', workspace: '/srv/notes' },
    ]);
  });

  it("reads a conversation's title, and leaves a null one absent", () => {
    const rows = conversations(EVERY_CONVERSATION);

    expect(rows).toHaveLength(2);
    expect(rows?.[0]?.id).toBe('cnv_3134E666E2D847AD');
    expect(rows?.[0] && 'title' in rows[0]).toBe(false);
    expect(rows?.[1]?.title).toBe('how many modules are there');
    expect(rows?.[1]?.project).toBe('plowshare');
  });

  it('reads nothing at all out of a refusal, so the caller says why', () => {
    // Nothing, and not an empty listing: "you have no conversations" and
    // "the server would not tell you" are different facts, and only the
    // first of them is one this client may put on a screen.
    const refused = {
      code: 'FORBIDDEN',
      said: 'this session may not list projects',
    };

    expect(projects(refused)).toBeUndefined();
    expect(conversations(refused)).toBeUndefined();
    expect(
      projects({ code: 'OK', payload: { name: 'not a list' } }),
    ).toBeUndefined();
  });
});

describe('what a line typed at the prompt is, before it is an utterance', () => {
  it('reads a question as a question, slashes inside it and all', () => {
    expect(typed('how many modules?')).toEqual({
      kind: 'utterance',
      text: 'how many modules?',
    });
    expect(typed('what does /projects do?')).toEqual({
      kind: 'utterance',
      text: 'what does /projects do?',
    });
  });

  it('reads the two listings as the frames they send', () => {
    expect(typed(PROJECTS_COMMAND)).toEqual({
      kind: 'projects',
      ask: listingProjects(),
    });
    expect(typed(CONVERSATIONS_COMMAND, 'plowshare')).toEqual({
      kind: 'conversations',
      ask: listingConversations('plowshare'),
    });
  });

  it('reads the two rosters as separate commands, because they are separate kinds', () => {
    // ONE FRAME AND TWO COMMANDS. `agent.list` answers with both kinds in
    // one list and the `bot` flag tells them apart, so the two asks are
    // identical and the difference is entirely in what is shown. That is
    // the shape the spec wants: a bot is somebody you talk to and an agent
    // is a role something invokes, and a person choosing between them is
    // not choosing between two rows of one list.
    expect(typed(BOTS_COMMAND, 'plowshare')).toEqual({
      kind: 'bots',
      ask: listingAgents('plowshare'),
    });
    expect(typed(AGENTS_COMMAND, 'plowshare')).toEqual({
      kind: 'agents',
      ask: listingAgents('plowshare'),
    });
  });

  it('reads /help, which is the first thing anybody types', () => {
    expect(typed(HELP_COMMAND)).toEqual({ kind: 'help' });
  });

  it('reads a diagnosis with an explicit target or leaves the current target to the view', () => {
    expect(typed(DIAGNOSE_COMMAND)).toEqual({ kind: 'diagnose' });
    expect(typed(`${DIAGNOSE_COMMAND} cnv_123`)).toEqual({
      kind: 'diagnose',
      conversation: 'cnv_123',
    });
    expect(typed(`${DIAGNOSE_COMMAND} -- why did it loop?`)).toEqual({
      kind: 'diagnose',
      question: 'why did it loop?',
    });
    expect(typed(`${DIAGNOSE_COMMAND} cnv_123 -- why did it loop?`)).toEqual({
      kind: 'diagnose',
      conversation: 'cnv_123',
      question: 'why did it loop?',
    });
    expect(typed(`${DIAGNOSE_COMMAND} --`)).toEqual({
      kind: 'usage',
      command: DIAGNOSE_COMMAND,
    });
    expect(typed(`${DIAGNOSE_COMMAND} cnv_123 without-a-delimiter`)).toEqual({
      kind: 'usage',
      command: DIAGNOSE_COMMAND,
    });
  });

  it('refuses a command it does not know rather than sending it as a question', () => {
    // A mistyped command sent as an utterance costs a model call and comes
    // back as an agent's puzzled answer, which is a worse reply than this.
    expect(typed('/porjects')).toEqual({ kind: 'unknown', named: '/porjects' });
    expect(describeUnknownCommand('/porjects')).toContain('/porjects');
    // AND IT POINTS AT ONE PLACE RATHER THAN RECITING THE LIST. The
    // sentence named both commands when there were two; with five it would
    // be the help text, written a second time and drifting from the first.
    expect(describeUnknownCommand('/porjects')).toContain(HELP_COMMAND);
  });
});

/** One `AgentView`, as `AgentListHandler` renders a served definition. */
function view(over: Record<string, unknown>): unknown {
  return {
    name: 'close_reader',
    tools: ['read_file'],
    calls: [],
    scopes: ['workspace:read'],
    served: true,
    withheld: [],
    bot: false,
    description: 'reads a diff and says what changed',
    ...over,
  };
}

/** The shipped bot's own opening sentence, used wherever a fixture needs a
 *  description long enough to be worth cutting down. */
const ARISTOXENUS_SAYS =
  'Aristoxenus of Tarentum, who broke with the Pythagoreans over whether' +
  ' a harmony is judged by the ear that hears it or by the elegance of the ratio behind it,' +
  " and took the ear's side.";

/** What every row carries and no case below is about: the four declaration lists. */
const DECLARES = { tools: [], calls: [], scopes: [], orchestrations: [] };

/** The roster of a deployment with the shipped bot and one refused file. */
const ROSTER: Agent[] = [
  {
    name: 'aristoxenus',
    bot: true,
    served: true,
    withheld: [],
    description: ARISTOXENUS_SAYS,
    preferred: false,
    ...DECLARES,
    tools: ['memory_recall'],
  },
  {
    name: 'close_reader',
    bot: false,
    served: true,
    withheld: [],
    description: 'reads a diff and says what changed',
    preferred: false,
    ...DECLARES,
    tools: ['read_file'],
    scopes: ['workspace:read'],
  },
  {
    name: 'hermippus',
    bot: false,
    served: false,
    withheld: ['bot: expected a boolean'],
    description: '',
    preferred: false,
    ...DECLARES,
  },
];

describe('agent.list, which is how this client learns what it may talk to', () => {
  it('asks under the type the server routes, with the tier it was given', () => {
    expect(listingAgents()).toEqual({ type: AGENT_LIST, payload: {} });
    expect(listingAgents('plowshare')).toEqual({
      type: AGENT_LIST,
      payload: { project: 'plowshare' },
    });
    expect(AGENT_LIST).toBe('agent.list');
  });

  it('reads every field of a whole AgentView, the four declaration lists included', () => {
    const rows = agents({
      code: 'OK',
      payload: [
        view({
          name: 'aristoxenus',
          bot: true,
          tools: ['memory_recall'],
          scopes: [],
          description: ARISTOXENUS_SAYS,
        }),
        view({}),
        view({
          name: 'hermippus',
          served: false,
          tools: [],
          scopes: [],
          withheld: ['bot: expected a boolean'],
          description: '',
        }),
      ],
    });

    expect(rows).toEqual(ROSTER);
  });

  it('reads bot and served off the row rather than assuming either', () => {
    // `AgentView.disabled` sends `bot: false` for a definition it could not
    // read, because a file that failed to parse has no `bot:` to report.
    // A client that treated an absent flag as true would invent a bot out
    // of a broken file.
    const rows = agents({ code: 'OK', payload: [{ name: 'bare' }] });

    expect(rows).toEqual([
      {
        name: 'bare',
        bot: false,
        served: false,
        withheld: [],
        description: '',
        preferred: false,
        ...DECLARES,
      },
    ]);
  });

  it('reads the model a row is dispatched under, and none from a row that sent none', () => {
    const rows = agents({
      code: 'OK',
      payload: [
        view({ name: 'coder', model: 'coder' }),
        view({ model: null }),
        view({}),
      ],
    });

    expect(rows?.map((row) => row.model)).toEqual([
      'coder',
      undefined,
      undefined,
    ]);
    expect(rows?.[1]).not.toHaveProperty('model');
  });

  it('reads nothing at all out of a refusal, so the caller says why', () => {
    expect(agents({ code: 'FORBIDDEN', said: 'not for you' })).toBeUndefined();
    expect(
      agents({ code: 'OK', payload: { name: 'not a list' } }),
    ).toBeUndefined();
  });
});

describe('who answers, decided before anything is opened', () => {
  it('talks to the one bot when nobody named anybody', () => {
    // THE BOT IS THE DEFAULT. `PLOWSHARE_AGENT` was required and the
    // ordinary way to use this system is a conversation, so a deployment
    // that serves exactly one bot has already answered the question.
    expect(whoAnswers(ROSTER)).toEqual({
      kind: 'answering',
      agent: ROSTER[0],
      asked: false,
      preferred: false,
    });
  });

  it('takes the first bot served when the tier names no default', () => {
    const two: Agent[] = [
      ...ROSTER,
      {
        name: 'hypatia',
        bot: true,
        served: true,
        withheld: [],
        description: '',
        preferred: false,
        ...DECLARES,
      },
    ];
    // Listing order is the server's (it sorts by name): aristoxenus, then hypatia.
    expect(whoAnswers(two)).toEqual({
      kind: 'answering',
      agent: ROSTER[0],
      asked: false,
      preferred: false,
    });
  });

  it('takes the first agent served when the tier serves no bot', () => {
    expect(whoAnswers([ROSTER[1] as Agent, ROSTER[2] as Agent])).toEqual({
      kind: 'answering',
      agent: ROSTER[1],
      asked: false,
      preferred: false,
    });
  });

  it('says a deployment that defines no bot differently from one whose bot would not parse', () => {
    // THE TWO STATES TASK 2 FLAGGED, AND A PERSON CAN ACT ON ONLY ONE.
    // `AgentView.disabled` carries the reason in `withheld`, so the
    // refusal is reachable from here — what is not reachable is whether
    // the refused file said `bot: true`, since a file that failed to
    // parse has no flag to read. So the sentence says that much and no
    // more. `none` is reached only when nothing is served at all — a
    // served agent, close_reader included, now answers on its own.
    const none = whoAnswers([]);
    const broken = whoAnswers([ROSTER[2] as Agent]);

    expect(none.kind).toBe('none');
    expect(broken.kind).toBe('none');
    expect(describeChoice(none)).not.toEqual(describeChoice(broken));
    expect(describeChoice(broken).join('\n')).toContain('hermippus');
    expect(describeChoice(broken).join('\n')).toContain(
      'bot: expected a boolean',
    );
    expect(describeChoice(none).join('\n')).not.toContain('hermippus');
  });

  it('says a name this server does not serve is not served, and names what is', () => {
    const chosen = whoAnswers(ROSTER, 'no_such_bot');

    expect(chosen.kind).toBe('unknown');
    const said = describeChoice(chosen).join('\n');
    expect(said).toContain('no_such_bot');
    expect(said).toContain('aristoxenus');
    expect(said).toContain('close_reader');
    // And never the one this server could not read: naming it would be
    // telling somebody to try a definition that does not run.
    expect(said).not.toContain('hermippus');
  });

  it('says why a named definition is defined and not served', () => {
    const chosen = whoAnswers(ROSTER, 'hermippus');

    expect(chosen.kind).toBe('refused');
    expect(describeChoice(chosen).join('\n')).toContain(
      'bot: expected a boolean',
    );
  });

  it('says once that a named agent is not a conversation, and answers anyway', () => {
    // Spec §4: an agent is written to be consumed — by a harness, by
    // another agent, over MCP — and its answer may be a structure rather
    // than a sentence. One sentence and not a refusal: somebody who wants
    // an agent gets one.
    const chosen = whoAnswers(ROSTER, 'close_reader');

    expect(chosen).toEqual({
      kind: 'answering',
      agent: ROSTER[1],
      asked: true,
      preferred: false,
    });
    const said = describeChoice(chosen).join('\n');
    expect(said).toContain('close_reader');
    expect(said).toContain('agent');
    expect(said).toContain('structure');
  });

  it('says nothing about kinds when a bot was named outright', () => {
    const said = describeChoice(whoAnswers(ROSTER, 'aristoxenus')).join('\n');

    expect(said).toContain('aristoxenus');
    expect(said).not.toContain('structure');
  });
});

describe('the two rosters, and the two silences that are not the same silence', () => {
  it('shows the bots under /bots and the agents under /agents', () => {
    expect(describeBots(ROSTER)[0]).toContain('aristoxenus');
    expect(describeBots(ROSTER).join('\n')).not.toContain('close_reader');
    expect(describeAgents(ROSTER)[0]).toContain('close_reader');
    expect(describeAgents(ROSTER).join('\n')).not.toContain('aristoxenus');
  });

  it('carries a definition it could not read into both, because it cannot say which', () => {
    // A file that failed to parse has no `bot:` to read, so it belongs to
    // neither list and excluding it from both would make the least readable
    // definitions the ones that vanish — which is the rule the server's own
    // listing handler already states.
    expect(describeBots(ROSTER).join('\n')).toContain('hermippus');
    expect(describeAgents(ROSTER).join('\n')).toContain('hermippus');
    expect(describeBots(ROSTER).join('\n')).toContain(
      'bot: expected a boolean',
    );
  });

  it('tells a deployment with no bots from one whose bot would not parse', () => {
    const none = describeBots([ROSTER[1] as Agent]);
    const broken = describeBots([ROSTER[1] as Agent, ROSTER[2] as Agent]);

    expect(none.join('\n')).toContain('no bots');
    expect(broken.join('\n')).toContain('no bots');
    expect(none).not.toEqual(broken);
    expect(broken.join('\n')).toContain('hermippus');
  });

  it('says so when there is nothing at all to list rather than printing nothing', () => {
    expect(describeBots([])).toHaveLength(1);
    expect(describeAgents([])).toHaveLength(1);
  });

  it("shows enough of a row's own description to say who it is", () => {
    // A bare name is survivable for an agent, whose name is usually its
    // job; it defeats the point for a bot, whose one legible difference
    // from a role is the sentence saying who he is.
    const said = describeBots(ROSTER).join('\n');

    expect(said).toContain('aristoxenus — Aristoxenus of Tarentum');
  });

  it('prints only the name when a served row has nothing to say about itself', () => {
    // A server too old to send `description`, or one whose field really is
    // blank, reads the same way here: `AgentView.disabled`'s own rule for
    // an empty string, carried through rather than dressed up as a dash
    // and nothing after it.
    const row: Agent = {
      name: 'bare',
      bot: false,
      served: true,
      withheld: [],
      description: '',
      preferred: false,
      ...DECLARES,
    };

    expect(describeAgents([row])).toEqual(['bare']);
  });

  it('cuts a long description at a word boundary, never mid-word', () => {
    // Thirty distinct tokens comfortably clear DESCRIPTION_BUDGET, so the
    // cut has to land somewhere inside them — and it must land on a space,
    // or the last thing shown would be a fragment like "toke…" rather than
    // a whole word with "…" after it.
    const words = Array.from({ length: 30 }, (_, i) => `token${i}`);
    const row: Agent = {
      name: 'verbose',
      bot: false,
      served: true,
      withheld: [],
      description: words.join(' '),
      preferred: false,
      ...DECLARES,
    };

    const [line] = describeAgents([row]);
    expect(line).toContain('…');
    const shown = (line ?? '').slice('verbose — '.length, -1);
    expect(words).toContain(shown.trim().split(' ').at(-1));
    // And short enough that a roster full of these still reads as a
    // roster rather than as thirty short paragraphs.
    expect(line?.length ?? 0).toBeLessThan(words.join(' ').length);
  });
});

describe('/help, which is the first thing anybody types and was refused', () => {
  it('names every command this client knows', () => {
    const said = describeHelp().join('\n');

    for (const command of COMMANDS) {
      expect(said).toContain(command);
    }
    expect(COMMANDS).toContain(HELP_COMMAND);
    expect(COMMANDS).toContain('/commands');
    expect(COMMANDS).toContain('/skills');
    expect(COMMANDS).toHaveLength(41);
  });

  it('says what a line that is not a command is', () => {
    // Otherwise the help reads as the whole of what this client does, and
    // the one thing it is for — saying something to whoever answers — is
    // the thing it does not mention.
    expect(describeHelp().join('\n')).toContain('anything else');
  });

  it('reaches every command it names, which a hand-written list would not', () => {
    // The list and the reading are the same list: a command added to
    // `COMMANDS` and not to `typed` would be advertised and then refused.
    for (const command of COMMANDS) {
      expect(typed(command).kind).not.toBe('unknown');
    }
  });
});

describe('/theme, which the view answers', () => {
  it('lists when bare and names a theme when given one', () => {
    expect(typed('/theme')).toEqual({ kind: 'theme' });
    expect(typed('/theme  dusk ')).toEqual({ kind: 'theme', name: 'dusk' });
  });

  it('does not swallow a command that merely starts with the same letters', () => {
    expect(typed('/themes').kind).toBe('unknown');
  });
});

describe('what a listing says, which is wording and not layout', () => {
  it('groups scopes by Application identity without exposing workspace paths', () => {
    // The name is what `PLOWSHARE_PROJECT` takes and what a person chose;
    // the workspace and the exclusions are a map of this server's disk, and
    // `ProjectListHandler` is explicit about what that second list is.
    expect(
      describeProjects([
        { name: 'notes', workspace: '/fixture/remote' },
        { name: 'plowshare', kind: 'application', type: 'MANAGED' },
        { name: 'pipeline', kind: 'application', type: 'DISJOINT' },
        { name: 'external', kind: 'project', type: 'DISJOINT' },
        { name: 'personal:reader', kind: 'personal' },
      ]),
    ).toEqual([
      'Applications:',
      '  plowshare',
      '  pipeline · DISJOINT · no sync',
      'Projects:',
      '  notes',
      '  external · DISJOINT · no sync',
      'Personal · personal:reader',
    ]);
  });

  it('says so when there is nothing to list, rather than saying nothing', () => {
    expect(describeProjects([])).toEqual([
      'Applications:',
      '  No Applications available to this account.',
      'Projects:',
      '  No Projects available to this account.',
    ]);
    expect(describeConversations([])[0]).toContain('no conversations');
  });

  it('shows the title, with the id after it rather than instead of it', () => {
    const said = describeConversations([
      { id: 'cnv_9F1A2B3C4D5E6F70', title: 'how many modules are there' },
    ]);

    expect(said[0]).toContain('how many modules are there');
    expect(said[0]).toContain('cnv_9F1A2B3C4D5E6F70');
    expect(said[0]?.indexOf('how many')).toBeLessThan(
      said[0]?.indexOf('cnv_') ?? 0,
    );
  });

  it('says a conversation with no title has none, rather than reading the id as one', () => {
    // THE COMMON ROW AND NOT THE EDGE ONE. The conversation is real; it
    // simply has no name yet, and the id standing bare where a name goes
    // would read as the name — which is the thing this plan exists to stop a
    // client doing.
    const said = describeConversations([{ id: 'cnv_3134E666E2D847AD' }]);

    expect(said[0]).toContain('cnv_3134E666E2D847AD');
    expect(said[0]).not.toBe('cnv_3134E666E2D847AD');
    expect(said[0]).toContain('not yet named');
  });
});

describe('what Tab may finish, which is never a name the server did not send', () => {
  /** The names {@link ROSTER} declares and this server will serve. */
  const NAMES = ['aristoxenus', 'close_reader'];

  it('finishes a command from the characters in front of it', () => {
    expect(completing('/bo', NAMES)).toEqual({
      word: '/bo',
      matches: [BOTS_COMMAND, '/board'],
    });
    expect(completing('/boa', NAMES)).toEqual({
      word: '/boa',
      matches: ['/board'],
    });
  });

  it('offers every command for a bare slash, which is the whole of the list', () => {
    expect(completing('/', NAMES).matches).toEqual(COMMANDS);
  });

  it('finishes a name this deployment declared', () => {
    expect(completing('aris', NAMES)).toEqual({
      word: 'aris',
      matches: ['aristoxenus'],
    });
  });

  it('finishes a name in the middle of a sentence, where a name is mentioned', () => {
    // The word under the cursor and not the line: `close_reader` is a thing
    // somebody writes into a question, and a completer that only ever fired
    // on the first word would be no use in the one place a name is typed.
    expect(completing('ask close', NAMES)).toEqual({
      word: 'close',
      matches: ['close_reader'],
    });
  });

  it('finishes NOTHING for a name this server never sent', () => {
    // <b>Completion that guesses is worse than none.</b> A name offered by
    // Tab reads as a name that works, and this client has exactly one list
    // of names that do — the roster the server declared at sign-in.
    expect(completing('hypa', NAMES).matches).toEqual([]);
    expect(completing('scr', []).matches).toEqual([]);
  });

  it('offers no command anywhere but at the start, because nowhere else is one', () => {
    // `typed` compares the WHOLE trimmed line to each command, so
    // `what does /bo` is an utterance however it is finished. Offering
    // `/bots` there would complete a person into a question about a command
    // rather than into the command.
    expect(completing('what does /bo', NAMES).matches).toEqual([]);
    expect(typed('what does /bots').kind).toBe('utterance');
  });

  it('offers nothing at all for an empty word, rather than the world', () => {
    // Tab on nothing is not a request for the roster — `/bots` is the
    // screen for that, and it says who each of them is.
    expect(completing('', NAMES)).toEqual({ word: '', matches: [] });
    expect(completing('ask ', NAMES)).toEqual({ word: '', matches: [] });
  });

  it('completes every command it advertises, which is the same list', () => {
    // COMMANDS is the help text's list and the reading's list; this makes
    // it the completer's too, so a sixth command cannot be advertised,
    // answered and then uncompletable.
    for (const command of COMMANDS) {
      expect(completing(command.slice(0, 2), []).matches).toContain(command);
    }
  });

  it('offers only the names this server will serve', () => {
    // A definition this server READ AND REFUSED is a name it declared and a
    // name that cannot answer. Completing it would hand somebody a name
    // that fails at the first thing they ask of it.
    expect(completable(ROSTER)).toEqual(['aristoxenus', 'close_reader']);
    expect(completable(ROSTER)).not.toContain('hermippus');
  });
});

describe('what Ctrl-C says, which is a sentence and not a silent exit', () => {
  it('names the run it asked to stop, and says the stop is not immediate', () => {
    const said = describeCancelling('job_000007');

    expect(said).toContain('job_000007');
    // <b>A cancel is honoured between steps and is not a kill</b> —
    // `JobCancelHandler` says so, and its answer still reads RUNNING. A
    // client that reported the run stopped would be reporting a state that
    // is not yet true and may never be: a run already finishing answers
    // anyway.
    expect(said).toMatch(/step|boundary/);
    expect(said).not.toMatch(/has stopped|was stopped/);
  });

  it('says how to leave without waiting for the server', () => {
    expect(describeCancelling('job_000007')).toContain('Ctrl-C');
  });

  it('says there is nothing to stop when the run has no handle yet', () => {
    // The window between `agent.run` going out and its ACCEPTED coming
    // back. There is a run and this client cannot name it, which is a
    // different thing from there being no run.
    const said = describeUnnamedRun();

    expect(said).toMatch(/handle/);
    expect(said).toContain('Ctrl-C');
  });

  it('says what Ctrl-C at an idle prompt did, rather than exiting without a word', () => {
    const said = describeNothingRunning();

    expect(said).toMatch(/nothing/);
    // AND HOW TO ACTUALLY LEAVE. Ctrl-C here throws the half-typed line
    // away and keeps the session; a person who wanted out needs the key
    // that does leave said to them.
    expect(said).toContain('Ctrl-D');
  });

  it('does not claim to have stopped a run it never named', () => {
    expect(describeLeaving(true)).toMatch(/the run was asked to stop/);
    // And the other half, which is the honest one: a second Ctrl-C before
    // the handle arrived leaves a run going that nothing asked to stop, and
    // the denial has to be a denial rather than the same claim reworded.
    expect(describeLeaving(false)).not.toMatch(/the run was asked to stop/);
    expect(describeLeaving(false)).toMatch(/nothing was asked to stop/);
    expect(describeLeaving(false)).toMatch(/may still be running/);
  });
});

describe('a heartbeat', () => {
  // MEASURED DEFECT, FOUND BY READING RATHER THAN BY RUNNING, AND REAL.
  // `ALIVE` is not one of the four kinds `followed` names, so it fell to the
  // tolerance branch and came out as `other`. `describeProgress` renders that
  // as "this run reported ALIVE" -- which a long run would put in the status
  // region every twenty seconds, in place of the progress it had.
  it('is a kind of its own rather than an unknown one', () => {
    expect(
      followed({
        job: 'job_1',
        kind: 'alive',
        agent: 'aristoxenus',
        steps: 2,
        modelCalls: 3,
      }),
    ).toEqual({
      kind: 'alive',
      job: 'job_1',
      agent: 'aristoxenus',
      steps: 2,
      modelCalls: 3,
    });
  });

  it('carries the counts it reports, which is what makes it progress', () => {
    const seen = followed({
      job: 'j',
      kind: 'alive',
      agent: 'a',
      steps: 7,
      modelCalls: 9,
    });
    expect(seen.kind === 'alive' ? [seen.steps, seen.modelCalls] : []).toEqual([
      7, 9,
    ]);
  });

  it('is still attached to the turn it belongs to', () => {
    // `HANDED` and not a hand-written answer: the handle comes out of
    // `payload.id` under an `ACCEPTED`, and a first version of this case
    // invented `{ code: OK, payload: { job } }`, which `answering` reads as
    // a refusal. The test failed for a reason that had nothing to do with
    // heartbeats.
    const turn = answering(
      taking('c-1', 'scribe', 'hello', LISTENING_AS),
      HANDED,
    );
    const after = following(turn, {
      job: 'job_000007',
      kind: 'alive',
      agent: 'scribe',
      steps: 1,
      modelCalls: 1,
    });
    expect(after.progress.at(-1)?.kind).toBe('alive');
  });

  it('does not end a turn, however many arrive', () => {
    let now = answering(taking('c-1', 'scribe', 'hello', LISTENING_AS), HANDED);
    for (let beat = 0; beat < 5; beat += 1) {
      now = following(now, {
        job: 'job_000007',
        kind: 'alive',
        agent: 'scribe',
        steps: beat,
        modelCalls: beat,
      });
    }
    expect(finished(now)).toBe(false);
  });
});

describe('describeProgress, for a heartbeat', () => {
  it('says the counts, the same way a model call does', () => {
    expect(
      describeProgress({
        kind: 'alive',
        job: 'j',
        agent: 'a',
        steps: 2,
        modelCalls: 3,
      }),
    ).toBe('2 steps, 3 model calls');
  });

  it('says nothing at all before there is anything to count', () => {
    // A beat arriving before the first model call has nothing to report,
    // and a status region is REPLACED rather than appended to -- so a
    // sentence here would wipe out whatever the run last actually said and
    // put "0 steps, 0 model calls" in its place.
    expect(
      describeProgress({
        kind: 'alive',
        job: 'j',
        agent: 'a',
        steps: 0,
        modelCalls: 0,
      }),
    ).toBeUndefined();
  });
});

describe('describeSilence', () => {
  it('says the run may still be going, which is the half that matters', () => {
    const said = describeSilence(60);
    expect(said).toContain('60');
    expect(said.toLowerCase()).toContain('still');
  });

  it('never says the run failed, because it very probably did not', () => {
    const said = describeSilence(60).toLowerCase();
    expect(said).not.toContain('failed');
    expect(said).not.toContain('error');
  });
});

describe('streamed', () => {
  it('reads a delta the server wrote', () => {
    // The exact shape `JobDeltaTest` pins on the other side of the wire.
    expect(streamed({ job: 'job_1', part: 'ANSWER', text: 'Bor' })).toEqual({
      job: 'job_1',
      part: 'answer',
      text: 'Bor',
    });
  });

  it('reads the thinking half too', () => {
    expect(streamed({ job: 'job_1', part: 'THINKING', text: 'Let ' })).toEqual({
      job: 'job_1',
      part: 'thinking',
      text: 'Let ',
    });
  });

  it('reads the case the server actually writes and not the one that reads nicer', () => {
    // Jackson spells an enum with name(). A client reading 'thinking' shows
    // NOTHING and reports nothing, because a delta it cannot read is
    // indistinguishable from a delta that never came -- which is the whole
    // reason the Java side has a test pinning the spelling.
    expect(
      streamed({ job: 'job_1', part: 'thinking', text: 'Let ' }),
    ).toBeUndefined();
  });

  it('is not fooled by a job event', () => {
    // Both travel the same socket as bare pushes. A lifecycle event has a
    // `kind` and no `text`; reading one as a delta would put "started" into
    // the live region.
    expect(
      streamed({ job: 'job_1', kind: 'started', agent: 'a' }),
    ).toBeUndefined();
  });

  it('refuses a delta with no text rather than blanking the preview', () => {
    expect(
      streamed({ job: 'job_1', part: 'ANSWER', text: '' }),
    ).toBeUndefined();
  });

  it('refuses a delta that names no job', () => {
    expect(streamed({ part: 'ANSWER', text: 'Bor' })).toBeUndefined();
  });

  it('refuses a part this build has never heard of', () => {
    // Tolerance here would mean inventing a stream. A third part is a
    // decision every renderer would have to make, so an unknown one is
    // nothing rather than a guess.
    expect(
      streamed({ job: 'job_1', part: 'SINGING', text: 'la' }),
    ).toBeUndefined();
  });

  it('never reads a delta as progress', () => {
    // `followed` must not claim one: a delta attached to a turn would grow
    // that turn without bound for the whole of a long answer.
    expect(followed({ job: 'job_1', part: 'ANSWER', text: 'Bor' }).kind).toBe(
      'unreadable',
    );
  });
});

describe('the inbox', () => {
  it('asks for the unread items', () => {
    expect(listingInbox()).toEqual({
      type: INBOX_LIST,
      payload: { unread: true, limit: 20 },
    });
  });
  it('marks the items it showed as read', () => {
    expect(readingInbox(['inb_1', 'inb_2'])).toEqual({
      type: INBOX_READ,
      payload: { items: ['inb_1', 'inb_2'] },
    });
  });
  it('reads /inbox as the inbox command', () => {
    expect(typed('/inbox')).toEqual({ kind: 'inbox', ask: listingInbox() });
  });
  it('reads the unread count off an inbox.changed push and nothing else', () => {
    expect(unreadOf({ kind: 'inbox.changed', unread: 3 })).toBe(3);
    expect(unreadOf({ kind: 'job.ended', job: 'job_1' })).toBeUndefined();
  });
});

describe('inboxPageOf', () => {
  const ITEM = {
    id: 'inb_1',
    arrivedAt: '2026-09-13T09:02:00Z',
    kind: 'run',
    ending: 'ANSWERED',
    answer: 'three PRs',
  };

  it('reads a well-formed inbox.list answer', () => {
    expect(
      inboxPageOf({ code: 'OK', payload: { unread: 2, items: [ITEM] } }),
    ).toEqual({ unread: 2, items: [ITEM] });
  });

  it('reads an empty page as an empty page and not a refusal', () => {
    expect(
      inboxPageOf({ code: 'OK', payload: { unread: 0, items: [] } }),
    ).toEqual({ unread: 0, items: [] });
  });
  it('retains read timestamps for mailbox history and accepts null for unread items', () => {
    const readAt = '2026-10-02T00:00:00Z';
    expect(
      inboxPageOf({
        code: 'OK',
        payload: { unread: 0, items: [{ ...ITEM, readAt }] },
      })?.items,
    ).toEqual([{ ...ITEM, readAt }]);
    expect(
      inboxPageOf({
        code: 'OK',
        payload: { unread: 1, items: [{ ...ITEM, readAt: null }] },
      })?.items,
    ).toEqual([ITEM]);
  });
  it('rejects a malformed read timestamp rather than treating a read item as unread', () => {
    expect(
      inboxPageOf({
        code: 'OK',
        payload: { unread: 0, items: [{ ...ITEM, readAt: 42 }] },
      }),
    ).toBeUndefined();
  });

  it('refuses an answer that is not OK, whatever its payload', () => {
    expect(
      inboxPageOf({
        code: 'BAD_REQUEST',
        said: 'no account on this socket',
        payload: { unread: 2, items: [ITEM] },
      }),
    ).toBeUndefined();
  });

  it('refuses an OK payload with no items', () => {
    expect(inboxPageOf({ code: 'OK', payload: { unread: 0 } })).toBeUndefined();
  });

  it('refuses an OK payload with no unread count', () => {
    expect(inboxPageOf({ code: 'OK', payload: { items: [] } })).toBeUndefined();
  });

  it('refuses an OK payload whose items is not an array', () => {
    expect(
      inboxPageOf({ code: 'OK', payload: { unread: 1, items: ITEM } }),
    ).toBeUndefined();
  });

  it('refuses a page holding one item with a field of the wrong type', () => {
    expect(
      inboxPageOf({
        code: 'OK',
        payload: { unread: 1, items: [{ ...ITEM, arrivedAt: 1_757_754_120 }] },
      }),
    ).toBeUndefined();
  });

  it('refuses a page holding one item missing a field entirely', () => {
    const { answer: _answer, ...rest } = ITEM;
    expect(
      inboxPageOf({ code: 'OK', payload: { unread: 1, items: [rest] } }),
    ).toBeUndefined();
  });

  it('refuses no payload at all', () => {
    expect(inboxPageOf({ code: 'OK' })).toBeUndefined();
  });
});

describe('inboxPageOf with notices', () => {
  it('reads a notice that has no ending and a missing kind as a run', () => {
    const page = inboxPageOf({
      code: 'OK',
      payload: {
        unread: 2,
        items: [
          { id: 'inb_1', arrivedAt: 't', kind: 'sync.conflict', answer: 'a' },
          { id: 'inb_2', arrivedAt: 't', ending: 'DONE', answer: 'b' },
        ],
      },
    });
    expect(page?.items.map((item) => [item.kind, item.ending])).toEqual([
      ['sync.conflict', undefined],
      ['run', 'DONE'],
    ]);
  });

  it('still refuses a run with no ending', () => {
    expect(
      inboxPageOf({
        code: 'OK',
        payload: {
          unread: 1,
          items: [{ id: 'inb_3', arrivedAt: 't', kind: 'run', answer: 'b' }],
        },
      }),
    ).toBeUndefined();
  });
});

describe('a tier that names its own default bot', () => {
  const SOPHRON: Agent = {
    name: 'sophron',
    bot: true,
    served: true,
    withheld: [],
    description: 'd',
    preferred: true,
    ...DECLARES,
  };

  it('reads the flag off a row, and false off a server too old to send it', () => {
    const rows = agents({
      code: 'OK',
      payload: [
        {
          name: 'sophron',
          bot: true,
          served: true,
          withheld: [],
          description: 'd',
          preferred: true,
        },
        {
          name: 'aristoxenus',
          bot: true,
          served: true,
          withheld: [],
          description: 'd',
        },
      ],
    });
    expect(rows?.map((row) => row.preferred)).toEqual([true, false]);
  });

  it('answers with the preferred bot even when there are several', () => {
    expect(whoAnswers([...ROSTER, SOPHRON])).toEqual({
      kind: 'answering',
      agent: SOPHRON,
      asked: false,
      preferred: true,
    });
  });

  it('still lets a named agent win over the default', () => {
    expect(whoAnswers([...ROSTER, SOPHRON], 'close_reader')).toEqual({
      kind: 'answering',
      agent: ROSTER[1],
      asked: true,
      preferred: false,
    });
  });

  it('says the default is unmet rather than quietly finding another bot', () => {
    const missing: Agent = {
      name: 'sophron',
      bot: false,
      served: false,
      preferred: true,
      description: '',
      withheld: [
        'named as the default bot in global/bots/default, and nothing a person can reach here is called that',
      ],
      ...DECLARES,
    };
    expect(whoAnswers([...ROSTER, missing])).toEqual({
      kind: 'unmet',
      named: 'sophron',
      why: missing.withheld,
    });
    const agentNotBot: Agent = { ...(ROSTER[1] as Agent), preferred: true };
    expect(whoAnswers([ROSTER[0] as Agent, agentNotBot]).kind).toBe('unmet');
  });

  it("calls the default the tier's, because the global tier is not a project", () => {
    const said = describeChoice(whoAnswers([...ROSTER, SOPHRON]))[0] ?? '';
    expect(said).toBe("talking to sophron, this tier's default");
    expect(said).not.toContain('project');
  });
});

describe('the three commands that move a person', () => {
  it('reads /here with and without a name', () => {
    expect(typed('/here')).toEqual({ kind: 'here' });
    expect(typed('/here  ledger ')).toEqual({ kind: 'here', name: 'ledger' });
  });

  it('reads /project and /cd, and asks for the argument each needs', () => {
    expect(typed('/project ledger')).toEqual({
      kind: 'project',
      name: 'ledger',
    });
    expect(typed('/cd ../my notes')).toEqual({
      kind: 'cd',
      path: '../my notes',
    });
    expect(typed('/project')).toEqual({ kind: 'usage', command: '/project' });
    expect(typed('/cd')).toEqual({ kind: 'usage', command: '/cd' });
  });

  it('keeps /projects the listing, not /project with an s', () => {
    expect(typed('/projects').kind).toBe('projects');
    expect(typed('/projectsx').kind).toBe('unknown');
  });

  it("reads a project row's machine and workspace when the server sends them", () => {
    expect(
      projects({
        code: 'OK',
        payload: [
          {
            name: 'ledger',
            workspace: '/u/ledger',
            machine: 'bench',
            lent: [],
            exclusions: [],
          },
          {
            name: 'notes',
            workspace: '/srv/notes',
            machine: null,
            lent: [],
            exclusions: [],
          },
        ],
      }),
    ).toEqual([
      { name: 'ledger', workspace: '/u/ledger', machine: 'bench' },
      { name: 'notes', workspace: '/srv/notes' },
    ]);
  });
});

describe('what is said about where a person is', () => {
  it("says the spec's refusal, and that nothing moved", () => {
    expect(
      describeRefusedMove(
        'ledger',
        { kind: 'none', unreadable: [] },
        { project: 'accounts', who: 'sophron' },
        false,
      ),
    ).toEqual([
      'ledger serves no bot and no agent, and nothing global answers for it.',
      "staying in accounts with sophron. Add one to ledger's bots/, or name one in its bots/default.",
    ]);
  });

  it('owns up to the project row a refused /here leaves behind', () => {
    expect(
      describeRefusedMove(
        'ledger',
        { kind: 'none', unreadable: [] },
        { who: 'aristoxenus' },
        true,
      ).join(' '),
    ).toContain('the server now knows ledger');
  });

  it('names the global tier when there is no project', () => {
    expect(describeMovedTo(undefined, 'aristoxenus', false)).toBe(
      'now in global resources, talking to aristoxenus',
    );
    expect(describeMovedTo('ledger', 'sophron', true)).toContain(
      'a different bot',
    );
  });

  it("tells files on another machine from the server's own, and from nobody's", () => {
    // Spec §5: "either another session roots them, or nobody does".
    expect(
      describeFilesElsewhere('notes', {
        machine: 'desk.local',
        workspace: '/srv/notes',
      }),
    ).toBe(
      'notes is rooted on desk.local, not here — its runs read files there while that' +
        ' machine is connected',
    );
    expect(describeFilesElsewhere('notes', { workspace: '/srv/notes' })).toBe(
      "notes's files are the server's own, so runs read them there",
    );
    const nobody = describeFilesElsewhere('notes', {});
    expect(nobody).toContain('nothing roots notes');
    expect(nobody).not.toContain("server's own");
  });

  it('says a default that cannot answer in the choice arm', () => {
    expect(
      describeChoice({ kind: 'unmet', named: 'sophron', why: ['gone'] })[0],
    ).toBe("this tier's default bot is sophron, and it cannot answer: gone");
  });

  it('mentions each new command in the help', () => {
    const help = describeHelp().join('\n');
    expect(help).toContain(HERE_COMMAND);
    expect(help).toContain(PROJECT_COMMAND);
    expect(help).toContain(CD_COMMAND);
  });
});

describe("/log, the provenance view — the log and each entry's state and target", () => {
  // The frame the server serves as conversation.trajectory, whose page
  // carries the V39 provenance columns EntryView now sends.
  const page = (entries: unknown[], total = entries.length): Answer => ({
    code: OK,
    payload: { entries, total, offset: 0, limit: 200 },
  });

  it("is a command that reaches something, the explorer's log view", () => {
    expect(typed(LOG_COMMAND)).toEqual({ kind: 'trajectory', view: 'log' });
    expect(COMMANDS).toContain(LOG_COMMAND);
  });

  it("reads a page's entries with their kind, state and provenance", () => {
    const rows = entriesOf(
      page([
        { ordinal: 1, turnOrdinal: 1, kind: 'utterance' },
        {
          ordinal: 2,
          turnOrdinal: 1,
          kind: 'refusal',
          dispatch: 'primary',
          wireModel: 'openai/gpt-oss-120b',
          completion: 'refused',
        },
        {
          ordinal: 3,
          turnOrdinal: 1,
          kind: 'answer',
          dispatch: 'fallback',
          wireModel: 'gpt-oss-20b-heretic',
          completion: 'answered',
          supersededBy: null,
        },
      ]),
    );

    expect(rows.map((entry) => entry.kind)).toEqual([
      'utterance',
      'refusal',
      'answer',
    ]);
    // An utterance names no model; the answer names its fallback target.
    expect(rows[0]?.dispatch).toBeUndefined();
    expect(answeredByFallback(rows[2] as never)).toBe(true);
    expect(answeredByFallback(rows[1] as never)).toBe(false);
    expect(rows.every((entry) => entry.state === 'stands')).toBe(true);
  });

  it("marks a folded entry's state rather than leaving it blank", () => {
    const rows = entriesOf(
      page([
        { ordinal: 1, turnOrdinal: 1, kind: 'answer', supersededBy: 8 },
        {
          ordinal: 2,
          turnOrdinal: 1,
          kind: 'tool_result',
          ejectedAt: '2026-09-13T00:00:00Z',
        },
      ]),
    );
    expect(rows[0]?.state).toBe('folded→8');
    expect(rows[1]?.state).toBe('ejected');
  });

  it("reports the whole log's size and not the page's", () => {
    expect(logTotal(page([{ ordinal: 1 }], 200))).toBe(200);
    // No total on the wire falls back to what the page holds, never larger.
    expect(logTotal({ code: OK, payload: { entries: [{ ordinal: 1 }] } })).toBe(
      1,
    );
  });

  it('names the fallback where a reader looks, and only names a model where there is one', () => {
    const [said, answered] = entriesOf(
      page([
        { ordinal: 1, turnOrdinal: 1, kind: 'utterance' },
        {
          ordinal: 2,
          turnOrdinal: 1,
          kind: 'answer',
          dispatch: 'fallback',
          wireModel: 'gpt-oss-20b-heretic',
          completion: 'answered',
        },
      ]),
    );
    expect(describeProvenance(said as never)).toBe('');
    expect(describeProvenance(answered as never)).toBe(
      'answered by the fallback (gpt-oss-20b-heretic)',
    );
  });

  it("reads a call's salient argument and the child it opened, and a result's outcome and timing", () => {
    const rows = entriesOf(
      page([
        {
          ordinal: 4,
          turnOrdinal: 2,
          kind: 'answer',
          excerpt: '',
          length: 0,
          toolCalls: [
            {
              id: 'c1',
              name: 'run',
              arguments: '{"command":"ls"}',
              length: 16,
              cut: false,
              salient: 'ls',
              opened: null,
            },
            {
              id: 'c2',
              name: 'agent_run',
              arguments: '{"agent":"helper"}',
              length: 18,
              cut: false,
              salient: 'helper',
              opened: { conversation: 'cnv_2', agent: 'helper' },
            },
          ],
        },
        {
          ordinal: 5,
          turnOrdinal: 2,
          kind: 'tool_result',
          excerpt: 'build\ndocs',
          length: 10,
          toolCallId: 'c1',
          outcome: 'ok',
          tookMillis: 53,
          recordedAt: '2026-09-29T10:00:00Z',
          handle: 'h-1',
        },
        {
          ordinal: 6,
          turnOrdinal: 2,
          kind: 'tool_result',
          excerpt: 'x',
          length: 1,
          supersededBy: 9,
        },
      ]),
    );
    expect(rows[0]?.calls).toEqual([
      {
        id: 'c1',
        name: 'run',
        arguments: '{"command":"ls"}',
        length: 16,
        cut: false,
        salient: 'ls',
      },
      {
        id: 'c2',
        name: 'agent_run',
        arguments: '{"agent":"helper"}',
        length: 18,
        cut: false,
        salient: 'helper',
        opened: { conversation: 'cnv_2', agent: 'helper' },
      },
    ]);
    expect(rows[0]?.asked).toBe(2);
    expect(rows[1]).toMatchObject({
      toolCallId: 'c1',
      outcome: 'ok',
      tookMillis: 53,
      recordedAt: '2026-09-29T10:00:00Z',
      handle: 'h-1',
    });
    expect(rows[2]?.supersededBy).toBe(9);
    expect(rows[1]?.calls).toBeUndefined();
  });

  it('asks for a trace, a log tail and an earlier log page over every kind', () => {
    expect(readingTrace('cnv_1', 40, 0)).toEqual({
      type: CONVERSATION_TRAJECTORY,
      payload: { conversation: 'cnv_1', after: 40, offset: 0 },
    });
    expect(readingLogTail('cnv_1')).toEqual({
      type: CONVERSATION_TRAJECTORY,
      payload: { conversation: 'cnv_1', tail: true, limit: LOG_PAGE },
    });
    expect(readingLogBefore('cnv_1', 301)).toEqual({
      type: CONVERSATION_TRAJECTORY,
      payload: { conversation: 'cnv_1', before: 301, limit: LOG_PAGE },
    });
  });
});

describe('/earlier, which goes back through the conversation on screen', () => {
  it('is a command that reaches something, takes no argument and says what it is for', () => {
    expect(typed(EARLIER_COMMAND)).toEqual({ kind: 'earlier' });
    expect(typed(' /earlier ')).toEqual({ kind: 'earlier' });
    expect(COMMANDS).toContain(EARLIER_COMMAND);
    expect(describeHelp().join('\n')).toContain('/earlier — ');
  });

  it('offers itself above a tail, and labels what it draws as earlier history', () => {
    expect(describeEarlierHint()).toBe('— earlier entries: /earlier —');
    expect(describeEarlier(40)).toBe('— 40 earlier entries —');
    expect(describeEarlier(1)).toBe('— 1 earlier entry —');
    expect(describeBeginning()).toBe(
      '— that is the beginning of this conversation —',
    );
  });

  it('tells no conversation apart from one whose log is not on the screen yet', () => {
    expect(describeNoEarlier()).toContain('no conversation');
    expect(describeNotYetShown()).toContain('not on the screen yet');
  });
});

describe('following the log of the conversation on screen', () => {
  it('asks to follow one conversation by its id', () => {
    expect(followingLog('cnv_1')).toEqual({
      type: CONVERSATION_FOLLOW,
      payload: { conversation: 'cnv_1' },
    });
  });

  it('replaces the complete set of followed logs and can follow none', () => {
    const views = ['cnv_1', 'cnv_2'];
    const ask = followingLogs(views);
    views.pop();
    expect(ask).toEqual({
      type: CONVERSATION_FOLLOW,
      payload: { conversations: ['cnv_1', 'cnv_2'] },
    });
    expect(followingLogs([])).toEqual({
      type: CONVERSATION_FOLLOW,
      payload: { conversations: [] },
    });
  });

  it('reads the push that says a followed log grew, and nothing else', () => {
    expect(
      appendedOf({
        kind: CONVERSATION_APPENDED,
        conversation: 'cnv_1',
        through: 12,
      }),
    ).toEqual({ conversation: 'cnv_1', through: 12 });
    expect(appendedOf({ kind: 'inbox.changed', unread: 2 })).toBeUndefined();
    expect(
      appendedOf({ kind: CONVERSATION_APPENDED, conversation: 'cnv_1' }),
    ).toBeUndefined();
    expect(
      appendedOf({ kind: CONVERSATION_APPENDED, through: 3 }),
    ).toBeUndefined();
    expect(appendedOf('not a push')).toBeUndefined();
  });

  it('reads the tail back from the end, and earlier from an ordinal, as only what the chat draws', () => {
    expect(DRAWN_KINDS).toEqual(['utterance', 'answer', 'summary']);
    expect(readingTail('cnv_1')).toEqual({
      type: CONVERSATION_TRAJECTORY,
      payload: {
        conversation: 'cnv_1',
        tail: true,
        limit: LOG_BACK,
        kinds: ['utterance', 'answer', 'summary'],
        drawn: true,
      },
    });
    expect(readingEarlier('cnv_1', 61)).toEqual({
      type: CONVERSATION_TRAJECTORY,
      payload: {
        conversation: 'cnv_1',
        before: 61,
        limit: LOG_BACK,
        kinds: ['utterance', 'answer', 'summary'],
        drawn: true,
      },
    });
    // Forty entries, and one read: never more than the server will put on a page.
    expect(LOG_BACK).toBe(40);
    expect(LOG_BACK).toBeLessThanOrEqual(LOG_PAGE);
  });

  it('catches up on what came after an ordinal, narrowed to what the chat draws', () => {
    expect(readingAfter('cnv_1', 40, 100)).toEqual({
      type: CONVERSATION_TRAJECTORY,
      payload: {
        conversation: 'cnv_1',
        after: 40,
        offset: 100,
        kinds: ['utterance', 'answer', 'summary'],
        drawn: true,
      },
    });
    expect(readingAfter('cnv_1', 40, 0, 1)).toEqual({
      type: CONVERSATION_TRAJECTORY,
      payload: {
        conversation: 'cnv_1',
        after: 40,
        offset: 0,
        limit: 1,
        kinds: ['utterance', 'answer', 'summary'],
        drawn: true,
      },
    });
  });

  it('reads a page read backwards into conversation order, with where to go back from and whether to', () => {
    const back = backPageOf({
      code: OK,
      payload: {
        entries: [
          { ordinal: 9, turnOrdinal: 4, kind: 'answer', excerpt: 'four' },
          { ordinal: 8, turnOrdinal: 2, kind: 'summary', excerpt: 'folded' },
          { ordinal: 7, turnOrdinal: 4, kind: 'utterance', excerpt: 'asked' },
        ],
        total: 12,
        offset: 0,
        limit: 40,
        through: 10,
        oldest: 7,
        more: true,
      },
    });
    // Newest first on the wire; drawn in the order the conversation happened, so a fold
    // written late sorts back among the turns it folds, as a forward reading orders it.
    expect(back?.entries.map((each) => each.ordinal)).toEqual([8, 7, 9]);
    expect(back?.through).toBe(10);
    expect(back?.oldest).toBe(7);
    expect(back?.more).toBe(true);
  });

  it('reads a backwards page that says nothing more as the beginning, and a refusal as nothing', () => {
    const back = backPageOf({
      code: OK,
      payload: {
        entries: [{ ordinal: 2, turnOrdinal: 1, kind: 'answer' }],
        total: 1,
        through: 2,
      },
    });
    expect(back?.oldest).toBe(2);
    expect(back?.more).toBe(false);
    expect(
      backPageOf({
        code: OK,
        payload: {
          entries: [],
          total: 0,
          through: 0,
          oldest: null,
          more: false,
        },
      }),
    ).toEqual({ entries: [], through: 0, more: false, total: 0 });
    expect(
      backPageOf({ code: 'NOT_FOUND', said: 'no conversation' }),
    ).toBeUndefined();
  });

  it('reads how far the log reaches off a page, and nothing off a refusal', () => {
    expect(
      logThrough({ code: OK, payload: { entries: [], total: 0, through: 57 } }),
    ).toBe(57);
    expect(
      logThrough({ code: 'NOT_FOUND', said: 'no conversation' }),
    ).toBeUndefined();
  });

  it('reads who spoke an entry, its text, and whether it asked for tools', () => {
    const [spoken, answered] = entriesOf({
      code: OK,
      payload: {
        entries: [
          {
            ordinal: 9,
            turnOrdinal: 4,
            kind: 'utterance',
            excerpt: 'The run stopped.',
            length: 16,
            cut: false,
            toolCalls: [],
            speaker: 'harness',
            speakerName: 'orchestration orc_1',
          },
          {
            ordinal: 10,
            turnOrdinal: 4,
            kind: 'answer',
            excerpt: 'Looking.',
            length: 9000,
            cut: true,
            toolCalls: [
              {
                id: 'c1',
                name: 'file_read',
                arguments: '{}',
                length: 2,
                cut: false,
              },
            ],
            speaker: null,
            speakerName: null,
          },
        ],
        total: 2,
      },
    });
    expect(spoken).toMatchObject({
      text: 'The run stopped.',
      speaker: 'harness',
      speakerName: 'orchestration orc_1',
    });
    expect(spoken?.asked).toBeUndefined();
    expect(answered).toMatchObject({
      text: 'Looking.',
      length: 9000,
      cut: true,
      asked: 1,
    });
    expect(answered?.speaker).toBeUndefined();
  });
});

describe('conversation.context, which is how full a conversation is', () => {
  it('prices the conversation against the agent a person is talking to now', () => {
    expect(measuring('cnv_1', 'coder')).toEqual({
      type: CONVERSATION_CONTEXT,
      payload: { conversation: 'cnv_1', agent: 'coder' },
    });
    expect(CONVERSATION_CONTEXT).toBe('conversation.context');
  });

  it('reads the newest measured prompt and the window out of a whole ContextView', () => {
    const load = loadOf({
      code: 'OK',
      payload: {
        sent: 16_234,
        sentAtTurn: 4,
        turns: 4,
        turnsMeasured: 3,
        measuredTurns: [],
        systemPromptTokens: null,
        toolTokens: null,
        messageTokens: null,
        cacheHitRate: null,
        unavailable: [],
        prefix: {
          agent: 'coder',
          model: 'coder',
          systemPromptCharacters: 900,
          toolCharacters: 4_000,
          tools: [],
          contextLength: 120_000,
        },
      },
    });

    expect(load).toEqual({
      sent: 16_234,
      sentAtTurn: 4,
      limit: 120_000,
      model: 'coder',
    });
  });

  it('says nothing it was not told: no turn measured, no prefix, an old server', () => {
    // A null `sent` is a conversation no turn of which reached a model
    // call, and it has to stay absent rather than become a zero.
    expect(
      loadOf({ code: 'OK', payload: { sent: null, prefix: null } }),
    ).toEqual({});
    expect(
      loadOf({ code: 'OK', payload: { sent: 900, prefix: { model: 'fast' } } }),
    ).toEqual({ sent: 900, model: 'fast' });
  });

  it('reads nothing out of a refusal', () => {
    expect(
      loadOf({ code: 'NOT_FOUND', said: 'no conversation cnv_1' }),
    ).toBeUndefined();
  });
});

describe('scheduling, read out of one sentence and saved only on a yes', () => {
  const PROPOSAL = {
    cron: '0 9 * * 1-5',
    zone: 'Australia/Sydney',
    when: 'every weekday at 9am',
    agent: 'interlocutor',
    task: 'summarise what changed yesterday',
    intoConversation: false,
    project: 'plowshare',
    conversation: null,
    nextFires: [
      '2026-09-14T23:00:00Z',
      '2026-09-15T23:00:00Z',
      '2026-09-16T23:00:00Z',
    ],
    names: {
      schedule: 'summarise-what-changed',
      trigger: 'summarise-what-changed',
      event: 'summarise-what-changed',
    },
  };
  const SCHEDULE = {
    name: 'daily',
    cron: '0 9 * * *',
    zone: 'UTC',
    emits: 'daily',
    paused: false,
    nextFireAt: '2026-09-14T09:00:00Z',
    definedBy: 'enzo',
  };
  const TRIGGER = {
    name: 'daily',
    event: 'daily',
    project: null,
    conversation: null,
    agent: 'interlocutor',
    task: 'say hello',
    maxModelCalls: null,
    maxTurns: null,
    queueCap: 1,
    paused: false,
    definedBy: 'enzo',
  };
  const FIRING = {
    id: 'fir_1',
    event: 'daily',
    data: '{}',
    schedule: 'daily',
    fireAt: '2026-09-14T09:00:00Z',
    trigger: 'daily',
    target: 'trigger:daily',
    status: 'DONE',
    supersededBy: null,
    reason: null,
    jobId: 'job_1',
    arrivedAt: '2026-09-14T09:00:01Z',
    startedAt: null,
    finishedAt: null,
  };

  it('reads a sentence after /schedule as a sentence to schedule', () => {
    expect(
      typed('/schedule every weekday at 9am have the interlocutor summarise'),
    ).toEqual({
      kind: 'schedule',
      text: 'every weekday at 9am have the interlocutor summarise',
    });
  });

  it('reads pause, resume and forget with one name as managing that schedule', () => {
    expect(typed('/schedule pause daily')).toEqual({
      kind: 'managing',
      verb: 'pause',
      name: 'daily',
    });
    expect(typed('/schedule resume daily')).toEqual({
      kind: 'managing',
      verb: 'resume',
      name: 'daily',
    });
    expect(typed('/schedule  forget   daily ')).toEqual({
      kind: 'managing',
      verb: 'forget',
      name: 'daily',
    });
  });

  it('answers a sub-verb with no name, and /schedule with nothing, with a usage line', () => {
    expect(typed('/schedule pause')).toEqual({
      kind: 'usage',
      command: '/schedule pause',
    });
    expect(typed('/schedule forget')).toEqual({
      kind: 'usage',
      command: '/schedule forget',
    });
    expect(typed('/fire')).toEqual({ kind: 'usage', command: '/fire' });
  });

  it('lists for /schedule list and bare /schedule, and reads list with more words as a sentence', () => {
    expect(typed('/schedule')).toEqual({ kind: 'schedules' });
    expect(typed('/schedule list')).toEqual({ kind: 'schedules' });
    expect(typed('/schedule  list ')).toEqual({ kind: 'schedules' });
    expect(typed('/schedule list the open PRs every morning')).toEqual({
      kind: 'schedule',
      text: 'list the open PRs every morning',
    });
  });

  it('has no /schedules, which /schedule list replaced', () => {
    expect(typed('/schedules')).toEqual({
      kind: 'unknown',
      named: '/schedules',
    });
    expect(COMMANDS).not.toContain('/schedules');
  });

  it('reads /fire <name> and /firings', () => {
    expect(typed('/fire daily')).toEqual({ kind: 'fire', name: 'daily' });
    expect(typed('/firings')).toEqual({
      kind: 'firings',
      ask: listingFirings(),
    });
    expect(typed('/fired').kind).toBe('unknown');
  });

  it('builds every frame with exactly the fields the handlers read', () => {
    expect(readingSchedule('every day', 'UTC')).toEqual({
      type: SCHEDULE_READ,
      payload: { text: 'every day', zone: 'UTC' },
    });
    expect(readingSchedule('every day', 'UTC', 'plowshare', 'cnv_1')).toEqual({
      type: SCHEDULE_READ,
      payload: {
        text: 'every day',
        zone: 'UTC',
        project: 'plowshare',
        conversation: 'cnv_1',
      },
    });
    expect(listingSchedules()).toEqual({ type: SCHEDULE_LIST, payload: {} });
    expect(listingTriggers()).toEqual({ type: TRIGGER_LIST, payload: {} });
    expect(pausingSchedule('daily', true)).toEqual({
      type: SCHEDULE_PAUSE,
      payload: { schedule: 'daily', paused: true },
    });
    expect(pausingTrigger('daily', false)).toEqual({
      type: TRIGGER_PAUSE,
      payload: { trigger: 'daily', paused: false },
    });
    expect(forgettingSchedule('daily')).toEqual({
      type: SCHEDULE_FORGET,
      payload: { schedule: 'daily' },
    });
    expect(forgettingTrigger('daily')).toEqual({
      type: TRIGGER_FORGET,
      payload: { trigger: 'daily' },
    });
    expect(firingEvent('daily')).toEqual({
      type: EVENT_FIRE,
      payload: { event: 'daily' },
    });
    expect(listingFirings()).toEqual({
      type: FIRING_LIST,
      payload: { limit: 10 },
    });
  });

  it('saves a proposal as a schedule and a trigger, naming a project only when there is one', () => {
    const proposal = proposalOf({ code: OK, payload: PROPOSAL }) as Proposal;
    expect(definingSchedule(proposal)).toEqual({
      type: SCHEDULE_DEFINE,
      payload: {
        schedule: 'summarise-what-changed',
        cron: '0 9 * * 1-5',
        zone: 'Australia/Sydney',
        emits: 'summarise-what-changed',
      },
    });
    expect(definingTrigger(proposal)).toEqual({
      type: TRIGGER_DEFINE,
      payload: {
        trigger: 'summarise-what-changed',
        event: 'summarise-what-changed',
        agent: 'interlocutor',
        task: 'summarise what changed yesterday',
        project: 'plowshare',
      },
    });
    const global = proposalOf({
      code: OK,
      payload: { ...PROPOSAL, project: null },
    }) as Proposal;
    expect(definingTrigger(global).payload).not.toHaveProperty('project');
    expect(definingTrigger(global).payload).not.toHaveProperty('conversation');
    const into = proposalOf({
      code: OK,
      payload: {
        ...PROPOSAL,
        project: null,
        intoConversation: true,
        conversation: 'cnv_1',
      },
    }) as Proposal;
    expect(definingTrigger(into).payload).toEqual({
      trigger: 'summarise-what-changed',
      event: 'summarise-what-changed',
      agent: 'interlocutor',
      task: 'summarise what changed yesterday',
      conversation: 'cnv_1',
    });
  });

  it('reads a proposal, and refuses one missing a field or carrying the wrong type', () => {
    expect(proposalOf({ code: OK, payload: PROPOSAL })).toEqual({
      cron: '0 9 * * 1-5',
      zone: 'Australia/Sydney',
      when: 'every weekday at 9am',
      agent: 'interlocutor',
      task: 'summarise what changed yesterday',
      intoConversation: false,
      project: 'plowshare',
      nextFires: PROPOSAL.nextFires,
      names: PROPOSAL.names,
    });
    const { task: _task, ...noTask } = PROPOSAL;
    expect(proposalOf({ code: OK, payload: noTask })).toBeUndefined();
    expect(
      proposalOf({ code: OK, payload: { ...PROPOSAL, nextFires: [1, 2, 3] } }),
    ).toBeUndefined();
    expect(
      proposalOf({
        code: OK,
        payload: { ...PROPOSAL, intoConversation: 'no' },
      }),
    ).toBeUndefined();
    expect(
      proposalOf({
        code: OK,
        payload: { ...PROPOSAL, names: { schedule: 'x' } },
      }),
    ).toBeUndefined();
    expect(
      proposalOf({ code: OK, payload: { ...PROPOSAL, project: 7 } }),
    ).toBeUndefined();
    // Into a conversation it does not name is a trigger that could not be defined.
    expect(
      proposalOf({
        code: OK,
        payload: { ...PROPOSAL, intoConversation: true },
      }),
    ).toBeUndefined();
    expect(
      proposalOf({ code: 'BAD_REQUEST', said: 'no', payload: PROPOSAL }),
    ).toBeUndefined();
  });

  it('reads schedules, and refuses a page with one bad row', () => {
    expect(schedulesOf({ code: OK, payload: [SCHEDULE] })).toEqual([
      {
        name: 'daily',
        cron: '0 9 * * *',
        zone: 'UTC',
        emits: 'daily',
        paused: false,
        nextFireAt: '2026-09-14T09:00:00Z',
      },
    ]);
    expect(
      schedulesOf({ code: OK, payload: [{ ...SCHEDULE, nextFireAt: null }] }),
    ).toEqual([
      {
        name: 'daily',
        cron: '0 9 * * *',
        zone: 'UTC',
        emits: 'daily',
        paused: false,
      },
    ]);
    expect(schedulesOf({ code: OK, payload: [] })).toEqual([]);
    const { emits: _emits, ...noEmits } = SCHEDULE;
    expect(
      schedulesOf({ code: OK, payload: [SCHEDULE, noEmits] }),
    ).toBeUndefined();
    expect(
      schedulesOf({ code: OK, payload: [{ ...SCHEDULE, paused: 'yes' }] }),
    ).toBeUndefined();
    expect(schedulesOf({ code: OK, payload: SCHEDULE })).toBeUndefined();
    expect(schedulesOf({ code: 'NOT_FOUND', said: 'no' })).toBeUndefined();
  });

  it('reads triggers, and refuses a page with one bad row', () => {
    expect(
      triggersOf({ code: OK, payload: [{ ...TRIGGER, project: 'plowshare' }] }),
    ).toEqual([
      {
        name: 'daily',
        event: 'daily',
        agent: 'interlocutor',
        task: 'say hello',
        paused: false,
        project: 'plowshare',
      },
    ]);
    const { task: _task, ...noTask } = TRIGGER;
    expect(triggersOf({ code: OK, payload: [noTask] })).toBeUndefined();
    expect(
      triggersOf({ code: OK, payload: [{ ...TRIGGER, conversation: 3 }] }),
    ).toBeUndefined();
    expect(triggersOf({ code: OK })).toBeUndefined();
  });

  it('reads firings, and refuses a page with one bad row', () => {
    expect(
      firingsOf({
        code: OK,
        payload: [
          FIRING,
          {
            ...FIRING,
            id: 'fir_2',
            trigger: null,
            status: 'REFUSED',
            reason: 'nobody listening',
          },
        ],
      }),
    ).toEqual([
      {
        id: 'fir_1',
        event: 'daily',
        status: 'DONE',
        arrivedAt: '2026-09-14T09:00:01Z',
        trigger: 'daily',
      },
      {
        id: 'fir_2',
        event: 'daily',
        status: 'REFUSED',
        arrivedAt: '2026-09-14T09:00:01Z',
        reason: 'nobody listening',
      },
    ]);
    const { status: _status, ...noStatus } = FIRING;
    expect(firingsOf({ code: OK, payload: [noStatus] })).toBeUndefined();
    expect(
      firingsOf({ code: OK, payload: [{ ...FIRING, arrivedAt: 12 }] }),
    ).toBeUndefined();
    expect(firingsOf({ code: 'BAD_REQUEST' })).toBeUndefined();
  });

  it('finds the triggers listening to a schedule by the event it emits', () => {
    const schedule = (schedulesOf({ code: OK, payload: [SCHEDULE] }) ?? [])[0];
    const rows =
      triggersOf({
        code: OK,
        payload: [TRIGGER, { ...TRIGGER, name: 'other', event: 'weekly' }],
      }) ?? [];
    expect(schedule).toBeDefined();
    expect(listeningTo(schedule as never, rows).map((row) => row.name)).toEqual(
      ['daily'],
    );
  });

  it('takes only y or yes as a yes', () => {
    for (const yes of ['y', 'Y', 'yes', ' YES ']) {
      expect(answeredYes(yes)).toBe(true);
    }
    for (const no of ['n', 'no', '', 'yeah', '/schedules', 'y please']) {
      expect(answeredYes(no)).toBe(false);
    }
  });
});

describe('asking a person before a command runs, on the wire', () => {
  /** One `ApprovalFrames.View`, every component, as Jackson writes it. */
  const VIEW = {
    id: 'apr_1',
    conversation: 'cnv_1',
    agent: 'builder',
    side: 'local',
    command: ['./gradlew', 'test'],
    cwd: '/repo',
    reason: null,
    state: 'asked',
    scope: null,
    prefix: null,
    defaultPrefix: ['./gradlew', 'test'],
    createdAt: '2026-09-15T10:00:00Z',
    answeredAt: null,
  };

  it("lists a conversation's questions or a project's approvals, never both", () => {
    expect(listingAsked('cnv_1')).toEqual({
      type: APPROVAL_LIST,
      payload: { conversation: 'cnv_1' },
    });
    expect(listingApprovals('plowshare')).toEqual({
      type: APPROVAL_LIST,
      payload: { project: 'plowshare' },
    });
  });

  it('sends a prefix with a project answer and with nothing else', () => {
    expect(answeringApproval('apr_1', 'project', ['make'])).toEqual({
      type: APPROVAL_ANSWER,
      payload: { id: 'apr_1', decision: 'project', prefix: ['make'] },
    });
    expect(answeringApproval('apr_1', 'once', ['make'])).toEqual({
      type: APPROVAL_ANSWER,
      payload: { id: 'apr_1', decision: 'once' },
    });
    expect(revokingApproval('apr_2')).toEqual({
      type: APPROVAL_REVOKE,
      payload: { id: 'apr_2' },
    });
  });

  it('reads a listing, with absent and null fields left absent', () => {
    expect(approvalsOf({ code: 'OK', payload: { approvals: [VIEW] } })).toEqual(
      [
        {
          id: 'apr_1',
          conversation: 'cnv_1',
          agent: 'builder',
          side: 'local',
          command: ['./gradlew', 'test'],
          cwd: '/repo',
          state: 'asked',
          defaultPrefix: ['./gradlew', 'test'],
        },
      ],
    );
    const standing = approvalsOf({
      code: 'OK',
      payload: {
        approvals: [
          {
            ...VIEW,
            reason: 'network',
            state: 'allowed',
            scope: 'project',
            prefix: ['./gradlew'],
          },
        ],
      },
    });
    expect(standing?.[0]).toMatchObject({
      reason: 'network',
      scope: 'project',
      prefix: ['./gradlew'],
    });
  });

  it("reads an acceptance set: no command of its own, its commands and the judge's words (V67)", () => {
    const rows = approvalsOf({
      code: 'OK',
      payload: {
        approvals: [
          {
            ...VIEW,
            command: [],
            commands: [
              ['pytest', '-q'],
              ['make', 'check'],
            ],
            judged: 'unsure',
          },
          { ...VIEW, id: 'apr_x', command: [], commands: [] },
          { ...VIEW, id: 'apr_y', command: [], commands: [['pytest'], []] },
        ],
      },
    });

    expect(rows).toEqual([
      {
        id: 'apr_1',
        conversation: 'cnv_1',
        agent: 'builder',
        side: 'local',
        command: [],
        commands: [
          ['pytest', '-q'],
          ['make', 'check'],
        ],
        judged: 'unsure',
        cwd: '/repo',
        state: 'asked',
        defaultPrefix: ['./gradlew', 'test'],
      },
    ]);
  });

  it('drops a row it cannot read and keeps the rest, and refuses a refusal', () => {
    const rows = approvalsOf({
      code: 'OK',
      payload: {
        approvals: [
          { ...VIEW, command: [] },
          { ...VIEW, id: 7 },
          { ...VIEW, command: ['ls', 3] },
          { ...VIEW, id: 'apr_2' },
        ],
      },
    });
    expect(rows?.map((row) => row.id)).toEqual(['apr_2']);
    expect(approvalsOf({ code: 'OK', payload: { approvals: [] } })).toEqual([]);
    expect(approvalsOf({ code: 'BAD_REQUEST', said: 'no' })).toBeUndefined();
    expect(approvalsOf({ code: 'OK', payload: {} })).toBeUndefined();
  });

  it('reads an answer that started a turn, and one the conversation was too busy for', () => {
    expect(
      answeredOf({
        code: 'OK',
        payload: {
          id: 'apr_1',
          state: 'allowed',
          job: 'job-9',
          busy: false,
          note: null,
        },
      }),
    ).toEqual({ id: 'apr_1', state: 'allowed', job: 'job-9', busy: false });
    expect(
      answeredOf({
        code: 'OK',
        payload: {
          id: 'apr_1',
          state: 'denied',
          job: null,
          busy: true,
          note: 'a turn is running',
        },
      }),
    ).toEqual({
      id: 'apr_1',
      state: 'denied',
      busy: true,
      note: 'a turn is running',
    });
    expect(
      answeredOf({ code: 'BAD_REQUEST', said: 'already answered' }),
    ).toBeUndefined();
  });

  it('reads a revoke, and tells refused from not revoked', () => {
    expect(
      revokedOf({ code: 'OK', payload: { id: 'apr_1', revoked: true } }),
    ).toBe(true);
    expect(
      revokedOf({ code: 'OK', payload: { id: 'apr_1', revoked: false } }),
    ).toBe(false);
    expect(
      revokedOf({ code: 'NOT_FOUND', said: 'no approval' }),
    ).toBeUndefined();
  });

  it('hands a continued turn the events that outran the answer naming its job', () => {
    const holding = following(
      following(
        { ask: answeringApproval('apr_1', 'once'), progress: [], held: [] },
        { job: 'job-9', kind: 'started', agent: 'builder' },
      ),
      { job: 'job-other', kind: 'started', agent: 'other' },
    );
    const handed = handing(holding, 'job-9');
    expect(handed.job).toBe('job-9');
    expect(handed.held).toEqual([]);
    expect(handed.progress).toEqual([
      { kind: 'started', job: 'job-9', agent: 'builder' },
    ]);
  });

  it('says a busy answer still stands', () => {
    const said = describeAnswered({
      id: 'apr_1',
      state: 'allowed',
      busy: true,
      note: 'a turn is running',
    });
    expect(said).toContain('allowed');
    expect(said).toContain('a turn is running');
  });
});

describe('/approvals', () => {
  it('lists bare, and revokes one id', () => {
    expect(typed('/approvals')).toEqual({ kind: 'approvals' });
    expect(typed('/approvals  revoke  apr_1 ')).toEqual({
      kind: 'revoking',
      id: 'apr_1',
      ask: { type: APPROVAL_REVOKE, payload: { id: 'apr_1' } },
    });
  });

  it('gives the usage line for anything else after it, rather than a listing', () => {
    for (const line of [
      '/approvals revoke',
      '/approvals revoke a b',
      '/approvals list',
      '/approvals apr_1',
    ]) {
      expect(typed(line)).toEqual({
        kind: 'usage',
        command: APPROVALS_COMMAND,
      });
    }
    expect(describeUsage(APPROVALS_COMMAND)).toContain('revoke <id>');
  });

  it('does not swallow a command that merely starts with the same letters', () => {
    expect(typed('/approvalsx').kind).toBe('unknown');
  });

  it('shows each approval with the id revoke takes, and says when there are none', () => {
    const lines = describeApprovals([
      {
        id: 'apr_1',
        conversation: 'cnv_1',
        agent: 'builder',
        side: 'server',
        command: ['./gradlew', 'test', 'x'],
        cwd: '/repo',
        state: 'allowed',
        scope: 'project',
        prefix: ['./gradlew', 'test'],
        defaultPrefix: [],
      },
    ]);
    expect(lines[0]).toContain('apr_1');
    expect(lines[0]).toContain('server');
    expect(lines[0]).toContain('./gradlew test');
    expect(lines[0]).not.toContain(' x');
    expect(lines.join('\n')).toContain('revoke');
    expect(describeApprovals([])[0]).toContain('no commands');
    expect(describeRevoked('apr_1', true)).toContain('revoked');
    expect(describeRevoked('apr_1', false)).toContain('not revoked');
  });
});

/** One `DefinitionView`, as `OrchestrationFrames` renders a definition it resolved. */
function definition(over: Record<string, unknown>): unknown {
  return {
    name: 'spec_driven_coding',
    description: 'writes the spec, then the code',
    tier: 'project',
    served: true,
    withheld: null,
    stages: [
      { id: 'design', doneWhen: 'the design is agreed', mayReturnTo: [] },
    ],
    triggers: ['when somebody asks for a feature'],
    ...over,
  };
}

/** One `RunView`, in the fields the two run screens read off it. */
function run(over: Record<string, unknown>): unknown {
  return {
    id: 'orc_1',
    definition: 'spec_driven_coding',
    tier: 'project',
    project: 'plowshare',
    state: 'running',
    parent: null,
    depth: 0,
    waitingFor: null,
    createdAt: '2026-09-23T09:00:00Z',
    endedAt: null,
    ...over,
  };
}

describe('/orchestrations, which is what a person can start', () => {
  it('lists bare and names one definition when given a name', () => {
    expect(typed(ORCHESTRATIONS_COMMAND, 'plowshare')).toEqual({
      kind: 'orchestrations',
      ask: listingDefinitions('plowshare'),
    });
    expect(
      typed(`${ORCHESTRATIONS_COMMAND}  spec_driven_coding `, 'plowshare'),
    ).toEqual({
      kind: 'orchestration',
      name: 'spec_driven_coding',
      ask: listingDefinitions('plowshare'),
    });
  });

  it('carries the project when there is one and nothing when there is not', () => {
    // `RequestedProjectId.forListing` degrades an absent project to the boot
    // set, so an empty payload is how the global tier is asked for — never
    // `{ project: null }`.
    expect(listingDefinitions().payload).toEqual({});
    expect(listingDefinitions('plowshare').payload).toEqual({
      project: 'plowshare',
    });
  });

  it('gives the usage line for more than one name, rather than guessing which', () => {
    expect(typed(`${ORCHESTRATIONS_COMMAND} a b`)).toEqual({
      kind: 'usage',
      command: ORCHESTRATIONS_COMMAND,
    });
    expect(describeUsage(ORCHESTRATIONS_COMMAND)).toContain(
      ORCHESTRATIONS_COMMAND,
    );
  });

  it('does not swallow a command that merely starts with the same letters', () => {
    expect(typed('/orchestrationsx').kind).toBe('unknown');
  });

  it('reads a definition, and a refused file as a row with its reason', () => {
    const said: Answer = {
      code: OK,
      payload: {
        definitions: [
          definition({}),
          definition({
            name: 'broken',
            description: null,
            tier: null,
            stages: [],
            triggers: [],
            served: false,
            withheld: 'stages: expected a list',
          }),
        ],
      },
    };
    expect(definitionsOf(said)).toEqual([
      {
        name: 'spec_driven_coding',
        description: 'writes the spec, then the code',
        tier: 'project',
        served: true,
        withheld: '',
        stages: [{ id: 'design', doneWhen: 'the design is agreed' }],
        triggers: ['when somebody asks for a feature'],
      },
      {
        name: 'broken',
        description: '',
        tier: '',
        served: false,
        withheld: 'stages: expected a list',
        stages: [],
        triggers: [],
      },
    ]);
  });

  it('answers nothing at all for a refusal, rather than an empty list', () => {
    // "there are none" and "the server would not say" are different facts,
    // and only the first is one a screen may state.
    expect(
      definitionsOf({ code: 'BAD_REQUEST', said: 'no such project' }),
    ).toBeUndefined();
    expect(definitionsOf({ code: OK, payload: {} })).toBeUndefined();
    expect(definitionsOf({ code: OK, payload: { definitions: [] } })).toEqual(
      [],
    );
  });
});

describe('/watch', () => {
  it('watches the run named, or the newest live one', () => {
    expect(typed('/watch')).toEqual({ kind: 'watch' });
    expect(typed('/watch orc_1')).toEqual({ kind: 'watch', run: 'orc_1' });
    expect(typed('/watch orc_1 orc_2')).toEqual({
      kind: 'usage',
      command: '/watch',
    });
  });
});

describe('/trajectory and /log, which open the explorer', () => {
  it('reads /trajectory and /log, each with an optional conversation', () => {
    expect(typed('/trajectory')).toEqual({
      kind: 'trajectory',
      view: 'trajectory',
    });
    expect(typed('/trajectory cnv_2')).toEqual({
      kind: 'trajectory',
      view: 'trajectory',
      conversation: 'cnv_2',
    });
    expect(typed('/log')).toEqual({ kind: 'trajectory', view: 'log' });
    expect(typed('/log cnv_2')).toEqual({
      kind: 'trajectory',
      view: 'log',
      conversation: 'cnv_2',
    });
  });
});

describe('/runs, which is your orchestration runs and not your jobs', () => {
  it('lists bare and reads one run when given an id', () => {
    expect(typed(RUNS_COMMAND)).toEqual({ kind: 'runs', ask: listingRuns() });
    expect(typed(`${RUNS_COMMAND}  orc_1 `)).toEqual({
      kind: 'run',
      id: 'orc_1',
      ask: readingRun('orc_1'),
    });
  });

  it('gives the usage line for more than one id', () => {
    expect(typed(`${RUNS_COMMAND} orc_1 orc_2`)).toEqual({
      kind: 'usage',
      command: RUNS_COMMAND,
    });
    expect(describeUsage(RUNS_COMMAND)).toContain(RUNS_COMMAND);
  });

  it('does not swallow a command that merely starts with the same letters', () => {
    expect(typed('/runsx').kind).toBe('unknown');
  });

  it('reads the runs a listing named, parent included', () => {
    const said: Answer = {
      code: OK,
      payload: {
        orchestrations: [
          run({}),
          run({
            id: 'orc_2',
            parent: 'orc_1',
            depth: 1,
            state: 'waiting',
            waitingFor: 'orc_3',
            endedAt: null,
          }),
        ],
      },
    };
    expect(runsOf(said)).toEqual([
      {
        id: 'orc_1',
        definition: 'spec_driven_coding',
        tier: 'project',
        project: 'plowshare',
        state: 'running',
        depth: 0,
        createdAt: '2026-09-23T09:00:00Z',
      },
      {
        id: 'orc_2',
        definition: 'spec_driven_coding',
        tier: 'project',
        project: 'plowshare',
        state: 'waiting',
        depth: 1,
        parent: 'orc_1',
        waitingFor: 'orc_3',
        createdAt: '2026-09-23T09:00:00Z',
      },
    ]);
  });

  it("reads the conductor's own conversation, and leaves it out where there is none", () => {
    expect(
      runsOf({
        code: OK,
        payload: { orchestrations: [run({ conductorConversation: 'cnv_7' })] },
      })?.[0]?.conductorConversation,
    ).toBe('cnv_7');
    expect(
      runsOf({ code: OK, payload: { orchestrations: [run({})] } })?.[0],
    ).not.toHaveProperty('conductorConversation');
  });

  it('reads the agent that started a run, and leaves it out where there is none', () => {
    // The question dialog says who else has a root's question: the caller the server delivers it to.
    expect(
      runsOf({
        code: OK,
        payload: { orchestrations: [run({ callerAgent: 'sophron' })] },
      })?.[0]?.callerAgent,
    ).toBe('sophron');
    expect(
      runsOf({ code: OK, payload: { orchestrations: [run({})] } })?.[0],
    ).not.toHaveProperty('callerAgent');
  });

  it('reads the outcome of a run that ended, and the cap one is stopped on', () => {
    // `OrchestrationRecord` sets exactly one of the two per ending, and the
    // cap only while a run is asking for it to be raised. A screen that
    // dropped them would say a run ended and never what it ended as.
    const ended = runsOf({
      code: OK,
      payload: {
        orchestrations: [
          run({
            state: 'finished',
            result: 'the suite is green',
            endedAt: '2026-09-23T10:00:00Z',
          }),
          run({
            id: 'orc_2',
            state: 'failed',
            failure: 'the conductor ran out of turns',
          }),
          run({ id: 'orc_3', state: 'asking', pendingCap: 'turn_cap' }),
        ],
      },
    });
    expect(ended?.[0]?.result).toBe('the suite is green');
    expect(ended?.[0]?.endedAt).toBe('2026-09-23T10:00:00Z');
    expect(ended?.[1]?.failure).toBe('the conductor ran out of turns');
    expect(ended?.[2]?.pendingCap).toBe('turn_cap');
    // And a live run carries none of the three, as an absent key rather than
    // an empty string: the screen shows a line only where there is one.
    expect(
      runsOf({ code: OK, payload: { orchestrations: [run({})] } })?.[0],
    ).not.toHaveProperty('result');
  });

  it('drops a row with no state, which would render as separators around nothing', () => {
    // The id and the state are what every line of both screens is built out
    // of. A row missing either reads as a broken client, not as a gap.
    expect(
      runsOf({
        code: OK,
        payload: {
          orchestrations: [run({}), { id: 'orc_9' }, { state: 'running' }],
        },
      })?.map((row) => row.id),
    ).toEqual(['orc_1']);
    expect(
      runStatusOf({ code: OK, payload: { orchestration: { id: 'orc_9' } } }),
    ).toBeUndefined();
  });

  it("surfaces the server's refusal rather than an empty list", () => {
    // `orchestration.list` requires a handle: a socket with no account is
    // refused, and a person must be told that rather than that they have no
    // runs.
    expect(
      runsOf({
        code: 'BAD_REQUEST',
        said: 'orchestration.list needs an account',
      }),
    ).toBeUndefined();
    expect(runsOf({ code: OK, payload: { orchestrations: [] } })).toEqual([]);
  });

  it("reads one run's stages, messages and children", () => {
    const said: Answer = {
      code: OK,
      payload: {
        orchestration: run({}),
        todos: [
          {
            id: 'td_1',
            text: 'design it',
            status: 'done',
            summary: 'agreed',
            stage: 'design',
            locked: false,
            parent: null,
            position: 0,
          },
        ],
        messages: [
          {
            id: 'msg_1',
            kind: 'question',
            text: 'which database?',
            author: 'conductor',
            createdAt: '2026-09-23T09:01:00Z',
          },
        ],
        children: [{ id: 'orc_2', state: 'finished' }],
      },
    };
    const status = runStatusOf(said);
    expect(status?.run.id).toBe('orc_1');
    expect(status?.stages).toEqual([
      {
        id: 'td_1',
        text: 'design it',
        status: 'done',
        summary: 'agreed',
        stage: 'design',
      },
    ]);
    expect(status?.messages).toEqual([
      { kind: 'question', text: 'which database?', author: 'conductor' },
    ]);
    expect(status?.children).toEqual([{ id: 'orc_2', state: 'finished' }]);
  });

  it('answers nothing for a status this build cannot read, and for a refusal', () => {
    expect(
      runStatusOf({ code: 'NOT_FOUND', said: 'no orchestration with that id' }),
    ).toBeUndefined();
    expect(
      runStatusOf({
        code: OK,
        payload: { todos: [], messages: [], children: [] },
      }),
    ).toBeUndefined();
    // A run with nothing under it yet is a run, not an unreadable answer.
    expect(
      runStatusOf({ code: OK, payload: { orchestration: run({}) } }),
    ).toEqual({
      run: {
        id: 'orc_1',
        definition: 'spec_driven_coding',
        tier: 'project',
        project: 'plowshare',
        state: 'running',
        depth: 0,
        createdAt: '2026-09-23T09:00:00Z',
      },
      stages: [],
      messages: [],
      children: [],
    });
  });
});

describe('/agents <name>, which is the one place tools are listed', () => {
  it('reads a name after the roster command, and lists when given none', () => {
    expect(typed(AGENTS_COMMAND, 'plowshare')).toEqual({
      kind: 'agents',
      ask: listingAgents('plowshare'),
    });
    expect(typed(`${AGENTS_COMMAND}  close_reader `, 'plowshare')).toEqual({
      kind: 'agent',
      name: 'close_reader',
      ask: listingAgents('plowshare'),
    });
    expect(typed(`${AGENTS_COMMAND} a b`)).toEqual({
      kind: 'usage',
      command: AGENTS_COMMAND,
    });
  });

  it('does not swallow a command that merely starts with the same letters', () => {
    expect(typed('/agentsx').kind).toBe('unknown');
  });

  it('keeps the four declaration lists the roster used to drop', () => {
    // They are read off `agent.list` and nowhere else: there is no
    // server-wide tool registry on the wire, so this frame is the only place
    // a person can be shown what an agent holds.
    const rows = agents({
      code: OK,
      payload: [
        view({
          tools: ['read_file'],
          calls: ['close_reader'],
          scopes: ['workspace:read'],
          orchestrations: ['spec_driven_coding'],
        }),
      ],
    });
    expect(rows?.[0]?.tools).toEqual(['read_file']);
    expect(rows?.[0]?.calls).toEqual(['close_reader']);
    expect(rows?.[0]?.scopes).toEqual(['workspace:read']);
    expect(rows?.[0]?.orchestrations).toEqual(['spec_driven_coding']);
  });

  it('reads a server too old to send orchestration grants as granting none', () => {
    const rows = agents({
      code: OK,
      payload: [view({ orchestrations: undefined })],
    });
    expect(rows?.[0]?.orchestrations).toEqual([]);
  });
});

describe('answering a run, which the caller bot could not be relied on to do', () => {
  it('splits the id off and carries the answer exactly as it was typed', () => {
    const line = typed(
      `${ANSWER_COMMAND} orc_1  Python 3.10+,  saves in ./saves`,
    );

    expect(line).toEqual({
      kind: 'answerRun',
      id: 'orc_1',
      answer: 'Python 3.10+,  saves in ./saves',
      ask: answeringRun('orc_1', 'Python 3.10+,  saves in ./saves'),
    });
    // The builder's own shape, since the frame is what the server binds.
    expect(answeringRun('orc_1', 'hi')).toEqual({
      type: ORCHESTRATION_ANSWER,
      payload: { id: 'orc_1', answer: 'hi' },
    });
  });

  it('needs both halves, and says the shape rather than sending a blank', () => {
    // The server refuses a blank answer; being told the shape is more use
    // than being handed that refusal.
    expect(typed(`${ANSWER_COMMAND} orc_1`)).toEqual({
      kind: 'usage',
      command: ANSWER_COMMAND,
    });
    expect(typed(`${ANSWER_COMMAND} orc_1    `)).toEqual({
      kind: 'usage',
      command: ANSWER_COMMAND,
    });
    expect(describeUsage(ANSWER_COMMAND)).toContain(RUNS_COMMAND);
  });

  it('cancels exactly one run, and refuses a line that names two', () => {
    expect(typed(`${CANCEL_COMMAND} orc_1`)).toEqual({
      kind: 'cancelRun',
      id: 'orc_1',
      ask: cancellingRun('orc_1'),
    });
    expect(cancellingRun('orc_1')).toEqual({
      type: ORCHESTRATION_CANCEL,
      payload: { id: 'orc_1' },
    });
    expect(typed(CANCEL_COMMAND)).toEqual({
      kind: 'usage',
      command: CANCEL_COMMAND,
    });
    expect(typed(`${CANCEL_COMMAND} orc_1 orc_2`)).toEqual({
      kind: 'usage',
      command: CANCEL_COMMAND,
    });
    // The cascade is said before somebody types it, not after.
    expect(describeUsage(CANCEL_COMMAND)).toContain('every run it started');
  });

  it('reads the state back off either frame, since the server sends one shape', () => {
    const ok = (state: string): Answer => ({
      code: 'OK',
      payload: { id: 'orc_1', state },
    });

    expect(settledRun(ok('running'))).toEqual({
      id: 'orc_1',
      state: 'running',
    });
    expect(settledRun(ok('cancelled'))).toEqual({
      id: 'orc_1',
      state: 'cancelled',
    });
    // An answer can unblock a run into waiting on a child it had already
    // started, which is why the state travels rather than a bare "done".
    expect(describeSettled({ id: 'orc_1', state: 'waiting' }, 'answered')).toBe(
      'orc_1 answered — it is now waiting',
    );
    expect(
      settledRun({ code: 'BAD_REQUEST', said: 'is not waiting for an answer' }),
    ).toBeUndefined();
  });
});

describe('a question with options', () => {
  const status = (messages: unknown[]) =>
    runStatusOf({
      code: 'OK',
      payload: {
        orchestration: {
          id: 'orc_1',
          definition: 'd',
          tier: 'project',
          project: 'p',
          state: 'asking',
          depth: 0,
          createdAt: '2026-09-30T09:00:00Z',
        },
        todos: [],
        messages,
        children: [],
      },
    });

  it("reads the last question's structure, options and all", () => {
    const read = status([
      { kind: 'question', author: 'c', text: 'old', structure: null },
      {
        kind: 'question',
        author: 'c',
        text: 'First: …',
        structure: {
          lead: 'First:',
          questions: [
            {
              header: 'Store',
              question: 'Which database?',
              multi: false,
              options: [
                { label: 'Postgres', description: 'p' },
                { label: 'SQLite', description: 's', preview: 'db.sqlite' },
              ],
            },
          ],
        },
      },
    ]);
    expect(read === undefined ? undefined : structureOf(read)).toEqual({
      lead: 'First:',
      questions: [
        {
          header: 'Store',
          question: 'Which database?',
          multi: false,
          options: [
            { label: 'Postgres', description: 'p' },
            { label: 'SQLite', description: 's', preview: 'db.sqlite' },
          ],
        },
      ],
    });
  });

  it('reads a question with no structure, or one it cannot read, as words', () => {
    const plain = status([{ kind: 'question', author: 'c', text: 'Which?' }]);
    expect(plain === undefined ? 'unread' : structureOf(plain)).toBeUndefined();
    const broken = status([
      {
        kind: 'question',
        author: 'c',
        text: 'Which?',
        structure: { lead: 'x', questions: [{ header: 'H' }] },
      },
    ]);
    expect(
      broken === undefined ? 'unread' : structureOf(broken),
    ).toBeUndefined();
  });

  it('reads the draft an install question carries beside its questions, whole or not at all', () => {
    const questions = [
      {
        header: 'Install',
        question: 'Install x?',
        multi: false,
        options: [
          { label: 'Install', description: 'i' },
          { label: 'Leave it', description: 'l' },
        ],
      },
    ];
    const read = (extra: Record<string, unknown>) => {
      const got = status([
        {
          kind: 'question',
          author: 'c',
          text: 'The draft …',
          structure: { lead: 'The draft', questions, ...extra },
        },
      ]);
      return got === undefined ? undefined : structureOf(got);
    };
    expect(
      read({
        name: 'x',
        path: 'artifacts/x.md',
        text: '---\nname: x\n---\nDo it.',
        sha256: 'ab',
      })?.draft,
    ).toEqual({
      name: 'x',
      path: 'artifacts/x.md',
      text: '---\nname: x\n---\nDo it.',
    });
    // A QUESTION STAYS READABLE WITHOUT IT: only the draft is dropped, never its options.
    const missing = read({ name: 'x', text: 'Do it.' });
    expect(missing?.questions).toHaveLength(1);
    expect(missing).not.toHaveProperty('draft');
    expect(read({ name: 'x', path: 7, text: 'Do it.' })).not.toHaveProperty(
      'draft',
    );
    expect(
      read({ name: 'x', path: 'artifacts/x.md', text: null }),
    ).not.toHaveProperty('draft');
    expect(read({})).not.toHaveProperty('draft');
  });

  it('answers by choices, with the words beside them as answer', () => {
    expect(
      answeringRunWith(
        'orc_1',
        [{ header: 'Store', chosen: ['SQLite'] }],
        'thanks',
      ),
    ).toEqual({
      type: 'orchestration.answer',
      payload: {
        id: 'orc_1',
        choices: [{ header: 'Store', chosen: ['SQLite'] }],
        answer: 'thanks',
      },
    });
    expect(
      answeringRunWith('orc_1', [
        { header: 'Store', chosen: [], other: 'MySQL' },
      ]),
    ).toEqual({
      type: 'orchestration.answer',
      payload: {
        id: 'orc_1',
        choices: [{ header: 'Store', chosen: [], other: 'MySQL' }],
      },
    });
  });
});

describe('answering without an id, which the runs waiting on a person make possible', () => {
  it('shows what is waiting for a bare /answer, rather than the shape of the command', () => {
    expect(typed(ANSWER_COMMAND)).toEqual({ kind: 'waitingRuns' });
    expect(typed(ANSWER_COMMAND, undefined, ['orc_1'])).toEqual({
      kind: 'waitingRuns',
    });
  });

  it('sends the whole line to the one run that is waiting', () => {
    expect(
      typed(`${ANSWER_COMMAND} yes, use  PostgreSQL`, undefined, ['orc_1']),
    ).toEqual({
      kind: 'answerRun',
      id: 'orc_1',
      answer: 'yes, use  PostgreSQL',
      ask: answeringRun('orc_1', 'yes, use  PostgreSQL'),
    });
  });

  it('holds an answer back when it cannot say which run it is for', () => {
    // Two runs waiting, or none this client has seen: guessing would put a
    // person's words in front of a conductor they were never meant for.
    expect(
      typed(`${ANSWER_COMMAND} yes`, undefined, ['orc_1', 'orc_2']),
    ).toEqual({ kind: 'waitingRuns', held: true });
    expect(typed(`${ANSWER_COMMAND} yes`, undefined, [])).toEqual({
      kind: 'waitingRuns',
      held: true,
    });
  });

  it('reads a first word that is a run id as the id, waiting or not', () => {
    // A run that started asking since the last check is not in the list
    // yet, and its id must still reach it rather than become answer text
    // for the run that is.
    expect(typed(`${ANSWER_COMMAND} orc_2 no`, undefined, ['orc_1'])).toEqual({
      kind: 'answerRun',
      id: 'orc_2',
      answer: 'no',
      ask: answeringRun('orc_2', 'no'),
    });
  });
});

describe('/always, which lets this project run commands without asking', () => {
  it('turns it on bare, off with off, and says the shape for anything else', () => {
    expect(typed(ALWAYS_COMMAND)).toEqual({ kind: 'always', on: true });
    expect(typed(`${ALWAYS_COMMAND} off`)).toEqual({
      kind: 'always',
      on: false,
    });
    expect(typed(`${ALWAYS_COMMAND} sometimes`)).toEqual({
      kind: 'usage',
      command: ALWAYS_COMMAND,
    });
  });
});

describe('/cap, which shows and sets the caps the person controls', () => {
  it('reads each form', () => {
    expect(typed(CAP_COMMAND)).toEqual({ kind: 'cap' });
    expect(typed(`${CAP_COMMAND} steps 40`)).toEqual({
      kind: 'capSet',
      key: 'steps',
      value: 40,
    });
    expect(typed(`${CAP_COMMAND} budget 600`)).toEqual({
      kind: 'capSet',
      key: 'budget',
      value: 600,
    });
    expect(typed(`${CAP_COMMAND} auto 3`)).toEqual({
      kind: 'capSet',
      key: 'auto-continue',
      value: 3,
    });
    expect(typed(`${ALWAYS_COMMAND} caps`)).toEqual({
      kind: 'capSet',
      key: 'auto-continue',
      value: 3,
    });
    // V69: the time cap, in minutes, and how many failed checks a run takes before it asks.
    expect(typed(`${CAP_COMMAND} time 90`)).toEqual({
      kind: 'capSet',
      key: 'time',
      value: 90,
    });
    expect(typed(`${CAP_COMMAND} checks 3`)).toEqual({
      kind: 'capSet',
      key: 'failed-checks',
      value: 3,
    });
  });

  it('refuses anything else with its usage', () => {
    for (const bad of [
      'steps',
      'steps many',
      'turns 4',
      'auto 3 4',
      'time 90m',
      'checks',
    ]) {
      expect(typed(`${CAP_COMMAND} ${bad}`)).toEqual({
        kind: 'usage',
        command: CAP_COMMAND,
      });
    }
  });
});

describe('a command approval waiting on a person, answered from anywhere', () => {
  const asked = (id: string, conversation: string): Approval => ({
    id,
    conversation,
    agent: 'code_implementation',
    side: 'local',
    command: ['pytest', '-q', 'tests/test_init_package.py'],
    cwd: '/Users/example/game_test',
    state: 'asked',
    defaultPrefix: ['pytest', '-q'],
  });

  it('asks for every open question on the account', () => {
    expect(listingMyApprovals()).toEqual({
      type: APPROVAL_LIST,
      payload: { mine: true },
    });
  });

  it('waits on each asked one, except the one this screen is already asking', () => {
    // The conversation on screen shows its own approval inline, with the keys to answer it.
    const waiting = approvalsWaiting(
      [
        asked('apr_1', 'cnv_conductor'),
        asked('apr_2', 'cnv_here'),
        { ...asked('apr_3', 'cnv_conductor'), state: 'allowed' },
      ],
      'cnv_here',
    );

    expect(waiting).toEqual([
      {
        id: 'apr_1',
        definition: 'code_implementation',
        kind: 'approval',
        question:
          'run `pytest -q tests/test_init_package.py` in /Users/example/game_test (local)',
        approval: asked('apr_1', 'cnv_conductor'),
      },
    ]);
  });

  it('waits on an acceptance set as one item, however many commands it names (V67)', () => {
    const set = {
      ...asked('apr_9', 'cnv_conductor'),
      agent: 'implement_specification',
      command: [],
      commands: Array.from({ length: 15 }, (_, n) => [
        'pytest',
        '-q',
        `tests/test_${n}.py`,
      ]),
    };

    const waiting = approvalsWaiting([set]);

    expect(waiting).toHaveLength(1);
    expect(waiting[0]).toMatchObject({
      id: 'apr_9',
      kind: 'approval',
      question:
        'run 15 acceptance commands at its acceptance stage, in /Users/example/game_test (local)',
    });
    expect(dialogKindOf(waiting[0] as Waiting)).toBe('approval');
  });

  it('answers one by its id and a decision', () => {
    expect(typed(`${ANSWER_COMMAND} apr_1 once`)).toEqual({
      kind: 'answerApproval',
      id: 'apr_1',
      decision: 'once',
      ask: answeringApproval('apr_1', 'once'),
    });
    expect(typed(`${ANSWER_COMMAND} apr_1 no`)).toMatchObject({
      decision: 'deny',
    });
    expect(typed(`${ANSWER_COMMAND} apr_1 yes`)).toMatchObject({
      decision: 'once',
    });
    expect(typed(`${ANSWER_COMMAND} apr_1 conversation`)).toMatchObject({
      decision: 'conversation',
    });
    // A decision that is not one of them is the shape, not a guess.
    expect(typed(`${ANSWER_COMMAND} apr_1 sure thing`)).toEqual({
      kind: 'usage',
      command: ANSWER_COMMAND,
    });
  });

  it('takes the decision alone when the one thing waiting is an approval', () => {
    expect(typed(`${ANSWER_COMMAND} deny`, undefined, ['apr_1'])).toEqual({
      kind: 'answerApproval',
      id: 'apr_1',
      decision: 'deny',
      ask: answeringApproval('apr_1', 'deny'),
    });
    expect(
      typed(`${ANSWER_COMMAND} use postgres`, undefined, ['apr_1']),
    ).toEqual({ kind: 'waitingRuns', held: true });
  });
});

describe('the runs waiting on a person, as a background check keeps them', () => {
  const run = (id: string, extra: Partial<Run> = {}): Run => ({
    id,
    definition: 'implement_specification',
    tier: 'project',
    state: 'asking',
    depth: 0,
    createdAt: '2026-09-25T01:00:00Z',
    ...extra,
  });

  it('asks for the asking runs alone', () => {
    expect(listingAsking()).toEqual({
      type: ORCHESTRATION_LIST,
      payload: { state: 'asking', limit: 20 },
    });
  });

  it('reads the latest question a run asked, and nothing for a run that asked none', () => {
    const status = (messages: RunStatus['messages']): RunStatus => ({
      run: run('orc_1'),
      stages: [],
      messages,
      children: [],
    });

    expect(
      questionOf(
        status([
          { kind: 'question', author: 'conductor', text: 'Which database?' },
          { kind: 'answer', author: 'enzo', text: 'PostgreSQL' },
          { kind: 'question', author: 'conductor', text: 'Which version?' },
        ]),
      ),
    ).toBe('Which version?');
    expect(questionOf(status([]))).toBeUndefined();
  });

  it('keeps what it already read and names only the new runs to read', () => {
    const before: Waiting[] = [
      { id: 'orc_1', definition: 'a', question: 'Which database?' },
    ];

    const after = waitingAfter(before, [
      run('orc_1'),
      run('orc_2', { pendingCap: 'turn_cap' }),
    ]);

    expect(after.now).toEqual([
      { id: 'orc_1', definition: 'a', question: 'Which database?' },
      {
        id: 'orc_2',
        definition: 'implement_specification',
        pendingCap: 'turn_cap',
      },
    ]);
    expect(after.unread).toEqual(['orc_2']);
  });

  it("leaves out a child run, whose question is its conductor's to answer", () => {
    // Measured: a code_implementation child asked its implement_specification
    // parent to run the tests. The listing names it (it is on this account),
    // the terminal announced it to the person, and the parent answered it
    // itself a moment later — so the person was offered a question that was
    // never theirs, and found "nothing is waiting" when they answered it.
    const after = waitingAfter(
      [],
      [run('orc_root'), run('orc_child', { parent: 'orc_root', depth: 1 })],
    );

    expect(after.now.map((waiting) => waiting.id)).toEqual(['orc_root']);
    expect(after.unread).toEqual(['orc_root']);
  });

  it('keeps a child asking the person whether it goes on after it stopped making progress', () => {
    // Spec 2026-09-28: a phase that would have failed `stuck` asks the person instead,
    // and the server delivers that question to nobody else — a parent conductor cannot
    // judge it, and a model's answer is refused. So this one is the person's to see.
    const after = waitingAfter(
      [],
      [
        run('orc_root'),
        run('orc_child', { parent: 'orc_root', depth: 1, pendingCap: 'stuck' }),
      ],
    );

    expect(after.now.map((waiting) => waiting.id)).toEqual([
      'orc_root',
      'orc_child',
    ]);
    expect(after.now[1]?.pendingCap).toBe('stuck');
    expect(after.unread).toEqual(['orc_root', 'orc_child']);
  });

  it('keeps a run asking the person whether its acceptance commands stand, as it keeps stuck', () => {
    // V65: only the person may answer it, so like `stuck` it is theirs wherever the run sits.
    const after = waitingAfter(
      [],
      [
        run('orc_root', { pendingCap: 'uncovered' }),
        run('orc_child', {
          parent: 'orc_root',
          depth: 1,
          pendingCap: 'uncovered',
        }),
      ],
    );

    expect(after.now.map((waiting) => waiting.id)).toEqual([
      'orc_root',
      'orc_child',
    ]);
    expect(after.now[0]?.pendingCap).toBe('uncovered');
    expect(PERSON_ONLY_KINDS).toEqual([
      'stuck',
      'uncovered',
      'check_failures',
      'install',
      'concerns',
      'product_check',
    ]);
  });

  it("keeps a phase asking the person to check its product or about its checker's concerns", () => {
    // V77: both are the person's alone, wherever in the tree the run sits.
    const after = waitingAfter(
      [],
      [
        run('orc_root', { pendingCap: 'product_check' }),
        run('orc_child', {
          parent: 'orc_root',
          depth: 1,
          pendingCap: 'concerns',
        }),
      ],
    );

    expect(after.now.map((waiting) => waiting.id)).toEqual([
      'orc_root',
      'orc_child',
    ]);
  });

  it('offers a child run asking about its cap, as the person may answer it first', () => {
    // Spec 2026-09-29 §2: a cap question goes to the parent model and the person at once, and
    // the first answer settles it — so unlike an ordinary child question, it is theirs too.
    const asking: Run[] = [
      run('orc_2', {
        definition: 'code_implementation',
        parent: 'orc_1',
        depth: 1,
        pendingCap: 'turn_cap',
      }),
      run('orc_3', {
        definition: 'code_implementation',
        parent: 'orc_1',
        depth: 1,
      }),
      run('orc_4', {
        definition: 'code_implementation',
        parent: 'orc_1',
        depth: 1,
        pendingCap: 'call_budget',
      }),
    ];
    expect(waitingAfter([], asking).now.map((each) => each.id)).toEqual([
      'orc_2',
      'orc_4',
    ]);
    expect(CAP_KINDS).toEqual(['turn_cap', 'call_budget', 'time_cap']);
    // V69: a phase past its time cap asks both too; one whose check kept failing, the person.
    expect(
      waitingAfter(
        [],
        [
          run('orc_5', {
            definition: 'code_implementation',
            parent: 'orc_1',
            depth: 1,
            pendingCap: 'time_cap',
          }),
          run('orc_6', {
            definition: 'code_implementation',
            parent: 'orc_1',
            depth: 1,
            pendingCap: 'check_failures',
          }),
        ],
      ).now.map((each) => each.id),
    ).toEqual(['orc_5', 'orc_6']);
  });

  it('carries the parent and the caller a dialog needs onto what is waiting', () => {
    const asking: Run[] = [
      run('orc_1', { callerAgent: 'sophron' }),
      run('orc_2', {
        definition: 'code_implementation',
        parent: 'orc_1',
        depth: 1,
        pendingCap: 'stuck',
        callerAgent: 'implement_specification',
      }),
    ];
    expect(waitingAfter([], asking).now).toEqual([
      {
        id: 'orc_1',
        definition: 'implement_specification',
        callerAgent: 'sophron',
      },
      {
        id: 'orc_2',
        definition: 'code_implementation',
        pendingCap: 'stuck',
        parent: 'orc_1',
        callerAgent: 'implement_specification',
      },
    ]);
  });

  it("opens a dialog for a cap, a stuck run, a root's own question and a run's approval, and for nothing else", () => {
    expect(
      dialogKindOf({
        id: 'orc_2',
        definition: 'a',
        pendingCap: 'turn_cap',
        parent: 'orc_1',
      }),
    ).toBe('cap');
    expect(
      dialogKindOf({ id: 'orc_2', definition: 'a', pendingCap: 'call_budget' }),
    ).toBe('cap');
    expect(
      dialogKindOf({
        id: 'orc_2',
        definition: 'a',
        pendingCap: 'stuck',
        parent: 'orc_1',
      }),
    ).toBe('stuck');
    expect(
      dialogKindOf({ id: 'orc_1', definition: 'a', pendingCap: 'uncovered' }),
    ).toBe('accept');
    expect(
      dialogKindOf({
        id: 'orc_1',
        definition: 'a',
        pendingCap: 'product_check',
      }),
    ).toBe('accept');
    expect(
      dialogKindOf({
        id: 'orc_2',
        definition: 'a',
        pendingCap: 'concerns',
        parent: 'orc_1',
      }),
    ).toBe('accept');
    expect(
      dialogKindOf({
        id: 'orc_2',
        definition: 'a',
        pendingCap: 'time_cap',
        parent: 'orc_1',
      }),
    ).toBe('cap');
    expect(
      dialogKindOf({
        id: 'orc_2',
        definition: 'a',
        pendingCap: 'check_failures',
        parent: 'orc_1',
      }),
    ).toBe('checks');
    expect(
      dialogKindOf({
        id: 'orc_1',
        definition: 'a',
        question: 'Which database?',
      }),
    ).toBe('question');
    // A phase's own question is its conductor's to answer.
    expect(
      dialogKindOf({
        id: 'orc_2',
        definition: 'a',
        question: 'Which?',
        parent: 'orc_1',
      }),
    ).toBeUndefined();
    // An approval opens the approval dialog once its row is in hand; a stall takes a look or a
    // cancel.
    expect(
      dialogKindOf({
        id: 'apr_1',
        definition: 'coder',
        kind: 'approval',
        question: 'run `ls`',
      }),
    ).toBeUndefined();
    expect(
      dialogKindOf({
        id: 'apr_1',
        definition: 'coder',
        kind: 'approval',
        question: 'run `ls`',
        approval: {
          id: 'apr_1',
          conversation: 'cnv_c',
          agent: 'coder',
          side: 'local',
          command: ['ls'],
          cwd: '/repo',
          state: 'asked',
          defaultPrefix: ['ls'],
        },
      }),
    ).toBe('approval');
    expect(
      dialogKindOf({ id: 'orc_9', definition: 'a', kind: 'stalled' }),
    ).toBeUndefined();
  });

  it('drops a run that has stopped asking', () => {
    const before: Waiting[] = [
      { id: 'orc_1', definition: 'a', question: 'Which database?' },
    ];

    expect(waitingAfter(before, [])).toEqual({ now: [], unread: [] });
  });

  it('reads a run again when a push says it has asked since', () => {
    // Answered and asking a second question between two checks: the id is
    // the same, and the question it holds is not.
    const before: Waiting[] = [
      { id: 'orc_1', definition: 'a', question: 'Which database?' },
    ];

    const after = waitingAfter(before, [run('orc_1')], new Set(['orc_1']));

    expect(after.now).toEqual([
      { id: 'orc_1', definition: 'implement_specification' },
    ]);
    expect(after.unread).toEqual(['orc_1']);
  });
});

describe('an install question', () => {
  it('is a question only the person may answer, opened as a question', () => {
    expect(PERSON_ONLY_KINDS).toContain('install');
    expect(
      dialogKindOf({
        id: 'orc_1',
        definition: 'design_orchestration',
        pendingCap: 'install',
      }),
    ).toBe('question');
    expect(
      dialogKindOf({
        id: 'orc_2',
        definition: 'design_orchestration',
        pendingCap: 'install',
        parent: 'orc_1',
      }),
    ).toBe('question');
  });
});

describe('a stalled run, listed as running and read off the wire', () => {
  it(
    'asks for running runs, since the server files a stall under running rather than a' +
      ' state of its own',
    () => {
      expect(listingStalled()).toEqual({
        type: ORCHESTRATION_LIST,
        payload: { state: 'running', limit: 20 },
      });
    },
  );

  it('reads stalledSince off a listed run, and nothing for a run the store has not marked', () => {
    const said: Answer = {
      code: OK,
      payload: {
        orchestrations: [
          run({ stalledSince: '2026-09-27T09:00:00Z' }),
          run({ id: 'orc_2', stalledSince: null }),
        ],
      },
    };
    expect(runsOf(said)?.[0]?.stalledSince).toBe('2026-09-27T09:00:00Z');
    expect(runsOf(said)?.[1]).not.toHaveProperty('stalledSince');
  });
});

describe('a stalled run, turned into what is waiting on a person', () => {
  const run = (id: string, extra: Partial<Run> = {}): Run => ({
    id,
    definition: 'implement_specification',
    tier: 'project',
    state: 'running',
    depth: 0,
    createdAt: '2026-09-27T08:00:00Z',
    ...extra,
  });

  it('keeps a run that carries a mark and drops one that does not, a child included', () => {
    // Unlike `waitingAfter`, which leaves a child out because its question is its
    // conductor's to answer: a child's stall is exactly what a person needs to see,
    // since nobody but a person can cancel a tree that has gone quiet.
    const stalled = stalledWaiting([
      run('orc_1', { stalledSince: '2026-09-27T09:00:00Z' }),
      run('orc_2', {
        definition: 'code_implementation',
        parent: 'orc_1',
        depth: 1,
        stalledSince: '2026-09-27T09:05:00Z',
      }),
      run('orc_3'),
    ]);

    expect(stalled).toEqual([
      { id: 'orc_1', definition: 'implement_specification', kind: 'stalled' },
      { id: 'orc_2', definition: 'code_implementation', kind: 'stalled' },
    ]);
  });
});

describe('the push that says a run is waiting on a person', () => {
  it('reads the id and the state off a frame that carries no job', () => {
    expect(
      changedOf({
        kind: 'orchestration.changed',
        orchestration: 'orc_1',
        state: 'asking',
      }),
    ).toEqual({ id: 'orc_1', state: 'asking' });
    // Every state, filtered nowhere here: `rechecks` narrows it, so a
    // state added tomorrow reaches that decision rather than being dropped.
    expect(
      changedOf({
        kind: 'orchestration.changed',
        orchestration: 'orc_1',
        state: 'done',
      }),
    ).toEqual({ id: 'orc_1', state: 'done' });
  });

  it('is nothing at all for any other frame', () => {
    expect(changedOf({ job: 'job_1', kind: 'started' })).toBeUndefined();
    expect(changedOf({ kind: 'inbox.changed', unread: 2 })).toBeUndefined();
    expect(
      changedOf({ kind: 'orchestration.changed', orchestration: 'orc_1' }),
    ).toBeUndefined();
    expect(changedOf(null)).toBeUndefined();
    expect(changedOf('orchestration.changed')).toBeUndefined();
  });

  it('sends the background check for asking, and for a run it is showing as waiting', () => {
    // Asking: a run has started waiting on a person, or asked again. A run
    // already counted that moved on: it was answered elsewhere, or ended,
    // and the count is now wrong. Nothing else — a push lands on every
    // committed change, and a list per stage move is the poll paying for
    // runs nobody is waiting on.
    expect(rechecks({ id: 'orc_1', state: 'asking' }, [])).toBe(true);
    expect(rechecks({ id: 'orc_1', state: 'running' }, ['orc_1'])).toBe(true);
    for (const state of [
      'running',
      'waiting',
      'finished',
      'failed',
      'cancelled',
      'invented',
    ]) {
      expect(rechecks({ id: 'orc_1', state }, ['orc_2'])).toBe(false);
    }
  });

  it('reads as unreadable to the job-event reader, which is why it is handled first', () => {
    // The pin on the reason the view checks `changedOf` before `followed`
    // and before its own between-turns gate.
    expect(
      followed({
        kind: 'orchestration.changed',
        orchestration: 'orc_1',
        state: 'asking',
      }),
    ).toEqual({
      kind: 'unreadable',
      said: 'a frame arrived that is not a job event',
    });
  });
});

describe('the log, rendered — what a person sees of it', () => {
  const at = (
    ordinal: number,
    turnOrdinal: number,
    kind: string,
    text: string,
    more: Partial<Entry> = {},
  ): Entry => ({ ordinal, turnOrdinal, kind, state: 'stands', text, ...more });

  it("shows a harness utterance as the harness's, attributed to its source, and a person's as theirs", () => {
    expect(
      loggedFrom([
        at(1, 1, 'utterance', 'hello', {
          speaker: 'person',
          speakerName: 'enzo',
        }),
        at(2, 2, 'utterance', 'The run stopped.', {
          speaker: 'harness',
          speakerName: 'orchestration orc_1',
        }),
        at(3, 3, 'utterance', 'from before speakers'),
      ]),
    ).toEqual([
      { kind: 'person', turn: 1, text: 'hello' },
      {
        kind: 'harness',
        turn: 2,
        source: 'orchestration orc_1',
        text: 'The run stopped.',
      },
      { kind: 'person', turn: 3, text: 'from before speakers' },
    ]);
  });

  it('shows the answer that ends a turn and a fold as a seam, and hides every kind the chat view hides', () => {
    const shown = loggedFrom([
      at(1, 1, 'answer', 'Looking first.', { asked: 1 }),
      at(2, 1, 'tool_result', '42'),
      at(3, 1, 'answer', 'It is **42**.'),
      at(4, 1, 'answer', '   '),
      at(5, 1, 'attempt_failed', 'STUCK: stopped'),
      at(6, 1, 'notice', 'you have mail'),
      at(7, 1, 'hook', '{}'),
      at(8, 1, 'thinking', 'hmm'),
      at(9, 1, 'diagnostic', 'folded'),
      at(10, 1, 'summary', 'They asked about 42.'),
    ]);
    expect(shown.map((item) => item.kind)).toEqual(['answer', 'seam']);
    expect(shown[1]).toMatchObject({
      kind: 'seam',
      fold: { throughOrdinal: 1 },
    });
  });

  it('says when an answer was cut, and skips an entry whose text was ejected', () => {
    const [cut] = loggedFrom([
      at(1, 1, 'answer', 'x'.repeat(10), { cut: true, length: 9000 }),
      { ordinal: 2, turnOrdinal: 1, kind: 'answer', state: 'ejected' },
    ]);
    expect(cut).toMatchObject({
      kind: 'answer',
      cut: { shown: 10, length: 9000 },
    });
  });

  it("replays what was read up to the log's reach, and counts the turns it holds and draws", () => {
    const read = [
      at(3, 2, 'answer', 'second'),
      at(4, 3, 'utterance', 'three'),
      at(5, 3, 'answer', 'third'),
      at(6, 4, 'utterance', 'four'),
      at(7, 4, 'answer', 'fourth'),
      at(8, 5, 'utterance', 'not committed when the log was read'),
    ];
    const replay = replaying(read, 7);
    expect(replay.items).toEqual([
      { kind: 'answer', turn: 2, body: expect.any(Array) as unknown },
      { kind: 'person', turn: 3, text: 'three' },
      { kind: 'answer', turn: 3, body: expect.any(Array) as unknown },
      { kind: 'person', turn: 4, text: 'four' },
      { kind: 'answer', turn: 4, body: expect.any(Array) as unknown },
    ]);
    // The latest turn is the log's count of them; two are spoken below.
    expect(replay.held).toBe(4);
    expect(replay.shown).toBe(2);
  });

  it('catches up on everything committed since, when no stream of its own showed any of it', () => {
    const caught = catchingUp(
      [
        at(11, 5, 'utterance', 'The run stopped.', {
          speaker: 'harness',
          speakerName: 'orchestration orc_1',
        }),
        at(12, 5, 'answer', 'It got stuck.'),
        at(13, 6, 'utterance', 'begun, not yet pushed'),
      ],
      10,
      12,
      false,
    );
    expect(caught.items.map((item) => item.kind)).toEqual([
      'harness',
      'answer',
    ]);
    expect(caught.through).toBe(12);
  });

  it('leaves out the turn it streamed itself, and shows the turns before it that it held', () => {
    const caught = catchingUp(
      [
        at(11, 5, 'utterance', 'The run stopped.', {
          speaker: 'harness',
          speakerName: 'orchestration orc_1',
        }),
        at(12, 5, 'answer', 'It got stuck.'),
        at(13, 6, 'utterance', 'and now?'),
        at(14, 6, 'answer', 'streamed already'),
      ],
      10,
      14,
      true,
    );
    expect(caught.items).toEqual([
      {
        kind: 'harness',
        turn: 5,
        source: 'orchestration orc_1',
        text: 'The run stopped.',
      },
      { kind: 'answer', turn: 5, body: expect.any(Array) as unknown },
    ]);
    expect(caught.through).toBe(14);
  });

  it('never moves what is shown backwards', () => {
    expect(catchingUp([], 20, 12, false).through).toBe(20);
  });

  it('draws an entry that two pages both held once, as a fold between them leaves it', () => {
    const caught = catchingUp(
      [
        at(11, 5, 'utterance', 'The run stopped.', {
          speaker: 'harness',
          speakerName: 'orchestration orc_1',
        }),
        at(12, 5, 'answer', 'It got stuck.'),
        at(12, 5, 'answer', 'It got stuck.'),
      ],
      10,
      12,
      false,
    );
    expect(caught.items.map((item) => item.kind)).toEqual([
      'harness',
      'answer',
    ]);
  });
});

it('reads /usage in the active conversation without asking the model', () => {
  expect(
    typed('/usage', 'research', [], { conversation: 'root', agent: 'hermes' }),
  ).toMatchObject({
    kind: 'usage-report',
    command: {
      type: 'usage.conversation',
      filter: { conversation: 'root', scope: 'subtree' },
    },
  });
  expect(
    typed('/usage count', 'research', [], {
      conversation: 'root',
      agent: 'hermes',
    }),
  ).toMatchObject({
    kind: 'usage-report',
    command: {
      type: 'conversation.context.count',
      filter: { conversation: 'root', agent: 'hermes' },
    },
  });
});

describe('first-class orchestration authoring command', () => {
  it('keeps new and revision intent whole without treating it as a chat slash command', () => {
    expect(
      typed(
        '/design-orchestration Read sources and ask before publishing',
        'research',
      ),
    ).toEqual({
      kind: 'design',
      text: 'Read sources and ask before publishing',
    });
    expect(
      typed(
        '/design-orchestration --revise source_review Add a checkpoint',
        'research',
      ),
    ).toEqual({
      kind: 'design',
      text: 'Add a checkpoint',
      revision: 'source_review',
    });
    expect(typed('/design-orchestration')).toEqual({
      kind: 'usage',
      command: '/design-orchestration',
    });
    expect(typed('/design-orchestration --revise source_review')).toEqual({
      kind: 'usage',
      command: '/design-orchestration',
    });
    expect(typed('/design-orchestrationx hello').kind).toBe('unknown');
    expect(COMMANDS).toContain('/design-orchestration');
  });
});

describe('persistent message commands', () => {
  it('binds project listings and keeps instance addresses in their own scope', () => {
    expect(typed('/message instances', 'payments')).toEqual({
      kind: 'messaging',
      command: { type: 'message.instances', payload: { project: 'payments' } },
    });
    expect(typed('/message stop ins_one', 'payments')).toEqual({
      kind: 'messaging',
      command: {
        type: 'message.instance.stop',
        payload: { instance: 'ins_one' },
      },
    });
    expect(typed('/message cancel bdm_one', 'payments')).toEqual({
      kind: 'messaging',
      command: { type: 'message.cancel', payload: { message: 'bdm_one' } },
    });
    expect(typed('/message open {"agent":"reviewer"}', 'payments').kind).toBe(
      'retrieval-error',
    );
    expect(
      typed(
        '/message deliveries {"instance":"ins_one","limit":50}',
        'payments',
      ),
    ).toMatchObject({
      kind: 'messaging',
      command: { type: 'message.deliveries' },
    });
  });
});

describe('server project management commands', () => {
  it('routes creation and role inspection while preserving project navigation', async () => {
    const { serverCommand } = await import('./session.ts');
    expect(
      serverCommand(
        '/project create {"name":"HA","workspace":"/srv/ha","type":"DISJOINT","writePaths":["generated","reports"]}',
      ),
    ).toMatchObject({
      kind: 'request',
      request: {
        type: 'project.create',
        payload: {
          name: 'HA',
          type: 'DISJOINT',
          writePaths: ['generated', 'reports'],
        },
      },
    });
    expect(
      serverCommand('/admin account update {"handle":"sam","enabled":false}'),
    ).toMatchObject({
      kind: 'request',
      request: {
        type: 'admin.account.update',
        payload: { handle: 'sam', enabled: false },
      },
    });
    expect(
      serverCommand('/admin session revoke {"handle":"sam"}'),
    ).toMatchObject({
      kind: 'request',
      request: { type: 'admin.session.revoke' },
    });
    expect(serverCommand('/admin pricing list', 'HA')).toMatchObject({
      kind: 'request',
      request: { type: 'admin.pricing.list', payload: {} },
    });
    expect(
      serverCommand(
        '/admin pricing set {"billingRoute":"hosted","model":"deployment","expectedVersion":"boot:","mode":"TOKEN","currency":"USD","rates":{"input":"0.40","output":"1.60"}}',
        'HA',
      ),
    ).toMatchObject({
      kind: 'request',
      request: {
        type: 'admin.pricing.set',
        payload: {
          billingRoute: 'hosted',
          rates: { input: '0.40', output: '1.60' },
        },
      },
    });
    expect(serverCommand('/admin status')).toMatchObject({
      kind: 'request',
      request: { type: 'admin.status' },
    });
    expect(
      serverCommand('/project member-add {"project":"HA","handle":"operator"}'),
    ).toMatchObject({
      kind: 'request',
      request: { type: 'project.member.add' },
    });
    expect(serverCommand('/project HA')).toEqual({ kind: 'unhandled' });
    expect(typed('/project HA')).toEqual({ kind: 'project', name: 'HA' });
    expect(
      serverCommand('/project create {"name":"HA","type":"DISJOINT"}').kind,
    ).toBe('usage');
    expect(
      serverCommand('/project create {"name":"HA","writePaths":["../outside"]}')
        .kind,
    ).toBe('usage');
  });
});

it('sets the project automatic execution override with an explicit on or off', () => {
  expect(typed('/cap auto-increase on')).toEqual({
    kind: 'capSet',
    key: 'auto-increase',
    value: 1,
  });
  expect(typed('/cap auto-increase off')).toEqual({
    kind: 'capSet',
    key: 'auto-increase',
    value: 0,
  });
  expect(typed('/cap auto-increase yes')).toEqual({
    kind: 'usage',
    command: CAP_COMMAND,
  });
});

describe('project access commands', () => {
  it('uses current project and preserves role assignments on the socket', async () => {
    const { serverCommand } = await import('./session.ts');
    expect(serverCommand('/project access', 'HA')).toMatchObject({
      kind: 'request',
      request: { type: 'project.access', payload: { project: 'HA' } },
    });
    expect(
      serverCommand(
        '/project member-role {"handle":"user","role":"VIEWER"}',
        'HA',
      ),
    ).toMatchObject({
      kind: 'request',
      request: {
        type: 'project.member.role',
        payload: { project: 'HA', handle: 'user', role: 'VIEWER' },
      },
    });
  });
});

describe('explicit failed orchestration recovery', () => {
  it('builds one keyed resume for /resume and /retry and validates their arguments', () => {
    for (const command of ['/resume', '/retry']) {
      const line = typed(`${command} orc_failed`);
      expect(line).toEqual({ kind: 'resumeRun', id: 'orc_failed' });
      expect(typed(command)).toEqual({ kind: 'usage', command });
      expect(typed(`${command} one two`)).toEqual({ kind: 'usage', command });
    }
  });
});

// These source commands use the server Application scope, never the TUI's local file runner.
it('builds Application file commands with the current scope and reviewed revision', async () => {
  const { serverCommand } = await import('./session.ts');
  expect(serverCommand('/application files', 'HA')).toMatchObject({
    kind: 'request',
    request: { type: 'application.files', payload: { project: 'HA' } },
  });
  expect(
    serverCommand('/application read {"path":"notes.md"}', 'HA'),
  ).toMatchObject({
    kind: 'request',
    request: {
      type: 'application.file.read',
      payload: { project: 'HA', path: 'notes.md' },
    },
  });
});
