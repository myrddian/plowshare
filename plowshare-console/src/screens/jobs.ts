import { ApiError } from '../api';
import { recordLinks } from './record-link';
import { consoleTransport } from '../transport';
import { background } from '../background.ts';
import {
  openEventStream,
  type EventStream,
  type EventStreamOptions,
} from '../events';
import { grantFieldFor } from '../grant';
import { describeEnding } from '../repl/render';
import {
  asJobEvent,
  type JobView,
  type LimitsView,
  type OutcomeView,
} from '../repl/wire';
import {
  button,
  describeCount,
  el,
  field,
  input,
  labelled,
  nothing,
  problemText,
  textOf,
  trouble,
} from './dom';
import type { Screen, Transport } from './screen';

/**
 * The jobs screen: every run this process is holding, and what each came to.
 *
 * <h2>The endpoint is the record and the stream is not</h2>
 *
 * `EventChannelHandler` offers each event to a bounded queue and drops on
 * overflow rather than let a slow listener hold a job's turn. The stream is
 * droppable *by design*, so **nothing on this screen is derived from an event**:
 * a frame arriving means only "ask again now", and every field of every row
 * comes from the typed `job.list` operation. The failure this prevents is precise -- a job whose
 * events were dropped rendering as a job that did nothing -- and the REPL
 * solved it the same way, by making a frame move a poll forward rather than
 * move a state.
 *
 * The consequence to hold on to is that **silence proves nothing**. A run this
 * tab saw no frames for is not a run that is idle; it is a run this tab was not
 * shown. So there is a poll as well as a socket, and the poll is what the
 * screen actually believes.
 *
 * <h2>A cancel is a request, and the state is not the request</h2>
 *
 * `JobView.cancelRequested` is reported beside `state` and never folded into
 * it, on the record's own instruction: **a cancelled run stays `RUNNING` until
 * it reaches its next turn boundary**, and a caller that could not see the
 * request would think its cancel had been lost and send another. So this screen
 * draws both, and draws the request as a request.
 *
 * `Job.cancelRequested()` is also false again once the run finishes -- it is one
 * phase of a three-valued atomic and not a flag beside the state -- so a
 * finished run says nothing here about having been asked to stop. What answers
 * that is the outcome's ending, which is why the ending is drawn whatever it is.
 *
 * <h2>This list has no bound, and that is a finding rather than a bug fixed
 * here</h2>
 *
 * `JobStore` holds every job this process has started and **nothing reaps it**,
 * so the answer grows with the server's uptime and a restart empties it
 * entirely. That is on the roadmap and is deliberately not fixed from the
 * console. What this screen does about it is refuse to fall over: it draws a
 * window of the most recent runs by default, says how many the process is
 * holding, and pages through bounded windows on request. The supported operation
 * still returns the whole process listing; client windowing cannot bound that reply.
 */

/** The two verbs and the socket a test replaces. */
export interface JobsOptions {
  readonly root: HTMLElement;
  readonly transport?: Transport;
  /** Defaults to the real socket. */
  readonly openStream?: (options: EventStreamOptions) => EventStream;
  /** The listener session id this screen attaches under. */
  readonly session: string;
  /**
   * How often to re-read the listing, or `null` for a screen that only reads
   * when it is asked to.
   *
   * `null` is what the tests use: a suite that let a timer run would be a
   * suite whose failures depend on how long a machine took.
   */
  readonly pollMs?: number | null;
}

/** How often the listing is re-read when nothing says otherwise. */
export const POLL_MS = 2000;

/**
 * How many runs are drawn before the rest are held behind a button.
 *
 * The number is in the constant and deliberately nowhere else, because this
 * list has no bound and the right window is a judgement that will change. What
 * is not a judgement is that a screen rebuilding thousands of nodes on every
 * poll stops being a screen.
 */
export const WINDOW = 60;

