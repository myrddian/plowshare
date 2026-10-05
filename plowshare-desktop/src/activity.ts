import { errorMessage } from 'plowshare-client-ts/binding/values';
import { isList } from 'plowshare-client-ts/binding/values';
import {
  administrativeReply,
  type OrchestrationStatus,
} from 'plowshare-client-ts/operations/administrative-replies';
import {
  listingInbox,
  readingInbox,
  inboxPageOf,
  listingRuns,
  runsOf,
  readingRun,
  runStatusOf,
  unreadOf,
  changedOf,
  fieldsOf,
} from 'plowshare-client-ts/operations/client-views';
import {
  listingLive,
  liveRunsOf,
  recordedOf,
  LIVE_STATES,
} from 'plowshare-client-ts/operations/activity';
import type {
  Ask,
  InboxItem,
} from 'plowshare-client-ts/operations/client-views';
import type { CheckedAnswer as Outcome } from 'plowshare-client-ts/operations/response';
import type { ActivityView, DesktopState } from './shared.ts';

const count = (n: unknown): n is number =>
  typeof n === 'number' && Number.isSafeInteger(n) && n >= 0;
const problem = (error: unknown) =>
  error instanceof Error ? error.message : 'Could not read account activity.';

/** Account notifications and reads are independent of foreground chat jobs. */
export class ActivityClient {
  private background(work: Promise<unknown>): void {
    void work.catch((reason: unknown) => {
      this.state().backgroundError = errorMessage(reason);
      this.emit();
    });
  }

