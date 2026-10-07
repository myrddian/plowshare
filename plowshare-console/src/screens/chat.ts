import { consoleTransport } from '../transport';
import { background } from '../background.ts';
import type { EventStream, EventStreamOptions } from '../events';
import { createRepl } from '../repl/repl';
import { button, el, labelled, problemText, trouble } from './dom';
import { createPicker, type Picker } from './picker';
import type { Screen, Transport } from './screen';
import {
  createTrajectory,
  READINGS,
  type Reading,
  type Trajectory,
} from './trajectory';

/**
 * One conversation, picked once and shown two ways at once.
 *
 * Spec §3. `sessions.ts` (now folded into this file), `picker.ts` (Task 2) and
 * `trajectory.ts` (Task 1) were each built and tested standing alone; this
 * file is mostly the wiring between them, and it was built here rather than
 * mounted directly into `shell.ts` so that the wiring could be tested before
 * this task changed what the shell shows at all. `shell.ts` now mounts this
 * screen directly under the `chat` view -- the REPL it used to adapt on its
 * own behalf lives here instead.
 *
 * <h2>One pick, and everything in this view follows it</h2>
 *
 * The picker names an id and does nothing else -- its own javadoc says so.
 * `onPick` is where this file decides what a pick means, and the whole of the
 * decision is that **both** of the things beside the sidebar are told the id:
 * {@link Repl.switchTo} for the REPL, {@link Trajectory.show} for the reading.
 * Each is one call, and each is a verb the component already exposes for
 * exactly this; nothing here re-implements what either does with an id, because
 * a second copy of a switch is the copy that drifts.
 *
 * That is not a tidiness point, it is the defect the redesign exists to
 * remove. §1 names it: a person picks a conversation in one place and the
 * thing next to it goes on showing another, with no selection carried across.
 * The two calls that were here before -- `resume`, which rebuilds a transcript
 * and clears no budget verdict, and `load`, which re-reads whatever the
 * trajectory had already chosen for itself -- reproduced it inside the one view
 * built to close it: a fresh conversation with the last one's prompt locked
 * over it, and a log tab quietly reading a different conversation than the one
 * highlighted in the sidebar.
 *
 * <h2>One chooser, and the two that were retired to get there</h2>
 *
 * The REPL and the trajectory screen each own a conversation `<select>`, and
 * both are correct standing alone -- each is mounted alone by its own suite,
 * where the select is the only door in. Composed here they are two more answers
 * to the question the sidebar already answers, and two elements carrying
 * `[data-conversations]` in one view is a scoped `querySelector` resolving by
 * DOM order. Both are built with `ownChooser: false`, which leaves each select
 * out of its own tree rather than hiding it, and makes each component *told*
 * which conversation to show instead of choosing one.
 *
 * <h2>The lifecycle filter is a control here, not a sentence under every row</h2>
 *
 * §3.1 keeps `sessions.ts`'s honesty about `GET /v1/conversations?lifecycle=`
 * being a filter and not a fact any row carries -- `ConversationView` still has
 * no lifecycle field -- but changes where that honesty lives: in the sidebar it
 * is {@link LIFECYCLES}, offered as a control over which tree the picker shows,
 * rather than a word printed under every conversation. `PickerOptions.lifecycle`
 * is fixed for the life of one `Picker`, so switching the filter here rebuilds
 * the picker against the same host rather than extending `Picker`'s already
 * reviewed contract to grow a mutable one -- the smaller change, and the one
 * that leaves `picker.test.ts` untouched.
 *
 * `ACTIVE`, `LIFECYCLES`, `describeLifecycle`, `ORIGIN_NOTE` and `TREE_NOTE`
 * used to be imported from `sessions.ts`; that file is deleted, and this is
 * now their only consumer, so they are defined below rather than
 * imported -- a second file for four constants and a function with one caller
 * would be a module for the sake of having one. `LIFECYCLES`'s own doc
 * comment, carried over unchanged, explains why it is deliberately not a type.
 *
 * <h2>The archive control this file did not get to invent</h2>
 *
 * `sessions.ts` offered every destination and rendered whatever the server's
 * 409 said, because `ConversationLifecycle.reachedFrom` is the transition
 * table and a copy of it here would be a second thing to keep in step -- and
 * one that would go wrong in the direction that matters: a client that greyed
 * out a legal move leaves a person unable to do something the server would
 * have done, with nothing on the screen to say the client decided that. This
 * screen keeps that shape: one destination chooser, offering every state
 * except the one currently showing (moving a row to the state it is already
 * in is refused by design, so it is not offered), and the refusal rendered as
 * the server phrased it rather than reworded here.
 *
 * What a *successful* move leaves behind is said too. The row has left the
 * listing on the screen, and the picker is rebuilt rather than redrawn so that
 * the pick goes with it -- otherwise the button stays armed with an id the
 * server has just moved, and a second press is a 409 a person did nothing to
 * earn. {@link NOTHING_PICKED} is the other half of the same honesty: the
 * listing control also drops the selection, and a move with nothing picked says
 * that rather than doing nothing.
 *
 * `sessions.ts` also said, on the page, two things the server knows and would
 * not otherwise tell a person looking at this screen: {@link ORIGIN_NOTE},
 * that every conversation reachable here is one a person opened and the other
 * three origins are unlistable rather than merely unlisted; and {@link
 * TREE_NOTE}, that a conversation's tree has no route that walks it. Both are
 * facts about the API and not about this screen's choices, and now that
 * `sessions.ts` is gone this file is the only place that says them -- so they
 * travel with the control, verbatim.
 */

