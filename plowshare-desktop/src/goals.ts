import { errorMessage } from 'plowshare-client-ts/binding/values';
import type { GoalAgentClient } from 'plowshare-client-ts/operations/goal-controls';
import type { GoalStatus } from 'plowshare-client-ts/operations/replies';
import type {
  DesktopState,
  GoalActivity,
  GoalActivityRequest,
} from './shared.ts';

/** Activity owns a single project, page, and selection. Closing or changing the
 * page invalidates outstanding reads while the authenticated client retains receipts. */
export class GoalActivityClient {
  private project: string | undefined;
  private cursors: (string | undefined)[] = [undefined];
  private reading: AbortController | undefined;

  private readonly state: () => DesktopState;
  private readonly goals: GoalAgentClient;
  private readonly emit: () => void;

  constructor(
    state: () => DesktopState,
    goals: GoalAgentClient,
    emit: () => void,
  ) {
    this.state = state;
    this.goals = goals;
    this.emit = emit;
  }

  close(): void {
    this.reading?.abort();
    this.reading = undefined;
    this.cursors = [undefined];
    delete this.state().activity.goals;
  }

  resetAccount(): void {
    this.close();
    this.project = undefined;
    this.goals.reset();
  }

  private hasProject(project: string): boolean {
    return this.state().projects.some((entry) => entry.name === project);
  }

  private available(): boolean {
    return this.state().connected && this.state().mode === 'live';
  }

  private begin(panel: GoalActivity): AbortController {
    this.reading?.abort();
    const read = new AbortController();
    this.reading = read;
    this.state().activity.goals = panel;
    this.emit();
    return read;
  }

  async refresh(): Promise<void> {
    if (!this.available()) return;
    const project = this.project;
    if (!project || !this.hasProject(project)) {
      this.project = undefined;
      this.close();
      this.emit();
      return;
    }
    await this.load(this.cursors, this.state().activity.goals?.selected?.goal);
  }

  private async load(
    cursors: (string | undefined)[],
    selected?: string,
  ): Promise<void> {
    const project = this.project;
    if (!project) return;
    const retained = this.state().activity.goals;
    const read = this.begin({
      project,
      previous: retained?.previous ?? false,
      ...(retained?.page === undefined ? {} : { page: retained.page }),
      loading: true,
    });
    try {
      const page = await this.goals.list(project, cursors.at(-1), read.signal);
      if (read.signal.aborted) return;
      this.cursors = cursors;
      const panel: GoalActivity = {
        project,
        page,
        previous: cursors.length > 1,
      };
      if (selected && page.goals.some((row) => row.goal === selected))
        await this.inspect(panel, selected);
      else {
        this.state().activity.goals = panel;
        this.emit();
      }
    } catch (error) {
      if (read.signal.aborted) return;
      this.state().activity.goals = {
        project,
        previous: retained?.previous ?? false,
        ...(retained?.page === undefined ? {} : { page: retained.page }),
        error: errorMessage(error),
      };
      this.emit();
    }
  }

  private async inspect(
    panel: GoalActivity,
    goal: string,
    failure?: string,
  ): Promise<void> {
    const project = panel.project;
    if (
      !project ||
      !this.hasProject(project) ||
      !panel.page?.goals.some((row) => row.goal === goal)
    )
      throw new Error('Choose a goal on the current project page.');
    const { page, previous } = panel;
    const read = this.begin({ project, page, previous, loading: true });
    const result: GoalActivity = { project, page, previous };
    try {
      const status = await this.goals.inspect(project, goal, read.signal);
      // Status rechecks disclosure for this row and supersedes the list preview.
      result.page = {
        ...page,
        goals: page.goals.map((row) => (row.goal === goal ? status : row)),
      };
      result.selected = status;
      if (failure) result.error = failure;
      const pending = this.goals.pending(status);
      if (pending !== undefined) result.pending = pending;
    } catch (error) {
      // A refused status can mean the Goal is no longer discoverable.
      result.page = {
        ...page,
        goals: page.goals.filter((row) => row.goal !== goal),
      };
      result.error = errorMessage(error);
    }
    if (read.signal.aborted) return;
    this.state().activity.goals = result;
    this.emit();
  }

  target(goal: string, version: number): GoalStatus {
    const status = this.state().activity.goals?.selected;
    if (
      !this.available() ||
      !status?.readable ||
      status.goal !== goal ||
      status.version !== version ||
      !this.hasProject(status.project)
    )
      throw new Error('Choose the current Goal Agent and refresh its status.');
    return status;
  }

  async handle(
    request: Exclude<GoalActivityRequest, { action: 'goal-trajectory' }>,
  ): Promise<void> {
    if (!this.available()) throw new Error('Connect to the server first.');
    if (request.action === 'goal-project') {
      if (!this.hasProject(request.project))
        throw new Error('Choose an available project.');
      this.close();
      this.project = request.project;
      await this.load(this.cursors);
      return;
    }
    const panel = this.state().activity.goals;
    if (!panel?.project || !this.hasProject(panel.project) || panel.loading)
      throw new Error('Wait for the current project page.');
    switch (request.action) {
      case 'goal-page': {
        if (request.direction === 'next' && panel.page?.next) {
          await this.load([...this.cursors, panel.page.next]);
        } else if (
          request.direction === 'previous' &&
          this.cursors.length > 1
        ) {
          await this.load(this.cursors.slice(0, -1));
        }
        break;
      }
      case 'goal-inspect':
        await this.inspect(panel, request.goal);
        break;
      case 'goal-clear': {
        this.goals.forget(this.target(request.goal, request.version));
        delete panel.pending;
        this.emit();
        break;
      }
      case 'goal-control': {
        const status = this.target(request.goal, request.version);
        const change = this.goals.change(
          status,
          request.control,
          request.limits,
        );
        const pending = this.goals.pending(status);
        if (pending !== undefined) panel.pending = pending;
        this.emit();
        let failure: string | undefined;
        try {
          await change;
        } catch (error) {
          failure = errorMessage(error);
        }
        // The control may have completed after the window moved to another goal.
        if (this.state().activity.goals !== panel) return;
        await this.inspect(panel, request.goal, failure);
        break;
      }
    }
  }
}
