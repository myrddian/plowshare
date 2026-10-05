import { errorMessage } from 'plowshare-client-ts/binding/values';
import {
  SWARM_PAGE,
  swarmMembers,
  readingSwarmActivity,
  swarmActivityOf,
} from 'plowshare-client-ts/operations/swarm';
import type { DesktopState } from './shared.ts';
import type { BoardView, Reading } from './board-shared.ts';
import type { OperationTransport } from 'plowshare-client-ts/operations/response';
import type {
  Operation,
  Payloads,
} from 'plowshare-client-ts/operations/direct';
import type { BoardInspection } from 'plowshare-client-ts/operations/board';

export {
  topicsOf,
  detailOf,
  swarmOf,
} from 'plowshare-client-ts/operations/board';
import {
  topicsOf,
  detailOf,
  swarmOf,
  isBoardMessage,
  isBoardTopic,
} from 'plowshare-client-ts/operations/board';

/** Lazy inspection, plus explicitly submitted person posts with durable request identities. */
export class BoardClient {
  private background(work: Promise<unknown>): void {
    void work.catch((reason: unknown) => {
      this.state().backgroundError = errorMessage(reason);
      this.emit();
    });
  }

  private epoch = 0;
  private pages = 1;
  private timer: ReturnType<typeof setInterval> | undefined;
  private flights = new Map<string, Promise<void>>();
  private state: () => DesktopState;
  private ask: OperationTransport['ask'];
  private emit: () => void;
  constructor(
    state: () => DesktopState,
    ask: OperationTransport['ask'],
    emit: () => void,
  ) {
    this.state = state;
    this.ask = ask;
    this.emit = emit;
  }
  async postingTopics(project: string, more = false) {
    if (
      !this.state().connected ||
      !this.state().projects.some((row) => row.name === project)
    )
      throw new Error(
        'Choose an available project and connect before posting.',
      );
    const board = this.state().board;
    if (board.posting?.busy || board.posting?.loading)
      throw new Error('Wait for the current board request.');
    const previous =
      board.posting?.project === project ? board.posting : undefined;
    if (more && !previous?.more)
      throw new Error('Choose the current topic list before loading more.');
    const epoch = this.epoch,
      offset = more ? previous!.topics.length : 0;
    const posting: NonNullable<BoardInspection['posting']> = {
      project,
      topics: more ? previous!.topics : [],
      more: false,
      loading: true,
    };
    board.posting = posting;
    this.emit();
    try {
      const answer = await this.ask('board.topics', {
        project,
        offset,
        limit: 200,
      });
      if (answer.code !== 'OK')
        throw new Error(answer.said ?? 'The topic list was refused.');
      const page = topicsOf(answer.payload);
      if (
        page.offset !== offset ||
        page.topics.some((row) => row.topic.project !== project)
      )
        throw new Error('The topic list names a different project or page.');
      if (epoch === this.epoch) {
        posting.topics = [...posting.topics, ...page.topics];
        posting.more = page.more;
      }
    } catch (error) {
      if (epoch === this.epoch)
        posting.error =
          error instanceof Error ? error.message : errorMessage(error);
    } finally {
      if (epoch === this.epoch) {
        posting.loading = false;
        this.emit();
      }
    }
  }
  async post(project: string, topic: string, body: string, requestId: string) {
    const state = this.state(),
      posting = state.board.posting;
    if (
      !state.connected ||
      !posting ||
      posting.project !== project ||
      posting.busy ||
      posting.loading ||
      !posting.topics.some(
        (row) =>
          row.topic.id === topic &&
          row.topic.project === project &&
          row.topic.state !== 'closed',
      )
    )
      throw new Error('Choose an open topic from the selected project.');
    if (
      typeof body !== 'string' ||
      !body.trim() ||
      body.length > 16000 ||
      typeof requestId !== 'string' ||
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
        requestId,
      )
    )
      throw new Error('Enter a message and valid request identity.');
    const epoch = this.epoch;
    posting.busy = true;
    delete posting.error;
    delete posting.notice;
    this.emit();
    try {
      const answer = await this.ask('board.post', {
        project,
        topic,
        body,
        requestId,
      });
      if (answer.code !== 'OK')
        throw new Error(answer.said ?? 'The board post was not confirmed.');
      const receipt = answer.payload as {
        requestId?: string;
        message?: unknown;
      };
      if (
        receipt?.requestId !== requestId ||
        !isBoardMessage(receipt.message) ||
        receipt.message.topic !== topic ||
        receipt.message.body !== body ||
        receipt.message.author !== state.handle ||
        receipt.message.authorKind !== 'person'
      )
        throw new Error('The server did not confirm this person’s post.');
      if (epoch === this.epoch) {
        posting.notice = 'Posted to the board.';
        this.emit();
        await this.refresh();
      }
    } catch (error) {
      if (epoch === this.epoch) {
        posting.error = `${error instanceof Error ? error.message : errorMessage(error)} Your draft and request identity are retained. No post is retried automatically.`;
        this.emit();
      }
      throw error;
    } finally {
      if (epoch === this.epoch) {
        posting.busy = false;
        this.emit();
      }
    }
  }
  async retry(
    project: string,
    topic: string,
    member: string,
    requestId: string,
    maxTurns: number,
    reconcile = false,
  ) {
    const state = this.state(),
      board = state.board,
      previous = board.retrying;
    if (
      !state.connected ||
      (!reconcile && !state.projects.some((row) => row.name === project)) ||
      previous?.busy
    )
      throw new Error('Connect and wait for the current member retry.');
    if (
      !Number.isSafeInteger(maxTurns) ||
      maxTurns < 1 ||
      maxTurns > 2147483647 ||
      !/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(requestId)
    )
      throw new Error(
        'Choose a positive whole step limit and valid request identity.',
      );
    const detail = board.details[topic]?.value;
    const chosen =
      detail?.topic ??
      board.swarm.value?.topics.find((row) => row.topic.id === topic)?.topic;
    const seat = (detail?.seats ?? board.swarm.value?.seats ?? []).find(
      (row) => row.seat.topic === topic && row.seat.occupant === member,
    );
    const recovering =
      previous?.requestId === requestId &&
      previous.project === project &&
      previous.topic === topic &&
      previous.member === member &&
      previous.maxTurns === maxTurns;
    // A retained request may already have succeeded and disappeared from active snapshots.
    // The server rechecks ownership/membership and returns the original durable receipt.
    if (
      !recovering &&
      !reconcile &&
      (!chosen || chosen.project !== project || !seat || member === '@opener')
    )
      throw new Error(
        'Choose a failed member on an open topic. Refresh to see its current state.',
      );
    const epoch = this.epoch;
    const retrying = (board.retrying = {
      project,
      topic,
      member,
      requestId,
      maxTurns,
      busy: true,
    } as NonNullable<BoardInspection['retrying']>);
    this.emit();
    try {
      const answer = await this.ask('board.retry', {
        project,
        topic,
        member,
        requestId,
        maxTurns,
      });
      if (answer.code !== 'OK') {
        retrying.refused = [
          'BAD_REQUEST',
          'NOT_FOUND',
          'CONFLICT',
          'VALIDATION_FAILED',
        ].includes(answer.code);
        throw new Error(answer.said ?? 'The member retry was not confirmed.');
      }
      const receipt = answer.payload as {
        requestId?: string;
        member?: string;
        maxTurns?: number;
        message?: unknown;
      };
      if (
        receipt?.requestId !== requestId ||
        receipt.member !== member ||
        receipt.maxTurns !== maxTurns ||
        !isBoardMessage(receipt.message) ||
        receipt.message.topic !== topic ||
        receipt.message.authorKind !== 'person' ||
        receipt.message.author !== state.handle ||
        !receipt.message.mentions.includes(member)
      )
        throw new Error('The server did not confirm this member retry.');
      if (epoch === this.epoch) {
        retrying.notice = 'Member retry queued in its existing conversation.';
        this.emit();
        await this.refresh();
      }
    } catch (error) {
      if (epoch === this.epoch) {
        retrying.error = `${error instanceof Error ? error.message : errorMessage(error)} Your request identity is retained. Retry explicitly to check the same request.`;
        this.emit();
      }
      throw error;
    } finally {
      if (epoch === this.epoch) {
        retrying.busy = false;
        this.emit();
      }
    }
  }
  async create(
    project: string,
    title: string,
    label: string,
    body: string,
    requestId: string,
    maxModelCalls?: number,
  ) {
    const state = this.state(),
      board = state.board;
    if (
      !state.connected ||
      !state.projects.some((row) => row.name === project) ||
      board.opening?.busy
    )
      throw new Error(
        'Choose an available project and connect before creating a topic.',
      );
    if (
      ![title, label, body].every(
        (value) => typeof value === 'string' && value.trim(),
      ) ||
      body.length > 16000 ||
      typeof requestId !== 'string' ||
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
        requestId,
      )
    )
      throw new Error(
        'Enter a title, label, opening message and valid request identity.',
      );
    if (
      maxModelCalls !== undefined &&
      (!Number.isSafeInteger(maxModelCalls) ||
        maxModelCalls < 2 ||
        maxModelCalls > 2147483647)
    )
      throw new Error(
        'The model call limit must be a whole number of at least two.',
      );
    const epoch = this.epoch,
      opening = (board.opening = { busy: true } as NonNullable<
        BoardInspection['opening']
      >);
    this.emit();
    try {
      const answer = await this.ask('board.open', {
        project,
        title,
        label,
        body,
        requestId,
        ...(maxModelCalls === undefined ? {} : { maxModelCalls }),
      });
      if (answer.code !== 'OK')
        throw new Error(answer.said ?? 'Topic creation was not confirmed.');
      const receipt = answer.payload as {
        requestId?: string;
        topic?: unknown;
        message?: unknown;
      };
      if (
        receipt?.requestId !== requestId ||
        !isBoardTopic(receipt.topic) ||
        receipt.topic.project !== project ||
        receipt.topic.account !== state.handle ||
        receipt.topic.openerKind !== 'person' ||
        receipt.topic.opener !== state.handle ||
        !isBoardMessage(receipt.message) ||
        receipt.message.topic !== receipt.topic.id ||
        receipt.message.body !== body ||
        receipt.message.authorKind !== 'person' ||
        receipt.message.author !== state.handle
      )
        throw new Error('The server did not confirm this person’s topic.');
      if (epoch === this.epoch) {
        opening.notice = 'Topic created.';
        board.project = project;
        board.selected = receipt.topic.id;
        this.pages = 1;
        if (board.posting?.project === project) delete board.posting;
        this.emit();
        await this.refresh();
      }
    } catch (error) {
      if (epoch === this.epoch) {
        opening.error = `${error instanceof Error ? error.message : errorMessage(error)} Your draft and request identity are retained.`;
        this.emit();
      }
      throw error;
    } finally {
      if (epoch === this.epoch) {
        opening.busy = false;
        this.emit();
      }
    }
  }
  reset() {
    this.epoch++;
    clearInterval(this.timer);
    this.timer = undefined;
    this.flights.clear();
    const b = this.state().board;
    b.topics.loading = false;
    b.swarm.loading = false;
    if (b.opening) b.opening.busy = false;
    if (b.retrying) b.retrying.busy = false;
    if (b.posting) {
      b.posting.loading = false;
      b.posting.busy = false;
    }
    for (const detail of Object.values(b.details)) detail.loading = false;
    for (const reading of Object.values(b.activity ?? {}))
      reading.loading = false;
  }
  async open(view?: BoardView, project?: string) {
    if (view !== undefined && view !== 'board' && view !== 'swarm')
      throw new Error('Choose Board or Swarm.');
    this.reset();
    const b = this.state().board;
    if (view === undefined) delete b.view;
    else b.view = view;
    if (project !== b.project) {
      if (project === undefined) delete b.project;
      else b.project = project;
      delete b.selected;
      if (this.state().mode === 'live') b.topics = {};
      this.pages = 1;
    }
    this.emit();
    if (!view) return;
    b.memberLimit ??= SWARM_PAGE;
    await this.refresh();
    this.startTimer();
  }
  private startTimer() {
    if (
      this.state().mode !== 'live' ||
      !this.state().connected ||
      !this.state().board.view ||
      this.timer
    )
      return;
    this.timer = setInterval(() => {
      this.background(this.refresh());
    }, 4000);
    this.timer.unref();
  }
  async reconnect() {
    if (this.state().board.view) {
      await this.refresh();
      this.startTimer();
    }
  }
  private read<T>(
    key: string,
    target: Reading<T>,
    type: Operation,
    payload: Payloads[Operation],
    parse: (
      v: Awaited<ReturnType<OperationTransport['ask']>>['payload'],
    ) => T | Promise<T>,
    accept?: (v: T) => void,
  ) {
    if (this.state().mode !== 'live' || !this.state().connected)
      return Promise.resolve();
    const existing = this.flights.get(key);
    if (existing) return existing;
    const epoch = this.epoch;
    target.loading = true;
    this.emit();
    const work = (async () => {
      try {
        const answer = await this.ask(type, payload);
        if (epoch !== this.epoch) return;
        if (answer.code !== 'OK')
          throw new Error(
            answer.said ??
              `${type} is unavailable. Update the server and try Refresh.`,
          );
        const value = await parse(answer.payload);
        if (epoch !== this.epoch) return;
        target.value = value;
        target.updatedAt = new Date().toISOString();
        delete target.error;
        accept?.(value);
      } catch (error) {
        if (epoch === this.epoch)
          target.error =
            error instanceof Error ? error.message : errorMessage(error);
      } finally {
        if (epoch === this.epoch) {
          target.loading = false;
          this.flights.delete(key);
          this.emit();
        }
      }
    })();
    this.flights.set(key, work);
    return work;
  }
  async refresh() {
    const b = this.state().board;
    if (!b.view) return;
    if (b.view === 'swarm') await this.members();
    else
      await Promise.all([
        this.topics(),
        b.selected ? this.detail(b.selected) : Promise.resolve(),
      ]);
  }
  private async members() {
    const b = this.state().board,
      epoch = this.epoch;
    await this.read('swarm', b.swarm, 'swarm.status', {}, swarmOf);
    if (epoch !== this.epoch || b.swarm.error || !b.swarm.value) return;
    const visible = swarmMembers(b.swarm.value, b.project).slice(
      0,
      b.memberLimit ?? SWARM_PAGE,
    );
    const conversations = [
      ...new Set(visible.map((m) => m.seat.seat.conversation)),
    ];
    const activity = (b.activity ??= {});
    // Bound fan-out, including after a view closes while a batch is pending.
    for (
      let at = 0;
      at < conversations.length && epoch === this.epoch;
      at += 4
    ) {
      await Promise.all(
        conversations.slice(at, at + 4).map((conversation) => {
          const ask = readingSwarmActivity(conversation);
          return this.read(
            `member:${conversation}`,
            (activity[conversation] ??= {}),
            ask.type,
            ask.payload,
            swarmActivityOf,
          );
        }),
      );
    }
  }
  private topics() {
    const b = this.state().board,
      epoch = this.epoch,
      pages = this.pages;
    let more = false;
    return this.read(
      'topics',
      b.topics,
      'board.topics',
      {
        ...(b.project === undefined ? {} : { project: b.project }),
        limit: 200,
      },
      async (v) => {
        let page = topicsOf(v);
        if (page.offset !== 0)
          throw new Error('The server returned a different topic page.');
        const rows = [...page.topics];
        for (let i = 1; i < pages && page.more; i++) {
          const answer = await this.ask('board.topics', {
            ...(b.project === undefined ? {} : { project: b.project }),
            offset: i * 200,
            limit: 200,
          });
          if (epoch !== this.epoch) return [];
          if (answer.code !== 'OK')
            throw new Error(answer.said ?? 'Could not read every topic page.');
          page = topicsOf(answer.payload);
          if (page.offset !== i * 200)
            throw new Error('The server returned a different topic page.');
          rows.push(...page.topics);
        }
        more = page.more;
        return [...new Map(rows.map((row) => [row.topic.id, row])).values()];
      },
      () => {
        b.topics.more = more;
      },
    );
  }
  async more() {
    const b = this.state().board;
    if (b.view === 'swarm') {
      b.memberLimit = (b.memberLimit ?? SWARM_PAGE) + SWARM_PAGE;
      await this.members();
      return;
    }
    if (b.view !== 'board' || !b.topics.more || b.topics.loading) return;
    this.pages++;
    await this.topics();
  }
  conversation(id: string) {
    const b = this.state().board,
      detail = b.selected ? b.details[b.selected]?.value : undefined;
    const member =
      b.view === 'swarm' && b.swarm.value
        ? swarmMembers(b.swarm.value, b.project)
            .slice(0, b.memberLimit ?? SWARM_PAGE)
            .find((m) => m.seat.seat.conversation === id)
        : undefined;
    const allowed =
      b.view === 'board' &&
      detail &&
      (detail.seats.some((s) => s.seat.conversation === id) ||
        detail.messages.some((m) => m.conversation === id) ||
        detail.topic.originConversation === id);
    if (!member && !allowed)
      throw new Error('Choose a displayed board conversation or swarm member.');
    if (this.state().mode === 'demo' && !this.state().history[id]) {
      this.state().history[id] = {
        entries: [...(b.activity?.[id]?.value?.entries ?? [])],
        more: false,
      };
    }
    const project = member?.topic?.topic.project ?? detail?.topic.project;
    if (!this.state().conversations.some((c) => c.id === id))
      this.state().conversations.push({
        id,
        ...(project === undefined ? {} : { project }),
        title: member
          ? `Swarm · ${member.seat.seat.occupant}`
          : `Board · ${detail!.topic.title}`,
      });
  }
  async select(id: string) {
    const b = this.state().board;
    const known =
      [...(b.topics.value ?? []), ...(b.swarm.value?.topics ?? [])].some(
        (t) => t.topic.id === id,
      ) ||
      b.swarm.value?.ready.some((r) => r.topic === id) ||
      Object.values(b.details).some(
        (d) =>
          d.value?.topic.parent === id ||
          d.value?.decisions.some((c) => c.child === id),
      );
    if (!b.view || !known) throw new Error('Choose an available board topic.');
    b.selected = id;
    this.emit();
    await this.detail(id);
  }
  private detail(id: string) {
    const b = this.state().board,
      target = (b.details[id] ??= {});
    return this.read(
      `detail:${id}`,
      target,
      'board.messages',
      { topic: id },
      (v) => {
        const d = detailOf(v);
        if (d.topic.id !== id)
          throw new Error('The server returned a different topic.');
        return d;
      },
    );
  }
}
