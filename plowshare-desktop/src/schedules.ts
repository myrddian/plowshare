import {
  definitionFromProposal,
  type ScheduleDefinition,
  type ScheduleFile,
} from 'plowshare-client-ts/operations/schedule-files';
import {
  request,
  resultOf,
  type Request as WsRequest,
} from 'plowshare-client-ts/operations/direct';
import type { CheckedAnswer as Outcome } from 'plowshare-client-ts/operations/response';
import type {
  ScheduleProposal,
  ScheduleRecord,
  TriggerRecord,
  FiringRecord,
} from 'plowshare-client-ts/operations/administrative-replies';
import type { DesktopState } from './shared.ts';

const problem = (error: unknown) =>
  error instanceof Error ? error.message : 'Scheduled work is unavailable.';
export const scheduleIdentity = (
  value: ScheduleRecord | TriggerRecord | ScheduleProposal,
) => JSON.stringify(value);
export class ScheduleClient {
  private epoch = 0;
  private reading = 0;
  private previewing = 0;
  private scheduleReading = 0;
  private fileReading = 0;
  private scheduleCursors: (string | undefined)[] = [undefined];
  private fileCursors: (string | undefined)[] = [undefined];
  private readonly pageSize = 50;
  private busy = false;
  private state: () => DesktopState;
  private send: (ask: WsRequest) => Promise<Outcome>;
  private emit: () => void;
  constructor(
    state: ScheduleClient['state'],
    send: ScheduleClient['send'],
    emit: () => void,
  ) {
    this.state = state;
    this.send = send;
    this.emit = emit;
  }
  private get value() {
    return (this.state().activity.schedules ??= {
      schedules: [],
      triggers: [],
      firings: [],
    });
  }
  reset() {
    this.epoch++;
    this.reading++;
    this.previewing++;
    this.scheduleReading++;
    this.fileReading++;
    this.scheduleCursors = [undefined];
    this.fileCursors = [undefined];
    this.busy = false;
    const value = this.value;
    value.schedulePage = { number: 1, more: false };
    value.filePage = { number: 1, more: false };
    value.loading = false;
    delete value.panels;
    value.previewing = false;
    value.busy = false;
    delete value.proposal;
  }
  private live() {
    if (!this.state().connected || this.state().mode !== 'live')
      throw new Error('Connect before managing scheduled work.');
  }
  private async checked(ask: WsRequest) {
    const answer = await this.send(ask),
      result = resultOf(ask, answer);
    if (result.kind === 'refused')
      throw new Error(answer.said ?? `The server refused ${ask.type}.`);
    if (result.kind === 'invalid-response')
      throw new Error(
        `The server returned incomplete ${ask.type} information.`,
      );
    return answer;
  }
  private identity() {
    return JSON.stringify([this.state().base, this.state().handle]);
  }
  private current(epoch: number, identity: string) {
    return (
      epoch === this.epoch &&
      identity === this.identity() &&
      this.state().connected &&
      this.state().mode === 'live'
    );
  }
  /** Validate bounded, unique keys before replacing a visible page. Ordering belongs
   * to PostgreSQL's collation; client string comparison cannot establish SQL order. */
  private pageRows<T>(
    rows: readonly T[],
    key: (row: T) => string,
    after?: string,
  ): readonly T[] {
    if (rows.length > this.pageSize + 1)
      throw new Error('The server returned an oversized schedule page.');
    const seen = new Set<string>();
    for (const row of rows) {
      const name = key(row);
      if (
        typeof name !== 'string' ||
        !name.trim() ||
        name.length > 512 ||
        name === after ||
        seen.has(name)
      )
        throw new Error(
          'The server returned an invalid or repeated schedule page key.',
        );
      seen.add(name);
    }
    return rows;
  }
  /** Refresh starts both lists from their first page; failed reads retain all shown rows. */
  async refresh() {
    this.live();
    const epoch = this.epoch,
      identity = this.identity(),
      revision = ++this.reading,
      scheduleRevision = ++this.scheduleReading,
      fileRevision = ++this.fileReading,
      value = this.value;
    value.loading = true;
    if (value.schedulePage) value.schedulePage.loading = false;
    if (value.filePage) value.filePage.loading = false;
    this.emit();
    const panels: Record<
      'schedules' | 'files' | 'triggers' | 'firings',
      { loading: boolean; error?: string }
    > = {
      schedules: { loading: true },
      files: { loading: true },
      triggers: { loading: true },
      firings: { loading: true },
    };
    value.panels = panels;
    value.schedulePage = {
      ...(value.schedulePage ?? { number: 1, more: false }),
      loading: true,
    };
    value.filePage = {
      ...(value.filePage ?? { number: 1, more: false }),
      loading: true,
    };
    this.emit();
    // Register all outcomes before waiting. Each collection publishes independently;
    // one refused/slow source must not hide another collection's confirmed snapshot.
    await Promise.all(
      (
        [
          ['schedules', request('schedule.list', { limit: this.pageSize + 1 })],
          ['triggers', request('trigger.list', {})],
          ['firings', request('firing.list', { limit: 100 })],
          ['files', request('schedule.files', { limit: this.pageSize + 1 })],
        ] as const
      ).map(async ([section, ask]) => {
        const current = () =>
          this.current(epoch, identity) &&
          revision === this.reading &&
          (section === 'schedules'
            ? scheduleRevision === this.scheduleReading
            : section === 'files'
              ? fileRevision === this.fileReading
              : true);
        try {
          const answer = await this.checked(ask);
          if (!current()) return;
          if (section === 'schedules') {
            const rows = this.pageRows(
              answer.payload as ScheduleRecord[],
              (row) => row.name,
            );
            value.schedules = rows.slice(0, this.pageSize);
            value.schedulePage = {
              number: 1,
              more: rows.length > this.pageSize,
            };
            this.scheduleCursors = [undefined];
          } else if (section === 'files') {
            const rows = this.pageRows(
              answer.payload as ScheduleFile[],
              (row) => row.internalName,
            );
            value.files = rows.slice(0, this.pageSize);
            value.filePage = { number: 1, more: rows.length > this.pageSize };
            this.fileCursors = [undefined];
          } else if (section === 'triggers')
            value.triggers = answer.payload as TriggerRecord[];
          else value.firings = answer.payload as FiringRecord[];
        } catch (error) {
          if (current()) {
            panels[section].error = problem(error);
            const page =
              section === 'schedules'
                ? value.schedulePage
                : section === 'files'
                  ? value.filePage
                  : undefined;
            if (page) page.error = problem(error);
          }
        } finally {
          if (current()) {
            panels[section].loading = false;
            const page =
              section === 'schedules'
                ? value.schedulePage
                : section === 'files'
                  ? value.filePage
                  : undefined;
            if (page) page.loading = false;
            value.loading = Object.values(panels).some(
              (panel) => panel.loading,
            );
            this.emit();
          }
        }
      }),
    );
  }

