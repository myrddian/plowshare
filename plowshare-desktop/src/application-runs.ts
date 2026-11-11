import { isList } from 'plowshare-client-ts/binding/values';
import {
  administrativeReply,
  type OrchestrationStatus,
} from 'plowshare-client-ts/operations/administrative-replies';
import {
  listingProjectRuns,
  readingRun,
  runsOf,
  runStatusOf,
  fieldsOf,
} from 'plowshare-client-ts/operations/client-views';
import {
  listingProjectLive,
  liveRunsOf,
  LIVE_STATES,
} from 'plowshare-client-ts/operations/activity';
import type { Run } from 'plowshare-client-ts/operations/inspection';
import type { CheckedAnswer } from 'plowshare-client-ts/operations/response';
import type { Ask } from 'plowshare-client-ts/operations/client-views';
import type { DesktopState } from './shared.ts';

const problem = (error: unknown) =>
  error instanceof Error ? error.message : 'Could not read Application runs.';

/** Live roots first, then newest history, each id once. */
export function mergeApplicationRuns(
  recent: readonly Run[],
  live: readonly Run[],
): Run[] {
  return [
    ...new Map([...recent, ...live].map((run) => [run.id, run])).values(),
  ].sort(
    (a, b) =>
      Number(LIVE_STATES.includes(b.state)) -
        Number(LIVE_STATES.includes(a.state)) ||
      b.createdAt.localeCompare(a.createdAt) ||
      a.id.localeCompare(b.id),
  );
}

/**
 * Application-scoped orchestration list and detail for the hub Runs view.
 * Writes the list into `applicationRuns` and detail into shared `activity.details`.
 */
export class ApplicationRunsClient {
  private epoch = 0;
  private flights = new Map<string, Promise<void>>();
  private state: () => DesktopState;
  private send: (ask: Ask) => Promise<CheckedAnswer>;
  private emit: () => void;
  constructor(
    state: () => DesktopState,
    send: (ask: Ask) => Promise<CheckedAnswer>,
    emit: () => void,
  ) {
    this.state = state;
    this.send = send;
    this.emit = emit;
  }
  reset() {
    this.epoch++;
    this.flights.clear();
    delete this.state().applicationRuns;
  }
  private available(project: string) {
    if (
      !this.state().connected ||
      !this.state().projects.some(
        (row) => row.name === project && row.kind === 'application',
      )
    )
      throw new Error('Choose an available server Application.');
  }
  private read(key: string, work: () => Promise<void>) {
    const prior = this.flights.get(key);
    const next = (prior ?? Promise.resolve())
      .catch(() => {})
      .then(work)
      .finally(() => {
        if (this.flights.get(key) === next) this.flights.delete(key);
      });
    this.flights.set(key, next);
    return next;
  }
  async list(project: string) {
    this.available(project);
    return this.read(`list:${project}`, async () => {
      const epoch = ++this.epoch;
      const prior = this.state().applicationRuns;
      const value = {
        project,
        items: prior?.items ?? [],
        loaded: prior?.loaded ?? false,
        loading: true,
        ...(prior?.selected ? { selected: prior.selected } : {}),
      };
      this.state().applicationRuns = { ...value };
      this.emit();
      try {
        const answers = await Promise.all(
          [listingProjectRuns(project), ...listingProjectLive(project)].map(
            (ask) => this.send(ask),
          ),
        );
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
                'Could not read all run states. The previous Application run list is retained.',
            );
        }
        const recentAnswer = answers[0];
        if (recentAnswer === undefined)
          throw new Error('run-list request returned no answer');
        const recent = runsOf(recentAnswer)!,
          live = liveRunsOf(answers.slice(1))!;
        if (epoch !== this.epoch) return;
        this.available(project);
        const items = mergeApplicationRuns(recent, live);
        const selected =
          value.selected && items.some((row) => row.id === value.selected)
            ? value.selected
            : undefined;
        this.state().applicationRuns = {
          project,
          items,
          loaded: true,
          loading: false,
          limited: answers
            .slice(1)
            .some((answer) => runsOf(answer)!.length >= 200),
          ...(selected ? { selected } : {}),
        };
      } catch (error) {
        if (epoch === this.epoch)
          this.state().applicationRuns = {
            project,
            items: value.items,
            loaded: value.loaded,
            loading: false,
            error: problem(error),
            ...(value.selected ? { selected: value.selected } : {}),
          };
      } finally {
        if (epoch === this.epoch) this.emit();
      }
    });
  }
  async inspect(id: string) {
    const listed = this.state().applicationRuns;
    const known =
      listed?.items.some((run) => run.id === id) ||
      Object.values(this.state().activity.details).some(
        (detail) =>
          detail.value?.run.id === id ||
          detail.value?.children.some((child) => child.id === id) ||
          detail.value?.run.parent === id,
      );
    if (!listed || !known) throw new Error('Choose an available run.');
    if (listed.selected !== id) {
      listed.selected = id;
      this.emit();
    }
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
}