/** The state a listing shows when nothing says otherwise, as the column spells it. */
export const ACTIVE = 'active';

/**
 * What each lifecycle state this server writes means, in a person's words.
 *
 * **Not exhaustive and deliberately not a type.** The keys are
 * `ConversationLifecycle.wireName()`'s spellings, which are a contract with
 * rows already written; a state added on the server renders as its own
 * spelling rather than failing here -- `ConversationLifecycle.of`'s rule on
 * the other side of the same contract.
 *
 * Each sentence says what the state *does*, because that is the whole argument
 * for the control: archiving that only filtered a listing would be the label
 * the design warns about, and a person choosing a destination is choosing an
 * effect.
 */
export const LIFECYCLES: Readonly<Record<string, string>> = Object.freeze({
  active:
    'active — takes new turns, and can be resumed. The state a person works in',
  archived:
    'archived — put away. It refuses new turns and refuses resumption, and its whole' +
    ' history is still readable. Reversible: moving it back to active is unarchiving',
  to_be_ejected:
    'to_be_ejected — marked for a retention sweep to strip the payloads out of. Nothing' +
    ' has been taken yet, and moving it back to active is cancelling the mark',
  ejected:
    'ejected — a sweep has nulled the payloads of this tree. The rows and their shape' +
    ' remain; what they held is gone. Terminal, and nothing comes back from it',
});

/** The state in words if this build knows the spelling, and as the spelling if not. */
export function describeLifecycle(state: unknown): string {
  if (typeof state !== 'string' || state === '') {
    return 'a state the server did not name';
  }
  return Object.hasOwn(LIFECYCLES, state)
    ? (LIFECYCLES[state] as string)
    : state;
}

/** What every row here is, and what none of them can be. */
export const ORIGIN_NOTE =
  'Every conversation here is one a person opened. ConversationStore.inHome splices' +
  " origin = 'turn' into this listing unconditionally, so it is not a filter that can be" +
  ' turned off: a delegation, a submission and a curator ruling are conversations too and' +
  ' none of them can appear on this screen. The wire carries no origin field either, so this' +
  ' is a fact about the endpoint rather than something the rows below are hiding.';

/** What a root is the root of, and why none of it is drawn. */
export const TREE_NOTE =
  'V17 made each of these the root of a tree — this turn plus everything delegated beneath it,' +
  ' which is one unit for archiving and for retention. Nothing below draws that tree, and not' +
  ' because it is empty: ConversationStore.treeOf is the read that walks parent_id and its' +
  ' only caller is the retention sweep, so no route enumerates a root’s children and no field' +
  ' here carries a parent. A child is reachable by its own id through the chat, trajectory and' +
  ' context reads; the one door onto that id is GET /v1/entries/search, which this console' +
  ' does not have. A count of nothing would say a conversation delegated nothing.';

/** Why the move did nothing, when nothing is picked to move. */
export const NOTHING_PICKED =
  'Nothing is picked, so there is nothing to move. Changing which listing is shown rebuilds' +
  ' the tree and drops the selection with it — the conversation beside this sidebar is' +
  ' still the one that was picked, but the move needs a row in the listing below.';

export interface ChatOptions {
  readonly root: HTMLElement;
  readonly transport?: Transport;
  readonly openStream?: (options: EventStreamOptions) => EventStream;
  readonly session: string;
  readonly project?: string | null;
}