  /** Each collection has its own cursor history and response generation. The extra row is
   * lookahead only; the next cursor always comes from the last displayed, authorized DTO. */
  async page(
    section: 'schedules' | 'files',
    direction: 'next' | 'previous' | 'first',
  ) {
    this.live();
    if (
      !['schedules', 'files'].includes(section) ||
      !['next', 'previous', 'first'].includes(direction)
    )
      throw new Error('Choose a schedule collection and page direction.');
    const value = this.value;
    if (this.busy)
      throw new Error('Wait for the scheduled-work request to finish.');
    const schedule = section === 'schedules';
    const page = (schedule ? value.schedulePage : value.filePage) ?? {
      number: 1,
      more: false,
    };
    if (page.loading) return;
    if (
      (direction === 'next' && !page.more) ||
      (direction === 'previous' && page.number <= 1)
    )
      return;
    const cursors = schedule ? this.scheduleCursors : this.fileCursors;
    const index =
      direction === 'first'
        ? 0
        : direction === 'next'
          ? page.number
          : page.number - 2;
    const after =
      direction === 'next'
        ? schedule
          ? value.schedules.at(-1)?.name
          : value.files?.at(-1)?.internalName
        : cursors[index];
    if (direction === 'next' && after === undefined) return;
    const epoch = this.epoch,
      identity = this.identity();
    const revision = schedule ? ++this.scheduleReading : ++this.fileReading;
    const paging = { ...page, loading: true };
    if (schedule) value.schedulePage = paging;
    else value.filePage = paging;
    this.emit();
    try {
      const answer = await this.checked(
        schedule
          ? request('schedule.list', {
              limit: this.pageSize + 1,
              ...(after === undefined ? {} : { after }),
            })
          : request('schedule.files', {
              limit: this.pageSize + 1,
              ...(after === undefined ? {} : { after }),
            }),
      );
      if (
        !this.current(epoch, identity) ||
        revision !== (schedule ? this.scheduleReading : this.fileReading)
      )
        return;
      const rows = schedule
        ? this.pageRows(
            answer.payload as ScheduleRecord[],
            (row) => row.name,
            after,
          )
        : this.pageRows(
            answer.payload as ScheduleFile[],
            (row) => row.internalName,
            after,
          );
      if (schedule)
        value.schedules = (rows as readonly ScheduleRecord[]).slice(
          0,
          this.pageSize,
        );
      else
        value.files = (rows as readonly ScheduleFile[]).slice(0, this.pageSize);
      const next = { number: index + 1, more: rows.length > this.pageSize };
      if (schedule) value.schedulePage = next;
      else value.filePage = next;
      cursors[index] = after;
      cursors.length = index + 1;
    } catch (error) {
      if (
        this.current(epoch, identity) &&
        revision === (schedule ? this.scheduleReading : this.fileReading)
      ) {
        paging.error = problem(error);
      }
    } finally {
      if (
        this.current(epoch, identity) &&
        revision === (schedule ? this.scheduleReading : this.fileReading)
      ) {
        paging.loading = false;
        this.emit();
      }
    }
  }
  private pagePayload(section: 'schedules' | 'files') {
    const page =
      section === 'schedules' ? this.value.schedulePage : this.value.filePage;
    const cursors =
      section === 'schedules' ? this.scheduleCursors : this.fileCursors;
    const after = cursors[(page?.number ?? 1) - 1];
    return {
      limit: this.pageSize + 1,
      ...(after === undefined ? {} : { after }),
    };
  }
  async preview(
    text: string,
    zone: string,
    project?: string,
    conversation?: string,
  ) {
    this.live();
    if (this.busy)
      throw new Error(
        'Wait for the scheduled-work change before reading another proposal.',
      );
    if (
      typeof text !== 'string' ||
      !text.trim() ||
      text.length > 16_000 ||
      typeof zone !== 'string' ||
      !zone.trim() ||
      zone.length > 128
    )
      throw new Error('Enter a schedule description and time zone.');
    if (
      project !== undefined &&
      !this.state().projects.some((row) => row.name === project)
    )
      throw new Error('Choose an available project.');
    if (
      conversation !== undefined &&
      !this.state().conversations.some((row) => row.id === conversation)
    )
      throw new Error('Choose an available conversation.');
    if (project && conversation)
      throw new Error('Choose a project or a conversation destination.');
    const epoch = this.epoch,
      revision = ++this.previewing,
      value = this.value;
    value.previewing = true;
    delete value.proposal;
    delete value.previewError;
    this.emit();
    try {
      const answer = await this.checked(
        request('schedule.read', {
          text,
          zone,
          ...(project ? { project } : {}),
          ...(conversation ? { conversation } : {}),
        }),
      );
      if (epoch !== this.epoch || revision !== this.previewing) return;
      const proposal = answer.payload as ScheduleProposal;
      if (
        proposal.project !== (project ?? null) ||
        proposal.conversation !== (conversation ?? null)
      )
        throw new Error(
          'The proposal names a different destination. Read a new proposal for the intended scope.',
        );
      const sourceProject =
        project ??
        this.state().conversations.find((row) => row.id === conversation)
          ?.project;
      if (sourceProject === undefined) delete value.sourceProject;
      else value.sourceProject = sourceProject;
      value.proposal = proposal;
    } catch (error) {
      if (epoch === this.epoch && revision === this.previewing) {
        value.previewError = problem(error);
        throw error;
      }
    } finally {
      if (epoch === this.epoch && revision === this.previewing) {
        value.previewing = false;
        this.emit();
      }
    }
  }
  async save(
    identity: string,
    definition?: ScheduleDefinition,
    source: 'server' | 'workspace' = 'server',
    project?: string,
  ) {
    this.live();
    const proposal = this.value.proposal;
    if (
      !proposal ||
      typeof identity !== 'string' ||
      scheduleIdentity(proposal) !== identity
    )
      throw new Error('Review the current proposal before saving.');
    await this.saveFile(
      proposal.names.schedule,
      definition ?? definitionFromProposal(proposal),
      source,
      project ?? this.value.sourceProject,
      false,
    );
    delete this.value.proposal;
  }
  async saveFile(
    name: string,
    definition: ScheduleDefinition,
    source: 'server' | 'workspace',
    project?: string,
    overwrite = false,
    identity?: string,
  ) {
    await this.mutate(async (guard) => {
      guard();
      if (overwrite) {
        const shown = this.value.files?.find(
          (file) =>
            file.name === name &&
            file.source === source &&
            file.project === (project ?? null),
        );
        if (!shown || identity !== JSON.stringify(shown))
          throw new Error('Choose a displayed schedule file before editing.');
        const payload = this.pagePayload('files');
        const answer = await this.checked(request('schedule.files', payload));
        const files = this.pageRows(
          answer.payload as ScheduleFile[],
          (row) => row.internalName,
          payload.after,
        );
        guard();
        const fresh = files.find(
          (file) => file.internalName === shown.internalName,
        );
        if (!fresh || JSON.stringify(fresh) !== identity)
          throw new Error(
            'This schedule file changed elsewhere. Refresh before saving.',
          );
      }
      const answer = await this.checked(
        request('schedule.save', {
          name,
          project: project ?? null,
          source,
          definition,
          overwrite,
        }),
      );
      const file = answer.payload as ScheduleFile;
      if (file.status !== 'active')
        throw new Error(
          file.error ?? 'The file was saved but its schedule was refused.',
        );
      return `Saved ${file.path}. The server is monitoring this folder.`;
    });
  }
  async sync(source: 'server' | 'workspace', project?: string) {
    await this.mutate(async (guard) => {
      guard();
      await this.checked(
        request('schedule.sync', { source, project: project ?? null }),
      );
      return 'Schedule folder registered and reconciled.';
    });
  }
  async change(
    kind: 'schedule' | 'trigger',
    name: string,
    identity: string,
    paused?: boolean,
  ) {
    this.live();
    if (kind !== 'schedule' && kind !== 'trigger')
      throw new Error('Choose a schedule or trigger.');
    const shown = (
      kind === 'schedule' ? this.value.schedules : this.value.triggers
    ).find((row) => row.name === name);
    if (
      !shown ||
      scheduleIdentity(shown) !== identity ||
      (paused !== undefined && typeof paused !== 'boolean')
    )
      throw new Error('Choose a displayed schedule or trigger.');
    await this.mutate(async (guard) => {
      const payload = this.pagePayload('schedules');
      const answer = await this.checked(
        kind === 'schedule'
          ? request('schedule.list', payload)
          : request('trigger.list', {}),
      );
      guard();
      const rows =
        kind === 'schedule'
          ? this.pageRows(
              answer.payload as ScheduleRecord[],
              (row) => row.name,
              payload.after,
            )
          : (answer.payload as TriggerRecord[]);
      const current = rows.find((row) => row.name === name);
      if (!current || scheduleIdentity(current) !== identity)
        throw new Error(
          'This item changed elsewhere. Refresh before making a decision.',
        );
      await this.checked(
        kind === 'schedule'
          ? paused === undefined
            ? request('schedule.forget', { schedule: name })
            : request('schedule.pause', { schedule: name, paused })
          : paused === undefined
            ? request('trigger.forget', { trigger: name })
            : request('trigger.pause', { trigger: name, paused }),
      );
      return `${kind === 'schedule' ? 'Schedule' : 'Trigger'} ${paused === undefined ? 'removed' : paused ? 'paused' : 'resumed'}.`;
    });
  }
  async fire(trigger: string, identity: string) {
    this.live();
    const shown = this.value.triggers.find((row) => row.name === trigger);
    if (!shown || scheduleIdentity(shown) !== identity)
      throw new Error('Choose a displayed trigger.');
    await this.mutate(async (guard) => {
      const current = (
        (await this.checked(request('trigger.list', {})))
          .payload as TriggerRecord[]
      ).find((row) => row.name === trigger);
      guard();
      if (!current || scheduleIdentity(current) !== identity)
        throw new Error(
          'This trigger changed elsewhere. Refresh before firing its event.',
        );
      const answer = await this.checked(
        request('event.fire', { event: current.event }),
      );
      return `Event emitted; ${(answer.payload as FiringRecord[]).length} firings returned.`;
    });
  }
  private async mutate(work: (guard: () => void) => Promise<string>) {
    if (this.busy)
      throw new Error('Another scheduled-work change is being sent.');
    // Mutations perform their own authoritative preflight/identity checks. An
    // unrelated inspection panel does not hold the entire control surface busy.
    const epoch = this.epoch,
      identity = this.identity(),
      value = this.value;
    this.busy = true;
    value.busy = true;
    delete value.error;
    delete value.notice;
    this.emit();
    const guard = () => {
      if (epoch !== this.epoch || identity !== this.identity())
        throw new Error('The connection changed. Refresh before retrying.');
      this.live();
    };
    try {
      guard();
      const notice = await work(guard);
      guard();
      value.notice = notice;
      await this.refresh();
    } catch (error) {
      if (epoch === this.epoch && identity === this.identity())
        value.error = `${problem(error)} The request will not be replayed. Refresh to inspect what was saved.`;
      throw error;
    } finally {
      if (epoch === this.epoch && identity === this.identity()) {
        this.busy = false;
        value.busy = false;
        this.emit();
      }
    }
  }
}