/**
 * How much the raise control's input starts filled with, before an operator
 * changes it. **Not the number that is sent** -- {@link raiseControl} reads
 * whatever the input holds at the moment of the click, always, so this only
 * saves most clicks a trip through the keyboard.
 *
 * The owner's own instruction is what rules out a fixed grant here: a run
 * nearing a cap is "a pausing condition the user can correct to allow more
 * budget again", and correcting means choosing an amount, not this screen
 * guessing one on an operator's behalf and calling the guess done. Spec
 * §4.5's objection is to a number "chosen to be big enough that nobody
 * notices" -- one nobody can see or change -- and a pre-filled, editable
 * field is neither: it is a starting point, visible and always able to be
 * overruled before anything is sent.
 */
const DEFAULT_RAISE_BY = 20;

/**
 * A positive whole number, typed by an operator, or `null` for anything that
 * is not one.
 *
 * **`Number.parseInt` is the wrong tool for this and was tried first.** It
 * reads a numeric *prefix* and stops rather than rejecting what follows it --
 * `Number.parseInt('3.7', 10)` is `3`, and `Number.parseInt('5abc', 10)` is
 * `5` -- so a field built on it silently drops whatever the operator typed
 * after the part it could parse and sends the truncation instead. That is the
 * exact failure {@link raiseControl}'s own validation exists to rule out,
 * wearing the operator's own keystrokes rather than an invented default: the
 * screen still substitutes a number nobody asked for, just one built out of
 * pieces of what they typed instead of out of nothing. So the whole string is
 * validated before any number is read from it, not the other way around.
 *
 * **Surrounding whitespace is trimmed and accepted; nothing else is.** A
 * leading or trailing space is the one shape of "wrong" input a person
 * plausibly produces by accident rather than by typing the wrong thing --
 * `' 7 '` is what a stray space bar press looks like. `'3.7'`, `'5abc'`, and
 * `'1e3'` are all rejected outright: each names a value that is not a whole
 * number of turns or model calls, and none of them has an unambiguous integer
 * "meant" inside it that this screen could extract without guessing.
 */
function parsePositiveWholeNumber(raw: string): number | null {
  const trimmed = raw.trim();
  if (!/^\d+$/.test(trimmed)) {
    return null;
  }
  const value = Number(trimmed);
  return Number.isSafeInteger(value) && value > 0 && value <= 2147483647
    ? value
    : null;
}

/**
 * What this server's job states mean, in a person's words.
 *
 * **Not a switch, and deliberately not exhaustive**, on the rule `describeEnding`
 * already follows: `JobView.state` carries the constant's own name as a string
 * precisely so that a state added on the server reaches this console without a
 * change here. An unknown name is rendered as itself.
 *
 * `Object.hasOwn` and not a plain lookup: `state` is a string off the wire, and
 * `STATES['constructor']` on a plain object literal answers a function, which
 * would reach `textContent` and render its own source on the page.
 */
const STATES: Readonly<Record<string, string>> = Object.freeze({
  RUNNING: 'running',
  DONE: 'done',
});

/** The state in words if this build knows the name, and as the name if not. */
export function describeState(state: unknown): string {
  if (typeof state !== 'string' || state === '') {
    return 'in a state the server did not name';
  }
  return Object.hasOwn(STATES, state) ? (STATES[state] as string) : state;
}