export function createChat(options: ChatOptions): Screen {
  const transport: Transport = options.transport ?? consoleTransport;
  const surface = el('div', 'chat-surface');
  const side = el('div', 'chat-side');
  const stage = el('div', 'chat-stage');
  const replHost = el('div', 'chat-repl');
  const readHost = el('div', 'chat-read');
  // No class: this is the slot `createPicker` replaces the children of, and
  // the tree it builds carries `.picker`, which is what the stylesheet rules.
  // A `chat-picker` class here named nothing the sheet had a rule for and
  // nothing any query looked for -- a second name for one thing, and the one
  // that could not be found by either.
  const pickerHost = el('div', '');
  const complaint = el('p', 'complaint');

  let showing: string = ACTIVE;

  const repl = createRepl({
    root: replHost,
    transport,
    session: options.session,
    project: options.project ?? null,
    ownChooser: false,
    ...(options.openStream === undefined
      ? {}
      : { openStream: options.openStream }),
  });
  // No `project`: that option scopes the listing that fills the trajectory's
  // own chooser, and a screen that is told which conversation to read makes
  // no listing to scope. The tier this view is on reaches the REPL and the
  // sidebar, which are what read one.
  const reading: Trajectory = createTrajectory({
    root: readHost,
    transport,
    ownChooser: false,
    ownTabs: false,
  });

  /**
   * One tab strip over both panes, because there are two panes and three
   * readings and they do not line up one-to-one.
   *
   * The REPL **is** the chat reading here: the conversation a person is
   * actually in, with a composer under it. The trajectory screen has a chat
   * reading of its own -- the same rows, read-only -- and composed beside the
   * REPL that was a second pane also called chat, with its own header
   * colliding with the REPL's. So this strip switches which pane is showing
   * and, for the two that are the trajectory's, tells it which reading to
   * draw.
   */
  const tabs = el('div', 'tabs');
  const shown: Record<Reading, HTMLElement> = {
    chat: replHost,
    trajectory: readHost,
    log: readHost,
  };
  let tab: Reading = 'chat';

  function drawTabs(): void {
    for (const control of tabs.querySelectorAll('[data-tab]')) {
      const name = (control as HTMLElement).dataset['tab'] as Reading;
      if (name === tab) {
        control.setAttribute('aria-current', 'page');
      } else {
        control.removeAttribute('aria-current');
      }
    }
    replHost.hidden = shown[tab] !== replHost;
    readHost.hidden = shown[tab] !== readHost;
  }

  for (const name of READINGS) {
    const control = button('tab', name);
    control.dataset['tab'] = name;
    control.addEventListener('click', () => {
      tab = name;
      drawTabs();
      // The trajectory is told which of its own readings to draw; the
      // chat tab is this console's REPL and is not one of them.
      if (name !== 'chat') {
        background(reading.choose(name));
      }
    });
    tabs.append(control);
  }

  function onPick(id: string): void {
    complaint.replaceChildren();
    // Both, always, and neither through a method that means something
    // narrower -- see the class doc. Fired and not awaited because a click
    // handler has nothing to wait for: each call draws its own progress and
    // its own failure into its own half of the view.
    background(repl.switchTo(id));
    background(reading.show(id));
  }

  let picker: Picker = createPicker({
    root: pickerHost,
    transport,
    lifecycle: showing,
    onPick,
  });

  /**
   * Build a fresh picker over the same host, and forget what was picked.
   *
   * Both callers need the second half as much as the first. `Picker.picked()`
   * is that picker's own memory of a click, and `Picker.load()` redraws the
   * tree without touching it -- so reloading in place leaves an id selected
   * that is no longer on the screen: after a move, one the server has just
   * taken out of this listing, which a second press would try to move again
   * and be refused for. Rebuilding is what makes "nothing is picked" true on
   * the page and in the object at the same time.
   */
  function rebuildPicker(): void {
    picker.destroy();
    picker = createPicker({
      root: pickerHost,
      transport,
      lifecycle: showing,
      onPick,
    });
    background(picker.load());
  }

  // Which listing the picker shows. Rebuilding rather than mutating: see the
  // class doc for why `Picker`'s contract is left alone.
  const states = document.createElement('select');
  states.dataset['lifecycle'] = '';
  for (const state of Object.keys(LIFECYCLES)) {
    const option = document.createElement('option');
    option.value = state;
    option.textContent = describeLifecycle(state);
    states.append(option);
  }
  states.value = showing;

  // Every destination except the one being shown -- see the class doc for
  // why offering it and rendering the server's refusal is the whole of this,
  // and why the one being shown is dropped rather than merely disabled: a
  // move to the state a conversation is already in is refused by design.
  const destination = document.createElement('select');
  destination.dataset['lifecycleTarget'] = '';

  function fillDestinations(): void {
    destination.replaceChildren();
    for (const state of Object.keys(LIFECYCLES)) {
      if (state === showing) {
        continue;
      }
      const option = document.createElement('option');
      option.value = state;
      option.textContent = state;
      destination.append(option);
    }
  }
  fillDestinations();

  // PUT /v1/conversations/{id}/lifecycle -- carried across from `sessions.ts`
  // so the collapse loses no verb.
  const move = button('lifecycle-move', 'move it there');
  move.dataset['lifecycleMove'] = '';
  move.addEventListener('click', () => {
    const id = picker.picked();
    if (id === null) {
      // Said rather than silent. Changing the listing rebuilds the tree
      // and so drops the selection, which is a thing that happened to a
      // person's pick without their doing it; a button that then did
      // nothing at all would read as a broken button.
      complaint.replaceChildren(trouble(NOTHING_PICKED));
      return;
    }
    const target = destination.value;
    void transport
      .put(`/v1/conversations/${encodeURIComponent(id)}/lifecycle`, {
        lifecycle: target,
      })
      .then(() => {
        rebuildPicker();
        complaint.replaceChildren(
          el(
            'span',
            'moved',
            `${id} is ${target} now, so it has left this listing — the "showing"` +
              ' control above is where the rest of them are. What is beside this' +
              ' sidebar is still that conversation, and still readable.',
          ),
        );
      })
      .catch((problem: unknown) => {
        complaint.replaceChildren(
          trouble(problemText(problem, 'That move was refused.')),
        );
      });
  });

  states.addEventListener('change', () => {
    showing = states.value;
    fillDestinations();
    rebuildPicker();
  });

  /**
   * The three sentences this listing owes a reader, behind a disclosure.
   *
   * **Kept, and moved.** Each states something the wire genuinely cannot do —
   * the lifecycle word belongs to the listing and not the row, the origin
   * filter is spliced in and cannot be turned off, the conversation tree has
   * no route — and this console's discipline is that such an absence states
   * itself rather than waiting to be discovered. That discipline is right and
   * they are not being deleted.
   *
   * What was wrong is where they sat. They were the first three children of
   * this sidebar, above the lifecycle control and above the conversations
   * themselves: nine paragraphs and 1818 characters on the screen a person
   * comes here to talk in, pushing the tree below the fold in a 15rem column.
   * Honesty that has to be read past on the way to the thing you came for
   * stops being honesty and becomes a wall.
   *
   * A `details` and not a tooltip or a modal: it needs no JavaScript, it is
   * keyboard-reachable and screen-reader-addressable for free, and the
   * sentences stay in the DOM — so the tests that assert this console says
   * these things go on asserting it.
   */
  const about = document.createElement('details');
  about.className = 'about';
  const aboutWhat = document.createElement('summary');
  aboutWhat.textContent = 'about this listing';
  about.append(
    aboutWhat,
    el(
      'p',
      'note',
      "The word under this listing is the listing's, not each row's:" +
        ' a conversation carries no lifecycle field, so this is what was asked for.',
    ),
    el('p', 'note', ORIGIN_NOTE),
    el('p', 'note', TREE_NOTE),
  );

  // The conversations first. Everything else on this side is something a
  // person does *to* the one they picked, so it reads after the picking.
  side.append(
    el('h2', 'side-title', 'conversations'),
    labelled('showing', states),
    pickerHost,
    el('h2', 'side-title', 'move the one you picked'),
    labelled('to', destination),
    move,
    complaint,
    about,
  );
  stage.append(tabs, replHost, readHost);
  drawTabs();
  surface.append(side, stage);
  options.root.replaceChildren(surface);

  return {
    element: () => surface,
    async showRecord(id) {
      complaint.replaceChildren();
      surface.dataset['record'] = id;
      tab = 'trajectory';
      drawTabs();
      await Promise.all([repl.switchTo(id), reading.show(id)]);
      await reading.choose('trajectory');
    },
    load: async (): Promise<void> => {
      await picker.load();
      // The reading too, and not only the two that were here: its tabs
      // are drawn from the first frame, so a load that skipped it left
      // the trajectory and log tabs as a tab note over an empty section
      // -- no rows, no sentence, no error, which is the one thing an
      // empty section is not allowed to be. Told nothing yet, it says so.
      await reading.load();
      await repl.start().catch(() => undefined);
    },
    destroy: (): void => {
      picker.destroy();
      reading.destroy();
      repl.destroy();
    },
  };
}
