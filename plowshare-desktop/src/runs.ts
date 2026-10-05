import { isList } from 'plowshare-client-ts/binding/values';
import { randomUUID } from 'node:crypto';
import {
  request,
  resultOf,
  type Request as WsRequest,
} from 'plowshare-client-ts/operations/direct';
import {
  recordReply,
  type RecordView,
} from 'plowshare-client-ts/operations/records';
import { runStatusOf } from 'plowshare-client-ts/operations/inspection';
import type {
  DefinitionView,
  OrchestrationStatus,
} from 'plowshare-client-ts/operations/administrative-replies';
import type { CheckedAnswer as Outcome } from 'plowshare-client-ts/operations/response';
import type { Choice } from 'plowshare-client-ts/operations/session';
import { orchestrationCallId } from './run-navigation.ts';
import type { Entry } from 'plowshare-client-ts/operations/client-views';
import { stepsOf, stepKey } from 'plowshare-client-ts/operations/trajectory';
import { runQuestion, type DesktopState } from './shared.ts';

const problem = (error: unknown) =>
  error instanceof Error
    ? error.message
    : 'Could not read orchestration information.';
/** Native orchestration inspection and explicit human decisions. Never start a turn or replay a mutation. */
export class RunClient {
  private epoch = 0;
  private reads = new Map<string, number>();
  private selected: string | undefined;
  private pending = new Set<string>();
  private resumes = new Map<
    string,
    { failure: string | null; requestId: string }
  >();
  private navigationReads = new Map<string, Promise<void>>();
  private navigationDirty = new Set<string>();
  private navigationViews = new Set<string>();
  private state: () => DesktopState;
  private send: (ask: WsRequest) => Promise<Outcome>;
  private emit: () => void;
  constructor(
    state: () => DesktopState,
    send: RunClient['send'],
    emit: () => void,
  ) {
    this.state = state;
    this.send = send;
    this.emit = emit;
  }
  pause() {
    this.selected = undefined;
  }
  followNavigation(id: string, following: boolean) {
    if (following) {
      this.known(id);
      this.navigationViews.add(id);
    } else this.navigationViews.delete(id);
  }
  reset() {
    this.epoch++;
    this.reads.clear();
    this.pending.clear();
    this.selected = undefined;
    this.navigationReads.clear();
    this.navigationDirty.clear();
    const activity = this.state().activity;
    if (activity.definitions) activity.definitions.loading = false;
    for (const page of Object.values(activity.records ?? {}))
      page.loading = false;
    for (const page of Object.values(activity.navigation ?? {}))
      page.loading = false;
    for (const decision of Object.values(activity.decisions ?? {}))
      decision.busy = false;
  }
  private live() {
    if (!this.state().connected || this.state().mode !== 'live')
      throw new Error('Connect before managing orchestrations.');
  }
  private known(id: string) {
    if (
      typeof id !== 'string' ||
      !id.trim() ||
      id.length > 512 ||
      !(
        this.state().activity.runs.items.some((run) => run.id === id) ||
        Object.values(this.state().activity.details).some(
          (detail) =>
            detail.value?.run.id === id ||
            detail.value?.run.parent === id ||
            detail.value?.children.some((child) => child.id === id),
        )
      )
    )
      throw new Error('Choose an available run.');
  }
  private async checked(ask: WsRequest): Promise<Outcome> {
    const answer = await this.send(ask),
      result = resultOf(ask, answer);
    if (result.kind === 'refused')
      throw new Error(answer.said ?? `The server refused ${ask.type}.`);
    if (result.kind === 'invalid-response')
      throw new Error(
        `The server returned incomplete ${ask.type} information. Inspect the current state before retrying.`,
      );
    return answer;
  }
  async definitions(project?: string) {
    this.live();
    if (
      project !== undefined &&
      !this.state().projects.some((row) => row.name === project)
    )
      throw new Error('Choose an available project.');
    const epoch = this.epoch,
      revision = (this.reads.get('definitions') ?? 0) + 1;
    this.reads.set('definitions', revision);
    const activity = this.state().activity,
      previous = activity.definitions;
    activity.definitions = {
      ...previous,
      items: previous?.items ?? [],
      loading: true,
    };
    this.emit();
    try {
      const answer = await this.checked(
        request('orchestration.definitions', {
          ...(project === undefined ? {} : { project }),
        }),
      );
      if (epoch === this.epoch && revision === this.reads.get('definitions'))
        activity.definitions = {
          ...(project === undefined ? {} : { project: project }),
          items: (answer.payload as { definitions: DefinitionView[] })
            .definitions,
        };
    } catch (error) {
      if (epoch === this.epoch && revision === this.reads.get('definitions')) {
        activity.definitions.error = problem(error);
        activity.definitions.loading = false;
      }
    } finally {
      if (epoch === this.epoch && revision === this.reads.get('definitions'))
        this.emit();
    }
  }
  async record(id: string, before?: number, kinds?: readonly string[]) {
    this.live();
    this.known(id);
    this.selected = id;
    if (before !== undefined && (!Number.isSafeInteger(before) || before < 1))
      throw new Error('Invalid record cursor.');
    if (
      kinds !== undefined &&
      (!isList(kinds) ||
        kinds.length > 20 ||
        kinds.some(
          (kind) =>
            typeof kind !== 'string' || !kind.trim() || kind.length > 128,
        ))
    )
      throw new Error('Choose valid record kinds.');
    const activity = this.state().activity,
      records = (activity.records ??= {}),
      previous = records[id];
    if (before !== undefined && (!previous?.more || before !== previous.oldest))
      throw new Error('Read the current record before loading earlier rows.');
    const filter = kinds ?? previous?.kinds,
      changed =
        JSON.stringify(filter ?? []) !== JSON.stringify(previous?.kinds ?? []);
    const epoch = this.epoch,
      revision = (this.reads.get(id) ?? 0) + 1;
    this.reads.set(id, revision);
    records[id] = {
      ...previous,
      root: previous?.root ?? id,
      rows: previous?.rows ?? [],
      through: previous?.through ?? 0,
      oldest: previous?.oldest ?? null,
      more: previous?.more ?? false,
      loading: true,
    };
    this.emit();
    try {
      const answer = await this.checked(
        request('orchestration.record', {
          root: id,
          limit: 100,
          ...(before === undefined ? { tail: true } : { before }),
          ...(filter?.length ? { kinds: filter } : {}),
        }),
      );
      const page = recordReply(answer.payload)!;
      if (epoch !== this.epoch || revision !== this.reads.get(id)) return;
      if (previous?.rows.length && page.root !== previous.root)
        throw new Error(
          'The record now names another tree. Its previous rows are retained.',
        );
      const rows = [
        ...new Map(
          [...(changed ? [] : (previous?.rows ?? [])), ...page.rows].map(
            (row) => [row.ordinal, row],
          ),
        ).values(),
      ].sort((a, b) => a.ordinal - b.ordinal);
      records[id] = {
        root: page.root,
        rows,
        through: Math.max(previous?.through ?? 0, page.through),
        oldest: rows[0]?.ordinal ?? null,
        more:
          before !== undefined || changed || !previous?.rows.length
            ? page.more === true
            : previous.more,
        ...(filter === undefined ? {} : { kinds: filter }),
      };
    } catch (error) {
      if (epoch === this.epoch && revision === this.reads.get(id)) {
        records[id].error = problem(error);
        records[id].loading = false;
      }
    } finally {
      if (epoch === this.epoch && revision === this.reads.get(id)) this.emit();
    }
  }
  /** Small transition-only pages keep navigation complete even when tool history is long
   * or the run record has a filter. Failed reads preserve the previous navigation snapshot. */
  async navigation(id: string): Promise<void> {
    this.live();
    this.known(id);
    const existing = this.navigationReads.get(id);
    if (existing) return existing;
    this.navigationDirty.delete(id);
    const epoch = this.epoch,
      navigation = (this.state().activity.navigation ??= {});
    navigation[id] = {
      ...navigation[id],
      rows: navigation[id]?.rows ?? [],
      loading: true,
    };
    this.emit();
    const work = (async () => {
      try {
        const rows: RecordView[] = [];
        let before: number | undefined, root: string | undefined;
        for (;;) {
          const answer = await this.checked(
            request('orchestration.record', {
              root: id,
              limit: 100,
              kinds: ['stage_moved'],
              ...(before === undefined ? { tail: true } : { before }),
            }),
          );
          if (epoch !== this.epoch) return;
          const page = recordReply(answer.payload)!;
          if (root !== undefined && page.root !== root)
            throw new Error('Stage history now names another tree.');
          root = page.root;
          rows.push(...page.rows);
          if (!page.more) break;
          if (
            page.oldest === null ||
            (before !== undefined && page.oldest >= before)
          )
            throw new Error(
              'Stage history did not advance to an earlier page.',
            );
          before = page.oldest;
        }
        navigation[id] = { rows: rows.sort((a, b) => a.ordinal - b.ordinal) };
      } catch (error) {
        if (epoch === this.epoch)
          navigation[id] = {
            rows: navigation[id]?.rows ?? [],
            error: problem(error),
          };
      } finally {
        if (epoch === this.epoch) this.emit();
      }
    })();
    this.navigationReads.set(id, work);
    try {
      await work;
    } finally {
      if (this.navigationReads.get(id) === work) {
        this.navigationReads.delete(id);
        if (epoch === this.epoch && this.navigationDirty.delete(id))
          void this.navigation(id).catch(() => {});
      }
    }
  }
  private async status(id: string): Promise<OrchestrationStatus> {
    const epoch = this.epoch;
    const answer = await this.checked(request('orchestration.status', { id }));
    if (epoch !== this.epoch)
      throw new Error('The connection changed while reading the run.');
    const wire = answer.payload as OrchestrationStatus,
      value = runStatusOf(answer)!;
    this.state().activity.details[id] = { value, wire };
    this.emit();
    return wire;
  }
  /** Resolve a recorded spawn without replaying the tool. Server status supplies
   * the conductor and must confirm that this inspected conversation is its caller. */
  async spawnedConversation(
    parent: string,
    key: string,
    entries: readonly Entry[] = this.state().history[parent]?.entries ?? [],
  ): Promise<string> {
    const step = stepsOf(entries).find((step) => stepKey(step) === key);
    if (step?.kind !== 'call')
      throw new Error('Choose a recorded delegation in this trajectory.');
    if (step.opened?.conversation) return step.opened.conversation;
    const id = orchestrationCallId(step);
    if (!id)
      throw new Error('Choose a recorded delegation in this trajectory.');
    this.live();
    const epoch = this.epoch;
    const answer = await this.checked(request('orchestration.status', { id }));
    if (epoch !== this.epoch)
      throw new Error('The connection changed while reading the run.');
    const wire = answer.payload as OrchestrationStatus,
      value = runStatusOf(answer)!;
    if (
      value.run.id !== id ||
      wire.orchestration.callerConversation !== parent ||
      !wire.orchestration.conductorConversation
    )
      throw new Error('This run does not belong to the selected call.');
    this.state().activity.details[id] = { value, wire };
    this.emit();
    return wire.orchestration.conductorConversation;
  }
  async answer(
    id: string,
    question: string,
    answer?: string,
    choices?: readonly Choice[],
  ) {
    this.live();
    this.known(id);
    const shown = this.state().activity.details[id]?.wire;
    if (
      !shown ||
      runQuestion(shown) !== question ||
      shown.orchestration.state !== 'asking'
    )
      throw new Error('Read the current question before answering.');
    if (
      answer !== undefined &&
      (typeof answer !== 'string' || answer.length > 2000)
    )
      throw new Error('Keep the answer within 2,000 characters.');
    this.validateChoices(shown, choices, answer);
    await this.decision(
      id,
      async (guard) => {
        const current = await this.status(id);
        guard();
        if (
          runQuestion(current) !== question ||
          current.orchestration.state !== 'asking'
        )
          throw new Error(
            'This question changed or was answered elsewhere. Review its current state.',
          );
        return this.checked(
          request('orchestration.answer', {
            id,
            ...(answer === undefined ? {} : { answer }),
            ...(choices === undefined
              ? {}
              : {
                  choices: choices.map((choice) => ({
                    ...choice,
                    chosen: [...choice.chosen],
                  })),
                }),
          }),
        );
      },
      'Answer recorded.',
    );
    if (shown.orchestration.pendingCap === 'install')
      await this.definitions(shown.orchestration.project ?? undefined);
  }
  private validateChoices(
    status: OrchestrationStatus,
    choices: readonly Choice[] | undefined,
    answer?: string,
  ) {
    const latest = [...status.messages]
      .reverse()
      .find((message) => message.kind === 'question');
    if (choices !== undefined) {
      const raw = latest?.structure as {
        questions?: {
          header: string;
          multi: boolean;
          options: { label: string }[];
        }[];
      } | null;
      const questions = raw?.questions;
      if (
        !isList(choices) ||
        !isList(questions) ||
        choices.length !== questions.length
      )
        throw new Error('Answer each displayed question.');
      questions.forEach((question, index) => {
        const choice = choices[index];
        if (
          !choice ||
          choice.header !== question.header ||
          !isList(choice.chosen) ||
          new Set(choice.chosen).size !== choice.chosen.length ||
          choice.chosen.some(
            (label: string) =>
              !question.options.some((option) => option.label === label),
          ) ||
          (!question.multi && choice.chosen.length > 1) ||
          (!choice.chosen.length && !choice.other?.trim()) ||
          [choice.other, choice.note].some(
            (text) =>
              text !== undefined &&
              (typeof text !== 'string' || text.length > 2000),
          )
        )
          throw new Error(
            'Choose valid options or enter another answer for each question.',
          );
      });
    } else if ((latest?.structure as { questions?: unknown } | null)?.questions)
      throw new Error('Choose answers for the displayed structured questions.');
    else if (!answer?.trim())
      throw new Error('Enter an answer before sending.');
  }
  async resume(id: string) {
    this.live();
    this.known(id);
    await this.decision(
      id,
      async (guard) => {
        const current = await this.status(id);
        guard();
        if (
          current.orchestration.state !== 'failed' ||
          current.orchestration.parent !== null
        )
          throw new Error('Choose a failed root run to resume.');
        const failure = current.orchestration.endedAt;
        const key = JSON.stringify([
          this.state().base,
          this.state().handle,
          id,
        ]);
        let retained = this.resumes.get(key);
        if (!retained || retained.failure !== failure) {
          retained = { failure, requestId: randomUUID() };
          this.resumes.set(key, retained);
        }
        return this.checked(
          request('orchestration.resume', {
            id,
            requestId: retained.requestId,
          }),
        );
      },
      'Resume request recorded.',
    );
  }
  async cancel(id: string) {
    this.live();
    this.known(id);
    await this.decision(
      id,
      async (guard) => {
        const current = await this.status(id);
        guard();
        if (
          !['running', 'asking', 'waiting'].includes(
            current.orchestration.state,
          )
        )
          throw new Error('This run has already ended.');
        return this.checked(request('orchestration.cancel', { id }));
      },
      'Run and its descendants cancelled.',
    );
  }
  private async decision(
    id: string,
    work: (guard: () => void) => Promise<Outcome>,
    notice: string,
  ) {
    if (this.pending.has(id))
      throw new Error('A decision for this run is already being sent.');
    const epoch = this.epoch,
      decisions = (this.state().activity.decisions ??= {});
    this.pending.add(id);
    decisions[id] = { busy: true };
    this.emit();
    const guard = () => {
      if (epoch !== this.epoch)
        throw new Error('The connection changed before sending the decision.');
      this.live();
    };
    let confirmed = false;
    try {
      guard();
      await work(guard);
      confirmed = true;
      if (epoch === this.epoch) {
        decisions[id] = { notice };
        await this.status(id);
      }
    } catch (error) {
      if (epoch === this.epoch)
        decisions[id] = {
          ...(confirmed ? { notice } : {}),
          error: `${problem(error)} Refresh before making another decision.`,
        };
      throw error;
    } finally {
      if (epoch === this.epoch) {
        this.pending.delete(id);
        decisions[id].busy = false;
        this.emit();
      }
    }
  }
  conversation(id: string, actor: 'conductor' | 'caller'): string {
    this.known(id);
    const run = this.state().activity.details[id]?.wire?.orchestration;
    const conversation =
      actor === 'conductor'
        ? run?.conductorConversation
        : actor === 'caller'
          ? run?.callerConversation
          : undefined;
    if (!conversation)
      throw new Error(
        'Read this run before opening its recorded conversation.',
      );
    return conversation;
  }
  push(value: unknown) {
    const push = value as {
      kind?: string;
      root?: string;
      settled?: number;
    } | null;
    if (push?.kind === 'orchestration.recorded')
      for (const id of Object.keys(this.state().activity.navigation ?? {})) {
        if (
          (id === this.selected || this.navigationViews.has(id)) &&
          this.state().activity.records?.[id]?.root === push.root
        ) {
          if (this.navigationReads.has(id)) this.navigationDirty.add(id);
          else void this.navigation(id).catch(() => {});
        }
      }
    if (
      !this.selected ||
      push?.kind !== 'orchestration.recorded' ||
      this.state().activity.records?.[this.selected]?.root !== push.root
    )
      return;
    const id = this.selected,
      ordinal = push.settled;
    void this.record(id)
      .then(() => {
        if (
          typeof ordinal === 'number' &&
          Number.isSafeInteger(ordinal) &&
          ordinal > 0 &&
          this.selected === id &&
          this.state().activity.records?.[id]?.rows.some(
            (row) => row.ordinal === ordinal,
          )
        )
          return this.settled(id, ordinal);
      })
      .catch(() => {});
  }
  private async settled(id: string, ordinal: number) {
    const epoch = this.epoch,
      revision = this.reads.get(id);
    try {
      const answer = await this.checked(
          request('orchestration.record', {
            root: id,
            after: ordinal - 1,
            limit: 1,
            kinds: ['tool_call'],
          }),
        ),
        page = recordReply(answer.payload)!;
      const row = page.rows.find((row) => row.ordinal === ordinal),
        shown = this.state().activity.records?.[id];
      if (
        epoch === this.epoch &&
        revision === this.reads.get(id) &&
        row &&
        shown?.root === page.root
      ) {
        shown.rows = shown.rows.map((each: RecordView) =>
          each.ordinal === ordinal ? row : each,
        );
        this.emit();
      }
    } catch (error) {
      const record = this.state().activity.records?.[id];
      if (epoch === this.epoch && record) {
        record.error = problem(error);
        this.emit();
      }
    }
  }
}
