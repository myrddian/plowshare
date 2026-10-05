import { consoleTransport } from '../transport';
import { background } from '../background.ts';
import { isList } from '../../../sdk/typescript/src/binding/values.ts';
import {
  button,
  el,
  field,
  input,
  labelled,
  moment,
  nothing,
  problemText,
  textOf,
  trouble,
} from './dom';
import type { Screen, Transport } from './screen';
import {
  GLOBAL_TIER,
  type ProposalView,
  type Reconsidered,
  type ResolvedProposal,
} from './wire';

/**
 * The proposals screen: what the curator is waiting on a person for.
 *
 * <h2>Settling is once, and the screen says so before the click</h2>
 *
 * `ProposalController.resolve` states both halves. **A settled proposal is
 * never re-opened** -- the row's resolution is what stops the curator asking
 * the same question next week, so a queue that forgot would ask forever. And
 * **a stale proposal is refused rather than applied**: between the question and
 * the answer the memory may have been superseded or invalidated, and `Archive.
 * promote` refuses a retired record naming its state, because promoting a fact
 * that has stopped being true into the tier every project reads is the worst
 * thing this queue could do.
 *
 * Three things follow, and all three are here rather than assumed:
 *
 * - **{@link FINALITY} is drawn with every proposal, above its controls.** A
 *   consequence a person learns after the click is a consequence they were not
 *   given a chance to weigh.
 * - **The controls disable synchronously on the first click**, before anything
 *   is awaited. A double click on `accept` is the ordinary hazard of a slow
 *   network, and the second one would be a second ruling on a row the first has
 *   already settled -- refused by the server, which is the good case, and
 *   reported to the person as a failure of the ruling they actually meant.
 * - **A refusal re-reads the queue rather than simply re-enabling.** A POST
 *   that failed is a POST whose fate this console does not know; the listing is
 *   the only thing that says whether the row is still waiting.
 *
 * <h2>Who decided is required, and is not this console's to invent</h2>
 *
 * `ResolveProposalRequest.by` is required with no default, and
 * `ProposalController` refuses a blank one: the row is the record that a human
 * agreed, and one that cannot say which human records only that somebody, once,
 * thought it was fine. So the rulings stay closed until a name has been typed,
 * and the screen says that is why rather than leaving a disabled button
 * unexplained.
 */

/** What a person is told before they rule, not after. */
export const FINALITY =
  'Settling this is once and for good. A settled proposal is never re-opened, and the server' +
  ' refuses a stale ruling rather than applying it. Accepting copies this memory into the' +
  ' global tier — the one every project reads — and retires this project’s own record with a' +
  ' forward link; rejecting is remembered, which is what stops the curator asking again.';

export const NOTHING_WAITING =
  'Nothing is waiting in this tier. That is an answer: settled proposals are not listed again,' +
  ' so an empty queue is one that has been answered rather than one that has lost anything.';

export const NEED_A_NAME =
  'Type who is deciding before ruling on anything. The server requires it and refuses a blank' +
  ' one: this row is the record that a person agreed, and a row that cannot say which person' +
  ' records only that somebody, once, thought it was fine.';

/**
 * What each action this server proposes means, in a person's words.
 *
 * **Not a switch, and not exhaustive.** `Proposal.action` is documented as an
 * open string — "{@code ProposalStore.PROMOTE} today; an open string so a
 * second kind of question needs no migration" — so a second kind must reach
 * this console without a change here.
 */
const ACTIONS: Readonly<Record<string, string>> = Object.freeze({
  promote: 'promote this memory into the global tier',
});

/**
 * Where a proposal is.
 *
 * `GET /v1/proposals` lists only what is waiting, so `pending` is the state
 * this screen ordinarily sees. The other two are here because the wire carries
 * the field and a listing that grew to include settled rows must not arrive as
 * a blank word; a fourth state must render as itself.
 */
const STATES: Readonly<Record<string, string>> = Object.freeze({
  pending: 'waiting for a person',
  accepted: 'accepted',
  rejected: 'rejected',
});

function described(
  table: Readonly<Record<string, string>>,
  value: unknown,
  absent: string,
): string {
  if (typeof value !== 'string' || value === '') {
    return absent;
  }
  // Object.hasOwn and not a plain lookup: the value is a string off the wire,
  // and `table['constructor']` on a plain object literal answers a function.
  return Object.hasOwn(table, value) ? (table[value] as string) : value;
}