  private epoch = 0;
  private revision = 0;
  private countRequest = 0;
  private timer: ReturnType<typeof setInterval> | undefined;
  private flights = new Map<string, Promise<void>>();
  private dirty = new Set<string>();
  private view: ActivityView | undefined;
  private selected: string | undefined;
  private marking = new Set<string>();
  private inboxShown = 20;
  private state: () => DesktopState;
  private send: (ask: Ask) => Promise<Outcome>;
  private emit: () => void;
  constructor(
    state: () => DesktopState,
    send: (ask: Ask) => Promise<Outcome>,
    emit: () => void,
  ) {
    this.state = state;
    this.send = send;
    this.emit = emit;
  }
  reset(clearView = false) {
    this.epoch++;
    this.revision++;
    this.countRequest++;
    clearInterval(this.timer);
    this.timer = undefined;
    this.flights.clear();
    this.dirty.clear();
    this.marking.clear();
    const activity = this.state().activity;
    activity.inbox.loading = false;
    activity.runs.loading = false;
    for (const detail of Object.values(activity.details))
      detail.loading = false;
    if (clearView) {
      this.view = undefined;
      this.selected = undefined;
      activity.view = undefined;
      this.inboxShown = 20;
    }
  }
  private live() {
    const s = this.state();
    return s.mode === 'live' && s.connected;
  }
  async start() {
    const epoch = this.epoch;
    await this.refresh();
    if (!this.live() || epoch !== this.epoch) return;
    this.timer = setInterval(() => {
      this.background(this.refresh());
    }, 15_000);
    this.timer.unref();
  }
  async open(view?: ActivityView) {
    if (
      view !== undefined &&
      view !== 'inbox' &&
      view !== 'runs' &&
      view !== 'definitions' &&
      view !== 'schedules' &&
      view !== 'builder'
    )
      throw new Error('Choose an Activity view.');
    this.view = view;
    this.state().activity.view = view;
    this.emit();
    if (view === 'inbox') await this.inbox();
    if (view === 'runs' || view === 'builder') {
      await this.runs();
      if (this.selected) await this.detail(this.selected);
    }
  }
  async refresh() {
    await Promise.all([
      this.view === 'inbox' ? this.inbox() : this.unread(),
      this.runs(),
      (this.view === 'runs' || this.view === 'builder') && this.selected
        ? this.detail(this.selected)
        : Promise.resolve(),
    ]);
  }
  private read(key: string, work: () => Promise<void>): Promise<void> {
    if (!this.live()) return Promise.resolve();
    this.dirty.add(key);
    const existing = this.flights.get(key);
    if (existing) return existing;
    const epoch = this.epoch;
    const flight = (async () => {
      while (epoch === this.epoch && this.live() && this.dirty.delete(key))
        await work();
    })().finally(() => {
      if (epoch === this.epoch) this.flights.delete(key);
    });
    this.flights.set(key, flight);
    return flight;
  }
  private unread() {
    return this.read('unread', async () => {
      const epoch = this.epoch,
        revision = this.revision,
        request = ++this.countRequest;
      try {
        const ask = listingInbox();
        const answer = await this.send({
          ...ask,
          payload: { ...fieldsOf(ask.payload), limit: 1 },
        });
        const page =
          administrativeReply('inbox.list', answer) && inboxPageOf(answer);
        if (!page) throw new Error('Unread inbox count is unavailable.');
        if (
          epoch === this.epoch &&
          revision === this.revision &&
          request === this.countRequest
        ) {
          const inbox = this.state().activity.inbox;
          inbox.unread = page.unread;
          if (!inbox.loaded) delete inbox.error;
          this.emit();
        }
      } catch (error) {
        if (
          epoch === this.epoch &&
          revision === this.revision &&
          request === this.countRequest
        ) {
          this.state().activity.inbox.error = problem(error);
          this.emit();
        }
      }
    });
  }
  private inbox() {
    return this.read('inbox', async () => {
      const epoch = this.epoch,
        revision = this.revision,
        request = ++this.countRequest;
      const inbox = this.state().activity.inbox;
      inbox.loading = true;
      this.emit();
      try {
        const wanted = this.inboxShown;
        const items: InboxItem[] = [];
        let unread: number | undefined;
        // Re-read the loaded prefix on refresh: arrivals shift offset pages, receipts do not.
        // One lookahead item discovers whether older history exists, without receiving a receipt.
        while (items.length <= wanted) {
          const limit = Math.min(200, wanted + 1 - items.length);
          const answer = await this.send({
            ...listingInbox(),
            payload: { unread: false, offset: items.length, limit },
          });
          const page =
            administrativeReply('inbox.list', answer) && inboxPageOf(answer);
          if (!page)
            throw new Error(
              answer.said ??
                'Could not read the inbox. Previously shown items are retained.',
            );
          if (epoch !== this.epoch) return;
          if (revision !== this.revision) {
            this.dirty.add('inbox');
            return;
          }
          unread ??= page.unread;
          items.push(...page.items);
          if (page.items.length < limit) break;
        }
        if (new Set(items.map((item) => item.id)).size !== items.length)
          throw new Error(
            'Mailbox changed while paging. Refresh to retry; previously shown items are retained.',
          );
        if (epoch !== this.epoch) return;
        // A receipt or account push during this read makes its snapshot stale.
        if (revision !== this.revision) {
          this.dirty.add('inbox');
          return;
        }
        const previouslyRead = new Set(inbox.read);
        inbox.items = items.slice(0, wanted);
        inbox.read = inbox.items
          .filter(
            (item) => item.readAt !== undefined || previouslyRead.has(item.id),
          )
          .map((item) => item.id);
        inbox.more = items.length > wanted;
        if (request === this.countRequest) inbox.unread = unread;
        inbox.loaded = true;
        delete inbox.error;
      } catch (error) {
        if (epoch === this.epoch) inbox.error = problem(error);
      } finally {
        if (epoch === this.epoch) {
          inbox.loading = false;
          this.emit();
        }
      }
    });
  }
  async older() {
    const inbox = this.state().activity.inbox;
    if (this.view !== 'inbox' || !inbox.loaded || !inbox.more)
      throw new Error('No older mailbox items are available.');
    if (!this.live())
      throw new Error('Reconnect before loading older mailbox items.');
    this.inboxShown += 20;
    await this.inbox();
  }
  async mark(id: string) {
    const inbox = this.state().activity.inbox;
    if (this.view !== 'inbox' || !inbox.items.some((item) => item.id === id))
      throw new Error('Only a displayed inbox item can be marked read.');
    if (inbox.read.includes(id) || this.marking.has(id)) return;
    const epoch = this.epoch,
      revision = this.revision;
    if (this.state().mode === 'demo') {
      inbox.read.push(id);
      inbox.unread = Math.max(0, (inbox.unread ?? 0) - 1);
      this.emit();
      return;
    }
    this.marking.add(id);
    this.countRequest++;
    try {
      const answer = await this.send(readingInbox([id]));
      const body = fieldsOf(answer.payload);
      if (
        !administrativeReply('inbox.read', answer) ||
        body['marked'] !== 1 ||
        !count(body['unread'])
      )
        throw new Error(
          answer.said ??
            'Read receipt was not confirmed. Refresh before trying again.',
        );
      if (epoch !== this.epoch) return;
      this.revision++;
      inbox.read.push(id);
      delete inbox.error;
      if (revision === this.revision - 1) inbox.unread = body['unread'];
      else this.background(this.unread());
      this.emit();
    } finally {
      if (epoch === this.epoch) this.marking.delete(id);
    }
  }
  private runs() {
    return this.read('runs', async () => {
      const epoch = this.epoch,
        runs = this.state().activity.runs;
      runs.loading = true;
      this.emit();
      try {
        const answers = await Promise.all(
          [listingRuns(), ...listingLive()].map((ask) => this.send(ask)),
        );
        // TUI readers are tolerant; reject dropped rows rather than imply that a live run ended.
        for (const answer of answers) {
          const raw = fieldsOf(answer.payload)['orchestrations'],
            parsed = runsOf(answer);
          if (
            !administrativeReply('orchestration.list', answer) ||
            !isList(raw) ||
            !parsed ||
            parsed.length !== raw.length
          )
            throw new Error(
              answer.said ??
                'Could not read all run states. The previous run list is retained.',
            );
        }
        const recentAnswer = answers[0];
        if (recentAnswer === undefined)
          throw new Error('run-list request returned no answer');
        const recent = runsOf(recentAnswer)!,
          live = liveRunsOf(answers.slice(1))!;
        if (epoch !== this.epoch) return;
        runs.items = [
          ...new Map([...recent, ...live].map((run) => [run.id, run])).values(),
        ].sort(
          (a, b) =>
            Number(LIVE_STATES.includes(b.state)) -
              Number(LIVE_STATES.includes(a.state)) ||
            b.createdAt.localeCompare(a.createdAt) ||
            a.id.localeCompare(b.id),
        );
        runs.loaded = true;
        runs.limited = answers
          .slice(1)
          .some((answer) => runsOf(answer)!.length >= 200);
        delete runs.error;
        this.emit();
        // Questions must reach chat even when no Activity window is open. Keep the
        // existing paged list authoritative and bound simultaneous status reads.
        const asking = runs.items
          .filter((run) => run.state === 'asking')
          .map((run) => run.id);
        let cursor = 0;
        await Promise.all(
          Array.from({ length: Math.min(4, asking.length) }, async () => {
            while (
              epoch === this.epoch &&
              this.live() &&
              cursor < asking.length
            ) {
              const id = asking[cursor++];
              if (id === undefined) return;
              await this.detail(id);
            }
          }),
        );
      } catch (error) {
        if (epoch === this.epoch) runs.error = problem(error);
      } finally {
        if (epoch === this.epoch) {
          runs.loading = false;
          this.emit();
        }
      }
    });
  }
  async select(id: string) {
    const activity = this.state().activity;
    const known =
      activity.runs.items.some((run) => run.id === id) ||
      Object.values(activity.details).some(
        (detail) =>
          detail.value?.run.id === id ||
          detail.value?.children.some((child) => child.id === id) ||
          detail.value?.run.parent === id,
      );
    if ((this.view !== 'runs' && this.view !== 'builder') || !known)
      throw new Error('Choose an available run.');
    this.selected = id;
    await this.detail(id);
  }
  private detail(id: string) {
    return this.read(`detail:${id}`, async () => {
      const epoch = this.epoch;
      const detail = (this.state().activity.details[id] ??= {});
      detail.loading = true;
      this.emit();
      try {
        const answer = await this.send(readingRun(id)),
          value = runStatusOf(answer),
          raw = fieldsOf(answer.payload);
        if (
          !administrativeReply('orchestration.status', answer) ||
          !value ||
          value.run.id !== id ||
          !(['todos', 'messages', 'children'] as const).every(
            (key, i) =>
              isList(raw[key]) &&
              raw[key].length ===
                [value.stages, value.messages, value.children][i]?.length,
          )
        )
          throw new Error(
            answer.said ??
              'Could not read this run in full. Its previous detail is retained.',
          );
        if (epoch === this.epoch) {
          detail.value = value;
          detail.wire = answer.payload as OrchestrationStatus;
          delete detail.error;
        }
      } catch (error) {
        if (epoch === this.epoch) detail.error = problem(error);
      } finally {
        if (epoch === this.epoch) {
          detail.loading = false;
          this.emit();
        }
      }
    });
  }
  push(value: unknown) {
    const unread = unreadOf(value);
    if (count(unread)) {
      this.revision++;
      this.state().activity.inbox.unread = unread;
      this.emit();
      if (this.view === 'inbox' && this.marking.size === 0)
        this.background(this.inbox());
    }
    const changed = changedOf(value),
      recorded = recordedOf(value);
    if (changed) {
      this.background(this.runs());
      if (
        this.state().activity.details[changed.id]?.wire?.orchestration.state ===
        'asking'
      )
        this.background(this.detail(changed.id));
    }
    if (
      (this.view === 'runs' || this.view === 'builder') &&
      this.selected &&
      (changed || recorded)
    ) {
      const detail = this.state().activity.details[this.selected]?.value;
      if (
        recorded?.root === this.selected ||
        changed?.id === this.selected ||
        detail?.children.some((child) => child.id === changed?.id)
      )
        this.background(this.detail(this.selected));
    }
  }
}