export function createJobs(options: JobsOptions): Screen {
  const transport: Transport = options.transport ?? consoleTransport;
  const openSocket = options.openStream ?? openEventStream;
  const pollMs = options.pollMs === undefined ? POLL_MS : options.pollMs;

  let held: readonly JobView[] = [];
  let page = 0;
  let active = true;
  let selected: string | null = null;
  let selectionEpoch = 0;
  const blocked = new Map<string, string>();
  const raiseAmounts = new Map<string, string>();
  const continuationAmounts = new Map<string, string>();
  let stream: EventStream | null = null;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let stopped = false;
  /** A read is in flight. */
  let reading = false;
  /** Something asked for a read while one was in flight. */
  let asked = false;

  const shell = el('section', 'screen jobs');
  const head = el('header', 'screen-head');
  const reload = button('reload', 'reload');
  const widen = button('widen', 'Next jobs page');
  const previous = button('previous', 'Previous jobs page');
  previous.hidden = true;
  const detail = el('div', 'job-selection');
  const body = el('div', 'screen-body');
  body.dataset['jobs'] = '';
  widen.hidden = true;

  // No stream label here. The socket is the tab's and not this screen's --
  // `multiplex` owns it and `chat` reads the same one -- so its state is said
  // once, at the rail's foot, where it is on screen whichever view is
  // showing. A copy here was a second [data-stream] element saying the same
  // thing about the same socket beside the first.
  head.append(el('h2', 'screen-title', 'jobs'), reload, previous, widen);
  body.append(detail);
  shell.append(head, body);
  options.root.replaceChildren(shell);

  reload.addEventListener('click', () => {
    background(refresh());
  });
  widen.addEventListener('click', () => {
    page += 1;
    draw();
  });

  previous.addEventListener('click', () => {
    page = Math.max(0, page - 1);
    draw();
  });

  function failure(
    key: string,
    control: HTMLButtonElement,
    card: HTMLElement,
    problem: unknown,
    fallback: string,
  ): void {
    if (stopped) return;
    if (problem instanceof ApiError && problem.status < 500) {
      blocked.delete(key);
      control.disabled = false;
    } else
      blocked.set(
        key,
        'Delivery is uncertain. This action was not replayed. Inspect server state before acting again.',
      );
    card.append(trouble(problemText(problem, fallback)));
    const note = blocked.get(key);
    if (note) card.append(trouble(note));
  }

  /**
   * A frame moves the poll forward and moves nothing else.
   *
   * The frame's own fields are not read past the shape check -- not the kind,
   * not the agent, not the tool. Everything drawn comes from the listing,
   * which is what makes a dropped frame cost a moment's staleness rather than
   * a wrong row.
   */
  function onEvent(frame: unknown): void {
    if (asJobEvent(frame) === null) {
      return;
    }
    background(refresh());
  }

  // --- drawing -------------------------------------------------------------

  function outcome(view: OutcomeView): HTMLElement {
    const node = el('div', 'outcome');
    node.dataset['outcome'] = '';
    // The ending first, and `answered` beside it: `JobView` says `answered`
    // is the one bit a caller has to read before believing `text`, so a
    // truncated run is never dressed here as an answer.
    node.append(el('div', 'ending', describeEnding(view.ending)));
    node.append(
      el(
        'div',
        'answered',
        view.answered === true
          ? 'it reached an answer'
          : 'it stopped without reaching an answer; what follows is where it got to',
      ),
    );
    node.append(el('pre', 'body', textOf(view.text)));
    const detail = textOf(view.detail);
    if (detail !== '') {
      node.append(field('detail', detail));
    }
    // `modelCalls` is this job's own count, not the shared, tree-wide
    // spend the limits block below prints as "model calls spent (shared
    // across the delegation tree)" -- see that field's own comment for
    // why the two numbers differ. The suffix below is appended outside
    // `describeCount` rather than folded into its plural, so the
    // not-reported case still reads "model calls not reported" and not
    // some sentence with "own" stitched into the middle of it.
    node.append(
      el(
        'div',
        'meta',
        `${describeCount(view.steps, 'step', 'steps')}, ` +
          `${describeCount(view.modelCalls, 'model call', 'model calls')} of this run's own`,
      ),
    );
    return node;
  }

  /**
   * The body `POST /v1/conversations/{id}/resume` gets for continuing this
   * run: the session it reaches, and whichever number stopped it.
   *
   * `grantFieldFor` -- `src/grant.ts`, shared with `repl.ts` rather than
   * reimplemented here -- says which field; this function only reads
   * `job.limits` to say what number goes in it, and mirrors `repl.ts`'s own
   * `considerOffer`/`grantMore` split doing the identical arithmetic: a turn
   * cap is the ceiling itself, since a resumed run starts again at zero
   * turns, while a model-call budget is conversation-cumulative, so the
   * field carries a new total -- what has already been spent plus the
   * ceiling that stopped this run -- and not the ceiling alone, which would
   * leave nothing spendable. An ending {@link grantFieldFor} does not know,
   * or a run whose `limits` cannot supply the number that field needs, sends
   * no grant field at all: the run is still asked to continue, just with
   * nothing raised, rather than this screen inventing a number to send.
   */
  function grantBody(
    job: JobView,
    came: OutcomeView,
  ): { session: string; maxTurns?: number; maxModelCalls?: number } {
    const body: { session: string; maxTurns?: number; maxModelCalls?: number } =
      { session: options.session };
    const field_ = grantFieldFor(came.ending);
    if (field_ === 'maxTurns') {
      const cap = job.limits?.maxTurns;
      if (typeof cap === 'number') {
        body.maxTurns = cap;
      }
    } else if (field_ === 'maxModelCalls') {
      const cap = job.limits?.maxModelCalls;
      const already = job.limits?.modelCallsSpent;
      if (typeof cap === 'number' && typeof already === 'number') {
        body.maxModelCalls = already + cap;
      }
    }
    return body;
  }

  /**
   * The control that continues a run which stopped unattended, or `null` for
   * a run this screen has nothing to offer about.
   *
   * <h2>`resumable` alone decides whether to offer it</h2>
   *
   * Read once and nothing else -- no ending is enumerated -- on
   * `OutcomeView.resumable`'s own instruction and `repl.ts`'s
   * `considerOffer`, which holds the identical rule for the REPL's own grant
   * control: `CANCELLED` is continued through this same door on request and
   * would never be found by a list of "endings this screen offers a button
   * for", which is the failure mode a copied table invites.
   *
   * **No conversation, no control.** A run with no `conversation` has
   * nowhere for `POST /v1/conversations/{id}/resume` to reach -- `JobView
   * .conversation`'s own javadoc names a curator pass as exactly that run,
   * many rulings and not one agent's turn in any of them.
   */
  function continueButton(
    job: JobView,
    came: OutcomeView,
    id: string,
    card: HTMLElement,
  ): HTMLElement | null {
    if (
      came.resumable !== true ||
      job.conversation === null ||
      job.conversation === undefined
    ) {
      return null;
    }
    const conversation = job.conversation;
    const grant = grantBody(job, came);
    const grantField =
      grant.maxTurns !== undefined
        ? 'maxTurns'
        : grant.maxModelCalls !== undefined
          ? 'maxModelCalls'
          : null;
    const amount = input('Continuation allowance', 'Positive whole number');
    if (grantField !== null) {
      amount.value = continuationAmounts.get(id) ?? String(grant[grantField]);
      amount.addEventListener('input', () =>
        continuationAmounts.set(id, amount.value),
      );
    }
    const resume = button('continue', 'continue this run');
    resume.dataset['continue'] = id;
    const key = `continue:${id}`;
    resume.disabled = blocked.has(key);
    resume.addEventListener('click', () => {
      if (blocked.has(key)) return;
      if (grantField !== null) {
        const value = parsePositiveWholeNumber(amount.value);
        if (value === null) {
          card.append(
            trouble(
              'Choose a positive whole-number continuation allowance within the supported range.',
            ),
          );
          return;
        }
        grant[grantField] = value;
      }
      blocked.set(key, 'Continuation requested. Inspect the resulting job.');
      resume.disabled = true;
      background(
        transport
          .post(
            `/v1/conversations/${encodeURIComponent(conversation)}/resume`,
            grant,
          )
          .then(
            // The continued run is a new job under the same conversation;
            // the listing is re-read rather than the answer trusted, same
            // rule as `cancel` and the raise control above it.
            () => refresh(),
            (problem: unknown) => {
              failure(
                key,
                resume,
                card,
                problem,
                'That run could not be continued.',
              );
            },
          ),
      );
    });
    const controls = el('div', 'continue-control');
    if (grantField !== null)
      controls.append(
        labelled(
          grantField === 'maxTurns'
            ? 'Continuation turn cap'
            : 'New total model-call budget',
          amount,
        ),
      );
    controls.append(resume);
    return controls;
  }

  /**
   * What bounds this run, both fields at once.
   *
   * **Ask `noTurnCap`/`noBudget` before reading the paired number, and never
   * the other order** -- `LimitsView`'s own javadoc's instruction, restated
   * here because getting it backwards is exactly how an absent ceiling would
   * be printed as `0`: `describeCount(null, ...)` already refuses that for a
   * spent count, but a ceiling has to be asked about the right way before it
   * is a number at all. This function is not called for a job whose `limits`
   * is null -- {@link drawJob} draws that third state itself -- so every call
   * here has a real `LimitsView` and both fields below are genuinely one of
   * "no ceiling" or a number, never the "nothing on this handle" case.
   *
   * **`modelCallsSpent` is not `outcome.modelCalls`, and the labels say so.**
   * `LimitsView`'s own javadoc is explicit that `modelCallsSpent` is read
   * live off the `Budget` this run shares by reference with its whole
   * delegation tree, so it keeps climbing after this run ends as sibling
   * turns spend from the same pot -- while `outcome.modelCalls`, printed in
   * the block above this one, is this job's own count and nothing else's.
   * Drawn adjacently with matching-looking numbers, an operator who has not
   * read both javadocs will otherwise read one as a typo of the other, or
   * read a delegating run's climbing spend as this run's own consumption.
   */
  function limits(view: LimitsView): HTMLElement {
    const node = el('div', 'limits');
    node.dataset['limits'] = '';
    node.append(
      field(
        'turn cap',
        view.noTurnCap
          ? 'no turn cap'
          : describeCount(view.maxTurns, 'turn', 'turns'),
      ),
    );
    node.append(
      field(
        'model call ceiling',
        view.noBudget
          ? 'no ceiling'
          : describeCount(view.maxModelCalls, 'model call', 'model calls'),
      ),
    );
    node.append(
      field(
        'model calls spent (shared across the delegation tree)',
        describeCount(view.modelCallsSpent, 'model call', 'model calls'),
      ),
    );
    return node;
  }

  /**
   * The control that raises a running job's ceiling, or `null` for a job
   * this control has nothing to offer.
   *
   * <h2>Two rules that read as contradictory and are not</h2>
   *
   * **The server, and only the server, decides a run whose budget has no
   * ceiling cannot have that ceiling raised** -- `Budget.changeTo` throws
   * "this budget has no ceiling, so there is nothing ... to raise", and that
   * refusal is not pre-empted here with a client-side rule of the same
   * shape; a job whose budget genuinely has a number is always sent one.
   * And the caller's `.catch` now shows that sentence: `api.ts`'s `ok()`
   * carries a refusal's own `detail` through, so `problemText` renders
   * whichever of the three distinct refusals this endpoint made rather than
   * the `"/v1/jobs/{id}/limits answered 400"` all three used to collapse to.
   * This paragraph recorded that collapse as a gap in `api.ts` for as long as
   * it was open; it is closed, and `api.test.ts` is where it is pinned.
   * **`noBudget` is still read here**, for a narrower reason
   * that does not conflict with the first: offering a control that can only
   * ever be refused teaches an operator to stop reading refusals, which is
   * the same reason this control is offered only on a `RUNNING` job in the
   * first place. The two rules are about different moments -- one is "never
   * guess what the server will say", the other is "never draw a control with
   * nothing to ask for" -- and both hold at once without conflict.
   *
   * A job that is not one agent's run (`bounds === null`) has no limits here
   * to move at all, and a job with both `noTurnCap` and `noBudget` already
   * true has nothing either field could raise -- both are the second rule,
   * not the first.
   *
   * <h2>The amount is the operator's to choose</h2>
   *
   * `AdjustLimitsRequest.maxTurns`/`.maxModelCalls` are exact numbers the
   * server writes down verbatim, and this screen does not get to decide what
   * one is worth to whoever is watching the run. {@link DEFAULT_RAISE_BY}
   * only pre-fills the box; the amount actually sent is read off the input
   * at the moment of the click, every time, so typing a different number and
   * clicking raises by that number instead. An entry that is not a positive
   * whole number sends no request at all -- there is no number to fall back
   * to that was not either invented here or stale from whatever the box last
   * held, and both are the substitution this control exists to avoid.
   */
  function raiseControl(
    job: JobView,
    bounds: LimitsView | null | undefined,
    id: string,
    card: HTMLElement,
  ): HTMLElement | null {
    if (job.state !== 'RUNNING' || bounds === null || bounds === undefined) {
      return null;
    }
    const raisesTurns =
      !bounds.noTurnCap && typeof bounds.maxTurns === 'number';
    const raisesBudget =
      !bounds.noBudget && typeof bounds.maxModelCalls === 'number';
    if (!raisesTurns && !raisesBudget) {
      return null;
    }
    const amount = input('raise by', 'how many more');
    amount.value = raiseAmounts.get(id) ?? String(DEFAULT_RAISE_BY);
    amount.addEventListener('input', () => raiseAmounts.set(id, amount.value));
    const raise = button('raise', 'raise the ceiling');
    raise.dataset['raise'] = id;
    const key = `limits:${id}`;
    raise.disabled = blocked.has(key);
    raise.addEventListener('click', () => {
      if (blocked.has(key)) return;
      const by = parsePositiveWholeNumber(amount.value);
      if (by === null) {
        // Refused here and not sent for the server to refuse: neither
        // a fraction nor trailing garbage nor a negative count is a
        // value `AdjustLimitsRequest`'s fields could ever carry, and
        // falling back to a number this screen picked -- even one
        // built from the digits the operator did type, truncated --
        // would be exactly the invented-default failure the
        // operator-chosen amount exists to rule out.
        card.append(
          trouble(
            'That is not a number of more turns or calls to raise anything by. Type a' +
              ' positive whole number first.',
          ),
        );
        return;
      }
      const body: { maxTurns?: number; maxModelCalls?: number } = {};
      if (raisesTurns && typeof bounds.maxTurns === 'number') {
        body.maxTurns = bounds.maxTurns + by;
      }
      if (raisesBudget && typeof bounds.maxModelCalls === 'number') {
        body.maxModelCalls = bounds.maxModelCalls + by;
      }
      if (
        Object.values(body).some(
          (value) => !Number.isSafeInteger(value) || value > 2147483647,
        )
      ) {
        card.append(
          trouble(
            'The resulting limit exceeds the supported whole-number range.',
          ),
        );
        return;
      }
      blocked.set(key, 'Sending limit change…');
      raise.disabled = true;
      // One `.then` with both handlers, and not a `.then().catch()`
      // pair: the two-argument form settles on the *first* microtask
      // turn after the request settles, and a pair takes a second turn
      // to hand the rejection on to `.catch`. A caller awaiting one
      // `Promise.resolve()` after the click -- as a person watching this
      // screen effectively does, since nothing else holds the tab open
      // in between -- must see this run through before it can ask what
      // happened.
      background(
        transport.post(`/v1/jobs/${encodeURIComponent(id)}/limits`, body).then(
          // Same rule as `cancel`: the listing is re-read rather than
          // the answer believed, because what a run's ceiling now is
          // comes from the listing and not from echoing back what was
          // asked.
          () => {
            blocked.delete(key);
            return refresh();
          },
          (problem: unknown) => {
            failure(
              key,
              raise,
              card,
              problem,
              'That run’s ceiling could not be raised.',
            );
          },
        ),
      );
    });
    const wrap = el('span', 'raise-control');
    wrap.append(labelled('raise by', amount), raise);
    return wrap;
  }

  function drawJob(job: JobView): HTMLElement {
    const card = el('article', 'job');
    const id = textOf(job.id);
    card.dataset['job'] = id;
    card.dataset['state'] = textOf(job.state);
    const bounds = job.limits;

    const line = el('div', 'job-head');
    line.append(el('span', 'job-id', id));
    line.append(el('span', 'agent', textOf(job.agent)));
    const state = el('span', 'state', describeState(job.state));
    state.dataset['jobState'] = '';
    line.append(state);

    // Separate from the state, never folded into it. A cancelled run is
    // still RUNNING until its next turn boundary, and a screen that showed
    // one word for both would be showing a lie about the state or losing
    // the request.
    if (job.cancelRequested === true) {
      const asked_ = el(
        'span',
        'cancel-requested',
        'a stop has been asked for; this run ends at its next turn boundary',
      );
      asked_.dataset['cancelRequested'] = 'true';
      line.append(asked_);
    }

    const stop = button('cancel', 'ask this run to stop');
    stop.dataset['cancel'] = id;
    const key = `cancel:${id}`;
    stop.disabled =
      blocked.has(key) ||
      job.cancelRequested === true ||
      job.state !== 'RUNNING';
    stop.addEventListener('click', () => {
      if (blocked.has(key)) return;
      blocked.set(key, 'Cancellation requested; waiting for server state.');
      stop.disabled = true;
      void transport
        .post(`/v1/jobs/${encodeURIComponent(id)}/cancel`)
        // The listing is re-read rather than the answer believed. A
        // cancel changes what the run will do, not what it has done,
        // and this screen's rule is that what a run has done comes from
        // the listing.
        .then(() => refresh())
        .catch((problem: unknown) => {
          failure(
            key,
            stop,
            card,
            problem,
            'That run could not be asked to stop.',
          );
        });
    });
    line.append(stop);

    const raise = raiseControl(job, bounds, id, card);
    if (raise !== null) {
      line.append(raise);
    }
    card.append(line, recordLinks(job.conversation));
    for (const verb of ['cancel', 'limits', 'continue']) {
      const note = blocked.get(`${verb}:${id}`);
      if (note) card.append(el('p', 'note', note));
    }

    if (bounds === null || bounds === undefined) {
      // Not an empty row and not a guess: a curator pass carries its
      // budget across a whole pass and has none on this handle, and that
      // is the third state -- at the whole-`limits` level, unlike either
      // field within it, which are only ever a number or "no ceiling".
      card.append(
        el(
          'div',
          'no-limits',
          'no limits on this handle -- this run does not own a budget of its own',
        ),
      );
    } else {
      card.append(limits(bounds));
    }

    const came = job.outcome;
    if (came === null || came === undefined) {
      // Not an empty outcome and not a guess: a job with no outcome has
      // not finished, whatever this tab has or has not seen go past.
      card.append(
        el(
          'div',
          'running-note',
          'still going, as of the last time this screen asked',
        ),
      );
    } else {
      card.append(outcome(came));
      const resume = continueButton(job, came, id, card);
      if (resume !== null) {
        card.append(resume);
      }
    }
    return card;
  }

  function draw(): void {
    if (held.length === 0) {
      widen.hidden = true;
      previous.hidden = true;
      body.replaceChildren(
        detail,
        nothing(
          'This process is holding no jobs. That is an answer and not a failure — and on' +
            ' a server that has been restarted it is the ordinary one, because these live' +
            ' in this process’s memory. What earlier runs produced is in the archive.',
        ),
      );
      return;
    }
    // Newest first: this list is read to see what just happened, and the
    // listing arrives newest last.
    const newestFirst = [...held].reverse();
    page = Math.min(page, Math.max(0, Math.ceil(held.length / WINDOW) - 1));
    const shown = newestFirst.slice(page * WINDOW, (page + 1) * WINDOW);
    widen.hidden = (page + 1) * WINDOW >= held.length;
    previous.hidden = page === 0;

    const nodes: HTMLElement[] = [];
    const note = el(
      'p',
      'window-note',
      held.length === shown.length
        ? `this process is holding ${describeCount(held.length, 'run', 'runs')}, all drawn`
        : `this process is holding ${describeCount(held.length, 'run', 'runs')};` +
            ` page ${page + 1} shows ${shown.length} runs`,
    );
    note.dataset['window'] = String(shown.length);
    note.dataset['held'] = String(held.length);
    nodes.push(note);
    nodes.push(
      el(
        'p',
        'window-note',
        'Nothing reaps this list, so it grows for as long as this process runs and is empty' +
          ' again after a restart. It is what this process is holding and not a history.',
      ),
    );
    body.replaceChildren(detail, ...nodes, ...shown.map(drawJob));
  }

  // --- the server ----------------------------------------------------------

  async function read(): Promise<void> {
    let listed: readonly JobView[];
    try {
      listed = (await transport.get('/v1/jobs')) ?? [];
    } catch (problem) {
      // The listing is left as it was rather than emptied. An unreadable
      // answer is not a server with no jobs on it, and drawing one would
      // be this screen concluding from a failure the same way it refuses
      // to conclude from silence.
      if (stopped) return;
      body.querySelector('[data-list-error]')?.remove();
      const error = trouble(
        problemText(problem, 'The jobs could not be listed.'),
      );
      error.dataset['listError'] = '';
      body.prepend(error);
      return;
    }
    if (stopped) return;
    held = listed;
    draw();
    if (selected !== null) await showRecord(selected);
  }

  /**
   * One read at a time, and one more if anything asked while it was going.
   *
   * A burst of frames is exactly the case this screen exists to survive, and
   * a read per frame would answer a dropped-event problem with a thundering
   * one. There is no timer in this: the coalescing is a latch, so a suite
   * driving it does not have to wait for a clock.
   */
  async function refresh(): Promise<void> {
    if (stopped || !active) return;
    if (reading) {
      asked = true;
      return;
    }
    reading = true;
    try {
      await read();
    } finally {
      reading = false;
    }
    if (asked && !stopped && active) {
      asked = false;
      await refresh();
    }
  }

  function schedulePoll(): void {
    if (pollMs === null || stopped || !active || timer !== null) {
      return;
    }
    timer = setTimeout(() => {
      timer = null;
      background(refresh().finally(schedulePoll));
    }, pollMs);
  }

  async function load(): Promise<void> {
    if (stream === null && !stopped) {
      // Reconnect reconciles the listing; the socket's state is reported once
      // at the rail's foot.
      stream = openSocket({
        session: options.session,
        onEvent,
        onStatus: (status) => {
          if (status.state === 'open' && stream !== null) background(refresh());
        },
      });
    }
    await refresh();
    schedulePoll();
  }

  async function showRecord(id: string): Promise<void> {
    selected = id;
    const epoch = ++selectionEpoch;
    try {
      const job =
        held.find((row) => row.id === id) ??
        (await transport.get(`/v1/jobs/${encodeURIComponent(id)}`));
      if (stopped || epoch !== selectionEpoch) return;
      if (!job) throw new Error('The server did not return this job.');
      detail.replaceChildren(el('h3', '', 'Selected job'), drawJob(job));
    } catch (problem) {
      if (stopped || epoch !== selectionEpoch) return;
      detail.replaceChildren(
        trouble(problemText(problem, 'This job is unavailable.')),
        el(
          'p',
          'note',
          'Historic job lookup is unavailable after the server process loses the job. Read its retained conversation or inbox delivery instead.',
        ),
      );
    }
  }
  return {
    element: () => shell,
    showRecord,
    setActive(next) {
      active = next;
      if (timer !== null) clearTimeout(timer);
      timer = null;
      if (active && !stopped) background(refresh().finally(schedulePoll));
    },
    load,
    destroy(): void {
      stopped = true;
      if (timer !== null) {
        clearTimeout(timer);
        timer = null;
      }
      stream?.close();
      stream = null;
    },
  };
}