/** What is proposed, in words if this build knows the name and as the name if not. */
export function describeAction(action: unknown): string {
  return described(ACTIONS, action, 'an action the server did not name');
}

/** Where the proposal is, in words if this build knows the name and as the name if not. */
export function describeProposalState(state: unknown): string {
  return described(STATES, state, 'in a state the server did not name');
}

export interface ProposalsOptions {
  readonly root: HTMLElement;
  readonly transport?: Transport;
  /** The tier to read, or null for global. */
  readonly project?: string | null;
}

export function createProposals(options: ProposalsOptions): Screen {
  const transport: Transport = options.transport ?? consoleTransport;
  let project: string | null = options.project ?? null;

  const shell = el('section', 'screen proposals');
  const head = el('header', 'screen-head');
  const tier = input('project', `blank for ${GLOBAL_TIER}`);
  const who = input('by', 'who is deciding');
  const reload = button('reload', 'reload');
  const reconsider = button(
    'reconsider',
    'reconsider the curator’s own rulings',
  );
  const body = el('div', 'screen-body');
  body.dataset['proposals'] = '';
  tier.value = project ?? '';

  head.append(
    el('h2', 'screen-title', 'proposals'),
    labelled('tier', tier),
    labelled('deciding as', who),
    reload,
    reconsider,
  );
  shell.append(head, body);
  options.root.replaceChildren(shell);

  reload.addEventListener('click', () => {
    project = tier.value.trim() === '' ? null : tier.value.trim();
    background(load());
  });
  tier.addEventListener('change', () => {
    project = tier.value.trim() === '' ? null : tier.value.trim();
    background(load());
  });
  // The rulings open the moment there is a name to record and close again if
  // it is erased, rather than being checked once when the screen was drawn.
  who.addEventListener('input', () => {
    showWhether();
  });
  reconsider.addEventListener('click', () => {
    reconsider.disabled = true;
    background(
      (async (): Promise<void> => {
        let said: HTMLElement;
        try {
          said = reconsidered(
            await transport.post(`/v1/proposals/reconsider${query()}`),
          );
        } catch (problem) {
          said = trouble(
            problemText(
              problem,
              'Nothing could be put back in front of a person.',
            ),
          );
        }
        // The queue is re-read first and the report put above it after,
        // because `load` replaces the whole body: a report drawn before the
        // reload is a report the reload erases.
        await load();
        body.prepend(said);
        reconsider.disabled = false;
      })(),
    );
  });

  function query(): string {
    return project === null ? '' : `?project=${encodeURIComponent(project)}`;
  }

  /**
   * What a reconsider pass did, both halves.
   *
   * `PromotionQueue.Reconsidered` carries the refusals as well as the
   * re-opened, on `Archive.Repair`'s rule: a caller told only how many
   * succeeded cannot tell a tier with nothing to re-open from one where every
   * row was blocked. So both are drawn, and both are drawn when empty.
   */
  function reconsidered(did: Reconsidered | null | undefined): HTMLElement {
    const node = el('div', 'reconsidered');
    node.dataset['reconsidered'] = '';
    const reopened = isList(did?.reopened) ? did.reopened : [];
    const refused = isList(did?.refused) ? did.refused : [];
    node.append(
      el(
        'div',
        'reopened',
        reopened.length === 0
          ? 'nothing went back in front of a person'
          : `back in front of a person: ${reopened.join(', ')}`,
      ),
    );
    node.append(
      el(
        'div',
        'refused',
        refused.length === 0
          ? 'nothing was refused'
          : `could not go back: ${refused.join(', ')}`,
      ),
    );
    return node;
  }

  /** Whether a ruling can be made at all, and the reason when it cannot. */
  let rulings: HTMLButtonElement[] = [];

  function showWhether(): void {
    const named = who.value.trim() !== '';
    for (const control of rulings) {
      // A control settled by its own click stays disabled. Re-enabling it
      // here would undo the one guard that stops a second ruling.
      if (control.dataset['spent'] !== 'true') {
        control.disabled = !named;
      }
    }
    const note = body.querySelector('[data-need-a-name]');
    if (note !== null) {
      (note as HTMLElement).hidden = named;
    }
  }

  function draw(proposal: ProposalView): HTMLElement {
    const card = el('article', 'proposal');
    const id = textOf(proposal.id);
    card.dataset['proposal'] = id;

    card.append(el('h3', 'proposal-head', describeAction(proposal.action)));
    card.append(field('memory', textOf(proposal.memoryId)));
    card.append(
      field(
        'tier',
        proposal.project === null || proposal.project === undefined
          ? GLOBAL_TIER
          : textOf(proposal.project),
      ),
    );
    card.append(field('state', describeProposalState(proposal.state)));
    card.append(field('raised', moment(proposal.createdAt)));
    card.append(
      field(
        'raised by',
        proposal.proposedBy === null ||
          proposal.proposedBy === undefined ||
          proposal.proposedBy === ''
          ? 'nobody recorded — this row predates the column'
          : textOf(proposal.proposedBy),
      ),
    );
    card.append(el('pre', 'body', textOf(proposal.reason)));

    // Above the controls, always, and never as a confirmation afterwards.
    const finality = el('p', 'finality', FINALITY);
    finality.dataset['finality'] = '';
    card.append(finality);

    const why = input('reason', 'why, in prose');
    const accept = button('accept', 'accept — promote it');
    const reject = button('reject', 'reject');
    rulings.push(accept, reject);

    /**
     * One ruling, and the disabling that makes it one.
     *
     * The controls are closed **before** the request is built, not in a
     * `then`: everything after the first `await` is a later turn of the
     * event loop, and a second click lands in between.
     */
    const rule = (accepted: boolean): void => {
      if (accept.disabled || reject.disabled) {
        return;
      }
      accept.disabled = true;
      reject.disabled = true;
      accept.dataset['spent'] = 'true';
      reject.dataset['spent'] = 'true';
      why.disabled = true;
      card.dataset['settling'] = 'true';
      background(
        (async (): Promise<void> => {
          let said: HTMLElement;
          try {
            said = settlement(
              await transport.post(
                `/v1/proposals/${encodeURIComponent(id)}/resolve`,
                { accept: accepted, reason: why.value, by: who.value.trim() },
              ),
              accepted,
            );
          } catch (problem) {
            said = trouble(
              problemText(
                problem,
                'That ruling was not applied, and the failure said nothing this console' +
                  ' can repeat.',
              ),
            );
          }
          // Re-read either way, and never simply re-enable. A POST that
          // failed is a POST whose fate this console does not know, and
          // the listing is the only thing that says whether this row is
          // still waiting.
          await load();
          body.prepend(said);
        })(),
      );
    };

    accept.addEventListener('click', () => rule(true));
    reject.addEventListener('click', () => rule(false));

    const controls = el('div', 'rulings');
    controls.append(labelled('because', why), accept, reject);
    card.append(controls);
    return card;
  }

  /**
   * What accepting did, including what it pushed out of the global index.
   *
   * `ResolvedProposal.demoted` is surfaced rather than silent for the reason
   * the record gives: an approval adds to the tier every project reads, and a
   * promotion that quietly pushed other memories out of that index would make
   * it shrink for a reason no caller could see. Cold, not deleted.
   */
  function settlement(
    settled: ResolvedProposal | null | undefined,
    accepted: boolean,
  ): HTMLElement {
    const node = el('div', 'settled');
    node.dataset['settled'] = '';
    const promoted = settled?.promotedId;
    node.append(
      el(
        'div',
        'what',
        accepted
          ? typeof promoted === 'string' && promoted !== ''
            ? `promoted, and the global memory is ${promoted}`
            : 'promoted; the answer did not name the global memory'
          : 'rejected, and the curator will not ask again',
      ),
    );
    const demoted = isList(settled?.demoted) ? settled.demoted : [];
    if (demoted.length > 0) {
      node.append(
        el(
          'div',
          'demoted',
          `these fell out of the global index to make room, and are cold rather than` +
            ` deleted: ${demoted.join(', ')}`,
        ),
      );
    }
    return node;
  }

  async function load(): Promise<void> {
    rulings = [];
    let waiting: readonly ProposalView[];
    try {
      waiting = (await transport.get(`/v1/proposals${query()}`)) ?? [];
    } catch (problem) {
      body.replaceChildren(
        trouble(problemText(problem, 'The queue could not be read.')),
      );
      return;
    }
    if (waiting.length === 0) {
      body.replaceChildren(nothing(NOTHING_WAITING));
      return;
    }
    const need = el('p', 'trouble', NEED_A_NAME);
    need.dataset['needAName'] = '';
    body.replaceChildren(need, ...waiting.map(draw));
    showWhether();
  }

  return {
    element: () => shell,
    load,
    destroy(): void {
      // Nothing to stop: no socket and no timer.
    },
  };
}
